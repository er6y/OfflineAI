package com.example.offlineai;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

import com.offlineai.mnn.MnnInference;

/**
 * MNN Embedding Handler - Unified embedding model handler
 * Combines functionality of EmbeddingModelHandler + EmbeddingModelManager + EmbeddingModelUtils
 * 
 * Key features:
 * - MNN one-stop solution (built-in tokenizer, no ONNX/PyTorch needed)
 * - Singleton pattern for model management
 * - Thread-safe embedding computation
 * - Persistent model loading for continuous reuse
 * - Dynamic memory mode switching (low/high)
 * - Automatic model unloading on mode conflict
 * 
 * Architecture:
 * EmbeddingHandler (app) → MnnInference (mnn-jni) → mnn_jni.cpp (native)
 */
public class EmbeddingHandler {
    private static final String TAG = "EmbeddingHandler";
    
    // ========== Memory Mode Enum ==========
    public enum MemoryMode {
        LOW("low"),       // Runtime dequantization, lowest memory, slowest
        NORMAL("normal"), // Balanced memory usage, default for batch building
        HIGH("high");     // Pre-dequantization, highest memory, fastest
        
        private final String value;
        
        MemoryMode(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    // ========== Singleton Instance ==========
    private static EmbeddingHandler sInstance;
    private static final Object sLock = new Object();
    
    // ========== Model Management ==========
    private long mNativeHandle = 0;
    private String mCurrentModelPath;
    private String mCurrentModelName;
    private int mEmbeddingDimension = 0;
    private MemoryMode mCurrentMemoryMode = null;
    private Context mContext;  // For accessing ConfigManager
    
    // ========== Thread Management ==========
    private final ExecutorService mExecutor;
    private final AtomicBoolean mIsLoading = new AtomicBoolean(false);
    private final AtomicBoolean mIsInUse = new AtomicBoolean(false);
    private final AtomicBoolean mShouldStop = new AtomicBoolean(false);
    
    // ========== Constructor ==========
    private EmbeddingHandler() {
        mExecutor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Get singleton instance
     */
    public static EmbeddingHandler getInstance(Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new EmbeddingHandler();
                    sInstance.mContext = context.getApplicationContext();
                }
            }
        }
        return sInstance;
    }
    
    // ========== Model Loading (Manager功能) ==========
    
    /**
     * Load embedding model with default memory mode (LOW)
     * @param modelPath Path to model directory or config.json
     * @return true if loaded successfully
     */
    public synchronized boolean loadModel(String modelPath) {
        return loadModel(modelPath, MemoryMode.LOW);
    }
    
    /**
     * Load embedding model with specified memory mode
     * @param modelPath Path to model directory or config.json
     * @param memoryMode Memory mode (LOW for RAG query, HIGH for batch building)
     * @return true if loaded successfully
     */
    public synchronized boolean loadModel(String modelPath, MemoryMode memoryMode) {
        LogManager.logI(TAG, "[LOCK] Acquired loadModel lock - thread=" + Thread.currentThread().getName());
        
        // CRITICAL: Check thread interruption first
        if (Thread.currentThread().isInterrupted()) {
            LogManager.logI(TAG, "Embedding load interrupted before start, aborting");
            Thread.interrupted(); // Clear interrupt flag
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (interrupted before start) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        LogManager.logI(TAG, "Loading MNN embedding model: " + modelPath + ", memory mode: " + memoryMode.getValue());
        
        // Check if already loaded with same mode
        if (mCurrentModelPath != null && mCurrentModelPath.equals(modelPath) && 
            mCurrentMemoryMode == memoryMode) {
            if (MnnInference.isEmbeddingValid(mNativeHandle)) {
                LogManager.logD(TAG, "Model already loaded with same memory mode");
                LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (already loaded) - thread=" + Thread.currentThread().getName());
                return true;
            }
        }
        
        // If mode changed, need to reload (like LocalLLM stops inference first)
        if (mCurrentModelPath != null && mCurrentModelPath.equals(modelPath) && 
            mCurrentMemoryMode != memoryMode) {
            LogManager.logI(TAG, "Memory mode changed from " + mCurrentMemoryMode + " to " + memoryMode + ", reloading model");
            
            // Stop inference before reloading (like LocalLLM)
            stopInference();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (interrupted during mode change) - thread=" + Thread.currentThread().getName());
                return false;
            }
        }
        
        // Release old model (only if not interrupted)
        if (mNativeHandle != 0) {
            LogManager.logI(TAG, "Releasing old embedding handle=" + mNativeHandle);
            try {
                MnnInference.releaseEmbedding(mNativeHandle);
                LogManager.logI(TAG, "Old embedding handle released successfully");
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to release old embedding handle: " + e.getMessage(), e);
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
        
        // Build runtime config JSON string (memory, power, precision)
        String runtimeConfig = buildRuntimeConfig(memoryMode);
        
        // Get model directory
        String modelDir = configFile.getParent();
        
        // No Thread.isInterrupted() check - using graceful stop pattern (mShouldStop flag only)
        
        // Create embedding session (统一模式：modelDir + runtimeConfig)
        // NOTE: This is a LONG blocking call (can take 10+ seconds)
        LogManager.logD(TAG, "[NATIVE] Calling createEmbeddingWithConfig...");
        try {
            mNativeHandle = MnnInference.createEmbeddingWithConfig(modelDir, runtimeConfig);
            LogManager.logD(TAG, "[NATIVE] createEmbeddingWithConfig returned, handle=" + mNativeHandle);
        } catch (Exception e) {
            LogManager.logE(TAG, "Exception during embedding creation: " + e.getMessage(), e);
            mNativeHandle = 0;
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (exception) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        // Model loaded successfully - if mShouldStop is set, computeEmbedding() will handle it gracefully
        
        if (mNativeHandle == 0) {
            LogManager.logE(TAG, "Failed to create MNN embedding");
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (handle is 0) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        // Get dimension
        mEmbeddingDimension = MnnInference.getEmbeddingDimension(mNativeHandle);
        if (mEmbeddingDimension <= 0) {
            LogManager.logE(TAG, "Invalid embedding dimension: " + mEmbeddingDimension);
            releaseModel();
            LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (invalid dimension) - thread=" + Thread.currentThread().getName());
            return false;
        }
        
        // Extract model name
        mCurrentModelPath = modelPath;
        mCurrentModelName = extractModelName(configFile.getParentFile());
        mCurrentMemoryMode = memoryMode;
        
        // Reset stop flag after successful model loading
        // This is critical: if we called stopInference() during mode change (Line 136),
        // we need to reset the flag so subsequent computeEmbedding() calls can proceed
        mShouldStop.set(false);
        
        LogManager.logI(TAG, "MNN embedding loaded successfully - Model: " + mCurrentModelName + 
            ", Dimension: " + mEmbeddingDimension + ", Memory Mode: " + memoryMode.getValue());
        
        // CRITICAL: Warm-up call to initialize MNN internal state
        // First embedding call can take 2+ minutes due to lazy initialization
        // This prevents timeout on actual first use
        LogManager.logI(TAG, "[WARMUP] Starting warm-up embedding call...");
        long warmupStart = System.currentTimeMillis();
        try {
            float[] warmupResult = MnnInference.computeEmbedding(mNativeHandle, "warm up");
            long warmupTime = System.currentTimeMillis() - warmupStart;
            LogManager.logI(TAG, "[WARMUP] Warm-up completed in " + warmupTime + "ms, result=" + 
                (warmupResult != null ? warmupResult.length : "null"));
        } catch (Exception e) {
            LogManager.logW(TAG, "[WARMUP] Warm-up failed (non-critical): " + e.getMessage());
        }
        
        LogManager.logI(TAG, "[LOCK] Releasing loadModel lock (success) - thread=" + Thread.currentThread().getName());
        return true;
    }
    
    /**
     * Get model instance (Manager功能)
     * @param modelPath Model path (will load if not current)
     * @return this instance
     * @throws Exception if model loading fails
     */
    public synchronized EmbeddingHandler getModel(String modelPath) throws Exception {
        return getModel(modelPath, MemoryMode.LOW);
    }
    
    /**
     * Get model instance with specified memory mode
     * @param modelPath Model path
     * @param memoryMode Memory mode
     * @return this instance
     * @throws Exception if model loading fails
     */
    public synchronized EmbeddingHandler getModel(String modelPath, MemoryMode memoryMode) throws Exception {
        if (mIsInUse.get()) {
            throw new Exception("Model is currently in use");
        }
        
        if (!loadModel(modelPath, memoryMode)) {
            throw new Exception("Failed to load model: " + modelPath + " with mode: " + memoryMode);
        }
        
        return this;
    }
    
    // ========== Embedding Computation (Handler功能) ==========
    
    /**
     * Compute embedding vector for text
     * CRITICAL: This method is synchronized to prevent concurrent MNN session access
     * which can cause SIGSEGV crashes when user clicks stop then send rapidly
     * @param text Input text
     * @return Embedding vector
     * @throws Exception if computation fails
     */
    public synchronized float[] computeEmbedding(String text) throws Exception {
        LogManager.logI(TAG, "[LOCK] >>> Entered synchronized computeEmbedding - Thread: " + Thread.currentThread().getName() + ", ID: " + Thread.currentThread().getId());
        
        // CRITICAL: Check thread interruption first (from Future.cancel(true))
        if (Thread.currentThread().isInterrupted()) {
            LogManager.logI(TAG, "Embedding thread interrupted, clearing interrupt flag and aborting");
            Thread.interrupted(); // Clear interrupt flag
            throw new InterruptedException("Embedding thread was interrupted");
        }
        
        
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Model not loaded");
        }
        
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }
        
        // Check stop flag before starting
        if (mShouldStop.get()) {
            LogManager.logI(TAG, "Embedding computation stopped by user (before start)");
            throw new InterruptedException("Embedding computation stopped by user");
        }
        
        mIsInUse.set(true);
        try {
            long startTime = System.currentTimeMillis();
            
            // Check stop flag before native call (no Thread.isInterrupted - graceful stop)
            if (mShouldStop.get()) {
                LogManager.logI(TAG, "Embedding computation stopped by user (before native call)");
                throw new InterruptedException("Embedding computation stopped by user");
            }
            
            // Compute embedding (this is a blocking call)
            // If stop requested during this, it will complete naturally
            LogManager.logI(TAG, "[EMBED_JAVA] >>> Calling MnnInference.computeEmbedding, handle=" + mNativeHandle + ", textLen=" + text.length());
            LogManager.logI(TAG, "[EMBED_JAVA] >>> Thread: " + Thread.currentThread().getName() + ", ID: " + Thread.currentThread().getId());
            float[] result = MnnInference.computeEmbedding(mNativeHandle, text);
            LogManager.logI(TAG, "[EMBED_JAVA] <<< MnnInference.computeEmbedding returned, result=" + (result != null ? result.length : "null"));
            
            if (result == null) {
                throw new Exception("Failed to compute embedding - native method returned null");
            }
            
            if (result.length != mEmbeddingDimension) {
                LogManager.logW(TAG, "Embedding dimension mismatch: expected " + 
                    mEmbeddingDimension + ", got " + result.length);
            }
            
            // Verify normalization (for debugging, can be disabled in production)
            // Note: Pass context if available, null check is safe
            if (false) { // Disabled for now, can enable with proper context
                float norm = calculateL2Norm(result);
                LogManager.logD(TAG, String.format("Embedding L2 norm: %.6f (should be ≈1.0 for normalized vectors)", norm));
                if (Math.abs(norm - 1.0f) > 0.1f) {
                    LogManager.logW(TAG, "Warning: Embedding vector may not be normalized (norm=" + norm + ")");
                }
            }
            
            // Check stop flag after native call (no Thread.isInterrupted - graceful stop)
            if (mShouldStop.get()) {
                LogManager.logI(TAG, "Embedding computation stopped by user (after native call)");
                throw new InterruptedException("Embedding computation stopped by user");
            }
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, "Embedding computed in " + duration + "ms, " +
                "text length: " + text.length() + ", vector size: " + result.length);
            
            LogManager.logI(TAG, "[LOCK] <<< Exiting synchronized computeEmbedding (success) - Thread: " + Thread.currentThread().getName());
            return result;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[LOCK] <<< Exiting synchronized computeEmbedding (exception) - Thread: " + Thread.currentThread().getName(), e);
            throw e;
        } finally {
            mIsInUse.set(false);
        }
    }
    
    /**
     * Calculate L2 norm of a vector
     */
    private float calculateL2Norm(float[] vector) {
        float sum = 0.0f;
        for (float value : vector) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum);
    }
    
    // ========== Model Information ==========
    
    /**
     * Get embedding dimension
     */
    public int getEmbeddingDimension() {
        return mEmbeddingDimension;
    }
    
    /**
     * Get current model name
     */
    public String getEmbeddingModel() {
        return mCurrentModelName != null ? mCurrentModelName : "Unknown";
    }
    
    /**
     * Get current model path
     */
    public String getCurrentModelPath() {
        return mCurrentModelPath;
    }
    
    /**
     * Check if model is loaded
     */
    public boolean isModelLoaded() {
        return mNativeHandle != 0 && MnnInference.isEmbeddingValid(mNativeHandle);
    }
    
    /**
     * Check if model is busy
     */
    public boolean isModelBusy() {
        return mIsLoading.get() || mIsInUse.get();
    }
    
    /**
     * Stop current embedding computation
     * Following LocalLLM stop mechanism design
     */
    public void stopInference() {
        LogManager.logI(TAG, "Stopping embedding computation");
        mShouldStop.set(true);
        
        // Note: Unlike LLM which has token-by-token callback,
        // embedding is a single native call. The stop flag will be
        // checked before/after the native call in computeEmbedding().
        // If native is running, we wait for it to complete naturally.
        LogManager.logD(TAG, "Stop signal sent for embedding");
    }
    
    /**
     * Reset stop flag (call before starting new computation)
     */
    public void resetStopFlag() {
        mShouldStop.set(false);
        LogManager.logD(TAG, "[STOP] Embedding resetStopFlag -> false");
    }
    
    /**
     * Check if embedding is currently loading
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
     * Get current memory mode
     */
    public MemoryMode getCurrentMemoryMode() {
        return mCurrentMemoryMode;
    }
    
    /**
     * Force unload model (for mode conflict resolution)
     */
    public synchronized void forceUnload() {
        if (mNativeHandle != 0) {
            LogManager.logI(TAG, "Force unloading embedding model due to mode conflict");
            releaseModel();
        }
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Find config.json file
     */
    private File findConfigFile(String path) {
        File f = new File(path);
        
        // Already config.json
        if (f.isFile() && f.getName().equals("config.json")) {
            return f;
        }
        
        // Directory with config.json
        if (f.isDirectory()) {
            File cfg = new File(f, "config.json");
            if (cfg.exists()) return cfg;
        }
        
        // Model file, check parent
        if (f.isFile()) {
            File parent = f.getParentFile();
            if (parent != null) {
                File cfg = new File(parent, "config.json");
                if (cfg.exists()) return cfg;
            }
        }
        
        return null;
    }
    
    /**
     * Build runtime config JSON string (统一模式 - 使用 ConfigBuilder)
     * Only contains runtime parameters (memory, power, precision, thread_num)
     */
    private String buildRuntimeConfig(MemoryMode memoryMode) {
        // Get thread count from settings
        int threads = ConfigManager.getThreads(mContext);

        // Resolve backend from global settings (same as LLM)
        String backendPreference = SettingsFragment.getBackendPreference(mContext);
        String mnnBackend;
        switch (backendPreference) {
            case "OPENCL":
                mnnBackend = "opencl";
                break;
            case "VULKAN":
                mnnBackend = "vulkan";
                break;
            case "NNAPI":
                mnnBackend = "npu";  // MNN uses "npu" for Android NNAPI
                break;
            case "CPU":
            default:
                mnnBackend = "cpu";
                break;
        }

        LogManager.logI(TAG, "Backend mapping for Embedding: '" + backendPreference + "' -> '" + mnnBackend + "'");

        // Use ConfigBuilder for type safety and consistency with LLM
        String configJson = new MnnInference.ConfigBuilder()
            .backendType(mnnBackend)
            .memory(memoryMode.getValue())
            .power("high")
            .precision("low")
            .threadNum(threads)
            .build();

        LogManager.logD(TAG, "Built runtime config: backend=" + mnnBackend + ", memory=" + memoryMode.getValue() + 
            ", power=high, precision=low, threads=" + threads);

        return configJson;
    }
    
    /**
     * Extract model name from directory
     */
    private String extractModelName(File modelDir) {
        if (modelDir == null) {
            return "Unknown";
        }
        
        // Try to read from llm_config.json
        File llmConfig = new File(modelDir, "llm_config.json");
        if (llmConfig.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(llmConfig))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                
                JSONObject json = new JSONObject(content.toString());
                if (json.has("model_name")) {
                    return json.getString("model_name");
                }
            } catch (IOException | JSONException e) {
                LogManager.logD(TAG, "Could not read model name from llm_config.json");
            }
        }
        
        // Use directory name
        return modelDir.getName();
    }
    
    /**
     * Check if file is a model file (Utils功能)
     */
    public static boolean isModelFile(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".mnn") || name.equals("config.json");
    }
    
    /**
     * Check and load embedding model (Utils功能)
     * Migrated from EmbeddingModelUtils
     */
    public static void checkAndLoadEmbeddingModel(
            Context context,
            KnowledgeGraphDatabase vectorDb,
            java.util.function.Consumer<String> callback,
            ModelSelectedCallback modelSelectedCallback) {
        
        // Get configuration paths
        String embeddingModelPath = ConfigManager.getEmbeddingModelPath(context);
        String modeldir = vectorDb.getMetadata().getModeldir();
        
        boolean needEmbeddingModelSelection = false;
        String modelPath = null;
        
        // Check embedding model
        if (modeldir != null && !modeldir.isEmpty()) {
            File embeddingModelDir = new File(embeddingModelPath);
            boolean embeddingModelFound = false;
            
            if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
                File[] directories = embeddingModelDir.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File dir : directories) {
                        if (dir.getName().equals(modeldir)) {
                            // Check if directory contains model files
                            File[] modelFiles = dir.listFiles(file -> isModelFile(file));
                            if (modelFiles != null && modelFiles.length > 0) {
                                modelPath = modelFiles[0].getAbsolutePath();
                                embeddingModelFound = true;
                                break;
                            }
                        }
                    }
                }
            }
            
            if (!embeddingModelFound) {
                needEmbeddingModelSelection = true;
            }
        } else {
            needEmbeddingModelSelection = true;
        }
        
        // If selection needed, show dialog (simplified version)
        if (needEmbeddingModelSelection) {
            LogManager.logI("EmbeddingHandler", "Model selection needed");
            // For now, just callback with null to indicate selection needed
            // Full dialog implementation can be added later if needed
            callback.accept(null);
        } else {
            callback.accept(modelPath);
        }
    }
    
    /**
     * Model selection callback interface
     */
    public interface ModelSelectedCallback {
        void onModelSelected(String embeddingModel, String rerankerModel);
    }
    
    // ========== Resource Management ==========
    
    /**
     * Release current model
     */
    public synchronized void releaseModel() {
        if (mNativeHandle != 0) {
            LogManager.logI(TAG, "Releasing model: " + mCurrentModelName);
            MnnInference.releaseEmbedding(mNativeHandle);
            mNativeHandle = 0;
            mCurrentModelPath = null;
            mCurrentModelName = null;
            mEmbeddingDimension = 0;
        }
    }
    
    /**
     * Close handler (for compatibility with old code)
     */
    public void close() {
        releaseModel();
    }
    
    /**
     * Shutdown handler and release resources
     */
    public void shutdown() {
        releaseModel();
        mExecutor.shutdown();
    }
}
