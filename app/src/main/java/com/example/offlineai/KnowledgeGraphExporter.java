package com.example.offlineai;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Knowledge Graph Exporter
 * 
 * Exports knowledge graph to Markdown format optimized for mobile portrait display.
 * 
 * Usage:
 * ```java
 * KnowledgeGraphExporter exporter = new KnowledgeGraphExporter(context, graphDb);
 * String markdown = exporter.exportToMarkdown(kbName, topN);
 * ```
 */
public class KnowledgeGraphExporter {
    private static final String TAG = "OfflineAI_GraphExport";
    
    private final Context context;
    private final KnowledgeGraphDatabase graphDb;
    
    public KnowledgeGraphExporter(Context context, KnowledgeGraphDatabase graphDb) {
        this.context = context;
        this.graphDb = graphDb;
    }
    
    // ========== Data Classes ==========
    
    public static class GraphStats {
        public int totalChunks;
        public int totalEntities;
        public int totalEdges;
        public double avgEntitiesPerChunk;
        
        public GraphStats(int chunks, int entities, int edges) {
            this.totalChunks = chunks;
            this.totalEntities = entities;
            this.totalEdges = edges;
            this.avgEntitiesPerChunk = chunks > 0 ? (double) entities / chunks : 0;
        }
    }
    
    public static class EntityInfo {
        public long id;
        public String text;
        public String type;
        public int frequency;
        public double avgConfidence;
        
        public EntityInfo(long id, String text, String type, int frequency, double avgConfidence) {
            this.id = id;
            this.text = text;
            this.type = type;
            this.frequency = frequency;
            this.avgConfidence = avgConfidence;
        }
    }
    
    public static class EdgeInfo {
        public String fromEntity;
        public String toEntity;
        public int weight;
        public String chunkIds;
        
        public EdgeInfo(String from, String to, int weight, String chunkIds) {
            this.fromEntity = from;
            this.toEntity = to;
            this.weight = weight;
            this.chunkIds = chunkIds;
        }
    }
    
    // ========== Statistics ==========
    
    public GraphStats getGraphStats() {
        SQLiteDatabase db = graphDb.getReadableDatabase();
        
        try {
            // Count chunks
            Cursor chunkCursor = db.rawQuery(
                "SELECT COUNT(*) FROM documents WHERE collection = ?",
                new String[]{graphDb.getCollection()}
            );
            int totalChunks = 0;
            if (chunkCursor.moveToFirst()) {
                totalChunks = chunkCursor.getInt(0);
            }
            chunkCursor.close();
            
            // Count entities
            Cursor entityCursor = db.rawQuery(
                "SELECT COUNT(*) FROM entities WHERE collection = ?",
                new String[]{graphDb.getCollection()}
            );
            int totalEntities = 0;
            if (entityCursor.moveToFirst()) {
                totalEntities = entityCursor.getInt(0);
            }
            entityCursor.close();
            
            // Count edges
            Cursor edgeCursor = db.rawQuery(
                "SELECT COUNT(*) FROM entity_edges WHERE collection = ?",
                new String[]{graphDb.getCollection()}
            );
            int totalEdges = 0;
            if (edgeCursor.moveToFirst()) {
                totalEdges = edgeCursor.getInt(0);
            }
            edgeCursor.close();
            
            return new GraphStats(totalChunks, totalEntities, totalEdges);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Get graph stats failed", e);
            return new GraphStats(0, 0, 0);
        }
    }
    
    // ========== Entity Queries ==========
    
    public List<EntityInfo> getTopEntities(int topN) {
        List<EntityInfo> entities = new ArrayList<>();
        SQLiteDatabase db = graphDb.getReadableDatabase();
        
        try {
            Cursor cursor = db.rawQuery(
                "SELECT id, entity_text, entity_type, frequency, avg_confidence " +
                "FROM entities WHERE collection = ? " +
                "ORDER BY frequency DESC LIMIT ?",
                new String[]{graphDb.getCollection(), String.valueOf(topN)}
            );
            
            while (cursor.moveToNext()) {
                entities.add(new EntityInfo(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getInt(3),
                    cursor.getDouble(4)
                ));
            }
            cursor.close();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Get top entities failed", e);
        }
        
        return entities;
    }
    
    // ========== Edge Queries ==========
    
    public List<EdgeInfo> getTopEdges(int topN) {
        List<EdgeInfo> edges = new ArrayList<>();
        SQLiteDatabase db = graphDb.getReadableDatabase();
        
        try {
            Cursor cursor = db.rawQuery(
                "SELECT from_entity, to_entity, weight, chunk_ids " +
                "FROM entity_edges WHERE collection = ? " +
                "ORDER BY weight DESC LIMIT ?",
                new String[]{graphDb.getCollection(), String.valueOf(topN)}
            );
            
            while (cursor.moveToNext()) {
                edges.add(new EdgeInfo(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2),
                    cursor.getString(3)
                ));
            }
            cursor.close();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Get top edges failed", e);
        }
        
        return edges;
    }
    
    // ========== Entity Type Statistics ==========
    
    public Map<String, List<EntityInfo>> getEntitiesByType(int topN) {
        Map<String, List<EntityInfo>> byType = new LinkedHashMap<>();
        SQLiteDatabase db = graphDb.getReadableDatabase();
        
        try {
            // Get all entity types
            Cursor typeCursor = db.rawQuery(
                "SELECT DISTINCT entity_type FROM entities WHERE collection = ? ORDER BY entity_type",
                new String[]{graphDb.getCollection()}
            );
            
            List<String> types = new ArrayList<>();
            while (typeCursor.moveToNext()) {
                types.add(typeCursor.getString(0));
            }
            typeCursor.close();
            
            // Get top entities for each type
            for (String type : types) {
                List<EntityInfo> entities = new ArrayList<>();
                Cursor cursor = db.rawQuery(
                    "SELECT id, entity_text, entity_type, frequency, avg_confidence " +
                    "FROM entities WHERE collection = ? AND entity_type = ? " +
                    "ORDER BY frequency DESC LIMIT ?",
                    new String[]{graphDb.getCollection(), type, String.valueOf(topN)}
                );
                
                while (cursor.moveToNext()) {
                    entities.add(new EntityInfo(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getDouble(4)
                    ));
                }
                cursor.close();
                
                if (!entities.isEmpty()) {
                    byType.put(type, entities);
                }
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Get entities by type failed", e);
        }
        
        return byType;
    }
    
    // ========== Markdown Export ==========
    
    /**
     * Export knowledge graph to Markdown format (optimized for mobile portrait)
     * @param kbName Knowledge base name
     * @param topN Number of top items to display
     * @return Markdown string
     */
    public String exportToMarkdown(String kbName, int topN) {
        StringBuilder md = new StringBuilder();
        
        try {
            // Title
            md.append("# 📊 知识库图谱：").append(kbName).append("\n\n");
            
            // Statistics
            GraphStats stats = getGraphStats();
            md.append("## 📈 统计信息\n\n");
            md.append("- **文档块数**：").append(stats.totalChunks).append(" 个\n");
            md.append("- **实体总数**：").append(stats.totalEntities).append(" 个\n");
            md.append("- **关系总数**：").append(stats.totalEdges).append(" 条\n");
            md.append("- **平均实体/块**：").append(String.format("%.1f", stats.avgEntitiesPerChunk)).append(" 个\n\n");
            md.append("---\n\n");
            
            // Top Entities (compact table for portrait)
            md.append("## 🏷️ 高频实体 TOP ").append(topN).append("\n\n");
            List<EntityInfo> topEntities = getTopEntities(topN);
            if (!topEntities.isEmpty()) {
                md.append("| # | 实体 | 类型 | 次数 |\n");
                md.append("|---|------|------|------|\n");
                for (int i = 0; i < topEntities.size(); i++) {
                    EntityInfo entity = topEntities.get(i);
                    md.append("| ").append(i + 1).append(" | ");
                    md.append(entity.text).append(" | ");
                    md.append(getTypeDisplayName(entity.type)).append(" | ");
                    md.append(entity.frequency).append(" |\n");
                }
            } else {
                md.append("*暂无实体数据*\n");
            }
            md.append("\n---\n\n");
            
            // Top Edges (compact table)
            md.append("## 🔗 核心关系网络 TOP ").append(topN).append("\n\n");
            List<EdgeInfo> topEdges = getTopEdges(topN);
            if (!topEdges.isEmpty()) {
                md.append("| 实体A | 实体B | 次数 |\n");
                md.append("|-------|-------|------|\n");
                for (EdgeInfo edge : topEdges) {
                    md.append("| ").append(edge.fromEntity).append(" | ");
                    md.append(edge.toEntity).append(" | ");
                    md.append(edge.weight).append(" |\n");
                }
            } else {
                md.append("*暂无关系数据*\n");
            }
            md.append("\n---\n\n");
            
            // Entities by Type
            md.append("## 📚 实体分类统计\n\n");
            Map<String, List<EntityInfo>> byType = getEntitiesByType(10); // Top 10 per type
            if (!byType.isEmpty()) {
                for (Map.Entry<String, List<EntityInfo>> entry : byType.entrySet()) {
                    String type = entry.getKey();
                    List<EntityInfo> entities = entry.getValue();
                    
                    md.append("### ").append(getTypeDisplayName(type));
                    md.append(" - ").append(entities.size()).append(" 个\n\n");
                    
                    for (EntityInfo entity : entities) {
                        md.append("- **").append(entity.text).append("** (").append(entity.frequency).append("次)\n");
                    }
                    md.append("\n");
                }
            } else {
                md.append("*暂无分类数据*\n");
            }
            
            // Footer
            md.append("\n---\n\n");
            md.append("*导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("*\n");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Export to markdown failed", e);
            return "# 导出失败\n\n错误：" + e.getMessage();
        }
        
        return md.toString();
    }
    
    // ========== Helper Methods ==========
    
    private String getTypeDisplayName(String type) {
        // Map HanLP POS tags to readable names
        switch (type) {
            case "nx": return "外文词";
            case "n": return "名词";
            case "m": return "数词";
            case "v": return "动词";
            case "a": return "形容词";
            case "r": return "代词";
            case "d": return "副词";
            case "p": return "介词";
            case "c": return "连词";
            case "u": return "助词";
            case "e": return "叹词";
            case "o": return "拟声词";
            case "q": return "量词";
            case "t": return "时间词";
            case "s": return "处所词";
            case "f": return "方位词";
            case "b": return "区别词";
            case "z": return "状态词";
            case "l": return "习用语";
            case "i": return "成语";
            case "j": return "简称";
            case "h": return "前接成分";
            case "k": return "后接成分";
            case "g": return "语素";
            case "x": return "非语素字";
            case "w": return "标点符号";
            default: return type;
        }
    }
}
