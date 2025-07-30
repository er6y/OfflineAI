package com.starlocalrag.tokenizers;

/**
 * 分词器JNI接口
 * 提供与Rust实现的分词器的本地方法交互
 */
public class TokenizerJNI {
    static {
        try {
            NativeLibraryLoader.load();
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 创建分词器
     * @param modelType 模型类型，如"bpe"或"wordpiece"
     * @return 分词器指针
     */
    public static native long createTokenizer(String modelType);
    
    /**
     * 从文件加载分词器
     * @param path 分词器文件路径
     * @return 分词器指针
     */
    public static long loadTokenizerFromFile(String path) {
        try {
            long result = loadTokenizerFromFileNative(path);
            return result;
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    
    private static native long loadTokenizerFromFileNative(String path);
    
    /**
     * 分词
     * @param tokenizerPtr 分词器指针
     * @param text 要分词的文本
     * @return JSON格式的分词结果
     */
    public static native String tokenize(long tokenizerPtr, String text);
    
    /**
     * 获取分词器配置
     * @param tokenizerPtr 分词器指针
     * @return JSON格式的分词器配置
     */
    public static native String getTokenizerConfig(long tokenizerPtr);
    
    /**
     * 解码token ID为文本
     * @param tokenizerPtr 分词器指针
     * @param ids JSON格式的token ID数组
     * @return 解码后的文本
     */
    public static native String decode(long tokenizerPtr, String ids);
    
    /**
     * 解码token ID为文本，可选择是否跳过特殊token
     * @param tokenizerPtr 分词器指针
     * @param ids JSON格式的token ID数组
     * @param skipSpecialTokens 是否跳过特殊token
     * @return 解码后的文本
     */
    public static native String decode(long tokenizerPtr, String ids, boolean skipSpecialTokens);
    
    /**
     * 解码token ID为文本并修复Unicode转义序列
     * @param tokenizerPtr 分词器指针
     * @param ids JSON格式的token ID数组
     * @param skipSpecialTokens 是否跳过特殊token
     * @return 解码并修复Unicode后的文本
     */
    public static String decodeWithUnicodeFix(long tokenizerPtr, String ids, boolean skipSpecialTokens) {
        String result = decode(tokenizerPtr, ids, skipSpecialTokens);
        return UnicodeDecoder.decodeUnicodeEscapes(result);
    }
    
    /**
     * 解码token ID为文本并修复Unicode转义序列（默认不跳过特殊token）
     * @param tokenizerPtr 分词器指针
     * @param ids JSON格式的token ID数组
     * @return 解码并修复Unicode后的文本
     */
    public static String decodeWithUnicodeFix(long tokenizerPtr, String ids) {
        return decodeWithUnicodeFix(tokenizerPtr, ids, false);
    }
    

    
    /**
     * 释放分词器
     * @param tokenizerPtr 分词器指针
     */
    public static native void freeTokenizer(long tokenizerPtr);
}
