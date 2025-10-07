package com.example.offlineai;

import android.content.Context;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.example.offlineai.api.TokenizerManager;
import com.example.offlineai.GlobalStopManager;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重排模型处理器
 * 支持ONNX格式的Cross-Encoder重排模型，如bge-reranker-m3
 */
public class RerankerModelHandler {
    private static final String TAG = "OfflineAI_RerankerModel";
    private static final int MAX_SEQUENCE_LENGTH = 512; // 最大序列长度
    private static final int MAX_BATCH_SIZE = 8; // 批处理大小，避免内存过大
    
    // ONNX会话状态常量
    private static final int SESSION_STATE_NONE = 0;      // 未初始化
    private static final int SESSION_STATE_LOADING = 1;   // 正在加载
    private static final int SESSION_STATE_READY = 2;     // 已就绪
    private static final int SESSION_STATE_ERROR = 3;     // 错误状态
    
    // 会话重试相关常量
    private static final int MAX_SESSION_RETRY = 3;       // 最大重试次数
    private static final long SESSION_RETRY_DELAY_MS = 500; // 重试间隔
    
    private final Context context;
    private final String modelPath;
    private OrtEnvironment environment;
    private OrtSession session;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    
    // 会话状态管理变量
    private final Object sessionLock = new Object();      // 会话锁
    private int sessionState = SESSION_STATE_NONE;        // 当前会话状态
    private int sessionRetryCount = 0;                    // 当前重试次数
    private long lastSessionCheckTime = 0;                // 上次会话检查时间
    
    // TokenizerManager实例 - 不再缓存，每次动态获取
    // private TokenizerManager tokenizerManager; // 移除缓存的实例
    
    // 模型输入输出名称（根据具体模型调整）
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String OUTPUT_LOGITS = "logits";
    
    /**
     * 重排结果类
     */
    public static class RerankResult implements Comparable<RerankResult> {
        public final String text;
        public final float score;
        public final int originalIndex;
        
        public RerankResult(String text, float score, int originalIndex) {
            this.text = text;
            this.score = score;
            this.originalIndex = originalIndex;
        }
        
        @Override
        public int compareTo(RerankResult other) {
            // 按分数降序排列
            return Float.compare(other.score, this.score);
        }
    }
    
    public RerankerModelHandler(Context context, String modelPath) {
        this.context = context;
        this.modelPath = modelPath;
    }
    
    /**
     * 初始化模型
     */
    public boolean initialize() {
        if (isInitialized.get()) {
            return true;
        }
        
        synchronized (sessionLock) {
            // 双重检查锁定模式
            if (isInitialized.get()) {
                return true;
            }
            
            if (isLoading.get()) {
                LogManager.logW(TAG, "Model is loading, please wait");
                return false;
            }
            
            isLoading.set(true);
            sessionState = SESSION_STATE_LOADING;
            LogManager.logD(TAG, "Session state changed: " + sessionState + " (loading started)");
        
        try {
            LogManager.logI(TAG, "Starting to initialize reranker model: " + modelPath);
            
            // Check if model file exists
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                LogManager.logE(TAG, "Reranker model file does not exist: " + modelPath);
                return false;
            }
            
            // 打印模型文件详细信息
            long fileSize = modelFile.length();
            LogManager.logI(TAG, "Model file size: " + (fileSize / 1024 / 1024) + " MB");
            LogManager.logI(TAG, "Model file readable: " + modelFile.canRead());
            LogManager.logI(TAG, "Model file absolute path: " + modelFile.getAbsolutePath());
            
            // 检查可用内存
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            LogManager.logI(TAG, "Memory status - Max: " + (maxMemory / 1024 / 1024) + "MB, " +
                           "Used: " + (usedMemory / 1024 / 1024) + "MB, " +
                           "Free: " + (freeMemory / 1024 / 1024) + "MB");
            
            // Create ONNX environment
            LogManager.logI(TAG, "Creating ONNX environment...");
            environment = OrtEnvironment.getEnvironment();
            LogManager.logI(TAG, "ONNX environment created successfully");
            
            // Create session options
            LogManager.logI(TAG, "Creating session options...");
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            
            // 设置线程数（从ConfigManager获取配置）
            int threadCount = ConfigManager.getThreads(context);
            LogManager.logI(TAG, "Setting thread count: " + threadCount);
            sessionOptions.setIntraOpNumThreads(threadCount);
            sessionOptions.setInterOpNumThreads(threadCount);
            
            // 设置内存优化选项
            LogManager.logI(TAG, "Setting memory optimization options...");
            sessionOptions.setMemoryPatternOptimization(true);
            
            // 设置执行模式为顺序执行，避免并发问题
            LogManager.logI(TAG, "Setting execution mode to sequential...");
            sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            
            // 设置其他选项
            LogManager.logI(TAG, "Session options setup completed");
            
            // Load model
            LogManager.logI(TAG, "Starting to load ONNX model...");
            LogManager.logI(TAG, "Before session creation - Thread: " + Thread.currentThread().getName());
            LogManager.logI(TAG, "Before session creation - Timestamp: " + System.currentTimeMillis());
            
            session = environment.createSession(modelPath, sessionOptions);
            
            LogManager.logI(TAG, "ONNX model loaded successfully");
            LogManager.logI(TAG, "After session creation - Timestamp: " + System.currentTimeMillis());
            
            // 打印模型信息
            LogManager.logI(TAG, "Model input count: " + session.getInputNames().size());
            LogManager.logI(TAG, "Model output count: " + session.getOutputNames().size());
            LogManager.logI(TAG, "Model input names: " + session.getInputNames());
            LogManager.logI(TAG, "Model output names: " + session.getOutputNames());
            
            // 初始化TokenizerManager - 不再缓存实例，每次动态获取
            //LogManager.logI(TAG, "开始初始化TokenizerManager...");
            try {
                TokenizerManager tempTokenizerManager = TokenizerManager.getInstance(context);
                
                // 获取tokenizer.json文件所在的目录
                File modelDir = modelFile.getParentFile();
                
                if (modelDir != null && modelDir.exists()) {
                    LogManager.logI(TAG, "Trying to initialize tokenizer from model directory: " + modelDir.getAbsolutePath());
                    boolean tokenizerSuccess = tempTokenizerManager.initialize(modelDir);
                    
                    if (tokenizerSuccess) {
                        LogManager.logI(TAG, "TokenizerManager初始化成功，将使用动态获取方式");
                        //LogManager.logI(TAG, "Tokenizer特殊token数量: " + tempTokenizerManager.getSpecialTokensSize());
                    } else {
                        LogManager.logW(TAG, "TokenizerManager initialization failed, will use simplified tokenizer");
                    }
                } else {
                    LogManager.logW(TAG, "Unable to get model directory, will use simplified tokenizer");
                    // 不再设置成员变量为null，因为我们使用动态获取方式
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "TokenizerManager initialization exception: " + e.getMessage(), e);
                // 不再设置成员变量为null，因为我们使用动态获取方式
            }
            
            isInitialized.set(true);
            sessionState = SESSION_STATE_READY;
            sessionRetryCount = 0; // 重置重试计数
            //LogManager.logI(TAG, "Reranker model initialization successful");
            //LogManager.logD(TAG, "Session state changed: " + sessionState + " (initialization successful)");
            
            return true;
            
        } catch (OutOfMemoryError e) {
            LogManager.logE(TAG, "Reranker model initialization failed - Out of memory");
            LogManager.logE(TAG, "Exception type: " + e.getClass().getSimpleName());
            LogManager.logE(TAG, "Exception message: " + e.getMessage());
            LogManager.logE(TAG, "Exception details: ", e);
            
            sessionState = SESSION_STATE_ERROR;
            LogManager.logD(TAG, "Session state changed: " + sessionState + " (out of memory error)");
            
            // 强制垃圾回收
            System.gc();
            
            cleanup();
            return false;
        } catch (Exception e) {
            LogManager.logE(TAG, "Reranker model initialization failed");
            LogManager.logE(TAG, "Exception type: " + e.getClass().getSimpleName());
            LogManager.logE(TAG, "Exception message: " + e.getMessage());
            LogManager.logE(TAG, "Exception details: ", e);
            
            sessionState = SESSION_STATE_ERROR;
            LogManager.logD(TAG, "Session state changed: " + sessionState + " (initialization exception)");
            
            // 打印更多调试信息
            if (e.getCause() != null) {
                LogManager.logE(TAG, "Root cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            
            // 检查是否是内存相关问题
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("memory")) {
                LogManager.logE(TAG, "Possibly memory-related failure");
                // 强制垃圾回收
                System.gc();
            }
            
            // 检查是否是文件访问问题
            if (e.getMessage() != null && 
                (e.getMessage().toLowerCase().contains("file") || 
                 e.getMessage().toLowerCase().contains("path") ||
                 e.getMessage().toLowerCase().contains("access"))) {
                LogManager.logE(TAG, "Possibly file access permission issue");
            }
            
            cleanup();
            return false;
        } finally {
            isLoading.set(false);
        }
        } // 结束synchronized块
    }
    
    /**
     * 重排进度回调接口
     */
    public interface RerankProgressCallback {
        void onRerankProgress(int processedCount, int totalCount, double score);
    }
    
    /**
     * 对文档进行重排
     * @param query 查询文本
     * @param documents 待重排的文档列表
     * @param topK 返回前K个结果
     * @return 重排后的结果列表
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        return rerank(query, documents, topK, null);
    }
    
    /**
     * 对文档进行重排（带进度回调）
     * @param query 查询文本
     * @param documents 待重排的文档列表
     * @param topK 返回前K个结果
     * @param progressCallback 进度回调
     * @return 重排后的结果列表
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topK, RerankProgressCallback progressCallback) {
        // 立即输出日志，确认方法被调用
        LogManager.logI(TAG, "=== RERANK METHOD EXECUTION STARTED ===");
        
        // 检查全局停止标志
        if (GlobalStopManager.isGlobalStopRequested()) {
            LogManager.logD(TAG, "检测到全局停止标志，中断重排序操作");
            return convertToRerankResults(documents); // 返回原始顺序
        }
        
        //LogManager.logI(TAG, "Query text: " + (query != null ? query.substring(0, Math.min(50, query.length())) + "..." : "null"));
        //LogManager.logI(TAG, "Document count: " + (documents != null ? documents.size() : 0));
        //LogManager.logI(TAG, "topK: " + topK);
        //LogManager.logI(TAG, "Model initialization status: " + isInitialized.get());
        
        // 首先检查TokenizerManager状态
        LogManager.logI(TAG, "🔍 检查TokenizerManager状态...");
        String statusReport = checkTokenizerManagerStatus();
        LogManager.logI(TAG, statusReport);
        
        // 验证TokenizerManager是否可用
        if (getCurrentTokenizerManager() == null) {
            LogManager.logW(TAG, "⚠️ TokenizerManager不可用，尝试重新初始化...");
            if (!validateAndReinitializeTokenizer()) {
                LogManager.logE(TAG, "❌ TokenizerManager重新初始化失败，返回原始顺序");
                return convertToRerankResults(documents);
            }
            LogManager.logI(TAG, "✅ TokenizerManager重新初始化成功");
        }
        
        if (!isInitialized.get()) {
            LogManager.logE(TAG, "Reranker model not initialized");
            return convertToRerankResults(documents); // 返回原始顺序
        }
        
        // 检查并恢复会话状态
        if (!checkAndRecoverSession()) {
            LogManager.logE(TAG, "Reranker model session unavailable, returning original order");
            return convertToRerankResults(documents);
        }
        
        if (documents == null || documents.isEmpty()) {
            LogManager.logW(TAG, "Document list is empty");
            return new ArrayList<>();
        }
        
        if (query == null || query.trim().isEmpty()) {
            LogManager.logW(TAG, "Query text is empty");
            return convertToRerankResults(documents);
        }
        
        try {
            //LogManager.logD(TAG, "Starting reranking, query: " + query.substring(0, Math.min(50, query.length())) + 
            //               "..., document count: " + documents.size());
            
            List<RerankResult> results = new ArrayList<>();
            
            // 分批处理以避免内存过大
            for (int i = 0; i < documents.size(); i += MAX_BATCH_SIZE) {
                // 在每个批次开始前检查停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "检测到全局停止标志，中断重排序批处理");
                    break;
                }
                
                int endIndex = Math.min(i + MAX_BATCH_SIZE, documents.size());
                List<String> batch = documents.subList(i, endIndex);
                
                List<RerankResult> batchResults = processBatch(query, batch, i, progressCallback);
                results.addAll(batchResults);
            }
            
            // 按分数排序
            Collections.sort(results);
            
            // 返回前topK个结果
            int resultSize = Math.min(topK, results.size());
            List<RerankResult> topResults = results.subList(0, resultSize);
            
            LogManager.logD(TAG, "Reranking completed, returning top " + resultSize + " results");
            return topResults;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error occurred during reranking process");
            LogManager.logE(TAG, "Exception type: " + e.getClass().getSimpleName());
            LogManager.logE(TAG, "Exception message: " + e.getMessage());
            LogManager.logE(TAG, "Query text: " + (query != null ? query.substring(0, Math.min(100, query.length())) : "null"));
            LogManager.logE(TAG, "Document count: " + (documents != null ? documents.size() : 0));
            LogManager.logE(TAG, "Exception details: ", e);
            
            if (e.getCause() != null) {
                LogManager.logE(TAG, "Root cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            
            return convertToRerankResults(documents); // 返回原始顺序
        }
    }
    
    /**
     * 处理一个批次的文档
     */
    private List<RerankResult> processBatch(String query, List<String> documents, int startIndex) throws OrtException {
        return processBatch(query, documents, startIndex, null);
    }
    
    /**
     * 处理一个批次的文档（带进度回调）
     */
    private List<RerankResult> processBatch(String query, List<String> documents, int startIndex, RerankProgressCallback progressCallback) throws OrtException {
        List<RerankResult> results = new ArrayList<>();
        
        //LogManager.logI(TAG, "Starting batch processing, document count: " + documents.size() + ", start index: " + startIndex);
        
        for (int i = 0; i < documents.size(); i++) {
            // 在处理每个文档前检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "检测到全局停止标志，中断文档处理");
                break;
            }
            
            long docStartTime = System.currentTimeMillis();
            int globalIndex = startIndex + i;
            
            LogManager.logI(TAG, "=== Starting document processing " + (i + 1) + "/" + documents.size() + " (global index: " + globalIndex + ") ===");
            
            String document = documents.get(i);
            if (document == null || document.trim().isEmpty()) {
                LogManager.logW(TAG, "Skipping empty document " + globalIndex);
                continue;
            }
            
            // 构建输入文本（标记输入格式：[Q] query [SEP] [D] document [SEP]）
            String inputText = "[Q] " + query + " [SEP] [D] " + document + " [SEP]";
            //LogManager.logI(TAG, "Building tagged input format text:");
            //LogManager.logI(TAG, "  - Query length: " + query.length() + " characters");
            //LogManager.logI(TAG, "  - Document length: " + document.length() + " characters");
            //LogManager.logI(TAG, "  - Combined total length: " + inputText.length() + " characters");
            //LogManager.logI(TAG, "  - Format: [Q] query [SEP] [D] document [SEP]");
            LogManager.logI(TAG, "Input text preview: " + inputText.substring(0, Math.min(150, inputText.length())) + "...");
            
            //LogManager.logW(TAG, "🔍 Performance analysis: Key differences between reranking vs embedding:");
            //LogManager.logW(TAG, "  1. Embedding model: Only needs to process single document, outputs fixed-dimension vector");
            //LogManager.logW(TAG, "  2. Reranking model: Needs to process query+document combination, each document requires separate inference");
            //LogManager.logW(TAG, "  3. Current document count: " + documents.size() + ", meaning " + documents.size() + " independent inferences needed");
            
            // 开始文本分词处理
            long tokenizeStartTime = System.currentTimeMillis();
            //LogManager.logI(TAG, "=== Starting TOKENIZATION for document " + (i+1) + "/" + documents.size() + " ===");
            //LogManager.logI(TAG, "🔤 Input text statistics:");
            //LogManager.logI(TAG, "  - Original query length: " + query.length() + " characters");
            //LogManager.logI(TAG, "  - Original document length: " + document.length() + " characters");
            //LogManager.logI(TAG, "  - Combined text length: " + inputText.length() + " characters");
            //LogManager.logI(TAG, "  - Estimated token count: ~" + (inputText.length() / 4) + " (estimate)");
            //LogManager.logI(TAG, "🎯 Starting tokenizeInput method call...");
            
            Map<String, OnnxTensor> inputs;
            try {
                inputs = tokenizeInput(inputText);
                long tokenizeTime = System.currentTimeMillis() - tokenizeStartTime;
                //LogManager.logI(TAG, "✅ TOKENIZATION completed, total time: " + tokenizeTime + "ms");
                //LogManager.logI(TAG, "📊 Output tensor statistics:");
                //LogManager.logI(TAG, "  - Tensor count: " + inputs.size());
                
                // 打印张量维度信息
                for (Map.Entry<String, OnnxTensor> entry : inputs.entrySet()) {
                    long[] shape = entry.getValue().getInfo().getShape();
                    //LogManager.logI(TAG, "  - Tensor [" + entry.getKey() + "] dimensions: " + java.util.Arrays.toString(shape));
                    //LogManager.logI(TAG, "  - Tensor [" + entry.getKey() + "] total elements: " + java.util.Arrays.stream(shape).reduce(1, (a, b) -> a * b));
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "❌ TOKENIZATION failed: " + e.getMessage(), e);
                LogManager.logE(TAG, "Failed input text length: " + inputText.length());
                LogManager.logE(TAG, "Failed input text preview: " + inputText.substring(0, Math.min(100, inputText.length())));
                continue;
            }
            
            try {
                // 运行推理
                long inferenceStartTime = System.currentTimeMillis();
                //LogManager.logI(TAG, "=== Starting model inference phase ===");
                //LogManager.logI(TAG, "🧠 Inference environment check:");
                //LogManager.logI(TAG, "  - Current thread: " + Thread.currentThread().getName());
                //LogManager.logI(TAG, "  - Session status: " + (session != null ? "valid" : "invalid"));
                //LogManager.logI(TAG, "  - Input tensor count: " + inputs.size());
                //LogManager.logI(TAG, "  - System memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB / " + Runtime.getRuntime().totalMemory() / 1024 / 1024 + "MB");
                
                // 关键的推理调用
                //LogManager.logI(TAG, "🚀 Calling session.run()...");
                LogManager.logI(TAG, "Inference timeout setting: 10 minutes");
                
                // 使用Future来实现超时机制
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                java.util.concurrent.Future<OrtSession.Result> future = executor.submit(() -> {
                    LogManager.logI(TAG, "⚡ Inference thread starting execution...");
                    //LogManager.logI(TAG, "Inference thread ID: " + Thread.currentThread().getId());
                    OrtSession.Result result = session.run(inputs);
                    LogManager.logI(TAG, "✅ Inference thread execution completed");
                    return result;
                });
                
                OrtSession.Result output;
                try {
                    // 设置10分钟超时
                    output = future.get(600, java.util.concurrent.TimeUnit.SECONDS);
                    // LogManager.logI(TAG, "✅ session.run() 返回成功");
                } catch (java.util.concurrent.TimeoutException e) {
                    LogManager.logE(TAG, "⏰ Inference timeout (10 minutes), canceling task");
                    future.cancel(true);
                    executor.shutdownNow();
                    continue; // 跳过这个文档
                } catch (java.util.concurrent.ExecutionException e) {
                    LogManager.logE(TAG, "❌ Inference execution exception: " + e.getCause().getMessage(), e.getCause());
                    executor.shutdownNow();
                    continue;
                } catch (InterruptedException e) {
                    LogManager.logE(TAG, "🛑 Inference interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                    continue;
                } finally {
                    executor.shutdown();
                }
                
                long inferenceTime = System.currentTimeMillis() - inferenceStartTime;
                // LogManager.logI(TAG, "✅ Model inference completed, time taken: " + inferenceTime + "ms");
                
                // 获取logits
                long outputProcessStartTime = System.currentTimeMillis();
                //LogManager.logI(TAG, "=== Starting model output processing ===");
                //LogManager.logI(TAG, "📤 Model output analysis:");
                //LogManager.logI(TAG, "  - Output object type: " + output.getClass().getName());
                //LogManager.logI(TAG, "  - Output key count: " + output.size());
                
                // 列出所有输出
                //LogManager.logI(TAG, "📋 Output tensor information:");
                //LogManager.logI(TAG, "  - Output tensor count: " + output.size());
                for (int j = 0; j < output.size(); j++) {
                    try {
                        OnnxTensor tensor = (OnnxTensor) output.get(j);
                        //LogManager.logI(TAG, "  - Tensor[" + j + "] shape: " + java.util.Arrays.toString(tensor.getInfo().getShape()));
                    } catch (Exception e) {
                        LogManager.logI(TAG, "  - Tensor[" + j + "] retrieval failed: " + e.getMessage());
                    }
                }
                
                //LogManager.logI(TAG, "🎯 Attempting to get logits tensor (index: 0)...");
                OnnxTensor logitsTensor = null;
                try {
                    logitsTensor = (OnnxTensor) output.get(0);
                } catch (Exception e) {
                    LogManager.logE(TAG, "❌ Failed to get logits tensor: " + e.getMessage());
                }
                
                if (logitsTensor != null) {
                    //LogManager.logI(TAG, "✅ Successfully obtained logits tensor");
                    Object logitsValue = logitsTensor.getValue();
                    //LogManager.logI(TAG, "📊 Logits detailed information:");
                    //LogManager.logI(TAG, "  - Data type: " + logitsValue.getClass().getName());
                    //LogManager.logI(TAG, "  - Tensor shape: " + java.util.Arrays.toString(logitsTensor.getInfo().getShape()));
                    
                    float score;
                    if (logitsValue instanceof float[][][]) {
                        // 三维输出：[batch_size, sequence_length, num_classes]
                        float[][][] logits3D = (float[][][]) logitsValue;
                        //LogManager.logI(TAG, "🔢 Detected 3D logits output");
                        //LogManager.logI(TAG, "  - Dimensions: [" + logits3D.length + ", " + 
                        //               (logits3D.length > 0 ? logits3D[0].length : 0) + ", " + 
                        //               (logits3D.length > 0 && logits3D[0].length > 0 ? logits3D[0][0].length : 0) + "]");
                        //LogManager.logI(TAG, "  - Interpretation: [batch_size, sequence_length, num_classes]");
                        
                        // 通常取第一个batch的第一个token的logits
                        if (logits3D.length > 0 && logits3D[0].length > 0) {
                            //LogManager.logI(TAG, "📈 Using first batch's first token to calculate score");
                            //LogManager.logI(TAG, "  - Original logits length: " + logits3D[0][0].length);
                            if (logits3D[0][0].length > 0) {
                                //LogManager.logI(TAG, "  - First 5 original logits values: " + java.util.Arrays.toString(java.util.Arrays.copyOf(logits3D[0][0], Math.min(5, logits3D[0][0].length))));
                            }
                            score = calculateRelevanceScore(logits3D[0][0]);
                        } else {
                            LogManager.logW(TAG, "⚠️ 3D logits array is empty");
                            score = 0.5f;
                        }
                    } else if (logitsValue instanceof float[][]) {
                        // 二维输出：[batch_size, num_classes]
                        float[][] logits2D = (float[][]) logitsValue;
                        //LogManager.logI(TAG, "🔢 Detected 2D logits output");
                        //LogManager.logI(TAG, "  - Dimensions: [" + logits2D.length + ", " + 
                        //               (logits2D.length > 0 ? logits2D[0].length : 0) + "]");
                        //LogManager.logI(TAG, "  - Interpretation: [batch_size, num_classes]");
                        
                        if (logits2D.length > 0) {
                            //LogManager.logI(TAG, "📈 Using first batch to calculate score");
                            //LogManager.logI(TAG, "  - Original logits length: " + logits2D[0].length);
                            if (logits2D[0].length > 0) {
                                //LogManager.logI(TAG, "  - First 5 original logits values: " + java.util.Arrays.toString(java.util.Arrays.copyOf(logits2D[0], Math.min(5, logits2D[0].length))));
                            }
                            score = calculateRelevanceScore(logits2D[0]);
                        } else {
                            LogManager.logW(TAG, "⚠️ 2D logits array is empty");
                            score = 0.5f;
                        }
                    } else {
                        LogManager.logE(TAG, "❌ Unsupported logits output type: " + logitsValue.getClass().getName());
                        score = 0.5f;
                    }
                    
                    //LogManager.logI(TAG, "🎯 Calculated relevance score: " + score);
                    results.add(new RerankResult(document, score, startIndex + i));
                    
                    // 调用进度回调
                    if (progressCallback != null) {
                        progressCallback.onRerankProgress(i + 1, documents.size(), score);
                    }
                    
                    long outputProcessTime = System.currentTimeMillis() - outputProcessStartTime;
                    LogManager.logI(TAG, "✅ Document " + globalIndex + " processing completed, score: " + score + ", output processing time: " + outputProcessTime + "ms");
                } else {
                    LogManager.logE(TAG, "❌ Unable to get logits output tensor");
                    LogManager.logE(TAG, "Possible reasons:");
                    LogManager.logE(TAG, "  1. Output key name mismatch (expected: " + OUTPUT_LOGITS + ")");
                    LogManager.logE(TAG, "  2. Model output format does not meet expectations");
                    LogManager.logE(TAG, "  3. Error occurred during inference");
                }
                
                // 清理输出
                output.close();
                
                long totalDocTime = System.currentTimeMillis() - docStartTime;
                //LogManager.logI(TAG, "=== Document " + globalIndex + " total time: " + totalDocTime + "ms ===");
                
            } finally {
                // 清理输入张量
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }
        }
        
        return results;
    }
    
    /**
     * Use TokenizerManager for tokenization
     * If TokenizerManager is unavailable, throw an exception directly
     */
    private Map<String, OnnxTensor> tokenizeInput(String text) throws OrtException {
        long tokenizeStartTime = System.currentTimeMillis();
        
        // 首先尝试获取当前TokenizerManager实例
        TokenizerManager currentTokenizerManager = getCurrentTokenizerManager();
        
        // 如果获取失败，尝试验证并重新初始化
        if (currentTokenizerManager == null) {
            LogManager.logW(TAG, "TokenizerManager实例无效，尝试重新初始化...");
            if (!validateAndReinitializeTokenizer()) {
                throw new IllegalStateException("TokenizerManager not initialized or unavailable, unable to perform tokenization");
            }
            // 重新获取实例
            currentTokenizerManager = getCurrentTokenizerManager();
            if (currentTokenizerManager == null) {
                throw new IllegalStateException("TokenizerManager重新初始化后仍然无效");
            }
        }
        
        LogManager.logI(TAG, "✅ TokenizerManager initialized, using professional tokenizer (dynamic instance with validation)");
        return tokenizeWithTokenizerManager(text, tokenizeStartTime, currentTokenizerManager);
    }
    
    /**
     * Use TokenizerManager for tokenization
     */
    private Map<String, OnnxTensor> tokenizeWithTokenizerManager(String text, long startTime, TokenizerManager tokenizerManager) throws OrtException {
        try {
            LogManager.logI(TAG, "🔧 === Using TokenizerManager for professional tokenization ===");
            
            // 使用TokenizerManager进行tokenization
            long tokenizeTime = System.currentTimeMillis();
            //LogManager.logI(TAG, "📞 Calling TokenizerManager.tokenize()...");
            //LogManager.logI(TAG, "  - Input text length: " + text.length());
            //LogManager.logI(TAG, "  - TokenizerManager instance: " + tokenizerManager.getClass().getSimpleName());
            
            long[][] tokenIds = tokenizerManager.tokenize(text);
            long tokenizeDuration = System.currentTimeMillis() - tokenizeTime;
            
            //LogManager.logI(TAG, "✅ TokenizerManager tokenization completed");
            //LogManager.logI(TAG, "📊 Tokenization result statistics:");
            //LogManager.logI(TAG, "  - Tokenization time: " + tokenizeDuration + "ms");
            //LogManager.logI(TAG, "  - Token sequence dimensions: [" + tokenIds.length + ", " + tokenIds[0].length + "]");
            //LogManager.logI(TAG, "  - Generated token count: " + tokenIds[0].length);
            //LogManager.logI(TAG, "  - Compression ratio: " + String.format("%.2f", (double)text.length() / tokenIds[0].length) + " characters/token");
            //LogManager.logI(TAG, "  - First 10 token IDs: " + java.util.Arrays.toString(
            //    java.util.Arrays.copyOf(tokenIds[0], Math.min(10, tokenIds[0].length))));
            if (tokenIds[0].length > 10) {
                //LogManager.logI(TAG, "  - Last 5 token IDs: " + java.util.Arrays.toString(
                //    java.util.Arrays.copyOfRange(tokenIds[0], Math.max(0, tokenIds[0].length - 5), tokenIds[0].length)));
            }
            
            // Handle sequence length
            //LogManager.logI(TAG, "🔍 Checking sequence length limits...");
            int actualLength = tokenIds[0].length;
            if (actualLength > MAX_SEQUENCE_LENGTH) {
                LogManager.logW(TAG, "⚠️ Sequence length exceeds limit!");
                LogManager.logW(TAG, "  - Original length: " + actualLength);
                LogManager.logW(TAG, "  - Maximum allowed: " + MAX_SEQUENCE_LENGTH);
                LogManager.logW(TAG, "  - Performing truncation...");
                long[][] truncatedTokenIds = new long[1][MAX_SEQUENCE_LENGTH];
                System.arraycopy(tokenIds[0], 0, truncatedTokenIds[0], 0, MAX_SEQUENCE_LENGTH);
                tokenIds = truncatedTokenIds;
                actualLength = MAX_SEQUENCE_LENGTH;
                LogManager.logI(TAG, "✅ Truncation completed, new length: " + actualLength);
            } else {
                LogManager.logI(TAG, "✅ Sequence length meets requirements: " + actualLength + " <= " + MAX_SEQUENCE_LENGTH);
            }
            
            // Create attention mask and token type ids
            long tensorCreateTime = System.currentTimeMillis();
            //LogManager.logI(TAG, "🎭 Creating attention mask and token type ids...");
            
            long[][] attentionMask = new long[1][actualLength];
            long[][] tokenTypeIds = new long[1][actualLength];
            
            // Fill attention mask (all positions are 1, indicating valid tokens)
            for (int i = 0; i < actualLength; i++) {
                attentionMask[0][i] = 1;
                tokenTypeIds[0][i] = 0; // For tagged input format, may need to set different token types based on [Q], [SEP], [D] positions
            }
            
            //LogManager.logI(TAG, "  - attention mask length: " + actualLength);
            //LogManager.logI(TAG, "  - All values set to: 1 (indicating all tokens need attention)");
            //LogManager.logI(TAG, "  - token type IDs length: " + actualLength);
            //LogManager.logI(TAG, "  - All values set to: 0 (indicating single sentence type)");
            
            // Create ONNX tensors
            LogManager.logI(TAG, "🔄 Converting to ONNX tensors...");
            Map<String, OnnxTensor> inputs = new HashMap<>();
            
            //LogManager.logI(TAG, "  - Creating input_ids tensor: [1, " + actualLength + "]");
            inputs.put(INPUT_IDS, OnnxTensor.createTensor(environment, tokenIds));
            
            //LogManager.logI(TAG, "  - Creating attention_mask tensor: [1, " + actualLength + "]");
            inputs.put(ATTENTION_MASK, OnnxTensor.createTensor(environment, attentionMask));
            
            //LogManager.logI(TAG, "  - Creating token_type_ids tensor: [1, " + actualLength + "]");
            inputs.put(TOKEN_TYPE_IDS, OnnxTensor.createTensor(environment, tokenTypeIds));
            
            long tensorCreateDuration = System.currentTimeMillis() - tensorCreateTime;
            long totalDuration = System.currentTimeMillis() - startTime;
            
            //LogManager.logI(TAG, "✅ TokenizerManager tokenization process completed");
            //LogManager.logI(TAG, "📈 Performance statistics:");
            //LogManager.logI(TAG, "  - Total time: " + totalDuration + "ms");
            //LogManager.logI(TAG, "  - Pure tokenization time: " + tokenizeDuration + "ms");
            //LogManager.logI(TAG, "  - Tensor creation time: " + tensorCreateDuration + "ms");
            //LogManager.logI(TAG, "  - Output tensor count: " + inputs.size());
            
            return inputs;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "❌ TokenizerManager tokenization failed: " + e.getMessage(), e);
            
            // 检查是否是分词器未初始化的错误
            if (e.getMessage() != null && e.getMessage().contains("分词器未初始化")) {
                LogManager.logW(TAG, "检测到分词器未初始化错误，这可能是由于TokenizerManager被重置导致的");
                LogManager.logW(TAG, "建议检查TokenizerManager的状态管理和重置逻辑");
            }
            
            // 检查是否是ONNX Runtime相关错误
            if (e.getMessage() != null && e.getMessage().contains("OrtException")) {
                LogManager.logW(TAG, "检测到ONNX Runtime错误，可能与输入张量维度有关");
            }
            
            throw new OrtException("Tokenization processing failed: " + e.getMessage());
        }
    }
    

    
    /**
     * 计算相关性分数
     */
    private float calculateRelevanceScore(float[] logits) {
        LogManager.logI(TAG, "🧮 === Starting relevance score calculation ===");
        // LogManager.logI(TAG, "📊 Input logits analysis:");
        
        if (logits == null || logits.length == 0) {
            LogManager.logW(TAG, "⚠️ logits is empty, returning default score 0.5");
            return 0.5f;
        }
        
        // 向量异常检测和修复
        try {
            VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(logits, -1);
            if (anomalyResult.isAnomalous) {
                LogManager.logW(TAG, "检测到logits向量异常，尝试修复");
                logits = VectorAnomalyHandler.repairVector(logits, anomalyResult.type);
                
                // 对修复后的向量进行最终验证
                VectorAnomalyHandler.AnomalyResult verifyResult = VectorAnomalyHandler.detectAnomalies(logits, -1);
                if (verifyResult.isAnomalous) {
                    LogManager.logW(TAG, "logits向量修复失败，使用默认分数");
                    return 0.5f;
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "logits向量异常处理失败: " + e.getMessage());
            return 0.5f;
        }
        
        LogManager.logI(TAG, "  - logits length: " + logits.length);
        // LogManager.logI(TAG, "  - All logits values: " + java.util.Arrays.toString(logits));
        
        // For binary classification tasks, sigmoid activation function is typically used
        // If logits has only one value, apply sigmoid directly
        if (logits.length == 1) {
            LogManager.logI(TAG, "🔢 Detected single-value logits mode");
            LogManager.logI(TAG, "  - Original logit value: " + logits[0]);
            float score = sigmoid(logits[0]);
            LogManager.logI(TAG, "  - Applied sigmoid function: sigmoid(" + logits[0] + ") = " + score);
            LogManager.logI(TAG, "✅ Final score: " + score);
            return score;
        }
        
        // If there are multiple logits values, softmax or other processing may be needed
        // Here we assume the second value is the positive class logits (adjust according to specific model)
        if (logits.length >= 2) {
            LogManager.logI(TAG, "🔢 Detected multi-value logits mode");
            LogManager.logI(TAG, "  - Negative class logit (index 0): " + logits[0]);
            LogManager.logI(TAG, "  - Positive class logit (index 1): " + logits[1]);
            
            float positiveLogit = logits[1];
            LogManager.logI(TAG, "  - Using positive class logit to calculate score: " + positiveLogit);
            float score = sigmoid(positiveLogit);
            LogManager.logI(TAG, "  - Applied sigmoid function: sigmoid(" + positiveLogit + ") = " + score);
            
            // Also calculate softmax as reference
            float[] softmaxScores = softmax(new float[]{logits[0], logits[1]});
            LogManager.logI(TAG, "  - Reference softmax scores: [" + softmaxScores[0] + ", " + softmaxScores[1] + "]");
            LogManager.logI(TAG, "  - Softmax positive class probability: " + softmaxScores[1]);
            
            LogManager.logI(TAG, "✅ Final score: " + score);
            return score;
        }
        
        LogManager.logW(TAG, "⚠️ Unknown logits format, returning default score 0.5");
        return 0.5f;
    }
    
    /**
     * Sigmoid激活函数（带异常处理）
     */
    private float sigmoid(float x) {
        // 检查输入异常
        if (Float.isNaN(x) || Float.isInfinite(x)) {
            LogManager.logW(TAG, "Sigmoid输入异常: " + x + "，返回默认值0.5");
            return 0.5f;
        }
        
        // 防止数值溢出
        if (x > 700) {
            return 1.0f; // exp(-700) 接近 0，sigmoid 接近 1
        } else if (x < -700) {
            return 0.0f; // exp(700) 接近无穷大，sigmoid 接近 0
        }
        
        try {
            float result = (float) (1.0 / (1.0 + Math.exp(-x)));
            
            // 检查结果异常
            if (Float.isNaN(result) || Float.isInfinite(result)) {
                LogManager.logW(TAG, "Sigmoid计算结果异常: " + result + "，返回默认值0.5");
                return 0.5f;
            }
            
            // 确保结果在[0,1]范围内
            return Math.max(0.0f, Math.min(1.0f, result));
        } catch (Exception e) {
            LogManager.logE(TAG, "Sigmoid计算异常: " + e.getMessage());
            return 0.5f;
        }
    }
    
    /**
     * Softmax函数（带异常处理）
     */
    private float[] softmax(float[] logits) {
        if (logits == null || logits.length == 0) {
            LogManager.logW(TAG, "Softmax输入为空，返回默认结果");
            return new float[]{0.5f};
        }
        
        float[] result = new float[logits.length];
        
        try {
            // 检查输入异常
            for (int i = 0; i < logits.length; i++) {
                if (Float.isNaN(logits[i]) || Float.isInfinite(logits[i])) {
                    LogManager.logW(TAG, "Softmax输入包含异常值: " + logits[i] + "，使用均匀分布");
                    // 返回均匀分布
                    float uniformValue = 1.0f / logits.length;
                    for (int j = 0; j < result.length; j++) {
                        result[j] = uniformValue;
                    }
                    return result;
                }
            }
            
            // 找到最大值以防止数值溢出
            float maxLogit = logits[0];
            for (int i = 1; i < logits.length; i++) {
                if (logits[i] > maxLogit) {
                    maxLogit = logits[i];
                }
            }
            
            // 计算exp值（减去最大值防止溢出）
            float sum = 0.0f;
            for (int i = 0; i < logits.length; i++) {
                float expValue = (float) Math.exp(logits[i] - maxLogit);
                if (Float.isNaN(expValue) || Float.isInfinite(expValue)) {
                    LogManager.logW(TAG, "Softmax exp计算异常，使用均匀分布");
                    float uniformValue = 1.0f / logits.length;
                    for (int j = 0; j < result.length; j++) {
                        result[j] = uniformValue;
                    }
                    return result;
                }
                result[i] = expValue;
                sum += expValue;
            }
            
            // 检查sum是否异常
            if (sum <= 0 || Float.isNaN(sum) || Float.isInfinite(sum)) {
                LogManager.logW(TAG, "Softmax sum异常: " + sum + "，使用均匀分布");
                float uniformValue = 1.0f / logits.length;
                for (int j = 0; j < result.length; j++) {
                    result[j] = uniformValue;
                }
                return result;
            }
            
            // 归一化
            for (int i = 0; i < result.length; i++) {
                result[i] /= sum;
                
                // 检查归一化结果
                if (Float.isNaN(result[i]) || Float.isInfinite(result[i])) {
                    LogManager.logW(TAG, "Softmax归一化结果异常，使用均匀分布");
                    float uniformValue = 1.0f / logits.length;
                    for (int j = 0; j < result.length; j++) {
                        result[j] = uniformValue;
                    }
                    return result;
                }
            }
            
            return result;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Softmax计算异常: " + e.getMessage());
            // 返回均匀分布作为备用
            float uniformValue = 1.0f / logits.length;
            for (int i = 0; i < result.length; i++) {
                result[i] = uniformValue;
            }
            return result;
        }
    }
    
    /**
     * 将文档列表转换为RerankResult列表（保持原始顺序）
     */
    private List<RerankResult> convertToRerankResults(List<String> documents) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            // Use decreasing scores to maintain original order
            float score = 1.0f - (i * 0.01f);
            results.add(new RerankResult(documents.get(i), score, i));
        }
        return results;
    }
    
    /**
     * 检查模型是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized.get();
    }
    
    /**
     * 获取模型路径
     */
    public String getModelPath() {
        return modelPath;
    }
    
    /**
     * 检查ONNX会话状态并尝试恢复
     * @return 会话是否可用
     */
    private boolean checkAndRecoverSession() {
        // Record current thread information
        //LogManager.logD(TAG, "Checking session status [Thread ID: " + Thread.currentThread().getId() + "]");
        
        synchronized (sessionLock) {
            // Record last check time
            lastSessionCheckTime = System.currentTimeMillis();
            
            // Check if session is available
            if (session != null && sessionState == SESSION_STATE_READY) {
                //LogManager.logD(TAG, "Session status is normal, ready to use");
                return true;
            }
            
            // If session is loading, wait for a while
            if (sessionState == SESSION_STATE_LOADING) {
                LogManager.logD(TAG, "Session is loading, waiting...");
                try {
                    // Wait for up to 3 seconds
                    for (int i = 0; i < 30; i++) {
                        // Check every 100 milliseconds
                        Thread.sleep(100);
                        if (session != null && sessionState == SESSION_STATE_READY) {
                            LogManager.logD(TAG, "Session loading completed, ready to use");
                            return true;
                        }
                    }
                } catch (InterruptedException e) {
                    LogManager.logE(TAG, "Waiting for session loading was interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }
            
            // If session is unavailable or in error state, attempt recovery
            if (session == null || sessionState == SESSION_STATE_ERROR) {
                // Check retry count
                if (sessionRetryCount >= MAX_SESSION_RETRY) {
                    LogManager.logE(TAG, "Session recovery failed, maximum retry count reached: " + sessionRetryCount);
                    return false;
                }
                
                LogManager.logD(TAG, "Attempting to recover session, current retry count: " + sessionRetryCount);
                
                // Update session state
                int oldState = sessionState;
                sessionState = SESSION_STATE_LOADING;
                LogManager.logD(TAG, "Session state changed: " + oldState + " -> " + sessionState + " (starting recovery)");
                
                // Increment retry count
                sessionRetryCount++;
                
                try {
                    // First close the previous session
                    if (session != null) {
                        try {
                            session.close();
                            LogManager.logD(TAG, "Closed old ONNX session");
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to close old ONNX session: " + e.getMessage(), e);
                        } finally {
                            session = null;
                        }
                    }
                    
                    // Reinitialize the model
                    try {
                        LogManager.logD(TAG, "Reinitializing rerank model: " + modelPath);
                        
                        // Reset state
                        isInitialized.set(false);
                        isLoading.set(false);
                        
                        // Call initialization method (but avoid infinite recursion)
                        boolean success = initializeInternal();
                        
                        if (success && session != null) {
                            LogManager.logD(TAG, "Rerank model session recovery successful");
                            sessionState = SESSION_STATE_READY;
                            return true;
                        } else {
                            LogManager.logE(TAG, "Rerank model session recovery failed, session is null");
                            sessionState = SESSION_STATE_ERROR;
                            return false;
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to reinitialize rerank model: " + e.getMessage(), e);
                        sessionState = SESSION_STATE_ERROR;
                        return false;
                    }
                } finally {
                    // If session is still null, ensure state is ERROR
                    if (session == null && sessionState != SESSION_STATE_ERROR) {
                        LogManager.logE(TAG, "Session is null but state is not ERROR, correcting state");
                        sessionState = SESSION_STATE_ERROR;
                    }
                    
                    // Record session recovery result
                    boolean success = session != null && sessionState == SESSION_STATE_READY;
                    LogManager.logD(TAG, "Session recovery " + (success ? "successful" : "failed") + 
                           ", final state: " + sessionState + 
                           ", retry count: " + sessionRetryCount + "/" + MAX_SESSION_RETRY);
                }
            }
            
            // Session is still unavailable
            LogManager.logE(TAG, "Session check completed, session still unavailable, state: " + sessionState);
            return false;
        }
    }
    
    /**
     * 内部初始化方法，避免递归调用
     */
    private boolean initializeInternal() {
        // Implement simplified initialization logic here, avoiding calls to checkAndRecoverSession
        try {
            // Check if model file exists
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                LogManager.logE(TAG, "Rerank model file does not exist: " + modelPath);
                return false;
            }
            
            // Create ONNX environment
            if (environment == null) {
                environment = OrtEnvironment.getEnvironment();
            }
            
            // Create session options
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            int threadCount = ConfigManager.getThreads(context);
            sessionOptions.setIntraOpNumThreads(threadCount);
            sessionOptions.setInterOpNumThreads(threadCount);
            sessionOptions.setMemoryPatternOptimization(true);
            sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            
            // Load model
            session = environment.createSession(modelPath, sessionOptions);
            
            if (session != null) {
                isInitialized.set(true);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            LogManager.logE(TAG, "Internal initialization failed: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取当前TokenizerManager实例并验证状态
     * @return 有效的TokenizerManager实例，如果无效则返回null
     */
    private TokenizerManager getCurrentTokenizerManager() {
        try {
            TokenizerManager manager = TokenizerManager.getInstance(context);
            if (manager != null && manager.isInitialized()) {
                LogManager.logD(TAG, "TokenizerManager状态验证通过: 实例有效且已初始化");
                return manager;
            } else {
                LogManager.logW(TAG, "TokenizerManager实例无效或未初始化");
                if (manager == null) {
                    LogManager.logW(TAG, "  - TokenizerManager实例为null");
                } else {
                    LogManager.logW(TAG, "  - TokenizerManager实例存在但未初始化");
                }
                return null;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "获取TokenizerManager实例时发生异常: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 检查TokenizerManager状态的详细信息
     * @return 状态检查报告
     */
    public String checkTokenizerManagerStatus() {
        StringBuilder report = new StringBuilder();
        report.append("=== TokenizerManager状态检查报告 ===\n");
        
        try {
            TokenizerManager manager = TokenizerManager.getInstance(context);
            if (manager == null) {
                report.append("❌ TokenizerManager实例: null\n");
                return report.toString();
            }
            
            report.append("✅ TokenizerManager实例: 存在\n");
            report.append("📋 初始化状态: ").append(manager.isInitialized() ? "已初始化" : "未初始化").append("\n");
            
            if (manager.isInitialized()) {
                // 可以添加更多状态检查
                report.append("🔧 分词器可用性: 可用\n");
            } else {
                report.append("⚠️ 分词器可用性: 不可用\n");
            }
            
        } catch (Exception e) {
            report.append("❌ 状态检查异常: ").append(e.getMessage()).append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * 验证TokenizerManager状态并尝试重新初始化
     * @return 是否成功获取有效的TokenizerManager
     */
    private boolean validateAndReinitializeTokenizer() {
        TokenizerManager manager = getCurrentTokenizerManager();
        if (manager != null) {
            return true;
        }
        
        LogManager.logW(TAG, "TokenizerManager状态无效，尝试重新初始化...");
        try {
            // 尝试重新获取并初始化TokenizerManager
            manager = TokenizerManager.getInstance(context);
            if (manager != null) {
                // 获取模型目录进行重新初始化
                File modelFile = new File(modelPath);
                File modelDir = modelFile.getParentFile();
                if (modelDir != null && modelDir.exists()) {
                    boolean success = manager.initialize(modelDir);
                    if (success) {
                        LogManager.logI(TAG, "TokenizerManager重新初始化成功");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "TokenizerManager重新初始化失败: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        synchronized (sessionLock) {
            try {
                if (session != null) {
                    session.close();
                    session = null;
                }
                if (environment != null) {
                    environment.close();
                    environment = null;
                }
                isInitialized.set(false);
                sessionState = SESSION_STATE_NONE;
                sessionRetryCount = 0;
                lastSessionCheckTime = 0;
                LogManager.logI(TAG, "Rerank model resources have been cleaned up");
            } catch (Exception e) {
                LogManager.logE(TAG, "Error occurred while cleaning up rerank model resources: " + e.getMessage(), e);
            }
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        cleanup();
        super.finalize();
    }
}
