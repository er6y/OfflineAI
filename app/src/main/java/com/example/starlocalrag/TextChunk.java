package com.example.starlocalrag;

import org.json.JSONObject;

/**
 * 表示一个文本块，包含文本内容、来源和元数据
 */
public class TextChunk {
    private String text;
    private String source;
    private int chunkIndex;
    private JSONObject metadata;

    /**
     * 构造函数
     * @param text 文本内容
     * @param source 文本来源（如文件名）
     * @param chunkIndex 块索引
     * @param metadata 元数据
     */
    public TextChunk(String text, String source, int chunkIndex, JSONObject metadata) {
        this.text = text;
        this.source = source;
        this.chunkIndex = chunkIndex;
        this.metadata = metadata;
    }

    /**
     * 获取文本内容
     * @return 文本内容
     */
    public String getText() {
        return text;
    }

    /**
     * 获取文本来源
     * @return 文本来源
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取块索引
     * @return 块索引
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /**
     * 获取元数据
     * @return 元数据
     */
    public JSONObject getMetadata() {
        return metadata;
    }

    /**
     * 设置文本内容
     * @param text 文本内容
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * 设置文本来源
     * @param source 文本来源
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 设置块索引
     * @param chunkIndex 块索引
     */
    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    /**
     * 设置元数据
     * @param metadata 元数据
     */
    public void setMetadata(JSONObject metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "TextChunk{" +
                "text='" + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + '\'' +
                ", source='" + source + '\'' +
                ", chunkIndex=" + chunkIndex +
                ", metadata=" + metadata +
                '}';
    }
}
