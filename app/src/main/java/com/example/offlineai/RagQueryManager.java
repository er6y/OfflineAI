package com.example.offlineai;

import android.content.Context;
import android.util.Log;
import com.example.offlineai.LogManager;

import com.example.offlineai.api.LlmApiAdapter;
import com.example.offlineai.api.LlmModelFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

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
     * 查询知识库获取相关内容（真正的向量检索实现）
     */
    private String queryKnowledgeBase(String knowledgeBase, String query) {
        String valueNone = context.getString(R.string.common_none);
        String valueNoAvailableKb = context.getString(R.string.value_no_available_kb);
        if (valueNone.equals(knowledgeBase) || valueNoAvailableKb.equals(knowledgeBase)) {
            LogManager.logD(TAG, "No knowledge base selected, skipping knowledge base query");
            return ""; // If "None" is selected
        }
        
        LogManager.logD(TAG, "[Vector RAG] Starting knowledge base query: " + knowledgeBase);
        
        try {
            // 1. 获取知识库目录
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(context);
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            if (!knowledgeBaseDir.exists()) {
                return "Knowledge base '" + knowledgeBase + "' does not exist";
            }
            
            LogManager.logD(TAG, "[Vector RAG] Knowledge base directory: " + knowledgeBaseDir.getAbsolutePath());
            
            // 2. 初始化向量数据库
            SQLiteVectorDatabaseHandler vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
            if (!vectorDb.loadDatabase()) {
                return "Failed to load vector database";
            }
            
            LogManager.logD(TAG, "[Vector RAG] Vector database loaded, chunk count: " + vectorDb.getChunkCount());
            
            // 3. 加载metadata获取embedding模型信息
            File metadataFile = new File(knowledgeBaseDir, "metadata.json");
            String embeddingModelPath = null;
            
            if (metadataFile.exists()) {
                try {
                    String metadataJson = readFileToString(metadataFile);
                    JSONObject metadata = new JSONObject(metadataJson);
                    String modeldir = metadata.optString("modeldir", null);
                    
                    if (modeldir != null && !modeldir.isEmpty()) {
                        String embeddingModelsPath = ConfigManager.getEmbeddingModelPath(context);
                        embeddingModelPath = embeddingModelsPath + File.separator + modeldir;
                        LogManager.logD(TAG, "[Vector RAG] Embedding model from metadata: " + embeddingModelPath);
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[Vector RAG] Failed to read metadata", e);
                }
            }
            
            // 如果metadata中没有，使用配置中的默认模型
            if (embeddingModelPath == null) {
                embeddingModelPath = ConfigManager.getEmbeddingModelPath(context);
                LogManager.logD(TAG, "[Vector RAG] Using default embedding model: " + embeddingModelPath);
            }
            
            // 4. 初始化EmbeddingHandler
            EmbeddingHandler embeddingHandler = EmbeddingHandler.getInstance(context);
            if (!embeddingHandler.loadModel(embeddingModelPath)) {
                return "Failed to load embedding model";
            }
            
            LogManager.logD(TAG, "[Vector RAG] Embedding model loaded successfully");
            
            // 5. 对query进行embedding
            float[] queryVector = embeddingHandler.computeEmbedding(query);
            if (queryVector == null) {
                return "Failed to compute query embedding";
            }
            
            LogManager.logD(TAG, "[Vector RAG] Query embedding computed, dimension: " + queryVector.length);
            
            // 6. 向量检索 - 获取TopK相关文档
            int topK = ConfigManager.getInt(context, ConfigManager.KEY_RETRIEVAL_COUNT, 20);
            List<SQLiteVectorDatabaseHandler.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, topK);
            
            LogManager.logD(TAG, "[Vector RAG] Vector search completed, found " + searchResults.size() + " results");
            
            // 7. 构建相关内容
            StringBuilder relevantContent = new StringBuilder();
            for (int i = 0; i < searchResults.size(); i++) {
                SQLiteVectorDatabaseHandler.SearchResult result = searchResults.get(i);
                relevantContent.append("[Document ").append(i + 1).append("] (similarity: ")
                               .append(String.format("%.4f", result.similarity)).append(")\n");
                relevantContent.append(result.text).append("\n\n");
                
                LogManager.logD(TAG, "[Vector RAG] Result " + (i+1) + " - similarity: " + 
                              String.format("%.4f", result.similarity) + ", source: " + result.source);
            }
            
            // 8. 关闭数据库
            vectorDb.closeDatabase();
            
            return relevantContent.toString();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[Vector RAG] Knowledge base query error", e);
            return "Vector search error: " + e.getMessage();
        }
    }
    
    /**
     * 读取文件内容为字符串
     */
    private String readFileToString(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
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
