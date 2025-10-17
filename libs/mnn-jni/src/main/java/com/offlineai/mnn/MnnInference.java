package com.offlineai.mnn;

import android.util.Log;
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
    
    // ========== Session Management ==========
    
    /**
     * Create MNN LLM session
     * @param modelDir Model directory containing llm.mnn, llm.mnn.weight, tokenizer.txt, etc.
     * @param configJson JSON configuration string for inference parameters
     * @return Session handle (pointer)
     */
    public static native long createSession(String modelDir, String configJson);
    
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
    
    // ========== Callback Interface ==========
    
    /**
     * Callback interface for streaming inference output
     */
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
}
