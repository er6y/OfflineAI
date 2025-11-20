package com.example.offlineai;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.example.offlineai.LogManager;
import com.example.offlineai.KnowledgeGraphDatabase;
import com.example.offlineai.HanLpNerHandler;
import com.offlineai.mnn.MnnInference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text chunk processor
 * Responsible for two-stage processing of text extraction and vectorization
 */
public class TextChunkProcessor {
    private static final String TAG = "OfflineAI_TextChunk";
    
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
    
    // Knowledge Graph components (TODO: Phase 6 - Re-implement with LLM NER)
    // private EntityRecognizer entityRecognizer;
    private KnowledgeGraphDatabase graphDatabase;
    
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
     * Write knowledge base metadata to SQLite metadata table and metadata.json file
     */
    private void writeKnowledgeBaseMetadata(String fullKnowledgeBasePath,
                                            String knowledgeBaseName,
                                            String embeddingModel,
                                            String rerankerModel,
                                            int embeddingDimension) {
        try {
            logMessage("Writing knowledge base metadata for: " + knowledgeBaseName);

            // 1) Update SQLite metadata via KnowledgeGraphDatabase
            KnowledgeGraphDatabase metaDb = null;
            try {
                String dbPath = fullKnowledgeBasePath + File.separator + "knowledge_graph.db";
                metaDb = new KnowledgeGraphDatabase(context, dbPath, knowledgeBaseName);

                KnowledgeGraphDatabase.DatabaseMetadata metadata =
                        new KnowledgeGraphDatabase.DatabaseMetadata(embeddingModel);
                metadata.setEmbeddingDimension(embeddingDimension);
                metadata.setModeldir(embeddingModel);
                if (rerankerModel != null && !rerankerModel.isEmpty()) {
                    metadata.setRerankerdir(rerankerModel);
                }

                boolean updated = metaDb.updateMetadata(metadata);
                if (updated) {
                    LogManager.logD(TAG, "Knowledge base DB metadata updated successfully");
                } else {
                    LogManager.logW(TAG, "Failed to update knowledge base DB metadata");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Error updating DB metadata: " + e.getMessage(), e);
            } finally {
                if (metaDb != null) {
                    try {
                        metaDb.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error closing metadata DB: " + e.getMessage(), e);
                    }
                }
            }

            // 2) Write metadata.json for compatibility
            try {
                File kbDir = new File(fullKnowledgeBasePath);
                File jsonMetadataFile = new File(kbDir, "metadata.json");

                JSONObject json = new JSONObject();
                json.put("knowledgeBase", knowledgeBaseName);
                json.put("embeddingModel", embeddingModel);
                json.put("modeldir", embeddingModel);
                if (rerankerModel != null && !rerankerModel.isEmpty()) {
                    json.put("rerankerModel", rerankerModel);
                }
                json.put("embeddingDimension", embeddingDimension);
                json.put("updated", System.currentTimeMillis());

                FileWriter writer = null;
                try {
                    writer = new FileWriter(jsonMetadataFile, false);
                    writer.write(json.toString());
                    writer.flush();
                    LogManager.logD(TAG, "metadata.json written successfully: " + jsonMetadataFile.getAbsolutePath());
                } finally {
                    if (writer != null) {
                        try {
                            writer.close();
                        } catch (IOException e) {
                            LogManager.logE(TAG, "Error closing metadata.json writer: " + e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Error writing metadata.json: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Unexpected error in writeKnowledgeBaseMetadata: " + e.getMessage(), e);
        }
    }

    /**
     * Chunk processing result (for serial execution)
     */
    private static class ChunkProcessResult {
        public float[] embedding = null;
        public List<HanLpNerHandler.NerResult.Entity> entities = new ArrayList<>();
        public long embeddingTime = 0;
        public long nerTime = 0;
        public TextChunk chunk;
        public int chunkIndex;
        public long chunkStartTime = 0;
    }
    
    /**
     * Progress callback interface
     */
    public interface ProgressCallback {
        void onTextExtractionProgress(int processedFiles, int totalFiles, String currentFile);
        void onVectorizationProgress(int processedChunks, int totalChunks, float percentage);
        void onTextExtractionComplete(int totalChunks);
        void onVectorizationComplete(int totalVectors);
        void onGraphBuildingProgress(int processedChunks, int totalChunks, float percentage);
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
        
        // Initialize Knowledge Graph components
        initializeKnowledgeGraph();
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
     * Process file list (DEPRECATED - use processFilesAndBuildKnowledgeBase instead)
     * @deprecated Use processFilesAndBuildKnowledgeBase for unified processing
     */
    @Deprecated
    public boolean processFiles(String knowledgeBasePath, List<Uri> files, int chunkSize, int chunkOverlap, 
                               EmbeddingHandler embeddingModel, Object vectorDB) {
        throw new UnsupportedOperationException("Deprecated - use processFilesAndBuildKnowledgeBase instead");
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
                    if (isJson || isSpecialDataset) {
                        // Send JSON-related status to UI so that special datasets and real JSON files are visible
                        logMessage(jsonStatusMessage);
                    } else {
                        // For non-JSON files, keep this information only in debug log to avoid UI noise
                        LogManager.logD(TAG, jsonStatusMessage);
                    }
                    
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
                            LogManager.logD(TAG, "JSON optimization disabled, will use standard chunking");
                        }
                    } else {
                        LogManager.logD(TAG, "JSON optimization disabled, will use standard chunking");
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
                        LogManager.logD(TAG, "Using standard chunking, chunk size: " + chunkSize + ", overlap size: " + chunkOverlap);
                        chunks = splitTextIntoChunks(text, chunkSize, chunkOverlap);
                        fileChunkCount = chunks.size();
                    }
                    
                    // Record the number of text chunks generated by this file (merged with text size for concise UI output)
                    int textSizeKb = textLength / 1024;
                    logMessage("File: " + fileName + " extracted text size: " + textSizeKb + "KB, generated " + fileChunkCount + " text chunks");
                    
                    // Warn if chunk count is abnormally high (may indicate formatting issues)
                    if (fileChunkCount > 1000) {
                        logMessage("Warning: File " + fileName + " generated " + fileChunkCount + 
                                 " chunks (text size: " + (textLength / 1024) + "KB). " +
                                 "This may indicate the document contains excessive formatting, tables, or repeated content.");
                    }
                    
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
     * Process chunks to vectors (DEPRECATED - integrated into processFilesAndBuildKnowledgeBase)
     * @deprecated Use processFilesAndBuildKnowledgeBase for unified processing
     */
    @Deprecated
    private boolean processChunksToVectors(List<TextChunk> chunks, EmbeddingHandler model, Object vectorDB) {
        throw new UnsupportedOperationException("Deprecated - use processFilesAndBuildKnowledgeBase instead");
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
            LogManager.logD(TAG, "Using embedding model: " + embeddingModelPath);
            
            // Use EmbeddingHandler to load model (LOW memory mode) only for dimension probing
            EmbeddingHandler model = EmbeddingHandler.getInstance(context);
            if (!model.loadModel(embeddingModelPath, EmbeddingHandler.MemoryMode.LOW)) {
                throw new Exception("Failed to load embedding model");
            }
            logMessage("Loaded embedding model with LOW memory mode");
            logMessage("Loaded embedding model: " + model.getEmbeddingModel());
            
            // Get model's vector dimension
            int embeddingDimension = model.getEmbeddingDimension();
            LogManager.logD(TAG, "Model embedding dimension: " + embeddingDimension);
            logMessage("Model embedding dimension: " + embeddingDimension);

            // Release EmbeddingHandler model to avoid extra native session during knowledge base building
            try {
                model.releaseModel();
                LogManager.logI(TAG, "[EMBEDDING_KB] Released EmbeddingHandler model after reading dimension");
            } catch (Exception e) {
                LogManager.logE(TAG, "[EMBEDDING_KB] Failed to release EmbeddingHandler model", e);
            }
            
            // MNN embedding has built-in tokenizer, no external tokenizer needed
            logMessage("Using MNN built-in tokenizer for consistent embedding generation");
            
            // Initialize knowledge graph database (unified storage for vectors + entities + graph)
            String graphDbPath = fullKnowledgeBasePath + File.separator + "knowledge_graph.db";
            File graphDbFile = new File(graphDbPath);
            boolean graphDbExistedBefore = graphDbFile.exists();
            KnowledgeGraphDatabase graphDB = new KnowledgeGraphDatabase(context, graphDbPath, knowledgeBaseName);

            // Load graph stopwords and hub threshold config
            String stopwordsPath = ConfigManager.getGraphStopwordsPath(context);
            int hubThreshold = ConfigManager.getGraphHubThresholdBuild(context);
            GraphStopwordsMatcher stopwordsMatcher = null;
            if (stopwordsPath != null && !stopwordsPath.isEmpty()) {
                try {
                    stopwordsMatcher = GraphStopwordsMatcher.loadFromFile(stopwordsPath);
                    File stopFile = new File(stopwordsPath);
                    logMessage("[STOPWORDS] Loaded graph stopwords file: " + stopFile.getName()
                            + " (exact=" + stopwordsMatcher.getExactCount()
                            + ", prefix=" + stopwordsMatcher.getPrefixCount()
                            + ", regex=" + stopwordsMatcher.getRegexCount() + ")");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[STOPWORDS] Failed to load graph stopwords file: " + stopwordsPath, e);
                    logMessage("Warning: Failed to load graph stopwords file: " + stopwordsPath);
                }
            } else {
                LogManager.logD(TAG, "[STOPWORDS] No graph stopwords file configured");
            }
            
            // Extract text and chunk
            List<TextChunk> chunks = extractTextFromFiles(fullKnowledgeBasePath, files, chunkSize, chunkOverlap);
            
            // Check if cancelled
            if (isTaskCancelled.get()) {
                logMessage("Task cancelled");
                graphDB.close();
                return false;
            }
            
            // 通知文本提取完成
            if (progressCallback != null) {
                progressCallback.onTextExtractionComplete(chunks.size());
            }
            
            // Initialize NER handler (always enabled; custom dictionary is optional)
            String dictPath = ConfigManager.getString(context, ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null);
            String valueNoneGraph = context.getString(R.string.common_none);

            String customDictPath = null;
            if (dictPath != null && !dictPath.isEmpty() && !valueNoneGraph.equals(dictPath)) {
                customDictPath = dictPath;
            }

            boolean nerEnabled = true;

            final HanLpNerHandler nerHandler = new HanLpNerHandler(customDictPath);

            // Report dictionary status to UI via logMessage (ProgressCallback.onLog)
            if (customDictPath == null) {
                // No custom dictionary selected
                logMessage("Dictionary: None");
            } else {
                String dictFileName = new File(customDictPath).getName();
                if (nerHandler.isDictionaryLoaded()) {
                    int wordCount = nerHandler.getLoadedWordCount();
                    logMessage("Dictionary: " + dictFileName + " (loaded " + wordCount + " words)");
                } else {
                    logMessage("Dictionary: " + dictFileName);
                    String dictError = nerHandler.getDictionaryErrorMessage();
                    if (dictError != null && !dictError.isEmpty()) {
                        logMessage("Dictionary load error: " + dictError);
                    } else {
                        logMessage("Dictionary load error: Unknown error");
                    }
                }
            }

            LogManager.logD(TAG, "Starting unified knowledge base building (NER: " + (nerEnabled ? "ON" : "OFF") + ")");

            // Initialize in-memory graph builder for entities/edges (inject protected entities from custom dictionary)
            final InMemoryGraphBuilder inMemoryGraphBuilder = new InMemoryGraphBuilder(nerHandler.getCustomDictionaryWords());
            LogManager.logI(TAG, "[GRAPH_MEM] Initialized in-memory graph builder");

            // If graph DB already exists (append mode), preload existing graph into memory
            if (graphDbExistedBefore) {
                try {
                    LogManager.logI(TAG, "[GRAPH_MEM] Existing graph DB detected, loading graph into memory for incremental build");
                    SQLiteDatabase preloadDb = graphDB.getReadableDatabase();
                    inMemoryGraphBuilder.loadFromDatabase(preloadDb, knowledgeBaseName);
                } catch (Exception e) {
                    LogManager.logE(TAG, "[GRAPH_MEM] Failed to preload existing graph from DB: " + e.getMessage(), e);
                }
            } else {
                LogManager.logI(TAG, "[GRAPH_MEM] No existing graph DB found, starting with empty in-memory graph");
            }

            // Determine embedding concurrency for knowledge base building
            int embeddingConcurrency = ConfigManager.getEmbeddingConcurrency(context);
            if (embeddingConcurrency < 1) {
                embeddingConcurrency = 1;
            }
            if (embeddingConcurrency > 4) {
                embeddingConcurrency = 4;
            }
            LogManager.logI(TAG, "[EMBEDDING_KB] Embedding concurrency: " + embeddingConcurrency + " sessions");

            // Determine MNN threads per embedding session for knowledge base building
            int embeddingThreads = ConfigManager.getEmbeddingThreads(context);
            if (embeddingThreads < 1) {
                embeddingThreads = 1;
            }
            if (embeddingThreads > 4) {
                embeddingThreads = 4;
            }
            LogManager.logI(TAG, "[EMBEDDING_KB] Embedding threads per session: " + embeddingThreads);

            // Build runtime config for embedding sessions
            String backendPreference = SettingsFragment.getBackendPreference(context);
            String mnnBackend;
            switch (backendPreference) {
                case "OPENCL":
                    mnnBackend = "opencl";
                    break;
                case "VULKAN":
                    mnnBackend = "vulkan";
                    break;
                case "NNAPI":
                    mnnBackend = "npu"; // MNN uses "npu" for Android NNAPI
                    break;
                case "CPU":
                default:
                    mnnBackend = "cpu";
                    break;
            }
            LogManager.logI(TAG, "[EMBEDDING_KB] Backend mapping: '" + backendPreference + "' -> '" + mnnBackend + "', thread_num=" + embeddingThreads);

            final String runtimeConfig = new MnnInference.ConfigBuilder()
                .backendType(mnnBackend)
                .memory(EmbeddingHandler.MemoryMode.LOW.getValue())
                .power("high")
                .precision("low")
                .threadNum(embeddingThreads)
                .build();

            LogManager.logD(TAG, "[EMBEDDING_KB] Built runtime config for knowledge base embedding sessions");

            // Shared model directory for all embedding sessions
            final String embeddingModelDir = embeddingModelPath;

            // Track all embedding session handles for cleanup
            final List<Long> embeddingHandles = Collections.synchronizedList(new ArrayList<>());

            // Thread-local embedding session handle: one session per worker thread
            final ThreadLocal<Long> embeddingHandleThreadLocal = new ThreadLocal<Long>() {
                @Override
                protected Long initialValue() {
                    long handle = 0L;
                    try {
                        LogManager.logI(TAG, "[EMBEDDING_KB] Creating embedding session for worker thread...");
                        handle = MnnInference.createEmbeddingWithConfig(embeddingModelDir, runtimeConfig);
                        LogManager.logI(TAG, "[EMBEDDING_KB] Created embedding session handle=" + handle);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[EMBEDDING_KB] Failed to create embedding session for worker thread", e);
                    }
                    if (handle != 0L) {
                        embeddingHandles.add(handle);
                    }
                    return handle;
                }
            };

            // Create ExecutorService for embedding concurrency
            ExecutorService executorService = Executors.newFixedThreadPool(embeddingConcurrency);
            LogManager.logI(TAG, "[EXECUTOR] Created embedding executor with concurrency=" + embeddingConcurrency);
            
            // Begin database transaction for batch insert
            graphDB.getWritableDatabase().beginTransaction();
            
            try {
                int totalChunks = chunks.size();
                logMessage("Starting unified processing: " + totalChunks + " chunks" + (nerEnabled ? " with NER" : ""));
                int lastLoggedPercent = -5;
                if (totalChunks > 0) {
                    // Initial 0% milestone for vectorization progress
                    logMessage("Vectorization progress: 0% (0/" + totalChunks + ")");
                    lastLoggedPercent = 0;
                }

                // Process each chunk: embedding in parallel, NER + DB sequentially
                LogManager.logI(TAG, String.format("[LOOP] Starting chunk processing loop with embedding concurrency=%d: %d chunks total", embeddingConcurrency, totalChunks));

                List<Future<ChunkProcessResult>> pendingFutures = new ArrayList<>();
                int processedChunks = 0;

                outerLoop:
                for (int i = 0; i < totalChunks; i++) {
                    LogManager.logD(TAG, String.format("[LOOP] ========== Iteration %d/%d START ==========", i + 1, totalChunks));
                    if (isTaskCancelled.get()) {
                        logMessage("Task cancelled");
                        LogManager.logD(TAG, "Processing interrupted: cancelled before submitting chunk " + (i + 1) + "/" + totalChunks);
                        break;
                    }

                    TextChunk chunk = chunks.get(i);
                    LogManager.logI(TAG, String.format("[LOOP] Chunk %d/%d: source=%s, textLen=%d", i + 1, totalChunks, chunk.source, chunk.text.length()));

                    final TextChunk chunkForTask = chunk;
                    final int chunkIndex = i + 1;

                    LogManager.logI(TAG, String.format("[SUBMIT] About to submit chunk %d embedding task to executor...", chunkIndex));
                    Future<ChunkProcessResult> future = executorService.submit(() -> {
                        LogManager.logI(TAG, String.format("[TASK %d] Embedding task started in executor, Thread: %s", chunkIndex, Thread.currentThread().getName()));
                        ChunkProcessResult result = new ChunkProcessResult();
                        result.chunk = chunkForTask;
                        result.chunkIndex = chunkIndex;
                        result.chunkStartTime = System.currentTimeMillis();

                        // Acquire or create thread-local embedding session
                        long handle = embeddingHandleThreadLocal.get();
                        if (handle == 0L) {
                            try {
                                LogManager.logI(TAG, "[EMBEDDING_KB] Thread-local handle is 0, retrying createEmbeddingWithConfig...");
                                handle = MnnInference.createEmbeddingWithConfig(embeddingModelDir, runtimeConfig);
                                if (handle != 0L) {
                                    embeddingHandles.add(handle);
                                    embeddingHandleThreadLocal.set(handle);
                                    LogManager.logI(TAG, "[EMBEDDING_KB] Retried and created embedding session handle=" + handle);
                                }
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[EMBEDDING_KB] Failed to create embedding session on retry", e);
                            }
                        }

                        if (handle == 0L) {
                            throw new IllegalStateException("Embedding session handle is 0");
                        }

                        long embeddingStart = System.currentTimeMillis();
                        LogManager.logD(TAG, String.format("[CHUNK %d] Starting embedding in worker thread (handle=%d)...", chunkIndex, handle));
                        float[] embedding = MnnInference.computeEmbedding(handle, chunkForTask.text);
                        long embeddingTime = System.currentTimeMillis() - embeddingStart;
                        result.embedding = embedding;
                        result.embeddingTime = embeddingTime;
                        LogManager.logI(TAG, String.format("[CHUNK %d] Embedding completed: %dms", chunkIndex, embeddingTime));

                        return result;
                    });

                    pendingFutures.add(future);

                    // If batch is full or this is the last chunk, process pending futures: NER + DB sequentially
                    if (pendingFutures.size() >= embeddingConcurrency || i == totalChunks - 1) {
                        for (Future<ChunkProcessResult> futureResult : pendingFutures) {
                            if (isTaskCancelled.get()) {
                                logMessage("Task cancelled");
                                LogManager.logD(TAG, "Processing interrupted: cancelled during batch consumption");
                                break outerLoop;
                            }

                            ChunkProcessResult result;
                            try {
                                result = futureResult.get();
                            } catch (Exception e) {
                                processedChunks++;
                                logError("Failed to process chunk " + processedChunks + ": " + e.getMessage(), e);
                                LogManager.logE(TAG, "[LOOP] Chunk embedding failed, continuing to next", e);
                                continue;
                            }

                            processedChunks++;

                            TextChunk processedChunk = result.chunk;
                            int chunkIndexForLog = result.chunkIndex;
                            float[] embedding = result.embedding;

                            // NER on main thread (single-threaded)
                            List<HanLpNerHandler.NerResult.Entity> entities = new ArrayList<>();
                            long nerTime = 0L;
                            if (nerEnabled && nerHandler != null) {
                                long nerStart = System.currentTimeMillis();
                                LogManager.logD(TAG, String.format("[CHUNK %d] Starting NER...", chunkIndexForLog));
                                try {
                                    HanLpNerHandler.NerResult nerResult = nerHandler.extractEntities(processedChunk.text);
                                    nerTime = System.currentTimeMillis() - nerStart;
                                    result.nerTime = nerTime;

                                    if (nerResult != null && nerResult.isSuccess()) {
                                        entities = nerResult.getEntities();
                                        LogManager.logI(TAG, String.format("[CHUNK %d] NER completed: %dms, entities=%d",
                                            chunkIndexForLog, nerTime, entities.size()));
                                    } else {
                                        LogManager.logW(TAG, String.format("[CHUNK %d] NER failed", chunkIndexForLog));
                                    }
                                } catch (Exception e) {
                                    LogManager.logE(TAG, String.format("[CHUNK %d] NER error: %s", chunkIndexForLog, e.getMessage()), e);
                                    // NER failure is not fatal, continue with empty entities
                                }
                            }

                            // Stopwords filtering
                            if (stopwordsMatcher != null && entities != null && !entities.isEmpty()) {
                                List<HanLpNerHandler.NerResult.Entity> filteredEntities = new ArrayList<>();
                                int filteredCount = 0;
                                for (HanLpNerHandler.NerResult.Entity entity : entities) {
                                    if (stopwordsMatcher.matches(entity.text)) {
                                        filteredCount++;
                                    } else {
                                        filteredEntities.add(entity);
                                    }
                                }
                                if (filteredCount > 0) {
                                    LogManager.logD(TAG, String.format(
                                            "[STOPWORDS] Chunk %d: filtered %d entities by stopwords (original=%d, remaining=%d)",
                                            chunkIndexForLog, filteredCount, entities.size(), filteredEntities.size()));
                                }
                                entities = filteredEntities;
                            }

                            // Alias normalization based on custom dictionary (canonical form for graph)
                            if (nerHandler != null && entities != null && !entities.isEmpty()) {
                                List<HanLpNerHandler.NerResult.Entity> normalizedEntities = new ArrayList<>();
                                int aliasNormalizedCount = 0;
                                for (HanLpNerHandler.NerResult.Entity entity : entities) {
                                    if (entity == null || entity.text == null) {
                                        continue;
                                    }
                                    String baseText = entity.text;
                                    String normalizedText = nerHandler.normalizeTextForGraph(baseText);
                                    if (normalizedText == null) {
                                        continue;
                                    }
                                    if (!normalizedText.equals(baseText)) {
                                        aliasNormalizedCount++;
                                    }
                                    HanLpNerHandler.NerResult.Entity normalizedEntity =
                                            new HanLpNerHandler.NerResult.Entity(normalizedText, entity.type, entity.start, entity.end, entity.confidence);
                                    normalizedEntities.add(normalizedEntity);
                                }
                                if (!normalizedEntities.isEmpty()) {
                                    entities = normalizedEntities;
                                    if (aliasNormalizedCount > 0) {
                                        LogManager.logD(TAG, String.format(
                                                "[GRAPH_ALIAS] Chunk %d: normalized %d entities by alias map (after stopwords)",
                                                chunkIndexForLog, aliasNormalizedCount));
                                    }
                                } else {
                                    entities = normalizedEntities;
                                }
                            }

                            long dbStartTime = System.currentTimeMillis();
                            LogManager.logD(TAG, String.format("[DB] Chunk %d: Saving document to database...", chunkIndexForLog));
                            long docId = graphDB.addChunk(processedChunk.text, processedChunk.source, embedding, processedChunk.metadata.toString());
                            long dbTime = System.currentTimeMillis() - dbStartTime;
                            LogManager.logD(TAG, String.format("[DB] Chunk %d: Saved docId=%d (took %dms)", chunkIndexForLog, docId, dbTime));

                            // Build graph structure in memory instead of writing entities/edges directly to SQLite
                            long graphStartTime = System.currentTimeMillis();
                            if (!entities.isEmpty()) {
                                LogManager.logD(TAG, String.format(
                                        "[GRAPH_MEM] Chunk %d: Adding %d entities and co-occurrence edges to in-memory graph...",
                                        chunkIndexForLog, entities.size()));
                            }
                            // Always register the chunk for in-memory graph statistics (even if there are no entities)
                            inMemoryGraphBuilder.addChunk(docId, entities);
                            long graphTime = System.currentTimeMillis() - graphStartTime;
                            LogManager.logD(TAG, String.format(
                                    "[GRAPH_MEM] Chunk %d: In-memory graph updated (took %dms)",
                                    chunkIndexForLog, graphTime));

                            long totalTime = System.currentTimeMillis() - result.chunkStartTime;
                            LogManager.logI(TAG, String.format("[SUMMARY] Chunk %d/%d: total=%dms (embed=%dms, ner=%dms, db=%dms), entities=%d",
                                chunkIndexForLog, totalChunks, totalTime, result.embeddingTime, nerTime, dbTime, entities.size()));

                            // Update progress based on completed chunks
                            float percentage = (float) processedChunks / totalChunks * 100.0f;
                            if (progressCallback != null) {
                                progressCallback.onVectorizationProgress(processedChunks, totalChunks, percentage);
                            }
                            if (notificationProgressCallback != null) {
                                notificationProgressCallback.onNotificationProgressUpdate(processedChunks, totalChunks, percentage);
                            }

                            // Milestone-based progress logs for UI (every 5%)
                            int currentPercent = Math.round(percentage);
                            int milestone = (currentPercent / 5) * 5;
                            if (milestone < 0) {
                                milestone = 0;
                            } else if (milestone > 100) {
                                milestone = 100;
                            }
                            if (milestone >= 0 && milestone <= 100 && milestone > lastLoggedPercent) {
                                logMessage("Vectorization progress: " + milestone + "% (" + processedChunks + "/" + totalChunks + ")");
                                lastLoggedPercent = milestone;
                            }

                            LogManager.logD(TAG, String.format("[LOOP] ========== Iteration %d/%d END ==========", chunkIndexForLog, totalChunks));
                        }

                        pendingFutures.clear();
                    }
                }
                
                LogManager.logI(TAG, "[LOOP] Chunk processing loop completed");
                inMemoryGraphBuilder.logSummary();
                
                // Commit transaction if not cancelled
                if (!isTaskCancelled.get()) {
                    graphDB.getWritableDatabase().setTransactionSuccessful();
                    logMessage("Unified processing completed: " + totalChunks + " chunks");
                    LogManager.logI(TAG, "Knowledge base building completed successfully");
                } else {
                    logMessage("Processing cancelled, changes rolled back");
                    LogManager.logW(TAG, "Knowledge base building cancelled");
                }
                
                if (progressCallback != null) {
                    progressCallback.onVectorizationComplete(totalChunks);
                }
                
            } finally {
                // Shutdown ExecutorService
                if (executorService != null) {
                    try {
                        executorService.shutdown();
                        if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                            executorService.shutdownNow();
                        }
                        LogManager.logD(TAG, "ExecutorService shut down");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error shutting down ExecutorService: " + e.getMessage(), e);
                        executorService.shutdownNow();
                    }
                }

                // Release embedding sessions created for knowledge base building
                if (embeddingHandles != null && !embeddingHandles.isEmpty()) {
                    for (Long handle : embeddingHandles) {
                        if (handle != null && handle != 0L) {
                            try {
                                MnnInference.releaseEmbedding(handle);
                                LogManager.logD(TAG, "[EMBEDDING_KB] Released embedding session handle=" + handle);
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[EMBEDDING_KB] Failed to release embedding session handle=" + handle, e);
                            }
                        }
                    }
                }
                
                // End transaction (commit or rollback)
                try {
                    graphDB.getWritableDatabase().endTransaction();
                    LogManager.logD(TAG, "Database transaction ended");
                } catch (Exception e) {
                    LogManager.logE(TAG, "Error ending transaction: " + e.getMessage(), e);
                }
                
                // Cleanup resources
                LogManager.logD(TAG, "Cleaning up resources");
                
                if (graphDB != null) {
                    try {
                        graphDB.close();
                        LogManager.logD(TAG, "Graph database closed");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error closing database: " + e.getMessage(), e);
                    }
                }
                
                if (nerHandler != null) {
                    try {
                        nerHandler.release();
                        LogManager.logD(TAG, "NER handler released");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error releasing NER handler: " + e.getMessage(), e);
                    }
                }
            }

            // After successful processing, apply in-memory hub filtering and persist graph/metadata
            if (!isTaskCancelled.get()) {
                // Apply in-memory hub threshold filtering if enabled
                if (hubThreshold > 0) {
                    InMemoryGraphBuilder.HubFilterProgressListener hubListener =
                            new InMemoryGraphBuilder.HubFilterProgressListener() {
                        @Override
                        public void onHubFilteringStarted(int totalHubEntities) {
                            if (totalHubEntities <= 0) {
                                logMessage("Graph hub filtering started: no hub entities found for threshold=" + hubThreshold);
                            } else {
                                logMessage("Graph hub filtering started: threshold=" + hubThreshold +
                                        ", candidate hub entities=" + totalHubEntities);
                                if (progressCallback != null) {
                                    // Report initial graph building progress (0%)
                                    progressCallback.onGraphBuildingProgress(0, totalHubEntities, 0.0f);
                                }
                            }
                        }

                        @Override
                        public void onHubFilteringProgress(int processedHubEntities, int totalHubEntities) {
                            if (progressCallback != null && totalHubEntities > 0) {
                                float percentage = (float) processedHubEntities / (float) totalHubEntities * 100.0f;
                                progressCallback.onGraphBuildingProgress(processedHubEntities, totalHubEntities, percentage);
                            }
                        }

                        @Override
                        public void onHubFilteringCompleted(int removedHubEntities, long durationMs) {
                            logMessage("Graph hub filtering completed: removed hub entities=" + removedHubEntities +
                                    ", time=" + durationMs + "ms");
                        }
                    };

                    int removed = inMemoryGraphBuilder.applyHubFilter(hubThreshold, hubListener);
                    logMessage("Graph hub filter applied (in-memory): threshold=" + hubThreshold + ", removed hub entities: " + removed);
                } else {
                    LogManager.logD(TAG, "[HUB_FILTER] Hub threshold is disabled (<=0), skipping hub filtering");
                }

                // Flush final in-memory graph to SQLite
                try {
                    KnowledgeGraphDatabase flushDb = new KnowledgeGraphDatabase(context, graphDbPath, knowledgeBaseName);
                    SQLiteDatabase writable = flushDb.getWritableDatabase();
                    inMemoryGraphBuilder.flushToDatabase(writable, knowledgeBaseName);
                    try {
                        flushDb.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[GRAPH_MEM] Failed to close graph DB after flush: " + e.getMessage(), e);
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[GRAPH_MEM] Failed to flush in-memory graph to DB: " + e.getMessage(), e);
                    logMessage("Warning: Failed to flush in-memory graph to DB: " + e.getMessage());
                }

                try {
                    writeKnowledgeBaseMetadata(fullKnowledgeBasePath, knowledgeBaseName, embeddingModel, rerankerModel, embeddingDimension);
                } catch (Exception e) {
                    logError("Failed to write knowledge base metadata: " + e.getMessage(), e);
                }
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

    /**
     * In-memory graph builder skeleton used to collect basic statistics
     * during knowledge base building. This will be extended to support
     * full in-memory hub filtering and bulk graph writes.
     */
    private static class InMemoryGraphBuilder {
        private static class EntityKey {
            final String text;
            final String type;

            EntityKey(String text, String type) {
                this.text = text;
                this.type = type;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof EntityKey)) return false;
                EntityKey that = (EntityKey) o;
                return ((text == null && that.text == null) || (text != null && text.equals(that.text))) &&
                    ((type == null && that.type == null) || (type != null && type.equals(that.type)));
            }

            @Override
            public int hashCode() {
                int result = (text != null ? text.hashCode() : 0);
                result = 31 * result + (type != null ? type.hashCode() : 0);
                return result;
            }
        }

        private static class EntityStats {
            String text;
            String type;
            String language;
            int frequency;
            float avgConfidence;
            long firstSeen;
            long lastSeen;
        }

        private static class ChunkEntityRef {
            String text;
            String type;
            float confidence;
        }

        private static class EdgeKey {
            final String from;
            final String to;

            EdgeKey(String from, String to) {
                this.from = from;
                this.to = to;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof EdgeKey)) return false;
                EdgeKey that = (EdgeKey) o;
                return ((from == null && that.from == null) || (from != null && from.equals(that.from))) &&
                    ((to == null && that.to == null) || (to != null && to.equals(that.to)));
            }

            @Override
            public int hashCode() {
                int result = (from != null ? from.hashCode() : 0);
                result = 31 * result + (to != null ? to.hashCode() : 0);
                return result;
            }
        }

        private static class EdgeStats {
            String from;
            String to;
            int weight;
            Set<Long> chunkIds = new LinkedHashSet<>();
        }

        interface HubFilterProgressListener {
            void onHubFilteringStarted(int totalHubEntities);
            void onHubFilteringProgress(int processedHubEntities, int totalHubEntities);
            void onHubFilteringCompleted(int removedHubEntities, long durationMs);
        }

        private final Map<EntityKey, EntityStats> entityMap = new HashMap<>();
        private final Map<Long, List<ChunkEntityRef>> chunkEntityMap = new HashMap<>();
        private final Map<EdgeKey, EdgeStats> edgeMap = new HashMap<>();

        private int totalChunks;
        private int totalEntities;
        private long totalPotentialEdges;

        private final Set<String> protectedEntityTexts;

        InMemoryGraphBuilder(Set<String> protectedEntityTexts) {
            if (protectedEntityTexts != null && !protectedEntityTexts.isEmpty()) {
                this.protectedEntityTexts = new HashSet<>(protectedEntityTexts);
            } else {
                this.protectedEntityTexts = null;
            }
        }

        void loadFromDatabase(SQLiteDatabase db, String collection) {
            long startTime = System.currentTimeMillis();
            int loadedEntities = 0;
            int loadedChunkEntities = 0;
            int loadedEdges = 0;
            int distinctChunks = 0;

            Cursor cursor = null;
            try {
                // Load entities
                cursor = db.query("entities",
                    new String[]{"entity_text", "entity_type", "language", "frequency", "avg_confidence", "first_seen", "last_seen"},
                    "collection=?",
                    new String[]{collection},
                    null, null, null);
                while (cursor.moveToNext()) {
                    String text = cursor.getString(0);
                    String type = cursor.getString(1);
                    String language = cursor.getString(2);
                    int frequency = cursor.getInt(3);
                    float avgConf = cursor.getFloat(4);
                    long firstSeen = cursor.getLong(5);
                    long lastSeen = cursor.getLong(6);

                    EntityKey key = new EntityKey(text, type);
                    EntityStats stats = new EntityStats();
                    stats.text = text;
                    stats.type = type;
                    stats.language = language;
                    stats.frequency = frequency;
                    stats.avgConfidence = avgConf;
                    stats.firstSeen = firstSeen;
                    stats.lastSeen = lastSeen;
                    entityMap.put(key, stats);
                    loadedEntities++;
                }
                cursor.close();
                cursor = null;

                // Load chunk-entity mappings
                cursor = db.query("chunk_entities",
                    new String[]{"chunk_id", "entity_text", "entity_type", "confidence"},
                    null,
                    null,
                    null, null, null);
                while (cursor.moveToNext()) {
                    long chunkId = cursor.getLong(0);
                    String text = cursor.getString(1);
                    String type = cursor.getString(2);
                    float conf = cursor.getFloat(3);

                    List<ChunkEntityRef> list = chunkEntityMap.get(chunkId);
                    if (list == null) {
                        list = new ArrayList<>();
                        chunkEntityMap.put(chunkId, list);
                        distinctChunks++;
                    }
                    ChunkEntityRef ref = new ChunkEntityRef();
                    ref.text = text;
                    ref.type = type;
                    ref.confidence = conf;
                    list.add(ref);
                    loadedChunkEntities++;
                }
                cursor.close();
                cursor = null;

                // Load edges
                cursor = db.query("entity_edges",
                    new String[]{"from_entity", "to_entity", "weight", "chunk_ids"},
                    "collection=?",
                    new String[]{collection},
                    null, null, null);
                while (cursor.moveToNext()) {
                    String from = cursor.getString(0);
                    String to = cursor.getString(1);
                    int weight = cursor.getInt(2);
                    String chunkIdsJson = cursor.getString(3);

                    if (from == null || to == null) {
                        continue;
                    }

                    EdgeKey key = makeEdgeKey(from, to);
                    EdgeStats stats = edgeMap.get(key);
                    if (stats == null) {
                        stats = new EdgeStats();
                        stats.from = key.from;
                        stats.to = key.to;
                        edgeMap.put(key, stats);
                    }
                    stats.weight += weight;

                    if (chunkIdsJson != null && !chunkIdsJson.isEmpty()) {
                        try {
                            JSONArray arr = new JSONArray(chunkIdsJson);
                            for (int i = 0; i < arr.length(); i++) {
                                long cid = arr.getLong(i);
                                stats.chunkIds.add(cid);
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    loadedEdges++;
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[GRAPH_MEM] Failed to load existing graph from DB: " + e.getMessage(), e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            totalChunks += distinctChunks;
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format(
                "[GRAPH_MEM] Loaded existing graph from DB: entities=%d, chunk_entities=%d, edges=%d, chunks=%d, time=%dms",
                loadedEntities, loadedChunkEntities, loadedEdges, distinctChunks, duration));
        }

        void addChunk(long chunkId, List<HanLpNerHandler.NerResult.Entity> entities) {
            totalChunks++;
            int entityCount = (entities != null) ? entities.size() : 0;
            totalEntities += entityCount;
            if (entityCount > 1) {
                totalPotentialEdges += (long) entityCount * (entityCount - 1) / 2L;
            }

            if (entities != null && !entities.isEmpty()) {
                for (HanLpNerHandler.NerResult.Entity entity : entities) {
                    if (entity == null || entity.text == null) {
                        continue;
                    }
                    EntityKey key = new EntityKey(entity.text, entity.type);
                    EntityStats stats = entityMap.get(key);
                    long nowSec = System.currentTimeMillis() / 1000L;
                    if (stats == null) {
                        stats = new EntityStats();
                        stats.text = entity.text;
                        stats.type = entity.type;
                        stats.language = "zh";
                        stats.frequency = 1;
                        stats.avgConfidence = entity.confidence;
                        stats.firstSeen = nowSec;
                        stats.lastSeen = nowSec;
                        entityMap.put(key, stats);
                    } else {
                        int oldFreq = stats.frequency;
                        int newFreq = oldFreq + 1;
                        float newConf = (stats.avgConfidence * oldFreq + entity.confidence) / newFreq;
                        stats.frequency = newFreq;
                        stats.avgConfidence = newConf;
                        stats.lastSeen = nowSec;
                    }

                    List<ChunkEntityRef> list = chunkEntityMap.get(chunkId);
                    if (list == null) {
                        list = new ArrayList<>();
                        chunkEntityMap.put(chunkId, list);
                    }
                    ChunkEntityRef ref = new ChunkEntityRef();
                    ref.text = entity.text;
                    ref.type = entity.type;
                    ref.confidence = entity.confidence;
                    list.add(ref);
                }

                int n = entities.size();
                for (int i = 0; i < n; i++) {
                    HanLpNerHandler.NerResult.Entity e1 = entities.get(i);
                    if (e1 == null || e1.text == null) {
                        continue;
                    }
                    for (int j = i + 1; j < n; j++) {
                        HanLpNerHandler.NerResult.Entity e2 = entities.get(j);
                        if (e2 == null || e2.text == null) {
                            continue;
                        }
                        EdgeKey key = makeEdgeKey(e1.text, e2.text);
                        EdgeStats stats = edgeMap.get(key);
                        if (stats == null) {
                            stats = new EdgeStats();
                            stats.from = key.from;
                            stats.to = key.to;
                            edgeMap.put(key, stats);
                        }
                        stats.weight += 1;
                        stats.chunkIds.add(chunkId);
                    }
                }
            }

            LogManager.logD(TAG, String.format("[GRAPH_MEM] Added chunk to in-memory graph snapshot: chunkId=%d, entities=%d", chunkId, entityCount));
        }

        int applyHubFilter(int threshold, HubFilterProgressListener listener) {
            if (threshold <= 0) {
                LogManager.logD(TAG, String.format("[GRAPH_MEM] Hub threshold <= 0, skip in-memory hub filtering (threshold=%d)", threshold));
                if (listener != null) {
                    listener.onHubFilteringStarted(0);
                    listener.onHubFilteringCompleted(0, 0L);
                }
                return 0;
            }

            long startTime = System.currentTimeMillis();

            class HubStats {
                int degree;
                int totalWeight;
            }

            Map<String, HubStats> hubStatsMap = new HashMap<>();

            for (EdgeStats edge : edgeMap.values()) {
                if (edge.from == null || edge.to == null) {
                    continue;
                }
                int w = edge.weight;

                if (edge.from.equals(edge.to)) {
                    HubStats s = hubStatsMap.get(edge.from);
                    if (s == null) {
                        s = new HubStats();
                        hubStatsMap.put(edge.from, s);
                    }
                    s.degree += 1;
                    s.totalWeight += w;
                } else {
                    HubStats sFrom = hubStatsMap.get(edge.from);
                    if (sFrom == null) {
                        sFrom = new HubStats();
                        hubStatsMap.put(edge.from, sFrom);
                    }
                    sFrom.degree += 1;
                    sFrom.totalWeight += w;

                    HubStats sTo = hubStatsMap.get(edge.to);
                    if (sTo == null) {
                        sTo = new HubStats();
                        hubStatsMap.put(edge.to, sTo);
                    }
                    sTo.degree += 1;
                    sTo.totalWeight += w;
                }
            }

            Set<String> hubEntities = new HashSet<>();
            Set<String> protectedSet = protectedEntityTexts;
            int fallbackThreshold = threshold * 5;
            for (Map.Entry<String, HubStats> entry : hubStatsMap.entrySet()) {
                String text = entry.getKey();
                HubStats stats = entry.getValue();
                int degree = stats.degree;
                int totalWeight = stats.totalWeight;

                boolean isProtected = protectedSet != null && protectedSet.contains(text);
                if (!isProtected) {
                    if (degree >= threshold || totalWeight >= threshold) {
                        hubEntities.add(text);
                    }
                } else {
                    if (fallbackThreshold > 0 && (degree >= fallbackThreshold || totalWeight >= fallbackThreshold)) {
                        hubEntities.add(text);
                    }
                }
            }

            if (hubEntities.isEmpty()) {
                LogManager.logD(TAG, String.format("[GRAPH_MEM] No hubs found for threshold=%d", threshold));
                long duration = System.currentTimeMillis() - startTime;
                if (listener != null) {
                    listener.onHubFilteringStarted(0);
                    listener.onHubFilteringCompleted(0, duration);
                }
                return 0;
            }

            int totalHubEntities = hubEntities.size();
            if (listener != null) {
                listener.onHubFilteringStarted(totalHubEntities);
            }

            int processed = 0;
            for (String hubText : hubEntities) {
                // Remove from entity map
                entityMap.entrySet().removeIf(entry -> hubText.equals(entry.getKey().text));

                // Remove from chunk-entity mappings
                for (Map.Entry<Long, List<ChunkEntityRef>> entry : chunkEntityMap.entrySet()) {
                    List<ChunkEntityRef> list = entry.getValue();
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    List<ChunkEntityRef> toKeep = new ArrayList<>();
                    for (ChunkEntityRef ref : list) {
                        if (!hubText.equals(ref.text)) {
                            toKeep.add(ref);
                        }
                    }
                    entry.setValue(toKeep);
                }

                processed++;
                if (listener != null) {
                    listener.onHubFilteringProgress(processed, totalHubEntities);
                }
            }

            // Remove edges involving hubs
            edgeMap.entrySet().removeIf(entry -> hubEntities.contains(entry.getKey().from) || hubEntities.contains(entry.getKey().to));

            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format(
                "[GRAPH_MEM] Hub filtering completed: threshold=%d, hubs=%d, time=%dms",
                threshold, totalHubEntities, duration));

            if (listener != null) {
                listener.onHubFilteringCompleted(totalHubEntities, duration);
            }

            return totalHubEntities;
        }

        void flushToDatabase(SQLiteDatabase db, String collection) {
            long startTime = System.currentTimeMillis();
            int entityRows = 0;
            int chunkEntityRows = 0;
            int edgeRows = 0;

            db.beginTransaction();
            try {
                db.delete("entities", "collection=?", new String[]{collection});
                db.delete("entity_edges", "collection=?", new String[]{collection});
                db.delete("chunk_entities", null, null);

                ContentValues values = new ContentValues();

                for (EntityStats stats : entityMap.values()) {
                    values.clear();
                    values.put("collection", collection);
                    values.put("entity_text", stats.text);
                    values.put("entity_type", stats.type);
                    values.put("language", stats.language != null ? stats.language : "zh");
                    values.put("frequency", stats.frequency);
                    values.put("avg_confidence", stats.avgConfidence);
                    long nowSec = System.currentTimeMillis() / 1000L;
                    values.put("first_seen", stats.firstSeen > 0 ? stats.firstSeen : nowSec);
                    values.put("last_seen", stats.lastSeen > 0 ? stats.lastSeen : nowSec);
                    db.insert("entities", null, values);
                    entityRows++;
                }

                for (Map.Entry<Long, List<ChunkEntityRef>> entry : chunkEntityMap.entrySet()) {
                    long chunkId = entry.getKey();
                    List<ChunkEntityRef> list = entry.getValue();
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    for (ChunkEntityRef ref : list) {
                        values.clear();
                        values.put("chunk_id", chunkId);
                        values.put("entity_text", ref.text);
                        values.put("entity_type", ref.type);
                        values.put("confidence", ref.confidence);
                        db.insertWithOnConflict("chunk_entities", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                        chunkEntityRows++;
                    }
                }

                for (EdgeStats edge : edgeMap.values()) {
                    values.clear();
                    values.put("collection", collection);
                    values.put("from_entity", edge.from);
                    values.put("to_entity", edge.to);
                    values.put("weight", edge.weight);
                    JSONArray arr = new JSONArray();
                    for (Long cid : edge.chunkIds) {
                        arr.put(cid);
                    }
                    values.put("chunk_ids", arr.toString());
                    values.put("updated_at", System.currentTimeMillis() / 1000L);
                    db.insert("entity_edges", null, values);
                    edgeRows++;
                }

                db.setTransactionSuccessful();
            } catch (Exception e) {
                LogManager.logE(TAG, "[GRAPH_MEM] Failed to flush in-memory graph to SQLite: " + e.getMessage(), e);
            } finally {
                try {
                    db.endTransaction();
                } catch (Exception ignore) {
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format(
                "[GRAPH_MEM] Flushed in-memory graph to SQLite: entities=%d, chunk_entities=%d, edges=%d, time=%dms",
                entityRows, chunkEntityRows, edgeRows, duration));
        }

        void logSummary() {
            LogManager.logI(TAG, String.format("[GRAPH_MEM] In-memory graph summary: chunks=%d, entities=%d, potential_edges=%d",
                totalChunks, totalEntities, totalPotentialEdges));
        }

        private EdgeKey makeEdgeKey(String a, String b) {
            if (a == null || b == null) {
                return new EdgeKey(a, b);
            }
            if (a.compareTo(b) <= 0) {
                return new EdgeKey(a, b);
            } else {
                return new EdgeKey(b, a);
            }
        }
    }
    
    // ========== Knowledge Graph RAG Methods ==========
    
    /**
     * Initialize Knowledge Graph components
     * TODO: Phase 6 - Re-implement with LLM NER
     */
    private void initializeKnowledgeGraph() {
        // TODO: Phase 6 - Re-implement with LLM NER
        LogManager.logD(TAG, "[KG] Knowledge Graph initialization temporarily disabled (Phase 6)");
        graphDatabase = null;
        /*
        try {
            LogManager.logD(TAG, "[KG] Initializing Knowledge Graph components...");
            
            // Initialize Entity Recognizer
            entityRecognizer = new HybridEntityRecognizer();
            boolean nerReady = entityRecognizer.initialize(context);
            
            if (!nerReady) {
                LogManager.logW(TAG, "[KG] Entity Recognizer initialization failed, Knowledge Graph disabled");
                entityRecognizer = null;
            } else {
                LogManager.logI(TAG, "[KG] Entity Recognizer initialized successfully");
            }
            
            // Initialize Knowledge Graph Database
            String dbPath = context.getDatabasePath("knowledge_graph.db").getAbsolutePath();
            graphDatabase = new KnowledgeGraphDatabase(context, dbPath);
            LogManager.logI(TAG, "[KG] Knowledge Graph Database initialized at: " + dbPath);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[KG] Failed to initialize Knowledge Graph: " + e.getMessage(), e);
            entityRecognizer = null;
            graphDatabase = null;
        }
        */
    }
    
    /**
     * Process chunk for Knowledge Graph (extract entities and build graph)
     * TODO: Phase 6 - Re-implement with LLM NER
     * @param chunkId Chunk ID in vector database
     * @param text Chunk text
     * @param source Source document
     * @param chunkIndex Chunk index in document
     */
    private void processChunkForKnowledgeGraph(long chunkId, String text, String source, int chunkIndex) {
        // TODO: Phase 6 - Re-implement with LLM NER
        // Temporarily disabled
        return;
        /*
        // Skip if Knowledge Graph is not initialized
        if (entityRecognizer == null || graphDatabase == null) {
            return;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Extract entities from text
            List<EntityRecognizer.Entity> entities = entityRecognizer.extractEntities(text);
            
            if (entities.isEmpty()) {
                LogManager.logD(TAG, String.format("[KG] No entities found in chunk %d (source: %s)", 
                    chunkIndex, source));
                return;
            }
            
            // Filter entities by confidence threshold
            float confidenceThreshold = ConfigManager.getGraphEntityConfidenceThreshold(context);
            List<EntityRecognizer.Entity> filteredEntities = new ArrayList<>();
            for (EntityRecognizer.Entity entity : entities) {
                if (entity.confidence >= confidenceThreshold) {
                    filteredEntities.add(entity);
                }
            }
            
            if (filteredEntities.isEmpty()) {
                LogManager.logD(TAG, String.format("[KG] No high-confidence entities (threshold=%.2f) in chunk %d", 
                    confidenceThreshold, chunkIndex));
                return;
            }
            
            // Add document to graph database
            long docId = graphDatabase.addDocument(source, text);
            
            // Add entities and build relationships
            List<Long> entityIds = new ArrayList<>();
            for (EntityRecognizer.Entity entity : filteredEntities) {
                long entityId = graphDatabase.addEntity(entity.text, entity.type, entity.confidence);
                entityIds.add(entityId);
                
                // Link entity to chunk
                graphDatabase.addChunkEntity(chunkId, entityId, entity.confidence);
            }
            
            // Build co-occurrence edges (entities in same chunk)
            for (int i = 0; i < entityIds.size(); i++) {
                for (int j = i + 1; j < entityIds.size(); j++) {
                    graphDatabase.addOrUpdateEdge(entityIds.get(i), entityIds.get(j), docId);
                }
            }
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, String.format("[KG] Processed chunk %d: %d entities, %d edges, time=%dms", 
                chunkIndex, filteredEntities.size(), 
                (entityIds.size() * (entityIds.size() - 1)) / 2, elapsedTime));
            
        } catch (Exception e) {
            LogManager.logE(TAG, String.format("[KG] Failed to process chunk %d for Knowledge Graph: %s", 
                chunkIndex, e.getMessage()), e);
        }
        */
    }
    
    /**
     * Release Knowledge Graph resources
     * TODO: Phase 6 - Re-implement
     */
    private void releaseKnowledgeGraph() {
        // TODO: Phase 6 - Re-implement
        /*
        try {
            if (entityRecognizer != null) {
                entityRecognizer.release();
                entityRecognizer = null;
                LogManager.logD(TAG, "[KG] Entity Recognizer released");
            }
            
            if (graphDatabase != null) {
                graphDatabase.close();
                graphDatabase = null;
                LogManager.logD(TAG, "[KG] Knowledge Graph Database closed");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[KG] Error releasing Knowledge Graph resources: " + e.getMessage(), e);
        }
        */
    }
}

