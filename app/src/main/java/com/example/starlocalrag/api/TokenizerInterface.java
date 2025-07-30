package com.example.starlocalrag.api;

import java.io.Closeable;
import java.io.File;
import java.util.Map;

/**
 * 统一的分词器接口
 * 所有分词器实现（包括TokenizerManager和HuggingfaceTokenizer）都应实现此接口
 * 确保RAG问答、知识库构建和知识笔记创建过程使用相同的分词策略
 */
public interface TokenizerInterface extends Closeable {
    
    /**
     * 检查分词器是否已初始化
     * @return 是否已初始化
     */
    boolean isInitialized();
    
    /**
     * 初始化分词器
     * @param modelDir 模型目录
     * @return 是否初始化成功
     */
    boolean initialize(File modelDir);
    
    /**
     * 初始化分词器（接受字符串路径）
     * @param modelPath 模型路径
     * @return 是否初始化成功
     */
    boolean initialize(String modelPath);
    
    /**
     * 对文本进行分词
     * @param text 输入文本
     * @return 分词结果（token ID数组）
     */
    long[][] tokenize(String text);
    
    /**
     * 设置是否使用一致性分词策略
     * @param useConsistent 是否使用一致性分词
     */
    void setUseConsistentTokenization(boolean useConsistent);
    
    /**
     * 检查是否使用一致性分词策略
     * @return 是否使用一致性分词
     */
    boolean isUseConsistentTokenization();
    
    /**
     * 设置是否启用调试模式
     * @param debug 是否启用调试
     */
    void setDebugMode(boolean debug);
    
    /**
     * 获取特殊token数量
     * @return 特殊token数量
     */
    int getSpecialTokensSize();
    
    /**
     * 重置分词器
     */
    void reset();
    
    /**
     * 获取特殊token的ID
     * @param token 特殊token的内容
     * @return 特殊token的ID，如果不存在则返回-1
     */
    int getSpecialTokenId(String token);
    
    /**
     * 根据ID获取特殊token的内容
     * @param id token的ID
     * @return 特殊token的内容，如果不存在则返回null
     */
    String getSpecialTokenContent(int id);
    
    /**
     * 检查给定的ID是否为特殊token
     * @param id token的ID
     * @return 是否为特殊token
     */
    boolean isSpecialToken(int id);
    
    /**
     * 获取所有特殊token的映射（内容到ID）
     * @return 特殊token映射
     */
    Map<String, Integer> getAllSpecialTokens();
    
    /**
     * 添加一个特殊token
     * @param content token内容
     * @param id token ID
     * @return 是否添加成功
     */
    boolean addSpecialToken(String content, int id);
    
    /**
     * 将token ID解码为文本
     * @param ids token ID数组
     * @return 解码后的文本
     */
    String decodeIds(int[] ids);
    
    /**
     * 应用聊天模板
     * @param messages 消息列表
     * @param addGenerationPrompt 是否添加生成提示
     * @return 应用模板后的文本
     */
    String applyChatTemplate(Object messages, boolean addGenerationPrompt);
    
    /**
     * 获取模型类型
     * @return 模型类型名称
     */
    String getModelType();
    
    /**
     * 设置模型类型
     * @param modelType 模型类型名称
     */
    void setModelType(String modelType);
    
    /**
     * 获取分词器配置信息
     * @return 配置信息的字符串表示
     */
    String getTokenizerInfo();
}
