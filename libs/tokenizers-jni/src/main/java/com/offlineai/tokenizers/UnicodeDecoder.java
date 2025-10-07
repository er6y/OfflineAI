package com.offlineai.tokenizers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnicodeDecoder {
    // 构造正则表达式，避免在源码中出现反斜杠加 u 的序列
    private static final Pattern UNICODE_PATTERN;
    static {
        String bs = "\\";  // 单个反斜杠字符
        String u = "u";      // 字母 u
        UNICODE_PATTERN = Pattern.compile(bs + bs + u + "([0-9a-fA-F]{4})");
    }

    public static String decodeUnicodeEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        try {
            Matcher matcher = UNICODE_PATTERN.matcher(input);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String hex = matcher.group(1);
                try {
                    int codePoint = Integer.parseInt(hex, 16);
                    char unicodeChar = (char) codePoint;
                    matcher.appendReplacement(result, String.valueOf(unicodeChar));
                } catch (NumberFormatException e) {
                    matcher.appendReplacement(result, matcher.group(0));
                }
            }
            matcher.appendTail(result);
            return result.toString();
        } catch (Exception e) {
            return input;
        }
    }

    public static boolean containsUnicodeEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return UNICODE_PATTERN.matcher(input).find();
    }
}