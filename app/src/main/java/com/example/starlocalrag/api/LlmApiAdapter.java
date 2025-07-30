package com.example.starlocalrag.api;

import android.content.Context;
import android.util.Log;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.AppConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 统一的大模型API适配器
 * 将不同供应商的API调用统一到OpenAI的接口模式
 * 支持流式响应
 */
public class LlmApiAdapter {
    private static final String TAG = "LlmApiAdapter";
    
    private final Context context;
    private final RequestQueue requestQueue;
    private final StreamingApiClient streamingClient;
    
    /**
     * 回调接口定义
     */
    public interface ApiCallback {
        void onSuccess(String response);
        void onStreamingData(String chunk);
        void onError(String errorMessage);
    }
    
    /**
     * API类型枚举
     */
    public enum ApiType {
        OPENAI,      // OpenAI兼容API
        DEEPSEEK,    // DeepSeek API
        MOONSHOT,    // Moonshot API
        DOUBAO,      // 豆包 API
        QIANWEN,     // 千问 API
        OLLAMA,      // Ollama API
        LOCAL        // 本地模型
    }
    
    public LlmApiAdapter(Context context) {
        this.context = context;
        this.requestQueue = Volley.newRequestQueue(context);
        this.streamingClient = new StreamingApiClient(context);
    }
    
    /**
     * 根据API URL自动检测API类型
     */
    public ApiType detectApiType(String apiUrl) {
        if (apiUrl.equalsIgnoreCase(AppConstants.ApiUrl.LOCAL)) {
            return ApiType.LOCAL;
        } else if (apiUrl.contains("ollama") || apiUrl.contains("localhost")) {
            return ApiType.OLLAMA;
        } else if (apiUrl.contains("deepseek")) {
            return ApiType.DEEPSEEK;
        } else if (apiUrl.contains("moonshot")) {
            return ApiType.MOONSHOT;
        } else if (apiUrl.contains("volces") || apiUrl.contains("ark")) {
            return ApiType.DOUBAO;
        } else if (apiUrl.contains("dashscope") || apiUrl.contains("aliyun")) {
            return ApiType.QIANWEN;
        } else {
            return ApiType.OPENAI;
        }
    }
    
    /**
     * 统一的API调用入口
     * 根据API类型自动选择适当的实现
     * 所有API调用都使用统一的流式处理方式
     */
    public void callLlmApi(String apiUrl, String apiKey, String model, String prompt, ApiCallback callback) {
        ApiType apiType = detectApiType(apiUrl);
        LogManager.logD(TAG, "检测到API类型: " + apiType.name());
        
        try {
            // 如果是本地模型，使用本地适配器
            if (apiType == ApiType.LOCAL) {
                LogManager.logD(TAG, "使用本地模型: " + model);
                LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(context);
                localAdapter.callLocalModel(model, prompt, callback);
                return;
            }
            
            // 创建适合当前API类型的请求体
            JSONObject requestBody = createRequestBody(apiType, model, prompt);
            
            // 补充API端点路径
            String fullApiUrl = getFullApiUrl(apiUrl, apiType);
            LogManager.logD(TAG, "完整API URL: " + fullApiUrl);
            
            // 使用流式客户端发送请求
            streamingClient.streamRequest(fullApiUrl, apiKey, model, prompt, new StreamingApiClient.StreamingCallback() {
                @Override
                public void onToken(String token) {
                    callback.onStreamingData(token);
                }
                
                @Override
                public void onComplete(String fullResponse) {
                    callback.onSuccess(fullResponse);
                }
                
                @Override
                public void onError(String errorMessage) {
                    callback.onError(errorMessage);
                }
            });
            
        } catch (Exception e) {
            LogManager.logE(TAG, "API调用错误", e);
            callback.onError("API调用错误: " + e.getMessage());
        }
    }
    
    /**
     * 获取完整的API URL，包含正确的端点路径
     */
    private String getFullApiUrl(String baseUrl, ApiType apiType) {
        // 如果是本地模型，直接返回
        if (apiType == ApiType.LOCAL) {
            return AppConstants.ApiUrl.LOCAL;
        }
        
        // 移除URL末尾的斜杠（如果有）
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        
        // 根据API类型添加正确的端点路径
        switch (apiType) {
            case DEEPSEEK:
                // DeepSeek API 端点
                if (!url.contains("/v1/chat/completions")) {
                    url += "/v1/chat/completions";
                }
                break;
                
            case MOONSHOT:
                // Moonshot API 端点
                if (!url.contains("/v1/chat/completions")) {
                    url += "/v1/chat/completions";
                }
                break;
                
            case DOUBAO:
                // 豆包 API 端点
                if (!url.contains("/api/completion")) {
                    url += "/api/completion";
                }
                break;
                
            case QIANWEN:
                // 千问 API 端点
                if (!url.contains("/v1/services/aigc/text-generation/generation")) {
                    url += "/v1/services/aigc/text-generation/generation";
                }
                break;
                
            case OLLAMA:
                // Ollama API 端点
                if (!url.contains("/api/generate")) {
                    url += "/api/generate";
                }
                break;
                
            case OPENAI:
            default:
                // OpenAI API 端点
                if (!url.contains("/v1/chat/completions")) {
                    url += "/v1/chat/completions";
                }
                break;
        }
        
        return url;
    }
    
    /**
     * 创建适合当前API类型的请求体
     */
    private JSONObject createRequestBody(ApiType apiType, String model, String prompt) throws JSONException {
        JSONObject requestBody = new JSONObject();
        
        // 如果是本地模型，返回空对象
        if (apiType == ApiType.LOCAL) {
            return requestBody;
        }
        
        // 添加模型名称
        requestBody.put("model", model);
        
        // 根据API类型添加不同的请求参数
        switch (apiType) {
            case LOCAL:
                // 本地模型不需要请求体
                break;
            case OLLAMA:
                // Ollama使用prompt字段
                requestBody.put("prompt", prompt);
                break;
                
            case OPENAI:
            case DEEPSEEK:
            case MOONSHOT:
                // 这些API使用messages数组
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "user").put("content", prompt));
                requestBody.put("messages", messages);
                break;
                
            case DOUBAO:
            case QIANWEN:
                // 这些API可能有特殊格式
                JSONArray specialMessages = new JSONArray();
                specialMessages.put(new JSONObject().put("role", "user").put("content", prompt));
                requestBody.put("messages", specialMessages);
                // 可能需要额外的参数
                requestBody.put("temperature", 0.7);
                break;
        }
        
        // 启用流式响应
        requestBody.put("stream", true);
        
        return requestBody;
    }
    
    /**
     * 同步调用API（阻塞方式）
     * 用于后台线程中调用
     */
    public String callLlmApiSync(String apiUrl, String apiKey, String model, String prompt) {
        // 如果是本地模型，使用本地适配器的同步调用
        if (detectApiType(apiUrl) == ApiType.LOCAL) {
            LogManager.logD(TAG, "同步调用本地模型: " + model);
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                final StringBuilder result = new StringBuilder();
                final StringBuilder error = new StringBuilder();
                
                LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(context);
                localAdapter.callLocalModel(model, prompt, new ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        result.append(response);
                        latch.countDown();
                    }
                    
                    @Override
                    public void onStreamingData(String chunk) {
                        result.append(chunk);
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        error.append(errorMessage);
                        latch.countDown();
                    }
                });
                
                boolean completed = latch.await(60, TimeUnit.SECONDS);
                if (!completed) {
                    return "本地模型调用超时";
                }
                
                if (error.length() > 0) {
                    return "本地模型调用错误: " + error.toString();
                }
                
                return result.toString();
            } catch (Exception e) {
                LogManager.logE(TAG, "本地模型同步调用错误", e);
                return "本地模型调用错误: " + e.getMessage();
            }
        }
        
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder result = new StringBuilder();
        final StringBuilder error = new StringBuilder();
        
        callLlmApi(apiUrl, apiKey, model, prompt, new ApiCallback() {
            @Override
            public void onSuccess(String response) {
                result.append(response);
                latch.countDown();
            }
            
            @Override
            public void onStreamingData(String chunk) {
                result.append(chunk);
            }
            
            @Override
            public void onError(String errorMessage) {
                error.append(errorMessage);
                latch.countDown();
            }
        });
        
        try {
            // 等待响应，最多60秒
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                return "API调用超时";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "API调用被中断: " + e.getMessage();
        }
        
        if (error.length() > 0) {
            return "API调用错误: " + error.toString();
        }
        
        return result.toString();
    }
}
