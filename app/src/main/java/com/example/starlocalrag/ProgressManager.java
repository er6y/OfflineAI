package com.example.starlocalrag;

import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;
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
    
    // Processing stages
    public enum ProcessingStage {
        IDLE,
        TEXT_EXTRACTION,
        VECTORIZATION,
        COMPLETED
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
        
        public ProgressData(int processedFiles, int totalFiles, int processedChunks, 
                          int totalChunks, float vectorizationPercentage, 
                          ProcessingStage currentStage, String currentFileName) {
            this.processedFiles = processedFiles;
            this.totalFiles = totalFiles;
            this.processedChunks = processedChunks;
            this.totalChunks = totalChunks;
            this.vectorizationPercentage = vectorizationPercentage;
            this.currentStage = currentStage;
            this.currentFileName = currentFileName;
        }
        
        public float getFileProgressPercentage() {
            return totalFiles > 0 ? (float) processedFiles / totalFiles * 100 : 0;
        }
        
        public float getVectorizationProgressPercentage() {
            return vectorizationPercentage;
        }
        
        public boolean isProcessing() {
            return currentStage == ProcessingStage.TEXT_EXTRACTION || 
                   currentStage == ProcessingStage.VECTORIZATION;
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
        notifyProgressChanged();
        LogManager.logD(TAG, "File processing initialized with total files: " + totalFileCount);
    }
    
    /**
     * Update file processing progress
     */
    public void updateFileProgress(int processed, String fileName) {
        processedFiles.set(processed);
        currentFileName.set(fileName != null ? fileName : "");
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
        notifyProgressChanged();
        LogManager.logD(TAG, "Vectorization progress updated: " + processed + "/" + total + " (" + percentage + "%)");
    }
    
    /**
     * Mark processing as completed
     */
    public void markCompleted() {
        currentStage.set(ProcessingStage.COMPLETED);
        notifyProgressChanged();
        LogManager.logD(TAG, "Processing marked as completed");
    }
    
    /**
     * Get current progress data
     */
    public ProgressData getCurrentProgress() {
        return new ProgressData(
            processedFiles.get(),
            totalFiles.get(),
            processedChunks.get(),
            totalChunks.get(),
            vectorizationPercentage.get(),
            currentStage.get(),
            currentFileName.get()
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
        return stage == ProcessingStage.TEXT_EXTRACTION || stage == ProcessingStage.VECTORIZATION;
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