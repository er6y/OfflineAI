package com.example.starlocalrag.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.starlocalrag.LogManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
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
     * 发送流式API请求
     * @param apiUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @param prompt 提示内容
     * @param callback 回调接口
     */
    public void streamRequest(String apiUrl, String apiKey, String model, String prompt, StreamingCallback callback) {
        try {
            LogManager.logD(TAG, "准备发送流式请求: " + apiUrl);
            
            // 检查提示词中是否包含系统提示词
            String systemPrompt = "";
            String userPrompt = prompt;
            
            // 提取系统提示词（如果存在）
            // 系统提示词通常位于提示词的开头，直到第一个空行
            if (prompt.contains("\n\n")) {
                int firstEmptyLineIndex = prompt.indexOf("\n\n");
                systemPrompt = prompt.substring(0, firstEmptyLineIndex).trim();
                userPrompt = prompt.substring(firstEmptyLineIndex + 2).trim();
            }
            
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            
            // 创建消息数组
            JSONArray messages = new JSONArray();
            
            // 添加系统提示词（如果存在）
            if (!systemPrompt.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                LogManager.logD(TAG, "添加系统提示词，长度: " + systemPrompt.length());
            }
            
            // 添加用户提示词
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            
            requestBody.put("messages", messages);
            requestBody.put("stream", true);
            
            // 构建请求
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
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
                        LogManager.logE(TAG, "Request failed, status code: " + response.code());
                        new Handler(Looper.getMainLooper()).post(() -> {
                            callback.onError("Request failed, status code: " + response.code());
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
                    
                    try {
                        BufferedSource source = body.source();
                        while (!source.exhausted()) {
                            String line = source.readUtf8Line();
                            if (line == null) continue;
                            
                            //LogManager.logD(TAG, "收到数据: " + line);
                            
                            if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                                String jsonStr = line.substring(6).trim();
                                try {
                                    JSONObject data = new JSONObject(jsonStr);
                                    JSONArray choices = data.getJSONArray("choices");
                                    JSONObject choice = choices.getJSONObject(0);
                                    JSONObject delta = choice.getJSONObject("delta");
                                    
                                    if (delta.has("content")) {
                                        String content = delta.getString("content");
                                        fullResponse.append(content);
                                        
                                        // 在主线程中回调
                                        new Handler(Looper.getMainLooper()).post(() -> {
                                            callback.onToken(content);
                                        });
                                    }
                                } catch (JSONException e) {
                                    LogManager.logE(TAG, "解析JSON失败: " + e.getMessage(), e);
                                }
                            }
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
}
