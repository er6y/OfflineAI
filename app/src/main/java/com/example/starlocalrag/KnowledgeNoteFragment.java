package com.example.starlocalrag;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface; // 添加DialogInterface的导入语句
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONException;
import org.json.JSONObject;

import com.example.starlocalrag.ConfigManager; // 添加 ConfigManager 的导入
import com.example.starlocalrag.EmbeddingModelManager; // 导入 EmbeddingModelManager
import com.example.starlocalrag.StateDisplayManager;
import com.example.starlocalrag.AppConstants;
import com.example.starlocalrag.EmbeddingModelHandler; // 导入 EmbeddingModelHandler
import com.example.starlocalrag.SQLiteVectorDatabaseHandler; // 导入 SQLiteVectorDatabaseHandler
import com.example.starlocalrag.EmbeddingModelUtils; // 导入词嵌入模型工具类
import com.example.starlocalrag.api.TokenizerManager; // 导入分词器管理器
import com.example.starlocalrag.TextChunkProcessor; // 导入文本处理器

public class KnowledgeNoteFragment extends Fragment {
    private static final String TAG = "KnowledgeNoteFragment";
    
    // 状态常量
    private static final String STATUS_LOADING_KB_LIST = "开始加载知识库名称列表";
    private static final String STATUS_GETTING_KB_PATH = "从设置中获取知识库路径";
    private static final String STATUS_KB_DIR_NOT_EXIST = "知识库目录不存在，尝试创建";
    private static final String STATUS_FOUND_KB_COUNT = "找到%d个知识库";
    private static final String STATUS_NO_KB_FOUND = "未找到知识库，已禁用添加按钮";
    private static final String STATUS_KB_SELECTED = "已选择知识库";
    private static final String STATUS_PROMPT_SELECTED = "选择了提示项，已清空知识库配置";
    private static final String STATUS_KB_SAVED = "已保存知识库选择到ConfigManager";
    private static final String STATUS_LOADING_LAST_KB = "从ConfigManager加载上次选择的知识库";
    private static final String STATUS_LAST_KB_SELECTED = "已选择上次使用的知识库";
    private static final String STATUS_LOAD_KB_FAILED = "加载知识库选择失败";
    private static final String STATUS_KB_NOT_EXIST = "错误：知识库不存在";
    private static final String STATUS_CHECK_MODEL_TIMEOUT = "错误：检查嵌入模型超时";
    private static final String STATUS_MODEL_CHECK_INTERRUPTED = "错误：等待模型检查被中断";
    private static final String STATUS_NO_EMBEDDING_MODEL = "错误：未找到可用的嵌入模型";
    private static final String STATUS_GET_MODEL_FROM_CONFIG = "从SQLite数据库中获取嵌入模型失败，尝试从ConfigManager中获取";
    private static final String STATUS_MODEL_FILE_NOT_EXIST = "模型文件不存在";
    private static final String STATUS_KB_MODEL_NOT_EXIST = "知识库的嵌入模型不存在，需要选择新的模型";
    private static final String STATUS_NO_MODEL_CONFIG = "错误：未找到可用的嵌入模型，请在设置中配置嵌入模型路径";
    private static final String STATUS_SELECT_MODEL_TIMEOUT = "错误：选择模型超时";
    private static final String STATUS_MODEL_SELECT_INTERRUPTED = "错误：等待模型选择被中断";
    private static final String STATUS_NO_MODEL_SELECTED = "错误：未选择嵌入模型";
    
    // 提示文本常量
    private static final String PROMPT_ENTER_TITLE = "请输入标题";
    private static final String PROMPT_ENTER_CONTENT = "请输入内容";
    // 移除硬编码常量，改用资源引用

    private EditText editTextTitle;
    private EditText editTextContent;
    private TextView textViewProgress;
    private ScrollView scrollViewProgress;
    private Spinner spinnerKnowledgeBase;
    private Button buttonAddToKnowledgeBase;
    private ExecutorService executorService;
    private List<String> knowledgeBaseNames = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper()); // 主线程Handler
    private StateDisplayManager stateDisplayManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_knowledge_note, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化UI元素
        editTextTitle = view.findViewById(R.id.editTextTitle);
        editTextContent = view.findViewById(R.id.editTextContent);
        textViewProgress = view.findViewById(R.id.textViewProgress);
        scrollViewProgress = (ScrollView) textViewProgress.getParent();
        spinnerKnowledgeBase = view.findViewById(R.id.spinnerNoteKnowledgeBase);
        buttonAddToKnowledgeBase = view.findViewById(R.id.buttonAddToKnowledgeBase);
        
        // 初始化StateDisplayManager
        stateDisplayManager = new StateDisplayManager(requireContext());
        
        // 设置文本框可长按选择
        editTextContent.setLongClickable(true);
        editTextContent.setTextIsSelectable(true);
        
        // 创建线程池
        executorService = Executors.newSingleThreadExecutor();
        
        // 加载知识库列表
        loadKnowledgeBaseNames();
        
        // 设置添加到知识库按钮的点击事件
        buttonAddToKnowledgeBase.setOnClickListener(v -> addToKnowledgeBase());
        
        // 应用全局字体大小
        applyGlobalTextSize();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次页面恢复时刷新知识库列表
        loadKnowledgeBaseNames();
        // 在页面恢复时重新应用字体大小，以便在设置页面修改后能够立即生效
        applyGlobalTextSize();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 关闭线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // 加载知识库名称列表
    private void loadKnowledgeBaseNames() {
        LogManager.logD(TAG, STATUS_LOADING_KB_LIST);
        knowledgeBaseNames.clear();

        // 获取设置中的知识库路径
        String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(requireContext());
        LogManager.logD(TAG, STATUS_GETTING_KB_PATH + ": " + knowledgeBasePath);

        // 获取知识库目录
        File knowledgeBaseDir = new File(knowledgeBasePath);
        if (!knowledgeBaseDir.exists()) {
            LogManager.logD(TAG, STATUS_KB_DIR_NOT_EXIST + ": " + knowledgeBaseDir.getAbsolutePath());
            knowledgeBaseDir.mkdirs();
        }

        // 获取所有子目录作为知识库
        File[] directories = knowledgeBaseDir.listFiles(File::isDirectory);
        if (directories != null && directories.length > 0) {
            for (File dir : directories) {
                // 添加所有有效的知识库目录（排除隐藏目录和系统目录）
                String dirName = dir.getName();
                if (!dirName.startsWith(".") && !dirName.startsWith("_")) {
                    knowledgeBaseNames.add(dirName);
                }
            }
            LogManager.logD(TAG, String.format(STATUS_FOUND_KB_COUNT, knowledgeBaseNames.size()));
            buttonAddToKnowledgeBase.setEnabled(true);
        } else {
            // 如果没有知识库，添加提示
            knowledgeBaseNames.add(getString(R.string.prompt_create_kb_first));
            buttonAddToKnowledgeBase.setEnabled(false);
            LogManager.logD(TAG, STATUS_NO_KB_FOUND);
        }

        // 设置适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, knowledgeBaseNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKnowledgeBase.setAdapter(adapter);
        
        // 设置选择监听器
        spinnerKnowledgeBase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKnowledgeBase = knowledgeBaseNames.get(position);
                LogManager.logD(TAG, "已选择知识库: " + selectedKnowledgeBase);
                
                // 如果选择的是状态显示文本，则禁用添加按钮
                if (StateDisplayManager.isKnowledgeBaseStatusDisplayText(requireContext(), selectedKnowledgeBase)) {
                    buttonAddToKnowledgeBase.setEnabled(false);
                    // 保存空字符串到ConfigManager表示没有选择知识库
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, "");
                    LogManager.logD(TAG, "选择了提示项，已清空知识库配置");
                } else {
                    buttonAddToKnowledgeBase.setEnabled(true);
                    // 保存选择的知识库到ConfigManager
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, selectedKnowledgeBase);
                    LogManager.logD(TAG, "已保存知识库选择到ConfigManager: " + selectedKnowledgeBase);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 从ConfigManager加载上次选择的知识库
        loadLastSelectedKnowledgeBase();
    }
    
    // 加载上次选择的知识库
    private void loadLastSelectedKnowledgeBase() {
        try {
            // 使用ConfigManager获取上次选择的知识库
            String lastKnowledgeBase = ConfigManager.getString(requireContext(), 
                    ConfigManager.KEY_KNOWLEDGE_BASE, "");
            
            LogManager.logD(TAG, STATUS_LOADING_LAST_KB + ": " + 
                    (lastKnowledgeBase.isEmpty() ? "[空]" : lastKnowledgeBase));
            
            if (!lastKnowledgeBase.isEmpty() && !knowledgeBaseNames.isEmpty() && 
                    !StateDisplayManager.isKnowledgeBaseStatusDisplayText(requireContext(), knowledgeBaseNames.get(0))) {
                // 在下拉列表中查找并选择上次使用的知识库
                for (int i = 0; i < knowledgeBaseNames.size(); i++) {
                    if (knowledgeBaseNames.get(i).equals(lastKnowledgeBase)) {
                        spinnerKnowledgeBase.setSelection(i);
                        LogManager.logD(TAG, STATUS_LAST_KB_SELECTED + ": " + lastKnowledgeBase);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, STATUS_LOAD_KB_FAILED + ": " + e.getMessage(), e);
        }
    }

    // 添加到知识库
    private void addToKnowledgeBase() {
        // 获取标题和内容
        String title = editTextTitle.getText().toString().trim();
        String content = editTextContent.getText().toString().trim();
        
        // 检查标题和内容是否为空
        if (title.isEmpty()) {
                Toast.makeText(requireContext(), PROMPT_ENTER_TITLE, Toast.LENGTH_SHORT).show();
                return;
            }

            if (content.isEmpty()) {
                Toast.makeText(requireContext(), PROMPT_ENTER_CONTENT, Toast.LENGTH_SHORT).show();
                return;
            }
        
        // 获取选中的知识库
        if (spinnerKnowledgeBase.getSelectedItemPosition() < 0 || 
            knowledgeBaseNames.isEmpty() || 
            getString(R.string.prompt_create_kb_first).equals(knowledgeBaseNames.get(0))) {
            Toast.makeText(requireContext(), getString(R.string.prompt_create_kb_first), Toast.LENGTH_SHORT).show();
            return;
        }
        
        String selectedKnowledgeBase = knowledgeBaseNames.get(spinnerKnowledgeBase.getSelectedItemPosition());
        
        // 显示进度
        textViewProgress.setText(getString(R.string.processing_status) + "\n");
        
        // 禁用按钮，防止重复点击
        buttonAddToKnowledgeBase.setEnabled(false);
        
        // 在后台线程中处理
        executorService.execute(() -> {
            try {
                // 获取知识库目录
                String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(requireContext());
                File knowledgeBaseDir = new File(knowledgeBasePath, selectedKnowledgeBase);
                if (!knowledgeBaseDir.exists()) {
                    updateProgress(STATUS_KB_NOT_EXIST);
                    enableAddButton();
                    return;
                }
                
                // 优先从SQLite数据库中获取嵌入模型信息
                String embeddingModelPath = null;
                SQLiteVectorDatabaseHandler vectorDb = null;
                try {
                    vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                    if (vectorDb.loadDatabase()) {
                        SQLiteVectorDatabaseHandler.DatabaseMetadata metadata = vectorDb.getMetadata();
                        if (metadata != null) {
                            String embeddingModel = metadata.getModeldir();
                            LogManager.logD(TAG, "从SQLite数据库中读取到嵌入模型目录: " + embeddingModel);
                            
                            // 使用EmbeddingModelUtils检查并加载嵌入模型
                            CountDownLatch modelLatch = new CountDownLatch(1);
                            final AtomicReference<String> modelPathRef = new AtomicReference<>();
                            final AtomicBoolean modelFoundRef = new AtomicBoolean(false);
                            
                            // 创建一个副本以在lambda表达式中使用
                            final SQLiteVectorDatabaseHandler finalVectorDb = vectorDb;
                            
                            requireActivity().runOnUiThread(() -> {
                                EmbeddingModelUtils.checkAndLoadEmbeddingModel(
                                    requireContext(),
                                    finalVectorDb,
                                    modelPath -> {
                                        if (modelPath != null) {
                                            modelPathRef.set(modelPath);
                                            modelFoundRef.set(true);
                                        }
                                        modelLatch.countDown();
                                    },
                                    (selectedModel, selectedModelPath) -> {
                                        if (selectedModelPath != null) {
                                            modelPathRef.set(selectedModelPath);
                                            modelFoundRef.set(true);
                                        }
                                        modelLatch.countDown();
                                    }
                                );
                            });
                            
                            // 等待模型检查完成
                            try {
                                boolean modelCheckCompleted = modelLatch.await(60, TimeUnit.SECONDS);
                                if (!modelCheckCompleted) {
                                    updateProgress(STATUS_CHECK_MODEL_TIMEOUT);
                                    enableAddButton();
                                    return;
                                }
                            } catch (InterruptedException e) {
                                updateProgress(STATUS_MODEL_CHECK_INTERRUPTED + ": " + e.getMessage());
                                enableAddButton();
                                return;
                            }
                            
                            // 获取模型路径
                            embeddingModelPath = modelPathRef.get();
                            if (!modelFoundRef.get() || embeddingModelPath == null) {
                                updateProgress(STATUS_NO_EMBEDDING_MODEL);
                                enableAddButton();
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "读取SQLite数据库失败", e);
                } finally {
                    if (vectorDb != null) {
                        try {
                            vectorDb.close();
                        } catch (Exception e) {
                            LogManager.logE(TAG, "关闭SQLite数据库失败", e);
                        }
                    }
                }
                
                // 如果从SQLite数据库中获取失败，则尝试从ConfigManager中获取（兼容旧版本）
                if (embeddingModelPath == null || embeddingModelPath.isEmpty()) {
                    LogManager.logD(TAG, STATUS_GET_MODEL_FROM_CONFIG);
                    embeddingModelPath = ConfigManager.getKnowledgeBaseEmbeddingModel(requireContext(), selectedKnowledgeBase);
                    
                    // 如果从ConfigManager中获取到了模型名称，检查模型文件是否存在
                    if (embeddingModelPath != null && !embeddingModelPath.isEmpty()) {
                        File modelFile = new File(embeddingModelPath);
                        if (!modelFile.exists()) {
                            LogManager.logD(TAG, STATUS_MODEL_FILE_NOT_EXIST + ": " + modelFile.getAbsolutePath());
                            embeddingModelPath = null;
                        }
                    }
                }
                
                // 如果模型路径为空或模型文件不存在，显示模型选择对话框
                if (embeddingModelPath == null || embeddingModelPath.isEmpty() || !new File(embeddingModelPath).exists()) {
                    updateProgress(STATUS_KB_MODEL_NOT_EXIST);
                    
                    // 获取可用的嵌入模型列表
                    List<String> availableModels = getAvailableEmbeddingModels();
                    
                    if (availableModels.isEmpty()) {
                        updateProgress(STATUS_NO_MODEL_CONFIG);
                        enableAddButton();
                        return;
                    }
                    
                    // 在UI线程中显示模型选择对话框
                    CountDownLatch dialogLatch = new CountDownLatch(1);
                    final AtomicReference<String> selectedModelRef = new AtomicReference<>();
                    final AtomicBoolean rememberChoiceRef = new AtomicBoolean(true);
                    
                    requireActivity().runOnUiThread(() -> {
                        showModelSelectionDialog(availableModels, (selectedModel, rememberChoice) -> {
                            selectedModelRef.set(selectedModel);
                            rememberChoiceRef.set(rememberChoice);
                            dialogLatch.countDown();
                        });
                    });
                    
                    // 等待用户选择模型
                    try {
                        boolean dialogCompleted = dialogLatch.await(60, TimeUnit.SECONDS);
                        if (!dialogCompleted) {
                            updateProgress(STATUS_SELECT_MODEL_TIMEOUT);
                            enableAddButton();
                            return;
                        }
                    } catch (InterruptedException e) {
                        updateProgress(STATUS_MODEL_SELECT_INTERRUPTED + ": " + e.getMessage());
                        enableAddButton();
                        return;
                    }
                    
                    // 获取用户选择的模型
                    embeddingModelPath = selectedModelRef.get();
                    boolean rememberChoice = rememberChoiceRef.get();
                    
                    if (embeddingModelPath == null || embeddingModelPath.isEmpty()) {
                        updateProgress(STATUS_NO_MODEL_SELECTED);
                        enableAddButton();
                        return;
                    }
                    
                    updateProgress(getString(R.string.selected_new_embedding_model, embeddingModelPath));
                    
                    // 如果用户选择记住选择，则更新知识库元数据中的模型信息
                    if (rememberChoice) {
                        updateProgress(getString(R.string.updating_kb_metadata));
                        updateKnowledgeBaseModelMetadata(knowledgeBaseDir, embeddingModelPath);
                    } else {
                        updateProgress(getString(R.string.use_selected_model_only));
                    }
                }
                
                updateProgress(getString(R.string.found_embedding_model, embeddingModelPath));
            updateProgress(getString(R.string.loading_model_wait));
                
                // 使用EmbeddingModelManager异步加载模型
                EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(requireContext());
                
                // 创建一个CountDownLatch来等待异步加载完成
                final CountDownLatch modelLoadLatch = new CountDownLatch(1);
                final AtomicReference<EmbeddingModelHandler> modelHandlerRef = new AtomicReference<>();
                final AtomicReference<Exception> modelErrorRef = new AtomicReference<>();
                
                modelManager.getModelAsync(embeddingModelPath, new EmbeddingModelManager.ModelCallback() {
                    @Override
                    public void onModelReady(EmbeddingModelHandler model) {
                        modelHandlerRef.set(model);
                        modelLoadLatch.countDown();
                    }
                    
                    @Override
                    public void onModelError(Exception e) {
                        modelErrorRef.set(e);
                        modelLoadLatch.countDown();
                    }
                });
                
                // 等待模型加载完成，设置超时
                try {
                    boolean modelLoaded = modelLoadLatch.await(180, TimeUnit.SECONDS);
                    if (!modelLoaded) {
                        updateProgress(getString(R.string.error_embedding_model_timeout));
                        enableAddButton();
                        return;
                    }
                } catch (InterruptedException e) {
                    updateProgress(getString(R.string.error_embedding_model_interrupted, e.getMessage()));
                    enableAddButton();
                    return;
                }
                
                // 检查是否有错误
                if (modelErrorRef.get() != null) {
                    updateProgress(getString(R.string.error_embedding_model_failed, modelErrorRef.get().getMessage()));
                    enableAddButton();
                    return;
                }
                
                // 获取加载好的模型
                EmbeddingModelHandler embeddingHandler = modelHandlerRef.get();
                if (embeddingHandler == null) {
                    updateProgress(getString(R.string.error_embedding_model_handler_failed));
                    enableAddButton();
                    return;
                }
                
                // 确保TokenizerManager已初始化
                TokenizerManager tokenizerManager = TokenizerManager.getInstance(requireContext());
                if (!tokenizerManager.isInitialized()) {
                    updateProgress(getString(R.string.initializing_tokenizer));
                    boolean initSuccess = tokenizerManager.initialize(embeddingModelPath);
                    if (initSuccess) {
                        updateProgress(getString(R.string.tokenizer_init_success));
                        // 启用一致性分词
                        tokenizerManager.setUseConsistentTokenization(true);
                    } else {
                        updateProgress(getString(R.string.tokenizer_init_failed));
                    }
                } else {
                    updateProgress(getString(R.string.using_global_tokenizer));
                    // 启用一致性分词
                    tokenizerManager.setUseConsistentTokenization(true);
                }
                
                updateProgress(getString(R.string.embedding_model_loaded_success, embeddingHandler.getModelType()));
                
                // 标记模型开始使用
                modelManager.markModelInUse();
                updateProgress(getString(R.string.mark_model_start_use));
                
                // 使用SQLiteVectorDatabaseHandler添加笔记
                updateProgress(getString(R.string.adding_note_to_kb));
                SQLiteVectorDatabaseHandler noteVectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "note");
                
                try {
                    // 加载数据库
                    if (!noteVectorDb.loadDatabase()) {
                        updateProgress(getString(R.string.error_load_sqlite_db));
                        enableAddButton();
                        
                        // 标记模型使用结束（即使发生错误）
                        modelManager.markModelNotInUse();
                        updateProgress(getString(R.string.progress_mark_model_end_use));
                        
                        return;
                    }
                    
                    // 获取分块大小和重叠大小
                    int chunkSize = ConfigManager.getChunkSize(requireContext());
                    int chunkOverlap = ConfigManager.getInt(requireContext(), ConfigManager.KEY_OVERLAP_SIZE, ConfigManager.DEFAULT_OVERLAP_SIZE);
                    int minChunkSize = ConfigManager.getMinChunkSize(requireContext());
                    
                    updateProgress(getString(R.string.progress_chunk_params_info, chunkSize, chunkOverlap, minChunkSize));
                    
                    // 如果内容长度超过分块大小，需要进行分块处理
                    if (content.length() > chunkSize) {
                        updateProgress(getString(R.string.chunking_text));
                        
                        // 使用TextChunkProcessor的分块方法，确保与构建知识库使用相同的分块算法
                        TextChunkProcessor textChunkProcessor = new TextChunkProcessor(requireContext());
                        List<String> chunks = textChunkProcessor.splitTextIntoChunks(content, chunkSize, chunkOverlap);
                        updateProgress(getString(R.string.text_chunking_complete, chunks.size()));
                        
                        // 获取添加前的文本块数量
                        int beforeChunkCount = noteVectorDb.getChunkCount();
                        updateProgress(getString(R.string.db_chunk_count_before, beforeChunkCount));
                        
                        boolean success = true;
                        int addedCount = 0;
                        
                        // 处理每个文本块
                        for (int i = 0; i < chunks.size(); i++) {
                            String chunk = chunks.get(i);
                            updateProgress(getString(R.string.adding_chunk_progress, (i + 1), chunks.size()));
                            
                            // 生成嵌入向量
                            float[] chunkEmbedding = embeddingHandler.generateEmbedding(chunk);
                            
                            // 向量异常处理
                            try {
                                // 检测向量异常
                                VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(chunkEmbedding, -1);
                                if (anomalyResult.isAnomalous) {
                                    updateProgress(getString(R.string.vector_anomaly_detected_repairing));
                                    // 尝试修复向量
                                    chunkEmbedding = VectorAnomalyHandler.repairVector(chunkEmbedding, anomalyResult.type);
                                    
                                    // 对修复后的向量进行最终验证
                                    VectorAnomalyHandler.AnomalyResult verifyResult = VectorAnomalyHandler.detectAnomalies(chunkEmbedding, -1);
                                    if (verifyResult.isAnomalous) {
                                        updateProgress(getString(R.string.vector_repair_failed_using_random));
                                        // 如果修复失败，生成随机单位向量作为备用
                                        chunkEmbedding = VectorAnomalyHandler.generateRandomUnitVector(chunkEmbedding.length);
                                    } else {
                                        updateProgress(getString(R.string.vector_repair_success));
                                    }
                                }
                            } catch (Exception e) {
                                updateProgress(getString(R.string.vector_processing_error, e.getMessage()));
                                // 异常情况下生成随机单位向量
                                chunkEmbedding = VectorAnomalyHandler.generateRandomUnitVector(chunkEmbedding.length);
                            }
                            
                            // 添加文本块到数据库
                            String chunkTitle = title + " (BLK " + (i+1) + "/" + chunks.size() + ")";
                            boolean chunkSuccess = noteVectorDb.addChunk(chunk, chunkTitle, chunkEmbedding);
                            
                            if (chunkSuccess) {
                                addedCount++;
                            } else {
                                success = false;
                                updateProgress(getString(R.string.add_chunk_failed, i+1));
                            }
                        }
                        
                        updateProgress(getString(R.string.all_chunks_added));
                        
                        // 保存数据库
                        if (success) {
                            success = noteVectorDb.saveDatabase();
                            if (success) {
                                updateProgress(getString(R.string.chunk_added_success));
                            }
                        }
                        
                        // 获取添加后的文本块数量
                        int afterChunkCount = noteVectorDb.getChunkCount();
                        updateProgress(getString(R.string.db_chunk_count_after, afterChunkCount));
                        updateProgress(getString(R.string.added_chunk_count, addedCount));
                    } else {
                        // 直接生成嵌入向量
                        updateProgress(getString(R.string.generating_embedding_vector));
                        float[] contentEmbedding = embeddingHandler.generateEmbedding(content);
                        
                        // 向量异常处理
                        try {
                            // 检测向量异常
                            VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(contentEmbedding, -1);
                            if (anomalyResult.isAnomalous) {
                                updateProgress(getString(R.string.vector_anomaly_detected_repairing));
                                // 尝试修复向量
                                contentEmbedding = VectorAnomalyHandler.repairVector(contentEmbedding, anomalyResult.type);
                                
                                // 对修复后的向量进行最终验证
                                VectorAnomalyHandler.AnomalyResult verifyResult = VectorAnomalyHandler.detectAnomalies(contentEmbedding, -1);
                                if (verifyResult.isAnomalous) {
                                    updateProgress(getString(R.string.vector_repair_failed_using_random));
                                    // 如果修复失败，生成随机单位向量作为备用
                                    contentEmbedding = VectorAnomalyHandler.generateRandomUnitVector(contentEmbedding.length);
                                } else {
                                    updateProgress(getString(R.string.vector_repair_success));
                                }
                            }
                        } catch (Exception e) {
                            updateProgress(getString(R.string.vector_processing_error, e.getMessage()));
                            // 异常情况下生成随机单位向量
                            contentEmbedding = VectorAnomalyHandler.generateRandomUnitVector(contentEmbedding.length);
                        }
                        
                        // 记录向量调试信息
                        String vectorDebugInfo = embeddingHandler.getVectorDebugInfo(content, contentEmbedding, System.currentTimeMillis());
                        updateProgress(vectorDebugInfo);
                        
                        // 获取添加前的文本块数量
                        int beforeChunkCount = noteVectorDb.getChunkCount();
                        updateProgress(getString(R.string.progress_db_chunk_count_before, beforeChunkCount));
                        
                        // 添加文本块到数据库
                        updateProgress(getString(R.string.saving_to_database));
                        boolean success = noteVectorDb.addChunk(content, title, contentEmbedding);
                        
                        // 保存数据库
                        if (success) {
                            success = noteVectorDb.saveDatabase();
                        }
                        
                        // 获取添加后的文本块数量
                        int afterChunkCount = noteVectorDb.getChunkCount();
                        updateProgress(getString(R.string.db_chunk_count_after, afterChunkCount));
                        updateProgress(getString(R.string.new_chunk_count, afterChunkCount - beforeChunkCount));
                    }
                    
                    // 在关闭数据库之前检查文本块数量
                    boolean hasChunks = noteVectorDb.getChunkCount() > 0;
                    
                    // 关闭数据库
                    noteVectorDb.close();
                    
                    // 标记模型使用结束
                    modelManager.markModelNotInUse();
                    updateProgress(getString(R.string.mark_model_end_use));
                    
                    if (hasChunks) {
                        updateProgress(getString(R.string.note_added_success));
                    }
                    
                    // 清空输入框
                    requireActivity().runOnUiThread(() -> {
                        editTextTitle.setText("");
                        editTextContent.setText("");
                    });
                } catch (Exception e) {
                    LogManager.logE(TAG, "生成嵌入向量或添加笔记失败", e);
                    updateProgress(getString(R.string.error_message, e.getMessage()));
                    if (noteVectorDb != null) {
                        noteVectorDb.close();
                    }
                    modelManager.markModelNotInUse();
                    updateProgress(getString(R.string.mark_model_end_use_error));
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "添加到知识库失败", e);
                updateProgress(getString(R.string.error_message, e.getMessage()));
            } finally {
                enableAddButton();
            }
        });
    }
    
    // 启用添加按钮
    private void enableAddButton() {
        requireActivity().runOnUiThread(() -> {
            buttonAddToKnowledgeBase.setEnabled(true);
        });
    }
    
    // 更新进度显示
    private void updateProgress(String message) {
        requireActivity().runOnUiThread(() -> {
            textViewProgress.append(message + "\n");
            // 滚动到底部
            scrollViewProgress.post(() -> {
                scrollViewProgress.fullScroll(ScrollView.FOCUS_DOWN);
            });
        });
    }

    // 获取可用的嵌入模型列表
    private List<String> getAvailableEmbeddingModels() {
        List<String> availableModels = new ArrayList<>();
        
        // 获取设置中的嵌入模型路径
        String embeddingModelPath = ConfigManager.getEmbeddingModelPath(requireContext());
        LogManager.logD(TAG, "嵌入模型路径: " + embeddingModelPath);
        
        // 获取嵌入模型目录
        File embeddingModelDir = new File(embeddingModelPath);
        if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
            // 获取所有模型文件
            File[] files = embeddingModelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    // 查找.pt、.pth、.onnx文件或包含model的目录
                    if ((file.isFile() && (name.endsWith(".pt") || name.endsWith(".pth") || name.endsWith(".onnx"))) ||
                        (file.isDirectory() && name.contains("model"))) {
                        availableModels.add(file.getName()); // 只添加文件名，而不是完整路径
                        LogManager.logD(TAG, "找到可用模型: " + file.getName());
                    }
                }
            }
        }
        
        if (availableModels.isEmpty()) {
            LogManager.logW(TAG, "未找到可用的嵌入模型");
        } else {
            LogManager.logD(TAG, "共找到 " + availableModels.size() + " 个可用模型");
        }
        
        return availableModels;
    }

    // 获取所有可能的模型路径
    private List<String> getPossibleModelPaths() {
        List<String> possiblePaths = new ArrayList<>();
        
        // 获取配置中的嵌入模型路径
        String configPath = ConfigManager.getEmbeddingModelPath(requireContext());
        possiblePaths.add(configPath);
        
        // 添加可能的替代路径
        File externalStorageDir = Environment.getExternalStorageDirectory();
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        
        possiblePaths.add(new File(externalStorageDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(downloadDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(externalStorageDir, "Download/starragdata/embeddings").getAbsolutePath());
        possiblePaths.add("/storage/emulated/0/Download/starragdata/embeddings");
        possiblePaths.add("/sdcard/Download/starragdata/embeddings");
        
        return possiblePaths;
    }

    // 显示模型选择对话框
    private void showModelSelectionDialog(List<String> availableModels, ModelSelectionCallback callback) {
        // 创建对话框
        ModelSelectionDialog dialog = new ModelSelectionDialog(requireContext(), availableModels, callback);
        dialog.show();
    }

    // 更新知识库元数据中的模型信息
    private void updateKnowledgeBaseModelMetadata(File knowledgeBaseDir, String embeddingModelPath) {
        try {
            LogManager.logD(TAG, "更新知识库元数据中的模型信息: " + embeddingModelPath);
            
            // 使用 SQLiteVectorDatabaseHandler 更新元数据
            SQLiteVectorDatabaseHandler vectorDb = null;
            try {
                // 创建SQLite向量数据库处理器
                LogManager.logI(TAG, "开始创建SQLite向量数据库处理器，知识库目录: " + knowledgeBaseDir.getAbsolutePath());
                
                vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "note");
                LogManager.logI(TAG, "正在加载SQLite向量数据库...");
                
                if (vectorDb.loadDatabase()) {
                    // 更新嵌入模型路径
                    vectorDb.updateEmbeddingModel(embeddingModelPath);
                    LogManager.logD(TAG, "成功更新元数据");
                    updateProgress(getString(R.string.progress_kb_metadata_updated));
                } else {
                    LogManager.logE(TAG, "加载SQLite向量数据库失败");
                    updateProgress(getString(R.string.warning_sqlite_load_failed));
                    
                    // 尝试创建新的元数据
                    vectorDb.updateEmbeddingModel(embeddingModelPath);
                    LogManager.logD(TAG, "创建了新的数据库元数据");
                    updateProgress(getString(R.string.progress_new_db_metadata_created));
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "使用 SQLiteVectorDatabaseHandler 更新元数据失败", e);
                updateProgress(getString(R.string.warning_update_db_metadata_failed, e.getMessage()));
            } finally {
                // 确保关闭数据库
                if (vectorDb != null) {
                    try {
                        vectorDb.close();
                        LogManager.logD(TAG, "已关闭向量数据库");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "关闭向量数据库失败", e);
                    }
                }
            }
            
            // 同时更新 metadata.json 文件以保持兼容性
            File jsonMetadataFile = new File(knowledgeBaseDir, "metadata.json");
            try {
                JSONObject metadata;
                if (jsonMetadataFile.exists()) {
                    String metadataJson = FileUtil.readFile(jsonMetadataFile);
                    if (!metadataJson.isEmpty()) {
                        metadata = new JSONObject(metadataJson);
                    } else {
                        metadata = new JSONObject();
                    }
                } else {
                    metadata = new JSONObject();
                }
                
                metadata.put("embeddingModel", embeddingModelPath);
                metadata.put("updated", System.currentTimeMillis());
                
                boolean success = FileUtil.writeFile(jsonMetadataFile, metadata.toString());
                if (success) {
                    LogManager.logD(TAG, "成功更新 metadata.json 文件");
                } else {
                    LogManager.logE(TAG, "更新 metadata.json 文件失败");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "处理 metadata.json 文件失败", e);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "更新知识库元数据时发生错误", e);
            updateProgress(getString(R.string.warning_update_kb_metadata_failed, e.getMessage()));
        }
    }

    // 模型选择回调接口
    private interface ModelSelectionCallback {
        void onModelSelected(String selectedModel, boolean rememberChoice);
    }

    // 模型选择对话框
    private static class ModelSelectionDialog extends AlertDialog {
        private List<String> availableModels;
        private ModelSelectionCallback callback;
        private boolean rememberChoice = true;

        public ModelSelectionDialog(Context context, List<String> availableModels, ModelSelectionCallback callback) {
            super(context);
            this.availableModels = availableModels;
            this.callback = callback;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.dialog_model_selection);
            setTitle(new StateDisplayManager(getContext()).getDialogDisplay(AppConstants.DIALOG_TITLE_SELECT_EMBEDDING_MODEL));

            // 获取控件
            TextView textViewInfo = findViewById(R.id.textViewInfo);
            Spinner spinnerModels = findViewById(R.id.spinnerModels);
            CheckBox checkBoxRemember = findViewById(R.id.checkBoxRemember);

            // 设置提示信息
            textViewInfo.setText("请选择一个嵌入模型用于此知识库。\n" +
                    "如果选择\"记住选择\"，将更新知识库元数据。");

            // 创建模型列表适配器
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, availableModels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerModels.setAdapter(adapter);

            // 设置记住选择的复选框监听器
            checkBoxRemember.setOnCheckedChangeListener((buttonView, isChecked) -> {
                rememberChoice = isChecked;
            });

            // 设置确认和取消按钮
            setButton(DialogInterface.BUTTON_POSITIVE, "确定", (dialog, which) -> {
                int selectedPosition = spinnerModels.getSelectedItemPosition();
                if (selectedPosition >= 0 && selectedPosition < availableModels.size()) {
                    String selectedModel = availableModels.get(selectedPosition);
                    callback.onModelSelected(selectedModel, rememberChoice);
                }
            });

            setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (dialog, which) -> {
                callback.onModelSelected(null, false);
            });
        }
    }

    /**
     * 将文本插入到内容编辑框中
     * @param text 要插入的文本
     */
    public void insertTextToContentEditor(String text) {
        if (editTextContent == null) {
            LogManager.logE(TAG, "内容编辑框为空，无法插入文本");
            return;
        }
        
        try {
            // 获取当前光标位置
            int cursorPosition = editTextContent.getSelectionStart();
            
            // 获取当前内容
            Editable editable = editTextContent.getText();
            
            // 在光标位置插入文本
            if (cursorPosition != -1) {
                editable.insert(cursorPosition, text);
            } else {
                // 如果没有光标位置，则追加到末尾
                editable.append(text);
                // 将光标移动到末尾
                editTextContent.setSelection(editable.length());
            }
            
            // 显示提示
            Toast.makeText(requireContext(), getString(R.string.toast_text_inserted_to_note), Toast.LENGTH_SHORT).show();
            
            // 如果标题为空，自动生成标题（使用文本的前10个字符）
            if (editTextTitle.getText().toString().trim().isEmpty()) {
                String title = text.trim();
                if (title.length() > 20) {
                    title = title.substring(0, 20) + "...";
                }
                editTextTitle.setText(title);
            }
            
            // 确保内容编辑框获得焦点
            editTextContent.requestFocus();
            
            LogManager.logD(TAG, "成功将文本插入到内容编辑框");
        } catch (Exception e) {
            LogManager.logE(TAG, "插入文本到内容编辑框失败", e);
            Toast.makeText(requireContext(), getString(R.string.toast_insert_text_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 应用全局字体大小设置
     */
    private void applyGlobalTextSize() {
        if (editTextContent != null) {
            float fontSize = ConfigManager.getGlobalTextSize(requireContext());
            editTextContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
            LogManager.logD(TAG, "已应用全局字体大小: " + fontSize + "sp");
        }
    }
}