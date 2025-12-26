package com.offlineai.mnn;

import android.util.Log;
import android.util.Pair;
import java.util.List;
import java.util.Map;

/**
 * MNN Inference JNI Interface
 * Provides simplified JNI bindings for MNN LLM inference engine
 * 
 * Features:
 * - One-shot inference with built-in autoregressive loop
 * - Streaming output via callback
 * - Multi-modal support (text, image, audio)
 * - Multiple backend support (CPU, OpenCL, Vulkan, NNAPI)
 * - Automatic KV cache management
 * 
 * @author OfflineAI Team
 * @version 1.0
 */
public class MnnInference {
    private static final String TAG = "MnnInference";
    
    // Load native library
    static {
        try {
            System.loadLibrary("mnn_jni");
            Log.i(TAG, "MNN JNI library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load MNN JNI library", e);
            throw new RuntimeException("Failed to load MNN JNI library", e);
        }
    }
    
    // ========== Logger Initialization (MUST call from Application.onCreate) ==========
    
    /**
     * Initialize MNN logger to redirect MNN_PRINT/MNN_ERROR to LogManager
     * CRITICAL: Must be called from Application.onCreate() after LogManager is initialized
     */
    public static native void initMnnLogger();
    
    /**
     * Register custom CPU operators (e.g., CPUGroupNorm)
     * CRITICAL: Must be called after MNN initialization (e.g., in Application.onCreate)
     */
    public static native void registerCPUGroupNorm();
    
    // ========== Session Management ==========
    
    /**
     * Create MNN LLM session
     * @param modelDir Model directory containing llm.mnn, llm.mnn.weight, tokenizer.txt, etc.
     * @param configJson JSON configuration string for inference parameters
     * @return Session handle (pointer)
     */
    public static native long createSession(String modelDir, String configJson);
    
    /**
     * Create MNN LLM session with separate config files
     * @param modelDir Model directory containing llm.mnn, llm.mnn.weight, tokenizer.txt, etc.
     * @param configJsonPath Path to config.json file (original model configuration)
     * @param runtimeConfigJson Runtime configuration JSON string for inference parameters
     * @return Session handle (pointer)
     */
    public static native long createSessionWithConfig(String modelDir, String configJsonPath, String runtimeConfigJson);
    
    /**
     * Destroy MNN LLM session and release resources
     * @param sessionHandle Session handle returned by createSession
     */
    public static native void destroySession(long sessionHandle);
    
    /**
     * Reset session history (keep system prompt)
     * @param sessionHandle Session handle
     */
    public static native void resetSession(long sessionHandle);
    
    // ========== Inference Methods ==========
    
    /**
     * Perform text-only inference (one-shot with streaming)
     * @param sessionHandle Session handle
     * @param prompt User prompt text
     * @param callback Streaming callback for token output
     * @return Inference statistics (prompt_len, decode_len, prefill_time, decode_time, etc.)
     */
    public static native Map<String, Long> inference(
        long sessionHandle,
        String prompt,
        InferenceCallback callback
    );
    
    /**
     * Perform multi-modal inference with images
     * @param sessionHandle Session handle
     * @param prompt User prompt text (can contain <img>path</img> tags)
     * @param imagePaths Array of image file paths
     * @param callback Streaming callback for token output
     * @return Inference statistics
     */
    public static native Map<String, Long> inferenceWithImages(
        long sessionHandle,
        String prompt,
        String[] imagePaths,
        InferenceCallback callback
    );
    
    /**
     * Perform multi-modal inference with audio
     * @param sessionHandle Session handle
     * @param prompt User prompt text (can contain <audio>path</audio> tags)
     * @param audioPaths Array of audio file paths
     * @param callback Streaming callback for token output
     * @return Inference statistics
     */
    public static native Map<String, Long> inferenceWithAudio(
        long sessionHandle,
        String prompt,
        String[] audioPaths,
        InferenceCallback callback
    );
    
    /**
     * Perform multi-modal inference with images and/or audio (unified interface)
     * @param sessionHandle Session handle
     * @param prompt User prompt text
     * @param imagePaths Array of image file paths (can be null)
     * @param audioPaths Array of audio file paths (can be null)
     * @param callback Streaming callback for token output
     * @return Inference statistics
     */
    public static native Map<String, Long> inferenceMultimodal(
        long sessionHandle,
        String prompt,
        String[] imagePaths,
        String[] audioPaths,
        InferenceCallback callback
    );
    
    /**
     * Perform streaming inference with conversation history
     * @param sessionHandle Session handle from createSession
     * @param history Conversation history (List of Pair<role, content>)
     * @param callback Streaming callback for token output
     * @return Inference statistics
     */
    public static native Map<String, Long> inferenceWithHistory(
        long sessionHandle,
        List<Pair<String, String>> history,
        InferenceCallback callback
    );
    
    // ========== Configuration Methods ==========
    
    /**
     * Update session configuration dynamically
     * @param sessionHandle Session handle
     * @param configJson New JSON configuration string
     */
    public static native void updateConfig(long sessionHandle, String configJson);
    
    /**
     * Get current configuration as JSON string
     * @param sessionHandle Session handle
     * @return JSON configuration string
     */
    public static native String getConfig(long sessionHandle);
    
    /**
     * Set system prompt
     * @param sessionHandle Session handle
     * @param systemPrompt System prompt text
     */
    public static native void setSystemPrompt(long sessionHandle, String systemPrompt);
    
    /**
     * Get system prompt
     * @param sessionHandle Session handle
     * @return System prompt text
     */
    public static native String getSystemPrompt(long sessionHandle);
    
    /**
     * Set max new tokens for generation
     * @param sessionHandle Session handle
     * @param maxNewTokens Maximum number of tokens to generate
     */
    public static native void setMaxNewTokens(long sessionHandle, int maxNewTokens);
    
    /**
     * Clear chat history (keep only system prompt)
     * @param sessionHandle Session handle
     * @param numToKeep Number of recent messages to keep (default: 1 for system prompt)
     */
    public static native void clearHistory(long sessionHandle, int numToKeep);
    
    // ========== Debug & Info Methods ==========
    
    /**
     * Get debug information (last prompt and response)
     * @param sessionHandle Session handle
     * @return Debug info string
     */
    public static native String getDebugInfo(long sessionHandle);
    
    /**
     * Get MNN version string
     * @return MNN version
     */
    public static native String getMnnVersion();
    
    /**
     * Check if backend is available
     * @param backendName Backend name ("cpu", "opencl", "vulkan", "nnapi")
     * @return true if backend is available
     */
    public static native boolean isBackendAvailable(String backendName);
    
    /**
     * Diagnose OpenCL library availability and paths
     * @return Diagnostic report string
     */
    public static native String diagnoseOpenCL();
    
    // ========== TTS (Text-to-Speech) Support ==========
    
    /**
     * Check if model supports TTS (has talker.mnn)
     * @param sessionHandle Session handle
     * @return true if TTS is supported
     */
    public static native boolean hasTTS(long sessionHandle);
    
    /**
     * Set TTS waveform callback to receive generated audio
     * @deprecated Use setTtsOutputPath() for synchronous file writing (Diffusion-style)
     * @param sessionHandle Session handle
     * @param callback TTS callback for receiving audio data
     * @return true if callback was set successfully
     */
    @Deprecated
    public static native boolean setWavformCallback(long sessionHandle, TtsCallback callback);
    
    /**
     * Set TTS output file path for synchronous WAV file writing
     * C++ layer will write WAV file directly, like Diffusion does for images
     * @param sessionHandle Session handle
     * @param outputPath Output WAV file path (or null to disable)
     */
    public static native void setTtsOutputPath(long sessionHandle, String outputPath);
    
    /**
     * Get TTS output file path (returns path after successful generation)
     * @param sessionHandle Session handle
     * @return Output path if file was written, null otherwise
     */
    public static native String getTtsOutputPath(long sessionHandle);
    
    /**
     * Callback interface for TTS audio output
     * @deprecated Use setTtsOutputPath() instead
     */
    @Deprecated
    public interface TtsCallback {
        /**
         * Called when audio data is generated (24kHz mono float32 PCM)
         * @param data Audio samples in range [-1.0, 1.0]
         * @param isEnd true if this is the last chunk
         * @return false to continue, true to stop
         */
        boolean onAudioData(float[] data, boolean isEnd);
    }
    
    public interface InferenceCallback {
        /**
         * Called when a new token is generated
         * @param token Generated token text (UTF-8 decoded)
         * @return true to stop generation, false to continue
         */
        boolean onToken(String token);
        
        /**
         * Called when inference is complete
         * @param stats Inference statistics map
         */
        void onComplete(Map<String, Long> stats);
        
        /**
         * Called when an error occurs
         * @param error Error message
         */
        void onError(String error);
    }
    
    // Note: inferenceWithHistory() now uses List<Pair<String, String>> instead of PromptItem[]
    // This matches ChatMNN implementation and avoids JNI field access issues
    // Use: List<Pair<String, String>> history = new ArrayList<>();
    //      history.add(new Pair<>("user", "Hello"));
    //      history.add(new Pair<>("assistant", "Hi there!"));
    
    // ========== Helper Classes ==========
    
    /**
     * MNN configuration builder for easy JSON generation
     */
    public static class ConfigBuilder {
        private final StringBuilder json = new StringBuilder("{");
        private boolean first = true;
        
        public ConfigBuilder backendType(String backend) {
            addField("backend_type", backend);
            return this;
        }
        
        public ConfigBuilder threadNum(int threads) {
            addField("thread_num", threads);
            return this;
        }
        
        public ConfigBuilder precision(String precision) {
            addField("precision", precision);
            return this;
        }
        
        public ConfigBuilder memory(String memory) {
            addField("memory", memory);
            return this;
        }
        
        public ConfigBuilder power(String power) {
            addField("power", power);
            return this;
        }
        
        public ConfigBuilder maxNewTokens(int tokens) {
            addField("max_new_tokens", tokens);
            return this;
        }
        
        public ConfigBuilder maxAllTokens(int tokens) {
            addField("max_all_tokens", tokens);
            return this;
        }
        
        public ConfigBuilder temperature(float temp) {
            addField("temperature", temp);
            return this;
        }
        
        public ConfigBuilder topP(float topP) {
            addField("topP", topP);
            return this;
        }
        
        public ConfigBuilder topK(int topK) {
            addField("topK", topK);
            return this;
        }
        
        public ConfigBuilder reuseKv(boolean reuse) {
            addField("reuse_kv", reuse);
            return this;
        }
        
        public ConfigBuilder useMmap(boolean mmap) {
            addField("use_mmap", mmap);
            return this;
        }
        
        public ConfigBuilder tmpPath(String path) {
            addField("tmp_path", path);
            return this;
        }
        
        public ConfigBuilder kvcacheMmap(boolean enable) {
            addField("kvcache_mmap", enable);
            return this;
        }
        
        public ConfigBuilder systemPrompt(String prompt) {
            addField("system_prompt", prompt);
            return this;
        }
        
        public ConfigBuilder chunk(int chunkSize) {
            addField("chunk", chunkSize);
            return this;
        }
        
        public ConfigBuilder kvcacheLimit(int limit) {
            addField("kvcache_limit", limit);
            return this;
        }
        
        public ConfigBuilder isAudio(boolean enable) {
            addField("is_audio", enable);
            return this;
        }
        
        public ConfigBuilder audioModel(String modelPath) {
            addField("audio_model", modelPath);
            return this;
        }
        
        public ConfigBuilder audioPad(int padToken) {
            addField("audio_pad", padToken);
            return this;
        }
        
        public ConfigBuilder talkerMaxNewTokens(int tokens) {
            addField("talker_max_new_tokens", tokens);
            return this;
        }
        
        // Note: talker_speaker NOT provided - model-specific parameter (e.g., Qwen2.5-Omni: "Chelsie"/"Ethan")
        // Different models have different speaker options, should use model's default config
        
        public ConfigBuilder ditSteps(int steps) {
            addField("dit_steps", steps);
            return this;
        }
        
        public ConfigBuilder ditSolver(int solver) {
            addField("dit_solver", solver);
            return this;
        }
        
        private void addField(String key, String value) {
            if (!first) json.append(",");
            json.append("\"").append(key).append("\":\"").append(value).append("\"");
            first = false;
        }
        
        private void addField(String key, int value) {
            if (!first) json.append(",");
            json.append("\"").append(key).append("\":").append(value);
            first = false;
        }
        
        private void addField(String key, float value) {
            if (!first) json.append(",");
            json.append("\"").append(key).append("\":").append(value);
            first = false;
        }
        
        private void addField(String key, boolean value) {
            if (!first) json.append(",");
            json.append("\"").append(key).append("\":").append(value);
            first = false;
        }
        
        public String build() {
            json.append("}");
            return json.toString();
        }
    }
    
    // ========== Embedding Support ==========
    
    /**
     * Create MNN Embedding session with runtime config
     * @param modelDir Path to model directory
     * @param runtimeConfig Runtime configuration JSON string (memory, power, precision, thread_num)
     * @return Embedding handle (pointer)
     */
    public static native long createEmbeddingWithConfig(String modelDir, String runtimeConfig);
    
    /**
     * Compute embedding vector for text
     * @param embeddingHandle Embedding handle returned by createEmbedding
     * @param text Input text
     * @return Embedding vector (float array)
     */
    public static native float[] computeEmbedding(long embeddingHandle, String text);
    
    /**
     * Get embedding dimension
     * @param embeddingHandle Embedding handle
     * @return Embedding dimension
     */
    public static native int getEmbeddingDimension(long embeddingHandle);
    
    /**
     * Check if embedding handle is valid
     * @param embeddingHandle Embedding handle
     * @return true if valid
     */
    public static native boolean isEmbeddingValid(long embeddingHandle);
    
    /**
     * Release embedding session and free resources
     * @param embeddingHandle Embedding handle
     */
    public static native void releaseEmbedding(long embeddingHandle);
    
    // ========== Reranker Support ==========
    
    /**
     * Create MNN Reranker session with runtime config
     * @param modelDir Path to model directory
     * @param runtimeConfig Runtime configuration JSON string (memory, power, precision, thread_num)
     * @return Reranker handle (pointer)
     */
    public static native long createRerankerWithConfig(String modelDir, String runtimeConfig);
    
    /**
     * Set instruction for reranker
     * @param rerankerHandle Reranker handle
     * @param instruction Instruction string
     */
    public static native void setRerankerInstruction(long rerankerHandle, String instruction);
    
    /**
     * Compute relevance scores for documents
     * MNN reranker outputs softmax-normalized scores in range [0.0, 1.0]
     * - score ≈ 1.0: highly relevant
     * - score ≈ 0.5: moderately relevant
     * - score ≈ 0.0: not relevant
     * 
     * @param rerankerHandle Reranker handle
     * @param query Query text
     * @param documents Array of document texts
     * @return Array of relevance scores (already normalized to [0.0, 1.0])
     */
    public static native float[] computeScores(long rerankerHandle, String query, String[] documents);
    
    /**
     * Check if reranker handle is valid
     * @param rerankerHandle Reranker handle
     * @return true if valid
     */
    public static native boolean isRerankerValid(long rerankerHandle);
    
    /**
     * Release reranker session and free resources
     * @param rerankerHandle Reranker handle
     */
    public static native void releaseReranker(long rerankerHandle);
    
    // ========== NER (Named Entity Recognition) Support ==========
    
    /**
     * Create MNN NER session with runtime config
     * Optimized for entity extraction with KV Cache management
     * @param modelDir Path to model directory
     * @param runtimeConfig Runtime configuration JSON string (memory, power, precision, thread_num)
     * @return NER handle (pointer)
     */
    public static native long createNerWithConfig(String modelDir, String runtimeConfig);
    
    /**
     * Extract entities from text
     * System prompt is cached in KV Cache for reuse
     * @param nerHandle NER handle
     * @param systemPrompt System prompt (cached on first call)
     * @param text Input text to extract entities from
     * @return LLM response (entity list in JSON or simple format)
     */
    public static native String extractEntities(long nerHandle, String systemPrompt, String text);
    
    /**
     * Release NER session and free resources
     * @param nerHandle NER handle
     */
    public static native void releaseNer(long nerHandle);
    
    // ========== Diffusion (Text2Image) Support ==========
    
    /**
     * Create MNN Diffusion session for text-to-image generation
     * @param modelDir Path to model directory containing text_encoder.mnn, unet.mnn, vae_decoder.mnn, vocab.json, merges.txt
     * @param modelType Model type: 0=SD1.5, 1=Taiyi Chinese, 2=ZImage
     * @param backendType Backend type: 0=CPU, 1=Metal, 3=OpenCL, 7=Vulkan
     * @param memoryMode Memory mode: 0=saving (slower), 1=enough (faster), 2=balance
     * @param imageSize Output image size (512, 640, 768, 896, 1024)
     * @param textEncoderOnCPU Force text_encoder to run on CPU to avoid GPU buffer size limit
     * @param cachePath Path to cache directory where .tempcache will be saved (backend-specific)
     * @param callback Callback for progress updates during model loading
     * @return Diffusion handle (pointer)
     */
    public static native long createDiffusion(String modelDir, int modelType, int backendType, int memoryMode, int imageSize, boolean textEncoderOnCPU, String cachePath, DiffusionCallback callback);
    
    /**
     * Create MNN Diffusion session with advanced GPU configuration
     * @param modelDir Path to model directory
     * @param modelType Model type: 0=SD1.5, 1=Taiyi Chinese, 2=ZImage
     * @param backendType Backend type: 0=CPU, 1=Metal, 3=OpenCL, 7=Vulkan
     * @param memoryMode Memory mode: 0=saving (slower), 1=enough (faster), 2=balance
     * @param imageSize Output image size (512, 640, 768, 896, 1024)
     * @param textEncoderOnCPU Force text_encoder to run on CPU
     * @param gpuMemoryMode GPU memory mode: 0=AUTO, 1=BUFFER, 2=IMAGE
     * @param precisionMode Precision mode: 0=AUTO, 1=LOW(FP16), 2=NORMAL(FP32), 3=HIGH(FP32)
     * @param numThreads CPU thread count for inference (from settings)
     * @param cachePath Path to cache directory
     * @param callback Callback for progress updates
     * @return Diffusion handle (pointer)
     */
    public static native long createDiffusionAdvanced(String modelDir, int modelType, int backendType, int memoryMode, int imageSize, boolean textEncoderOnCPU, int gpuMemoryMode, int precisionMode, int numThreads, String cachePath, DiffusionCallback callback);
    
    /**
     * Create MNN Diffusion session with separate width and height for non-square aspect ratios
     * @param modelDir Path to model directory
     * @param modelType Model type: 0=SD1.5, 1=Taiyi Chinese, 2=ZImage
     * @param backendType Backend type: 0=CPU, 1=Metal, 3=OpenCL, 7=Vulkan
     * @param memoryMode Memory mode: 0=saving (slower), 1=enough (faster), 2=balance
     * @param imageWidth Output image width (must be multiple of 8, e.g., 1280, 1024, 768, 720, 512)
     * @param imageHeight Output image height (must be multiple of 8, e.g., 720, 1024, 768, 1280, 512)
     * @param textEncoderOnCPU Force text_encoder to run on CPU
     * @param gpuMemoryMode GPU memory mode: 0=AUTO, 1=BUFFER, 2=IMAGE
     * @param precisionMode Precision mode: 0=AUTO, 1=LOW(FP16), 2=NORMAL(FP32), 3=HIGH(FP32)
     * @param numThreads CPU thread count for inference (from settings)
     * @param cachePath Path to cache directory
     * @param callback Callback for progress updates
     * @return Diffusion handle (pointer)
     */
    public static native long createDiffusionWithSize(String modelDir, int modelType, int backendType, int memoryMode, int imageWidth, int imageHeight, boolean textEncoderOnCPU, int gpuMemoryMode, int precisionMode, int numThreads, String cachePath, DiffusionCallback callback);
    
    /**
     * Generate image from text prompt
     * @param diffusionHandle Diffusion handle returned by createDiffusion
     * @param prompt Text prompt describing the image to generate
     * @param outputPath Output image file path (e.g., /sdcard/output.jpg)
     * @param iterNum Number of denoising iterations (recommended: 10-20)
     * @param randomSeed Random seed for reproducibility (use -1 for random)
     * @param cfgScale CFG (Classifier-Free Guidance) scale (0.0-10.0, default 1.0 for ZImage, 7.5 for SD1.5)
     * @param callback Progress callback (receives progress percentage 0-100)
     * @return true if generation succeeded, false otherwise
     */
    public static native boolean generateImage(
        long diffusionHandle, 
        String prompt, 
        String outputPath, 
        int iterNum, 
        int randomSeed,
        float cfgScale,
        DiffusionCallback callback
    );
    
    /**
     * Release diffusion session and free resources
     * @param diffusionHandle Diffusion handle
     */
    public static native void releaseDiffusion(long diffusionHandle);
    
    /**
     * Callback interface for diffusion progress
     */
    public interface DiffusionCallback {
        /**
         * Called periodically to report generation progress
         * @param progress Progress percentage (0-100)
         * @return true to continue, false to stop generation
         */
        boolean onProgress(int progress);
        
        /**
         * Called to report detailed status messages (with <debug> tags)
         * @param message Status message
         * @return true to continue, false to stop
         */
        boolean onToken(String message);
    }

    // ========== ASR (Automatic Speech Recognition) ==========
    // NOTE: ASR functionality has been moved to sherpa-mnn-jni module
    // Use com.k2fsa.sherpa.mnn.OnlineRecognizer from sherpa-mnn's Kotlin API
    
    // ========== TTS (Text-to-Speech) - External Models ==========
    
    /**
     * Create external TTS session (for standalone TTS models, not Omni)
     * @param modelDir TTS model directory path
     * @param configJson JSON configuration string for TTS model
     * @return TTS session handle (pointer), 0 if failed
     */
    public static native long createTtsSession(String modelDir, String configJson);
    
    /**
     * Generate TTS audio from text
     * @param ttsHandle TTS session handle
     * @param text Input text to synthesize
     * @return true if generation succeeded, false otherwise
     */
    public static native boolean generateTts(long ttsHandle, String text);
    
    /**
     * Destroy TTS session and release resources
     * @param ttsHandle TTS session handle
     */
    public static native void destroyTtsSession(long ttsHandle);
    
    // ========== External TTS (bert-vits2-MNN) - REMOVED ==========
    // bert-vits2-MNN should be treated as a standard LLM text-to-audio model
    // Use the existing LLM inference framework (load as LLM model)
    // No separate TTS SDK needed

} // End of MnnInference class
