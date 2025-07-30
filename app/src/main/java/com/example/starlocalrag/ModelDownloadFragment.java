package com.example.starlocalrag;

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
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.example.starlocalrag.ConfigManager;
import com.example.starlocalrag.LogManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelDownloadFragment extends Fragment {
    private static final String TAG = "ModelDownloadFragment";
    
    // Constants removed - now using string resources
    
    // UI组件
    private CheckBox checkBoxBgeM3;
    private CheckBox checkBoxBgeReranker;
    private CheckBox checkBoxQwen06B;
    private CheckBox checkBoxQwen17B;
    private TextView textViewProgress;
    private ScrollView scrollViewProgress;
    private Button buttonDownload;
    
    // 下载相关
    private ExecutorService downloadExecutor;
    private Handler mainHandler;
    private boolean isDownloading = false;
    
    // 进度显示
    private StringBuilder progressText = new StringBuilder();
    private int lastReportedProgress = 0;
    
    // 电源管理
    private PowerManager.WakeLock wakeLock;
    
    // 模型配置
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
        setupListeners();
        
        downloadExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化进度文本
        progressText.setLength(0);
        progressText.append(textViewProgress.getText());
        
        return view;
    }
    
    private void initViews(View view) {
        checkBoxBgeM3 = view.findViewById(R.id.checkBoxBgeM3);
        checkBoxBgeReranker = view.findViewById(R.id.checkBoxBgeReranker);
        checkBoxQwen06B = view.findViewById(R.id.checkBoxQwen06B);
        checkBoxQwen17B = view.findViewById(R.id.checkBoxQwen17B);
        textViewProgress = view.findViewById(R.id.textViewProgress);
        scrollViewProgress = (ScrollView) textViewProgress.getParent();
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
        
        if (checkBoxBgeM3.isChecked()) {
            selected.add("bge-m3");
        }
        if (checkBoxBgeReranker.isChecked()) {
            selected.add("bge-reranker");
        }
        if (checkBoxQwen06B.isChecked()) {
            selected.add("qwen-0.6b");
        }
        if (checkBoxQwen17B.isChecked()) {
            selected.add("qwen-1.7b");
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
        
        for (String modelKey : selectedModels) {
            ModelConfig config = MODEL_CONFIGS.get(modelKey);
            if (config != null) {
                File targetDir = getTargetDirectory(config);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    conflicts.add(config.directoryName);
                }
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
        for (String modelKey : selectedModels) {
            ModelConfig config = MODEL_CONFIGS.get(modelKey);
            if (config != null) {
                File targetDir = getTargetDirectory(config);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    // 删除目录中的所有文件
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
    
    private boolean downloadModel(String modelKey) {
        ModelConfig config = MODEL_CONFIGS.get(modelKey);
        if (config == null) {
            mainHandler.post(() -> appendProgress(getString(R.string.common_unknown_model) + ": " + modelKey + "\n"));
            return false;
        }
        
        mainHandler.post(() -> appendProgress("\n" + getString(R.string.log_start_downloading) + ": " + config.directoryName + "\n"));
        
        // 创建目标目录
        File targetDir = getTargetDirectory(config);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        
        // 下载所有文件
        for (int i = 0; i < config.downloadUrls.length; i++) {
            // 检查是否已被中断
            if (!isDownloading) {
                mainHandler.post(() -> appendProgress("Download interrupted\n"));
                return false;
            }
            
            String url = config.downloadUrls[i];
            String filename = config.filenames[i];
            
            mainHandler.post(() -> appendProgress(getString(R.string.log_downloading_file) + ": " + filename + "\n"));
            
            // 准备主URL和备用URL
            String[] urlsToTry = {
                url,
                url.replace("hf-mirror.com", "huggingface.co")
            };
            
            boolean success = false;
            for (int urlIndex = 0; urlIndex < urlsToTry.length && !success; urlIndex++) {
                if (urlIndex > 0) {
                    mainHandler.post(() -> appendProgress(getString(R.string.log_trying_backup_url) + "\n"));
                }
                success = downloadFileWithRetry(urlsToTry[urlIndex], new File(targetDir, filename));
            }
            
            if (!success) {
                mainHandler.post(() -> appendProgress(getString(R.string.log_download_failed) + ": " + filename + "\n"));
                return false;
            }
        }
        
        mainHandler.post(() -> appendProgress(config.directoryName + " " + getString(R.string.log_download_completed) + "\n"));
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
        
        // 自动滚动到底部
        scrollViewProgress.post(() -> {
            scrollViewProgress.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }
    
    private void acquireWakeLocks() {
        try {
            PowerManager powerManager = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarLocalRAG:ModelDownload");
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