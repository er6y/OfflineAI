package com.example.offlineai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Knowledge Graph Database (New Design - No Legacy Burden)
 * 
 * Architecture:
 * - documents: Text chunks + vectors (same as before)
 * - entities: Unique entities with frequency stats
 * - entity_edges: Co-occurrence relationships (weighted graph)
 * - chunk_entities: Many-to-many mapping
 * 
 * Features:
 * - Optimized indexes for fast graph traversal
 * - Detailed logging for effect evaluation
 * - Clean separation from legacy vector database
 * 
 * Performance Target:
 * - Insert: <5ms per chunk (including entity extraction)
 * - Query: <50ms for 1-hop graph expansion
 * - Storage: ~10% overhead vs vector-only database
 */
public class KnowledgeGraphDatabase extends SQLiteOpenHelper {
    private static final String TAG = "OfflineAI_GraphDB";
    
    // Database info
    private static final String DB_NAME = "knowledge_graph.db";
    private static final int DB_VERSION = 1;
    
    // Table names
    private static final String TABLE_DOCUMENTS = "documents";
    private static final String TABLE_ENTITIES = "entities";
    private static final String TABLE_EDGES = "entity_edges";
    private static final String TABLE_CHUNK_ENTITIES = "chunk_entities";
    private static final String TABLE_METADATA = "metadata";
    
    // Context
    private final Context context;
    private final String collection;
    
    // Write lock to reduce SQLite lock contention in multi-threaded scenarios
    private final Object DB_WRITE_LOCK = new Object();
    
    /**
     * Constructor
     * @param context Android context
     * @param dbPath Database file path
     * @param collection Knowledge base collection name
     */
    public KnowledgeGraphDatabase(Context context, String dbPath, String collection) {
        super(context, dbPath, null, DB_VERSION);
        this.context = context;
        this.collection = collection;
        LogManager.logI(TAG, String.format("Initialized GraphDB: path=%s, collection=%s", dbPath, collection));
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        LogManager.logI(TAG, "Creating new knowledge graph database schema...");
        long startTime = System.currentTimeMillis();
        
        // 1. Documents table (text chunks + vectors)
        db.execSQL("CREATE TABLE " + TABLE_DOCUMENTS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "collection TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "source TEXT, " +
            "metadata TEXT, " +
            "embedding BLOB NOT NULL, " +
            "created_at INTEGER DEFAULT (strftime('%s', 'now'))" +
            ")");
        db.execSQL("CREATE INDEX idx_doc_collection ON " + TABLE_DOCUMENTS + "(collection)");
        
        // 2. Entities table (unique entities with stats)
        db.execSQL("CREATE TABLE " + TABLE_ENTITIES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "collection TEXT NOT NULL, " +
            "entity_text TEXT NOT NULL, " +
            "entity_type TEXT NOT NULL, " +  // person/location/organization
            "language TEXT, " +                // zh/en/mixed
            "frequency INTEGER DEFAULT 1, " +  // Total occurrence count
            "avg_confidence REAL, " +          // Average confidence score
            "first_seen INTEGER DEFAULT (strftime('%s', 'now')), " +
            "last_seen INTEGER DEFAULT (strftime('%s', 'now')), " +
            "UNIQUE(collection, entity_text, entity_type)" +
            ")");
        db.execSQL("CREATE INDEX idx_entity_text ON " + TABLE_ENTITIES + "(entity_text)");
        db.execSQL("CREATE INDEX idx_entity_type ON " + TABLE_ENTITIES + "(entity_type)");
        db.execSQL("CREATE INDEX idx_entity_collection ON " + TABLE_ENTITIES + "(collection)");
        db.execSQL("CREATE INDEX idx_entity_freq ON " + TABLE_ENTITIES + "(frequency DESC)");
        
        // 3. Entity edges table (co-occurrence graph)
        db.execSQL("CREATE TABLE " + TABLE_EDGES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "collection TEXT NOT NULL, " +
            "from_entity TEXT NOT NULL, " +
            "to_entity TEXT NOT NULL, " +
            "weight INTEGER DEFAULT 1, " +     // Co-occurrence count
            "chunk_ids TEXT, " +                // JSON array of chunk IDs
            "created_at INTEGER DEFAULT (strftime('%s', 'now')), " +
            "updated_at INTEGER DEFAULT (strftime('%s', 'now')), " +
            "UNIQUE(collection, from_entity, to_entity)" +
            ")");
        db.execSQL("CREATE INDEX idx_edge_from ON " + TABLE_EDGES + "(from_entity)");
        db.execSQL("CREATE INDEX idx_edge_to ON " + TABLE_EDGES + "(to_entity)");
        db.execSQL("CREATE INDEX idx_edge_collection ON " + TABLE_EDGES + "(collection)");
        db.execSQL("CREATE INDEX idx_edge_weight ON " + TABLE_EDGES + "(weight DESC)");
        
        // 4. Chunk-Entity mapping table (many-to-many)
        db.execSQL("CREATE TABLE " + TABLE_CHUNK_ENTITIES + " (" +
            "chunk_id INTEGER NOT NULL, " +
            "entity_text TEXT NOT NULL, " +
            "entity_type TEXT NOT NULL, " +
            "confidence REAL, " +
            "FOREIGN KEY(chunk_id) REFERENCES " + TABLE_DOCUMENTS + "(id) ON DELETE CASCADE, " +
            "PRIMARY KEY(chunk_id, entity_text, entity_type)" +
            ")");
        db.execSQL("CREATE INDEX idx_ce_chunk ON " + TABLE_CHUNK_ENTITIES + "(chunk_id)");
        db.execSQL("CREATE INDEX idx_ce_entity ON " + TABLE_CHUNK_ENTITIES + "(entity_text)");
        
        // 5. Metadata table (database info)
        db.execSQL("CREATE TABLE " + TABLE_METADATA + " (" +
            "key TEXT PRIMARY KEY, " +
            "value TEXT" +
            ")");
        
        // Insert initial metadata
        ContentValues meta = new ContentValues();
        meta.put("key", "schema_version");
        meta.put("value", String.valueOf(DB_VERSION));
        db.insert(TABLE_METADATA, null, meta);
        
        meta = new ContentValues();
        meta.put("key", "created_at");
        meta.put("value", String.valueOf(System.currentTimeMillis()));
        db.insert(TABLE_METADATA, null, meta);
        
        long duration = System.currentTimeMillis() - startTime;
        LogManager.logI(TAG, String.format("[DB_CREATE] Schema created in %dms", duration));
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        LogManager.logW(TAG, String.format("Upgrading database from v%d to v%d", oldVersion, newVersion));
        // Future: Add migration logic here
        // For now: Drop and recreate (acceptable for MVP)
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHUNK_ENTITIES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_EDGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ENTITIES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DOCUMENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_METADATA);
        onCreate(db);
    }
    
    // ========== Document Operations ==========
    
    /**
     * Add document chunk with vector
     * @return Chunk ID (for entity linking)
     */
    public long addChunk(String content, String source, float[] embedding, String metadata) {
        SQLiteDatabase db = getWritableDatabase();
        long startTime = System.currentTimeMillis();
        
        try {
            ContentValues values = new ContentValues();
            values.put("collection", collection);
            values.put("content", content);
            values.put("source", source);
            values.put("metadata", metadata);
            values.put("embedding", vectorToBlob(embedding));
            
            long chunkId = db.insert(TABLE_DOCUMENTS, null, values);
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, String.format("[ADD_CHUNK] id=%d, size=%d chars, vector_dim=%d, time=%dms", 
                chunkId, content.length(), embedding.length, duration));
            
            return chunkId;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[ADD_CHUNK] Failed", e);
            return -1;
        }
    }
    
    /**
     * Search similar chunks by vector (cosine similarity)
     */
    public List<SearchResult> searchSimilar(float[] queryVector, int topK) {
        SQLiteDatabase db = getReadableDatabase();
        long startTime = System.currentTimeMillis();
        List<SearchResult> results = new ArrayList<>();
        
        try {
            Cursor cursor = db.query(TABLE_DOCUMENTS,
                new String[]{"id", "content", "source", "embedding"},
                "collection=?",
                new String[]{collection},
                null, null, null);
            
            List<ScoredResult> scored = new ArrayList<>();
            
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String content = cursor.getString(1);
                String source = cursor.getString(2);
                byte[] embeddingBlob = cursor.getBlob(3);
                
                float[] embedding = blobToVector(embeddingBlob);
                float similarity = cosineSimilarity(queryVector, embedding);
                
                scored.add(new ScoredResult(id, content, source, similarity));
            }
            cursor.close();
            
            // Sort by similarity (descending)
            scored.sort((a, b) -> Float.compare(b.similarity, a.similarity));
            
            // Take top K
            int resultCount = Math.min(topK, scored.size());
            for (int i = 0; i < resultCount; i++) {
                ScoredResult sr = scored.get(i);
                results.add(new SearchResult(sr.id, sr.content, sr.source, sr.similarity));
            }
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format("[VECTOR_SEARCH] topK=%d, scanned=%d chunks, time=%dms", 
                topK, scored.size(), duration));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[VECTOR_SEARCH] Failed", e);
        }
        
        return results;
    }
    
    // ========== Entity Operations ==========
    
    /**
     * Add or update entity (upsert)
     * TODO: Phase 6 - Update to use new Entity class
     */
    /**
     * Add or update entity (Phase 6 - Re-enabled)
     * @param entity LlmNerHandler.NerResult.Entity object
     */
    public void addOrUpdateEntity(Object entity) {
        // Cast to HanLpNerHandler.NerResult.Entity
        if (!(entity instanceof HanLpNerHandler.NerResult.Entity)) {
            LogManager.logE(TAG, "[ADD_ENTITY] Invalid entity type: " + entity.getClass().getName());
            return;
        }
        
        HanLpNerHandler.NerResult.Entity e = (HanLpNerHandler.NerResult.Entity) entity;
        
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            // Check if entity exists
            Cursor cursor = db.query(TABLE_ENTITIES,
                new String[]{"id", "frequency", "avg_confidence"},
                "collection=? AND entity_text=? AND entity_type=?",
                new String[]{collection, e.text, e.type},
                null, null, null);
            
            if (cursor.moveToFirst()) {
                // Update existing entity
                long id = cursor.getLong(0);
                int oldFreq = cursor.getInt(1);
                float oldConf = cursor.getFloat(2);
                
                int newFreq = oldFreq + 1;
                float newConf = (oldConf * oldFreq + e.confidence) / newFreq;
                
                ContentValues values = new ContentValues();
                values.put("frequency", newFreq);
                values.put("avg_confidence", newConf);
                values.put("last_seen", System.currentTimeMillis() / 1000);
                
                db.update(TABLE_ENTITIES, values, "id=?", new String[]{String.valueOf(id)});
                
            } else {
                // Insert new entity
                ContentValues values = new ContentValues();
                values.put("collection", collection);
                values.put("entity_text", e.text);
                values.put("entity_type", e.type);
                values.put("language", "zh");  // Default to Chinese
                values.put("frequency", 1);
                values.put("avg_confidence", e.confidence);
                
                long id = db.insert(TABLE_ENTITIES, null, values);
            }
            
            cursor.close();
            
        } catch (Exception ex) {
            LogManager.logE(TAG, "[ADD_ENTITY] Failed for: " + e.text, ex);
        }
    }
    
    /**
     * Link chunk to entities (Phase 6 - Re-enabled)
     * @param chunkId Chunk ID
     * @param entities List of HanLpNerHandler.NerResult.Entity objects
     */
    public void linkChunkToEntities(long chunkId, Object entities) {
        // Cast to List<HanLpNerHandler.NerResult.Entity>
        if (!(entities instanceof java.util.List)) {
            LogManager.logE(TAG, "[LINK_ENTITIES] Invalid entities type: " + entities.getClass().getName());
            return;
        }
        
        @SuppressWarnings("unchecked")
        java.util.List<HanLpNerHandler.NerResult.Entity> entityList = 
            (java.util.List<HanLpNerHandler.NerResult.Entity>) entities;
        
        SQLiteDatabase db = getWritableDatabase();
        long startTime = System.currentTimeMillis();
        
        try {
            db.beginTransaction();
            
            for (HanLpNerHandler.NerResult.Entity entity : entityList) {
                ContentValues values = new ContentValues();
                values.put("chunk_id", chunkId);
                values.put("entity_text", entity.text);
                values.put("entity_type", entity.type);
                values.put("confidence", entity.confidence);
                
                db.insertWithOnConflict(TABLE_CHUNK_ENTITIES, null, values, 
                    SQLiteDatabase.CONFLICT_IGNORE);
            }
            
            db.setTransactionSuccessful();
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, String.format("[LINK_ENTITIES] chunk_id=%d, entities=%d, time=%dms", 
                chunkId, entityList.size(), duration));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[LINK_ENTITIES] Failed", e);
        } finally {
            db.endTransaction();
        }
    }
    
    // ========== New Entity Operations (Phase 6) ==========
    
    /**
     * Add entity to graph (new implementation)
     * @param text Entity text
     * @param type Entity type code
     * @param confidence Confidence score
     * @return Entity ID
     */
    public long addEntity(String text, String type, float confidence) {
        // Synchronize to reduce SQLite lock contention
        synchronized (DB_WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            
            try {
            // Check if entity exists
            Cursor cursor = db.query(TABLE_ENTITIES,
                new String[]{"id", "frequency", "avg_confidence"},
                "collection=? AND entity_text=? AND entity_type=?",
                new String[]{collection, text, type},
                null, null, null);
            
            if (cursor.moveToFirst()) {
                // Update existing entity
                long id = cursor.getLong(0);
                int oldFreq = cursor.getInt(1);
                float oldConf = cursor.getFloat(2);
                
                int newFreq = oldFreq + 1;
                float newConf = (oldConf * oldFreq + confidence) / newFreq;
                
                ContentValues values = new ContentValues();
                values.put("frequency", newFreq);
                values.put("avg_confidence", newConf);
                values.put("last_seen", System.currentTimeMillis() / 1000);
                
                db.update(TABLE_ENTITIES, values, "id=?", new String[]{String.valueOf(id)});
                cursor.close();
                return id;
                
            } else {
                cursor.close();
                
                // Insert new entity
                ContentValues values = new ContentValues();
                values.put("collection", collection);
                values.put("entity_text", text);
                values.put("entity_type", type);
                values.put("language", "zh"); // Default language
                values.put("frequency", 1);
                values.put("avg_confidence", confidence);
                values.put("first_seen", System.currentTimeMillis() / 1000);
                values.put("last_seen", System.currentTimeMillis() / 1000);
                
                long id = db.insert(TABLE_ENTITIES, null, values);
                return id;
            }
            
            } catch (Exception e) {
                LogManager.logE(TAG, "[ADD_ENTITY] Failed for: " + text, e);
                return -1;
            }
        }
    }
    
    /**
     * Link chunk to entity
     * @param chunkId Chunk ID
     * @param entityText Entity text
     * @param entityType Entity type
     * @param confidence Confidence score
     */
    public void linkChunkToEntity(long chunkId, String entityText, String entityType, float confidence) {
        synchronized (DB_WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            
            try {
            ContentValues values = new ContentValues();
            values.put("chunk_id", chunkId);
            values.put("entity_text", entityText);
            values.put("entity_type", entityType);
            values.put("confidence", confidence);
            
            db.insertWithOnConflict(TABLE_CHUNK_ENTITIES, null, values, 
                SQLiteDatabase.CONFLICT_IGNORE);
            
            } catch (Exception e) {
                LogManager.logE(TAG, "[LINK_ENTITY] Failed", e);
            }
        }
    }
    
    /**
     * Add edge between entities
     * @param fromEntityId From entity ID
     * @param toEntityId To entity ID
     * @param weight Edge weight
     */
    public void addEdge(long fromEntityId, long toEntityId, float weight) {
        synchronized (DB_WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            
            try {
            // Get entity texts
            String fromText = getEntityText(fromEntityId);
            String toText = getEntityText(toEntityId);
            
            if (fromText == null || toText == null) {
                return;
            }
            
            // Ensure consistent ordering (smaller ID first)
            if (fromEntityId > toEntityId) {
                long temp = fromEntityId;
                fromEntityId = toEntityId;
                toEntityId = temp;
                
                String tempText = fromText;
                fromText = toText;
                toText = tempText;
            }
            
            // Check if edge exists
            Cursor cursor = db.query(TABLE_EDGES,
                new String[]{"id", "weight"},
                "collection=? AND from_entity=? AND to_entity=?",
                new String[]{collection, fromText, toText},
                null, null, null);
            
            if (cursor.moveToFirst()) {
                // Update existing edge
                long id = cursor.getLong(0);
                float oldWeight = cursor.getFloat(1);
                float newWeight = oldWeight + weight;
                
                ContentValues values = new ContentValues();
                values.put("weight", newWeight);
                
                db.update(TABLE_EDGES, values, "id=?", new String[]{String.valueOf(id)});
                cursor.close();
                
            } else {
                cursor.close();
                
                // Insert new edge
                ContentValues values = new ContentValues();
                values.put("collection", collection);
                values.put("from_entity", fromText);
                values.put("to_entity", toText);
                values.put("weight", weight);
                // Note: edge_type removed - not in schema
                
                db.insert(TABLE_EDGES, null, values);
            }
            
            } catch (Exception e) {
                LogManager.logE(TAG, "[ADD_EDGE] Failed", e);
            }
        }
    }
    
    /**
     * Get entity text by ID
     */
    private String getEntityText(long entityId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_ENTITIES,
            new String[]{"entity_text"},
            "id=?",
            new String[]{String.valueOf(entityId)},
            null, null, null);
        
        if (cursor.moveToFirst()) {
            String text = cursor.getString(0);
            cursor.close();
            return text;
        }
        
        cursor.close();
        return null;
    }
    
    // ========== Graph Operations ==========
    
    /**
     * Build co-occurrence edges for a chunk
     * All entities in the same chunk are connected (complete graph)
     * TODO: Phase 6 - Update to use new Entity class
     */
    @Deprecated
    public void buildEdges(long chunkId, Object entities) {
        // TODO: Phase 6 - Re-implement with new Entity class
        throw new UnsupportedOperationException("Temporarily disabled - Phase 6");
        /*
        if (entities.size() < 2) {
            return; // Need at least 2 entities to form an edge
        }
        
        SQLiteDatabase db = getWritableDatabase();
        long startTime = System.currentTimeMillis();
        int edgeCount = 0;
        
        try {
            db.beginTransaction();
            
            // Build edges between all pairs (complete graph)
            for (int i = 0; i < entities.size(); i++) {
                for (int j = i + 1; j < entities.size(); j++) {
                    String entity1 = entities.get(i).text;
                    String entity2 = entities.get(j).text;
                    
                    // Ensure consistent ordering (alphabetical)
                    String from = entity1.compareTo(entity2) < 0 ? entity1 : entity2;
                    String to = entity1.compareTo(entity2) < 0 ? entity2 : entity1;
                    
                    addOrUpdateEdge(db, from, to, chunkId);
                    edgeCount++;
                }
            }
            
            db.setTransactionSuccessful();
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format("[BUILD_EDGES] chunk_id=%d, entities=%d, edges=%d, time=%dms", 
                chunkId, entities.size(), edgeCount, duration));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[BUILD_EDGES] Failed", e);
        } finally {
            db.endTransaction();
        }
        */
    }
    
    /**
     * Add or update edge (upsert)
     */
    private void addOrUpdateEdge(SQLiteDatabase db, String from, String to, long chunkId) {
        try {
            // Check if edge exists
            Cursor cursor = db.query(TABLE_EDGES,
                new String[]{"id", "weight", "chunk_ids"},
                "collection=? AND from_entity=? AND to_entity=?",
                new String[]{collection, from, to},
                null, null, null);
            
            if (cursor.moveToFirst()) {
                // Update existing edge
                long id = cursor.getLong(0);
                int oldWeight = cursor.getInt(1);
                String chunkIdsJson = cursor.getString(2);
                
                // Parse chunk IDs
                JSONArray chunkIds = new JSONArray();
                if (chunkIdsJson != null && !chunkIdsJson.isEmpty()) {
                    chunkIds = new JSONArray(chunkIdsJson);
                }
                chunkIds.put(chunkId);
                
                ContentValues values = new ContentValues();
                values.put("weight", oldWeight + 1);
                values.put("chunk_ids", chunkIds.toString());
                values.put("updated_at", System.currentTimeMillis() / 1000);
                
                db.update(TABLE_EDGES, values, "id=?", new String[]{String.valueOf(id)});
                
                LogManager.logD(TAG, String.format("[UPDATE_EDGE] '%s'-'%s' weight: %d→%d", 
                    from, to, oldWeight, oldWeight + 1));
                
            } else {
                // Insert new edge
                JSONArray chunkIds = new JSONArray();
                chunkIds.put(chunkId);
                
                ContentValues values = new ContentValues();
                values.put("collection", collection);
                values.put("from_entity", from);
                values.put("to_entity", to);
                values.put("weight", 1);
                values.put("chunk_ids", chunkIds.toString());
                
                long id = db.insert(TABLE_EDGES, null, values);
                
                LogManager.logD(TAG, String.format("[ADD_EDGE] id=%d, '%s'-'%s'", id, from, to));
            }
            
            cursor.close();
            
        } catch (Exception e) {
            LogManager.logE(TAG, String.format("[ADD_EDGE] Failed for '%s'-'%s'", from, to), e);
        }
    }
    
    /**
     * Get connected entities (1-hop expansion)
     * @param seedEntities Starting entities
     * @param minWeight Minimum edge weight (filter low-confidence edges)
     * @param maxResults Maximum number of results
     * @return List of connected entities with weights
     */
    public List<ConnectedEntity> getConnectedEntities(Set<String> seedEntities, int minWeight, int maxResults) {
        SQLiteDatabase db = getReadableDatabase();
        long startTime = System.currentTimeMillis();
        List<ConnectedEntity> connected = new ArrayList<>();
        
        if (seedEntities.isEmpty()) {
            return connected;
        }
        
        try {
            // Build query with placeholders
            String placeholders = String.join(",", Collections.nCopies(seedEntities.size(), "?"));
            String query = "SELECT to_entity, weight FROM " + TABLE_EDGES + 
                          " WHERE collection=? AND from_entity IN (" + placeholders + ") AND weight >= ?" +
                          " UNION " +
                          "SELECT from_entity, weight FROM " + TABLE_EDGES +
                          " WHERE collection=? AND to_entity IN (" + placeholders + ") AND weight >= ?" +
                          " ORDER BY weight DESC LIMIT ?";
            
            // Build arguments
            List<String> args = new ArrayList<>();
            args.add(collection);
            args.addAll(seedEntities);
            args.add(String.valueOf(minWeight));
            args.add(collection);
            args.addAll(seedEntities);
            args.add(String.valueOf(minWeight));
            args.add(String.valueOf(maxResults));
            
            Cursor cursor = db.rawQuery(query, args.toArray(new String[0]));
            
            while (cursor.moveToNext()) {
                String entityText = cursor.getString(0);
                int weight = cursor.getInt(1);
                
                // Skip if already in seed set
                if (!seedEntities.contains(entityText)) {
                    connected.add(new ConnectedEntity(entityText, weight));
                }
            }
            cursor.close();
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format("[GRAPH_EXPAND] seeds=%d, min_weight=%d, found=%d entities, time=%dms", 
                seedEntities.size(), minWeight, connected.size(), duration));
            
            // Detailed logging
            if (connected.size() > 0) {
                StringBuilder sb = new StringBuilder("[GRAPH_EXPAND] Connected: ");
                for (int i = 0; i < Math.min(10, connected.size()); i++) {
                    ConnectedEntity ce = connected.get(i);
                    sb.append(String.format("[%s:w=%d] ", ce.entityText, ce.weight));
                }
                if (connected.size() > 10) {
                    sb.append("...");
                }
                LogManager.logD(TAG, sb.toString());
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GRAPH_EXPAND] Failed", e);
        }
        
        return connected;
    }
    
    /**
     * Get chunks by entities
     */
    public List<Long> getChunkIdsByEntities(List<String> entities) {
        SQLiteDatabase db = getReadableDatabase();
        long startTime = System.currentTimeMillis();
        Set<Long> chunkIds = new HashSet<>();
        
        if (entities.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            String placeholders = String.join(",", Collections.nCopies(entities.size(), "?"));
            String query = "SELECT DISTINCT chunk_id FROM " + TABLE_CHUNK_ENTITIES +
                          " WHERE entity_text IN (" + placeholders + ")";
            
            Cursor cursor = db.rawQuery(query, entities.toArray(new String[0]));
            
            while (cursor.moveToNext()) {
                chunkIds.add(cursor.getLong(0));
            }
            cursor.close();
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format("[GET_CHUNKS_BY_ENTITIES] entities=%d, chunks=%d, time=%dms", 
                entities.size(), chunkIds.size(), duration));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GET_CHUNKS_BY_ENTITIES] Failed", e);
        }
        
        return new ArrayList<>(chunkIds);
    }
    
    /**
     * Get chunk details by IDs
     */
    public List<SearchResult> getChunksByIds(List<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        SQLiteDatabase db = getReadableDatabase();
        long startTime = System.currentTimeMillis();
        List<SearchResult> results = new ArrayList<>();
        
        try {
            String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
            String query = "SELECT id, content, source FROM " + TABLE_DOCUMENTS +
                          " WHERE id IN (" + placeholders + ")";
            
            String[] args = new String[chunkIds.size()];
            for (int i = 0; i < chunkIds.size(); i++) {
                args[i] = String.valueOf(chunkIds.get(i));
            }
            
            Cursor cursor = db.rawQuery(query, args);
            
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String content = cursor.getString(1);
                String source = cursor.getString(2);
                
                results.add(new SearchResult(id, content, source, 0.0f)); // Similarity not applicable here
            }
            cursor.close();
            
            long duration = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, String.format("[GET_CHUNKS_BY_IDS] ids=%d, found=%d, time=%dms", 
                chunkIds.size(), results.size(), duration));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[GET_CHUNKS_BY_IDS] Failed", e);
        }
        
        return results;
    }
    
    /**
     * Get entity texts for a list of chunk IDs
     */
    public Map<Long, List<String>> getEntitiesForChunks(List<Long> chunkIds) {
        Map<Long, List<String>> result = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return result;
        }

        SQLiteDatabase db = getReadableDatabase();
        long startTime = System.currentTimeMillis();

        try {
            String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
            String query = "SELECT chunk_id, entity_text FROM " + TABLE_CHUNK_ENTITIES +
                           " WHERE chunk_id IN (" + placeholders + ")";

            String[] args = new String[chunkIds.size()];
            for (int i = 0; i < chunkIds.size(); i++) {
                args[i] = String.valueOf(chunkIds.get(i));
            }

            Cursor cursor = db.rawQuery(query, args);

            while (cursor.moveToNext()) {
                long chunkId = cursor.getLong(0);
                String entityText = cursor.getString(1);

                List<String> list = result.get(chunkId);
                if (list == null) {
                    list = new ArrayList<>();
                    result.put(chunkId, list);
                }
                list.add(entityText);
            }
            cursor.close();

            long duration = System.currentTimeMillis() - startTime;
            LogManager.logD(TAG, String.format("[GET_ENTITIES_FOR_CHUNKS] chunks=%d, time=%dms", 
                chunkIds.size(), duration));

        } catch (Exception e) {
            LogManager.logE(TAG, "[GET_ENTITIES_FOR_CHUNKS] Failed", e);
        }

        return result;
    }

    /**
     * Get database statistics (for debugging and evaluation)
     */
    public DatabaseStats getStats() {
        SQLiteDatabase db = getReadableDatabase();
        DatabaseStats stats = new DatabaseStats();
        
        try {
            // Count documents
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_DOCUMENTS + 
                " WHERE collection=?", new String[]{collection});
            if (cursor.moveToFirst()) {
                stats.chunkCount = cursor.getInt(0);
            }
            cursor.close();
            
            // Count entities
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_ENTITIES + 
                " WHERE collection=?", new String[]{collection});
            if (cursor.moveToFirst()) {
                stats.entityCount = cursor.getInt(0);
            }
            cursor.close();
            
            // Count edges
            cursor = db.rawQuery("SELECT COUNT(*), SUM(weight) FROM " + TABLE_EDGES + 
                " WHERE collection=?", new String[]{collection});
            if (cursor.moveToFirst()) {
                stats.edgeCount = cursor.getInt(0);
                stats.totalEdgeWeight = cursor.getInt(1);
            }
            cursor.close();
            
            // Entity type distribution
            cursor = db.rawQuery("SELECT entity_type, COUNT(*) FROM " + TABLE_ENTITIES + 
                " WHERE collection=? GROUP BY entity_type", new String[]{collection});
            while (cursor.moveToNext()) {
                String type = cursor.getString(0);
                int count = cursor.getInt(1);
                stats.entityTypeDistribution.put(type, count);
            }
            cursor.close();
            
            // Top entities by frequency
            cursor = db.rawQuery("SELECT entity_text, frequency FROM " + TABLE_ENTITIES + 
                " WHERE collection=? ORDER BY frequency DESC LIMIT 10", new String[]{collection});
            while (cursor.moveToNext()) {
                String text = cursor.getString(0);
                int freq = cursor.getInt(1);
                stats.topEntities.add(text + "(" + freq + ")");
            }
            cursor.close();
            
            LogManager.logI(TAG, String.format("[STATS] chunks=%d, entities=%d, edges=%d, total_weight=%d", 
                stats.chunkCount, stats.entityCount, stats.edgeCount, stats.totalEdgeWeight));
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[STATS] Failed", e);
        }
        
        return stats;
    }

    /**
     * Apply hub threshold filtering on entities.
     * Entities whose neighbor degree or total edge weight exceed the threshold
     * will be treated as hubs and removed together with their edges and chunk mappings.
     * @param threshold Hub threshold (0 or negative = disabled)
     * @return Number of hub entity texts removed
     */
    public int applyHubThreshold(int threshold) {
        if (threshold <= 0) {
            LogManager.logD(TAG, String.format("[HUB_FILTER] Threshold <= 0, skip hub filtering (threshold=%d)", threshold));
            return 0;
        }

        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = null;
        int hubCount = 0;
        long startTime = System.currentTimeMillis();

        try {
            // Compute neighbor degree and total edge weight per entity_text
            String hubQuery =
                "SELECT entity_text, COUNT(DISTINCT neighbor) AS degree, SUM(weight) AS total_weight " +
                "FROM (" +
                "  SELECT from_entity AS entity_text, to_entity AS neighbor, weight FROM " + TABLE_EDGES + " WHERE collection=? " +
                "  UNION ALL " +
                "  SELECT to_entity AS entity_text, from_entity AS neighbor, weight FROM " + TABLE_EDGES + " WHERE collection=?" +
                ") AS edges " +
                "GROUP BY entity_text";

            cursor = db.rawQuery(hubQuery, new String[]{collection, collection});

            List<String> hubEntities = new ArrayList<>();
            while (cursor.moveToNext()) {
                String entityText = cursor.getString(0);
                int degree = cursor.getInt(1);
                int totalWeight = cursor.getInt(2);

                if (degree >= threshold || totalWeight >= threshold) {
                    hubEntities.add(entityText);
                }
            }
            cursor.close();
            cursor = null;

            if (hubEntities.isEmpty()) {
                LogManager.logD(TAG, String.format("[HUB_FILTER] No hubs found for threshold=%d", threshold));
                return 0;
            }

            db.beginTransaction();

            // Remove hub entities and related edges/mappings
            for (String hub : hubEntities) {
                // Delete from entities table (current collection only)
                int entityRows = db.delete(TABLE_ENTITIES,
                    "collection=? AND entity_text=?",
                    new String[]{collection, hub});

                // Delete chunk-entity mappings (single-collection DB, no collection column)
                int chunkEntityRows = db.delete(TABLE_CHUNK_ENTITIES,
                    "entity_text=?",
                    new String[]{hub});

                // Delete edges that involve this entity as from/to
                int edgeRows = db.delete(TABLE_EDGES,
                    "collection=? AND (from_entity=? OR to_entity=?)",
                    new String[]{collection, hub, hub});

                hubCount++;

                if (hubCount <= 10) {
                    LogManager.logD(TAG, String.format(
                        "[HUB_FILTER] Removed hub '%s': entities=%d, chunk_entities=%d, edges=%d",
                        hub, entityRows, chunkEntityRows, edgeRows));
                }
            }

            db.setTransactionSuccessful();

            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format(
                "[HUB_FILTER] Completed: threshold=%d, hubs=%d, time=%dms",
                threshold, hubCount, duration));

        } catch (Exception e) {
            LogManager.logE(TAG, "[HUB_FILTER] Failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            try {
                db.endTransaction();
            } catch (Exception ignore) {
            }
        }

        return hubCount;
    }
    
    /**
     * Query hub entities by threshold without mutating the database.
     * This is used for query-time hub filtering in Graph RAG to ignore
     * super-entities during graph expansion and scoring.
     * @param threshold Hub threshold (0 or negative = disabled)
     * @return Set of entity_text values considered hubs
     */
    public java.util.Set<String> getHubEntities(int threshold) {
        java.util.Set<String> hubEntities = new java.util.HashSet<>();
        if (threshold <= 0) {
            LogManager.logD(TAG, String.format("[HUB_FILTER_QUERY] Threshold <= 0, skip hub query (threshold=%d)", threshold));
            return hubEntities;
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        long startTime = System.currentTimeMillis();

        try {
            String hubQuery =
                "SELECT entity_text, COUNT(DISTINCT neighbor) AS degree, SUM(weight) AS total_weight " +
                "FROM (" +
                "  SELECT from_entity AS entity_text, to_entity AS neighbor, weight FROM " + TABLE_EDGES + " WHERE collection=? " +
                "  UNION ALL " +
                "  SELECT to_entity AS entity_text, from_entity AS neighbor, weight FROM " + TABLE_EDGES + " WHERE collection=?" +
                ") AS edges " +
                "GROUP BY entity_text";

            cursor = db.rawQuery(hubQuery, new String[]{collection, collection});

            int hubCount = 0;
            while (cursor.moveToNext()) {
                String entityText = cursor.getString(0);
                int degree = cursor.getInt(1);
                int totalWeight = cursor.getInt(2);

                if (degree >= threshold || totalWeight >= threshold) {
                    hubEntities.add(entityText);
                    hubCount++;

                    if (hubCount <= 10) {
                        LogManager.logD(TAG, String.format(
                            "[HUB_FILTER_QUERY] Hub candidate '%s': degree=%d, totalWeight=%d",
                            entityText, degree, totalWeight));
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            LogManager.logI(TAG, String.format(
                "[HUB_FILTER_QUERY] Completed: threshold=%d, hubs=%d, time=%dms",
                threshold, hubEntities.size(), duration));

        } catch (Exception e) {
            LogManager.logE(TAG, "[HUB_FILTER_QUERY] Failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return hubEntities;
    }
    
    // ========== Utility Methods ==========
    
    private byte[] vectorToBlob(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }
    
    private float[] blobToVector(byte[] blob) {
        float[] vector = new float[blob.length / 4];
        ByteBuffer.wrap(blob)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(vector);
        return vector;
    }
    
    private float cosineSimilarity(float[] a, float[] b) {
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    // ========== Data Classes ==========
    
    public static class SearchResult {
        public long id;
        public String content;
        public String source;
        public float similarity;
        
        public SearchResult(long id, String content, String source, float similarity) {
            this.id = id;
            this.content = content;
            this.source = source;
            this.similarity = similarity;
        }
    }
    
    private static class ScoredResult {
        long id;
        String content;
        String source;
        float similarity;
        
        ScoredResult(long id, String content, String source, float similarity) {
            this.id = id;
            this.content = content;
            this.source = source;
            this.similarity = similarity;
        }
    }
    
    /**
     * Get raw database for custom queries
     * WARNING: Use with caution, prefer typed methods
     */
    public SQLiteDatabase getDatabase() {
        return getReadableDatabase();
    }
    
    /**
     * Get collection name
     * @return Collection name
     */
    public String getCollection() {
        return collection;
    }
    
    public static class ConnectedEntity {
        public String entityText;
        public int weight;
        
        public ConnectedEntity(String entityText, int weight) {
            this.entityText = entityText;
            this.weight = weight;
        }
    }
    
    public static class DatabaseStats {
        public int chunkCount = 0;
        public int entityCount = 0;
        public int edgeCount = 0;
        public int totalEdgeWeight = 0;
        public Map<String, Integer> entityTypeDistribution = new HashMap<>();
        public List<String> topEntities = new ArrayList<>();
        
        @Override
        public String toString() {
            return String.format("Stats{chunks=%d, entities=%d, edges=%d, weight=%d, types=%s, top=%s}",
                chunkCount, entityCount, edgeCount, totalEdgeWeight, 
                entityTypeDistribution, topEntities);
        }
    }
    
    // ========== Metadata Management (Compatibility API) ==========
    
    /**
     * Database metadata for embedding model info
     */
    public static class DatabaseMetadata {
        private String embeddingModel;
        private String modeldir;
        private String rerankerdir;
        private int embeddingDimension = 768;
        
        public DatabaseMetadata(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
        
        public String getEmbeddingModel() {
            return embeddingModel;
        }
        
        public String getModeldir() {
            return modeldir;
        }
        
        public void setModeldir(String modeldir) {
            this.modeldir = modeldir;
        }
        
        public String getRerankerdir() {
            return rerankerdir;
        }
        
        public void setRerankerdir(String rerankerdir) {
            this.rerankerdir = rerankerdir;
        }
        
        public int getEmbeddingDimension() {
            return embeddingDimension;
        }
        
        public void setEmbeddingDimension(int dimension) {
            this.embeddingDimension = dimension;
        }
    }
    
    /**
     * Get database metadata
     * @return DatabaseMetadata object
     */
    public DatabaseMetadata getMetadata() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            DatabaseMetadata metadata = new DatabaseMetadata("unknown");
            
            cursor = db.query(TABLE_METADATA, 
                new String[]{"key", "value"}, 
                null, null, null, null, null);
            
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                
                switch (key) {
                    case "embedding_model":
                        metadata.embeddingModel = value;
                        break;
                    case "modeldir":
                        metadata.setModeldir(value);
                        break;
                    case "rerankerdir":
                        metadata.setRerankerdir(value);
                        break;
                    case "embedding_dimension":
                        try {
                            metadata.setEmbeddingDimension(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            LogManager.logW(TAG, "Invalid embedding_dimension: " + value);
                        }
                        break;
                }
            }
            
            return metadata;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get metadata", e);
            return new DatabaseMetadata("unknown");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
    
    /**
     * Update database metadata
     * @param metadata DatabaseMetadata object
     * @return true if successful
     */
    public boolean updateMetadata(DatabaseMetadata metadata) {
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            db.beginTransaction();
            
            // Update embedding_model
            if (metadata.getEmbeddingModel() != null) {
                ContentValues values = new ContentValues();
                values.put("key", "embedding_model");
                values.put("value", metadata.getEmbeddingModel());
                db.insertWithOnConflict(TABLE_METADATA, null, values, 
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            
            // Update modeldir
            if (metadata.getModeldir() != null) {
                ContentValues values = new ContentValues();
                values.put("key", "modeldir");
                values.put("value", metadata.getModeldir());
                db.insertWithOnConflict(TABLE_METADATA, null, values, 
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            
            // Update rerankerdir
            if (metadata.getRerankerdir() != null) {
                ContentValues values = new ContentValues();
                values.put("key", "rerankerdir");
                values.put("value", metadata.getRerankerdir());
                db.insertWithOnConflict(TABLE_METADATA, null, values, 
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            
            // Update embedding_dimension
            ContentValues values = new ContentValues();
            values.put("key", "embedding_dimension");
            values.put("value", String.valueOf(metadata.getEmbeddingDimension()));
            db.insertWithOnConflict(TABLE_METADATA, null, values, 
                SQLiteDatabase.CONFLICT_REPLACE);
            
            db.setTransactionSuccessful();
            LogManager.logD(TAG, "Metadata updated successfully");
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to update metadata", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }
    
    /**
     * Get chunk count in database
     * @return Number of chunks
     */
    public int getChunkCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_DOCUMENTS + " WHERE collection = ?",
                new String[]{collection}
            );
            
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get chunk count", e);
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
