package com.starlocalrag.llamacpp;

import android.util.Log;

/**
 * LlamaCppInference - LlamaCpp推理接口
 * 参考官方llama.android示例，提供简洁的JNI接口
 * 直接对接C++的JNI适配器，包含所有与llama_inference.cpp中JNI方法对应的native方法声明
 */
public class LlamaCppInference {
    private static final String TAG = "LlamaCppInference";
    
    // 加载本地库
    static {
        try {
            NativeLibraryLoader.loadLibrary("llamacpp_jni");
            Log.i(TAG, "LlamaCppInference native library loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load LlamaCppInference native library", e);
            throw new RuntimeException("Failed to load LlamaCppInference native library", e);
        }
    }
    
    // ========== 基础模型和上下文管理 ==========
    
    /**
     * 初始化后端
     */
    public static native void backend_init();
    
    /**
     * 加载模型
     * @param modelPath 模型文件路径
     * @return 模型句柄
     */
    public static native long load_model(String modelPath);
    
    // DEPRECATED: load_model_with_gpu has been removed.
    // Use load_model_with_backend with backend preference instead.
    
    /**
     * 加载模型（带后端偏好参数）
     * @param modelPath 模型文件路径
     * @param backendPreference 后端偏好字符串（"CPU"/"VULKAN"/"OPENCL"/"BLAS"/"CANN"）
     * @return 模型句柄
     */
    public static native long load_model_with_backend(String modelPath, String backendPreference);
    
    /**
     * 释放模型
     * @param modelHandle 模型句柄
     */
    public static native void free_model(long modelHandle);
    
    /**
     * 创建上下文
     * @param modelHandle 模型句柄
     * @param contextSize 上下文大小
     * @param threads 线程数
     * @param gpuLayers GPU层数
     * @return 上下文句柄
     */
    public static native long new_context_with_params(long modelHandle, int contextSize, int threads, int gpuLayers);
    
    /**
     * 创建上下文（简化版本，后端配置已在模型加载时确定）
     * @param modelHandle 模型句柄
     * @param nCtx 上下文长度
     * @param nBatch 批处理大小
     * @param nThreads 线程数
     * @return 上下文句柄
     */
    public static native long new_context_with_backend(long modelHandle, int nCtx, int nBatch, int nThreads);
    
    /**
     * 释放上下文
     * @param contextHandle 上下文句柄
     */
    public static native void free_context(long contextHandle);
    
    // ========== 批处理管理 ==========
    
    /**
     * 创建批处理
     * @param nTokens token数量
     * @param embd 嵌入维度
     * @param nSeqMax 最大序列数
     * @return 批处理句柄
     */
    public static native long new_batch(int nTokens, int embd, int nSeqMax);
    
    /**
     * 释放批处理
     * @param batchHandle 批处理句柄
     */
    public static native void free_batch(long batchHandle);
    
    // ========== 采样器管理 ==========
    
    /**
     * 创建采样器
     * @return 采样器句柄
     */
    public static native long new_sampler();
    
    /**
     * 释放采样器
     * @param samplerHandle 采样器句柄
     */
    public static native void free_sampler(long samplerHandle);
    
    // ========== 推理和生成 ==========
    
    /**
     * 初始化完成
     * @param contextHandle 上下文句柄
     * @param batchHandle 批处理句柄
     * @param prompt 提示词
     * @param maxTokens 最大token数
     * @param addBos 是否添加BOS
     * @return token数量
     */
    public static native int completion_init(long contextHandle, long batchHandle, String prompt, int maxTokens, boolean addBos);
    
    /**
     * 完成循环
     * @param contextHandle 上下文句柄
     * @param batchHandle 批处理句柄
     * @param samplerHandle 采样器句柄
     * @param maxTokens 最大token数
     * @param currentPos 当前位置（输入输出参数）
     * @return 生成的token文本
     */
    public static native String completion_loop(long contextHandle, long batchHandle, long samplerHandle, int maxTokens, IntVar currentPos);
    
    /**
     * 清空KV缓存
     * @param contextHandle 上下文句柄
     */
    public static native void kv_cache_clear(long contextHandle);
    
    // ========== 推理控制 ==========
    
    /**
     * 设置停止标志
     * @param shouldStop 是否应该停止推理
     */
    public static native void set_should_stop(boolean shouldStop);
    
    /**
     * 获取停止标志状态
     * @return 当前停止标志状态
     */
    public static native boolean get_should_stop();
    
    // ========== Vulkan信息获取 ==========
    
    /**
     * 获取Vulkan版本字符串
     * @return Vulkan版本字符串，如"1.3"或"1.1"，如果Vulkan不可用则返回null
     */
    public static native String get_vulkan_version();
    
    // ========== 模型元数据获取 ==========
    
    /**
     * 获取模型元数据键值对数量
     * @param modelHandle 模型句柄
     * @return 元数据键值对数量
     */
    public static native int model_meta_count(long modelHandle);
    
    /**
     * 根据索引获取元数据键名
     * @param modelHandle 模型句柄
     * @param index 索引
     * @return 键名，如果失败返回null
     */
    public static native String model_meta_key_by_index(long modelHandle, int index);
    
    /**
     * 根据键名获取元数据值
     * @param modelHandle 模型句柄
     * @param key 键名
     * @return 值，如果失败返回null
     */
    public static native String model_meta_val_str(long modelHandle, String key);
    
    /**
     * 根据索引获取元数据值
     * @param modelHandle 模型句柄
     * @param index 索引
     * @return 值，如果失败返回null
     */
    public static native String model_meta_val_str_by_index(long modelHandle, int index);
    
    /**
     * 获取模型大小（字节）
     * @param modelHandle 模型句柄
     * @return 模型大小（字节），如果失败返回0
     */
    public static native long model_size(long modelHandle);
    
    // ========== 参数设置（通过采样器实现）==========
    
    /**
     * 创建带参数的采样器
     * @param temperature 温度值
     * @param topP top-p值
     * @param topK top-k值
     * @return 采样器句柄
     */
    public static native long new_sampler_with_params(float temperature, float topP, int topK);
    
    /**
     * 创建带完整参数的采样器（包含repeat_penalty）
     */
    public static native long new_sampler_with_full_params(float temperature, float topP, int topK, float repeatPenalty);
    
    // ========= Context Shift (KV-Cache sliding) configuration =========
    /**
     * Enable/disable context shift and set n_keep prefix tokens to preserve.
     * English: When enabled, if current position reaches n_ctx, JNI will shift KV-Cache to keep only the first n_keep tokens
     * and continue generation, effectively enabling a sliding window.
     */
    public static native void set_context_shift(boolean enable, int nKeep);

    /**
     * English: Returns whether context shift is enabled in JNI.
     */
    public static native boolean get_context_shift_enabled();

    /**
     * English: Returns the configured n_keep value in JNI.
     */
    public static native int get_context_shift_n_keep();
    
    /**
     * 注意：参数设置已改为通过采样器实现
     * 使用 new_sampler_with_params() 代替单独的参数设置方法
     */
    @Deprecated
    public static void setTemperature(float temperature) {
        // 此方法已废弃，参数应通过采样器设置
        android.util.Log.w("LlamaCppInference", "setTemperature已废弃，请使用new_sampler_with_params");
    }
    
    @Deprecated
    public static void setTopP(float topP) {
        // 此方法已废弃，参数应通过采样器设置
        android.util.Log.w("LlamaCppInference", "setTopP已废弃，请使用new_sampler_with_params");
    }
    
    @Deprecated
    public static void setTopK(int topK) {
        // 此方法已废弃，参数应通过采样器设置
        android.util.Log.w("LlamaCppInference", "setTopK已废弃，请使用new_sampler_with_params");
    }
    
    // ========== 后端偏好设置 ==========
    
    /**
     * 设置后端偏好
     * @param backendPreference 后端偏好字符串 ("CPU", "VULKAN", "BLAS", "OPENCL", "CANN")
     */
    public static native void set_backend_preference(String backendPreference);
    
    /**
     * 获取当前后端偏好设置
     * @return 当前后端偏好字符串
     */
    public static native String get_backend_preference();
    
    /**
     * 设置后端偏好（Java层包装方法）
     * 提供参数验证和日志记录
     * @param backendPreference 后端偏好字符串
     */
    public static void setBackendPreference(String backendPreference) {
        if (backendPreference == null) {
            Log.w(TAG, "Backend preference is null, using default CPU");
            backendPreference = "CPU";
        }
        
        // 验证后端偏好值
        String normalizedPreference = backendPreference.toUpperCase().trim();
        switch (normalizedPreference) {
            case "CPU":
            case "VULKAN":
            case "BLAS":
            case "OPENCL":
            case "CANN":
            case "KLEIDIAI":
            case "KLEIDIAI-SME":
                Log.i(TAG, "Setting backend preference to: " + normalizedPreference);
                set_backend_preference(normalizedPreference);
                break;
            default:
                Log.w(TAG, "Unknown backend preference: " + backendPreference + ", using CPU");
                set_backend_preference("CPU");
                break;
        }
    }
    
    /**
     * 获取后端偏好（Java层包装方法）
     * @return 后端偏好字符串，默认为"CPU"
     */
    public static String getBackendPreference() {
        try {
            String preference = get_backend_preference();
            return preference != null ? preference : "CPU";
        } catch (Exception e) {
            Log.e(TAG, "Failed to get backend preference", e);
            return "CPU";
        }
    }
    
    // ========== 辅助类和接口 ==========
    
    /**
     * 整数变量类，用于传递可变整数参数
     */
    public static class IntVar {
        public int value;
        
        public IntVar(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public void inc() {
            value++;
        }
    }
    
    /**
     * 生成参数类
     */
    public static class GeneratorParams {
        private int maxLength = 512;
        private int threads = 4;
        private int maxNewTokens = 512;
        private boolean useGpu = false;
        
        public GeneratorParams setSearchOption(String key, Object value) {
            switch (key) {
                case "max_length":
                    this.maxLength = (Integer) value;
                    break;
                case "threads":
                    this.threads = (Integer) value;
                    break;
                case "max_new_tokens":
                    this.maxNewTokens = (Integer) value;
                    break;
                case "use_gpu":
                    this.useGpu = (Boolean) value;
                    break;
            }
            return this;
        }
        
        public int getMaxLength() { return maxLength; }
        public int getThreads() { return threads; }
        public int getMaxNewTokens() { return maxNewTokens; }
        public boolean isUseGpu() { return useGpu; }
    }
    
    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onText(String text, boolean isComplete);
    }
    
    // ========== 工具函数 ==========
    
    /**
     * 获取模型信息
     * @param model 模型句柄
     * @return 模型信息字符串，包含模型元数据
     */
    public static String getModelInfo(long model) {
        StringBuilder info = new StringBuilder();
        info.append("模型信息:\n");
        
        try {
            // 获取元数据数量
            int metaCount = model_meta_count(model);
            info.append("元数据数量: ").append(metaCount).append("\n");
            
            // 获取所有元数据
            info.append("元数据:\n");
            for (int i = 0; i < metaCount; i++) {
                String key = model_meta_key_by_index(model, i);
                String value = model_meta_val_str_by_index(model, i);
                if (key != null && value != null) {
                    info.append("  ").append(key).append(": ").append(value).append("\n");
                }
            }
            
            // 尝试获取一些常见的元数据
            String[] commonKeys = {"general.architecture", "general.name", "llama.context_length", 
                                  "llama.embedding_length", "llama.block_count", "llama.feed_forward_length", 
                                  "llama.attention.head_count", "llama.attention.head_count_kv", 
                                  "llama.rope.dimension_count", "tokenizer.ggml.model", "tokenizer.ggml.tokens"};
            
            info.append("\n常用参数:\n");
            for (String key : commonKeys) {
                String value = model_meta_val_str(model, key);
                if (value != null) {
                    info.append("  ").append(key).append(": ").append(value).append("\n");
                }
            }
        } catch (Exception e) {
            info.append("获取模型元数据失败: ").append(e.getMessage());
        }
        
        return info.toString();
    }
    
    /**
     * 生成文本（同步方式）
     * @param ctx 上下文句柄
     * @param prompt 输入提示
     * @param maxTokens 最大生成token数
     * @param temperature 温度参数
     * @param topP top-p采样参数
     * @param topK top-k采样参数
     * @return 生成的文本，失败返回null
     */
    public static String generateText(long ctx, String prompt, int maxTokens, 
                                           float temperature, float topP, int topK) {
        try {
            // 动态创建批处理大小，支持超长prompt
            // 首先估算prompt的token数量（粗略估算：字符数/4）
            int estimatedTokens = Math.max(512, prompt.length() / 4 + 100);
            // 限制最大batch大小避免内存问题
            int batchSize = Math.min(estimatedTokens, 2048);
            
            Log.i("LlamaCppInference", "动态batch大小 - 估算tokens: " + estimatedTokens + ", 使用batch大小: " + batchSize);
            
            // 创建批处理和采样器
            long batch = new_batch(batchSize, 0, 1);
            long sampler = new_sampler_with_params(temperature, topP, topK);
            
            // 清空KV缓存
            kv_cache_clear(ctx);
            
            // 初始化完成
            int tokenCount = completion_init(ctx, batch, prompt, maxTokens, false);
            if (tokenCount < 0) {
                free_batch(batch);
                free_sampler(sampler);
                return null;
            }
            
            // 生成循环
            StringBuilder result = new StringBuilder();
            IntVar currentPos = new IntVar(tokenCount);
            
            for (int i = 0; i < maxTokens; i++) {
                String token = completion_loop(ctx, batch, sampler, maxTokens, currentPos);
                if (token == null || token.isEmpty()) {
                    break;
                }
                result.append(token);
            }
            
            // 清理资源
            free_batch(batch);
            free_sampler(sampler);
            
            return result.toString();
        } catch (Exception e) {
            Log.e("LlamaCppInference", "Text generation failed", e);
            return null;
        }
    }
    
    /**
     * 获取库版本信息
     * @return 版本信息
     */
    public static String getVersion() {
        return "LlamaCpp JNI v1.0";
    }
}