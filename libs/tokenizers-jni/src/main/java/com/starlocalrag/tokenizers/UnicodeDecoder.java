package com.starlocalrag.tokenizers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unicode解码工具类，专门用于修复tokenizer输出中的Unicode转义序列问题
 */
public class UnicodeDecoder {
    // Unicode转义序列的正则表达式
    private static final Pattern UNICODE_PATTERN = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    
    /**
     * 将Unicode转义序列转换为实际的字符
     * 例如：\u4e2d\u6587 -> 中文
     * 
     * @param input 包含Unicode转义序列的字符串
     * @return 解码后的字符串
     */
    public static String decodeUnicodeEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        try {
            Matcher matcher = UNICODE_PATTERN.matcher(input);
            StringBuffer result = new StringBuffer();
            
            while (matcher.find()) {
                String hexCode = matcher.group(1);
                try {
                    int codePoint = Integer.parseInt(hexCode, 16);
                    char unicodeChar = (char) codePoint;
                    matcher.appendReplacement(result, String.valueOf(unicodeChar));
                } catch (NumberFormatException e) {
                    // 如果解析失败，保持原样
                    matcher.appendReplacement(result, matcher.group(0));
                }
            }
            matcher.appendTail(result);
            
            return result.toString();
        } catch (Exception e) {
            // 如果出现任何异常，返回原字符串
            return input;
        }
    }
    
    /**
     * 检查字符串是否包含Unicode转义序列
     * 
     * @param input 要检查的字符串
     * @return 如果包含Unicode转义序列返回true，否则返回false
     */
    public static boolean containsUnicodeEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return UNICODE_PATTERN.matcher(input).find();
    }
}