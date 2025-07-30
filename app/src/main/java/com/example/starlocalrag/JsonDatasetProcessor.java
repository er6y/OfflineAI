package com.example.starlocalrag;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON 训练集数据处理器
 * 用于处理各种格式的 JSON 训练集数据
 */
public class JsonDatasetProcessor {
    private static final String TAG = "StarLocalRAG_JsonProc";
    
    // 最小文本块大小，从ConfigManager中获取
    private int minChunkSize;
    
    // 上下文
    private final Context context;
    
    /**
     * 构造函数
     * @param context 上下文
     */
    public JsonDatasetProcessor(Context context) {
        this.context = context;
        this.minChunkSize = ConfigManager.getMinChunkSize(context);
    }
    
    /**
     * 处理 JSON 字符串，提取训练数据
     * @param jsonContent JSON 字符串内容
     * @return 处理后的文本块列表
     */
    public static List<String> processJsonDataset(Context context, String jsonContent) {
        List<String> chunks = new ArrayList<>();
        
        try {
            LogManager.logD(TAG, "开始处理JSON数据，内容长度: " + jsonContent.length() + " 字符");
            
            // 首先尝试解析为 JSON 对象或数组
            if (jsonContent.trim().startsWith("[")) {
                // JSON 数组格式
                JSONArray jsonArray = new JSONArray(jsonContent);
                LogManager.logD(TAG, "检测到JSON数组格式，共 " + jsonArray.length() + " 个元素");
                processJsonArray(context, jsonArray, chunks);
            } else if (jsonContent.trim().startsWith("{")) {
                // JSON 对象格式
                JSONObject jsonObject = new JSONObject(jsonContent);
                LogManager.logD(TAG, "检测到JSON对象格式，包含 " + jsonObject.length() + " 个字段");
                processJsonObject(context, jsonObject, chunks);
            } else {
                LogManager.logE(TAG, "无法识别的 JSON 格式");
                return chunks;
            }
            
            LogManager.logD(TAG, "JSON 处理完成，生成 " + chunks.size() + " 个文本块");
            LogManager.logD(TAG, "优化开关状态: " + (chunks.size() > 0 ? "开启" : "关闭"));
            LogManager.logD(TAG, "分块数量: " + chunks.size());
            return chunks;
        } catch (JSONException e) {
            LogManager.logE(TAG, "JSON 解析失败: " + e.getMessage(), e);
            return chunks;
        }
    }
    
    /**
     * 处理 JSON 字符串，提取训练数据
     * @param jsonContent JSON 字符串内容
     * @param ignoreMinSize 是否忽略最小块大小限制，严格按照项的个数处理
     * @return 处理后的文本块列表
     */
    public static List<String> processJsonDataset(Context context, String jsonContent, boolean ignoreMinSize) {
        List<String> chunks = new ArrayList<>();
        
        try {
            LogManager.logD(TAG, "开始处理JSON数据，内容长度: " + jsonContent.length() + " 字符" + 
                  (ignoreMinSize ? " (忽略最小块大小限制)" : ""));
            
            // 首先尝试解析为 JSON 对象或数组
            if (jsonContent.trim().startsWith("[")) {
                // JSON 数组格式
                JSONArray jsonArray = new JSONArray(jsonContent);
                LogManager.logD(TAG, "检测到JSON数组格式，共 " + jsonArray.length() + " 个元素");
                
                // 检查是否为特定数据集
                if (ignoreMinSize) {
                    LogManager.logD(TAG, "使用特定数据集处理模式，忽略最小块大小限制");
                }
                
                processJsonArray(context, jsonArray, chunks, ignoreMinSize);
            } else if (jsonContent.trim().startsWith("{")) {
                // JSON 对象格式
                JSONObject jsonObject = new JSONObject(jsonContent);
                LogManager.logD(TAG, "检测到JSON对象格式，包含 " + jsonObject.length() + " 个字段");
                processJsonObject(context, jsonObject, chunks);
            } else {
                LogManager.logE(TAG, "无法识别的 JSON 格式");
                return chunks;
            }
            
            LogManager.logD(TAG, "JSON 处理完成，生成 " + chunks.size() + " 个文本块");
            LogManager.logD(TAG, "优化开关状态: " + (chunks.size() > 0 ? "开启" : "关闭"));
            LogManager.logD(TAG, "分块数量: " + chunks.size());
            return chunks;
        } catch (JSONException e) {
            LogManager.logE(TAG, "JSON 解析失败: " + e.getMessage(), e);
            return chunks;
        }
    }
    
    /**
     * 判断文本内容是否为JSON格式
     * @param text 文本内容
     * @return 是否为JSON格式
     */
    public static boolean isJsonContent(String text) {
        if (text == null || text.trim().isEmpty()) {
            LogManager.logD(TAG, "文本为空，不是JSON格式");
            return false;
        }
        
        String trimmedText = text.trim();
        boolean startsWithBrace = trimmedText.startsWith("{");
        boolean startsWithBracket = trimmedText.startsWith("[");
        boolean endsWithBrace = trimmedText.endsWith("}");
        boolean endsWithBracket = trimmedText.endsWith("]");
        
        boolean potentialJson = (startsWithBrace && endsWithBrace) || (startsWithBracket && endsWithBracket);
        
        if (!potentialJson) {
            LogManager.logD(TAG, "文本不符合JSON基本格式要求（不是以{或[开头，以}或]结尾）");
            return false;
        }
        
        // 检查是否为Alpaca格式的JSON数组
        if (startsWithBracket && trimmedText.contains("\"instruction\"") && 
            (trimmedText.contains("\"output\"") || trimmedText.contains("\"response\""))) {
            LogManager.logD(TAG, "检测到可能是Alpaca格式的JSON数组");
            
            // 尝试解析完整文本
            try {
                new JSONArray(trimmedText);
                LogManager.logD(TAG, "成功解析完整JSON数组，确认为有效JSON格式");
                return true;
            } catch (JSONException e) {
                // 如果完整解析失败，尝试修复和部分解析
                LogManager.logD(TAG, "完整JSON解析失败: " + e.getMessage() + "，尝试修复和部分解析");
                
                try {
                    // 尝试解析前10个元素，检查是否为有效的JSON数组格式
                    int openBracketCount = 0;
                    int closeBracketCount = 0;
                    int elementCount = 0;
                    boolean inString = false;
                    boolean escaped = false;
                    StringBuilder validJsonBuilder = new StringBuilder("[");
                    
                    for (int i = 1; i < Math.min(trimmedText.length(), 10000); i++) {
                        char c = trimmedText.charAt(i);
                        validJsonBuilder.append(c);
                        
                        if (escaped) {
                            escaped = false;
                            continue;
                        }
                        
                        if (c == '\\') {
                            escaped = true;
                            continue;
                        }
                        
                        if (c == '"' && !escaped) {
                            inString = !inString;
                            continue;
                        }
                        
                        if (!inString) {
                            if (c == '{') {
                                openBracketCount++;
                            } else if (c == '}') {
                                closeBracketCount++;
                                if (openBracketCount == closeBracketCount && openBracketCount > 0) {
                                    elementCount++;
                                    // 如果已找到足够的元素，提前结束
                                    if (elementCount >= 3) {
                                        validJsonBuilder.append("]");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    
                    // 如果找到了至少一个完整的元素
                    if (elementCount > 0) {
                        String partialJson = validJsonBuilder.toString();
                        new JSONArray(partialJson);
                        LogManager.logD(TAG, "部分JSON解析成功，找到 " + elementCount + " 个有效元素，确认为JSON格式");
                        return true;
                    }
                } catch (Exception ex) {
                    LogManager.logD(TAG, "部分JSON解析也失败: " + ex.getMessage());
                }
            }
        }
        
        // 标准JSON验证
        try {
            // 对于大文件，只验证文件的开头和结尾部分
            if (trimmedText.length() > 5000) {
                // 检查开头部分
                String startPart = trimmedText.substring(0, 2000);
                // 检查结尾部分
                String endPart = trimmedText.substring(trimmedText.length() - 2000);
                
                // 对于数组，检查是否包含至少一个完整的元素
                if (startsWithBracket) {
                    // 找到第一个完整的元素
                    int firstElementEnd = findFirstCompleteElement(startPart);
                    if (firstElementEnd > 0) {
                        String firstElement = startPart.substring(0, firstElementEnd) + "]";
                        try {
                            new JSONArray(firstElement);
                            LogManager.logD(TAG, "成功解析JSON数组的第一个元素，确认为有效JSON格式");
                            return true;
                        } catch (JSONException e) {
                            LogManager.logD(TAG, "第一个元素解析失败: " + e.getMessage());
                        }
                    }
                }
                
                LogManager.logD(TAG, "文件过大，无法完整验证JSON格式，但基本结构符合JSON要求");
                return potentialJson; // 返回基于基本结构的判断
            } else {
                // 对于小文件，尝试完整解析
                if (startsWithBrace) {
                    new JSONObject(trimmedText);
                    LogManager.logD(TAG, "成功解析JSON对象格式");
                } else {
                    new JSONArray(trimmedText);
                    LogManager.logD(TAG, "成功解析JSON数组格式");
                }
                
                LogManager.logD(TAG, "文本是有效的JSON格式，总长度: " + trimmedText.length() + " 字符");
                return true;
            }
        } catch (JSONException e) {
            LogManager.logD(TAG, "JSON解析失败: " + e.getMessage() + "，可能不是有效的JSON格式");
            return false;
        }
    }
    
    /**
     * 查找JSON数组中第一个完整元素的结束位置
     * @param jsonStart JSON数组的开始部分
     * @return 第一个完整元素的结束位置，如果没有找到则返回-1
     */
    private static int findFirstCompleteElement(String jsonStart) {
        if (!jsonStart.startsWith("[")) {
            return -1;
        }
        
        int openBraces = 0;
        boolean inString = false;
        boolean escaped = false;
        
        for (int i = 1; i < jsonStart.length(); i++) {
            char c = jsonStart.charAt(i);
            
            if (escaped) {
                escaped = false;
                continue;
            }
            
            if (c == '\\') {
                escaped = true;
                continue;
            }
            
            if (c == '"' && !escaped) {
                inString = !inString;
                continue;
            }
            
            if (!inString) {
                if (c == '{') {
                    openBraces++;
                } else if (c == '}') {
                    openBraces--;
                    if (openBraces == 0) {
                        // 找到一个完整的对象
                        int commaPos = jsonStart.indexOf(',', i);
                        if (commaPos > 0) {
                            return commaPos + 1; // 返回逗号后的位置
                        } else {
                            return i + 1; // 返回右花括号后的位置
                        }
                    }
                } else if (c == ',' && openBraces == 0) {
                    // 找到一个完整的元素（可能不是对象）
                    return i + 1;
                } else if (c == ']' && openBraces == 0) {
                    // 数组结束
                    return i;
                }
            }
        }
        
        return -1; // 没有找到完整的元素
    }
    
    /**
     * 处理 JSON 数组
     * @param jsonArray JSON 数组
     * @param chunks 存储结果的文本块列表
     */
    private static void processJsonArray(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        // 检查是否为空数组
        if (jsonArray.length() == 0) {
            LogManager.logD(TAG, "JSON数组为空，无法处理");
            return;
        }
        
        // 检查第一个元素，判断数据集类型
        JSONObject firstItem = null;
        try {
            firstItem = jsonArray.getJSONObject(0);
            LogManager.logD(TAG, "成功获取JSON数组第一个元素，开始识别格式");
            
            // 输出第一个元素的所有字段，帮助调试
            Iterator<String> keys = firstItem.keys();
            StringBuilder fieldsInfo = new StringBuilder("第一个元素包含的字段: ");
            while (keys.hasNext()) {
                String key = keys.next();
                fieldsInfo.append(key).append(", ");
            }
            LogManager.logD(TAG, fieldsInfo.toString());
            
        } catch (JSONException e) {
            // 如果不是对象，可能是简单数组，直接返回
            LogManager.logD(TAG, "JSON 数组元素不是对象，无法识别数据集类型: " + e.getMessage());
            return;
        }
        
        // 根据字段判断数据集类型
        boolean isAlpaca = isAlpacaFormat(firstItem);
        boolean isCoT = isCoTFormat(firstItem);
        boolean isDPO = isDPOFormat(firstItem);
        boolean isConversation = isConversationFormat(firstItem);
        
        LogManager.logD(TAG, "数据集格式识别结果: isAlpaca=" + isAlpaca + ", isCoT=" + isCoT + 
              ", isDPO=" + isDPO + ", isConversation=" + isConversation);
        
        // 检查是否为特定数据集，需要特殊处理
        boolean isSpecialDataset = isSpecialDataset(jsonArray);
        
        if (isAlpaca) {
            LogManager.logD(TAG, "识别为Alpaca格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processAlpacaDataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isCoT) {
            LogManager.logD(TAG, "识别为CoT格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processCoTDataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isDPO) {
            LogManager.logD(TAG, "识别为DPO格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processDPODataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isConversation) {
            LogManager.logD(TAG, "识别为对话格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processConversationDataset(context, jsonArray, chunks, isSpecialDataset);
        } else {
            // 未识别的格式，尝试通用处理
            LogManager.logD(TAG, "未识别的数据集格式，尝试通用处理");
            processGenericJsonArray(context, jsonArray, chunks);
        }
    }
    
    /**
     * 处理 JSON 数组
     * @param jsonArray JSON 数组
     * @param chunks 存储结果的文本块列表
     * @param ignoreMinSize 是否忽略最小块大小限制，严格按照项的个数处理
     */
    private static void processJsonArray(Context context, JSONArray jsonArray, List<String> chunks, boolean ignoreMinSize) throws JSONException {
        // 检查是否为空数组
        if (jsonArray.length() == 0) {
            LogManager.logD(TAG, "JSON数组为空，无法处理");
            return;
        }
        
        // 检查第一个元素，判断数据集类型
        JSONObject firstItem = null;
        try {
            firstItem = jsonArray.getJSONObject(0);
            LogManager.logD(TAG, "成功获取JSON数组第一个元素，开始识别格式");
            
            // 输出第一个元素的所有字段，帮助调试
            Iterator<String> keys = firstItem.keys();
            StringBuilder fieldsInfo = new StringBuilder("第一个元素包含的字段: ");
            while (keys.hasNext()) {
                String key = keys.next();
                fieldsInfo.append(key).append(", ");
            }
            LogManager.logD(TAG, fieldsInfo.toString());
            
        } catch (JSONException e) {
            // 如果不是对象，可能是简单数组，直接返回
            LogManager.logD(TAG, "JSON 数组元素不是对象，无法识别数据集类型: " + e.getMessage());
            return;
        }
        
        // 根据字段判断数据集类型
        boolean isAlpaca = isAlpacaFormat(firstItem);
        boolean isCoT = isCoTFormat(firstItem);
        boolean isDPO = isDPOFormat(firstItem);
        boolean isConversation = isConversationFormat(firstItem);
        
        LogManager.logD(TAG, "数据集格式识别结果: isAlpaca=" + isAlpaca + ", isCoT=" + isCoT + 
              ", isDPO=" + isDPO + ", isConversation=" + isConversation);
        
        // 检查是否为特定数据集，需要特殊处理
        boolean isSpecialDataset = isSpecialDataset(jsonArray);
        
        if (isAlpaca) {
            LogManager.logD(TAG, "识别为Alpaca格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processAlpacaDataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isCoT) {
            LogManager.logD(TAG, "识别为CoT格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processCoTDataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isDPO) {
            LogManager.logD(TAG, "识别为DPO格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processDPODataset(context, jsonArray, chunks, isSpecialDataset);
        } else if (isConversation) {
            LogManager.logD(TAG, "识别为对话格式数据集" + (isSpecialDataset ? " (特定数据集，忽略大小限制)" : ""));
            processConversationDataset(context, jsonArray, chunks, isSpecialDataset);
        } else {
            // 未识别的格式，尝试通用处理
            LogManager.logD(TAG, "未识别的数据集格式，尝试通用处理");
            processGenericJsonArray(context, jsonArray, chunks);
        }
    }
    
    /**
     * 判断是否为特定需要特殊处理的数据集
     * 特定数据集将忽略最小块大小限制，严格按照项的个数处理
     * @param jsonArray JSON 数组
     * @return 是否为特定数据集
     */
    private static boolean isSpecialDataset(JSONArray jsonArray) {
        try {
            // 检查数据集大小，如果项目数量在特定范围内，可能是特定数据集
            int itemCount = jsonArray.length();
            
            // 检查第一个元素是否包含特定字段组合
            if (itemCount > 0 && itemCount < 1000) {
                JSONObject firstItem = jsonArray.getJSONObject(0);
                
                // 检查是否为特定的Alpaca格式数据集
                if (firstItem.has("instruction") && 
                    (firstItem.has("output") || firstItem.has("response")) &&
                    firstItem.optString("instruction", "").contains("STAR")) {
                    LogManager.logD(TAG, "检测到STAR特定数据集，将忽略最小块大小限制");
                    return true;
                }
                
                // 检查文件名特征（通过日志信息推断）
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stackTrace) {
                    String methodName = element.getMethodName();
                    if (methodName.contains("extractTextFromFiles") || methodName.contains("processFiles")) {
                        String fileName = Log.getStackTraceString(new Throwable());
                        if (fileName.contains("datasets-sb") || fileName.contains("alpaca") || 
                            fileName.contains("STAR") || fileName.contains("star")) {
                            LogManager.logD(TAG, "通过堆栈信息检测到特定数据集，将忽略最小块大小限制");
                            return true;
                        }
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            LogManager.logE(TAG, "检查特定数据集时出错: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 处理 JSON 对象
     * @param jsonObject JSON 对象
     * @param chunks 存储结果的文本块列表
     */
    private static void processJsonObject(Context context, JSONObject jsonObject, List<String> chunks) throws JSONException {
        // 检查是否包含数据数组
        if (jsonObject.has("data") && jsonObject.get("data") instanceof JSONArray) {
            processJsonArray(context, jsonObject.getJSONArray("data"), chunks);
            return;
        }
        
        if (jsonObject.has("conversations") && jsonObject.get("conversations") instanceof JSONArray) {
            processConversationDataset(context, jsonObject.getJSONArray("conversations"), chunks);
            return;
        }
        
        if (jsonObject.has("examples") && jsonObject.get("examples") instanceof JSONArray) {
            processJsonArray(context, jsonObject.getJSONArray("examples"), chunks);
            return;
        }
        
        // 未识别的格式，尝试通用处理
        LogManager.logD(TAG, "未识别的数据集格式，尝试通用处理");
        processGenericJsonObject(context, jsonObject, chunks);
    }
    
    /**
     * 判断是否为 Alpaca 格式
     * 增强识别能力，处理可能的变体格式
     */
    private static boolean isAlpacaFormat(JSONObject item) {
        // 检查必要字段
        boolean hasInstruction = item.has("instruction");
        boolean hasOutput = item.has("output");
        boolean hasResponse = item.has("response");
        boolean hasCompletion = item.has("completion");
        
        // 检查可选字段
        boolean hasInput = item.has("input");
        boolean hasSystem = item.has("system");
        
        // Alpaca格式必须有instruction字段，以及output/response/completion中的至少一个
        boolean isAlpaca = hasInstruction && (hasOutput || hasResponse || hasCompletion);
        
        // 详细日志
        LogManager.logD(TAG, "格式检查 - Alpaca: " + 
              "hasInstruction=" + hasInstruction + 
              ", hasOutput=" + hasOutput + 
              ", hasResponse=" + hasResponse + 
              ", hasCompletion=" + hasCompletion +
              ", hasInput=" + hasInput +
              ", hasSystem=" + hasSystem +
              " => isAlpaca=" + isAlpaca);
        
        // 如果是Alpaca格式，记录样本内容长度
        if (isAlpaca) {
            try {
                String instruction = item.optString("instruction", "");
                String output = item.optString("output", "");
                String response = item.optString("response", "");
                String completion = item.optString("completion", "");
                String input = item.optString("input", "");
                
                // 确定输出内容
                String outputContent = "";
                if (hasOutput) outputContent = output;
                else if (hasResponse) outputContent = response;
                else if (hasCompletion) outputContent = completion;
                
                LogManager.logD(TAG, "Alpaca格式样本: instruction长度=" + instruction.length() + 
                      ", 输出内容长度=" + outputContent.length() +
                      (hasInput ? ", input长度=" + input.length() : ""));
            } catch (Exception e) {
                LogManager.logE(TAG, "读取Alpaca字段时出错: " + e.getMessage());
            }
        }
        
        return isAlpaca;
    }
    
    /**
     * 判断是否为 CoT (Chain of Thought) 格式
     */
    private static boolean isCoTFormat(JSONObject item) {
        return item.has("question") && (item.has("rationale") || item.has("reasoning") || item.has("chain_of_thought")) && item.has("answer");
    }
    
    /**
     * 判断是否为 DPO (Direct Preference Optimization) 格式
     */
    private static boolean isDPOFormat(JSONObject item) {
        return (item.has("prompt") || item.has("instruction")) && item.has("chosen") && item.has("rejected");
    }
    
    /**
     * 判断是否为对话格式
     */
    private static boolean isConversationFormat(JSONObject item) {
        return item.has("conversations") || item.has("messages") || item.has("dialog") || 
               (item.has("input") && item.has("output")) || 
               (item.has("human") && item.has("assistant"));
    }
    
    /**
     * 处理 Alpaca 格式数据集
     * @param jsonArray JSON 数组
     * @param chunks 存储结果的文本块列表
     * @param ignoreMinSize 是否忽略最小块大小限制，严格按照项的个数处理
     */
    private static void processAlpacaDataset(Context context, JSONArray jsonArray, List<String> chunks, boolean ignoreMinSize) throws JSONException {
        LogManager.logD(TAG, "开始处理Alpaca格式数据集...");
        int originalChunksSize = chunks.size();
        int validItemCount = 0;
        int skippedItemCount = 0;
        
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            StringBuilder chunk = new StringBuilder();
            
            // 添加指令
            if (item.has("instruction")) {
                String instruction = item.getString("instruction");
                chunk.append("指令: ").append(instruction).append("\n\n");
                LogManager.logD(TAG, "Alpaca项[" + i + "] - 指令长度: " + instruction.length());
            }
            
            // 添加输入（如果有）
            if (item.has("input") && !item.getString("input").isEmpty()) {
                String input = item.getString("input");
                chunk.append("输入: ").append(input).append("\n\n");
                LogManager.logD(TAG, "Alpaca项[" + i + "] - 输入长度: " + input.length());
            }
            
            // 添加输出/响应/completion
            String output = "";
            if (item.has("output")) {
                output = item.getString("output");
                LogManager.logD(TAG, "Alpaca项[" + i + "] - 输出字段长度: " + output.length());
            } else if (item.has("response")) {
                output = item.getString("response");
                LogManager.logD(TAG, "Alpaca项[" + i + "] - 响应字段长度: " + output.length());
            } else if (item.has("completion")) {
                output = item.getString("completion");
                LogManager.logD(TAG, "Alpaca项[" + i + "] - completion字段长度: " + output.length());
            }
            
            if (!output.isEmpty()) {
                chunk.append("输出: ").append(output);
            }
            
            // 添加系统提示（如果有）
            if (item.has("system") && !item.getString("system").isEmpty()) {
                String system = item.getString("system");
                chunk.append("\n\n系统: ").append(system);
                LogManager.logD(TAG, "Alpaca项[" + i + "] - 系统提示长度: " + system.length());
            }
            
            String chunkText = chunk.toString();
            
            // 检查是否忽略最小块大小限制
            int minChunkSize = ConfigManager.getMinChunkSize(context);
            if (ignoreMinSize || chunkText.length() >= minChunkSize) {
                chunks.add(chunkText);
                validItemCount++;
                // 记录块大小，用于调试
                LogManager.logD(TAG, "添加Alpaca文本块[" + i + "]: 大小=" + chunkText.length() + " 字符" + 
                      (ignoreMinSize && chunkText.length() < minChunkSize ? " (忽略大小限制)" : ""));
            } else {
                skippedItemCount++;
                LogManager.logD(TAG, "跳过过小的Alpaca文本块[" + i + "]: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
            }
            
            // 每处理100个项目记录一次日志
            if (i % 100 == 0 || i == jsonArray.length() - 1) {
                LogManager.logD(TAG, "Alpaca数据集处理进度: " + (i + 1) + "/" + jsonArray.length());
            }
        }
        
        int addedChunks = chunks.size() - originalChunksSize;
        LogManager.logD(TAG, "Alpaca数据集处理完成，总项目数: " + jsonArray.length() + 
              ", 有效项目: " + validItemCount + 
              ", 跳过项目: " + skippedItemCount + 
              ", 添加块数: " + addedChunks +
              (ignoreMinSize ? " (忽略大小限制)" : ""));
    }
    
    /**
     * 处理 Alpaca 格式数据集（默认使用最小块大小限制）
     */
    private static void processAlpacaDataset(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        // 默认不忽略最小块大小限制
        processAlpacaDataset(context, jsonArray, chunks, false);
    }
    
    /**
     * 处理 CoT (Chain of Thought) 格式数据集
     */
    private static void processCoTDataset(Context context, JSONArray jsonArray, List<String> chunks, boolean ignoreMinSize) throws JSONException {
        LogManager.logD(TAG, "开始处理CoT格式数据集...");
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            StringBuilder chunk = new StringBuilder();
            
            // 添加问题
            if (item.has("question")) {
                chunk.append("问题: ").append(item.getString("question")).append("\n\n");
            }
            
            // 添加推理过程
            String reasoning = item.has("rationale") ? item.getString("rationale") : 
                              (item.has("reasoning") ? item.getString("reasoning") : 
                              (item.has("chain_of_thought") ? item.getString("chain_of_thought") : ""));
            if (!reasoning.isEmpty()) {
                chunk.append("推理过程: ").append(reasoning).append("\n\n");
            }
            
            // 添加答案
            if (item.has("answer")) {
                chunk.append("答案: ").append(item.getString("answer"));
            }
            
            String chunkText = chunk.toString();
            // 检查文本块大小，只添加足够大的块
            int minChunkSize = ConfigManager.getMinChunkSize(context);
            if (ignoreMinSize || chunkText.length() >= minChunkSize) {
                chunks.add(chunkText);
                // 记录块大小，用于调试
                LogManager.logD(TAG, "添加CoT文本块: 大小=" + chunkText.length() + " 字符" + 
                      (ignoreMinSize && chunkText.length() < minChunkSize ? " (忽略大小限制)" : ""));
            } else {
                LogManager.logD(TAG, "跳过过小的CoT文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
            }
            
            // 每处理100个项目记录一次日志
            if (i % 100 == 0 || i == jsonArray.length() - 1) {
                LogManager.logD(TAG, "CoT数据集处理进度: " + (i + 1) + "/" + jsonArray.length());
            }
        }
        LogManager.logD(TAG, "CoT数据集处理完成，共提取 " + chunks.size() + " 个训练样本");
    }
    
    /**
     * 处理 CoT (Chain of Thought) 格式数据集（默认使用最小块大小限制）
     */
    private static void processCoTDataset(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        // 默认不忽略最小块大小限制
        processCoTDataset(context, jsonArray, chunks, false);
    }
    
    /**
     * 处理 DPO (Direct Preference Optimization) 格式数据集
     */
    private static void processDPODataset(Context context, JSONArray jsonArray, List<String> chunks, boolean ignoreMinSize) throws JSONException {
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            
            // 获取提示/指令
            String prompt = item.has("prompt") ? item.getString("prompt") : 
                           (item.has("instruction") ? item.getString("instruction") : "");
            
            // 获取选中的回答
            String chosen = item.has("chosen") ? item.getString("chosen") : "";
            
            // 创建块 - 只使用 prompt 和 chosen，不使用 rejected
            if (!prompt.isEmpty() && !chosen.isEmpty()) {
                StringBuilder chunk = new StringBuilder();
                chunk.append("提示: ").append(prompt).append("\n\n");
                chunk.append("首选回答: ").append(chosen);
                
                String chunkText = chunk.toString();
                // 检查文本块大小，只添加足够大的块
                int minChunkSize = ConfigManager.getMinChunkSize(context);
                if (ignoreMinSize || chunkText.length() >= minChunkSize) {
                    chunks.add(chunkText);
                    // 记录块大小，用于调试
                    LogManager.logD(TAG, "添加DPO文本块: 大小=" + chunkText.length() + " 字符" + 
                          (ignoreMinSize && chunkText.length() < minChunkSize ? " (忽略大小限制)" : ""));
                } else {
                    LogManager.logD(TAG, "跳过过小的DPO文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
                }
                
                // 添加调试日志
                LogManager.logD(TAG, "DPO数据集项处理: 提取prompt和chosen，忽略rejected字段");
            }
            
            // 注意：我们不使用拒绝的回答，因为它可能会降低训练质量
        }
    }
    
    /**
     * 处理 DPO (Direct Preference Optimization) 格式数据集（默认使用最小块大小限制）
     */
    private static void processDPODataset(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        // 默认不忽略最小块大小限制
        processDPODataset(context, jsonArray, chunks, false);
    }
    
    /**
     * 处理对话格式数据集
     */
    private static void processConversationDataset(Context context, JSONArray jsonArray, List<String> chunks, boolean ignoreMinSize) throws JSONException {
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            
            // 处理不同类型的对话格式
            if (item.has("conversations") && item.get("conversations") instanceof JSONArray) {
                // 处理嵌套的对话数组
                processConversation(context, item.getJSONArray("conversations"), chunks);
            } else if (item.has("messages") && item.get("messages") instanceof JSONArray) {
                // 处理消息数组
                processMessages(context, item.getJSONArray("messages"), chunks);
            } else if (item.has("human") && item.has("assistant")) {
                // 处理简单的人类-助手对话
                StringBuilder chunk = new StringBuilder();
                chunk.append("人类: ").append(item.getString("human")).append("\n\n");
                chunk.append("助手: ").append(item.getString("assistant"));
                
                String chunkText = chunk.toString();
                // 检查文本块大小，只添加足够大的块
                int minChunkSize = ConfigManager.getMinChunkSize(context);
                if (ignoreMinSize || chunkText.length() >= minChunkSize) {
                    chunks.add(chunkText);
                    // 记录块大小，用于调试
                    LogManager.logD(TAG, "添加对话文本块: 大小=" + chunkText.length() + " 字符" + 
                          (ignoreMinSize && chunkText.length() < minChunkSize ? " (忽略大小限制)" : ""));
                } else {
                    LogManager.logD(TAG, "跳过过小的对话文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
                }
            } else if (item.has("input") && item.has("output")) {
                // 处理输入-输出对
                StringBuilder chunk = new StringBuilder();
                chunk.append("输入: ").append(item.getString("input")).append("\n\n");
                chunk.append("输出: ").append(item.getString("output"));
                
                String chunkText = chunk.toString();
                // 检查文本块大小，只添加足够大的块
                int minChunkSize = ConfigManager.getMinChunkSize(context);
                if (ignoreMinSize || chunkText.length() >= minChunkSize) {
                    chunks.add(chunkText);
                    // 记录块大小，用于调试
                    LogManager.logD(TAG, "添加对话文本块: 大小=" + chunkText.length() + " 字符" + 
                          (ignoreMinSize && chunkText.length() < minChunkSize ? " (忽略大小限制)" : ""));
                } else {
                    LogManager.logD(TAG, "跳过过小的对话文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
                }
            } else {
                // 未识别的对话格式，尝试通用处理
                processGenericJsonObject(context, item, chunks);
            }
        }
    }
    
    /**
     * 处理对话格式数据集（默认使用最小块大小限制）
     */
    private static void processConversationDataset(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        // 默认不忽略最小块大小限制
        processConversationDataset(context, jsonArray, chunks, false);
    }
    
    /**
     * 处理对话数组
     */
    private static void processConversation(Context context, JSONArray conversations, List<String> chunks) throws JSONException {
        StringBuilder chunk = new StringBuilder();
        
        for (int i = 0; i < conversations.length(); i++) {
            JSONObject message = conversations.getJSONObject(i);
            
            if (message.has("role") && message.has("content")) {
                String role = message.getString("role");
                String content = message.getString("content");
                
                if (role.equalsIgnoreCase(AppConstants.ChatRole.SYSTEM)) {
                    chunk.append("系统: ").append(content).append("\n\n");
                } else if (role.equalsIgnoreCase(AppConstants.ChatRole.USER) || role.equalsIgnoreCase(AppConstants.ChatRole.HUMAN)) {
                    chunk.append("用户: ").append(content).append("\n\n");
                } else if (role.equalsIgnoreCase(AppConstants.ChatRole.ASSISTANT) || role.equalsIgnoreCase(AppConstants.ChatRole.BOT)) {
                    chunk.append("助手: ").append(content).append("\n\n");
                } else {
                    chunk.append(role).append(": ").append(content).append("\n\n");
                }
            } else if (message.has("from") && message.has("value")) {
                String from = message.getString("from");
                String value = message.getString("value");
                
                chunk.append(from).append(": ").append(value).append("\n\n");
            }
        }
        
        String chunkText = chunk.toString();
        // 检查文本块大小，只添加足够大的块
        int minChunkSize = ConfigManager.getMinChunkSize(context);
        if (chunkText.length() >= minChunkSize) {
            chunks.add(chunkText.trim());
            // 记录块大小，用于调试
            LogManager.logD(TAG, "添加对话文本块: 大小=" + chunkText.length() + " 字符");
        } else {
            LogManager.logD(TAG, "跳过过小的对话文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
        }
    }
    
    /**
     * 处理消息数组
     */
    private static void processMessages(Context context, JSONArray messages, List<String> chunks) throws JSONException {
        StringBuilder chunk = new StringBuilder();
        
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            
            if (message.has("role") && message.has("content")) {
                String role = message.getString("role");
                String content = message.getString("content");
                
                if (role.equalsIgnoreCase("system")) {
                    chunk.append("系统: ").append(content).append("\n\n");
                } else if (role.equalsIgnoreCase("user") || role.equalsIgnoreCase("human")) {
                    chunk.append("用户: ").append(content).append("\n\n");
                } else if (role.equalsIgnoreCase("assistant") || role.equalsIgnoreCase("bot")) {
                    chunk.append("助手: ").append(content).append("\n\n");
                } else {
                    chunk.append(role).append(": ").append(content).append("\n\n");
                }
            }
        }
        
        String chunkText = chunk.toString();
        // 检查文本块大小，只添加足够大的块
        int minChunkSize = ConfigManager.getMinChunkSize(context);
        if (chunkText.length() >= minChunkSize) {
            chunks.add(chunkText.trim());
            // 记录块大小，用于调试
            LogManager.logD(TAG, "添加对话文本块: 大小=" + chunkText.length() + " 字符");
        } else {
            LogManager.logD(TAG, "跳过过小的对话文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
        }
    }
    
    /**
     * 通用 JSON 数组处理
     */
    private static void processGenericJsonArray(Context context, JSONArray jsonArray, List<String> chunks) throws JSONException {
        for (int i = 0; i < jsonArray.length(); i++) {
            try {
                Object item = jsonArray.get(i);
                if (item instanceof JSONObject) {
                    processGenericJsonObject(context, (JSONObject) item, chunks);
                } else if (item instanceof JSONArray) {
                    processGenericJsonArray(context, (JSONArray) item, chunks);
                } else if (item instanceof String) {
                    // 如果是字符串，直接添加
                    String text = jsonArray.getString(i);
                    if (!text.trim().isEmpty()) {
                        String chunkText = text;
                        // 检查文本块大小，只添加足够大的块
                        int minChunkSize = ConfigManager.getMinChunkSize(context);
                        if (chunkText.length() >= minChunkSize) {
                            chunks.add(chunkText);
                            // 记录块大小，用于调试
                            LogManager.logD(TAG, "添加通用文本块: 大小=" + chunkText.length() + " 字符");
                        } else {
                            LogManager.logD(TAG, "跳过过小的通用文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
                        }
                    }
                }
            } catch (JSONException e) {
                LogManager.logE(TAG, "处理 JSON 数组项时出错: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 通用 JSON 对象处理
     */
    private static void processGenericJsonObject(Context context, JSONObject jsonObject, List<String> chunks) throws JSONException {
        StringBuilder chunk = new StringBuilder();
        
        // 遍历所有键
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = jsonObject.get(key);
                
                if (value instanceof String) {
                    // 字符串值，添加键值对
                    String strValue = jsonObject.getString(key);
                    if (!strValue.trim().isEmpty()) {
                        chunk.append(key).append(": ").append(strValue).append("\n\n");
                    }
                } else if (value instanceof JSONObject) {
                    // 嵌套对象，递归处理
                    List<String> nestedChunks = new ArrayList<>();
                    processGenericJsonObject(context, (JSONObject) value, nestedChunks);
                    
                    if (!nestedChunks.isEmpty()) {
                        chunk.append(key).append(":\n");
                        for (String nestedChunk : nestedChunks) {
                            chunk.append(nestedChunk).append("\n");
                        }
                        chunk.append("\n");
                    }
                } else if (value instanceof JSONArray) {
                    // 数组值，特殊处理
                    JSONArray array = jsonObject.getJSONArray(key);
                    if (array.length() > 0) {
                        chunk.append(key).append(":\n");
                        for (int i = 0; i < array.length(); i++) {
                            try {
                                if (array.get(i) instanceof String) {
                                    chunk.append("- ").append(array.getString(i)).append("\n");
                                }
                            } catch (JSONException e) {
                                // 忽略错误，继续处理
                            }
                        }
                        chunk.append("\n");
                    }
                }
            } catch (JSONException e) {
                // 忽略错误，继续处理其他键
                LogManager.logE(TAG, "处理键 '" + key + "' 时出错: " + e.getMessage(), e);
            }
        }
        
        String chunkText = chunk.toString();
        // 检查文本块大小，只添加足够大的块
        int minChunkSize = ConfigManager.getMinChunkSize(context);
        if (chunkText.length() >= minChunkSize) {
            chunks.add(chunkText.trim());
            // 记录块大小，用于调试
            LogManager.logD(TAG, "添加通用文本块: 大小=" + chunkText.length() + " 字符");
        } else {
            LogManager.logD(TAG, "跳过过小的通用文本块: 大小=" + chunkText.length() + " 字符，小于最小限制 " + minChunkSize);
        }
    }
}
