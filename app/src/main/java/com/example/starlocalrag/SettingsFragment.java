package com.example.starlocalrag;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.api.LocalLlmHandler;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.MenuProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.Navigation;

import org.json.JSONObject;

import java.io.File;

public class SettingsFragment extends Fragment {
    private static final String TAG = "SettingsFragment";
    
    // Backend preference options (hardcoded to avoid resource file complexity)
    private static final String[] BACKEND_OPTIONS = {"CPU", "Vulkan"};
    private static final String[] BACKEND_VALUES = {"CPU", "VULKAN"};
    
    // UI组件
    private SeekBar seekBarChunkSize;
    private TextView textViewChunkSizeValue;
    private SeekBar seekBarOverlapSize;
    private TextView textViewOverlapSizeValue;
    private SeekBar seekBarMinChunkSize;
    private TextView textViewMinChunkSizeValue;
    private EditText editTextModelPath;
    private EditText editTextEmbeddingModelPath;
    private EditText editTextRerankerModelPath;
    private EditText editTextKnowledgeBasePath;
    private Button buttonSelectModelPath;
    private Button buttonSelectEmbeddingModelPath;
    private Button buttonSelectRerankerModelPath;
    private Button buttonSelectKnowledgeBasePath;
    private Button buttonSaveSettings;
    private SwitchCompat switchDebugMode;
    private Spinner spinnerUseGpu;
    // ONNX引擎开关已移除
    private SwitchCompat switchJsonDatasetSplitting; // JSON训练集分块优化开关
    private SeekBar seekBarFontSize; // 字体大小拖动条
    private TextView textViewFontSizeValue; // 字体大小值显示
    
    // LLM 推理设置相关UI组件
    private SeekBar seekBarMaxSequenceLength;
    private TextView textViewMaxSequenceLengthValue;
    private SeekBar seekBarThreads;
    private TextView textViewThreadsValue;
    private SeekBar seekBarKvCacheSize;
    private TextView textViewKvCacheSizeValue;
    
    // 手动推理参数UI组件
    private SeekBar seekBarManualTemperature;
    private TextView textViewManualTemperatureValue;
    private SeekBar seekBarManualTopP;
    private TextView textViewManualTopPValue;
    private SeekBar seekBarManualTopK;
    private TextView textViewManualTopKValue;
    private SeekBar seekBarManualRepeatPenalty;
    private TextView textViewManualRepeatPenaltyValue;
    private SwitchCompat switchPriorityManualParams; // 优先手动参数开关
    
    // Activity Result Launchers
    private ActivityResultLauncher<Intent> modelPathLauncher;
    private ActivityResultLauncher<Intent> embeddingModelPathLauncher;
    private ActivityResultLauncher<Intent> rerankerModelPathLauncher;
    private ActivityResultLauncher<Intent> knowledgeBasePathLauncher;
    // 思考模式开关已移动到RAG问答界面
    
    // 设置变更监听器
    private SettingsChangeListener settingsChangeListener;
    
    // 请求码
    private static final int REQUEST_CODE_MODEL_PATH = 1001;
    private static final int REQUEST_CODE_EMBEDDING_MODEL_PATH = 1002;
    private static final int REQUEST_CODE_RERANKER_MODEL_PATH = 1003;
    private static final int REQUEST_CODE_KNOWLEDGE_BASE_PATH = 1004;
    
    // 设置监听器接口
    public interface SettingsChangeListener {
        void onSettingsChanged();
    }
    
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            settingsChangeListener = (SettingsChangeListener) context;
        } catch (ClassCastException e) {
            LogManager.logE(TAG, "Activity must implement SettingsChangeListener", e);
        }
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化Activity Result Launchers
        modelPathLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleDirectorySelection(result.getData().getData(), editTextModelPath);
                }
            }
        );
        
        embeddingModelPathLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleDirectorySelection(result.getData().getData(), editTextEmbeddingModelPath);
                }
            }
        );
        
        rerankerModelPathLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleDirectorySelection(result.getData().getData(), editTextRerankerModelPath);
                }
            }
        );
        
        knowledgeBasePathLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleDirectorySelection(result.getData().getData(), editTextKnowledgeBasePath);
                }
            }
        );
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        // 初始化UI控件
        seekBarChunkSize = view.findViewById(R.id.seekBarChunkSize);
        textViewChunkSizeValue = view.findViewById(R.id.textViewChunkSizeValue);
        seekBarOverlapSize = view.findViewById(R.id.seekBarOverlapSize);
        textViewOverlapSizeValue = view.findViewById(R.id.textViewOverlapSizeValue);
        seekBarMinChunkSize = view.findViewById(R.id.seekBarMinChunkSize);
        textViewMinChunkSizeValue = view.findViewById(R.id.textViewMinChunkSizeValue);
        editTextModelPath = view.findViewById(R.id.editTextModelPath);
        editTextEmbeddingModelPath = view.findViewById(R.id.editTextEmbeddingModelPath);
        editTextRerankerModelPath = view.findViewById(R.id.editTextRerankerModelPath);
        editTextKnowledgeBasePath = view.findViewById(R.id.editTextKnowledgeBasePath);
        buttonSelectModelPath = view.findViewById(R.id.buttonSelectModelPath);
        buttonSelectEmbeddingModelPath = view.findViewById(R.id.buttonSelectEmbeddingModelPath);
        buttonSelectRerankerModelPath = view.findViewById(R.id.buttonSelectRerankerModelPath);
        buttonSelectKnowledgeBasePath = view.findViewById(R.id.buttonSelectKnowledgeBasePath);
        buttonSaveSettings = view.findViewById(R.id.buttonSaveSettings);
        switchDebugMode = view.findViewById(R.id.switchDebugMode);
        spinnerUseGpu = view.findViewById(R.id.spinnerBackendPreference);
        // ONNX引擎开关初始化已移除
        switchJsonDatasetSplitting = view.findViewById(R.id.switchJsonDatasetSplitting); // JSON训练集分块优化开关
        
        // 初始化 LLM 推理设置相关UI组件
        seekBarMaxSequenceLength = view.findViewById(R.id.seekBarMaxSequenceLength);
        textViewMaxSequenceLengthValue = view.findViewById(R.id.textViewMaxSequenceLengthValue);
        seekBarThreads = view.findViewById(R.id.seekBarThreads);
        textViewThreadsValue = view.findViewById(R.id.textViewThreadsValue);
        seekBarKvCacheSize = view.findViewById(R.id.seekBarKvCacheSize);
        textViewKvCacheSizeValue = view.findViewById(R.id.textViewKvCacheSizeValue);
        // switchNoThinking已移动到RAG问答界面
        seekBarFontSize = view.findViewById(R.id.seekBarFontSize); // 字体大小拖动条
        textViewFontSizeValue = view.findViewById(R.id.textViewFontSizeValue); // 字体大小值显示
        
        // 初始化手动推理参数UI组件
        seekBarManualTemperature = view.findViewById(R.id.seekBarManualTemperature);
        textViewManualTemperatureValue = view.findViewById(R.id.textViewManualTemperatureValue);
        seekBarManualTopP = view.findViewById(R.id.seekBarManualTopP);
        textViewManualTopPValue = view.findViewById(R.id.textViewManualTopPValue);
        seekBarManualTopK = view.findViewById(R.id.seekBarManualTopK);
        textViewManualTopKValue = view.findViewById(R.id.textViewManualTopKValue);
        seekBarManualRepeatPenalty = view.findViewById(R.id.seekBarManualRepeatPenalty);
        textViewManualRepeatPenaltyValue = view.findViewById(R.id.textViewManualRepeatPenaltyValue);
        switchPriorityManualParams = view.findViewById(R.id.switchPriorityManualParams); // 优先手动参数开关
        
        // 设置后端偏好Spinner适配器
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, BACKEND_OPTIONS);
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUseGpu.setAdapter(backendAdapter);
        
        // 加载当前设置
        loadSettings();
        
        // 设置按钮点击事件
        setupListeners();
        
        // 设置字体大小拖动条变化监听器
        setupFontSizeSeekBar();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 添加MenuProvider来处理菜单
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.settings_menu, menu);
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                
                if (id == R.id.action_close_settings) {
                    // 关闭设置页面 - 使用Navigation组件的返回操作
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    return true;
                }
                
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }
    
    private void setupListeners() {
        // 选择模型目录
        buttonSelectModelPath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            modelPathLauncher.launch(intent);
        });
        
        // 选择嵌入模型目录
        buttonSelectEmbeddingModelPath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            embeddingModelPathLauncher.launch(intent);
        });
        
        // 选择重排模型目录
        buttonSelectRerankerModelPath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            rerankerModelPathLauncher.launch(intent);
        });
        
        // 选择知识库目录
        buttonSelectKnowledgeBasePath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            knowledgeBasePathLauncher.launch(intent);
        });
        
        // 保存设置
        buttonSaveSettings.setOnClickListener(v -> saveSettings());
        
        // 设置所有SeekBar监听器
        setupChunkSizeSeekBar();
        setupOverlapSizeSeekBar();
        setupMinChunkSizeSeekBar();
        setupMaxSequenceLengthSeekBar();
        setupThreadsSeekBar();
        setupKvCacheSizeSeekBar();
        setupManualTemperatureSeekBar();
        setupManualTopPSeekBar();
        setupManualTopKSeekBar();
        setupManualRepeatPenaltySeekBar();
    }
    
    private void setupFontSizeSeekBar() {
        // 设置初始值
        float currentFontSize = ConfigManager.getGlobalTextSize(requireContext());
        // 将字体大小转换为进度值（10-24sp对应0-14的进度）
        int progress = Math.round(currentFontSize) - 10;
        if (progress < 0) progress = 0;
        if (progress > 14) progress = 14;
        seekBarFontSize.setProgress(progress);
        updateFontSizeText(progress);
        
        seekBarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 更新字体大小值显示
                updateFontSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 不需要处理
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 停止拖动时，应用字体大小变化
                int progress = seekBar.getProgress();
                float fontSize = progress + 10;
                // 立即应用字体大小变化，让用户可以预览效果
                ConfigManager.setGlobalTextSize(requireContext(), fontSize);
                // 通知设置已变更
                if (settingsChangeListener != null) {
                    settingsChangeListener.onSettingsChanged();
                }
            }
        });
    }
    
    private void updateFontSizeText(int progress) {
        float fontSize = progress + 10;
        textViewFontSizeValue.setText(String.format("Font Size: %.0fsp", fontSize));
        // 应用字体大小到预览文本
        textViewFontSizeValue.setTextSize(fontSize);
    }
    
    private void setupChunkSizeSeekBar() {
        seekBarChunkSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 更新分块大小值显示
                updateChunkSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 不需要处理
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 不需要处理
            }
        });
    }
    
    private void updateChunkSizeText(int progress) {
        int chunkSize = (progress * 100) + 100;
        textViewChunkSizeValue.setText(String.valueOf(chunkSize));
    }
    
    private void loadSettings() {
        try {
            // 从ConfigManager加载设置
            Context context = requireContext();
            
            // 加载分块设置
            int chunkSize = ConfigManager.getChunkSize(context);
            int overlapSize = ConfigManager.getInt(context, ConfigManager.KEY_OVERLAP_SIZE, ConfigManager.DEFAULT_OVERLAP_SIZE);
            int minChunkSize = ConfigManager.getMinChunkSize(context); // 获取最小分块限制
            
            // 加载模型路径
            String modelPath = ConfigManager.getModelPath(context);
            String embeddingModelPath = ConfigManager.getEmbeddingModelPath(context);
            String rerankerModelPath = ConfigManager.getRerankerModelPath(context);
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(context);
            
            // 加载调试模式设置
            boolean debugMode = ConfigManager.getBoolean(context, ConfigManager.KEY_DEBUG_MODE, false);
            
            // 加载后端偏好设置（兼容旧的GPU设置）
            String backendPreference = ConfigManager.getString(context, ConfigManager.KEY_USE_GPU, "CPU");
            // 兼容性迁移：如果存储的是布尔值，则转换为字符串
            if ("true".equals(backendPreference)) {
                backendPreference = "VULKAN";
                ConfigManager.setString(context, ConfigManager.KEY_USE_GPU, backendPreference);
            } else if ("false".equals(backendPreference)) {
                backendPreference = "CPU";
                ConfigManager.setString(context, ConfigManager.KEY_USE_GPU, backendPreference);
            }
            // 兼容性迁移：移除 CANN 选项后，如发现旧值，则回退为 CPU
            if ("CANN".equals(backendPreference)) {
                LogManager.logW(TAG, "Backend 'CANN' is deprecated and removed from UI. Fallback to 'CPU'.");
                backendPreference = "CPU";
                ConfigManager.setString(context, ConfigManager.KEY_USE_GPU, backendPreference);
            }
            
            // ONNX引擎设置加载已移除
            
            // 加载JSON训练集分块优化开关
            boolean jsonDatasetSplitting = ConfigManager.isJsonDatasetSplittingEnabled(context);
            
            // 加载字体大小
            float fontSize = ConfigManager.getGlobalTextSize(context);
            
            // 加载 LLM 推理设置
            int maxSequenceLength = ConfigManager.getMaxSequenceLength(context);
            int threads = ConfigManager.getThreads(context);
            int maxNewTokens = ConfigManager.getMaxNewTokens(context);
            boolean noThinking = ConfigManager.getNoThinking(context);
            
            // 设置UI
            seekBarChunkSize.setProgress((chunkSize - 100) / 100);
            updateChunkSizeText((chunkSize - 100) / 100);
            seekBarOverlapSize.setProgress((overlapSize - 20) / 20);
            updateOverlapSizeText((overlapSize - 20) / 20);
            seekBarMinChunkSize.setProgress((minChunkSize - 10) / 10);
            updateMinChunkSizeText((minChunkSize - 10) / 10);
            editTextModelPath.setText(modelPath);
            editTextEmbeddingModelPath.setText(embeddingModelPath);
            editTextRerankerModelPath.setText(rerankerModelPath);
            editTextKnowledgeBasePath.setText(knowledgeBasePath);
            switchDebugMode.setChecked(debugMode);
            
            // 设置后端偏好Spinner
            int selectedIndex = 0;
            for (int i = 0; i < BACKEND_VALUES.length; i++) {
                if (BACKEND_VALUES[i].equals(backendPreference)) {
                    selectedIndex = i;
                    break;
                }
            }
            spinnerUseGpu.setSelection(selectedIndex);
            // ONNX引擎开关设置已移除
            switchJsonDatasetSplitting.setChecked(jsonDatasetSplitting);
            seekBarFontSize.setProgress(Math.round(fontSize) - 10);
            updateFontSizeText(Math.round(fontSize) - 10);
            
            // 设置 LLM 推理设置UI
            seekBarMaxSequenceLength.setProgress((maxSequenceLength - 512) / 512);
            updateMaxSequenceLengthText((maxSequenceLength - 512) / 512);
            seekBarThreads.setProgress(threads - 1);
            updateThreadsText(threads - 1);
            seekBarKvCacheSize.setProgress((maxNewTokens - 512) / 512);
            updateKvCacheSizeText((maxNewTokens - 512) / 512);
            // switchNoThinking已移动到RAG问答界面
            
            // 加载手动推理参数
            float manualTemperature = ConfigManager.getManualTemperature(context);
            float manualTopP = ConfigManager.getManualTopP(context);
            int manualTopK = ConfigManager.getManualTopK(context);
            float manualRepeatPenalty = ConfigManager.getManualRepeatPenalty(context);
            boolean priorityManualParams = ConfigManager.getBoolean(context, ConfigManager.KEY_PRIORITY_MANUAL_PARAMS, false);
            
            // 设置手动推理参数UI
            seekBarManualTemperature.setProgress((int)(manualTemperature * 10));
            updateManualTemperatureText((int)(manualTemperature * 10));
            seekBarManualTopP.setProgress((int)(manualTopP / 0.05f));
            updateManualTopPText((int)(manualTopP / 0.05f));
            seekBarManualTopK.setProgress((manualTopK - 10) / 10);
            updateManualTopKText((manualTopK - 10) / 10);
            seekBarManualRepeatPenalty.setProgress((int)(manualRepeatPenalty * 10));
            updateManualRepeatPenaltyText((int)(manualRepeatPenalty * 10));
            switchPriorityManualParams.setChecked(priorityManualParams);
            
            LogManager.logD(TAG, "Settings loaded successfully");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load settings: " + e.getMessage(), e);
            Toast.makeText(requireContext(), getString(R.string.toast_load_settings_failed), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void saveSettings() {
        Context context = requireContext();
        
        try {
            // 获取用户输入
            int chunkSize = (seekBarChunkSize.getProgress() * 100) + 100;
            int overlapSize = (seekBarOverlapSize.getProgress() * 20) + 20;
            int minChunkSize = (seekBarMinChunkSize.getProgress() * 10) + 10;
            String modelPathStr = editTextModelPath.getText().toString().trim();
            String embeddingModelPathStr = editTextEmbeddingModelPath.getText().toString().trim();
            String rerankerModelPathStr = editTextRerankerModelPath.getText().toString().trim();
            String knowledgeBasePathStr = editTextKnowledgeBasePath.getText().toString().trim();
            
            // 数值已经从SeekBar获取，无需验证输入格式
            
            // 验证值范围
            if (chunkSize < 100 || chunkSize > 4000) {
                Toast.makeText(context, getString(R.string.toast_chunk_size_range_old), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (overlapSize < 20 || overlapSize > 800 || overlapSize >= chunkSize) {
                Toast.makeText(context, getString(R.string.toast_overlap_size_range_old), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (minChunkSize < 10 || minChunkSize > 200 || minChunkSize >= chunkSize) {
                Toast.makeText(context, getString(R.string.toast_min_chunk_limit_range_old), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取路径设置
            String modelPath = editTextModelPath.getText().toString().trim();
            String embeddingModelPath = editTextEmbeddingModelPath.getText().toString().trim();
            String rerankerModelPath = editTextRerankerModelPath.getText().toString().trim();
            String knowledgeBasePath = editTextKnowledgeBasePath.getText().toString().trim();
            
            // 验证路径
            if (modelPath.isEmpty() || embeddingModelPath.isEmpty() || rerankerModelPath.isEmpty() || knowledgeBasePath.isEmpty()) {
                Toast.makeText(context, getString(R.string.toast_please_set_all_paths), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取调试模式设置
            boolean debugMode = switchDebugMode.isChecked();
            
            // 获取后端偏好设置
            int selectedIndex = spinnerUseGpu.getSelectedItemPosition();
            String backendPreference = (selectedIndex >= 0 && selectedIndex < BACKEND_VALUES.length) ? 
                BACKEND_VALUES[selectedIndex] : "CPU";
            
            // ONNX引擎设置获取已移除
            
            // 获取JSON训练集分块优化开关
            boolean jsonDatasetSplitting = switchJsonDatasetSplitting.isChecked();
            
            // 获取字体大小
            int progress = seekBarFontSize.getProgress();
            float fontSize = progress + 10;
            
            // 获取 LLM 推理设置
            int maxSequenceLength = (seekBarMaxSequenceLength.getProgress() * 512) + 512;
            int threads = seekBarThreads.getProgress() + 1;
            int maxNewTokens = (seekBarKvCacheSize.getProgress() * 512) + 512;
            // noThinking已移动到RAG问答界面
            
            // 获取手动推理参数
            float manualTemperature = seekBarManualTemperature.getProgress() / 10.0f;
            float manualTopP = seekBarManualTopP.getProgress() * 0.05f;
            int manualTopK = (seekBarManualTopK.getProgress() * 10) + 10;
            float manualRepeatPenalty = seekBarManualRepeatPenalty.getProgress() / 10.0f;
            boolean priorityManualParams = switchPriorityManualParams.isChecked();
            
            // 数值已经从SeekBar获取，无需验证输入格式和转换
            
            // 验证值范围
            if (maxSequenceLength < 100 || maxSequenceLength > 8192) {
                Toast.makeText(context, getString(R.string.toast_max_seq_length_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (threads < 1 || threads > 16) {
                Toast.makeText(context, getString(R.string.toast_inference_threads_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (maxNewTokens < 512 || maxNewTokens > 4096) {
                Toast.makeText(context, getString(R.string.toast_max_output_tokens_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 验证手动推理参数范围
            if (manualTemperature < 0.0f || manualTemperature > 2.0f) {
                Toast.makeText(context, getString(R.string.toast_manual_temperature_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (manualTopP < 0.0f || manualTopP > 1.0f) {
                Toast.makeText(context, getString(R.string.toast_manual_top_p_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (manualTopK < 1 || manualTopK > 100) {
                Toast.makeText(context, getString(R.string.toast_manual_top_k_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (manualRepeatPenalty < 0.0f || manualRepeatPenalty > 2.0f) {
                Toast.makeText(context, getString(R.string.toast_manual_repeat_penalty_range), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 验证最大序列长度与最大输出token数的关系
            // maxSequenceLength是总的上下文长度，maxNewTokens是最大输出token数
        // 输入token数 = maxSequenceLength - maxNewTokens，需要预留一定缓冲区
        if (maxSequenceLength <= maxNewTokens + 256) {
            Toast.makeText(context, getString(R.string.toast_max_seq_length_must_be_greater, maxNewTokens + 256), Toast.LENGTH_LONG).show();
                return;
            }
            
            // 保存设置到ConfigManager
            ConfigManager.setChunkSize(context, chunkSize);
            ConfigManager.setInt(context, ConfigManager.KEY_OVERLAP_SIZE, overlapSize);
            ConfigManager.setMinChunkSize(context, minChunkSize); // 保存最小分块限制
            ConfigManager.setString(context, ConfigManager.KEY_MODEL_PATH, modelPath);
            ConfigManager.setString(context, ConfigManager.KEY_EMBEDDING_MODEL_PATH, embeddingModelPath);
            ConfigManager.setString(context, ConfigManager.KEY_RERANKER_MODEL_PATH, rerankerModelPath);
            ConfigManager.setString(context, ConfigManager.KEY_KNOWLEDGE_BASE_PATH, knowledgeBasePath);
            ConfigManager.setBoolean(context, ConfigManager.KEY_DEBUG_MODE, debugMode);
            ConfigManager.setString(context, ConfigManager.KEY_USE_GPU, backendPreference);
            // ONNX引擎配置保存已移除
            ConfigManager.setJsonDatasetSplittingEnabled(context, jsonDatasetSplitting);
            ConfigManager.setGlobalTextSize(context, fontSize);
            
            // 保存 LLM 推理设置
            ConfigManager.setMaxSequenceLength(context, maxSequenceLength);
            ConfigManager.setThreads(context, threads);
            ConfigManager.setMaxNewTokens(context, maxNewTokens);
            // noThinking已移动到RAG问答界面
            
            // 保存手动推理参数
            ConfigManager.setManualTemperature(context, manualTemperature);
            ConfigManager.setManualTopP(context, manualTopP);
            ConfigManager.setManualTopK(context, manualTopK);
            ConfigManager.setManualRepeatPenalty(context, manualRepeatPenalty);
            ConfigManager.setBoolean(context, ConfigManager.KEY_PRIORITY_MANUAL_PARAMS, priorityManualParams);
            
            // 创建JSON格式的设置摘要
            JSONObject settingsSummary = new JSONObject();
            settingsSummary.put("chunkSize", chunkSize);
            settingsSummary.put("overlapSize", overlapSize);
            settingsSummary.put("minChunkSize", minChunkSize); // 添加最小分块限制
            settingsSummary.put("modelPath", modelPath);
            settingsSummary.put("embeddingModelPath", embeddingModelPath);
            settingsSummary.put("rerankerModelPath", rerankerModelPath);
            settingsSummary.put("knowledgeBasePath", knowledgeBasePath);
            settingsSummary.put("debugMode", debugMode);
            settingsSummary.put("backendPreference", backendPreference);
            // ONNX引擎设置摘要已移除
            settingsSummary.put("jsonDatasetSplitting", jsonDatasetSplitting);
            
            // 添加 LLM 推理设置信息
            settingsSummary.put("maxSequenceLength", maxSequenceLength);
            settingsSummary.put("threads", threads);
            // noThinking已移动到RAG问答界面
            
            LogManager.logD(TAG, "Settings saved: " + settingsSummary.toString());
            
            // 显示成功消息
            Toast.makeText(context, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show();
            
            // 更新LocalLlmHandler的推理引擎配置
            try {
                LocalLlmHandler localLlmHandler = LocalLlmHandler.getInstance(context);
                localLlmHandler.updateEngineFromConfig();
                LogManager.logI(TAG, "Inference engine configuration updated");
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to update inference engine configuration: " + e.getMessage(), e);
            }
            
            // 通知监听器
            if (settingsChangeListener != null) {
                settingsChangeListener.onSettingsChanged();
            }
        } catch (NumberFormatException e) {
            LogManager.logE(TAG, "Failed to parse number: " + e.getMessage(), e);
            Toast.makeText(context, getString(R.string.toast_please_enter_valid_number), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save settings: " + e.getMessage(), e);
            Toast.makeText(context, getString(R.string.toast_save_settings_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleDirectorySelection(Uri uri, EditText targetEditText) {
        if (uri != null) {
            try {
                // 将 URI 转换为实际可用的文件路径
                String realPath = getPathFromUri(uri);
                LogManager.logD(TAG, "Selected path URI: " + uri);
                LogManager.logD(TAG, "Converted actual path: " + realPath);
                
                targetEditText.setText(realPath);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to convert path: " + e.getMessage(), e);
                Toast.makeText(requireContext(), getString(R.string.toast_cannot_get_selected_path), Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 将 URI 转换为实际可用的文件路径
     * @param uri 文件 URI
     * @return 实际文件路径
     */
    private String getPathFromUri(Uri uri) {
        String path = uri.getPath();
        
        // 检查是否是 content:// 类型的 URI
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            // 处理 /tree/primary: 格式的路径
            if (path != null && path.contains("primary:")) {
                // 提取 primary: 后面的部分
                String[] segments = path.split("primary:");
                if (segments.length > 1) {
                    // 转换为实际的存储路径
                    String relativePath = segments[1];
                    // 替换可能存在的重复路径
                    if (relativePath.contains("/document/primary:")) {
                        relativePath = relativePath.split("/document/primary:")[0];
                    }
                    
                    // 构建实际路径
                    File externalStorage = Environment.getExternalStorageDirectory();
                    path = new File(externalStorage, relativePath).getAbsolutePath();
                    LogManager.logD(TAG, "Converting primary: path: " + path);
                }
            }
        }
        
        return path;
    }
    

    
    // 静态方法，用于获取设置值
    public static int getChunkSize(Context context) {
        return ConfigManager.getChunkSize(context);
    }
    
    public static int getOverlapSize(Context context) {
        return ConfigManager.getInt(context, ConfigManager.KEY_OVERLAP_SIZE, ConfigManager.DEFAULT_OVERLAP_SIZE);
    }
    
    /**
     * 获取最小分块限制
     * @param context 上下文
     * @return 最小分块限制
     */
    public static int getMinChunkSize(Context context) {
        return ConfigManager.getMinChunkSize(context);
    }
    
    public static String getModelPath(Context context) {
        return ConfigManager.getModelPath(context);
    }
    
    public static String getEmbeddingModelPath(Context context) {
        return ConfigManager.getEmbeddingModelPath(context);
    }
    
    public static String getKnowledgeBasePath(Context context) {
        return ConfigManager.getKnowledgeBasePath(context);
    }
    
    /**
     * 获取是否启用调试模式
     * @param context 上下文
     * @return 是否启用调试模式
     */
    public static boolean isDebugModeEnabled(Context context) {
        return ConfigManager.getBoolean(context, ConfigManager.KEY_DEBUG_MODE, false);
    }
    
    /**
     * 获取后端偏好设置
     * @param context 上下文
     * @return 后端偏好字符串
     */
    public static String getBackendPreference(Context context) {
        String backendPreference = ConfigManager.getString(context, ConfigManager.KEY_USE_GPU, "CPU");
        // Compatibility: map deprecated CANN to CPU
        if ("CANN".equals(backendPreference)) {
            LogManager.logW(TAG, "Detected deprecated backend 'CANN' in config. Using 'CPU' instead.");
            backendPreference = "CPU";
            ConfigManager.setString(context, ConfigManager.KEY_USE_GPU, backendPreference);
        }
        // 验证后端偏好值是否有效，无效则使用CPU
        for (String validValue : BACKEND_VALUES) {
            if (validValue.equals(backendPreference)) {
                return backendPreference;
            }
        }
        return "CPU";
    }
    
    /**
     * 获取是否使用GPU加速（兼容性方法）
     * @param context 上下文
     * @return 是否使用GPU加速
     */

    
    /**
     * 获取是否启用JSON训练集分块优化
     * @param context 上下文
     * @return 是否启用JSON训练集分块优化
     */
    public static boolean isJsonDatasetSplittingEnabled(Context context) {
        return ConfigManager.isJsonDatasetSplittingEnabled(context);
    }
    
    private void setupOverlapSizeSeekBar() {
        seekBarOverlapSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateOverlapSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateOverlapSizeText(int progress) {
        int overlapSize = (progress * 20) + 20;
        textViewOverlapSizeValue.setText(String.valueOf(overlapSize));
    }
    
    private void setupMinChunkSizeSeekBar() {
        seekBarMinChunkSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMinChunkSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateMinChunkSizeText(int progress) {
        int minChunkSize = (progress * 10) + 10;
        textViewMinChunkSizeValue.setText(String.valueOf(minChunkSize));
    }
    
    private void setupMaxSequenceLengthSeekBar() {
        seekBarMaxSequenceLength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMaxSequenceLengthText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateMaxSequenceLengthText(int progress) {
        int maxSequenceLength = (progress * 512) + 512;
        textViewMaxSequenceLengthValue.setText(String.valueOf(maxSequenceLength));
    }
    
    private void setupThreadsSeekBar() {
        seekBarThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateThreadsText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateThreadsText(int progress) {
        int threads = progress + 1;
        textViewThreadsValue.setText(String.valueOf(threads));
    }
    
    private void setupKvCacheSizeSeekBar() {
        seekBarKvCacheSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateKvCacheSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateKvCacheSizeText(int progress) {
        int kvCacheSize = (progress * 512) + 512;
        textViewKvCacheSizeValue.setText(String.valueOf(kvCacheSize));
    }
    
    private void setupManualTemperatureSeekBar() {
        seekBarManualTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateManualTemperatureText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateManualTemperatureText(int progress) {
        float temperature = progress / 10.0f;
        textViewManualTemperatureValue.setText(String.format("%.1f", temperature));
    }
    
    private void setupManualTopPSeekBar() {
        seekBarManualTopP.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateManualTopPText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateManualTopPText(int progress) {
        float topP = progress * 0.05f;
        textViewManualTopPValue.setText(String.format("%.2f", topP));
    }
    
    private void setupManualTopKSeekBar() {
        seekBarManualTopK.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateManualTopKText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateManualTopKText(int progress) {
        int topK = (progress * 10) + 10;
        textViewManualTopKValue.setText(String.valueOf(topK));
    }
    
    private void setupManualRepeatPenaltySeekBar() {
        seekBarManualRepeatPenalty.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateManualRepeatPenaltyText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateManualRepeatPenaltyText(int progress) {
        float repeatPenalty = progress / 10.0f;
        textViewManualRepeatPenaltyValue.setText(String.format("%.1f", repeatPenalty));
    }
}
