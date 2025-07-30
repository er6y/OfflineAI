package com.example.starlocalrag;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局停止管理器
 * 提供统一的停止标志管理，允许各个模块主动轮询和响应停止信号
 */
public class GlobalStopManager {
    private static final String TAG = "StarLocalRAG_GlobalStopManager";
    
    // 全局停止标志
    private static final AtomicBoolean globalStopFlag = new AtomicBoolean(false);
    
    /**
     * 设置全局停止标志
     * @param stop 是否停止
     */
    public static void setGlobalStopFlag(boolean stop) {
        globalStopFlag.set(stop);
        if (stop) {
            LogManager.logD(TAG, "Global stop flag set to true");
        } else {
            LogManager.logD(TAG, "Global stop flag reset to false");
        }
    }
    
    /**
     * 检查是否请求了全局停止
     * @return 是否请求了全局停止
     */
    public static boolean isGlobalStopRequested() {
        return globalStopFlag.get();
    }

    /**
     * 重置全局停止标志
     */
    public static void resetGlobalStopFlag() {
        setGlobalStopFlag(false);
    }
    
    /**
     * 检查本地LLM是否已停止
     * @return 本地LLM是否已停止
     */
    public static boolean isLocalLlmStopped() {
        try {
            // 如果设置了停止标志，认为LocalLLM应该停止
            return isGlobalStopRequested();
        } catch (Exception e) {
            return true; // 发生异常时认为已停止
        }
    }
    
    /**
     * 检查Embedding模型是否已停止
     * @return Embedding模型是否已停止
     */
    public static boolean isEmbeddingModelStopped() {
        try {
            // 如果设置了停止标志，认为Embedding模型应该停止
            return isGlobalStopRequested();
        } catch (Exception e) {
            return true; // 发生异常时认为已停止
        }
    }
    
    /**
     * 检查Reranker模型是否已停止
     * @return Reranker模型是否已停止
     */
    public static boolean isRerankerModelStopped() {
        try {
            // 如果设置了停止标志，认为Reranker模型应该停止
            return isGlobalStopRequested();
        } catch (Exception e) {
            return true; // 发生异常时认为已停止
        }
    }
    
    /**
     * 检查Tokenizer是否已停止
     * @return Tokenizer是否已停止
     */
    public static boolean isTokenizerStopped() {
        try {
            // 如果设置了停止标志，认为Tokenizer应该停止
            return isGlobalStopRequested();
        } catch (Exception e) {
            return true; // 发生异常时认为已停止
        }
    }
    
    /**
     * 检查所有模块是否都已停止
     * @return 所有模块是否都已停止
     */
    public static boolean areAllModulesStopped() {
        return isLocalLlmStopped() && 
               isEmbeddingModelStopped() && 
               isRerankerModelStopped() && 
               isTokenizerStopped();
    }
    
    /**
     * 检查指定模块是否已停止
     * @param moduleName 模块名称
     * @return 指定模块是否已停止
     */
    public static boolean isModuleStopped(String moduleName) {
        if (moduleName == null) {
            return true;
        }
        
        switch (moduleName) {
            case "LocalLLM":
                return isLocalLlmStopped();
            case "Embedding":
                return isEmbeddingModelStopped();
            case "Reranker":
                return isRerankerModelStopped();
            case "Tokenizer":
                return isTokenizerStopped();
            default:
                return true; // 未知模块认为已停止
        }
    }
    

}