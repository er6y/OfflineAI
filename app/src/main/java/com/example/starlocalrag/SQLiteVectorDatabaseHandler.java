package com.example.starlocalrag;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.AppConstants;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQLite vector database handler class, compatible with Windows Python-generated database format
 */
public class SQLiteVectorDatabaseHandler {
    private static final String TAG = "StarLocalR...teVectorDB";
    
    // Database file names
    private static final String DB_FILENAME = "vectorstore.db";
    private static final String METADATA_FILENAME = "metadata.json";
    
    // Database version
    private static final int DATABASE_VERSION = 1;
    
    // Database table name
    private static final String TABLE_DOCUMENTS = "documents";
    
    // Database column names
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_COLLECTION = "collection";
    private static final String COLUMN_CONTENT = "content";
    private static final String COLUMN_METADATA = "metadata";
    private static final String COLUMN_EMBEDDING = "embedding";
    
    // Database directory
    private final File databaseDir;
    private final Context context;
    
    // Database connection
    private SQLiteDatabase database;
    
    // Database metadata cache
    private DatabaseMetadata metadata;
    
    /**
     * Database metadata class
     */
    public static class DatabaseMetadata {
        private String embeddingModel;
        private int embeddingDimension;
        private int chunkCount;
        private List<String> sources;
        private List<String> files;
        private int fileCount;
        private long creationTimestamp;
        private long lastModifiedTimestamp;
        private String collection;
        private String modelType;
        private String vectorStoreType;
        private String modeldir;
        private String rerankerdir;
        
        public DatabaseMetadata(String embeddingModel) {
            this.embeddingModel = embeddingModel;
            this.embeddingDimension = 0;
            this.chunkCount = 0;
            this.sources = new ArrayList<>();
            this.files = new ArrayList<>();
            this.fileCount = 0;
            this.creationTimestamp = System.currentTimeMillis();
            this.lastModifiedTimestamp = this.creationTimestamp;
            this.collection = "";
            this.modelType = AppConstants.ApiUrl.LOCAL;
            this.vectorStoreType = "sqlite";
            this.modeldir = "";
            this.rerankerdir = "";
        }
        
        public String getEmbeddingModel() {
            return embeddingModel;
        }
        
        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
            this.lastModifiedTimestamp = System.currentTimeMillis();
        }
        
        public int getEmbeddingDimension() {
            return embeddingDimension;
        }
        
        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }
        
        public int getChunkCount() {
            return chunkCount;
        }
        
        public void incrementChunkCount() {
            this.chunkCount++;
            this.lastModifiedTimestamp = System.currentTimeMillis();
        }
        
        public List<String> getSources() {
            return sources;
        }
        
        public void addSource(String source) {
            if (!sources.contains(source)) {
                sources.add(source);
                this.lastModifiedTimestamp = System.currentTimeMillis();
            }
        }
        
        public List<String> getFiles() {
            return files;
        }
        
        public void addFile(String file) {
            if (!files.contains(file)) {
                files.add(file);
                this.fileCount++;
                this.lastModifiedTimestamp = System.currentTimeMillis();
            }
        }
        
        public int getFileCount() {
            return fileCount;
        }
        
        public long getCreationTimestamp() {
            return creationTimestamp;
        }
        
        public long getLastModifiedTimestamp() {
            return lastModifiedTimestamp;
        }
        
        public String getCollection() {
            return collection;
        }
        
        public void setCollection(String collection) {
            this.collection = collection;
        }
        
        public String getModelType() {
            return modelType;
        }
        
        public void setModelType(String modelType) {
            this.modelType = modelType;
        }
        
        public String getVectorStoreType() {
            return vectorStoreType;
        }
        
        public void setVectorStoreType(String vectorStoreType) {
            this.vectorStoreType = vectorStoreType;
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
    }
    
    /**
     * Search result class
     */
    public static class SearchResult {
        public String text;
        public String source;
        public float similarity;
        
        public SearchResult(String text, String source, float similarity) {
            this.text = text;
            this.source = source;
            this.similarity = similarity;
        }
    }
    
    /**
     * SQLite database helper class
     */
    private class VectorDatabaseHelper extends SQLiteOpenHelper {
        public VectorDatabaseHelper(Context context, String dbPath) {
            super(context, dbPath, null, DATABASE_VERSION);
            LogManager.logD(TAG, "Initializing VectorDatabaseHelper, database path: " + dbPath);
        }
        
        @Override
        public void onCreate(SQLiteDatabase db) {
            LogManager.logD(TAG, "Creating database tables...");
            try {
                // Create documents table
                String createTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_DOCUMENTS + " (" +
                        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        COLUMN_COLLECTION + " TEXT, " +
                        COLUMN_CONTENT + " TEXT, " +
                        COLUMN_METADATA + " TEXT, " +
                        COLUMN_EMBEDDING + " BLOB)";
                db.execSQL(createTableSql);
                LogManager.logD(TAG, "Successfully created database table: " + TABLE_DOCUMENTS);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to create database table: " + e.getMessage() + "\nStack trace: " + Log.getStackTraceString(e));
            }
        }
        
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            LogManager.logD(TAG, "Upgrading database, old version: " + oldVersion + ", new version: " + newVersion);
            // No operations for now, keep existing data
        }
        
        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            //LogManager.logD(TAG, "Database opened, path: " + db.getPath() + ", read-only: " + db.isReadOnly());
        }
    }
    
    /**
     * Constructor
     * @param databaseDir Database directory
     * @param embeddingModel Embedding model name to use
     */
    public SQLiteVectorDatabaseHandler(File databaseDir, String embeddingModel) {
        this.databaseDir = databaseDir;
        this.context = null;
        
        // Ensure directory exists
        if (!databaseDir.exists()) {
            databaseDir.mkdirs();
        }
        
        // Delay database initialization to avoid this-escape warning
        initializeDatabase(embeddingModel, 0);
    }
    
    /**
     * Constructor
     * @param databaseDir Database directory
     * @param embeddingModel Embedding model name to use
     * @param embeddingDimension Embedding vector dimension
     */
    public SQLiteVectorDatabaseHandler(File databaseDir, String embeddingModel, int embeddingDimension) {
        this.databaseDir = databaseDir;
        this.context = null;
        
        // 确保目录存在
        if (!databaseDir.exists()) {
            databaseDir.mkdirs();
        }
        
        // 延迟初始化数据库以避免this-escape警告
        initializeDatabase(embeddingModel, embeddingDimension);
        
        LogManager.logD(TAG, "Initializing vector database, model: " + embeddingModel + ", embedding dimension: " + embeddingDimension);
    }
    
    /**
     * Constructor
     * @param context Context
     */
    public SQLiteVectorDatabaseHandler(Context context) {
        this.context = context;
        this.databaseDir = null;
        this.metadata = null;
    }
    
    /**
     * Initialize database (avoid this-escape warning)
     * @param embeddingModel Embedding model name
     * @param embeddingDimension Embedding vector dimension (0 means not specified)
     */
    private void initializeDatabase(String embeddingModel, int embeddingDimension) {
        // Open or create database
        openDatabase();
        
        // Load or create metadata
        if (!loadMetadata()) {
            this.metadata = new DatabaseMetadata(embeddingModel);
            this.metadata.setCollection(databaseDir.getName());
            if (embeddingDimension > 0) {
                this.metadata.setEmbeddingDimension(embeddingDimension);
            }
            saveMetadata();
        } else if (embeddingDimension > 0 && this.metadata.getEmbeddingDimension() == 0) {
            // If metadata exists but dimension is 0, update dimension
            this.metadata.setEmbeddingDimension(embeddingDimension);
            saveMetadata();
        }
    }
    
    /**
     * Get knowledge base directory
     * @param knowledgeBaseName Knowledge base name
     * @return Knowledge base directory
     */
    private File getKnowledgeBaseDir(String knowledgeBaseName) {
        if (context == null) {
            LogManager.logE(TAG, "Context is null, cannot get knowledge base directory");
            return null;
        }
        File dir = new File(context.getFilesDir(), "knowledge_bases/" + knowledgeBaseName);
        LogManager.logD(TAG, "Knowledge base directory path: " + dir.getAbsolutePath());
        return dir;
    }
    
    /**
     * Open database connection
     */
    private final void openDatabase() {
        try {
            // If there's already an open database connection, close it first
            if (database != null && database.isOpen()) {
                LogManager.logI(TAG, "Existing database connection found, closing it first");
                closeDatabase();
            }
            
            if (databaseDir == null) {
                LogManager.logE(TAG, "Database directory is null, cannot open database");
                return;
            }
            
            File dbFile = new File(databaseDir, DB_FILENAME);
            String dbPath = dbFile.getAbsolutePath();
            LogManager.logI(TAG, "Attempting to open database file: " + dbPath + ", directory exists: " + databaseDir.exists() + ", file exists: " + dbFile.exists());
            
            // Check database file size
            if (dbFile.exists()) {
                LogManager.logI(TAG, "Database file size: " + dbFile.length() + " bytes");
            }
            
            // Check if database file is readable
            if (dbFile.exists() && !dbFile.canRead()) {
                LogManager.logE(TAG, "Database file exists but cannot be read: " + dbPath);
                return;
            }
            
            // Use SQLiteOpenHelper to open database
            Context appContext = context != null ? context : GlobalApplication.getAppContext();
            if (appContext == null) {
                LogManager.logE(TAG, "Cannot get application context, cannot open database");
                return;
            }
            
            LogManager.logI(TAG, "Opening database using context: " + appContext.getPackageName());
            VectorDatabaseHelper helper = new VectorDatabaseHelper(appContext, dbPath);
            
            try {
                LogManager.logI(TAG, "Attempting to open database in writable mode...");
                database = helper.getWritableDatabase();
                LogManager.logI(TAG, "Database connection successful: " + dbPath);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to get writable database: " + e.getMessage() + "\nStack trace: " + Log.getStackTraceString(e));
                // Try to open in read-only mode
                try {
                    LogManager.logI(TAG, "Attempting to open database in read-only mode...");
                    database = helper.getReadableDatabase();
                    LogManager.logI(TAG, "Database connection successful in read-only mode: " + dbPath);
                } catch (Exception e2) {
                    LogManager.logE(TAG, "Failed to get read-only database as well: " + e2.getMessage() + "\nStack trace: " + Log.getStackTraceString(e2));
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to open database: " + e.getMessage() + "\nStack trace: " + Log.getStackTraceString(e));
        }
    }
    
    /**
     * Close database connection
     */
    public final void closeDatabase() {
        try {
            if (database != null && database.isOpen()) {
                LogManager.logI(TAG, "Closing database connection");
                database.close();
                database = null;
                LogManager.logI(TAG, "Database connection closed");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to close database: " + e.getMessage() + "\nStack trace: " + Log.getStackTraceString(e));
        }
    }
    
    /**
     * 关闭数据库连接
     */
    public void close() {
        if (database != null && database.isOpen()) {
            database.close();
            LogManager.logD(TAG, "Database connection closed");
        }
    }
    
    /**
     * Save database
     */
    public boolean saveDatabase() {
        // Save metadata
        return saveMetadata();
    }
    
    /**
     * Load database metadata
     * @return Whether loading was successful
     */
    private boolean loadMetadata() {
        if (databaseDir == null) {
            LogManager.logE(TAG, "Database directory is null, cannot load metadata");
            return false;
        }
        
        try {
            // Load metadata from metadata.json file
            File metadataFile = new File(databaseDir, METADATA_FILENAME);
            if (!metadataFile.exists()) {
                LogManager.logD(TAG, "Metadata file does not exist, will create new metadata");
                return false;
            }
            
            // Read JSON file
            StringBuilder jsonContent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(metadataFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonContent.append(line);
                }
            }
            
            // Parse JSON
            JSONObject json = new JSONObject(jsonContent.toString());
            
            // Create new metadata object
            metadata = new DatabaseMetadata("");
            
            // Extract metadata from JSON
            if (json.has("embedding_model")) {
                metadata.embeddingModel = json.getString("embedding_model");
            }
            
            if (json.has("embedding_dimension")) {
                metadata.embeddingDimension = json.getInt("embedding_dimension");
            }
            
            if (json.has("chunk_count")) {
                metadata.chunkCount = json.getInt("chunk_count");
            }
            
            if (json.has("collection")) {
                metadata.collection = json.getString("collection");
            } else {
                metadata.collection = databaseDir.getName();
            }
            
            if (json.has("model_type")) {
                metadata.modelType = json.getString("model_type");
            }
            
            if (json.has("vector_store_type")) {
                metadata.vectorStoreType = json.getString("vector_store_type");
            }
            
            if (json.has("modeldir")) {
                metadata.modeldir = json.getString("modeldir");
            }
            
            if (json.has("rerankerdir")) {
                metadata.rerankerdir = json.getString("rerankerdir");
            }
            
            // Parse creation time
            if (json.has("created_at")) {
                String createdAtStr = json.getString("created_at");
                try {
                    // Try to parse PC format time string
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.getDefault());
                    Date createdDate = sdf.parse(createdAtStr);
                    if (createdDate != null) {
                        metadata.creationTimestamp = createdDate.getTime();
                    }
                } catch (Exception e) {
                    LogManager.logW(TAG, "Failed to parse creation time, using current time: " + e.getMessage());
                    metadata.creationTimestamp = System.currentTimeMillis();
                }
            } else {
                metadata.creationTimestamp = System.currentTimeMillis();
            }
            
            if (json.has("last_modified_timestamp")) {
                metadata.lastModifiedTimestamp = json.getLong("last_modified_timestamp");
            } else {
                metadata.lastModifiedTimestamp = System.currentTimeMillis();
            }
            
            if (json.has("files")) {
                JSONArray filesArray = json.getJSONArray("files");
                for (int i = 0; i < filesArray.length(); i++) {
                    metadata.files.add(filesArray.getString(i));
                }
            }
            
            if (json.has("file_count")) {
                metadata.fileCount = json.getInt("file_count");
            } else if (metadata.files != null) {
                // If no file_count but has files array, use array length
                metadata.fileCount = metadata.files.size();
            }
            
            // Load sources list
            if (json.has("sources")) {
                if (json.get("sources") instanceof JSONArray) {
                    JSONArray sourcesArray = json.getJSONArray("sources");
                    for (int i = 0; i < sourcesArray.length(); i++) {
                        metadata.sources.add(sourcesArray.getString(i));
                    }
                }
            }
            
            LogManager.logD(TAG, "Metadata loaded successfully, embedding model directory: " + metadata.getModeldir() +
                    ", dimension: " + metadata.getEmbeddingDimension() +
                    ", chunk count: " + metadata.getChunkCount());
            
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load metadata: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Save database metadata
     * @return Whether saving was successful
     */
    private boolean saveMetadata() {
        if (databaseDir == null) {
            LogManager.logE(TAG, "Database directory is null, cannot save metadata");
            return false;
        }
        
        if (metadata == null) {
            LogManager.logE(TAG, "Metadata is null, cannot save");
            return false;
        }
        
        try {
            // Create JSON object
            JSONObject json = new JSONObject();
            
            // Format creation time
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.getDefault());
            String formattedCreationTime = sdf.format(new Date(metadata.creationTimestamp));
            
            // Save basic metadata in PC format
            json.put("file_count", metadata.fileCount);
            json.put("chunk_count", metadata.chunkCount);
            json.put("files", new JSONArray(metadata.files));
            json.put("embedding_model", metadata.embeddingModel);
            json.put("model_type", metadata.modelType);
            json.put("created_at", formattedCreationTime);
            json.put("vector_store_type", metadata.vectorStoreType);
            json.put("modeldir", metadata.modeldir);
            json.put("rerankerdir", metadata.rerankerdir);
            
            // Add embedding dimension info, ensure PC compatibility
            if (metadata.getEmbeddingDimension() > 0) {
                json.put("embedding_dimension", metadata.getEmbeddingDimension());
            }
            
            // Add source info, ensure PC compatibility
            if (metadata.sources != null && !metadata.sources.isEmpty()) {
                json.put("sources", new JSONArray(metadata.sources));
            }
            
            // Add collection name, ensure PC compatibility
            if (metadata.getCollection() != null && !metadata.getCollection().isEmpty()) {
                json.put("collection", metadata.getCollection());
            }
            
            // Write JSON file
            File metadataFile = new File(databaseDir, METADATA_FILENAME);
            try (FileWriter writer = new FileWriter(metadataFile)) {
                writer.write(json.toString(4)); // Use 4-space indentation for pretty output
            }
            
            LogManager.logD(TAG, "Metadata saved successfully");
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save metadata: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Convert embedding vector to binary data
     * @param vector Vector
     * @return Binary data
     */
    private byte[] vectorToBlob(float[] vector) {
        // Use little-endian, consistent with PC
        byte[] embeddingBytes = new byte[vector.length * 4]; // Each float takes 4 bytes
        ByteBuffer buffer = ByteBuffer.wrap(embeddingBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return embeddingBytes;
    }
    
    /**
     * Convert binary data to vector
     * @param blob Binary data
     * @return Vector
     */
    private float[] blobToVector(byte[] blob) {
        // Use little-endian, consistent with PC
        float[] vector = new float[blob.length / 4];
        ByteBuffer.wrap(blob)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(vector);
        return vector;
    }
    
    /**
     * Add text chunk to database
     * @param text Text content
     * @param source Source information
     * @param embedding Embedding vector
     * @return Whether addition was successful
     */
    public boolean addChunk(String text, String source, float[] embedding) {
        if (database == null || !database.isOpen()) {
            LogManager.logE(TAG, "Database not open, cannot add text chunks");
            return false;
        }
        
        if (text == null || text.isEmpty()) {
            LogManager.logE(TAG, "Text content is empty, cannot add text chunk");
            return false;
        }
        
        if (embedding == null || embedding.length == 0) {
            LogManager.logE(TAG, "Embedding vector is empty, cannot add text chunk");
            return false;
        }
        
        try {
            // Begin transaction
            database.beginTransaction();
            
            // Convert embedding vector to byte array - use little-endian, consistent with PC
            byte[] embeddingBytes = vectorToBlob(embedding);
            
            // Create metadata JSON
            JSONObject metadataJson = new JSONObject();
            metadataJson.put("source", source != null ? source : "unknown");
            metadataJson.put("created_at", System.currentTimeMillis());
            
            // Prepare insert data
            ContentValues values = new ContentValues();
            values.put(COLUMN_COLLECTION, metadata.getCollection());
            values.put(COLUMN_CONTENT, text);
            values.put(COLUMN_METADATA, metadataJson.toString());
            values.put(COLUMN_EMBEDDING, embeddingBytes);
            
            // Insert data
            long rowId = database.insert(TABLE_DOCUMENTS, null, values);
            
            if (rowId == -1) {
                LogManager.logE(TAG, "Failed to insert text chunk");
                return false;
            }
            
            // Update metadata
            if (metadata != null) {
                // Update chunk count
                metadata.incrementChunkCount();
                
                // If source not in list, add to source list
                if (source != null && !source.isEmpty() && !metadata.sources.contains(source)) {
                    metadata.addSource(source);
                }
                
                // If embedding dimension not set, set embedding dimension
                if (metadata.getEmbeddingDimension() == 0) {
                    metadata.setEmbeddingDimension(embedding.length);
                }
            }
            
            // Commit transaction
            database.setTransactionSuccessful();
            
            LogManager.logD(TAG, "Successfully added text chunk, ID: " + rowId);
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to add text chunk: " + e.getMessage(), e);
            return false;
        } finally {
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }
    
    /**
     * Load all text chunks from database
     * @return Whether loading was successful
     */
    public boolean loadDatabase() {
        if (databaseDir == null) {
            LogManager.logE(TAG, "Database directory is null, cannot load database");
            return false;
        }
        
        // If database is already open and metadata loaded, return success directly
        if (database != null && database.isOpen() && metadata != null) {
            LogManager.logI(TAG, "Database already open and metadata loaded, no need to reload");
            return true;
        }
        
        File dbFile = new File(databaseDir, DB_FILENAME);
        LogManager.logI(TAG, "Attempting to load database file: " + dbFile.getAbsolutePath());
        
        if (!dbFile.exists()) {
            LogManager.logE(TAG, "Database file does not exist: " + dbFile.getAbsolutePath());
            return false;
        }
        
        // Check database file size and permissions
        LogManager.logI(TAG, "Database file size: " + dbFile.length() + " bytes, readable: " + dbFile.canRead() + ", writable: " + dbFile.canWrite());
        
        try {
            // Check global stop flag before opening database
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "Global stop requested, aborting database loading");
                return false;
            }
            
            // Open database
            LogManager.logI(TAG, "Starting to open database connection...");
            openDatabase();
            
            if (database == null || !database.isOpen()) {
                LogManager.logE(TAG, "Failed to open database, database object is null or not open");
                return false;
            }
            
            LogManager.logI(TAG, "Database connection successful, database object: " + database + ", path: " + database.getPath() + ", read-only: " + database.isReadOnly());
            
            // Check global stop flag before loading metadata
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "Global stop requested, aborting metadata loading");
                closeDatabase();
                return false;
            }
            
            // Load metadata
            LogManager.logI(TAG, "Starting to load metadata...");
            if (!loadMetadata()) {
                LogManager.logE(TAG, "Failed to load metadata");
                closeDatabase(); // Close database connection
                return false;
            }
            
            // Check if document table exists
            LogManager.logI(TAG, "Checking if document table exists: " + TABLE_DOCUMENTS);
            Cursor cursor = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{TABLE_DOCUMENTS});
            
            boolean tableExists = cursor.getCount() > 0;
            cursor.close();
            
            if (!tableExists) {
                LogManager.logE(TAG, "Document table does not exist: " + TABLE_DOCUMENTS);
                closeDatabase(); // Close database connection
                return false;
            }
            
            LogManager.logI(TAG, "Document table exists: " + TABLE_DOCUMENTS);
            
            // Check table structure
            try {
                LogManager.logI(TAG, "Checking table structure...");
                Cursor columnCursor = database.rawQuery("PRAGMA table_info(" + TABLE_DOCUMENTS + ")", null);
                StringBuilder columnsInfo = new StringBuilder("Table structure info:\n");
                while (columnCursor.moveToNext()) {
                    String columnName = columnCursor.getString(1);
                    String columnType = columnCursor.getString(2);
                    columnsInfo.append("Column: ").append(columnName).append(", Type: ").append(columnType).append("\n");
                }
                columnCursor.close();
                LogManager.logI(TAG, columnsInfo.toString());
            } catch (Exception e) {
                LogManager.logW(TAG, "Failed to get table structure info: " + e.getMessage());
            }
            
            // Check text chunk count
            LogManager.logI(TAG, "Checking text chunk count...");
            cursor = database.rawQuery("SELECT COUNT(*) FROM " + TABLE_DOCUMENTS, null);
            cursor.moveToFirst();
            int count = cursor.getInt(0);
            cursor.close();
            
            LogManager.logI(TAG, "Database loaded successfully, total " + count + " text chunks");
            
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load database: " + e.getMessage() + "\nStack trace: " + Log.getStackTraceString(e));
            closeDatabase(); // Close database connection
            return false;
        }
    }
    
    /**
     * Get text chunk count in database
     * @return Text chunk count
     */
    public int getChunkCount() {
        if (database == null || !database.isOpen()) {
            LogManager.logE(TAG, "Database not open, cannot get text chunk count");
            return 0;
        }
        
        try {
            Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM " + TABLE_DOCUMENTS, null);
            cursor.moveToFirst();
            int count = cursor.getInt(0);
            cursor.close();
            
            return count;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get text chunk count: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Get database metadata
     * @return Database metadata
     */
    public DatabaseMetadata getMetadata() {
        return metadata;
    }
    
    /**
     * Calculate cosine similarity between two vectors
     * @param vec1 Vector 1
     * @param vec2 Vector 2
     * @return Cosine similarity value, range [-1, 1]
     */
    private float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            LogManager.logE(TAG, "Vector dimensions do not match: vec1 length=" + vec1.length + ", vec2 length=" + vec2.length);
            throw new IllegalArgumentException("Vector dimensions do not match");
        }
        
        // 1. 向量异常检测和修复
        VectorAnomalyHandler.AnomalyResult anomaly1 = VectorAnomalyHandler.detectAnomalies(vec1, -1);
        VectorAnomalyHandler.AnomalyResult anomaly2 = VectorAnomalyHandler.detectAnomalies(vec2, -1);
        
        float[] processedVec1 = vec1;
        float[] processedVec2 = vec2;
        
        // 修复第一个向量的异常
        if (anomaly1.isAnomalous) {
            LogManager.logW(TAG, String.format("Vector 1 anomaly detected in similarity calculation: %s (severity: %.2f)", 
                    anomaly1.type.name(), anomaly1.severity));
            processedVec1 = VectorAnomalyHandler.repairVector(vec1, anomaly1.type);
            if (processedVec1 == null) {
                LogManager.logE(TAG, "Failed to repair vector 1 anomaly, using original vector");
                processedVec1 = vec1;
            }
        }
        
        // 修复第二个向量的异常
        if (anomaly2.isAnomalous) {
            LogManager.logW(TAG, String.format("Vector 2 anomaly detected in similarity calculation: %s (severity: %.2f)", 
                    anomaly2.type.name(), anomaly2.severity));
            processedVec2 = VectorAnomalyHandler.repairVector(vec2, anomaly2.type);
            if (processedVec2 == null) {
                LogManager.logE(TAG, "Failed to repair vector 2 anomaly, using original vector");
                processedVec2 = vec2;
            }
        }
        
        // 2. 计算余弦相似度
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        
        for (int i = 0; i < processedVec1.length; i++) {
            float val1 = processedVec1[i];
            float val2 = processedVec2[i];
            
            // 检查单个值的异常
            if (Float.isNaN(val1) || Float.isInfinite(val1)) {
                LogManager.logW(TAG, String.format("Anomalous value in vector 1 at index %d: %f", i, val1));
                val1 = 0.0f;
            }
            if (Float.isNaN(val2) || Float.isInfinite(val2)) {
                LogManager.logW(TAG, String.format("Anomalous value in vector 2 at index %d: %f", i, val2));
                val2 = 0.0f;
            }
            
            dotProduct += val1 * val2;
            normA += val1 * val1;
            normB += val2 * val2;
        }
        
        // 3. 检查范数异常
        if (normA <= 1e-6f || normB <= 1e-6f) {
            LogManager.logW(TAG, String.format("Vector norm too small: normA=%.2e, normB=%.2e, returning zero similarity", normA, normB));
            return 0.0f;
        }
        
        // 4. 计算最终相似度
        float similarity = dotProduct / (float)(Math.sqrt(normA) * Math.sqrt(normB));
        
        // 5. 检查相似度结果异常
        if (Float.isNaN(similarity) || Float.isInfinite(similarity)) {
            LogManager.logW(TAG, String.format("Anomalous similarity result: %f, returning zero", similarity));
            return 0.0f;
        }
        
        // 6. 钳制相似度到合理范围 [-1, 1]
        if (similarity > 1.0f) {
            LogManager.logW(TAG, String.format("Similarity > 1.0: %f, clamping to 1.0", similarity));
            similarity = 1.0f;
        } else if (similarity < -1.0f) {
            LogManager.logW(TAG, String.format("Similarity < -1.0: %f, clamping to -1.0", similarity));
            similarity = -1.0f;
        }
        
        return similarity;
    }
    
    /**
     * Search for text chunks most similar to query vector
     * @param queryVector Query vector
     * @param topK Maximum number of results to return
     * @return Search result list, sorted by similarity in descending order
     */
    public List<SearchResult> searchSimilar(float[] queryVector, int topK) {
        List<SearchResult> results = new ArrayList<>();
        
        if (database == null || !database.isOpen()) {
            LogManager.logE(TAG, "Database not open, cannot search");
            return results;
        }
        
        if (queryVector == null || queryVector.length == 0) {
            LogManager.logE(TAG, "Query vector is empty, cannot search");
            return results;
        }
        
        try {
            // Get documents from current knowledge base
            String collection = metadata.getCollection();
            Cursor cursor = database.query(
                    TABLE_DOCUMENTS,
                    new String[]{COLUMN_ID, COLUMN_CONTENT, COLUMN_METADATA, COLUMN_EMBEDDING},
                    COLUMN_COLLECTION + "=?",
                    new String[]{collection},
                    null, null, null);
            
            // Create result list to store similarity and corresponding text chunks
            List<Pair<Float, SearchResult>> similarityResults = new ArrayList<>();
            
            // Iterate through all text chunks
            while (cursor.moveToNext()) {
                // Check global stop flag
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting database search");
                    cursor.close();
                    return results;
                }
                
                int contentIndex = cursor.getColumnIndex(COLUMN_CONTENT);
                int metadataIndex = cursor.getColumnIndex(COLUMN_METADATA);
                int embeddingIndex = cursor.getColumnIndex(COLUMN_EMBEDDING);
                
                // Check if column indices are valid
                if (contentIndex == -1 || metadataIndex == -1 || embeddingIndex == -1) {
                    LogManager.logE(TAG, "Database table structure mismatch, missing required columns");
                    continue;
                }
                
                String content = cursor.getString(contentIndex);
                String metadataJson = cursor.getString(metadataIndex);
                byte[] embeddingBlob = cursor.getBlob(embeddingIndex);
                
                // Parse metadata
                String source = "unknown";
                try {
                    JSONObject metadataObj = new JSONObject(metadataJson);
                    if (metadataObj.has("source")) {
                        source = metadataObj.getString("source");
                    }
                } catch (JSONException e) {
                    LogManager.logW(TAG, "Failed to parse metadata: " + e.getMessage());
                }
                
                // Convert byte array to float array - use little-endian, consistent with PC
                float[] embedding = blobToVector(embeddingBlob);
                
                // Calculate cosine similarity
                float similarity = cosineSimilarity(queryVector, embedding);
                
                // Add to result list
                similarityResults.add(new Pair<>(similarity, new SearchResult(content, source, similarity)));
            }
            
            cursor.close();
            
            // Sort by similarity in descending order
            Collections.sort(similarityResults, (a, b) -> Float.compare(b.first, a.first));
            
            // Take top K results
            int resultCount = Math.min(topK, similarityResults.size());
            for (int i = 0; i < resultCount; i++) {
                results.add(similarityResults.get(i).second);
            }
            
            LogManager.logD(TAG, "Search completed, found " + results.size() + " similar text chunks");
            return results;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to search similar text chunks: " + e.getMessage(), e);
            return results;
        }
    }
    
    /**
     * Batch add text chunks to database
     * @param texts List of text content
     * @param sources List of source information
     * @param embeddings List of embedding vectors
     * @return Whether addition was successful
     */
    public boolean addChunks(List<String> texts, List<String> sources, List<float[]> embeddings) {
        if (database == null || !database.isOpen()) {
            LogManager.logE(TAG, "Database not open, cannot add text chunk");
            return false;
        }
        
        if (texts == null || texts.isEmpty()) {
            LogManager.logE(TAG, "Text content list is empty, cannot add text chunks");
            return false;
        }
        
        if (embeddings == null || embeddings.isEmpty()) {
            LogManager.logE(TAG, "Embedding vector list is empty, cannot add text chunks");
            return false;
        }
        
        if (texts.size() != embeddings.size()) {
            LogManager.logE(TAG, "Text content list and embedding vector list length mismatch");
            return false;
        }
        
        if (sources != null && sources.size() != texts.size()) {
            LogManager.logE(TAG, "Source information list and text content list length mismatch");
            return false;
        }
        
        try {
            // Begin transaction
            database.beginTransaction();
            
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                String source = (sources != null && i < sources.size()) ? sources.get(i) : "unknown";
                float[] embedding = embeddings.get(i);
                
                // Convert embedding vector to byte array - use little-endian, consistent with PC
                byte[] embeddingBytes = vectorToBlob(embedding);
                
                // Create metadata JSON
                JSONObject metadataJson = new JSONObject();
                metadataJson.put("source", source);
                metadataJson.put("created_at", System.currentTimeMillis());
                
                // Prepare insert data
                ContentValues values = new ContentValues();
                values.put(COLUMN_COLLECTION, metadata.getCollection());
                values.put(COLUMN_CONTENT, text);
                values.put(COLUMN_METADATA, metadataJson.toString());
                values.put(COLUMN_EMBEDDING, embeddingBytes);
                
                // Insert data
                long rowId = database.insert(TABLE_DOCUMENTS, null, values);
                
                if (rowId == -1) {
                    LogManager.logE(TAG, "Failed to insert text chunk");
                    return false;
                }
                
                // Update metadata
                if (metadata != null) {
                    // Update text chunk count
                    metadata.incrementChunkCount();
                    
                    // If source is not in list, add to source list
                    if (source != null && !source.isEmpty() && !metadata.sources.contains(source)) {
                        metadata.addSource(source);
                    }
                    
                    // If embedding dimension is not set, set embedding dimension
                    if (metadata.getEmbeddingDimension() == 0 && embedding.length > 0) {
                        metadata.setEmbeddingDimension(embedding.length);
                    }
                }
            }
            
            // Commit transaction
            database.setTransactionSuccessful();
            
            LogManager.logD(TAG, "Successfully batch added " + texts.size() + " text chunks");
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Batch adding text chunks failed: " + e.getMessage(), e);
            return false;
        } finally {
            if (database.inTransaction()) {
                database.endTransaction();
            }
        }
    }
    
    /**
     * Add single text chunk and vector to database
     * @param text Text content
     * @param embedding Embedding vector
     * @param source Source information
     * @param metadataStr Metadata (JSON string)
     * @return Whether addition was successful
     */
    public boolean addVector(String text, float[] embedding, String source, String metadataStr) {
        if (database == null || !database.isOpen()) {
            LogManager.logE(TAG, "Database not open, cannot add text chunk");
            return false;
        }
        
        if (text == null || text.isEmpty()) {
            LogManager.logE(TAG, "Text content is empty, cannot add text chunk");
            return false;
        }
        
        if (embedding == null || embedding.length == 0) {
            LogManager.logE(TAG, "Embedding vector is empty, cannot add text chunk");
            return false;
        }
        
        try {
            // Convert embedding vector to byte array - use little-endian, consistent with PC
            byte[] embeddingBytes = vectorToBlob(embedding);
            
            // Create metadata JSON
            JSONObject finalMetadata;
            try {
                // Try to parse the passed metadata string
                finalMetadata = new JSONObject(metadataStr);
            } catch (JSONException e) {
                // If parsing fails, create new metadata object
                finalMetadata = new JSONObject();
                LogManager.logW(TAG, "Failed to parse metadata string, creating new metadata object: " + e.getMessage());
            }
            
            // Add basic metadata
            if (!finalMetadata.has("source") && source != null && !source.isEmpty()) {
                finalMetadata.put("source", source);
            }
            if (!finalMetadata.has("created_at")) {
                finalMetadata.put("created_at", System.currentTimeMillis());
            }
            
            // Prepare insert data
            ContentValues values = new ContentValues();
            values.put(COLUMN_COLLECTION, metadata.getCollection());
            values.put(COLUMN_CONTENT, text);
            values.put(COLUMN_METADATA, finalMetadata.toString());
            values.put(COLUMN_EMBEDDING, embeddingBytes);
            
            // Insert data
            long rowId = database.insert(TABLE_DOCUMENTS, null, values);
            
            if (rowId == -1) {
                LogManager.logE(TAG, "Failed to insert text chunk");
                return false;
            }
            
            // Update metadata
            if (metadata != null) {
                // Update text chunk count
                metadata.incrementChunkCount();
                
                // If source is not in list, add to source list
                if (source != null && !source.isEmpty() && !metadata.sources.contains(source)) {
                    metadata.addSource(source);
                }
                
                // If embedding dimension is not set, set embedding dimension
                if (metadata.getEmbeddingDimension() == 0 && embedding.length > 0) {
                    metadata.setEmbeddingDimension(embedding.length);
                }
            }
            
            LogManager.logD(TAG, "Successfully added text chunk: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text));
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to add text chunk: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Add file to metadata
     * @param fileName File name
     */
    public void addFileToMetadata(String fileName) {
        if (metadata != null) {
            metadata.addFile(fileName);
            LogManager.logD(TAG, "Added file to metadata: " + fileName);
        }
    }
    
    /**
     * Update embedding model path
     * @param embeddingModelPath New embedding model path
     * @return Whether update was successful
     */
    public boolean updateEmbeddingModel(String embeddingModelPath) {
        if (metadata == null) {
            LogManager.logE(TAG, "Metadata object is null, cannot update embedding model path");
            return false;
        }
        
        try {
            LogManager.logD(TAG, "Updating embedding model path: " + embeddingModelPath);
            metadata.setEmbeddingModel(embeddingModelPath);
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to update embedding model path", e);
            return false;
        }
    }
}
