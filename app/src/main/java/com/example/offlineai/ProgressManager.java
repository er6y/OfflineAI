package com.example.offlineai;

import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Progress Manager - Centralized progress tracking without string parsing
 * 进度管理器 - 集中式进度跟踪，避免字符串解析
 */
public class ProgressManager {
    private static final String TAG = "ProgressManager";
    
    // Singleton instance
    private static volatile ProgressManager instance;
    
    // Progress data
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private final AtomicReference<Float> vectorizationPercentage = new AtomicReference<>(0.0f);
    private final AtomicReference<ProcessingStage> currentStage = new AtomicReference<>(ProcessingStage.IDLE);
    private final AtomicReference<String> currentFileName = new AtomicReference<>("");
    private final AtomicLong startTimeMs = new AtomicLong(0L);
    private final AtomicLong lastUpdateTimeMs = new AtomicLong(0L);
    // Separate start timestamp for vectorization/graph building stage to compute ETA per chunk
    private final AtomicLong vectorizationStartTimeMs = new AtomicLong(0L);
    private final AtomicLong etaMs = new AtomicLong(-1L);

    // Build configuration snapshot (for display only)
    private final AtomicReference<String> knowledgeBaseName = new AtomicReference<>("");
    private final AtomicReference<String> embeddingModelName = new AtomicReference<>("");
    private final AtomicReference<String> rerankerModelName = new AtomicReference<>("");
    private final AtomicReference<String> dictionaryFileName = new AtomicReference<>("");
    private final AtomicInteger chunkSize = new AtomicInteger(0);
    private final AtomicInteger overlapSize = new AtomicInteger(0);
    
    // Processing stages
    public enum ProcessingStage {
        IDLE,
        TEXT_EXTRACTION,
        VECTORIZATION,
        GRAPH_BUILDING,
        COMPLETED,
        ERROR
    }
    
    // Progress listener interface
    public interface ProgressListener {
        void onProgressChanged(ProgressData progressData);
    }
    
    // Progress data container
    public static class ProgressData {
        public final int processedFiles;
        public final int totalFiles;
        public final int processedChunks;
        public final int totalChunks;
        public final float vectorizationPercentage;
        public final ProcessingStage currentStage;
        public final String currentFileName;

        // Timing
        public final long startTimeMs;
        public final long elapsedMs;
        public final long etaMs;

        // Build configuration snapshot
        public final String knowledgeBaseName;
        public final String embeddingModelName;
        public final String rerankerModelName;
        public final String dictionaryFileName;
        public final int chunkSize;
        public final int overlapSize;
        
        public ProgressData(int processedFiles, int totalFiles, int processedChunks,
                            int totalChunks, float vectorizationPercentage,
                            ProcessingStage currentStage, String currentFileName,
                            long startTimeMs, long elapsedMs, long etaMs,
                            String knowledgeBaseName, String embeddingModelName,
                            String rerankerModelName, String dictionaryFileName,
                            int chunkSize, int overlapSize) {
            this.processedFiles = processedFiles;
            this.totalFiles = totalFiles;
            this.processedChunks = processedChunks;
            this.totalChunks = totalChunks;
            this.vectorizationPercentage = vectorizationPercentage;
            this.currentStage = currentStage;
            this.currentFileName = currentFileName;
            this.startTimeMs = startTimeMs;
            this.elapsedMs = elapsedMs;
            this.etaMs = etaMs;
            this.knowledgeBaseName = knowledgeBaseName;
            this.embeddingModelName = embeddingModelName;
            this.rerankerModelName = rerankerModelName;
            this.dictionaryFileName = dictionaryFileName;
            this.chunkSize = chunkSize;
            this.overlapSize = overlapSize;
        }
        
        public float getFileProgressPercentage() {
            return totalFiles > 0 ? (float) processedFiles / totalFiles * 100 : 0;
        }
        
        public float getVectorizationProgressPercentage() {
            return vectorizationPercentage;
        }
        
        public boolean isProcessing() {
            return currentStage == ProcessingStage.TEXT_EXTRACTION ||
                   currentStage == ProcessingStage.VECTORIZATION ||
                   currentStage == ProcessingStage.GRAPH_BUILDING;
        }
    }
    
    private ProgressListener progressListener;
    
    private ProgressManager() {
        // Private constructor for singleton
    }
    
    public static ProgressManager getInstance() {
        if (instance == null) {
            synchronized (ProgressManager.class) {
                if (instance == null) {
                    instance = new ProgressManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Set progress listener
     */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }
    
    /**
     * Reset all progress data
     */
    public void reset() {
        processedFiles.set(0);
        totalFiles.set(0);
        processedChunks.set(0);
        totalChunks.set(0);
        vectorizationPercentage.set(0.0f);
        currentStage.set(ProcessingStage.IDLE);
        currentFileName.set("");
        startTimeMs.set(0L);
        lastUpdateTimeMs.set(0L);
        vectorizationStartTimeMs.set(0L);
        etaMs.set(-1L);
        knowledgeBaseName.set("");
        embeddingModelName.set("");
        rerankerModelName.set("");
        dictionaryFileName.set("");
        chunkSize.set(0);
        overlapSize.set(0);
        notifyProgressChanged();
        LogManager.logD(TAG, "Progress data reset");
    }
    
    /**
     * Initialize file processing
     */
    public void initFileProcessing(int totalFileCount) {
        totalFiles.set(totalFileCount);
        processedFiles.set(0);
        currentStage.set(ProcessingStage.TEXT_EXTRACTION);
        long now = System.currentTimeMillis();
        startTimeMs.compareAndSet(0L, now);
        lastUpdateTimeMs.set(now);
        etaMs.set(-1L);
        notifyProgressChanged();
        LogManager.logD(TAG, "File processing initialized with total files: " + totalFileCount);
    }
    
    /**
     * Update file processing progress
     */
    public void updateFileProgress(int processed, String fileName) {
        processedFiles.set(processed);
        currentFileName.set(fileName != null ? fileName : "");
        updateTimingAndEta();
        notifyProgressChanged();
        LogManager.logD(TAG, "File progress updated: " + processed + "/" + totalFiles.get() + ", current file: " + fileName);
    }
    
    /**
     * Initialize vectorization
     */
    public void initVectorization(int totalChunkCount) {
        totalChunks.set(totalChunkCount);
        processedChunks.set(0);
        vectorizationPercentage.set(0.0f);
        currentStage.set(ProcessingStage.VECTORIZATION);
        long now = System.currentTimeMillis();
        if (startTimeMs.get() == 0L) {
            startTimeMs.set(now);
        }
        // Use a dedicated start time for vectorization/graph building ETA calculation
        vectorizationStartTimeMs.set(now);
        lastUpdateTimeMs.set(now);
        // Reset ETA at the beginning of vectorization; it will be calibrated once chunks are processed
        etaMs.set(-1L);
        notifyProgressChanged();
        LogManager.logD(TAG, "Vectorization initialized with total chunks: " + totalChunkCount);
    }
    
    /**
     * Update vectorization progress
     */
    public void updateVectorizationProgress(int processed, int total, float percentage) {
        processedChunks.set(processed);
        totalChunks.set(total);
        vectorizationPercentage.set(percentage);
        currentStage.set(ProcessingStage.VECTORIZATION);
        updateTimingAndEta();
        notifyProgressChanged();
        LogManager.logD(TAG, "Vectorization progress updated: " + processed + "/" + total + " (" + percentage + "%)");
    }
    
    /**
     * Mark processing as completed
     */
    public void markCompleted() {
        currentStage.set(ProcessingStage.COMPLETED);
        updateTimingAndEta();
        notifyProgressChanged();
        LogManager.logD(TAG, "Processing marked as completed");
    }

    /**
     * Mark graph building stage.
     */
    public void markGraphBuilding() {
        currentStage.set(ProcessingStage.GRAPH_BUILDING);
        long now = System.currentTimeMillis();
        // Reset stage-specific start time for graph building ETA
        vectorizationStartTimeMs.set(now);
        etaMs.set(-1L);
        updateTimingAndEta();
        notifyProgressChanged();
        LogManager.logD(TAG, "Processing stage switched to graph building");
    }

    /**
     * Update KB build configuration snapshot.
     */
    public void setBuildConfig(String kbName, String embeddingModel,
                               String rerankerModel, String dictFile,
                               int chunkSize, int overlapSize) {
        knowledgeBaseName.set(kbName != null ? kbName : "");
        embeddingModelName.set(embeddingModel != null ? embeddingModel : "");
        rerankerModelName.set(rerankerModel != null ? rerankerModel : "");
        dictionaryFileName.set(dictFile != null ? dictFile : "");
        this.chunkSize.set(chunkSize);
        this.overlapSize.set(overlapSize);
        LogManager.logD(TAG, "Build config set: kb=" + kbName + ", model=" + embeddingModel +
                ", reranker=" + rerankerModel + ", dict=" + dictFile +
                ", chunkSize=" + chunkSize + ", overlap=" + overlapSize);
    }

    /**
     * Internal helper to update elapsed time and ETA.
     */
    private void updateTimingAndEta() {
        long start = startTimeMs.get();
        if (start == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        lastUpdateTimeMs.set(now);
        long elapsed = now - start;

        ProcessingStage stage = currentStage.get();

        // For text extraction stage we do not compute ETA to avoid misleading estimates
        if (stage == ProcessingStage.TEXT_EXTRACTION) {
            etaMs.set(-1L);
            return;
        }

        // For vectorization / graph building, estimate ETA based on average time per processed chunk
        if (stage == ProcessingStage.VECTORIZATION || stage == ProcessingStage.GRAPH_BUILDING) {
            int total = totalChunks.get();
            int processed = processedChunks.get();

            // Guard against division by zero and invalid totals
            if (processed <= 0 || total <= 0 || processed >= total) {
                etaMs.set(-1L);
                return;
            }

            long vecStart = vectorizationStartTimeMs.get();
            long vecElapsed;
            if (vecStart > 0L && now > vecStart) {
                vecElapsed = now - vecStart;
            } else {
                // Fallback to global elapsed time if vectorization start is not initialized
                vecElapsed = elapsed;
            }

            if (vecElapsed <= 0L) {
                etaMs.set(-1L);
                return;
            }

            long remainingChunks = total - processed;
            if (remainingChunks <= 0L) {
                etaMs.set(0L);
                return;
            }

            double avgPerChunk = (double) vecElapsed / (double) processed;
            long eta = (long) (avgPerChunk * (double) remainingChunks);
            if (eta < 0L) {
                eta = 0L;
            }
            etaMs.set(eta);
            return;
        }

        // For completed or idle/error stages, ETA is not applicable
        if (stage == ProcessingStage.COMPLETED) {
            etaMs.set(0L);
        } else {
            etaMs.set(-1L);
        }
    }
    
    /**
     * Get current progress data
     */
    public ProgressData getCurrentProgress() {
        long start = startTimeMs.get();
        long now = System.currentTimeMillis();
        long elapsed = start > 0L ? (now - start) : 0L;
        return new ProgressData(
            processedFiles.get(),
            totalFiles.get(),
            processedChunks.get(),
            totalChunks.get(),
            vectorizationPercentage.get(),
            currentStage.get(),
            currentFileName.get(),
            start,
            elapsed,
            etaMs.get(),
            knowledgeBaseName.get(),
            embeddingModelName.get(),
            rerankerModelName.get(),
            dictionaryFileName.get(),
            chunkSize.get(),
            overlapSize.get()
        );
    }
    
    /**
     * Get current stage
     */
    public ProcessingStage getCurrentStage() {
        return currentStage.get();
    }
    
    /**
     * Check if currently processing
     */
    public boolean isProcessing() {
        ProcessingStage stage = currentStage.get();
        return stage == ProcessingStage.TEXT_EXTRACTION ||
               stage == ProcessingStage.VECTORIZATION ||
               stage == ProcessingStage.GRAPH_BUILDING;
    }
    
    /**
     * Get processed chunks count
     */
    public int getProcessedChunks() {
        return processedChunks.get();
    }
    
    /**
     * Get total chunks count
     */
    public int getTotalChunks() {
        return totalChunks.get();
    }
    
    /**
     * Get vectorization percentage
     */
    public float getVectorizationPercentage() {
        return vectorizationPercentage.get();
    }
    
    /**
     * Get processed files count
     */
    public int getProcessedFiles() {
        return processedFiles.get();
    }
    
    /**
     * Get total files count
     */
    public int getTotalFiles() {
        return totalFiles.get();
    }
    
    /**
     * Notify progress listener
     */
    private void notifyProgressChanged() {
        if (progressListener != null) {
            progressListener.onProgressChanged(getCurrentProgress());
        }
    }
}
