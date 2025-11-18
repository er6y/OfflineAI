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

    // Stage weights for overall progress and ETA (percent of total expected time)
    private static final float TEXT_STAGE_WEIGHT = 1.0f;
    private static final float VECTOR_STAGE_WEIGHT = 97.0f;
    private static final float HUB_STAGE_WEIGHT = 2.0f;

    // Singleton instance
    private static volatile ProgressManager instance;
    
    // Progress data
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private final AtomicReference<Float> vectorizationPercentage = new AtomicReference<>(0.0f);
    // Hub filtering progress (number of hub entities processed / total hub candidates)
    private final AtomicInteger hubProcessed = new AtomicInteger(0);
    private final AtomicInteger hubTotal = new AtomicInteger(0);
    private final AtomicReference<Float> hubFilteringPercentage = new AtomicReference<>(0.0f);
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
        public final int hubProcessed;
        public final int hubTotal;
        public final float hubFilteringPercentage;
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
        
        public ProgressData(int processedFiles, int totalFiles,
                            int processedChunks, int totalChunks,
                            float vectorizationPercentage,
                            int hubProcessed, int hubTotal,
                            float hubFilteringPercentage,
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
            this.hubProcessed = hubProcessed;
            this.hubTotal = hubTotal;
            this.hubFilteringPercentage = hubFilteringPercentage;
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
        
        public float getHubFilteringProgressPercentage() {
            return hubFilteringPercentage;
        }
        
        /**
         * Get overall progress percentage based on three-stage weighting:
         * 1% text extraction, 97% vectorization, 2% hub filtering/metadata.
         */
        public float getOverallProgressPercentage() {
            // Completed stage always reports 100%
            if (currentStage == ProcessingStage.COMPLETED) {
                return 100.0f;
            }

            float textStage = 0.0f;
            if (totalFiles > 0) {
                textStage = (float) processedFiles / (float) totalFiles;
            }

            float vecStage = vectorizationPercentage / 100.0f;
            float hubStage = hubFilteringPercentage / 100.0f;

            // Overall = weighted sum of stage completion (weights sum to 100%)
            float overall = textStage * TEXT_STAGE_WEIGHT +
                            vecStage * VECTOR_STAGE_WEIGHT +
                            hubStage * HUB_STAGE_WEIGHT;

            if (overall < 0.0f) {
                overall = 0.0f;
            } else if (overall > 100.0f) {
                overall = 100.0f;
            }
            return overall;
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
        hubProcessed.set(0);
        hubTotal.set(0);
        hubFilteringPercentage.set(0.0f);
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
        // Estimate ETA immediately using completed text stage and configured stage weights
        updateTimingAndEta();
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
        ProcessingStage previous = currentStage.getAndSet(ProcessingStage.GRAPH_BUILDING);
        if (previous != ProcessingStage.GRAPH_BUILDING) {
            long now = System.currentTimeMillis();
            // Reset stage-specific start time for graph building ETA
            vectorizationStartTimeMs.set(now);
            etaMs.set(-1L);
            updateTimingAndEta();
            notifyProgressChanged();
            LogManager.logD(TAG, "Processing stage switched to graph building");
        }
    }

    /**
     * Update hub filtering progress.
     */
    public void updateHubFilteringProgress(int processed, int total, float percentage) {
        hubProcessed.set(processed);
        hubTotal.set(total);
        hubFilteringPercentage.set(percentage);
        currentStage.set(ProcessingStage.GRAPH_BUILDING);
        updateTimingAndEta();
        notifyProgressChanged();
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
     * Uses weighted stage completion (text/vectorization/hub) to estimate total time.
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

        // For idle or error states, ETA is not applicable
        if (stage == ProcessingStage.IDLE || stage == ProcessingStage.ERROR) {
            etaMs.set(-1L);
            return;
        }

        // Completed stage always reports ETA = 0
        if (stage == ProcessingStage.COMPLETED) {
            etaMs.set(0L);
            return;
        }

        if (elapsed <= 0L) {
            etaMs.set(-1L);
            return;
        }

        // Compute normalized stage completion values in [0,1]
        int totalFileCount = totalFiles.get();
        int processedFileCount = processedFiles.get();
        float textStage = 0.0f;
        if (totalFileCount > 0 && processedFileCount >= 0) {
            textStage = (float) processedFileCount / (float) totalFileCount;
            if (textStage < 0.0f) {
                textStage = 0.0f;
            } else if (textStage > 1.0f) {
                textStage = 1.0f;
            }
        }

        float vecStage = vectorizationPercentage.get() / 100.0f;
        if (vecStage < 0.0f) {
            vecStage = 0.0f;
        } else if (vecStage > 1.0f) {
            vecStage = 1.0f;
        }

        float hubStage = hubFilteringPercentage.get() / 100.0f;
        if (hubStage < 0.0f) {
            hubStage = 0.0f;
        } else if (hubStage > 1.0f) {
            hubStage = 1.0f;
        }

        // Weighted overall progress in [0,1]
        float weightedProgress = (TEXT_STAGE_WEIGHT / 100.0f) * textStage +
                                 (VECTOR_STAGE_WEIGHT / 100.0f) * vecStage +
                                 (HUB_STAGE_WEIGHT / 100.0f) * hubStage;

        // Avoid unstable estimates when progress is extremely small
        if (weightedProgress <= 0.0f) {
            etaMs.set(-1L);
            return;
        }

        // Clamp progress slightly below 1.0 to avoid division blow-up near completion
        float clampedProgress = weightedProgress;
        if (clampedProgress > 0.99f) {
            clampedProgress = 0.99f;
        }

        long totalEstimated = (long) (elapsed / clampedProgress);
        long eta = totalEstimated - elapsed;
        if (eta < 0L) {
            eta = 0L;
        }
        etaMs.set(eta);
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
            hubProcessed.get(),
            hubTotal.get(),
            hubFilteringPercentage.get(),
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
