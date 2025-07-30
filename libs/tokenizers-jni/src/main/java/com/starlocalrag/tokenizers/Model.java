package com.starlocalrag.tokenizers;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;

/**
 * 分词模型接口
 * 定义了分词模型的基本功能
 */
public interface Model extends TokenizerComponent {
    /**
     * 将文本序列转换为token ID
     * @param tokens 要分词的文本序列
     * @return token ID列表
     */
    List<Integer> tokenize(List<String> tokens);
    
    /**
     * 将token转换为ID
     * @param token 要转换的token
     * @return token的ID，如果不存在则返回未知token的ID
     */
    int tokenToId(String token);
    
    /**
     * 将ID转换为token
     * @param id 要转换的ID
     * @return 对应的token，如果不存在则返回空字符串
     */
    String idToToken(int id);
    
    /**
     * 获取词汇表
     * @return token到ID的映射
     */
    Map<String, Integer> getVocab();
    
    /**
     * 获取词汇表大小
     * @return 词汇表中的token数量
     */
    int getSpecialTokensSize();
    
    /**
     * 从映射加载词汇表
     * @param vocabJson token到ID的JSON映射
     * @throws JSONException 如果JSON解析失败
     */
    void loadVocabFromMap(JSONObject vocabJson) throws JSONException;
    
    /**
     * 从数组加载词汇表
     * @param vocabArray token数组，索引作为ID
     * @throws JSONException 如果JSON解析失败
     */
    void loadVocabFromArray(JSONArray vocabArray) throws JSONException;
    
    /**
     * 设置未知token的ID
     * @param unkId 未知token的ID
     */
    void setUnkTokenId(int unkId);
    
    /**
     * 检查词汇表中是否包含指定token
     * @param token 要检查的token
     * @return 如果词汇表中包含该token则返回true
     */
    boolean hasToken(String token);
    
    /**
     * 向词汇表中添加token
     * @param token 要添加的token
     * @param id token的ID
     */
    void addToken(String token, int id);
}
