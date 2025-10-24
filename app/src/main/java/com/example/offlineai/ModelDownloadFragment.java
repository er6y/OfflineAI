package com.example.offlineai;

import android.app.AlertDialog;
import android.content.Context;


import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelDownloadFragment extends Fragment {
    private static final String TAG = "ModelDownloadFragment";
    
    // Constants removed - now using string resources
    
    // UI组件
    private LinearLayout containerEmbeddingModels;
    private LinearLayout containerRerankerModels;
    private LinearLayout containerLlmModels;
    private LinearLayout containerAsrModels;
    private LinearLayout containerTtsModels;
    private TextView textViewProgress;
    private ScrollView scrollViewProgress;
    private Button buttonDownload;
    // 模型注释：模型名称 -> 注释文本
    private final Map<String, String> modelComments = new HashMap<>();
    
    // 下载相关
    private ExecutorService downloadExecutor;
    private Handler mainHandler;
    private boolean isDownloading = false;
    
    // 进度显示
    private StringBuilder progressText = new StringBuilder();
    private int lastReportedProgress = 0;
    
    // 电源管理
    private PowerManager.WakeLock wakeLock;
    
    // JSON 管理的模型列表：类别 -> 模型名称 -> (文件名 -> URL)
    private final Map<String, Map<String, Map<String, String>>> modelList = new HashMap<>();
    // 各类别复选框映射
    private final Map<CheckBox, String> embeddingCheckBoxMap = new HashMap<>();
    private final Map<CheckBox, String> rerankerCheckBoxMap = new HashMap<>();
    private final Map<CheckBox, String> llmCheckBoxMap = new HashMap<>();
    private final Map<CheckBox, String> asrCheckBoxMap = new HashMap<>();
    private final Map<CheckBox, String> ttsCheckBoxMap = new HashMap<>();
    // 旧的内置配置（用于首次生成默认JSON）
    private static final Map<String, ModelConfig> MODEL_CONFIGS = new HashMap<>();
    
    static {
        // 嵌入模型配置
        MODEL_CONFIGS.put("bge-m3", new ModelConfig(
            "bge-m3_dynamic_int8_onnx",
            ModelType.EMBEDDING,
            new String[]{
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/tokenizer_config.json?download=true",
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/tokenizer.json?download=true",
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/special_tokens_map.json?download=true",
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/model.onnx?download=true",
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/conversion_info.json?download=true",
                "https://hf-mirror.com/er6y/bge-m3_dynamic_int8_onnx/resolve/main/config.json?download=true"
            },
            new String[]{
                "tokenizer_config.json",
                "tokenizer.json",
                "special_tokens_map.json",
                "model.onnx",
                "conversion_info.json",
                "config.json"
            }
        ));
        
        // Qwen3 Embedding模型配置
        MODEL_CONFIGS.put("qwen3-embedding", new ModelConfig(
            "Qwen3-Embedding-0.6B-onnx-uint8",
            ModelType.EMBEDDING,
            new String[]{
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/.gitattributes?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/added_tokens.json?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/config.json?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/dynamic_uint8.onnx?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/merges.txt?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/tokenizer.json?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/tokenizer_config.json?download=true",
                "https://hf-mirror.com/electroglyph/Qwen3-Embedding-0.6B-onnx-uint8/resolve/main/vocab.json?download=true"
            },
            new String[]{
                ".gitattributes",
                "added_tokens.json",
                "config.json",
                "dynamic_uint8.onnx",
                "merges.txt",
                "tokenizer.json",
                "tokenizer_config.json",
                "vocab.json"
            }
        ));
        
        // 重排模型配置
        MODEL_CONFIGS.put("bge-reranker", new ModelConfig(
            "bge-reranker-v2-m3_dynamic_int8_onnx",
            ModelType.RERANKER,
            new String[]{
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/config.json?download=true",
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/conversion_info.json?download=true",
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/model.onnx?download=true",
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/special_tokens_map.json?download=true",
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/tokenizer.json?download=true",
                "https://hf-mirror.com/er6y/bge-reranker-v2-m3_dynamic_int8_onnx/resolve/main/tokenizer_config.json?download=true"
            },
            new String[]{
                "config.json",
                "conversion_info.json",
                "model.onnx",
                "special_tokens_map.json",
                "tokenizer.json",
                "tokenizer_config.json"
            }
        ));
        
        // LLM模型配置
        MODEL_CONFIGS.put("qwen-0.6b", new ModelConfig(
            "Qwen3-0.6B-GGUF",
            ModelType.LLM,
            new String[]{
                "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf?download=true",
                "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/params?download=true"
            },
            new String[]{
                "Qwen3-0.6B-Q8_0.gguf",
                "params"
            }
        ));
        
        MODEL_CONFIGS.put("qwen-1.7b", new ModelConfig(
            "Qwen3-1.7B-GGUF",
            ModelType.LLM,
            new String[]{
                "https://hf-mirror.com/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf?download=true",
                "https://hf-mirror.com/Qwen/Qwen3-1.7B-GGUF/resolve/main/params?download=true"
            },
            new String[]{
                "Qwen3-1.7B-Q8_0.gguf",
                "params"
            }
        ));
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_model_download, container, false);
        
        initViews(view);
        // 确保并加载 ModelDownloadList.json，然后动态构建复选框
        ensureAndLoadModelList();
        buildCheckboxesFromModelList();
        setupListeners();
        
        downloadExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化进度文本
        progressText.setLength(0);
        progressText.append(textViewProgress.getText());
        
        return view;
    }
    
    private void initViews(View view) {
        containerEmbeddingModels = view.findViewById(R.id.containerEmbeddingModels);
        containerRerankerModels = view.findViewById(R.id.containerRerankerModels);
        containerLlmModels = view.findViewById(R.id.containerLlmModels);
        containerAsrModels = view.findViewById(R.id.containerAsrModels);
        containerTtsModels = view.findViewById(R.id.containerTtsModels);
        textViewProgress = view.findViewById(R.id.textViewProgress);
        // Find the ScrollView ancestor that contains the progress TextView
        scrollViewProgress = view.findViewById(R.id.scrollViewMain);
        if (scrollViewProgress == null) {
            // Fallback: try to find ScrollView by traversing parent hierarchy
            android.view.ViewParent parent = textViewProgress.getParent();
            while (parent != null && !(parent instanceof ScrollView)) {
                parent = parent.getParent();
            }
            if (parent instanceof ScrollView) {
                scrollViewProgress = (ScrollView) parent;
            }
        }
        buttonDownload = view.findViewById(R.id.buttonDownload);
        
        // Enable text selection and scrolling for progress TextView
        textViewProgress.setTextIsSelectable(true);
        textViewProgress.setFocusable(true);
        textViewProgress.setFocusableInTouchMode(true);
        textViewProgress.setMovementMethod(new android.text.method.ScrollingMovementMethod());
    }
    
    private void setupListeners() {
        // 添加MenuProvider来处理菜单
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.model_download_menu, menu);
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                
                if (id == R.id.action_close_download) {
                    // Close download page - Use Navigation component's back operation
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    return true;
                }
                
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        
        buttonDownload.setOnClickListener(v -> {
            if (isDownloading) {
                stopDownload();
            } else {
                startDownload();
            }
        });
    }
    
    private void startDownload() {
        List<String> selectedModels = getSelectedModels();
        if (selectedModels.isEmpty()) {
            Toast.makeText(getContext(), getString(R.string.dialog_select_at_least_one_model), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 直接开始下载
        proceedWithDownload(selectedModels);
    }
    
    private List<String> getSelectedModels() {
        List<String> selected = new ArrayList<>();
        for (Map.Entry<CheckBox, String> entry : embeddingCheckBoxMap.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        for (Map.Entry<CheckBox, String> entry : rerankerCheckBoxMap.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        for (Map.Entry<CheckBox, String> entry : llmCheckBoxMap.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        for (Map.Entry<CheckBox, String> entry : asrCheckBoxMap.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        for (Map.Entry<CheckBox, String> entry : ttsCheckBoxMap.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        return selected;
    }
    

    
    private void proceedWithDownload(List<String> selectedModels) {
        // 显示Wi-Fi下载提示
        showWifiDownloadDialog(selectedModels);
    }
    
    private void showWifiDownloadDialog(List<String> selectedModels) {
        new AlertDialog.Builder(getContext())
            .setTitle(getString(R.string.dialog_download_confirm_title))
                .setMessage(getString(R.string.dialog_download_confirm_message))
                .setPositiveButton(getString(R.string.common_download), (dialog, which) -> checkDirectoryConflictsAndDownload(selectedModels))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show();
    }
    
    private void checkDirectoryConflictsAndDownload(List<String> selectedModels) {
        // 检查目录冲突
        List<String> conflictDirs = checkDirectoryConflicts(selectedModels);
        if (!conflictDirs.isEmpty()) {
            showOverwriteDialog(conflictDirs, selectedModels);
        } else {
            executeDownload(selectedModels);
        }
    }
    
    /**
     * Get the base path for a model based on its category
     * @param modelName the model name
     * @return the base path for the model category
     */
    private String getModelBasePath(String modelName) {
        if (modelList.containsKey("embedding") && modelList.get("embedding").containsKey(modelName)) {
            return ConfigManager.getEmbeddingModelPath(getContext());
        } else if (modelList.containsKey("reranker") && modelList.get("reranker").containsKey(modelName)) {
            return ConfigManager.getRerankerModelPath(getContext());
        } else if (modelList.containsKey("llm") && modelList.get("llm").containsKey(modelName)) {
            return ConfigManager.getModelPath(getContext());
        } else if (modelList.containsKey("asr") && modelList.get("asr").containsKey(modelName)) {
            return ConfigManager.getAsrModelPath(getContext());
        } else if (modelList.containsKey("tts") && modelList.get("tts").containsKey(modelName)) {
            return ConfigManager.getTtsModelPath(getContext());
        }
        // Default to LLM model path if category not found
        return ConfigManager.getModelPath(getContext());
    }
    
    private List<String> checkDirectoryConflicts(List<String> selectedModels) {
        List<String> conflicts = new ArrayList<>();
        for (String modelName : selectedModels) {
            String basePath = getModelBasePath(modelName);
            File targetDir = new File(basePath, modelName);
            if (targetDir.exists() && targetDir.isDirectory()) {
                conflicts.add(modelName);
            }
        }
        return conflicts;
    }
    
    private void showOverwriteDialog(List<String> conflictDirs, List<String> selectedModels) {
        String message = getString(R.string.dialog_msg_dir_exists) + "\n\n" + String.join("\n", conflictDirs);
        
        new AlertDialog.Builder(getContext())
            .setTitle(getString(R.string.dialog_directory_exists_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.common_overwrite), (dialog, which) -> executeDownloadWithOverwrite(selectedModels))
            .setNeutralButton(getString(R.string.common_continue), (dialog, which) -> executeDownload(selectedModels))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show();
    }
    
    private void executeDownloadWithOverwrite(List<String> selectedModels) {
        // Delete files in conflicting directories, then start download
        for (String modelName : selectedModels) {
            String basePath = getModelBasePath(modelName);
            File targetDir = new File(basePath, modelName);
            if (targetDir.exists() && targetDir.isDirectory()) {
                File[] files = targetDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            file.delete();
                        }
                    }
                }
            }
        }
        executeDownload(selectedModels);
    }
    
    private void executeDownload(List<String> selectedModels) {
        isDownloading = true;
        buttonDownload.setText(R.string.button_interrupt);
        
        // 清空进度文本，开始新的下载
        progressText.setLength(0);
        
        // 获取电源锁
        acquireWakeLocks();
        
        appendProgress(getString(R.string.log_download_selected_models) + "\n");
        
        downloadExecutor.execute(() -> {
            boolean allSuccess = true;
            try {
                for (String modelKey : selectedModels) {
                    // 检查是否已被中断
                    if (!isDownloading) {
                        mainHandler.post(() -> appendProgress("\n" + getString(R.string.log_download_was_interrupted) + "\n"));
                        return;
                    }
                    boolean success = downloadModel(modelKey);
                    if (!success) {
                        allSuccess = false;
                    }
                }
                
                // 只有在所有模型都成功下载且未被中断的情况下才显示完成信息
                if (isDownloading && allSuccess) {
                    mainHandler.post(() -> {
                        appendProgress("\n" + getString(R.string.log_all_models_downloaded) + "\n");
                        finishDownload();
                    });
                } else {
                    mainHandler.post(() -> finishDownload());
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "下载错误", e);
                mainHandler.post(() -> {
                    appendProgress("\n" + getString(R.string.log_download_error) + ": " + e.getMessage() + "\n");
                    finishDownload();
                });
            }
        });
    }
    
    private boolean downloadModel(String modelName) {
        // Determine model category and get corresponding file mapping
        Map<String, String> filesMap = null;
        final String modelCategory;
        
        if (modelList.containsKey("embedding") && modelList.get("embedding").containsKey(modelName)) {
            filesMap = modelList.get("embedding").get(modelName);
            modelCategory = "embedding";
        } else if (modelList.containsKey("reranker") && modelList.get("reranker").containsKey(modelName)) {
            filesMap = modelList.get("reranker").get(modelName);
            modelCategory = "reranker";
        } else if (modelList.containsKey("llm") && modelList.get("llm").containsKey(modelName)) {
            filesMap = modelList.get("llm").get(modelName);
            modelCategory = "llm";
        } else if (modelList.containsKey("asr") && modelList.get("asr").containsKey(modelName)) {
            filesMap = modelList.get("asr").get(modelName);
            modelCategory = "asr";
        } else if (modelList.containsKey("tts") && modelList.get("tts").containsKey(modelName)) {
            filesMap = modelList.get("tts").get(modelName);
            modelCategory = "tts";
        } else {
            modelCategory = null;
        }
        
        if (filesMap == null || filesMap.isEmpty()) {
            mainHandler.post(() -> appendProgress(getString(R.string.common_unknown_model) + ": " + modelName + "\n"));
            return false;
        }
        
        mainHandler.post(() -> appendProgress("\n" + getString(R.string.log_start_downloading) + ": " + modelName + "\n"));
        
        // Create target directory based on model category
        String basePath;
        switch (modelCategory) {
            case "embedding":
                basePath = ConfigManager.getEmbeddingModelPath(getContext());
                break;
            case "reranker":
                basePath = ConfigManager.getRerankerModelPath(getContext());
                break;
            case "llm":
                basePath = ConfigManager.getModelPath(getContext());
                break;
            case "asr":
                basePath = ConfigManager.getAsrModelPath(getContext());
                break;
            case "tts":
                basePath = ConfigManager.getTtsModelPath(getContext());
                break;
            default:
                mainHandler.post(() -> appendProgress("Unknown model category: " + modelCategory + "\n"));
                return false;
        }
        
        File targetDir = new File(basePath, modelName);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        
        // 下载所有文件
        for (Map.Entry<String, String> entry : filesMap.entrySet()) {
            // 检查是否已被中断
            if (!isDownloading) {
                mainHandler.post(() -> appendProgress("Download interrupted\n"));
                return false;
            }
            String filename = entry.getKey();
            String url = entry.getValue();
            File targetFile = new File(targetDir, filename);
            
            // Create parent directories if filename contains subdirectories
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    LogManager.logE(TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
                    mainHandler.post(() -> appendProgress(getString(R.string.log_failed_to_create_dir) + ": " + parentDir.getName() + "\n"));
                    return false;
                }
            }
            
            // 先检查文件是否已完整下载，如果是则跳过
            if (isFileCompletelyDownloaded(url, targetFile)) {
                mainHandler.post(() -> appendProgress(getString(R.string.log_downloading_file) + ": " + filename + " - " + getString(R.string.log_file_already_complete) + "\n"));
                continue;
            }
            
            // Calculate and display file size (in background thread)
            String sizeInfo = "";
            try {
                URL sizeUrl = new URL(url);
                HttpURLConnection sizeConn = (HttpURLConnection) sizeUrl.openConnection();
                sizeConn.setRequestMethod("HEAD");
                sizeConn.setConnectTimeout(5000);
                sizeConn.setReadTimeout(5000);
                long fileSize = sizeConn.getContentLengthLong();
                sizeConn.disconnect();
                if (fileSize > 0) {
                    sizeInfo = " (" + formatFileSize(fileSize) + ")";
                }
            } catch (Exception e) {
                // Ignore size fetch errors
            }
            final String finalSizeInfo = sizeInfo;
            mainHandler.post(() -> appendProgress(getString(R.string.log_downloading_file) + ": " + filename + finalSizeInfo + "\n"));
            
            // 仅使用来自 JSON 的主地址进行下载（移除备份地址逻辑）
            boolean success = downloadFileWithRetry(url, targetFile);
            
            if (!success) {
                mainHandler.post(() -> appendProgress(getString(R.string.log_download_failed) + ": " + filename + "\n"));
                return false;
            }
        }
        
        mainHandler.post(() -> appendProgress(modelName + " " + getString(R.string.log_download_completed) + "\n"));
        return true;
    }
    
    private File getTargetDirectory(ModelConfig config) {
        String basePath;
        switch (config.type) {
            case EMBEDDING:
                basePath = ConfigManager.getEmbeddingModelPath(getContext());
                break;
            case RERANKER:
                basePath = ConfigManager.getRerankerModelPath(getContext());
                break;
            case LLM:
                basePath = ConfigManager.getModelPath(getContext());
                break;
            case ASR:
                basePath = ConfigManager.getAsrModelPath(getContext());
                break;
            case TTS:
                basePath = ConfigManager.getTtsModelPath(getContext());
                break;
            default:
                throw new IllegalArgumentException("未知模型" + "类型: " + config.type);
        }
        
        return new File(basePath, config.directoryName);
    }

    // === JSON 加载与 UI 构建 ===
    private void ensureAndLoadModelList() {
        try {
            File baseDir = new File(ConfigManager.getDataRootPath(requireContext()));
            if (!baseDir.exists()) baseDir.mkdirs();
            File listFile = new File(baseDir, "ModelDownloadList.txt");
            
            // Smart merge: Always merge assets file with user file
            mergeModelListFromAssets(listFile);
            
            String content = readFileContent(listFile);
            // 统一处理已存在文件：将 "\/" 规范化为 "/" 并回写
            boolean needRewrite = content.contains("\\/");
            String normalized = needRewrite ? content.replace("\\/", "/") : content;
            org.json.JSONObject root = new org.json.JSONObject(normalized);
            if (needRewrite) {
                try (FileOutputStream fos = new FileOutputStream(listFile)) {
                    String pretty = root.toString(2).replace("\\/", "/");
                    fos.write(pretty.getBytes(StandardCharsets.UTF_8));
                }
            }
            // 优先读取英文分类键，兼容老的中文键；统一存储为英文键
            String[] categoriesPreferred = new String[]{"embedding", "reranker", "llm", "asr", "tts"};
            String[] categoriesLegacy = new String[]{"嵌入式模型", "重排模型", "LLM模型", "asr", "tts"};
            for (int i = 0; i < categoriesPreferred.length; i++) {
                String storeKey = categoriesPreferred[i];
                String key = root.has(storeKey) ? storeKey : (root.has(categoriesLegacy[i]) ? categoriesLegacy[i] : null);
                if (key == null) continue;
                org.json.JSONObject catObj = root.getJSONObject(key);
                Map<String, Map<String, String>> models = new HashMap<>();
                Iterator<String> modelIter = catObj.keys();
                while (modelIter.hasNext()) {
                    String modelName = modelIter.next();
                    org.json.JSONObject filesObj = catObj.getJSONObject(modelName);
                    Map<String, String> files = new HashMap<>();
                    Iterator<String> fileIter = filesObj.keys();
                    while (fileIter.hasNext()) {
                        String fileName = fileIter.next();
                        if ("comments".equals(fileName)) {
                            try {
                                modelComments.put(modelName, filesObj.getString(fileName));
                            } catch (Exception ignored) {
                            }
                        } else {
                            String value = filesObj.getString(fileName);
                            if (value != null) {
                                value = value.trim();
                                // 去除可能包裹在反引号中的URL
                                if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
                                    value = value.substring(1, value.length() - 1).trim();
                                }
                            }
                            files.put(fileName, value);
                        }
                    }
                    models.put(modelName, files);
                }
                modelList.put(storeKey, models);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "加载模型列表失败", e);
        }
    }

    private String buildDefaultModelListJsonFromBuiltins() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONObject emb = new org.json.JSONObject();
            org.json.JSONObject rer = new org.json.JSONObject();
            org.json.JSONObject llm = new org.json.JSONObject();
            org.json.JSONObject asr = new org.json.JSONObject();
            org.json.JSONObject tts = new org.json.JSONObject();
            for (Map.Entry<String, ModelConfig> entry : MODEL_CONFIGS.entrySet()) {
                ModelConfig cfg = entry.getValue();
                org.json.JSONObject files = new org.json.JSONObject();
                for (int i = 0; i < cfg.filenames.length; i++) {
                    files.put(cfg.filenames[i], cfg.downloadUrls[i]);
                }
                switch (cfg.type) {
                    case EMBEDDING:
                        emb.put(cfg.directoryName, files);
                        break;
                    case RERANKER:
                        rer.put(cfg.directoryName, files);
                        break;
                    case LLM:
                        llm.put(cfg.directoryName, files);
                        break;
                    case ASR:
                        asr.put(cfg.directoryName, files);
                        break;
                    case TTS:
                        tts.put(cfg.directoryName, files);
                        break;
                }
            }
            // 使用英文分类键，避免中文与空格
            root.put("embedding", emb);
            root.put("reranker", rer);
            root.put("llm", llm);
            root.put("asr", asr);
            root.put("tts", tts);
            return root.toString(2);
        } catch (Exception e) {
            LogManager.logE(TAG, "构建默认模型列表失败", e);
            return "{}";
        }
    }

    private void buildCheckboxesFromModelList() {
        // 清空容器
        containerEmbeddingModels.removeAllViews();
        containerRerankerModels.removeAllViews();
        containerLlmModels.removeAllViews();
        containerAsrModels.removeAllViews();
        containerTtsModels.removeAllViews();
        embeddingCheckBoxMap.clear();
        rerankerCheckBoxMap.clear();
        llmCheckBoxMap.clear();
        asrCheckBoxMap.clear();
        ttsCheckBoxMap.clear();

        // Embedding models
        Map<String, Map<String, String>> emb = modelList.get("embedding");
        if (emb != null) {
            for (String modelName : emb.keySet()) {
                LinearLayout item = new LinearLayout(requireContext());
                item.setOrientation(LinearLayout.VERTICAL);
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(modelName);
                item.addView(cb);
                String comment = modelComments.get(modelName);
                if (comment != null && !comment.isEmpty()) {
                    TextView tv = new TextView(requireContext());
                    tv.setText(comment);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF7A7A7A);
                    item.addView(tv);
                }
                containerEmbeddingModels.addView(item);
                embeddingCheckBoxMap.put(cb, modelName);
            }
        }
        // Reranker models
        Map<String, Map<String, String>> rer = modelList.get("reranker");
        if (rer != null) {
            for (String modelName : rer.keySet()) {
                LinearLayout item = new LinearLayout(requireContext());
                item.setOrientation(LinearLayout.VERTICAL);
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(modelName);
                item.addView(cb);
                String comment = modelComments.get(modelName);
                if (comment != null && !comment.isEmpty()) {
                    TextView tv = new TextView(requireContext());
                    tv.setText(comment);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF7A7A7A);
                    item.addView(tv);
                }
                containerRerankerModels.addView(item);
                rerankerCheckBoxMap.put(cb, modelName);
            }
        }
        // LLM models
        Map<String, Map<String, String>> llm = modelList.get("llm");
        if (llm != null) {
            for (String modelName : llm.keySet()) {
                LinearLayout item = new LinearLayout(requireContext());
                item.setOrientation(LinearLayout.VERTICAL);
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(modelName);
                item.addView(cb);
                String comment = modelComments.get(modelName);
                if (comment != null && !comment.isEmpty()) {
                    TextView tv = new TextView(requireContext());
                    tv.setText(comment);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF7A7A7A);
                    item.addView(tv);
                }
                containerLlmModels.addView(item);
                llmCheckBoxMap.put(cb, modelName);
            }
        }
        // ASR models
        Map<String, Map<String, String>> asr = modelList.get("asr");
        if (asr != null) {
            for (String modelName : asr.keySet()) {
                LinearLayout item = new LinearLayout(requireContext());
                item.setOrientation(LinearLayout.VERTICAL);
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(modelName);
                item.addView(cb);
                String comment = modelComments.get(modelName);
                if (comment != null && !comment.isEmpty()) {
                    TextView tv = new TextView(requireContext());
                    tv.setText(comment);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF7A7A7A);
                    item.addView(tv);
                }
                containerAsrModels.addView(item);
                asrCheckBoxMap.put(cb, modelName);
            }
        }
        // TTS models
        Map<String, Map<String, String>> tts = modelList.get("tts");
        if (tts != null) {
            for (String modelName : tts.keySet()) {
                LinearLayout item = new LinearLayout(requireContext());
                item.setOrientation(LinearLayout.VERTICAL);
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(modelName);
                item.addView(cb);
                String comment = modelComments.get(modelName);
                if (comment != null && !comment.isEmpty()) {
                    TextView tv = new TextView(requireContext());
                    tv.setText(comment);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF7A7A7A);
                    item.addView(tv);
                }
                containerTtsModels.addView(item);
                ttsCheckBoxMap.put(cb, modelName);
            }
        }
    }

    private static String readFileContent(File file) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            byte[] buf = new byte[(int) file.length()];
            int read = fis.read(buf);
            return new String(buf, 0, read);
        } finally {
            fis.close();
        }
    }

    private boolean copyAssetToFile(String assetName, File outFile) {
        try (InputStream is = requireContext().getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "复制默认模型列表失败: " + assetName, e);
            return false;
        }
    }
    
    private boolean downloadFileWithRetry(String urlString, File targetFile) {
        final int MAX_RETRY_ATTEMPTS = 10; // 最大重试次数
        final int CONNECT_TIMEOUT = 60000; // 连接超时60秒
        final int READ_TIMEOUT = 120000; // 读取超时120秒
        
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            // 检查是否已被中断
            if (!isDownloading) {
                LogManager.logI(TAG, "Download interrupted");
                return false;
            }
            
            if (attempt > 1) {
                LogManager.logI(TAG, "Retry attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS + " for URL: " + urlString);
            }
            
            try {
                // 检查是否支持断点续传
                long existingFileSize = 0;
                if (targetFile.exists()) {
                    existingFileSize = targetFile.length();
                    LogManager.logI(TAG, "Found existing file, size: " + existingFileSize + " bytes");
                }
                
                URL url = new URL(urlString);
                String finalUrl = urlString;
                long serverFileSize = -1;
                int headResponseCode = 0;
                HttpURLConnection connection = null;
                
                // 第一步：手动处理重定向，获取真实下载URL
                // 参考 MnnLlmChat 的做法：先发请求看是否有重定向
                HttpURLConnection redirectCheckConn = (HttpURLConnection) url.openConnection();
                redirectCheckConn.setConnectTimeout(CONNECT_TIMEOUT);
                redirectCheckConn.setReadTimeout(READ_TIMEOUT);
                redirectCheckConn.setInstanceFollowRedirects(false); // 不自动跟随，手动处理
                redirectCheckConn.setRequestMethod("HEAD");
                
                int redirectCode = redirectCheckConn.getResponseCode();
                LogManager.logI(TAG, "Initial HEAD response code: " + redirectCode);
                
                // 处理重定向 (301, 302, 303, 307, 308)
                if (redirectCode >= 301 && redirectCode <= 308) {
                    String redirectLocation = redirectCheckConn.getHeaderField("Location");
                    if (redirectLocation != null && !redirectLocation.isEmpty()) {
                        finalUrl = redirectLocation;
                        LogManager.logI(TAG, "Redirect detected, final URL: " + finalUrl);
                    }
                    redirectCheckConn.disconnect();
                    
                    // 第二步：使用最终URL进行HEAD请求获取文件大小
                    connection = (HttpURLConnection) new URL(finalUrl).openConnection();
                    connection.setConnectTimeout(CONNECT_TIMEOUT);
                    connection.setReadTimeout(READ_TIMEOUT);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestMethod("HEAD");
                    
                    headResponseCode = connection.getResponseCode();
                    serverFileSize = connection.getContentLengthLong();
                    
                    if (serverFileSize <= 0) {
                        String contentLengthHeader = connection.getHeaderField("Content-Length");
                        if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
                            try {
                                serverFileSize = Long.parseLong(contentLengthHeader);
                            } catch (NumberFormatException e) {
                                LogManager.logW(TAG, "Failed to parse Content-Length header: " + contentLengthHeader);
                            }
                        }
                    }
                    connection.disconnect();
                } else {
                    // 没有重定向，直接从第一次请求获取文件大小
                    headResponseCode = redirectCode;
                    serverFileSize = redirectCheckConn.getContentLengthLong();
                    
                    if (serverFileSize <= 0) {
                        // 如果 getContentLengthLong() 失败，尝试手动从响应头获取
                        String contentLengthHeader = redirectCheckConn.getHeaderField("Content-Length");
                        if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
                            try {
                                serverFileSize = Long.parseLong(contentLengthHeader);
                            } catch (NumberFormatException e) {
                                LogManager.logW(TAG, "Failed to parse Content-Length header: " + contentLengthHeader);
                            }
                        }
                    }
                    redirectCheckConn.disconnect();
                }
                
                // 打印所有响应头用于调试
                LogManager.logI(TAG, "HEAD response: " + headResponseCode + ", server file size: " + serverFileSize + ", local file size: " + existingFileSize);
                
                // 检查HEAD请求是否成功且返回了有效的文件大小
                if (headResponseCode == HttpURLConnection.HTTP_OK && serverFileSize > 0) {
                    // 比较本地文件大小和服务器文件大小
                    if (existingFileSize == serverFileSize) {
                        // 本地文件大小等于服务器文件大小，文件完整
                        LogManager.logI(TAG, "File already completely downloaded (size: " + existingFileSize + " bytes)");
                        mainHandler.post(() -> appendProgress("File already exists and complete.\n"));
                        return true;
                    } else if (existingFileSize > serverFileSize) {
                        // 本地文件大于服务器文件，文件已损坏，删除重新下载
                        LogManager.logW(TAG, "Local file is corrupted (local: " + existingFileSize + " > server: " + serverFileSize + "), deleting...");
                        targetFile.delete();
                        existingFileSize = 0;
                        mainHandler.post(() -> appendProgress("Corrupted file detected, redownloading...\n"));
                    } else if (existingFileSize > 0) {
                        // 本地文件小于服务器文件，断点续传
                        final long resumeSize = existingFileSize;
                        final long totalSize = serverFileSize;
                        LogManager.logI(TAG, "File partially downloaded, will resume (local: " + resumeSize + "/" + totalSize + ")");
                        mainHandler.post(() -> appendProgress("Resuming from " + resumeSize + " bytes...\n"));
                    }
                } else if (existingFileSize > 0) {
                    // HEAD 请求失败，尝试用 GET 请求获取文件大小（fallback）
                    LogManager.logW(TAG, "HEAD request failed, trying GET request to obtain file size");
                    try {
                        HttpURLConnection getConnection = (HttpURLConnection) new URL(finalUrl).openConnection();
                        getConnection.setConnectTimeout(CONNECT_TIMEOUT);
                        getConnection.setReadTimeout(READ_TIMEOUT);
                        getConnection.setInstanceFollowRedirects(true);
                        getConnection.setRequestMethod("GET");
                        
                        int getResponseCode = getConnection.getResponseCode();
                        long getServerFileSize = getConnection.getContentLengthLong();
                        
                        if (getServerFileSize <= 0) {
                            String contentLengthHeader = getConnection.getHeaderField("Content-Length");
                            if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
                                try {
                                    getServerFileSize = Long.parseLong(contentLengthHeader);
                                } catch (NumberFormatException e) {
                                    LogManager.logW(TAG, "Failed to parse Content-Length from GET: " + contentLengthHeader);
                                }
                            }
                        }
                        
                        getConnection.disconnect();
                        
                        LogManager.logI(TAG, "GET response: " + getResponseCode + ", server file size: " + getServerFileSize);
                        
                        if (getResponseCode == HttpURLConnection.HTTP_OK && getServerFileSize > 0) {
                            serverFileSize = getServerFileSize;
                            
                            if (existingFileSize == serverFileSize) {
                                LogManager.logI(TAG, "File already completely downloaded (size: " + existingFileSize + " bytes)");
                                mainHandler.post(() -> appendProgress("File already exists and complete.\n"));
                                return true;
                            } else if (existingFileSize > serverFileSize) {
                                LogManager.logW(TAG, "Local file is corrupted (local: " + existingFileSize + " > server: " + serverFileSize + "), deleting...");
                                targetFile.delete();
                                existingFileSize = 0;
                                mainHandler.post(() -> appendProgress("Corrupted file detected, redownloading...\n"));
                            } else {
                                final long resumeSize = existingFileSize;
                                final long totalSize = serverFileSize;
                                LogManager.logI(TAG, "File partially downloaded, will resume (local: " + resumeSize + "/" + totalSize + ")");
                                mainHandler.post(() -> appendProgress("Resuming from " + resumeSize + " bytes...\n"));
                            }
                        } else {
                            LogManager.logW(TAG, "GET request also failed to get file size, will attempt download anyway");
                        }
                    } catch (Exception e) {
                        LogManager.logW(TAG, "GET fallback failed", e);
                    }
                } else {
                    // HEAD请求失败且没有本地文件，记录警告但继续尝试下载
                    LogManager.logW(TAG, "HEAD request failed or returned invalid size, will attempt download anyway");
                }
                
                // 重新建立连接进行下载，使用重定向后的最终URL
                connection = (HttpURLConnection) new URL(finalUrl).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setInstanceFollowRedirects(true); // 确保下载时也跟随重定向
                
                // 设置断点续传请求头
                if (existingFileSize > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existingFileSize + "-");
                    LogManager.logI(TAG, "Attempting resume download from byte: " + existingFileSize);
                }
                
                int responseCode = connection.getResponseCode();
                
                // 检查响应码
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // 支持断点续传
                    LogManager.logI(TAG, "Resume download supported (HTTP 206)");
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 不支持断点续传，重新下载
                    if (existingFileSize > 0) {
                        LogManager.logI(TAG, "Resume not supported, restarting download");
                        targetFile.delete();
                        existingFileSize = 0;
                    }
                } else if (responseCode == 416) {
                    // HTTP 416: Requested Range Not Satisfiable
                    // 说明请求的范围超出文件大小，文件已完整下载
                    LogManager.logI(TAG, "HTTP 416: File already completely downloaded (Range Not Satisfiable)");
                    connection.disconnect();
                    mainHandler.post(() -> appendProgress("File already complete (HTTP 416).\n"));
                    return true;
                } else {
                    LogManager.logE(TAG, "Download failed, HTTP response code: " + responseCode + ", attempt: " + attempt);
                    connection.disconnect();
                    
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        final int currentAttempt = attempt;
                        mainHandler.post(() -> appendProgress("Retry " + currentAttempt + "/" + MAX_RETRY_ATTEMPTS + "..."));
                        Thread.sleep(2000 * attempt); // 递增延迟重试
                        continue;
                    } else {
                        LogManager.logE(TAG, "All retry attempts failed for URL: " + urlString);
                        return false;
                    }
                }
                
                long totalFileSize = connection.getContentLengthLong();
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // 对于断点续传，需要加上已下载的部分
                    totalFileSize += existingFileSize;
                }
                
                // 初始化进度显示
                if (attempt == 1) {
                    final long finalExistingFileSize = existingFileSize;
                    if (existingFileSize == 0) {
                        mainHandler.post(() -> appendProgress(getString(R.string.log_progress) + "(%):0"));
                    } else {
                        mainHandler.post(() -> appendProgress("Resuming from " + formatFileSize(finalExistingFileSize) + "..."));
                    }
                }
                
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(targetFile, existingFileSize > 0)) {
                    
                    byte[] buffer = new byte[32768]; // 32KB缓冲区
                    long totalBytesRead = existingFileSize; // 包含已下载的字节数
                    int bytesRead;
                    // FIXED: Track last reported 10% milestone, not actual progress
                    // This ensures every 10% increment is displayed correctly
                    int lastReportedTenPercent = totalFileSize > 0 ? ((int) ((totalBytesRead * 100) / totalFileSize) / 10) * 10 : 0;
                    
                    LogManager.logI(TAG, "Starting download, total file size: " + totalFileSize + " bytes, resume from: " + existingFileSize);
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        // 检查是否已被中断
                        if (!isDownloading) {
                            LogManager.logI(TAG, "Download interrupted");
                            return false;
                        }
                        
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        
                        if (totalFileSize > 0) {
                            final int progress = (int) ((totalBytesRead * 100) / totalFileSize);
                            
                            // 每10%显示一次进度: 0..10..20..30..40..50..60..70..80..90..100
                            // FIXED: Calculate current 10% milestone
                            int currentTenPercent = (progress / 10) * 10;
                            
                            // Display all missing milestones from last to current
                            // This fixes small files that jump from 0 to 100 quickly
                            if (currentTenPercent > lastReportedTenPercent && currentTenPercent <= 100) {
                                // Display all intermediate milestones
                                for (int milestone = lastReportedTenPercent + 10; milestone <= currentTenPercent; milestone += 10) {
                                    final String progressText = ".." + milestone;
                                    LogManager.logI(TAG, "Progress update: " + milestone + "%");
                                    mainHandler.post(() -> appendProgress(progressText));
                                }
                                lastReportedTenPercent = currentTenPercent;
                            }
                        }
                    }
                }
                
                // 完成后显示结果
                if (totalFileSize > 0) {
                    mainHandler.post(() -> appendProgress("..100 " + getString(R.string.log_100_percent) + "\n"));
                }
                
                LogManager.logI(TAG, "Download completed successfully");
                return true;
                
            } catch (IOException | InterruptedException e) {
                LogManager.logE(TAG, "Download failed on attempt " + attempt + ": " + urlString, e);
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    final int currentAttempt = attempt;
                    mainHandler.post(() -> appendProgress("Error, retry " + currentAttempt + "/" + MAX_RETRY_ATTEMPTS + "..."));
                    try {
                        Thread.sleep(2000 * attempt); // 递增延迟重试：2s, 4s, 6s...
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    LogManager.logE(TAG, "All retry attempts failed for: " + urlString);
                    return false;
                }
            }
        }
        
        return false;
    }
    
    private boolean isFileCompletelyDownloaded(String urlString, File targetFile) {
        if (!targetFile.exists() || targetFile.length() == 0) {
            return false;
        }
        
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                long serverFileSize = connection.getContentLengthLong();
                long localFileSize = targetFile.length();
                connection.disconnect();
                
                // 如果本地文件大小大于等于服务器文件大小，认为已完整下载
                return serverFileSize > 0 && localFileSize >= serverFileSize;
            }
            connection.disconnect();
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to check file completeness: " + targetFile.getName(), e);
        }
        
        return false;
    }
    
    private void appendProgress(String text) {
        // Append text to progress buffer
        progressText.append(text);
        
        // Update TextView content
        textViewProgress.setText(progressText.toString());
        
        // Auto-scroll to bottom: try both ScrollView and TextView scrolling
        textViewProgress.post(() -> {
            // Scroll TextView itself to bottom
            int scrollAmount = textViewProgress.getLayout().getLineTop(textViewProgress.getLineCount()) 
                             - textViewProgress.getHeight();
            if (scrollAmount > 0) {
                textViewProgress.scrollTo(0, scrollAmount);
            }
            
            // Also scroll the parent ScrollView to bottom if available
            if (scrollViewProgress != null) {
                scrollViewProgress.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }
    
    private void acquireWakeLocks() {
        try {
            PowerManager powerManager = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineAI:ModelDownload");
            wakeLock.acquire(30 * 60 * 1000L); // 30分钟超时
        } catch (Exception e) {
            LogManager.logE(TAG, getString(R.string.log_acquire_wakelock_failed), e);
        }
    }
    
    private void releaseWakeLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, getString(R.string.log_release_wakelock_failed), e);
        }
    }
    
    private void finishDownload() {
        isDownloading = false;
        buttonDownload.setText(R.string.button_download_selected_models);
        releaseWakeLocks();
    }
    
    private void stopDownload() {
        if (isDownloading) {
            isDownloading = false;
            
            // 中断下载线程
            if (downloadExecutor != null) {
                downloadExecutor.shutdownNow();
                downloadExecutor = Executors.newSingleThreadExecutor();
            }
            
            mainHandler.post(() -> {
                appendProgress("\nDownload interrupted\n");
                buttonDownload.setText(R.string.button_download_selected_models);
                releaseWakeLocks();
            });
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (downloadExecutor != null) {
            downloadExecutor.shutdown();
        }
        
        releaseWakeLocks();
    }
    
    /**
     * Smart merge: Add missing models from assets to user's file
     * - Keep user's original order and custom models
     * - Only add models that don't exist in user's file
     * - Only write file if there are new models to add
     */
    private void mergeModelListFromAssets(File userListFile) {
        try {
            // Read assets file (APP built-in models)
            org.json.JSONObject assetsJson = null;
            try (InputStream is = requireContext().getAssets().open("ModelDownloadList.txt")) {
                String assetsContent = readStreamContent(is);
                assetsContent = assetsContent.replace("\\/", "/");
                assetsJson = new org.json.JSONObject(assetsContent);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to read assets ModelDownloadList.txt", e);
                // Fallback: If assets file not found, create from built-in configs
                String json = buildDefaultModelListJsonFromBuiltins();
                json = json.replace("\\/", "/");
                assetsJson = new org.json.JSONObject(json);
            }
            
            // Read user file (preserve user's order and custom models)
            org.json.JSONObject userJson = null;
            if (userListFile.exists()) {
                try {
                    String userContent = readFileContent(userListFile);
                    userContent = userContent.replace("\\/", "/");
                    userJson = new org.json.JSONObject(userContent);
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to read user ModelDownloadList.txt, will use assets only", e);
                }
            }
            
            // If user file doesn't exist, just copy from assets
            if (userJson == null) {
                try (FileOutputStream fos = new FileOutputStream(userListFile)) {
                    String pretty = assetsJson.toString(2).replace("\\/", "/");
                    fos.write(pretty.getBytes(StandardCharsets.UTF_8));
                    LogManager.logI(TAG, "Created ModelDownloadList.txt from assets");
                }
                return;
            }
            
            // Check each category and add missing models from assets
            String[] categories = new String[]{"embedding", "reranker", "llm", "asr", "tts"};
            boolean hasNewModels = false;
            
            for (String category : categories) {
                // Ensure category exists in user file
                if (!userJson.has(category)) {
                    userJson.put(category, new org.json.JSONObject());
                }
                org.json.JSONObject userCategory = userJson.getJSONObject(category);
                
                // Check each model in assets
                if (assetsJson.has(category)) {
                    org.json.JSONObject assetsCategory = assetsJson.getJSONObject(category);
                    Iterator<String> assetsModels = assetsCategory.keys();
                    
                    while (assetsModels.hasNext()) {
                        String modelName = assetsModels.next();
                        
                        // If user doesn't have this model, add it
                        if (!userCategory.has(modelName)) {
                            userCategory.put(modelName, assetsCategory.getJSONObject(modelName));
                            hasNewModels = true;
                            LogManager.logI(TAG, "Added new model from assets: " + category + "/" + modelName);
                        }
                        // If user already has this model, skip (keep user's version)
                    }
                }
            }
            
            // Only write file if there are new models added
            if (hasNewModels) {
                try (FileOutputStream fos = new FileOutputStream(userListFile)) {
                    String pretty = userJson.toString(2).replace("\\/", "/");
                    fos.write(pretty.getBytes(StandardCharsets.UTF_8));
                    LogManager.logI(TAG, "Updated ModelDownloadList.txt with new models");
                }
            } else {
                LogManager.logI(TAG, "No new models to add, user file is up-to-date");
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to merge ModelDownloadList.txt", e);
            // Fallback: If merge fails and user file doesn't exist, copy from assets
            if (!userListFile.exists()) {
                copyAssetToFile("ModelDownloadList.txt", userListFile);
            }
        }
    }
    
    /**
     * Read content from InputStream
     */
    private String readStreamContent(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
    
    /**
     * Format file size to human-readable format (GB/MB/KB)
     * @param bytes File size in bytes
     * @return Formatted string like "1.5GB", "256MB", "512KB"
     */
    private String formatFileSize(long bytes) {
        if (bytes < 0) {
            return "Unknown";
        }
        
        final double KB = 1024;
        final double MB = KB * 1024;
        final double GB = MB * 1024;
        
        if (bytes >= GB) {
            return String.format("%.1fGB", bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.0fMB", bytes / MB);
        } else if (bytes >= KB) {
            return String.format("%.0fKB", bytes / KB);
        } else {
            return bytes + "B";
        }
    }
    
    // 模型配置类
    private static class ModelConfig {
        final String directoryName;
        final ModelType type;
        final String[] downloadUrls;
        final String[] filenames;
        
        ModelConfig(String directoryName, ModelType type, String[] downloadUrls, String[] filenames) {
            this.directoryName = directoryName;
            this.type = type;
            this.downloadUrls = downloadUrls;
            this.filenames = filenames;
        }
    }
    
    // 模型类型枚举
    private enum ModelType {
        EMBEDDING, RERANKER, LLM, ASR, TTS
    }
}
