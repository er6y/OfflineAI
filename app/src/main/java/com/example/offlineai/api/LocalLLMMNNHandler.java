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
 * MNN Local LLM Inference Handler
 * Implements unified InferenceEngine interface using MNN LLM framework
 * 
 * Features:
 * - One-shot inference with built-in autoregressive loop
 * - Multiple backend support (CPU, OpenCL, Vulkan, NNAPI, KleidiAI)
 * - Streaming output with token-by-token callback
 * - Automatic KV cache management
 * - Multi-modal support (text, image, audio)
 * 
 * @author OfflineAI Team
 * @version 1.0
 */
public class LocalLLMMNNHandler implements LocalLlmHandler.InferenceEngine {
    private static final String TAG = "LocalLLMMNNHandler";
    
    // Context reference
    private final Context context;
    
    // MNN session handle
    private long sessionHandle = 0;
    
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
        
        // MNN uses directory-based model structure
        // Return directory path if it contains required files
        File llmFile = new File(modelDir, "llm.mnn");
        if (llmFile.exists()) {
            LogManager.logI(TAG, "Found MNN model directory: " + modelDir.getAbsolutePath());
            return modelDir.getAbsolutePath();
        }
        
        LogManager.logW(TAG, "No MNN model found in: " + modelDir.getAbsolutePath());
        return null;
    }
    
    @Override
    public void initialize(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
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
        
        // Check for required files
        File configFile = new File(modelDir, "config.json");
        File llmFile = new File(modelDir, "llm.mnn");
        File weightFile = new File(modelDir, "llm.mnn.weight");
        File tokenizerFile = new File(modelDir, "tokenizer.txt");
        
        LogManager.logI(TAG, "Checking required files:");
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
        sessionHandle = MnnInference.createSession(modelPath, mnnConfig);
        
        if (sessionHandle == 0) {
            throw new Exception("Failed to create MNN session");
        }
        
        isInitialized.set(true);
        LogManager.logI(TAG, "MNN session created successfully: " + sessionHandle);
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
                    MnnInference.inferenceWithImages(sessionHandle, prompt, imagePathsArray, mnnCallback);
                } else {
                    // Text-only inference
                    MnnInference.inference(sessionHandle, prompt, mnnCallback);
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

        if (sessionHandle != 0) {
            MnnInference.destroySession(sessionHandle);
            sessionHandle = 0;
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
     * Reset MNN session - clear KV cache and conversation history
     */
    public void resetSession() {
        if (sessionHandle != 0) {
            LogManager.logI(TAG, "Resetting MNN session (clearing KV cache)");
            MnnInference.resetSession(sessionHandle);
            LogManager.logI(TAG, "MNN session reset completed");
        } else {
            LogManager.logW(TAG, "Cannot reset session: session not initialized");
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
        
        // Fixed chunk size for balanced memory and performance
        final int CHUNK_SIZE = 256;
        
        // Build configuration using MnnInference.ConfigBuilder
        MnnInference.ConfigBuilder builder = new MnnInference.ConfigBuilder()
            .backendType(mnnBackend)
            .threadNum(threads)
            .precision("low")  // Use FP16 for better performance
            .memory("low")     // Hardcoded: runtime dequantization to save memory (4B model)
            .power("high")     // Hardcoded: use big cores for performance
            .maxNewTokens(maxNewTokens)
            .chunk(CHUNK_SIZE)         // Fixed chunk size for prefill stage
            .kvcacheLimit(maxSeqLength) // KV cache size limit (context window)
            .reuseKv(true)     // Enable KV cache reuse for multi-turn
            .useMmap(true);    // Use mmap for low memory
        
        // Add temp path for mmap
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
        
        LogManager.logI(TAG, String.format(
            "Built MNN config - Backend: %s, Threads: %d, MaxTokens: %d, Chunk: %d, KVLimit: %d, temp=%.2f, top_p=%.2f, top_k=%d",
            mnnBackend, threads, maxNewTokens, CHUNK_SIZE, maxSeqLength, temperature, topP, topK));
        
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
     * Map backend preference to MNN backend type
     * @param backendPreference Backend preference from settings
     * @return MNN backend type string
     */
    private String mapBackendToMnn(String backendPreference) {
        if (backendPreference == null) {
            return "cpu";
        }
        
        switch (backendPreference.toUpperCase()) {
            case "VULKAN":
                if (MnnInference.isBackendAvailable("vulkan")) {
                    LogManager.logI(TAG, "Using Vulkan backend");
                    return "vulkan";
                }
                LogManager.logW(TAG, "Vulkan not available, falling back to CPU");
                return "cpu";
                
            case "OPENCL":
            case "GPU":
                if (MnnInference.isBackendAvailable("opencl")) {
                    LogManager.logI(TAG, "Using OpenCL backend");
                    return "opencl";
                }
                LogManager.logW(TAG, "OpenCL not available, falling back to CPU");
                return "cpu";
                
            case "NNAPI":
                if (MnnInference.isBackendAvailable("nnapi")) {
                    LogManager.logI(TAG, "Using NNAPI backend");
                    return "nnapi";
                }
                LogManager.logW(TAG, "NNAPI not available, falling back to CPU");
                return "cpu";
                
            case "CPU":
            default:
                LogManager.logI(TAG, "Using CPU backend");
                return "cpu";
        }
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
    
    @Override
    public String getEngineType() {
        return "MNN";
    }
}
