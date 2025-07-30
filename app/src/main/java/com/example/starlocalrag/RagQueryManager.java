package com.example.starlocalrag;

import android.content.Context;
import android.util.Log;
import com.example.starlocalrag.LogManager;

import com.example.starlocalrag.api.LlmApiAdapter;
import com.example.starlocalrag.api.LlmModelFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * RAG查询管理器
 * 负责处理知识库查询和大模型调用的整合
 */
public class RagQueryManager {
    private static final String TAG = "RagQueryManager";
    
    private final Context context;
    private final LlmModelFactory modelFactory;
    
    public RagQueryManager(Context context) {
        this.context = context;
        this.modelFactory = LlmModelFactory.getInstance(context);
    }
    
    /**
     * 执行RAG查询
     * @param apiUrl API地址
     * @param apiKey API密钥
     * @param model 模型名称
     * @param knowledgeBase 知识库名称
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提问
     * @param callback 回调接口
     */
    public void executeRagQuery(String apiUrl, String apiKey, String model, String knowledgeBase, 
                               String systemPrompt, String userPrompt, RagQueryCallback callback) {
        // 检查是否使用知识库
        String valueNone = context.getString(R.string.common_none);
        String valueNoAvailableKb = context.getString(R.string.value_no_available_kb);
        if (valueNone.equals(knowledgeBase) || valueNoAvailableKb.equals(knowledgeBase)) {
            // 不使用知识库，直接构建提示词
            String fullPrompt = buildDirectPrompt(systemPrompt, userPrompt);
            LogManager.logD(TAG, "Not using knowledge base, building prompt directly: " + fullPrompt);
            
            // 回调进度更新
            callback.onProgressUpdate(context.getString(R.string.building_prompt), context.getString(R.string.not_using_kb_building_prompt));
            
            // 调用大模型API
            callback.onProgressUpdate(context.getString(R.string.calling_llm_api), context.getString(R.string.api_type) + ": " + modelFactory.getProviderByUrl(apiUrl).getName());
            
            modelFactory.callModel(apiUrl, apiKey, model, fullPrompt, new LlmApiAdapter.ApiCallback() {
                @Override
                public void onSuccess(String response) {
                    callback.onQueryCompleted(response);
                }
                
                @Override
                public void onStreamingData(String chunk) {
                    callback.onStreamingData(chunk);
                }
                
                @Override
                public void onError(String errorMessage) {
                    callback.onQueryError(errorMessage);
                }
            });
        } else {
            // 使用知识库，先查询知识库
            callback.onProgressUpdate("Querying knowledge base...", "Knowledge base: " + knowledgeBase);
            
            // 在后台线程中执行知识库查询
            new Thread(() -> {
                try {
                    // 查询知识库获取相关内容
                    String relevantContent = queryKnowledgeBase(knowledgeBase, userPrompt);
                    LogManager.logD(TAG, "Knowledge base query result (first 200 characters): " + 
                          (relevantContent.length() > 200 ? relevantContent.substring(0, 200) + "..." : relevantContent));
                    
                    // 回调进度更新
                    callback.onProgressUpdate("Querying knowledge base...", 
                                            "Retrieved " + relevantContent.length() + " characters from knowledge base");
                    
                    // 构建完整的提示词
                    callback.onProgressUpdate("Building prompt...", "Building complete prompt with knowledge base content");
                    String fullPrompt = buildFullPrompt(systemPrompt, userPrompt, relevantContent);
                    
                    // 调用大模型API
                    callback.onProgressUpdate("Calling LLM API...", 
                                            "API type: " + modelFactory.getProviderByUrl(apiUrl).getName());
                    
                    modelFactory.callModel(apiUrl, apiKey, model, fullPrompt, new LlmApiAdapter.ApiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            callback.onQueryCompleted(response);
                        }
                        
                        @Override
                        public void onStreamingData(String chunk) {
                            callback.onStreamingData(chunk);
                        }
                        
                        @Override
                        public void onError(String errorMessage) {
                            callback.onQueryError(errorMessage);
                        }
                    });
                    
                } catch (Exception e) {
                    LogManager.logE(TAG, "RAG query exception", e);
                    callback.onQueryError("RAG query error: " + e.getMessage());
                }
            }).start();
        }
    }
    
    /**
     * 查询知识库获取相关内容
     */
    private String queryKnowledgeBase(String knowledgeBase, String query) {
        String valueNone = context.getString(R.string.common_none);
        String valueNoAvailableKb = context.getString(R.string.value_no_available_kb);
        if (valueNone.equals(knowledgeBase) || valueNoAvailableKb.equals(knowledgeBase)) {
            LogManager.logD(TAG, "No knowledge base selected, skipping knowledge base query");
            return ""; // If "None" is selected
        }
        
        LogManager.logD(TAG, "Starting knowledge base query: " + knowledgeBase);
        
        try {
            // 获取知识库目录
            File knowledgeBaseDir = new File(context.getExternalFilesDir(null), "knowledge_bases/" + knowledgeBase);
            if (!knowledgeBaseDir.exists()) {
                return "Knowledge base '" + knowledgeBase + "' does not exist";
            }
            
            // In actual implementation, vector database should be used for similarity search
            // Simplified here to read file contents from knowledge base
            StringBuilder relevantContent = new StringBuilder();
            File[] files = knowledgeBaseDir.listFiles();
            
            if (files != null && files.length > 0) {
                // For simplicity, only read the first 3 files
                int count = Math.min(files.length, 3);
                
                for (int i = 0; i < count; i++) {
                    if (files[i].isFile() && files[i].getName().endsWith(".txt")) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(files[i]))) {
                            LogManager.logD(TAG, "Reading knowledge base file: " + files[i].getName());
                            
                            String line;
                            while ((line = reader.readLine()) != null) {
                                relevantContent.append(line).append("\n");
                            }
                        }
                    }
                }
            }
            
            return relevantContent.toString();
        } catch (IOException e) {
            LogManager.logE(TAG, "Knowledge base query error", e);
            return "Knowledge base query error: " + e.getMessage();
        }
    }
    
    /**
     * 构建不包含知识库内容的直接提示词
     */
    private String buildDirectPrompt(String systemPrompt, String userPrompt) {
        StringBuilder fullPrompt = new StringBuilder();
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append("系统: ").append(systemPrompt).append("\n\n");
        }
        fullPrompt.append("用户: ").append(userPrompt);
        return fullPrompt.toString();
    }
    
    /**
     * 构建包含知识库内容的完整提示词
     */
    private String buildFullPrompt(String systemPrompt, String userPrompt, String relevantContent) {
        StringBuilder fullPrompt = new StringBuilder();
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append("系统: ").append(systemPrompt).append("\n\n");
        }
        fullPrompt.append("相关知识: ").append(relevantContent).append("\n\n");
        fullPrompt.append("用户: ").append(userPrompt);
        return fullPrompt.toString();
    }
    
    /**
     * RAG查询回调接口
     */
    public interface RagQueryCallback {
        /**
         * 查询进度更新
         * @param progress 进度信息
         * @param debugInfo 调试信息
         */
        void onProgressUpdate(String progress, String debugInfo);
        
        /**
         * 查询完成
         * @param result 查询结果
         */
        void onQueryCompleted(String result);
        
        /**
         * 查询错误
         * @param errorMessage 错误信息
         */
        void onQueryError(String errorMessage);
        
        /**
         * 接收流式数据
         * @param chunk 流式数据块
         */
        void onStreamingData(String chunk);
    }
}