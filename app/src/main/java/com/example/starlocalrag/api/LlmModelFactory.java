package com.example.starlocalrag.api;

import android.content.Context;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.AppConstants;
import com.example.starlocalrag.R;

import com.example.starlocalrag.ConfigManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 大模型工厂类
 * 负责创建和管理不同API提供商的模型实例
 */
public class LlmModelFactory {
    private static final String TAG = "LlmModelFactory";
    
    // 单例实例
    private static LlmModelFactory instance;
    
    // 上下文
    private final Context context;
    
    // API适配器实例
    private final LlmApiAdapter apiAdapter;
    
    // 模型提供商映射
    private final Map<String, ModelProvider> modelProviders = new HashMap<>();
    
    /**
     * 模型提供商类
     * 包含提供商名称、基础URL和支持的模型列表
     */
    public static class ModelProvider {
        private final String name;
        private final String baseUrl;
        private final String[] supportedModels;
        
        public ModelProvider(String name, String baseUrl, String[] supportedModels) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.supportedModels = supportedModels;
        }
        
        public String getName() {
            return name;
        }
        
        public String getBaseUrl() {
            return baseUrl;
        }
        
        public String[] getSupportedModels() {
            return supportedModels;
        }
    }
    
    /**
     * 获取工厂类单例
     */
    public static synchronized LlmModelFactory getInstance(Context context) {
        if (instance == null) {
            instance = new LlmModelFactory(context);
        }
        return instance;
    }
    
    /**
     * 私有构造函数
     */
    private LlmModelFactory(Context context) {
        this.context = context;
        this.apiAdapter = new LlmApiAdapter(context);
        initializeModelProviders();
    }
    
    /**
     * 初始化模型提供商
     */
    private void initializeModelProviders() {
        // 本地模型
        // 注意：本地模型的实际模型列表将在运行时动态获取
        modelProviders.put(AppConstants.ApiUrl.LOCAL, new ModelProvider(
                context.getString(R.string.api_url_local) + "模型",
                AppConstants.ApiUrl.LOCAL,
                new String[]{"Qwen3-0.6B_onnx_static_int8", "Qwen3-01.7B_onnx_static_int8"}
        ));
        
        // OpenAI兼容API
        modelProviders.put("openai", new ModelProvider(
                "OpenAI兼容API",
                "https://api.openai.com/v1",
                new String[]{"gpt-3.5-turbo", "gpt-4", "gpt-4-turbo"}
        ));
        
        // DeepSeek API
        modelProviders.put("deepseek", new ModelProvider(
                "DeepSeek API",
                "https://api.deepseek.com/v1",
                new String[]{"deepseek-chat", "deepseek-coder"}
        ));
        
        // Moonshot API
        modelProviders.put("moonshot", new ModelProvider(
                "Moonshot API",
                "https://api.moonshot.cn/v1",
                new String[]{"moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"}
        ));
        
        // 豆包 API
        modelProviders.put("doubao", new ModelProvider(
                "豆包 API",
                "https://api.doubao.com/v1",
                new String[]{"doubao-lite", "doubao-pro"}
        ));
        
        // 千问 API
        modelProviders.put("qianwen", new ModelProvider(
                "千问 API",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation",
                new String[]{"qwen-turbo", "qwen-plus", "qwen-max"}
        ));
        
        // Ollama API
        modelProviders.put("ollama", new ModelProvider(
                "Ollama API",
                "http://localhost:11434",
                new String[]{"llama2", "mistral", "gemma"}
        ));
    }
    
    /**
     * 获取API适配器
     */
    public LlmApiAdapter getApiAdapter() {
        return apiAdapter;
    }
    
    /**
     * 获取所有模型提供商
     */
    public Map<String, ModelProvider> getModelProviders() {
        return modelProviders;
    }
    
    /**
     * 根据URL获取对应的模型提供商
     */
    public ModelProvider getProviderByUrl(String url) {
        LlmApiAdapter.ApiType apiType = apiAdapter.detectApiType(url);
        
        switch (apiType) {
            case LOCAL:
                return modelProviders.get(AppConstants.ApiUrl.LOCAL);
            case DEEPSEEK:
                return modelProviders.get("deepseek");
            case MOONSHOT:
                return modelProviders.get("moonshot");
            case DOUBAO:
                return modelProviders.get("doubao");
            case QIANWEN:
                return modelProviders.get("qianwen");
            case OLLAMA:
                return modelProviders.get("ollama");
            case OPENAI:
            default:
                return modelProviders.get("openai");
        }
    }
    
    /**
     * 调用大模型API
     */
    public void callModel(String apiUrl, String apiKey, String model, String prompt, LlmApiAdapter.ApiCallback callback) {
        // 如果是本地模型，更新模型列表
        if (apiUrl.equalsIgnoreCase(AppConstants.ApiUrl.LOCAL)) {
            updateLocalModelList();
        }
        apiAdapter.callLlmApi(apiUrl, apiKey, model, prompt, callback);
    }
    
    /**
     * 同步调用大模型API
     */
    public String callModelSync(String apiUrl, String apiKey, String model, String prompt) {
        // 如果是本地模型，更新模型列表
        if (apiUrl.equalsIgnoreCase(AppConstants.ApiUrl.LOCAL)) {
            updateLocalModelList();
        }
        return apiAdapter.callLlmApiSync(apiUrl, apiKey, model, prompt);
    }
    
    /**
     * 获取指定提供商支持的模型列表
     */
    public String[] getSupportedModels(String providerKey) {
        ModelProvider provider = modelProviders.get(providerKey);
        if (provider != null) {
            return provider.getSupportedModels();
        }
        return new String[]{"未知模型"};
    }
    
    /**
     * 根据API URL获取支持的模型列表
     */
    public String[] getSupportedModelsByUrl(String apiUrl) {
        // 如果是本地模型，更新模型列表
        if (apiUrl.equalsIgnoreCase(AppConstants.ApiUrl.LOCAL)) {
            updateLocalModelList();
        }
        
        ModelProvider provider = getProviderByUrl(apiUrl);
        if (provider != null) {
            return provider.getSupportedModels();
        }
        return new String[]{"未知模型"};
    }
    
    /**
     * 更新本地模型列表
     */
    private void updateLocalModelList() {
        try {
            // 获取本地模型列表
            LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(context);
            String[] localModels = localAdapter.listAvailableModels();
            
            // 更新本地模型提供商
            modelProviders.put(AppConstants.ApiUrl.LOCAL, new ModelProvider(
                    context.getString(R.string.api_url_local) + "模型",
                    AppConstants.ApiUrl.LOCAL,
                    localModels
            ));
            
            LogManager.logD(TAG, "更新本地模型列表，找到" + localModels.length + "个模型");
        } catch (Exception e) {
            LogManager.logE(TAG, "更新本地模型列表失败", e);
        }
    }
}
