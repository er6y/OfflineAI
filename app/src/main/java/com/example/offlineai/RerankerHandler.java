package com.example.offlineai;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.offlineai.mnn.MnnInference;
import com.example.offlineai.ConfigManager;

/**
 * MNN Reranker Handler - Unified reranker handler
 * 
 * Key features:
 * - MNN one-stop solution (built-in tokenizer, no ONNX needed)
 * - Singleton pattern for persistent model loading
 * - Thread-safe reranking
 * - Support for Qwen3 and GTE reranker models
 * - Softmax-normalized scores [0.0, 1.0] (no additional normalization needed)
 * 
 * Architecture:
 * RerankerHandler (app) → MnnInference (mnn-jni) → mnn_jni.cpp (native) → MNN reranker.hpp
 * 
 * Thread Model:
 * - Independent from EmbeddingHandler (parallel execution)
 * - Model loaded once and kept resident for multiple calls
 * - Thread-safe for concurrent reranking requests
 */
public class RerankerHandler {
    private static final String TAG = "RerankerHandler";
    
    // ========== Singleton Instance ==========
    private static RerankerHandler sInstance;
    private static final Object sLock = new Object();
    
    // ========== Model State ==========
    private long mNativeHandle = 0;
    private String mCurrentModelPath;
    private String mCurrentModelType;  // "qwen3" or "gte"
    private String mInstruction;  // Current instruction for Qwen3
    private Context mContext;  // For accessing ConfigManager
    
    // ========== Thread Management ==========
    private final AtomicBoolean mIsLoading = new AtomicBoolean(false);
    private final AtomicBoolean mIsInUse = new AtomicBoolean(false);
    private final AtomicBoolean mShouldStop = new AtomicBoolean(false);
    
    // ========== Progress Callback ==========
    public interface ProgressCallback {
        void onProgress(int current, int total);
    }
    
    // ========== Score Callback (for real-time score output) ==========
    public interface ScoreCallback {
        void onScore(int index, float score, String text);
    }
    
    private ProgressCallback mProgressCallback;
    private ScoreCallback mScoreCallback;
    
    /**
     * Rerank result class
     * Contains document text, relevance score, and original index
     */
    public static class RerankResult implements Comparable<RerankResult> {
        public final String text;
        public final float score;  // Softmax-normalized score [0.0, 1.0]
        public final int originalIndex;
        
        public RerankResult(String text, float score, int originalIndex) {
            this.text = text;
            this.score = score;
            this.originalIndex = originalIndex;
        }
        
        @Override
        public int compareTo(RerankResult other) {
            // Sort by score descending (highest relevance first)
            return Float.compare(other.score, this.score);
        }
        
        @Override
        public String toString() {
            return String.format("RerankResult{index=%d, score=%.4f, text='%s...'}",
                originalIndex, score, text.substring(0, Math.min(50, text.length())));
        }
    }
    
    // ========== Constructor ==========
    private RerankerHandler() {}
    
    /**
     * Get singleton instance
     */
    public static RerankerHandler getInstance(Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new RerankerHandler();
                    sInstance.mContext = context.getApplicationContext();
                }
            }
        }
        return sInstance;
    }
    
    // ========== Model Loading ==========
    
    /**
     * Load reranker model - MNN one-stop solution
     * MNN will automatically detect reranker type from config.json
     * Note: Reranker always uses Memory_Low mode (hardcoded for efficiency)
     * @param modelPath Path to model directory or config.json
     * @return true if loaded successfully
     */
    public synchronized boolean loadModel(String modelPath) {
        LogManager.logI(TAG, "[LOCK] Acquired loadModel lock - thread=" + Thread.currentThread().getName());
        
        // CRITICAL: Check thread interruption first
        if (Thread.currentThread().isInterrupted()) {
            LogManager.logI(TAG, "Reranker load interrupted before start, aborting");
            Thread.interrupted(); // Clear interrupt flag
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (interrupted before start) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        LogManager.logI(TAG, "Loading MNN reranker model: " + modelPath + " (memory=low, hardcoded)");
        
        // Check if already loaded
        if (mCurrentModelPath != null && mCurrentModelPath.equals(modelPath)) {
            if (MnnInference.isRerankerValid(mNativeHandle)) {
                LogManager.logD(TAG, "Model already loaded and valid");
                LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (already loaded) - thread=" + Thread.currentThread().getName());
                return true;
            }
        }
        
        // Release old model (only if not interrupted)
        if (mNativeHandle != 0) {
            LogManager.logI(TAG, "Releasing old reranker handle=" + mNativeHandle);
            try {
                MnnInference.releaseReranker(mNativeHandle);
                LogManager.logI(TAG, "Old reranker handle released successfully");
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to release old reranker handle: " + e.getMessage(), e);
            }
            mNativeHandle = 0;
        }
        
        // Find config.json
        File configFile = findConfigFile(modelPath);
        if (configFile == null) {
            LogManager.logE(TAG, "config.json not found for: " + modelPath);
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (config not found) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        LogManager.logI(TAG, "Using config: " + configFile.getAbsolutePath());
        
        // Build runtime config JSON string (hardcoded: memory=low, power=high)
        String runtimeConfig = buildRuntimeConfig();
        
        // Get model directory
        String modelDir = configFile.getParent();
        
        // No Thread.isInterrupted() check - using graceful stop pattern (mShouldStop flag only)
        
        // Create reranker session (统一模式：modelDir + runtimeConfig)
        // NOTE: This is a LONG blocking call (can take 60+ seconds)
        // If user clicks stop during this call, mShouldStop will be set, and rerank() will check it
        LogManager.logD(TAG, "[NATIVE] Calling createRerankerWithConfig...");
        try {
            mNativeHandle = MnnInference.createRerankerWithConfig(modelDir, runtimeConfig);
            LogManager.logD(TAG, "[NATIVE] createRerankerWithConfig returned, handle=" + mNativeHandle);
        } catch (Exception e) {
            LogManager.logE(TAG, "Exception during reranker creation: " + e.getMessage(), e);
            mNativeHandle = 0;
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (exception) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        // Model loaded successfully - if mShouldStop is set, rerank() will handle it gracefully
        
        if (mNativeHandle == 0) {
            LogManager.logE(TAG, "Failed to create MNN reranker");
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (handle is 0) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        mCurrentModelPath = modelPath;
        // mCurrentModelType is no longer needed - MNN handles it
        
        LogManager.logI(TAG, "Reranker model loaded successfully (type auto-detected by MNN)");
        LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (success) - thread=" + Thread.currentThread().getName());
        return true;
    }
    
    // ========== Reranking Operations ==========
    
    /**
     * Set instruction for reranker (optional, for Qwen3 model)
     * @param instruction Instruction string describing the reranking task
     */
    public void setInstruction(String instruction) {
        if (mNativeHandle == 0) {
            LogManager.logW(TAG, "Reranker not loaded, cannot set instruction");
            return;
        }
        
        mInstruction = instruction;
        MnnInference.setRerankerInstruction(mNativeHandle, instruction);
        LogManager.logD(TAG, "Instruction set: " + instruction);
    }
    
    /**
     * Rerank documents based on query relevance
     * CRITICAL: This method is synchronized to prevent concurrent MNN session access
     * which can cause SIGSEGV crashes when user clicks stop then send rapidly
     * 
     * @param query Query text
     * @param documents List of documents to rerank
     * @param topK Return top K results
     * @return List of reranked results sorted by relevance (highest first)
     * 
     * Note: Scores are softmax-normalized [0.0, 1.0] by MNN reranker
     * - score ≈ 1.0: highly relevant
     * - score ≈ 0.5: moderately relevant
     * - score ≈ 0.0: not relevant
     */
    public synchronized List<RerankResult> rerank(String query, List<String> documents, int topK) {
        // CRITICAL: Check thread interruption first (from Future.cancel(true))
        if (Thread.currentThread().isInterrupted()) {
            LogManager.logI(TAG, "Reranker thread interrupted, clearing interrupt flag and aborting");
            Thread.interrupted(); // Clear interrupt flag
            return convertToOriginalOrder(documents);
        }
        
        
        if (mNativeHandle == 0) {
            LogManager.logE(TAG, "Reranker not loaded");
            return convertToOriginalOrder(documents);
        }
        
        if (documents == null || documents.isEmpty()) {
            LogManager.logW(TAG, "Document list is empty");
            return new ArrayList<>();
        }
        
        if (query == null || query.trim().isEmpty()) {
            LogManager.logW(TAG, "Query is empty, returning original order");
            return convertToOriginalOrder(documents);
        }
        
        // Check stop flag before starting
        if (mShouldStop.get()) {
            LogManager.logI(TAG, "Reranking stopped by user (before start)");
            return convertToOriginalOrder(documents);
        }
        
        mIsInUse.set(true);
        try {
            long startTime = System.currentTimeMillis();
            
            LogManager.logD(TAG, "Computing scores for " + documents.size() + " documents (one by one)");
            
            // Process documents one by one for better performance and interruptibility
            List<RerankResult> results = new ArrayList<>();
            
            for (int i = 0; i < documents.size(); i++) {
                // Check stop flag before each document
                if (mShouldStop.get()) {
                    LogManager.logI(TAG, "Reranking stopped by user (after " + i + "/" + documents.size() + " documents)");
                    // Return results processed so far with original order for remaining
                    for (int j = i; j < documents.size(); j++) {
                        results.add(new RerankResult(documents.get(j), 0.0f, j));
                    }
                    break;
                }
                
                // Compute score for single document
                String[] singleDoc = new String[]{documents.get(i)};
                float[] scores = MnnInference.computeScores(mNativeHandle, query, singleDoc);
                
                if (scores == null || scores.length != 1) {
                    LogManager.logE(TAG, "Invalid score returned for document " + i);
                    results.add(new RerankResult(documents.get(i), 0.0f, i));
                } else {
                    float score = scores[0];
                    results.add(new RerankResult(documents.get(i), score, i));
                    LogManager.logD(TAG, "Document " + (i + 1) + "/" + documents.size() + " scored: " + score);
                    
                    // CRITICAL: Report score immediately after computing (real-time output)
                    if (mScoreCallback != null) {
                        mScoreCallback.onScore(i, score, documents.get(i));
                    }
                }
                
                // Report progress
                if (mProgressCallback != null) {
                    mProgressCallback.onProgress(i + 1, documents.size());
                }
            }
            
            // Sort by score (descending)
            Collections.sort(results);
            
            // Return top K
            int resultSize = Math.min(topK, results.size());
            List<RerankResult> topResults = results.subList(0, resultSize);
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format("Reranking completed in %dms: processed %d documents, returning top %d",
                duration, documents.size(), resultSize));
            
            // Log top 3 scores for debugging
            for (int i = 0; i < Math.min(3, topResults.size()); i++) {
                RerankResult r = topResults.get(i);
                LogManager.logD(TAG, String.format("  Top %d: score=%.4f, index=%d", 
                    i + 1, r.score, r.originalIndex));
            }
            
            return topResults;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Reranking failed: " + e.getMessage(), e);
            return convertToOriginalOrder(documents);
        } finally {
            mIsInUse.set(false);
        }
    }
    
    // ========== Status Queries ==========
    
    /**
     * Check if model is loaded and ready
     */
    public boolean isModelLoaded() {
        return mNativeHandle != 0 && MnnInference.isRerankerValid(mNativeHandle);
    }
    
    /**
     * Check if reranker is currently processing
     */
    public boolean isInUse() {
        return mIsInUse.get();
    }
    
    /**
     * Stop current reranking computation
     * Following LocalLLM stop mechanism design
     */
    public void stopInference() {
        LogManager.logI(TAG, "Stopping reranking computation");
        mShouldStop.set(true);
        
        // Note: Like embedding, reranking is a single native call.
        // The stop flag will be checked before/after the native call.
        // If native is running, we wait for it to complete naturally.
        LogManager.logD(TAG, "Stop signal sent for reranker");
    }
    
    /**
     * Reset stop flag (call before starting new computation)
     */
    public void resetStopFlag() {
        mShouldStop.set(false);
        LogManager.logD(TAG, "[STOP] Reranker resetStopFlag -> false");
    }
    
    /**
     * Set progress callback for reranking
     */
    public void setProgressCallback(ProgressCallback callback) {
        mProgressCallback = callback;
    }
    
    /**
     * Set score callback for real-time score output
     */
    public void setScoreCallback(ScoreCallback callback) {
        mScoreCallback = callback;
    }
    
    /**
     * Check if reranker is currently loading
     */
    public boolean isLoading() {
        return mIsLoading.get();
    }
    
    /**
     * Check if should stop
     */
    public boolean shouldStop() {
        return mShouldStop.get();
    }
    
    /**
     * Get current model type
     */
    public String getModelType() {
        return mCurrentModelType;
    }
    
    /**
     * Get current model path
     */
    public String getCurrentModelPath() {
        return mCurrentModelPath;
    }
    
    /**
     * Get current instruction
     */
    public String getInstruction() {
        return mInstruction;
    }
    
    // ========== Resource Management ==========
    
    /**
     * Release current model
     */
    public synchronized void releaseModel() {
        if (mNativeHandle != 0) {
            LogManager.logI(TAG, "Releasing reranker model: " + mCurrentModelType);
            MnnInference.releaseReranker(mNativeHandle);
            mNativeHandle = 0;
            mCurrentModelPath = null;
            mCurrentModelType = null;
            mInstruction = null;
        }
    }
    
    /**
     * Close handler (for compatibility)
     */
    public void close() {
        releaseModel();
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Find config.json file
     */
    private File findConfigFile(String modelPath) {
        File file = new File(modelPath);
        
        // If modelPath is already config.json
        if (file.isFile() && file.getName().equals("config.json")) {
            return file;
        }
        
        // If modelPath is a directory, look for config.json inside
        if (file.isDirectory()) {
            File configFile = new File(file, "config.json");
            if (configFile.exists()) {
                return configFile;
            }
        }
        
        return null;
    }
    
    /**
     * Build runtime config JSON string (统一模式 - 使用 ConfigBuilder)
     * Hardcoded for reranker: memory=low, power=high, precision=low, thread_num from settings
     */
    private String buildRuntimeConfig() {
        // Get thread count from settings
        int threads = ConfigManager.getThreads(mContext);
        
        // Use ConfigBuilder for type safety and consistency with LLM
        String configJson = new MnnInference.ConfigBuilder()
            .memory("low")
            .power("high")
            .precision("low")
            .threadNum(threads)
            .build();
        
        LogManager.logD(TAG, "Built runtime config: memory=low, power=high, precision=low, threads=" + threads);
        
        return configJson;
    }
    
    /**
     * Convert documents to original order with default scores
     */
    private List<RerankResult> convertToOriginalOrder(List<String> documents) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            // Use 0.5 as default score (neutral relevance)
            results.add(new RerankResult(documents.get(i), 0.5f, i));
        }
        return results;
    }
}
