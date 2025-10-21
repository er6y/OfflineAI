package com.example.offlineai.api;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// ONNX Runtime相关导入已移除
import java.util.Iterator;
import java.util.Arrays;

/**
 * 本地LLM处理程序
 * 负责加载和管理本地模型，执行本地推理
 * 支持多种模型类型，包括ONNX等
 * 
 * 重构说明：
 * 1. 统一接口规范，明确职责分离
 * 2. 简化回调接口，提高可维护性
 * 3. 为ONNX Runtime GenAI迁移做准备
 */
public class LocalLlmHandler {
    private static final String TAG = "LocalLLMHandler";
    
    /**
     * 模型状态枚举 - 统一状态管理
     */
    public enum ModelState {
        UNLOADED,    // 未加载
        LOADING,     // 正在加载
        READY,       // 已加载且空闲
        BUSY         // 正在推理
    }
    
    // 单例实例
    private static LocalLlmHandler instance;
    
    // 上下文
    private final Context context;
    
    // 线程池
    private final ExecutorService executorService;
    
    // 当前加载的模型名称
    private String currentModelName;
    
    // 统一的模型状态管理 - 简化为单一状态源
    private final AtomicReference<ModelState> modelState = new AtomicReference<>(ModelState.UNLOADED);
    
    // 是否使用GPU
    private String useGpu = "CPU";
    // 全局思考模式开关，默认关闭，避免未勾选也插入 <think>
    private volatile boolean thinkingModeEnabled = false;
    
    // 推理停止标志
    private final AtomicBoolean shouldStopInference = new AtomicBoolean(false);
    
    // 推理引擎接口（支持多种实现）
    private InferenceEngine inferenceEngine;
    
    // 模型配置
    private ModelConfig modelConfig;
    
    // 词汇表相关字段已不再使用，由 Rust tokenizer 处理
    
    // 特殊token
    private int bosToken = 1;
    private int eosToken = 2;
    private int padToken = 0;
    
    // 最大序列长度
    private int maxSeqLen = 2048;
    
    // 模型类型
    private String modelType = "gguf";
    
    // ONNX相关变量已移除
    
    // 模型配置类
    public static class ModelConfig {
        String modelType; // 模型类型，如"qwen", "deepseek"等
        int vocabSize;    // 词汇表大小
        int hiddenSize;   // 隐藏层大小
        int numLayers;    // 层数
        int numHeads;     // 注意力头数
        private String modelPath; // 模型路径
        private int bosToken;     // 开始标记ID
        private int eosToken;     // 结束标记ID
        
        // 量化相关配置
        private boolean isQuantized = false;     // 是否为量化模型
        private String quantizationType = null; // 量化类型："int8", "int4", "fp16"等
        private float quantizationScale = 1.0f; // 量化缩放因子
        private int quantizationZeroPoint = 0;  // 量化零点
        private boolean enableKVCache = false;  // 是否启用KV缓存，默认禁用以避免兼容性问题
        private int maxBatchSize = 2;           // 最大批处理大小（调查报告建议：从1增加到2-4）
        private int maxSequenceLength = 1024;   // 最大序列长度（动态调整，结合maxSequenceLength配置）
        private Map<String, Object> kvCacheConfig = new HashMap<>(); // KV缓存配置
        
        public ModelConfig(String modelType, int vocabSize, int hiddenSize, int numLayers, int numHeads) {
            this.modelType = modelType;
            this.vocabSize = vocabSize;
            this.hiddenSize = hiddenSize;
            this.numLayers = numLayers;
            this.numHeads = numHeads;
        }
        
        /**
         * 判断模型是否需要注意力掩码
         * @return 是否需要注意力掩码
         */
        public boolean requiresAttentionMask() {
            // 大多数模型都需要注意力掩码
            return true;
        }
        
        /**
         * 判断模型是否需要位置编码
         * @return 是否需要位置编码
         */
        public boolean requiresPositionIds() {
            // 根据模型类型判断是否需要位置编码
            // 例如，某些模型可能使用RoPE等相对位置编码，不需要显式的位置ID
            return "qwen".equalsIgnoreCase(modelType) || "deepseek".equalsIgnoreCase(modelType);
        }
        
        /**
         * 获取模型路径
         * @return 模型路径
         */
        public String getModelPath() {
            return modelPath;
        }
        
        /**
         * 设置模型路径
         * @param modelPath 模型路径
         */
        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }
        
        /**
         * 获取开始标记ID
         * @return 开始标记ID
         */
        public int getBosToken() {
            return bosToken;
        }
        
        /**
         * 设置开始标记ID
         * @param bosToken 开始标记ID
         */
        public void setBosToken(int bosToken) {
            this.bosToken = bosToken;
        }
        
        /**
         * 获取结束标记ID
         * @return 结束标记ID
         */
        public int getEosToken() {
            return eosToken;
        }
        
        /**
         * 设置结束标记ID
         * @param eosToken 结束标记ID
         */
        public void setEosToken(int eosToken) {
            this.eosToken = eosToken;
        }
        
        /**
         * 获取结束标记ID（别名方法）
         * @return 结束标记ID
         */
        public int getEosTokenId() {
            return eosToken;
        }
        
        /**
         * 获取填充标记ID（通常与EOS相同或为0）
         * @return 填充标记ID
         */
        public int getPadTokenId() {
            // 如果没有专门的pad token，通常使用eos token或0
            return eosToken != 0 ? eosToken : 0;
        }
        
        /**
         * 获取注意力头数
         * @return 注意力头数
         */
        public int getNumAttentionHeads() {
            return numHeads;
        }
        
        /**
         * 获取隐藏层大小
         * @return 隐藏层大小
         */
        public int getHiddenSize() {
            return hiddenSize;
        }
        
        /**
         * 获取隐藏层数量
         * @return 隐藏层数量
         */
        public int getNumHiddenLayers() {
            return numLayers;
        }
        
        /**
         * 获取模型类型
         * @return 模型类型
         */
        public String getModelType() {
            return modelType;
        }
        
        /**
         * 获取tokenizer.json文件路径
         * @return tokenizer.json文件路径
         */
        public String getTokenizerJsonPath() {
            if (modelPath == null || modelPath.isEmpty()) {
                return null;
            }
            return modelPath + "/tokenizer.json";
        }
        
        // 量化相关的getter和setter方法
        public boolean isQuantized() {
            return isQuantized;
        }
        
        public void setQuantized(boolean quantized) {
            this.isQuantized = quantized;
        }
        
        public String getQuantizationType() {
            return quantizationType;
        }
        
        public void setQuantizationType(String quantizationType) {
            this.quantizationType = quantizationType;
        }
        
        public float getQuantizationScale() {
            return quantizationScale;
        }
        
        public void setQuantizationScale(float quantizationScale) {
            this.quantizationScale = quantizationScale;
        }
        
        public int getQuantizationZeroPoint() {
            return quantizationZeroPoint;
        }
        
        public void setQuantizationZeroPoint(int quantizationZeroPoint) {
            this.quantizationZeroPoint = quantizationZeroPoint;
        }
        
        public boolean isEnableKVCache() {
            return enableKVCache;
        }
        
        public void setEnableKVCache(boolean enableKVCache) {
            this.enableKVCache = enableKVCache;
        }
        
        public int getMaxBatchSize() {
            return maxBatchSize;
        }
        
        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }
        
        public int getMaxSequenceLength() {
            return maxSequenceLength;
        }
        
        public void setMaxSequenceLength(int maxSequenceLength) {
            this.maxSequenceLength = maxSequenceLength;
        }
        
        public Map<String, Object> getKvCacheConfig() {
            return kvCacheConfig;
        }
        
        public void setKvCacheConfig(Map<String, Object> kvCacheConfig) {
            this.kvCacheConfig = kvCacheConfig;
        }
        
        /**
         * 动态禁用KV缓存（用于内存不足时的降级策略）
         */
        public void disableKVCache() {
            this.enableKVCache = false;
            LogManager.logW("ModelConfig", "KV缓存已被动态禁用以节省内存");
        }
        
        /**
         * 获取适合当前内存状况的缓存大小
         * @param requestedSize 请求的缓存大小
         * @return 调整后的缓存大小
         */
        public int getAdaptiveCacheSize(int requestedSize) {
            // 获取可用内存
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long availableMemory = maxMemory - totalMemory + freeMemory;
            
            // 如果可用内存小于50MB，使用最小缓存
            if (availableMemory < 50 * 1024 * 1024) {
                return Math.min(requestedSize, 128);
            }
            // 如果可用内存小于100MB，使用中等缓存
            else if (availableMemory < 100 * 1024 * 1024) {
                return Math.min(requestedSize, 256);
            }
            // 否则使用请求的缓存大小
            else {
                return requestedSize;
            }
        }
    }
    
    /**
     * 本地LLM回调接口
     */
    public interface LocalLlmCallback {
        void onToken(String token);
        void onTokenGenerated(String token); // 添加兼容方法
        void onComplete(String fullResponse);
        void onError(String errorMessage);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized LocalLlmHandler getInstance(Context context) {
        if (instance == null) {
            instance = new LocalLlmHandler(context);
        }
        return instance;
    }
    
    /**
     * 私有构造函数
     */
    private LocalLlmHandler(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        
        LogManager.logD(TAG, "LocalLlmHandler初始化: 模型状态设置为 " + ModelState.UNLOADED);
        
        // 初始化GPU设置
        this.useGpu = ConfigManager.getString(context, ConfigManager.KEY_USE_GPU, "CPU");
        LogManager.logD(TAG, "LocalLlmHandler初始化: 后端偏好设置为 " + this.useGpu);

        // 从配置管理器读取思考模式（cfgmng 负责持久化），默认不禁用思考
        boolean noThinking = ConfigManager.getNoThinking(context);
        this.thinkingModeEnabled = !noThinking;
        LogManager.logD(TAG, "LocalLlmHandler初始化: 思考模式=" + (this.thinkingModeEnabled ? "启用" : "禁用") + "（来自配置管理器）");
        
        // Inference engine will be auto-selected when loading model
        // Supported engine: MNN (Mobile Neural Network)
        this.inferenceEngine = null;
        LogManager.logI(TAG, "LocalLlmHandler initialized: inference engine will be auto-selected based on model type");
    }
    
    /**
     * 设置推理引擎
     * @param engine 推理引擎实例
     */
    public void setInferenceEngine(InferenceEngine engine) {
        if (this.inferenceEngine != null) {
            this.inferenceEngine.release();
        }
        this.inferenceEngine = engine;
        LogManager.logI(TAG, "推理引擎已切换为: " + engine.getEngineType());
    }
    
    // ONNX引擎切换方法已移除
    
    /**
     * 获取推理引擎实例
     * @return 推理引擎实例，可能为null
     */
    public InferenceEngine getInferenceEngine() {
        return inferenceEngine;
    }
    
    /**
     * 获取当前推理引擎类型
     * @return 引擎类型名称
     */
    public String getCurrentEngineType() {
        return inferenceEngine != null ? inferenceEngine.getEngineType() : "未知";
    }
    
    /**
     * 设置后端偏好
     */
    public void setUseGpu(String useGpu) {
        this.useGpu = useGpu;
    }

    // 设置全局思考模式，供 UI 开关调用
    public void setThinkingMode(boolean thinkingMode) {
        this.thinkingModeEnabled = thinkingMode;
    }
    
    /**
     * 根据配置更新推理引擎
     */
    public void updateEngineFromConfig() {
        // MNN engine configuration is managed automatically
        LogManager.logI(TAG, "MNN engine only, no manual config update needed");
    }
    
    /**
     * 加载本地模型（简化版接口）
     * @param modelName 模型名称（目录名）
     * @param callback 回调接口
     */
    public void loadModel(String modelName, final StreamingCallback callback) {
        loadModel(modelName, new LocalLlmCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }
            
            @Override
            public void onTokenGenerated(String token) {
                callback.onToken(token);
            }
            
            @Override
            public void onComplete(String fullResponse) {
                callback.onComplete(fullResponse);
            }
            
            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }
    
    /**
     * 加载本地模型（简化版本）
     * @param modelName 模型名称（目录名）
     * @param callback 回调接口
     */
    @Deprecated
    public void loadModel(String modelName, final LocalLlmCallback callback) {

        
        LogManager.logI(TAG, "DEBUG: Load model request: " + modelName + ", thread: " + Thread.currentThread().getName());
         
         // 发送后台启动快照（重置停止标志之前）
        ModelState currentState = modelState.get();
        LogManager.logD(TAG, "[SNAPSHOT][BG_START] pre-reset-stop, state=" + currentState
                + ", shouldStop=" + shouldStopInference.get()
                + ", thread=" + Thread.currentThread().getName());
        
        // 重置停止标志，并发送 [STREAM] onStart 日志
        resetStopFlag();
        LogManager.logD(TAG, "[STREAM] onStart - engine=unknown, model=" + modelName);
        
        // 在后台线程执行模型加载
        executorService.submit(() -> {
            try {
                // Check if the same model is already loaded
                if (modelName.equals(currentModelName) && modelState.get() == ModelState.READY) {
                    LogManager.logI(TAG, "Model already loaded: " + modelName + ", skipping reload");
                    callback.onComplete("Already loaded");
                    return;
                }
                
                ModelState st = modelState.get();
                if (st == ModelState.BUSY) {
                    callback.onError("Model is busy, cannot load now");
                    return;
                }

                // 如果目标模型与当前不同，且已有模型已加载，先强制卸载以避免残留会话/资源
                if (currentModelName != null && !modelName.equals(currentModelName) && isModelLoaded()) {
                    LogManager.logI(TAG, "Switching model: force unload previous model '" + currentModelName + "' before loading '" + modelName + "'");
                    unloadModel();
                }

                forceSetModelState(ModelState.LOADING);
                
                String baseModelPath = ConfigManager.getModelPath(context);
                File modelDir = new File(baseModelPath, modelName);
                if (!modelDir.exists() || !modelDir.isDirectory()) {
                    forceSetModelState(ModelState.UNLOADED);
                    callback.onError("Model directory not found: " + modelDir.getAbsolutePath());
                    return;
                }
                
                InferenceEngine engine = selectInferenceEngine(modelDir);
                if (engine == null) {
                    forceSetModelState(ModelState.UNLOADED);
                    callback.onError("No suitable inference engine for model: " + modelName);
                    return;
                }
                
                // Let the engine find and validate the model file
                // This delegates format-specific logic (e.g., .gguf, .mnn) to the engine
                String modelPath = engine.findModelFile(modelDir);
                if (modelPath == null) {
                    forceSetModelState(ModelState.UNLOADED);
                    callback.onError("No valid model file found in: " + modelDir.getAbsolutePath());
                    return;
                }
                
                ModelConfig config = createBasicModelConfig(modelPath);
                
                // Create StreamingCallback wrapper for engine initialization
                StreamingCallback engineCallback = new StreamingCallback() {
                    @Override
                    public void onToken(String token) {
                        callback.onToken(token);
                    }
                    @Override
                    public void onComplete(String fullResponse) {}
                    @Override
                    public void onError(String errorMessage) {}
                };
                
                engine.initialize(modelPath, config, engineCallback);
                
                setInferenceEngine(engine);
                currentModelName = modelName;
                forceSetModelState(ModelState.READY);
                LogManager.logI(TAG, "Model loaded successfully: " + modelName + ", engine=" + engine.getEngineType());
                callback.onComplete("Loaded");
            } catch (Exception e) {
                forceSetModelState(ModelState.UNLOADED);
                LogManager.logE(TAG, "Error loading model: " + modelName, e);
                callback.onError("Error loading model: " + e.getMessage());
            }
        });
    }
    
    /**
     * 执行推理（兼容旧接口）
     * @param prompt 输入提示词
     * @param callback 回调接口
     */
    @Deprecated
    public void inference(String prompt, LocalLlmCallback callback) {
        // 使用包装以适配 StreamingCallback，避免非法类型转换
        StreamingCallback wrapper = new StreamingCallback() {
            @Override
            public void onToken(String token) { callback.onToken(token); }
            @Override
            public void onComplete(String fullResponse) { callback.onComplete(fullResponse); }
            @Override
            public void onError(String errorMessage) { callback.onError(errorMessage); }
        };
        inference(prompt, wrapper);
    }

    // 新增：执行推理（主用接口，支持流式回调）
    public void inference(String prompt, final StreamingCallback callback) {
        inference(prompt, null, callback);
    }
    
    // 新增：执行多模态推理（支持图片输入）
    public void inference(String prompt, java.util.List<String> imagePaths, final StreamingCallback callback) {
        if (prompt == null) {
            LogManager.logE(TAG, "Inference prompt is null");
            if (callback != null) callback.onError("Prompt is null");
            return;
        }

        LogManager.logI(TAG, "DEBUG: Inference request received, thread=" + Thread.currentThread().getName() + ", promptLen=" + prompt.length());

        // 发送后台启动快照（重置停止标志之前）
        ModelState currentState = modelState.get();
        LogManager.logD(TAG, "[SNAPSHOT][BG_START] pre-reset-stop, state=" + currentState
                + ", shouldStop=" + shouldStopInference.get()
                + ", thread=" + Thread.currentThread().getName());

        // 重置停止标志，并发送 [STREAM] onStart 日志
        resetStopFlag();
        String engineType = (inferenceEngine != null) ? inferenceEngine.getEngineType() : "unknown";
        String modelName = (currentModelName != null) ? currentModelName : "unknown";
        LogManager.logD(TAG, "[STREAM] onStart - engine=" + engineType + ", model=" + modelName + ", promptLen=" + prompt.length());

        // 前置检查
        if (inferenceEngine == null) {
            LogManager.logE(TAG, "Inference engine is null, cannot start inference");
            if (callback != null) callback.onError("Inference engine not initialized");
            return;
        }
        if (!isModelReady()) {
            LogManager.logW(TAG, "Model is not ready: state=" + modelState.get());
            if (callback != null) callback.onError("Model is not ready");
            return;
        }

        // 设置状态为 BUSY
        forceSetModelState(ModelState.BUSY);

        // 构造推理参数：按使用点从配置管理器读取，确保设置即时生效
        InferenceParams params = buildParamsFromConfig();

        // 轻量统计：记录开始时间与token数
        final long startNs = System.nanoTime();
        final java.util.concurrent.atomic.AtomicInteger tokenCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // 委托调用前日志
        LogManager.logI(TAG, "[DELEGATE] submitting engine.inference task - engine=" + engineType + ", thread=" + Thread.currentThread().getName());

        // Log multimodal info
        if (imagePaths != null && !imagePaths.isEmpty()) {
            LogManager.logI(TAG, "[MULTIMODAL] Inference with " + imagePaths.size() + " images");
        }
        
        // 在后台线程发起推理，底层引擎内部也会切线程，但此处保持与加载一致的异步行为
        executorService.submit(() -> {
            LogManager.logD(TAG, "[ASYNC] delegate worker started - thread=" + Thread.currentThread().getName());
            try {
                LogManager.logD(TAG, "[DELEGATE] calling engine.inference(...), promptLen=" + prompt.length());
                inferenceEngine.inference(prompt, imagePaths, params, new StreamingCallback() {
                    @Override
                    public void onToken(String token) {
                        int c = tokenCount.incrementAndGet();
                        if (callback != null) callback.onToken(token);
                        if (c == 1 || c % 50 == 0) {
                            LogManager.logD(TAG, "[STREAM] onToken - count=" + c);
                        }
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                        LogManager.logD(TAG, "[STREAM] onComplete - tokens=" + tokenCount.get() + ", elapsedMs=" + elapsedMs);
                        LogManager.logD(TAG, "DEBUG: Inference completed, responseLen=" + (fullResponse != null ? fullResponse.length() : 0));
                        forceSetModelState(ModelState.READY);
                        if (callback != null) callback.onComplete(fullResponse);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                        LogManager.logE(TAG, "[STREAM] onError - tokens=" + tokenCount.get() + ", elapsedMs=" + elapsedMs + ", error=" + errorMessage);
                        LogManager.logE(TAG, "DEBUG: Inference failed: " + errorMessage);
                        forceSetModelState(ModelState.READY);
                        if (callback != null) callback.onError(errorMessage);
                    }
                });
                LogManager.logD(TAG, "[DELEGATE] engine.inference returned (streaming ongoing if no error)");
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to start inference", e);
                forceSetModelState(ModelState.READY);
                if (callback != null) callback.onError("Failed to start inference: " + e.getMessage());
            } finally {
                LogManager.logD(TAG, "[ASYNC] delegate worker finished - thread=" + Thread.currentThread().getName());
            }
        });
    }

    /**
     * 从配置管理器构建推理参数（按调用点读取，避免使用陈旧的全局缓存值）
     */
    private InferenceParams buildParamsFromConfig() {
        InferenceParams params = new InferenceParams();
        // 思考模式：no_thinking=true 表示禁用思考，因此需要取反
        boolean noThinking = ConfigManager.getNoThinking(context);
        params.setThinkingMode(!noThinking);

        // 统一从ConfigManager读取所有推理参数，确保设置即时生效
        // 优先顺序：如果开启“优先手动参数”则使用手动参数；否则读取全局LLM/LlamaCpp配置
        boolean priorityManual = ConfigManager.getPriorityManualParams(context);
        if (priorityManual) {
            // 手动参数区域（Manual Inference Params）
            int maxNewTokens = ConfigManager.getMaxNewTokens(context);
            float temperature = ConfigManager.getManualTemperature(context);
            int topK = ConfigManager.getManualTopK(context);
            float topP = ConfigManager.getManualTopP(context);
            float repetitionPenalty = ConfigManager.getManualRepeatPenalty(context);
            int seed = -1; // 手动参数暂不提供seed配置，使用默认随机

            params.setMaxTokens(maxNewTokens);
            params.setTemperature(temperature);
            params.setTopK(topK);
            params.setTopP(topP);
            params.setRepetitionPenalty(repetitionPenalty);
            params.setSeed(seed);
        } else {
            // MNN/LLM common parameters (centralized management for all engines)
            int maxNewTokens = ConfigManager.getMaxNewTokens(context);
            float temperature = ConfigManager.getLlamaCppTemperature(context);
            int topK = ConfigManager.getLlamaCppTopK(context);
            float topP = ConfigManager.getLlamaCppTopP(context);
            float repetitionPenalty = ConfigManager.getLlamaCppRepetitionPenalty(context);
            int seed = ConfigManager.getLlamaCppSeed(context);

            params.setMaxTokens(maxNewTokens);
            params.setTemperature(temperature);
            params.setTopK(topK);
            params.setTopP(topP);
            params.setRepetitionPenalty(repetitionPenalty);
            params.setSeed(seed);
        }

        return params;
    }
    
    /**
     * 批处理推理 - 支持多序列并行推理
     * @param inputTexts 输入文本数组
     * @param maxTokens 最大生成token数
     * @param temperature 温度参数
     * @param topK topK参数
     * @param topP topP参数
     * @param callback 流式回调
     * @return 生成的文本数组
     */
    public String[] inferenceStreamBatch(String[] inputTexts, int maxTokens, float temperature, int topK, float topP, LocalLlmCallback callback) {
        LogManager.logE(TAG, "批处理推理不支持，ONNX引擎已移除");
        String[] errorResults = new String[inputTexts.length];
        for (int i = 0; i < inputTexts.length; i++) {
            errorResults[i] = "批处理推理不支持";
        }
        return errorResults;
    }
    
    /**
     * 批处理推理（简化版本）
     * @param inputTexts 输入文本数组
     * @param callback 回调接口
     * @return 生成的文本数组
     */
    public String[] inferenceStreamBatch(String[] inputTexts, LocalLlmCallback callback) {
        // 使用默认参数
        return inferenceStreamBatch(inputTexts, 512, 0.7f, 40, 0.9f, callback);
    }
    
    /**
     * 获取当前模型的批处理能力信息
     * @return 批处理信息字符串
     */
    public String getBatchProcessingInfo() {
        if (modelConfig == null) {
            return "模型未加载";
        }
        
        StringBuilder info = new StringBuilder();
        String batchSupport = modelConfig.getMaxBatchSize() > 1 ? context.getString(R.string.common_yes) : context.getString(R.string.common_no);
        info.append("Batch Support: ").append(batchSupport).append("\n");
        info.append("Max Batch Size: ").append(modelConfig.getMaxBatchSize()).append("\n");
        info.append("Max Sequence Length: ").append(modelConfig.getMaxSequenceLength()).append("\n");
        String kvCache = modelConfig.isEnableKVCache() ? context.getString(R.string.common_enabled) : context.getString(R.string.common_disabled);
        info.append("KV Cache: ").append(kvCache).append("\n");
        String quantizationType = modelConfig.isQuantized() ? modelConfig.getQuantizationType() : context.getString(R.string.common_none);
        info.append("Quantization Type: ").append(quantizationType).append("\n");
        
        return info.toString();
    }
    
    /**
     * 停止当前推理（简化版本）
     */
    public void stopInference() {
        LogManager.logD(TAG, "Stop inference, current stop flag: " + shouldStopInference.get());
        
        // 调用推理引擎的stopInference方法
        if (inferenceEngine != null) {
            try {
                inferenceEngine.stopInference();
            } catch (Exception e) {
                LogManager.logE(TAG, "Inference engine stopInference failed: " + e.getMessage());
            }
        } else {
            LogManager.logW(TAG, "Inference engine is null, cannot call stopInference");
        }
        
        // 设置本地停止标志
        shouldStopInference.set(true);
        
        // DO NOT force state change here!
        // State will be changed to READY by onComplete/onError callbacks
        // when native thread actually stops
        LogManager.logD(TAG, "Stop signal sent, waiting for native thread to finish");
    }
    
    /**
     * 检查是否应该停止推理
     */
    public boolean shouldStopInference() {
        return shouldStopInference.get();
    }
    
    /**
     * 重置停止标志
     */
    public void resetStopFlag() {
        shouldStopInference.set(false);
        LogManager.logD(TAG, "[STOP] resetStopFlag -> false");
    }
    
    /**
     * 重置模型记忆 - 清除KV缓存和对话历史
     */
    public void resetModelMemory() {
        ModelState currentState = modelState.get();
        if (currentState != ModelState.READY && currentState != ModelState.BUSY) {
            LogManager.logW(TAG, "模型不可用，无法重置记忆，当前状态: " + currentState);
            return;
        }
        
        try {
            if (inferenceEngine != null && inferenceEngine instanceof LocalLLMMNNHandler) {
                LogManager.logI(TAG, "Resetting MNN session (clearing KV cache)");
                ((LocalLLMMNNHandler) inferenceEngine).resetSession();
            } else {
                LogManager.logI(TAG, "Current engine does not support session reset");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to reset model memory", e);
        }
    }
    
    // ========== 统一状态管理方法 ==========
    
    /**
     * 获取当前模型状态
     * @return 当前模型状态
     */
    public ModelState getModelState() {
        return modelState.get();
    }
    
    /**
     * 检查模型是否可以进行推理
     * @return true如果模型处于READY状态，false否则
     */
    public boolean isModelReady() {
        return modelState.get() == ModelState.READY;
    }
    
    /**
     * 检查模型是否正忙
     * @return true如果模型处于BUSY或LOADING状态，false否则
     */
    public boolean isModelBusy() {
        ModelState state = modelState.get();
        return state == ModelState.BUSY || state == ModelState.LOADING;
    }
    
    /**
     * 尝试将模型状态从期望状态转换为目标状态
     * @param expectedState 期望的当前状态
     * @param newState 目标状态
     * @return true如果转换成功，false否则
     */
    // 移除tryTransitionState方法，简化为直接使用forceSetModelState
    
    /**
     * 强制设置模型状态（仅在错误恢复时使用）
     * @param newState 新状态
     */
    public void forceSetModelState(ModelState newState) {
        ModelState oldState = modelState.getAndSet(newState);
        LogManager.logW(TAG, "Force model state change: " + oldState + " -> " + newState);
    }
    
    // 移除兼容性标志更新方法，简化状态管理
    
    // ========== 兼容性方法（保持向后兼容） ==========
    
    /**
     * 检查模型是否已加载（兼容性方法）
     * @return true如果模型已加载，false否则
     */
    public boolean isModelLoaded() {
        ModelState state = modelState.get();
        return state == ModelState.READY || state == ModelState.BUSY;
    }
    
    /**
     * 获取当前加载的模型名称
     * @return 当前模型名称，如果没有加载模型则返回null
     */
    public String getCurrentModelName() {
        return currentModelName;
    }
    
    /**
     * 卸载模型
     */
    public void unloadModel() {
        LogManager.logD(TAG, "开始卸载模型");
        
        ModelState currentState = modelState.get();
        if (currentState == ModelState.UNLOADED) {
            LogManager.logD(TAG, "模型已经是未加载状态");
            return;
        }
        
        // 如果模型正在忙碌，先停止推理
        if (currentState == ModelState.BUSY) {
            LogManager.logI(TAG, "模型正忙，先停止推理");
            stopInference();
            // 等待一小段时间让推理停止
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        try {
            // 释放推理引擎资源
            if (inferenceEngine != null) {
                inferenceEngine.release();
                inferenceEngine = null;
            }
            
            // 清空当前模型名称
            currentModelName = null;
            
            // 转换状态为UNLOADED
            forceSetModelState(ModelState.UNLOADED);
            
            LogManager.logI(TAG, "模型卸载完成");
        } catch (Exception e) {
            LogManager.logE(TAG, "卸载模型时发生错误: " + e.getMessage(), e);
            // 即使出错也要设置为UNLOADED状态
            forceSetModelState(ModelState.UNLOADED);
        } finally {
            // 释放内存
            System.gc();
            LogManager.logD(TAG, "已请求垃圾回收");
        }
    }
    
    /**
     * 根据配置的最大序列长度动态计算实际序列长度
     * @param configuredMaxSeqLength 配置的最大序列长度
     * @return 动态计算的序列长度
     */
    private int calculateDynamicSequenceLength(int configuredMaxSeqLength) {
        // 基础输入长度预留（用于提示词、上下文等）
        int baseInputLength = 512;
        
        // 计算总序列长度：基础输入 + 配置的最大长度 + 安全边距
        int calculatedLength = baseInputLength + configuredMaxSeqLength + 128;
        
        // 设置合理的范围限制
        int minLength = 1024;  // 最小序列长度
        int maxLength = 8192;  // 最大序列长度（考虑内存限制）
        
        // 应用范围限制
        calculatedLength = Math.max(minLength, Math.min(maxLength, calculatedLength));
        
        LogManager.logD(TAG, String.format("序列长度计算: 基础输入=%d, 配置值=%d, 计算结果=%d", 
            baseInputLength, configuredMaxSeqLength, calculatedLength));
        
        return calculatedLength;
    }
    
    // ...
    private void logMemoryInfo() {
        // 记录内存信息逻辑
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        LogManager.logD(TAG, "内存信息 - 最大: " + maxMemory / 1024 / 1024 + "MB, 总计: " + totalMemory / 1024 / 1024 + "MB, 已用: " + usedMemory / 1024 / 1024 + "MB, 空闲: " + freeMemory / 1024 / 1024 + "MB");
        
        // 垃圾回收优化：当内存使用率超过75%时，建议进行垃圾回收
        double memoryUsageRatio = (double) usedMemory / maxMemory;
        if (memoryUsageRatio > 0.75) {
            LogManager.logW(TAG, "内存使用率较高 (" + String.format("%.1f", memoryUsageRatio * 100) + "%)，建议进行垃圾回收");
            // 建议垃圾回收，但不强制执行，让系统自行决定
            System.gc();
            LogManager.logI(TAG, "已建议系统进行垃圾回收以优化内存使用");
        }
    }

    // ONNX Runtime GenAI推理引擎已移除

    // ONNX Runtime推理引擎已移除

    /**
     * 加载模型配置
     * @param configFile 配置文件
     * @return 模型配置对象
     * @throws Exception 异常
     */
    private ModelConfig loadModelConfig(File configFile) throws Exception {
        LogManager.logD(TAG, "加载模型配置: " + configFile.getPath());
        
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        
        // 检查文件内容是否为空
        String configContent = content.toString().trim();
        if (configContent.isEmpty()) {
            throw new Exception("配置文件为空: " + configFile.getPath());
        }
        
        LogManager.logD(TAG, "配置文件内容长度: " + configContent.length() + " 字符");
        LogManager.logD(TAG, "配置文件前100字符: " + configContent.substring(0, Math.min(100, configContent.length())));
        
        JSONObject config = new JSONObject(configContent);
        
        // 检查是否为ONNX Runtime GenAI格式 (genai_config.json)
        boolean isGenAIConfig = configFile.getName().equals("genai_config.json") || config.has("model");
        
        String modelType;
        int vocabSize;
        int hiddenSize;
        int numLayers;
        int numHeads;
        
        if (isGenAIConfig) {
            // ONNX Runtime GenAI格式 - 使用库内置解析，仅设置基本信息
            LogManager.logI(TAG, "检测到ONNX Runtime GenAI配置格式，使用库内置解析");
            
            // 对于GenAI格式，只需要设置基本信息，具体配置由ONNX Runtime GenAI库自动处理
            modelType = "genai";
            vocabSize = 32000;  // 默认值，实际值由库从配置中读取
            hiddenSize = 4096;  // 默认值，实际值由库从配置中读取
            numLayers = 32;     // 默认值，实际值由库从配置中读取
            numHeads = 32;      // 默认值，实际值由库从配置中读取
        } else {
            // 传统HuggingFace格式解析
            LogManager.logI(TAG, "解析传统HuggingFace配置格式");
            modelType = config.optString("model_type", "unknown");
            vocabSize = config.optInt("vocab_size", 32000);
            hiddenSize = config.optInt("hidden_size", 4096);
            numLayers = config.optInt("num_hidden_layers", 32);
            numHeads = config.optInt("num_attention_heads", 32);
        }
        
        ModelConfig modelConfig = new ModelConfig(modelType, vocabSize, hiddenSize, numLayers, numHeads);
        // 设置模型路径
        modelConfig.setModelPath(configFile.getParentFile().getAbsolutePath());
        
        // 获取特殊token
        if (isGenAIConfig) {
            // ONNX Runtime GenAI格式 - 特殊token由库自动处理，无需手动解析
            LogManager.logI(TAG, "特殊token将由ONNX Runtime GenAI库自动处理");
        } else {
            // 传统格式的token配置
            if (config.has("bos_token_id")) {
                int bosToken = config.getInt("bos_token_id");
                modelConfig.setBosToken(bosToken);
                LogManager.logD(TAG, "设置BOS token: " + bosToken);
            }
            if (config.has("eos_token_id")) {
                int eosToken = config.getInt("eos_token_id");
                modelConfig.setEosToken(eosToken);
                LogManager.logD(TAG, "设置EOS token: " + eosToken);
            }
        }
        
        // 解析量化相关配置
        parseQuantizationConfig(config, modelConfig, configFile);
        
        // 动态调整maxSequenceLength
        int configuredMaxSeqLength = ConfigManager.getMaxSequenceLength(context);
        modelConfig.setMaxSequenceLength(configuredMaxSeqLength);
        
        LogManager.logD(TAG, String.format("模型配置: 类型=%s, 词汇表大小=%d, 隐藏层大小=%d, 层数=%d, 注意力头数=%d",
            modelType, vocabSize, hiddenSize, numLayers, numHeads));
        
        return modelConfig;
    }
    
    
    /**
     * 根据模型目录内容选择合适的推理引擎
     * @param modelDir 模型目录
     * @return 推理引擎实例
     */
    private InferenceEngine selectInferenceEngine(File modelDir) {
        LogManager.logI(TAG, "Detecting model type: " + modelDir.getAbsolutePath());
        
        // Check for MNN models (both LLM and Diffusion)
        // LocalLLMMNNHandler now handles both LLM and Diffusion models
        if (isDiffusionModel(modelDir)) {
            LogManager.logI(TAG, "Detected MNN DIFFUSION model (Text-to-Image), selecting MNN inference engine");
            return new LocalLLMMNNHandler(context);
        }
        
        if (isMnnModel(modelDir)) {
            LogManager.logI(TAG, "Detected MNN LLM model, selecting MNN inference engine");
            return new LocalLLMMNNHandler(context);
        }
        
        // No compatible model format found
        LogManager.logW(TAG, "No compatible model format found (supported: MNN LLM, MNN Diffusion)");
        return null;
    }
    
    /**
     * Check if directory contains Diffusion model (Text-to-Image)
     * Diffusion models have specific file structure:
     * - text_encoder.mnn (+ .weight)
     * - unet.mnn (+ .weight)
     * - vae_decoder.mnn (+ .weight)
     * - vocab.json, merges.txt (tokenizer files)
     * 
     * @param modelDir Model directory
     * @return true if Diffusion model files exist
     */
    private boolean isDiffusionModel(File modelDir) {
        if (!modelDir.isDirectory()) {
            return false;
        }
        
        // Check for Diffusion-specific model files
        File textEncoder = new File(modelDir, "text_encoder.mnn");
        File unet = new File(modelDir, "unet.mnn");
        File vaeDecoder = new File(modelDir, "vae_decoder.mnn");
        File vocabJson = new File(modelDir, "vocab.json");
        File mergesTxt = new File(modelDir, "merges.txt");
        
        // Log file existence for debugging
        LogManager.logD(TAG, "Checking Diffusion files in: " + modelDir.getAbsolutePath());
        LogManager.logD(TAG, "  text_encoder.mnn: " + (textEncoder.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  unet.mnn: " + (unet.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  vae_decoder.mnn: " + (vaeDecoder.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  vocab.json: " + (vocabJson.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  merges.txt: " + (mergesTxt.exists() ? "✓" : "✗"));
        
        // A Diffusion model must have all three core model files
        boolean isDiffusion = textEncoder.exists() && unet.exists() && vaeDecoder.exists();
        
        if (isDiffusion) {
            LogManager.logI(TAG, "✓ DIFFUSION model detected (Stable Diffusion for Text-to-Image)");
            LogManager.logI(TAG, "  This is NOT an LLM model - use /image command to generate images");
        }
        
        return isDiffusion;
    }
    
    /**
     * Check if directory contains MNN model
     * @param modelDir Model directory
     * @return true if MNN model files exist
     */
    private boolean isMnnModel(File modelDir) {
        if (!modelDir.isDirectory()) {
            LogManager.logW(TAG, "Not a directory: " + modelDir.getAbsolutePath());
            return false;
        }
        
        // Check for required MNN model files
        File llmMnn = new File(modelDir, "llm.mnn");
        File llmWeight = new File(modelDir, "llm.mnn.weight");
        File tokenizer = new File(modelDir, "tokenizer.txt");
        File config = new File(modelDir, "config.json");
        
        // Check for optional multimodal (vision) files
        File visualMnn = new File(modelDir, "visual.mnn");
        File visualWeight = new File(modelDir, "visual.mnn.weight");
        
        // Check for optional embedding file (required for some models like Qwen2.5-VL)
        File embeddingFile = new File(modelDir, "embeddings_bf16.bin");
        
        // Log file existence for debugging
        LogManager.logD(TAG, "Checking MNN files in: " + modelDir.getAbsolutePath());
        LogManager.logD(TAG, "  llm.mnn: " + (llmMnn.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  llm.mnn.weight: " + (llmWeight.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  tokenizer.txt: " + (tokenizer.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  config.json: " + (config.exists() ? "✓" : "✗"));
        LogManager.logD(TAG, "  embeddings_bf16.bin: " + (embeddingFile.exists() ? "✓ (" + formatFileSize(embeddingFile.length()) + ")" : "✗ (optional)"));
        LogManager.logD(TAG, "  visual.mnn: " + (visualMnn.exists() ? "✓ (multimodal)" : "✗ (text-only)"));
        LogManager.logD(TAG, "  visual.mnn.weight: " + (visualWeight.exists() ? "✓ (multimodal)" : "✗ (text-only)"));
        
        boolean isMnn = llmMnn.exists() && llmWeight.exists() && tokenizer.exists() && config.exists();
        
        if (isMnn) {
            boolean isMultimodal = visualMnn.exists() && visualWeight.exists();
            if (isMultimodal) {
                LogManager.logI(TAG, "MNN MULTIMODAL model files found (with vision support)");
            } else {
                LogManager.logI(TAG, "MNN TEXT-ONLY model files found");
            }
        } else {
            LogManager.logW(TAG, "MNN model files incomplete or missing");
        }
        
        return isMnn;
    }
    
    /**
     * Create basic model configuration for MNN
     * @param modelPath Model path
     * @return Model configuration
     */
    private ModelConfig createBasicModelConfig(String modelPath) {
        LogManager.logI(TAG, "Creating basic config for MNN model");
        
        // Create basic configuration
        ModelConfig config = new ModelConfig("mnn", 32000, 4096, 32, 32);
        config.setModelPath(modelPath);
        
        // Set sequence length
        int configuredMaxSeqLength = ConfigManager.getMaxSequenceLength(context);
        config.setMaxSequenceLength(configuredMaxSeqLength);
        
        // Set basic tokens
        config.setBosToken(1);
        config.setEosToken(2);
        
        LogManager.logD(TAG, "MNN basic config created, max sequence length: " + configuredMaxSeqLength);
        
        return config;
    }
    
    /**
     * 解析量化配置参数
     * @param config JSON配置对象
     * @param modelConfig 模型配置对象
     * @param configFile 配置文件
     */
    private void parseQuantizationConfig(JSONObject config, ModelConfig modelConfig, File configFile) {
        try {
            // 检查模型文件名是否包含量化标识
            String modelFileName = configFile.getParentFile().getName().toLowerCase();
            boolean isQuantizedByName = modelFileName.contains("int8") || modelFileName.contains("int4") || 
                                      modelFileName.contains("quant") || modelFileName.contains("quantized");
            
            // 从配置文件中读取量化信息
            boolean isQuantized = config.optBoolean("quantized", isQuantizedByName);
            modelConfig.setQuantized(isQuantized);
            
            if (isQuantized) {
                // 确定量化类型
                String quantType = config.optString("quantization_type", "");
                if (quantType.isEmpty()) {
                    // 从文件名推断量化类型
                    if (modelFileName.contains("int8")) {
                        quantType = "int8";
                    } else if (modelFileName.contains("int4")) {
                        quantType = "int4";
                    } else if (modelFileName.contains("fp16")) {
                        quantType = "fp16";
                    } else {
                        quantType = "int8"; // 默认为int8动态量化
                    }
                }
                modelConfig.setQuantizationType(quantType);
                
                // 读取量化参数
                if (config.has("quantization_config")) {
                    JSONObject quantConfig = config.getJSONObject("quantization_config");
                    
                    // 量化缩放因子
                    float scale = (float) quantConfig.optDouble("scale", 1.0);
                    modelConfig.setQuantizationScale(scale);
                    
                    // 量化零点
                    int zeroPoint = quantConfig.optInt("zero_point", 0);
                    modelConfig.setQuantizationZeroPoint(zeroPoint);
                } else {
                    // 使用默认量化参数
                    if ("int8".equals(quantType)) {
                        modelConfig.setQuantizationScale(0.1f);
                        modelConfig.setQuantizationZeroPoint(128);
                    } else if ("int4".equals(quantType)) {
                        modelConfig.setQuantizationScale(0.2f);
                        modelConfig.setQuantizationZeroPoint(8);
                    }
                }
                
                // KV缓存配置
                boolean enableKVCache = config.optBoolean("enable_kv_cache", false);
                modelConfig.setEnableKVCache(enableKVCache);
                
                // 批处理配置
                int maxBatchSize = config.optInt("max_batch_size", 1);
                modelConfig.setMaxBatchSize(maxBatchSize);
                
                LogManager.logI(TAG, "检测到量化模型，类型: " + quantType + ", 启用优化配置");
            }
            
        } catch (Exception e) {
            LogManager.logW(TAG, "解析量化配置失败，使用默认配置: " + e.getMessage());
            // 默认启用int8动态量化
            modelConfig.setQuantized(true);
            modelConfig.setQuantizationType("int8");
            modelConfig.setQuantizationScale(0.1f);
            modelConfig.setQuantizationZeroPoint(128);
        }
    }


    /**
     * 推理引擎接口
     * 统一不同推理后端的调用方式
     */
    public interface InferenceEngine {
        /**
         * Find the main model file in the given directory
         * This method handles format-specific logic (e.g., .gguf, .mnn)
         * and intelligently selects the correct file (e.g., main model vs mmproj)
         * @param modelDir Model directory
         * @return Absolute path to the main model file, or null if not found
         */
        String findModelFile(File modelDir);
        
        /**
         * 初始化推理引擎
         * @param modelPath 模型路径
         * @param config 模型配置
         * @param callback 可选的流式回调，用于显示加载进度
         * @throws Exception 初始化异常
         */
        void initialize(String modelPath, ModelConfig config, StreamingCallback callback) throws Exception;
        
        /**
         * 执行推理
         * @param prompt 输入提示词
         * @param params 推理参数
         * @param callback 流式回调
         */
        void inference(String prompt, InferenceParams params, StreamingCallback callback);
        
        /**
         * 执行多模态推理（支持图片输入）
         * @param prompt 输入提示词
         * @param imagePaths 图片路径列表（可为null）
         * @param params 推理参数
         * @param callback 流式回调
         */
        void inference(String prompt, java.util.List<String> imagePaths, InferenceParams params, StreamingCallback callback);
        
        /**
         * 停止推理
         */
        void stopInference();
        
        /**
         * 释放资源
         */
        void release();
        
        /**
         * 获取引擎类型
         * @return 引擎类型名称
         */
        String getEngineType();
    }
    
    /**
     * 推理参数类
     * 集中管理推理相关参数
     */
    public static class InferenceParams {
        private int maxTokens = 512;
        private float temperature = 0.7f;
        private int topK = 40;
        private float topP = 0.9f;
        private boolean thinkingMode = false;
        private float repetitionPenalty = 1.1f;
        private int seed = -1; // -1表示随机种子
        
        // Getter和Setter方法
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        
        public int getMaxTokenLength() { return maxTokens; } // 兼容方法
        
        public float getTemperature() { return temperature; }
        public void setTemperature(float temperature) { this.temperature = temperature; }
        
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        
        public float getTopP() { return topP; }
        public void setTopP(float topP) { this.topP = topP; }
        
        public boolean isThinkingMode() { return thinkingMode; }
        public void setThinkingMode(boolean thinkingMode) { this.thinkingMode = thinkingMode; }
        
        public float getRepetitionPenalty() { return repetitionPenalty; }
        public void setRepetitionPenalty(float repetitionPenalty) { this.repetitionPenalty = repetitionPenalty; }
        
        public int getSeed() { return seed; }
        public void setSeed(int seed) { this.seed = seed; }
    }
    
    /**
     * 流式回调接口（简化版）
     * 统一回调接口，简化使用
     */
    public interface StreamingCallback {
        /**
         * 生成新token时调用
         * @param token 生成的token
         */
        void onToken(String token);
        
        /**
         * 推理完成时调用
         * @param fullResponse 完整响应
         */
        void onComplete(String fullResponse);
        
        /**
         * 发生错误时调用
         * @param errorMessage 错误信息
         */
        void onError(String errorMessage);
    }
    
    /**
     * Check if current model supports multimodal (vision) input
     * @return true if model supports images, false if text-only
     */
    public boolean isMultimodalModel() {
        // MNN supports multimodal by default if model has vision encoder
        if (inferenceEngine instanceof LocalLLMMNNHandler) {
            return true; // MNN models can support multimodal
        }
        return false;
    }
    
    /**
     * Get the target image size for the multimodal model
     * @return image size in pixels, or 336 if not available
     */
    public int getModelImageSize() {
        // MNN default image size
        return 336;
    }
    
    /**
     * Get the model architecture name
     * @return architecture name or null if not available
     */
    public String getModelArchitecture() {
        // MNN architecture
        return "mnn";
    }
    
    /**
     * Format file size to human-readable string
     * @param size File size in bytes
     * @return Formatted string (e.g., "594MB")
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + "B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1fKB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.0fMB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2fGB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}

