package com.example.starlocalrag;

import android.content.Context;
import com.example.starlocalrag.R;

/**
 * State Display Manager
 * Responsible for converting state constants to display text, supports multiple languages
 */
public class StateDisplayManager {
    
    private final Context context;
    
    public StateDisplayManager(Context context) {
        this.context = context;
    }
    
    /**
     * Get display text for model state
     */
    public String getModelStateDisplay(String state) {
        switch (state) {
            case AppConstants.ModelState.DOWNLOADING:
                return context.getString(R.string.common_downloading);
            case AppConstants.ModelState.DOWNLOADED:
                return context.getString(R.string.common_downloaded);
            case AppConstants.ModelState.NOT_DOWNLOADED:
                return context.getString(R.string.model_state_not_downloaded);
            case AppConstants.ModelState.DOWNLOAD_FAILED:
                return context.getString(R.string.common_download) + context.getString(R.string.common_failed);
            case AppConstants.ModelState.LOADING:
                return context.getString(R.string.common_loading);
            case AppConstants.ModelState.LOADED:
                return context.getString(R.string.common_loaded);
            case AppConstants.ModelState.LOAD_FAILED:
                return context.getString(R.string.common_failed);
            case AppConstants.ModelState.UNLOADED:
                return context.getString(R.string.model_state_unloaded);
            // MODEL_STATUS constants
            case AppConstants.MODEL_STATUS_DIRECTORY_NOT_EXIST:
                return context.getString(R.string.model_status_directory_not_exist); 
            case AppConstants.MODEL_STATUS_NOT_FOUND:
                return context.getString(R.string.model_status_not_found);
            case AppConstants.MODEL_STATUS_NO_AVAILABLE:
                return context.getString(R.string.model_status_no_available);
            case AppConstants.MODEL_STATUS_FETCH_FAILED:
                return context.getString(R.string.common_fetch_failed);
            case AppConstants.MODEL_STATUS_PLEASE_SELECT_EMBEDDING:
                return context.getString(R.string.model_status_please_select_embedding);
            case AppConstants.MODEL_STATUS_PARSE_FAILED:
                return context.getString(R.string.common_parse_failed);
            default:
                return context.getString(R.string.common_unknown_model);
        }
    }
    
    /**
     * Get display text for knowledge base state
     */
    public String getKnowledgeBaseStateDisplay(String state) {
        switch (state) {
            case AppConstants.KnowledgeBaseState.BUILDING:
                return context.getString(R.string.kb_state_building);
            case AppConstants.KnowledgeBaseState.BUILT:
                return context.getString(R.string.kb_state_built);
            case AppConstants.KnowledgeBaseState.NOT_BUILT:
                return context.getString(R.string.kb_state_not_built);
            case AppConstants.KnowledgeBaseState.BUILD_FAILED:
                return context.getString(R.string.common_build_failed);
            case AppConstants.KnowledgeBaseState.LOADING:
                return context.getString(R.string.common_loading);
            case AppConstants.KnowledgeBaseState.LOADED:
                return context.getString(R.string.common_loaded);
            case AppConstants.KnowledgeBaseState.LOAD_FAILED:
                return context.getString(R.string.common_failed);
            case AppConstants.KnowledgeBaseState.EMPTY:
                return context.getString(R.string.kb_state_empty);
            case AppConstants.KnowledgeBaseState.READY:
                return context.getString(R.string.common_ready);
            default:
                return context.getString(R.string.common_unknown_state);
        }
    }
    
    /**
     * Get display text for progress state
     */
    public String getProgressStateDisplay(String state) {
        switch (state) {
            case AppConstants.ProgressState.INITIALIZING:
                return context.getString(R.string.common_initializing);
            case AppConstants.ProgressState.PROCESSING:
                return context.getString(R.string.common_processing);
            case AppConstants.ProgressState.COMPLETED:
                return context.getString(R.string.common_completed);
            case AppConstants.ProgressState.FAILED:
                return context.getString(R.string.common_failed);
            case AppConstants.ProgressState.CANCELLED:
                return context.getString(R.string.common_cancelled);
            case AppConstants.ProgressState.PAUSED:
                return context.getString(R.string.common_paused);
            default:
                return context.getString(R.string.common_unknown_state);
        }
    }
    
    /**
     * Get display text for API URL
     */
    public String getApiUrlDisplay(String apiUrl) {
        switch (apiUrl) {
            case AppConstants.ApiUrl.LOCAL:
                return context.getString(R.string.api_url_local);
            case AppConstants.ApiUrl.OPENAI:
                return context.getString(R.string.api_url_openai);
            case AppConstants.ApiUrl.CUSTOM:
                return context.getString(R.string.common_custom);
            case AppConstants.ApiUrl.NEW:
                return context.getString(R.string.common_new);
            default:
                return apiUrl; // 直接返回原始值
        }
    }
    
    /**
     * Convert display text back to API URL constant
     * 将显示文本转换回API URL常量
     */
    public String getApiUrlFromDisplayText(String displayText) {
        // 检查是否为资源键
        if ("api_url_local".equals(displayText)) {
            return AppConstants.ApiUrl.LOCAL;
        } else if ("api_url_openai".equals(displayText)) {
            return AppConstants.ApiUrl.OPENAI;
        } else if ("common_custom".equals(displayText)) {
            return AppConstants.ApiUrl.CUSTOM;
        } else if ("common_new".equals(displayText)) {
            return AppConstants.ApiUrl.NEW;
        }
        
        // 检查是否为显示文本
        if (displayText.equals(context.getString(R.string.api_url_local))) {
            return AppConstants.ApiUrl.LOCAL;
        } else if (displayText.equals(context.getString(R.string.api_url_openai))) {
            return AppConstants.ApiUrl.OPENAI;
        } else if (displayText.equals(context.getString(R.string.common_custom))) {
            return AppConstants.ApiUrl.CUSTOM;
        } else if (displayText.equals(context.getString(R.string.common_new))) {
            return AppConstants.ApiUrl.NEW;
        } else {
            return displayText; // 直接返回原始值（自定义URL）
        }
    }
    
    /**
     * Convert display text back to reranker model constant
     * 将显示文本转换回重排模型常量
     */
    public String getRerankerModelFromDisplayText(String displayText) {
        // 检查是否为资源键
        if ("common_none".equals(displayText)) {
            return AppConstants.RerankerModel.NONE;
        } else if ("reranker_model_bge_reranker".equals(displayText)) {
            return AppConstants.RerankerModel.BGE_RERANKER;
        } else if ("common_custom".equals(displayText)) {
            return AppConstants.RerankerModel.CUSTOM;
        }
        
        // 检查是否为显示文本
        if (displayText.equals(context.getString(R.string.common_none))) {
            return AppConstants.RerankerModel.NONE;
        } else if (displayText.equals(context.getString(R.string.reranker_model_bge_reranker))) {
            return AppConstants.RerankerModel.BGE_RERANKER;
        } else if (displayText.equals(context.getString(R.string.common_custom))) {
            return AppConstants.RerankerModel.CUSTOM;
        } else {
            return displayText; // 直接返回原始值（自定义模型）
        }
    }
    
    /**
     * Convert display text back to knowledge base state constant
     * 将显示文本转换回知识库状态常量
     */
    public String getKnowledgeBaseFromDisplayText(String displayText) {
        // 检查是否为资源键
        if ("common_none".equals(displayText)) {
            return AppConstants.KnowledgeBaseState.NONE;
        } else if ("kb_state_empty".equals(displayText)) {
            return AppConstants.KnowledgeBaseState.EMPTY;
        } else if ("common_loading".equals(displayText)) {
            return AppConstants.KnowledgeBaseState.LOADING;
        } else if ("common_ready".equals(displayText)) {
            return AppConstants.KnowledgeBaseState.READY;
        }
        
        // 检查是否为显示文本
        if (displayText.equals(context.getString(R.string.common_none))) {
            return AppConstants.KnowledgeBaseState.NONE;
        } else if (displayText.equals(context.getString(R.string.kb_state_empty))) {
            return AppConstants.KnowledgeBaseState.EMPTY;
        } else if (displayText.equals(context.getString(R.string.common_loading))) {
            return AppConstants.KnowledgeBaseState.LOADING;
        } else if (displayText.equals(context.getString(R.string.common_ready))) {
            return AppConstants.KnowledgeBaseState.READY;
        } else {
            return displayText; // 直接返回原始值（知识库名称）
        }
    }
    
    /**
     * Get display text for embedding model
     */
    public String getEmbeddingModelDisplay(String model) {
        switch (model) {
            case AppConstants.EmbeddingModel.BGE_SMALL:
                return context.getString(R.string.embedding_model_bge_small);
            case AppConstants.EmbeddingModel.BGE_BASE:
                return context.getString(R.string.embedding_model_bge_base);
            case AppConstants.EmbeddingModel.BGE_LARGE:
                return context.getString(R.string.embedding_model_bge_large);
            case AppConstants.EmbeddingModel.SENTENCE_TRANSFORMER:
                return context.getString(R.string.embedding_model_sentence_transformer);
            case AppConstants.EmbeddingModel.ROOT_DIRECTORY:
                return context.getString(R.string.embedding_model_root_directory);
            default:
                return context.getString(R.string.common_unknown_model);
        }
    }
    
    /**
     * Get display text for reranker model
     */
    public String getRerankerModelDisplay(String model) {
        // 处理"none"值（AppConstants.RerankerModel.NONE和AppConstants.RERANKER_MODEL_NONE都是"none"）
        if (AppConstants.RerankerModel.NONE.equals(model) || AppConstants.RERANKER_MODEL_NONE.equals(model)) {
            return context.getString(R.string.common_none);
        }
        
        switch (model) {
            case AppConstants.RerankerModel.BGE_RERANKER:
                return context.getString(R.string.reranker_model_bge_reranker);
            case AppConstants.RerankerModel.CUSTOM:
                return context.getString(R.string.common_custom);
            default:
                return model; // 返回原始模型名称，用于自定义模型
        }
    }
    
    /**
     * Get display text for menu item
     */
    public String getMenuItemDisplay(String menuItem) {
        switch (menuItem) {
            case AppConstants.MenuItem.COPY:
                return context.getString(R.string.common_copy);
            case AppConstants.MenuItem.SELECT_ALL:
                return context.getString(R.string.common_select_all);
            case AppConstants.MenuItem.CLEAR:
                return context.getString(R.string.common_clear);
            case AppConstants.MenuItem.SAVE:
                return context.getString(R.string.common_save);
            case AppConstants.MenuItem.SHARE:
                return context.getString(R.string.common_share);
            default:
                return context.getString(R.string.common_unknown_operation);
        }
    }
    
    /**
     * Get display text for file type
     */
    public String getFileTypeDisplay(String fileType) {
        switch (fileType) {
            case AppConstants.FileType.PDF:
                return context.getString(R.string.file_type_pdf);
            case AppConstants.FileType.TXT:
                return context.getString(R.string.file_type_txt);
            case AppConstants.FileType.DOC:
                return context.getString(R.string.file_type_doc);
            case AppConstants.FileType.DOCX:
                return context.getString(R.string.file_type_docx);
            case AppConstants.FileType.MD:
                return context.getString(R.string.file_type_md);
            case AppConstants.FileType.HTML:
                return context.getString(R.string.file_type_html);
            default:
                return context.getString(R.string.common_unknown_file_type);
        }
    }
    
    /**
     * Get display text for validation messages
     */
    public String getValidationDisplay(String validationKey) {
        switch (validationKey) {
            case AppConstants.VALIDATION_PLEASE_SELECT_VALID_EMBEDDING:
                return context.getString(R.string.validation_please_select_valid_embedding);
            case AppConstants.VALIDATION_PLEASE_SELECT_FILES:
                return context.getString(R.string.validation_please_select_files);
            case AppConstants.VALIDATION_KB_NAME_CANNOT_BE_EMPTY:
                return context.getString(R.string.validation_kb_name_cannot_be_empty);
            default:
                return context.getString(R.string.common_unknown_validation);
        }
    }
    
    /**
     * Get display text for dialog
     */
    public String getDialogDisplay(String dialogKey) {
        switch (dialogKey) {
            case AppConstants.DIALOG_TITLE_CONFIRM_INTERRUPT:
                return context.getString(R.string.dialog_title_confirm_interrupt);
            case AppConstants.DIALOG_MESSAGE_CONFIRM_INTERRUPT:
                return context.getString(R.string.dialog_message_confirm_interrupt);
            case AppConstants.DIALOG_TITLE_KB_EXISTS:
                return context.getString(R.string.dialog_title_kb_exists);
            case AppConstants.DIALOG_MESSAGE_KB_EXISTS:
                return context.getString(R.string.dialog_message_kb_exists);
            case AppConstants.DIALOG_TITLE_NEED_FULL_FILE_ACCESS:
                return context.getString(R.string.dialog_title_need_full_file_access);
            case AppConstants.DIALOG_MESSAGE_NEED_FULL_FILE_ACCESS:
                return context.getString(R.string.dialog_message_need_full_file_access);
            case AppConstants.DIALOG_TITLE_NEED_STORAGE_PERMISSION:
                return context.getString(R.string.dialog_title_need_storage_permission);
            case AppConstants.DIALOG_MESSAGE_NEED_STORAGE_PERMISSION:
                return context.getString(R.string.dialog_message_need_storage_permission);
            case AppConstants.DIALOG_TITLE_ABOUT:
                return context.getString(R.string.dialog_title_about);
            case AppConstants.DIALOG_MESSAGE_ABOUT:
                return context.getString(R.string.dialog_message_about);
            default:
                return dialogKey; // Return original key as fallback
        }
    }

    /**
     * Get display text for button
     */
    public String getButtonDisplay(String buttonKey) {
        switch (buttonKey) {
            case AppConstants.BUTTON_TEXT_OK:
                return context.getString(R.string.common_ok);
            case AppConstants.BUTTON_TEXT_CANCEL:
                return context.getString(R.string.common_cancel);
            case AppConstants.BUTTON_TEXT_CREATE_KB:
                return context.getString(R.string.button_create_kb);
            case AppConstants.BUTTON_TEXT_CONTINUE:
                return context.getString(R.string.common_continue);
            case AppConstants.BUTTON_TEXT_NEW_KB:
                return context.getString(R.string.button_new_kb);
            case AppConstants.BUTTON_TEXT_OVERWRITE:
                return context.getString(R.string.common_overwrite);
            case AppConstants.BUTTON_TEXT_APPEND:
                return context.getString(R.string.common_append);
            case AppConstants.BUTTON_TEXT_GO_TO_SETTINGS:
                return context.getString(R.string.button_go_to_settings);
            default:
                return buttonKey; // Return original key as fallback
        }
    }

    /**
     * Get display text for processing status
     */
    public String getProcessingStatusDisplay(String status) {
        switch (status) {
            case AppConstants.PROCESSING_STATUS_EXTRACTING_TEXT:
                return context.getString(R.string.progress_text_extraction_keyword);
            case AppConstants.PROCESSING_STATUS_GENERATING_VECTORS:
                return context.getString(R.string.progress_vectorization_keyword);
            case AppConstants.PROCESSING_STATUS_PREPARING:
                return context.getString(R.string.common_preparing);
            case AppConstants.PROCESSING_STATUS_OVERWRITE_DELETED:
                return context.getString(R.string.processing_status_overwrite_deleted);
            case AppConstants.PROCESSING_STATUS_TASK_INTERRUPTED:
                return context.getString(R.string.processing_status_task_interrupted);
            case AppConstants.PROCESSING_STATUS_KB_CREATION_COMPLETED:
                return context.getString(R.string.processing_status_kb_creation_completed);
            case AppConstants.PROCESSING_STATUS_TEXT_EXTRACTION_COMPLETE:
                return context.getString(R.string.processing_status_text_extraction_complete);
            case AppConstants.PROCESSING_STATUS_VECTORIZATION_COMPLETE:
                return context.getString(R.string.processing_status_vectorization_complete);
            default:
                return status; // Return original status as fallback
        }
    }

    /**
     * Automatically select corresponding display method based on state key type
     */
    public String getDisplayText(String stateType, String state) {
        switch (stateType) {
            case "model":
                return getModelStateDisplay(state);
            case "knowledge_base":
                return getKnowledgeBaseStateDisplay(state);
            case "progress":
                return getProgressStateDisplay(state);
            case "processing_status":
                return getProcessingStatusDisplay(state);
            case "api_url":
                return getApiUrlDisplay(state);
            case "embedding_model":
                return getEmbeddingModelDisplay(state);
            case "reranker_model":
                return getRerankerModelDisplay(state);
            case "menu_item":
                return getMenuItemDisplay(state);
            case "file_type":
                return getFileTypeDisplay(state);
            case "validation":
                return getValidationDisplay(state);
            case "button":
                return getButtonDisplay(state);
            default:
                return state; // Return original state value as fallback
        }
    }
    
    // Backward compatible methods, maintain compatibility with old versions
    public String getModelStateDisplayText(String stateKey) {
        return getModelStateDisplay(stateKey);
    }
    
    public String getKnowledgeBaseStateDisplayText(String stateKey) {
        return getKnowledgeBaseStateDisplay(stateKey);
    }
    
    public String getProgressStateDisplayText(String stateKey) {
        return getProgressStateDisplay(stateKey);
    }
    
    public String getApiUrlDisplayText(String stateKey) {
        return getApiUrlDisplay(stateKey);
    }
    
    // Static methods for backward compatibility
    public static String getApiUrlDisplayText(Context context, String apiUrl) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getApiUrlDisplay(apiUrl);
    }
    
    public static String getApiUrlFromDisplayText(Context context, String displayText) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getApiUrlFromDisplayText(displayText);
    }
    
    public static String getKnowledgeBaseStatusDisplayText(Context context, String status) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getKnowledgeBaseStateDisplay(status);
    }
    
    public static boolean isModelStatusDisplayText(Context context, String status) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.isModelStatus(status);
    }
    
    public static boolean isKnowledgeBaseStatusDisplayText(Context context, String status) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.isKnowledgeBaseStatus(status);
    }
    
    /**
     * Check if it is a model status
     */
    public boolean isModelStatus(String status) {
        return AppConstants.ModelState.LOADING.equals(status) ||
               AppConstants.ModelState.LOADED.equals(status) ||
               AppConstants.ModelState.NOT_DOWNLOADED.equals(status);
    }
    
    /**
     * Check if it is a knowledge base status
     */
    public boolean isKnowledgeBaseStatus(String status) {
        return AppConstants.KnowledgeBaseState.LOADING.equals(status) ||
               AppConstants.KnowledgeBaseState.EMPTY.equals(status) ||
               AppConstants.KnowledgeBaseState.READY.equals(status);
    }
    
    public static String getDialogDisplayText(Context context, String key) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("dialog", key);
    }
    
    public static String getButtonDisplayText(Context context, String key) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("button", key);
    }
    
    public static String getProcessingStatusDisplayText(Context context, String key) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("processing_status", key);
    }
    
    public static String getErrorDisplayText(Context context, String key) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("error", key);
    }
    
    public static String getRerankerModelDisplayText(Context context, String key) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("reranker_model", key);
    }
    
    public static boolean isProcessingStatusDisplayText(Context context, String status, String targetStatus) {
        return status != null && status.equals(targetStatus);
    }
    
    public static String getModelStatusDisplayText(Context context, String status) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getModelStateDisplay(status);
    }

    public static String getSuccessDisplayText(Context context, String successKey) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("success", successKey);
    }

    public static String getValidationDisplayText(Context context, String validationKey) {
        StateDisplayManager manager = new StateDisplayManager(context);
        return manager.getDisplayText("validation", validationKey);
    }

    /**
     * Get display text for menu items
     */
    public String getMenuDisplay(String menuKey) {
        switch (menuKey) {
            case AppConstants.MENU_SWITCH_TO_ENGLISH:
                return context.getString(R.string.menu_switch_to_english);
            case AppConstants.MENU_SWITCH_TO_CHINESE:
                return context.getString(R.string.menu_switch_to_chinese);
            case AppConstants.MENU_CONVERT_TO_NOTE:
                return context.getString(R.string.menu_convert_to_note);
            default:
                return menuKey;
        }
    }

}