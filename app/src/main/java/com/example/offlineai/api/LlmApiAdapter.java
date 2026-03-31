package com.example.offlineai.api;

import android.content.Context;
import android.util.Log;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.example.offlineai.LogManager;
import com.example.offlineai.AppConstants;
import com.example.offlineai.BackgroundTaskManager;
import com.example.offlineai.ConfigManager;
import com.example.offlineai.ipc.InferenceClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    
    // Task ID for log routing - set before calling callLlmApi
    private volatile String currentTaskId = null;
    
    // Endpoint cache: baseUrl -> successful full endpoint URL
    private static final Map<String, String> endpointCache = new ConcurrentHashMap<>();
    
    /**
     * 回调接口定义
     */
    public interface ApiCallback {
        void onSuccess(String response);
        void onStreamingData(String chunk);
        void onError(String errorMessage);
    }
    
    /**
     * Model capability: determines endpoint routing and response handling.
     * Callers set this based on user selection / model metadata, not hardcoded names.
     */
    public enum ModelCapability {
        TEXT_GENERATION,   // Standard LLM chat (streaming)
        IMAGE_GENERATION   // Image generation (synchronous, returns image URL)
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
        ZHIPU,       // 智谱 API
        OLLAMA,      // Ollama API
        MIMO,        // 小米 MiMo API (uses api-key header)
        MINIMAX,     // MiniMax API
        LOCAL        // 本地模型
    }
    
    public LlmApiAdapter(Context context) {
        this.context = context;
        this.requestQueue = Volley.newRequestQueue(context);
        this.streamingClient = new StreamingApiClient(context);
    }
    
    /**
     * Set the task ID for log routing.
     * Must be called before callLlmApi() for logs to be properly captured.
     */
    public void setTaskId(String taskId) {
        this.currentTaskId = taskId;
        LogManager.logD(TAG, "[TASK] Set adapter taskId: " + taskId);
    }
    
    /**
     * Get the current task ID.
     */
    public String getTaskId() {
        return currentTaskId;
    }
    
    /**
     * Heuristic to detect image generation models by name.
     * Used as fallback when caller does not specify ModelCapability.
     */
    private static boolean looksLikeImageModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("image") || m.contains("dall-e") || m.contains("wanx")
            || m.contains("flux") || m.contains("stable-diffusion") || m.contains("cogview");
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
        } else if (apiUrl.contains("bigmodel")) {
            return ApiType.ZHIPU;
        } else if (apiUrl.contains("xiaomimimo")) {
            return ApiType.MIMO;
        } else if (apiUrl.contains("minimax")) {
            return ApiType.MINIMAX;
        } else {
            return ApiType.OPENAI;
        }
    }
    
    
    /**
     * 统一的API调用入口 - 新版本，接受独立的system和user prompt
     * 根据API类型自动选择适当的实现
     * 所有API调用都使用统一的流式处理方式
     */
    public void callLlmApi(String apiUrl, String apiKey, String model, String systemPrompt, String userPrompt, java.util.List<String> imagePaths, java.util.List<String> audioPaths, ApiCallback callback) {
        // Detect capability: caller can use the overload with explicit ModelCapability,
        // otherwise fall back to heuristic based on model name.
        ModelCapability capability = looksLikeImageModel(model)
                ? ModelCapability.IMAGE_GENERATION : ModelCapability.TEXT_GENERATION;
        callLlmApi(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths, capability, callback);
    }

    /**
     * Unified API entry point with explicit model capability.
     * Callers who know the model type should use this overload.
     */
    public void callLlmApi(String apiUrl, String apiKey, String model, String systemPrompt, String userPrompt,
                            java.util.List<String> imagePaths, java.util.List<String> audioPaths,
                            ModelCapability capability, ApiCallback callback) {
        if (capability == ModelCapability.IMAGE_GENERATION) {
            LogManager.logI(TAG, "[IMAGE_GEN] Detected image generation model: " + model);
            handleImageGeneration(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, callback);
            return;
        }
        
        // For local model, combine prompts (LocalLLMMNNHandler expects single prompt)
        // For online API, pass separately to avoid incorrect \n\n splitting
        ApiType apiType = detectApiType(apiUrl);
        
        if (apiType == ApiType.LOCAL) {
            // Local model: combine system and user prompt
            String combinedPrompt = "";
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                combinedPrompt = systemPrompt + "\n\n";
            }
            if (userPrompt != null) {
                combinedPrompt += userPrompt;
            }
            callLlmApi(apiUrl, apiKey, model, combinedPrompt, imagePaths, audioPaths, callback);
            return;
        }
        
        // Online API: use new streamRequest signature with separate prompts
        LogManager.logD(TAG, "检测到API类型: " + apiType.name());
        LogManager.logD(TAG, "[STREAM] onStart - source=api, model=" + model + ", thread=" + Thread.currentThread().getName());
        
        // MiniMax Chat Completion API does NOT support multimodal image input
        // MiniMax's OpenAI-compatible API only supports text chat
        final List<String> finalImagePaths;
        if (apiType == ApiType.MINIMAX && imagePaths != null && !imagePaths.isEmpty()) {
            LogManager.logW(TAG, "[MINIMAX] MiniMax Chat API does not support image input, clearing " + imagePaths.size() + " image(s)");
            LogManager.logW(TAG, "[MINIMAX] If you need image understanding, please use MiniMax-VL-01 model or Hailuo AI with vision capabilities");
            finalImagePaths = null;
        } else {
            finalImagePaths = imagePaths;
        }
        
        try {
            // Check endpoint cache first
            String cachedEndpoint = endpointCache.get(apiUrl);
            if (cachedEndpoint != null) {
                LogManager.logD(TAG, "Using cached endpoint: " + cachedEndpoint);
                makeStreamingRequestWithSeparatePrompts(cachedEndpoint, apiKey, model, systemPrompt, userPrompt, finalImagePaths, audioPaths, apiType, callback, null);
                return;
            }
            
            // For DOUBAO, use vendor endpoint directly (skip standard endpoint)
            if (apiType == ApiType.DOUBAO) {
                String vendorEndpoint = getFullApiUrl(apiUrl, apiType);
                LogManager.logD(TAG, "Using DOUBAO vendor endpoint directly: " + vendorEndpoint);
                
                makeStreamingRequestWithSeparatePrompts(vendorEndpoint, apiKey, model, systemPrompt, userPrompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        endpointCache.put(apiUrl, vendorEndpoint);
                        LogManager.logI(TAG, "DOUBAO endpoint succeeded, cached: " + vendorEndpoint);
                        callback.onSuccess(response);
                    }
                    
                    @Override
                    public void onStreamingData(String chunk) {
                        callback.onStreamingData(chunk);
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        callback.onError(errorMessage);
                    }
                }, null);
                return;
            }
            
            // Try OpenAI standard endpoint first for other APIs
            String standardEndpoint = getStandardEndpoint(apiUrl);
            LogManager.logD(TAG, "Trying standard endpoint: " + standardEndpoint);
            
            makeStreamingRequestWithSeparatePrompts(standardEndpoint, apiKey, model, systemPrompt, userPrompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                @Override
                public void onSuccess(String response) {
                    endpointCache.put(apiUrl, standardEndpoint);
                    LogManager.logI(TAG, "Standard endpoint succeeded, cached: " + standardEndpoint);
                    callback.onSuccess(response);
                }
                
                @Override
                public void onStreamingData(String chunk) {
                    callback.onStreamingData(chunk);
                }
                
                @Override
                public void onError(String errorMessage) {
                    callback.onError(errorMessage);
                }
            }, (errorMessage, statusCode) -> {
                if (statusCode == 404 || statusCode == 400) {
                    LogManager.logW(TAG, "Standard endpoint failed with " + statusCode + ", trying vendor-specific endpoint");
                    
                    String vendorEndpoint = getFullApiUrl(apiUrl, apiType);
                    LogManager.logD(TAG, "Trying vendor endpoint: " + vendorEndpoint);
                    
                    makeStreamingRequestWithSeparatePrompts(vendorEndpoint, apiKey, model, systemPrompt, userPrompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            endpointCache.put(apiUrl, vendorEndpoint);
                            LogManager.logI(TAG, "Vendor endpoint succeeded, cached: " + vendorEndpoint);
                            callback.onSuccess(response);
                        }
                        
                        @Override
                        public void onStreamingData(String chunk) {
                            callback.onStreamingData(chunk);
                        }
                        
                        @Override
                        public void onError(String errorMessage) {
                            callback.onError(errorMessage);
                        }
                    }, null);
                } else {
                    LogManager.logE(TAG, "Request failed with non-retryable error: " + statusCode);
                    callback.onError(errorMessage);
                }
            });
        } catch (Exception e) {
            LogManager.logE(TAG, "调用API时发生异常: " + e.getMessage(), e);
            callback.onError("调用API时发生异常: " + e.getMessage());
        }
    }
    
    /**
     * 统一的API调用入口 - 旧版本，接受单个prompt（兼容性）
     * 根据API类型自动选择适当的实现
     * 所有API调用都使用统一的流式处理方式
     */
    public void callLlmApi(String apiUrl, String apiKey, String model, String prompt, java.util.List<String> imagePaths, java.util.List<String> audioPaths, ApiCallback callback) {
        ApiType apiType = detectApiType(apiUrl);
        LogManager.logD(TAG, "检测到API类型: " + apiType.name());
        // [STREAM] onStart at unified entry (English log)
        LogManager.logD(TAG, "[STREAM] onStart - source=" + (apiType == ApiType.LOCAL ? "local" : "api")
                + ", model=" + model
                + ", thread=" + Thread.currentThread().getName());
        
        try {
            // MiniMax Chat Completion API does NOT support multimodal image input
            final List<String> finalImagePaths;
            if (apiType == ApiType.MINIMAX && imagePaths != null && !imagePaths.isEmpty()) {
                LogManager.logW(TAG, "[MINIMAX] MiniMax Chat API does not support image input, clearing " + imagePaths.size() + " image(s)");
                LogManager.logW(TAG, "[MINIMAX] If you need image understanding, please use MiniMax-VL-01 model or Hailuo AI with vision capabilities");
                finalImagePaths = null;
            } else {
                finalImagePaths = imagePaths;
            }
            
            // 如果是本地模型，使用本地适配器
            if (apiType == ApiType.LOCAL) {
                LogManager.logD(TAG, "使用本地模型(多进程): " + model);
                // Push latest runtime configuration to inference process before each call
                com.example.offlineai.RuntimeConfigUtil.pushToInference(context);
                // [STREAM] Use proxy callback to inject streaming logs for local model via IPC
                InferenceClient client = InferenceClient.getInstance(context.getApplicationContext());
                ApiCallback proxyCb = new ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        int len = response != null ? response.length() : 0;
                        LogManager.logD(TAG, "[STREAM] onComplete - source=local, len=" + len + ", thread=" + Thread.currentThread().getName());
                        callback.onSuccess(response);
                    }
                    
                    @Override
                    public void onStreamingData(String chunk) {
                        // CRITICAL: Do NOT write to buffer here!
                        // Buffer writes are handled by RagQueryManager.emitStreamingChunkFromManager()
                        // which is called via callback.onStreamingData() -> RagQueryManager.onStreamingData()
                        // Writing here would cause duplicate entries (was causing 3x duplication)
                        callback.onStreamingData(chunk);
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        LogManager.logD(TAG, "[STREAM] onError - source=local, msg=" + errorMessage + ", thread=" + Thread.currentThread().getName());
                        callback.onError(errorMessage);
                    }
                };
                client.runLlmTask(model, prompt, finalImagePaths, audioPaths, proxyCb);
                return;
            }
            
            // Check endpoint cache first
            String cachedEndpoint = endpointCache.get(apiUrl);
            if (cachedEndpoint != null) {
                LogManager.logD(TAG, "Using cached endpoint: " + cachedEndpoint);
                makeStreamingRequest(cachedEndpoint, apiKey, model, prompt, finalImagePaths, audioPaths, apiType, callback);
                return;
            }
            
            // For DOUBAO, use vendor endpoint directly (skip standard endpoint)
            if (apiType == ApiType.DOUBAO) {
                String vendorEndpoint = getFullApiUrl(apiUrl, apiType);
                LogManager.logD(TAG, "Using DOUBAO vendor endpoint directly: " + vendorEndpoint);
                
                makeStreamingRequest(vendorEndpoint, apiKey, model, prompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        endpointCache.put(apiUrl, vendorEndpoint);
                        LogManager.logI(TAG, "DOUBAO endpoint succeeded, cached: " + vendorEndpoint);
                        callback.onSuccess(response);
                    }
                    
                    @Override
                    public void onStreamingData(String chunk) {
                        callback.onStreamingData(chunk);
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        callback.onError(errorMessage);
                    }
                }, null);
                return;
            }
            
            // Try OpenAI standard endpoint first for other APIs
            String standardEndpoint = getStandardEndpoint(apiUrl);
            LogManager.logD(TAG, "Trying standard endpoint: " + standardEndpoint);
            
            AtomicBoolean retryWithVendor = new AtomicBoolean(false);
            
            makeStreamingRequest(standardEndpoint, apiKey, model, prompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                @Override
                public void onSuccess(String response) {
                    // Standard endpoint works, cache it
                    endpointCache.put(apiUrl, standardEndpoint);
                    LogManager.logI(TAG, "Standard endpoint succeeded, cached: " + standardEndpoint);
                    callback.onSuccess(response);
                }
                
                @Override
                public void onStreamingData(String chunk) {
                    callback.onStreamingData(chunk);
                }
                
                @Override
                public void onError(String errorMessage) {
                    callback.onError(errorMessage);
                }
            }, (errorMessage, statusCode) -> {
                // Check if we should retry with vendor-specific endpoint
                if (statusCode == 404 || statusCode == 400) {
                    LogManager.logW(TAG, "Standard endpoint failed with " + statusCode + ", trying vendor-specific endpoint");
                    
                    // Try vendor-specific endpoint
                    String vendorEndpoint = getFullApiUrl(apiUrl, apiType);
                    LogManager.logD(TAG, "Trying vendor endpoint: " + vendorEndpoint);
                    
                    makeStreamingRequest(vendorEndpoint, apiKey, model, prompt, finalImagePaths, audioPaths, apiType, new ApiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            // Vendor endpoint works, cache it
                            endpointCache.put(apiUrl, vendorEndpoint);
                            LogManager.logI(TAG, "Vendor endpoint succeeded, cached: " + vendorEndpoint);
                            callback.onSuccess(response);
                        }
                        
                        @Override
                        public void onStreamingData(String chunk) {
                            callback.onStreamingData(chunk);
                        }
                        
                        @Override
                        public void onError(String errorMessage) {
                            callback.onError(errorMessage);
                        }
                    }, null); // No more retry
                } else {
                    // Other errors (500, network, etc.), don't retry
                    LogManager.logE(TAG, "Request failed with non-retryable error: " + statusCode);
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
        // 用户填写的API地址（包括版本号v1/v2/v3/v4等）保持原样，app只添加标准路径
        switch (apiType) {
            case OLLAMA:
                // Ollama API 端点（特殊格式）
                if (!url.contains("/api/generate")) {
                    url += "/api/generate";
                }
                break;
                
            case DEEPSEEK:
            case MOONSHOT:
            case DOUBAO:
            case QIANWEN:
            case ZHIPU:
            case MINIMAX:
            case OPENAI:
            default:
                // 标准 OpenAI 兼容格式：用户地址 + /chat/completions
                // 用户可以自己控制版本号（如 /v1、/v4 等）
                if (!url.contains("/chat/completions")) {
                    url += "/chat/completions";
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
            case ZHIPU:
            case MIMO:
            case MINIMAX:
                // 这些API使用messages数组（智谱API、MiniMax兼容OpenAI格式）
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
                
                InferenceClient client = InferenceClient.getInstance(context.getApplicationContext());
                client.runLlmTask(model, prompt, null, null, new ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        int len = response != null ? response.length() : 0;
                        LogManager.logD(TAG, "[STREAM][SYNC] onComplete (local) - len=" + len + ", thread=" + Thread.currentThread().getName());
                        result.append(response);
                        latch.countDown();
                    }
                    
                    @Override
                    public void onStreamingData(String chunk) {
                        int size = chunk != null ? chunk.length() : 0;
                        String preview = (chunk != null) ? chunk.substring(0, Math.min(40, chunk.length())).replace("\n", "\\n") : "";
                        LogManager.logD(TAG, "[STREAM][SYNC] onToken (local) - size=" + size + (size > 0 ? (", preview=\"" + preview + "\"") : "") + ", thread=" + Thread.currentThread().getName());
                        result.append(chunk);
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        LogManager.logD(TAG, "[STREAM][SYNC] onError (local) - msg=" + errorMessage + ", thread=" + Thread.currentThread().getName());
                        error.append(errorMessage);
                        latch.countDown();
                    }
                });
                
                long startMs = System.currentTimeMillis();
                LogManager.logD(TAG, "[STREAM][SYNC] waiting - source=local, timeout=60s, thread=" + Thread.currentThread().getName());
                boolean completed = latch.await(60, TimeUnit.SECONDS);
                long elapsed = System.currentTimeMillis() - startMs;
                LogManager.logD(TAG, "[STREAM][SYNC] done waiting - source=local, completed=" + completed + ", elapsedMs=" + elapsed + ", thread=" + Thread.currentThread().getName());
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
        
        callLlmApi(apiUrl, apiKey, model, prompt, null, null, new ApiCallback() {
            @Override
            public void onSuccess(String response) {
                int len = response != null ? response.length() : 0;
                LogManager.logD(TAG, "[STREAM][SYNC] onComplete (api) - len=" + len + ", thread=" + Thread.currentThread().getName());
                result.append(response);
                latch.countDown();
            }
            
            @Override
            public void onStreamingData(String chunk) {
                int size = chunk != null ? chunk.length() : 0;
                String preview = (chunk != null) ? chunk.substring(0, Math.min(40, chunk.length())).replace("\n", "\\n") : "";
                LogManager.logD(TAG, "[STREAM][SYNC] onToken (api) - size=" + size + (size > 0 ? (", preview=\"" + preview + "\"") : "") + ", thread=" + Thread.currentThread().getName());
                result.append(chunk);
            }
            
            @Override
            public void onError(String errorMessage) {
                LogManager.logD(TAG, "[STREAM][SYNC] onError (api) - msg=" + errorMessage + ", thread=" + Thread.currentThread().getName());
                error.append(errorMessage);
                latch.countDown();
            }
        });
        
        try {
            // 等待响应，最多60秒
            long startMs = System.currentTimeMillis();
            LogManager.logD(TAG, "[STREAM][SYNC] waiting - source=api, timeout=60s, thread=" + Thread.currentThread().getName());
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startMs;
            LogManager.logD(TAG, "[STREAM][SYNC] done waiting - source=api, completed=" + completed + ", elapsedMs=" + elapsed + ", thread=" + Thread.currentThread().getName());
            if (!completed) {
                return "API调用超时";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogManager.logD(TAG, "[STREAM][SYNC] wait interrupted - thread=" + Thread.currentThread().getName() + ", msg=" + e.getMessage());
            return "API调用被中断: " + e.getMessage();
        }
        
        if (error.length() > 0) {
            return "API调用错误: " + error.toString();
        }
        
        return result.toString();
    }
    
    /**
     * Get OpenAI standard endpoint (always try this first)
     * 用户填写的API地址（包括版本号v1/v2/v3/v4等）保持原样，只添加/chat/completions
     */
    private String getStandardEndpoint(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!url.contains("/chat/completions")) {
            url += "/chat/completions";
        }
        return url;
    }
    
    /**
     * Make streaming request (overload without error callback)
     */
    private void makeStreamingRequest(String fullApiUrl, String apiKey, String model, String prompt,
                                      java.util.List<String> imagePaths, java.util.List<String> audioPaths,
                                      ApiType apiType, ApiCallback callback) {
        makeStreamingRequest(fullApiUrl, apiKey, model, prompt, imagePaths, audioPaths, apiType, callback, null);
    }
    
    /**
     * Make streaming request with optional error callback for retry logic
     */
    private void makeStreamingRequest(String fullApiUrl, String apiKey, String model, String prompt,
                                      java.util.List<String> imagePaths, java.util.List<String> audioPaths,
                                      ApiType apiType, ApiCallback callback, ErrorCallbackWithStatus errorCallbackWithStatus) {
        boolean noThinking = ConfigManager.getNoThinking(context);
        boolean thinkingEnabled = !noThinking;
        
        LogManager.logD(TAG, "[THINKING] makeStreamingRequest: noThinking=" + noThinking + ", thinkingEnabled=" + thinkingEnabled + ", apiType=" + apiType.name());
        
        streamingClient.streamRequest(fullApiUrl, apiKey, model, prompt, imagePaths, audioPaths, apiType, thinkingEnabled, new StreamingApiClient.StreamingCallback() {
            @Override
            public void onToken(String token) {
                callback.onStreamingData(token);
            }
            
            @Override
            public void onComplete(String fullResponse) {
                int len = fullResponse != null ? fullResponse.length() : 0;
                LogManager.logD(TAG, "[STREAM] onComplete - source=api, apiType=" + apiType.name() + ", len=" + len + ", thread=" + Thread.currentThread().getName());
                callback.onSuccess(fullResponse);
            }
            
            @Override
            public void onError(String errorMessage) {
                LogManager.logD(TAG, "[STREAM] onError - source=api, apiType=" + apiType.name() + ", msg=" + errorMessage + ", thread=" + Thread.currentThread().getName());
                callback.onError(errorMessage);
            }
            
            @Override
            public void onError(String errorMessage, int statusCode) {
                LogManager.logD(TAG, "[STREAM] onError - source=api, apiType=" + apiType.name() + ", msg=" + errorMessage + ", statusCode=" + statusCode + ", thread=" + Thread.currentThread().getName());
                if (errorCallbackWithStatus != null) {
                    errorCallbackWithStatus.onError(errorMessage, statusCode);
                } else {
                    callback.onError(errorMessage);
                }
            }
        });
    }
    
    /**
     * Make streaming request with separate system and user prompts (NEW)
     */
    private void makeStreamingRequestWithSeparatePrompts(String fullApiUrl, String apiKey, String model, 
                                                         String systemPrompt, String userPrompt,
                                                         java.util.List<String> imagePaths, java.util.List<String> audioPaths,
                                                         ApiType apiType, ApiCallback callback, ErrorCallbackWithStatus errorCallbackWithStatus) {
        boolean noThinking = ConfigManager.getNoThinking(context);
        boolean thinkingEnabled = !noThinking;
        
        LogManager.logD(TAG, "[THINKING] makeStreamingRequestWithSeparatePrompts: noThinking=" + noThinking + ", thinkingEnabled=" + thinkingEnabled + ", apiType=" + apiType.name());
        
        streamingClient.streamRequest(fullApiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths, apiType, thinkingEnabled, new StreamingApiClient.StreamingCallback() {
            @Override
            public void onToken(String token) {
                callback.onStreamingData(token);
            }
            
            @Override
            public void onComplete(String fullResponse) {
                int len = fullResponse != null ? fullResponse.length() : 0;
                LogManager.logD(TAG, "[STREAM] onComplete - source=api, apiType=" + apiType.name() + ", len=" + len + ", thread=" + Thread.currentThread().getName());
                callback.onSuccess(fullResponse);
            }
            
            @Override
            public void onError(String errorMessage) {
                LogManager.logD(TAG, "[STREAM] onError - source=api, apiType=" + apiType.name() + ", msg=" + errorMessage + ", thread=" + Thread.currentThread().getName());
                callback.onError(errorMessage);
            }
            
            @Override
            public void onError(String errorMessage, int statusCode) {
                LogManager.logD(TAG, "[STREAM] onError - source=api, apiType=" + apiType.name() + ", msg=" + errorMessage + ", statusCode=" + statusCode + ", thread=" + Thread.currentThread().getName());
                if (errorCallbackWithStatus != null) {
                    errorCallbackWithStatus.onError(errorMessage, statusCode);
                } else {
                    callback.onError(errorMessage);
                }
            }
        });
    }
    
    /**
     * Handle image generation API call.
     * Reuses StreamingApiClient for HTTP + auth, ApiUtils for Base64 + image saving.
     * Supports Qianwen and OpenAI-compatible providers.
     * For local Diffusion models, routes to local inference path.
     */
    private void handleImageGeneration(String apiUrl, String apiKey, String model,
                                       String systemPrompt, String userPrompt,
                                       java.util.List<String> imagePaths, ApiCallback callback) {
        try {
            ApiType apiType = detectApiType(apiUrl);
            
            // CRITICAL: Local Diffusion models should use local inference, not HTTP request
            if (apiType == ApiType.LOCAL) {
                LogManager.logI(TAG, "[IMAGE_GEN] Detected local Diffusion model: " + model);
                LogManager.logI(TAG, "[IMAGE_GEN] Routing to local inference path");
                
                // Combine prompts
                String prompt = userPrompt;
                if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                    prompt = systemPrompt + "\n" + userPrompt;
                }
                
                // Push latest runtime configuration to inference process
                com.example.offlineai.RuntimeConfigUtil.pushToInference(context);
                
                // Use InferenceClient for local Diffusion inference
                InferenceClient client = InferenceClient.getInstance(context.getApplicationContext());
                client.runLlmTask(model, prompt, imagePaths, null, callback);
                return;
            }
            
            String baseUrl = ApiUtils.extractBaseUrl(apiUrl);
            final boolean isQianwenFormat = (apiType == ApiType.QIANWEN);

            // Determine endpoint
            String endpoint = isQianwenFormat
                    ? baseUrl + "/api/v1/services/aigc/multimodal-generation/generation"
                    : baseUrl + "/v1/images/generations";

            LogManager.logI(TAG, "[IMAGE_GEN] Detected image generation model: " + model);
            LogManager.logI(TAG, "[IMAGE_GEN] endpoint=" + endpoint + ", format=" + (isQianwenFormat ? "Qianwen" : "OpenAI"));

            // Combine prompts
            String prompt = userPrompt;
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                prompt = systemPrompt + "\n" + userPrompt;
            }
            final String finalPrompt = prompt;

            // Get image size from ConfigManager (reuse Diffusion settings)
            int imageWidth = ConfigManager.getDiffusionImageWidth(context);
            int imageHeight = ConfigManager.getDiffusionImageHeight(context);
            // Use 1024 as default if not configured
            if (imageWidth == 0) imageWidth = 1024;
            if (imageHeight == 0) imageHeight = 1024;
            LogManager.logI(TAG, "[IMAGE_GEN] Image size: " + imageWidth + "x" + imageHeight);

            // Build request body
            JSONObject requestBody = buildImageGenRequestBody(model, finalPrompt, imagePaths, isQianwenFormat, imageWidth, imageHeight);
            LogManager.logI(TAG, "[IMAGE_GEN] Request size: " + requestBody.toString().length() + " bytes");

            // Execute on background thread via StreamingApiClient.imageRequest
            final String origApiUrl = apiUrl;
            new Thread(() -> {
                streamingClient.imageRequest(endpoint, apiKey, origApiUrl, requestBody,
                    new StreamingApiClient.StreamingCallback() {
                        @Override
                        public void onToken(String token) { }

                        @Override
                        public void onComplete(String responseBody) {
                            try {
                                String imageUrl = parseImageUrl(responseBody, isQianwenFormat);
                                LogManager.logI(TAG, "[IMAGE_GEN] Image URL extracted: " + imageUrl);

                                // Download and save using shared OkHttp client + ApiUtils
                                String savedPath = ApiUtils.downloadAndSaveImage(
                                        context, imageUrl, streamingClient.getClient());

                                if (savedPath == null) {
                                    callback.onError("Failed to download/save generated image");
                                    return;
                                }

                                // Return [IMAGE:path] so RagQueryManager can detect it
                                String resultMessage = "[IMAGE:" + savedPath + "]";
                                // CRITICAL: onStreamingData first to populate fullResponseAccumulator
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                    callback.onStreamingData(resultMessage);
                                    callback.onSuccess(resultMessage);
                                });
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[IMAGE_GEN] Parse/download error: " + e.getMessage(), e);
                                callback.onError("Image generation error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            callback.onError(errorMessage);
                        }

                        @Override
                        public void onError(String errorMessage, int statusCode) {
                            callback.onError(errorMessage);
                        }
                    });
            }).start();

        } catch (Exception e) {
            LogManager.logE(TAG, "[IMAGE_GEN] Failed to build request: " + e.getMessage(), e);
            callback.onError("Failed to build image generation request: " + e.getMessage());
        }
    }

    /**
     * Build image generation request body for Qianwen or OpenAI format.
     * Qianwen: text and image are mutually exclusive in content array.
     * OpenAI: uses prompt field directly.
     */
    private JSONObject buildImageGenRequestBody(String model, String prompt,
                                                 java.util.List<String> imagePaths,
                                                 boolean isQianwenFormat,
                                                 int imageWidth, int imageHeight) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);

        if (isQianwenFormat) {
            JSONObject input = new JSONObject();
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");

            JSONArray content = new JSONArray();
            boolean hasInputImage = (imagePaths != null && !imagePaths.isEmpty());

            // Text content is ALWAYS required by Qianwen image API
            JSONObject textObj = new JSONObject();
            textObj.put("text", prompt);
            content.put(textObj);

            if (hasInputImage) {
                // Image-to-image: add all input images alongside text
                for (int i = 0; i < imagePaths.size(); i++) {
                    String base64 = ApiUtils.encodeImageToBase64(imagePaths.get(i));
                    if (base64 != null) {
                        JSONObject imgObj = new JSONObject();
                        imgObj.put("image", "data:image/jpeg;base64," + base64);
                        content.put(imgObj);
                        LogManager.logI(TAG, "[IMAGE_GEN] Image-to-image mode, added image " + (i + 1) + "/" + imagePaths.size());
                    }
                }
            } else {
                LogManager.logI(TAG, "[IMAGE_GEN] Text-to-image mode");
            }

            message.put("content", content);
            messages.put(message);
            input.put("messages", messages);
            body.put("input", input);

            JSONObject parameters = new JSONObject();
            // Qianwen format: "width*height"
            parameters.put("size", imageWidth + "*" + imageHeight);
            body.put("parameters", parameters);
            LogManager.logI(TAG, "[IMAGE_GEN] Qianwen size parameter: " + imageWidth + "*" + imageHeight);
        } else {
            // OpenAI format: "widthxheight"
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", imageWidth + "x" + imageHeight);
            LogManager.logI(TAG, "[IMAGE_GEN] OpenAI size parameter: " + imageWidth + "x" + imageHeight);
        }
        return body;
    }

    /**
     * Parse image URL from API response (Qianwen or OpenAI format).
     */
    private String parseImageUrl(String responseBody, boolean isQianwenFormat) throws JSONException {
        JSONObject json = new JSONObject(responseBody);
        if (isQianwenFormat) {
            // {"output":{"choices":[{"message":{"content":[{"image":"..."}]}}]}}
            return json.getJSONObject("output")
                       .getJSONArray("choices").getJSONObject(0)
                       .getJSONObject("message")
                       .getJSONArray("content").getJSONObject(0)
                       .getString("image");
        } else {
            // {"data":[{"url":"..."}]}
            return json.getJSONArray("data").getJSONObject(0).getString("url");
        }
    }
    
    /**
     * Error callback with status code for retry logic
     */
    @FunctionalInterface
    private interface ErrorCallbackWithStatus {
        void onError(String errorMessage, int statusCode);
    }
}

