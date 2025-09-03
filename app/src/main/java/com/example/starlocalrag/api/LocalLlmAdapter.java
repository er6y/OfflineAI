package com.example.starlocalrag.api;

import android.content.Context;
import android.util.Log;
import com.example.starlocalrag.LogManager;

import com.example.starlocalrag.ConfigManager;

import java.io.File;


/**
 * 本地LLM适配器
 * 将本地LLM处理程序与LlmApiAdapter接口对齐，方便上层代码无缝切换
 * 
 * 重构说明：
 * 1. 简化状态管理，完全依赖LocalLlmHandler的统一状态管理
 * 2. 移除复杂的等待机制，使用Handler替代新线程
 * 3. 统一模型加载逻辑，加载成功后立即执行推理
 * 4. 简化错误处理，BUSY状态强制重置
 */
public class LocalLlmAdapter {
    private static final String TAG = "LocalLlmAdapter";
    
    // 上下文
    private final Context context;
    
    // 本地LLM处理程序
    private final LocalLlmHandler localLlmHandler;
    
    // 单例实例
    private static LocalLlmAdapter instance;
    
    /**
     * 获取单例实例
     */
    public static synchronized LocalLlmAdapter getInstance(Context context) {
        if (instance == null) {
            instance = new LocalLlmAdapter(context);
        }
        return instance;
    }
    
    /**
     * 私有构造函数
     */
    private LocalLlmAdapter(Context context) {
        this.context = context;
        this.localLlmHandler = LocalLlmHandler.getInstance(context);
        LogManager.logD(TAG, "LocalLlmAdapter initialized");
    }
    
    // 防重复调用的同步锁
    private final Object callLock = new Object();
    private volatile boolean isProcessingCall = false;
    private volatile long lastCallStartTime = 0; // 记录最后一次调用开始时间
    
    /**
     * 调用本地模型进行推理（添加防重复调用机制）
     * 简化的状态处理逻辑：
     * - READY: 检查模型匹配，匹配则直接推理，不匹配则重新加载
     * - LOADING: 等待加载完成
     * - BUSY: 强制重置并重新处理
     * - UNLOADED: 直接加载
     */
    public void callLocalModel(String modelName, String prompt, LlmApiAdapter.ApiCallback callback) {
        LogManager.logI(TAG, "DEBUG: Call local model request: " + modelName + ", thread: " + Thread.currentThread().getName());
        
        long currentTime = System.currentTimeMillis();
        
        // 防重复调用检查 - 增强版本，支持超时重置
        synchronized (callLock) {
            if (isProcessingCall) {
                // 检查是否为长时间未重置的僵死状态
                if (currentTime - lastCallStartTime > 60000) { // 60秒超时
                    LogManager.logW(TAG, "Detected stale call flag, force reset after 60s timeout");
                    isProcessingCall = false;
                    lastCallStartTime = 0;
                } else {
                    LogManager.logW(TAG, "Another call is already in progress, rejecting duplicate call");
                    callback.onError("Another model call is already in progress, please wait");
                    return;
                }
            }
            isProcessingCall = true;
            lastCallStartTime = currentTime;
            LogManager.logI(TAG, "DEBUG: Call flag set, processing call for: " + modelName);
        }
        
        try {
            LocalLlmHandler.ModelState currentState = localLlmHandler.getModelState();
            String currentModelName = localLlmHandler.getCurrentModelName();
            
            LogManager.logI(TAG, "DEBUG: Current state: " + currentState + ", current model: " + currentModelName + ", target model: " + modelName);
            
            switch (currentState) {
                case READY:
                    // 模型已就绪，检查是否为目标模型
                    if (modelName.equals(currentModelName)) {
                        LogManager.logI(TAG, "DEBUG: Model ready and matches, execute inference directly: " + modelName);
                        executeInference(prompt, callback);
                    } else {
                        LogManager.logI(TAG, "DEBUG: Model mismatch, need to load target model: " + modelName + " (current: " + currentModelName + ")");
                        loadModelAndInference(modelName, prompt, callback);
                    }
                    break;
                    
                case LOADING:
                    // 模型正在加载，检查是否为目标模型
                    if (modelName.equals(currentModelName)) {
                        LogManager.logI(TAG, "DEBUG: Target model is already loading, wait for completion: " + modelName);
                        waitForModelReadyWithHandler(modelName, prompt, callback);
                    } else {
                        LogManager.logW(TAG, "DEBUG: Different model is loading (current: " + currentModelName + ", target: " + modelName + "), force reset and retry!");
                        forceResetCallFlag("Different model loading conflict");
                        // 强制重置状态并重新尝试
                        localLlmHandler.forceSetModelState(LocalLlmHandler.ModelState.UNLOADED);
                        loadModelAndInference(modelName, prompt, callback);
                    }
                    break;
                    
                case BUSY:
                    // 模型正忙，强制停止之前的推理并重新开始
                    LogManager.logW(TAG, "Model busy, force stop previous inference and restart: " + modelName);
                    // 强制停止当前推理
                    localLlmHandler.stopInference();
                    // 等待推理停止后重新开始
                    waitForModelStoppedAndRestart(modelName, prompt, callback);
                    break;
                    
                case UNLOADED:
                default:
                    // 模型未加载，直接加载
                    LogManager.logI(TAG, "DEBUG: Model unloaded, start loading: " + modelName);
                    loadModelAndInference(modelName, prompt, callback);
                    break;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error checking model state: " + modelName, e);
            forceResetCallFlag("Exception in callLocalModel: " + e.getMessage()); // 发生异常时强制重置标志
            callback.onError("Error checking model state: " + e.getMessage());
        }
    }
    
    /**
     * 重置调用标志（在推理完成后调用）
     */
    private void resetCallFlag() {
        synchronized (callLock) {
            if (isProcessingCall) {
                isProcessingCall = false;
                lastCallStartTime = 0;
                LogManager.logD(TAG, "Call flag reset, ready for next call");
            } else {
                LogManager.logW(TAG, "Call flag already reset, possible duplicate reset");
            }
        }
    }
    
    /**
     * 强制重置调用标志（用于异常恢复）
     */
    private void forceResetCallFlag(String reason) {
        synchronized (callLock) {
            boolean wasProcessing = isProcessingCall;
            isProcessingCall = false;
            lastCallStartTime = 0;
            LogManager.logW(TAG, "Force reset call flag, reason: " + reason + ", was processing: " + wasProcessing);
        }
    }
    
    /**
     * 等待模型停止后重新开始推理
     * @param modelName 模型名称
     * @param prompt 提示词
     * @param callback 回调接口
     */
    private void waitForModelStoppedAndRestart(String modelName, String prompt, LlmApiAdapter.ApiCallback callback) {
        LogManager.logD(TAG, "Waiting for model to stop before restarting: " + modelName);
        
        // 在后台线程中等待模型停止
        new Thread(() -> {
            try {
                int maxWaitTime = 5000; // 最大等待5秒
                int waitInterval = 100;   // 每100ms检查一次
                int waitedTime = 0;
                
                while (waitedTime < maxWaitTime) {
                    LocalLlmHandler.ModelState currentState = localLlmHandler.getModelState();
                    LogManager.logD(TAG, "Waiting for model to stop, current state: " + currentState + ", waited: " + waitedTime + "ms");
                    
                    if (currentState == LocalLlmHandler.ModelState.READY) {
                        // 模型已停止，检查当前加载的模型是否匹配
                        String currentModel = localLlmHandler.getCurrentModelName();
                        LogManager.logI(TAG, "Model stopped and ready, checking match - expected: " + modelName + ", current: " + currentModel);
                        
                        if (modelName.equals(currentModel)) {
                            LogManager.logI(TAG, "Model stopped and matches, executing new inference: " + modelName);
                            executeInference(prompt, callback);
                            return;
                        } else {
                            LogManager.logW(TAG, "Model stopped but mismatch, need to load target model: " + modelName + " (current: " + currentModel + ")");
                            loadModelAndInference(modelName, prompt, callback);
                            return;
                        }
                    } else if (currentState == LocalLlmHandler.ModelState.UNLOADED) {
                        LogManager.logI(TAG, "Model unloaded after stop, loading target model: " + modelName);
                        loadModelAndInference(modelName, prompt, callback);
                        return;
                    }
                    
                    // 继续等待
                    Thread.sleep(waitInterval);
                    waitedTime += waitInterval;
                }
                
                // 等待超时，强制重置状态
                LogManager.logW(TAG, "Wait for model stop timeout after " + maxWaitTime + "ms, force reset state: " + modelName);
                localLlmHandler.forceSetModelState(LocalLlmHandler.ModelState.READY);
                
                // 检查模型匹配并执行推理
                String currentModel = localLlmHandler.getCurrentModelName();
                if (modelName.equals(currentModel)) {
                    executeInference(prompt, callback);
                } else {
                    loadModelAndInference(modelName, prompt, callback);
                }
                
            } catch (InterruptedException e) {
                LogManager.logE(TAG, "Wait for model stop interrupted", e);
                resetCallFlag();
                callback.onError("Model stop wait interrupted");
            } catch (Exception e) {
                LogManager.logE(TAG, "Error waiting for model stop", e);
                resetCallFlag();
                callback.onError("Error waiting for model stop: " + e.getMessage());
            }
        }, "ModelStopWaitThread").start();
    }
    
    /**
     * 等待模型就绪（避免重复加载）
     * @param modelName 模型名称
     * @param prompt 提示词
     * @param callback 回调接口
     */
    private void waitForModelReadyWithHandler(String modelName, String prompt, LlmApiAdapter.ApiCallback callback) {
        LogManager.logD(TAG, "Model is loading, waiting for ready state: " + modelName);
        LogManager.logI(TAG, "DEBUG: Entering waitForModelReadyWithHandler for model: " + modelName);
        
        // 在后台线程中等待模型就绪
        new Thread(() -> {
            try {
                int maxWaitTime = 30000; // 最大等待30秒
                int waitInterval = 100;   // 每100ms检查一次
                int waitedTime = 0;
                
                while (waitedTime < maxWaitTime) {
                    LocalLlmHandler.ModelState currentState = localLlmHandler.getModelState();
                    LogManager.logD(TAG, "DEBUG: Waiting for model ready, current state: " + currentState + ", waited: " + waitedTime + "ms");
                    
                    if (currentState == LocalLlmHandler.ModelState.READY) {
                        // 检查当前加载的模型是否匹配
                        String currentModel = localLlmHandler.getCurrentModelName();
                        LogManager.logI(TAG, "DEBUG: Model ready, checking match - expected: " + modelName + ", current: " + currentModel);
                        
                        if (modelName.equals(currentModel)) {
                            LogManager.logI(TAG, "Model ready and matches, executing inference: " + modelName);
                            executeInference(prompt, callback);
                            return;
                        } else {
                            LogManager.logE(TAG, "Model ready but mismatch, expected: " + modelName + ", current: " + currentModel + ". This should not happen during wait!");
                            resetCallFlag(); // 重置调用标志
                            callback.onError("Model mismatch during wait, expected: " + modelName + ", got: " + currentModel);
                            return;
                        }
                    } else if (currentState == LocalLlmHandler.ModelState.UNLOADED) {
                        LogManager.logE(TAG, "Model state changed to UNLOADED during wait, this indicates loading failed: " + modelName);
                        resetCallFlag(); // 重置调用标志
                        callback.onError("Model loading failed, state changed to UNLOADED");
                        return;
                    }
                    
                    // 继续等待
                    Thread.sleep(waitInterval);
                    waitedTime += waitInterval;
                }
                
                // 等待超时
                LogManager.logE(TAG, "Wait for model ready timeout after " + maxWaitTime + "ms: " + modelName);
                resetCallFlag(); // 等待超时后重置调用标志
                callback.onError("Model loading timeout, please try again");
                
            } catch (InterruptedException e) {
                LogManager.logE(TAG, "Wait for model ready interrupted", e);
                resetCallFlag(); // 等待被中断后重置调用标志
                callback.onError("Model loading interrupted");
            } catch (Exception e) {
                LogManager.logE(TAG, "Error waiting for model ready", e);
                resetCallFlag(); // 等待过程中发生异常后重置调用标志
                callback.onError("Error waiting for model: " + e.getMessage());
            }
        }, "ModelWaitThread").start();
    }
    
    /**
     * 加载模型并执行推理
     * @param modelName 模型名称
     * @param prompt 提示词
     * @param callback 回调接口
     */
    private void loadModelAndInference(String modelName, String prompt, LlmApiAdapter.ApiCallback callback) {
        LogManager.logI(TAG, "DEBUG: Starting loadModelAndInference for: " + modelName);
        
        try {
            localLlmHandler.loadModel(modelName, new LocalLlmHandler.StreamingCallback() {
                @Override
                public void onToken(String token) {
                    // 模型加载过程中不需要处理token
                }
                
                @Override
                public void onComplete(String fullResponse) {
                    LogManager.logI(TAG, "DEBUG: Model loaded successfully, proceeding to inference: " + modelName);
                    executeInference(prompt, callback);
                }
                
                @Override
                public void onError(String error) {
                    LogManager.logE(TAG, "DEBUG: Model loading failed: " + modelName + ", error: " + error);
                    resetCallFlag(); // 模型加载失败后重置调用标志
                    callback.onError("Model loading failed: " + error);
                }
            });
        } catch (Exception e) {
            LogManager.logE(TAG, "DEBUG: Exception in loadModelAndInference: " + modelName, e);
            resetCallFlag(); // 发生异常时重置调用标志
            callback.onError("Error calling model loading: " + e.getMessage());
        }
    }
    
    /**
     * 执行推理
     * @param prompt 提示词
     * @param callback 回调接口
     */
    private void executeInference(String prompt, LlmApiAdapter.ApiCallback callback) {
        LogManager.logD(TAG, "Execute local LLM inference, prompt length: " + prompt.length());
        
        localLlmHandler.inference(prompt, new LocalLlmHandler.StreamingCallback() {
            @Override
            public void onToken(String token) {
                LogManager.print(token);
                callback.onStreamingData(token);
            }
            
            @Override
            public void onComplete(String fullResponse) {
                LogManager.logD(TAG, "Local LLM inference completed, response length: " + fullResponse.length());
                resetCallFlag(); // 推理完成后重置调用标志
                callback.onSuccess(fullResponse);
            }
            
            @Override
            public void onError(String errorMessage) {
                LogManager.logE(TAG, "Local LLM inference failed: " + errorMessage);
                resetCallFlag(); // 推理失败后重置调用标志
                callback.onError(errorMessage);
            }
        });
    }
    
    /**
     * 列出可用的本地模型
     * @return 模型名称数组
     */
    public String[] listAvailableModels() {
        // 从配置中获取模型路径
        String modelPath = ConfigManager.getModelPath(context);
        LogManager.logD(TAG, "Get model list from path: " + modelPath);
        
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            LogManager.logE(TAG, "Model directory does not exist: " + modelPath);
            return new String[0];
        }
        
        // 获取目录下的所有子目录，每个子目录就是一个模型
        File[] modelDirs = modelDir.listFiles(File::isDirectory);
        if (modelDirs == null || modelDirs.length == 0) {
            LogManager.logW(TAG, "Model directory is empty: " + modelPath);
            return new String[0];
        }
        
        // 提取目录名称作为模型名称
        String[] modelNames = new String[modelDirs.length];
        for (int i = 0; i < modelDirs.length; i++) {
            modelNames[i] = modelDirs[i].getName();
            LogManager.logD(TAG, "Found model: " + modelNames[i]);
        }
        
        return modelNames;
    }
    
    /**
     * 更新GPU设置
     * @param useGpu 是否使用GPU
     */
    public void updateGpuSetting(String useGpu) {
        LogManager.logD(TAG, "LocalLLM backend preference update: " + useGpu);
        
        if (localLlmHandler != null) {
            try {
                localLlmHandler.setUseGpu(useGpu);
                LogManager.logI(TAG, "LocalLLM backend preference: Successfully updated backend preference to " + useGpu);
                
                // 注意：后端偏好设置变更可能需要重新加载模型才能生效
                LogManager.logW(TAG, "LocalLLM backend preference: Note that backend preference changes may require model reload to take full effect");
            } catch (Exception e) {
                LogManager.logE(TAG, "LocalLLM backend preference: Failed to update backend preference: " + e.getMessage(), e);
            }
        } else {
            LogManager.logW(TAG, "LocalLLM backend preference: LocalLlmHandler not initialized, cannot update backend preference");
        }
    }
    
    /**
     * 停止生成
     */
    public void stopGeneration() {
        LogManager.logD(TAG, "Stop generation, localLlmHandler status: " + (localLlmHandler != null ? "initialized" : "not initialized"));
        
        if (localLlmHandler != null) {
            LogManager.logD(TAG, "Calling localLlmHandler.stopInference()");
            localLlmHandler.stopInference();
            LogManager.logD(TAG, "stopInference call completed, state management handled by LocalLlmHandler");
        } else {
            LogManager.logW(TAG, "localLlmHandler is null, cannot stop inference");
        }
    }
    
    /**
     * 重置停止标志
     */
    public void resetStopFlag() {
        LogManager.logD(TAG, "Reset local LLM stop flag");
        if (localLlmHandler != null) {
            localLlmHandler.resetStopFlag();
        }
    }
    
    /**
     * 重置模型记忆 - 清除KV缓存和对话历史
     */
    public void resetModelMemory() {
        if (localLlmHandler != null) {
            localLlmHandler.resetModelMemory();
        } else {
            LogManager.logW(TAG, "localLlmHandler is null, cannot reset model memory");
        }
    }
    
    /**
     * 检查当前模型状态
     * @return 当前模型状态
     */
    public LocalLlmHandler.ModelState getModelState() {
        return localLlmHandler.getModelState();
    }
    
    /**
     * 检查模型是否就绪
     * @return true如果模型就绪，false否则
     */
    public boolean isModelReady() {
        return localLlmHandler.isModelReady();
    }
    
    /**
     * 检查模型是否正忙
     * @return true如果模型正忙，false否则
     */
    public boolean isModelBusy() {
        return localLlmHandler.isModelBusy();
    }
    
    /**
     * 检查推理是否正在运行
     * @return true如果推理正在运行，false否则
     */
    public boolean isInferenceRunning() {
        if (localLlmHandler == null) {
            return false;
        }
        // 检查模型状态是否为BUSY，表示正在进行推理
        LocalLlmHandler.ModelState state = localLlmHandler.getModelState();
        return state == LocalLlmHandler.ModelState.BUSY;
    }
    
    /**
     * 获取停止标志状态
     * @return true如果应该停止推理，false否则
     */
    public boolean getShouldStop() {
        if (localLlmHandler == null) {
            return true; // 如果handler为null，认为应该停止
        }
        return localLlmHandler.shouldStopInference();
    }
    
    /**
     * 关闭适配器，释放资源
     */
    public void shutdown() {
        if (localLlmHandler != null) {
            localLlmHandler.unloadModel();
        }
        instance = null;
    }
}
