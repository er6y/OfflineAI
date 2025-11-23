package com.example.offlineai.ipc;

import android.content.Context;
import android.util.Log;

import com.example.offlineai.ChatHistoryFilter;
import com.example.offlineai.ChatHistoryManager;
import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.R;
import com.example.offlineai.SettingsFragment;
import com.example.offlineai.RuntimeConfigHolder;
import com.example.offlineai.ipc.RuntimeConfig;
import com.example.offlineai.BackgroundTask;
import com.example.offlineai.BackgroundTaskManager;
import com.example.offlineai.TaskLogBuffer;
import com.example.offlineai.UnifiedForegroundService;
import com.example.offlineai.chat.model.ChatDataItem;
import com.offlineai.mnn.MnnInference;
import com.taobao.meta.avatar.tts.TtsService;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MNN Inference Handler - Unified handler for all MNN capabilities
 * Implements unified InferenceEngine interface using MNN framework
 * 
 * Supported capabilities:
 * - LLM: Text generation with autoregressive loop
 * - Diffusion: Text-to-Image generation (Stable Diffusion)
 * - VL: Vision-Language understanding (future)
 * - Audio: Speech recognition/synthesis (future)
 * 
 * Features:
 * - Multiple backend support (CPU, OpenCL, Vulkan, NNAPI, KleidiAI)
 * - Streaming output with token-by-token callback
 * - Automatic resource management
 * - Multi-modal support
 * 
 * @author OfflineAI Team
 * @version 2.0
 */
public class LocalLLMMNNHandler implements LocalLlmHandler.InferenceEngine {
    private static final String TAG = "LocalLLMMNNHandler";
    
    // ========== Diffusion参数配置 (基于Stable Diffusion官方标准) ==========
    private static final int DEFAULT_DIFFUSION_STEPS = 20;  // 快速模式
    private static final int MAX_DIFFUSION_STEPS = 50;      // 标准模式
    private static final int MIN_DIFFUSION_STEPS = 1;       // 允许最小1步（快速预览，质量差）
    private static final float CFG_SCALE = 7.5f;
    private static final String SCHEDULER = "PLMS";
    
    // Context reference
    private final Context context;
    
    // MNN session handles
    private long llmSessionHandle = 0;      // For LLM text generation
    private long diffusionHandle = 0;       // For Diffusion image generation
    
    // Model type detection
    private enum ModelType { LLM, DIFFUSION, UNKNOWN }
    private ModelType currentModelType = ModelType.UNKNOWN;
    
    // Executor for async operations
    private ExecutorService executorService;  // NOT final - need to recreate after shutdown
    private volatile java.util.concurrent.Future<?> currentTask;
    
    // State management
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // Statistics
    private final AtomicInteger totalTokensGenerated = new AtomicInteger(0);
    private final AtomicInteger currentSessionTokens = new AtomicInteger(0);
    private long generationStartTime = 0;
    private long inferenceStartTime = 0;
    private long inferenceEndTime = 0;  // Record LLM completion time (excluding TTS)
    private long promptTokens = 0;
    private long generatedTokens = 0;

    
    // Store full response for onComplete callback
    private final StringBuilder fullResponseBuilder = new StringBuilder();
    
    // Track if [TEXT:] head has been sent (for LLM models)
    private volatile boolean textHeadSent = false;
    
    // Thread manager for forceful shutdown
    private final ThreadManager threadManager = new ThreadManager();
    
    // Configuration snapshot (for change detection)
    private ConfigSnapshot lastConfigSnapshot = null;
    
    // Runtime for memory stats
    private final Runtime runtime = Runtime.getRuntime();
    
    // Model configuration
    private LocalLlmHandler.ModelConfig modelConfig;
    private LocalLlmHandler.InferenceParams currentParams;
    private LocalLlmHandler.InferenceParams modelFileParams; // Parameters from model config.json
    private String currentModelPath;

    // Diffusion session config tracking (for backend/memory change detection)
    private String lastDiffusionBackend = null;
    private int lastDiffusionMemoryMode = -1;
    
    // TTS (Text-to-Speech) support - Omni Native TTS only
    private boolean hasTtsSupport = false;  // Native Omni TTS (talker.mnn)
    private boolean enableNativeTts = false;  // Whether native Omni TTS is enabled
    private String currentAudioOutputPath = null;
    private java.io.ByteArrayOutputStream ttsAudioBuffer = null;
    
    
    /**
     * Constructor
     * @param context Application context
     */
    public LocalLLMMNNHandler(Context context) {
        this.context = context;
        // CRITICAL: Use cached thread pool to avoid deadlock between playback and consumer threads
        // Single thread executor would cause playback thread to block consumer thread
        this.executorService = Executors.newCachedThreadPool();
        
        LogManager.logI(TAG, "LocalLLMMNNHandler initialized");
    }
    
    @Override
    public String findModelFile(File modelDir) {
        if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
            return null;
        }
        
        // Check for Diffusion model (Text-to-Image)
        File textEncoder = new File(modelDir, "text_encoder.mnn");
        if (textEncoder.exists()) {
            LogManager.logI(TAG, "Found MNN Diffusion model directory: " + modelDir.getAbsolutePath());
            currentModelType = ModelType.DIFFUSION;
            return modelDir.getAbsolutePath();
        }
        
        // Check for LLM model - smart detection with priority
        String mainModelFile = findMainModelFile(modelDir);
        if (mainModelFile != null) {
            LogManager.logI(TAG, "Found MNN LLM model directory: " + modelDir.getAbsolutePath());
            LogManager.logI(TAG, "  Main model file: " + mainModelFile);
            currentModelType = ModelType.LLM;
            return modelDir.getAbsolutePath();
        }
        
        LogManager.logW(TAG, "No MNN model found in: " + modelDir.getAbsolutePath());
        return null;
    }
    
    @Override
    public void initialize(String modelPath, LocalLlmHandler.ModelConfig config, LocalLlmHandler.StreamingCallback callback) throws Exception {
        if (isInitialized.get()) {
            LogManager.logI(TAG, "Handler already initialized, skipping");
            return;
        }
        
        LogManager.logI(TAG, "Initializing MNN handler with model: " + modelPath);
        
        this.modelConfig = config;
        this.currentModelPath = modelPath;
        
        // Validate model directory
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            throw new Exception("Model directory not found: " + modelPath);
        }
        
        // Route to specific initializer based on model type
        if (currentModelType == ModelType.DIFFUSION) {
            initializeDiffusion(modelPath, config, callback);
        } else if (currentModelType == ModelType.LLM) {
            initializeLLM(modelPath, config);
        } else {
            throw new Exception("Unknown model type");
        }
        
        isInitialized.set(true);
        LogManager.logI(TAG, "MNN handler initialized successfully");
    }
    
    /**
     * Initialize LLM model
     */
    private void initializeLLM(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
        // Check for required files - DON'T hardcode filenames!
        File modelDir = new File(modelPath);
        File configFile = new File(modelDir, "config.json");
        File tokenizerFile = new File(modelDir, "tokenizer.txt");
        
        // Find main model file intelligently
        String mainModelFileName = findMainModelFile(modelDir);
        if (mainModelFileName == null) {
            throw new Exception("No main .mnn model file found in directory");
        }
        
        File llmFile = new File(modelDir, mainModelFileName);
        String baseName = mainModelFileName.replace(".mnn", "");
        File weightFile = new File(modelDir, baseName + ".mnn.weight");
        
        LogManager.logI(TAG, "Checking LLM required files:");
        LogManager.logI(TAG, "  config.json: " + (configFile.exists() ? "✓ " + configFile.length() + " bytes" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  " + llmFile.getName() + ": ✓ " + llmFile.length() + " bytes");
        LogManager.logI(TAG, "  " + weightFile.getName() + ": " + (weightFile.exists() ? "✓ " + weightFile.length() + " bytes" : "✗ NOT FOUND (may be embedded)"));
        LogManager.logI(TAG, "  tokenizer.txt: " + (tokenizerFile.exists() ? "✓ " + tokenizerFile.length() + " bytes" : "✗ NOT FOUND"));
        
        // config.json is optional - some models don't have it
        if (!configFile.exists()) {
            LogManager.logW(TAG, "config.json not found - will use default parameters");
        }
        
        // tokenizer.txt is required
        if (!tokenizerFile.exists()) {
            throw new Exception("tokenizer.txt not found in model directory");
        }
        
        // Weight file is optional - can be embedded
        if (!weightFile.exists()) {
            LogManager.logI(TAG, "Weight file not found - assuming weights are embedded in " + llmFile.getName());
        } else {
            LogManager.logI(TAG, "Weight file found - using external weight format");
        }
        
        // Read model config.json to get default parameters
        modelFileParams = readModelConfigParams(configFile);
        
        // Build MNN configuration (use null for params during initialization)
        String mnnConfig = buildMnnConfig(null);
        LogManager.logI(TAG, "MNN Config: " + mnnConfig);
        
        // Create MNN session - MNN backend will handle both embedded and external weights
        llmSessionHandle = MnnInference.createSession(modelPath, mnnConfig);
        
        if (llmSessionHandle == 0) {
            throw new Exception("Failed to create MNN LLM session - check if model format is compatible");
        }
        
        LogManager.logI(TAG, "MNN LLM session created successfully: " + llmSessionHandle);
        
        // Detect TTS support and setup callback
        hasTtsSupport = MnnInference.hasTTS(llmSessionHandle);
        if (hasTtsSupport) {
            LogManager.logI(TAG, "Model supports TTS (Text-to-Speech)");
            // Note: TTS output path is set per-inference in inferenceLLM()
        } else {
            LogManager.logI(TAG, "Model does not support TTS");
        }
    }
    
    /**
     * Initialize Diffusion model
     */
    private void initializeDiffusion(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
        initializeDiffusion(modelPath, config, null);
    }
    
    /**
     * Initialize Diffusion model with optional UI callback
     */
    private void initializeDiffusion(String modelPath, LocalLlmHandler.ModelConfig config, LocalLlmHandler.StreamingCallback callback) throws Exception {
        // Release old Diffusion session if exists (to save kernel cache)
        if (diffusionHandle != 0) {
            LogManager.logI(TAG, "Releasing old Diffusion session to save kernel cache...");
            MnnInference.releaseDiffusion(diffusionHandle);
            diffusionHandle = 0;
            // Give MNN time to save cache
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        
        File modelDir = new File(modelPath);
        
        // Check for required Diffusion files
        File textEncoder = new File(modelDir, "text_encoder.mnn");
        File unet = new File(modelDir, "unet.mnn");
        File vaeDecoder = new File(modelDir, "vae_decoder.mnn");
        File vocabJson = new File(modelDir, "vocab.json");
        File mergesTxt = new File(modelDir, "merges.txt");
        
        LogManager.logI(TAG, "Checking Diffusion required files:");
        LogManager.logI(TAG, "  text_encoder.mnn: " + (textEncoder.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  unet.mnn: " + (unet.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  vae_decoder.mnn: " + (vaeDecoder.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  vocab.json: " + (vocabJson.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  merges.txt: " + (mergesTxt.exists() ? "✓" : "✗ NOT FOUND"));
        
        if (!textEncoder.exists() || !unet.exists() || !vaeDecoder.exists()) {
            throw new Exception("Required Diffusion model files not found");
        }
        
        // Get user-selected backend from RuntimeConfig (fallback to CPU)
        String backendPreference = RuntimeConfigHolder.getBackendPreferenceOrDefault("CPU");
        int backendType = mapBackendToMnnForwardType(backendPreference);
        
        LogManager.logI(TAG, "[Diffusion] Creating session with backend: " + backendPreference + " (type=" + backendType + ")");
        
        // First-time GPU load warning
        if (backendType == 3 || backendType == 7) { // OpenCL or Vulkan
            LogManager.logW(TAG, "[Diffusion] ⚠️ FIRST-TIME GPU LOAD: May take 5-15 minutes to compile kernels!");
        }
        
        // Get memory mode from RuntimeConfig snapshot
        int memoryMode = RuntimeConfigHolder.getDiffusionMemoryModeOrDefault(ConfigManager.DEFAULT_DIFFUSION_MEMORY_MODE);
        String memoryModeStr;
        switch (memoryMode) {
            case 0:
                memoryModeStr = "low";
                break;
            case 1:
                memoryModeStr = "enough";
                break;
            case 2:
                memoryModeStr = "balance";
                break;
            default:
                memoryModeStr = "unknown";
                break;
        }
        LogManager.logI(TAG, "Using memory mode: " + memoryMode + " (" + memoryModeStr + ")");

        // Save current diffusion config snapshot for change detection
        lastDiffusionBackend = backendPreference;
        lastDiffusionMemoryMode = memoryMode;
        
        // ========== 创建 App-Specific 后端专用缓存目录 ==========
        // 使用 app-specific 目录避免权限问题，格式:
        // /Android/data/com.example.offlineai/files/cache/mnn/<model_name>/<backend>/.tempcache
        String backendName = getBackendName(backendType);
        String modelName = new File(modelPath).getName(); // 提取模型名称
        
        File appCacheRoot = new File(context.getExternalFilesDir(null), "cache/mnn");
        File modelCacheDir = new File(appCacheRoot, modelName);
        File backendCacheDir = new File(modelCacheDir, backendName.toLowerCase());
        
        if (!backendCacheDir.exists()) {
            backendCacheDir.mkdirs();
            LogManager.logI(TAG, "[Diffusion] Created app-specific cache directory: " + backendCacheDir.getAbsolutePath());
        }
        String cachePath = backendCacheDir.getAbsolutePath();
        LogManager.logI(TAG, "[Diffusion] Cache path: " + cachePath);
        
        // All printing is now done in JNI layer
        LogManager.logI(TAG, "[Diffusion] Initializing model...");
        
        // Create DiffusionCallback adapter
        MnnInference.DiffusionCallback diffusionCallback = null;
        if (callback != null) {
            diffusionCallback = new MnnInference.DiffusionCallback() {
                @Override
                public boolean onToken(String token) {
                    callback.onToken(token);
                    return false; // Don't stop
                }
                
                @Override
                public boolean onProgress(int progress) {
                    return false; // Don't stop
                }
            };
        }
        
        diffusionHandle = MnnInference.createDiffusion(
            modelPath,
            0, // STABLE_DIFFUSION_1_5
            backendType,
            memoryMode,
            cachePath,  // 传递缓存目录路径
            diffusionCallback    // 传递 callback 到 JNI 层进行打印
        );
        
        if (diffusionHandle == 0) {
            String errorMsg = "[Diffusion] Failed to create session with backend: " + backendPreference;
            LogManager.logE(TAG, errorMsg);
            throw new Exception(errorMsg);
        }
        
        LogManager.logI(TAG, "[Diffusion] Session created successfully, handle=" + diffusionHandle);
        // JNI layer already printed "Models loaded successfully!"
    }
    
    @Override
    public void inference(String prompt, LocalLlmHandler.InferenceParams params,
                         LocalLlmHandler.StreamingCallback callback) {
        // Delegate to unified multimodal method
        inferenceWithConversationHistory(prompt, null, null, params, callback);
    }
    
    @Override
    public void inference(String prompt, List<String> imagePaths,
                         LocalLlmHandler.InferenceParams params,
                         LocalLlmHandler.StreamingCallback callback) {
        // Delegate to unified multimodal method
        inferenceWithConversationHistory(prompt, imagePaths, null, params, callback);
    }
    
    /**
     * NER-specific inference without conversation history
     * Optimized for entity extraction with deterministic output
     * @param prompt NER prompt (already contains extraction instructions + text)
     * @param callback Streaming callback
     */
    public void inferenceNER(String prompt, LocalLlmHandler.StreamingCallback callback) {
        if (!isInitialized.get()) {
            LogManager.logW(TAG, "[NER] Handler not initialized");
            if (callback != null) {
                callback.onError("Model not initialized");
            }
            return;
        }
        
        // CRITICAL FIX: Remove isGenerating check to allow concurrent NER tasks to queue
        // MNN doesn't support parallel inference, but tasks should queue instead of being rejected
        // The actual serialization happens in the executor (single thread pool)
        LogManager.logD(TAG, "[NER] Task submitted to queue (will wait if another NER is running)");
        
        try {
            // Don't set isGenerating here - let tasks queue naturally
            
            // NER-specific parameters (hardcoded for deterministic output)
            final int maxNewTokens = 2048;    // Long enough for JSON output
            
            LogManager.logI(TAG, "[NER] Starting inference: temp=0.0, topK=1, maxTokens=2048");
            LogManager.logI(TAG, "[NER] Prompt length: " + prompt.length() + " chars");
            
            // Disable thinking mode for NER
            try {
                MnnInference.updateConfig(llmSessionHandle, "{\"jinja\":{\"context\":{\"enable_thinking\":false}}}");
                LogManager.logI(TAG, "[THINKING] Thinking mode disabled");
            } catch (Exception e) {
                LogManager.logW(TAG, "[THINKING] Failed to disable thinking mode: " + e.getMessage());
            }
            
            // Set max_new_tokens
            try {
                MnnInference.updateConfig(llmSessionHandle, "{\"max_new_tokens\":" + maxNewTokens + "}");
                LogManager.logI(TAG, "[INFERENCE] max_new_tokens set to " + maxNewTokens);
            } catch (Exception e) {
                LogManager.logW(TAG, "[INFERENCE] Failed to set max_new_tokens: " + e.getMessage());
            }
            
            // Build empty history with only current user message
            List<android.util.Pair<String, String>> emptyHistory = new ArrayList<>();
            emptyHistory.add(new android.util.Pair<>("user", prompt));
            
            // NER-specific stop flag (independent from global shouldStop)
            // This prevents NER from being stopped by other tasks (e.g. embedding completion)
            final AtomicBoolean nerShouldStop = new AtomicBoolean(false);
            LogManager.logI(TAG, "[NER] Using independent stop flag for NER task");
            
            // Submit to executor
            executorService.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // Call inferenceWithHistory with empty history (no conversation context)
                    LogManager.logI(TAG, "[NER] Calling inferenceWithHistory with empty history (independent stop)");
                    Map<String, Long> stats = MnnInference.inferenceWithHistory(
                        llmSessionHandle,
                        emptyHistory,
                        new MnnInference.InferenceCallback() {
                            private final StringBuilder fullResponse = new StringBuilder();
                            
                            @Override
                            public boolean onToken(String token) {
                                fullResponse.append(token);
                                if (callback != null) {
                                    callback.onToken(token);
                                }
                                // Use NER-specific stop flag, not global shouldStop
                                // Return true to stop, false to continue (per InferenceCallback contract)
                                return nerShouldStop.get();
                            }
                            
                            @Override
                            public void onComplete(Map<String, Long> stats) {
                                long elapsedMs = System.currentTimeMillis() - startTime;
                                LogManager.logI(TAG, "[NER] Inference complete: " + elapsedMs + "ms, responseLen=" + fullResponse.length());
                                
                                if (callback != null) {
                                    callback.onComplete(fullResponse.toString());
                                }
                            }
                            
                            @Override
                            public void onError(String error) {
                                LogManager.logE(TAG, "[NER] Inference error: " + error);
                                if (callback != null) {
                                    callback.onError(error);
                                }
                            }
                        }
                    );
                } catch (Exception e) {
                    LogManager.logE(TAG, "[NER] Inference failed", e);
                    if (callback != null) {
                        callback.onError("NER inference failed: " + e.getMessage());
                    }
                }
                // Don't reset isGenerating - not used anymore for NER
            });
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[NER] Failed to start inference", e);
            // Don't reset isGenerating - not used anymore for NER
            if (callback != null) {
                callback.onError("Failed to start NER inference: " + e.getMessage());
            }
        }
    }
    
    /**
     * Multimodal inference with audio support
     * @param prompt Text prompt
     * @param audioPaths Audio file paths
     * @param params Inference parameters
     * @param callback Streaming callback
     */
    public void inferenceWithAudio(String prompt, List<String> audioPaths,
                                   LocalLlmHandler.InferenceParams params,
                                   LocalLlmHandler.StreamingCallback callback) {
        // Delegate to unified multimodal method
        inferenceWithConversationHistory(prompt, null, audioPaths, params, callback);
    }
    
    /**
     * Multimodal inference with images and audio
     * @param prompt Text prompt
     * @param imagePaths Image file paths
     * @param audioPaths Audio file paths
     * @param params Inference parameters
     * @param callback Streaming callback
     */
    public void inferenceMultimodal(String prompt, List<String> imagePaths, List<String> audioPaths,
                                    LocalLlmHandler.InferenceParams params,
                                    LocalLlmHandler.StreamingCallback callback) {
        // Delegate to unified multimodal method
        inferenceWithConversationHistory(prompt, imagePaths, audioPaths, params, callback);
    }
    
    /**
     * Inference with conversation history (sliding window)
     * Automatically loads and filters markdown history
     * @param userPrompt Current user prompt (text only)
     * @param imagePaths Current round image paths (will be added as <img> tags)
     * @param audioPaths Current round audio paths (will be added as <audio> tags)
     * @param params Inference parameters
     * @param callback Streaming callback
     */
    public void inferenceWithConversationHistory(String userPrompt,
                                                  List<String> imagePaths,
                                                  List<String> audioPaths,
                                                  LocalLlmHandler.InferenceParams params,
                                                  LocalLlmHandler.StreamingCallback callback) {
        // ========== CRITICAL: Fail-safe check for state consistency ==========
        // If isInitialized is false but we're called, something went wrong (e.g. app restart)
        // Force re-initialization to recover from inconsistent state
        if (!isInitialized.get()) {
            LogManager.logW(TAG, "[FAILSAFE] Handler not initialized in inferenceWithConversationHistory - forcing re-initialization");
            try {
                if (currentModelPath != null && modelConfig != null) {
                    // Re-create executor if terminated
                    if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
                        LogManager.logW(TAG, "[FAILSAFE] ExecutorService is terminated, recreating...");
                        executorService = Executors.newCachedThreadPool();
                        LogManager.logI(TAG, "[FAILSAFE] ExecutorService recreated");
                    }
                    
                    initializeLLM(currentModelPath, modelConfig);
                    isInitialized.set(true);
                    LogManager.logI(TAG, "[FAILSAFE] Handler re-initialized successfully in inferenceWithConversationHistory");
                } else {
                    LogManager.logE(TAG, "[FAILSAFE] Cannot re-initialize: currentModelPath or modelConfig is null");
                    if (callback != null) {
                        callback.onError("模型未初始化，请重新加载模型");
                    }
                    return;
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[FAILSAFE] Re-initialization failed in inferenceWithConversationHistory", e);
                if (callback != null) {
                    callback.onError("模型初始化失败: " + e.getMessage());
                }
                return;
            }
        }
        
        if (isGenerating.get()) {
            LogManager.logW(TAG, "Inference already in progress");
            if (callback != null) {
                callback.onError("Inference already in progress");
            }
            return;
        }
        
        // Route to specific inference based on model type
        if (currentModelType == ModelType.DIFFUSION) {
            // Diffusion models don't use history, just call inferenceDiffusion
            inferenceDiffusion(userPrompt, params, callback);
            return;
        } else if (currentModelType != ModelType.LLM) {
            // Unknown model type
            LogManager.logE(TAG, "Unknown model type: " + currentModelType);
            if (callback != null) {
                callback.onError("Unknown model type");
            }
            return;
        }
        
        try {
            // Get history rounds and chat folder from RuntimeConfig snapshot
            int historyRounds = RuntimeConfigHolder.getHistoryRoundsOrDefault(ConfigManager.DEFAULT_HISTORY_ROUNDS);
            LogManager.logI(TAG, "[HISTORY] Starting inference with " + historyRounds + " rounds of history");
            
            // Load current chat history from markdown
            String chatFolder = RuntimeConfigHolder.getCurrentChatFolderOrNull();
            List<ChatDataItem> allMessages = new ArrayList<>();
            if (chatFolder != null && !chatFolder.isEmpty()) {
                allMessages = ChatHistoryManager.loadConversation(context, chatFolder);
                if (allMessages == null) {
                    allMessages = new ArrayList<>();
                }
            }
            LogManager.logI(TAG, "[HISTORY] Loaded " + allMessages.size() + " messages from markdown");
            
            // Get system prompt from params or RuntimeConfig
            String systemPrompt = "";
            if (params != null && params.systemPrompt != null && !params.systemPrompt.isEmpty()) {
                systemPrompt = params.systemPrompt;
            } else {
                RuntimeConfig cfg = RuntimeConfigHolder.get();
                systemPrompt = (cfg != null && cfg.systemPrompt != null) ? cfg.systemPrompt : "";
            }
            
            // Build filtered history using ChatHistoryFilter (removes <img>/<audio> from history)
            List<ChatHistoryFilter.PromptItem> history = ChatHistoryFilter.buildHistoryForInference(
                context, allMessages, systemPrompt, historyRounds);
            
            LogManager.logI(TAG, "[HISTORY] Built history with " + history.size() + " items (excluding current)");
            
            // Convert filtered history to List<Pair<String, String>> format for JNI
            List<android.util.Pair<String, String>> historyPairs = new ArrayList<>();
            for (ChatHistoryFilter.PromptItem item : history) {
                historyPairs.add(new android.util.Pair<>(item.role, item.content));
            }
            
            // Build current user message with <img>/<audio> tags
            StringBuilder currentUserMessage = new StringBuilder();
            
            // Add image tags for current round
            if (imagePaths != null && !imagePaths.isEmpty()) {
                for (String imagePath : imagePaths) {
                    currentUserMessage.append("<img>").append(imagePath).append("</img>");
                }
                LogManager.logI(TAG, "[HISTORY] Added " + imagePaths.size() + " <img> tag(s) to current message");
            }
            
            // Add audio tags for current round
            if (audioPaths != null && !audioPaths.isEmpty()) {
                for (String audioPath : audioPaths) {
                    currentUserMessage.append("<audio>").append(audioPath).append("</audio>");
                }
                LogManager.logI(TAG, "[HISTORY] Added " + audioPaths.size() + " <audio> tag(s) to current message");
            }
            
            // Add user text
            currentUserMessage.append(userPrompt);
            
            // Add current user message to history
            historyPairs.add(new android.util.Pair<>("user", currentUserMessage.toString()));
            
            LogManager.logI(TAG, "[HISTORY] Total history size: " + historyPairs.size() + " messages (including current)");
            LogManager.logI(TAG, "[HISTORY] Current user message length: " + currentUserMessage.length() + " chars");
            
            // Use inferenceWithHistory API (C++ will pass directly to MNN, no accumulation)
            performHistoryInference(historyPairs, params, callback);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[HISTORY] Error building history: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Failed to build history: " + e.getMessage());
            }
        }
    }
    
    // ✅ DELETED: performSimpleInference() method removed
    // No longer needed - all inference goes through performHistoryInference()
    
    /**
     * Internal method to perform inference with history (uses inferenceWithHistory API)
     * @param history List of (role, content) pairs
     * @param params Inference parameters
     * @param callback Streaming callback
     */
    private void performHistoryInference(List<android.util.Pair<String, String>> history,
                                         LocalLlmHandler.InferenceParams params,
                                         LocalLlmHandler.StreamingCallback callback) {
        // ========== CRITICAL: Fail-safe check for state consistency ==========
        // If isInitialized is false but we're called, something went wrong (e.g. app restart)
        // Force re-initialization to recover from inconsistent state
        if (!isInitialized.get()) {
            LogManager.logW(TAG, "[FAILSAFE] Handler not initialized but inference requested - forcing re-initialization");
            try {
                if (currentModelPath != null && modelConfig != null) {
                    initializeLLM(currentModelPath, modelConfig);
                    isInitialized.set(true);
                    LogManager.logI(TAG, "[FAILSAFE] Handler re-initialized successfully");
                } else {
                    LogManager.logE(TAG, "[FAILSAFE] Cannot re-initialize: currentModelPath or modelConfig is null");
                    if (callback != null) {
                        callback.onError("模型未初始化，请重新加载模型");
                    }
                    return;
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[FAILSAFE] Re-initialization failed", e);
                if (callback != null) {
                    callback.onError("模型初始化失败: " + e.getMessage());
                }
                return;
            }
        }
        
    // ========== Configuration Change Detection ==========
        try {
            ConfigSnapshot currentConfig = ConfigSnapshot.fromCurrentSettings(context);
            
            if (lastConfigSnapshot == null) {
                // First inference: save snapshot
                lastConfigSnapshot = currentConfig;
                LogManager.logI(TAG, "[CONFIG] First inference, snapshot saved");
            } else {
                // Compare with last snapshot
                ReloadPlan plan = lastConfigSnapshot.compareWith(currentConfig);
                
                if (plan.needAnyReload()) {
                    LogManager.logI(TAG, "[CONFIG] Configuration changes detected:");
                    LogManager.logI(TAG, "[CONFIG] " + plan.getDetailedSummary());
                    
                    // Execute reload plan
                    if (plan.needReloadLlm) {
                        LogManager.logI(TAG, "[CONFIG] Reloading LLM Session...");
                        
                        if (callback != null) {
                            callback.onToken("<debug>\n检测到重要配置变化，正在重新加载模型...\n");
                            callback.onToken(plan.getDetailedSummary() + "\n");
                        }
                        
                        // Save old session handle for potential rollback
                        long oldSessionHandle = llmSessionHandle;
                        
                        try {
                            // Release old session
                            if (llmSessionHandle != 0) {
                                MnnInference.destroySession(llmSessionHandle);
                                llmSessionHandle = 0;
                                isInitialized.set(false);
                                LogManager.logI(TAG, "[CONFIG] Old LLM Session released");
                            }
                            
                            // Reinitialize with new config
                            // Note: initializeLLM() will call buildMnnConfig() with updated settings
                            initializeLLM(currentModelPath, modelConfig);
                            isInitialized.set(true);
                            LogManager.logI(TAG, "[CONFIG] LLM Session reloaded successfully with new config");
                            
                            if (callback != null) {
                                callback.onToken("模型重新加载完成\n</debug>\n\n");
                            }
                        } catch (Exception reloadException) {
                            // Reload failed - keep old snapshot, don't update
                            LogManager.logE(TAG, "[CONFIG] LLM Session reload failed", reloadException);
                            if (callback != null) {
                                callback.onError("❌ 模型重新加载失败，请检查配置: " + reloadException.getMessage());
                            }
                            isInitialized.set(false);
                            // Don't update lastConfigSnapshot - keep old one for retry
                            return;  // Abort config update, inference will be skipped
                        }
                    }
                    
                    // TTS reload removed - now handled by TtsAdapter in RagQaFragment
                    
                    if (plan.needReloadDiffusion) {
                        LogManager.logI(TAG, "[CONFIG] Reloading Diffusion Session...");
                        
                        if (callback != null) {
                            callback.onToken("<debug>\nDiffusion配置变化，重新加载...\n");
                        }
                        
                        // Release old diffusion session
                        if (diffusionHandle != 0) {
                            MnnInference.releaseDiffusion(diffusionHandle);
                            diffusionHandle = 0;
                            LogManager.logI(TAG, "[CONFIG] Old Diffusion Session released");
                        }
                        
                        // Reinitialize will happen on next generateImage() call
                        LogManager.logI(TAG, "[CONFIG] Diffusion Session will reinit on next use");
                        
                        if (callback != null) {
                            callback.onToken("Diffusion配置已更新\n</debug>\n");
                        }
                    }
                    
                    // Update snapshot
                    lastConfigSnapshot = currentConfig;
                    LogManager.logI(TAG, "[CONFIG] Configuration snapshot updated");
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[CONFIG] Failed to check/reload configuration", e);
            if (callback != null) {
                callback.onError("配置检测失败: " + e.getMessage());
            }
            // Don't abort inference on config check failure
        }
        // ====================================================
        isGenerating.set(true);
        shouldStop.set(false);
        fullResponseBuilder.setLength(0);
        textHeadSent = false; // Reset text head flag for each inference
        generationStartTime = System.currentTimeMillis();
        inferenceStartTime = System.currentTimeMillis();
        
        // ========== Clean up previous TTS temp files ==========
        // CRITICAL: Clean at inference start, not when TTS thread starts
        // This ensures previous playback has completed before deletion
        final String tempDir = context.getCacheDir().getAbsolutePath() + "/tts_temp";
        File tempDirFile = new File(tempDir);
        if (tempDirFile.exists()) {
            LogManager.logI(TAG, "[TTS] Cleaning up previous temp files");
            File[] oldFiles = tempDirFile.listFiles();
            if (oldFiles != null) {
                for (File oldFile : oldFiles) {
                    boolean deleted = oldFile.delete();
                    if (!deleted) {
                        LogManager.logW(TAG, "[TTS] Failed to delete temp file: " + oldFile.getName());
                    }
                }
            }
        } else {
            tempDirFile.mkdirs();
            LogManager.logI(TAG, "[TTS] Created temp directory: " + tempDir);
        }
        // ======================================================
        
        // Set TTS output path based on model capability and user selection
        // CRITICAL: Must handle all 6 scenarios (3 TTS modes × 2 model types)
        // 
        // Scenario Matrix:
        // 1. Omni + None       → Disable native TTS (set path to null)
        // 2. Omni + Native     → Enable native TTS (set output path)
        // 3. Omni + External   → Disable native TTS, external TTS will be called later
        // 4. Non-Omni + None   → No action needed (model doesn't support native TTS)
        // 5. Non-Omni + Native → ERROR: User misconfiguration, warn user
        // 6. Non-Omni + External → No action needed, external TTS will be called later
        
        if (enableNativeTts) {
            // User selected "Native TTS"
            if (hasTtsSupport) {
                // Scenario 2: Omni + Native → Enable native TTS
                try {
                    String chatFolderPath = RuntimeConfigHolder.getCurrentChatFolderOrNull();
                    
                    if (chatFolderPath != null && !chatFolderPath.isEmpty()) {
                        File chatFolder = new File(chatFolderPath);
                        if (chatFolder.exists() && chatFolder.isDirectory() && chatFolder.canWrite()) {
                            long timestamp = System.currentTimeMillis();
                            currentAudioOutputPath = new File(chatFolder, "audio_" + timestamp + "_ai.wav").getAbsolutePath();
                            MnnInference.setTtsOutputPath(llmSessionHandle, currentAudioOutputPath);
                            LogManager.logI(TAG, "[TTS] Native TTS enabled: " + currentAudioOutputPath);
                        }
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] Failed to set output path", e);
                }
            } else {
                // Scenario 5: Non-Omni + Native → User misconfiguration
                LogManager.logE(TAG, "[TTS] ERROR: User selected native TTS but model doesn't support it!");
                LogManager.logE(TAG, "[TTS] Current model: " + currentModelPath);
                LogManager.logE(TAG, "[TTS] Native TTS requires Omni model with talker.mnn");
                showToast(R.string.toast_tts_model_not_supported);
            }
        } else if (hasTtsSupport) {
            // Omni model but user selected non-native TTS
            // Disable native TTS (System/External TTS handled by TtsAdapter in RagQaFragment)
            MnnInference.setTtsOutputPath(llmSessionHandle, null);
            LogManager.logI(TAG, "[TTS] Native TTS disabled (TTS handled by TtsAdapter)");
        }
        // Non-Omni models: System/External TTS handled by TtsAdapter in RagQaFragment
        
        // ========== Configure thinking mode (Qwen3 support) ==========
        // Reference: MNN iOS app LLMInferenceEngineWrapper.mm Line 637-643
        // Qwen3 defaults to enable_thinking=true, must explicitly disable if needed
        RuntimeConfig cfgForThinking = RuntimeConfigHolder.get();
        boolean thinkingEnabled = !(cfgForThinking != null && cfgForThinking.noThinking);
        try {
            String thinkingConfig = String.format(
                "{\"jinja\":{\"context\":{\"enable_thinking\":%s}}}",
                thinkingEnabled ? "true" : "false"
            );
            MnnInference.updateConfig(llmSessionHandle, thinkingConfig);
            LogManager.logI(TAG, "[THINKING] Thinking mode " + (thinkingEnabled ? "enabled" : "disabled"));
        } catch (Exception e) {
            LogManager.logW(TAG, "[THINKING] Failed to set thinking mode: " + e.getMessage());
        }
        // =============================================================
        
        // ========== Configure max_new_tokens (runtime parameter) ==========
        // CRITICAL: C++ layer reads max_new_tokens from config_json_ at inference time
        // Must set this via updateConfig() to override the default 2048
        int maxNewTokens = RuntimeConfigHolder.getMaxNewTokensOrDefault(ConfigManager.DEFAULT_MAX_NEW_TOKENS);
        try {
            String maxTokensConfig = String.format("{\"max_new_tokens\":%d}", maxNewTokens);
            MnnInference.updateConfig(llmSessionHandle, maxTokensConfig);
            LogManager.logI(TAG, "[INFERENCE] max_new_tokens set to " + maxNewTokens);
        } catch (Exception e) {
            LogManager.logW(TAG, "[INFERENCE] Failed to set max_new_tokens: " + e.getMessage());
        }
        // ==================================================================
        
        currentTask = executorService.submit(() -> {
            try {
                // Use inferenceWithHistory API (C++ will pass directly to MNN)
                Map<String, Long> stats = MnnInference.inferenceWithHistory(
                    llmSessionHandle,
                    history,
                    new MnnInference.InferenceCallback() {
                        @Override
                        public boolean onToken(String token) {
                            if (shouldStop.get()) {
                                return true;
                            }
                            
                            // Send [TEXT:] head before first token (for main flow to detect and close <debug>)
                            if (!textHeadSent && callback != null) {
                                callback.onToken("\n[TEXT:]");
                                textHeadSent = true;
                            }
                            
                            fullResponseBuilder.append(token);
                            currentSessionTokens.incrementAndGet();
                            totalTokensGenerated.incrementAndGet();
                            
                            if (callback != null) {
                                callback.onToken(token);
                            }
                            
                            // TTS processing removed - now handled by TtsAdapter in RagQaFragment
                            
                            return false;
                        }
                        
                        @Override
                        public void onComplete(Map<String, Long> statistics) {
                            // CRITICAL: Reset textHeadSent to prevent performance stats from being processed by TTS
                            textHeadSent = false;
                            
                            // Record inference end time (for accurate performance stats excluding TTS)
                            inferenceEndTime = System.currentTimeMillis();
                            long totalTime = inferenceEndTime - generationStartTime;
                            
                            if (statistics != null) {
                                promptTokens = statistics.getOrDefault("prompt_len", 0L);
                                generatedTokens = statistics.getOrDefault("decode_len", 0L);
                            }
                            
                            LogManager.logI(TAG, "[HISTORY] Inference complete: " + generatedTokens + " tokens in " + totalTime + "ms");
                            
                            // Check TTS audio generation after inference completes
                            // CRITICAL: Must handle all 6 scenarios consistently with performHistoryInference()
                            // 
                            // Scenario Matrix (Audio Generation Phase):
                            // 1. Omni + None       → No audio generation
                            // 2. Omni + Native     → Check native TTS audio file
                            // 3. Omni + External   → Generate external TTS
                            // 4. Non-Omni + None   → No audio generation
                            // 5. Non-Omni + Native → Should have been caught earlier (user misconfiguration)
                            // 6. Non-Omni + External → Generate external TTS
                            
                            if (enableNativeTts) {
                                // User selected "Native TTS"
                                if (currentAudioOutputPath != null) {
                                    // Scenario 2: Omni + Native → Check native TTS audio
                                    File audioFile = new File(currentAudioOutputPath);
                                    if (audioFile.exists() && audioFile.length() > 0) {
                                        LogManager.logI(TAG, "[TTS] Native TTS audio generated: " + currentAudioOutputPath + 
                                                      " (" + audioFile.length() + " bytes)");
                                        if (callback != null) {
                                            callback.onToken("\n\n[AUDIO:" + currentAudioOutputPath + "]");
                                        }
                                    } else {
                                        LogManager.logE(TAG, "[TTS] Native TTS audio file not generated or empty");
                                        showToast(R.string.toast_tts_generation_failed);
                                    }
                                }
                                // Scenario 5: Non-Omni + Native → Already warned in performHistoryInference()
                                // No audio will be generated, user already saw error toast
                            }
                            
                            // TTS processing removed - now handled by TtsAdapter in RagQaFragment
                            // Mark inference complete and send performance stats
                            isGenerating.set(false);
                            String perfStats = getPerformanceStats();
                            
                            // Build full response: text + performance
                            // Note: Debug info is accumulated and saved by RagQueryManager in main process
                            // LocalLLMMNNHandler runs in InferenceService process and cannot access
                            // main process TaskLogBuffer, so we don't try to get debug info here
                            String fullResponse = fullResponseBuilder.toString() + perfStats;
                            
                            if (callback != null) {
                                callback.onToken(perfStats);
                                callback.onComplete(fullResponse);
                            }
                        }
                        
                        @Override
                        public void onError(String error) {
                            LogManager.logE(TAG, "[HISTORY] Inference error: " + error);
                            isGenerating.set(false);
                            
                            if (callback != null) {
                                callback.onError(error);
                            }
                        }
                    }
                );
                
            } catch (Exception e) {
                LogManager.logE(TAG, "[HISTORY] Exception during inference: " + e.getMessage(), e);
                isGenerating.set(false);
                
                if (callback != null) {
                    callback.onError("Inference failed: " + e.getMessage());
                }
            } finally {
                // Ensure generation state and task reference are always cleared
                isGenerating.set(false);
                currentTask = null;
            }
        });
    }
    
    // ✅ DELETED: inferenceLLM() method removed
    // All LLM inference now goes through inferenceWithConversationHistory()
    // This eliminates code duplication and ensures consistent TTS handling
    
    @Override
    public void stopInference() {
        LogManager.logI(TAG, "Stopping inference");
        shouldStop.set(true);

        // Try to cancel running task to avoid lingering threads on model switch
        // Use cancel(false) to avoid interrupting threads running inside native code
        try {
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(false);
                LogManager.logD(TAG, "Cancelled current inference task (no thread interrupt)");
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to cancel inference task: " + e.getMessage());
        }

        // ✗ DO NOT call resetSession() here!
        // Calling resetSession() while Native is still cleaning up (embedding, KV cache)
        // causes resource conflicts → SIGSEGV crash
        // 
        // ✓ Correct approach (following MNN official example):
        // - Set shouldStop flag (done above)
        // - Let Native check the flag in onToken callback
        // - Native will stop gracefully and clean up safely
        // - Only call reset() when starting a NEW session
        
        LogManager.logD(TAG, "Stop signal sent, waiting for Native to finish gracefully");

        // Wait for inference to stop gracefully
        // Key insight: shouldStop flag will be checked at next token generation
        // Normal stop time: 1-5 seconds (time to generate next token)
        // 
        // TEST MODE: No timeout - wait indefinitely to see how long MNN actually takes
        try {
            // Check if executor is still usable
            if (executorService != null && !executorService.isShutdown() && !executorService.isTerminated()) {
                executorService.submit(() -> {
                    long startTime = System.currentTimeMillis();
                    int waits = 0;
                    
                    // Wait indefinitely, but log progress every 5 seconds
                    while (isGenerating.get()) {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        waits++;
                        
                        // Log every 5 seconds
                        if (waits % 50 == 0) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            LogManager.logI(TAG, String.format("Still waiting for MNN to stop... elapsed: %.1fs", elapsed / 1000.0));
                        }
                    }
                    
                    // Log final stop time
                    long totalTime = System.currentTimeMillis() - startTime;
                    LogManager.logI(TAG, String.format("✓ MNN stopped successfully after %.3fs", totalTime / 1000.0));
                });
            } else {
                LogManager.logW(TAG, "ExecutorService is not available for stop monitoring");
            }
        } catch (Throwable ignored) {}
    }
    
    @Override
    public void release() {
        LogManager.logI(TAG, "Releasing MNN handler");

        // Release LLM session
        if (llmSessionHandle != 0) {
            MnnInference.destroySession(llmSessionHandle);
            llmSessionHandle = 0;
        }
        
        // Release Diffusion session
        if (diffusionHandle != 0) {
            MnnInference.releaseDiffusion(diffusionHandle);
            diffusionHandle = 0;
        }

        isInitialized.set(false);
        isGenerating.set(false);

        // Force stop executor to avoid lingering threads
        try {
            java.util.List<Runnable> dropped = executorService.shutdownNow();
            LogManager.logD(TAG, "Executor shutdownNow, dropped tasks: " + (dropped != null ? dropped.size() : 0));
        } catch (Exception e) {
            LogManager.logW(TAG, "Executor shutdownNow failed: " + e.getMessage());
        }
        currentTask = null;

        LogManager.logI(TAG, "MNN handler released");
    }
    
    /**
     * Reset MNN session - clear KV cache and conversation history (LLM only)
     */
    public void resetSession() {
        if (llmSessionHandle != 0) {
            LogManager.logI(TAG, "Resetting MNN LLM session (clearing KV cache)");
            MnnInference.resetSession(llmSessionHandle);
            LogManager.logI(TAG, "MNN LLM session reset completed");
        } else {
            LogManager.logW(TAG, "Cannot reset session: LLM session not initialized");
        }
    }
    
    /**
     * Build MNN configuration JSON from RuntimeConfig settings and InferenceParams
     * Priority: User params (runtime) > RuntimeConfig settings > Model config.json
     * @param params Inference parameters (optional, uses defaults if null)
     * @return JSON configuration string
     */
    private String buildMnnConfig(LocalLlmHandler.InferenceParams params) {
        // Get configuration from RuntimeConfig snapshot
        RuntimeConfig cfg = RuntimeConfigHolder.get();
        int maxSeqLength = RuntimeConfigHolder.getMaxSequenceLengthOrDefault(ConfigManager.DEFAULT_MAX_SEQUENCE_LENGTH);
        int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
        int maxNewTokens = RuntimeConfigHolder.getMaxNewTokensOrDefault(ConfigManager.DEFAULT_MAX_NEW_TOKENS);
        String backendPreference = RuntimeConfigHolder.getBackendPreferenceOrDefault("CPU");

        // Map backend preference to MNN backend type
        String mnnBackend = mapBackendToMnn(backendPreference);

        // Log the actual backend being used
        LogManager.logI(TAG, String.format("🔍 Backend resolution: requested=%s, resolved=%s",
                backendPreference, mnnBackend));

        // CRITICAL: Do NOT set chunk size (keep default 0 = no chunking)
        // Reason: Setting chunk > 0 causes vision_pad tokens to be split across chunks,
        // which triggers multiple embedding() calls. After first embedding(), MNN clears
        // mVisionEmbeddings, causing SIGBUS crash on second embedding() call.
        // ChatMNN also uses default (no chunk) to avoid this issue.
        // Reference: libs/mnn/transformers/llm/engine/src/llm.cpp Line 708
        final int CHUNK_SIZE = 0;  // 0 = no chunking (MNN default behavior)

        // KV Cache size limit calculation
        // -1 = unlimited (recommended for best performance)
        final int KV_CACHE_LIMIT_MB = -1;  // -1 for unlimited, or set MB limit per layer
        int kvcacheLimitBytes = (KV_CACHE_LIMIT_MB == -1) ? -1 : KV_CACHE_LIMIT_MB * 1024 * 1024;

        // Auto-detect audio support (Qwen2.5-Omni, etc.)
        File audioModel = new File(currentModelPath, "audio.mnn");
        boolean hasAudioSupport = audioModel.exists();
        if (hasAudioSupport) {
            LogManager.logI(TAG, "AUTO-DETECTED: audio.mnn found, enabling audio support (Omni model)");
        }

        // Build configuration using MnnInference.ConfigBuilder
        MnnInference.ConfigBuilder builder = new MnnInference.ConfigBuilder()
                .backendType(mnnBackend)
                .threadNum(threads)
                .precision("low")  // Use FP16 for better performance
                .memory("low")     // Hardcoded: runtime dequantization to save memory (4B model)
                .power("high")     // Hardcoded: use big cores for performance
                .maxAllTokens(maxSeqLength)  // CRITICAL: Total context window (input + output)
                .maxNewTokens(maxNewTokens)  // Single response generation limit
                // .chunk() NOT called - use MNN default (0 = no chunking) to avoid vision_pad split
                .kvcacheLimit(kvcacheLimitBytes)   // CRITICAL: -1 = unlimited, or bytes limit per layer
                .reuseKv(false)    // CRITICAL: Disable KV cache reuse (like ChatMNN)
                //.useMmap(true)   // Use mmap for model weights, bug here, android do not open
                .kvcacheMmap(false); // CRITICAL: Disable KV cache mmap to avoid /tmp crash on Android

        // Enable audio support if audio.mnn exists
        if (hasAudioSupport) {
            builder.isAudio(true).audioModel("audio.mnn");
            // Qwen2.5-Omni audio pad token (from official config)
            builder.audioPad(151666);
        }

        // TTS configuration based on RuntimeConfig (Omni native TTS)
        File talkerModel = new File(currentModelPath, "talker.mnn");
        boolean hasTtsSupport = talkerModel.exists();
        String noneOption = context.getString(R.string.settings_tts_model_none);
        String ttsModelSelection = RuntimeConfigHolder.getTtsModelOrDefault(noneOption);
        String nativeOmniName = context.getString(R.string.settings_tts_model_native_omni);

        this.enableNativeTts = false;
        if (hasTtsSupport && nativeOmniName.equals(ttsModelSelection)) {
            // Native Omni TTS
            this.enableNativeTts = true;
            int ditSteps = RuntimeConfigHolder.getTtsDitStepsOrDefault(ConfigManager.DEFAULT_TTS_DIT_STEPS);
            builder.ditSteps(ditSteps);  // User-configured DiT steps
            builder.ditSolver(1);        // 1=Euler (fast), 4=RK4 (4x slower but better)
            LogManager.logI(TAG, "🔊 Native TTS enabled: dit_steps=" + ditSteps + ", dit_solver=1");
        } else {
            // TTS disabled or handled by TtsAdapter (System/External TTS)
            LogManager.logI(TAG, "🔊 Non-Omni TTS selected: " + ttsModelSelection + " (handled by TtsAdapter)");
        }

        // Add temp path for weight mmap (not for kvcache)
        File cacheDir = context.getCacheDir();
        builder.tmpPath(cacheDir.getAbsolutePath());

        // Parameter priority logic:
        // 1. If priorityManual=true (手动参数优先): use manual params from RuntimeConfig
        // 2. If priorityManual=false (非手动参数优先): do NOT set params, let MNN read model config.json
        boolean priorityManual = (cfg != null && cfg.priorityManualParams);

        if (priorityManual && params != null) {
            // 手动参数优先模式：设置runtime parameters（覆盖模型config.json）
            float temperature = params.getTemperature();
            float topP = params.getTopP();
            int topK = params.getTopK();
            float repeatPenalty = params.getRepetitionPenalty();

            builder.temperature(temperature)
                    .topP(topP)
                    .topK(topK);

            LogManager.logI(TAG, String.format(
                    "Using manual params (priority) - temp=%.2f, top_p=%.2f, top_k=%d, repeat_penalty=%.2f",
                    temperature, topP, topK, repeatPenalty));

            // Note: MNN does not support repeat_penalty in ConfigBuilder, ignored for now
        } else {
            // 非手动参数优先模式：不设置采样参数，让MNN读取模型config.json
            LogManager.logI(TAG, "Using model config.json parameters (not setting runtime params)");
        }

        String config = builder.build();

        String kvLimitStr = (KV_CACHE_LIMIT_MB == -1) ? "unlimited" : KV_CACHE_LIMIT_MB + "MB/layer";

        if (priorityManual && params != null) {
            LogManager.logI(TAG, String.format(
                    "Built MNN config - Backend: %s, Threads: %d, MaxAllTokens: %d, MaxNewTokens: %d, Chunk: %d, KVLimit: %s, SamplingParams: manual_priority",
                    mnnBackend, threads, maxSeqLength, maxNewTokens, CHUNK_SIZE, kvLimitStr));
        } else {
            LogManager.logI(TAG, String.format(
                    "Built MNN config - Backend: %s, Threads: %d, MaxAllTokens: %d, MaxNewTokens: %d, Chunk: %d, KVLimit: %s, SamplingParams: model_default",
                    mnnBackend, threads, maxSeqLength, maxNewTokens, CHUNK_SIZE, kvLimitStr));
        }

        return config;
    }
    
    /**
     * Read inference parameters from model config.json
     * @param configFile config.json file
     * @return InferenceParams or null if not found
     */
    private LocalLlmHandler.InferenceParams readModelConfigParams(File configFile) {
        try {
            // Read config.json
            StringBuilder content = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            
            org.json.JSONObject config = new org.json.JSONObject(content.toString());
            
            // Try to read sampling parameters from config
            LocalLlmHandler.InferenceParams params = new LocalLlmHandler.InferenceParams();
            boolean foundParams = false;
            
            if (config.has("temperature")) {
                params.setTemperature((float) config.getDouble("temperature"));
                foundParams = true;
            }
            if (config.has("top_p")) {
                params.setTopP((float) config.getDouble("top_p"));
                foundParams = true;
            }
            if (config.has("top_k")) {
                params.setTopK(config.getInt("top_k"));
                foundParams = true;
            }
            if (config.has("repetition_penalty")) {
                params.setRepetitionPenalty((float) config.getDouble("repetition_penalty"));
                foundParams = true;
            }
            
            if (foundParams) {
                LogManager.logI(TAG, String.format(
                    "Read model config params - temp=%.2f, top_p=%.2f, top_k=%d",
                    params.getTemperature(), params.getTopP(), params.getTopK()));
                return params;
            }
            
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to read parameters from config.json: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Calculate token generation rate (LLM only, excluding TTS time)
     */
    private double calculateTokenRate() {
        // Use inferenceEndTime if available (LLM completed), otherwise use current time
        long endTime = inferenceEndTime > 0 ? inferenceEndTime : System.currentTimeMillis();
        long elapsedTime = inferenceStartTime > 0 ? endTime - inferenceStartTime : 0;
        if (elapsedTime > 0 && currentSessionTokens.get() > 0) {
            return currentSessionTokens.get() * 1000.0 / elapsedTime;
        }
        return 0.0;
    }
    
    /**
     * Generate complete performance statistics report
     */
    public String getPerformanceStats() {
        double tokenRate = calculateTokenRate();
        // Use inferenceEndTime for accurate LLM-only time (excluding TTS)
        long endTime = inferenceEndTime > 0 ? inferenceEndTime : System.currentTimeMillis();
        long elapsedTime = inferenceStartTime > 0 ? endTime - inferenceStartTime : 0;
        
        // Get JVM memory info
        long jvmMaxMemory = runtime.maxMemory();
        long jvmTotalMemory = runtime.totalMemory();
        long jvmUsedMemory = jvmTotalMemory - runtime.freeMemory();
        
        // Simplified performance statistics report format
        StringBuilder stats = new StringBuilder();
        stats.append("\n\n<performance>\n");
        
        // Add model name at the beginning
        if (currentModelPath != null) {
            String modelName = new File(currentModelPath).getName();
            stats.append(String.format("Model: %s\n", modelName));
        }
        
        stats.append(String.format("tokens: %d • Time: %.2fs • Rate: %.2f token/s • JVMUsedMem: %dMB",
            currentSessionTokens.get(),
            elapsedTime / 1000.0,
            tokenRate,
            jvmUsedMemory / (1024 * 1024)
        ));
        
        // Configuration info (from RuntimeConfig snapshot)
        int maxNewTokens = RuntimeConfigHolder.getMaxNewTokensOrDefault(ConfigManager.DEFAULT_MAX_NEW_TOKENS);
        int maxSeqLength = RuntimeConfigHolder.getMaxSequenceLengthOrDefault(ConfigManager.DEFAULT_MAX_SEQUENCE_LENGTH);
        int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
        String backendPreference = RuntimeConfigHolder.getBackendPreferenceOrDefault("CPU");
        
        stats.append(String.format("\n   • maxNewTokens: %d tokens\n", maxNewTokens));
        stats.append(String.format("   • maxSeqLength: %d tokens\n", maxSeqLength));
        stats.append(String.format("   • threads: %d\n", threads));
        stats.append(String.format("   • Backend: %s\n", backendPreference));
        
        // Display actual inference parameters if available
        if (currentParams != null) {
            stats.append(String.format("   • mnnParam: temp=%.2f, top_p=%.2f, top_k=%d, repeat_penalty=%.2f\n",
                currentParams.getTemperature(), currentParams.getTopP(),
                currentParams.getTopK(), currentParams.getRepetitionPenalty()));
        }
        
        stats.append("</performance>\n");
        
        return stats.toString();
    }
    
    /**
     * Get statistics
     */
    public String getStatistics() {
        if (!isInitialized.get()) {
            return "Engine not initialized";
        }
        
        return String.format(
            "Total tokens: %d, Current session: %d, Token rate: %.2f tok/s",
            totalTokensGenerated.get(),
            currentSessionTokens.get(),
            calculateTokenRate()
        );
    }
    
    /**
     * Get backend name string for cache directory naming
     */
    private static String getBackendName(int backendType) {
        switch (backendType) {
            case 0: return "CPU";
            case 3: return "OpenCL";
            case 7: return "Vulkan";
            case 6: return "NNAPI";
            default: return "Unknown";
        }
    }
    
    /**
     * Map backend preference to MNN backend type
     * NO FALLBACK - Use exactly what user selected for easier debugging
     * @param backendPreference Backend preference from settings
     * @return MNN backend type string (MNN uses "npu" internally for NNAPI)
     */
    private String mapBackendToMnn(String backendPreference) {
        String mnnBackend;
        switch (backendPreference) {
            case "OPENCL":
                mnnBackend = "opencl";
                break;
                
            case "VULKAN":
                mnnBackend = "vulkan";
                break;
                
            case "NNAPI":
                mnnBackend = "npu";  // MNN uses "npu" for Android NNAPI
                break;
                
            case "CPU":
            default:
                mnnBackend = "cpu";
                break;
        }
        
        LogManager.logI(TAG, String.format("🎯 Backend mapping: '%s' -> MNN '%s' (NO FALLBACK)", 
            backendPreference, mnnBackend));
        return mnnBackend;
    }
    
    /**
     * Get total tokens generated across all sessions
     * @return Total token count
     */
    public int getTotalTokensGenerated() {
        return totalTokensGenerated.get();
    }
    
    /**
     * Get tokens generated in current session
     * @return Current session token count
     */
    public int getCurrentSessionTokens() {
        return currentSessionTokens.get();
    }
    
    /**
     * Check if handler is initialized
     * @return true if initialized
     */
    public boolean isInitialized() {
        return isInitialized.get();
    }
    
    /**
     * Check if inference is in progress
     * @return true if generating
     */
    public boolean isGenerating() {
        return isGenerating.get();
    }
    
    /**
     * Diffusion image generation inference
     */
    private void inferenceDiffusion(String prompt, LocalLlmHandler.InferenceParams params,
                                    LocalLlmHandler.StreamingCallback callback) {
        if (isGenerating.get()) {
            callback.onError("Image generation already in progress");
            return;
        }
        
        LogManager.logI(TAG, "Starting image generation with prompt: " + prompt);
        
        // Submit async task (including initialization to avoid blocking UI)
        executorService.submit(() -> {
            try {
                isGenerating.set(true);
                shouldStop.set(false);

                // Create background task snapshot for Diffusion image generation
                String diffusionTaskId = null;
                try {
                    String chatFolder = RuntimeConfigHolder.getCurrentChatFolderOrNull();
                    java.util.Map<String, String> extras = new java.util.HashMap<>();
                    extras.put("chatFolder", chatFolder != null ? chatFolder : "");
                    extras.put("modelPath", currentModelPath != null ? currentModelPath : "");
                    extras.put("prompt", prompt != null ? prompt : "");

                    BackgroundTask task = BackgroundTaskManager.getInstance().createTask(
                            BackgroundTask.TaskType.DIFFUSION,
                            "Diffusion image generation",
                            true,
                            extras
                    );
                    diffusionTaskId = task.getId();
                    LogManager.logI(TAG, "[TASK] Created Diffusion background task, id=" + diffusionTaskId);
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TASK] Failed to create Diffusion background task: " + e.getMessage(), e);
                }
                
                // Detect Diffusion backend/memory changes even when no LLM inference is running
                // This ensures that changing backend (e.g. OPENCL -> CPU) or diffusion memory mode
                // via RuntimeConfig will take effect for pure image generation workflows.
                String currentBackend = RuntimeConfigHolder.getBackendPreferenceOrDefault("CPU");
                int currentMemoryMode = RuntimeConfigHolder.getDiffusionMemoryModeOrDefault(ConfigManager.DEFAULT_DIFFUSION_MEMORY_MODE);

                boolean backendChanged = (lastDiffusionBackend != null
                        && !lastDiffusionBackend.equalsIgnoreCase(currentBackend));
                boolean memoryChanged = (lastDiffusionMemoryMode >= 0
                        && lastDiffusionMemoryMode != currentMemoryMode);

                if (diffusionHandle != 0 && (backendChanged || memoryChanged)) {
                    LogManager.logI(TAG, String.format(
                            "[CONFIG][Diffusion] Runtime config changed (backend: %s -> %s, memory: %d -> %d), forcing session reload",
                            lastDiffusionBackend, currentBackend,
                            lastDiffusionMemoryMode, currentMemoryMode));

                    try {
                        MnnInference.releaseDiffusion(diffusionHandle);
                        LogManager.logI(TAG, "[CONFIG][Diffusion] Old Diffusion session released due to config change");
                    } catch (Exception e) {
                        LogManager.logW(TAG, "[CONFIG][Diffusion] Failed to release old session: " + e.getMessage());
                    }

                    diffusionHandle = 0;
                    // Update snapshot so next initializeDiffusion() will record the new values
                    lastDiffusionBackend = currentBackend;
                    lastDiffusionMemoryMode = currentMemoryMode;
                }
                
                // Check if diffusion handle is valid
                if (!isInitialized.get() || diffusionHandle == 0) {
                    LogManager.logW(TAG, "Diffusion handle lost, reinitializing...");
                    try {
                        if (this.currentModelPath != null) {
                            // Reinitialize (JNI will print cache status and loading progress)
                            initializeDiffusion(this.currentModelPath, null, callback);
                        } else {
                            callback.onError("Diffusion not initialized and no model path available");
                            return;
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to reinitialize Diffusion: " + e.getMessage(), e);
                        callback.onError("Failed to reinitialize Diffusion: " + e.getMessage());
                        return;
                    }
                } else {
                    // Already initialized
                    LogManager.logI(TAG, "Using existing Diffusion session (handle=" + diffusionHandle + ")");
                }
                
                // Get current chat folder from RuntimeConfig snapshot
                String chatFolderPath = RuntimeConfigHolder.getCurrentChatFolderOrNull();
                
                if (chatFolderPath == null || chatFolderPath.isEmpty()) {
                    LogManager.logE(TAG, "No chat folder set, cannot save image");
                    callback.onError("对话文件夹未初始化，请重试");
                    return;
                }
                
                File outputDir = new File(chatFolderPath);
                if (!outputDir.exists()) {
                    LogManager.logE(TAG, "Chat folder doesn't exist: " + chatFolderPath);
                    callback.onError("对话文件夹不存在，请重试");
                    return;
                }
                
                String outputPath = new File(outputDir, "diffusion_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
                LogManager.logI(TAG, "Output path: " + outputPath);
                
                // Get steps and seed from RuntimeConfig snapshot
                int steps = RuntimeConfigHolder.getDiffusionStepsOrDefault(ConfigManager.DEFAULT_DIFFUSION_STEPS);
                steps = Math.max(MIN_DIFFUSION_STEPS, Math.min(steps, MAX_DIFFUSION_STEPS));
                LogManager.logI(TAG, "Using steps from config: " + steps);
                
                int seed;
                boolean useRandomSeed = RuntimeConfigHolder.isDiffusionSeedRandomOrDefault(ConfigManager.DEFAULT_DIFFUSION_SEED_RANDOM);
                if (useRandomSeed) {
                    seed = -1;
                    LogManager.logI(TAG, "Using random seed");
                } else {
                    seed = RuntimeConfigHolder.getDiffusionSeedOrDefault(ConfigManager.DEFAULT_DIFFUSION_SEED);
                    LogManager.logI(TAG, "Using fixed seed from config: " + seed);
                }
                
                LogManager.logI(TAG, "Diffusion params: steps=" + steps + ", cfg=" + CFG_SCALE + ", scheduler=" + SCHEDULER + ", seed=" + seed);
                
                // Generate image (debug tag already opened at the beginning)
                final long startTime = System.currentTimeMillis();
                final String diffusionTaskIdFinal = diffusionTaskId;

                boolean success = MnnInference.generateImage(
                    diffusionHandle,
                    prompt,
                    outputPath,
                    steps,
                    seed,
                    new MnnInference.DiffusionCallback() {
                        @Override
                        public boolean onProgress(int progress) {
                            if (shouldStop.get()) {
                                LogManager.logI(TAG, "Image generation cancelled by user");
                                return false;
                            }
                            // Update Diffusion background task progress
                            if (diffusionTaskIdFinal != null) {
                                try {
                                    BackgroundTaskManager.getInstance().updateTask(
                                            diffusionTaskIdFinal,
                                            BackgroundTask.TaskState.RUNNING,
                                            progress,
                                            "Image generation " + progress + "%"
                                    );
                                } catch (Exception e) {
                                    LogManager.logE(TAG, "[TASK] Failed to update Diffusion background task progress: " + e.getMessage(), e);
                                }
                            }
                            return true;
                        }
                        
                        @Override
                        public boolean onToken(String message) {
                            callback.onToken(message);
                            return !shouldStop.get();
                        }
                    }
                );
                
                long duration = System.currentTimeMillis() - startTime;

                // JNI layer already closed </debug> tag

                // If user requested stop, treat task as cancelled and skip result handling
                if (shouldStop.get()) {
                    LogManager.logI(TAG, "Diffusion generation stopped by user (post-generateImage)");
                    if (diffusionTaskIdFinal != null) {
                        try {
                            BackgroundTaskManager.getInstance().updateTask(
                                    diffusionTaskIdFinal,
                                    BackgroundTask.TaskState.CANCELLED,
                                    0,
                                    "Image generation cancelled by user"
                            );
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TASK] Failed to finalize Diffusion task as CANCELLED: " + e.getMessage(), e);
                        }
                    }
                    return;
                }

                // Check if generation succeeded
                if (success && new File(outputPath).exists()) {
                    LogManager.logI(TAG, "Image generated successfully in " + duration + "ms: " + outputPath);

                    // Output performance stats
                    String perfStats = getDiffusionPerformanceStats(duration, steps, seed, this.currentModelPath);

                    // NOTE: MD persistence is handled by RagQueryManager.onSuccess() for unified architecture.
                    // Handler only sends data via callback, Manager handles persistence + cursor update.
                    // This ensures consistent pos management across LLM/Diffusion models.

                    // Output image path to streaming callback
                    // RagQueryManager will accumulate this and persist to MD
                    callback.onToken("\n\n[IMAGE:" + outputPath + "]");

                    // Output performance stats to streaming callback
                    callback.onToken("\n\n" + perfStats);

                    callback.onComplete("Image generation completed");

                    if (diffusionTaskIdFinal != null) {
                        try {
                            BackgroundTaskManager.getInstance().updateTask(
                                    diffusionTaskIdFinal,
                                    BackgroundTask.TaskState.COMPLETED,
                                    100,
                                    "Image generation completed"
                            );
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TASK] Failed to finalize Diffusion task as COMPLETED: " + e.getMessage(), e);
                        }
                    }
                } else {
                    String errorMsg = "Failed to generate image";
                    LogManager.logE(TAG, errorMsg);
                    callback.onError(errorMsg);

                    if (diffusionTaskIdFinal != null) {
                        try {
                            BackgroundTaskManager.getInstance().updateTask(
                                    diffusionTaskIdFinal,
                                    BackgroundTask.TaskState.FAILED,
                                    0,
                                    errorMsg
                            );
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TASK] Failed to finalize Diffusion task as FAILED: " + e.getMessage(), e);
                        }
                    }
                }

            } catch (Exception e) {
                LogManager.logE(TAG, "Error during image generation", e);
                callback.onError("Image generation error: " + e.getMessage());
            } finally {
                isGenerating.set(false);
            }
        });
    }
    
    /**
     * Generate performance statistics for Diffusion
     */
    private String getDiffusionPerformanceStats(long duration, int steps, int seed, String modelPath) {
        float totalSec = duration / 1000.0f;
        float secPerStep = totalSec / steps;

        // Get JVM memory info
        long jvmMaxMemory = runtime.maxMemory();
        long jvmTotalMemory = runtime.totalMemory();
        long jvmUsedMemory = jvmTotalMemory - runtime.freeMemory();

        StringBuilder stats = new StringBuilder();
        stats.append("<performance>\n");

        // Model name
        if (modelPath != null) {
            String modelName = new File(modelPath).getName();
            stats.append(String.format("Model: %s\n", modelName));
        }

        // Performance metrics
        stats.append(String.format("Time: %.1fs • Speed: %.2fs/step • JVMUsedMem: %dMB\n",
            totalSec, secPerStep, jvmUsedMemory / (1024 * 1024)));

        // Configuration parameters
        String backendPreference = SettingsFragment.getBackendPreference(context);
        String memoryModeStr = ConfigManager.getDiffusionMemoryModeString(context);
        stats.append(String.format("   • Backend: %s\n", backendPreference));
        stats.append(String.format("   • Memory Mode: %s\n", memoryModeStr));

        // Diffusion specific parameters
        stats.append(String.format("   • diffusionParam: steps=%d, cfg=7.5, scheduler=PLMS, seed=%s, size=512x512\n",
            steps, seed < 0 ? "Random" : String.valueOf(seed)));

        stats.append("</performance>\n");

        return stats.toString();
    }
    
    /**
     * Map backend preference to MNN Forward Type integer (for Diffusion)
     * @param backendPreference Backend from settings
     * @return MNN Forward Type integer
     */
    private int mapBackendToMnnForwardType(String backendPreference) {
        if (backendPreference == null) {
            LogManager.logW(TAG, "⚠️ Backend preference is null, using CPU (0)");
            return 0; // MNN_FORWARD_CPU
        }
        
        int forwardType;
        switch (backendPreference.toUpperCase()) {
            case "VULKAN":
                forwardType = 7; // MNN_FORWARD_VULKAN
                break;
                
            case "OPENCL":
            case "GPU":
                forwardType = 3; // MNN_FORWARD_OPENCL
                break;
                
            case "NNAPI":
                forwardType = 6; // MNN_FORWARD_NN (Android NNAPI)
                break;
                
            case "CPU":
            default:
                forwardType = 0; // MNN_FORWARD_CPU
                break;
        }
        
        LogManager.logI(TAG, String.format("🎯 Backend mapping: '%s' -> MNN ForwardType %d (NO FALLBACK)", 
            backendPreference, forwardType));
        return forwardType;
    }
    
    // ========== TTS (Text-to-Speech) Implementation ==========
    
    // ========== Deprecated TTS Callback Code (replaced by C++ synchronous file writing) ==========
    // All TTS file writing is now done in C++ layer (mnn_jni.cpp::writeWavFile)
    // like Diffusion does for images - synchronous and reliable
    
    /**
     * Get TTS audio output path from last inference (if available)
     * File is written synchronously by C++ layer, like Diffusion images
     * @return Audio file path or null
     */
    public String getTtsAudioOutputPath() {
        // Get path from C++ layer (returns path only if file was successfully written)
        String cppPath = MnnInference.getTtsOutputPath(llmSessionHandle);
        if (cppPath != null) {
            // Verify file exists
            File audioFile = new File(cppPath);
            if (audioFile.exists() && audioFile.length() > 0) {
                LogManager.logI(TAG, "[TTS] Verified audio file: " + cppPath + " (" + audioFile.length() + " bytes)");
                return cppPath;
            } else {
                LogManager.logW(TAG, "[TTS] File not found or empty: " + cppPath);
                return null;
            }
        }
        return null;
    }
    
    /**
     * Check if handler supports TTS
     * @return true if TTS is supported
     */
    public boolean hasTtsSupport() {
        return hasTtsSupport;
    }
    
    // ========== External TTS Support ==========
    
    /**
     * Build MNN config for External TTS model (bert-vits2-MNN)
     * External TTS is loaded as a standard MNN LLM model
     * 
     * @param modelPath Path to External TTS model directory
     * @return JSON config string
     */
    private String buildExternalTtsConfig(String modelPath) {
        try {
            MnnInference.ConfigBuilder builder = new MnnInference.ConfigBuilder();
            
            // Get backend from settings
            String backendPreference = SettingsFragment.getBackendPreference(context);
            String mnnBackend = mapBackendToMnn(backendPreference);
            
            // Basic config
            builder.backendType(mnnBackend);  // Use same backend as main model
            builder.threadNum(4);
            builder.precision("low");
            builder.memory("low");
            builder.power("high");
            
            // Token limits (for TTS models, these control audio length)
            builder.maxAllTokens(4096);
            builder.maxNewTokens(2048);  // bert-vits2 may need more tokens for longer audio
            
            // KV cache
            builder.reuseKv(false);
            builder.kvcacheMmap(false);
            
            // TTS-specific: Enable audio output
            builder.isAudio(true);
            
            // Temp path for weight mmap
            File cacheDir = context.getCacheDir();
            builder.tmpPath(cacheDir.getAbsolutePath());
            
            String config = builder.build();
            LogManager.logI(TAG, "[TTS] External TTS config: " + config);
            return config;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Failed to build External TTS config", e);
            return "{}";
        }
    }
    
    // TTS helper methods removed - now handled by TtsAdapter in RagQaFragment
    
    /**
     * Show toast on main thread
     */
    private void showToast(int stringResId) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
            android.widget.Toast.makeText(context, stringResId, android.widget.Toast.LENGTH_SHORT).show()
        );
    }
    
    // ========== ASR (Automatic Speech Recognition) ==========
    // ASR functionality has been moved to AsrAdapter.java for better separation of concerns
    // ASR is now independent of LLM inference engines
    
    /**
     * Smart model file detection - handles multi-modal models correctly
     * Priority:
     * 1. config.json is HIGHEST priority - must use if exists
     * 2. Regex match: contains "llm", "qwen", "model", "main" (case-insensitive)
     * 3. If only one .mnn file exists, use it
     * 4. Exclude auxiliary models: visual, audio, dit, text_encoder, talker
     */
    private String findMainModelFile(File modelDir) {
        try {
            // Step 1: config.json is HIGHEST priority
            File configFile = new File(modelDir, "config.json");
            if (configFile.exists()) {
                String configContent = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
                org.json.JSONObject config = new org.json.JSONObject(configContent);
                
                // Check for llm_model field
                if (config.has("llm_model")) {
                    String llmModel = config.getString("llm_model");
                    File modelFile = new File(modelDir, llmModel);
                    if (modelFile.exists()) {
                        LogManager.logI(TAG, "✓ Found main model from config.json: " + llmModel);
                        return llmModel;
                    } else {
                        LogManager.logE(TAG, "✗ config.json specifies llm_model=" + llmModel + " but file not found!");
                        throw new RuntimeException("config.json specifies missing model: " + llmModel);
                    }
                }
            }
            
            // Step 2: List all .mnn files (exclude .weight)
            File[] allMnnFiles = modelDir.listFiles((dir, name) -> 
                name.endsWith(".mnn") && !name.endsWith(".weight")
            );
            
            if (allMnnFiles == null || allMnnFiles.length == 0) {
                return null;
            }
            
            // Step 3: If only ONE .mnn file, it must be the main model
            if (allMnnFiles.length == 1) {
                LogManager.logI(TAG, "✓ Only one .mnn file found, using: " + allMnnFiles[0].getName());
                return allMnnFiles[0].getName();
            }
            
            // Step 4: Filter out known auxiliary models
            String[] auxiliaryPatterns = {
                "visual", "vision", "image",
                "audio", "speech",
                "dit", "diffusion",
                "text_encoder", "encoder",
                "talker", "tts"
            };
            
            java.util.List<File> candidates = new java.util.ArrayList<>();
            for (File file : allMnnFiles) {
                String fileName = file.getName().toLowerCase();
                boolean isAuxiliary = false;
                for (String pattern : auxiliaryPatterns) {
                    if (fileName.contains(pattern)) {
                        isAuxiliary = true;
                        break;
                    }
                }
                if (!isAuxiliary) {
                    candidates.add(file);
                }
            }
            
            if (candidates.isEmpty()) {
                LogManager.logW(TAG, "⚠ All .mnn files seem to be auxiliary, using first one anyway");
                return allMnnFiles[0].getName();
            }
            
            // Step 5: Regex match - contains "llm", "qwen", "model", "main"
            String[] keywordPatterns = {"llm", "qwen", "model", "main"};
            for (String keyword : keywordPatterns) {
                for (File candidate : candidates) {
                    if (candidate.getName().toLowerCase().contains(keyword)) {
                        LogManager.logI(TAG, "✓ Found main model by keyword '" + keyword + "': " + candidate.getName());
                        return candidate.getName();
                    }
                }
            }
            
            // Step 6: Use first candidate
            String result = candidates.get(0).getName();
            LogManager.logI(TAG, "✓ Using first candidate as main model: " + result);
            if (candidates.size() > 1) {
                LogManager.logW(TAG, "⚠ Multiple candidates found, recommend adding config.json:");
                for (File c : candidates) {
                    LogManager.logW(TAG, "    - " + c.getName());
                }
            }
            return result;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error finding main model file", e);
            return null;
        }
    }
    
    @Override
    public String getEngineType() {
        return "MNN";
    }
    
    // ========== Inner Classes: Configuration Management ==========
    
    /**
     * Configuration Snapshot for change detection
     * Records all configuration that may require model/service reload
     * 
     * Configuration Reload Matrix:
     * ┌────────────────────────────┬────────────────────┬────────────────────────┐
     * │ Configuration              │ Reload Required    │ Update Method          │
     * ├────────────────────────────┼────────────────────┼────────────────────────┤
     * │ Backend (CPU/GPU/Vulkan)   │ ✅ LLM Session     │ Release + Reinitialize │
     * │ Thread Count               │ ✅ LLM Session     │ Release + Reinitialize │
     * │ Max Sequence Length        │ ✅ LLM Session     │ Release + Reinitialize │
     * │ Max New Tokens             │ ✅ LLM Session     │ Release + Reinitialize │
     * │ Omni TTS Enable/Disable    │ ✅ LLM Session     │ Release + Reinitialize │
     * │ TTS DiT Steps (Omni)       │ ✅ LLM Session     │ Release + Reinitialize │
     * │ External TTS Model         │ ✅ TTS Service     │ Release old service    │
     * │ Diffusion Memory Mode      │ ✅ Diffusion       │ Release + Reinitialize │
     * │ Temperature/TopP/TopK      │ ❌ Runtime         │ updateConfig()         │
     * │ Thinking Mode              │ ❌ Runtime         │ updateConfig()         │
     * │ Diffusion Steps/Seed       │ ❌ Runtime         │ Per-generation params  │
     * └────────────────────────────┴────────────────────┴────────────────────────┘
     */
    private static class ConfigSnapshot {
        // LLM Session configuration (requires reload if changed)
        String backend;          // CPU, OpenCL, Vulkan, NNAPI
        int threadCount;         // Thread number
        int maxSequenceLength;   // Total context window
        int maxNewTokens;        // Max tokens per response
        
        // TTS configuration
        String ttsModel;         // none, system, external_xxx, native_omni
        boolean omniTtsEnabled;  // Whether Omni native TTS is enabled
        int ttsDitSteps;         // TTS DiT steps (affects Session creation for Omni)
        
        // Diffusion configuration (requires reload if changed)
        int diffusionMemoryMode; // Diffusion memory mode (affects Session creation)
        // Note: diffusionSteps and diffusionSeed are runtime params, no reload needed
        
        /**
         * Create snapshot from current RuntimeConfig settings (inference process)
         */
        static ConfigSnapshot fromCurrentSettings(Context context) {
            ConfigSnapshot snapshot = new ConfigSnapshot();

            RuntimeConfig cfg = RuntimeConfigHolder.get();

            // LLM Session configuration
            snapshot.backend = RuntimeConfigHolder.getBackendPreferenceOrDefault("CPU");
            snapshot.threadCount = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
            snapshot.maxSequenceLength = RuntimeConfigHolder.getMaxSequenceLengthOrDefault(ConfigManager.DEFAULT_MAX_SEQUENCE_LENGTH);
            snapshot.maxNewTokens = RuntimeConfigHolder.getMaxNewTokensOrDefault(ConfigManager.DEFAULT_MAX_NEW_TOKENS);

            // TTS configuration
            String noneOption = context.getString(R.string.settings_tts_model_none);
            snapshot.ttsModel = RuntimeConfigHolder.getTtsModelOrDefault(noneOption);
            String omniName = context.getString(R.string.settings_tts_model_native_omni);
            snapshot.omniTtsEnabled = omniName.equals(snapshot.ttsModel);
            snapshot.ttsDitSteps = RuntimeConfigHolder.getTtsDitStepsOrDefault(ConfigManager.DEFAULT_TTS_DIT_STEPS);

            // Diffusion configuration
            snapshot.diffusionMemoryMode = RuntimeConfigHolder.getDiffusionMemoryModeOrDefault(ConfigManager.DEFAULT_DIFFUSION_MEMORY_MODE);

            return snapshot;
        }
        
        private interface ConfigChangeRule {
            void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan);
        }

        private static final ConfigChangeRule[] RULES = new ConfigChangeRule[] {
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (!safeEquals(previous.backend, current.backend)) {
                        plan.needReloadLlm = true;
                        plan.needReloadDiffusion = true;
                        plan.reasons.add("Backend: " + previous.backend + " → " + current.backend);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.threadCount != current.threadCount) {
                        plan.needReloadLlm = true;
                        plan.reasons.add("Threads: " + previous.threadCount + " → " + current.threadCount);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.maxSequenceLength != current.maxSequenceLength) {
                        plan.needReloadLlm = true;
                        plan.reasons.add("MaxSeqLen: " + previous.maxSequenceLength + " → " + current.maxSequenceLength);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.maxNewTokens != current.maxNewTokens) {
                        plan.needReloadLlm = true;
                        plan.reasons.add("MaxNewTokens: " + previous.maxNewTokens + " → " + current.maxNewTokens);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.omniTtsEnabled != current.omniTtsEnabled) {
                        plan.needReloadLlm = true;
                        String before = previous.omniTtsEnabled ? "enabled" : "disabled";
                        String after = current.omniTtsEnabled ? "enabled" : "disabled";
                        plan.reasons.add("Omni TTS: " + before + " → " + after);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (!safeEquals(previous.ttsModel, current.ttsModel)
                            && !previous.omniTtsEnabled && !current.omniTtsEnabled) {
                        plan.needReloadExternalTts = true;
                        plan.reasons.add("TTS Model: " + previous.ttsModel + " → " + current.ttsModel);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.omniTtsEnabled && previous.ttsDitSteps != current.ttsDitSteps) {
                        plan.needReloadLlm = true;
                        plan.reasons.add("TTS DiT Steps: " + previous.ttsDitSteps + " → " + current.ttsDitSteps);
                    }
                }
            },
            new ConfigChangeRule() {
                @Override
                public void apply(ConfigSnapshot previous, ConfigSnapshot current, ReloadPlan plan) {
                    if (previous.diffusionMemoryMode != current.diffusionMemoryMode) {
                        plan.needReloadDiffusion = true;
                        plan.reasons.add("Diffusion Memory Mode: " + previous.diffusionMemoryMode + " → " + current.diffusionMemoryMode);
                    }
                }
            }
        };

        /**
         * Compare with another snapshot and generate reload plan
         */
        ReloadPlan compareWith(ConfigSnapshot current) {
            ReloadPlan plan = new ReloadPlan();
            for (ConfigChangeRule rule : RULES) {
                rule.apply(this, current, plan);
            }
            return plan;
        }
        
        private static boolean safeEquals(Object a, Object b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
    }
    
    /**
     * Reload Plan - describes what needs to be reloaded
     */
    private static class ReloadPlan {
        boolean needReloadLlm = false;           // Need to reload LLM Session
        boolean needReloadExternalTts = false;   // Need to reload external TTS
        boolean needReloadDiffusion = false;     // Need to reload Diffusion Session
        
        java.util.List<String> reasons = new java.util.ArrayList<>();
        
        boolean needAnyReload() {
            return needReloadLlm || needReloadExternalTts || needReloadDiffusion;
        }
        
        String getSummary() {
            if (!needAnyReload()) {
                return "No changes";
            }
            return String.join(", ", reasons);
        }
        
        String getDetailedSummary() {
            StringBuilder sb = new StringBuilder();
            if (needReloadLlm) {
                sb.append("• LLM Session需要重载\n");
            }
            if (needReloadExternalTts) {
                sb.append("• TTS服务需要重载\n");
            }
            if (needReloadDiffusion) {
                sb.append("• Diffusion Session需要重载\n");
            }
            if (reasons.size() > 0) {
                sb.append("变化详情：").append(String.join(", ", reasons));
            }
            return sb.toString();
        }
    }
    
    // ========== Inner Class: Thread Manager ==========
    
    /**
     * Thread Manager for forceful shutdown
     * Manages all inference-related threads and provides interrupt-based stop
     */
    private static class ThreadManager {
        private static final String TAG = "ThreadManager";
        
        private final java.util.Set<java.util.concurrent.Future<?>> activeTasks = 
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private final AtomicBoolean globalStopFlag = new AtomicBoolean(false);
        
        void registerTask(java.util.concurrent.Future<?> task) {
            if (task != null) {
                activeTasks.add(task);
                LogManager.logD(TAG, "Task registered, total active: " + activeTasks.size());
            }
        }
        
        void unregisterTask(java.util.concurrent.Future<?> task) {
            if (task != null) {
                activeTasks.remove(task);
                LogManager.logD(TAG, "Task unregistered, total active: " + activeTasks.size());
            }
        }
        
        AtomicBoolean getGlobalStopFlag() {
            return globalStopFlag;
        }
        
        /**
         * Stop all tasks FORCEFULLY with interrupt
         * @param timeoutMs Maximum wait time before force clear
         * @return Number of tasks interrupted
         */
        int stopAllTasksForcefully(long timeoutMs) {
            LogManager.logI(TAG, "========== FORCE STOPPING ALL TASKS ==========");
            LogManager.logI(TAG, "Active tasks before stop: " + activeTasks.size());
            
            // Set global stop flag
            globalStopFlag.set(true);
            
            // Force interrupt all tasks
            int interruptedCount = 0;
            for (java.util.concurrent.Future<?> task : activeTasks) {
                if (!task.isDone()) {
                    task.cancel(true);  // interrupt=true
                    interruptedCount++;
                    LogManager.logI(TAG, "Force interrupted task #" + interruptedCount);
                }
            }
            
            LogManager.logI(TAG, "Interrupted " + interruptedCount + " tasks, waiting " + timeoutMs + "ms...");
            
            // Wait briefly for threads to respond
            long startTime = System.currentTimeMillis();
            while (!activeTasks.isEmpty() && (System.currentTimeMillis() - startTime < timeoutMs)) {
                activeTasks.removeIf(java.util.concurrent.Future::isDone);
                
                if (!activeTasks.isEmpty()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            // Force clear remaining tasks
            int remainingTasks = activeTasks.size();
            if (remainingTasks > 0) {
                LogManager.logW(TAG, "⚠️ " + remainingTasks + " tasks still running, force clearing");
                activeTasks.clear();
            }
            
            LogManager.logI(TAG, "All tasks stopped, interrupted=" + interruptedCount + ", remaining=" + remainingTasks);
            LogManager.logI(TAG, "========== FORCE STOP COMPLETE ==========");
            
            return interruptedCount;
        }
        
        void reset() {
            activeTasks.clear();
            globalStopFlag.set(false);
            LogManager.logI(TAG, "Thread manager reset");
        }
        
        int getActiveTaskCount() {
            activeTasks.removeIf(java.util.concurrent.Future::isDone);
            return activeTasks.size();
        }
        
        boolean hasActiveTasks() {
            return getActiveTaskCount() > 0;
        }
    }
    
    /**
     * Calculate relative path from base directory to target directory
     * Example: base=/storage/emulated/0/Download/OfflineAIData/tts/bert-vits2-MNN
     *          target=/data/user/0/com.example.offlineai/cache/mnn/bert-vits2-MNN/tts
     *          result=../../../../../../../data/user/0/com.example.offlineai/cache/mnn/bert-vits2-MNN/tts
     * 
     * @param basePath Base directory (absolute path)
     * @param targetPath Target directory (absolute path)
     * @return Relative path from base to target
     */
    private static String calculateRelativePath(String basePath, String targetPath) {
        // Normalize paths (remove trailing slashes)
        basePath = basePath.replaceAll("/+$", "");
        targetPath = targetPath.replaceAll("/+$", "");
        
        // Split paths into components
        String[] baseComponents = basePath.split("/");
        String[] targetComponents = targetPath.split("/");
        
        // Find common prefix length
        int commonLength = 0;
        int minLength = Math.min(baseComponents.length, targetComponents.length);
        for (int i = 0; i < minLength; i++) {
            if (baseComponents[i].equals(targetComponents[i])) {
                commonLength++;
            } else {
                break;
            }
        }
        
        // Build relative path
        StringBuilder relativePath = new StringBuilder();
        
        // Add "../" for each remaining component in base path
        int upLevels = baseComponents.length - commonLength;
        for (int i = 0; i < upLevels; i++) {
            if (relativePath.length() > 0) {
                relativePath.append("/");
            }
            relativePath.append("..");
        }
        
        // Add remaining components from target path
        for (int i = commonLength; i < targetComponents.length; i++) {
            if (relativePath.length() > 0) {
                relativePath.append("/");
            }
            relativePath.append(targetComponents[i]);
        }
        
        return relativePath.toString();
    }
}
