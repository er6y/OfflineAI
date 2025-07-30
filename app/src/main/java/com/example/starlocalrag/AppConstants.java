package com.example.starlocalrag;

/**
 * 应用状态常量定义类
 * 统一管理所有模块的状态常量，避免硬编码中文逻辑判断
 */
public class AppConstants {

    /**
     * 模型状态常量
     */
    public static class ModelState {
        public static final String DOWNLOADING = "downloading";
        public static final String DOWNLOADED = "downloaded";
        public static final String NOT_DOWNLOADED = "not_downloaded";
        public static final String DOWNLOAD_FAILED = "download_failed";
        public static final String LOADING = "loading";
        public static final String LOADED = "loaded";
        public static final String LOAD_FAILED = "load_failed";
        public static final String UNLOADED = "unloaded";
    }

    /**
     * 知识库状态常量
     */
    public static class KnowledgeBaseState {
        public static final String BUILDING = "building";
        public static final String BUILT = "built";
        public static final String NOT_BUILT = "not_built";
        public static final String BUILD_FAILED = "build_failed";
        public static final String LOADING = "loading";
        public static final String LOADED = "loaded";
        public static final String LOAD_FAILED = "load_failed";
        public static final String EMPTY = "empty";
        public static final String READY = "ready";
        public static final String NONE = "none";
    }

    /**
     * 进度状态常量
     */
    public static class ProgressState {
        public static final String INITIALIZING = "initializing";
        public static final String PROCESSING = "processing";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
        public static final String CANCELLED = "cancelled";
        public static final String PAUSED = "paused";
        
        // 进度状态关键词
        public static final String BLOCKS_GENERATED_KEYWORD = "blocks_generated";
        public static final String TEXT_EXTRACTION_KEYWORD = "text_extraction";
        public static final String VECTORIZATION_KEYWORD = "vectorization";
    }

    /**
     * API URL常量
     */
    public static class ApiUrl {
        public static final String LOCAL = "local";
        public static final String OPENAI = "openai";
        public static final String CUSTOM = "custom";
        public static final String NEW = "new";
    }
    
    /**
     * 嵌入模型选择常量
     */
    public static class EmbeddingModel {
        public static final String BGE_SMALL = "bge_small";
        public static final String BGE_BASE = "bge_base";
        public static final String BGE_LARGE = "bge_large";
        public static final String SENTENCE_TRANSFORMER = "sentence_transformer";
        public static final String ROOT_DIRECTORY = "root_directory";
    }
    
    /**
     * 模型名称常量
     */
    public static class ModelName {
        public static final String BGE_M3 = "bge-m3";
        public static final String SBKN_BASE_V1_0 = "SBKNBaseV1.0";
    }
    
    /**
     * 对话角色常量
     */
    public static class ChatRole {
        public static final String SYSTEM = "system";
        public static final String USER = "user";
        public static final String HUMAN = "human";
        public static final String ASSISTANT = "assistant";
        public static final String BOT = "bot";
    }
    
    /**
     * 重排模型选择常量
     */
    public static class RerankerModel {
        public static final String NONE = "none";
        public static final String BGE_RERANKER = "bge_reranker";
        public static final String CUSTOM = "custom";
    }

    /**
     * 菜单项常量
     */
    public static class MenuItem {
        public static final String COPY = "copy";
        public static final String SELECT_ALL = "select_all";
        public static final String CLEAR = "clear";
        public static final String SAVE = "save";
        public static final String SHARE = "share";
    }

    /**
     * 操作类型常量
     */
    public static class OperationType {
        public static final String CREATE = "create";
        public static final String UPDATE = "update";
        public static final String DELETE = "delete";
        public static final String QUERY = "query";
        public static final String IMPORT = "import";
        public static final String EXPORT = "export";
    }

    /**
     * 文件类型常量
     */
    public static class FileType {
        public static final String PDF = "pdf";
        public static final String TXT = "txt";
        public static final String DOC = "doc";
        public static final String DOCX = "docx";
        public static final String MD = "md";
        public static final String HTML = "html";
    }
    
    /**
     * 文件名常量
     */
    public static class FileName {
        public static final String CONFIG_JSON = "config.json";
        public static final String TOKENIZER_JSON = "tokenizer.json";
        public static final String SPECIAL_TOKENS_MAP_JSON = "special_tokens_map.json";
        public static final String TOKENIZER_CONFIG_JSON = "tokenizer_config.json";
        public static final String MODEL_ONNX = "model.onnx";
        public static final String GENAI_CONFIG_JSON = "genai_config.json";
    }

    /**
     * 配置键常量
     */
    public static class ConfigKey {
        public static final String MODEL_PATH = "model_path";
        public static final String KB_PATH = "kb_path";
        public static final String API_URL = "api_url";
        public static final String EMBEDDING_MODEL = "embedding_model";
        public static final String LANGUAGE = "language";
        public static final String THEME = "theme";
    }
    
    // 向后兼容的常量定义
    public static final String API_URL_NEW = ApiUrl.NEW;
    public static final String KNOWLEDGE_BASE_STATUS_LOADING = KnowledgeBaseState.LOADING;
    public static final String KNOWLEDGE_BASE_STATUS_NONE = KnowledgeBaseState.EMPTY;
    public static final String KNOWLEDGE_BASE_STATUS_NO_AVAILABLE = "no_available";
    public static final String MODEL_STATUS_LOADING = ModelState.LOADING;
    public static final String MODEL_STATUS_DIRECTORY_NOT_EXIST = "directory_not_exist";
    public static final String MODEL_STATUS_NOT_FOUND = "not_found";
    public static final String MODEL_STATUS_NO_AVAILABLE = "no_available";
    public static final String MODEL_STATUS_FETCH_FAILED = "fetch_failed";
    public static final String MODEL_STATUS_PLEASE_SELECT_EMBEDDING = "please_select_embedding";
    public static final String MODEL_STATUS_PARSE_FAILED = "parse_failed";
    
    // 旧版本模型状态常量（向后兼容）
    public static final String MODEL_STATE_LOADING = ModelState.LOADING;
    public static final String MODEL_STATE_NO_AVAILABLE = "no_available";
    public static final String MODEL_STATE_FETCH_FAILED = "fetch_failed";
    public static final String MODEL_STATE_ERROR = "error";
    public static final String MODEL_STATE_NONE = "none";
    public static final String MODEL_STATE_FETCHING = "fetching";
    public static final String MODEL_STATE_UNAVAILABLE = "unavailable";
    
    // 旧版本知识库状态常量（向后兼容）
    public static final String KB_STATE_LOADING = "kb_loading";
    public static final String KB_STATE_NONE = "kb_none";
    public static final String KB_STATE_PLEASE_CREATE = "kb_please_create";
    public static final String KB_STATE_CREATE = "kb_create";
    public static final String KB_STATE_EMPTY = KnowledgeBaseState.EMPTY;
    
    // 处理状态常量
    public static final String PROCESSING_STATUS_EXTRACTING_TEXT = "extracting_text";
    public static final String PROCESSING_STATUS_GENERATING_VECTORS = "generating_vectors";
    public static final String PROCESSING_STATUS_TEXT_EXTRACTION_COMPLETE = "text_extraction_complete";
    public static final String PROCESSING_STATUS_VECTORIZATION_COMPLETE = "vectorization_complete";
    public static final String PROCESSING_STATUS_OVERWRITE_DELETED = "overwrite_deleted";
    public static final String PROCESSING_STATUS_CANCELLED = "cancelled";
    public static final String PROCESSING_STATUS_PREPARING = "preparing";
    public static final String PROCESSING_STATUS_TASK_INTERRUPTED = "task_interrupted";
    public static final String PROCESSING_STATUS_KB_CREATION_COMPLETED = "kb_creation_completed";
    
    // 进度状态常量（向后兼容）
    public static final String PROGRESS_STATE_EXTRACTING_TEXT = "extracting_text";
    public static final String PROGRESS_STATE_GENERATING_VECTORS = "generating_vectors";
    public static final String PROGRESS_STATE_TEXT_EXTRACTION_COMPLETE = "text_extraction_complete";
    public static final String PROGRESS_STATE_VECTORIZATION_COMPLETE = "vectorization_complete";
    
    // 重排序模型常量
    public static final String RERANKER_MODEL_NONE = "none";
    
    // 对话框常量
    public static final String DIALOG_TITLE_NEW_KB = "new_kb_title";
    public static final String DIALOG_MESSAGE_ENTER_KB_NAME = "enter_kb_name";
    public static final String DIALOG_TITLE_MODEL_MISMATCH_WARNING = "model_mismatch_warning";
    public static final String DIALOG_MESSAGE_MODEL_MISMATCH = "model_mismatch";
    public static final String DIALOG_MESSAGE_CURRENT_MODEL = "current_model";
    public static final String DIALOG_MESSAGE_MISMATCH_WARNING = "mismatch_warning";
    public static final String DIALOG_TITLE_CONFIRM_INTERRUPT = "confirm_interrupt";
    public static final String DIALOG_MESSAGE_CONFIRM_INTERRUPT = "confirm_interrupt_message";
    public static final String DIALOG_TITLE_KB_EXISTS = "kb_exists";
    public static final String DIALOG_MESSAGE_KB_EXISTS = "kb_exists_message";
    public static final String DIALOG_TITLE_SELECT_EMBEDDING_RERANKER = "select_embedding_reranker";
    public static final String DIALOG_TITLE_SELECT_EMBEDDING_MODEL = "select_embedding_model";
    
    // 按钮文本常量
    public static final String BUTTON_TEXT_OK = "ok";
    public static final String BUTTON_TEXT_CANCEL = "cancel";
    public static final String BUTTON_TEXT_CREATE_KB = "create_kb";
    public static final String BUTTON_TEXT_CONTINUE = "continue";
    public static final String BUTTON_TEXT_NEW_KB = "new_kb";
    public static final String BUTTON_TEXT_OVERWRITE = "overwrite";
    public static final String BUTTON_TEXT_APPEND = "append";
    
    // MainActivity dialog constants
    public static final String DIALOG_TITLE_NEED_FULL_FILE_ACCESS = "dialog_title_need_full_file_access";
    public static final String DIALOG_MESSAGE_NEED_FULL_FILE_ACCESS = "dialog_message_need_full_file_access";
    public static final String DIALOG_TITLE_NEED_STORAGE_PERMISSION = "dialog_title_need_storage_permission";
    public static final String DIALOG_MESSAGE_NEED_STORAGE_PERMISSION = "dialog_message_need_storage_permission";
    public static final String DIALOG_TITLE_ABOUT = "dialog_title_about";
    public static final String DIALOG_MESSAGE_ABOUT = "dialog_message_about";
    public static final String BUTTON_TEXT_GO_TO_SETTINGS = "button_go_to_settings";
    
    // 成功消息常量
    public static final String SUCCESS_KB_NAME_ADDED = "kb_name_added";
    
    // 验证消息常量
    public static final String VALIDATION_KB_NAME_CANNOT_BE_EMPTY = "kb_name_cannot_be_empty";
    public static final String VALIDATION_PLEASE_SELECT_FILES = "please_select_files";
    public static final String VALIDATION_PLEASE_SELECT_VALID_EMBEDDING = "please_select_valid_embedding";
     
     // 错误消息常量
    public static final String ERROR_CHECK_VECTOR_DIMENSION = "check_vector_dimension";
    public static final String ERROR_VECTOR_DIMENSION_MISMATCH = "vector_dimension_mismatch";
    public static final String ERROR_DIMENSION_MUST_MATCH = "dimension_must_match";
    
    // 方法显示文本常量
    public static final String METHOD_GET_DIALOG_DISPLAY_TEXT = "getDialogDisplayText";
    public static final String METHOD_GET_BUTTON_DISPLAY_TEXT = "getButtonDisplayText";
    public static final String METHOD_GET_PROCESSING_STATUS_DISPLAY_TEXT = "getProcessingStatusDisplayText";
    public static final String METHOD_GET_ERROR_DISPLAY_TEXT = "getErrorDisplayText";
    public static final String METHOD_GET_RERANKER_MODEL_DISPLAY_TEXT = "getRerankerModelDisplayText";
    public static final String METHOD_IS_PROCESSING_STATUS_DISPLAY_TEXT = "isProcessingStatusDisplayText";
    
    // 嵌入模型路径常量
    public static final String EMBEDDING_MODEL_ROOT_DIR = "root_directory";
    
    // 菜单项常量（向后兼容）
    public static final String MENU_CONVERT_TO_NOTE = "convert_to_note";
    public static final String MENU_SWITCH_TO_ENGLISH = "menu_switch_to_english";
    public static final String MENU_SWITCH_TO_CHINESE = "menu_switch_to_chinese";
    
    // 知识库操作常量
    public static final String KB_ACTION_CREATE_NEW = "create_new";
    public static final String KB_ACTION_OVERWRITE = "overwrite";
    public static final String KB_ACTION_APPEND = "append";
    
    // 下载状态常量（向后兼容）
    public static final String DOWNLOAD_STATE_DOWNLOADING = ModelState.DOWNLOADING;
    public static final String DOWNLOAD_STATE_DOWNLOADED = ModelState.DOWNLOADED;
    public static final String DOWNLOAD_STATE_NOT_DOWNLOADED = ModelState.NOT_DOWNLOADED;
    public static final String DOWNLOAD_STATE_DOWNLOAD_FAILED = ModelState.DOWNLOAD_FAILED;
    
    // 私有构造函数，防止实例化
    private AppConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}