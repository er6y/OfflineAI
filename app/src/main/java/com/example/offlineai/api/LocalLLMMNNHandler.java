package com.example.offlineai.api;

import android.content.Context;
import android.util.Log;

import com.example.offlineai.ChatHistoryFilter;
import com.example.offlineai.ChatHistoryManager;
import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.R;
import com.example.offlineai.SettingsFragment;
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
    private static final int MIN_DIFFUSION_STEPS = 10;      // 预览模式
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
    private final ExecutorService executorService;
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
    private long promptTokens = 0;
    private long generatedTokens = 0;
    private long prefillTimeUs = 0;
    private long decodeTimeUs = 0;
    
    // Store full response for onComplete callback
    private final StringBuilder fullResponseBuilder = new StringBuilder();
    
    // Track if [TEXT:] head has been sent (for LLM models)
    private volatile boolean textHeadSent = false;
    
    // Runtime for memory stats
    private final Runtime runtime = Runtime.getRuntime();
    
    // Model configuration
    private LocalLlmHandler.ModelConfig modelConfig;
    private LocalLlmHandler.InferenceParams currentParams;
    private LocalLlmHandler.InferenceParams modelFileParams; // Parameters from model config.json
    private String currentModelPath;
    
    // TTS (Text-to-Speech) support
    private boolean hasTtsSupport = false;  // Native Omni TTS (talker.mnn)
    private boolean enableNativeTts = false;  // Whether native Omni TTS is enabled
    private String currentAudioOutputPath = null;
    private java.io.ByteArrayOutputStream ttsAudioBuffer = null;
    
    // External TTS support (using MNN TTS Framework)
    private TtsService externalTtsService = null;  // External TTS service instance
    private boolean externalTtsLoaded = false;  // Whether external TTS is loaded
    private boolean externalTtsLoadFailed = false;  // Whether external TTS load failed (avoid retry)
    private final AtomicBoolean externalTtsLoading = new AtomicBoolean(false);  // Loading flag
    private String currentTtsModelSelection = "";  // Current TTS model selection
    
    // Sentence boundary regex for streaming TTS (Chinese/English punctuation, excluding quotes)
    private static final java.util.regex.Pattern SENTENCE_PATTERN = java.util.regex.Pattern.compile(
        "[^。！？.!?]+[。！？.!?]+|[^。！？.!?]+$"
    );
    
    
    /**
     * Constructor
     * @param context Application context
     */
    public LocalLLMMNNHandler(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        
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
            LogManager.logI(TAG, "✅ Model supports TTS (Text-to-Speech)");
            // Note: TTS output path is set per-inference in inferenceLLM()
        } else {
            LogManager.logI(TAG, "ℹ️ Model does not support TTS");
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
        
        // Get user-selected backend
        String backendPreference = SettingsFragment.getBackendPreference(context);
        int backendType = mapBackendToMnnForwardType(backendPreference);
        
        LogManager.logI(TAG, "[Diffusion] Creating session with backend: " + backendPreference + " (type=" + backendType + ")");
        
        // First-time GPU load warning
        if (backendType == 3 || backendType == 7) { // OpenCL or Vulkan
            LogManager.logW(TAG, "[Diffusion] ⚠️ FIRST-TIME GPU LOAD: May take 5-15 minutes to compile kernels!");
        }
        
        // Get memory mode from config
        int memoryMode = ConfigManager.getDiffusionMemoryMode(context);
        LogManager.logI(TAG, "Using memory mode: " + memoryMode + " (" + ConfigManager.getDiffusionMemoryModeString(context) + ")");
        
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
            LogManager.logI(TAG, "[Diffusion] ✅ Created app-specific cache directory: " + backendCacheDir.getAbsolutePath());
        }
        String cachePath = backendCacheDir.getAbsolutePath();
        LogManager.logI(TAG, "[Diffusion] 📁 Cache path: " + cachePath);
        
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
        if (!isInitialized.get()) {
            LogManager.logE(TAG, "Handler not initialized");
            if (callback != null) {
                callback.onError("Handler not initialized");
            }
            return;
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
            // Get history rounds from config
            int historyRounds = ConfigManager.getInt(context, ConfigManager.KEY_HISTORY_ROUNDS, 5);
            LogManager.logI(TAG, "[HISTORY] Starting inference with " + historyRounds + " rounds of history");
            
            // Load current chat history from markdown
            String chatFolder = ConfigManager.getString(context, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            List<ChatDataItem> allMessages = new ArrayList<>();
            if (!chatFolder.isEmpty()) {
                allMessages = ChatHistoryManager.loadConversation(context, chatFolder);
                if (allMessages == null) {
                    allMessages = new ArrayList<>();
                }
            }
            LogManager.logI(TAG, "[HISTORY] Loaded " + allMessages.size() + " messages from markdown");
            
            // Get system prompt from config or params
            String systemPrompt = "";
            if (params != null && params.systemPrompt != null && !params.systemPrompt.isEmpty()) {
                systemPrompt = params.systemPrompt;
            } else {
                systemPrompt = ConfigManager.getString(context, ConfigManager.KEY_SYSTEM_PROMPT, "");
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
        isGenerating.set(true);
        shouldStop.set(false);
        fullResponseBuilder.setLength(0);
        textHeadSent = false; // Reset text head flag for each inference
        generationStartTime = System.currentTimeMillis();
        inferenceStartTime = System.currentTimeMillis();
        
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
                    String chatFolderPath = ConfigManager.getString(context, 
                        ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                    
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
            // User selected "None" or "External TTS", but model supports native TTS
            // Must explicitly disable native TTS to prevent unwanted audio generation
            if (currentTtsModelSelection.isEmpty() || 
                context.getString(R.string.settings_tts_model_none).equals(currentTtsModelSelection)) {
                // Scenario 1: Omni + None → Disable native TTS
                MnnInference.setTtsOutputPath(llmSessionHandle, null);
                LogManager.logI(TAG, "[TTS] Native TTS disabled by user (None selected)");
            } else {
                // Scenario 3: Omni + External → Disable native TTS
                MnnInference.setTtsOutputPath(llmSessionHandle, null);
                LogManager.logI(TAG, "[TTS] Native TTS disabled (External TTS selected: " + currentTtsModelSelection + ")");
            }
        }
        // Scenario 4 & 6: Non-Omni + (None or External) → No action needed
        // Model doesn't support native TTS, so no need to set/clear output path
        
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
                            
                            return false;
                        }
                        
                        @Override
                        public void onComplete(Map<String, Long> statistics) {
                            long endTime = System.currentTimeMillis();
                            long totalTime = endTime - generationStartTime;
                            
                            if (statistics != null) {
                                promptTokens = statistics.getOrDefault("prompt_len", 0L);
                                generatedTokens = statistics.getOrDefault("decode_len", 0L);
                                prefillTimeUs = statistics.getOrDefault("prefill_time", 0L);
                                decodeTimeUs = statistics.getOrDefault("decode_time", 0L);
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
                            } else if (!currentTtsModelSelection.isEmpty() && 
                                       !context.getString(R.string.settings_tts_model_none).equals(currentTtsModelSelection)) {
                                // User selected "External TTS"
                                // Scenario 3 (Omni + External) or Scenario 6 (Non-Omni + External)
                                // External TTS works with ANY LLM model type
                                LogManager.logI(TAG, "[TTS] Generating external TTS: " + currentTtsModelSelection + 
                                              " (model=" + (hasTtsSupport ? "Omni" : "Non-Omni") + ")");
                                
                                String chatFolderPath = ConfigManager.getString(context, 
                                    ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                                if (!chatFolderPath.isEmpty()) {
                                    String responseText = fullResponseBuilder.toString();
                                    
                                    // Check stop flag before starting TTS
                                    if (shouldStop.get()) {
                                        LogManager.logI(TAG, "[TTS] TTS generation cancelled (stop requested before start)");
                                        return;
                                    }
                                    
                                    // Notify TTS generation start
                                    if (callback != null) {
                                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                            callback.onToken("\n\n[TTS_START]");
                                        });
                                    }
                                    
                                    executorService.submit(() -> {
                                        // Check stop flag again in TTS thread
                                        if (shouldStop.get()) {
                                            LogManager.logI(TAG, "[TTS] TTS generation cancelled (stop requested in TTS thread)");
                                            if (callback != null) {
                                                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                                    callback.onToken("\n\n[TTS_END]");
                                                });
                                            }
                                            return;
                                        }
                                        
                                        generateExternalTts(responseText, chatFolderPath, (audioPath, error) -> {
                                            if (audioPath != null && callback != null) {
                                                LogManager.logI(TAG, "[TTS] External TTS audio generated: " + audioPath);
                                                // CRITICAL: callback must be called on main thread for UI safety
                                                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                                    callback.onToken("\n\n[AUDIO:" + audioPath + "]");
                                                    callback.onToken("\n\n[TTS_END]");
                                                });
                                            } else if (error != null) {
                                                LogManager.logE(TAG, "[TTS] External TTS generation failed (" + currentTtsModelSelection + ")", error);
                                                showToast(R.string.toast_tts_generation_failed);
                                                // Notify TTS end even on error
                                                if (callback != null) {
                                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                                        callback.onToken("\n\n[TTS_END]");
                                                    });
                                                }
                                            }
                                        });
                                    });
                                } else {
                                    LogManager.logW(TAG, "[TTS] External TTS skipped: no chat folder path");
                                }
                            }
                            // Scenario 1 & 4: (Omni or Non-Omni) + None → No audio generation (intentional)
                            
                            isGenerating.set(false);
                            
                            if (callback != null) {
                                String perfStats = getPerformanceStats();
                                callback.onToken(perfStats);
                                callback.onComplete(fullResponseBuilder.toString() + perfStats);
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
        try {
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(true);
                LogManager.logD(TAG, "Cancelled current inference task");
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
        
        // Release external TTS service
        if (externalTtsService != null) {
            try {
                externalTtsService.destroy();
                externalTtsService = null;
                externalTtsLoaded = false;
                LogManager.logI(TAG, "[TTS] External TTS service released");
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Error releasing TTS service", e);
            }
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
     * Build MNN configuration JSON from ConfigManager settings and InferenceParams
     * Priority: User params (runtime) > ConfigManager settings > Model config.json
     * @param params Inference parameters (optional, uses defaults if null)
     * @return JSON configuration string
     */
    private String buildMnnConfig(LocalLlmHandler.InferenceParams params) {
        // Get configuration from ConfigManager
        int maxSeqLength = ConfigManager.getMaxSequenceLength(context);
        int threads = ConfigManager.getThreads(context);
        int maxNewTokens = ConfigManager.getMaxNewTokens(context);
        String backendPreference = SettingsFragment.getBackendPreference(context);
        
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
        // Or set to bytes: e.g., 10 * 1024 * 1024 = 10MB per layer
        // Formula: kv_heads × max_tokens × head_dim × bytes_per_element × 2 (key+value)
        // Example for 2048 tokens: ~4MB per layer (quantized)
        final int KV_CACHE_LIMIT_MB = -1;  // -1 for unlimited, or set MB limit per layer
        int kvcacheLimitBytes = (KV_CACHE_LIMIT_MB == -1) ? -1 : KV_CACHE_LIMIT_MB * 1024 * 1024;
        
        // Auto-detect audio support (Qwen2.5-Omni, etc.)
        File audioModel = new File(currentModelPath, "audio.mnn");
        boolean hasAudioSupport = audioModel.exists();
        if (hasAudioSupport) {
            LogManager.logI(TAG, "✅ AUTO-DETECTED: audio.mnn found, enabling audio support (Omni model)");
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
            //.useMmap(true)     // Use mmap for model weights, bug here, android do not open
            .kvcacheMmap(false); // CRITICAL: Disable KV cache mmap to avoid /tmp crash on Android
        
        // Enable audio support if audio.mnn exists
        if (hasAudioSupport) {
            builder.isAudio(true).audioModel("audio.mnn");
            // Qwen2.5-Omni audio pad token (from official config)
            builder.audioPad(151666);
            LogManager.logI(TAG, "🎤 Audio support enabled: audio_model=audio.mnn, audio_pad=151666");
        }
        
        // Check if TTS (Talker) is supported
        File talkerModel = new File(currentModelPath, "talker.mnn");
        boolean hasTtsSupport = talkerModel.exists();
        
        // Check user's TTS model selection
        String ttsModelSelection = ConfigManager.getString(context, ConfigManager.KEY_TTS_MODEL, 
            ConfigManager.DEFAULT_TTS_MODEL);
        String nativeOmniName = context.getString(R.string.settings_tts_model_native_omni);
        
        // Determine TTS mode: Native Omni, External, or None
        this.enableNativeTts = false;
        String noneOption = context.getString(R.string.settings_tts_model_none);
        
        // Check if TTS model selection changed
        boolean ttsModelChanged = !ttsModelSelection.equals(this.currentTtsModelSelection);
        this.currentTtsModelSelection = ttsModelSelection;
        
        if (hasTtsSupport && nativeOmniName.equals(ttsModelSelection)) {
            // Native Omni TTS
            this.enableNativeTts = true;
            int ditSteps = ConfigManager.getTtsDitSteps(context);
            builder.ditSteps(ditSteps);  // User-configured DiT steps
            builder.ditSolver(1);        // 1=Euler (fast), 4=RK4 (4x slower but better)
            LogManager.logI(TAG, "🔊 Native TTS enabled: dit_steps=" + ditSteps + ", dit_solver=1");
            
            // Release external TTS if switching from external to native
            if (ttsModelChanged && externalTtsService != null) {
                try {
                    externalTtsService.destroy();
                    externalTtsService = null;
                    externalTtsLoaded = false;
                    externalTtsLoadFailed = false;
                    LogManager.logI(TAG, "[TTS] External TTS released due to mode change to native");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] Error releasing TTS service", e);
                }
            }
        } else if (!noneOption.equals(ttsModelSelection) && !ttsModelSelection.isEmpty()) {
            // External TTS model selected
            LogManager.logI(TAG, "🔊 External TTS selected: " + ttsModelSelection + " (will lazy load)");
            
            // Release old external TTS if model changed
            if (ttsModelChanged && externalTtsService != null) {
                try {
                    externalTtsService.destroy();
                    externalTtsService = null;
                    externalTtsLoaded = false;
                    externalTtsLoadFailed = false;  // Reset failed flag for new model
                    LogManager.logI(TAG, "[TTS] External TTS released due to model change");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] Error releasing TTS service", e);
                }
            }
        } else {
            // TTS disabled
            LogManager.logI(TAG, "🔊 TTS disabled (user selected: " + ttsModelSelection + ")");
            
            // Note: No need to set ditSteps=0 hack
            // C++ layer controls TTS via enable_audio_output_ flag (like ChatMNN)
            // TTS will not be generated because setTtsOutputPath(null) is called
            
            // Release external TTS if switching to disabled
            if (ttsModelChanged && externalTtsService != null) {
                try {
                    externalTtsService.destroy();
                    externalTtsService = null;
                    externalTtsLoaded = false;
                    externalTtsLoadFailed = false;
                    LogManager.logI(TAG, "[TTS] External TTS released due to mode change to disabled");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] Error releasing TTS service", e);
                }
            }
        }
        
        // Add temp path for weight mmap (not for kvcache)
        // Reference: libs/mnn/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp
        File cacheDir = context.getCacheDir();
        builder.tmpPath(cacheDir.getAbsolutePath());
        
        // Parameter priority logic:
        // 1. If priorityManual=true (手动参数优先): use manual params from ConfigManager
        // 2. If priorityManual=false (非手动参数优先): do NOT set params, let MNN read model config.json
        // Note: params!=null means buildParamsFromConfig() was called, but we must check priorityManual flag
        
        boolean priorityManual = ConfigManager.getPriorityManualParams(context);
        
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
     * Calculate token generation rate
     */
    private double calculateTokenRate() {
        long elapsedTime = inferenceStartTime > 0 ? System.currentTimeMillis() - inferenceStartTime : 0;
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
        long elapsedTime = inferenceStartTime > 0 ? System.currentTimeMillis() - inferenceStartTime : 0;
        
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
        
        // Configuration info
        int maxNewTokens = ConfigManager.getMaxNewTokens(context);
        int maxSeqLength = ConfigManager.getMaxSequenceLength(context);
        int threads = ConfigManager.getThreads(context);
        String backendPreference = SettingsFragment.getBackendPreference(context);
        
        stats.append(String.format("\n   • maxNewTokens: %d tokens\n", maxNewTokens));
        stats.append(String.format("   • maxSeqLength: %d tokens\n", maxSeqLength));
        stats.append(String.format("   • threads: %d\n", threads));
        stats.append(String.format("   • Backend: %s\n", backendPreference));
        
        // Display MNN-specific stats
        if (promptTokens > 0 || generatedTokens > 0) {
            float prefillSpeed = prefillTimeUs > 0 ? (promptTokens * 1000000.0f / prefillTimeUs) : 0;
            float decodeSpeed = decodeTimeUs > 0 ? (generatedTokens * 1000000.0f / decodeTimeUs) : 0;
            
            stats.append(String.format("   • Prefill: %d tokens, %.2f ms (%.2f tok/s)\n",
                promptTokens, prefillTimeUs / 1000.0, prefillSpeed));
            stats.append(String.format("   • Decode: %d tokens, %.2f ms (%.2f tok/s)\n",
                generatedTokens, decodeTimeUs / 1000.0, decodeSpeed));
        }
        
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
                
                // Get current chat folder
                String chatFolderPath = ConfigManager.getString(context, 
                    ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                
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
                
                // Get steps and seed from config
                int steps = ConfigManager.getDiffusionSteps(context);
                steps = Math.max(MIN_DIFFUSION_STEPS, Math.min(steps, MAX_DIFFUSION_STEPS));
                LogManager.logI(TAG, "Using steps from config: " + steps);
                
                int seed;
                boolean useRandomSeed = ConfigManager.getDiffusionSeedRandom(context);
                if (useRandomSeed) {
                    seed = -1;
                    LogManager.logI(TAG, "Using random seed");
                } else {
                    seed = ConfigManager.getDiffusionSeed(context);
                    LogManager.logI(TAG, "Using fixed seed from config: " + seed);
                }
                
                LogManager.logI(TAG, "Diffusion params: steps=" + steps + ", cfg=" + CFG_SCALE + ", scheduler=" + SCHEDULER + ", seed=" + seed);
                
                // Generate image (debug tag already opened at the beginning)
                final long startTime = System.currentTimeMillis();
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
                
                // Check if generation succeeded
                if (success && new File(outputPath).exists()) {
                    LogManager.logI(TAG, "Image generated successfully in " + duration + "ms: " + outputPath);
                    
                    // Note: Cache is automatically saved by MNN when session is released
                    // Using app-specific directory, no manual save or JNI callback needed
                    
                    // Output image path
                    callback.onToken("\n\n[IMAGE:" + outputPath + "]");
                    
                    // Output performance stats
                    String perfStats = getDiffusionPerformanceStats(duration, steps, seed, this.currentModelPath);
                    callback.onToken("\n\n" + perfStats);
                    
                    callback.onComplete("Image generation completed");
                } else {
                    String errorMsg = "Failed to generate image";
                    LogManager.logE(TAG, errorMsg);
                    callback.onError(errorMsg);
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
    
    /**
     * Lazy load external TTS model (thread-safe)
     * Loads bert-vits2-MNN as a standard MNN LLM model
     * 
     * @return true if loaded successfully
     */
    private synchronized boolean ensureExternalTtsLoaded() {
        LogManager.logI(TAG, String.format("[TTS] ensureExternalTtsLoaded() - loaded=%b, service=%s, failed=%b, loading=%b, model=%s",
            externalTtsLoaded, (externalTtsService != null ? "initialized" : "null"), externalTtsLoadFailed, externalTtsLoading.get(), currentTtsModelSelection));
        
        // Already loaded
        if (externalTtsLoaded && externalTtsService != null) {
            LogManager.logI(TAG, "[TTS] External TTS already loaded");
            return true;
        }
        
        // Previously failed, don't retry to avoid repeated Toast spam
        if (externalTtsLoadFailed) {
            LogManager.logW(TAG, "[TTS] External TTS load previously failed, skipping retry");
            return false;
        }
        
        // Check if model selection is valid
        if (currentTtsModelSelection == null || currentTtsModelSelection.isEmpty()) {
            LogManager.logE(TAG, "[TTS] currentTtsModelSelection is null or empty!");
            externalTtsLoadFailed = true;
            return false;
        }
        
        // Another thread is loading
        if (externalTtsLoading.compareAndSet(false, true)) {
            try {
                boolean success = loadExternalTtsModelSdk();
                if (!success) {
                    externalTtsLoadFailed = true;  // Mark as failed to avoid retry
                    LogManager.logE(TAG, "[TTS_SDK] External TTS load failed, marked to prevent retry");
                }
                return success;
            } finally {
                externalTtsLoading.set(false);
            }
        } else {
            // Wait for other thread to finish loading
            while (externalTtsLoading.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return externalTtsLoaded;
        }
    }
    
    /**
     * Load external TTS model as standard MNN LLM
     * Runs in background thread (called from ensureExternalTtsLoaded)
     * bert-vits2-MNN is loaded like a regular LLM model, not via TTS SDK
     * 
     * @return true if loaded successfully
     */
    private boolean loadExternalTtsModelSdk() {  // Keep method name for compatibility
        try {
            LogManager.logI(TAG, "[TTS] ========== Loading External TTS Model ==========");
            LogManager.logI(TAG, "[TTS] Model selection: " + currentTtsModelSelection);
            
            String ttsBasePath = ConfigManager.getTtsModelPath(context);
            LogManager.logI(TAG, "[TTS] TTS base path: " + ttsBasePath);
            
            File ttsModelDir = new File(ttsBasePath, currentTtsModelSelection);
            LogManager.logI(TAG, "[TTS] Full model path: " + ttsModelDir.getAbsolutePath());
            
            if (!ttsModelDir.exists()) {
                LogManager.logE(TAG, "[TTS] ❌ Model directory does NOT exist: " + ttsModelDir);
                showToast(R.string.toast_tts_model_load_failed);
                return false;
            }
            
            if (!ttsModelDir.isDirectory()) {
                LogManager.logE(TAG, "[TTS] ❌ Path exists but is NOT a directory: " + ttsModelDir);
                showToast(R.string.toast_tts_model_load_failed);
                return false;
            }
            
            LogManager.logI(TAG, "[TTS] ✅ Model directory exists and is valid");
            
            // Check for .mnn files
            File[] mnnFiles = ttsModelDir.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".mnn"));
            
            if (mnnFiles == null) {
                LogManager.logE(TAG, "[TTS] ❌ listFiles() returned null (I/O error or not a directory)");
                showToast(R.string.toast_tts_model_load_failed);
                return false;
            }
            
            if (mnnFiles.length == 0) {
                LogManager.logE(TAG, "[TTS] ❌ No .mnn files found in: " + ttsModelDir);
                // List all files for debugging
                File[] allFiles = ttsModelDir.listFiles();
                if (allFiles != null && allFiles.length > 0) {
                    LogManager.logI(TAG, "[TTS] Directory contains " + allFiles.length + " files:");
                    for (File f : allFiles) {
                        LogManager.logI(TAG, "[TTS]   - " + f.getName());
                    }
                } else {
                    LogManager.logE(TAG, "[TTS] Directory is empty or unreadable");
                }
                showToast(R.string.toast_tts_model_load_failed);
                return false;
            }
            
            LogManager.logI(TAG, String.format("[TTS] ✅ Found %d .mnn file(s):", mnnFiles.length));
            for (File f : mnnFiles) {
                LogManager.logI(TAG, "[TTS]   - " + f.getName());
            }
            LogManager.logI(TAG, "[TTS] Loading External TTS using MNN TTS Framework...");
            
            // Create TtsService instance
            LogManager.logI(TAG, "[TTS] Creating TtsService...");
            externalTtsService = new TtsService();
            
            // Initialize TTS service with model directory
            // Note: We use reflection to call nativeLoadResourcesFromFile directly (it's synchronous)
            LogManager.logI(TAG, "[TTS] Initializing TTS service with model dir: " + ttsModelDir.getAbsolutePath());
            
            try {
                // Get native pointer via reflection
                java.lang.reflect.Field nativeField = TtsService.class.getDeclaredField("ttsServiceNative");
                nativeField.setAccessible(true);
                long nativePtr = nativeField.getLong(externalTtsService);
                
                // Call nativeLoadResourcesFromFile via reflection
                java.lang.reflect.Method loadMethod = TtsService.class.getDeclaredMethod(
                    "nativeLoadResourcesFromFile", long.class, String.class, String.class, String.class);
                loadMethod.setAccessible(true);
                boolean result = (Boolean) loadMethod.invoke(externalTtsService, 
                    nativePtr, ttsModelDir.getAbsolutePath(), "", "");
                
                if (!result) {
                    LogManager.logE(TAG, "[TTS] ❌ nativeLoadResourcesFromFile returned false");
                    showToast(R.string.toast_tts_model_load_failed);
                    return false;
                }
                
                // Set isLoaded flag via reflection
                java.lang.reflect.Field loadedField = TtsService.class.getDeclaredField("isLoaded");
                loadedField.setAccessible(true);
                loadedField.setBoolean(externalTtsService, true);
                
                LogManager.logI(TAG, "[TTS] ✅ TtsService initialized successfully");
                
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] ❌ Failed to initialize TtsService via reflection", e);
                showToast(R.string.toast_tts_model_load_failed);
                return false;
            }
            
            externalTtsLoaded = true;
            LogManager.logI(TAG, "[TTS] ========== External TTS Loaded Successfully ==========");
            LogManager.logI(TAG, "[TTS] Model: " + currentTtsModelSelection);
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] ❌ Exception during External TTS load", e);
            showToast(R.string.toast_tts_model_load_failed);
            return false;
        }
    }
    
    /**
     * Generate TTS audio using external model (uses MNN TTS Framework)
     * @param text Full text to synthesize
     * @param outputDir Output directory for audio files
     * @param callback Callback for generated audio file
     */
    private void generateExternalTts(String text, String outputDir, 
                                     java.util.function.BiConsumer<String, Exception> callback) {
        LogManager.logI(TAG, "[TTS] generateExternalTts() called - text.len=" + text.length() + ", outputDir=" + outputDir);
        
        if (!ensureExternalTtsLoaded()) {
            LogManager.logE(TAG, "[TTS] ❌ ensureExternalTtsLoaded() returned false");
            callback.accept(null, new Exception("External TTS not loaded"));
            return;
        }
        
        LogManager.logI(TAG, "[TTS] External TTS is loaded, proceeding with generation...");
        
        // Run TTS generation in background thread
        executorService.submit(() -> {
            try {
                // Check stop flag before TTS processing
                if (shouldStop.get()) {
                    LogManager.logI(TAG, "[TTS] TTS generation cancelled (stop requested before processing)");
                    callback.accept(null, new Exception("TTS generation cancelled"));
                    return;
                }
                
                LogManager.logI(TAG, "[TTS] Calling TtsService.process() for text: " + text);
                
                // Call TtsService.process() to generate audio
                // Returns short[] PCM samples at 44100 Hz
                short[] audioSamples = externalTtsService.process(text, 0);
                
                if (audioSamples == null || audioSamples.length == 0) {
                    LogManager.logE(TAG, "[TTS] TtsService returned empty audio");
                    callback.accept(null, new Exception("TTS generation returned empty audio"));
                    return;
                }
                
                LogManager.logI(TAG, String.format("[TTS] Generated %d audio samples (%.2f seconds at 44100 Hz)",
                    audioSamples.length, audioSamples.length / 44100.0));
                
                // Save to WAV file
                long timestamp = System.currentTimeMillis();
                String audioPath = new File(outputDir, "audio_" + timestamp + "_ai.wav").getAbsolutePath();
                
                if (saveWavFile(audioPath, audioSamples, 44100)) {
                    File audioFile = new File(audioPath);
                    LogManager.logI(TAG, String.format("[TTS] Audio saved: %s (%d bytes)", 
                        audioPath, audioFile.length()));
                    callback.accept(audioPath, null);
                } else {
                    LogManager.logE(TAG, "[TTS] Failed to save WAV file");
                    callback.accept(null, new Exception("Failed to save audio file"));
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] External TTS generation error", e);
                callback.accept(null, e);
            }
        });
    }
    
    /**
     * Save PCM samples to WAV file
     * @param filePath Output file path
     * @param samples PCM samples (16-bit)
     * @param sampleRate Sample rate (e.g., 44100)
     * @return true if saved successfully
     */
    private boolean saveWavFile(String filePath, short[] samples, int sampleRate) {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(filePath)) {
            // WAV header
            int numChannels = 1;  // Mono
            int bitsPerSample = 16;
            int byteRate = sampleRate * numChannels * bitsPerSample / 8;
            int blockAlign = numChannels * bitsPerSample / 8;
            int dataSize = samples.length * 2;  // 2 bytes per sample
            int fileSize = 36 + dataSize;
            
            // RIFF header
            fos.write("RIFF".getBytes());
            fos.write(intToBytes(fileSize));
            fos.write("WAVE".getBytes());
            
            // fmt chunk
            fos.write("fmt ".getBytes());
            fos.write(intToBytes(16));  // Chunk size
            fos.write(shortToBytes((short)1));  // Audio format (1 = PCM)
            fos.write(shortToBytes((short)numChannels));
            fos.write(intToBytes(sampleRate));
            fos.write(intToBytes(byteRate));
            fos.write(shortToBytes((short)blockAlign));
            fos.write(shortToBytes((short)bitsPerSample));
            
            // data chunk
            fos.write("data".getBytes());
            fos.write(intToBytes(dataSize));
            
            // Write PCM data
            for (short sample : samples) {
                fos.write(shortToBytes(sample));
            }
            
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS_SDK] Failed to save WAV file", e);
            return false;
        }
    }
    
    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte)(value & 0xFF),
            (byte)((value >> 8) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 24) & 0xFF)
        };
    }
    
    private byte[] shortToBytes(short value) {
        return new byte[] {
            (byte)(value & 0xFF),
            (byte)((value >> 8) & 0xFF)
        };
    }
    
    // Remove old sentence-based generation code
    private void generateExternalTts_OLD_REMOVED(String text, String outputDir, 
                                     java.util.function.BiConsumer<String, Exception> callback) {
        // OLD CODE - REMOVED
        // Segment text by sentences
        java.util.regex.Matcher matcher = SENTENCE_PATTERN.matcher(text);
        int sentenceIndex = 0;
        java.util.List<String> generatedAudioPaths = new java.util.ArrayList<>();
        
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (sentence.isEmpty()) {
                continue;
            }
            
            sentenceIndex++;
        }
        
        // Merge audio files if multiple segments
        if (generatedAudioPaths.size() > 1) {
            String mergedPath = mergeAudioFiles(generatedAudioPaths, outputDir);
            if (mergedPath != null) {
                callback.accept(mergedPath, null);
            } else {
                // Fallback: return first audio file
                callback.accept(generatedAudioPaths.get(0), null);
            }
        } else if (generatedAudioPaths.size() == 1) {
            callback.accept(generatedAudioPaths.get(0), null);
        } else {
            callback.accept(null, new Exception("No audio generated"));
        }
    }
    
    /**
     * Merge multiple WAV audio files into one
     * @param audioPaths List of audio file paths to merge
     * @param outputDir Output directory
     * @return Merged audio file path, or null if failed
     */
    private String mergeAudioFiles(java.util.List<String> audioPaths, String outputDir) {
        try {
            long timestamp = System.currentTimeMillis();
            String mergedPath = new File(outputDir, "audio_" + timestamp + "_ai.wav").getAbsolutePath();
            
            // Simple concatenation for WAV files (same format assumed)
            // Read all audio data and concatenate
            java.io.ByteArrayOutputStream mergedData = new java.io.ByteArrayOutputStream();
            byte[] wavHeader = null;
            int totalDataSize = 0;
            
            for (String audioPath : audioPaths) {
                // Use try-with-resources to ensure file is closed even if exception occurs
                try (java.io.FileInputStream fis = new java.io.FileInputStream(audioPath)) {
                    byte[] fileData = new byte[fis.available()];
                    fis.read(fileData);
                    
                    if (wavHeader == null && fileData.length > 44) {
                        // Save first file's WAV header (44 bytes)
                        wavHeader = java.util.Arrays.copyOfRange(fileData, 0, 44);
                    }
                    
                    // Append audio data (skip header)
                    if (fileData.length > 44) {
                        mergedData.write(fileData, 44, fileData.length - 44);
                        totalDataSize += (fileData.length - 44);
                    }
                } catch (java.io.IOException e) {
                    LogManager.logE(TAG, "[TTS] Failed to read audio file: " + audioPath, e);
                    // Continue with other files
                }
            }
            
            if (wavHeader == null) {
                LogManager.logE(TAG, "[TTS] No valid WAV header found");
                return null;
            }
            
            // Update WAV header with new file size
            int fileSize = 36 + totalDataSize;
            wavHeader[4] = (byte)(fileSize & 0xFF);
            wavHeader[5] = (byte)((fileSize >> 8) & 0xFF);
            wavHeader[6] = (byte)((fileSize >> 16) & 0xFF);
            wavHeader[7] = (byte)((fileSize >> 24) & 0xFF);
            
            wavHeader[40] = (byte)(totalDataSize & 0xFF);
            wavHeader[41] = (byte)((totalDataSize >> 8) & 0xFF);
            wavHeader[42] = (byte)((totalDataSize >> 16) & 0xFF);
            wavHeader[43] = (byte)((totalDataSize >> 24) & 0xFF);
            
            // Write merged file
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mergedPath)) {
                fos.write(wavHeader);
                fos.write(mergedData.toByteArray());
            }
            
            LogManager.logI(TAG, "[TTS] Merged " + audioPaths.size() + " audio files into: " + mergedPath);
            
            // Delete temporary segment files
            for (String audioPath : audioPaths) {
                new File(audioPath).delete();
            }
            
            return mergedPath;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Failed to merge audio files", e);
            return null;
        }
    }
    
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
}
