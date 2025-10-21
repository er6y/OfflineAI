package com.example.offlineai.api;

import android.content.Context;
import android.util.Log;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.SettingsFragment;
import com.offlineai.mnn.MnnInference;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    
    // Runtime for memory stats
    private final Runtime runtime = Runtime.getRuntime();
    
    // Model configuration
    private LocalLlmHandler.ModelConfig modelConfig;
    private LocalLlmHandler.InferenceParams currentParams;
    private LocalLlmHandler.InferenceParams modelFileParams; // Parameters from model config.json
    private String currentModelPath;
    
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
        
        // Check for LLM model
        File llmFile = new File(modelDir, "llm.mnn");
        if (llmFile.exists()) {
            LogManager.logI(TAG, "Found MNN LLM model directory: " + modelDir.getAbsolutePath());
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
        // Check for required files
        File modelDir = new File(modelPath);
        File configFile = new File(modelDir, "config.json");
        File llmFile = new File(modelDir, "llm.mnn");
        File weightFile = new File(modelDir, "llm.mnn.weight");
        File tokenizerFile = new File(modelDir, "tokenizer.txt");
        
        LogManager.logI(TAG, "Checking LLM required files:");
        LogManager.logI(TAG, "  config.json: " + (configFile.exists() ? "✓ " + configFile.length() + " bytes" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  llm.mnn: " + (llmFile.exists() ? "✓ " + llmFile.length() + " bytes" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  llm.mnn.weight: " + (weightFile.exists() ? "✓ " + weightFile.length() + " bytes" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  tokenizer.txt: " + (tokenizerFile.exists() ? "✓ " + tokenizerFile.length() + " bytes" : "✗ NOT FOUND"));
        
        if (!configFile.exists()) {
            throw new Exception("config.json not found in model directory");
        }
        if (!llmFile.exists()) {
            throw new Exception("llm.mnn not found in model directory");
        }
        if (!weightFile.exists()) {
            throw new Exception("llm.mnn.weight not found in model directory");
        }
        if (!tokenizerFile.exists()) {
            throw new Exception("tokenizer.txt not found in model directory");
        }
        
        // Read model config.json to get default parameters
        modelFileParams = readModelConfigParams(configFile);
        
        // Build MNN configuration (use null for params during initialization)
        String mnnConfig = buildMnnConfig(null);
        LogManager.logI(TAG, "MNN Config: " + mnnConfig);
        
        // Create MNN session
        llmSessionHandle = MnnInference.createSession(modelPath, mnnConfig);
        
        if (llmSessionHandle == 0) {
            throw new Exception("Failed to create MNN LLM session");
        }
        
        LogManager.logI(TAG, "MNN LLM session created successfully: " + llmSessionHandle);
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
        
        LogManager.logI(TAG, "Creating Diffusion session with backend: " + backendPreference + " (type=" + backendType + ")");
        
        // First-time GPU load warning
        if (backendType == 3 || backendType == 7) { // OpenCL or Vulkan
            LogManager.logW(TAG, "⚠️ FIRST-TIME GPU LOAD: May take 5-15 minutes to compile kernels!");
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
            LogManager.logI(TAG, "✅ Created app-specific cache directory: " + backendCacheDir.getAbsolutePath());
        }
        String cachePath = backendCacheDir.getAbsolutePath();
        LogManager.logI(TAG, "📁 Cache path: " + cachePath);
        
        // All printing is now done in JNI layer
        LogManager.logI(TAG, "Initializing diffusion model...");
        
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
            String errorMsg = "Failed to create Diffusion session with backend: " + backendPreference;
            LogManager.logE(TAG, errorMsg);
            throw new Exception(errorMsg);
        }
        
        LogManager.logI(TAG, "Diffusion session created successfully, handle=" + diffusionHandle);
        // JNI layer already printed "Models loaded successfully!"
    }
    
    @Override
    public void inference(String prompt, LocalLlmHandler.InferenceParams params,
                         LocalLlmHandler.StreamingCallback callback) {
        // Call multimodal version with null imagePaths
        inference(prompt, null, params, callback);
    }
    
    @Override
    public void inference(String prompt, List<String> imagePaths,
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
            inferenceDiffusion(prompt, params, callback);
        } else if (currentModelType == ModelType.LLM) {
            inferenceLLM(prompt, imagePaths, params, callback);
        } else {
            callback.onError("Unknown model type");
        }
    }
    
    /**
     * LLM text generation inference
     */
    private void inferenceLLM(String prompt, List<String> imagePaths,
                             LocalLlmHandler.InferenceParams params,
                             LocalLlmHandler.StreamingCallback callback) {
        // Reset stop flag and response builder
        shouldStop.set(false);
        isGenerating.set(true);
        generationStartTime = System.currentTimeMillis();
        inferenceStartTime = generationStartTime;
        currentSessionTokens.set(0);
        fullResponseBuilder.setLength(0); // Clear previous response
        
        // Reset statistics
        promptTokens = 0;
        generatedTokens = 0;
        prefillTimeUs = 0;
        decodeTimeUs = 0;
        
        // Store current inference params for stats
        currentParams = params;
        
        // Run inference asynchronously (track Future for cancellation)
        currentTask = executorService.submit(() -> {
            try {
                LogManager.logI(TAG, "Starting MNN inference");
                LogManager.logD(TAG, "Prompt: " + prompt);
                
                // Create MNN callback
                MnnInference.InferenceCallback mnnCallback = new MnnInference.InferenceCallback() {
                    @Override
                    public boolean onToken(String token) {
                        // Check stop flag
                        if (shouldStop.get()) {
                            LogManager.logI(TAG, "Inference stopped by user");
                            isGenerating.set(false); // CRITICAL: Set false immediately
                            // NOTE: Native may still be cleaning up (30+ seconds)
                            // but we mark as "not generating" to unblock Failsafe checks
                            return true; // Stop generation
                        }
                        
                        // Skip end marker
                        if (token.contains("<eop>")) {
                            return false;
                        }
                        
                        // Update statistics
                        currentSessionTokens.incrementAndGet();
                        totalTokensGenerated.incrementAndGet();
                        
                        // Accumulate response
                        fullResponseBuilder.append(token);
                        
                        // Call user callback
                        if (callback != null) {
                            callback.onToken(token);
                        }
                        
                        return false; // Continue generation
                    }
                    
                    @Override
                    public void onComplete(Map<String, Long> stats) {
                        long elapsedMs = System.currentTimeMillis() - generationStartTime;
                        
                        // Extract and store statistics
                        promptTokens = stats.getOrDefault("prompt_len", 0L);
                        generatedTokens = stats.getOrDefault("gen_seq_len", 0L);
                        prefillTimeUs = stats.getOrDefault("prefill_us", 0L);
                        decodeTimeUs = stats.getOrDefault("decode_us", 0L);
                        
                        // Calculate speeds
                        float prefillSpeed = prefillTimeUs > 0 ? (promptTokens * 1000000.0f / prefillTimeUs) : 0;
                        float decodeSpeed = decodeTimeUs > 0 ? (generatedTokens * 1000000.0f / decodeTimeUs) : 0;
                        
                        LogManager.logI(TAG, String.format(
                            "Inference complete - Prompt: %d tokens, Generated: %d tokens, " +
                            "Prefill: %.2f ms (%.2f tok/s), Decode: %.2f ms (%.2f tok/s), Total: %d ms",
                            promptTokens, generatedTokens,
                            prefillTimeUs / 1000.0, prefillSpeed,
                            decodeTimeUs / 1000.0, decodeSpeed,
                            elapsedMs
                        ));
                        
                        // Generate and append performance stats
                        String perfStats = getPerformanceStats();
                        String fullResponse = fullResponseBuilder.toString() + perfStats;
                        
                        // Call user callback with full response + stats
                        if (callback != null) {
                            // Send stats as a separate token to ensure UI displays it correctly
                            callback.onToken(perfStats);
                            // Then send complete response
                            callback.onComplete(fullResponse);
                        }
                        
                        isGenerating.set(false);
                    }
                    
                    @Override
                    public void onError(String error) {
                        LogManager.logE(TAG, "Inference error: " + error);
                        
                        if (callback != null) {
                            callback.onError(error);
                        }
                        
                        isGenerating.set(false);
                    }
                };
                
                // Perform inference
                if (imagePaths != null && !imagePaths.isEmpty()) {
                    // Multi-modal inference with images
                    String[] imagePathsArray = imagePaths.toArray(new String[0]);
                    MnnInference.inferenceWithImages(llmSessionHandle, prompt, imagePathsArray, mnnCallback);
                } else {
                    // Text-only inference
                    MnnInference.inference(llmSessionHandle, prompt, mnnCallback);
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Exception during inference", e);
                if (callback != null) {
                    callback.onError("Inference failed: " + e.getMessage());
                }
                isGenerating.set(false);
            }
        });
    }
    
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
        
        // Fixed chunk size for balanced memory and performance
        final int CHUNK_SIZE = 256;
        
        // KV Cache size limit calculation
        // -1 = unlimited (recommended for best performance)
        // Or set to bytes: e.g., 10 * 1024 * 1024 = 10MB per layer
        // Formula: kv_heads × max_tokens × head_dim × bytes_per_element × 2 (key+value)
        // Example for 2048 tokens: ~4MB per layer (quantized)
        final int KV_CACHE_LIMIT_MB = -1;  // -1 for unlimited, or set MB limit per layer
        int kvcacheLimitBytes = (KV_CACHE_LIMIT_MB == -1) ? -1 : KV_CACHE_LIMIT_MB * 1024 * 1024;
        
        // Build configuration using MnnInference.ConfigBuilder
        MnnInference.ConfigBuilder builder = new MnnInference.ConfigBuilder()
            .backendType(mnnBackend)
            .threadNum(threads)
            .precision("low")  // Use FP16 for better performance
            .memory("low")     // Hardcoded: runtime dequantization to save memory (4B model)
            .power("high")     // Hardcoded: use big cores for performance
            .maxAllTokens(maxSeqLength)  // CRITICAL: Total context window (input + output)
            .maxNewTokens(maxNewTokens)  // Single response generation limit
            .chunk(CHUNK_SIZE)         // Fixed chunk size for prefill stage
            .kvcacheLimit(kvcacheLimitBytes)   // CRITICAL: -1 = unlimited, or bytes limit per layer
            .reuseKv(true)     // Enable KV cache reuse for multi-turn
            //.useMmap(true)     // Use mmap for model weights, bug here, android do not open
            .kvcacheMmap(false); // CRITICAL: Disable KV cache mmap to avoid /tmp crash on Android
        
        // Add temp path for weight mmap (not for kvcache)
        // Reference: libs/mnn/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp
        File cacheDir = context.getCacheDir();
        builder.tmpPath(cacheDir.getAbsolutePath());
        
        // Parameter priority: runtime params > ConfigManager > model config.json
        float temperature;
        float topP;
        int topK;
        
        if (params != null) {
            // Use runtime parameters (highest priority)
            temperature = params.getTemperature();
            topP = params.getTopP();
            topK = params.getTopK();
            LogManager.logI(TAG, "Using runtime inference parameters");
        } else if (modelFileParams != null) {
            // Use model config.json parameters
            temperature = modelFileParams.getTemperature();
            topP = modelFileParams.getTopP();
            topK = modelFileParams.getTopK();
            LogManager.logI(TAG, "Using model config.json parameters");
        } else {
            // Use defaults
            temperature = 0.7f;
            topP = 0.9f;
            topK = 40;
            LogManager.logI(TAG, "Using default parameters");
        }
        
        builder.temperature(temperature)
               .topP(topP)
               .topK(topK);
        
        String config = builder.build();
        
        String kvLimitStr = (KV_CACHE_LIMIT_MB == -1) ? "unlimited" : KV_CACHE_LIMIT_MB + "MB/layer";
        LogManager.logI(TAG, String.format(
            "Built MNN config - Backend: %s, Threads: %d, MaxAllTokens: %d, MaxNewTokens: %d, Chunk: %d, KVLimit: %s, temp=%.2f, top_p=%.2f, top_k=%d",
            mnnBackend, threads, maxSeqLength, maxNewTokens, CHUNK_SIZE, kvLimitStr, temperature, topP, topK));
        
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
    
    @Override
    public String getEngineType() {
        return "MNN";
    }
}
