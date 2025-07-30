package com.example.starlocalrag;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.starlocalrag.FileUtil;
import com.example.starlocalrag.SQLiteVectorDatabaseHandler;

/**
 * 配置管理器，用于保存和读取应用程序配置
 * 配置保存在应用程序目录下的.config文件中
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    
    // 日志信息常量 - 使用字符串资源
    private static String getLogString(Context context, int resId) {
        return context != null ? context.getString(resId) : "";
    }
    
    private static String getLogString(Context context, int resId, Object... formatArgs) {
        return context != null ? context.getString(resId, formatArgs) : "";
    }
    
    // 日志资源ID常量
    private static final int LOG_SAVE_CONFIG_FAILED = R.string.config_save_failed;
    private static final int LOG_FOUND_SQLITE_DB = R.string.config_found_sqlite_db;
    private static final int LOG_READ_EMBEDDING_FROM_SQLITE = R.string.config_read_embedding_from_sqlite;
    private static final int LOG_SEARCH_IN_SETTING_PATH = R.string.config_search_in_setting_path;
    private static final int LOG_FOUND_EMBEDDING_FILE = R.string.config_found_embedding_file;
    private static final int LOG_EMBEDDING_FILE_NOT_EXIST = R.string.config_embedding_file_not_exist;
    private static final int LOG_READ_SQLITE_FAILED = R.string.config_read_sqlite_failed;
    private static final int LOG_CLOSE_SQLITE_FAILED = R.string.config_close_sqlite_failed;
    private static final int LOG_FOUND_METADATA_JSON = R.string.config_found_metadata_json;
    private static final int LOG_READ_EMBEDDING_FROM_JSON = R.string.config_read_embedding_from_json;
    private static final int LOG_READ_METADATA_JSON_FAILED = R.string.config_read_metadata_json_failed;
    private static final int LOG_SEARCH_EMBEDDING_IN_KB_DIR = R.string.config_search_embedding_in_kb_dir;
    private static final int LOG_FOUND_POSSIBLE_EMBEDDING = R.string.config_found_possible_embedding;
    private static final int LOG_FOUND_POSSIBLE_MODEL = R.string.config_found_possible_model;
    private static final int LOG_SEARCH_EMBEDDING_ERROR = R.string.config_search_embedding_error;
    private static final int LOG_NO_EMBEDDING_FOUND = R.string.config_no_embedding_found;
    private static final int LOG_SAVE_API_KEY_FAILED = R.string.config_save_api_key_failed;
    private static final int LOG_GET_API_KEY_FAILED = R.string.config_get_api_key_failed;
    private static final int LOG_GET_SYSTEM_PROMPTS_FAILED = R.string.config_get_system_prompts_failed;
    private static final int LOG_SAVE_SYSTEM_PROMPT_FAILED = R.string.config_save_system_prompt_failed;
    private static final String CONFIG_FILENAME = ".config";
    private static final String KNOWLEDGE_BASE_CONFIG = "config.json";

    // API相关的键
    public static final String KEY_API_URL = "api_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL_NAME = "model_name";
    public static final String KEY_KNOWLEDGE_BASE = "knowledge_base";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    
    // 分块相关的键
    public static final String KEY_BLOCK_SIZE = "block_size";
    public static final String KEY_OVERLAP_SIZE = "overlap_size";
    public static final String KEY_MIN_CHUNK_SIZE = "min_chunk_size"; // 添加最小分块限制键
    public static final String KEY_EMBEDDING_MODEL = "embedding_model";
    public static final String KEY_ENABLE_JSON_DATASET_SPLITTING = "enable_json_dataset_splitting";
    public static final String KEY_CHUNK_SIZE = "chunk_size"; // 分块设置键
    public static final String KEY_LAST_SELECTED_KB = "last_selected_kb"; // 知识库相关键
    public static final String KEY_LAST_SELECTED_EMBEDDING_MODEL = "last_selected_embedding_model"; // 知识库相关键
    public static final String KEY_LAST_SELECTED_RERANKER_MODEL = "last_selected_reranker_model"; // 重排模型相关键
    
    // 设置相关的键
    public static final String KEY_MODEL_PATH = "model_path";
    public static final String KEY_EMBEDDING_MODEL_PATH = "embedding_model_path";
    public static final String KEY_RERANKER_MODEL_PATH = "reranker_model_path";
    public static final String KEY_KNOWLEDGE_BASE_PATH = "knowledge_base_path";
    public static final String KEY_SEARCH_DEPTH = "search_depth";
    public static final String KEY_RERANK_COUNT = "rerank_count";
    public static final String KEY_RETRIEVAL_COUNT = "retrieval_count";
    public static final String KEY_DEBUG_MODE = "debug_mode"; // 调试模式配置键
    public static final String KEY_USE_GPU = "use_gpu"; // GPU加速配置键
    
    // LLM 推理相关的键
    public static final String KEY_MAX_SEQUENCE_LENGTH = "maxSequenceLength"; // 最大序列长度
    public static final String KEY_NO_THINKING = "no_thinking"; // 是否禁用思考模式
    public static final String KEY_THREADS = "threads"; // ONNX推理线程数
    public static final String KEY_MAX_NEW_TOKENS = "max_new_tokens"; // 最大输出token数
    public static final String KEY_KV_CACHE_SIZE = "kv_cache_size"; // 兼容性保留，已废弃，使用max_new_tokens
    // ONNX相关配置项已移除
    
    // LlamaCpp 相关配置键
    public static final String KEY_LLAMACPP_MODEL_PATH = "llamacpp_model_path"; // LlamaCpp模型路径
    public static final String KEY_LLAMACPP_CONTEXT_SIZE = "llamacpp_context_size"; // 上下文大小
    public static final String KEY_LLAMACPP_BATCH_SIZE = "llamacpp_batch_size"; // 批处理大小
    public static final String KEY_LLAMACPP_THREADS = "llamacpp_threads"; // 线程数
    public static final String KEY_LLAMACPP_GPU_LAYERS = "llamacpp_gpu_layers"; // GPU层数
    public static final String KEY_LLAMACPP_TEMPERATURE = "llamacpp_temperature"; // 温度参数
    public static final String KEY_LLAMACPP_TOP_P = "llamacpp_top_p"; // Top-P采样
    public static final String KEY_LLAMACPP_TOP_K = "llamacpp_top_k"; // Top-K采样
    public static final String KEY_LLAMACPP_REPEAT_PENALTY = "llamacpp_repeat_penalty"; // 重复惩罚
    public static final String KEY_LLAMACPP_REPEAT_LAST_N = "llamacpp_repeat_last_n"; // 重复检查长度
    public static final String KEY_LLAMACPP_SEED = "llamacpp_seed"; // 随机种子
    public static final String KEY_LLAMACPP_USE_MMAP = "llamacpp_use_mmap"; // 使用内存映射
    public static final String KEY_LLAMACPP_USE_MLOCK = "llamacpp_use_mlock"; // 使用内存锁定
    public static final String KEY_LLAMACPP_NORMALIZE_EMBEDDINGS = "llamacpp_normalize_embeddings"; // 归一化嵌入
    public static final String KEY_LLAMACPP_EMBEDDING_BATCH_SIZE = "llamacpp_embedding_batch_size"; // 嵌入批处理大小
    public static final String KEY_USE_LLAMACPP = "use_llamacpp"; // 是否使用LlamaCpp引擎
    
    // 手动推理参数配置键（用于外部设置）
    public static final String KEY_MANUAL_TEMPERATURE = "manual_temperature"; // 手动温度参数
    public static final String KEY_MANUAL_TOP_P = "manual_top_p"; // 手动Top-P采样
    public static final String KEY_MANUAL_TOP_K = "manual_top_k"; // 手动Top-K采样
    public static final String KEY_MANUAL_REPEAT_PENALTY = "manual_repeat_penalty"; // 手动重复惩罚
    public static final String KEY_PRIORITY_MANUAL_PARAMS = "priority_manual_params"; // 优先手动参数开关
    
    // 语言设置配置键
    public static final String KEY_LANGUAGE = "language"; // 语言设置
    
    // 文本大小相关的键
    public static final String KEY_GLOBAL_TEXT_SIZE = "global_text_size";
    public static final String KEY_RAG_RESPONSE_TEXT_SIZE = "rag_response_text_size";
    public static final String KEY_BUILD_SELECTED_FILES_TEXT_SIZE = "build_selected_files_text_size";
    public static final String KEY_BUILD_PROGRESS_TEXT_SIZE = "build_progress_text_size";
    public static final String KEY_NOTE_CONTENT_TEXT_SIZE = "note_content_text_size";
    public static final String KEY_LOG_CONTENT_TEXT_SIZE = "log_content_text_size";

    // 默认值
    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_BLOCK_SIZE = DEFAULT_CHUNK_SIZE;
    public static final int DEFAULT_OVERLAP_SIZE = 100;
    public static final int DEFAULT_MIN_CHUNK_SIZE = 10; // 修改为200，与PC端保持一致
    public static final String DEFAULT_MODEL_PATH = "/storage/emulated/0/Download/StarRagData/models";
    public static final String DEFAULT_EMBEDDING_MODEL_PATH = "/storage/emulated/0/Download/StarRagData/embeddings";
    public static final String DEFAULT_RERANKER_MODEL_PATH = "/storage/emulated/0/Download/StarRagData/rerankers";
    public static final String DEFAULT_KNOWLEDGE_BASE_PATH = "/storage/emulated/0/Download/StarRagData/knowledge_bases";
    public static final int DEFAULT_SEARCH_DEPTH = 20;
    public static final int DEFAULT_RERANK_COUNT = 5;

    public static final float DEFAULT_TEXT_SIZE = 14f;
    
    // LLM 推理相关的默认值
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 4096;
    public static final boolean DEFAULT_NO_THINKING = false;
    public static final int DEFAULT_THREADS = 4;
    public static final int DEFAULT_MAX_NEW_TOKENS = 512; // 最大输出token数默认值
    
    // LlamaCpp 相关默认值
    public static final String DEFAULT_LLAMACPP_MODEL_PATH = "files/models/llamacpp";
    public static final int DEFAULT_LLAMACPP_CONTEXT_SIZE = DEFAULT_MAX_SEQUENCE_LENGTH; // 统一使用maxSequenceLength
    public static final int DEFAULT_LLAMACPP_BATCH_SIZE = DEFAULT_MAX_SEQUENCE_LENGTH; // 统一使用maxSequenceLength
    public static final int DEFAULT_LLAMACPP_THREADS = 4;
    public static final int DEFAULT_LLAMACPP_GPU_LAYERS = 0;
    public static final float DEFAULT_LLAMACPP_TEMPERATURE = 0.8f;
    public static final float DEFAULT_LLAMACPP_TOP_P = 0.95f;
    public static final int DEFAULT_LLAMACPP_TOP_K = 40;
    public static final float DEFAULT_LLAMACPP_REPEAT_PENALTY = 1.1f;
    public static final int DEFAULT_LLAMACPP_REPEAT_LAST_N = 64;
    public static final int DEFAULT_LLAMACPP_SEED = -1; // -1表示随机种子
    public static final boolean DEFAULT_LLAMACPP_USE_MMAP = true;
    public static final boolean DEFAULT_LLAMACPP_USE_MLOCK = false;
    public static final boolean DEFAULT_LLAMACPP_NORMALIZE_EMBEDDINGS = true; // 归一化控制
    public static final int DEFAULT_LLAMACPP_EMBEDDING_BATCH_SIZE = 32;
    public static final boolean DEFAULT_USE_LLAMACPP = false;
    
    // 手动推理参数默认值
    public static final float DEFAULT_MANUAL_TEMPERATURE = 0.8f;
    public static final float DEFAULT_MANUAL_TOP_P = 0.95f;
    public static final int DEFAULT_MANUAL_TOP_K = 40;
    public static final float DEFAULT_MANUAL_REPEAT_PENALTY = 1.1f;
    
    // 语言设置默认值
    public static final String DEFAULT_LANGUAGE = "CHN"; // 默认中文

    private static JSONObject configCache = null;
    
    /**
     * 检查是否为需要多语言处理的配置键
     * @param key 配置键
     * @return 是否需要多语言处理
     */
    private static boolean isMultiLanguageConfigKey(String key) {
        return KEY_API_URL.equals(key) || 
               KEY_KNOWLEDGE_BASE.equals(key) || 
               KEY_LAST_SELECTED_RERANKER_MODEL.equals(key);
    }
    
    /**
     * 将显示文本转换为资源键
     * @param context 上下文
     * @param displayText 显示文本
     * @return 资源键，如果无法转换则返回null
     */
    private static String convertDisplayTextToResourceKey(Context context, String displayText) {
        if (displayText == null) {
            return null;
        }
        
        // API URL 相关转换
        if (displayText.equals(context.getString(R.string.api_url_local))) {
            return "api_url_local";
        }
        if (displayText.equals(context.getString(R.string.api_url_openai))) {
            return "api_url_openai";
        }
        if (displayText.equals(context.getString(R.string.common_custom))) {
            return "common_custom";
        }
        if (displayText.equals(context.getString(R.string.common_new))) {
            return "common_new";
        }
        
        // 通用 "无" 相关转换
        if (displayText.equals(context.getString(R.string.common_none))) {
            return "common_none";
        }
        
        // 知识库状态相关转换
        if (displayText.equals(context.getString(R.string.kb_state_empty))) {
            return "kb_state_empty";
        }
        if (displayText.equals(context.getString(R.string.common_loading))) {
            return "common_loading";
        }
        if (displayText.equals(context.getString(R.string.common_ready))) {
            return "common_ready";
        }
        
        // 重排模型相关转换
        if (displayText.equals(context.getString(R.string.reranker_model_bge_reranker))) {
            return "reranker_model_bge_reranker";
        }
        
        // 如果不是特殊的多语言文本，返回null表示不需要转换
        return null;
    }
    
    /**
     * 将资源键转换为显示文本
     * @param context 上下文
     * @param resourceKey 资源键
     * @return 显示文本，如果无法转换则返回null
     */
    private static String convertResourceKeyToDisplayText(Context context, String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        
        switch (resourceKey) {
            case "api_url_local":
                return context.getString(R.string.api_url_local);
            case "api_url_openai":
                return context.getString(R.string.api_url_openai);
            case "common_custom":
                return context.getString(R.string.common_custom);
            case "common_new":
                return context.getString(R.string.common_new);
            case "common_none":
                return context.getString(R.string.common_none);
            case "kb_state_empty":
                return context.getString(R.string.kb_state_empty);
            case "common_loading":
                return context.getString(R.string.common_loading);
            case "common_ready":
            return context.getString(R.string.common_ready);
            case "reranker_model_bge_reranker":
                return context.getString(R.string.reranker_model_bge_reranker);
            default:
                return null; // 如果不是资源键，返回null表示不需要转换
        }
    }

    /**
     * 获取配置文件
     * @param context 上下文
     * @return 配置文件
     */
    private static File getConfigFile(Context context) {
        return new File(context.getFilesDir(), CONFIG_FILENAME);
    }

    /**
     * 加载配置
     * @param context 上下文
     * @return 配置JSON对象
     */
    public static JSONObject loadConfig(Context context) {
        // 如果缓存存在，直接返回缓存
        if (configCache != null) {
            return configCache;
        }
        
        try {
            // 获取配置文件
            File configFile = getConfigFile(context);
            
            // 如果配置文件不存在，创建默认配置
            if (!configFile.exists()) {
                LogManager.logD(TAG, getLogString(context, R.string.config_not_exist));
                JSONObject defaultConfig = createDefaultConfig();
                saveConfig(context, defaultConfig);
                return defaultConfig;
            }
            
            // 读取配置文件
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                LogManager.logE(TAG, getLogString(context, R.string.config_read_failed), e);
                JSONObject defaultConfig = createDefaultConfig();
                saveConfig(context, defaultConfig);
                return defaultConfig;
            }
            
            // 解析配置文件
            JSONObject config;
            try {
                config = new JSONObject(content.toString());
            } catch (JSONException e) {
                LogManager.logE(TAG, getLogString(context, R.string.config_parse_failed), e);
                JSONObject defaultConfig = createDefaultConfig();
                saveConfig(context, defaultConfig);
                return defaultConfig;
            }
            
            // 验证配置是否包含必要的配置项
            boolean needReset = false;
            String[] requiredKeys = {
                KEY_MODEL_PATH, KEY_EMBEDDING_MODEL_PATH, KEY_KNOWLEDGE_BASE_PATH,
                KEY_CHUNK_SIZE, KEY_OVERLAP_SIZE, KEY_SEARCH_DEPTH,
                KEY_API_URL, KEY_MODEL_NAME, KEY_KNOWLEDGE_BASE
                // ONNX推理引擎配置项已移除
            };
            
            // 创建一个默认配置，仅在需要时使用
            JSONObject defaultConfig = null;
            
            // 检查必要配置项，如果缺少则添加默认值，而不是重置整个配置
            for (String key : requiredKeys) {
                if (!config.has(key)) {
                    try {
                        if (defaultConfig == null) {
                            defaultConfig = createDefaultConfig();
                        }
                        config.put(key, defaultConfig.get(key));
                        Log.d(TAG, getLogString(context, R.string.config_missing_key, key));
                    } catch (JSONException ex) {
                        Log.e(TAG, getLogString(context, R.string.config_add_default_failed) + ": " + key, ex);
                        needReset = true;
                        break;
                    }
                }
            }
            
            // 确保有API Keys
            if (!config.has("api_keys")) {
                try {
                    if (defaultConfig == null) {
                        defaultConfig = createDefaultConfig();
                    }
                    config.put("api_keys", defaultConfig.getJSONObject("api_keys"));
                    LogManager.logD(TAG, getLogString(context, R.string.config_missing_api_keys));
                } catch (JSONException ex) {
                    LogManager.logE(TAG, getLogString(context, R.string.config_add_api_keys_failed), ex);
                    needReset = true;
                }
            }
            
            if (needReset) {
                LogManager.logD(TAG, getLogString(context, R.string.config_corrupted));
                if (defaultConfig == null) {
                    defaultConfig = createDefaultConfig();
                }
                saveConfig(context, defaultConfig);
                return defaultConfig;
            }
            
            // 保存更新后的配置
            saveConfig(context, config);
            
            // 缓存配置
            configCache = config;
            
            return config;
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_load_failed), e);
            JSONObject defaultConfig = createDefaultConfig();
            try {
                saveConfig(context, defaultConfig);
            } catch (Exception ex) {
                LogManager.logE(TAG, getLogString(context, R.string.config_save_default_failed), ex);
            }
            return defaultConfig;
        }
    }
    
    /**
     * 保存配置
     * @param context 上下文
     * @param config 配置JSON对象
     */
    public static void saveConfig(Context context, JSONObject config) {
        try {
            // 清理配置中的重复项，只在需要时执行
            boolean needCleanup = false;
            
            // 检查是否需要清理配置
            String[] requiredKeys = {
                KEY_MODEL_PATH, KEY_EMBEDDING_MODEL_PATH, KEY_KNOWLEDGE_BASE_PATH,
                KEY_CHUNK_SIZE, KEY_OVERLAP_SIZE, KEY_SEARCH_DEPTH,
                KEY_API_URL, KEY_MODEL_NAME, KEY_KNOWLEDGE_BASE, KEY_SYSTEM_PROMPT
            };
            
            for (String key : requiredKeys) {
                if (!config.has(key)) {
                    needCleanup = true;
                    break;
                }
            }
            
            if (!config.has("api_keys")) {
                needCleanup = true;
            }
            
            // 只在需要时执行清理
            if (needCleanup) {
                cleanupConfig(config);
            }
            
            // 获取配置文件
            File configFile = getConfigFile(context);
            
            // 写入配置文件
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config.toString(2));
                
                // 更新缓存
                configCache = new JSONObject(config.toString());
                
                LogManager.logD(TAG, getLogString(context, R.string.config_saved, configFile.getAbsolutePath()));
                //LogManager.logD(TAG, "保存的配置内容: " + config.toString(2));
            } catch (IOException e) {
                LogManager.logE(TAG, getLogString(context, R.string.config_save_failed), e);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, LOG_SAVE_CONFIG_FAILED), e);
        }
    }

    /**
     * 获取字符串配置
     * @param context 上下文
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static String getString(Context context, String key, String defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                String value = config.getString(key);
                
                // 对于特定的多语言配置项，从资源键转换为显示文本
                if (isMultiLanguageConfigKey(key)) {
                    String displayText = convertResourceKeyToDisplayText(context, value);
                    if (displayText != null) {
                        return displayText;
                    }
                }
                
                return value;
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_get_string_failed) + ": " + key, e);
        }
        return defaultValue;
    }

    /**
     * 获取整数配置
     * @param context 上下文
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static int getInt(Context context, String key, int defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                return config.getInt(key);
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_get_int_failed) + ": " + key, e);
        }
        return defaultValue;
    }

    /**
     * 获取布尔值配置
     * @param context 上下文
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static boolean getBoolean(Context context, String key, boolean defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                return config.getBoolean(key);
            }
        } catch (JSONException e) {
            Log.e(TAG, getLogString(context, R.string.config_get_boolean_failed) + ": " + key, e);
        }
        return defaultValue;
    }

    /**
     * 获取API URL
     * @param context 上下文
     * @return API URL
     */
    public static String getApiUrl(Context context) {
        return getString(context, KEY_API_URL, "");
    }

    /**
     * 设置API URL
     * @param context 上下文
     * @param apiUrl API URL
     */
    public static void setApiUrl(Context context, String apiUrl) {
        setString(context, KEY_API_URL, apiUrl);
    }

    /**
     * 获取API Key
     * @param context 上下文
     * @return API Key
     */
    public static String getApiKey(Context context) {
        return getString(context, KEY_API_KEY, "");
    }

    /**
     * 设置API Key
     * @param context 上下文
     * @param apiKey API Key
     */
    public static void setApiKey(Context context, String apiKey) {
        setString(context, KEY_API_KEY, apiKey);
    }

    /**
     * 获取模型名称
     * @param context 上下文
     * @return 模型名称
     */
    public static String getModelName(Context context) {
        return getString(context, KEY_MODEL_NAME, "");
    }

    /**
     * 设置模型名称
     * @param context 上下文
     * @param modelName 模型名称
     */
    public static void setModelName(Context context, String modelName) {
        setString(context, KEY_MODEL_NAME, modelName);
    }

    /**
     * 获取知识库名称
     * @param context 上下文
     * @return 知识库名称
     */
    public static String getKnowledgeBase(Context context) {
        return getString(context, KEY_KNOWLEDGE_BASE, "");
    }

    /**
     * 设置知识库名称
     * @param context 上下文
     * @param knowledgeBase 知识库名称
     */
    public static void setKnowledgeBase(Context context, String knowledgeBase) {
        setString(context, KEY_KNOWLEDGE_BASE, knowledgeBase);
    }

    /**
     * 获取系统提示词
     * @param context 上下文
     * @return 系统提示词
     */
    public static String getSystemPrompt(Context context) {
        return getString(context, KEY_SYSTEM_PROMPT, getLogString(context, R.string.config_default_system_prompt));
    }

    /**
     * 设置系统提示词
     * @param context 上下文
     * @param systemPrompt 系统提示词
     */
    public static void setSystemPrompt(Context context, String systemPrompt) {
        setString(context, KEY_SYSTEM_PROMPT, systemPrompt);
    }

    /**
     * 获取分块大小
     * @param context 上下文
     * @return 分块大小
     */
    public static int getBlockSize(Context context) {
        return getInt(context, KEY_BLOCK_SIZE, DEFAULT_BLOCK_SIZE);
    }

    /**
     * 设置分块大小
     * @param context 上下文
     * @param blockSize 分块大小
     */
    public static void setBlockSize(Context context, int blockSize) {
        setInt(context, KEY_BLOCK_SIZE, blockSize);
    }

    /**
     * 获取重叠大小
     * @param context 上下文
     * @return 重叠大小
     */
    public static int getOverlapSize(Context context) {
        return getInt(context, KEY_OVERLAP_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    /**
     * 设置重叠大小
     * @param context 上下文
     * @param overlapSize 重叠大小
     */
    public static void setOverlapSize(Context context, int overlapSize) {
        setInt(context, KEY_OVERLAP_SIZE, overlapSize);
    }

    /**
     * 获取嵌入模型
     * @param context 上下文
     * @return 嵌入模型
     */
    public static String getEmbeddingModel(Context context) {
        return getString(context, KEY_EMBEDDING_MODEL, "");
    }

    /**
     * 设置嵌入模型
     * @param context 上下文
     * @param embeddingModel 嵌入模型
     */
    public static void setEmbeddingModel(Context context, String embeddingModel) {
        setString(context, KEY_EMBEDDING_MODEL, embeddingModel);
    }

    /**
     * 获取知识库使用的嵌入模型
     * @param context 上下文
     * @param knowledgeBaseName 知识库名称
     * @return 嵌入模型路径
     */
    public static String getKnowledgeBaseEmbeddingModel(Context context, String knowledgeBaseName) {
        // 获取设置中的知识库路径
        String knowledgeBasePath = SettingsFragment.getKnowledgeBasePath(context);
        File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBaseName);
        
        LogManager.logD(TAG, getLogString(context, R.string.config_try_read_metadata) + ": " + knowledgeBaseName);
        
        if (!knowledgeBaseDir.exists()) {
            LogManager.logE(TAG, getLogString(context, R.string.config_kb_dir_not_exist) + ": " + knowledgeBaseDir.getAbsolutePath());
            return null;
        }
        
        // 首先尝试从SQLite数据库中读取嵌入模型信息
        File sqliteDbFile = new File(knowledgeBaseDir, "vectorstore.db");
        if (sqliteDbFile.exists()) {
            LogManager.logD(TAG, getLogString(context, LOG_FOUND_SQLITE_DB));
            SQLiteVectorDatabaseHandler vectorDb = null;
            try {
                vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                if (vectorDb.loadDatabase()) {
                    SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
                    if (metadata != null) {
                        String embeddingModel = metadata.getModeldir();
                        LogManager.logD(TAG, LOG_READ_EMBEDDING_FROM_SQLITE + ": " + embeddingModel);
                        
                        if (embeddingModel != null && !embeddingModel.isEmpty()) {
                            // 获取设置中的嵌入模型路径
                            String embeddingModelPath = SettingsFragment.getEmbeddingModelPath(context);
                            
                            // 检查嵌入模型文件是否存在
                            File modelFile = new File(embeddingModel);
                            if (!modelFile.exists()) {
                                // 尝试在设置的嵌入模型路径中查找
                                modelFile = new File(embeddingModelPath, embeddingModel);
                                LogManager.logD(TAG, LOG_SEARCH_IN_SETTING_PATH + ": " + modelFile.getAbsolutePath());
                            }
                            
                            if (modelFile.exists()) {
                                LogManager.logD(TAG, LOG_FOUND_EMBEDDING_FILE + ": " + modelFile.getAbsolutePath());
                                return modelFile.getAbsolutePath();
                            } else {
                                LogManager.logE(TAG, LOG_EMBEDDING_FILE_NOT_EXIST + ": " + embeddingModel);
                                return null; // 直接返回null，让调用者处理模型不存在的情况
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, getLogString(context, LOG_READ_SQLITE_FAILED), e);
            } finally {
                if (vectorDb != null) {
                    try {
                        vectorDb.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, getLogString(context, LOG_CLOSE_SQLITE_FAILED), e);
                    }
                }
            }
        }
        
        // 如果无法从SQLite数据库中获取，尝试从metadata.json文件中读取
        File jsonMetadataFile = new File(knowledgeBaseDir, "metadata.json");
        if (jsonMetadataFile.exists()) {
            LogManager.logD(TAG, getLogString(context, LOG_FOUND_METADATA_JSON));
            try {
                String jsonContent = FileUtil.readFile(jsonMetadataFile);
                if (jsonContent != null && !jsonContent.isEmpty()) {
                    JSONObject metadata = new JSONObject(jsonContent);
                    if (metadata.has("embeddingModel")) {
                        String embeddingModel = metadata.getString("embeddingModel");
                        LogManager.logD(TAG, LOG_READ_EMBEDDING_FROM_JSON + ": " + embeddingModel);
                        
                        if (embeddingModel != null && !embeddingModel.isEmpty()) {
                            // 获取设置中的嵌入模型路径
                            String embeddingModelPath = SettingsFragment.getEmbeddingModelPath(context);
                            
                            // 检查嵌入模型文件是否存在
                            File modelFile = new File(embeddingModel);
                            if (!modelFile.exists()) {
                                // 尝试在设置的嵌入模型路径中查找
                                modelFile = new File(embeddingModelPath, embeddingModel);
                                LogManager.logD(TAG, LOG_SEARCH_IN_SETTING_PATH + ": " + modelFile.getAbsolutePath());
                            }
                            
                            if (modelFile.exists()) {
                                LogManager.logD(TAG, LOG_FOUND_EMBEDDING_FILE + ": " + modelFile.getAbsolutePath());
                                return modelFile.getAbsolutePath();
                            } else {
                                LogManager.logE(TAG, LOG_EMBEDDING_FILE_NOT_EXIST + ": " + embeddingModel);
                                return null; // 直接返回null，让调用者处理模型不存在的情况
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, getLogString(context, LOG_READ_METADATA_JSON_FAILED), e);
            }
        }
        
        // 已移除对旧版本metadata.dat文件的兼容性支持
        
        // 如果无法从元数据中获取，尝试查找知识库目录中的任何嵌入模型文件
        LogManager.logD(TAG, getLogString(context, LOG_SEARCH_EMBEDDING_IN_KB_DIR));
        try {
            File[] files = knowledgeBaseDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().toLowerCase().contains("embedding")) {
                        LogManager.logD(TAG, LOG_FOUND_POSSIBLE_EMBEDDING + ": " + file.getAbsolutePath());
                        return file.getAbsolutePath();
                    }
                }
                
                // 如果没有找到包含"embedding"的文件，尝试查找.pt、.pth或.onnx文件
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (file.isFile() && (name.endsWith(".pt") || name.endsWith(".pth") || name.endsWith(".onnx"))) {
                        LogManager.logD(TAG, LOG_FOUND_POSSIBLE_MODEL + ": " + file.getAbsolutePath());
                        return file.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, LOG_SEARCH_EMBEDDING_ERROR), e);
        }
        
        // 不再尝试在设置的嵌入模型路径中查找默认模型，直接返回null
        LogManager.logE(TAG, getLogString(context, LOG_NO_EMBEDDING_FOUND));
        return null;
    }

    /**
     * 保存API URL和Key的映射关系
     * @param context 上下文
     * @param apiUrl API URL
     * @param apiKey API Key
     */
    public static void saveApiKeyForUrl(Context context, String apiUrl, String apiKey) {
        JSONObject config = loadConfig(context);
        try {
            // 获取API Keys映射
            JSONObject apiKeys;
            if (config.has("api_keys")) {
                apiKeys = config.getJSONObject("api_keys");
            } else {
                apiKeys = new JSONObject();
                config.put("api_keys", apiKeys);
            }
            
            // 保存映射关系
            apiKeys.put(apiUrl, apiKey);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, LOG_SAVE_API_KEY_FAILED), e);
        }
    }

    /**
     * 获取API URL对应的Key
     * @param context 上下文
     * @param apiUrl API URL
     * @return API Key
     */
    public static String getApiKeyForUrl(Context context, String apiUrl) {
        JSONObject config = loadConfig(context);
        try {
            if (config.has("api_keys")) {
                JSONObject apiKeys = config.getJSONObject("api_keys");
                return apiKeys.optString(apiUrl, "");
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, LOG_GET_API_KEY_FAILED), e);
        }
        return "";
    }

    /**
     * 获取所有保存的系统提示词
     * @param context 上下文
     * @return 系统提示词列表
     */
    public static Map<String, String> getSavedSystemPrompts(Context context) {
        JSONObject config = loadConfig(context);
        Map<String, String> prompts = new HashMap<>();
        
        try {
            if (config.has("system_prompts")) {
                JSONObject promptsJson = config.getJSONObject("system_prompts");
                Iterator<String> keys = promptsJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    prompts.put(key, promptsJson.getString(key));
                }
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, LOG_GET_SYSTEM_PROMPTS_FAILED), e);
        }
        
        return prompts;
    }

    /**
     * 保存系统提示词
     * @param context 上下文
     * @param name 提示词名称
     * @param prompt 提示词内容
     */
    public static void saveSystemPrompt(Context context, String name, String prompt) {
        JSONObject config = loadConfig(context);
        try {
            // 获取系统提示词映射
            JSONObject prompts;
            if (config.has("system_prompts")) {
                prompts = config.getJSONObject("system_prompts");
            } else {
                prompts = new JSONObject();
                config.put("system_prompts", prompts);
            }
            
            // 保存提示词
            prompts.put(name, prompt);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, getLogString(context, LOG_SAVE_SYSTEM_PROMPT_FAILED), e);
        }
    }

    /**
     * 获取分块大小
     * @param context 上下文
     * @return 分块大小
     */
    public static int getChunkSize(Context context) {
        return getInt(context, KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
    }

    /**
     * 设置分块大小
     * @param context 上下文
     * @param chunkSize 分块大小
     */
    public static void setChunkSize(Context context, int chunkSize) {
        setInt(context, KEY_CHUNK_SIZE, chunkSize);
    }

    /**
     * 获取重叠大小
     * @param context 上下文
     * @return 重叠大小
     */
    public static int getChunkOverlap(Context context) {
        return getInt(context, KEY_OVERLAP_SIZE, DEFAULT_OVERLAP_SIZE);
    }

    /**
     * 获取模型路径
     * @param context 上下文
     * @return 模型路径
     */
    public static String getModelPath(Context context) {
        return getString(context, KEY_MODEL_PATH, DEFAULT_MODEL_PATH);
    }

    /**
     * 设置模型路径
     * @param context 上下文
     * @param modelPath 模型路径
     */
    public static void setModelPath(Context context, String modelPath) {
        setString(context, KEY_MODEL_PATH, modelPath);
    }

    /**
     * 获取嵌入模型路径
     * @param context 上下文
     * @return 嵌入模型路径
     */
    public static String getEmbeddingModelPath(Context context) {
        return getString(context, KEY_EMBEDDING_MODEL_PATH, DEFAULT_EMBEDDING_MODEL_PATH);
    }

    /**
     * 设置嵌入模型路径
     * @param context 上下文
     * @param embeddingModelPath 嵌入模型路径
     */
    public static void setEmbeddingModelPath(Context context, String embeddingModelPath) {
        setString(context, KEY_EMBEDDING_MODEL_PATH, embeddingModelPath);
    }

    /**
     * 获取重排模型路径
     * @param context 上下文
     * @return 重排模型路径
     */
    public static String getRerankerModelPath(Context context) {
        return getString(context, KEY_RERANKER_MODEL_PATH, DEFAULT_RERANKER_MODEL_PATH);
    }

    /**
     * 设置重排模型路径
     * @param context 上下文
     * @param rerankerModelPath 重排模型路径
     */
    public static void setRerankerModelPath(Context context, String rerankerModelPath) {
        setString(context, KEY_RERANKER_MODEL_PATH, rerankerModelPath);
    }

    /**
     * 获取最后选择的词嵌入模型
     * @param context 上下文
     * @return 最后选择的词嵌入模型名称
     */
    public static String getLastSelectedEmbeddingModel(Context context) {
        return getString(context, KEY_LAST_SELECTED_EMBEDDING_MODEL, "");
    }

    /**
     * 设置最后选择的词嵌入模型
     * @param context 上下文
     * @param modelName 词嵌入模型名称
     */
    public static void setLastSelectedEmbeddingModel(Context context, String modelName) {
        setString(context, KEY_LAST_SELECTED_EMBEDDING_MODEL, modelName);
    }

    /**
     * 获取最后选择的重排模型
     * @param context 上下文
     * @return 最后选择的重排模型名称
     */
    public static String getLastSelectedRerankerModel(Context context) {
        return getString(context, KEY_LAST_SELECTED_RERANKER_MODEL, "");
    }

    /**
     * 设置最后选择的重排模型
     * @param context 上下文
     * @param modelName 重排模型名称
     */
    public static void setLastSelectedRerankerModel(Context context, String modelName) {
        setString(context, KEY_LAST_SELECTED_RERANKER_MODEL, modelName);
    }

    /**
     * 获取知识库路径
     * @param context 上下文
     * @return 知识库路径
     */
    public static String getKnowledgeBasePath(Context context) {
        return getString(context, KEY_KNOWLEDGE_BASE_PATH, DEFAULT_KNOWLEDGE_BASE_PATH);
    }

    /**
     * 设置知识库路径
     * @param context 上下文
     * @param knowledgeBasePath 知识库路径
     */
    public static void setKnowledgeBasePath(Context context, String knowledgeBasePath) {
        setString(context, KEY_KNOWLEDGE_BASE_PATH, knowledgeBasePath);
    }

    /**
     * 获取检索数
     * @param context 上下文
     * @return 检索数
     */
    public static int getSearchDepth(Context context) {
        return getInt(context, KEY_SEARCH_DEPTH, DEFAULT_SEARCH_DEPTH);
    }

    /**
     * 设置检索数
     * @param context 上下文
     * @param searchDepth 检索数
     */
    public static void setSearchDepth(Context context, int searchDepth) {
        setInt(context, KEY_SEARCH_DEPTH, searchDepth);
    }

    /**
     * 获取重排数
     * @param context 上下文
     * @return 重排数
     */
    public static int getRerankCount(Context context) {
        return getInt(context, KEY_RERANK_COUNT, DEFAULT_RERANK_COUNT);
    }

    /**
     * 设置重排数
     * @param context 上下文
     * @param rerankCount 重排数
     */
    public static void setRerankCount(Context context, int rerankCount) {
        setInt(context, KEY_RERANK_COUNT, rerankCount);
    }

    /**
     * 设置字符串配置
     * @param context 上下文
     * @param key 配置键
     * @param value 配置值
     */
    public static void setString(Context context, String key, String value) {
        try {
            JSONObject config = loadConfig(context);
            
            // 对于特定的多语言配置项，存储资源键而非显示文本
            if (isMultiLanguageConfigKey(key)) {
                String resourceKey = convertDisplayTextToResourceKey(context, value);
                if (resourceKey != null) {
                    config.put(key, resourceKey);
                } else {
                    config.put(key, value); // 如果无法转换，保存原值
                }
            } else {
                config.put(key, value);
            }
            
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, "设置字符串配置失败: " + key, e);
        }
    }
    
    /**
     * 获取最大序列长度
     * @param context 上下文
     * @return 最大序列长度
     */
    public static int getMaxSequenceLength(Context context) {
        return getInt(context, KEY_MAX_SEQUENCE_LENGTH, DEFAULT_MAX_SEQUENCE_LENGTH);
    }
    
    /**
     * 设置最大序列长度
     * @param context 上下文
     * @param maxSequenceLength 最大序列长度
     */
    public static void setMaxSequenceLength(Context context, int maxSequenceLength) {
        setInt(context, KEY_MAX_SEQUENCE_LENGTH, maxSequenceLength);
    }
    
    /**
     * 获取是否禁用思考模式
     * @param context 上下文
     * @return 是否禁用思考模式
     */
    public static boolean getNoThinking(Context context) {
        return getBoolean(context, KEY_NO_THINKING, DEFAULT_NO_THINKING);
    }
    
    /**
     * 设置是否禁用思考模式
     * @param context 上下文
     * @param noThinking 是否禁用思考模式
     */
    public static void setNoThinking(Context context, boolean noThinking) {
        setBoolean(context, KEY_NO_THINKING, noThinking);
    }
    
    /**
     * 获取ONNX推理线程数
     * @param context 上下文
     * @return ONNX推理线程数
     */
    public static int getThreads(Context context) {
        return getInt(context, KEY_THREADS, DEFAULT_THREADS);
    }
    
    /**
     * 设置ONNX推理线程数
     * @param context 上下文
     * @param threads ONNX推理线程数
     */
    public static void setThreads(Context context, int threads) {
        setInt(context, KEY_THREADS, threads);
    }

    /**
     * 获取最大输出token数
     * @param context 上下文
     * @return 最大输出token数
     */
    public static int getMaxNewTokens(Context context) {
        // 优先使用新的key，如果不存在则使用旧的key进行兼容
        int newValue = getInt(context, KEY_MAX_NEW_TOKENS, -1);
        if (newValue != -1) {
            return newValue;
        }
        return getInt(context, KEY_KV_CACHE_SIZE, DEFAULT_MAX_NEW_TOKENS);
    }

    /**
     * 设置最大输出token数
     * @param context 上下文
     * @param maxNewTokens 最大输出token数
     */
    public static void setMaxNewTokens(Context context, int maxNewTokens) {
        setInt(context, KEY_MAX_NEW_TOKENS, maxNewTokens);
        // 同时更新旧的key以保持兼容性
        setInt(context, KEY_KV_CACHE_SIZE, maxNewTokens);
    }

    /**
     * 获取最大输出token数（兼容性方法，已废弃）
     * @deprecated 使用 getMaxNewTokens() 替代
     */
    @Deprecated
    public static int getKvCacheSize(Context context) {
        return getMaxNewTokens(context);
    }

    /**
     * 设置最大输出token数（兼容性方法，已废弃）
     * @deprecated 使用 setMaxNewTokens() 替代
     */
    @Deprecated
    public static void setKvCacheSize(Context context, int kvCacheSize) {
        setMaxNewTokens(context, kvCacheSize);
    }

    /**
     * 设置整数配置
     * @param context 上下文
     * @param key 配置键
     * @param value 配置值
     */
    public static void setInt(Context context, String key, int value) {
        try {
            JSONObject config = loadConfig(context);
            config.put(key, value);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, "设置整数配置失败: " + key, e);
        }
    }

    /**
     * 设置布尔值配置
     * @param context 上下文
     * @param key 配置键
     * @param value 配置值
     */
    public static void setBoolean(Context context, String key, boolean value) {
        try {
            JSONObject config = loadConfig(context);
            config.put(key, value);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, "设置布尔值配置失败: " + key, e);
        }
    }

    /**
     * 保存API Key
     * @param context 上下文
     * @param apiUrl API URL
     * @param apiKey API Key
     */
    public static void saveApiKey(Context context, String apiUrl, String apiKey) {
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            // 获取API Keys映射
            JSONObject apiKeys;
            if (config.has("api_keys")) {
                apiKeys = config.getJSONObject("api_keys");
            } else {
                apiKeys = new JSONObject();
                config.put("api_keys", apiKeys);
            }
            
            // 保存API Key
            apiKeys.put(apiUrl, apiKey);
            
            // 保存配置
            saveConfig(context, config);
            
            LogManager.logD(TAG, "保存API Key: " + apiUrl + " -> " + maskApiKey(apiKey));
        } catch (Exception e) {
            LogManager.logE(TAG, "保存API Key失败", e);
        }
    }
    
    /**
     * 获取API Key
     * @param context 上下文
     * @param apiUrl API URL
     * @return API Key，如果不存在则返回空字符串
     */
    public static String getApiKey(Context context, String apiUrl) {
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            // 获取API Keys
            JSONObject apiKeys = config.getJSONObject("api_keys");
            
            // 获取API Key
            if (apiKeys.has(apiUrl)) {
                String apiKey = apiKeys.getString(apiUrl);
                LogManager.logD(TAG, "获取API Key: " + apiUrl + " -> " + maskApiKey(apiKey));
                return apiKey;
            }
            
            LogManager.logD(TAG, "未找到API Key: " + apiUrl);
            return "";
        } catch (JSONException e) {
            LogManager.logE(TAG, "获取API Key失败", e);
            return "";
        }
    }
    
    /**
     * 掩码API Key，只显示前4位和后4位
     * @param apiKey API Key
     * @return 掩码后的API Key
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        
        if (apiKey.length() <= 8) {
            return "****";
        }
        
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 获取所有系统提示词
     * @param context 上下文
     * @return 系统提示词映射
     */
    public static Map<String, String> getSystemPrompts(Context context) {
        JSONObject config = loadConfig(context);
        Map<String, String> prompts = new HashMap<>();
        
        try {
            if (config.has("system_prompts")) {
                JSONObject promptsJson = config.getJSONObject("system_prompts");
                Iterator<String> keys = promptsJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    prompts.put(key, promptsJson.getString(key));
                }
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, "获取系统提示词列表失败", e);
        }
        
        return prompts;
    }

    /**
     * 清理配置，确保包含所有必要的配置项
     * @param config 配置JSON对象
     * @return 清理后的配置JSON对象
     */
    private static JSONObject cleanupConfig(JSONObject config) {
        try {
            // 创建默认配置，用于补充缺失项
            JSONObject defaultConfig = createDefaultConfig();
            
            // 确保包含所有必要的配置项
            String[] requiredKeys = {
                KEY_MODEL_PATH, KEY_EMBEDDING_MODEL_PATH, KEY_KNOWLEDGE_BASE_PATH,
                KEY_CHUNK_SIZE, KEY_OVERLAP_SIZE, KEY_SEARCH_DEPTH,
                KEY_API_URL, KEY_MODEL_NAME, KEY_KNOWLEDGE_BASE
                // ONNX推理引擎配置项已移除
            };
            
            for (String key : requiredKeys) {
                if (!config.has(key)) {
                    config.put(key, defaultConfig.get(key));
                    LogManager.logD(TAG, "添加缺失的配置项: " + key);
                }
            }
            
            // 确保有API Keys
            if (!config.has("api_keys")) {
                config.put("api_keys", defaultConfig.getJSONObject("api_keys"));
                LogManager.logD(TAG, "添加缺失的API Keys");
            }
            
            // 确保有系统提示词（一级项）
            if (!config.has(KEY_SYSTEM_PROMPT)) {
                config.put(KEY_SYSTEM_PROMPT, defaultConfig.getString(KEY_SYSTEM_PROMPT));
                LogManager.logD(TAG, "添加缺失的系统提示词");
            }
            
            // 如果存在旧的系统提示词格式（多级项），则迁移到新格式
            if (config.has("system_prompts")) {
                try {
                    JSONObject systemPrompts = config.getJSONObject("system_prompts");
                    if (systemPrompts.has("default") && !config.has(KEY_SYSTEM_PROMPT)) {
                        config.put(KEY_SYSTEM_PROMPT, systemPrompts.getString("default"));
                        LogManager.logD(TAG, "从多级项迁移系统提示词到一级项");
                    }
                    // 移除旧的多级项
                    config.remove("system_prompts");
                    LogManager.logD(TAG, "移除旧的系统提示词多级项");
                } catch (JSONException e) {
                    // 忽略错误，继续使用新格式
                    LogManager.logD(TAG, "处理旧系统提示词格式时出错，使用新格式");
                }
            }
            
            return config;
        } catch (JSONException e) {
            LogManager.logE(TAG, "清理配置失败", e);
            return config;
        }
    }

    /**
     * 检查配置管理器中是否存在api_keys配置
     * @param context 上下文
     * @return 是否存在api_keys配置
     */
    public static boolean hasApiKeysConfig(Context context) {
        try {
            JSONObject config = loadConfig(context);
            return config.has("api_keys") && config.getJSONObject("api_keys").length() > 0;
        } catch (JSONException e) {
            LogManager.logE(TAG, "检查api_keys配置失败", e);
            return false;
        }
    }
    
    /**
     * 初始化默认API Keys
     * @param context 上下文
     */
    public static void initializeDefaultApiKeys(Context context) {
        try {
            JSONObject config = loadConfig(context);
            
            // 检查是否已经有API Keys
            if (!config.has("api_keys") || config.getJSONObject("api_keys").length() == 0) {
                // 创建默认API Keys
                JSONObject apiKeys = new JSONObject();
                
                // 添加常用API服务的默认空Key
                apiKeys.put("https://api.deepseek.com", "");
                apiKeys.put("https://api.moonshot.cn/v1", "");
                apiKeys.put("https://dashscope.aliyuncs.com/compatible-mode/v1", "");
                apiKeys.put("https://ark.cn-beijing.volces.com/api/v3", "");
                
                config.put("api_keys", apiKeys);
                
                // 保存配置
                saveConfig(context, config);
                LogManager.logD(TAG, "已初始化默认API Keys");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "初始化默认API Keys失败", e);
        }
    }
    
    /**
     * 获取所有API Keys
     * @param context 上下文
     * @return API Keys映射
     */
    public static Map<String, String> getAllApiKeys(Context context) {
        Map<String, String> apiKeys = new HashMap<>();
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            if (config.has("api_keys")) {
                JSONObject apiKeysJson = config.getJSONObject("api_keys");
                Iterator<String> keys = apiKeysJson.keys();
                while (keys.hasNext()) {
                    String apiUrl = keys.next();
                    String apiKey = apiKeysJson.getString(apiUrl);
                    apiKeys.put(apiUrl, apiKey);
                }
            }
            
            LogManager.logD(TAG, "获取所有API Keys: " + apiKeys.size() + "个");
            return apiKeys;
        } catch (JSONException e) {
            LogManager.logE(TAG, "获取所有API Keys失败", e);
            return apiKeys;
        }
    }

    /**
     * 获取所有API URLs
     * @param context 上下文
     * @return API URLs数组
     */
    public static String[] getApiUrls(Context context) {
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            // 获取API Keys
            JSONObject apiKeys = config.getJSONObject("api_keys");
            
            // 将API Keys的键转换为数组
            List<String> apiUrlsList = new ArrayList<>();
            Iterator<String> keys = apiKeys.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                apiUrlsList.add(key);
            }
            
            // 转换为数组
            String[] apiUrls = new String[apiUrlsList.size()];
            apiUrlsList.toArray(apiUrls);
            
            LogManager.logD(TAG, "获取所有API URLs: " + apiUrlsList.size() + "个");
            return apiUrls;
        } catch (JSONException e) {
            LogManager.logE(TAG, "获取API URLs失败", e);
            return new String[0];
        }
    }
    
    /**
     * 添加新的API URL
     * @param context 上下文
     * @param apiUrl API URL
     * @param apiKey API Key
     */
    public static void addApiUrl(Context context, String apiUrl, String apiKey) {
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            // 获取API Keys
            JSONObject apiKeys = config.getJSONObject("api_keys");
            
            // 添加新的API URL和Key
            apiKeys.put(apiUrl, apiKey);
            
            // 保存配置
            saveConfig(context, config);
            
            LogManager.logD(TAG, "添加新的API URL: " + apiUrl);
        } catch (JSONException e) {
            LogManager.logE(TAG, "添加API URL失败", e);
        }
    }

    /**
     * 删除API URL
     * @param context 上下文
     * @param apiUrl 要删除的API URL
     */
    public static void removeApiUrl(Context context, String apiUrl) {
        try {
            // 加载配置
            JSONObject config = loadConfig(context);
            
            // 获取API Keys
            JSONObject apiKeys = config.getJSONObject("api_keys");
            
            // 删除API URL
            if (apiKeys.has(apiUrl)) {
                apiKeys.remove(apiUrl);
                LogManager.logD(TAG, "删除API URL: " + apiUrl);
                
                // 保存配置
                saveConfig(context, config);
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, "删除API URL失败: " + apiUrl, e);
        }
    }

    /**
     * 获取浮点数配置
     * @param context 上下文
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static float getFloat(Context context, String key, float defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                return (float)config.getDouble(key);
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, "获取浮点数配置失败: " + key, e);
        }
        return defaultValue;
    }

    /**
     * 设置浮点数配置
     * @param context 上下文
     * @param key 配置键
     * @param value 配置值
     */
    public static void setFloat(Context context, String key, float value) {
        try {
            JSONObject config = loadConfig(context);
            config.put(key, value);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, "设置浮点数配置失败: " + key, e);
        }
    }

    /**
     * 设置模型映射配置
     * @param context 上下文
     * @param key 配置键
     * @param value 配置值
     */
    public static void setModelMapping(Context context, String key, String value) {
        try {
            JSONObject config = loadConfig(context);
            config.put(key, value);
            saveConfig(context, config);
            LogManager.logD(TAG, "设置模型映射配置: " + key + " = " + value);
        } catch (Exception e) {
            LogManager.logE(TAG, "设置模型映射配置失败: " + key, e);
        }
    }
    
    /**
     * 获取模型映射配置
     * @param context 上下文
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static String getModelMapping(Context context, String key, String defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                String value = config.getString(key);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return defaultValue;
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_get_model_mapping_failed) + ": " + key, e);
            return defaultValue;
        }
    }

    /**
     * 获取是否启用JSON训练集分块优化
     * @param context 上下文
     * @return 是否启用JSON训练集分块优化
     */
    public static boolean isJsonDatasetSplittingEnabled(Context context) {
        return getBoolean(context, KEY_ENABLE_JSON_DATASET_SPLITTING, true);
    }

    /**
     * 设置是否启用JSON训练集分块优化
     * @param context 上下文
     * @param enabled 是否启用
     */
    public static void setJsonDatasetSplittingEnabled(Context context, boolean enabled) {
        setBoolean(context, KEY_ENABLE_JSON_DATASET_SPLITTING, enabled);
    }

    /**
     * 获取全局字体大小
     * @param context 上下文
     * @return 全局字体大小
     */
    public static float getGlobalTextSize(Context context) {
        return getFloat(context, KEY_GLOBAL_TEXT_SIZE, DEFAULT_TEXT_SIZE);
    }

    /**
     * 设置全局字体大小
     * @param context 上下文
     * @param size 字体大小
     */
    public static void setGlobalTextSize(Context context, float size) {
        setFloat(context, KEY_GLOBAL_TEXT_SIZE, size);
    }

    /**
     * 获取最小分块限制
     * @param context 上下文
     * @return 最小分块限制
     */
    public static int getMinChunkSize(Context context) {
        return getInt(context, KEY_MIN_CHUNK_SIZE, DEFAULT_MIN_CHUNK_SIZE);
    }

    /**
     * 设置最小分块限制
     * @param context 上下文
     * @param minChunkSize 最小分块限制
     */
    public static void setMinChunkSize(Context context, int minChunkSize) {
        setInt(context, KEY_MIN_CHUNK_SIZE, minChunkSize);
    }

    /**
     * 获取LlamaCpp批处理大小
     * @param context 上下文
     * @return 批处理大小
     */
    public static int getLlamaCppBatchSize(Context context) {
        return getInt(context, KEY_LLAMACPP_BATCH_SIZE, DEFAULT_LLAMACPP_BATCH_SIZE);
    }

    /**
     * 设置LlamaCpp批处理大小
     * @param context 上下文
     * @param batchSize 批处理大小
     */
    public static void setLlamaCppBatchSize(Context context, int batchSize) {
        setInt(context, KEY_LLAMACPP_BATCH_SIZE, batchSize);
    }

    /**
     * 获取手动温度参数
     * @param context 上下文
     * @return 手动温度参数
     */
    public static float getManualTemperature(Context context) {
        return getFloat(context, KEY_MANUAL_TEMPERATURE, DEFAULT_MANUAL_TEMPERATURE);
    }

    /**
     * 设置手动温度参数
     * @param context 上下文
     * @param temperature 温度参数
     */
    public static void setManualTemperature(Context context, float temperature) {
        setFloat(context, KEY_MANUAL_TEMPERATURE, temperature);
    }

    /**
     * 获取手动Top-P参数
     * @param context 上下文
     * @return 手动Top-P参数
     */
    public static float getManualTopP(Context context) {
        return getFloat(context, KEY_MANUAL_TOP_P, DEFAULT_MANUAL_TOP_P);
    }

    /**
     * 设置手动Top-P参数
     * @param context 上下文
     * @param topP Top-P参数
     */
    public static void setManualTopP(Context context, float topP) {
        setFloat(context, KEY_MANUAL_TOP_P, topP);
    }

    /**
     * 获取手动Top-K参数
     * @param context 上下文
     * @return 手动Top-K参数
     */
    public static int getManualTopK(Context context) {
        return getInt(context, KEY_MANUAL_TOP_K, DEFAULT_MANUAL_TOP_K);
    }

    /**
     * 设置手动Top-K参数
     * @param context 上下文
     * @param topK Top-K参数
     */
    public static void setManualTopK(Context context, int topK) {
        setInt(context, KEY_MANUAL_TOP_K, topK);
    }

    /**
     * 获取手动重复惩罚参数
     * @param context 上下文
     * @return 手动重复惩罚参数
     */
    public static float getManualRepeatPenalty(Context context) {
        return getFloat(context, KEY_MANUAL_REPEAT_PENALTY, DEFAULT_MANUAL_REPEAT_PENALTY);
    }

    /**
     * 设置手动重复惩罚参数
     * @param context 上下文
     * @param repeatPenalty 重复惩罚参数
     */
    public static void setManualRepeatPenalty(Context context, float repeatPenalty) {
        setFloat(context, KEY_MANUAL_REPEAT_PENALTY, repeatPenalty);
    }

    /**
     * 获取优先手动参数开关状态
     * @param context 上下文
     * @return 优先手动参数开关状态
     */
    public static boolean getPriorityManualParams(Context context) {
        return getBoolean(context, KEY_PRIORITY_MANUAL_PARAMS, false);
    }

    /**
     * 设置优先手动参数开关状态
     * @param context 上下文
     * @param priorityManualParams 优先手动参数开关状态
     */
    public static void setPriorityManualParams(Context context, boolean priorityManualParams) {
        setBoolean(context, KEY_PRIORITY_MANUAL_PARAMS, priorityManualParams);
    }

    /**
     * 获取LlamaCpp上下文大小
     * @param context 上下文
     * @return 上下文大小
     */
    public static int getLlamaCppContextSize(Context context) {
        return getInt(context, KEY_LLAMACPP_CONTEXT_SIZE, DEFAULT_LLAMACPP_CONTEXT_SIZE);
    }

    /**
     * 设置LlamaCpp上下文大小
     * @param context 上下文
     * @param contextSize 上下文大小
     */
    public static void setLlamaCppContextSize(Context context, int contextSize) {
        setInt(context, KEY_LLAMACPP_CONTEXT_SIZE, contextSize);
    }

    /**
     * 检查是否启用调试模式
     * @param context 上下文
     * @return 是否启用调试模式
     */
    public static boolean isDebugMode(Context context) {
        SharedPreferences prefs = getSharedPreferences(context);
        return prefs.getBoolean(KEY_DEBUG_MODE, false);
    }

    /**
     * 创建默认配置
     * @return 默认配置
     */
    private static JSONObject createDefaultConfig() {
        try {
            JSONObject config = new JSONObject();
            
            // 基本路径设置 - 使用绝对路径而非相对路径
            config.put(KEY_MODEL_PATH, DEFAULT_MODEL_PATH);
            config.put(KEY_EMBEDDING_MODEL_PATH, DEFAULT_EMBEDDING_MODEL_PATH);
            config.put(KEY_RERANKER_MODEL_PATH, DEFAULT_RERANKER_MODEL_PATH);
            config.put(KEY_KNOWLEDGE_BASE_PATH, DEFAULT_KNOWLEDGE_BASE_PATH);
            
            // 分块设置
            config.put(KEY_CHUNK_SIZE, 1000);
            config.put(KEY_OVERLAP_SIZE, 200);
            config.put(KEY_MIN_CHUNK_SIZE, 200); // 修改为200，与PC端保持一致
            
            // 搜索设置
            config.put(KEY_SEARCH_DEPTH, 10);
            config.put(KEY_RETRIEVAL_COUNT, 20);
            
            // 调试设置
            config.put(KEY_DEBUG_MODE, false); // 默认关闭调试模式
            config.put(KEY_USE_GPU, false); // 默认不使用GPU加速
            // ONNX引擎默认配置已移除
            
            // API设置
            config.put(KEY_API_URL, AppConstants.ApiUrl.LOCAL);
            config.put(KEY_MODEL_NAME, "deepseek-chat");
            config.put(KEY_KNOWLEDGE_BASE, "默认知识库");
            
            // API Keys
            JSONObject apiKeys = new JSONObject();
            
            // 添加常用API服务的默认空Key
            apiKeys.put("https://api.deepseek.com", "");
            apiKeys.put("https://api.moonshot.cn/v1", "");
            apiKeys.put("https://dashscope.aliyuncs.com/compatible-mode/v1", "");
            apiKeys.put("https://ark.cn-beijing.volces.com/api/v3", "");
            
            config.put("api_keys", apiKeys);
            
            // 系统提示词
            config.put(KEY_SYSTEM_PROMPT, "根据检索内容回答，");
            
            // 文本大小相关配置
            config.put(KEY_GLOBAL_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            config.put(KEY_RAG_RESPONSE_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            config.put(KEY_BUILD_SELECTED_FILES_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            config.put(KEY_BUILD_PROGRESS_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            config.put(KEY_NOTE_CONTENT_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            config.put(KEY_LOG_CONTENT_TEXT_SIZE, DEFAULT_TEXT_SIZE);
            
            // LlamaCpp 相关配置
            config.put(KEY_LLAMACPP_MODEL_PATH, DEFAULT_LLAMACPP_MODEL_PATH);
            config.put(KEY_LLAMACPP_CONTEXT_SIZE, DEFAULT_LLAMACPP_CONTEXT_SIZE);
            config.put(KEY_LLAMACPP_BATCH_SIZE, DEFAULT_LLAMACPP_BATCH_SIZE);
            config.put(KEY_LLAMACPP_THREADS, DEFAULT_LLAMACPP_THREADS);
            config.put(KEY_LLAMACPP_GPU_LAYERS, DEFAULT_LLAMACPP_GPU_LAYERS);
            config.put(KEY_LLAMACPP_TEMPERATURE, DEFAULT_LLAMACPP_TEMPERATURE);
            config.put(KEY_LLAMACPP_TOP_P, DEFAULT_LLAMACPP_TOP_P);
            config.put(KEY_LLAMACPP_TOP_K, DEFAULT_LLAMACPP_TOP_K);
            config.put(KEY_LLAMACPP_REPEAT_PENALTY, DEFAULT_LLAMACPP_REPEAT_PENALTY);
            config.put(KEY_LLAMACPP_REPEAT_LAST_N, DEFAULT_LLAMACPP_REPEAT_LAST_N);
            config.put(KEY_LLAMACPP_SEED, DEFAULT_LLAMACPP_SEED);
            config.put(KEY_LLAMACPP_USE_MMAP, DEFAULT_LLAMACPP_USE_MMAP);
            config.put(KEY_LLAMACPP_USE_MLOCK, DEFAULT_LLAMACPP_USE_MLOCK);
            config.put(KEY_LLAMACPP_NORMALIZE_EMBEDDINGS, DEFAULT_LLAMACPP_NORMALIZE_EMBEDDINGS);
            config.put(KEY_LLAMACPP_EMBEDDING_BATCH_SIZE, DEFAULT_LLAMACPP_EMBEDDING_BATCH_SIZE);
            config.put(KEY_USE_LLAMACPP, DEFAULT_USE_LLAMACPP);
            
            // 手动推理参数配置
            config.put(KEY_MANUAL_TEMPERATURE, DEFAULT_MANUAL_TEMPERATURE);
            config.put(KEY_MANUAL_TOP_P, DEFAULT_MANUAL_TOP_P);
            config.put(KEY_MANUAL_TOP_K, DEFAULT_MANUAL_TOP_K);
            config.put(KEY_MANUAL_REPEAT_PENALTY, DEFAULT_MANUAL_REPEAT_PENALTY);
            
            // 语言设置
            config.put(KEY_LANGUAGE, DEFAULT_LANGUAGE);
            
            Log.d(TAG, "创建默认配置: " + config.toString(2));
            return config;
        } catch (JSONException e) {
            Log.e(TAG, "创建默认配置失败", e);
            return new JSONObject();
        }
    }
    
    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE);
    }
}
