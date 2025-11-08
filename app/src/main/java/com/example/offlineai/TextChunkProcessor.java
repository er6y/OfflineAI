package com.example.offlineai;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.example.offlineai.LogManager;
import com.example.offlineai.KnowledgeGraphDatabase;
import com.example.offlineai.HanLpNerHandler;

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
            
            // Use EmbeddingHandler to get model (LOW memory mode)
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
            
            // MNN embedding has built-in tokenizer, no external tokenizer needed
            logMessage("Using MNN built-in tokenizer for consistent embedding generation");
            
            // Initialize knowledge graph database (unified storage for vectors + entities + graph)
            String graphDbPath = fullKnowledgeBasePath + File.separator + "knowledge_graph.db";
            KnowledgeGraphDatabase graphDB = new KnowledgeGraphDatabase(context, graphDbPath, knowledgeBaseName);

            // Load graph stopwords and hub threshold config
            String stopwordsPath = ConfigManager.getGraphStopwordsPath(context);
            int hubThreshold = ConfigManager.getGraphHubThreshold(context);
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
            
            // Create ExecutorService for SERIAL chunk processing (single thread)
            ExecutorService executorService = Executors.newFixedThreadPool(1);
            LogManager.logI(TAG, "[EXECUTOR] Created single-thread executor for serial chunk processing");
            
            // Begin database transaction for batch insert
            graphDB.getWritableDatabase().beginTransaction();
            
            try {
                int totalChunks = chunks.size();
                logMessage("Starting unified processing: " + totalChunks + " chunks" + (nerEnabled ? " with NER" : ""));
                int lastLoggedPercent = -10;
                if (totalChunks > 0) {
                    // Initial 0% milestone
                    logMessage("Vectorization progress: 0% (0/" + totalChunks + ")");
                    lastLoggedPercent = 0;
                }
                
                // Process each chunk: Embedding and NER serially in a single task
                LogManager.logI(TAG, String.format("[LOOP] Starting chunk processing loop: %d chunks total", totalChunks));
                for (int i = 0; i < totalChunks; i++) {
                    LogManager.logD(TAG, String.format("[LOOP] ========== Iteration %d/%d START ==========", i + 1, totalChunks));
                    if (isTaskCancelled.get()) {
                        logMessage("Task cancelled");
                        LogManager.logD(TAG, "Processing interrupted: cancelled at chunk " + i + "/" + totalChunks);
                        break;  // Exit loop, finally block will handle cleanup
                    }
                    
                    TextChunk chunk = chunks.get(i);
                    LogManager.logI(TAG, String.format("[LOOP] Chunk %d/%d: source=%s, textLen=%d", i + 1, totalChunks, chunk.source, chunk.text.length()));
                    
                    try {
                        long chunkStartTime = System.currentTimeMillis();
                        
                        // Step 1 & 2: Process Embedding and NER serially in a single task
                        final String chunkText = chunk.text;
                        final int chunkIndex = i + 1;  // Make final for lambda
                        final boolean nerEnabledForChunk = nerEnabled && nerHandler != null;
                        
                        LogManager.logI(TAG, String.format("[SUBMIT] About to submit chunk %d task to executor...", chunkIndex));
                        long submitTime = System.currentTimeMillis();
                        
                        // Submit a single task that does: embedding -> NER -> return results
                        Future<ChunkProcessResult> chunkFuture = executorService.submit(() -> {
                            LogManager.logI(TAG, String.format("[TASK %d] Task started in executor, Thread: %s", chunkIndex, Thread.currentThread().getName()));
                            ChunkProcessResult result = new ChunkProcessResult();
                            
                            // 1. Embedding (串行执行)
                            long embeddingStart = System.currentTimeMillis();
                            LogManager.logD(TAG, String.format("[CHUNK %d] Starting embedding...", chunkIndex));
                            try {
                                result.embedding = model.computeEmbedding(chunkText);
                                result.embeddingTime = System.currentTimeMillis() - embeddingStart;
                                LogManager.logI(TAG, String.format("[CHUNK %d] Embedding completed: %dms", chunkIndex, result.embeddingTime));
                            } catch (Exception e) {
                                LogManager.logE(TAG, String.format("[CHUNK %d] Embedding failed: %s", chunkIndex, e.getMessage()), e);
                                throw e;
                            }
                            
                            // 2. NER (串行执行，在embedding之后)
                            if (nerEnabledForChunk) {
                                long nerStart = System.currentTimeMillis();
                                LogManager.logD(TAG, String.format("[CHUNK %d] Starting NER...", chunkIndex));
                                try {
                                    HanLpNerHandler.NerResult nerResult = nerHandler.extractEntities(chunkText);
                                    result.nerTime = System.currentTimeMillis() - nerStart;
                                    
                                    if (nerResult != null && nerResult.isSuccess()) {
                                        result.entities = nerResult.getEntities();
                                        LogManager.logI(TAG, String.format("[CHUNK %d] NER completed: %dms, entities=%d", 
                                            chunkIndex, result.nerTime, result.entities.size()));
                                    } else {
                                        LogManager.logW(TAG, String.format("[CHUNK %d] NER failed", chunkIndex));
                                    }
                                } catch (Exception e) {
                                    LogManager.logE(TAG, String.format("[CHUNK %d] NER error: %s", chunkIndex, e.getMessage()), e);
                                    // NER failure is not fatal, continue with empty entities
                                }
                            }
                            
                            LogManager.logI(TAG, String.format("[TASK %d] Task completed, returning result", chunkIndex));
                            return result;
                        });
                        
                        LogManager.logI(TAG, String.format("[SUBMIT] Chunk %d task submitted (took %dms), now waiting for completion...", chunkIndex, System.currentTimeMillis() - submitTime));
                        
                        // Wait for the entire chunk processing to complete (NO TIMEOUT - wait forever)
                        ChunkProcessResult result = null;
                        long getStartTime = System.currentTimeMillis();
                        try {
                            LogManager.logI(TAG, String.format("[GET] Calling future.get() for chunk %d (NO TIMEOUT - will wait forever)...", chunkIndex));
                            result = chunkFuture.get();  // NO TIMEOUT - wait forever for debugging
                            LogManager.logI(TAG, String.format("[GET] future.get() returned for chunk %d (waited %dms)", chunkIndex, System.currentTimeMillis() - getStartTime));
                        } catch (Exception e) {
                            LogManager.logE(TAG, String.format("Chunk %d processing error: %s", i + 1, e.getMessage()), e);
                            throw e;
                        }
                        
                        float[] embedding = result.embedding;
                        List<HanLpNerHandler.NerResult.Entity> entities = result.entities;
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
                                        chunkIndex, filteredCount, entities.size(), filteredEntities.size()));
                            }
                            entities = filteredEntities;
                        }
                        long embeddingTime = result.embeddingTime;
                        long nerTime = result.nerTime;
                        long totalTime = System.currentTimeMillis() - chunkStartTime;
                        
                        // Step 3: Save to database (chunk + vector + entities)
                        LogManager.logD(TAG, String.format("[DB] Chunk %d: Saving to database...", chunkIndex));
                        long dbStartTime = System.currentTimeMillis();
                        long docId = graphDB.addChunk(chunk.text, chunk.source, embedding, chunk.metadata.toString());
                        long dbTime = System.currentTimeMillis() - dbStartTime;
                        LogManager.logD(TAG, String.format("[DB] Chunk %d: Saved docId=%d (took %dms)", chunkIndex, docId, dbTime));
                        
                        if (!entities.isEmpty()) {
                            LogManager.logD(TAG, String.format("[DB] Chunk %d: Saving %d entities and building relationships...", chunkIndex, entities.size()));
                            long entitiesStartTime = System.currentTimeMillis();
                            
                            // Step 1: Add entities and collect entity IDs
                            List<Long> entityIds = new ArrayList<>();
                            for (HanLpNerHandler.NerResult.Entity entity : entities) {
                                long entityId = graphDB.addEntity(entity.text, entity.type, entity.confidence);
                                if (entityId > 0) {
                                    entityIds.add(entityId);
                                    // Link chunk to entity
                                    graphDB.linkChunkToEntity(docId, entity.text, entity.type, entity.confidence);
                                }
                            }
                            
                            // Step 2: Build co-occurrence edges (entities in same chunk are related)
                            if (entityIds.size() > 1) {
                                for (int j = 0; j < entityIds.size(); j++) {
                                    for (int k = j + 1; k < entityIds.size(); k++) {
                                        long fromId = entityIds.get(j);
                                        long toId = entityIds.get(k);
                                        // Add bidirectional edges with weight 1.0
                                        graphDB.addEdge(fromId, toId, 1.0f);
                                        graphDB.addEdge(toId, fromId, 1.0f);
                                    }
                                }
                                LogManager.logD(TAG, String.format("[DB] Chunk %d: Built %d co-occurrence edges", 
                                    chunkIndex, entityIds.size() * (entityIds.size() - 1)));
                            }
                            
                            long entitiesTime = System.currentTimeMillis() - entitiesStartTime;
                            LogManager.logD(TAG, String.format("[DB] Chunk %d: Entities and relationships saved (took %dms)", chunkIndex, entitiesTime));
                        }
                        
                        LogManager.logI(TAG, String.format("[SUMMARY] Chunk %d/%d: total=%dms (embed=%dms, ner=%dms, db=%dms), entities=%d", 
                            i + 1, totalChunks, totalTime, embeddingTime, nerTime, dbTime, entities.size()));
                        
                        // Update progress
                        float percentage = (float) (i + 1) / totalChunks * 100;
                        if (progressCallback != null) {
                            progressCallback.onVectorizationProgress(i + 1, totalChunks, percentage);
                        }
                        if (notificationProgressCallback != null) {
                            notificationProgressCallback.onNotificationProgressUpdate(i + 1, totalChunks, percentage);
                        }

                        // Dot-style and milestone progress logs for UI
                        logMessage(".");
                        int currentPercent = (int) percentage;
                        int milestone = (currentPercent / 10) * 10;
                        if (milestone >= 0 && milestone <= 100 && milestone > lastLoggedPercent) {
                            logMessage("Vectorization progress: " + milestone + "% (" + (i + 1) + "/" + totalChunks + ")");
                            lastLoggedPercent = milestone;
                        }
                        
                    } catch (Exception e) {
                        logError("Failed to process chunk " + (i + 1) + ": " + e.getMessage(), e);
                        LogManager.logE(TAG, String.format("[LOOP] Chunk %d failed, continuing to next chunk", i + 1), e);
                        // Continue processing next chunk
                    }
                    
                    LogManager.logD(TAG, String.format("[LOOP] ========== Iteration %d/%d END ==========", i + 1, totalChunks));
                }
                
                LogManager.logI(TAG, "[LOOP] Chunk processing loop completed");
                
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

            // After successful processing, persist metadata to database and metadata.json
            if (!isTaskCancelled.get()) {
                // Apply hub threshold filter on completed graph if enabled
                if (hubThreshold > 0) {
                    KnowledgeGraphDatabase hubDb = null;
                    try {
                        hubDb = new KnowledgeGraphDatabase(context, graphDbPath, knowledgeBaseName);
                        int removed = hubDb.applyHubThreshold(hubThreshold);
                        logMessage("Graph hub filter applied: threshold=" + hubThreshold + ", removed hub entities: " + removed);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[HUB_FILTER] Failed to apply hub threshold: " + e.getMessage(), e);
                        logMessage("Warning: Failed to apply graph hub threshold: " + e.getMessage());
                    } finally {
                        if (hubDb != null) {
                            try {
                                hubDb.close();
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[HUB_FILTER] Failed to close graph DB after hub filtering: " + e.getMessage(), e);
                            }
                        }
                    }
                } else {
                    LogManager.logD(TAG, "[HUB_FILTER] Hub threshold is disabled (<=0), skipping hub filtering");
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

