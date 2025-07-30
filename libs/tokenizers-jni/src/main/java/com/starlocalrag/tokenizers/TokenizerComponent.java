package com.starlocalrag.tokenizers;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 分词器组件接口
 * 所有分词器组件的基础接口
 */
public interface TokenizerComponent {
    /**
     * 从配置加载组件设置
     * @param config 包含组件配置的JSON对象
     * @throws JSONException 如果配置解析失败
     */
    void loadConfig(JSONObject config) throws JSONException;
}
