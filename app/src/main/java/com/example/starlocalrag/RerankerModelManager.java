package com.example.starlocalrag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reranker Model Manager
 * Uses singleton pattern, responsible for loading, caching and unloading reranker models
 * References the design pattern of EmbeddingModelManager
 */
public class RerankerModelManager {
    private static final String TAG = "StarLocalRAG_RerankerModelManager";
    private static final long MODEL_UNLOAD_DELAY_MS = 300000; // Unload model after 5 minutes
    
    private static volatile RerankerModelManager instance;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Model state
    private RerankerModelHandler currentModel;
    private String currentModelPath;
    private long lastAccessTime;
    private final AtomicBoolean isModelBusy = new AtomicBoolean(false);
    
    // Unload task
    private Runnable unloadTask;
    
    /**
     * Model callback interface
     */
    public interface RerankerModelCallback {
        void onModelReady(RerankerModelHandler model);
        void onModelError(String error);
    }
    
    /**
     * Rerank result callback interface
     */
    public interface RerankerCallback {
        void onRerankComplete(java.util.List<RerankerModelHandler.RerankResult> results);
        void onRerankError(String error);
        void onRerankProgress(String message);
    }
    
    /**
     * Model loading listener
     */
    public interface RerankerModelLoadListener {
        void onLoadStart();
        void onLoadProgress(String message);
        void onLoadComplete();
        void onLoadError(String error);
    }
    
    private RerankerModelLoadListener loadListener;
    
    private RerankerModelManager() {
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Get singleton instance
     */
    public static RerankerModelManager getInstance(Context context) {
        if (instance == null) {
            synchronized (RerankerModelManager.class) {
                if (instance == null) {
                    instance = new RerankerModelManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Set loading listener
     */
    public void setLoadListener(RerankerModelLoadListener listener) {
        this.loadListener = listener;
    }
    
    /**
     * Asynchronously get reranker model
     */
    public void getModelAsync(String modelPath, RerankerModelCallback callback) {
        LogManager.logI(TAG, "=== getModelAsync execution started ===");
        LogManager.logI(TAG, "Requested model path: " + modelPath);
        LogManager.logI(TAG, "Callback object: " + (callback != null ? "not null" : "null"));
        
        if (modelPath == null || modelPath.trim().isEmpty()) {
            LogManager.logW(TAG, "Reranker model path is empty");
            if (callback != null) {
                mainHandler.post(() -> callback.onModelError("Reranker model path is empty"));
            }
            return;
        }
        
        LogManager.logI(TAG, "Submitting async task to thread pool...");
        executor.execute(() -> {
            try {
                LogManager.logI(TAG, "Async task started executing, thread: " + Thread.currentThread().getName());
                RerankerModelHandler model = getModel(modelPath);
                LogManager.logI(TAG, "getModel return result: " + (model != null ? "success" : "failed"));
                
                if (model != null) {
                    LogManager.logI(TAG, "Model obtained successfully, preparing to call onModelReady callback");
                    if (callback != null) {
                        LogManager.logI(TAG, "Sending onModelReady callback to main thread");
                        mainHandler.post(() -> {
                            LogManager.logI(TAG, "onModelReady callback started executing");
                            callback.onModelReady(model);
                            LogManager.logI(TAG, "onModelReady callback execution completed");
                        });
                    } else {
                        LogManager.logW(TAG, "Callback object is null, cannot call onModelReady");
                    }
                } else {
                    LogManager.logE(TAG, "Model acquisition failed, preparing to call onModelError callback");
                    if (callback != null) {
                        mainHandler.post(() -> callback.onModelError("Reranker model loading failed"));
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Async reranker model acquisition failed: " + e.getMessage(), e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onModelError("Reranker model loading exception: " + e.getMessage()));
                }
            }
        });
    }
    
    /**
     * Asynchronously execute rerank task (recommended to use this method to avoid thread nesting issues)
     */
    public void rerankAsync(String modelPath, String query, java.util.List<String> documents, int topK, RerankerCallback callback) {
        LogManager.logI(TAG, "=== rerankAsync execution started ===");
        LogManager.logI(TAG, "Model path: " + modelPath);
        LogManager.logI(TAG, "Query: " + query);
        LogManager.logI(TAG, "Document count: " + (documents != null ? documents.size() : "null"));
        LogManager.logI(TAG, "topK: " + topK);
        
        if (modelPath == null || modelPath.trim().isEmpty()) {
            LogManager.logW(TAG, "Reranker model path is empty");
            if (callback != null) {
                mainHandler.post(() -> callback.onRerankError("Reranker model path is empty"));
            }
            return;
        }
        
        if (query == null || query.trim().isEmpty()) {
            LogManager.logW(TAG, "Query content is empty");
            if (callback != null) {
                mainHandler.post(() -> callback.onRerankError("Query content is empty"));
            }
            return;
        }
        
        if (documents == null || documents.isEmpty()) {
            LogManager.logW(TAG, "Document list is empty");
            if (callback != null) {
                mainHandler.post(() -> callback.onRerankError("Document list is empty"));
            }
            return;
        }
        
        // 检查模型是否忙碌
        if (isModelBusy.get()) {
            LogManager.logW(TAG, "Reranker model is currently in use, please wait");
            if (callback != null) {
                mainHandler.post(() -> callback.onRerankError("Reranker model is currently in use, please wait"));
            }
            return;
        }
        
        LogManager.logI(TAG, "Submitting rerank task to thread pool...");
        executor.execute(() -> {
            try {
                LogManager.logI(TAG, "Rerank task started executing, thread: " + Thread.currentThread().getName());
                
                // 在线程内部标记模型为忙碌状态
                isModelBusy.set(true);
                
                // 开始加载重排模型（不显示进度信息）
                
                // 获取模型
                RerankerModelHandler model = getModel(modelPath);
                if (model == null) {
                    LogManager.logE(TAG, "Reranker model acquisition failed");
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRerankError("Reranker model loading failed"));
                    }
                    return;
                }
                
                LogManager.logI(TAG, "Reranker model loaded successfully, starting rerank execution");
                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting rerank execution");
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRerankError("Operation stopped by user"));
                    }
                    return;
                }
                
                // 开始重排文档（不显示进度信息）
                
                // 执行重排
                java.util.List<RerankerModelHandler.RerankResult> results = model.rerank(query, documents, topK, new RerankerModelHandler.RerankProgressCallback() {
                    @Override
                    public void onRerankProgress(int processedCount, int totalCount, double score) {
                        // 在主线程中更新UI
                        mainHandler.post(() -> {
                            if (callback != null) {
                                String progressMessage = String.format("%.3f, ", score);
                                callback.onRerankProgress(progressMessage);
                            }
                        });
                    }
                });
                LogManager.logI(TAG, "Rerank execution completed, result count: " + (results != null ? results.size() : "null"));
                
                // 返回结果
                if (callback != null) {
                    if (results != null) {
                        mainHandler.post(() -> callback.onRerankComplete(results));
                    } else {
                        mainHandler.post(() -> callback.onRerankError("Rerank execution failed, returned result is empty"));
                    }
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Rerank task execution failed: " + e.getMessage(), e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onRerankError("Rerank execution exception: " + e.getMessage()));
                }
            } finally {
                // 标记模型为非忙碌状态
                isModelBusy.set(false);
                LogManager.logI(TAG, "Rerank task completed, releasing model busy state");
            }
        });
    }
    
    /**
     * Synchronously get reranker model
     */
    public synchronized RerankerModelHandler getModel(String modelPath) {
        LogManager.logI(TAG, "=== getModel execution started ===");
        LogManager.logI(TAG, "Requested model path: " + modelPath);
        LogManager.logI(TAG, "Current model path: " + currentModelPath);
        LogManager.logI(TAG, "Current model status: " + (currentModel != null ? "exists" : "does not exist"));
        
        if (modelPath == null || modelPath.trim().isEmpty()) {
            LogManager.logW(TAG, "Reranker model path is empty");
            return null;
        }
        
        // 更新最后访问时间
        lastAccessTime = System.currentTimeMillis();
        LogManager.logI(TAG, "Updated last access time: " + lastAccessTime);
        
        // 取消卸载任务
        cancelUnloadTask();
        LogManager.logI(TAG, "Cancel unload task completed");
        
        // 检查是否需要重新加载模型
        boolean needReloadResult = needReload(modelPath);
        LogManager.logI(TAG, "Need to reload: " + needReloadResult);
        
        if (needReloadResult) {
            LogManager.logI(TAG, "Need to load new reranker model: " + modelPath);
            
            // 关闭当前模型
            if (currentModel != null) {
                LogManager.logI(TAG, "Cleaning up current model");
                currentModel.cleanup();
                currentModel = null;
            }
            
            // 加载新模型
            LogManager.logI(TAG, "Starting to load new model");
            currentModel = loadModel(modelPath);
            currentModelPath = modelPath;
            LogManager.logI(TAG, "Model loading completed, result: " + (currentModel != null ? "success" : "failed"));
        } else {
            LogManager.logI(TAG, "Using existing model, no need to reload");
        }
        
        LogManager.logI(TAG, "getModel return result: " + (currentModel != null ? "success" : "failed"));
        return currentModel;
    }
    
    /**
     * Check if model needs to be reloaded
     */
    private boolean needReload(String modelPath) {
        return currentModel == null || 
               !modelPath.equals(currentModelPath) || 
               !currentModel.isInitialized();
    }
    
    /**
     * Load reranker model
     */
    private RerankerModelHandler loadModel(String modelPath) {
        try {
            LogManager.logI(TAG, "Starting to load reranker model: " + modelPath);
            
            // 通知加载开始
            if (loadListener != null) {
                mainHandler.post(() -> loadListener.onLoadStart());
            }
            
            // 检查模型文件是否存在
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                String error = "Reranker model file does not exist: " + modelPath;
                LogManager.logE(TAG, error);
                if (loadListener != null) {
                    mainHandler.post(() -> loadListener.onLoadError(error));
                }
                return null;
            }
            
            // 通知加载进度
            if (loadListener != null) {
                mainHandler.post(() -> loadListener.onLoadProgress("Initializing reranker model..."));
            }
            
            // 创建模型处理器
            RerankerModelHandler handler = new RerankerModelHandler(null, modelPath);
            
            // 初始化模型
            boolean success = handler.initialize();
            if (!success) {
                String error = "Reranker model initialization failed";
                LogManager.logE(TAG, error);
                if (loadListener != null) {
                    mainHandler.post(() -> loadListener.onLoadError(error));
                }
                handler.cleanup();
                return null;
            }
            
            LogManager.logI(TAG, "Reranker model loaded successfully: " + modelPath);
            
            // 通知加载完成
            if (loadListener != null) {
                mainHandler.post(() -> loadListener.onLoadComplete());
            }
            
            return handler;
            
        } catch (Exception e) {
            String error = "Exception occurred while loading reranker model: " + e.getMessage();
            LogManager.logE(TAG, error, e);
            if (loadListener != null) {
                mainHandler.post(() -> loadListener.onLoadError(error));
            }
            return null;
        }
    }
    
    /**
     * Mark model as starting to be used
     */
    public void markModelInUse() {
        isModelBusy.set(true);
        lastAccessTime = System.currentTimeMillis();
        cancelUnloadTask();
        LogManager.logD(TAG, "Marked reranker model as starting to be used");
    }
    
    /**
     * Mark model as finished being used
     */
    public void markModelNotInUse() {
        isModelBusy.set(false);
        lastAccessTime = System.currentTimeMillis();
        scheduleUnloadTask();
        LogManager.logD(TAG, "Marked reranker model as finished being used");
    }
    
    /**
     * Schedule unload task
     */
    private void scheduleUnloadTask() {
        // Note: Auto-unload is currently disabled, consistent with EmbeddingModelManager
        // If you need to enable it, you can uncomment the following
        /*
        cancelUnloadTask();
        unloadTask = () -> {
            synchronized (RerankerModelManager.this) {
                if (!isModelBusy.get() && 
                    System.currentTimeMillis() - lastAccessTime >= MODEL_UNLOAD_DELAY_MS) {
                    LogManager.logI(TAG, "Auto-unloading reranker model");
                    unloadModel();
                }
            }
        };
        mainHandler.postDelayed(unloadTask, MODEL_UNLOAD_DELAY_MS);
        */
    }
    
    /**
     * Cancel unload task
     */
    private void cancelUnloadTask() {
        if (unloadTask != null) {
            mainHandler.removeCallbacks(unloadTask);
            unloadTask = null;
        }
    }
    
    /**
     * Unload model
     */
    public synchronized void unloadModel() {
        if (currentModel != null) {
            LogManager.logI(TAG, "Unloading reranker model: " + currentModelPath);
            currentModel.cleanup();
            currentModel = null;
            currentModelPath = null;
        }
        cancelUnloadTask();
    }
    
    /**
     * Get current model path
     */
    public String getCurrentModelPath() {
        return currentModelPath;
    }
    
    /**
     * Check if model is currently in use
     */
    public boolean isModelBusy() {
        return isModelBusy.get();
    }
    
    /**
     * Check if there is a model loaded
     */
    public boolean hasModel() {
        return currentModel != null && currentModel.isInitialized();
    }
    
    /**
     * Shutdown manager
     */
    public void shutdown() {
        LogManager.logI(TAG, "Shutting down reranker model manager");
        
        // 卸载模型
        unloadModel();
        
        // 关闭线程池
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        
        // 清理监听器
        loadListener = null;
    }
    
    /**
     * Reset manager (for testing or re-initialization)
     */
    public static synchronized void resetManager() {
        if (instance != null) {
            LogManager.logD(TAG, "Resetting reranker model manager");
            instance.shutdown();
            instance = null;
        }
    }
}