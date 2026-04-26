package com.example.offlineai.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.offlineai.LogManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 专门用于处理流式API请求的客户端
 * 使用OkHttp实现流式响应处理
 */
public class StreamingApiClient {
    private static final String TAG = "StreamingApiClient";
    private final Context context;
    private final OkHttpClient client;
    
    /**
     * 流式API回调接口
     */
    public interface StreamingCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(String errorMessage);
        void onError(String errorMessage, int statusCode);
    }
    
    public StreamingApiClient(Context context) {
        this.context = context;
        
        // 创建OkHttp客户端，配置超时
        this.client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)  // 连接超时增加到60秒
            .readTimeout(300, TimeUnit.SECONDS)    // 读取超时增加到5分钟
            .writeTimeout(60, TimeUnit.SECONDS)    // 写入超时增加到60秒
            .build();
    }
    
    /**
     * 发送流式API请求（支持多模态输入）- 旧版本，接受单个prompt（向后兼容）
     * @param apiUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @param prompt 提示内容（可能包含system和user prompt拼接）
     * @param imagePaths 图片路径列表（可选）
     * @param audioPaths 音频路径列表（可选，暂不支持）
     * @param callback 回调接口
     */
    public void streamRequest(String apiUrl, String apiKey, String model, String prompt, 
                             List<String> imagePaths, List<String> audioPaths,
                             StreamingCallback callback) {
        // Legacy method: auto-split prompt by \n\n (may cause issues with Agent prompts)
        // For new code, use the overload with separate systemPrompt and userPrompt
        String systemPrompt = "";
        String userPrompt = prompt;
        
        if (prompt != null && prompt.contains("\n\n")) {
            int firstEmptyLineIndex = prompt.indexOf("\n\n");
            systemPrompt = prompt.substring(0, firstEmptyLineIndex).trim();
            userPrompt = prompt.substring(firstEmptyLineIndex + 2).trim();
        }
        
        // Call new method with separated prompts (no thinking control for legacy)
        streamRequest(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths, null, null, callback);
    }
    
    /**
     * 发送流式API请求（支持thinking控制）- 接受单个prompt，自动分离system和user
     * @param apiUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @param prompt 提示内容（可能包含system和user prompt拼接）
     * @param imagePaths 图片路径列表（可选）
     * @param audioPaths 音频路径列表（可选，暂不支持）
     * @param apiType API类型（用于thinking参数注入）
     * @param thinkingEnabled 是否启用thinking模式（null表示不控制）
     * @param callback 回调接口
     */
    public void streamRequest(String apiUrl, String apiKey, String model, String prompt, 
                             List<String> imagePaths, List<String> audioPaths,
                             LlmApiAdapter.ApiType apiType, Boolean thinkingEnabled,
                             StreamingCallback callback) {
        // Auto-split prompt by \n\n (may cause issues with Agent prompts)
        String systemPrompt = "";
        String userPrompt = prompt;
        
        if (prompt != null && prompt.contains("\n\n")) {
            int firstEmptyLineIndex = prompt.indexOf("\n\n");
            systemPrompt = prompt.substring(0, firstEmptyLineIndex).trim();
            userPrompt = prompt.substring(firstEmptyLineIndex + 2).trim();
        }
        
        // Call new method with separated prompts
        streamRequest(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths, apiType, thinkingEnabled, callback);
    }
    
    /**
     * 发送流式API请求（支持多模态输入）- 新版本，接受独立的system和user prompt
     * @param apiUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @param systemPrompt 系统提示词（可为null或空）
     * @param userPrompt 用户提示词
     * @param imagePaths 图片路径列表（可选）
     * @param audioPaths 音频路径列表（可选，暂不支持）
     * @param apiType API类型（用于thinking参数注入）
     * @param thinkingEnabled 是否启用thinking模式（null表示不控制）
     * @param callback 回调接口
     */
    public void streamRequest(String apiUrl, String apiKey, String model, 
                             String systemPrompt, String userPrompt,
                             List<String> imagePaths, List<String> audioPaths,
                             LlmApiAdapter.ApiType apiType, Boolean thinkingEnabled,
                             StreamingCallback callback) {
        try {
            LogManager.logD(TAG, "准备发送流式请求: " + apiUrl);
            
            // CRITICAL: Do NOT auto-split prompt by \n\n!
            // System prompt may contain multiple paragraphs with \n\n separators.
            // Accept systemPrompt and userPrompt as separate parameters.
            if (systemPrompt == null) {
                systemPrompt = "";
            }
            if (userPrompt == null) {
                userPrompt = "";
            }
            
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            // Agent mode sampling (more conservative to reduce coordinate drift)
            boolean isAgentPrompt = (systemPrompt != null && systemPrompt.contains("You are a GUI agent"))
                    && (userPrompt != null && userPrompt.contains("What is the next action"));
            if (isAgentPrompt) {
                requestBody.put("temperature", 0.2);
                requestBody.put("top_p", 0.9);
                requestBody.put("top_k", 40);
                LogManager.logI(TAG, "[AGENT_SAMPLING] Applied conservative sampling: temperature=0.2, top_p=0.9, top_k=40");
            }
            
            // 创建消息数组
            JSONArray messages = new JSONArray();
            
            // 添加系统提示词（如果存在）
            if (!systemPrompt.trim().isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                LogManager.logI(TAG, "添加系统提示词，长度: " + systemPrompt.length());
            }
            
            // Build user message with multimodal content if images are provided
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            
            // Check if we have images (multimodal input)
            boolean hasImages = (imagePaths != null && !imagePaths.isEmpty());
            
            if (hasImages) {
                // Multimodal format: content is an array of {type, text/image_url}
                JSONArray contentArray = new JSONArray();
                
                // Add text content first
                JSONObject textContent = new JSONObject();
                textContent.put("type", "text");
                textContent.put("text", userPrompt);
                contentArray.put(textContent);
                
                // Add image contents
                for (String imagePath : imagePaths) {
                    try {
                        String base64Image = ApiUtils.encodeImageToBase64(imagePath);
                        if (base64Image != null) {
                            JSONObject imageContent = new JSONObject();
                            imageContent.put("type", "image_url");
                            
                            JSONObject imageUrl = new JSONObject();
                            imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
                            imageContent.put("image_url", imageUrl);
                            
                            contentArray.put(imageContent);
                            LogManager.logI(TAG, "Added image to request: " + imagePath);
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to encode image: " + imagePath, e);
                    }
                }
                
                userMessage.put("content", contentArray);
            } else {
                // Text-only format: content is a simple string
                userMessage.put("content", userPrompt);
            }
            
            messages.put(userMessage);
            
            requestBody.put("messages", messages);
            requestBody.put("stream", true);
            
            // ========== Configure thinking mode based on API gateway ==========
            // The thinking parameter format is decided by the API gateway, not the model.
            // Verified against official docs as of 2026/04. Classification:
            //
            //   Family A: top-level "thinking": {"type": "enabled"|"disabled"}
            //     - DOUBAO   (Volcengine Ark, ark.cn-beijing.volces.com) -> Doubao Seed series
            //     - ZHIPU    (BigModel, open.bigmodel.cn)                -> GLM-4.5 / GLM-Z series
            //     - DEEPSEEK (api.deepseek.com)                          -> deepseek-v4-pro/flash
            //                  Also supports "reasoning_effort": "high"|"max" for effort control.
            //     - MOONSHOT (api.moonshot.ai)                           -> kimi-k2.6 / kimi-k2.5 / kimi-k2-thinking
            //                  Default is enabled; pass disabled to turn off.
            //
            //   Family B: "extra_body": {"enable_thinking": true|false}
            //     - QIANWEN (DashScope, dashscope.aliyuncs.com)          -> Qwen3 series
            //     - MIMO    (Xiaomi MiMo, OpenAI-compatible)             -> MiMo-V2 series
            //     - OPENAI  (OpenAI-compatible, e.g. self-hosted vLLM)   -> Qwen3 / DeepSeek deploys
            //
            //   Family D: no thinking param (model name selects thinking mode, or no thinking at all)
            //     - MINIMAX  -> abab/Text-01 (no think) vs MiniMax-M1 (forced think)
            //     - OLLAMA / LOCAL -> not applicable (handled elsewhere)
            //
            // Note: We do NOT check whether the specific model supports thinking. If the user
            // toggles thinking on for a model that doesn't support it, the gateway may return
            // 400 - that's expected user-visible behavior, not something to silently swallow.
            if (thinkingEnabled != null && apiType != null) {
                // Family A: top-level thinking.type ("enabled" / "disabled")
                if (apiType == LlmApiAdapter.ApiType.DOUBAO
                        || apiType == LlmApiAdapter.ApiType.ZHIPU
                        || apiType == LlmApiAdapter.ApiType.DEEPSEEK
                        || apiType == LlmApiAdapter.ApiType.MOONSHOT) {
                    try {
                        JSONObject thinking = new JSONObject();
                        thinking.put("type", thinkingEnabled ? "enabled" : "disabled");
                        requestBody.put("thinking", thinking);
                        LogManager.logI(TAG, "[THINKING] " + apiType.name()
                                + " top-level thinking set: type=" + (thinkingEnabled ? "enabled" : "disabled"));
                    } catch (JSONException e) {
                        LogManager.logE(TAG, "Failed to set thinking parameter for " + apiType.name(), e);
                    }
                }

                // Family B: extra_body.enable_thinking (boolean)
                else if (apiType == LlmApiAdapter.ApiType.QIANWEN
                        || apiType == LlmApiAdapter.ApiType.MIMO
                        || apiType == LlmApiAdapter.ApiType.OPENAI) {
                    try {
                        JSONObject extraBody = new JSONObject();
                        extraBody.put("enable_thinking", thinkingEnabled);
                        requestBody.put("extra_body", extraBody);
                        LogManager.logI(TAG, "[THINKING] " + apiType.name()
                                + " extra_body set: enable_thinking=" + thinkingEnabled);
                    } catch (JSONException e) {
                        LogManager.logE(TAG, "Failed to set enable_thinking for " + apiType.name(), e);
                    }
                }

                // Family D: no parameter (MINIMAX / OLLAMA / LOCAL)
                else {
                    LogManager.logI(TAG, "[THINKING] " + apiType.name()
                            + " uses model-name-based thinking selection; no parameter sent");
                }
            }
            
            LogManager.logI(TAG, "[DEBUG_REQUEST] Request body size: " + requestBody.toString().length() + " bytes");
            
            // 构建请求 - Use cached auth method if available, default to Bearer
            Request.Builder requestBuilder = new Request.Builder()
                .url(apiUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
            
            String cachedMethod = AuthMethodCache.getCachedMethod(context, apiUrl);
            if (cachedMethod != null) {
                AuthMethodCache.applyAuthHeader(requestBuilder, apiKey, cachedMethod);
            } else {
                // Default to Bearer, will be updated after successful model fetch
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            
            Request request = requestBuilder
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();
                
            // 发送请求
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LogManager.logE(TAG, "请求失败: " + e.getMessage(), e);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onError("请求失败: " + e.getMessage());
                    });
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        int statusCode = response.code();
                        LogManager.logE(TAG, "Request failed, status code: " + statusCode);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onError("Request failed, status code: " + statusCode, statusCode);
                        });
                        return;
                    }
                    
                    ResponseBody body = response.body();
                    if (body == null) {
                        LogManager.logE(TAG, "响应体为空");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onError("响应体为空");
                        });
                        return;
                    }
                    
                    StringBuilder fullResponse = new StringBuilder();
                    StringBuilder thinkingContent = new StringBuilder();
                    boolean[] textHeadSent = {false}; // Track if [TEXT:] head has been sent
                    boolean[] isInThinkingMode = {false}; // Track if we're in thinking phase
                    boolean[] hasLoggedThinkingEntry = {false}; // Track if we've logged thinking entry
                    
                    try {
                        BufferedSource source = body.source();
                        while (!source.exhausted()) {
                            String line = source.readUtf8Line();
                            if (line == null) continue;
                            
                            //LogManager.logD(TAG, "Received line: " + line);
                            
                            if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                                String jsonStr = line.substring(6).trim();
                                try {
                                    JSONObject data = new JSONObject(jsonStr);
                                    JSONArray choices = data.getJSONArray("choices");
                                    JSONObject choice = choices.getJSONObject(0);
                                    JSONObject delta = choice.getJSONObject("delta");
                                    
                                    // Handle reasoning_content (thinking phase)
                                    if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                        String reasoning = delta.getString("reasoning_content");
                                        thinkingContent.append(reasoning);
                                        
                                        // Mark that we entered thinking mode (only log once)
                                        if (!isInThinkingMode[0]) {
                                            isInThinkingMode[0] = true;
                                            if (!hasLoggedThinkingEntry[0]) {
                                                LogManager.logI(TAG, "[THINKING] Entered thinking phase");
                                                hasLoggedThinkingEntry[0] = true;
                                            }
                                        }
                                        
                                        // Send thinking content to UI (will be shown in debug section)
                                        new Handler(Looper.getMainLooper()).post(() -> {
                                            callback.onToken(reasoning);
                                        });
                                    }
                                    
                                    // Handle content (response phase)
                                    if (delta.has("content") && !delta.isNull("content")) {
                                        String content = delta.getString("content");
                                        fullResponse.append(content);
                                        
                                        // If we were in thinking mode, mark exit (don't log yet)
                                        if (isInThinkingMode[0]) {
                                            isInThinkingMode[0] = false;
                                        }
                                        
                                        // 在主线程中回调
                                        new Handler(Looper.getMainLooper()).post(() -> {
                                            // Send [TEXT:] head before first token (for main flow to detect and close <debug>)
                                            if (!textHeadSent[0]) {
                                                callback.onToken("\n[TEXT:]");
                                                textHeadSent[0] = true;
                                            }
                                            callback.onToken(content);
                                        });
                                    }
                                } catch (JSONException e) {
                                    LogManager.logE(TAG, "Failed to parse JSON: " + e.getMessage(), e);
                                }
                            }
                        }
                        
                        // Log final thinking length if we had thinking content
                        if (hasLoggedThinkingEntry[0] && thinkingContent.length() > 0) {
                            LogManager.logI(TAG, "[THINKING] Exited thinking phase, thinking length: " + thinkingContent.length());
                        }
                        
                        // 流结束，回调完整响应
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onComplete(fullResponse.toString());
                        });
                        
                    } catch (IOException e) {
                        LogManager.logE(TAG, "读取响应失败: " + e.getMessage(), e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onError("读取响应失败: " + e.getMessage());
                        });
                    } finally {
                        body.close();
                    }
                }
            });
            
        } catch (Exception e) {
            LogManager.logE(TAG, "创建请求失败: " + e.getMessage(), e);
            callback.onError("创建请求失败: " + e.getMessage());
        }
    }
    
    /**
     * Synchronous image generation API request.
     * Reuses OkHttp client and auth logic from streaming path.
     * Builds OpenAI-compatible multimodal content array.
     *
     * @param apiUrl       Full image generation endpoint URL
     * @param apiKey       API key
     * @param origApiUrl   Original user-configured API URL (for auth cache lookup)
     * @param requestBody  Pre-built JSON request body
     * @param callback     Callback for result
     */
    public void imageRequest(String apiUrl, String apiKey, String origApiUrl,
                             JSONObject requestBody, StreamingCallback callback) {
        // Build request with same auth logic as streamRequest
        Request.Builder requestBuilder = new Request.Builder()
            .url(apiUrl)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")));

        String cachedMethod = AuthMethodCache.getCachedMethod(context, origApiUrl);
        if (cachedMethod != null) {
            AuthMethodCache.applyAuthHeader(requestBuilder, apiKey, cachedMethod);
        } else {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        // Execute synchronously on caller's thread (already a background thread)
        try {
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                int statusCode = response.code();
                LogManager.logE(TAG, "[IMAGE_GEN] Request failed: " + statusCode + " - " + errorBody);
                // Stay on background thread - caller may need to do more I/O
                callback.onError("Image generation failed: " + errorBody, statusCode);
                return;
            }

            String responseBody = response.body().string();
            LogManager.logI(TAG, "[IMAGE_GEN] Response: " + responseBody.substring(0, Math.min(200, responseBody.length())));

            // Stay on background thread - caller needs to download image (network I/O)
            callback.onComplete(responseBody);
        } catch (Exception e) {
            LogManager.logE(TAG, "[IMAGE_GEN] Error: " + e.getMessage(), e);
            // Stay on background thread
            callback.onError("Image generation error: " + e.getMessage());
        }
    }

    /**
     * Get the OkHttp client instance (for reuse by ApiUtils.downloadAndSaveImage).
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * Synchronously call MiniMax VLM interface to convert images to text description.
     * MiniMax VLM (GUI understanding model) can extract text, coordinates, buttons from screenshots.
     * Must be called on a background thread.
     *
     * @param apiKey        MiniMax API key
     * @param imagePaths    List of local image file paths
     * @param userPrompt    Reserved, not used by VLM (VLM uses a fixed universal analysis prompt)
     * @return              Text description of images, or null if failed
     */
    public String callMinimaxVlmSync(String apiKey, List<String> imagePaths, String userPrompt) {
        if (imagePaths == null || imagePaths.isEmpty()) return null;

        try {
            // Build VLM request body
            JSONObject requestBody = new JSONObject();

            // Fixed universal image analysis prompt - userPrompt is for downstream chat only
            String vlmPrompt =
                "Analyze this image comprehensively and output a structured report with the following sections:\n\n" +
                "## 1. Overall Description\n" +
                "Briefly describe the overall content and purpose of the image (1-3 sentences).\n\n" +
                "## 2. Text Content (OCR)\n" +
                "List ALL visible text in the image, preserving the original language. " +
                "For each text block, provide: text content and approximate position (e.g., top-left, center, bottom-right).\n\n" +
                "## 3. UI Elements & Coordinates (GUI)\n" +
                "List ALL interactive UI elements (buttons, icons, input fields, tabs, menus, links, etc.). " +
                "For each element, provide:\n" +
                "- Element type (button/icon/input/tab/etc.)\n" +
                "- Label or description\n" +
                "- Center coordinate as [x, y] in normalized range 0-999, " +
                "where [0,0] is top-left and [999,999] is bottom-right of the image, " +
                "x increases rightward, y increases downward\n\n" +
                "## 4. Layout Structure\n" +
                "Describe the overall layout: navigation bars, content areas, key sections.\n\n" +
                "Be precise with coordinates. If the image is a mobile screenshot, " +
                "note the screen resolution if visible.";
            requestBody.put("prompt", vlmPrompt);

            // Encode first image to base64 data URL (API accepts single image_url)
            String firstImagePath = imagePaths.get(0);
            String base64 = ApiUtils.encodeImageToBase64(firstImagePath);
            if (base64 == null) {
                LogManager.logW(TAG, "[MINIMAX_VLM] Failed to encode image, skipping VLM");
                return null;
            }
            // Determine MIME type from file extension
            String mimeType = "image/jpeg";
            String lowerPath = firstImagePath.toLowerCase();
            if (lowerPath.endsWith(".png")) mimeType = "image/png";
            else if (lowerPath.endsWith(".gif")) mimeType = "image/gif";
            else if (lowerPath.endsWith(".webp")) mimeType = "image/webp";
            String imageDataUrl = "data:" + mimeType + ";base64," + base64;
            requestBody.put("image_url", imageDataUrl);
            LogManager.logI(TAG, "[MINIMAX_VLM] Encoded image: " + firstImagePath + ", mimeType=" + mimeType);

            // Build request with mandatory mm-api-source header
            Request request = new Request.Builder()
                    .url("https://api.minimax.chat/v1/coding_plan/vlm")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("mm-api-source", "minimax-mcp")
                    .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                    .build();

            LogManager.logI(TAG, "[MINIMAX_VLM] Calling VLM endpoint...");

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                LogManager.logE(TAG, "[MINIMAX_VLM] VLM request failed: " + response.code() + " - " + errorBody);
                return null;
            }

            String responseStr = response.body().string();
            LogManager.logI(TAG, "[MINIMAX_VLM] VLM response length: " + responseStr.length());

            // Parse response: { "content": "..." }
            JSONObject responseJson = new JSONObject(responseStr);
            String content = responseJson.optString("content", "");
            if (content.isEmpty()) {
                LogManager.logW(TAG, "[MINIMAX_VLM] VLM returned empty content, raw: " + responseStr.substring(0, Math.min(200, responseStr.length())));
                return null;
            }

            LogManager.logI(TAG, "[MINIMAX_VLM] VLM description length: " + content.length());
            return content;

        } catch (Exception e) {
            LogManager.logE(TAG, "[MINIMAX_VLM] VLM call failed: " + e.getMessage(), e);
            return null;
        }
    }
}

