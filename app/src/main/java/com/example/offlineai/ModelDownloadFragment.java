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
        textViewProgress = view.findViewById(R.id.textViewProgress);
        // 修复类型转换错误：根据布局文件，textViewProgress的父视图是LinearLayout，不是ScrollView
        // 因此我们不尝试获取ScrollView引用，将其设为null
        scrollViewProgress = null;
        buttonDownload = view.findViewById(R.id.buttonDownload);
        
        // 设置进度文本框支持文本选择和滚动
        textViewProgress.setTextIsSelectable(true);
        textViewProgress.setFocusable(true);
        textViewProgress.setFocusableInTouchMode(true);
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
    
    private List<String> checkDirectoryConflicts(List<String> selectedModels) {
        List<String> conflicts = new ArrayList<>();
        String basePath = ConfigManager.getModelPath(getContext());
        for (String modelName : selectedModels) {
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
        // 删除冲突目录中的文件，然后开始下载
        String basePath = ConfigManager.getModelPath(getContext());
        for (String modelName : selectedModels) {
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
        // 根据类别查找对应的文件映射
        Map<String, String> filesMap = null;
        if (modelList.containsKey("embedding") && modelList.get("embedding").containsKey(modelName)) {
            filesMap = modelList.get("embedding").get(modelName);
        } else if (modelList.containsKey("reranker") && modelList.get("reranker").containsKey(modelName)) {
            filesMap = modelList.get("reranker").get(modelName);
        } else if (modelList.containsKey("llm") && modelList.get("llm").containsKey(modelName)) {
            filesMap = modelList.get("llm").get(modelName);
        }
        if (filesMap == null || filesMap.isEmpty()) {
            mainHandler.post(() -> appendProgress(getString(R.string.common_unknown_model) + ": " + modelName + "\n"));
            return false;
        }
        
        mainHandler.post(() -> appendProgress("\n" + getString(R.string.log_start_downloading) + ": " + modelName + "\n"));
        
        // 创建目标目录（统一到模型目录）
        File targetDir = new File(ConfigManager.getModelPath(getContext()), modelName);
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
            
            mainHandler.post(() -> appendProgress(getString(R.string.log_downloading_file) + ": " + filename + "\n"));
            
            // 仅使用来自 JSON 的主地址进行下载（移除备份地址逻辑）
            boolean success = downloadFileWithRetry(url, new File(targetDir, filename));
            
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
            default:
                throw new IllegalArgumentException("未知模型" + "类型: " + config.type);
        }
        
        return new File(basePath, config.directoryName);
    }

    // === JSON 加载与 UI 构建 ===
    private void ensureAndLoadModelList() {
        try {
            File baseDir = new File(ConfigManager.getModelPath(requireContext()));
            if (!baseDir.exists()) baseDir.mkdirs();
            File listFile = new File(baseDir, "ModelDownloadList.txt");
            if (!listFile.exists()) {
                // 首选：从 assets 复制默认文件 ModelDownloadList.txt
                boolean copied = copyAssetToFile("ModelDownloadList.txt", listFile);
                if (!copied) {
                    // 兜底：若复制失败，则使用内置配置生成默认 JSON
                    String json = buildDefaultModelListJsonFromBuiltins();
                    json = json.replace("\\/", "/");
                    try (FileOutputStream fos = new FileOutputStream(listFile)) {
                        fos.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
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
            String[] categoriesPreferred = new String[]{"embedding", "reranker", "llm"};
            String[] categoriesLegacy = new String[]{"嵌入式模型", "重排模型", "LLM模型"};
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
                }
            }
            // 使用英文分类键，避免中文与空格
            root.put("embedding", emb);
            root.put("reranker", rer);
            root.put("llm", llm);
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
        embeddingCheckBoxMap.clear();
        rerankerCheckBoxMap.clear();
        llmCheckBoxMap.clear();

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
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                
                // 首先获取文件总大小来检查是否已完整下载
                connection.setRequestMethod("HEAD");
                int headResponseCode = connection.getResponseCode();
                long serverFileSize = connection.getContentLengthLong();
                connection.disconnect();
                
                // 检查文件是否已经完整下载
                if (existingFileSize > 0 && serverFileSize > 0 && existingFileSize >= serverFileSize) {
                    LogManager.logI(TAG, "File already completely downloaded, size: " + existingFileSize + " bytes");
                    mainHandler.post(() -> appendProgress("File already exists and complete.\n"));
                    return true;
                }
                
                // 重新建立连接进行下载
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                
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
                        mainHandler.post(() -> appendProgress(getString(R.string.log_progress) + ":"));
                    } else {
                        mainHandler.post(() -> appendProgress("Resuming from " + (finalExistingFileSize / 1024 / 1024) + "MB..."));
                    }
                }
                
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(targetFile, existingFileSize > 0)) {
                    
                    byte[] buffer = new byte[32768]; // 32KB缓冲区
                    long totalBytesRead = existingFileSize; // 包含已下载的字节数
                    int bytesRead;
                    int lastReportedProgress = totalFileSize > 0 ? (int) ((totalBytesRead * 100) / totalFileSize) : 0;
                    
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
                            
                            // 确保每个百分点都显示一个点，防止跳跃
                            if (progress > lastReportedProgress) {
                                // 构建所有需要添加的点号和百分比
                                StringBuilder progressText = new StringBuilder();
                                while (progress > lastReportedProgress && lastReportedProgress < 100) {
                                    lastReportedProgress++;
                                    progressText.append(".");
                                    
                                    // 每10个点插入百分比数字，不换行
                                    if (lastReportedProgress % 10 == 0) {
                                        progressText.append(lastReportedProgress).append("%");
                                    }
                                }
                                
                                final String textToAdd = progressText.toString();
                                LogManager.logI(TAG, "Progress update: " + lastReportedProgress + "%, content: " + textToAdd);
                                
                                // 一次性添加所有内容，减少UI更新次数
                                mainHandler.post(() -> appendProgress(textToAdd));
                            }
                        }
                    }
                }
                
                // 完成后显示结果
                if (totalFileSize > 0) {
                    mainHandler.post(() -> appendProgress(" " + getString(R.string.log_100_percent) + "\n"));
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
    
    private void appendProgress(String text) {
        // 简单的文本追加
        progressText.append(text);
        
        // 直接设置文本内容
        textViewProgress.setText(progressText.toString());
        
        // 自动滚动到底部（如果scrollViewProgress不为null）
        if (scrollViewProgress != null) {
            scrollViewProgress.post(() -> {
                scrollViewProgress.fullScroll(ScrollView.FOCUS_DOWN);
            });
        }
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
        buttonDownload.setText("Download Selected Models");
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
                buttonDownload.setText("Download Selected Models");
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
        EMBEDDING, RERANKER, LLM
    }
}
