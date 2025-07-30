package com.starlocalrag.tokenizers;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.io.Closeable;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hugging Face分词器的Java包装类
 * 提供对Hugging Face Tokenizers库的访问
 * 同时实现Model接口以兼容现有代码
 */
public class HuggingfaceTokenizer implements Closeable, Model {
    private long nativePtr;
    private boolean isClosed = false;
    // 模型路径
    private String modelPath;
    
    /**
     * 检查分词器是否已关闭
     * @throws IllegalStateException 如果分词器已关闭
     */
    private void checkClosed() {
        if (isClosed) {
            throw new IllegalStateException("分词器已关闭");
        }
    }
    
    // 词汇表 (token -> id)
    private Map<String, Integer> vocab = new HashMap<>();
    
    // 特殊 token ID 数组，初始化为 -1 表示无效
    private int[] specialTokenIds = new int[256];
    // 需要保留的特殊 token ID 数组，如思考链相关的标记
    private int[] preservedTokenIds = new int[256];
    
    // 反向词汇表 (id -> token)
    private Map<Integer, String> vocabReverse = new HashMap<>();
    
    // JNI方法
    private native long createTokenizer(String path, boolean consistentTokenization);
    private native String[] tokenizeToStrings(long tokenizerPtr, String text);
    private native int[] tokenizeToIds(long tokenizerPtr, String text);
    private native String applyChatTemplate(long tokenizerPtr, String text, String jsonConfig);
    
    // 使用已有的 JNI 方法
    private String getVocabFromJNI() {
        return TokenizerJNI.getTokenizerConfig(nativePtr);
    }
    
    private String decodeIdsToString(int[] ids, boolean skipSpecialTokens) {
        // 将 int[] 转换为 JSON 字符串
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(ids[i]);
        }
        sb.append("]");
        return TokenizerJNI.decodeWithUnicodeFix(nativePtr, sb.toString(), skipSpecialTokens);
    }
    
    private String decodeIdsToString(int[] ids) {
        // 默认不跳过特殊token
        return decodeIdsToString(ids, false);
    }
    
    // 特殊token常量 - 这些常量可能在未来的方法中使用
    public static final String CLS_TOKEN = "[CLS]";
    public static final String SEP_TOKEN = "[SEP]";
    public static final String UNK_TOKEN = "[UNK]";
    public static final String PAD_TOKEN = "[PAD]";
    public static final String MASK_TOKEN = "[MASK]";
    
    // 特殊token变量 - 用于内部处理
    private String clsToken = CLS_TOKEN;
    private String sepToken = SEP_TOKEN;
    private String unkToken = UNK_TOKEN;
    private String padToken = PAD_TOKEN;
    private String maskToken = MASK_TOKEN;
    
    // 是否使用一致性分词策略
    private boolean useConsistentTokenization = false;
    
    // 是否启用调试模式
    private boolean debugMode = false;
    
    /**
     * 获取当前加载的模型路径
     * @return 模型路径字符串
     */
    public String getModelPath() {
        return modelPath;
    }
    
    /**
     * 使用指定的模型类型创建分词器
     * @param modelType 模型类型，如"bpe"或"wordpiece"
     * @throws IllegalArgumentException 如果创建分词器失败
     */
    public HuggingfaceTokenizer(String modelType) {
        this.nativePtr = TokenizerJNI.createTokenizer(modelType);
        if (this.nativePtr == 0) {
            throw new IllegalArgumentException("创建分词器失败，模型类型: " + modelType);
        }
    }
    
    /**
     * 从文件创建分词器
     * @param path 模型文件路径或模型类型
     * @param isFile 是否是文件路径，如果为false则表示是模型类型
     */
    public HuggingfaceTokenizer(String path, boolean isFile) {
        try {
            // 初始化特殊 token ID 数组
            for (int i = 0; i < specialTokenIds.length; i++) {
                specialTokenIds[i] = -1;
            }
            for (int i = 0; i < preservedTokenIds.length; i++) {
                preservedTokenIds[i] = -1;
            }
            
            // 保存模型路径
            this.modelPath = path;
            
            if (isFile) {
                System.out.println("[HuggingfaceTokenizer] 开始JNI调用加载分词器: " + path);
                long startTime = System.currentTimeMillis();
                
                // 从文件加载
                try {
                    nativePtr = TokenizerJNI.loadTokenizerFromFile(path);
                    long endTime = System.currentTimeMillis();
                    System.out.println("[HuggingfaceTokenizer] JNI调用完成，耗时: " + (endTime - startTime) + "ms");
                } catch (UnsatisfiedLinkError e) {
                    e.printStackTrace();
                    throw e;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                }
                
                // 加载词汇表
                try {
                    loadVocabFromFile(new File(path));
                    // 加载特殊 token ID
                    loadSpecialTokenIds(new File(path));
                } catch (Exception e) {
                    e.printStackTrace();
                    // 不抛出异常，因为词汇表加载失败不应影响分词器的基本功能
                }
            } else {
                // 使用模型类型创建
                try {
                    nativePtr = TokenizerJNI.createTokenizer(path);
                } catch (UnsatisfiedLinkError e) {
                    e.printStackTrace();
                    throw e;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                }
            }
            
            if (nativePtr == 0) {
                throw new IllegalArgumentException("创建分词器失败，可能是文件路径错误或格式不兼容");
            }
            
            System.out.println("[HuggingfaceTokenizer] 分词器创建成功，nativePtr: " + nativePtr);
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    

    
    /**
     * 对文本进行分词
     * @param text 要分词的文本
     * @return 分词结果
     * @throws IllegalStateException 如果分词器已关闭
     */
    public TokenizerResult tokenize(String text) {
        checkClosed();
        
        // System.out.println("[INFO] HuggingfaceTokenizer.tokenize 调用JNI");
        // System.out.println("[INFO] nativePtr: " + nativePtr);
        // System.out.println("[INFO] 输入文本: '" + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + "'");
        
        long startTime = System.currentTimeMillis();
        String jsonResult = TokenizerJNI.tokenize(nativePtr, text);
        long jniCallTime = System.currentTimeMillis();
        
        // System.out.println("[INFO] JNI调用耗时: " + (jniCallTime - startTime) + " ms");
        
        if (jsonResult == null) {
            System.out.println("[ERROR] JNI返回null，分词失败");
            throw new IllegalStateException("分词失败");
        }
        
        // System.out.println("[INFO] JNI返回JSON长度: " + jsonResult.length());
        // System.out.println("[INFO] JNI返回JSON前200字符: " + (jsonResult.length() > 200 ? jsonResult.substring(0, 200) + "..." : jsonResult));
        
        try {
            JSONObject json = new JSONObject(jsonResult);
            JSONArray idsArray = json.getJSONArray("ids");
            JSONArray tokensArray = json.getJSONArray("tokens");
            
            // System.out.println("[INFO] 解析JSON成功，ids数组长度: " + idsArray.length() + ", tokens数组长度: " + tokensArray.length());
            
            List<Integer> ids = new ArrayList<>();
            List<String> tokens = new ArrayList<>();
            
            for (int i = 0; i < idsArray.length(); i++) {
                ids.add(idsArray.getInt(i));
            }
            
            for (int i = 0; i < tokensArray.length(); i++) {
                tokens.add(tokensArray.getString(i));
            }
            
            long endTime = System.currentTimeMillis();
            // System.out.println("[INFO] tokenize方法完成，总耗时: " + (endTime - startTime) + " ms");
            
            return new TokenizerResult(ids, tokens);
        } catch (JSONException e) {
            System.out.println("[ERROR] 解析JSON失败: " + e.getMessage());
            throw new IllegalStateException("解析分词结果失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取分词器配置
     * @return 分词器配置的JSON对象
     * @throws IllegalStateException 如果分词器已关闭
     */
    public JSONObject getTokenizerConfig() {
        checkClosed();
        String jsonStr = TokenizerJNI.getTokenizerConfig(nativePtr);
        if (jsonStr == null) {
            throw new IllegalStateException("获取分词器配置失败");
        }
        
        try {
            return new JSONObject(jsonStr);
        } catch (JSONException e) {
            throw new IllegalStateException("解析分词器配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有特殊token
     * @return 特殊token的映射表，key为token名称，value为token内容
     * @throws IllegalStateException 如果分词器已关闭
     */
    public Map<String, String> getSpecialTokens() {
        checkClosed();
        Map<String, String> specialTokens = new HashMap<>();
        
        try {
            JSONObject config = getTokenizerConfig();
            
            // 尝试从配置中提取特殊token
            if (config.has("added_tokens")) {
                JSONArray addedTokens = config.getJSONArray("added_tokens");
                for (int i = 0; i < addedTokens.length(); i++) {
                    JSONObject tokenObj = addedTokens.getJSONObject(i);
                    if (tokenObj.has("content") && tokenObj.has("special") && tokenObj.getBoolean("special")) {
                        String tokenContent = tokenObj.getString("content");
                        // 尝试找出这个token的名称
                        String tokenName = "special_token_" + i;
                        
                        // 基于token内容猜测名称
                        if (tokenContent.contains("[CLS]") || tokenContent.equals("<s>")) {
                            tokenName = "cls_token";
                            clsToken = tokenContent;
                        } else if (tokenContent.contains("[SEP]") || tokenContent.equals("</s>")) {
                            tokenName = "sep_token";
                            sepToken = tokenContent;
                        } else if (tokenContent.contains("[UNK]") || tokenContent.equals("<unk>")) {
                            tokenName = "unk_token";
                            unkToken = tokenContent;
                        } else if (tokenContent.contains("[PAD]") || tokenContent.equals("<pad>")) {
                            tokenName = "pad_token";
                            padToken = tokenContent;
                        } else if (tokenContent.contains("[MASK]") || tokenContent.equals("<mask>")) {
                            tokenName = "mask_token";
                            maskToken = tokenContent;
                        }
                        
                        specialTokens.put(tokenName, tokenContent);
                    }
                }
            }
            
            // 如果没有找到特殊token，尝试从配置的其他部分查找
            if (specialTokens.isEmpty()) {
                // 检查常见的特殊token字段
                String[] tokenFields = {"cls_token", "sep_token", "unk_token", "pad_token", "mask_token"};
                for (String field : tokenFields) {
                    if (config.has(field)) {
                        String tokenContent = config.getString(field);
                        specialTokens.put(field, tokenContent);
                        
                        // 更新内部变量
                        if (field.equals("cls_token")) {
                            clsToken = tokenContent;
                        } else if (field.equals("sep_token")) {
                            sepToken = tokenContent;
                        } else if (field.equals("unk_token")) {
                            unkToken = tokenContent;
                        } else if (field.equals("pad_token")) {
                            padToken = tokenContent;
                        } else if (field.equals("mask_token")) {
                            maskToken = tokenContent;
                        }
                    }
                }
            }
            
            // 如果还是没有找到特殊token，使用默认值
            if (specialTokens.isEmpty()) {
                specialTokens.put("cls_token", clsToken);
                specialTokens.put("sep_token", sepToken);
                specialTokens.put("unk_token", unkToken);
                specialTokens.put("pad_token", padToken);
                specialTokens.put("mask_token", maskToken);
            }
            
            return specialTokens;
        } catch (Exception e) {
            // 如果出错，返回默认的特殊token
            specialTokens.put("cls_token", clsToken);
            specialTokens.put("sep_token", sepToken);
            specialTokens.put("unk_token", unkToken);
            specialTokens.put("pad_token", padToken);
            specialTokens.put("mask_token", maskToken);
            return specialTokens;
        }
    }
    
    /**
     * 获取特殊token的ID
     * @param tokenName token名称
     * @return token ID，如果不存在则返回-1
     * @throws IllegalStateException 如果分词器已关闭
     */
    public int getSpecialTokenId(String tokenName) {
        checkClosed();
        
        try {
            // 获取特殊token的内容
            Map<String, String> specialTokens = getSpecialTokens();
            String tokenContent = specialTokens.get(tokenName);
            
            if (tokenContent != null && vocab.containsKey(tokenContent)) {
                return vocab.get(tokenContent);
            }
            
            // 如果没有找到，尝试使用默认的特殊token
            if (tokenName.equals("cls_token") && vocab.containsKey(clsToken)) {
                return vocab.get(clsToken);
            } else if (tokenName.equals("sep_token") && vocab.containsKey(sepToken)) {
                return vocab.get(sepToken);
            } else if (tokenName.equals("unk_token") && vocab.containsKey(unkToken)) {
                return vocab.get(unkToken);
            } else if (tokenName.equals("pad_token") && vocab.containsKey(padToken)) {
                return vocab.get(padToken);
            } else if (tokenName.equals("mask_token") && vocab.containsKey(maskToken)) {
                return vocab.get(maskToken);
            }
            
            return -1; // 如果没有找到，返回-1
        } catch (Exception e) {
            return -1; // 如果出错，返回-1
        }
    }
    
    /**
     * 获取特殊token的内容
     * @param tokenName token名称
     * @return token内容，如果不存在则返回null
     * @throws IllegalStateException 如果分词器已关闭
     */
    public String getSpecialTokenContent(String tokenName) {
        checkClosed();
        Map<String, String> specialTokens = getSpecialTokens();
        return specialTokens.get(tokenName);
    }
    
    /**
     * 将token ID数组解码为文本
     * 注意：为了保持一致性，该方法现在直接调用 decodeForModelOutput 方法
     * @param ids token ID数组
     * @return 解码后的文本
     * @throws IllegalStateException 如果分词器已关闭
     */
    public String decode(int[] ids) {
        // 直接调用 decodeForModelOutput 方法，确保特殊标记被正确过滤
        return decodeForModelOutput(ids);
    }
    
    /**
     * 将token ID数组解码为文本，并跳过特殊token
     * @param ids token ID数组
     * @param skipSpecialTokens 是否跳过特殊token
     * @return 解码后的文本
     * @throws IllegalStateException 如果分词器已关闭
     */
    public String decode(int[] ids, boolean skipSpecialTokens) {
        checkClosed();
        return decodeIdsToString(ids, skipSpecialTokens);
    }
    
    /**
     * 加载特殊 token ID
     * @param modelFile 模型文件
     * @throws IOException 如果读取文件时出错
     * @throws JSONException 如果解析JSON时出错
     */
    private void loadSpecialTokenIds(File modelFile) throws IOException, JSONException {
        // 如果是目录，则查找 tokenizer.json 文件
        File tokenizerFile;
        if (modelFile.isDirectory()) {
            tokenizerFile = new File(modelFile, "tokenizer.json");
        } else {
            // 如果是文件，则尝试从同目录下找 tokenizer.json
            tokenizerFile = new File(modelFile.getParentFile(), "tokenizer.json");
        }
        
        if (!tokenizerFile.exists()) {
            System.err.println("未找到 tokenizer.json 文件: " + tokenizerFile.getAbsolutePath());
            return;
        }
        
        System.out.println("正在加载 tokenizer.json 文件: " + tokenizerFile.getAbsolutePath());
        
        // 读取 tokenizer.json 文件
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(tokenizerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        
        // 解析 JSON
        JSONObject json = new JSONObject(content.toString());
        
        // 获取特殊 token 信息
        int specialTokenIndex = 0;
        int preservedTokenIndex = 0;
        
        // 处理 added_tokens 部分
        if (json.has("added_tokens")) {
            JSONArray addedTokens = json.getJSONArray("added_tokens");
            for (int i = 0; i < addedTokens.length(); i++) {
                JSONObject token = addedTokens.getJSONObject(i);
                String tokenContent = token.getString("content");
                int id = token.getInt("id");
                boolean special = token.optBoolean("special", false);
                
                if (special) {
                    // 判断是否是需要保留的特殊 token（如思考链相关的标记）
                    if (tokenContent.equals("<think>") || tokenContent.equals("</think>")) {
                        preservedTokenIds[preservedTokenIndex++] = id;
                        System.out.println("添加需要保留的特殊 token: " + tokenContent + ", ID: " + id);
                    } else {
                        specialTokenIds[specialTokenIndex++] = id;
                        // System.out.println("添加需要过滤的特殊 token: " + tokenContent + ", ID: " + id);
                    }
                }
            }
        }
        
        // 处理 special_tokens_map 部分
        if (json.has("special_tokens_map")) {
            JSONObject specialTokensMap = json.getJSONObject("special_tokens_map");
            java.util.Iterator<String> keys = specialTokensMap.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = specialTokensMap.get(key);
                String tokenContent = null;
                
                if (value instanceof String) {
                    tokenContent = (String) value;
                } else if (value instanceof JSONObject) {
                    JSONObject tokenObj = (JSONObject) value;
                    if (tokenObj.has("content")) {
                        tokenContent = tokenObj.getString("content");
                    }
                }
                
                if (tokenContent != null && vocab.containsKey(tokenContent)) {
                    int id = vocab.get(tokenContent);
                    
                    // 判断是否是需要保留的特殊 token
                    if (tokenContent.equals("<think>") || tokenContent.equals("</think>")) {
                        preservedTokenIds[preservedTokenIndex++] = id;
                        System.out.println("添加需要保留的特殊 token: " + tokenContent + ", ID: " + id);
                    } else {
                        specialTokenIds[specialTokenIndex++] = id;
                        // System.out.println("添加需要过滤的特殊 token: " + tokenContent + ", ID: " + id);
                    }
                }
            }
        }
    }
    
    /**
     * 检查是否是特殊 token ID
     * @param id token ID
     * @return 如果是需要过滤的特殊 token ID 则返回 true，否则返回 false
     */
    private boolean isSpecialTokenId(int id) {
        String tokenContent = vocabReverse.containsKey(id) ? vocabReverse.get(id) : "<unknown>";
        System.out.println("检查是否是特殊 token ID: " + id + ", 内容: " + tokenContent);
        
        // 首先检查是否是需要保留的特殊 token ID
        for (int i = 0; i < preservedTokenIds.length && preservedTokenIds[i] != -1; i++) {
            if (preservedTokenIds[i] == id) {
                System.out.println("检测到需要保留的特殊 token ID: " + id + ", 内容: " + tokenContent);
                return false; // 返回 false，表示不将其过滤掉
            }
        }
        
        // 然后检查是否是需要过滤的特殊 token ID
        for (int i = 0; i < specialTokenIds.length && specialTokenIds[i] != -1; i++) {
            if (specialTokenIds[i] == id) {
                System.out.println("检测到需要过滤的特殊 token ID: " + id + ", 内容: " + tokenContent);
                return true; // 返回 true，表示需要过滤掉
            }
        }
        
        return false; // 不是特殊 token ID，不需要过滤
    }
    
    /**
     * 将token ID数组解码为文本，专门用于模型输出处理
     * 会过滤掉特定的特殊token，但保留<think>和</think>标记
     * @param ids token ID数组
     * @return 解码后的文本
     * @throws IllegalStateException 如果分词器已关闭
     */
    public String decodeForModelOutput(int[] ids) {
        checkClosed();
        
        System.out.println("输入 token ID 数量: " + ids.length);
        for (int i = 0; i < Math.min(10, ids.length); i++) {
            System.out.println("输入 token ID[" + i + "]: " + ids[i] + ", 内容: " + 
                (vocabReverse.containsKey(ids[i]) ? vocabReverse.get(ids[i]) : "<unknown>"));
        }
        
        // 过滤掉特殊 token ID，但保留需要保留的 token ID
        // 注意：isSpecialTokenId 方法已经考虑了需要保留的特殊 token ID
        List<Integer> filteredIds = new ArrayList<>();
        for (int id : ids) {
            // 如果不是特殊 token ID，则保留
            if (!isSpecialTokenId(id)) {
                filteredIds.add(id);
            } else {
                String tokenContent = vocabReverse.containsKey(id) ? vocabReverse.get(id) : "<unknown>";
                System.out.println("过滤掉特殊 token ID: " + id + ", 内容: " + tokenContent);
            }
        }
        
        //System.out.println("过滤后 token ID 数量: " + filteredIds.size());
        
        // 将过滤后的 ID 转换为数组
        int[] filteredIdsArray = new int[filteredIds.size()];
        for (int i = 0; i < filteredIds.size(); i++) {
            filteredIdsArray[i] = filteredIds.get(i);
        }
        
        // 解码（已包含Unicode修复）
        String result = decodeIdsToString(filteredIdsArray);
        
        // 强化Unicode修复 - 确保所有Unicode转义序列都被正确解码
        if (result != null && result.contains("\\u")) {
            String originalResult = result;
            result = UnicodeDecoder.decodeUnicodeEscapes(result);
            
            // 多次修复，直到没有更多的Unicode转义序列
            int maxAttempts = 3;
            int attempts = 0;
            while (result.contains("\\u") && attempts < maxAttempts) {
                String beforeFix = result;
                result = UnicodeDecoder.decodeUnicodeEscapes(result);
                attempts++;
                
                // 如果没有变化，跳出循环避免无限循环
                if (beforeFix.equals(result)) {
                    break;
                }
            }
            
            System.out.println("Unicode修复: 原始长度=" + originalResult.length() + ", 修复后长度=" + result.length() + ", 修复次数=" + attempts);
        }
        
        //System.out.println("字符串过滤后的解码结果: " + result);
        return result;
    }
    
    /**
     * 应用聊天模板
     * 这个方法与 Python 的 tokenizer.apply_chat_template 方法类似
     * @param messages 消息数组，每个消息应包含 role 和 content 字段
     * @param addGenerationPrompt 是否添加生成提示
     * @param enableThinking 是否启用思考模式
     * @param chatTemplate 自定义聊天模板，如果为null则使用模型默认模板
     * @param continueFinalMessage 是否继续最后一条消息，而不是开始新消息
     * @return 应用模板后的文本
     */
    public String applyChatTemplate(JSONArray messages, boolean addGenerationPrompt, boolean enableThinking,
                               String chatTemplate, boolean continueFinalMessage) {
        checkClosed();
        
        try {
            // 获取分词器配置
            JSONObject config = getTokenizerConfig();
            Map<String, String> specialTokens = getSpecialTokens();
            
            // 确定使用哪个模板
            String templateToUse = chatTemplate;
            if (templateToUse == null) {
                // 尝试从配置中获取聊天模板
                if (config.has("chat_template")) {
                    templateToUse = config.getString("chat_template");
                }
            }
            
            // 检查是否同时设置了 addGenerationPrompt 和 continueFinalMessage
            if (addGenerationPrompt && continueFinalMessage) {
                throw new IllegalArgumentException("不能同时设置 addGenerationPrompt 和 continueFinalMessage");
            }
            
            // 如果有模板，使用模板，否则使用默认模板
            if (templateToUse != null && !templateToUse.isEmpty()) {
                return applyTemplate(templateToUse, messages, specialTokens, addGenerationPrompt, enableThinking, continueFinalMessage);
            } else {
                return applyDefaultTemplate(messages, specialTokens, addGenerationPrompt, enableThinking, continueFinalMessage);
            }
        } catch (Exception e) {
            throw new RuntimeException("应用聊天模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 应用聊天模板（简化版本，兼容旧接口）
     * @param messages 消息数组，每个消息应包含 role 和 content 字段
     * @param addGenerationPrompt 是否添加生成提示
     * @param enableThinking 是否启用思考模式
     * @return 应用模板后的文本
     * @throws IllegalStateException 如果分词器已关闭
     */
    public String applyChatTemplate(JSONArray messages, boolean addGenerationPrompt, boolean enableThinking) {
        return applyChatTemplate(messages, addGenerationPrompt, enableThinking, null, false);
    }
    
    /**
     * 应用指定的模板
     * @param template 模板字符串
     * @param messages 消息数组
     * @param specialTokens 特殊标记映射
     * @param addGenerationPrompt 是否添加生成提示
     * @param enableThinking 是否启用思考模式
     * @param continueFinalMessage 是否继续最后一条消息
     * @return 应用模板后的文本
     */
    private String applyTemplate(String template, JSONArray messages, Map<String, String> specialTokens, 
                                boolean addGenerationPrompt, boolean enableThinking, boolean continueFinalMessage) {
        // 替换模板变量
        String result = template;
        
        // 替换 messages 变量
        result = replaceVariable(result, "messages", messages);
        
        // 替换 add_generation_prompt 变量
        result = replaceVariable(result, "add_generation_prompt", addGenerationPrompt);
        
        // 替换 enable_thinking 变量
        result = replaceVariable(result, "enable_thinking", enableThinking);
        
        // 替换 continue_final_message 变量
        result = replaceVariable(result, "continue_final_message", continueFinalMessage);
        
        // 替换特殊标记
        for (Map.Entry<String, String> entry : specialTokens.entrySet()) {
            String tokenName = entry.getKey();
            String tokenValue = entry.getValue();
            result = result.replace("<|" + tokenName + "|>", tokenValue);
        }
        
        // 处理其他变量表达式
        // 移除所有未替换的变量表达式
        result = result.replaceAll("\\{\\{[^\\}]*\\}\\}", "");
        
        // 如果禁用思考模式，在用户提示词尾部添加/no_think指令
        if (!enableThinking) {
            // 查找最后一个用户消息的位置
            String imStart = specialTokens.getOrDefault("im_start", "<|im_start|>");
            String imEnd = specialTokens.getOrDefault("im_end", "<|im_end|>");
            String userRole = "user";
            
            // 查找最后一个用户消息的结束位置
            String userStart = imStart + userRole;
            int lastUserIndex = result.lastIndexOf(userStart);
            if (lastUserIndex >= 0) {
                // 查找该用户消息的结束标记
                int userEndIndex = result.indexOf(imEnd, lastUserIndex);
                if (userEndIndex >= 0) {
                    // 在用户消息结束标记前添加/no_think指令
                    result = result.substring(0, userEndIndex) + "\n/no_think" + result.substring(userEndIndex);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 应用默认模板
     * @param messages 消息数组
     * @param specialTokens 特殊标记映射
     * @param addGenerationPrompt 是否添加生成提示
     * @param enableThinking 是否启用思考模式
     * @param continueFinalMessage 是否继续最后一条消息
     * @return 应用模板后的文本
     */
    private String applyDefaultTemplate(JSONArray messages, Map<String, String> specialTokens,
                                     boolean addGenerationPrompt, boolean enableThinking, boolean continueFinalMessage) {
        StringBuilder result = new StringBuilder();
        String imStart = specialTokens.getOrDefault("im_start", "<|im_start|>");
        String imEnd = specialTokens.getOrDefault("im_end", "<|im_end|>");
        String thinkTag = specialTokens.getOrDefault("think_tag", "<think>");
        
        // 处理消息数组
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                String role = message.getString("role");
                String content = message.getString("content");
                
                // 根据角色添加相应的标记
                result.append(imStart).append(role).append("\n");
                
                // 思考模式处理已移至统一的/no_think指令方式
                
                result.append(content).append("\n");
                result.append(imEnd).append("\n");
            }
        } catch (JSONException e) {
            throw new RuntimeException("Error processing messages: " + e.getMessage(), e);
        }
        
        // 如果需要添加生成提示
        if (addGenerationPrompt) {
            result.append(imStart).append("assistant\n");
            // 思考模式处理已移至统一的/no_think指令方式
        }
        
        // 如果禁用思考模式，在最后一个用户消息尾部添加/no_think指令
        if (!enableThinking && messages.length() > 0) {
            try {
                // 查找最后一个用户消息
                for (int i = messages.length() - 1; i >= 0; i--) {
                    JSONObject message = messages.getJSONObject(i);
                    String role = message.getString("role");
                    if ("user".equals(role)) {
                        // 在结果中查找这个用户消息的位置并添加/no_think指令
                        String userStart = imStart + "user";
                        int userIndex = result.lastIndexOf(userStart);
                        if (userIndex >= 0) {
                            int userEndIndex = result.indexOf(imEnd, userIndex);
                            if (userEndIndex >= 0 && !result.substring(userIndex, userEndIndex).contains("/no_think")) {
                                result.insert(userEndIndex, "\n/no_think");
                            }
                        }
                        break;
                    }
                }
            } catch (JSONException e) {
                throw new RuntimeException("Error processing thinking mode: " + e.getMessage(), e);
            }
        }
        
        // 如果需要继续最后一条消息
        if (continueFinalMessage && messages.length() > 0) {
            try {
                // 获取最后一条消息
                JSONObject lastMessage = messages.getJSONObject(messages.length() - 1);
                String role = lastMessage.getString("role");
                
                // 只有当最后一条消息是助手消息时才继续
                if ("assistant".equals(role)) {
                    // 移除最后一个 im_end 标记
                    int lastImEndIndex = result.lastIndexOf(imEnd);
                    if (lastImEndIndex >= 0) {
                        result.delete(lastImEndIndex, result.length());
                    }
                }
            } catch (JSONException e) {
                throw new RuntimeException("Error processing final message continuation: " + e.getMessage(), e);
            }
        }
        
        return result.toString();
    }
    
    /**
     * 替换模板中的变量
     * @param template 模板字符串
     * @param variable 变量名
     * @param value 变量值
     * @return 替换后的字符串
     */
    private String replaceVariable(String template, String variable, Object value) {
        // 将值转换为字符串
        String valueStr;
        if (value instanceof JSONArray || value instanceof JSONObject) {
            valueStr = value.toString();
        } else {
            valueStr = String.valueOf(value);
        }
        
        // 支持多种变量格式: {{ var }}, {{var}}, {{ var}}, {{var }}
        String result = template;
        result = result.replace("{{ " + variable + " }}", valueStr);
        result = result.replace("{{" + variable + "}}", valueStr);
        result = result.replace("{{ " + variable + "}}", valueStr);
        result = result.replace("{{" + variable + " }}", valueStr);
        return result;
    }
    
    /**
     * 获取当前时间格式化字符串
     * @param format 格式化字符串
     * @return 格式化后的时间字符串
     */
    private String getCurrentTimeFormatted(String format) {
        SimpleDateFormat formatter = new SimpleDateFormat(format, Locale.getDefault());
        return formatter.format(new Date());
    }
    
    /**
     * 从文件加载特殊token
     * @param file 分词器文件
     * @throws IOException 如果文件读取失败
     * @throws JSONException 如果JSON解析失败
     */
    private void loadVocabFromFile(File file) throws IOException, JSONException {
        // 直接使用tokenizer.json文件，不再尝试加载vocab.json
        File tokenizerFile = new File(file.getParentFile(), "tokenizer.json");
        
        if (tokenizerFile.exists()) {
            try {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(tokenizerFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                }
                
                JSONObject json = new JSONObject(content.toString());
                
                // 不再加载完整词汇表，只提取关键特殊token
                
                // 从 special_tokens_map 中提取特殊token
                if (json.has("special_tokens_map")) {
                    JSONObject specialTokensMap = json.getJSONObject("special_tokens_map");
                    
                    // 提取常用特殊token
                    if (specialTokensMap.has("cls_token")) {
                        clsToken = specialTokensMap.getString("cls_token");
                    }
                    if (specialTokensMap.has("sep_token")) {
                        sepToken = specialTokensMap.getString("sep_token");
                    }
                    if (specialTokensMap.has("unk_token")) {
                        unkToken = specialTokensMap.getString("unk_token");
                    }
                    if (specialTokensMap.has("pad_token")) {
                        padToken = specialTokensMap.getString("pad_token");
                    }
                    if (specialTokensMap.has("mask_token")) {
                        maskToken = specialTokensMap.getString("mask_token");
                    }
                }
                
                // 如果没有找到special_tokens_map，尝试从added_tokens中提取
                if (json.has("added_tokens")) {
                    JSONArray addedTokens = json.getJSONArray("added_tokens");
                    for (int i = 0; i < addedTokens.length(); i++) {
                        JSONObject tokenObj = addedTokens.getJSONObject(i);
                        if (tokenObj.has("content") && tokenObj.has("special") && tokenObj.getBoolean("special")) {
                            String tokenContent = tokenObj.getString("content");
                            int tokenId = -1;
                            if (tokenObj.has("id")) {
                                tokenId = tokenObj.getInt("id");
                            }
                            
                            // 只记录关键特殊token
                            if (tokenContent.contains("[CLS]") || tokenContent.equals("<s>")) {
                                clsToken = tokenContent;
                                if (tokenId >= 0) {
                                    vocab.put(tokenContent, tokenId);
                                    vocabReverse.put(tokenId, tokenContent);
                                }
                            } else if (tokenContent.contains("[SEP]") || tokenContent.equals("</s>")) {
                                sepToken = tokenContent;
                                if (tokenId >= 0) {
                                    vocab.put(tokenContent, tokenId);
                                    vocabReverse.put(tokenId, tokenContent);
                                }
                            } else if (tokenContent.contains("[UNK]") || tokenContent.equals("<unk>")) {
                                unkToken = tokenContent;
                                if (tokenId >= 0) {
                                    vocab.put(tokenContent, tokenId);
                                    vocabReverse.put(tokenId, tokenContent);
                                }
                            } else if (tokenContent.contains("[PAD]") || tokenContent.equals("<pad>")) {
                                padToken = tokenContent;
                                if (tokenId >= 0) {
                                    vocab.put(tokenContent, tokenId);
                                    vocabReverse.put(tokenId, tokenContent);
                                }
                            } else if (tokenContent.contains("[MASK]") || tokenContent.equals("<mask>")) {
                                maskToken = tokenContent;
                                if (tokenId >= 0) {
                                    vocab.put(tokenContent, tokenId);
                                    vocabReverse.put(tokenId, tokenContent);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 捕获异常但不抛出，因为特殊token加载失败不应影响分词器的基本功能
                // 不打印详细错误信息，避免日志过大
            }
        }
        // 如果文件不存在，不抛出异常，使用默认特殊token
    }
    
    /**
     * 对文本进行分词，返回long[][]格式的结果
     * 这个方法是为了兼容HuggingfaceTokenizer的接口
     * @param text 输入文本
     * @return 分词结果（token ID矩阵）
     */
    public long[][] tokenizeToLongArray(String text) {
        checkClosed();
        
        // System.out.println("[INFO] HuggingfaceTokenizer.tokenizeToLongArray 开始分词");
        // System.out.println("[INFO] 输入文本长度: " + text.length());
        // System.out.println("[INFO] 当前特殊token数量: " + vocab.size());
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 使用现有的tokenize方法（调用JNI）
            // System.out.println("[INFO] 调用JNI tokenize方法");
            TokenizerResult result = tokenize(text);
            List<Integer> ids = result.getIds();
            List<String> tokens = result.getTokens();
            
            long jniTime = System.currentTimeMillis();
            // System.out.println("[INFO] JNI分词完成，耗时: " + (jniTime - startTime) + " ms");
            // System.out.println("[INFO] JNI返回token数量: " + ids.size());
            
            // 打印前5个token用于调试
            if (ids.size() > 0) {
                StringBuilder tokenInfo = new StringBuilder();
                int printCount = Math.min(5, ids.size());
                for (int i = 0; i < printCount; i++) {
                    tokenInfo.append(String.format("[%d:'%s']", ids.get(i), tokens.get(i)));
                    if (i < printCount - 1) tokenInfo.append(", ");
                }
                if (ids.size() > 5) tokenInfo.append("...");
                // System.out.println("[INFO] 前" + printCount + "个token: " + tokenInfo.toString());
            }
            
            // 将List<Integer>转换为long[][]格式
            long[][] longResult = new long[1][ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                longResult[0][i] = ids.get(i);
            }
            
            long endTime = System.currentTimeMillis();
            // System.out.println("[INFO] tokenizeToLongArray完成，总耗时: " + (endTime - startTime) + " ms");
            
            return longResult;
        } catch (Exception e) {
            System.out.println("[ERROR] tokenizeToLongArray异常: " + e.getMessage());
            e.printStackTrace();
            return new long[1][0]; // 返回空结果
        }
    }
    
    /**
     * 关闭分词器并释放资源
     */
    @Override
    public synchronized void close() {
        if (!isClosed) {
            try {
                System.out.println("[HuggingfaceTokenizer] 开始释放分词器资源，nativePtr: " + nativePtr);
                
                // 添加短暂延迟，确保JNI层准备就绪
                Thread.sleep(50);
                
                // 检查nativePtr是否有效
                if (nativePtr != 0) {
                    TokenizerJNI.freeTokenizer(nativePtr);
                    System.out.println("[HuggingfaceTokenizer] 成功释放JNI资源");
                } else {
                    System.out.println("[HuggingfaceTokenizer] nativePtr为0，跳过JNI资源释放");
                }
                
                isClosed = true;
                nativePtr = 0; // 清空指针
                System.out.println("[HuggingfaceTokenizer] 分词器资源释放完成");
                
            } catch (Exception e) {
                System.err.println("[HuggingfaceTokenizer] 释放资源时发生异常: " + e.getMessage());
                e.printStackTrace();
                // 即使出现异常，也要标记为已关闭，避免重复释放
                isClosed = true;
                nativePtr = 0;
            }
        } else {
            System.out.println("[HuggingfaceTokenizer] 分词器已经关闭，跳过释放");
        }
    }
    
    // ===== 实现Model接口的方法 =====
    
    /**
     * 对文本列表进行分词（实现Model接口）
     * @param tokens 要分词的文本列表
     * @return 分词结果的token ID列表
     */
    @Override
    public List<Integer> tokenize(List<String> tokens) {
        StringBuilder text = new StringBuilder();
        for (String token : tokens) {
            if (text.length() > 0) {
                text.append(" ");
            }
            text.append(token);
        }
        
        try {
            TokenizerResult result = tokenize(text.toString());
            return result.getIds();
        } catch (Exception e) {
            // 如果分词失败，返回未知标记
            List<Integer> fallback = new ArrayList<>();
            int unkTokenId = getSpecialTokenId("unk_token");
            if (unkTokenId != -1) {
                fallback.add(unkTokenId);
            }
            return fallback;
        }
    }
    
    /**
     * 将token转换为ID（实现Model接口）
     * @param token 要转换的token
     * @return token的ID，如果不存在则返回未知标记ID
     */
    @Override
    public int tokenToId(String token) {
        return vocab.getOrDefault(token, getSpecialTokenId("unk_token"));
    }
    
    /**
     * 将ID转换为token（实现Model接口）
     * @param id 要转换的ID
     * @return 对应的token，如果不存在则返回空字符串
     */
    @Override
    public String idToToken(int id) {
        return vocabReverse.getOrDefault(id, "");
    }
    
    /**
     * 获取词汇表（实现Model接口）
     * @return 词汇表映射（token -> id）
     */
    @Override
    public Map<String, Integer> getVocab() {
        return vocab;
    }
    
    /**
     * 获取特殊token数量（实现Model接口）
     * 注意：这里返回的是特殊token的数量，不是完整词汇表大小
     * @return 特殊token数量
     */
    @Override
    public int getSpecialTokensSize() {
        System.out.println("获取特殊token数量: " + vocab.size());
        return vocab.size();
    }
    

    
    /**
     * 加载配置（实现TokenizerComponent接口）
     * @param config 配置信息
     * @throws JSONException 如果JSON解析失败
     */
    @Override
    public void loadConfig(JSONObject config) throws JSONException {
        // 重新加载配置
        if (config != null && config.has("vocab")) {
            vocab.clear();
            vocabReverse.clear();
            
            JSONObject vocabJson = config.getJSONObject("vocab");
            java.util.Iterator<String> keys = vocabJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int id = vocabJson.getInt(key);
                vocab.put(key, id);
                vocabReverse.put(id, key);
            }
        }
        
        // 更新特殊标记
        if (config != null) {
            if (config.has("cls_token")) {
                clsToken = config.getString("cls_token");
            }
            if (config.has("sep_token")) {
                sepToken = config.getString("sep_token");
            }
            if (config.has("unk_token")) {
                unkToken = config.getString("unk_token");
            }
            if (config.has("pad_token")) {
                padToken = config.getString("pad_token");
            }
            if (config.has("mask_token")) {
                maskToken = config.getString("mask_token");
            }
        }
    }
    
    /**
     * 从映射加载词汇表（实现Model接口）
     * @param vocabJson 词汇表JSON对象
     * @throws JSONException 如果JSON解析失败
     */
    @Override
    public void loadVocabFromMap(JSONObject vocabJson) throws JSONException {
        try {
            vocab.clear();
            vocabReverse.clear();
            
            int count = 0;
            java.util.Iterator<String> keys = vocabJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int id = vocabJson.getInt(key);
                vocab.put(key, id);
                vocabReverse.put(id, key);
                count++;
                
                // 每加载10000个打印一次进度，避免日志过多
                if (debugMode && count % 10000 == 0) {
                    System.out.println("词汇表加载进度: " + count + " 个token");
                }
            }
            
            if (debugMode) {
                System.out.println("成功加载词汇表，大小: " + count + " 个token");
            }
        } catch (Exception e) {
            System.err.println("加载词汇表映射失败");
            throw e; // 重新抛出异常，使调用者知道出错了
        }
    }
    
    /**
     * 从数组加载词汇表（实现Model接口）
     * @param vocabArray 词汇表数组
     * @throws JSONException 如果JSON解析失败
     */
    @Override
    public void loadVocabFromArray(JSONArray vocabArray) throws JSONException {
        vocab.clear();
        vocabReverse.clear();
        for (int i = 0; i < vocabArray.length(); i++) {
            String token = vocabArray.getString(i);
            vocab.put(token, i);
            vocabReverse.put(i, token);
        }
        if (debugMode) {
            System.out.println("从JSONArray加载词汇表，大小: " + vocab.size());
        }
    }
    
    /**
     * 设置未知标记ID（实现Model接口）
     * @param unkId 未知标记ID
     */
    @Override
    public void setUnkTokenId(int unkId) {
        // 在已有的词汇表中查找对应的token
        String token = vocabReverse.get(unkId);
        if (token != null) {
            unkToken = token;
        }
    }
    
    /**
     * 检查是否存在指定的token（实现Model接口）
     * @param token 要检查的token
     * @return 是否存在
     */
    @Override
    public boolean hasToken(String token) {
        return vocab.containsKey(token);
    }
    
    /**
     * 添加新的token（实现Model接口）
     * @param token 要添加的token
     * @param id token的ID
     */
    @Override
    public void addToken(String token, int id) {
        vocab.put(token, id);
        vocabReverse.put(id, token);
    }
    
    /**
     * 分词结果类
     */
    public static class TokenizerResult {
        private final List<Integer> ids;
        private final List<String> tokens;
        
        public TokenizerResult(List<Integer> ids, List<String> tokens) {
            this.ids = ids;
            this.tokens = tokens;
        }
        
        /**
         * 获取token IDs
         * @return token ID列表
         */
        public List<Integer> getIds() {
            return ids;
        }
        
        /**
         * 获取tokens
         * @return token字符串列表
         */
        public List<String> getTokens() {
            return tokens;
        }
    }
}
