package com.example.starlocalrag.api;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.example.starlocalrag.ConfigManager;
import com.example.starlocalrag.GlobalStopManager;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.SettingsFragment;
import com.starlocalrag.tokenizers.UnicodeDecoder;
import com.starlocalrag.llamacpp.LlamaCppInference;
import com.starlocalrag.llamacpp.NativeLibraryLoader;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * LlamaCpp本地LLM推理处理器
 * 直接对标LocalLLMOnnxRuntimeGenAIHandler，实现统一的接口
 * 负责管理llama.cpp模型的加载、推理和资源释放
 * 
 * 主要特性：
 * 1. 支持GGUF格式模型的文本生成
 * 2. 流式文本生成和完整的资源管理
 * 3. 实现token输出总量和速率统计
 * 4. 统一的错误处理和日志记录
 * 
 * @author StarLocalRAG Team
 * @version 2.0
 */
public class LocalLLMLlamaCppHandler implements LocalLlmHandler.InferenceEngine {
    private static final String TAG = "LocalLLMLlamaCppHandler";
    
    // 上下文
    private final Context context;
    
    /**
     * 获取上下文
     * @return 上下文对象
     */
    private Context getContext() {
        return context;
    }
    
    // LlamaCpp推理引擎句柄
    private long modelHandle = 0;
    private long contextHandle = 0;
    private ExecutorService executorService;
    
    // 状态管理
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // 线程异常检测和强制终止
    private static final long THREAD_TIMEOUT_MS = -1; // 禁用线程超时检查
    private static final long INFERENCE_TIMEOUT_MS = Long.MAX_VALUE; // 取消推理超时限制，允许长时间推理
    private static final long THREAD_CHECK_INTERVAL_MS = 5000; // 5秒检查间隔
    private static final int MAX_FORCE_TERMINATION_RETRIES = 3; // 最大强制终止重试次数
    
    private volatile Thread currentInferenceThread = null;
    private volatile long inferenceThreadStartTime = 0;
    private volatile long lastThreadHealthCheckTime = 0;
    private final AtomicBoolean threadHealthCheckEnabled = new AtomicBoolean(false); // 禁用线程健康检查
    private final AtomicInteger forceTerminationRetryCount = new AtomicInteger(0);
    private volatile CompletableFuture<?> currentInferenceTask = null;
    
    // 内存池管理 - 预分配策略
    private long preallocatedBatch = 0;
    private long preallocatedSampler = 0;
    private final AtomicBoolean batchInUse = new AtomicBoolean(false);
    private final AtomicBoolean samplerInUse = new AtomicBoolean(false);
    private int preallocatedBatchSize; // 预分配的batch大小，从配置获取
    
    // 生命周期管理
    private final AtomicBoolean shouldKeepModelLoaded = new AtomicBoolean(true);
    private long lastUsedTime = System.currentTimeMillis();
    
    // 统计信息
    private final AtomicInteger totalTokensGenerated = new AtomicInteger(0);
    private final AtomicInteger currentSessionTokens = new AtomicInteger(0);
    private long generationStartTime = 0;
    private long inferenceStartTime = 0;
    
    // 内存监控
    private long memoryBeforeInference = 0;
    private long memoryMaxDuringInference = 0;
    private final Runtime runtime = Runtime.getRuntime();
    private ActivityManager activityManager;
    private ActivityManager.MemoryInfo memoryInfo;
    
    // 模型配置
    private LocalLlmHandler.ModelConfig modelConfig;
    private String currentModelPath;
    private int maxTokens = 512; // 配置参数 - 从ConfigManager获取
    
    // 模型参数缓存
    private LocalLlmHandler.InferenceParams modelParams = null;
    
    // 实际使用的推理参数（用于性能统计显示）
    private volatile LocalLlmHandler.InferenceParams actualUsedParams = null;
    
    public LocalLLMLlamaCppHandler(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        
        // 初始化内存监控
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.memoryInfo = new ActivityManager.MemoryInfo();
        
        // 加载本地库
        try {
            NativeLibraryLoader.loadLibrary("llamacpp_jni");
            LogManager.logI(TAG, "LlamaCpp本地库加载成功");
        } catch (Exception e) {
            LogManager.logE(TAG, "加载llama.cpp本地库失败", e);
            throw new RuntimeException("Failed to load llama.cpp native libraries");
        }
        
        LogManager.logI(TAG, "LocalLLMLlamaCppHandler初始化完成");
    }
    
    @Override
    public void initialize(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
        if (isInitialized.get()) {
            LogManager.logW(TAG, "推理引擎已经初始化");
            return;
        }
        
        LogManager.logI(TAG, "开始初始化LlamaCpp推理引擎: " + modelPath);
        
        this.modelConfig = config;
        this.currentModelPath = modelPath;
        
        // 验证模型文件
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new Exception("模型文件不存在: " + modelPath);
        }
        
        initializeLlamaCpp(modelPath, config);
        
        isInitialized.set(true);
        LogManager.logI(TAG, "LlamaCpp推理引擎初始化成功");
        
        // 模型参数已在initializeLlamaCpp中提取，无需重复调用
    }
    
    /**
     * 初始化LlamaCpp推理引擎
     */
    private void initializeLlamaCpp(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
        LogManager.logI(TAG, "使用LlamaCpp API初始化...");
        
        // 查找GGUF模型文件
        File modelFile = new File(modelPath);
        File ggufFile = findGgufFile(modelFile);
        if (ggufFile == null) {
            throw new Exception("在目录中未找到GGUF模型文件: " + modelPath);
        }
        
        LogManager.logI(TAG, "找到GGUF模型文件: " + ggufFile.getAbsolutePath());
        
        // 从ConfigManager获取配置参数 - 参考OnnxRuntimeGenAI的做法
        int configMaxSeqLength = ConfigManager.getMaxSequenceLength(context);
        int configThreads = ConfigManager.getThreads(context);
        int configMaxNewTokens = ConfigManager.getMaxNewTokens(context);
        boolean configUseGpu = ConfigManager.getBoolean(context, ConfigManager.KEY_USE_GPU, false);
        
        // 线程数配置 - 与OnnxRuntimeGenAI对齐：MIN(CPU核心数, getThreads)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int actualThreads = Math.min(configThreads, availableProcessors);
        
        // 设置参数
        maxTokens = configMaxNewTokens; // 使用最大输出token数作为maxTokens
        int contextSize = configMaxSeqLength; // 上下文大小应该是最大序列长度，不是输出token数
        int nGpuLayers = configUseGpu ? -1 : 0; // 根据配置设置GPU层数
        
        LogManager.logI(TAG, String.format("线程配置 - 用户配置: %d线程, CPU核心: %d, 实际使用: %d线程", 
            configThreads, availableProcessors, actualThreads));
        LogManager.logI(TAG, String.format("GPU配置 - 使用GPU: %s, GPU层数: %d", 
            configUseGpu ? "是" : "否", nGpuLayers));
        
        // 使用新的静态方法初始化
        // 1. 初始化后端
        LlamaCppInference.backend_init();
        
        // 2. 加载模型（使用GPU层数参数）
        modelHandle = LlamaCppInference.load_model_with_gpu(ggufFile.getAbsolutePath(), nGpuLayers);
        if (modelHandle == 0) {
            throw new RuntimeException("模型加载失败: " + ggufFile.getAbsolutePath());
        }
        
        // 3. 创建上下文（传递正确的参数：模型句柄、上下文大小、线程数、GPU层数）
        contextHandle = LlamaCppInference.new_context_with_params(modelHandle, contextSize, actualThreads, nGpuLayers);
        if (contextHandle == 0) {
            LlamaCppInference.free_model(modelHandle);
            throw new RuntimeException("上下文创建失败");
        }
        
        LogManager.logI(TAG, String.format("上下文创建成功 - contextSize: %d, threads: %d, gpuLayers: %d", 
            contextSize, actualThreads, nGpuLayers));
        
        // 4. 预分配batch和sampler - 内存池管理
        preallocateResources();
        
        // 5. 模型加载完成后立即提取并打印模型参数
        extractAndPrintModelParams();
        
        LogManager.logI(TAG, "✓ LlamaCpp引擎初始化完成");
        LogManager.logI(TAG, "模型句柄: " + modelHandle + ", 上下文句柄: " + contextHandle);
        LogManager.logI(TAG, String.format("配置参数 - 最大序列长度: %d, 线程数: %d, 最大输出token数: %d, GPU加速: %s", 
            configMaxSeqLength, actualThreads, configMaxNewTokens, configUseGpu ? "启用" : "禁用"));
        LogManager.logI(TAG, "预分配资源完成 - batch: " + preallocatedBatch + ", sampler: " + preallocatedSampler);
    }
    
    /**
     * 预分配资源 - 内存池管理策略
     */
    private void preallocateResources() {
        try {
            // 使用maxSequenceLength作为批处理大小，统一配置
            preallocatedBatchSize = ConfigManager.getMaxSequenceLength(getContext());
            LogManager.logI(TAG, "使用maxSequenceLength作为批处理大小: " + preallocatedBatchSize);
            
            // 预分配batch - 使用配置的最大batch大小
            preallocatedBatch = LlamaCppInference.new_batch(preallocatedBatchSize, 0, 1);
            if (preallocatedBatch == 0) {
                LogManager.logW(TAG, "预分配batch失败，将使用动态分配");
            } else {
                LogManager.logI(TAG, "预分配batch成功: " + preallocatedBatch + ", 大小: " + preallocatedBatchSize);
            }
            
            // 预分配sampler - 使用默认参数
            preallocatedSampler = LlamaCppInference.new_sampler();
            if (preallocatedSampler == 0) {
                LogManager.logW(TAG, "预分配sampler失败，将使用动态分配");
            } else {
                LogManager.logI(TAG, "预分配sampler成功: " + preallocatedSampler);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "预分配资源失败", e);
        }
    }
    
    /**
     * 获取batch资源 - 内存池管理
     */
    private synchronized long acquireBatch(int requiredSize) {
        // 如果预分配的batch可用且大小足够，则复用
        if (preallocatedBatch != 0 && requiredSize <= preallocatedBatchSize && 
            batchInUse.compareAndSet(false, true)) {
            LogManager.logD(TAG, "复用预分配batch: " + preallocatedBatch);
            return preallocatedBatch;
        }
        
        // 否则动态分配
        try {
            long batch = LlamaCppInference.new_batch(requiredSize, 0, 1);
            LogManager.logD(TAG, "动态分配batch: " + batch + ", 大小: " + requiredSize);
            return batch;
        } catch (Exception e) {
            LogManager.logE(TAG, "动态分配batch失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 释放batch资源 - 内存池管理
     */
    private synchronized void releaseBatch(long batch) {
        if (batch == 0) {
            LogManager.logW(TAG, "尝试释放无效的batch句柄: 0");
            return;
        }
        
        if (batch == preallocatedBatch) {
            // 预分配的batch，标记为可用
            batchInUse.set(false);
            LogManager.logD(TAG, "释放预分配batch回池: " + batch);
        } else {
            // 动态分配的batch，直接释放
            try {
                LlamaCppInference.free_batch(batch);
                LogManager.logD(TAG, "释放动态batch: " + batch);
            } catch (Exception e) {
                LogManager.logE(TAG, "释放动态batch失败: " + batch, e);
            }
        }
    }
    
    /**
     * 模型加载完成后从模型目录文件中提取推理参数
     */
    private void extractAndPrintModelParams() {
        LogManager.logI(TAG, "========== 模型参数提取开始 ==========");
        
        // 从模型目录文件中提取参数
        modelParams = extractParamsFromModelDirectory();
        
        if (modelParams != null) {
            LogManager.logI(TAG, "✓ 成功从模型目录文件提取推理参数:");
            LogManager.logI(TAG, String.format("  • Temperature: %.2f", modelParams.getTemperature()));
            LogManager.logI(TAG, String.format("  • Top-P: %.2f", modelParams.getTopP()));
            LogManager.logI(TAG, String.format("  • Top-K: %d", modelParams.getTopK()));
            LogManager.logI(TAG, String.format("  • Repeat Penalty: %.2f", modelParams.getRepetitionPenalty()));
        } else {
            LogManager.logI(TAG, "✗ 模型目录中未找到推理参数文件，将使用备份配置或默认值");
        }
        
        LogManager.logI(TAG, "========== 模型参数提取完成 ==========");
    }
    
    /**
     * 从模型目录文件中提取推理参数
     * @return 从模型目录文件中提取的推理参数，如果提取失败则返回null
     */
    private LocalLlmHandler.InferenceParams extractParamsFromModelDirectory() {
        try {
            if (currentModelPath == null || currentModelPath.isEmpty()) {
                LogManager.logW(TAG, "当前模型路径为空，无法提取参数");
                return null;
            }
            
            LogManager.logI(TAG, "开始从模型目录提取推理参数，模型路径: " + currentModelPath);
            
            // 确定模型目录
            File modelFile = new File(currentModelPath);
            File modelDir;
            
            if (modelFile.isFile()) {
                // 如果是文件，获取其父目录
                modelDir = modelFile.getParentFile();
            } else {
                // 如果是目录，直接使用
                modelDir = modelFile;
            }
            
            if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
                LogManager.logW(TAG, "模型目录不存在或无效: " + (modelDir != null ? modelDir.getAbsolutePath() : "null"));
                return null;
            }
            
            LogManager.logI(TAG, "模型目录: " + modelDir.getAbsolutePath());
            
            // 读取推理参数
            LocalLlmHandler.InferenceParams params = readInferenceParams(modelDir.getAbsolutePath());
            
            if (params != null) {
                LogManager.logI(TAG, "✓ 成功从模型目录文件提取推理参数");
                return params;
            } else {
                LogManager.logI(TAG, "✗ 模型目录中未找到有效的推理参数文件");
                return null;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "从模型目录提取参数失败", e);
            return null;
        }
    }
    
    /**
     * 从模型目录读取推理参数
     * @param modelDirPath 模型目录路径
     * @return 推理参数对象，如果读取失败返回null
     */
    private static LocalLlmHandler.InferenceParams readInferenceParams(String modelDirPath) {
        if (modelDirPath == null || modelDirPath.isEmpty()) {
            LogManager.logW(TAG, "模型目录路径为空");
            return null;
        }
        
        File modelDir = new File(modelDirPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            LogManager.logW(TAG, "模型目录不存在: " + modelDirPath);
            return null;
        }
        
        // 尝试读取 params 文件
        File paramsFile = new File(modelDir, "params");
        if (paramsFile.exists()) {
            LogManager.logI(TAG, "找到params文件: " + paramsFile.getAbsolutePath());
            return readFromParamsFile(paramsFile);
        }
        
        // 尝试读取 generation_config.json 文件
        File configFile = new File(modelDir, "generation_config.json");
        if (configFile.exists()) {
            LogManager.logI(TAG, "找到generation_config.json文件: " + configFile.getAbsolutePath());
            return readFromJsonFile(configFile);
        }
        
        LogManager.logI(TAG, "模型目录下未找到参数配置文件: " + modelDirPath);
        return null;
    }
    
    /**
     * 从params文件读取参数（支持JSON格式和简单键值对格式）
     */
    private static LocalLlmHandler.InferenceParams readFromParamsFile(File paramsFile) {
        try {
            LogManager.logI(TAG, "开始读取params文件: " + paramsFile.getAbsolutePath());
            String content = readFileContent(paramsFile);
            if (content == null || content.trim().isEmpty()) {
                LogManager.logW(TAG, "params文件内容为空");
                return null;
            }
            
            LogManager.logI(TAG, "params文件内容长度: " + content.length() + " 字符");
            
            // 检测文件格式：如果内容以{开头，尝试作为JSON解析
            String trimmedContent = content.trim();
            if (trimmedContent.startsWith("{")) {
                LogManager.logI(TAG, "检测到JSON格式，使用JSON解析器");
                return readFromJsonContent(content);
            } else {
                LogManager.logI(TAG, "检测到键值对格式，使用键值对解析器");
                return readFromKeyValueContent(content);
            }
            
        } catch (Exception e) {
            LogManager.logW(TAG, "读取params文件失败", e);
            return null;
        }
    }
    
    /**
     * 从JSON内容解析参数
     */
    private static LocalLlmHandler.InferenceParams readFromJsonContent(String content) {
        try {
            JSONObject json = new JSONObject(content);
            LocalLlmHandler.InferenceParams params = new LocalLlmHandler.InferenceParams();
            boolean hasParams = false;
            
            LogManager.logI(TAG, "开始解析JSON格式参数");
            
            // 解析temperature
            if (json.has("temperature")) {
                try {
                    params.setTemperature((float) json.getDouble("temperature"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析temperature: " + json.getDouble("temperature"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析temperature失败", e);
                }
            }
            
            // 解析top_p
            if (json.has("top_p")) {
                try {
                    params.setTopP((float) json.getDouble("top_p"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析top_p: " + json.getDouble("top_p"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析top_p失败", e);
                }
            }
            
            // 解析top_k
            if (json.has("top_k")) {
                try {
                    params.setTopK(json.getInt("top_k"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析top_k: " + json.getInt("top_k"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析top_k失败", e);
                }
            }
            
            // 解析repeat_penalty或repetition_penalty
            String[] repeatKeys = {"repeat_penalty", "repetition_penalty"};
            for (String key : repeatKeys) {
                if (json.has(key)) {
                    try {
                        params.setRepetitionPenalty((float) json.getDouble(key));
                        hasParams = true;
                        LogManager.logI(TAG, "✓ 解析" + key + ": " + json.getDouble(key));
                        break;
                    } catch (Exception e) {
                        LogManager.logW(TAG, "解析" + key + "失败", e);
                    }
                }
            }
            
            LogManager.logI(TAG, "JSON参数解析完成，hasParams: " + hasParams);
            return hasParams ? params : null;
            
        } catch (Exception e) {
            LogManager.logW(TAG, "JSON解析失败", e);
            return null;
        }
    }
    
    /**
     * 从键值对内容解析参数
     */
    private static LocalLlmHandler.InferenceParams readFromKeyValueContent(String content) {
        try {
            LocalLlmHandler.InferenceParams params = new LocalLlmHandler.InferenceParams();
            boolean hasParams = false;
            
            String[] lines = content.split("\n");
            LogManager.logI(TAG, "解析到 " + lines.length + " 行键值对内容");
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // 跳过空行和注释
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    LogManager.logW(TAG, "行格式不正确，跳过: [" + line + "]");
                    continue;
                }
                
                String key = parts[0].trim();
                String value = parts[1].trim();
                LogManager.logI(TAG, "解析键值对: [" + key + "] = [" + value + "]");
                
                try {
                    switch (key.toLowerCase()) {
                        case "temperature":
                        case "temp":
                            params.setTemperature(Float.parseFloat(value));
                            hasParams = true;
                            LogManager.logI(TAG, "✓ 解析temperature: " + value);
                            break;
                        case "top_p":
                        case "topp":
                            params.setTopP(Float.parseFloat(value));
                            hasParams = true;
                            LogManager.logI(TAG, "✓ 解析top_p: " + value);
                            break;
                        case "top_k":
                        case "topk":
                            params.setTopK(Integer.parseInt(value));
                            hasParams = true;
                            LogManager.logI(TAG, "✓ 解析top_k: " + value);
                            break;
                        case "repeat_penalty":
                        case "repetition_penalty":
                            params.setRepetitionPenalty(Float.parseFloat(value));
                            hasParams = true;
                            LogManager.logI(TAG, "✓ 解析repeat_penalty: " + value);
                            break;
                        default:
                            LogManager.logI(TAG, "未识别的参数键: [" + key + "]，跳过");
                            break;
                    }
                } catch (NumberFormatException e) {
                    LogManager.logW(TAG, "解析参数失败 [" + key + "=" + value + "]", e);
                }
            }
            
            LogManager.logI(TAG, "键值对参数解析完成，hasParams: " + hasParams);
            return hasParams ? params : null;
            
        } catch (Exception e) {
            LogManager.logW(TAG, "键值对解析失败", e);
            return null;
        }
    }
    
    /**
     * 从JSON文件读取参数
     */
    private static LocalLlmHandler.InferenceParams readFromJsonFile(File jsonFile) {
        try {
            String content = readFileContent(jsonFile);
            if (content == null || content.trim().isEmpty()) {
                LogManager.logW(TAG, "JSON文件内容为空");
                return null;
            }
            
            JSONObject json = new JSONObject(content);
            LocalLlmHandler.InferenceParams params = new LocalLlmHandler.InferenceParams();
            boolean hasParams = false;
            
            // 解析temperature
            if (json.has("temperature")) {
                try {
                    params.setTemperature((float) json.getDouble("temperature"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析temperature: " + json.getDouble("temperature"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析temperature失败", e);
                }
            }
            
            // 解析top_p
            if (json.has("top_p")) {
                try {
                    params.setTopP((float) json.getDouble("top_p"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析top_p: " + json.getDouble("top_p"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析top_p失败", e);
                }
            }
            
            // 解析top_k
            if (json.has("top_k")) {
                try {
                    params.setTopK(json.getInt("top_k"));
                    hasParams = true;
                    LogManager.logI(TAG, "✓ 解析top_k: " + json.getInt("top_k"));
                } catch (Exception e) {
                    LogManager.logW(TAG, "解析top_k失败", e);
                }
            }
            
            // 解析repeat_penalty或repetition_penalty
            String[] repeatKeys = {"repeat_penalty", "repetition_penalty"};
            for (String key : repeatKeys) {
                if (json.has(key)) {
                    try {
                        params.setRepetitionPenalty((float) json.getDouble(key));
                        hasParams = true;
                        LogManager.logI(TAG, "✓ 解析" + key + ": " + json.getDouble(key));
                        break;
                    } catch (Exception e) {
                        LogManager.logW(TAG, "解析" + key + "失败", e);
                    }
                }
            }
            
            return hasParams ? params : null;
            
        } catch (Exception e) {
            LogManager.logW(TAG, "读取JSON文件失败", e);
            return null;
        }
    }
    
    /**
     * 读取文件内容
     */
    private static String readFileContent(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogManager.logW(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * 已废弃：从模型元数据中提取推理参数的方法
     * 根据用户要求，推理参数不应该存储在模型文件中，因此移除此功能
     */
    @Deprecated
    private LocalLlmHandler.InferenceParams extractParamsFromModelMetadata_DEPRECATED(long modelHandle) {
        // 此方法已废弃，推理参数应该从模型目录的配置文件中读取，而不是从模型元数据中读取
        LogManager.logW(TAG, "extractParamsFromModelMetadata方法已废弃，请使用extractParamsFromModelDirectory");
        return null;
    }
    
    /**
     * 获取sampler资源 - 内存池管理
     */
    private synchronized long acquireSampler(LocalLlmHandler.InferenceParams params) {
        LocalLlmHandler.InferenceParams finalParams = null;
        
        // 检查是否优先使用手动参数
        Context context = getContext();
        boolean priorityManualParams = false;
        if (context != null) {
            priorityManualParams = ConfigManager.getPriorityManualParams(context);
        }
        
        if (priorityManualParams) {
            // 优先手动参数开关打开，直接使用手动参数
            finalParams = getManualInferenceParams();
            LogManager.logI(TAG, "优先手动参数开关已开启，直接使用手动推理参数");
        } else {
            // 参数优先级：模型目录参数 > 手动配置参数
            if (modelParams != null) {
                // 使用模型目录参数（最高优先级）
                finalParams = modelParams;
                LogManager.logI(TAG, "使用模型目录的推理参数（最高优先级）");
            } else {
                // 使用手动配置参数（第二优先级）
                finalParams = getManualInferenceParams();
                LogManager.logI(TAG, "使用手动配置的推理参数（第二优先级）");
            }
        }
        
        // 记录实际使用的参数（用于性能统计显示）
        actualUsedParams = finalParams;
        
        // 如果预分配的sampler可用且使用默认参数，则复用
        if (preallocatedSampler != 0 && finalParams == null && 
            samplerInUse.compareAndSet(false, true)) {
            LogManager.logD(TAG, "复用预分配sampler: " + preallocatedSampler);
            return preallocatedSampler;
        }
        
        // 否则动态分配
        try {
            long sampler = finalParams != null ? 
                LlamaCppInference.new_sampler_with_full_params(
                    finalParams.getTemperature(),
                    finalParams.getTopP(),
                    finalParams.getTopK(),
                    finalParams.getRepetitionPenalty()
                ) : LlamaCppInference.new_sampler();
            LogManager.logD(TAG, "动态分配sampler: " + sampler + 
                (finalParams != null ? String.format(" (temp=%.2f, top_p=%.2f, top_k=%d, repeat_penalty=%.2f)", 
                    finalParams.getTemperature(), finalParams.getTopP(), finalParams.getTopK(), finalParams.getRepetitionPenalty()) : " (默认参数)"));
            return sampler;
        } catch (Exception e) {
            LogManager.logE(TAG, "动态分配sampler失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 释放sampler资源 - 内存池管理
     */
    private synchronized void releaseSampler(long sampler) {
        if (sampler == 0) {
            LogManager.logW(TAG, "尝试释放无效的sampler句柄: 0");
            return;
        }
        
        if (sampler == preallocatedSampler) {
            // 预分配的sampler，标记为可用
            samplerInUse.set(false);
            LogManager.logD(TAG, "释放预分配sampler回池: " + sampler);
        } else {
            // 动态分配的sampler，直接释放
            try {
                LlamaCppInference.free_sampler(sampler);
                LogManager.logD(TAG, "释放动态sampler: " + sampler);
            } catch (Exception e) {
                LogManager.logE(TAG, "释放动态sampler失败: " + sampler, e);
            }
        }
    }
    
    /**
     * 查找GGUF模型文件
     */
    private File findGgufFile(File modelDir) {
        if (modelDir.isFile() && modelDir.getName().toLowerCase().endsWith(".gguf")) {
            return modelDir;
        }
        
        if (modelDir.isDirectory()) {
            File[] files = modelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().toLowerCase().endsWith(".gguf")) {
                        return file;
                    }
                }
            }
        }
        
        return null;
    }
    
    @Override
    public void inference(String prompt, LocalLlmHandler.InferenceParams params, 
                         LocalLlmHandler.StreamingCallback callback) {
        generateText(prompt, params, callback);
    }
    
    public void generateText(String prompt, LocalLlmHandler.InferenceParams params, 
                           LocalLlmHandler.StreamingCallback callback) {
        // 检查全局停止标志
        if (GlobalStopManager.isGlobalStopRequested()) {
            LogManager.logD(TAG, "Detected global stop flag, interrupting text generation");
            if (callback != null) {
                callback.onError("Text generation interrupted by global stop flag");
            }
            return;
        }
        
        if (!isInitialized.get()) {
            String error = "推理引擎未初始化";
            LogManager.logE(TAG, error);
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        if (isGenerating.get()) {
            String error = "正在生成中，请等待当前任务完成";
            LogManager.logW(TAG, error);
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        // 在后台线程执行推理
        currentInferenceTask = CompletableFuture.runAsync(() -> {
            try {
                // 记录当前推理线程
                currentInferenceThread = Thread.currentThread();
                inferenceThreadStartTime = System.currentTimeMillis();
                lastThreadHealthCheckTime = System.currentTimeMillis();
                
                LogManager.logD(TAG, "推理线程启动 [线程ID: " + currentInferenceThread.getId() + "]");
                
                isGenerating.set(true);
                
                // 启动超时监控线程
                startInferenceTimeoutMonitor(callback);
                
                // 推理开始时必须重置停止标志
                // 停止调试日志已移除
                shouldStop.set(false);
                
                // 同时重置JNI层的停止标志
                try {
                    LlamaCppInference.set_should_stop(false);
                    // 停止调试日志已移除
                } catch (Exception e) {
                    LogManager.logE(TAG, "重置JNI停止标志失败: " + e.getMessage(), e);
                }
                generationStartTime = System.currentTimeMillis();
                inferenceStartTime = System.currentTimeMillis();
                totalTokensGenerated.set(0);
                
                // 重置当前会话统计
                currentSessionTokens.set(0);
                
                // 开始内存监控
                startMemoryMonitoring();
                
                LogManager.logI(TAG, "开始LlamaCpp流式生成文本");
                
                // 更新推理参数
                if (params != null) {
                    updateInferenceParameters(params);
                }
                
                StringBuilder fullResponse = new StringBuilder();
                
                generateWithLlamaCpp(prompt, params, callback, fullResponse);
                
            } catch (Exception e) {
                LogManager.logE(TAG, "生成文本异常", e);
                if (callback != null) {
                    callback.onError("生成文本时发生异常: " + e.getMessage());
                }
            } finally {
                isGenerating.set(false);
            }
        });
    }
    
    /**
     * 使用LlamaCpp进行推理
     */
    private void generateWithLlamaCpp(String prompt, LocalLlmHandler.InferenceParams params, LocalLlmHandler.StreamingCallback callback, StringBuilder fullResponse) {
        try {
            // 从ConfigManager获取配置参数
            int maxTokens = ConfigManager.getMaxNewTokens(context);
            
            LogManager.logI(TAG, String.format("推理参数 - MaxTokens: %d", maxTokens));
            
            // 应用chat template和thinking模式处理
            boolean thinkingMode = params != null ? params.isThinkingMode() : true;
            String processedPrompt = applyLlamaCppTemplate(prompt, thinkingMode);
            LogManager.logD(TAG, String.format("应用模板后的prompt长度: %d, thinking模式: %s", 
                processedPrompt.length(), thinkingMode));
            
            // 动态创建批处理大小，支持超长prompt
            // 首先估算prompt的token数量（粗略估算：字符数/4）
            int estimatedTokens = Math.max(512, processedPrompt.length() / 4 + 100);
            // 限制最大batch大小避免内存问题，使用maxSequenceLength
            int maxBatchSize = ConfigManager.getMaxSequenceLength(getContext());
            int batchSize = Math.min(estimatedTokens, maxBatchSize);
            
            LogManager.logI(TAG, String.format("动态batch大小 - 估算tokens: %d, 使用batch大小: %d", estimatedTokens, batchSize));
            
            // 使用内存池管理获取资源
            long batch = acquireBatch(batchSize);
            long sampler = acquireSampler(params);
            
            // 更新最后使用时间
            lastUsedTime = System.currentTimeMillis();
            
            try {
                // 清空KV缓存 - 添加有效性检查
                if (contextHandle != 0) {
                    LlamaCppInference.kv_cache_clear(contextHandle);
                } else {
                    LogManager.logW(TAG, "contextHandle为0，跳过KV缓存清理");
                    if (callback != null) {
                        callback.onError("推理引擎未正确初始化");
                    }
                    return;
                }
                
                // 调试日志已移除
                
                // 初始化完成
                int tokenCount = LlamaCppInference.completion_init(contextHandle, batch, processedPrompt, maxTokens, false);
                if (tokenCount < 0) {
                    LogManager.logE(TAG, "初始化完成失败");
                    return;
                }
                
                // 生成循环
                LlamaCppInference.IntVar currentPos = new LlamaCppInference.IntVar(tokenCount);
                
                for (int i = 0; i < maxTokens && !shouldStop.get() && !GlobalStopManager.isGlobalStopRequested(); i++) {
                    // 检查全局停止标志
                    if (GlobalStopManager.isGlobalStopRequested()) {
                        LogManager.logD(TAG, "Detected global stop flag during generation loop, breaking");
                        break;
                    }
                    
                    if (shouldStop.get()) {
                        // 停止调试日志已移除
                        break;
                    }
                    
                    // 线程健康检查
                    if (!checkThreadHealth()) {
                        LogManager.logW(TAG, "线程健康检查失败，尝试强制终止");
                        if (forceTerminateInferenceThread()) {
                            LogManager.logI(TAG, "推理线程已被强制终止");
                            return;
                        } else {
                            LogManager.logE(TAG, "强制终止推理线程失败，继续执行");
                        }
                    }
                    
                    // 在JNI调用前再次确保JNI层的停止标志已设置
                    if (shouldStop.get()) {
                        try {
                            LlamaCppInference.set_should_stop(true);
                            // 停止调试日志已移除
                        } catch (Exception e) {
                            LogManager.logE(TAG, "设置JNI停止标志失败: " + e.getMessage(), e);
                        }
                        break;
                    }
                    
                    // 注意：这个JNI调用可能会阻塞，无法被中断
                    String token = LlamaCppInference.completion_loop(contextHandle, batch, sampler, maxTokens, currentPos);
                    
                    // JNI调用返回后再次检查停止标志
                    if (shouldStop.get() || GlobalStopManager.isGlobalStopRequested()) {
                        // 停止调试日志已移除
                        break;
                    }
                    
                    // UTF-8容错处理：区分真正的推理结束和字符累积中的临时null
                    if (token == null) {
                        // JNI返回null可能是因为UTF-8字符正在累积中，继续等待下一个token
                        LogManager.logD(TAG, "JNI returned null, continuing to wait for UTF-8 sequence completion");
                        continue;
                    }
                    
                    if (token.isEmpty()) {
                        // 空字符串表示真正的推理结束
                        LogManager.logD(TAG, "Received empty token, inference completed");
                        break;
                    }
                    
                    // 检查是否为长度限制截断提示
                    if ("（已达输出上限，强行截断！）".equals(token)) {
                        LogManager.logI(TAG, "Received length limit truncation notice");
                        // 输出截断提示
                        fullResponse.append(token);
                        if (callback != null) {
                            callback.onToken(token);
                        }
                        // 下一次循环将收到空字符串并正确结束
                        continue;
                    }
                    
                    // 对从LlamaCpp返回的token进行Unicode修复
                    String fixedToken = token;
                    try {
                        // 使用UnicodeDecoder进行多重修复，确保完全解码
                        fixedToken = UnicodeDecoder.decodeUnicodeEscapes(token);
                        // 如果仍然包含转义序列，再次修复
                        while (fixedToken.contains("\\u") && !fixedToken.equals(token)) {
                            token = fixedToken;
                            fixedToken = UnicodeDecoder.decodeUnicodeEscapes(token);
                        }
                        LogManager.logD(TAG, String.format("Token Unicode修复: '%s' -> '%s'", token, fixedToken));
                    } catch (Exception e) {
                        LogManager.logW(TAG, "Token Unicode修复失败: " + e.getMessage());
                        fixedToken = token; // 回退到原始token
                    }
                    
                    fullResponse.append(fixedToken);
                    totalTokensGenerated.incrementAndGet();
                    currentSessionTokens.incrementAndGet();
                    
                    // 更新内存监控
                    updateMemoryMonitoring();
                    
                    if (callback != null) {
                        callback.onToken(fixedToken);
                    }
                }
            } finally {
                // 使用内存池管理释放资源
                releaseBatch(batch);
                releaseSampler(sampler);
            }
            
            if (!shouldStop.get() && callback != null) {
                // 计算统计信息
                long duration = System.currentTimeMillis() - generationStartTime;
                int tokens = totalTokensGenerated.get();
                double tokensPerSecond = tokens > 0 && duration > 0 ? 
                    (tokens * 1000.0) / duration : 0.0;
                
                LogManager.logI(TAG, String.format(
                    "生成完成 - 总tokens: %d, 耗时: %dms, 速率: %.2f tokens/s",
                    tokens, duration, tokensPerSecond));
                
                // 生成性能统计报告并追加到响应中
                String performanceStats = getPerformanceStats();
                String finalResponse = fullResponse.toString() + performanceStats;
                
                // 单独发送统计信息token，确保UI能够正确显示
                callback.onToken(performanceStats);
                
                callback.onComplete(finalResponse);
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "推理失败: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("推理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 备用方法：使用传统流式生成（如果一站式API有问题）
     */
    private void generateWithTraditionalStreaming(String prompt, LocalLlmHandler.InferenceParams params, LocalLlmHandler.StreamingCallback callback, StringBuilder fullResponse) {
        try {
            // 使用底层JNI方法进行流式生成
            int maxTokens = ConfigManager.getMaxNewTokens(context);
            
            // 动态创建批处理大小，支持超长prompt
            // 首先估算prompt的token数量（粗略估算：字符数/4）
            int estimatedTokens = Math.max(512, prompt.length() / 4 + 100);
            // 限制最大batch大小避免内存问题，使用maxSequenceLength
            int maxBatchSize = ConfigManager.getMaxSequenceLength(getContext());
            int batchSize = Math.min(estimatedTokens, maxBatchSize);
            
            LogManager.logI(TAG, String.format("动态batch大小 - 估算tokens: %d, 使用batch大小: %d", estimatedTokens, batchSize));
            
            // 使用内存池管理获取资源
            long batch = acquireBatch(batchSize);
            long sampler = acquireSampler(params);
            
            // 更新最后使用时间
            lastUsedTime = System.currentTimeMillis();
            
            try {
                // 清空KV缓存 - 添加有效性检查
                if (contextHandle != 0) {
                    LlamaCppInference.kv_cache_clear(contextHandle);
                } else {
                    LogManager.logW(TAG, "contextHandle为0，跳过KV缓存清理");
                    if (callback != null) {
                        callback.onError("推理引擎未正确初始化");
                    }
                    return;
                }
                
                // 调试日志已移除
                
                // 初始化完成
                int tokenCount = LlamaCppInference.completion_init(contextHandle, batch, prompt, maxTokens, false);
                if (tokenCount < 0) {
                    LogManager.logE(TAG, "初始化完成失败");
                    if (callback != null) {
                        callback.onError("初始化完成失败");
                    }
                    return;
                }
                
                // 生成循环
                LlamaCppInference.IntVar currentPos = new LlamaCppInference.IntVar(tokenCount);
                
                for (int i = 0; i < maxTokens && !shouldStop.get() && !GlobalStopManager.isGlobalStopRequested(); i++) {
                    // 检查全局停止标志
                    if (GlobalStopManager.isGlobalStopRequested()) {
                        LogManager.logD(TAG, "Detected global stop flag during traditional streaming loop, breaking");
                        break;
                    }
                    
                    if (shouldStop.get()) {
                        // 停止调试日志已移除
                        break;
                    }
                    
                    // 注意：这个JNI调用可能会阻塞，无法被中断
                    String token = LlamaCppInference.completion_loop(contextHandle, batch, sampler, maxTokens, currentPos);
                    
                    // JNI调用返回后再次检查停止标志
                    if (shouldStop.get()) {
                        // 停止调试日志已移除
                        break;
                    }
                    
                    // UTF-8容错处理：区分真正的推理结束和字符累积中的临时null
                    if (token == null) {
                        // JNI返回null可能是因为UTF-8字符正在累积中，继续等待下一个token
                        LogManager.logD(TAG, "Traditional API: JNI returned null, continuing to wait for UTF-8 sequence completion");
                        continue;
                    }
                    
                    if (token.isEmpty()) {
                        // 空字符串表示真正的推理结束
                        LogManager.logD(TAG, "Traditional API: Received empty token, inference completed");
                        break;
                    }
                    
                    // 检查是否为长度限制截断提示
                    if ("（已达输出上限，强行截断！）".equals(token)) {
                        LogManager.logI(TAG, "Traditional API: Received length limit truncation notice");
                        // 输出截断提示
                        fullResponse.append(token);
                        if (callback != null) {
                            callback.onToken(token);
                        }
                        // 下一次循环将收到空字符串并正确结束
                        continue;
                    }
                    
                    // 对从LlamaCpp返回的token进行Unicode修复
                    String fixedToken = token;
                    try {
                        // 使用UnicodeDecoder进行多重修复，确保完全解码
                        fixedToken = UnicodeDecoder.decodeUnicodeEscapes(token);
                        // 如果仍然包含转义序列，再次修复
                        while (fixedToken.contains("\\u") && !fixedToken.equals(token)) {
                            token = fixedToken;
                            fixedToken = UnicodeDecoder.decodeUnicodeEscapes(token);
                        }
                        LogManager.logD(TAG, String.format("传统API Token Unicode修复: '%s' -> '%s'", token, fixedToken));
                    } catch (Exception e) {
                        LogManager.logW(TAG, "传统API Token Unicode修复失败: " + e.getMessage());
                        fixedToken = token; // 回退到原始token
                    }
                    
                    fullResponse.append(fixedToken);
                    totalTokensGenerated.incrementAndGet();
                    currentSessionTokens.incrementAndGet();
                    
                    // 更新内存监控
                    updateMemoryMonitoring();
                    
                    if (callback != null) {
                        callback.onToken(fixedToken);
                    }
                }
                
                // 生成完成
                if (callback != null && !shouldStop.get()) {
                    // 计算统计信息
                    long duration = System.currentTimeMillis() - generationStartTime;
                    int tokens = totalTokensGenerated.get();
                    double tokensPerSecond = tokens > 0 && duration > 0 ? 
                        (tokens * 1000.0) / duration : 0.0;
                    
                    LogManager.logI(TAG, String.format(
                        "传统API生成完成 - 总tokens: %d, 耗时: %dms, 速率: %.2f tokens/s",
                        tokens, duration, tokensPerSecond));
                    
                    // 生成性能统计报告并追加到响应中
                    String performanceStats = getPerformanceStats();
                    String finalResponse = fullResponse.toString() + performanceStats;
                    
                    // 单独发送统计信息token，确保UI能够正确显示
                    callback.onToken(performanceStats);
                    
                    callback.onComplete(finalResponse);
                }
            } finally {
                // 使用内存池管理释放资源
                releaseBatch(batch);
                releaseSampler(sampler);
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "传统API推理失败: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("传统API推理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 应用LlamaCpp的thinking模式处理
     * LlamaCpp内部使用C++分词器处理chat template，这里只处理thinking模式指令
     * @param prompt 原始提示词
     * @param thinkingMode 是否启用思考模式
     * @return 处理后的提示词
     */
    private String applyLlamaCppTemplate(String prompt, boolean thinkingMode) {
        try {
            LogManager.logD(TAG, "处理LlamaCpp thinking模式，思考模式: " + thinkingMode);
            
            // LlamaCpp内部会处理chat template，这里只需要处理thinking模式指令
            String result = addThinkingInstruction(prompt, thinkingMode);
            
            LogManager.logD(TAG, "thinking模式处理完成，处理后长度: " + result.length());
            return result;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "处理thinking模式失败: " + e.getMessage(), e);
            // 回退到原始prompt
            return prompt;
        }
    }
    
    /**
     * 添加thinking模式指令
     * 根据ConfigManager的no_thinking逻辑控制/think或/no_think指令
     * @param text 文本
     * @param thinkingMode 是否启用思考模式
     * @return 处理后的文本
     */
    private String addThinkingInstruction(String text, boolean thinkingMode) {
        if (thinkingMode && !text.contains("/think")) {
            // 启用思考模式时添加/think指令
            return text + "\n/think";
        } else if (!thinkingMode && !text.contains("/no_think")) {
            // 禁用思考模式时添加/no_think指令
            return text + "\n/no_think";
        }
        return text;
    }
    
    /**
     * 更新推理参数
     */
    private void updateInferenceParameters(LocalLlmHandler.InferenceParams params) {
        if (modelHandle != 0 && params != null) {
            try {
                // 参数设置已改为通过采样器实现，在创建采样器时设置参数
                // 注意：LlamaCppInference没有setRepeatPenalty方法，暂时跳过
                // LlamaCppInference.setRepeatPenalty(params.getRepetitionPenalty());
                
                LogManager.logD(TAG, String.format(
                    "更新推理参数 - temp: %.2f, top_p: %.2f, top_k: %d, repeat_penalty: %.2f, thinking: %s",
                    params.getTemperature(), params.getTopP(), params.getTopK(), 
                    params.getRepetitionPenalty(), params.isThinkingMode()));
            } catch (Exception e) {
                LogManager.logW(TAG, "更新推理参数失败", e);
            }
        }
    }
    
    /**
     * 生成文本（同步）
     * @param prompt 输入提示词
     * @return 生成的文本
     */
    public CompletableFuture<String> generateTextAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isInitialized.get()) {
                Log.e(TAG, "推理引擎未初始化");
                return "";
            }
            
            if (isGenerating.getAndSet(true)) {
                Log.w(TAG, "正在生成中，请等待当前任务完成");
                return "";
            }
            
            try {
                Log.i(TAG, "开始生成文本: " + prompt.substring(0, Math.min(prompt.length(), 100)) + "...");
                
                // 使用底层JNI方法进行同步生成
                int maxTokens = ConfigManager.getMaxNewTokens(context);
                
                // 动态创建批处理大小，支持超长prompt
                // 首先估算prompt的token数量（粗略估算：字符数/4）
                int estimatedTokens = Math.max(512, prompt.length() / 4 + 100);
                // 限制最大batch大小避免内存问题，使用maxSequenceLength
                int maxBatchSize = ConfigManager.getMaxSequenceLength(getContext());
                int batchSize = Math.min(estimatedTokens, maxBatchSize);
                
                LogManager.logI(TAG, String.format("动态batch大小 - 估算tokens: %d, 使用batch大小: %d", estimatedTokens, batchSize));
                
                long batch = LlamaCppInference.new_batch(batchSize, 0, 1);
                // 使用默认参数创建采样器
                long sampler = LlamaCppInference.new_sampler_with_params(
                    1.0f,  // 默认温度
                    0.9f,  // 默认top_p
                    40     // 默认top_k
                );
                
                StringBuilder result = new StringBuilder();
                
                try {
                    // 清空KV缓存 - 添加有效性检查
                    if (contextHandle != 0) {
                        LlamaCppInference.kv_cache_clear(contextHandle);
                    } else {
                        LogManager.logW(TAG, "contextHandle为0，跳过KV缓存清理");
                        return "";
                    }
                
                // 调试日志已移除
                
                // 初始化完成
                int tokenCount = LlamaCppInference.completion_init(contextHandle, batch, prompt, maxTokens, false);
                    if (tokenCount < 0) {
                        LogManager.logE(TAG, "初始化完成失败");
                        return "";
                    }
                    
                    // 生成循环
                    LlamaCppInference.IntVar currentPos = new LlamaCppInference.IntVar(tokenCount);
                    
                    for (int i = 0; i < maxTokens; i++) {
                        String token = LlamaCppInference.completion_loop(contextHandle, batch, sampler, maxTokens, currentPos);
                        if (token == null || token.isEmpty()) {
                            break;
                        }
                        
                        // 多重Unicode修复逻辑
                        String originalToken = token;
                        String fixedToken = token;
                        int maxAttempts = 3;
                        
                        for (int attempt = 0; attempt < maxAttempts; attempt++) {
                            String previousToken = fixedToken;
                            fixedToken = UnicodeDecoder.decodeUnicodeEscapes(fixedToken);
                            
                            // 如果没有变化，说明已经完全解码
                            if (fixedToken.equals(previousToken)) {
                                break;
                            }
                        }
                        
                        if (!originalToken.equals(fixedToken)) {
                            LogManager.logD(TAG, "Unicode修复: " + originalToken + " -> " + fixedToken);
                        }
                        
                        result.append(fixedToken);
                    }
                } finally {
                    // 清理资源
                    LlamaCppInference.free_batch(batch);
                    LlamaCppInference.free_sampler(sampler);
                }
                
                String finalResult = result.toString();
                
                if (finalResult == null) {
                    finalResult = "";
                }
                
                Log.i(TAG, "文本生成完成，长度: " + (finalResult != null ? finalResult.length() : 0));
                return finalResult != null ? finalResult : "";
                
            } catch (Exception e) {
                Log.e(TAG, "生成文本异常", e);
                return "";
            } finally {
                isGenerating.set(false);
            }
        }, executorService);
    }
    
    @Override
    public synchronized void stopInference() {
        LogManager.logD(TAG, "[停止调试] 停止标志当前状态: " + shouldStop.get());
        shouldStop.set(true);
        
        // 同时设置JNI层的停止标志
        try {
            boolean currentJniState = LlamaCppInference.get_should_stop();
            LlamaCppInference.set_should_stop(true);
            boolean newJniState = LlamaCppInference.get_should_stop();
            
            if (!newJniState) {
                LogManager.logE(TAG, "[JNI调试] ✗ JNI层停止标志设置失败 - 状态未改变");
            }
            
        } catch (UnsatisfiedLinkError e) {
            LogManager.logE(TAG, "[JNI调试] JNI方法链接错误: " + e.getMessage(), e);
        } catch (NoSuchMethodError e) {
            LogManager.logE(TAG, "[JNI调试] JNI方法不存在: " + e.getMessage(), e);
        } catch (Exception e) {
            LogManager.logE(TAG, "[JNI调试] 设置JNI停止标志失败: " + e.getMessage(), e);
        }
        
        // 检查线程健康状态，如果异常则强制终止
        if (isGenerating.get() && currentInferenceThread != null) {
            LogManager.logD(TAG, "检查推理线程状态以决定是否需要强制终止");
            
            // 等待一段时间看线程是否自然停止
            try {
                Thread.sleep(2000); // 等待2秒
                
                if (isGenerating.get()) {
                    LogManager.logW(TAG, "推理线程未在2秒内停止，检查线程健康状态");
                    
                    if (!checkThreadHealth()) {
                        LogManager.logW(TAG, "线程健康检查失败，执行强制终止");
                        forceTerminateInferenceThread();
                    }
                }
            } catch (InterruptedException e) {
                LogManager.logE(TAG, "等待线程停止时被中断: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        
        // 注意：不要在这里设置isGenerating为false
        // 让推理循环检查shouldStop标志后自然结束，然后在finally块中设置isGenerating为false
    }
    

    

    

    

    
    /**
     * 获取状态信息
     */
    public boolean isInitialized() {
        return modelHandle != 0 && contextHandle != 0 && isInitialized.get();
    }
    
    public boolean isGenerating() {
        return isGenerating.get();
    }
    

    
    public String getModelInfo() {
        if (modelHandle != 0 && contextHandle != 0) {
            return "LlamaCpp推理引擎已初始化";
        }
        return "未初始化";
    }
    
    /**
     * 开始内存监控
     */
    private void startMemoryMonitoring() {
        Runtime runtime = Runtime.getRuntime();
        // 记录推理开始前的内存状态
        memoryBeforeInference = runtime.totalMemory() - runtime.freeMemory();
        memoryMaxDuringInference = memoryBeforeInference;
        
        LogManager.logD(TAG, String.format("内存监控开始 - 应用内存: %d MB", 
            memoryBeforeInference / (1024 * 1024)));
    }
    
    /**
     * 更新内存监控数据
     */
    private void updateMemoryMonitoring() {
        Runtime runtime = Runtime.getRuntime();
        long currentMemory = runtime.totalMemory() - runtime.freeMemory();
        
        if (currentMemory > memoryMaxDuringInference) {
            memoryMaxDuringInference = currentMemory;
        }
    }
    
    /**
     * 获取内存统计信息
     */
    private String getMemoryStats() {
        long memoryIncrease = memoryMaxDuringInference - memoryBeforeInference;
        
        // 获取系统内存信息
        activityManager.getMemoryInfo(memoryInfo);
        long availableMemory = memoryInfo.availMem / (1024 * 1024); // MB
        long totalMemory = memoryInfo.totalMem / (1024 * 1024); // MB
        
        return String.format("\n内存统计:\n" +
            "- 应用推理前: %d MB\n" +
            "- 应用最大占用: %d MB\n" +
            "- 应用推理增量: %d MB\n" +
            "- 系统可用内存: %d MB\n" +
            "- 系统总内存: %d MB",
            memoryBeforeInference / (1024 * 1024),
            memoryMaxDuringInference / (1024 * 1024),
            memoryIncrease / (1024 * 1024),
            availableMemory,
            totalMemory);
    }
    
    /**
     * 计算token生成速率
     */
    private double calculateTokenRate() {
        long elapsedTime = inferenceStartTime > 0 ? System.currentTimeMillis() - inferenceStartTime : 0;
        int tokens = currentSessionTokens.get();
        return tokens > 0 && elapsedTime > 0 ? (tokens * 1000.0) / elapsedTime : 0.0;
    }
    
    /**
     * 估算文本的token数量（简单估算）
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 简单估算：平均每个token约4个字符（包括中英文）
        return Math.max(1, text.length() / 4);
    }
    
    /**
     * 生成完整的性能统计报告
     */
    public String getPerformanceStats() {
        double tokenRate = calculateTokenRate();
        long elapsedTime = inferenceStartTime > 0 ? System.currentTimeMillis() - inferenceStartTime : 0;
        
        // 获取JVM内存信息
        long jvmMaxMemory = runtime.maxMemory(); // JVM最大可用内存
        long jvmTotalMemory = runtime.totalMemory(); // JVM当前总内存
        long jvmUsedMemory = jvmTotalMemory - runtime.freeMemory(); // JVM当前使用内存
        
        // 获取真实的模型大小（通过llama.cpp API）
        long modelSizeBytes = 0;
        if (modelHandle != 0) {
            try {
                modelSizeBytes = LlamaCppInference.model_size(modelHandle);
                LogManager.logD(TAG, "通过llama_model_size获取模型大小: " + (modelSizeBytes / 1024 / 1024) + "MB");
            } catch (Exception e) {
                LogManager.logW(TAG, "获取模型大小失败，使用内存增量估算", e);
            }
        }
        
        // 计算LLM内存使用：优先使用真实模型大小，否则使用内存增量估算
        long llmMemoryUsage;
        if (modelSizeBytes > 0) {
            // 使用真实模型大小 + 上下文内存估算
            long contextMemoryEstimate = 0;
            if (contextHandle != 0) {
                // 估算上下文内存：contextSize * 2 * sizeof(float) * layers（简化估算）
                int contextSize = ConfigManager.getLlamaCppContextSize(context);
                contextMemoryEstimate = contextSize * 2 * 4 * 32; // 假设32层，每个token 2个float（key+value）
            }
            llmMemoryUsage = modelSizeBytes + contextMemoryEstimate;
            LogManager.logD(TAG, "LLM内存使用（模型+上下文）: " + (llmMemoryUsage / 1024 / 1024) + "MB");
        } else {
            // 回退到内存增量估算
            llmMemoryUsage = memoryMaxDuringInference - memoryBeforeInference;
            LogManager.logD(TAG, "LLM内存使用（增量估算）: " + (llmMemoryUsage / 1024 / 1024) + "MB");
        }
        
        // 获取系统内存信息
        activityManager.getMemoryInfo(memoryInfo);
        long systemAvailableMemory = memoryInfo.availMem / (1024 * 1024); // MB
        long systemTotalMemory = memoryInfo.totalMem / (1024 * 1024); // MB
        
        // 简化的性能统计报告格式
        StringBuilder stats = new StringBuilder();
        stats.append("\n\n---\n");
        stats.append(String.format("tokens: %d • Time: %.2fs • Rate: %.2f token/s • JVMMaxMem: %dMB • LLMMaxMem: %dMB • SysMaxMem: %dMB",
            currentSessionTokens.get(),
            elapsedTime / 1000.0,
            tokenRate,
            jvmUsedMemory / (1024 * 1024),
            Math.max(0, llmMemoryUsage / (1024 * 1024)),
            (systemTotalMemory - systemAvailableMemory)
        ));
        
        // 配置信息
        int maxNewTokens = ConfigManager.getMaxNewTokens(context);
        int contextSize = ConfigManager.getLlamaCppContextSize(context);
        int batchSize = ConfigManager.getMaxSequenceLength(context);
        int threads = ConfigManager.getThreads(context);
        boolean useGpu = SettingsFragment.getUseGpu(context);
        
        stats.append(String.format("\n   • maxNewTokens: %d tokens\n", maxNewTokens));
        stats.append(String.format("   • contextSize: %d tokens\n", contextSize));
        stats.append(String.format("   • batchSize: %d tokens\n", batchSize));
        stats.append(String.format("   • threads: %d\n", threads));
        stats.append(String.format("   • GPU: %s\n", useGpu ? "True" : "False"));
        
        // 显示实际使用的推理参数
        LocalLlmHandler.InferenceParams actualParams = getActualInferenceParams();
        if (actualParams != null) {
            stats.append(String.format("   • ggufParam: temp=%.2f, top_p=%.2f, top_k=%d, repeat_penalty=%.2f\n",
                actualParams.getTemperature(), actualParams.getTopP(), 
                actualParams.getTopK(), actualParams.getRepetitionPenalty()));
        }
        
        stats.append("═══════════════════════════════════════\n");
        
        return stats.toString();
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        if (!isInitialized.get()) {
            return "引擎未初始化";
        }
        
        long duration = generationStartTime > 0 ? 
            System.currentTimeMillis() - generationStartTime : 0;
        int tokens = totalTokensGenerated.get();
        double tokensPerSecond = tokens > 0 && duration > 0 ? 
            (tokens * 1000.0) / duration : 0.0;
        
        return String.format(
            "引擎类型: %s\n" +
            "模型路径: %s\n" +
            "是否生成中: %s\n" +
            "总生成tokens: %d\n" +
            "生成速率: %.2f tokens/s",
            getEngineType(),
            currentModelPath != null ? currentModelPath : "未设置",
            isGenerating.get() ? "是" : "否",
            tokens,
            tokensPerSecond
        );
    }
    
    @Override
    public synchronized void release() {
        LogManager.logI(TAG, "释放LlamaCpp推理引擎资源");
        
        // 停止当前生成
        stopInference();
        
        // 释放LlamaCpp句柄
        if (contextHandle != 0) {
            long contextToFree = contextHandle;
            contextHandle = 0; // 先置零，防止重复释放
            
            try {
                LlamaCppInference.free_context(contextToFree);
                LogManager.logI(TAG, "上下文资源释放完成");
            } catch (Exception e) {
                LogManager.logW(TAG, "释放上下文时发生异常: " + contextToFree, e);
            }
        }
        
        if (modelHandle != 0) {
            long modelToFree = modelHandle;
            modelHandle = 0; // 先置零，防止重复释放
            
            try {
                LlamaCppInference.free_model(modelToFree);
                LogManager.logI(TAG, "模型资源释放完成");
            } catch (Exception e) {
                LogManager.logW(TAG, "释放模型时发生异常: " + modelToFree, e);
            }
        }
        
        // 释放预分配资源
        releasePreallocatedResources();
        
        // 关闭线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        isInitialized.set(false);
        LogManager.logI(TAG, "LlamaCpp推理引擎资源释放完成");
    }
    
    @Override
    public String getEngineType() {
        return "LlamaCpp";
    }
    

    
    /**
     * 释放预分配资源
     */
    private synchronized void releasePreallocatedResources() {
        LogManager.logI(TAG, "开始释放预分配资源");
        
        try {
            // 释放预分配batch
            if (preallocatedBatch != 0) {
                long batchToFree = preallocatedBatch;
                preallocatedBatch = 0; // 先置零，防止重复释放
                
                try {
                    LlamaCppInference.free_batch(batchToFree);
                    LogManager.logI(TAG, "预分配batch资源释放完成: " + batchToFree);
                } catch (Exception e) {
                    LogManager.logE(TAG, "释放预分配batch失败: " + batchToFree, e);
                }
            }
            
            // 释放预分配sampler
            if (preallocatedSampler != 0) {
                long samplerToFree = preallocatedSampler;
                preallocatedSampler = 0; // 先置零，防止重复释放
                
                try {
                    LlamaCppInference.free_sampler(samplerToFree);
                    LogManager.logI(TAG, "预分配sampler资源释放完成: " + samplerToFree);
                } catch (Exception e) {
                    LogManager.logE(TAG, "释放预分配sampler失败: " + samplerToFree, e);
                }
            }
            
            // 重置使用标志
            batchInUse.set(false);
            samplerInUse.set(false);
            
            LogManager.logI(TAG, "预分配资源释放完成");
        } catch (Exception e) {
            LogManager.logE(TAG, "释放预分配资源失败", e);
        }
    }
    
    /**
     * 检查推理线程健康状态
     * @return 线程是否健康
     */
    private boolean checkThreadHealth() {
        if (!threadHealthCheckEnabled.get()) {
            return true;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 检查是否需要进行健康检查
        if (currentTime - lastThreadHealthCheckTime < THREAD_CHECK_INTERVAL_MS) {
            return true;
        }
        
        lastThreadHealthCheckTime = currentTime;
        
        // 检查推理线程是否存在且正在运行
        if (currentInferenceThread != null && isGenerating.get()) {
            long threadRunTime = currentTime - inferenceThreadStartTime;
            
            LogManager.logD(TAG, "线程健康检查 [线程ID: " + currentInferenceThread.getId() + 
                           ", 运行时间: " + threadRunTime + "ms, 状态: " + currentInferenceThread.getState() + "]");
            
            // 检查线程是否超时（如果THREAD_TIMEOUT_MS为-1则跳过超时检查）
            if (THREAD_TIMEOUT_MS > 0 && threadRunTime > THREAD_TIMEOUT_MS) {
                LogManager.logW(TAG, "检测到推理线程超时，运行时间: " + threadRunTime + "ms > " + THREAD_TIMEOUT_MS + "ms");
                return false;
            }
            
            // 检查线程状态是否异常
            Thread.State threadState = currentInferenceThread.getState();
            if (threadState == Thread.State.TERMINATED || threadState == Thread.State.BLOCKED) {
                LogManager.logW(TAG, "检测到推理线程状态异常: " + threadState);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 启动推理超时监控
     */
    private void startInferenceTimeoutMonitor(LocalLlmHandler.StreamingCallback callback) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(INFERENCE_TIMEOUT_MS);
                
                // 检查推理是否仍在进行
                if (isGenerating.get() && currentInferenceThread != null) {
                    LogManager.logW(TAG, "推理超时检测：推理已运行 " + INFERENCE_TIMEOUT_MS + "ms，强制终止");
                    
                    // 设置停止标志
                    shouldStop.set(true);
                    
                    try {
                        LlamaCppInference.set_should_stop(true);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "设置JNI停止标志失败: " + e.getMessage());
                    }
                    
                    // 强制终止推理线程
                    if (forceTerminateInferenceThread()) {
                        LogManager.logI(TAG, "推理超时：成功终止推理线程");
                        if (callback != null) {
                            callback.onError("推理超时：推理时间超过 " + (INFERENCE_TIMEOUT_MS / 1000) + " 秒，已自动终止");
                        }
                    } else {
                        LogManager.logE(TAG, "推理超时：无法终止推理线程");
                        if (callback != null) {
                            callback.onError("推理超时：无法终止推理线程，请重启应用");
                        }
                    }
                }
            } catch (InterruptedException e) {
                LogManager.logD(TAG, "推理超时监控线程被中断");
            } catch (Exception e) {
                LogManager.logE(TAG, "推理超时监控异常: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * 强制终止推理线程
     * @return 是否成功终止
     */
    private boolean forceTerminateInferenceThread() {
        LogManager.logW(TAG, "开始强制终止推理线程，重试次数: " + forceTerminationRetryCount.get());
        
        if (forceTerminationRetryCount.get() >= MAX_FORCE_TERMINATION_RETRIES) {
            LogManager.logE(TAG, "强制终止推理线程失败，已达到最大重试次数: " + MAX_FORCE_TERMINATION_RETRIES);
            return false;
        }
        
        forceTerminationRetryCount.incrementAndGet();
        
        try {
            // 1. 首先尝试取消CompletableFuture任务
            if (currentInferenceTask != null && !currentInferenceTask.isDone()) {
                LogManager.logD(TAG, "尝试取消CompletableFuture任务");
                boolean cancelled = currentInferenceTask.cancel(true);
                LogManager.logD(TAG, "CompletableFuture任务取消结果: " + cancelled);
                
                // 等待一段时间看任务是否被取消
                Thread.sleep(1000);
                if (currentInferenceTask.isCancelled()) {
                    LogManager.logI(TAG, "CompletableFuture任务已成功取消");
                    resetInferenceState();
                    return true;
                }
            }
            
            // 2. 尝试设置停止标志并等待自然结束
            LogManager.logD(TAG, "设置停止标志并等待线程自然结束");
            shouldStop.set(true);
            
            // 同时设置JNI层停止标志
            try {
                LlamaCppInference.set_should_stop(true);
            } catch (Exception e) {
                LogManager.logE(TAG, "设置JNI停止标志失败: " + e.getMessage());
            }
            
            // 等待线程自然结束
            for (int i = 0; i < 10; i++) {
                Thread.sleep(500);
                if (!isGenerating.get() || currentInferenceThread == null || 
                    currentInferenceThread.getState() == Thread.State.TERMINATED) {
                    LogManager.logI(TAG, "推理线程已自然结束");
                    resetInferenceState();
                    return true;
                }
            }
            
            // 3. 尝试关闭并重新创建ExecutorService
            LogManager.logW(TAG, "线程未自然结束，尝试重新创建ExecutorService");
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
                
                // 等待ExecutorService关闭
                if (!executorService.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    LogManager.logW(TAG, "ExecutorService未能在3秒内关闭");
                }
            }
            
            // 重新创建ExecutorService
            executorService = Executors.newSingleThreadExecutor();
            LogManager.logI(TAG, "ExecutorService已重新创建");
            
            resetInferenceState();
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "强制终止推理线程时发生异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 重置推理状态
     */
    private void resetInferenceState() {
        LogManager.logD(TAG, "重置推理状态");
        
        isGenerating.set(false);
        shouldStop.set(false);
        currentInferenceThread = null;
        inferenceThreadStartTime = 0;
        currentInferenceTask = null;
        forceTerminationRetryCount.set(0);
        
        // 重置JNI层停止标志
        try {
            LlamaCppInference.set_should_stop(false);
        } catch (Exception e) {
            LogManager.logE(TAG, "重置JNI停止标志失败: " + e.getMessage());
        }
        
        LogManager.logI(TAG, "推理状态已重置");
    }
    
    /**
     * 重置模型记忆 - 清除KV缓存和对话历史
     */
    public void resetModelMemory() {
        // 新对话调试日志已移除
        
        if (!isInitialized.get()) {
            LogManager.logW(TAG, "模型未初始化，无法重置记忆");
            return;
        }
        
        // 新对话调试日志已移除
        
        try {
            // 清除KV缓存 - 添加有效性检查
            if (contextHandle != 0) {
                // 新对话调试日志已移除
                LlamaCppInference.kv_cache_clear(contextHandle);
            } else {
                LogManager.logW(TAG, "contextHandle为0，无法清除KV缓存");
            }
            
            // 重置统计信息
        // 新对话调试日志已移除
        totalTokensGenerated.set(0);
        currentSessionTokens.set(0);
        generationStartTime = 0;
        inferenceStartTime = 0;
        memoryBeforeInference = 0;
        memoryMaxDuringInference = 0;
        // 新对话调试日志已移除
            
            // 更新最后使用时间
            lastUsedTime = System.currentTimeMillis();
            // 新对话调试日志已移除
        } catch (Exception e) {
            LogManager.logE(TAG, "重置模型记忆失败", e);
        }
    }
    
    /**
     * 生命周期管理 - 检查是否应该保持模型加载
     */
    public boolean shouldKeepModelLoaded() {
        return shouldKeepModelLoaded.get();
    }
    
    /**
     * 设置是否保持模型加载
     */
    public void setShouldKeepModelLoaded(boolean keep) {
        shouldKeepModelLoaded.set(keep);
        LogManager.logI(TAG, "设置模型保持加载状态: " + keep);
    }
    
    /**
     * 获取最后使用时间
     */
    public long getLastUsedTime() {
        return lastUsedTime;
    }
    
    /**
     * 检查内存压力并决定是否释放模型
     */
    public boolean shouldReleaseForMemoryPressure() {
        // 获取运行时内存信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        // 计算内存使用率
        double memoryUsageRatio = (double) usedMemory / maxMemory;
        
        // 如果内存使用率超过85%，建议释放模型
        boolean shouldRelease = memoryUsageRatio > 0.85;
        
        if (shouldRelease) {
            LogManager.logW(TAG, String.format(
                "内存压力检测 - 使用率: %.2f%%, 建议释放模型", 
                memoryUsageRatio * 100));
        }
        
        return shouldRelease;
    }
    
    /**
     * 获取手动推理参数
     * @return 手动推理参数对象
     */
    private LocalLlmHandler.InferenceParams getManualInferenceParams() {
        try {
            Context context = getContext();
            if (context == null) {
                LogManager.logW(TAG, "Context为空，无法获取手动推理参数");
                return null;
            }
            
            // 从ConfigManager获取手动推理参数
            float temperature = ConfigManager.getManualTemperature(context);
            float topP = ConfigManager.getManualTopP(context);
            int topK = ConfigManager.getManualTopK(context);
            float repeatPenalty = ConfigManager.getManualRepeatPenalty(context);
            
            // 创建推理参数对象
            LocalLlmHandler.InferenceParams params = new LocalLlmHandler.InferenceParams();
            params.setTemperature(temperature);
            params.setTopP(topP);
            params.setTopK(topK);
            params.setRepetitionPenalty(repeatPenalty);
            
            LogManager.logI(TAG, String.format("获取备份推理参数 - Temperature: %.2f, Top-P: %.2f, Top-K: %d, Repeat Penalty: %.2f",
                temperature, topP, topK, repeatPenalty));
            
            return params;
        } catch (Exception e) {
            LogManager.logE(TAG, "获取备份推理参数失败", e);
            return null;
        }
    }
    
    /**
     * 获取实际使用的推理参数（用于性能统计显示）
     * @return 实际使用的推理参数，逻辑与acquireSampler中的参数选择一致
     */
    private LocalLlmHandler.InferenceParams getActualInferenceParams() {
        // 直接返回实际使用的参数（在acquireSampler中记录）
        return actualUsedParams;
    }
    
    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
    }
}