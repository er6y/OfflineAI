package com.example.offlineai;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Knowledge Graph Builder
 * 
 * Orchestrates the graph construction process:
 * 1. LLM NER extraction (2 threads)
 * 2. Embedding generation (2 threads)
 * 3. Database insertion
 * 
 * Progress tracking and error handling included
 * 
 * TODO: Phase 6 - Implement full functionality
 */
public class KnowledgeGraphBuilder {
    // TODO: Phase 6 - This class is a placeholder for now
    // Will be fully implemented when integrating with knowledge base building process
    private static final String TAG = "OfflineAI_GraphBuilder";
    
    private final Context context;
    private final ExecutorService embeddingExecutor;
    
    // Progress tracking
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private int totalChunks = 0;
    
    // Statistics
    private final AtomicInteger successfulChunks = new AtomicInteger(0);
    private final AtomicInteger totalEntities = new AtomicInteger(0);
    private final AtomicInteger totalEdges = new AtomicInteger(0);
    
    // Components
    private HanLpNerHandler nerHandler;
    private EmbeddingHandler embeddingHandler;
    private KnowledgeGraphDatabase graphDatabase;
    private ProgressCallback progressCallback;
    
    public KnowledgeGraphBuilder(Context context) {
        this.context = context;
        // Embedding uses fixed thread pool for parallel processing
        // NER uses HanLP (fast, no async needed)
        this.embeddingExecutor = Executors.newFixedThreadPool(2);
    }
    
    /**
     * Build knowledge graph from text chunks
     * 
     * @param chunks List of text chunks
     * @param nerModelPath LLM model path for NER (null to skip NER)
     * @param embeddingModelPath Embedding model path
     * @param graphDbPath Graph database path
     * @param collection Collection name
     * @param progressCallback Progress callback (optional)
     * @return true if successful
     */
    public boolean buildGraph(
            List<TextChunk> chunks,
            String nerModelPath,
            String embeddingModelPath,
            String graphDbPath,
            String collection,
            ProgressCallback progressCallback) {
        
        if (chunks == null || chunks.isEmpty()) {
            LogManager.logW(TAG, "No chunks to process");
            return false;
        }
        
        totalChunks = chunks.size();
        processedChunks.set(0);
        
        LogManager.logI(TAG, String.format("[GraphBuild] Starting: %d chunks, NER=%s, Embedding=%s", 
            totalChunks, nerModelPath != null ? "enabled" : "disabled", embeddingModelPath));
        
        try {
            // Initialize components
            if (!initializeComponents(nerModelPath, embeddingModelPath, graphDbPath, collection)) {
                return false;
            }
            
            // Process chunks sequentially (as per user requirement)
            for (TextChunk chunk : chunks) {
                if (!processChunk(chunk, nerModelPath != null, progressCallback)) {
                    LogManager.logW(TAG, String.format("[GraphBuild] Failed to process chunk: %s", chunk.source));
                    // Continue with next chunk
                }
                
                // Progress is already incremented in processChunk(), just read it here
                int progress = processedChunks.get();
                if (progressCallback != null) {
                    progressCallback.onProgress(progress, totalChunks);
                }
            }
            
            LogManager.logI(TAG, String.format("[GraphBuild] ✅ Completed: %d/%d chunks processed", 
                processedChunks.get(), totalChunks));
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GraphBuild] ❌ Failed: " + e.getMessage());
            return false;
        } finally {
            cleanup();
        }
    }
    
    /**
     * Initialize all components
     */
    private boolean initializeComponents(String nerModelPath, String embeddingModelPath, 
                                        String graphDbPath, String collection) {
        try {
            LogManager.logI(TAG, "[GraphBuild] ========== Initialization Start ==========");
            LogManager.logI(TAG, String.format("[GraphBuild] NER model: %s", 
                nerModelPath != null ? nerModelPath : "DISABLED"));
            LogManager.logI(TAG, String.format("[GraphBuild] Embedding model: %s", embeddingModelPath));
            LogManager.logI(TAG, String.format("[GraphBuild] Graph DB: %s", graphDbPath));
            
            // Initialize NER handler (HanLP)
            String dictPath = ConfigManager.getString(context, ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null);
            nerHandler = new HanLpNerHandler(dictPath);
            LogManager.logI(TAG, "[GraphBuild] ✓ HanLP NER handler initialized");
            
            // TODO: Phase 6 - Embedding handler should be passed from caller, not initialized here
            // This avoids duplicate model loading and memory mode confusion
            // embeddingHandler = EmbeddingHandler.getInstance(context);
            // if (!embeddingHandler.loadModel(embeddingModelPath, EmbeddingHandler.MemoryMode.LOW)) {
            //     LogManager.logE(TAG, "[GraphBuild] Failed to load embedding model");
            //     return false;
            // }
            // LogManager.logI(TAG, "[GraphBuild] ✓ Embedding handler initialized (LOW memory mode)");
            LogManager.logW(TAG, "[GraphBuild] KnowledgeGraphBuilder is not currently used - Phase 6 TODO");
            
            // Initialize graph database
            graphDatabase = new KnowledgeGraphDatabase(context, graphDbPath, collection);
            LogManager.logI(TAG, "[GraphBuild] ✓ Graph database initialized");
            
            LogManager.logI(TAG, "[GraphBuild] ========== Initialization Complete ==========");
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GraphBuild] Initialization error: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Process a single chunk
     */
    private boolean processChunk(TextChunk chunk, boolean enableNer, ProgressCallback progressCallback) {
        int chunkIndex = processedChunks.incrementAndGet();
        String threadName = Thread.currentThread().getName();
        
        try {
            long startTime = System.currentTimeMillis();
            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d processing started (Thread: %s, NER: %s)", 
                chunkIndex, threadName, enableNer ? "ON" : "OFF"));
            
            // Step 1 & 2: Start Embedding (NER will be done in batch)
            // HanLP is fast, no need for async per chunk
            
            Future<float[]> embeddingFuture = embeddingExecutor.submit(() -> {
                try {
                    return embeddingHandler.computeEmbedding(chunk.content);
                } catch (Exception e) {
                    LogManager.logE(TAG, "[GraphBuild] Embedding error: " + e.getMessage(), e);
                    return null;
                }
            });
            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: Embedding task submitted", chunkIndex));
            
            // Step 3: Wait for both tasks to complete (TRUE parallel execution)
            // CRITICAL: Both tasks are already running in parallel in their respective thread pools
            // We just need to wait for BOTH to complete, not wait for one then the other
            long parallelWaitStart = System.currentTimeMillis();
            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] Starting parallel wait for NER and Embedding", chunkIndex));
            
            // NER will be done in batch after all embeddings
            List<HanLpNerHandler.NerResult.Entity> entities = new ArrayList<>();
            long nerWaitTime = 0;
            if (enableNer) {
                long nerWaitStart = System.currentTimeMillis();
                try {
                    HanLpNerHandler.NerResult nerResult = nerHandler.extractEntities(chunk.content);
                    nerWaitTime = System.currentTimeMillis() - nerWaitStart;
                    if (nerResult.isSuccess()) {
                        entities = nerResult.getEntities();
                        LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] NER wait completed in %dms, extracted %d entities", 
                            chunkIndex, nerWaitTime, entities.size()));
                        // DEBUG: Print entity details
                        for (int i = 0; i < entities.size() && i < 5; i++) {
                            HanLpNerHandler.NerResult.Entity entity = entities.get(i);
                            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] Entity #%d: text='%s', type='%s'", 
                                chunkIndex, i + 1, entity.text, entity.type));
                        }
                        if (entities.size() > 5) {
                            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] ... and %d more entities", 
                                chunkIndex, entities.size() - 5));
                        }
                    } else {
                        LogManager.logW(TAG, String.format("[GraphBuild] Chunk #%d: NER failed - %s, continuing with vector-only", 
                            chunkIndex, nerResult.getError()));
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d: NER error: %s, continuing with vector-only", chunkIndex, e.getMessage()), e);
                }
            }
            
            // Wait for embedding (may already be done if NER took longer)
            float[] embedding = null;
            long embeddingWaitTime = 0;
            long embeddingWaitStart = System.currentTimeMillis();
            try {
                embedding = embeddingFuture.get(30, TimeUnit.SECONDS);
                embeddingWaitTime = System.currentTimeMillis() - embeddingWaitStart;
                if (embedding == null || embedding.length == 0) {
                    LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d: Failed to generate embedding", chunkIndex));
                    return false;
                }
                LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] Embedding wait completed in %dms (%d dims)", 
                    chunkIndex, embeddingWaitTime, embedding.length));
            } catch (java.util.concurrent.TimeoutException e) {
                LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d: Embedding timeout after 30s", chunkIndex));
                return false;
            } catch (Exception e) {
                LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d: Embedding error: %s", chunkIndex, e.getMessage()), e);
                return false;
            }
            
            long parallelWaitTime = System.currentTimeMillis() - parallelWaitStart;
            LogManager.logI(TAG, String.format("[GraphBuild] Chunk #%d: [DEBUG] Total parallel wait: %dms (NER wait: %dms, Embedding wait: %dms, overlap: %dms)", 
                chunkIndex, parallelWaitTime, nerWaitTime, embeddingWaitTime, (nerWaitTime + embeddingWaitTime - parallelWaitTime)));
            
            // Step 3: Insert into database
            long docId = graphDatabase.addChunk(chunk.content, chunk.source, embedding, chunk.metadata);
            
            if (docId < 0) {
                LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d: Failed to insert document", chunkIndex));
                return false;
            }
            LogManager.logD(TAG, String.format("[GraphBuild] Chunk #%d: Inserted as docId=%d", chunkIndex, docId));
            
            // Step 4: Add entities and relationships (if NER enabled)
            if (enableNer && !entities.isEmpty()) {
                int entitiesAdded = addEntitiesToGraph(docId, entities, chunk.content);
                totalEntities.addAndGet(entitiesAdded);
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            successfulChunks.incrementAndGet();
            LogManager.logI(TAG, String.format("[GraphBuild] Chunk #%d completed in %dms: docId=%d, entities=%d (Thread: %s)", 
                chunkIndex, elapsed, docId, entities.size(), threadName));
            
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, String.format("[GraphBuild] Chunk #%d failed: %s (Thread: %s)", 
                chunkIndex, e.getMessage(), threadName), e);
            return false;
        }
    }
    
    /**
     * Add entities to graph and build co-occurrence relationships
     * @return Number of entities added
     */
    private int addEntitiesToGraph(long docId, List<HanLpNerHandler.NerResult.Entity> entities, String content) {
        float confidenceThreshold = ConfigManager.getGraphEntityConfidenceThreshold(context);
        
        // Keep both entity IDs and entity objects for edge building
        List<Long> entityIds = new ArrayList<>();
        List<HanLpNerHandler.NerResult.Entity> validEntities = new ArrayList<>();
        int filteredCount = 0;
        
        for (HanLpNerHandler.NerResult.Entity entity : entities) {
            if (entity.confidence >= confidenceThreshold) {
                long entityId = graphDatabase.addEntity(entity.text, entity.type, entity.confidence);
                entityIds.add(entityId);
                validEntities.add(entity);
                // Link chunk to entity using entity_text and entity_type (not entity_id)
                graphDatabase.linkChunkToEntity(docId, entity.text, entity.type, entity.confidence);
            } else {
                filteredCount++;
            }
        }
        
        // Build co-occurrence relationships (entities in same chunk)
        int edgesAdded = 0;
        for (int i = 0; i < entityIds.size(); i++) {
            for (int j = i + 1; j < entityIds.size(); j++) {
                graphDatabase.addEdge(entityIds.get(i), entityIds.get(j), 1.0f);
                edgesAdded++;
            }
        }
        totalEdges.addAndGet(edgesAdded);
        
        LogManager.logD(TAG, String.format("[GraphBuild] DocId=%d: added %d entities (filtered %d), %d edges", 
            docId, entityIds.size(), filteredCount, edgesAdded));
        
        return entityIds.size();
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        try {
            LogManager.logI(TAG, "[GraphBuild] ========== Cleanup Start ==========");
            
            // Cleanup NER handler (if NER was used)
            if (nerHandler != null) {
                try {
                    nerHandler.release();
                    LogManager.logI(TAG, "[GraphBuild] ✓ NER handler cleaned up");
                } catch (Exception e) {
                    LogManager.logW(TAG, "[GraphBuild] Failed to cleanup NER handler: " + e.getMessage());
                }
            }
            
            if (embeddingHandler != null) {
                embeddingHandler.releaseModel();
                LogManager.logI(TAG, "[GraphBuild] ✓ Embedding handler released");
            }
            if (graphDatabase != null) {
                graphDatabase.close();
                LogManager.logI(TAG, "[GraphBuild] ✓ Graph database closed");
            }
            
            // Print final statistics
            LogManager.logI(TAG, "[GraphBuild] ========== Final Statistics ==========");
            LogManager.logI(TAG, String.format("[GraphBuild] Total chunks processed: %d", processedChunks.get()));
            LogManager.logI(TAG, String.format("[GraphBuild] Successful chunks: %d", successfulChunks.get()));
            LogManager.logI(TAG, String.format("[GraphBuild] Failed chunks: %d", 
                processedChunks.get() - successfulChunks.get()));
            LogManager.logI(TAG, String.format("[GraphBuild] Total entities: %d", totalEntities.get()));
            LogManager.logI(TAG, String.format("[GraphBuild] Total edges: %d", totalEdges.get()));
            
            // Display entity summary from database
            displayEntitySummary();
            
            LogManager.logI(TAG, "[GraphBuild] ========== Cleanup Complete ==========");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GraphBuild] Cleanup error: " + e.getMessage(), e);
        }
    }
    
    /**
     * Shutdown executors and wait for completion
     */
    public void shutdown() {
        LogManager.logI(TAG, "[GraphBuild] Shutting down thread pools...");
        
        // Shutdown embedding executor
        embeddingExecutor.shutdown();
        
        try {
            // Wait for embedding tasks to complete
            if (!embeddingExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                LogManager.logW(TAG, "[GraphBuild] Embedding executor timeout, forcing shutdown");
                embeddingExecutor.shutdownNow();
            } else {
                LogManager.logI(TAG, "[GraphBuild] ✓ Embedding executor shutdown complete");
            }
        } catch (InterruptedException e) {
            LogManager.logW(TAG, "[GraphBuild] Shutdown interrupted");
            embeddingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // NER handler manages its own executor, cleanup() will handle it
        cleanup();
    }
    
    /**
     * Display entity summary from database
     */
    private void displayEntitySummary() {
        if (graphDatabase == null) {
            LogManager.logW(TAG, "[GraphBuild] Cannot display entities: database is null");
            return;
        }
        
        try {
            LogManager.logI(TAG, "[GraphBuild] ========== Entity Summary ==========");
            
            // Query entity type distribution
            android.database.Cursor cursor = graphDatabase.getDatabase().rawQuery(
                "SELECT type, COUNT(*) as count FROM entities GROUP BY type ORDER BY count DESC", null);
            
            LogManager.logI(TAG, "[GraphBuild] Entity Type Distribution:");
            int typeCount = 0;
            while (cursor.moveToNext() && typeCount < 10) {
                String type = cursor.getString(0);
                int count = cursor.getInt(1);
                LogManager.logI(TAG, String.format("[GraphBuild]   - %s: %d entities", type, count));
                typeCount++;
            }
            cursor.close();
            
            // Query top entities by edge count (most connected)
            cursor = graphDatabase.getDatabase().rawQuery(
                "SELECT e.name, e.type, COUNT(DISTINCT ee.id) as edge_count " +
                "FROM entities e " +
                "LEFT JOIN entity_edges ee ON e.id = ee.entity1_id OR e.id = ee.entity2_id " +
                "GROUP BY e.id " +
                "ORDER BY edge_count DESC " +
                "LIMIT 15", null);
            
            LogManager.logI(TAG, "[GraphBuild] Top 15 Most Connected Entities:");
            int entityIndex = 0;
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String type = cursor.getString(1);
                int edgeCount = cursor.getInt(2);
                entityIndex++;
                LogManager.logI(TAG, String.format("[GraphBuild]   %2d. [%s] %s (edges: %d)", 
                    entityIndex, type, name, edgeCount));
            }
            cursor.close();
            
            // Query sample entities for each type
            cursor = graphDatabase.getDatabase().rawQuery(
                "SELECT DISTINCT type FROM entities ORDER BY type LIMIT 5", null);
            
            LogManager.logI(TAG, "[GraphBuild] Sample Entities by Type:");
            while (cursor.moveToNext()) {
                String type = cursor.getString(0);
                android.database.Cursor sampleCursor = graphDatabase.getDatabase().rawQuery(
                    "SELECT name FROM entities WHERE type = ? LIMIT 3", new String[]{type});
                
                StringBuilder samples = new StringBuilder();
                while (sampleCursor.moveToNext()) {
                    if (samples.length() > 0) samples.append(", ");
                    samples.append(sampleCursor.getString(0));
                }
                sampleCursor.close();
                
                LogManager.logI(TAG, String.format("[GraphBuild]   [%s]: %s", type, samples.toString()));
            }
            cursor.close();
            
            LogManager.logI(TAG, "[GraphBuild] ========================================");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GraphBuild] Failed to display entity summary: " + e.getMessage(), e);
        }
    }
    
    /**
     * Text chunk data class
     */
    public static class TextChunk {
        public final String content;
        public final String source;
        public final String metadata;
        
        public TextChunk(String content, String source, String metadata) {
            this.content = content;
            this.source = source;
            this.metadata = metadata;
        }
    }
    
    /**
     * Progress callback interface
     */
    public interface ProgressCallback {
        void onProgress(int current, int total);
    }
}
