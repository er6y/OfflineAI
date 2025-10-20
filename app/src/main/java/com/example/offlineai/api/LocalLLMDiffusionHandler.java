package com.example.offlineai.api;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.SettingsFragment;
import com.offlineai.mnn.MnnInference;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MNN Diffusion Inference Handler (Text-to-Image)
 * Implements unified InferenceEngine interface using MNN Diffusion framework
 * 
 * Features:
 * - Stable Diffusion v1.5 support
 * - OpenCL GPU acceleration
 * - Progress callback
 * - Automatic model loading and session management
 * 
 * @author OfflineAI Team
 * @version 1.0
 */
public class LocalLLMDiffusionHandler implements LocalLlmHandler.InferenceEngine {
    private static final String TAG = "LocalLLMDiffusionHandler";
    
    // ========== Diffusion参数配置 (基于Stable Diffusion官方标准) ==========
    /**
     * Stable Diffusion标准参数：
     * - Steps: 50步（CompVis/SD官方默认，NOT "可能10-20"）
     * - CFG Scale: 7.5（当前MNN硬编码在diffusion.cpp:L261）
     * - Scheduler: PLMS（固定，MNN当前唯一实现）
     * - Seed: -1=随机，>=0=固定（可复现）
     * - MemoryMode: 0=省内存/1=正常/2=平衡
     * 
     * 性能参考（基于log.ini实测）：
     * - 50步 × 13秒/步 = ~10分钟（首次含kernel编译）
     * - 50步 × 5秒/步 = ~4分钟（二次运行用缓存）
     * - 20步 × 5秒/步 = ~1.5分钟（快速模式）
     */
    
    // 默认推理步数（可通过maxTokens参数覆盖）
    private static final int DEFAULT_STEPS = 20;  // 快速模式，平衡质量与速度
    private static final int MAX_STEPS = 50;      // 标准模式，最佳质量
    private static final int MIN_STEPS = 10;      // 预览模式，最快速度
    
    // CFG Scale（当前MNN硬编码，未来可暴露）
    private static final float CFG_SCALE = 7.5f;
    
    // 调度器（当前固定PLMS）
    private static final String SCHEDULER = "PLMS";
    
    // Context reference
    private final Context context;
    
    // Diffusion session handle
    private long diffusionHandle = 0;
    
    // Model configuration
    private LocalLlmHandler.ModelConfig modelConfig;
    
    // Executor for async operations
    private final ExecutorService executorService;
    
    // State management
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // Model path
    private String currentModelPath;
    
    /**
     * Constructor
     * @param context Application context
     */
    public LocalLLMDiffusionHandler(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        
        LogManager.logI(TAG, "LocalLLMDiffusionHandler initialized");
    }
    
    @Override
    public String findModelFile(File modelDir) {
        if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
            return null;
        }
        
        // Diffusion uses directory-based model structure
        // Return directory path if it contains required files
        File textEncoder = new File(modelDir, "text_encoder.mnn");
        if (textEncoder.exists()) {
            LogManager.logI(TAG, "Found Diffusion model directory: " + modelDir.getAbsolutePath());
            return modelDir.getAbsolutePath();
        }
        
        LogManager.logW(TAG, "No Diffusion model found in: " + modelDir.getAbsolutePath());
        return null;
    }
    
    @Override
    public void initialize(String modelPath, LocalLlmHandler.ModelConfig config) throws Exception {
        if (isInitialized.get()) {
            LogManager.logI(TAG, "Handler already initialized, skipping");
            return;
        }
        
        // Save model path for performance stats
        this.currentModelPath = modelPath;
        
        LogManager.logI(TAG, "Initializing Diffusion handler with model: " + modelPath);
        this.modelConfig = config;
        
        // Validate model directory
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            throw new Exception("Model directory not found: " + modelPath);
        }
        
        // Check for required files
        File textEncoder = new File(modelDir, "text_encoder.mnn");
        File unet = new File(modelDir, "unet.mnn");
        File vaeDecoder = new File(modelDir, "vae_decoder.mnn");
        File vocabJson = new File(modelDir, "vocab.json");
        File mergesTxt = new File(modelDir, "merges.txt");
        
        LogManager.logI(TAG, "Checking required files:");
        LogManager.logI(TAG, "  text_encoder.mnn: " + (textEncoder.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  unet.mnn: " + (unet.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  vae_decoder.mnn: " + (vaeDecoder.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  vocab.json: " + (vocabJson.exists() ? "✓" : "✗ NOT FOUND"));
        LogManager.logI(TAG, "  merges.txt: " + (mergesTxt.exists() ? "✓" : "✗ NOT FOUND"));
        
        if (!textEncoder.exists() || !unet.exists() || !vaeDecoder.exists()) {
            throw new Exception("Required Diffusion model files not found");
        }
        
        // Get user-selected backend from settings - now supports ALL backends including CPU!
        String backendPreference = com.example.offlineai.SettingsFragment.getBackendPreference(context);
        int backendType = mapBackendToMnnForwardType(backendPreference);
        
        // CPU backend is now supported via custom CPUGroupNorm implementation
        // User can choose: CPU (slow but compatible), OpenCL, Vulkan, or NNAPI
        LogManager.logI(TAG, "Creating Diffusion session with user-selected backend: " + backendPreference + " (type=" + backendType + ")");
        
        // ⚠️ First load warning for GPU backends
        if (backendType == 3 || backendType == 7) { // OpenCL or Vulkan
            LogManager.logW(TAG, "========================================");
            LogManager.logW(TAG, "⚠️ FIRST-TIME GPU LOAD WARNING ⚠️");
            LogManager.logW(TAG, "Diffusion model will compile OpenCL/Vulkan kernels");
            LogManager.logW(TAG, "This may take 5-15 MINUTES on first load!");
            LogManager.logW(TAG, "Subsequent loads will use cached kernels (much faster)");
            LogManager.logW(TAG, "Please be patient and don't force-close the app...");
            LogManager.logW(TAG, "========================================");
        }
        
        // 从ConfigManager获取内存模式配置
        int memoryMode = ConfigManager.getDiffusionMemoryMode(context);
        LogManager.logI(TAG, "Using memory mode from config: " + memoryMode + " (" + ConfigManager.getDiffusionMemoryModeString(context) + ")");
        
        // CRITICAL: CPU backend doesn't support FmhaV2 operator in models
        // OpenCL models have FmhaV2 baked in, need to use OpenCL backend
        // Note: MNN has Attention fallback, but it's not triggered for pre-optimized models
        boolean isOpenCLModel = modelPath.toLowerCase().contains("opencl");
        if (backendType == 0 && isOpenCLModel) {
            LogManager.logW(TAG, "⚠️ Using OpenCL-optimized model with CPU backend");
            LogManager.logW(TAG, "This model uses FmhaV2 which may not work on CPU");
            LogManager.logW(TAG, "If it crashes, please:");
            LogManager.logW(TAG, "1. Switch to OpenCL backend (Settings → Diffusion Backend)");
            LogManager.logW(TAG, "2. Or download a CPU-compatible Diffusion model");
            LogManager.logW(TAG, "Attempting to load anyway...");
            // Don't throw exception - let MNN try (it might work with some models)
        }
        
        diffusionHandle = MnnInference.createDiffusion(
            modelPath,
            0, // STABLE_DIFFUSION_1_5
            backendType, // Use user-selected backend
            memoryMode  // Use configured memory mode (0=low, 1=enough, 2=balance)
        );
        
        if (diffusionHandle == 0) {
            // Provide detailed error message based on backend type
            String errorMsg = "Failed to create Diffusion session with backend: " + backendPreference;
            if (backendType == 0) { // CPU
                errorMsg += "\nCPU backend failed. Note: CPU doesn't support FmhaV2 operator used in OpenCL models.";
                errorMsg += "\nTry: Use OpenCL backend with opencl model, or use a basic CPU model.";
            } else if (backendType == 3) { // OpenCL
                errorMsg += "\nOpenCL backend failed. Try: Vulkan, NNAPI, or CPU (with CPU-compatible model).";
            } else if (backendType == 7) { // Vulkan
                errorMsg += "\nVulkan backend failed. Try: OpenCL, NNAPI, or CPU (with CPU-compatible model).";
            } else {
                errorMsg += "\nBackend initialization failed. Check model compatibility with selected backend.";
            }
            LogManager.logE(TAG, errorMsg);
            throw new Exception(errorMsg);
        }
        
        LogManager.logI(TAG, "Diffusion session created successfully, handle=" + diffusionHandle);
        isInitialized.set(true);
    }
    
    @Override
    public void inference(String prompt, LocalLlmHandler.InferenceParams params, LocalLlmHandler.StreamingCallback callback) {
        // Call the overloaded version with null images
        inference(prompt, null, params, callback);
    }
    
    @Override
    public void inference(String prompt, List<String> imagePaths, LocalLlmHandler.InferenceParams params, LocalLlmHandler.StreamingCallback callback) {
        if (isGenerating.get()) {
            callback.onError("Image generation already in progress");
            return;
        }
        
        // 检查handle是否有效，如果无效则重新初始化
        if (!isInitialized.get() || diffusionHandle == 0) {
            LogManager.logW(TAG, "Diffusion handle lost, reinitializing...");
            try {
                // 重新初始化（使用上次的配置）
                if (this.currentModelPath != null) {
                    initialize(this.currentModelPath, null);
                } else {
                    callback.onError("Diffusion not initialized and no model path available");
                    return;
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to reinitialize Diffusion: " + e.getMessage(), e);
                callback.onError("Failed to reinitialize Diffusion: " + e.getMessage());
                return;
            }
        }
        
        if (isGenerating.get()) {
            callback.onError("Image generation already in progress");
            return;
        }
        
        LogManager.logI(TAG, "Starting image generation with prompt: " + prompt);
        
        // Submit async task
        executorService.submit(() -> {
            try {
                isGenerating.set(true);
                shouldStop.set(false);
                
                // Prepare output path
                File cacheDir = context.getCacheDir();
                String outputPath = new File(cacheDir, "diffusion_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
                
                LogManager.logI(TAG, "Output path: " + outputPath);
                
                // 从 ConfigManager 获取 Steps 配置
                int steps = ConfigManager.getDiffusionSteps(context);
                // 限制范围 1-50
                steps = Math.max(MIN_STEPS, Math.min(steps, MAX_STEPS));
                LogManager.logI(TAG, "Using steps from config: " + steps);
                
                // 从 ConfigManager 获取 Seed 配置
                int seed;
                boolean useRandomSeed = ConfigManager.getDiffusionSeedRandom(context);
                if (useRandomSeed) {
                    seed = -1;  // -1 = 随机
                    LogManager.logI(TAG, "Using random seed");
                } else {
                    seed = ConfigManager.getDiffusionSeed(context);
                    LogManager.logI(TAG, "Using fixed seed from config: " + seed);
                }
                
                LogManager.logI(TAG, "Diffusion params: steps=" + steps + ", cfg=" + CFG_SCALE + ", scheduler=" + SCHEDULER + ", seed=" + seed);
                
                // 开始debug输出（只输出开头标签）
                final StringBuilder debugLog = new StringBuilder();
                debugLog.append("<debug>");
                callback.onToken(debugLog.toString());
                debugLog.setLength(0); // 清空，后续追加内容
                
                // Generate image
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
                            // Check if user requested stop
                            if (shouldStop.get()) {
                                LogManager.logI(TAG, "Image generation cancelled by user");
                                return false;
                            }
                            return true;
                        }
                        
                        @Override
                        public boolean onToken(String message) {
                            // 流式追加debug内容（不带标签，因为已经在开头输出<debug>了）
                            callback.onToken(message);
                            return !shouldStop.get();
                        }
                    }
                );
                
                long duration = System.currentTimeMillis() - startTime;
                
                // 关闭debug标签
                callback.onToken("\n</debug>");
                
                // Check if generation succeeded
                if (success && new File(outputPath).exists()) {
                    // Image generated successfully
                    LogManager.logI(TAG, "Image generated successfully in " + duration + "ms: " + outputPath);
                    
                    // 输出顺序：debug -> 图片 -> performance
                    
                    // 1. 输出图片路径（使用[IMAGE:path]标记，RagQaFragment会自动处理并显示在chat UI中）
                    callback.onToken("\n\n[IMAGE:" + outputPath + "]");
                    
                    // 2. 输出完整performance统计（参考LLM格式）
                    String perfStats = getPerformanceStats(duration, steps, seed, this.currentModelPath);
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
    @Override
    public void stopInference() {
        LogManager.logI(TAG, "Stopping image generation");
        shouldStop.set(true);
    }

    /**
     * Generate complete performance statistics report (similar to LLM)
     */
    private String getPerformanceStats(long duration, int steps, int seed, String modelPath) {
        float totalSec = duration / 1000.0f;
        float secPerStep = totalSec / steps;

        // Get JVM memory info
        Runtime runtime = Runtime.getRuntime();
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

    @Override
    public void release() {
        destroy();
    }
    
    /**
     * Clean up resources
     */
    public void destroy() {
        LogManager.logI(TAG, "Releasing Diffusion handler");

        try {
            // Stop any ongoing generation
            stopInference();

            // Release Diffusion session
            if (diffusionHandle != 0) {
                MnnInference.releaseDiffusion(diffusionHandle);
                diffusionHandle = 0;
                LogManager.logI(TAG, "Diffusion session released");
            }
            
            // Shutdown executor
            if (!executorService.isShutdown()) {
                List<Runnable> droppedTasks = executorService.shutdownNow();
                LogManager.logD(TAG, "Executor shutdownNow, dropped tasks: " + droppedTasks.size());
            }
            
            isInitialized.set(false);
            LogManager.logI(TAG, "Diffusion handler released");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error releasing Diffusion handler", e);
        }
    }
    
    @Override
    public String getEngineType() {
        return "diffusion";
    }
    
    /**
     * Map backend preference to MNN Forward Type integer
     * NO FALLBACK - Use exactly what user selected for easier debugging
     * Backend types in MNN native:
     * - MNN_FORWARD_CPU = 0
     * - MNN_FORWARD_OPENCL = 3
     * - MNN_FORWARD_VULKAN = 7
     * - MNN_FORWARD_NN (NNAPI) = 6
     * 
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
}
