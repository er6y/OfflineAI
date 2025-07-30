package com.example.starlocalrag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unicode工具类，用于处理Unicode转义序列
 */
public class UnicodeUtils {
    private static final String TAG = "UnicodeUtils";
    
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
    
    /**
     * 将字符串中的中文字符编码为Unicode转义序列
     * 主要用于调试和测试
     * 
     * @param input 输入字符串
     * @return 编码后的字符串
     */
    public static String encodeChineseToUnicode(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            // 检查是否为中文字符（基本汉字范围）
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                result.append(String.format("\\u%04x", (int) ch));
            } else {
                result.append(ch);
            }
        }
        
        return result.toString();
    }
    
    /**
     * 清理和标准化文本，移除或转换特殊字符
     * 
     * @param input 输入文本
     * @return 清理后的文本
     */
    public static String cleanText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // 首先解码Unicode转义序列
        String decoded = decodeUnicodeEscapes(input);
        
        // 移除或替换其他控制字符
        decoded = decoded.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        
        return decoded;
    }
}