package com.example.starlocalrag;

import android.content.Context;
import android.util.Log;

import com.example.starlocalrag.api.TokenizerManager;
import com.example.starlocalrag.GlobalStopManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * 处理词嵌入模型的工具类，支持TorchScript和ONNX格式
 * 
 * 主要改进:
 * 1. 智能模型检测：自动识别Qwen、BGE、BERT等不同模型类型
 * 2. 动态输入配置：根据模型实际需要的输入参数自动配置，避免硬编码
 * 3. 智能向量提取：针对不同模型使用合适的向量提取策略（平均池化、CLS token等）
 * 4. 自适应归一化：根据模型类型选择合适的归一化策略
 * 5. 智能维度推断：支持更多模型的向量维度自动识别
 * 
 * 兼容性修复:
 * - 修复Qwen3模型的attention_mask输入问题
 * - 优化不同模型的向量提取和后处理逻辑
 * - 确保向量质量和一致性
 */
public class EmbeddingModelHandler {
    private static final String TAG = "StarLocalRAG_EmbeddingModel";
    private static final int MODEL_LOAD_TIMEOUT_SECONDS = 180;
    
    // ONNX会话状态常量
    private static final int SESSION_STATE_NONE = 0;      // 未初始化
    private static final int SESSION_STATE_LOADING = 1;   // 正在加载
    private static final int SESSION_STATE_READY = 2;     // 已就绪
    private static final int SESSION_STATE_ERROR = 3;     // 错误状态
    
    // 会话重试相关常量
    private static final int MAX_SESSION_RETRY = 3;       // 最大重试次数
    private static final long SESSION_RETRY_DELAY_MS = 500; // 重试间隔
    
    // 模型类型枚举
    public enum ModelType {
        TORCH_SCRIPT,
        ONNX,
        UNKNOWN
    }
    
    private Module torchModel;
    private OrtSession onnxSession;
    private OrtEnvironment ortEnvironment;
    private ModelType modelType;
    private String modelPath;
    private TokenizerManager tokenizer; // 使用TokenizerManager进行分词
    private JSONObject configJson; // 添加模型配置字段
    private String modelName; // 添加模型名称字段
    
    // 是否使用一致性处理
    private boolean useConsistentProcessing = true; // 默认启用一致性处理
    
    // 是否启用调试模式
    private boolean debugMode = false;
    
    // 是否使用 GPU 加速
    private boolean useGpu = false;
    
    // 添加Context引用，用于获取TokenizerManager
    private Context context;
    
    // 嵌入向量维度
    private int embeddingSize = 0;
    
    // 最大序列长度
    private int maxSequenceLength = 512;
    
    // 是否使用平均池化
    private boolean useMeanPooling = true;
    
    // 会话状态变量
    private final Object sessionLock = new Object();      // 会话锁
    private int sessionState = SESSION_STATE_NONE;        // 当前会话状态
    private int sessionRetryCount = 0;                    // 当前重试次数
    private long lastSessionCheckTime = 0;                // 上次会话检查时间
    
    /**
     * 默认构造函数
     */
    public EmbeddingModelHandler() {
        // 默认构造函数
    }
    
    /**
     * 带参数的构造函数
     * @param modelPath 模型文件路径
     * @throws Exception 如果加载失败
     */
    public EmbeddingModelHandler(String modelPath) throws Exception {
        this(null, modelPath, false);
    }
    
    /**
     * 带参数的构造函数，支持 GPU 加速
     * @param modelPath 模型文件路径
     * @param useGpu 是否使用 GPU 加速
     * @throws Exception 如果加载失败
     */
    public EmbeddingModelHandler(String modelPath, boolean useGpu) throws Exception {
        this(null, modelPath, useGpu);
    }
    
    /**
     * 带参数的构造函数，支持 GPU 加速和上下文
     * @param context 应用上下文
     * @param modelPath 模型文件路径
     * @param useGpu 是否使用 GPU 加速
     * @throws Exception 如果加载失败
     */
    public EmbeddingModelHandler(Context context, String modelPath, boolean useGpu) throws Exception {
        // 检查全局停止标志
        if (GlobalStopManager.isGlobalStopRequested()) {
            LogManager.logD(TAG, "检测到全局停止请求，取消模型加载");
            throw new Exception("模型加载被用户取消");
        }
        
        LogManager.logD(TAG, "创建EmbeddingModelHandler实例，context: " + (context != null ? "有效" : "null") + ", 模型路径: " + modelPath);
        this.context = context;
        this.modelPath = modelPath;
        this.modelType = determineModelType(modelPath);
        this.useGpu = useGpu;
        
        try {
            // 再次检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止请求，取消模型加载");
                throw new Exception("模型加载被用户取消");
            }
            
            if (this.modelType == ModelType.TORCH_SCRIPT) {
                // 加载TorchScript模型
                this.torchModel = Module.load(modelPath);
                LogManager.logD(TAG, "TorchScript模型加载成功");
            } else if (this.modelType == ModelType.ONNX) {
                // 加载ONNX模型
                loadOnnxModel(modelPath);
            } else {
                throw new RuntimeException("不支持的模型类型");
            }
        } catch (Exception e) {
            // 如果使用GPU加速失败，尝试降级到CPU模式
            if (useGpu && (e.getMessage().contains("OpenGLRenderer") || 
                          e.getMessage().contains("HwEditorHelperImpl") || 
                          e.getMessage().contains("GPU") || 
                          e.getMessage().contains("gpu"))) {
                LogManager.logW(TAG, "GPU加速失败，降级到CPU模式: " + e.getMessage(), e);
                this.useGpu = false;
                
                // 重新尝试加载模型，但不使用GPU
                if (this.modelType == ModelType.TORCH_SCRIPT) {
                    this.torchModel = Module.load(modelPath);
                    LogManager.logD(TAG, "TorchScript模型使用CPU模式加载成功");
                } else if (this.modelType == ModelType.ONNX) {
                    loadOnnxModel(modelPath);
                }
            } else {
                // 其他错误，直接抛出
                throw e;
            }
        }
    }
    
    /**
     * 根据模型文件路径创建嵌入模型处理器
     * @param modelPath 模型文件的完整路径
     * @param context 应用上下文
     * @return 创建的模型处理器，如果创建失败则返回null
     */
    public static EmbeddingModelHandler create(String modelPath, Context context) {
        LogManager.logD(TAG, "开始创建嵌入模型处理器，模型路径: " + modelPath);
        
        if (modelPath == null || modelPath.isEmpty()) {
            LogManager.logE(TAG, "模型路径为空");
            return null;
        }
        
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                LogManager.logE(TAG, "模型文件不存在: " + modelPath);
                return null;
            }
            
            LogManager.logD(TAG, "模型文件存在，大小: " + modelFile.length() + " 字节");
            
            // 检查是否是目录
            if (modelFile.isDirectory()) {
                LogManager.logD(TAG, "指定的路径是目录，正在查找模型文件...");
                
                // 查找模型文件，支持递归搜索
                File modelFileFound = findModelFileInDirectory(modelFile);
                if (modelFileFound != null) {
                    LogManager.logD(TAG, "找到模型文件: " + modelFileFound.getAbsolutePath() + "，大小: " + modelFileFound.length() + " 字节");
                    
                    // 检查是否存在必要的配置文件
                    File modelDir = modelFileFound.getParentFile();
                    checkRequiredConfigFiles(modelDir);
                    
                    return loadModelWithTimeout(modelFileFound.getAbsolutePath(), context);
                } else {
                    LogManager.logE(TAG, "在目录及其子目录中没有找到模型文件: " + modelPath);
                    return null;
                }
            }
            
            // 如果是文件，直接加载
            return loadModelWithTimeout(modelPath, context);
        } catch (Exception e) {
            LogManager.logE(TAG, "创建嵌入模型处理器失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 检查模型目录中是否存在必要的配置文件
     * @param modelDir 模型目录
     */
    private static void checkRequiredConfigFiles(File modelDir) {
        if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
            LogManager.logW(TAG, "模型目录无效，无法检查配置文件");
            return;
        }
        
        // 检查config.json
        File configFile = new File(modelDir, "config.json");
        if (configFile.exists()) {
            LogManager.logD(TAG, "找到config.json: " + configFile.getAbsolutePath() + ", 大小: " + configFile.length() + " 字节");
        } else {
            LogManager.logW(TAG, "未找到config.json文件");
        }
        
        // 检查tokenizer.json
        File tokenizerFile = new File(modelDir, "tokenizer.json");
        if (tokenizerFile.exists()) {
            LogManager.logD(TAG, "找到tokenizer.json: " + tokenizerFile.getAbsolutePath() + ", 大小: " + tokenizerFile.length() + " 字节");
        } else {
            LogManager.logW(TAG, "未找到tokenizer.json文件");
        }
        
        // 检查special_tokens_map.json
        File specialTokensMapFile = new File(modelDir, "special_tokens_map.json");
        if (specialTokensMapFile.exists()) {
            LogManager.logD(TAG, "找到special_tokens_map.json: " + specialTokensMapFile.getAbsolutePath() + ", 大小: " + specialTokensMapFile.length() + " 字节");
        } else {
            LogManager.logW(TAG, "未找到special_tokens_map.json文件");
        }
        
        // 检查tokenizer_config.json
        File tokenizerConfigFile = new File(modelDir, "tokenizer_config.json");
        if (tokenizerConfigFile.exists()) {
            LogManager.logD(TAG, "找到tokenizer_config.json: " + tokenizerConfigFile.getAbsolutePath() + ", 大小: " + tokenizerConfigFile.length() + " 字节");
        } else {
            LogManager.logW(TAG, "未找到tokenizer_config.json文件");
        }
    }
    
    /**
     * 在目录及其子目录中查找模型文件
     * @param directory 要搜索的目录
     * @return 找到的模型文件，如果没找到则返回null
     */
    private static File findModelFileInDirectory(File directory) {
        if (!directory.isDirectory()) {
            return null;
        }
        
        // 只支持 .pt 和 .onnx 词嵌入模型
        String[] supportedExtensions = {".pt", ".onnx"};
        
        // 打印目录内容，用于调试
        LogManager.logD(TAG, "查找目录内容: " + directory.getAbsolutePath());
        File[] allFiles = directory.listFiles();
        if (allFiles != null) {
            // 检查必要的配置文件是否存在
            boolean hasConfigJson = false;
            boolean hasTokenizerJson = false;
            boolean hasSpecialTokensMap = false;
            boolean hasTokenizerConfig = false;
            
            for (File file : allFiles) {
                LogManager.logD(TAG, "  - " + file.getName() + (file.isDirectory() ? " [目录]" : " [文件, " + file.length() + " 字节]"));
                
                // 检查必要的配置文件
                if (file.getName().equals(AppConstants.FileName.CONFIG_JSON)) {
                    hasConfigJson = true;
                    LogManager.logD(TAG, "找到config.json文件");
                } else if (file.getName().equals(AppConstants.FileName.TOKENIZER_JSON)) {
                    hasTokenizerJson = true;
                    LogManager.logD(TAG, "找到tokenizer.json文件");
                } else if (file.getName().equals(AppConstants.FileName.SPECIAL_TOKENS_MAP_JSON)) {
                    hasSpecialTokensMap = true;
                    LogManager.logD(TAG, "找到special_tokens_map.json文件");
                } else if (file.getName().equals(AppConstants.FileName.TOKENIZER_CONFIG_JSON)) {
                    hasTokenizerConfig = true;
                    LogManager.logD(TAG, "找到tokenizer_config.json文件");
                }
            }
            
            // 记录配置文件状态
            LogManager.logD(TAG, "配置文件检查结果: config.json=" + hasConfigJson + 
                      ", tokenizer.json=" + hasTokenizerJson + 
                      ", special_tokens_map.json=" + hasSpecialTokensMap + 
                      ", tokenizer_config.json=" + hasTokenizerConfig);
            
            // 首先查找model.onnx文件
            for (File file : allFiles) {
                if (file.isFile() && file.getName().equals(AppConstants.FileName.MODEL_ONNX)) {
                    LogManager.logD(TAG, "找到model.onnx文件: " + file.getAbsolutePath());
                    return file;
                }
            }
            
            // 然后查找其他.onnx或.pt文件
            for (File file : allFiles) {
                if (file.isFile()) {
                    String fileName = file.getName().toLowerCase();
                    for (String ext : supportedExtensions) {
                        if (fileName.endsWith(ext)) {
                            LogManager.logD(TAG, "找到模型文件: " + file.getAbsolutePath());
                            return file;
                        }
                    }
                }
            }
            
            // 递归搜索子目录
            for (File file : allFiles) {
                if (file.isDirectory()) {
                    File modelFile = findModelFileInDirectory(file);
                    if (modelFile != null) {
                        return modelFile;
                    }
                }
            }
        } else {
            LogManager.logD(TAG, "  无法列出目录内容");
        }
        
        return null;
    }
    
    /**
     * 使用超时机制加载模型
     * @param modelPath 模型文件路径
     * @param context 应用上下文
     * @return 加载的模型处理器，如果加载失败则返回null
     */
    private static EmbeddingModelHandler loadModelWithTimeout(String modelPath, Context context) {
        LogManager.logD(TAG, "使用超时机制加载模型: " + modelPath);
        
        // 创建线程池
        ExecutorService executor = Executors.newSingleThreadExecutor();
        
        try {
            // 提交加载任务
            Future<EmbeddingModelHandler> future = executor.submit(() -> {
                try {
                    EmbeddingModelHandler handler = new EmbeddingModelHandler(context, modelPath, false);
                    return handler;
                } catch (Exception e) {
                    LogManager.logE(TAG, "模型加载失败: " + e.getMessage(), e);
                    throw new RuntimeException("模型加载失败: " + e.getMessage(), e);
                }
            });
            
            // 等待加载完成，设置超时
            return future.get(MODEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LogManager.logE(TAG, "模型加载超时: " + e.getMessage(), e);
        } catch (Exception e) {
            LogManager.logE(TAG, "模型加载失败: " + e.getMessage(), e);
        } finally {
            // 关闭线程池
            executor.shutdownNow();
        }
        
        return null;
    }
    
    /**
     * 加载ONNX模型
     * @param modelPath 模型路径
     * @return 是否加载成功
     */
    private boolean loadOnnxModel(String modelPath) {
        try {
            // 检查全局停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止请求，取消ONNX模型加载");
                return false;
            }
            
            LogManager.logD(TAG, "开始加载ONNX模型: " + modelPath);
            
            // 检查模型路径
            File modelFile = new File(modelPath);
            
            // 如果是文件，获取其父目录作为模型目录
            if (modelFile.isFile()) {
                // 保存原始模型文件路径用于加载模型
                String modelFilePath = modelPath;
                
                // 获取模型目录（父目录）
                File modelDir = modelFile.getParentFile();
                if (modelDir != null && modelDir.exists()) {
                    // 设置模型目录路径，用于后续加载tokenizer等
                    this.modelPath = modelDir.getAbsolutePath();
                    LogManager.logD(TAG, "模型文件所在目录: " + this.modelPath);
                }
                
                // 使用原始文件路径加载模型
                modelPath = modelFilePath;
            } 
            // 如果是目录，在目录中查找模型文件
            else if (modelFile.isDirectory()) {
                // 保存目录路径
                this.modelPath = modelPath;
                LogManager.logD(TAG, "指定的路径是一个目录，尝试在目录中查找模型文件");
                
                File foundModelFile = findModelFileInDirectory(modelFile);
                if (foundModelFile != null) {
                    modelPath = foundModelFile.getAbsolutePath();
                    LogManager.logD(TAG, "在目录中找到模型文件: " + modelPath);
                } else {
                    LogManager.logE(TAG, "在目录中未找到有效的模型文件: " + modelPath);
                    return false;
                }
            } else {
                LogManager.logE(TAG, "模型路径不存在: " + modelPath);
                return false;
            }
            
            // 创建ONNX运行时环境
            
            // 检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止请求，取消ONNX运行时环境创建");
                return false;
            }
            
            try {
                ortEnvironment = OrtEnvironment.getEnvironment();
                
                if (ortEnvironment == null) {
                    LogManager.logE(TAG, "ortEnvironment为null");
                    throw new RuntimeException("ONNX运行时环境创建失败，返回null");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "创建ONNX运行时环境失败: " + e.getMessage(), e);
                e.printStackTrace();
                throw e;
            }
            
            // 配置会话选项
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            
            // 动态设置线程数为系统CPU核心数的一半
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int numThreads = Math.max(1, availableProcessors / 2); // 至少使用1个线程
            
            sessionOptions.setIntraOpNumThreads(numThreads);
            sessionOptions.setInterOpNumThreads(numThreads);
            LogManager.logD(TAG, "动态设置线程数 - 可用CPU核心: " + availableProcessors + 
                  ", 使用内部线程: " + numThreads + ", 外部线程: " + numThreads);
            
            // 设置优化级别
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            LogManager.logD(TAG, "设置优化级别: ALL_OPT");
            
            // 如果启用GPU，按优先级尝试不同的GPU加速方式
        if (useGpu) {
            boolean gpuEnabled = false;
            LogManager.logD(TAG, "EmbeddingModel: 尝试启用GPU加速...");
            
            // 检查系统信息
            String osVersion = android.os.Build.VERSION.RELEASE;
            String deviceModel = android.os.Build.MODEL;
            String manufacturer = android.os.Build.MANUFACTURER;
            LogManager.logI(TAG, String.format("EmbeddingModel GPU环境检查 - 系统版本: %s, 设备型号: %s, 制造商: %s", 
                osVersion, deviceModel, manufacturer));
            
            // 检查是否为HarmonyOS
            boolean isHarmonyOS = manufacturer.toLowerCase().contains("huawei") || 
                                 manufacturer.toLowerCase().contains("honor") ||
                                 android.os.Build.DISPLAY.toLowerCase().contains("harmony");
            if (isHarmonyOS) {
                LogManager.logI(TAG, "EmbeddingModel: 检测到HarmonyOS系统，将尝试兼容性GPU加速方案");
            }
            
            // 使用反射机制尝试调用可能存在的GPU加速方法
            String[] gpuMethods = {"addNNAPI", "addOpenCL", "addCUDA"};
            String[] gpuNames = {"NNAPI", "OpenCL", "CUDA"};
            String[] gpuDescriptions = {
                "Android神经网络API (适用于Android 8.1+)",
                "开放计算语言 (跨平台并行计算)",
                "NVIDIA CUDA (NVIDIA GPU专用)"
            };
            
            for (int i = 0; i < gpuMethods.length && !gpuEnabled; i++) {
                try {
                    LogManager.logD(TAG, String.format("EmbeddingModel: 尝试启用%s加速 - %s", gpuNames[i], gpuDescriptions[i]));
                    
                    // 尝试通过反射调用方法
                    java.lang.reflect.Method method = ai.onnxruntime.OrtSession.SessionOptions.class.getMethod(gpuMethods[i]);
                    method.invoke(sessionOptions);
                    
                    LogManager.logI(TAG, String.format("EmbeddingModel: ✓ 成功启用%s加速", gpuNames[i]));
                    gpuEnabled = true;
                    
                } catch (NoSuchMethodException e) {
                    // 方法不存在，跳过
                    LogManager.logW(TAG, String.format("EmbeddingModel: ✗ %s加速方法不可用 - ONNX Runtime版本可能不支持此加速方式", gpuNames[i]));
                    
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // 方法调用失败，获取具体原因
                    Throwable cause = e.getCause();
                    String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                    LogManager.logE(TAG, String.format("EmbeddingModel: ✗ %s加速启用失败: %s", gpuNames[i], errorMsg));
                    
                    // 针对不同错误提供具体建议
                    if (errorMsg != null) {
                        if (errorMsg.contains("NNAPI") && errorMsg.contains("not supported")) {
                            LogManager.logW(TAG, "EmbeddingModel建议: NNAPI可能在此设备上不受支持，这在某些华为/荣耀设备上较常见");
                        } else if (errorMsg.contains("OpenCL") && errorMsg.contains("not found")) {
                            LogManager.logW(TAG, "EmbeddingModel建议: OpenCL驱动未找到，可能需要更新GPU驱动或系统版本");
                        } else if (errorMsg.contains("CUDA")) {
                            LogManager.logW(TAG, "EmbeddingModel建议: CUDA仅支持NVIDIA GPU，当前设备可能使用其他GPU");
                        }
                    }
                    
                } catch (Exception e) {
                    // 其他未知错误
                    LogManager.logE(TAG, String.format("EmbeddingModel: ✗ %s加速启用失败 (未知错误): %s", gpuNames[i], e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            }
            
            if (!gpuEnabled) {
                 LogManager.logW(TAG, "EmbeddingModel: 所有GPU加速方式均失败，将使用CPU模式");
                 
                 // 执行GPU诊断（仅在第一次失败时执行，避免重复日志）
                 try {
                     if (context != null) {
                         String diagnosticReport = com.example.starlocalrag.GPUDiagnosticTool.performFullDiagnosis(context);
                         LogManager.logI(TAG, "EmbeddingModel GPU诊断报告:\n" + diagnosticReport);
                     }
                 } catch (Exception e) {
                     LogManager.logE(TAG, "EmbeddingModel GPU诊断失败: " + e.getMessage(), e);
                 }
                 
                 // 提供针对性建议
                 if (isHarmonyOS) {
                     LogManager.logI(TAG, "EmbeddingModel HarmonyOS建议: 1) 确保系统版本支持GPU加速 2) 检查开发者选项中的硬件加速设置 3) 尝试重启应用");
                 } else {
                     LogManager.logI(TAG, "EmbeddingModel通用建议: 1) 检查设备GPU驱动版本 2) 确认应用权限设置 3) 尝试在开发者选项中启用硬件加速");
                 }
                 
                 LogManager.logI(TAG, "EmbeddingModel CPU模式性能提示: 虽然无法使用GPU加速，但CPU模式仍可正常运行，只是速度相对较慢");
             } else {
                 LogManager.logI(TAG, "EmbeddingModel: GPU加速启用成功");
             }
        } else {
            LogManager.logD(TAG, "EmbeddingModel: 未启用GPU加速，使用CPU模式");
        }
            
            // 加载模型
            
            // 检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止请求，取消ONNX模型会话创建");
                return false;
            }
            
            // 检查模型文件是否存在
            File modelFileCheck = new File(modelPath);
            if (!modelFileCheck.exists()) {
                LogManager.logE(TAG, "模型文件不存在: " + modelPath);
                return false;
            }
            
            if (!modelFileCheck.canRead()) {
                LogManager.logE(TAG, "模型文件无法读取: " + modelPath);
                return false;
            }
            
            try {
                onnxSession = ortEnvironment.createSession(modelPath, sessionOptions);
                
                if (onnxSession == null) {
                    LogManager.logE(TAG, "onnxSession为null");
                    return false;
                }
                
            } catch (OrtException e) {
                LogManager.logE(TAG, "加载ONNX模型失败: " + e.getMessage(), e);
                e.printStackTrace();
                return false;
            } catch (Exception e) {
                LogManager.logE(TAG, "创建会话时发生未知异常: " + e.getMessage(), e);
                e.printStackTrace();
                return false;
            }
            
            try {
                // 加载tokenizer和配置
                loadTokenizerAndConfig();
                
                // 设置会话状态为就绪
                synchronized (sessionLock) {
                    sessionState = SESSION_STATE_READY;
                    sessionRetryCount = 0;
                }
            } catch (IOException e) {
                LogManager.logE(TAG, "加载tokenizer和配置失败: " + e.getMessage(), e);
                e.printStackTrace();
                return false;
            }
            
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "加载ONNX模型失败: " + e.getMessage(), e);
            
            // 设置会话状态为错误
            synchronized (sessionLock) {
                sessionState = SESSION_STATE_ERROR;
            }
            
            return false;
        }
    }
    
    /**
     * 确定模型类型
     * @param modelPath 模型路径
     * @return 模型类型
     */
    private ModelType determineModelType(String modelPath) {
        File modelFile = new File(modelPath);
        
        if (!modelFile.exists()) {
            LogManager.logE(TAG, "模型文件不存在: " + modelPath);
            return ModelType.UNKNOWN;
        }
        
        // 检查文件扩展名
        String fileName = modelFile.getName().toLowerCase();
        if (fileName.endsWith(".pt") || fileName.endsWith(".pth") || fileName.endsWith(".ptl")) {
            LogManager.logD(TAG, "检测到TorchScript模型: " + modelPath);
            return ModelType.TORCH_SCRIPT;
        } else if (fileName.endsWith(".onnx")) {
            LogManager.logD(TAG, "检测到ONNX模型: " + modelPath);
            return ModelType.ONNX;
        }
        
        // 检查文件是否是目录
        if (modelFile.isDirectory()) {
            // 检查目录中是否包含ONNX模型文件
            File[] files = modelFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().toLowerCase().endsWith(".onnx")) {
                        String onnxPath = file.getAbsolutePath();
                        LogManager.logD(TAG, "在目录中找到ONNX模型: " + onnxPath);
                        this.modelPath = onnxPath; // 更新模型路径
                        return ModelType.ONNX;
                    }
                }
                
                // 检查是否包含TorchScript模型文件
                for (File file : files) {
                    if (file.getName().toLowerCase().endsWith(".pt") || 
                        file.getName().toLowerCase().endsWith(".pth") || 
                        file.getName().toLowerCase().endsWith(".ptl")) {
                        String torchPath = file.getAbsolutePath();
                        LogManager.logD(TAG, "在目录中找到TorchScript模型: " + torchPath);
                        this.modelPath = torchPath; // 更新模型路径
                        return ModelType.TORCH_SCRIPT;
                    }
                }
            }
        }
        
        // 尝试读取文件头部字节来判断
        try {
            byte[] header = new byte[4];
            try (FileInputStream fis = new FileInputStream(modelFile)) {
                if (fis.read(header) == 4) {
                    // ONNX文件通常以"ONNX"字符串开头
                    if (header[0] == 0x08 && header[1] == 0x00) {
                        LogManager.logD(TAG, "通过文件头检测到ONNX模型");
                        return ModelType.ONNX;
                    }
                    
                    // PyTorch文件通常以特定的魔数开头
                    if (header[0] == 0x80 && header[1] == 0x02) {
                        LogManager.logD(TAG, "通过文件头检测到TorchScript模型");
                        return ModelType.TORCH_SCRIPT;
                    }
                }
            }
        } catch (IOException e) {
            LogManager.logE(TAG, "读取文件头失败: " + e.getMessage());
        }
        
        LogManager.logD(TAG, "无法确定模型类型，默认尝试作为ONNX模型");
        return ModelType.ONNX; // 默认尝试作为ONNX模型
    }
    
    /**
     * 加载tokenizer和配置文件
     * @throws IOException 如果加载失败
     */
    private void loadTokenizerAndConfig() throws IOException {
        // 获取模型文件所在目录
        File modelFile = new File(modelPath);
        File modelDir;
        
        // 如果模型路径直接指向model.onnx文件，则使用其父目录
        if (modelFile.getName().equals(AppConstants.FileName.MODEL_ONNX)) {
            modelDir = modelFile.getParentFile();
            LogManager.logD(TAG, "检测到model.onnx文件，使用其父目录作为模型目录: " + modelDir.getAbsolutePath());
        } else {
            // 如果路径本身就是目录，则直接使用
            modelDir = modelFile.isDirectory() ? modelFile : modelFile.getParentFile();
        }
        
        if (modelDir == null || !modelDir.exists()) {
            throw new IOException("模型目录不存在: " + modelPath);
        }
        
        LogManager.logD(TAG, "使用模型目录: " + modelDir.getAbsolutePath());
        
        // 获取模型目录名称（例如 bge-m3_static_quant_INT8）
        String modelDirName = modelDir.getName();
        
        // 提取模型的基本名称（去除_static_quant后缀）
        String baseModelName = modelDirName;
        if (modelDirName.contains("_static_quant")) {
            baseModelName = modelDirName.split("_static_quant")[0];
            LogManager.logD(TAG, "从目录名称提取模型基本名称: " + baseModelName);
        }
        
        // 首先尝试在当前模型目录中查找 tokenizer.json
        File tokenizerFile = new File(modelDir, "tokenizer.json");
        boolean tokenizerFound = false;
        
        if (tokenizerFile.exists()) {
            LogManager.logD(TAG, "在模型目录中找到tokenizer.json: " + tokenizerFile.getAbsolutePath());
            tokenizerFound = true;
        }
        
        // 如果在当前目录中找不到，尝试在父目录中查找基本模型目录
        if (!tokenizerFound) {
            File embeddingsDir = modelDir.getParentFile();
            if (embeddingsDir != null && embeddingsDir.exists()) {
                LogManager.logD(TAG, "尝试在父目录中查找基本模型目录: " + embeddingsDir.getAbsolutePath());
                
                // 在embeddings目录下查找与基本模型名称相同的目录
                File baseModelDir = new File(embeddingsDir, baseModelName);
                if (baseModelDir.exists() && baseModelDir.isDirectory()) {
                    File baseModelTokenizerFile = new File(baseModelDir, "tokenizer.json");
                    if (baseModelTokenizerFile.exists()) {
                        LogManager.logD(TAG, "在基本模型目录中找到tokenizer.json: " + baseModelTokenizerFile.getAbsolutePath());
                        tokenizerFile = baseModelTokenizerFile;
                        modelDir = baseModelDir;
                        tokenizerFound = true;
                    }
                }
            }
        }
        
        // 如果仍然找不到tokenizer.json，尝试使用默认路径
        if (!tokenizerFound) {
            try {
                // 使用默认的嵌入模型路径
                String embeddingModelPath = "/storage/emulated/0/Download/starragdata/embeddings";
                
                if (embeddingModelPath != null && !embeddingModelPath.isEmpty()) {
                    File embeddingsBaseDir = new File(embeddingModelPath);
                    if (embeddingsBaseDir.exists() && embeddingsBaseDir.isDirectory()) {
                        // 尝试在默认的嵌入模型路径下查找基本模型目录
                        File defaultBaseModelDir = new File(embeddingsBaseDir, baseModelName);
                        if (defaultBaseModelDir.exists() && defaultBaseModelDir.isDirectory()) {
                            File defaultTokenizerFile = new File(defaultBaseModelDir, "tokenizer.json");
                            if (defaultTokenizerFile.exists()) {
                                LogManager.logD(TAG, "在默认的嵌入模型路径中找到tokenizer.json: " + defaultTokenizerFile.getAbsolutePath());
                                tokenizerFile = defaultTokenizerFile;
                                modelDir = defaultBaseModelDir;
                                tokenizerFound = true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "使用默认路径查找tokenizer.json时出错", e);
            }
        }
        
        if (!tokenizerFound) {
            LogManager.logW(TAG, "无法找到tokenizer.json文件，请确保模型目录结构正确");
            throw new IOException("无法找到tokenizer.json文件，请检查模型目录结构");
        }
        
        // 检查tokenizer.json文件的大小和可读性
        if (tokenizerFile.length() == 0) {
            LogManager.logE(TAG, "tokenizer.json文件大小为0: " + tokenizerFile.getAbsolutePath());
            throw new IOException("tokenizer.json文件大小为0，可能已损坏");
        }
        
        if (!tokenizerFile.canRead()) {
            LogManager.logE(TAG, "tokenizer.json文件无法读取: " + tokenizerFile.getAbsolutePath());
            throw new IOException("tokenizer.json文件无法读取，请检查文件权限");
        }
        
        // 获取模型名称，用于设置分词器类型
        String modelName = modelDir.getName();
        // 如果模型名称包含后缀，如"_static_quant_INT8"，则去除后缀
        if (modelName.contains("_static_quant")) {
            modelName = modelName.split("_static_quant")[0];
            LogManager.logD(TAG, "从目录名称提取模型名称: " + modelName);
        }
        
        // 在加载新的tokenizer之前，确保安全释放之前的资源
        if (tokenizer != null) {
            LogManager.logD(TAG, "开始释放之前的tokenizer资源...");
            try {
                // 添加延迟，确保所有操作完成
                Thread.sleep(50);
                tokenizer.close();
                LogManager.logI(TAG, "之前的tokenizer资源释放完成");
            } catch (Exception e) {
                LogManager.logW(TAG, "关闭tokenizer资源失败: " + e.getMessage());
            } finally {
                tokenizer = null;
            }
        }
        
        // 安全重置全局TokenizerManager，确保使用新模型的tokenizer
        if (context != null) {
            LogManager.logD(TAG, "准备重置TokenizerManager以加载新模型...");
            try {
                // 检查全局停止标志，如果正在停止则跳过重置
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logW(TAG, "检测到全局停止标志，跳过TokenizerManager重置");
                    throw new IOException("操作被用户取消");
                }
                
                // 添加延迟，确保之前的操作完全完成
                Thread.sleep(100);
                
                TokenizerManager.resetManager();
                LogManager.logI(TAG, "TokenizerManager重置完成");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("TokenizerManager重置被中断");
            } catch (Exception e) {
                LogManager.logE(TAG, "TokenizerManager重置失败: " + e.getMessage(), e);
                throw new IOException("TokenizerManager重置失败: " + e.getMessage(), e);
            }
        }
        
        // 初始化TokenizerManager
        if (context != null) {
            try {
                TokenizerManager tokenizerManager = TokenizerManager.getInstance(context);
                
                // 获取tokenizer.json文件所在的目录
                File tokenizerDir = tokenizerFile.getParentFile();
                
                // 检查目录是否存在和可读
                if (!tokenizerDir.exists()) {
                    LogManager.logE(TAG, "tokenizer目录不存在: " + tokenizerDir.getAbsolutePath());
                    throw new IOException("tokenizer目录不存在: " + tokenizerDir.getAbsolutePath());
                }
                
                if (!tokenizerDir.canRead()) {
                    LogManager.logE(TAG, "tokenizer目录无法读取: " + tokenizerDir.getAbsolutePath());
                    throw new IOException("tokenizer目录无法读取: " + tokenizerDir.getAbsolutePath());
                }
                
                // 直接传递目录路径给TokenizerManager.initialize
                boolean success = tokenizerManager.initialize(tokenizerDir);
                
                if (success) {
                    this.tokenizer = tokenizerManager;
                    
                    // 设置模型类型，用于正确处理特殊token
                    if (modelName != null && !modelName.isEmpty()) {
                        tokenizerManager.setModelType(modelName);
                    }
                } else {
                    LogManager.logE(TAG, "TokenizerManager初始化失败，无法加载tokenizer");
                    throw new IOException("TokenizerManager初始化失败，无法加载tokenizer");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "TokenizerManager初始化异常: " + e.getMessage(), e);
                e.printStackTrace();
                throw new IOException("初始化TokenizerManager失败: " + e.getMessage(), e);
            }
        } else {
            LogManager.logE(TAG, "Context为null，无法初始TokenizerManager");
            throw new IOException("Context为null，无法初始TokenizerManager");
        }
        
        // 加载配置文件
        File configFile = new File(modelDir, "config.json");
        if (configFile.exists()) {
            try {
                String configContent = readFileContent(configFile);
                configJson = new JSONObject(configContent);
                LogManager.logD(TAG, "模型配置加载成功");
                
                // 从配置中提取模型名称
                if (configJson.has("model_name")) {
                    this.modelName = configJson.getString("model_name");
                    LogManager.logD(TAG, "从配置中提取模型名称: " + this.modelName);
                } else if (configJson.has("model_type")) {
                    this.modelName = configJson.getString("model_type");
                    LogManager.logD(TAG, "从配置中提取模型类型作为名称: " + this.modelName);
                } else if (configJson.has("architectures") && configJson.getJSONArray("architectures").length() > 0) {
                    this.modelName = configJson.getJSONArray("architectures").getString(0);
                    LogManager.logD(TAG, "从配置中提取架构作为名称: " + this.modelName);
                }
                
                // 提取嵌入大小
                if (configJson.has("hidden_size")) {
                    embeddingSize = configJson.getInt("hidden_size");
                    LogManager.logD(TAG, "从配置中提取嵌入大小: " + embeddingSize);
                } else if (configJson.has("dim")) {
                    embeddingSize = configJson.getInt("dim");
                    LogManager.logD(TAG, "从配置中提取嵌入大小(dim): " + embeddingSize);
                }
                
                // 提取最大序列长度
                if (configJson.has("max_position_embeddings")) {
                    maxSequenceLength = configJson.getInt("max_position_embeddings");
                    LogManager.logD(TAG, "从配置中提取最大序列长度: " + maxSequenceLength);
                }
            } catch (JSONException e) {
                LogManager.logE(TAG, "解析配置文件失败: " + e.getMessage(), e);
            }
        } else {
            LogManager.logW(TAG, "配置文件不存在: " + configFile.getAbsolutePath());
        }
    }
    
    /**
     * 尝试使用Java实现的Tokenizer初始化
     * 当TokenizerManager无法初始化时使用此方法作为备用
     * @param modelDir 模型目录
     */
    private void tryInitializeWithJavaTokenizer(File modelDir) {
        try {
            LogManager.logD(TAG, "尝试使用Java实现的Tokenizer初始化");
            
            // 尝试从应用程序获取Context
            if (context == null) {
                Context appContext = getApplicationContext();
                if (appContext != null) {
                    LogManager.logD(TAG, "成功从应用程序获取Context");
                    this.context = appContext;
                    
                    // 再次尝试使用TokenizerManager
                    TokenizerManager tokenizerManager = TokenizerManager.getInstance(appContext);
                    boolean success = tokenizerManager.initialize(modelDir);
                    if (success) {
                        this.tokenizer = tokenizerManager;
                        LogManager.logD(TAG, "成功使用应用Context初始化TokenizerManager");
                        return;
                    }
                }
            }
            
            // 如果仍然无法使用TokenizerManager，尝试直接使用HuggingfaceTokenizer
            // 先检查模型目录下是否有tokenizer.json文件
            File tokenizerFile = new File(modelDir, "tokenizer.json");
            
            // 如果模型目录下没有tokenizer.json，尝试在父目录下查找
            if (!tokenizerFile.exists() && modelDir.getName().equals(AppConstants.FileName.MODEL_ONNX)) {
                File parentDir = modelDir.getParentFile();
                if (parentDir != null && parentDir.exists()) {
                    tokenizerFile = new File(parentDir, "tokenizer.json");
                    LogManager.logD(TAG, "在父目录中查找tokenizer.json: " + tokenizerFile.getAbsolutePath());
                }
            }
            
            // 如果模型目录名包含"_static_quant"等后缀，尝试去除后缀后的目录
            if (!tokenizerFile.exists() && modelDir.getName().contains("_static_quant")) {
                String dirName = modelDir.getName().split("_static_quant")[0];
                
                // 尝试在多个可能的位置查找
                List<File> possibleDirs = new ArrayList<>();
                
                // 1. 在当前目录的父目录中查找
                if (modelDir.getParentFile() != null) {
                    possibleDirs.add(new File(modelDir.getParentFile(), dirName));
                }
                
                // 2. 在当前目录的父目录的父目录中查找
                if (modelDir.getParentFile() != null && modelDir.getParentFile().getParentFile() != null) {
                    possibleDirs.add(new File(modelDir.getParentFile().getParentFile(), dirName));
                }
                
                // 3. 在嵌入模型目录的父目录中查找
                File embeddingsDir = new File(modelDir.getParentFile().getParentFile(), "embeddings");
                if (embeddingsDir.exists()) {
                    possibleDirs.add(new File(embeddingsDir, dirName));
                }
                
                // 遍历所有可能的目录
                for (File dir : possibleDirs) {
                    if (dir.exists()) {
                        File candidateFile = new File(dir, "tokenizer.json");
                        if (candidateFile.exists()) {
                            tokenizerFile = candidateFile;
                            LogManager.logD(TAG, "在去除后缀的目录中找到tokenizer.json: " + tokenizerFile.getAbsolutePath());
                            break;
                        }
                    }
                }
            }
            
            // 如果还是找不到，尝试在embeddings目录下查找
            if (!tokenizerFile.exists()) {
                // 尝试在各种可能的位置查找embeddings目录
                List<File> possibleEmbeddingsDirs = new ArrayList<>();
                
                // 1. 在当前目录的父目录中查找
                if (modelDir.getParentFile() != null) {
                    possibleEmbeddingsDirs.add(new File(modelDir.getParentFile(), "embeddings"));
                }
                
                // 2. 在当前目录的父目录的父目录中查找
                if (modelDir.getParentFile() != null && modelDir.getParentFile().getParentFile() != null) {
                    possibleEmbeddingsDirs.add(new File(modelDir.getParentFile().getParentFile(), "embeddings"));
                }
                
                // 遍历所有可能的embeddings目录
                for (File dir : possibleEmbeddingsDirs) {
                    if (dir.exists()) {
                        // 在embeddings目录下直接查找tokenizer.json
                        File candidateFile = new File(dir, "tokenizer.json");
                        if (candidateFile.exists()) {
                            tokenizerFile = candidateFile;
                            LogManager.logD(TAG, "在embeddings目录中找到tokenizer.json: " + tokenizerFile.getAbsolutePath());
                            break;
                        }
                        
                        // 如果没有直接找到，遍历embeddings目录下的所有子目录
                        File[] subdirs = dir.listFiles(File::isDirectory);
                        if (subdirs != null) {
                            for (File subdir : subdirs) {
                                candidateFile = new File(subdir, "tokenizer.json");
                                if (candidateFile.exists()) {
                                    tokenizerFile = candidateFile;
                                    LogManager.logD(TAG, "在embeddings的子目录中找到tokenizer.json: " + tokenizerFile.getAbsolutePath());
                                    break;
                                }
                            }
                            if (tokenizerFile.exists()) {
                                break;
                            }
                        }
                    }
                }
            }
            
            if (tokenizerFile.exists()) {
                LogManager.logD(TAG, "找到tokenizer.json文件，尝试直接加载HuggingfaceTokenizer: " + tokenizerFile.getAbsolutePath());
                try {
                    // 使用正确的构造函数，第二个参数表示是从文件加载
                    com.starlocalrag.tokenizers.HuggingfaceTokenizer huggingfaceTokenizer = 
                        new com.starlocalrag.tokenizers.HuggingfaceTokenizer(tokenizerFile.getAbsolutePath(), true);
                    
                    // 初始化TokenizerManager
                    TokenizerManager tokenizerManager = TokenizerManager.getInstance(context);
                    if (tokenizerManager.initialize(tokenizerFile.getParentFile())) {
                        this.tokenizer = tokenizerManager;
                        LogManager.logD(TAG, "成功初始化TokenizerManager");
                    } else {
                        LogManager.logE(TAG, "初始化TokenizerManager失败");
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "初始化HuggingfaceTokenizer失败: " + e.getMessage(), e);
                }
            } else {
                LogManager.logE(TAG, "在多个可能的位置都未找到tokenizer.json文件");
                LogManager.logE(TAG, "已尝试的路径: " + modelDir.getAbsolutePath() + "/tokenizer.json");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "备用初始化tokenizer失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 加载本地tokenizer（已废弃，由loadTokenizerAndConfig替代）
     * @param modelDir 模型目录
     * @throws IOException 如果加载失败
     */
    @Deprecated
    private void loadLocalTokenizer(File modelDir) throws IOException {
        throw new IOException("此方法已废弃，请使用loadTokenizerAndConfig");
    }
    
    /**
     * 使用ONNX模型生成嵌入向量
     * @param text 输入文本
     * @return 嵌入向量
     * @throws Exception 如果生成失败
     */
    private float[] generateEmbeddingWithOnnx(String text) throws Exception {
        if (onnxSession == null) {
            throw new IllegalStateException("ONNX会话未初始化");
        }
        
        // 检查并恢复会话状态
        if (!checkAndRecoverOnnxSession()) {
            throw new IllegalStateException("ONNX会话不可用且无法恢复");
        }
        
        // 对文本进行分词
        long[][] tokenizeResult;
        try {
            tokenizeResult = tokenizeText(text);
        } catch (IOException e) {
            throw new Exception("分词失败: " + e.getMessage(), e);
        }
        
        if (tokenizeResult.length == 0 || tokenizeResult[0].length == 0) {
            throw new IllegalArgumentException("分词结果为空");
        }
        
        long[] inputIds = tokenizeResult[0];
        
        // 限制序列长度
        if (inputIds.length > maxSequenceLength) {
            debugLog("输入序列长度超过最大限制，将被截断: " + inputIds.length + " -> " + maxSequenceLength);
            long[] truncatedIds = new long[maxSequenceLength];
            System.arraycopy(inputIds, 0, truncatedIds, 0, maxSequenceLength - 1);
            truncatedIds[maxSequenceLength - 1] = 2; // [SEP] token
            inputIds = truncatedIds;
        }
        
        // 创建注意力掩码数组，全部填充为1
        long[] attentionMask = new long[inputIds.length];
        Arrays.fill(attentionMask, 1L);
        
        // 创建token_type_ids数组，对于单句文本全部填充为0
        long[] tokenTypeIds = new long[inputIds.length];
        Arrays.fill(tokenTypeIds, 0L);
        
        // 记录输入张量形状和示例
        LogManager.logD(TAG, "输入张量形状: [1, " + inputIds.length + "]");
        LogManager.logD(TAG, "输入ID示例: " + Arrays.toString(Arrays.copyOfRange(inputIds, 0, Math.min(10, inputIds.length))));
        LogManager.logD(TAG, "输入数据类型: INT64 (与PC端保持一致)");
        
        // 记录推理开始时间
        long startTime = System.currentTimeMillis();
        
        try {
            // 将数组转换为LongBuffer (对应INT64类型)
            LongBuffer inputIdsBuffer = LongBuffer.allocate(inputIds.length);
            inputIdsBuffer.put(inputIds);
            inputIdsBuffer.rewind();
            
            LongBuffer attentionMaskBuffer = LongBuffer.allocate(attentionMask.length);
            attentionMaskBuffer.put(attentionMask);
            attentionMaskBuffer.rewind();
            
            LongBuffer tokenTypeIdsBuffer = LongBuffer.allocate(tokenTypeIds.length);
            tokenTypeIdsBuffer.put(tokenTypeIds);
            tokenTypeIdsBuffer.rewind();
            
            // 设置输入形状
            long[] inputShape = new long[]{1, inputIds.length};
            
            // 创建输入张量 - 确保使用INT64类型
            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, inputIdsBuffer, inputShape);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, attentionMaskBuffer, inputShape);
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(ortEnvironment, tokenTypeIdsBuffer, inputShape);
            
            // 记录输入张量信息
            LogManager.logD(TAG, "输入张量类型 - input_ids: " + inputIdsTensor.getInfo().type + ", attention_mask: " + attentionMaskTensor.getInfo().type + ", token_type_ids: " + tokenTypeIdsTensor.getInfo().type);
            
            // 准备输入数据 - 根据模型实际需要的输入动态提供
            Map<String, OnnxTensor> inputs = new HashMap<>();
            
            // 获取模型实际需要的输入名称
            Map<String, NodeInfo> inputInfo = onnxSession.getInputInfo();
            LogManager.logD(TAG, "模型需要的输入参数: " + inputInfo.keySet().toString());
            
            // 智能检测模型输入需求，根据实际模型输入自动配置
            // 优先根据模型实际需要的输入名称来决定提供哪些输入
            boolean isQwenModel = modelName != null && modelName.toLowerCase().contains("qwen");
            boolean isBertLikeModel = modelName != null && (modelName.toLowerCase().contains("bge") || 
                                                           modelName.toLowerCase().contains("bert") ||
                                                           modelName.toLowerCase().contains("sentence"));
            
            LogManager.logD(TAG, String.format("模型类型检测 - Qwen: %b, BERT-like: %b, 模型名称: %s", 
                isQwenModel, isBertLikeModel, modelName));
            
            // 根据模型实际需要的输入动态提供，而不是硬编码
            if (inputInfo.containsKey("input_ids")) {
                inputs.put("input_ids", inputIdsTensor);
                LogManager.logD(TAG, "已添加input_ids输入");
            }
            
            if (inputInfo.containsKey("attention_mask")) {
                inputs.put("attention_mask", attentionMaskTensor);
                LogManager.logD(TAG, "已添加attention_mask输入");
            }
            
            if (inputInfo.containsKey("token_type_ids")) {
                inputs.put("token_type_ids", tokenTypeIdsTensor);
                LogManager.logD(TAG, "已添加token_type_ids输入");
            }
            
            LogManager.logD(TAG, String.format("最终提供的输入: %s (模型需要: %s)", 
                inputs.keySet().toString(), inputInfo.keySet().toString()));
            
            // 检查是否提供了正确的输入数量
            if (inputs.size() < inputInfo.size()) {
                LogManager.logW(TAG, "提供的输入数量(" + inputs.size() + ")少于模型需要的数量(" + inputInfo.size() + ")");
                // 尝试用默认值填充缺失的输入
                for (Map.Entry<String, NodeInfo> entry : inputInfo.entrySet()) {
                    if (!inputs.containsKey(entry.getKey())) {
                        LogManager.logW(TAG, "缺少输入: " + entry.getKey());
                        // 尝试用默认值填充
                        // 由于无法直接访问NodeInfo的type属性和OnnxTensor.TensorType枚举，
                        // 我们移除这部分可能引起编译错误的代码
                        LogManager.logW(TAG, "无法为输入提供默认值: " + entry.getKey() + "，请检查模型输入要求");
                    }
                }
            }
            
            // 执行模型推理
            OrtSession.Result result = onnxSession.run(inputs);
            
            // 获取输出张量，通常是embedding
            OnnxTensor outputTensor = (OnnxTensor) result.get(0);
            
            // 提取嵌入向量数据 - 处理不同维度的输出
            Object outputValue = outputTensor.getValue();
            float[] embedding;
            
            if (outputValue instanceof float[][][]) {
                // 三维输出：[batch_size, sequence_length, hidden_size]
                float[][][] embeddingData3D = (float[][][]) outputValue;
                LogManager.logD(TAG, "检测到三维输出，形状: [" + embeddingData3D.length + ", " + 
                    embeddingData3D[0].length + ", " + embeddingData3D[0][0].length + "]");
                
                // 智能选择向量提取策略
                if (isQwenModel) {
                    // Qwen模型：使用平均池化或最后一个有效token
                    if (useMeanPooling) {
                        // 计算所有token的平均值（排除padding）
                        embedding = new float[embeddingData3D[0][0].length];
                        int validTokens = Math.min(inputIds.length, embeddingData3D[0].length);
                        for (int i = 0; i < validTokens; i++) {
                            for (int j = 0; j < embedding.length; j++) {
                                embedding[j] += embeddingData3D[0][i][j];
                            }
                        }
                        for (int j = 0; j < embedding.length; j++) {
                            embedding[j] /= validTokens;
                        }
                        LogManager.logD(TAG, "Qwen模型使用平均池化，有效token数: " + validTokens);
                    } else {
                        // 使用最后一个有效token
                        int lastValidIndex = Math.min(inputIds.length - 1, embeddingData3D[0].length - 1);
                        embedding = embeddingData3D[0][lastValidIndex];
                        LogManager.logD(TAG, "Qwen模型使用最后一个有效token，索引: " + lastValidIndex);
                    }
                } else {
                    // BERT类模型：使用[CLS] token（第一个token）
                    embedding = embeddingData3D[0][0];
                    LogManager.logD(TAG, "BERT类模型使用[CLS] token作为嵌入向量");
                }
                
            } else if (outputValue instanceof float[][]) {
                // 二维输出：[batch_size, hidden_size]
                float[][] embeddingData2D = (float[][]) outputValue;
                LogManager.logD(TAG, "检测到二维输出，形状: [" + embeddingData2D.length + ", " + 
                    embeddingData2D[0].length + "]");
                embedding = embeddingData2D[0]; // 获取第一个（也是唯一的）样本的向量
                
            } else if (outputValue instanceof byte[][][]) {
                // 三维字节输出：[batch_size, sequence_length, hidden_size]
                byte[][][] embeddingData3D = (byte[][][]) outputValue;
                LogManager.logD(TAG, "检测到三维字节输出，形状: [" + embeddingData3D.length + ", " + 
                    embeddingData3D[0].length + ", " + embeddingData3D[0][0].length + "]");
                
                // 转换字节数据为浮点数据
                float[][] floatData = new float[embeddingData3D[0].length][];
                for (int i = 0; i < embeddingData3D[0].length; i++) {
                    floatData[i] = new float[embeddingData3D[0][i].length];
                    for (int j = 0; j < embeddingData3D[0][i].length; j++) {
                        floatData[i][j] = (float) embeddingData3D[0][i][j];
                    }
                }
                
                // 取第一个batch的第一个token（[CLS]）
                embedding = floatData[0];
                
            } else if (outputValue instanceof byte[][]) {
                // 二维字节输出：[batch_size, hidden_size]
                byte[][] embeddingData2D = (byte[][]) outputValue;
                LogManager.logD(TAG, "检测到二维字节输出，形状: [" + embeddingData2D.length + ", " + 
                    embeddingData2D[0].length + "]");
                
                // 转换字节数据为浮点数据
                embedding = new float[embeddingData2D[0].length];
                for (int i = 0; i < embeddingData2D[0].length; i++) {
                    embedding[i] = (float) embeddingData2D[0][i];
                }
                
            } else {
                throw new IllegalStateException("不支持的输出张量类型: " + outputValue.getClass().getName());
            }
            
            // 记录向量信息
            LogManager.logD(TAG, "原始嵌入向量维度: " + embedding.length);
            LogManager.logD(TAG, "嵌入向量样例 (前5个值): " + 
                Arrays.toString(Arrays.copyOfRange(embedding, 0, Math.min(5, embedding.length))));
            
            // 记录生成时间
            long endTime = System.currentTimeMillis();
            LogManager.logD(TAG, "生成嵌入向量耗时: " + (endTime - startTime) + "ms");
            
            // 记录原始向量信息（不进行归一化，留给上层方法处理）
            LogManager.logD(TAG, "原始嵌入向量维度: " + embedding.length);
            LogManager.logD(TAG, "嵌入向量样例 (前5个值): " + 
                Arrays.toString(Arrays.copyOfRange(embedding, 0, Math.min(5, embedding.length))));
            
            // 创建一个副本，避免在关闭张量后访问其内存
            float[] embeddingCopy = Arrays.copyOf(embedding, embedding.length);
            
            // 清空输入映射
            inputs.clear();
            
            return embeddingCopy;
        } catch (Exception e) {
            LogManager.logE(TAG, "生成嵌入向量失败: " + e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 生成文本的嵌入向量
     * @param text 输入文本
     * @return 嵌入向量（浮点数数组）
     * @throws Exception 如果生成嵌入失败
     */
    public float[] generateEmbedding(String text) throws Exception {
        LogManager.logD(TAG, "开始生成嵌入向量，文本长度: " + text.length());
        
        // 检查全局停止标志
        if (GlobalStopManager.isGlobalStopRequested()) {
            LogManager.logD(TAG, "检测到全局停止标志，中断嵌入向量生成");
            throw new InterruptedException("嵌入向量生成被用户停止");
        }
        
        if (text == null || text.isEmpty()) {
            LogManager.logW(TAG, "输入文本为空，返回空向量");
            return new float[0];
        }
        
        try {
            // 根据模型类型生成嵌入向量
            float[] embedding;
            long startTime = System.currentTimeMillis();
            
            // 再次检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止标志，中断嵌入向量生成");
                throw new InterruptedException("嵌入向量生成被用户停止");
            }
            
            if (modelType == ModelType.ONNX) {
                // 检查ONNX会话状态并尝试恢复
                if (!checkAndRecoverOnnxSession()) {
                    LogManager.logE(TAG, "ONNX会话不可用，无法生成嵌入向量");
                    throw new RuntimeException("ONNX会话不可用");
                }
                
                embedding = generateEmbeddingWithOnnx(text);
            } else if (modelType == ModelType.TORCH_SCRIPT) {
                // Torch模型的实现（暂未实现）
                LogManager.logE(TAG, "TorchScript模型尚未实现");
                return null;
            } else {
                LogManager.logE(TAG, "未知的模型类型");
                return null;
            }
            
            // 记录生成时间
            long endTime = System.currentTimeMillis();
            LogManager.logD(TAG, "生成嵌入向量耗时: " + (endTime - startTime) + "ms");
            
            // 智能归一化策略：根据模型类型和配置决定是否归一化
            boolean shouldNormalize = true; // 默认启用
            if (context != null) {
                shouldNormalize = ConfigManager.getBoolean(context, 
                    ConfigManager.KEY_LLAMACPP_NORMALIZE_EMBEDDINGS, 
                    ConfigManager.DEFAULT_LLAMACPP_NORMALIZE_EMBEDDINGS);
            }
            
            // 根据模型类型调整归一化策略
            boolean isQwenModel = modelName != null && modelName.toLowerCase().contains("qwen");
            boolean isBertLikeModel = modelName != null && (modelName.toLowerCase().contains("bge") || 
                                                           modelName.toLowerCase().contains("bert") ||
                                                           modelName.toLowerCase().contains("sentence"));
            
            if (isQwenModel) {
                // Qwen模型：强制启用归一化以确保向量质量
                shouldNormalize = true;
                LogManager.logD(TAG, "Qwen模型强制启用向量归一化以确保一致性");
            } else if (isBertLikeModel) {
                // BERT类模型：遵循配置设置
                LogManager.logD(TAG, "BERT类模型使用配置的归一化设置: " + shouldNormalize);
            } else {
                // 未知模型类型：保守策略，启用归一化
                shouldNormalize = true;
                LogManager.logD(TAG, "未知模型类型，启用归一化作为保守策略");
            }
            
            // 执行归一化
            if (shouldNormalize) {
                embedding = normalizeVector(embedding);
                LogManager.logD(TAG, "已完成向量归一化");
            } else {
                LogManager.logD(TAG, "已禁用向量归一化，保持原始向量");
            }
            
            // 打印前5个和后5个向量值，帮助调试
            if (embedding != null && embedding.length > 10) {
                StringBuilder sb = new StringBuilder("嵌入向量样例 (前5个值): ");
                for (int i = 0; i < 5; i++) {
                    sb.append(embedding[i]).append(", ");
                }
                sb.append(" ... (后5个值): ");
                for (int i = embedding.length - 5; i < embedding.length; i++) {
                    sb.append(embedding[i]).append(", ");
                }
                LogManager.logD(TAG, sb.toString());
                
                // 计算向量范数，帮助确认归一化是否有效
                double norm = 0.0;
                for (float v : embedding) {
                    norm += v * v;
                }
                norm = Math.sqrt(norm);
                LogManager.logD(TAG, "嵌入向量L2范数: " + norm + " (应接近1.0)");
            }
            
            return embedding;
        } catch (Exception e) {
            LogManager.logE(TAG, "生成嵌入向量失败: " + e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 获取向量生成的调试信息
     * @param text 输入文本
     * @param embedding 生成的向量
     * @param startTime 开始时间
     * @return 向量生成的调试信息
     */
    public String getVectorDebugInfo(String text, float[] embedding, long startTime) {
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        StringBuilder vectorInfo = new StringBuilder();
        vectorInfo.append("向量生成信息:\n");
        vectorInfo.append("模型: ").append(modelName).append("\n");
        vectorInfo.append("输入文本长度: ").append(text.length()).append(" 字符\n");
        vectorInfo.append("向量维度: ").append(embedding.length).append("\n");
        vectorInfo.append("处理时间: ").append(duration).append(" 毫秒\n");
        
        // 向量样本输出已移除，减少UI信息冗余
        
        return vectorInfo.toString();
    }
    
    /**
     * 获取嵌入向量维度
     * @return 嵌入向量维度
     */
    public int getEmbeddingDimension() {
        try {
            // 尝试从模型配置中获取维度
            if (configJson != null && configJson.has("hidden_size")) {
                int hiddenSize = configJson.getInt("hidden_size");
                //LogManager.logD(TAG, "从配置文件获取向量维度: " + hiddenSize + ", 模型路径: " + modelPath);
                return hiddenSize;
            }
            
            // 如果配置中没有，则根据模型名称智能推断维度
            if (modelName != null) {
                String lowerModelName = modelName.toLowerCase();
                
                // Qwen系列模型
                if (lowerModelName.contains("qwen")) {
                    if (lowerModelName.contains("0.6b") || lowerModelName.contains("600m")) {
                        LogManager.logD(TAG, "根据模型名称判断Qwen-0.6B向量维度: 1024, 模型名称: " + modelName);
                        return 1024;
                    } else if (lowerModelName.contains("1.5b") || lowerModelName.contains("1500m")) {
                        LogManager.logD(TAG, "根据模型名称判断Qwen-1.5B向量维度: 1536, 模型名称: " + modelName);
                        return 1536;
                    } else {
                        LogManager.logD(TAG, "根据模型名称判断Qwen默认向量维度: 1024, 模型名称: " + modelName);
                        return 1024;
                    }
                }
                // BGE系列模型
                else if (lowerModelName.contains("bge")) {
                    if (lowerModelName.contains("small") || lowerModelName.contains("base")) {
                        LogManager.logD(TAG, "根据模型名称判断BGE-small/base向量维度: 768, 模型名称: " + modelName);
                        return 768;
                    } else if (lowerModelName.contains("large") || lowerModelName.contains("m3")) {
                        LogManager.logD(TAG, "根据模型名称判断BGE-large/m3向量维度: 1024, 模型名称: " + modelName);
                        return 1024;
                    }
                }
                // BERT系列模型
                else if (lowerModelName.contains("bert")) {
                    if (lowerModelName.contains("large")) {
                        LogManager.logD(TAG, "根据模型名称判断BERT-large向量维度: 1024, 模型名称: " + modelName);
                        return 1024;
                    } else {
                        LogManager.logD(TAG, "根据模型名称判断BERT-base向量维度: 768, 模型名称: " + modelName);
                        return 768;
                    }
                }
                // Sentence-Transformers系列
                else if (lowerModelName.contains("sentence")) {
                    LogManager.logD(TAG, "根据模型名称判断Sentence-Transformers向量维度: 768, 模型名称: " + modelName);
                    return 768;
                }
            }
            
            // 默认维度
            LogManager.logW(TAG, "无法确定向量维度，使用默认值1024, 模型名称: " + modelName + ", 模型路径: " + modelPath);
            return 1024; // 修改默认值为1024
        } catch (Exception e) {
            LogManager.logE(TAG, "获取嵌入向量维度失败: " + e.getMessage() + ", 模型名称: " + modelName + ", 模型路径: " + modelPath, e);
            return 1024; // 修改默认值为1024
        }
    }
    
    /**
     * 设置是否使用一致性处理
     * 确保在知识库构建和查询过程中使用相同的分词逻辑
     * @param consistent 是否使用一致性处理
     */
    public void setUseConsistentProcessing(boolean consistent) {
        this.useConsistentProcessing = consistent;
        LogManager.logD(TAG, "设置一致性处理: " + consistent);
        
        // 如果tokenizer已初始化，同步设置其一致性分词策略
        if (tokenizer != null) {
            tokenizer.setUseConsistentTokenization(consistent);
            LogManager.logD(TAG, "同步设置tokenizer的一致性分词策略: " + consistent);
        }
    }
    
    /**
     * 获取当前是否使用一致性处理
     * @return 是否使用一致性处理
     */
    public boolean isUsingConsistentProcessing() {
        return this.useConsistentProcessing;
    }
    
    /**
     * 设置调试模式
     * @param debug 是否启用调试
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        LogManager.logD(TAG, "设置调试模式: " + debug);
        
        // 如果tokenizer已初始化，同步设置其调试模式
        if (tokenizer != null) {
            tokenizer.setDebugMode(debug);
            LogManager.logD(TAG, "同步设置tokenizer的调试模式: " + debug);
        }
    }
    
    /**
     * 获取当前是否启用调试模式
     * @return 是否启用调试模式
     */
    public boolean isDebugModeEnabled() {
        return this.debugMode;
    }
    
    /**
     * 输出调试日志
     * 只有在调试模式启用时才会输出
     * @param message 日志信息
     */
    private void debugLog(String message) {
        if (debugMode) {
            LogManager.logD(TAG, message);
        }
    }

    /**
     * 获取嵌入模型名称
     * @return 嵌入模型名称
     */
    public String getEmbeddingModel() {
        return modelName != null ? modelName : extractModelNameFromPath(modelPath);
    }
    
    /**
     * 判断文件是否为模型文件
     * @param file 要检查的文件
     * @return 如果是模型文件返回true，否则返回false
     */
    public static boolean isModelFile(File file) {
        return file.isFile() && (file.getName().endsWith(".pt") || 
                                file.getName().endsWith(".pth") || 
                                file.getName().endsWith(".onnx"));
    }
    
    /**
     * 获取应用程序上下文
     * 尝试从Android环境获取应用Context
     * @return 应用程序上下文，如果无法获取则返回null
     */
    private Context getApplicationContext() {
        try {
            // 尝试通过反射获取应用上下文
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Object application = activityThreadClass.getMethod("getApplication").invoke(activityThread);
            if (application != null) {
                return (Context) application;
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "无法通过反射获取应用上下文: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 从路径中提取模型名称
     * @param path 模型路径
     * @return 提取的模型名称
     */
    private String extractModelNameFromPath(String path) {
        try {
            File file = new File(path);
            File parentDir = file.getParentFile();
            
            // 首先尝试从目录名称检测是否是bge-m3
            if (parentDir != null) {
                String dirName = parentDir.getName();
                LogManager.logD(TAG, "检查目录名称是否包含bge-m3: " + dirName);
                
                if (dirName.toLowerCase().contains("bge-m3")) {
                    LogManager.logD(TAG, "检测到bge-m3模型: " + dirName);
                    return "BGE-M3";
                }
                
                // 检查tokenizer.json文件是否存在，并尝试从中提取模型信息
                File tokenizerFile = new File(parentDir, "tokenizer.json");
                if (tokenizerFile.exists()) {
                    String modelNameFromTokenizer = extractModelNameFromTokenizer(tokenizerFile);
                    if (modelNameFromTokenizer != null && !modelNameFromTokenizer.isEmpty()) {
                        LogManager.logD(TAG, "从tokenizer.json成功提取模型名称: " + modelNameFromTokenizer);
                        return modelNameFromTokenizer;
                    }
                }
            }
            
            // 尝试从config.json提取
            if (parentDir != null) {
                String configName = extractModelNameFromConfig(parentDir);
                if (configName != null && !configName.isEmpty()) {
                    LogManager.logD(TAG, "从config.json成功提取模型名称: " + configName);
                    return configName;
                }
            }
            
            // 尝试从目录名称提取
            if (parentDir != null) {
                String dirName = parentDir.getName();
                LogManager.logD(TAG, "尝试从目录名称提取模型名称: " + dirName);
                
                // 检查是否包含常见的模型名称关键词
                List<String> modelKeywords = Arrays.asList(
                    "bge", "bert", "roberta", "clip", "e5", "gpt", "llama", "sbert", 
                    "sentence", "transformer", "embedding", "encoder", "minilm", "mpnet"
                );
                
                for (String keyword : modelKeywords) {
                    if (dirName.toLowerCase().contains(keyword.toLowerCase())) {
                        LogManager.logD(TAG, "从目录名称提取到模型关键词: " + keyword);
                        return dirName;
                    }
                }
                
                // 如果目录名称不包含关键词，但看起来像是模型名称（包含字母和数字的组合），也返回它
                if (dirName.matches(".*[a-zA-Z].*") && dirName.matches(".*[0-9].*")) {
                    LogManager.logD(TAG, "目录名称看起来像模型名称: " + dirName);
                    return dirName;
                }
            }
            
            // 如果无法从目录名称中提取，返回文件名
            String fileName = file.getName()
                .replace(".onnx", "")
                .replace(".pt", "")
                .replace("model", "")
                .replace("_", " ")
                .trim();
            
            if (!fileName.isEmpty()) {
                LogManager.logD(TAG, "从文件名提取模型名称: " + fileName);
                return fileName;
            }
            
            // 如果所有方法都失败，返回一个默认名称
            LogManager.logD(TAG, "无法提取模型名称，使用默认名称");
            return "Embedding Model";
        } catch (Exception e) {
            LogManager.logE(TAG, "提取模型名称失败: " + e.getMessage(), e);
            return "Embedding Model";
        }
    }

    /**
     * 从tokenizer.json文件中提取模型名称
     * @param tokenizerFile tokenizer.json文件
     * @return 提取的模型名称，如果无法提取则返回null
     */
    private String extractModelNameFromTokenizer(File tokenizerFile) {
        try {
            LogManager.logD(TAG, "尝试从tokenizer.json提取模型名称: " + tokenizerFile.getAbsolutePath());
            
            // 读取tokenizer.json文件的前1000个字符，足够检测模型类型
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(tokenizerFile))) {
                char[] buffer = new char[1000];
                int read = reader.read(buffer);
                if (read > 0) {
                    content.append(buffer, 0, read);
                }
            }
            
            String contentStr = content.toString();
            
            // 检查是否是bge-m3模型的tokenizer
            if (contentStr.contains("\"name\":") && contentStr.contains("\"model\":")) {
                // 尝试提取name字段
                int nameIndex = contentStr.indexOf("\"name\":");
                if (nameIndex != -1) {
                    int nameStartIndex = contentStr.indexOf("\"", nameIndex + 7);
                    if (nameStartIndex != -1) {
                        int nameEndIndex = contentStr.indexOf("\"", nameStartIndex + 1);
                        if (nameEndIndex != -1) {
                            String name = contentStr.substring(nameStartIndex + 1, nameEndIndex);
                            LogManager.logD(TAG, "从tokenizer.json的name字段提取模型名称: " + name);
                            
                            // 如果名称包含bge-m3，直接返回标准化的名称
                            if (name.toLowerCase().contains("bge-m3")) {
                                return "BGE-M3";
                            }
                            
                            return name;
                        }
                    }
                }
                
                // 如果找不到name字段或name字段不包含bge-m3，但内容特征符合bge-m3
                if (contentStr.contains("[\"<s>\",0]") && contentStr.contains("[\"<pad>\",0]") && 
                    contentStr.contains("[\"</s>\",0]") && contentStr.contains("[\"<unk>\",0]")) {
                    LogManager.logD(TAG, "从tokenizer.json内容特征识别为bge-m3模型");
                    return "BGE-M3";
                }
            }
            
            return null;
        } catch (Exception e) {
            LogManager.logE(TAG, "从tokenizer.json提取模型名称失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从config.json文件中提取模型名称
     * @param modelDir 模型目录
     * @return 提取的模型名称，如果无法提取则返回null
     */
    private String extractModelNameFromConfig(File modelDir) {
        try {
            File configFile = new File(modelDir, "config.json");
            if (!configFile.exists()) {
                LogManager.logD(TAG, "config.json文件不存在: " + configFile.getAbsolutePath());
                return null;
            }
            
            LogManager.logD(TAG, "尝试从config.json提取模型名称: " + configFile.getAbsolutePath());
            
            // 读取config.json文件内容
            String content = readFileContent(configFile);
            
            // 解析JSON
            JSONObject config = new JSONObject(content);
            LogManager.logD(TAG, "成功解析config.json");
            
            // 尝试从不同字段提取模型名称
            String name = null;
            
            // 尝试从model_type字段提取
            if (config.has("model_type") && !config.isNull("model_type")) {
                name = config.getString("model_type");
                LogManager.logD(TAG, "从model_type字段提取模型名称: " + name);
            }
            
            // 尝试从architectures字段提取
            if ((name == null || name.isEmpty()) && config.has("architectures") && !config.isNull("architectures")) {
                JSONArray architectures = config.getJSONArray("architectures");
                if (architectures.length() > 0) {
                    name = architectures.getString(0);
                    LogManager.logD(TAG, "从architectures字段提取模型名称: " + name);
                }
            }
            
            // 尝试从_name_or_path字段提取
            if ((name == null || name.isEmpty()) && config.has("_name_or_path") && !config.isNull("_name_or_path")) {
                name = config.getString("_name_or_path");
                LogManager.logD(TAG, "从_name_or_path字段提取模型名称: " + name);
            }
            
            // 尝试从name字段提取
            if ((name == null || name.isEmpty()) && config.has("name") && !config.isNull("name")) {
                name = config.getString("name");
                LogManager.logD(TAG, "从name字段提取模型名称: " + name);
            }
            
            // 尝试从hidden_size和embedding_size推断模型大小
            String modelSize = "";
            if (config.has("hidden_size") && !config.isNull("hidden_size")) {
                int hiddenSize = config.getInt("hidden_size");
                
                // 根据hidden_size推断模型大小
                if (hiddenSize <= 384) {
                    modelSize = "Tiny";
                } else if (hiddenSize <= 512) {
                    modelSize = "Mini";
                } else if (hiddenSize <= 768) {
                    modelSize = "Small";
                } else if (hiddenSize <= 1024) {
                    modelSize = "Medium";
                } else {
                    modelSize = "Large";
                }
                
                LogManager.logD(TAG, "从hidden_size推断模型大小: " + modelSize + " (hidden_size=" + hiddenSize + ")");
            }
            
            // 组合模型名称和大小
            if (name != null && !name.isEmpty()) {
                if (!modelSize.isEmpty()) {
                    return name + "-" + modelSize;
                }
                return name;
            }
            
            return null;
        } catch (Exception e) {
            LogManager.logE(TAG, "从config.json提取模型名称失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 读取文件内容
     * @param file 文件
     * @return 文件内容
     * @throws IOException 如果读取失败
     */
    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }
    
    /**
     * 获取模型名称
     * @return 模型名称
     */
    public String getModelName() {
        if (modelName != null && !modelName.isEmpty()) {
            return modelName;
        }
        
        // 检查路径中是否包含bge-m3
        if (modelPath != null && modelPath.toLowerCase().contains("bge-m3")) {
            LogManager.logD(TAG, "从路径中检测到bge-m3模型");
            return "BGE-M3";
        }
        
        return extractModelNameFromPath(modelPath);
    }
    
    /**
     * 获取模型类型
     * @return 模型类型
     */
    public ModelType getModelType() {
        return modelType;
    }
    
    /**
     * 获取模型路径
     * @return 模型路径
     */
    public String getModelPath() {
        return modelPath;
    }
    
    /**
     * 检查ONNX会话状态并尝试恢复
     * @return 会话是否可用
     */
    private boolean checkAndRecoverOnnxSession() {
        // 记录当前线程信息
        //LogManager.logD(TAG, "检查会话状态 [线程ID: " + Thread.currentThread().getId() + "]");
        
        synchronized (sessionLock) {
            // 记录上次检查时间
            lastSessionCheckTime = System.currentTimeMillis();
            
            // 检查会话是否可用
            if (onnxSession != null && sessionState == SESSION_STATE_READY) {
                //LogManager.logD(TAG, "会话状态正常，可以使用");
                return true;
            }
            
            // 如果会话正在加载中，等待一段时间
            if (sessionState == SESSION_STATE_LOADING) {
                //LogManager.logD(TAG, "会话正在加载中，等待...");
                try {
                    // 等待最多3秒
                    for (int i = 0; i < 30; i++) {
                        // 每100毫秒检查一次
                        Thread.sleep(100);
                        if (onnxSession != null && sessionState == SESSION_STATE_READY) {
                            //LogManager.logD(TAG, "会话已加载完成，可以使用");
                            return true;
                        }
                    }
                } catch (InterruptedException e) {
                    LogManager.logE(TAG, "等待会话加载被中断: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }
            
            // 如果会话不可用或处于错误状态，尝试恢复
            if (onnxSession == null || sessionState == SESSION_STATE_ERROR) {
                // 检查重试次数
                if (sessionRetryCount >= MAX_SESSION_RETRY) {
                    LogManager.logE(TAG, "会话恢复失败，已达到最大重试次数: " + sessionRetryCount);
                    return false;
                }
                
                LogManager.logD(TAG, "尝试恢复会话，当前重试次数: " + sessionRetryCount);
                
                // 更新会话状态
                int oldState = sessionState;
                sessionState = SESSION_STATE_LOADING;
                LogManager.logD(TAG, "会话状态变更: " + oldState + " -> " + sessionState + " (开始恢复)");
                
                // 增加重试计数
                sessionRetryCount++;
                
                try {
                    // 先关闭之前的会话
                    if (onnxSession != null) {
                        try {
                            onnxSession.close();
                            LogManager.logD(TAG, "已关闭旧的ONNX会话");
                        } catch (Exception e) {
                            LogManager.logE(TAG, "关闭旧的ONNX会话失败: " + e.getMessage(), e);
                        } finally {
                            onnxSession = null;
                        }
                    }
                    
                    // 重新加载ONNX模型
                    try {
                        LogManager.logD(TAG, "重新加载ONNX模型: " + modelPath);
                        loadOnnxModel(modelPath);
                        
                        // 检查会话是否加载成功
                        if (onnxSession != null) {
                            //LogManager.logD(TAG, "ONNX会话恢复成功");
                            sessionState = SESSION_STATE_READY;
                            return true;
                        } else {
                            LogManager.logE(TAG, "ONNX会话恢复失败，会话为null");
                            sessionState = SESSION_STATE_ERROR;
                            return false;
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "重新加载ONNX模型失败: " + e.getMessage(), e);
                        sessionState = SESSION_STATE_ERROR;
                        return false;
                    }
                } finally {
                    // 如果会话仍然为null，确保状态为ERROR
                    if (onnxSession == null && sessionState != SESSION_STATE_ERROR) {
                        LogManager.logE(TAG, "会话为null但状态不是ERROR，更正状态");
                        sessionState = SESSION_STATE_ERROR;
                    }
                    
                    // 记录会话恢复结果
                    boolean success = onnxSession != null && sessionState == SESSION_STATE_READY;
                    LogManager.logD(TAG, "会话恢复" + (success ? "成功" : "失败") + 
                           "，最终状态: " + sessionState + 
                           "，重试次数: " + sessionRetryCount + "/" + MAX_SESSION_RETRY);
                }
            }
            
            // 会话仍然不可用
            LogManager.logE(TAG, "会话检查结束，会话仍然不可用，状态: " + sessionState);
            return false;
        }
    }
    
    /**
     * 关闭模型资源
     */
    public void close() {
        try {
            LogManager.logD(TAG, "开始关闭模型资源");
            
            synchronized (sessionLock) {
                if (torchModel != null) {
                    // PyTorch模型不需要显式关闭
                    LogManager.logD(TAG, "释放TorchScript模型资源");
                    torchModel = null;
                }
                
                if (onnxSession != null) {
                    try {
                        LogManager.logD(TAG, "关闭ONNX会话");
                        onnxSession.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, "关闭ONNX会话失败: " + e.getMessage(), e);
                    } finally {
                        onnxSession = null;
                        sessionState = SESSION_STATE_NONE;
                    }
                }
                
                // 重置会话状态
                sessionRetryCount = 0;
                lastSessionCheckTime = 0;
            }
            
            LogManager.logD(TAG, "模型资源已关闭");
        } catch (Exception e) {
            LogManager.logE(TAG, "关闭模型资源失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 对向量进行L2归一化（增强版，包含异常处理）
     * @param vector 输入向量
     * @return 归一化后的向量
     */
    private float[] normalizeVector(float[] vector) {
        LogManager.logD(TAG, "Starting enhanced vector normalization, vector length: " + vector.length);
        
        // 1. 向量异常检测和修复
        VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(vector, -1);
        
        float[] processedVector = vector;
        if (anomalyResult.isAnomalous) {
            LogManager.logW(TAG, String.format("Vector anomaly detected before normalization: %s (severity: %.2f) - %s", 
                    anomalyResult.type.name(), anomalyResult.severity, anomalyResult.description));
            
            // 修复向量异常
            processedVector = VectorAnomalyHandler.repairVector(vector, anomalyResult.type);
            if (processedVector == null) {
                LogManager.logE(TAG, "Failed to repair vector anomaly, using original vector");
                processedVector = vector;
            } else {
                LogManager.logD(TAG, "Vector anomaly repaired successfully");
            }
        }
        
        // 2. 计算L2范数
        float[] normalized = new float[processedVector.length];
        float norm = 0.0f;
        
        for (float v : processedVector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        
        LogManager.logD(TAG, String.format("Vector L2 norm before normalization: %.6f", norm));
        
        // 3. 处理零向量或极小范数的情况
        if (norm < 1e-6f) {
            LogManager.logW(TAG, String.format("Vector norm too small (%.2e), generating random unit vector", norm));
            
            // 生成随机单位向量
            java.util.Random random = new java.util.Random();
            for (int i = 0; i < normalized.length; i++) {
                normalized[i] = (float) (random.nextGaussian() * 0.1);
            }
            
            // 重新计算范数
            norm = 0.0f;
            for (float v : normalized) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            
            // 归一化随机向量
            if (norm > 1e-6f) {
                for (int i = 0; i < normalized.length; i++) {
                    normalized[i] /= norm;
                }
            } else {
                // 如果随机向量仍然有问题，使用默认单位向量
                LogManager.logW(TAG, "Random vector also has small norm, using default unit vector");
                Arrays.fill(normalized, 0.0f);
                if (normalized.length > 0) {
                    normalized[0] = 1.0f;
                }
            }
        } else {
            // 4. 正常归一化
            for (int i = 0; i < processedVector.length; i++) {
                normalized[i] = processedVector[i] / norm;
            }
        }
        
        // 5. 验证归一化结果
        float newNorm = 0.0f;
        for (float v : normalized) {
            newNorm += v * v;
        }
        newNorm = (float) Math.sqrt(newNorm);
        
        LogManager.logD(TAG, String.format("Vector L2 norm after normalization: %.6f (should be close to 1.0)", newNorm));
        
        // 6. 最终异常检测
        VectorAnomalyHandler.AnomalyResult finalResult = VectorAnomalyHandler.detectAnomalies(normalized, -1);
        if (finalResult.isAnomalous) {
            LogManager.logW(TAG, String.format("Normalized vector still has anomalies: %s - %s", 
                    finalResult.type.name(), finalResult.description));
        }
        
        return normalized;
    }
    
    /**
     * 对文本进行分词处理
     * @param text 输入文本
     * @return 包含input_ids和attention_mask的数组
     * @throws IOException 如果分词失败
     */
    private long[][] tokenizeText(String text) throws IOException {
        if (tokenizer == null) {
            throw new IOException("分词器未初始化");
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 设置一致性分词策略
            tokenizer.setUseConsistentTokenization(useConsistentProcessing);
            
            //LogManager.logD(TAG, "=== 开始分词 ===");
            //LogManager.logD(TAG, "输入文本长度: " + text.length());
            //LogManager.logD(TAG, "输入文本前100字符: " + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
            //LogManager.logD(TAG, "一致性分词策略: " + useConsistentProcessing);
            
            // 获取分词器特殊token数量
            int specialTokensSize = tokenizer.getSpecialTokensSize();
            //LogManager.logD(TAG, "分词器特殊token数量: " + specialTokensSize);
            
            // 执行分词
            long[][] result = tokenizer.tokenize(text);
            
            long endTime = System.currentTimeMillis();
            LogManager.logD(TAG, String.format("分词完成，耗时: %d ms, token数量: %d", 
                (endTime - startTime), result[0].length));
            
            // 打印前10个token ID用于调试
            if (result[0].length > 0) {
                StringBuilder tokenIds = new StringBuilder();
                int printCount = Math.min(10, result[0].length);
                for (int i = 0; i < printCount; i++) {
                    tokenIds.append(result[0][i]);
                    if (i < printCount - 1) tokenIds.append(", ");
                }
                if (result[0].length > 10) tokenIds.append("...");
                LogManager.logD(TAG, "前" + printCount + "个token ID: [" + tokenIds.toString() + "]");
            }
            //LogManager.logD(TAG, "=== 分词结束 ===");
            
            return result;
        } catch (Exception e) {
            LogManager.logE(TAG, "分词失败: " + e.getMessage(), e);
            throw new IOException("分词失败: " + e.getMessage(), e);
        }
    }
}
