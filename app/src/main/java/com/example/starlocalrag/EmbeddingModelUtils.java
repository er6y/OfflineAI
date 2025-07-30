package com.example.starlocalrag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.example.starlocalrag.ConfigManager;
import com.example.starlocalrag.StateDisplayManager;
import com.example.starlocalrag.AppConstants;

/**
 * 模型工具类，提供嵌入模型和重排模型检测和查找的公共方法
 */
public class EmbeddingModelUtils {
    private static final String TAG = "EmbeddingModelUtils";
    
    /**
     * 检查并加载嵌入模型和重排模型
     * @param context 上下文
     * @param vectorDb 向量数据库处理器
     * @param callback 回调函数，参数为找到的模型路径，如果未找到则为null
     * @param modelSelectedCallback 模型选择回调，当用户选择了模型后调用
     */
    public static void checkAndLoadEmbeddingModel(
            Context context,
            SQLiteVectorDatabaseHandler vectorDb,
            Consumer<String> callback,
            ModelSelectedCallback modelSelectedCallback) {
        
        LogManager.logD(TAG, "=== Starting checkAndLoadEmbeddingModel ===");
        
        // 获取配置路径
        String embeddingModelPath = ConfigManager.getEmbeddingModelPath(context);
        String rerankerModelPath = ConfigManager.getRerankerModelPath(context);
        
        LogManager.logD(TAG, "Embedding model path: " + embeddingModelPath);
        LogManager.logD(TAG, "Reranker model path: " + rerankerModelPath);
        
        // 获取元数据中的模型目录
        String modeldir = vectorDb.getMetadata().getModeldir();
        String rerankerdir = vectorDb.getMetadata().getRerankerdir();
        
        LogManager.logD(TAG, "Metadata modeldir: '" + modeldir + "'");
        LogManager.logD(TAG, "Metadata rerankerdir: '" + rerankerdir + "'");
        
        boolean needEmbeddingModelSelection = false;
        boolean needRerankerModelSelection = false;
        String modelPath = null;
        
        // 检查嵌入模型
        LogManager.logD(TAG, "=== Checking embedding model ===");
        if (modeldir != null && !modeldir.isEmpty()) {
            LogManager.logD(TAG, "Modeldir is not empty, checking if it exists in available models");
            // 检查modeldir是否在embedding_model_path下的所有模型目录中存在
            File embeddingModelDir = new File(embeddingModelPath);
            boolean embeddingModelFound = false;
            
            LogManager.logD(TAG, "Embedding model directory exists: " + embeddingModelDir.exists());
            LogManager.logD(TAG, "Embedding model directory is directory: " + embeddingModelDir.isDirectory());
            
            if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
                File[] directories = embeddingModelDir.listFiles(File::isDirectory);
                LogManager.logD(TAG, "Found " + (directories != null ? directories.length : 0) + " subdirectories");
                if (directories != null) {
                    for (File dir : directories) {
                        LogManager.logD(TAG, "Checking directory: " + dir.getName() + " against modeldir: " + modeldir);
                        if (dir.getName().equals(modeldir)) {
                            LogManager.logD(TAG, "Found matching directory: " + dir.getName());
                            // 检查目录中是否有模型文件
                            File[] modelFiles = dir.listFiles(file -> isModelFile(file));
                            LogManager.logD(TAG, "Found " + (modelFiles != null ? modelFiles.length : 0) + " model files in directory");
                            if (modelFiles != null && modelFiles.length > 0) {
                                modelPath = modelFiles[0].getAbsolutePath();
                                embeddingModelFound = true;
                                LogManager.logD(TAG, "Embedding model found at: " + modelPath);
                                break;
                            }
                        }
                    }
                }
                
                // 如果是根目录（空字符串），检查根目录
                if (!embeddingModelFound && modeldir.isEmpty()) {
                    LogManager.logD(TAG, "Modeldir is empty, checking root directory for model files");
                    File[] rootModelFiles = embeddingModelDir.listFiles(file -> isModelFile(file));
                    LogManager.logD(TAG, "Found " + (rootModelFiles != null ? rootModelFiles.length : 0) + " model files in root directory");
                    if (rootModelFiles != null && rootModelFiles.length > 0) {
                        modelPath = rootModelFiles[0].getAbsolutePath();
                        embeddingModelFound = true;
                        LogManager.logD(TAG, "Embedding model found in root at: " + modelPath);
                    }
                }
            }
            
            if (!embeddingModelFound) {
                LogManager.logD(TAG, "嵌入模型目录 '" + modeldir + "' 在可用模型目录中未找到");
                needEmbeddingModelSelection = true;
            } else {
                LogManager.logD(TAG, "Embedding model validation passed, no selection needed");
            }
        } else {
            LogManager.logD(TAG, "元数据中没有modeldir配置或为空");
            needEmbeddingModelSelection = true;
        }
        
        // 检查重排模型（如果rerankerdir不为空且不为"无"）
        String noneRerankerText = context.getString(R.string.common_none);
        if (rerankerdir != null && !rerankerdir.isEmpty() && !rerankerdir.equals(noneRerankerText)) {
            File rerankerModelDir = new File(rerankerModelPath);
            boolean rerankerModelFound = false;
            
            if (rerankerModelDir.exists() && rerankerModelDir.isDirectory()) {
                File[] directories = rerankerModelDir.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File dir : directories) {
                        if (dir.getName().equals(rerankerdir)) {
                            rerankerModelFound = true;
                            break;
                        }
                    }
                }
            }
            
            if (!rerankerModelFound) {
                LogManager.logD(TAG, "重排模型目录 '" + rerankerdir + "' 在可用模型目录中未找到");
                needRerankerModelSelection = true;
            }
        }

        // 如果需要选择模型
        LogManager.logD(TAG, "=== Final decision ===");
        LogManager.logD(TAG, "Need embedding model selection: " + needEmbeddingModelSelection);
        LogManager.logD(TAG, "Need reranker model selection: " + needRerankerModelSelection);
        LogManager.logD(TAG, "Final model path: " + modelPath);
        
        if (needEmbeddingModelSelection || needRerankerModelSelection) {
            LogManager.logD(TAG, "需要选择模型，嵌入模型: " + needEmbeddingModelSelection + ", 重排模型: " + needRerankerModelSelection);
            LogManager.logW(TAG, "*** MODEL SELECTION DIALOG WILL BE SHOWN ***");
            
            // 获取可用的嵌入模型列表
            List<String> availableEmbeddingModels = new ArrayList<>();
            File embeddingModelDir = new File(embeddingModelPath);
            if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
                File[] directories = embeddingModelDir.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File dir : directories) {
                        // 检查目录中是否有模型文件
                        File[] modelFiles = dir.listFiles(file -> isModelFile(file));
                        if (modelFiles != null && modelFiles.length > 0) {
                            availableEmbeddingModels.add(dir.getName());
                        }
                    }
                }
                
                // 也检查根目录中的模型文件
                File[] rootModelFiles = embeddingModelDir.listFiles(file -> isModelFile(file));
                if (rootModelFiles != null && rootModelFiles.length > 0) {
                    availableEmbeddingModels.add(context.getString(R.string.embedding_model_root_directory));
                }
            }
            
            // 获取可用的重排模型列表
            List<String> availableRerankerModels = new ArrayList<>();
            availableRerankerModels.add(context.getString(R.string.common_none)); // 添加"无"选项
            File rerankerModelDir = new File(rerankerModelPath);
            if (rerankerModelDir.exists() && rerankerModelDir.isDirectory()) {
                File[] directories = rerankerModelDir.listFiles(File::isDirectory);
                if (directories != null) {
                    for (File dir : directories) {
                        availableRerankerModels.add(dir.getName());
                    }
                }
            }
            
            if (!availableEmbeddingModels.isEmpty()) {
                // 弹出模型选择对话框
                showModelSelectionDialog(context, modeldir, rerankerdir, availableEmbeddingModels, availableRerankerModels, 
                    embeddingModelPath, rerankerModelPath, vectorDb, modelSelectedCallback);
                callback.accept(null); // 返回null，表示需要等待用户选择
            } else {
                LogManager.logE(TAG, "在嵌入模型目录中未找到可用的模型文件");
                callback.accept(null); // 返回null，表示没有找到模型
            }
        } else {
            // 模型文件存在，直接返回路径
            callback.accept(modelPath);
        }
    }
    
    /**
     * 显示模型选择对话框
     */
    private static void showModelSelectionDialog(
            Context context,
            String originalEmbeddingModel,
            String originalRerankerModel,
            List<String> availableEmbeddingModels,
            List<String> availableRerankerModels,
            String embeddingModelPath,
            String rerankerModelPath,
            SQLiteVectorDatabaseHandler vectorDb,
            ModelSelectedCallback callback) {
        
        // 确保在主线程中执行UI操作
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                LogManager.logD(TAG, "开始创建模型选择对话框");
                long startTime = System.currentTimeMillis();
                
                // 使用自定义布局创建对话框
                View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_model_selection, null);
                
                // 获取控件
                TextView textViewInfo = dialogView.findViewById(R.id.textViewInfo);
                Spinner spinnerModels = dialogView.findViewById(R.id.spinnerModels);
                Spinner spinnerRerankers = dialogView.findViewById(R.id.spinnerRerankers);
                CheckBox checkBoxRemember = dialogView.findViewById(R.id.checkBoxRemember);
                
                // 设置提示信息
                String infoText = "模型配置不匹配，请重新选择：";
                if (originalEmbeddingModel != null && !originalEmbeddingModel.isEmpty()) {
                    infoText += "\n嵌入模型 '" + originalEmbeddingModel + "' 不可用";
                }
                String noneRerankerText = context.getString(R.string.common_none);
        if (originalRerankerModel != null && !originalRerankerModel.isEmpty() && !originalRerankerModel.equals(noneRerankerText)) {
                    infoText += "\n重排模型 '" + originalRerankerModel + "' 不可用";
                }
                textViewInfo.setText(infoText);
                
                // 创建嵌入模型列表适配器
                ArrayAdapter<String> embeddingAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, availableEmbeddingModels);
                embeddingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerModels.setAdapter(embeddingAdapter);
                
                // 创建重排模型列表适配器
                ArrayAdapter<String> rerankerAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, availableRerankerModels);
                rerankerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerRerankers.setAdapter(rerankerAdapter);
                
                // 查找原始嵌入模型在列表中的位置
                int originalEmbeddingModelIndex = availableEmbeddingModels.indexOf(originalEmbeddingModel);
                if (originalEmbeddingModelIndex >= 0) {
                    spinnerModels.setSelection(originalEmbeddingModelIndex);
                }
                
                // 查找原始重排模型在列表中的位置
                int originalRerankerModelIndex = availableRerankerModels.indexOf(originalRerankerModel);
                if (originalRerankerModelIndex >= 0) {
                    spinnerRerankers.setSelection(originalRerankerModelIndex);
                } else {
                    // 默认选择"无"
                    spinnerRerankers.setSelection(0);
                }
                
                LogManager.logD(TAG, "准备创建对话框，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                
                // 创建状态显示管理器
                StateDisplayManager stateDisplayManager = new StateDisplayManager(context);
                
                // 创建对话框 - 使用原生按钮
                AlertDialog.Builder builder = new AlertDialog.Builder(context)
                        .setTitle(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_TITLE_SELECT_EMBEDDING_RERANKER))
                        .setView(dialogView)
                        .setCancelable(false) // 防止用户通过点击外部或返回键关闭对话框
                        .setPositiveButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_OK), (dialog, which) -> {
                            LogManager.logD(TAG, "确定按钮被点击");
                            long processStartTime = System.currentTimeMillis();
                            
                            String selectedEmbeddingModel = (String) spinnerModels.getSelectedItem();
                            String selectedRerankerModel = (String) spinnerRerankers.getSelectedItem();
                            boolean rememberChoice = checkBoxRemember.isChecked();
                            
                            if (selectedEmbeddingModel != null) {
                                LogManager.logD(TAG, "用户选择了嵌入模型: " + selectedEmbeddingModel + ", 重排模型: " + selectedRerankerModel + ", 记住选择: " + rememberChoice + ", 处理耗时: " + (System.currentTimeMillis() - processStartTime) + "ms");
                                
                                // 在后台线程中处理选定的模型，避免阻塞UI线程
                                new Thread(() -> {
                                    LogManager.logD(TAG, "开始在后台线程处理选定的模型");
                                    long threadStartTime = System.currentTimeMillis();
                                    
                                    // 处理选定的模型
                                    processSelectedModels(context, selectedEmbeddingModel, selectedRerankerModel, embeddingModelPath, rerankerModelPath, vectorDb, callback);
                                    
                                    LogManager.logD(TAG, "模型处理完成，耗时: " + (System.currentTimeMillis() - threadStartTime) + "ms");
                                }).start();
                            }
                        })
                        .setNegativeButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_CANCEL), (dialog, which) -> {
                            LogManager.logD(TAG, "取消按钮被点击");
                            LogManager.logD(TAG, "用户取消了模型选择");
                            
                            // 调用回调函数，传递null表示用户取消了选择
                            if (callback != null) {
                                callback.onModelSelected(null, null);
                            }
                        });
                
                // 创建并显示对话框
                AlertDialog dialog = builder.create();
                dialog.show();
                
                LogManager.logD(TAG, "已显示模型选择对话框，总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
            } catch (Exception e) {
                LogManager.logE(TAG, "显示模型选择对话框失败: " + e.getMessage(), e);
                // 出错时调用回调函数，传递null表示选择失败
                if (callback != null) {
                    callback.onModelSelected(null, null);
                }
            }
        });
    }
    
    /**
     * 处理用户选择的模型
     */
    private static void processSelectedModels(
            Context context,
            String selectedEmbeddingModel,
            String selectedRerankerModel,
            String embeddingModelPath,
            String rerankerModelPath,
            SQLiteVectorDatabaseHandler vectorDb,
            ModelSelectedCallback callback) {
        
        String embeddingModelPath_result = null;
        String rerankerModelPath_result = null;
        boolean embeddingModelFound = false;
        boolean rerankerModelFound = true; // 重排模型默认为找到（因为可以选择"无"）
        
        // 处理嵌入模型选择
        String rootDirectoryText = context.getString(R.string.embedding_model_root_directory);
        if (selectedEmbeddingModel.equals(rootDirectoryText)) {
            // 在根目录中查找嵌入模型文件
            File embeddingModelDir = new File(embeddingModelPath);
            File[] files = embeddingModelDir.listFiles(file -> isModelFile(file));
            if (files != null && files.length > 0) {
                embeddingModelPath_result = files[0].getAbsolutePath();
                embeddingModelFound = true;
                
                // 更新元数据中的modeldir
                SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
                metadata.setModeldir("");
                LogManager.logD(TAG, "已更新元数据，modeldir设置为空（使用根目录）");
            }
        } else {
            // 使用选定的嵌入模型目录
            File selectedEmbeddingDir = new File(embeddingModelPath, selectedEmbeddingModel);
            if (selectedEmbeddingDir.exists() && selectedEmbeddingDir.isDirectory()) {
                File[] files = selectedEmbeddingDir.listFiles(file -> isModelFile(file));
                if (files != null && files.length > 0) {
                    embeddingModelPath_result = files[0].getAbsolutePath();
                    embeddingModelFound = true;
                    
                    // 更新元数据中的modeldir
                    SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
                    metadata.setModeldir(selectedEmbeddingModel);
                    LogManager.logD(TAG, "已更新元数据，modeldir设置为: " + selectedEmbeddingModel);
                }
            }
        }
        
        // 处理重排模型选择
        String noneRerankerText = context.getString(R.string.common_none);
        if (selectedRerankerModel.equals(noneRerankerText)) {
            // 用户选择不使用重排模型
            SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
            metadata.setRerankerdir(context.getString(R.string.common_none));
            LogManager.logD(TAG, "已更新元数据，rerankerdir设置为: " + context.getString(R.string.common_none));
        } else {
            // 使用选定的重排模型目录
            File selectedRerankerDir = new File(rerankerModelPath, selectedRerankerModel);
            if (selectedRerankerDir.exists() && selectedRerankerDir.isDirectory()) {
                // 检查重排模型目录是否包含模型文件（这里可以根据实际需要调整检查逻辑）
                rerankerModelFound = true;
                
                // 更新元数据中的rerankerdir
                SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
                metadata.setRerankerdir(selectedRerankerModel);
                LogManager.logD(TAG, "已更新元数据，rerankerdir设置为: " + selectedRerankerModel);
            } else {
                rerankerModelFound = false;
                LogManager.logE(TAG, "选定的重排模型目录不存在: " + selectedRerankerModel);
            }
        }
        
        // 保存数据库元数据
        vectorDb.saveDatabase();
        
        if (embeddingModelFound && rerankerModelFound && callback != null) {
            // 调用回调函数
            callback.onModelSelected(selectedEmbeddingModel, embeddingModelPath_result);
        } else {
            if (!embeddingModelFound) {
                LogManager.logE(TAG, "在选定的嵌入模型目录中未找到模型文件");
            }
            if (!rerankerModelFound) {
                LogManager.logE(TAG, "重排模型配置失败");
            }
            if (callback != null) {
                callback.onModelSelected(null, null);
            }
        }
    }
    
    /**
     * 判断文件是否为模型文件
     */
    private static boolean isModelFile(File file) {
        return EmbeddingModelHandler.isModelFile(file);
    }
    
    /**
     * 模型选择回调接口
     */
    public interface ModelSelectedCallback {
        void onModelSelected(String selectedModel, String modelPath);
    }
}
