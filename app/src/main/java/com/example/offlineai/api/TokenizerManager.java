package com.example.offlineai.api;

import android.content.Context;

import com.example.offlineai.LogManager;
import com.example.offlineai.UnicodeUtils;
import com.example.offlineai.GlobalStopManager;
import com.offlineai.tokenizers.HuggingfaceTokenizer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 分词器管理类，提供全局统一的分词器实例
 * 确保RAG问答、知识库构建和知识笔记创建过程使用相同的分词策略
 * 内部使用 HuggingfaceTokenizer 开源分词器实现
 */
public class TokenizerManager implements TokenizerInterface {
    private static final String TAG = "OfflineAI_TokenizerMgr";
    
    // 单例实例
    private static TokenizerManager instance;
    
    // 是否已初始化
    private boolean initialized = false;
    
    // 应用上下文
    private Context context;
    
    // 分词器实例
    private HuggingfaceTokenizer tokenizer = null;
    
    // 当前加载的模型路径
    private String currentModelPath = null;
    
    // 模型类型
    private String modelType = "";
    
    // 是否使用一致性分词策略
    private boolean useConsistentTokenization = false;
    
    // 是否启用调试模式
    private boolean debugMode = false;
    
    /**
     * 私有构造函数，防止外部实例化
     */
    private TokenizerManager(Context context) {
        this.context = context.getApplicationContext();
        
        // 先不创建分词器实例，等待初始化时创建
        //LogManager.logI(TAG, "分词器管理器已创建，等待初始化");
    }
    
    /**
     * 获取单例实例
     * @param context 应用上下文
     * @return TokenizerManager实例
     */
    public static synchronized TokenizerManager getInstance(Context context) {
        if (instance == null) {
            instance = new TokenizerManager(context);
            LogManager.logD(TAG, "创建分词器管理器实例");
        }
        return instance;
    }
    
    /**
     * 检查分词器是否已初始化
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 初始化分词器
     * @param modelPath 模型目录或模型文件路径
     * @return 是否初始化成功
     */
    public boolean initialize(File modelPath) {
       // LogManager.logI(TAG, "📁 开始处理File对象初始化...");
        LogManager.logI(TAG, "传入的File路径: " + (modelPath != null ? modelPath.getAbsolutePath() : "null"));
        
        if (modelPath == null) {
            LogManager.logE(TAG, "❌ File对象为null");
            return false;
        }
        
        //LogManager.logI(TAG, "File对象状态检查:");
        //LogManager.logI(TAG, "  - 是否存在: " + modelPath.exists());
        //LogManager.logI(TAG, "  - 是否为文件: " + modelPath.isFile());
        //LogManager.logI(TAG, "  - 是否为目录: " + modelPath.isDirectory());
        //LogManager.logI(TAG, "  - 是否可读: " + modelPath.canRead());
        
        try {
            // 判断传入的是文件还是目录
            File modelDir = modelPath;
            if (modelPath.isFile()) {
                // 如果是文件，使用其父目录
                LogManager.logI(TAG, "🔄 检测到传入的是模型文件而非目录，将使用其父目录: " + modelPath.getAbsolutePath());
                modelDir = modelPath.getParentFile();
                LogManager.logI(TAG, "父目录路径: " + (modelDir != null ? modelDir.getAbsolutePath() : "null"));
                
                if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
                    LogManager.logE(TAG, "❌ 无法获取模型文件的父目录，或父目录不存在: " + modelPath.getAbsolutePath());
                    return false;
                }
                LogManager.logI(TAG, "✅ 父目录有效，继续使用: " + modelDir.getAbsolutePath());
            } else {
                LogManager.logI(TAG, "✅ 传入的是目录路径，直接使用: " + modelDir.getAbsolutePath());
            }
            
            // 检查是否与当前加载的模型路径相同
            String newModelPath = modelDir.getAbsolutePath();
            if (initialized && tokenizer != null && newModelPath.equals(currentModelPath)) {
                LogManager.logD(TAG, "分词器已经初始化为相同的模型路径，无需重新加载: " + newModelPath);
                return true;
            }
            
            // 如果是不同的模型路径，先关闭现有分词器
            if (tokenizer != null) {
                LogManager.logD(TAG, "关闭现有分词器实例，准备加载新分词器: " + newModelPath);
                try {
                    tokenizer.close();
                    tokenizer = null;
                    initialized = false;
                } catch (Exception e) {
                    LogManager.logE(TAG, "关闭分词器失败: " + e.getMessage(), e);
                    // 即使关闭失败也继续初始化新分词器
                }
            }
            
            LogManager.logD(TAG, "开始初始化分词器，模型目录: " + modelDir.getAbsolutePath());
            boolean success = loadFromDirectory(modelDir);
            
            if (success) {
                // 更新当前模型路径
                currentModelPath = newModelPath;
                
                //LogManager.logD(TAG, "分词器初始化成功，特殊token数量: " + getSpecialTokensSize());
                
                // 默认启用一致性分词策略
                setUseConsistentTokenization(true);
                //LogManager.logD(TAG, "已启用一致性分词策略");
                
                initialized = true;
                return true;
            } else {
                LogManager.logE(TAG, "分词器初始化失败");
                return false;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "分词器初始化异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 初始化分词器（接受字符串路径）
     * @param modelPath 模型路径（可以是目录或文件路径）
     * @return 是否初始化成功
     */
    public boolean initialize(String modelPath) {
        //LogManager.logI(TAG, "=== 开始分词器初始化流程 ===");
        //LogManager.logI(TAG, "尝试从模型目录初始化tokenizer: " + modelPath);
        //LogManager.logI(TAG, "当前线程: " + Thread.currentThread().getName());
        //LogManager.logI(TAG, "系统时间: " + System.currentTimeMillis());
        
        if (modelPath == null || modelPath.trim().isEmpty()) {
            LogManager.logE(TAG, "❌ 模型路径为空，无法初始化tokenizer");
            return false;
        }
        
        // 检查当前分词器是否已经加载了相同的文件
        String newModelPath = modelPath.trim();
        //LogManager.logI(TAG, "检查是否需要重新加载分词器...");
        //LogManager.logI(TAG, "当前模型路径: " + (currentModelPath != null ? currentModelPath : "null"));
        //LogManager.logI(TAG, "新模型路径: " + newModelPath);
        
        if (currentModelPath != null && currentModelPath.equals(newModelPath)) {
            LogManager.logD(TAG, "✅ 分词器已经初始化为相同的模型路径，无需重新加载: " + newModelPath);
            boolean isInit = isInitialized();
            LogManager.logI(TAG, "当前分词器状态: " + (isInit ? "已初始化" : "未初始化"));
            return isInit;
        }
        
        // 关闭现有的分词器实例
        if (tokenizer != null) {
            LogManager.logD(TAG, "🔄 关闭现有分词器实例，准备加载新分词器: " + newModelPath);
            //LogManager.logI(TAG, "释放现有分词器资源...");
            tokenizer.close();
            tokenizer = null;
            //LogManager.logI(TAG, "现有分词器资源已释放");
        }
        
        try {
            LogManager.logI(TAG, "🔍 开始查找分词器文件...");
            File modelFile = new File(modelPath);
            //LogManager.logI(TAG, "模型文件对象创建完成: " + modelFile.getAbsolutePath());
            //LogManager.logI(TAG, "检查路径类型...");
            //LogManager.logI(TAG, "是否为目录: " + modelFile.isDirectory());
            //LogManager.logI(TAG, "是否为文件: " + modelFile.isFile());
            //LogManager.logI(TAG, "是否存在: " + modelFile.exists());
            
            return initialize(modelFile);
        } catch (Exception e) {
            LogManager.logE(TAG, "初始化分词器失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取分词器实例
     * @return 分词器实例，如果未初始化则返回null
     */
    public HuggingfaceTokenizer getTokenizer() {
        if (!initialized) {
            LogManager.logW(TAG, "分词器未初始化，无法获取实例");
            return null;
        }
        return tokenizer;
    }
    
    /**
     * 设置是否使用一致性分词策略
     * @param useConsistent 是否使用一致性分词
     */
    public void setUseConsistentTokenization(boolean useConsistent) {
        this.useConsistentTokenization = useConsistent;
        LogManager.logD(TAG, "设置一致性分词策略: " + useConsistent);
    }
    
    /**
     * 检查是否使用一致性分词策略
     * @return 是否使用一致性分词
     */
    public boolean isUseConsistentTokenization() {
        return useConsistentTokenization;
    }
    
    /**
     * 设置是否启用调试模式
     * @param debug 是否启用调试
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        LogManager.logD(TAG, "设置调试模式: " + debug);
    }
    
    /**
     * 从指定目录加载分词器
     * @param directory 包含tokenizer.json的目录
     * @return 是否成功加载
     */
    public boolean loadFromDirectory(File directory) {
        LogManager.logI(TAG, "📂 开始从目录加载分词器...");
        LogManager.logI(TAG, "目标目录: " + (directory != null ? directory.getAbsolutePath() : "null"));
        
        if (directory == null) {
            LogManager.logE(TAG, "❌ 目录对象为空");
            return false;
        }
        
        LogManager.logI(TAG, "目录状态检查:");
        LogManager.logI(TAG, "  - 是否存在: " + directory.exists());
        LogManager.logI(TAG, "  - 是否为目录: " + directory.isDirectory());
        LogManager.logI(TAG, "  - 是否可读: " + directory.canRead());
        
        if (!directory.exists() || !directory.isDirectory()) {
            LogManager.logE(TAG, "❌ 指定的目录不存在或不是目录: " + directory.getAbsolutePath());
            return false;
        }
        
        // 尝试查找tokenizer.json文件
        LogManager.logI(TAG, "🔍 开始查找tokenizer.json文件...");
        File tokenizerFile = new File(directory, "tokenizer.json");
        LogManager.logI(TAG, "tokenizer.json完整路径: " + tokenizerFile.getAbsolutePath());
        LogManager.logI(TAG, "tokenizer.json文件状态:");
        LogManager.logI(TAG, "  - 是否存在: " + tokenizerFile.exists());
        LogManager.logI(TAG, "  - 是否为文件: " + tokenizerFile.isFile());
        LogManager.logI(TAG, "  - 文件大小: " + (tokenizerFile.exists() ? tokenizerFile.length() + " bytes" : "文件不存在"));
        LogManager.logI(TAG, "  - 是否可读: " + (tokenizerFile.exists() ? tokenizerFile.canRead() : "文件不存在"));
        
        if (!tokenizerFile.exists() || !tokenizerFile.isFile()) {
            LogManager.logE(TAG, "❌ 在目录中找不到tokenizer.json文件: " + directory.getAbsolutePath());
            LogManager.logI(TAG, "📋 列出目录中的所有文件:");
            File[] files = directory.listFiles();
            if (files != null && files.length > 0) {
                for (File file : files) {
                    LogManager.logI(TAG, "  - " + file.getName() + " (" + (file.isDirectory() ? "目录" : file.length() + " bytes") + ")");
                }
            } else {
                LogManager.logI(TAG, "  目录为空或无法列出文件");
            }
            return false;
        }
        
        // 检查文件大小和权限
        if (tokenizerFile.length() == 0) {
            LogManager.logE(TAG, "❌ tokenizer.json文件大小为0: " + tokenizerFile.getAbsolutePath());
            return false;
        }
        
        if (!tokenizerFile.canRead()) {
            LogManager.logE(TAG, "❌ tokenizer.json文件无法读取: " + tokenizerFile.getAbsolutePath());
            return false;
        }
        
        LogManager.logI(TAG, "✅ tokenizer.json文件验证通过，开始加载...");
        
        // 检查当前分词器是否已经加载了相同的文件
        if (tokenizer != null) {
            try {
                String currentModelPath = tokenizer.getModelPath();
                if (currentModelPath != null && currentModelPath.equals(tokenizerFile.getAbsolutePath())) {
                    LogManager.logD(TAG, "当前分词器已经加载了相同的模型文件，无需重新加载");
                    return true;
                }
                
                // 如果是不同的模型文件，关闭现有分词器
                LogManager.logD(TAG, "加载新的模型文件，关闭现有分词器");
                try {
                    tokenizer.close();
                } catch (Exception e) {
                    LogManager.logW(TAG, "关闭现有分词器时出错", e);
                } finally {
                    tokenizer = null;
                }
            } catch (Exception e) {
                LogManager.logW(TAG, "检查当前分词器模型路径时出错", e);
                // 关闭现有分词器
                try {
                    tokenizer.close();
                } catch (Exception ex) {
                    LogManager.logW(TAG, "关闭现有分词器时出错", ex);
                } finally {
                    tokenizer = null;
                }
            }
        }
        
        try {
            LogManager.logI(TAG, "🔧 开始创建HuggingfaceTokenizer实例...");
            LogManager.logI(TAG, "目标文件: " + tokenizerFile.getAbsolutePath());
            LogManager.logI(TAG, "文件最后修改时间: " + new java.util.Date(tokenizerFile.lastModified()));
            
            // 检查JNI库加载状态
            LogManager.logI(TAG, "📚 检查JNI库加载状态...");
            
            try {
                LogManager.logI(TAG, "正在加载tokenizers_jni库...");
                System.loadLibrary("tokenizers_jni");
                LogManager.logI(TAG, "✅ tokenizers_jni库加载成功");
            } catch (UnsatisfiedLinkError jniError) {
                LogManager.logE(TAG, "❌ JNI库加载失败: " + jniError.getMessage(), jniError);
                LogManager.logE(TAG, "可能的原因: 1) 库文件不存在 2) 架构不匹配 3) 依赖库缺失");
                return false;
            } catch (Exception e) {
                LogManager.logE(TAG, "❌ JNI库加载时发生未知异常: " + e.getMessage(), e);
                return false;
            }
            
            try {
                LogManager.logI(TAG, "🚀 开始创建HuggingfaceTokenizer对象...");
                LogManager.logI(TAG, "构造参数: path=" + tokenizerFile.getAbsolutePath() + ", isFile=true");
                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Detected global stop flag, interrupting tokenizer creation");
                    return false;
                }
                
                long createStartTime = System.currentTimeMillis();
                
                // 正确传递参数，第二个参数应该是true，表示这是一个文件路径
                tokenizer = new HuggingfaceTokenizer(tokenizerFile.getAbsolutePath(), true);
                
                // 创建完成后再次检查停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Detected global stop flag after tokenizer creation, closing tokenizer");
                    try {
                        tokenizer.close();
                    } catch (Exception e) {
                        LogManager.logW(TAG, "Error closing tokenizer after stop signal", e);
                    } finally {
                        tokenizer = null;
                    }
                    return false;
                }
                
                long createEndTime = System.currentTimeMillis();
                LogManager.logI(TAG, "HuggingfaceTokenizer创建耗时: " + (createEndTime - createStartTime) + "ms");
                
                // 如果需要设置一致性分词，可以在这里设置
                if (tokenizer != null) {
                    LogManager.logI(TAG, "✅ 成功从文件加载分词器: " + tokenizerFile.getAbsolutePath());
                    //LogManager.logI(TAG, "分词器对象类型: " + tokenizer.getClass().getName());
                    //LogManager.logI(TAG, "分词器模型路径: " + tokenizer.getModelPath());
                    
                    // 获取特殊token信息
                    try {
                        int specialTokensCount = tokenizer.getSpecialTokensSize();
                        //LogManager.logI(TAG, "特殊token数量: " + specialTokensCount);
                    } catch (Exception e) {
                        LogManager.logW(TAG, "获取特殊token数量失败: " + e.getMessage());
                    }
                    
                    return true;
                } else {
                    LogManager.logE(TAG, "❌ 分词器创建成功但实例为空");
                    return false;
                }
            } catch (IllegalArgumentException e) {
                LogManager.logE(TAG, "创建分词器失败，参数错误: " + e.getMessage(), e);
                return false;
            } catch (UnsatisfiedLinkError e) {
                LogManager.logE(TAG, "创建分词器失败，本地库加载错误: " + e.getMessage(), e);
                return false;
            } catch (Exception e) {
                LogManager.logE(TAG, "创建分词器失败，未知错误: " + e.getMessage(), e);
                return false;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "加载分词器过程中发生未捕获的异常: " + e.getMessage(), e);
            return false;
        }
    }
    

    
    /**
     * 对文本进行分词
     * @param text 输入文本
     * @return 分词结果（token ID列表）
     */
    public long[][] tokenize(String text) {
        // 检查全局停止标志
        if (GlobalStopManager.isGlobalStopRequested()) {
            LogManager.logD(TAG, "Detected global stop flag, interrupting tokenization");
            return new long[1][0];
        }
        
        if (tokenizer == null) {
            LogManager.logE(TAG, "分词器未初始化");
            return new long[1][0];
        }
        
        LogManager.logD(TAG, "TokenizerManager开始分词，文本长度: " + text.length());
        LogManager.logD(TAG, "使用的分词器类型: " + tokenizer.getClass().getSimpleName());
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 再次检查停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "Detected global stop flag during tokenization, interrupting");
                return new long[1][0];
            }
            
            // 使用HuggingfaceTokenizer分词
            long[][] result = tokenizer.tokenizeToLongArray(text);
            long endTime = System.currentTimeMillis();
            
            LogManager.logD(TAG, String.format("TokenizerManager分词完成，耗时: %d ms, 结果维度: %dx%d", 
                (endTime - startTime), result.length, result[0].length));
            
            return result;
        } catch (Exception e) {
            LogManager.logE(TAG, "分词器异常: " + e.getMessage(), e);
            return new long[1][0];
        }
    }
    
    /**
     * 获取特殊token数量
     * @return 特殊token数量
     */
    public int getSpecialTokensSize() {
        if (tokenizer != null) {
            int size = tokenizer.getSpecialTokensSize();
            //LogManager.logD(TAG, "获取特殊token数量: " + size);
            return size;
        }
        LogManager.logD(TAG, "分词器未初始化，返回0");
        return 0;
    }
    

    
    /**
     * 释放资源
     */
    @Override
    public void close() {
        if (tokenizer != null) {
            try {
                // 添加同步锁，防止并发访问导致的内存问题
                synchronized (this) {
                    if (tokenizer != null) {
                        LogManager.logD(TAG, "开始释放分词器资源...");
                        
                        // 添加延迟，确保JNI层完成所有操作
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        
                        tokenizer.close();
                        tokenizer = null;
                        LogManager.logI(TAG, "分词器资源释放完成");
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "关闭分词器时出错: " + e.getMessage(), e);
                // 即使出错也要清空引用，防止内存泄漏
                tokenizer = null;
            }
        }
    }
    
    /**
     * 重置分词器管理器
     * 用于在应用重启或模型切换时重新初始化
     */
    public static synchronized void resetManager() {
        if (instance != null) {
            LogManager.logD(TAG, "开始重置分词器管理器...");
            
            // 安全释放资源
            try {
                // 先标记为未初始化，防止其他线程使用
                instance.initialized = false;
                
                // 添加延迟，确保所有正在进行的操作完成
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                
                // 释放资源
                instance.close();
                
                LogManager.logI(TAG, "分词器管理器重置完成");
            } catch (Exception e) {
                LogManager.logE(TAG, "释放分词器资源失败: " + e.getMessage(), e);
                // 记录详细的错误信息用于调试
                LogManager.logE(TAG, "错误堆栈: ", e);
            } finally {
                // 无论是否出错都要清空实例
                instance = null;
            }
        } else {
            LogManager.logD(TAG, "分词器管理器实例为空，无需重置");
        }
    }
    
    /**
     * 将token ID解码为文本
     * @param ids token ID数组
     * @return 解码后的文本
     */
    @Override
    public String decodeIds(int[] ids) {
        if (tokenizer == null) {
            LogManager.logE(TAG, "分词器未初始化");
            return "";
        }
        
        try {
            // 使用原生的HuggingfaceTokenizer解码功能
            if (debugMode) {
                LogManager.logD(TAG, "使用HuggingfaceTokenizer解码，ID数量: " + ids.length);
            }
            
            // 如果是特定模型，可以添加特殊处理
            if (modelType != null && !modelType.isEmpty()) {
                // 这里可以根据模型类型添加特殊处理逻辑
                if (debugMode) {
                    LogManager.logD(TAG, "当前模型类型: " + modelType);
                }
            }
            
            // 使用HuggingfaceTokenizer的特殊解码方法，过滤掉特殊token
        if (debugMode) {
            LogManager.logD(TAG, "使用HuggingfaceTokenizer的decodeForModelOutput方法解码");
        }
        String decodedText = tokenizer.decodeForModelOutput(ids);
         
         // 强化Unicode解码修复 - 多重检查和修复
         String fixedText = decodedText;
         
         // 第一次修复：使用UnicodeUtils
         if (UnicodeUtils.containsUnicodeEscapes(fixedText)) {
             fixedText = UnicodeUtils.decodeUnicodeEscapes(fixedText);
             if (debugMode) {
                 LogManager.logD(TAG, "第一次Unicode修复: " + decodedText.substring(0, Math.min(100, decodedText.length())) + " -> " + fixedText.substring(0, Math.min(100, fixedText.length())));
             }
         }
         
         // 第二次修复：处理可能遗漏的转义序列
         if (fixedText.contains("\\u")) {
             String secondFix = UnicodeUtils.decodeUnicodeEscapes(fixedText);
             if (!secondFix.equals(fixedText)) {
                 if (debugMode) {
                     LogManager.logD(TAG, "第二次Unicode修复: " + fixedText.substring(0, Math.min(100, fixedText.length())) + " -> " + secondFix.substring(0, Math.min(100, secondFix.length())));
                 }
                 fixedText = secondFix;
             }
         }
         
         // 第三次修复：清理可能的残留转义字符
         fixedText = UnicodeUtils.cleanText(fixedText);
         
         if (debugMode && !decodedText.equals(fixedText)) {
             LogManager.logD(TAG, "最终Unicode解码修复完成");
         }
         
         return fixedText;
        } catch (Exception e) {
            LogManager.logE(TAG, "解码异常: " + e.getMessage(), e);
            return "";
        }
    }
    
    /**
     * 重置分词器
     */
    @Override
    public void reset() {
        LogManager.logD(TAG, "开始重置分词器...");
        
        // 先重置状态
        initialized = false;
        
        // 安全释放分词器资源
        if (tokenizer != null) {
            try {
                synchronized (this) {
                    if (tokenizer != null) {
                        // 添加延迟，确保JNI层完成所有操作
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        
                        tokenizer.close();
                        tokenizer = null;
                        LogManager.logI(TAG, "分词器资源释放完成");
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "释放分词器资源失败: " + e.getMessage(), e);
                // 即使出错也要清空引用
                tokenizer = null;
            }
        }
        
        LogManager.logD(TAG, "分词器重置完成");
    }
    
    /**
     * 获取特殊token的ID
     * @param token 特殊token的内容
     * @return 特殊token的ID，如果不存在则返回-1
     */
    @Override
    public int getSpecialTokenId(String token) {
        if (tokenizer != null) {
            try {
                Map<String, String> specialTokens = tokenizer.getSpecialTokens();
                if (specialTokens != null && specialTokens.containsValue(token)) {
                    // 由于HuggingfaceTokenizer的getSpecialTokens返回的是类型到内容的映射
                    // 这里简化处理，返回-1表示由底层处理
                    return -1;
                }
            } catch (Exception e) {
                LogManager.logW(TAG, "获取特殊token ID失败: " + e.getMessage());
            }
        }
        return -1;
    }
    
    /**
     * 根据ID获取特殊token的内容
     * @param id token的ID
     * @return 特殊token的内容，如果不存在则返回null
     */
    @Override
    public String getSpecialTokenContent(int id) {
        if (tokenizer != null) {
            try {
                int[] singleId = {id};
                return tokenizer.decode(singleId);
            } catch (Exception e) {
                LogManager.logW(TAG, "获取特殊token内容失败: " + e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 检查给定的ID是否为特殊token
     * @param id token的ID
     * @return 是否为特殊token
     */
    @Override
    public boolean isSpecialToken(int id) {
        // 简化实现，委托给HuggingfaceTokenizer
        return false;
    }
    
    /**
     * 获取所有特殊token的映射（内容到ID）
     * @return 特殊token映射
     */
    @Override
    public Map<String, Integer> getAllSpecialTokens() {
        if (tokenizer != null) {
            try {
                Map<String, String> specialTokens = tokenizer.getSpecialTokens();
                Map<String, Integer> result = new HashMap<>();
                if (specialTokens != null) {
                    for (String content : specialTokens.values()) {
                        result.put(content, -1); // 简化处理，ID由底层管理
                    }
                }
                return result;
            } catch (Exception e) {
                LogManager.logW(TAG, "获取所有特殊token失败: " + e.getMessage());
            }
        }
        return new HashMap<>();
    }
    
    /**
     * 添加一个特殊token
     * @param content token内容
     * @param id token ID，如果为-1，表示暂时不知道ID，由JNI层处理
     * @return 是否添加成功
     */
    @Override
    public boolean addSpecialToken(String content, int id) {
        if (content == null || content.isEmpty()) {
            LogManager.logE(TAG, "特殊token内容不能为空");
            return false;
        }
        
        // 简化实现，特殊token由HuggingfaceTokenizer管理
        if (debugMode) {
            LogManager.logD(TAG, "特殊token添加请求: " + content + " (委托给HuggingfaceTokenizer处理)");
        }
        
        return true;
    }
    
    /**
     * 应用聊天模板
     * @param messages 消息列表
     * @param addGenerationPrompt 是否添加生成提示
     * @return 应用模板后的文本
     */
    @Override
    public String applyChatTemplate(Object messages, boolean addGenerationPrompt) {
        if (tokenizer == null) {
            LogManager.logE(TAG, "分词器未初始化，无法应用聊天模板");
            return "";
        }
        
        try {
            // 调用HuggingfaceTokenizer的applyChatTemplate方法
            if (messages instanceof JSONArray) {
                // 使用现有的JSONArray重载方法
                return tokenizer.applyChatTemplate((JSONArray)messages, addGenerationPrompt, false);
            } else {
                // 如果是其他类型，尝试转换为JSON字符串
                String jsonStr = messages.toString();
                try {
                    JSONArray jsonArray = new JSONArray(jsonStr);
                    return tokenizer.applyChatTemplate(jsonArray, addGenerationPrompt, false);
                } catch (JSONException je) {
                    LogManager.logE(TAG, "无法将消息转换为JSONArray: " + je.getMessage(), je);
                    return "";
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "应用聊天模板失败: " + e.getMessage(), e);
            return "";
        }
    }
    
    /**
     * 获取模型类型
     * @return 模型类型名称
     */
    @Override
    public String getModelType() {
        return modelType;
    }
    
    /**
     * 设置模型类型
     * @param modelType 模型类型名称
     */
    @Override
    public void setModelType(String modelType) {
        this.modelType = modelType;
        if (debugMode) {
            LogManager.logD(TAG, "设置模型类型: " + modelType);
        }
    }
    
    /**
     * 对输入文本进行分词（兼容方法）
     * @param text 输入文本
     * @return token ID数组
     */
    public int[] tokenizeInput(String text) {
        if (!initialized || tokenizer == null) {
            LogManager.logE(TAG, "分词器未初始化，无法进行分词");
            return new int[0];
        }
        
        try {
            // 使用现有的tokenize方法
            long[][] result = tokenize(text);
            if (result != null && result.length > 0 && result[0] != null) {
                // 转换long[]到int[]
                long[] longIds = result[0];
                int[] intIds = new int[longIds.length];
                for (int i = 0; i < longIds.length; i++) {
                    intIds[i] = (int) longIds[i];
                }
                return intIds;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "分词失败: " + e.getMessage(), e);
        }
        
        return new int[0];
    }
    
    /**
     * 获取分词器配置信息
     * @return 配置信息的字符串表示
     */
    @Override
    public String getTokenizerInfo() {
        StringBuilder info = new StringBuilder();
        info.append("TokenizerManager配置信息:\n");
        //info.append("- 初始化状态: ").append(initialized ? "已初始化" : "未初始化").append("\n");
        //info.append("- 特殊token数量: ").append(getSpecialTokensSize()).append("\n");
        //info.append("- 模型类型: ").append(modelType.isEmpty() ? "未设置" : modelType).append("\n");
        //info.append("- 一致性分词: ").append(useConsistentTokenization ? "启用" : "禁用").append("\n");
        //info.append("- 调试模式: ").append(debugMode ? "启用" : "禁用").append("\n");
        
        // 添加特殊token信息
        if (tokenizer != null) {
            try {
                Map<String, String> specialTokens = tokenizer.getSpecialTokens();
                if (specialTokens != null) {
                    info.append("- 特殊token数量: ").append(specialTokens.size()).append("\n");
                    //info.append("- 常用特殊token:\n");
                    for (Map.Entry<String, String> entry : specialTokens.entrySet()) {
                        //info.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
            } catch (Exception e) {
                info.append("- 特殊token信息获取失败\n");
            }
        }
        
        return info.toString();
    }
}

