package com.example.offlineai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.content.res.AssetManager;

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
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.offlineai.FileUtil;
// Removed: import com.example.offlineai.SQLiteVectorDatabaseHandler; - Now using KnowledgeGraphDatabase directly

/**
 * 配置管理器，用于保存和读取应用程序配置
 * 配置保存在应用程序目录下的.config文件中
 */
@SuppressWarnings("deprecation")
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
    
    // Knowledge Graph RAG 相关的键
    public static final String KEY_GRAPH_CUSTOM_DICT_PATH = "graph_custom_dict_path"; // 图谱外挂词典路径
    public static final String KEY_GRAPH_MIN_EDGE_WEIGHT = "graph_min_edge_weight"; // 图扩展最小边权重
    public static final String KEY_GRAPH_MAX_EXPAND_ENTITIES = "graph_max_expand_entities"; // 图扩展最大实体数
    public static final String KEY_GRAPH_ENTITY_CONFIDENCE_THRESHOLD = "graph_entity_confidence_threshold"; // 实体置信度阈值
    public static final String KEY_GRAPH_RAG_ENABLED = "graph_rag_enabled";
    public static final String KEY_GRAPH_RAG_WEIGHT_PRESET = "graph_rag_weight_preset";
    public static final String KEY_GRAPH_MAX_EXPAND_CHUNKS = "graph_max_expand_chunks";
    // 旧版单一 Hub 阈值（仅作兼容回退，推荐使用 BUILD/QUERY 两个新键）
    public static final String KEY_GRAPH_HUB_THRESHOLD = "graph_hub_threshold"; // Legacy super-entity hub threshold (0=disabled)
    // 新版：构建期 / 召回期分别配置的超大实体门限
    public static final String KEY_GRAPH_HUB_THRESHOLD_BUILD = "graph_hub_threshold_build";   // 构建阶段 Hub 过滤阈值
    public static final String KEY_GRAPH_HUB_THRESHOLD_QUERY = "graph_hub_threshold_query";   // 查询阶段 Hub 过滤阈值
    public static final String KEY_GRAPH_STOPWORDS_PATH = "graph_stopwords_path"; // 图谱停用词表路径
    
    // 设置相关的键
    public static final String KEY_DATA_ROOT_PATH = "data_root_path"; // 数据根目录
    public static final String KEY_CURRENT_CHAT_FOLDER = "current_chat_folder"; // 当前对话文件夹路径
    public static final String KEY_SEARCH_DEPTH = "search_depth";
    public static final String KEY_RERANK_COUNT = "rerank_count";
    public static final String KEY_RETRIEVAL_COUNT = "retrieval_count";
    public static final String KEY_DEBUG_MODE = "debug_mode"; // 调试模式配置键
    public static final String KEY_USE_GPU = "use_gpu"; // GPU加速配置键
    
    // LLM 推理相关的键
    public static final String KEY_MAX_SEQUENCE_LENGTH = "maxSequenceLength"; // 最大序列长度
    public static final String KEY_NO_THINKING = "no_thinking"; // 是否禁用思考模式
    public static final String KEY_THREADS = "threads"; // ONNX推理线程数
    public static final String KEY_EMBEDDING_CONCURRENCY = "embedding_concurrency"; // Embedding session concurrency for knowledge base building
    public static final String KEY_EMBEDDING_THREADS = "embedding_threads"; // MNN threads per embedding session for knowledge base building
    // KEY_IMAGE_ENCODING_THREADS已移除（MNN不支持独立配置）
    public static final String KEY_MAX_NEW_TOKENS = "max_new_tokens"; // 最大输出token数
    public static final String KEY_HISTORY_ROUNDS = "history_rounds"; // 对话历史轮数（滑窗机制，0-20）
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
    public static final String KEY_IMAGE_PREPROCESS_SIZE = "image_preprocess_size"; // 图片预处理尺寸
    
    // Diffusion扩散模型配置键
    public static final String KEY_DIFFUSION_MEMORY_MODE = "diffusion_memory_mode"; // 内存模式 (0=low, 1=enough, 2=balance)
    public static final String KEY_DIFFUSION_STEPS = "diffusion_steps"; // 推理步数 (1-50)
    public static final String KEY_DIFFUSION_SEED = "diffusion_seed"; // 随机种子 (-1=随机)
    public static final String KEY_DIFFUSION_SEED_RANDOM = "diffusion_seed_random"; // 是否使用随机种子
    
    // ASR语音识别配置键
    public static final String KEY_ASR_MODEL = "asr_model"; // ASR模型选择
    
    // TTS语音合成配置键
    public static final String KEY_TTS_MODEL = "tts_model"; // TTS模型选择
    public static final String KEY_TTS_DIT_STEPS = "tts_dit_steps"; // DiT步数 (1-10)
    public static final String KEY_TTS_AUTO_PLAY = "tts_auto_play"; // TTS自动播放
    public static final String KEY_TTS_SPEAKER_ID = "tts_speaker_id"; // TTS角色ID (0-9)
    public static final String KEY_TTS_SPEED = "tts_speed"; // TTS语速 (0.5-2.0)
    public static final String KEY_TTS_PITCH = "tts_pitch"; // TTS音调 (0.5-2.0)
    
    // 语言设置配置键
    public static final String KEY_LANGUAGE = "language"; // 语言设置
    
    // 全局设置相关的键
    public static final String KEY_SHOW_DEBUG_PERFORMANCE = "show_debug_performance"; // 对话显示调试和性能
    
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
    public static final String DEFAULT_DATA_ROOT_PATH = "/storage/emulated/0/Download/OfflineAIData";
    public static final int DEFAULT_SEARCH_DEPTH = 20;
    public static final int DEFAULT_RERANK_COUNT = 5;
    
    // Knowledge Graph RAG 默认值
    public static final int DEFAULT_GRAPH_MIN_EDGE_WEIGHT = 2; // 最小边权重：2（过滤低频共现）
    public static final int DEFAULT_GRAPH_MAX_EXPAND_ENTITIES = 50; // 最大扩展实体数：50
    public static final float DEFAULT_GRAPH_ENTITY_CONFIDENCE_THRESHOLD = 0.7f; // 实体置信度阈值：0.7
    public static final boolean DEFAULT_GRAPH_RAG_ENABLED = true;
    public static final int DEFAULT_GRAPH_MAX_EXPAND_CHUNKS = 50;
    public static final int DEFAULT_GRAPH_RAG_WEIGHT_PRESET = 1;
    // 旧版单一 Hub 阈值默认值（仅用于向后兼容，不再直接展示在设置界面）
    public static final int DEFAULT_GRAPH_HUB_THRESHOLD = 100; // Legacy hub threshold (0=disabled)
    // 新版：构建/召回独立默认值
    public static final int DEFAULT_GRAPH_HUB_THRESHOLD_BUILD = 1000; // 超大实体门限（构建，0=关闭）
    public static final int DEFAULT_GRAPH_HUB_THRESHOLD_QUERY = 300;  // 超大实体门限（召回，0=关闭）
    
    // 硬编码的子目录名称
    private static final String SUBDIR_MODELS = "models";
    private static final String SUBDIR_EMBEDDINGS = "embeddings";
    private static final String SUBDIR_RERANKERS = "rerankers";
    private static final String SUBDIR_ASR = "asr";
    private static final String SUBDIR_TTS = "tts";
    private static final String SUBDIR_KNOWLEDGE_BASES = "knowledge_bases";
    private static final String SUBDIR_STOPWORDS = "stopwords";
    private static final String SUBDIR_CHAT_HISTORY = "chathistory";

    public static final float DEFAULT_TEXT_SIZE = 14f;
    
    // LLM 推理相关的默认值
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 4096;
    public static final boolean DEFAULT_NO_THINKING = false;
    public static final int DEFAULT_THREADS = 4;
    public static final int DEFAULT_EMBEDDING_CONCURRENCY = 2; // Parallel embedding sessions (KB build), clamped to 1-4
    public static final int DEFAULT_EMBEDDING_THREADS = 1; // MNN threads per embedding session (KB build), range 1-4
    // DEFAULT_IMAGE_ENCODING_THREADS已移除（MNN不支持独立配置）
    public static final int DEFAULT_MAX_NEW_TOKENS = 512; // 最大输出token数默认值
    
    // Diffusion扩散模型默认值
    public static final int DEFAULT_DIFFUSION_MEMORY_MODE = 0; // 0=low (省内存)
    public static final int DEFAULT_DIFFUSION_STEPS = 20; // 默认20步（平衡质量和速度）
    public static final int DEFAULT_DIFFUSION_SEED = -1; // -1表示随机
    public static final boolean DEFAULT_DIFFUSION_SEED_RANDOM = true; // 默认使用随机种子
    
    // ASR语音识别默认值
    public static final String DEFAULT_ASR_MODEL = "无"; // 默认无（不使用ASR）
    
    // TTS语音合成默认值
    public static final String DEFAULT_TTS_MODEL = "无"; // 默认无（不使用外挂TTS）
    public static final int DEFAULT_TTS_DIT_STEPS = 3; // 默认3步（平衡质量和速度）
    public static final boolean DEFAULT_TTS_AUTO_PLAY = false; // 默认不自动播放
    
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
    
    // Image preprocessing size presets (all multiples of 28 for VL models)
    public static final int IMAGE_SIZE_MIN = 112;      // 28×4, ~16 tokens
    public static final int IMAGE_SIZE_SMALL = 280;    // 28×10, ~100 tokens
    public static final int IMAGE_SIZE_MEDIUM = 392;   // 28×14, ~196 tokens
    public static final int IMAGE_SIZE_DEFAULT = 504;  // 28×18, ~324 tokens (recommended)
    public static final int IMAGE_SIZE_LARGE = 672;    // 28×24, ~576 tokens
    public static final int IMAGE_SIZE_XLARGE = 896;   // 28×32, ~1024 tokens
    public static final int IMAGE_SIZE_MAX_RESIZE = 1008; // 28×36, ~1296 tokens
    public static final int IMAGE_SIZE_ORIGINAL = 0;   // No resize (MAX mode)
    
    // 手动推理参数默认值
    public static final float DEFAULT_MANUAL_TEMPERATURE = 0.8f;
    public static final float DEFAULT_MANUAL_TOP_P = 0.95f;
    public static final int DEFAULT_MANUAL_TOP_K = 40;
    public static final float DEFAULT_MANUAL_REPEAT_PENALTY = 1.1f;
    public static final int DEFAULT_IMAGE_PREPROCESS_SIZE = IMAGE_SIZE_ORIGINAL; // 图片预处理尺寸默认值（0=MAX模式，让MNN自己处理）
    public static final int DEFAULT_HISTORY_ROUNDS = 5; // 默认保留5轮对话历史
    public static final boolean DEFAULT_DEBUG_MODE = false; // 默认关闭调试模式
    public static final boolean DEFAULT_PRIORITY_MANUAL_PARAMS = false; // 默认不优先使用手动参数
    
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
                KEY_DATA_ROOT_PATH,
                KEY_CHUNK_SIZE, KEY_OVERLAP_SIZE, KEY_SEARCH_DEPTH,
                KEY_API_URL, KEY_MODEL_NAME, KEY_KNOWLEDGE_BASE
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
                KEY_DATA_ROOT_PATH,
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
        String knowledgeBasePath = getKnowledgeBasePath(context);
        File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBaseName);
        
        LogManager.logD(TAG, getLogString(context, R.string.config_try_read_metadata) + ": " + knowledgeBaseName);
        
        if (!knowledgeBaseDir.exists()) {
            LogManager.logE(TAG, getLogString(context, R.string.config_kb_dir_not_exist) + ": " + knowledgeBaseDir.getAbsolutePath());
            return null;
        }
        
        // 首先尝试从SQLite数据库中读取嵌入模型信息（统一使用 knowledge_graph.db）
        File sqliteDbFile = new File(knowledgeBaseDir, "knowledge_graph.db");
        if (sqliteDbFile.exists()) {
            LogManager.logD(TAG, getLogString(context, LOG_FOUND_SQLITE_DB));
            KnowledgeGraphDatabase vectorDb = null;
            try {
                String dbPath = sqliteDbFile.getAbsolutePath();
                vectorDb = new KnowledgeGraphDatabase(context, dbPath, "unknown");
                // KnowledgeGraphDatabase is auto-loaded on construction
                {
                    KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
                    if (metadata != null) {
                        String embeddingModel = metadata.getModeldir();
                        LogManager.logD(TAG, LOG_READ_EMBEDDING_FROM_SQLITE + ": " + embeddingModel);
                        
                        if (embeddingModel != null && !embeddingModel.isEmpty()) {
                            // 获取设置中的嵌入模型路径
                            String embeddingModelPath = getEmbeddingModelPath(context);
                            
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
                            String embeddingModelPath = getEmbeddingModelPath(context);
                            
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
                
                // 如果没有找到包含"embedding"的文件，尝试查找MNN模型文件
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (file.isFile() && (name.endsWith(".mnn") || name.equals("config.json"))) {
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
    
    // ========== Knowledge Graph RAG Settings ==========
    
    /**
     * Get graph expansion minimum edge weight
     * @param context Context
     * @return Minimum edge weight (1-10)
     */
    public static int getGraphMinEdgeWeight(Context context) {
        return getInt(context, KEY_GRAPH_MIN_EDGE_WEIGHT, DEFAULT_GRAPH_MIN_EDGE_WEIGHT);
    }
    
    /**
     * Set graph expansion minimum edge weight
     * @param context Context
     * @param weight Minimum edge weight (1-10)
     */
    public static void setGraphMinEdgeWeight(Context context, int weight) {
        setInt(context, KEY_GRAPH_MIN_EDGE_WEIGHT, weight);
    }
    
    /**
     * Get graph expansion maximum entities
     * @param context Context
     * @return Maximum number of entities (10-100)
     */
    public static int getGraphMaxExpandEntities(Context context) {
        return getInt(context, KEY_GRAPH_MAX_EXPAND_ENTITIES, DEFAULT_GRAPH_MAX_EXPAND_ENTITIES);
    }
    
    /**
     * Set graph expansion maximum entities
     * @param context Context
     * @param maxEntities Maximum number of entities (10-100)
     */
    public static void setGraphMaxExpandEntities(Context context, int maxEntities) {
        setInt(context, KEY_GRAPH_MAX_EXPAND_ENTITIES, maxEntities);
    }
    
    /**
     * Get entity confidence threshold
     * @param context Context
     * @return Confidence threshold (0.5-1.0)
     */
    public static float getGraphEntityConfidenceThreshold(Context context) {
        return getFloat(context, KEY_GRAPH_ENTITY_CONFIDENCE_THRESHOLD, DEFAULT_GRAPH_ENTITY_CONFIDENCE_THRESHOLD);
    }
    
    /**
     * Set entity confidence threshold
     * @param context Context
     * @param threshold Confidence threshold (0.5-1.0)
     */
    public static void setGraphEntityConfidenceThreshold(Context context, float threshold) {
        setFloat(context, KEY_GRAPH_ENTITY_CONFIDENCE_THRESHOLD, threshold);
    }

    /**
     * Get maximum number of chunks allowed from graph expansion
     * @param context Context
     * @return Maximum number of chunks
     */
    public static int getGraphMaxExpandChunks(Context context) {
        return getInt(context, KEY_GRAPH_MAX_EXPAND_CHUNKS, DEFAULT_GRAPH_MAX_EXPAND_CHUNKS);
    }

    /**
     * Set maximum number of chunks allowed from graph expansion
     * @param context Context
     * @param maxChunks Maximum number of chunks
     */
    public static void setGraphMaxExpandChunks(Context context, int maxChunks) {
        setInt(context, KEY_GRAPH_MAX_EXPAND_CHUNKS, maxChunks);
    }

    /**
     * Get Graph RAG fusion weight preset index
     * @param context Context
     * @return Preset index (0-based)
     */
    public static int getGraphRagWeightPreset(Context context) {
        return getInt(context, KEY_GRAPH_RAG_WEIGHT_PRESET, DEFAULT_GRAPH_RAG_WEIGHT_PRESET);
    }

    /**
     * Set Graph RAG fusion weight preset index
     * @param context Context
     * @param preset Preset index (0-based)
     */
    public static void setGraphRagWeightPreset(Context context, int preset) {
        setInt(context, KEY_GRAPH_RAG_WEIGHT_PRESET, preset);
    }

    /**
     * Get whether Graph RAG mode is enabled for RAG QA
     * @param context Context
     * @return true if Graph RAG mode is enabled
     */
    public static boolean isGraphRagEnabled(Context context) {
        return getBoolean(context, KEY_GRAPH_RAG_ENABLED, DEFAULT_GRAPH_RAG_ENABLED);
    }

    /**
     * Set whether Graph RAG mode is enabled for RAG QA
     * @param context Context
     * @param enabled Whether Graph RAG mode is enabled
     */
    public static void setGraphRagEnabled(Context context, boolean enabled) {
        setBoolean(context, KEY_GRAPH_RAG_ENABLED, enabled);
    }

    /**
     * Legacy getter for super-entity (hub) threshold.
     * For backward compatibility this now returns the build-time
     * hub threshold. New code should prefer getGraphHubThresholdBuild
     * or getGraphHubThresholdQuery explicitly.
     *
     * @param context Context
     * @return Hub threshold used during graph building (0 = disabled)
     */
    public static int getGraphHubThreshold(Context context) {
        return getGraphHubThresholdBuild(context);
    }

    /**
     * Get super-entity (hub) threshold for graph building phase.
     * Resolution order:
     * 1. KEY_GRAPH_HUB_THRESHOLD_BUILD (new)
     * 2. KEY_GRAPH_HUB_THRESHOLD (legacy single threshold)
     * 3. DEFAULT_GRAPH_HUB_THRESHOLD_BUILD
     */
    public static int getGraphHubThresholdBuild(Context context) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(KEY_GRAPH_HUB_THRESHOLD_BUILD)) {
                return config.getInt(KEY_GRAPH_HUB_THRESHOLD_BUILD);
            }
            if (config.has(KEY_GRAPH_HUB_THRESHOLD)) {
                return config.getInt(KEY_GRAPH_HUB_THRESHOLD);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_get_int_failed) + ": " + KEY_GRAPH_HUB_THRESHOLD_BUILD, e);
        }
        return DEFAULT_GRAPH_HUB_THRESHOLD_BUILD;
    }

    /**
     * Set super-entity (hub) threshold for graph building phase.
     * @param context Context
     * @param threshold Hub threshold based on neighbor count (0 = disabled)
     */
    public static void setGraphHubThresholdBuild(Context context, int threshold) {
        setInt(context, KEY_GRAPH_HUB_THRESHOLD_BUILD, threshold);
    }

    /**
     * Get super-entity (hub) threshold for query-time Graph RAG filtering.
     * Resolution order:
     * 1. KEY_GRAPH_HUB_THRESHOLD_QUERY (new)
     * 2. KEY_GRAPH_HUB_THRESHOLD (legacy single threshold)
     * 3. DEFAULT_GRAPH_HUB_THRESHOLD_QUERY
     */
    public static int getGraphHubThresholdQuery(Context context) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(KEY_GRAPH_HUB_THRESHOLD_QUERY)) {
                return config.getInt(KEY_GRAPH_HUB_THRESHOLD_QUERY);
            }
            if (config.has(KEY_GRAPH_HUB_THRESHOLD)) {
                return config.getInt(KEY_GRAPH_HUB_THRESHOLD);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, getLogString(context, R.string.config_get_int_failed) + ": " + KEY_GRAPH_HUB_THRESHOLD_QUERY, e);
        }
        return DEFAULT_GRAPH_HUB_THRESHOLD_QUERY;
    }

    /**
     * Set super-entity (hub) threshold for query-time Graph RAG filtering.
     * @param context Context
     * @param threshold Hub threshold based on neighbor count (0 = disabled)
     */
    public static void setGraphHubThresholdQuery(Context context, int threshold) {
        setInt(context, KEY_GRAPH_HUB_THRESHOLD_QUERY, threshold);
    }

    /**
     * Get graph stopwords file path
     * @param context Context
     * @return Absolute path to selected stopwords JSON file, or empty string if none
     */
    public static String getGraphStopwordsPath(Context context) {
        return getString(context, KEY_GRAPH_STOPWORDS_PATH, "");
    }

    /**
     * Set graph stopwords file path
     * @param context Context
     * @param path Absolute path to stopwords JSON file, or empty/NULL for none
     */
    public static void setGraphStopwordsPath(Context context, String path) {
        setString(context, KEY_GRAPH_STOPWORDS_PATH, path == null ? "" : path);
    }

    /**
     * 获取数据根目录
     * @param context 上下文
     * @return 数据根目录路径
     */
    public static String getDataRootPath(Context context) {
        return getString(context, KEY_DATA_ROOT_PATH, DEFAULT_DATA_ROOT_PATH);
    }

    /**
     * 设置数据根目录
     * @param context 上下文
     * @param dataRootPath 数据根目录路径
     */
    public static void setDataRootPath(Context context, String dataRootPath) {
        setString(context, KEY_DATA_ROOT_PATH, dataRootPath);
    }

    /**
     * 获取模型路径（根目录 + models）
     * @param context 上下文
     * @return 模型路径
     */
    public static String getModelPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_MODELS).getAbsolutePath();
    }

    /**
     * 获取嵌入模型路径（根目录 + embeddings）
     * @param context 上下文
     * @return 嵌入模型路径
     */
    public static String getEmbeddingModelPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_EMBEDDINGS).getAbsolutePath();
    }

    /**
     * 获取重排模型路径（根目录 + rerankers）
     * @param context 上下文
     * @return 重排模型路径
     */
    public static String getRerankerModelPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_RERANKERS).getAbsolutePath();
    }

    /**
     * 获取ASR模型路径（根目录 + asr）
     * @param context 上下文
     * @return ASR模型路径
     */
    public static String getAsrModelPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_ASR).getAbsolutePath();
    }

    /**
     * 获取TTS模型路径（根目录 + tts）
     * @param context 上下文
     * @return TTS模型路径
     */
    public static String getTtsModelPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_TTS).getAbsolutePath();
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
     * 获取知识库路径（根目录 + knowledge_bases）
     * @param context 上下文
     * @return 知识库路径
     */
    public static String getKnowledgeBasePath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_KNOWLEDGE_BASES).getAbsolutePath();
    }

    /**
     * 获取停用词目录路径（根目录 + stopwords）
     * @param context 上下文
     * @return 停用词目录路径
     */
    public static String getStopwordsDirectoryPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_STOPWORDS).getAbsolutePath();
    }

    /**
     * Ensure default stopwords example JSON exists under data root / stopwords.
     * This will copy assets/example_stop.json to that directory if needed.
     */
    public static void ensureDefaultStopwordsExample(Context context) {
        try {
            String dirPath = getStopwordsDirectoryPath(context);
            File dir = new File(dirPath);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    LogManager.logW(TAG, "[STOPWORDS] Failed to create stopwords directory: " + dirPath);
                    return;
                }
            }

            File exampleFile = new File(dir, "example_stop.json");
            if (exampleFile.exists() && exampleFile.length() > 0) {
                return;
            }

            AssetManager assetManager = context.getAssets();
            try (InputStream in = assetManager.open("example_stop.json");
                 FileOutputStream out = new FileOutputStream(exampleFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            LogManager.logD(TAG, "[STOPWORDS] Copied default example_stop.json to: " + exampleFile.getAbsolutePath());
        } catch (Exception e) {
            LogManager.logE(TAG, "[STOPWORDS] Failed to ensure default stopwords example", e);
        }
    }

    /**
     * 获取对话历史路径（根目录 + chathistory）
     * @param context 上下文
     * @return 对话历史路径
     */
    public static String getChatHistoryPath(Context context) {
        return new File(getDataRootPath(context), SUBDIR_CHAT_HISTORY).getAbsolutePath();
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
     * Get embedding concurrency for knowledge base building.
     * @param context Android context
     * @return number of parallel embedding sessions
     */
    public static int getEmbeddingConcurrency(Context context) {
        int value = getInt(context, KEY_EMBEDDING_CONCURRENCY, DEFAULT_EMBEDDING_CONCURRENCY);
        if (value < 1) {
            value = 1;
        }
        if (value > 4) {
            value = 4;
        }
        return value;
    }
    
    /**
     * Set embedding concurrency for knowledge base building.
     * @param context Android context
     * @param concurrency number of parallel embedding sessions
     */
    public static void setEmbeddingConcurrency(Context context, int concurrency) {
        setInt(context, KEY_EMBEDDING_CONCURRENCY, concurrency);
    }
    
    /**
     * Get MNN threads per embedding session for knowledge base building.
     * @param context Android context
     * @return thread count per embedding session (1-4)
     */
    public static int getEmbeddingThreads(Context context) {
        int value = getInt(context, KEY_EMBEDDING_THREADS, DEFAULT_EMBEDDING_THREADS);
        if (value < 1) {
            value = 1;
        }
        if (value > 4) {
            value = 4;
        }
        return value;
    }
    
    /**
     * Set MNN threads per embedding session for knowledge base building.
     * @param context Android context
     * @param threads thread count per embedding session
     */
    public static void setEmbeddingThreads(Context context, int threads) {
        setInt(context, KEY_EMBEDDING_THREADS, threads);
    }
    
    // getImageEncodingThreads和setImageEncodingThreads方法已移除（MNN不支持独立配置）
    
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
     * 获取Diffusion内存模式
     * @param context 上下文
     * @return 内存模式整数值 (0=low, 1=enough, 2=balance)
     */
    public static int getDiffusionMemoryMode(Context context) {
        return getInt(context, KEY_DIFFUSION_MEMORY_MODE, DEFAULT_DIFFUSION_MEMORY_MODE);
    }
    
    /**
     * 设置Diffusion内存模式
     * @param context 上下文
     * @param mode 内存模式 (0=low, 1=enough, 2=balance)
     */
    public static void setDiffusionMemoryMode(Context context, int mode) {
        setInt(context, KEY_DIFFUSION_MEMORY_MODE, mode);
    }
    
    /**
     * 获取Diffusion内存模式描述文本
     * @param context 上下文
     * @return 内存模式描述（low/balance/enough）
     */
    public static String getDiffusionMemoryModeString(Context context) {
        int mode = getDiffusionMemoryMode(context);
        switch (mode) {
            case 0: return "low";
            case 1: return "enough";
            case 2: return "balance";
            default: return "low";
        }
    }
    
    /**
     * 获取Diffusion推理步数
     * @param context 上下文
     * @return 推理步数 (1-50)
     */
    public static int getDiffusionSteps(Context context) {
        return getInt(context, KEY_DIFFUSION_STEPS, DEFAULT_DIFFUSION_STEPS);
    }
    
    /**
     * 设置Diffusion推理步数
     * @param context 上下文
     * @param steps 推理步数 (1-50)
     */
    public static void setDiffusionSteps(Context context, int steps) {
        setInt(context, KEY_DIFFUSION_STEPS, steps);
    }
    
    /**
     * 获取Diffusion随机种子
     * @param context 上下文
     * @return 随机种子 (-1表示随机)
     */
    public static int getDiffusionSeed(Context context) {
        return getInt(context, KEY_DIFFUSION_SEED, DEFAULT_DIFFUSION_SEED);
    }
    
    /**
     * 设置Diffusion随机种子
     * @param context 上下文
     * @param seed 随机种子
     */
    public static void setDiffusionSeed(Context context, int seed) {
        setInt(context, KEY_DIFFUSION_SEED, seed);
    }
    
    /**
     * 获取Diffusion是否使用随机种子
     * @param context 上下文
     * @return true=使用随机种子，false=使用固定种子
     */
    public static boolean getDiffusionSeedRandom(Context context) {
        return getBoolean(context, KEY_DIFFUSION_SEED_RANDOM, DEFAULT_DIFFUSION_SEED_RANDOM);
    }
    
    /**
     * 设置Diffusion是否使用随机种子
     * @param context 上下文
     * @param random true=随机，false=固定
     */
    public static void setDiffusionSeedRandom(Context context, boolean random) {
        setBoolean(context, KEY_DIFFUSION_SEED_RANDOM, random);
    }
    
    /**
     * 获取检索数量
     * @param context 上下文
     * @return 检索数量
     */
    public static int getRetrievalCount(Context context) {
        return getInt(context, KEY_RETRIEVAL_COUNT, DEFAULT_SEARCH_DEPTH);
    }
    
    /**
     * 设置检索数量
     * @param context 上下文
     * @param count 检索数量
     */
    public static void setRetrievalCount(Context context, int count) {
        setInt(context, KEY_RETRIEVAL_COUNT, count);
    }
    
    /**
     * 获取当前对话文件夹路径
     * @param context 上下文
     * @return 当前对话文件夹路径
     */
    public static String getCurrentChatFolder(Context context) {
        return getString(context, KEY_CURRENT_CHAT_FOLDER, "");
    }
    
    /**
     * 设置当前对话文件夹路径
     * @param context 上下文
     * @param folderPath 对话文件夹路径
     */
    public static void setCurrentChatFolder(Context context, String folderPath) {
        setString(context, KEY_CURRENT_CHAT_FOLDER, folderPath);
    }
    
    /**
     * 获取语言设置
     * @param context 上下文
     * @return 语言设置 (CHN/ENG)
     */
    public static String getLanguage(Context context) {
        return getString(context, KEY_LANGUAGE, DEFAULT_LANGUAGE);
    }
    
    /**
     * 设置语言设置
     * @param context 上下文
     * @param language 语言设置 (CHN/ENG)
     */
    public static void setLanguage(Context context, String language) {
        setString(context, KEY_LANGUAGE, language);
    }
    
    /**
     * 获取最小分块大小
     * @param context 上下文
     * @return 最小分块大小
     */
    public static int getMinChunkSize(Context context) {
        return getInt(context, KEY_MIN_CHUNK_SIZE, DEFAULT_MIN_CHUNK_SIZE);
    }
    
    /**
     * 设置最小分块大小
     * @param context 上下文
     * @param minChunkSize 最小分块大小
     */
    public static void setMinChunkSize(Context context, int minChunkSize) {
        setInt(context, KEY_MIN_CHUNK_SIZE, minChunkSize);
    }
    
    /**
     * 获取历史对话轮数
     * @param context 上下文
     * @return 历史对话轮数
     */
    public static int getHistoryRounds(Context context) {
        return getInt(context, KEY_HISTORY_ROUNDS, DEFAULT_HISTORY_ROUNDS);
    }
    
    /**
     * 设置历史对话轮数
     * @param context 上下文
     * @param rounds 历史对话轮数
     */
    public static void setHistoryRounds(Context context, int rounds) {
        setInt(context, KEY_HISTORY_ROUNDS, rounds);
    }
    
    /**
     * 获取图片预处理尺寸
     * @param context 上下文
     * @return 图片预处理尺寸
     */
    public static int getImagePreprocessSize(Context context) {
        return getInt(context, KEY_IMAGE_PREPROCESS_SIZE, DEFAULT_IMAGE_PREPROCESS_SIZE);
    }
    
    /**
     * 设置图片预处理尺寸
     * @param context 上下文
     * @param size 图片预处理尺寸
     */
    public static void setImagePreprocessSize(Context context, int size) {
        setInt(context, KEY_IMAGE_PREPROCESS_SIZE, size);
    }
    
    /**
     * 获取TTS Diffusion步数
     * @param context 上下文
     * @return TTS Diffusion步数
     */
    public static int getTtsDitSteps(Context context) {
        return getInt(context, KEY_TTS_DIT_STEPS, DEFAULT_TTS_DIT_STEPS);
    }
    
    /**
     * 设置TTS Diffusion步数
     * @param context 上下文
     * @param steps TTS Diffusion步数
     */
    public static void setTtsDitSteps(Context context, int steps) {
        setInt(context, KEY_TTS_DIT_STEPS, steps);
    }
    
    /**
     * 获取TTS自动播放设置
     * @param context 上下文
     * @return 是否自动播放TTS生成的音频
     */
    public static boolean getTtsAutoPlay(Context context) {
        return getBoolean(context, KEY_TTS_AUTO_PLAY, DEFAULT_TTS_AUTO_PLAY);
    }
    
    /**
     * 设置TTS自动播放
     * @param context 上下文
     * @param autoPlay 是否自动播放
     */
    public static void setTtsAutoPlay(Context context, boolean autoPlay) {
        setBoolean(context, KEY_TTS_AUTO_PLAY, autoPlay);
    }
    
    /**
     * 获取调试模式
     * @param context 上下文
     * @return 是否启用调试模式
     */
    public static boolean getDebugMode(Context context) {
        return getBoolean(context, KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE);
    }
    
    /**
     * 设置调试模式
     * @param context 上下文
     * @param enabled 是否启用调试模式
     */
    public static void setDebugMode(Context context, boolean enabled) {
        setBoolean(context, KEY_DEBUG_MODE, enabled);
    }
    
    /**
     * 获取是否优先使用手动参数
     * @param context 上下文
     * @return 是否优先使用手动参数
     */
    public static boolean getPriorityManualParams(Context context) {
        return getBoolean(context, KEY_PRIORITY_MANUAL_PARAMS, DEFAULT_PRIORITY_MANUAL_PARAMS);
    }
    
    /**
     * 设置是否优先使用手动参数
     * @param context 上下文
     * @param priority 是否优先使用手动参数
     */
    public static void setPriorityManualParams(Context context, boolean priority) {
        setBoolean(context, KEY_PRIORITY_MANUAL_PARAMS, priority);
    }
    
    /**
     * 获取手动Top-K值
     * @param context 上下文
     * @return 手动Top-K值
     */
    public static int getManualTopK(Context context) {
        return getInt(context, KEY_MANUAL_TOP_K, DEFAULT_MANUAL_TOP_K);
    }
    
    /**
     * 设置手动Top-K值
     * @param context 上下文
     * @param topK 手动Top-K值
     */
    public static void setManualTopK(Context context, int topK) {
        setInt(context, KEY_MANUAL_TOP_K, topK);
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
     * Get float configuration value
     * @param context Context
     * @param key Configuration key
     * @param defaultValue Default value
     * @return Configuration value
     */
    public static float getFloat(Context context, String key, float defaultValue) {
        try {
            JSONObject config = loadConfig(context);
            if (config.has(key)) {
                return (float) config.getDouble(key);
            }
        } catch (JSONException e) {
            LogManager.logE(TAG, "Get float config failed: " + key, e);
        }
        return defaultValue;
    }

    /**
     * Set float configuration value
     * @param context Context
     * @param key Configuration key
     * @param value Configuration value
     */
    public static void setFloat(Context context, String key, float value) {
        try {
            JSONObject config = loadConfig(context);
            config.put(key, (double) value);
            saveConfig(context, config);
        } catch (JSONException e) {
            LogManager.logE(TAG, "Set float config failed: " + key, e);
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
                KEY_DATA_ROOT_PATH,
                KEY_CHUNK_SIZE, KEY_OVERLAP_SIZE, KEY_SEARCH_DEPTH,
                KEY_API_URL, KEY_MODEL_NAME, KEY_KNOWLEDGE_BASE
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
     * 获取是否启用JSON训练集分块优化（别名方法，保持API一致性）
     * @param context 上下文
     * @return 是否启用
     */
    public static boolean getJsonDatasetSplittingEnabled(Context context) {
        return isJsonDatasetSplittingEnabled(context);
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
     * Get show debug & performance setting
     * @param context Context
     * @return Whether to show debug & performance sections
     */
    public static boolean getShowDebugPerformance(Context context) {
        return getBoolean(context, KEY_SHOW_DEBUG_PERFORMANCE, true); // Default: true (show)
    }

    /**
     * Set show debug & performance setting
     * @param context Context
     * @param show Whether to show debug & performance sections
     */
    public static void setShowDebugPerformance(Context context, boolean show) {
        setBoolean(context, KEY_SHOW_DEBUG_PERFORMANCE, show);
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
     * 获取LlamaCpp温度参数
     * @param context 上下文
     * @return 温度参数
     */
    public static float getLlamaCppTemperature(Context context) {
        return getFloat(context, KEY_LLAMACPP_TEMPERATURE, DEFAULT_LLAMACPP_TEMPERATURE);
    }

    /**
     * 获取LlamaCpp Top-P参数
     * @param context 上下文
     * @return Top-P参数
     */
    public static float getLlamaCppTopP(Context context) {
        return getFloat(context, KEY_LLAMACPP_TOP_P, DEFAULT_LLAMACPP_TOP_P);
    }

    /**
     * 获取LlamaCpp Top-K参数
     * @param context 上下文
     * @return Top-K参数
     */
    public static int getLlamaCppTopK(Context context) {
        return getInt(context, KEY_LLAMACPP_TOP_K, DEFAULT_LLAMACPP_TOP_K);
    }

    /**
     * 获取LlamaCpp重复惩罚参数
     * @param context 上下文
     * @return 重复惩罚参数
     */
    public static float getLlamaCppRepetitionPenalty(Context context) {
        return getFloat(context, KEY_LLAMACPP_REPEAT_PENALTY, DEFAULT_LLAMACPP_REPEAT_PENALTY);
    }

    /**
     * 获取LlamaCpp随机种子
     * @param context 上下文
     * @return 随机种子（-1表示随机）
     */
    public static int getLlamaCppSeed(Context context) {
        return getInt(context, KEY_LLAMACPP_SEED, DEFAULT_LLAMACPP_SEED);
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
            
            // 基本路径设置 - 只设置根目录，子目录硬编码
            config.put(KEY_DATA_ROOT_PATH, DEFAULT_DATA_ROOT_PATH);
            
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
            config.put(KEY_IMAGE_PREPROCESS_SIZE, DEFAULT_IMAGE_PREPROCESS_SIZE);
            
            // 语言设置
            config.put(KEY_LANGUAGE, DEFAULT_LANGUAGE);
            
            // Global settings
            config.put(KEY_SHOW_DEBUG_PERFORMANCE, true); // Default: show debug & performance
            
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

    // ========== TTS Parameter Getters/Setters ==========

    /**
     * Get TTS speaker ID (0-9)
     * @param context Context
     * @return Speaker ID
     */
    public static int getTtsSpeakerId(Context context) {
        return getInt(context, KEY_TTS_SPEAKER_ID, 0);
    }

    /**
     * Set TTS speaker ID
     * @param context Context
     * @param id Speaker ID (0-9)
     */
    public static void setTtsSpeakerId(Context context, int id) {
        setInt(context, KEY_TTS_SPEAKER_ID, id);
    }

    /**
     * Get TTS speech rate (0.5-2.0)
     * @param context Context
     * @return Speech rate
     */
    public static float getTtsSpeed(Context context) {
        return getFloat(context, KEY_TTS_SPEED, 1.0f);
    }

    /**
     * Set TTS speech rate
     * @param context Context
     * @param speed Speech rate (0.5-2.0)
     */
    public static void setTtsSpeed(Context context, float speed) {
        setFloat(context, KEY_TTS_SPEED, speed);
    }

    /**
     * Get TTS pitch (0.5-2.0)
     * @param context Context
     * @return Pitch
     */
    public static float getTtsPitch(Context context) {
        return getFloat(context, KEY_TTS_PITCH, 1.0f);
    }

    /**
     * Set TTS pitch
     * @param context Context
     * @param pitch Pitch (0.5-2.0)
     */
    public static void setTtsPitch(Context context, float pitch) {
        setFloat(context, KEY_TTS_PITCH, pitch);
    }
}

