package com.example.starlocalrag;

import com.example.starlocalrag.api.TokenizerManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.example.starlocalrag.LogManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text chunk processor
 * Responsible for two-stage processing of text extraction and vectorization
 */
public class TextChunkProcessor {
    private static final String TAG = "StarLocalRAG_TextChunk";
    
    // 最小文本块大小，从ConfigManager中获取
    private int minChunkSize;
    
    // 上下文
    private final Context context;
    
    // 文档解析器
    private final DocumentParser documentParser;
    
    // 任务取消标志
    private final AtomicBoolean isTaskCancelled;
    
    // 进度回调
    private ProgressCallback progressCallback;
    
    // 通知进度回调
    private NotificationProgressCallback notificationProgressCallback;
    
    // 中间文件名
    private static final String INTERMEDIATE_FILE_NAME = "intermediate_chunks.json";
    
    /**
     * Text chunk class
     */
    public static class TextChunk {
        public String text;
        public String source;
        public int chunkIndex;
        public JSONObject metadata;
        
        public TextChunk(String text, String source, int chunkIndex, JSONObject metadata) {
            this.text = text;
            this.source = source;
            this.chunkIndex = chunkIndex;
            this.metadata = metadata;
        }
    }
    
    /**
     * Progress callback interface
     */
    public interface ProgressCallback {
        void onTextExtractionProgress(int processedFiles, int totalFiles, String currentFile);
        void onVectorizationProgress(int processedChunks, int totalChunks, float percentage);
        void onTextExtractionComplete(int totalChunks);
        void onVectorizationComplete(int totalVectors);
        void onError(String errorMessage);
        void onLog(String message);
    }
    
    /**
     * Notification progress callback interface
     */
    public interface NotificationProgressCallback {
        void onNotificationProgressUpdate(int processedChunks, int totalChunks, float percentage);
    }
    
    /**
     * Constructor
     * @param context Context
     */
    public TextChunkProcessor(Context context) {
        this.context = context;
        this.minChunkSize = ConfigManager.getMinChunkSize(context);
        this.isTaskCancelled = new AtomicBoolean(false);
        this.documentParser = new DocumentParser(context);
    }
    
    /**
     * Constructor
     * @param context Context
     */
    public TextChunkProcessor(Context context, AtomicBoolean isTaskCancelled) {
        this.context = context;
        this.minChunkSize = ConfigManager.getMinChunkSize(context);
        this.isTaskCancelled = isTaskCancelled;
        this.documentParser = new DocumentParser(context);
    }
    
    /**
     * Set progress callback
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }
    
    /**
     * Set notification progress callback
     */
    public void setNotificationProgressCallback(NotificationProgressCallback callback) {
        this.notificationProgressCallback = callback;
    }
    
    /**
     * Process file list
     * @param knowledgeBasePath Knowledge base path
     * @param files File list
     * @param chunkSize Chunk size
     * @param chunkOverlap Chunk overlap size
     * @param embeddingModel Embedding model
     * @param vectorDB Vector database
     * @return Whether successful
     */
    public boolean processFiles(String knowledgeBasePath, List<Uri> files, int chunkSize, int chunkOverlap, 
                               EmbeddingModelHandler embeddingModel, SQLiteVectorDatabaseHandler vectorDB) {
        try {
            // 第一阶段：提取文本并分块
            List<TextChunk> chunks = extractTextFromFiles(knowledgeBasePath, files, chunkSize, chunkOverlap);
            if (chunks == null || chunks.isEmpty()) {
                logMessage("Failed to extract any text chunks");
                return false;
            }
            
            // 保存中间结果
            saveIntermediateChunks(knowledgeBasePath, chunks);
            
            // Notify text extraction completed
            if (progressCallback != null) {
                progressCallback.onTextExtractionComplete(chunks.size());
            }
            
            // Check if task is cancelled
            if (isTaskCancelled.get()) {
                logMessage("Task cancelled");
                return false;
            }
            
            // 第二阶段：向量化
            boolean success = processChunksToVectors(chunks, embeddingModel, vectorDB);
            
            // 删除中间文件
            deleteIntermediateFile(knowledgeBasePath);
            
            return success;
        } catch (Exception e) {
            logError("Failed to process files: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Extract text from files and chunk
     */
    private List<TextChunk> extractTextFromFiles(String knowledgeBasePath, List<Uri> files, int chunkSize, int chunkOverlap) {
        List<TextChunk> allChunks = new ArrayList<>();
        int totalFiles = files.size();
        AtomicInteger processedFiles = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);
        
        try {
            for (int i = 0; i < totalFiles; i++) {
                // Check if task is cancelled
                if (isTaskCancelled.get()) {
                    logMessage("Text extraction cancelled");
                    return allChunks;
                }
                
                Uri fileUri = files.get(i);
                String fileName = UriUtils.getFileName(context, fileUri);
                
                // 更新进度
                int currentProcessed = processedFiles.incrementAndGet();
                if (progressCallback != null) {
                    //progressCallback.onTextExtractionProgress(currentProcessed, totalFiles, fileName);
                }
                
                try {
                    // 提取文本
                    String text = documentParser.extractText(fileUri);
                    if (text == null || text.trim().isEmpty()) {
                        logMessage("Warning: Failed to extract text from file " + fileName);
                        continue;
                    }
                    
                    // Record file size
                    int textLength = text.length();
                    logMessage("File: " + fileName + " extracted text size: " + (textLength / 1024) + "KB");
                    
                    // Check if it's JSON content
                    boolean isJson = false;
                    boolean isSpecialDataset = false;
                    try {
                        // First check if filename contains json
                        boolean fileNameIndicatesJson = fileName.toLowerCase().endsWith(".json");
                        
                        // Check if it's a specific dataset
                        if (fileNameIndicatesJson && (
                            fileName.contains("datasets-sb") || 
                            fileName.contains("alpaca") || 
                            fileName.contains("STAR") || 
                            fileName.contains("star"))) {
                            isSpecialDataset = true;
                            logMessage("Detected specific dataset: " + fileName + ", will ignore minimum chunk size limit");
                        }
                        
                        if (fileNameIndicatesJson) {
                            logMessage("Filename indicates this might be a JSON file: " + fileName);
                            // For JSON files, use stricter detection
                            isJson = JsonDatasetProcessor.isJsonContent(text);
                        } else {
                            // For non-JSON files, only recognize as JSON when content is clearly JSON format
                            String trimmedText = text.trim();
                            boolean looksLikeJson = (trimmedText.startsWith("{") && trimmedText.endsWith("}")) || 
                                                  (trimmedText.startsWith("[") && trimmedText.endsWith("]"));
                            
                            if (looksLikeJson) {
                                isJson = JsonDatasetProcessor.isJsonContent(text);
                            }
                        }
                    } catch (Exception e) {
                        logError("Error checking JSON content: " + e.getMessage(), e);
                    }
                    
                    boolean jsonOptimizationEnabled = ConfigManager.isJsonDatasetSplittingEnabled(context);
                    
                    // Clearly display JSON format recognition result
                    String jsonStatusMessage = "File: " + fileName + (isJson ? " is JSON format" : " is not JSON format") + 
                                              (isSpecialDataset ? " (specific dataset, ignore minimum chunk size limit)" : "");
                    logMessage(jsonStatusMessage);
                    
                    if (isJson) {
                        String configStatusMessage = "JSON optimization config status: " + (jsonOptimizationEnabled ? "enabled" : "disabled");
                        logMessage(configStatusMessage);
                        
                        if (jsonOptimizationEnabled) {
                            // Try to recognize JSON format
                            try {
                                // Get first 100 characters of JSON content as preview
                                String jsonPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                                logMessage("JSON content preview: " + jsonPreview);
                                
                                // Use JsonDatasetProcessor to process JSON content
                                logMessage("Starting to process JSON content using JsonDatasetProcessor...");
                                
                                // Ensure no exceptions prevent processing
                                List<String> jsonChunks = new ArrayList<>();
                                try {
                                    // For large JSON files, special processing may be needed
                                    if (text.length() > 1000000) { // Files over 1MB
                                        logMessage("Detected large JSON file (" + (text.length() / 1024 / 1024) + "MB), using segmented processing");
                                        
                                        // Try to fix possible JSON format issues
                                        String processedText = text;
                                        if (text.trim().startsWith("[") && !text.trim().endsWith("]")) {
                                            processedText = text.trim() + "]";
                                            logMessage("JSON file seems incomplete, trying to fix by adding closing bracket");
                                        }
                                        
                                        // Use specific dataset processing flag
                                        jsonChunks = JsonDatasetProcessor.processJsonDataset(context, processedText, isSpecialDataset);
                                    } else {
                                        // Use specific dataset processing flag
                                        jsonChunks = JsonDatasetProcessor.processJsonDataset(context, text, isSpecialDataset);
                                    }
                                    logMessage("JSON processing completed, returned " + jsonChunks.size() + " text chunks");
                                } catch (Exception e) {
                                    logError("Error during JSON processing: " + e.getMessage(), e);
                                    // Try to process JSON using more tolerant approach
                                    try {
                                        logMessage("Trying to use alternative method to process JSON...");
                                        // Check if it's Alpaca format
                                        if (text.contains("\"instruction\"") && 
                                            (text.contains("\"output\"") || text.contains("\"response\""))) {
                                            logMessage("Detected possible Alpaca format, trying manual parsing");
                                            
                                            // Simple manual parsing, extract instruction and output/response pairs
                                            String[] lines = text.split("\\n");
                                            StringBuilder currentItem = new StringBuilder();
                                            for (String line : lines) {
                                                line = line.trim();
                                                if (line.contains("\"instruction\"")) {
                                                    if (currentItem.length() > 0) {
                                                        // Process previous item
                                                        String itemText = currentItem.toString();
                                                        if (itemText.length() >= minChunkSize) {
                                                            jsonChunks.add(itemText);
                                                        }
                                                        currentItem = new StringBuilder();
                                                    }
                                                    // Start new item
                                                    currentItem.append("Instruction: ").append(extractValue(line)).append("\n\n");
                                                } else if (line.contains("\"output\"") || line.contains("\"response\"")) {
                                                    currentItem.append("Output: ").append(extractValue(line));
                                                }
                                            }
                                            
                                            // Process last item
                                            if (currentItem.length() > 0) {
                                                String itemText = currentItem.toString();
                                                if (itemText.length() >= minChunkSize) {
                                                    jsonChunks.add(itemText);
                                                }
                                            }
                                            
                                            logMessage("Manual parsing completed, extracted " + jsonChunks.size() + " text chunks");
                                        }
                                    } catch (Exception ex) {
                                        logError("Alternative JSON processing method also failed: " + ex.getMessage(), ex);
                                    }
                                }
                                
                                if (!jsonChunks.isEmpty()) {
                                    logMessage("JSON processing successful, optimization applied, generated " + jsonChunks.size() + " text chunks");
                                    
                                    // 创建文本块对象
                                    for (int j = 0; j < jsonChunks.size(); j++) {
                                        String chunkText = jsonChunks.get(j);
                                        
                                        // Note: No need to check if chunk size is reasonable here
                                        // LangChainTextSplitter has already filtered out chunks that are too small based on minChunkSize
                                        
                                        // Create metadata
                                        JSONObject metadata = new JSONObject();
                                        try {
                                            metadata.put("fileName", fileName);
                                            metadata.put("fileIndex", i);
                                            metadata.put("chunkIndex", j);
                                            metadata.put("totalChunks", jsonChunks.size());
                                            metadata.put("extractionTime", System.currentTimeMillis());
                                            metadata.put("processingMethod", "JsonOptimized");
                                        } catch (JSONException e) {
                                            logError("Failed to create metadata: " + e.getMessage(), e);
                                        }
                                        
                                        // Add to text chunk list
                                        TextChunk chunk = new TextChunk(chunkText, fileName, j, metadata);
                                        allChunks.add(chunk);
                                    }
                                    
                                    // Print chunk count for each file
                                    String fileProcessingSummary = "Processed JSON file: " + fileName + ", extracted " + jsonChunks.size() + " text chunks using optimization";
                                    logMessage(fileProcessingSummary);
                                    
                                    // If progress callback exists, ensure this info is displayed on UI
                                    if (progressCallback != null) {
                                        progressCallback.onLog("File: " + fileName + " -> JSON optimized chunk count: " + jsonChunks.size());
                                    }
                                    
                                    totalChunks.addAndGet(jsonChunks.size());
                                    continue; // Skip standard chunking processing
                                } else {
                                    logMessage("JSON processing generated no text chunks, will fallback to standard chunking");
                                }
                            } catch (Exception e) {
                                logError("JSON processing failed: " + e.getMessage() + ", will fallback to standard chunking", e);
                            }
                        } else {
                            logMessage("JSON optimization disabled, will use standard chunking");
                        }
                    } else {
                        logMessage("JSON optimization disabled, will use standard chunking");
                    }
                    
                    // Process text chunking
                    List<String> chunks;
                    int fileChunkCount = 0;
                    
                    if (isJson && jsonOptimizationEnabled) {
                        // Use JSON processing logic
                        List<String> jsonChunks = new ArrayList<>();
                        try {
                            // For large JSON files, special processing may be needed
                            if (text.length() > 1000000) { // Files over 1MB
                                logMessage("Detected large JSON file (" + (text.length() / 1024 / 1024) + "MB), using segmented processing");
                                
                                // Try to fix possible JSON format issues
                                String processedText = text;
                                if (text.trim().startsWith("[") && !text.trim().endsWith("]")) {
                                    processedText = text.trim() + "]";
                                    logMessage("JSON file seems incomplete, trying to fix by adding closing bracket");
                                }
                                
                                // Use specific dataset processing flag
                                jsonChunks = JsonDatasetProcessor.processJsonDataset(context, processedText, isSpecialDataset);
                            } else {
                                // Use specific dataset processing flag
                                jsonChunks = JsonDatasetProcessor.processJsonDataset(context, text, isSpecialDataset);
                            }
                            logMessage("JSON processing completed, returned " + jsonChunks.size() + " text chunks");
                        } catch (Exception e) {
                            logError("Error during JSON processing: " + e.getMessage(), e);
                            // If JSON processing fails, use standard chunking
                            logMessage("JSON processing failed, fallback to standard chunking");
                            jsonChunks = splitTextIntoChunks(text, chunkSize, chunkOverlap);
                        }
                        
                        chunks = jsonChunks;
                        fileChunkCount = jsonChunks.size();
                    } else {
                        // Use standard chunking processing
                        logMessage("Using standard chunking, chunk size: " + chunkSize + ", overlap size: " + chunkOverlap);
                        chunks = splitTextIntoChunks(text, chunkSize, chunkOverlap);
                        fileChunkCount = chunks.size();
                    }
                    
                    // Record the number of text chunks generated by this file
                    logMessage("File: " + fileName + " generated " + fileChunkCount + " text chunks");
                    
                    // Add to total chunk list
                    int chunkIndex = 0;
                    for (String chunk : chunks) {
                        JSONObject metadata = new JSONObject();
                        try {
                            metadata.put("source", fileName);
                            metadata.put("chunkIndex", chunkIndex++);
                            metadata.put("extractionTime", System.currentTimeMillis());
                        } catch (JSONException e) {
                            logError("Error creating metadata: " + e.getMessage(), e);
                        }
                        
                        allChunks.add(new TextChunk(chunk, fileName, chunkIndex - 1, metadata));
                        totalChunks.incrementAndGet();
                    }
                    
                    // Update progress callback, including current file's text chunk count
                    if (progressCallback != null) {
                        progressCallback.onTextExtractionProgress(currentProcessed, totalFiles, fileName + " (generated " + fileChunkCount + " text chunks)");
                    }
                    
                } catch (Exception e) {
                    logError("Failed to process file: " + fileName + ", error: " + e.getMessage(), e);
                }
            }
            
            logMessage("Text extraction completed, total " + totalChunks.get() + " text chunks");
            return allChunks;
        } catch (Exception e) {
            logError("Text extraction process failed: " + e.getMessage(), e);
            return allChunks;
        }
    }
    
    /**
     * Split text into chunks
     * @param text Text to split
     * @param chunkSize Chunk size
     * @param chunkOverlap Chunk overlap size
     * @return List of split text chunks
     */
    public List<String> splitTextIntoChunks(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Use LangChainTextSplitter for text splitting, ensure consistency with PC side
        LogManager.logD(TAG, "Using LangChainTextSplitter for text splitting");
        // Get minimum chunk size from ConfigManager instead of using hardcoded value
        LangChainTextSplitter splitter = new LangChainTextSplitter(chunkSize, chunkOverlap, minChunkSize);
        List<String> chunks = splitter.splitText(text);
        
        LogManager.logD(TAG, "Text splitting completed, generated " + chunks.size() + " text chunks");
        
        return chunks;
    }
    
    /**
     * Vectorize text chunks and store them in database
     */
    private boolean processChunksToVectors(List<TextChunk> chunks, EmbeddingModelHandler model, 
                                          SQLiteVectorDatabaseHandler vectorDB) {
        if (chunks == null || chunks.isEmpty()) {
            logError("No text chunks to process", null);
            return false;
        }
        
        try {
            int totalChunks = chunks.size();
            logMessage("Starting vectorization processing, total " + totalChunks + " text chunks");
            LogManager.logD(TAG, "Starting vectorization processing, total " + totalChunks + " text chunks");
            
            // Get EmbeddingModelManager instance to mark model usage status
            EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(context);
            
            try {
                // Mark model as in use
                modelManager.markModelInUse();
                LogManager.logD(TAG, "Marked model as in use");
                
                // Initialize progress log
                String vectorizationProgress = context.getString(R.string.status_vectorization_progress);
                StringBuilder progressLog = new StringBuilder(vectorizationProgress);
                int lastPercentage = 0;
                
                // First send initial progress log
                if (progressCallback != null) {
                    progressCallback.onLog(progressLog.toString());
                }
                
                // Process all text chunks
                for (int i = 0; i < totalChunks; i++) {
                    // Check if task is cancelled
                    if (isTaskCancelled.get()) {
                        logMessage("Task cancelled");
                        return false;
                    }
                    
                    TextChunk chunk = chunks.get(i);
                    String text = chunk.text;
                    String source = chunk.source;
                    JSONObject metadata = chunk.metadata;
                    
                    try {
                        // Add a dot for each processed text chunk
                        progressLog.append(".");
                        
                        // Calculate current percentage
                        int currentPercentage = (i + 1) * 100 / totalChunks;
                        
                        // Check if percentage needs to be displayed
                        boolean showPercentage = currentPercentage / 10 > lastPercentage / 10 || i == totalChunks - 1;
                        
                        if (showPercentage) {
                            // Print percentage
                            progressLog.append(currentPercentage + "%");
                            lastPercentage = currentPercentage;
                        }
                        
                        // Update UI display for each processed text chunk
                        if (progressCallback != null) {
                            progressCallback.onLog(progressLog.toString());
                        }
                        
                        // Record detailed log every 100 text chunks or at the last text chunk (only shown in debug log)
                        if (i % 100 == 0 || i == totalChunks - 1) {
                            LogManager.logD(TAG, "Vectorization detailed progress: " + (i + 1) + "/" + totalChunks + 
                                  ", Thread ID: " + Thread.currentThread().getId() + 
                                  ", Source file: " + source);
                        }
                        
                        // Generate vector
                        float[] embedding = model.generateEmbedding(text);
                        
                        // 向量异常处理
                        if (embedding != null && embedding.length > 0) {
                            // 检测向量异常
                            VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(embedding, -1);
                            
                            if (anomalyResult.isAnomalous) {
                                LogManager.logW(TAG, String.format("Vector anomaly detected for chunk %d/%d: %s (severity: %.2f) - %s", 
                                        i + 1, totalChunks, anomalyResult.type.name(), anomalyResult.severity, anomalyResult.description));
                                
                                // 修复向量异常
                                float[] repairedEmbedding = VectorAnomalyHandler.repairVector(embedding, anomalyResult.type);
                                if (repairedEmbedding != null) {
                                    embedding = repairedEmbedding;
                                    LogManager.logD(TAG, String.format("Vector anomaly repaired for chunk %d/%d", i + 1, totalChunks));
                                } else {
                                    LogManager.logW(TAG, String.format("Failed to repair vector anomaly for chunk %d/%d, using original vector", i + 1, totalChunks));
                                }
                            }
                            
                            // 最终向量验证
                            VectorAnomalyHandler.AnomalyResult finalCheck = VectorAnomalyHandler.detectAnomalies(embedding, -1);
                            if (finalCheck.isAnomalous && finalCheck.severity > 0.8f) {
                                LogManager.logE(TAG, String.format("Critical vector anomaly remains after repair for chunk %d/%d: %s", 
                                        i + 1, totalChunks, finalCheck.description));
                                // 对于严重异常，生成随机单位向量作为备用
                                embedding = VectorAnomalyHandler.generateRandomUnitVector(embedding.length);
                                LogManager.logW(TAG, String.format("Generated random unit vector as fallback for chunk %d/%d", i + 1, totalChunks));
                            }
                        }
                        
                        // Add to database
                        vectorDB.addVector(text, embedding, source, metadata.toString());
                        
                        // Update progress
                        float percentage = (float) (i + 1) / totalChunks * 100;
                        if (progressCallback != null) {
                            progressCallback.onVectorizationProgress(i + 1, totalChunks, percentage);
                        }
                        
                        // Notify progress update
                        if (notificationProgressCallback != null) {
                            notificationProgressCallback.onNotificationProgressUpdate(i + 1, totalChunks, percentage);
                        }
                    } catch (Exception e) {
                        logError("Vectorization failed: " + e.getMessage(), e);
                    }
                }
                
                // Save database
                vectorDB.saveDatabase();
                logMessage("Vectorization processing completed");
                LogManager.logD(TAG, "Vectorization processing fully completed, processed " + totalChunks + " text chunks, Thread ID: " + Thread.currentThread().getId());
                
                // Notify vectorization processing completed
                if (progressCallback != null) {
                    progressCallback.onVectorizationComplete(totalChunks);
                }
            } finally {
                // Whether successful or failed, finally mark model as not in use
                modelManager.markModelNotInUse();
                LogManager.logD(TAG, "Batch vectorization processing completed, marked model as not in use");
                
                // Close database
                vectorDB.close();
                LogManager.logD(TAG, "Vector database closed");
            }
            
            return !isTaskCancelled.get();
        } catch (Exception e) {
            logError("Failed to process knowledge base: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Save intermediate text chunks to file
     */
    private void saveIntermediateChunks(String knowledgeBasePath, List<TextChunk> chunks) {
        File intermediateFile = new File(knowledgeBasePath, INTERMEDIATE_FILE_NAME);
        
        try (FileWriter writer = new FileWriter(intermediateFile)) {
            JSONArray jsonArray = new JSONArray();
            
            for (TextChunk chunk : chunks) {
                JSONObject jsonChunk = new JSONObject();
                jsonChunk.put("text", chunk.text);
                jsonChunk.put("source", chunk.source);
                jsonChunk.put("chunkIndex", chunk.chunkIndex);
                jsonChunk.put("metadata", chunk.metadata.toString());
                jsonArray.put(jsonChunk);
            }
            
            writer.write(jsonArray.toString());
            logMessage("Saved intermediate text chunks to: " + intermediateFile.getAbsolutePath());
        } catch (IOException | JSONException e) {
            logError("Failed to save intermediate text chunks: " + e.getMessage(), e);
        }
    }
    
    /**
     * Load intermediate text chunks from file
     */
    public List<TextChunk> loadIntermediateChunks(String knowledgeBasePath) {
        File intermediateFile = new File(knowledgeBasePath, INTERMEDIATE_FILE_NAME);
        List<TextChunk> chunks = new ArrayList<>();
        
        if (!intermediateFile.exists()) {
            logMessage("Intermediate file does not exist: " + intermediateFile.getAbsolutePath());
            return chunks;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(intermediateFile))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            
            JSONArray jsonArray = new JSONArray(content.toString());
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonChunk = jsonArray.getJSONObject(i);
                String text = jsonChunk.getString("text");
                String source = jsonChunk.getString("source");
                int chunkIndex = jsonChunk.getInt("chunkIndex");
                JSONObject metadata = new JSONObject(jsonChunk.getString("metadata"));
                
                chunks.add(new TextChunk(text, source, chunkIndex, metadata));
            }
            
            logMessage("Loaded " + chunks.size() + " intermediate text chunks");
        } catch (IOException | JSONException e) {
            logError("Failed to load intermediate text chunks: " + e.getMessage(), e);
        }
        
        return chunks;
    }
    
    /**
     * Check if intermediate file exists
     */
    public boolean hasIntermediateFile(String knowledgeBasePath) {
        File intermediateFile = new File(knowledgeBasePath, INTERMEDIATE_FILE_NAME);
        return intermediateFile.exists();
    }
    
    /**
     * Delete intermediate file
     */
    public void deleteIntermediateFile(String knowledgeBasePath) {
        File intermediateFile = new File(knowledgeBasePath, INTERMEDIATE_FILE_NAME);
        if (intermediateFile.exists()) {
            boolean deleted = intermediateFile.delete();
            if (deleted) {
                logMessage("Deleted intermediate file: " + intermediateFile.getAbsolutePath());
            } else {
                logMessage("Unable to delete intermediate file: " + intermediateFile.getAbsolutePath());
            }
        }
    }
    
    /**
     * Process files and build knowledge base - integrated method
     * 
     * @param knowledgeBaseName Knowledge base name
     * @param embeddingModel Embedding model name
     * @param files List of files to process
     * @param chunkSize Chunk size
     * @param chunkOverlap Overlap size
     * @return Whether successfully completed (not cancelled)
     */
    public boolean processFilesAndBuildKnowledgeBase(String knowledgeBaseName, String embeddingModel, String rerankerModel,
                                                  List<Uri> files, int chunkSize, int chunkOverlap) {
        try {
            LogManager.logD(TAG, "Starting to process files and build knowledge base, Thread ID: " + Thread.currentThread().getId() + 
                  ", Knowledge base: " + knowledgeBaseName + ", File count: " + files.size());
            
            // Get knowledge base directory path
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(context);
            String fullKnowledgeBasePath = knowledgeBasePath + File.separator + knowledgeBaseName;
            LogManager.logD(TAG, "Knowledge base directory: " + fullKnowledgeBasePath);
            logMessage("Knowledge base directory: " + fullKnowledgeBasePath);
            
            // Create knowledge base directory
            File knowledgeBaseDir = new File(fullKnowledgeBasePath);
            if (!knowledgeBaseDir.exists()) {
                boolean created = knowledgeBaseDir.mkdirs();
                if (!created) {
                    throw new Exception("Unable to create knowledge base directory: " + fullKnowledgeBasePath);
                }
            }
            
            // Get embedding model path
            String embeddingModelPath = ConfigManager.getEmbeddingModelPath(context) + File.separator + embeddingModel;
            logMessage("Using embedding model: " + embeddingModelPath);
            
            // Use model manager to get model
            EmbeddingModelHandler model = EmbeddingModelManager.getInstance(context).getModel(embeddingModelPath);
            logMessage("Loaded embedding model: " + model.getModelName());
            
            // Get model's vector dimension
            int embeddingDimension = model.getEmbeddingDimension();
            LogManager.logD(TAG, "Model embedding dimension: " + embeddingDimension);
            logMessage("Model embedding dimension: " + embeddingDimension);
            
            // Initialize TokenizerManager
            TokenizerManager tokenizerManager = TokenizerManager.getInstance(context);
            if (!tokenizerManager.isInitialized()) {
                logMessage("Initializing global tokenizer...");
                boolean initSuccess = tokenizerManager.initialize(model.getModelPath());
                if (initSuccess) {
                    logMessage("Global tokenizer initialized successfully, will use unified tokenization strategy");
                    // Enable consistent tokenization
                    tokenizerManager.setUseConsistentTokenization(true);
                    
                    // Set debug mode
                    boolean debugMode = ConfigManager.isDebugMode(context);
                    if (debugMode) {
                        tokenizerManager.setDebugMode(true);
                        logMessage("Global tokenizer debug mode enabled");
                    }
                } else {
                    logMessage("Global tokenizer initialization failed, will use model's built-in tokenizer");
                }
            } else {
                logMessage("Using already initialized global tokenizer");
                // Enable consistent tokenization
                tokenizerManager.setUseConsistentTokenization(true);
                
                // Set debug mode
                boolean debugMode = ConfigManager.isDebugMode(context);
                if (debugMode) {
                    tokenizerManager.setDebugMode(true);
                }
            }
            
            // Enable consistent tokenization strategy
            model.setUseConsistentProcessing(true);
            logMessage("Enabled consistent tokenization strategy to ensure knowledge base construction and query use the same tokenization logic");
            
            // Whether to enable debug mode
            boolean debugMode = ConfigManager.isDebugMode(context);
            if (debugMode) {
                model.setDebugMode(true);
                logMessage("Debug mode enabled, will output detailed tokenization and processing logs");
            }
            
            // Initialize vector database
            SQLiteVectorDatabaseHandler vectorDB = new SQLiteVectorDatabaseHandler(
                    new File(fullKnowledgeBasePath), model.getEmbeddingModel(), embeddingDimension);
            
            // Set embedding model directory to metadata
            vectorDB.getMetadata().setModeldir(embeddingModel);
            logMessage("Set embedding model directory: " + embeddingModel);
            
            // Set reranker model information to metadata
            String valueNone = context.getString(R.string.common_none);
            if (rerankerModel != null && !rerankerModel.isEmpty() && !valueNone.equals(rerankerModel)) {
                vectorDB.getMetadata().setRerankerdir(rerankerModel);
                logMessage("Set reranker model: " + rerankerModel);
            }
            
            // Extract text and chunk
            List<TextChunk> chunks = extractTextFromFiles(fullKnowledgeBasePath, files, chunkSize, chunkOverlap);
            
            // Check if cancelled
            if (isTaskCancelled.get()) {
                logMessage("Task cancelled");
                vectorDB.close();
                return false;
            }
            
            // 通知文本提取完成
            if (progressCallback != null) {
                progressCallback.onTextExtractionComplete(chunks.size());
            }
            
            // Get model manager instance
            EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(context);
            
            // Mark model as in use before vectorization processing starts to prevent automatic unloading during vectorization
            modelManager.markModelInUse();
            LogManager.logD(TAG, "Starting batch vectorization processing, marked model as in use to prevent automatic unloading");
            
            try {
                // Generate vectors and add to database
                int totalChunks = chunks.size();
                logMessage("Starting vectorization processing, total " + totalChunks + " text chunks");
                
                // Vectorization processing
                for (int i = 0; i < totalChunks; i++) {
                    // Check if task is cancelled
                    if (isTaskCancelled.get()) {
                        logMessage("Task cancelled");
                        LogManager.logD(TAG, "Vectorization processing interrupted: task cancelled, processed " + i + "/" + totalChunks + " text chunks");
                        vectorDB.close();
                        return false;
                    }
                    
                    TextChunk chunk = chunks.get(i);
                    String text = chunk.text;
                    String source = chunk.source;
                    JSONObject metadata = chunk.metadata;
                    
                    try {
                        // Log every 100 text chunks
                        if (i % 100 == 0 || i == totalChunks - 1) {
                            LogManager.logD(TAG, "Vectorization progress: " + i + "/" + totalChunks + 
                                  ", Thread ID: " + Thread.currentThread().getId() + 
                                  ", Source file: " + source);
                        }
                        
                        // Generate vector
                        float[] embedding = model.generateEmbedding(text);
                        
                        // Add to database
                        vectorDB.addVector(text, embedding, source, metadata.toString());
                        
                        // Update progress
                        float percentage = (float) (i + 1) / totalChunks * 100;
                        if (progressCallback != null) {
                            progressCallback.onVectorizationProgress(i + 1, totalChunks, percentage);
                        }
                        
                        // Notify progress update
                        if (notificationProgressCallback != null) {
                            notificationProgressCallback.onNotificationProgressUpdate(i + 1, totalChunks, percentage);
                        }
                    } catch (Exception e) {
                        logError("Vectorization failed: " + e.getMessage(), e);
                    }
                }
                
                // Save database
                vectorDB.saveDatabase();
                logMessage("Vectorization processing completed");
                LogManager.logD(TAG, "Vectorization processing fully completed, processed " + totalChunks + " text chunks, Thread ID: " + Thread.currentThread().getId());
                
                // Notify vectorization processing completed
                if (progressCallback != null) {
                    progressCallback.onVectorizationComplete(totalChunks);
                }
            } finally {
                // Whether successful or failed, finally mark model as not in use
                modelManager.markModelNotInUse();
                LogManager.logD(TAG, "Batch vectorization processing completed, marked model as not in use");
                
                // Close database
                vectorDB.close();
                LogManager.logD(TAG, "Vector database closed");
            }
            
            return !isTaskCancelled.get();
        } catch (Exception e) {
            logError("Failed to process knowledge base: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Extract value from JSON string
     * For example, extract "Hello" from "instruction": "Hello"
     */
    private String extractValue(String jsonLine) {
        try {
            int colonPos = jsonLine.indexOf(':');
            if (colonPos < 0) return "";
            
            String valueStr = jsonLine.substring(colonPos + 1).trim();
            
            // If value is enclosed in quotes
            if (valueStr.startsWith("\"") && valueStr.indexOf("\"", 1) > 0) {
                int endQuote = valueStr.lastIndexOf("\"");
                if (endQuote > 0) {
                    return valueStr.substring(1, endQuote);
                }
            }
            
            // Handle case where value might have comma at the end
            if (valueStr.endsWith(",")) {
                valueStr = valueStr.substring(0, valueStr.length() - 1);
            }
            
            // Remove possible quotes
            if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
                valueStr = valueStr.substring(1, valueStr.length() - 1);
            }
            
            return valueStr;
        } catch (Exception e) {
            logError("Error extracting JSON value: " + e.getMessage(), e);
            return "";
        }
    }
    
    /**
     * Log message
     */
    private void logMessage(String message) {
        LogManager.logD(TAG, message);
        if (progressCallback != null) {
            progressCallback.onLog(message);
        }
    }
    
    /**
     * Log error message
     */
    private void logError(String message, Exception e) {
        LogManager.logE(TAG, message, e);
        if (progressCallback != null) {
            progressCallback.onError(message);
        }
    }
}
