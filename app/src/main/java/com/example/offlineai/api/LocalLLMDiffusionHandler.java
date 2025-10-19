package com.example.offlineai.api;

import android.content.Context;
import android.net.Uri;

import com.example.offlineai.LogManager;
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
        
        LogManager.logI(TAG, "Initializing Diffusion handler with model: " + modelPath);
        
        this.currentModelPath = modelPath;
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
        
        diffusionHandle = MnnInference.createDiffusion(
            modelPath,
            0, // STABLE_DIFFUSION_1_5
            backendType, // Use user-selected backend
            0  // CRITICAL: memory_low mode (0=low, 1=enough, 2=balance) - enough mode causes OOM/freeze
        );
        
        if (diffusionHandle == 0) {
            // Provide detailed error message based on backend type
            String errorMsg = "Failed to create Diffusion session with backend: " + backendPreference;
            if (backendType == 0) { // CPU
                errorMsg += "\nCPU backend should work now (slower). Please check model files or try GPU backend for better performance.";
            } else if (backendType == 3) { // OpenCL
                errorMsg += "\nOpenCL backend failed. Try: CPU (slower but works), Vulkan, or NNAPI.";
            } else if (backendType == 7) { // Vulkan
                errorMsg += "\nVulkan backend failed. Try: CPU (slower but works), OpenCL, or NNAPI.";
            } else {
                errorMsg += "\nBackend initialization failed. Try CPU backend (slower but most compatible).";
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
        if (!isInitialized.get() || diffusionHandle == 0) {
            callback.onError("Diffusion model not initialized");
            return;
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
                
                // Get denoising steps from params (default 20)
                int steps = params != null && params.getMaxTokens() > 0 ? Math.min(params.getMaxTokens(), 50) : 20;
                
                // Notify loading
                callback.onToken("🔄 Loading Stable Diffusion model...\n");
                
                // Generate image
                final long startTime = System.currentTimeMillis();
                boolean success = MnnInference.generateImage(
                    diffusionHandle,
                    prompt,
                    outputPath,
                    steps,
                    -1, // random seed
                    progress -> {
                        // Update progress
                        if (progress % 10 == 0 || progress == 100) {
                            String progressText = "\n🎨 Generating image... " + progress + "%";
                            callback.onToken(progressText);
                        }
                        
                        // Check if user requested stop
                        if (shouldStop.get()) {
                            LogManager.logI(TAG, "Image generation cancelled by user");
                            return false; // Stop generation
                        }
                        
                        return true; // Continue generation
                    }
                );
                
                long duration = System.currentTimeMillis() - startTime;
                
                // Check if generation succeeded
                if (success && new File(outputPath).exists()) {
                    // Image generated successfully
                    LogManager.logI(TAG, "Image generated successfully in " + duration + "ms: " + outputPath);
                    
                    // Send special token to indicate image location
                    String imageMessage = "\n\n✨ Image generated successfully (" + (duration / 1000.0f) + "s)\n[IMAGE:" + outputPath + "]";
                    
                    callback.onToken(imageMessage);
                    callback.onComplete(imageMessage);
                } else {
                    // Generation failed
                    LogManager.logE(TAG, "Image generation failed");
                    callback.onError("Image generation failed. Please check logs for details.");
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
    
    @Override
    public void release() {
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
