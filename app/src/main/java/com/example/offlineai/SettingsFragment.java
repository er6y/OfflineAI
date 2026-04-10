package com.example.offlineai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import com.example.offlineai.LogManager;
import com.example.offlineai.ipc.LocalLlmHandler;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Switch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    
    // Backend preference options: MNN supports CPU, Vulkan, OpenCL and NNAPI
    // Note: KleidiAI optimizations are auto-enabled on arm64 CPU backend (via MNN_KLEIDIAI=ON)
    // Note: NNAPI is only available on arm64 devices (disabled on x86_64 emulator)
    private static final String[] BACKEND_OPTIONS = {"CPU", "Vulkan", "OpenCL", "NNAPI"};
    private static final String[] BACKEND_VALUES = {"CPU", "VULKAN", "OPENCL", "NNAPI"};
    
    // UI组件
    private SeekBar seekBarChunkSize;
    private TextView textViewChunkSizeValue;
    private SeekBar seekBarOverlapSize;
    private TextView textViewOverlapSizeValue;
    private SeekBar seekBarMinChunkSize;
    private TextView textViewMinChunkSizeValue;
    private SeekBar seekBarEmbeddingConcurrency;
    private TextView textViewEmbeddingConcurrencyValue;
    private SeekBar seekBarEmbeddingThreads;
    private TextView textViewEmbeddingThreadsValue;
    
    // Hub 阈值和 Graph RAG 向量粗召回放大预设值
    private static final int[] HUB_THRESHOLD_PRESETS = {
        0,
        50, 100, 200, 300, 400, 600,
        800, 1000, 1200, 1600,
        2000, 2500, 3200,
        4800, 6400, 9600, 12800, 25600
    };

    private static final int[] GRAPH_RAG_VECTOR_EXPAND_PRESETS = {
        0, 3, 5, 8, 10, 15, 20, 25, 30, 40, 50
    };
    
    // Knowledge Graph RAG UI组件
    private SeekBar seekBarGraphMinEdgeWeight;
    private TextView textViewGraphMinEdgeWeightValue;
    private SeekBar seekBarGraphMaxExpandEntities;
    private TextView textViewGraphMaxExpandEntitiesValue;
    private SeekBar seekBarGraphEntityConfidenceThreshold;
    private TextView textViewGraphEntityConfidenceThresholdValue;
    private SwitchCompat switchGraphRagMode;
    private Spinner spinnerGraphRagWeightPreset;
    private SeekBar seekBarGraphMaxExpandChunks;
    private TextView textViewGraphMaxExpandChunksValue;
    // Hub thresholds: build & query
    private SeekBar seekBarGraphHubThreshold;
    private TextView textViewGraphHubThresholdValue;
    private SeekBar seekBarGraphHubThresholdQuery;
    private TextView textViewGraphHubThresholdQueryValue;
    private SeekBar seekBarGraphRagVectorExpand;
    private TextView textViewGraphRagVectorExpandValue;
    private Spinner spinnerGraphStopwords;
    private Switch switchAgentExperienceSummary;
    private Switch switchAgentTts;
    private SeekBar seekBarAgentMaxSteps;
    private TextView textViewAgentMaxStepsValue;
    
    private EditText editTextDataRootPath;
    private Button buttonSelectDataRootPath;
    private SwitchCompat switchShowDebugPerformance; // Show debug & performance switch
    private SwitchCompat switchDebugMode;
    private Spinner spinnerUseGpu;
    private Spinner spinnerGraphCustomDictionary;
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
    private SeekBar seekBarHistoryRounds;
    private TextView textViewHistoryRoundsValue;
    
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
    private SeekBar seekBarImagePreprocessSize; // 图片预处理尺寸
    private TextView textViewImagePreprocessSizeValue; // 图片预处理尺寸显示
    // 图像编码线程数UI已移除（MNN不支持独立配置）
    
    // Diffusion扩散设置
    private Spinner spinnerDiffusionMemoryMode;
    private Spinner spinnerDiffusionPrecisionMode;
    private Spinner spinnerDiffusionGpuMemoryMode;
    private Spinner spinnerDiffusionImageSize;
    private SeekBar seekBarDiffusionSteps;
    private TextView textViewDiffusionStepsValue;
    private SeekBar seekBarDiffusionCfg;
    private TextView textViewDiffusionCfgValue;
    private EditText editTextDiffusionSeed;
    private SwitchCompat switchDiffusionSeedRandom;
    private SwitchCompat switchDiffusionTeOnCpu;
    
    // ASR语音识别设置
    private Spinner spinnerAsrModel;
    
    // TTS语音合成设置
    private Spinner spinnerTtsModel;
    private LinearLayout layoutTtsDitSteps;
    private SeekBar seekBarTtsDitSteps;
    private TextView textViewTtsDitStepsValue;
    private LinearLayout layoutTtsSpeaker;
    private Spinner spinnerTtsSpeaker;
    private SwitchCompat switchTtsAutoPlay;
    private LinearLayout layoutTtsSpeed;
    private SeekBar seekBarTtsSpeed;
    private TextView textViewTtsSpeedValue;
    private LinearLayout layoutTtsPitch;
    private SeekBar seekBarTtsPitch;
    private TextView textViewTtsPitchValue;
    
    // Flags to avoid writing config during initial spinner binding
    private boolean isAsrSpinnerInitialized = false;
    private boolean isTtsSpinnerInitialized = false;
    private boolean isDiffusionMemoryModeSpinnerInitialized = false;
    private boolean isDiffusionImageSizeSpinnerInitialized = false;
    
    // Activity Result Launchers
    private ActivityResultLauncher<Intent> dataRootPathLauncher;
    // 思考模式开关已移动到RAG问答界面
    
    // 设置变更监听器
    private SettingsChangeListener settingsChangeListener;
    
    // 请求码
    private static final int REQUEST_CODE_DATA_ROOT_PATH = 1001;
    
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
        dataRootPathLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleDirectorySelection(result.getData().getData(), editTextDataRootPath);
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
        seekBarEmbeddingConcurrency = view.findViewById(R.id.seekBarEmbeddingConcurrency);
        textViewEmbeddingConcurrencyValue = view.findViewById(R.id.textViewEmbeddingConcurrencyValue);
        seekBarEmbeddingThreads = view.findViewById(R.id.seekBarEmbeddingThreads);
        textViewEmbeddingThreadsValue = view.findViewById(R.id.textViewEmbeddingThreadsValue);
        
        // Knowledge Graph RAG UI组件初始化
        seekBarGraphMinEdgeWeight = view.findViewById(R.id.seekBarGraphMinEdgeWeight);
        textViewGraphMinEdgeWeightValue = view.findViewById(R.id.textViewGraphMinEdgeWeightValue);
        seekBarGraphMaxExpandEntities = view.findViewById(R.id.seekBarGraphMaxExpandEntities);
        textViewGraphMaxExpandEntitiesValue = view.findViewById(R.id.textViewGraphMaxExpandEntitiesValue);
        seekBarGraphEntityConfidenceThreshold = view.findViewById(R.id.seekBarGraphEntityConfidenceThreshold);
        textViewGraphEntityConfidenceThresholdValue = view.findViewById(R.id.textViewGraphEntityConfidenceThresholdValue);
        switchGraphRagMode = view.findViewById(R.id.switchGraphRagMode);
        spinnerGraphRagWeightPreset = view.findViewById(R.id.spinnerGraphRagWeightPreset);
        seekBarGraphMaxExpandChunks = view.findViewById(R.id.seekBarGraphMaxExpandChunks);
        textViewGraphMaxExpandChunksValue = view.findViewById(R.id.textViewGraphMaxExpandChunksValue);
        seekBarGraphHubThreshold = view.findViewById(R.id.seekBarGraphHubThreshold);
        textViewGraphHubThresholdValue = view.findViewById(R.id.textViewGraphHubThresholdValue);
        seekBarGraphHubThresholdQuery = view.findViewById(R.id.seekBarGraphHubThresholdQuery);
        textViewGraphHubThresholdQueryValue = view.findViewById(R.id.textViewGraphHubThresholdQueryValue);
        seekBarGraphRagVectorExpand = view.findViewById(R.id.seekBarGraphRagVectorExpand);
        textViewGraphRagVectorExpandValue = view.findViewById(R.id.textViewGraphRagVectorExpandValue);
        spinnerGraphStopwords = view.findViewById(R.id.spinnerGraphStopwords);
        switchAgentExperienceSummary = view.findViewById(R.id.switchAgentExperienceSummary);
        switchAgentTts = view.findViewById(R.id.switchAgentTts);
        seekBarAgentMaxSteps = view.findViewById(R.id.seekBarAgentMaxSteps);
        textViewAgentMaxStepsValue = view.findViewById(R.id.textViewAgentMaxStepsValue);
        
        editTextDataRootPath = view.findViewById(R.id.editTextDataRootPath);
        buttonSelectDataRootPath = view.findViewById(R.id.buttonSelectDataRootPath);
        switchShowDebugPerformance = view.findViewById(R.id.switchShowDebugPerformance); // Show debug & performance switch
        switchDebugMode = view.findViewById(R.id.switchDebugMode);
        spinnerUseGpu = view.findViewById(R.id.spinnerBackendPreference);
        spinnerGraphCustomDictionary = view.findViewById(R.id.spinnerGraphCustomDictionary);
        // ONNX引擎开关初始化已移除
        switchJsonDatasetSplitting = view.findViewById(R.id.switchJsonDatasetSplitting); // JSON训练集分块优化开关
        
        // 初始化 LLM 推理设置相关UI组件
        seekBarMaxSequenceLength = view.findViewById(R.id.seekBarMaxSequenceLength);
        textViewMaxSequenceLengthValue = view.findViewById(R.id.textViewMaxSequenceLengthValue);
        seekBarThreads = view.findViewById(R.id.seekBarThreads);
        textViewThreadsValue = view.findViewById(R.id.textViewThreadsValue);
        seekBarKvCacheSize = view.findViewById(R.id.seekBarKvCacheSize);
        textViewKvCacheSizeValue = view.findViewById(R.id.textViewKvCacheSizeValue);
        seekBarHistoryRounds = view.findViewById(R.id.seekBarHistoryRounds);
        textViewHistoryRoundsValue = view.findViewById(R.id.textViewHistoryRoundsValue);
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
        seekBarImagePreprocessSize = view.findViewById(R.id.seekBarImagePreprocessSize); // 图片预处理尺寸
        textViewImagePreprocessSizeValue = view.findViewById(R.id.textViewImagePreprocessSizeValue); // 图片预处理尺寸显示
        // 图像编码线程数UI已移除（MNN不支持独立配置）
        
        // Diffusion扩散设置控件
        spinnerDiffusionMemoryMode = view.findViewById(R.id.spinnerDiffusionMemoryMode);
        spinnerDiffusionImageSize = view.findViewById(R.id.spinnerDiffusionImageSize);
        seekBarDiffusionSteps = view.findViewById(R.id.seekBarDiffusionSteps);
        textViewDiffusionStepsValue = view.findViewById(R.id.textViewDiffusionStepsValue);
        seekBarDiffusionCfg = view.findViewById(R.id.seekBarDiffusionCfg);
        textViewDiffusionCfgValue = view.findViewById(R.id.textViewDiffusionCfgValue);
        editTextDiffusionSeed = view.findViewById(R.id.editTextDiffusionSeed);
        switchDiffusionSeedRandom = view.findViewById(R.id.switchDiffusionSeedRandom);
        switchDiffusionTeOnCpu = view.findViewById(R.id.switchDiffusionTeOnCpu);
        
        // ASR/TTS设置控件
        spinnerAsrModel = view.findViewById(R.id.spinnerAsrModel);
        spinnerTtsModel = view.findViewById(R.id.spinnerTtsModel);
        layoutTtsDitSteps = view.findViewById(R.id.layoutTtsDitSteps);
        seekBarTtsDitSteps = view.findViewById(R.id.seekBarTtsDitSteps);
        textViewTtsDitStepsValue = view.findViewById(R.id.textViewTtsDitStepsValue);
        layoutTtsSpeaker = view.findViewById(R.id.layoutTtsSpeaker);
        spinnerTtsSpeaker = view.findViewById(R.id.spinnerTtsSpeaker);
        switchTtsAutoPlay = view.findViewById(R.id.switchTtsAutoPlay);
        layoutTtsSpeed = view.findViewById(R.id.layoutTtsSpeed);
        seekBarTtsSpeed = view.findViewById(R.id.seekBarTtsSpeed);
        textViewTtsSpeedValue = view.findViewById(R.id.textViewTtsSpeedValue);
        layoutTtsPitch = view.findViewById(R.id.layoutTtsPitch);
        seekBarTtsPitch = view.findViewById(R.id.seekBarTtsPitch);
        textViewTtsPitchValue = view.findViewById(R.id.textViewTtsPitchValue);
        
        // 设置后端偏好Spinner适配器
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, BACKEND_OPTIONS);
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUseGpu.setAdapter(backendAdapter);
        
        // 设置Diffusion内存模式Spinner适配器
        ArrayAdapter<CharSequence> memoryModeAdapter = ArrayAdapter.createFromResource(requireContext(),
            R.array.diffusion_memory_modes, android.R.layout.simple_spinner_item);
        memoryModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiffusionMemoryMode.setAdapter(memoryModeAdapter);

        // 设置Diffusion精度模式Spinner适配器
        spinnerDiffusionPrecisionMode = view.findViewById(R.id.spinnerDiffusionPrecisionMode);
        ArrayAdapter<CharSequence> precisionModeAdapter = ArrayAdapter.createFromResource(requireContext(),
            R.array.diffusion_precision_modes, android.R.layout.simple_spinner_item);
        precisionModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiffusionPrecisionMode.setAdapter(precisionModeAdapter);

        // 设置Diffusion GPU内存模式Spinner适配器
        spinnerDiffusionGpuMemoryMode = view.findViewById(R.id.spinnerDiffusionGpuMemoryMode);
        ArrayAdapter<CharSequence> gpuMemoryModeAdapter = ArrayAdapter.createFromResource(requireContext(),
            R.array.diffusion_gpu_memory_modes, android.R.layout.simple_spinner_item);
        gpuMemoryModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiffusionGpuMemoryMode.setAdapter(gpuMemoryModeAdapter);

        ArrayAdapter<CharSequence> diffusionImageSizeAdapter = ArrayAdapter.createFromResource(requireContext(),
            R.array.diffusion_image_sizes, android.R.layout.simple_spinner_item);
        diffusionImageSizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiffusionImageSize.setAdapter(diffusionImageSizeAdapter);
        
        // 设置ASR/TTS模型Spinner适配器
        setupAsrTtsSpinners();
        
        // 初始化图谱停用词与外挂词典下拉框
        ConfigManager.ensureDefaultStopwordsExample(requireContext());
        initializeGraphStopwordsSpinner();
        initializeGraphCustomDictionarySpinner();
        
        // 初始化Agent设置
        initializeAgentExperienceSummarySwitch();
        initializeAgentTtsSwitch();
        initializeAgentMaxStepsSeekBar();
        
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
                // No menu items for Settings Fragment
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == android.R.id.home) {
                    // CRITICAL: Check if fragment is still attached before accessing Activity
                    if (!isAdded() || getActivity() == null) {
                        android.util.Log.w("SettingsFragment", "[MENU] Fragment not attached, ignoring back button");
                        return true;
                    }
                    
                    // CRITICAL: Restore main UI BEFORE popBackStack (Fragment will be detached after pop)
                    androidx.appcompat.app.ActionBar actionBar = ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar();
                    if (actionBar != null) {
                        actionBar.setDisplayHomeAsUpEnabled(false);
                        actionBar.setTitle(R.string.app_name);
                    }
                    getActivity().findViewById(R.id.container).setVisibility(android.view.View.GONE);
                    getActivity().findViewById(R.id.viewPager).setVisibility(android.view.View.VISIBLE);
                    
                    // Now clear all fragments from back stack
                    androidx.fragment.app.FragmentManager fm = getActivity().getSupportFragmentManager();
                    while (fm.getBackStackEntryCount() > 0) {
                        fm.popBackStackImmediate();
                    }
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Show back button and set title in ActionBar
        if (getActivity() != null && ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.title_settings);
        }

        if (switchGraphRagMode != null) {
            boolean graphRagEnabled = ConfigManager.isGraphRagEnabled(requireContext());
            if (switchGraphRagMode.isChecked() != graphRagEnabled) {
                switchGraphRagMode.setChecked(graphRagEnabled);
            }
        }
    }
    
    private void setupListeners() {
        // 选择数据根目录
        buttonSelectDataRootPath.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            dataRootPathLauncher.launch(intent);
        });
        
        // Diffusion Steps滑块监听器
        seekBarDiffusionSteps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int steps = progress + 1; // 转换为1-50范围
                textViewDiffusionStepsValue.setText(String.valueOf(steps));
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int steps = seekBar.getProgress() + 1;
                ConfigManager.setDiffusionSteps(requireContext(), steps);
            }
        });
        
        // Diffusion CFG滑块监听器 (0~10, step 0.25, max=40 means 0~10 with 0.25 step)
        seekBarDiffusionCfg.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float cfg = progress * 0.25f; // progress 0-40 -> cfg 0.0-10.0
                textViewDiffusionCfgValue.setText(String.format(java.util.Locale.US, "%.2f", cfg));
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float cfg = seekBar.getProgress() * 0.25f;
                ConfigManager.setDiffusionCfg(requireContext(), cfg);
            }
        });
        
        // Diffusion Seed随机复选框监听器
        switchDiffusionSeedRandom.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editTextDiffusionSeed.setEnabled(!isChecked);
            if (isChecked) {
                editTextDiffusionSeed.setAlpha(0.5f); // 变灰不可编辑
            } else {
                editTextDiffusionSeed.setAlpha(1.0f); // 恢复正常
                // 如果没有值，随机生成一个初始值
                if (editTextDiffusionSeed.getText().toString().trim().isEmpty()) {
                    int randomSeed = new java.util.Random().nextInt(1000000);
                    editTextDiffusionSeed.setText(String.valueOf(randomSeed));
                }
            }
            // Save both the random flag and the seed value
            ConfigManager.setDiffusionSeedRandom(requireContext(), isChecked);
            if (!isChecked) {
                // Fixed seed mode: save the seed value
                String seedText = editTextDiffusionSeed.getText().toString().trim();
                if (!seedText.isEmpty()) {
                    try {
                        int seed = Integer.parseInt(seedText);
                        ConfigManager.setDiffusionSeed(requireContext(), seed);
                    } catch (NumberFormatException e) {
                        // Invalid number, use default
                        ConfigManager.setDiffusionSeed(requireContext(), 42);
                    }
                }
            }
        });
        
        // Diffusion Seed EditText focus change listener - save value when focus is lost
        editTextDiffusionSeed.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !switchDiffusionSeedRandom.isChecked()) {
                // Lost focus and not in random mode, save the seed value
                String seedText = editTextDiffusionSeed.getText().toString().trim();
                if (!seedText.isEmpty()) {
                    try {
                        int seed = Integer.parseInt(seedText);
                        ConfigManager.setDiffusionSeed(requireContext(), seed);
                    } catch (NumberFormatException e) {
                        // Invalid number, ignore
                    }
                }
            }
        });
        
        // Diffusion Text Encoder on CPU switch listener
        switchDiffusionTeOnCpu.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setDiffusionTextEncoderOnCPU(requireContext(), isChecked);
        });
        
        // 设置所有SeekBar监听器
        setupChunkSizeSeekBar();
        setupOverlapSizeSeekBar();
        setupMinChunkSizeSeekBar();
        setupGraphMinEdgeWeightSeekBar();
        setupGraphMaxExpandEntitiesSeekBar();
        setupGraphEntityConfidenceThresholdSeekBar();
        setupMaxSequenceLengthSeekBar();
        setupThreadsSeekBar();
        setupKvCacheSizeSeekBar();
        setupHistoryRoundsSeekBar();
        setupManualTemperatureSeekBar();
        setupManualTopPSeekBar();
        setupManualTopKSeekBar();
        setupManualRepeatPenaltySeekBar();
        setupImagePreprocessSizeSeekBar();
        setupTtsDitStepsSeekBar();
        setupTtsSpeedSeekBar();
        setupTtsPitchSeekBar();
        // setupImageEncodingThreadsSeekBar已移除（MNN不支持独立配置）
        
        // 设置Spinner监听器
        spinnerUseGpu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String backend = (position >= 0 && position < BACKEND_VALUES.length) ? BACKEND_VALUES[position] : "CPU";
                // Note: Backend preference uses setString directly as it's a simple string value
                ConfigManager.setString(requireContext(), ConfigManager.KEY_USE_GPU, backend);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerDiffusionImageSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isDiffusionImageSizeSpinnerInitialized) {
                    isDiffusionImageSizeSpinnerInitialized = true;
                    int currentWidth = ConfigManager.getDiffusionImageWidth(requireContext());
                    int currentHeight = ConfigManager.getDiffusionImageHeight(requireContext());
                    LogManager.logD(TAG, "[SETTINGS] Diffusion image size spinner initial selection: position=" + position + ", currentSize=" + currentWidth + "x" + currentHeight);
                    return;
                }

                // Preset sizes: index -> (width, height)
                // Sorted by pixel count from small to large, default is 512x512 (index 8)
                // 0: 256x256 (1:1 Fast)
                // 1: 320x320 (1:1 Fast)
                // 2: 384x384 (1:1 Fast)
                // 3: 288x512 (9:16 Small Tall)
                // 4: 512x288 (16:9 Small Wide)
                // 5: 448x448 (1:1 Medium Fast)
                // 6: 384x640 (3:5 Portrait)
                // 7: 640x384 (5:3 Widescreen)
                // 8: 512x512 (1:1 Default)
                // 9: 512x768 (2:3 Portrait)
                // 10: 768x512 (3:2 Widescreen)
                // 11: 640x640 (1:1 Medium)
                // 12: 768x768 (1:1)
                // 13: 512x896 (4:7 Ultra-tall)
                // 14: 896x512 (7:4 Ultra-wide)
                // 15: 896x896 (1:1 High Quality)
                // 16: 768x1024 (3:4 Portrait)
                // 17: 1024x768 (4:3 Landscape)
                // 18: 720x1280 (9:16 Mobile)
                // 19: 1280x720 (16:9 Widescreen)
                // 20: 1024x1024 (1:1)
                // 21: 1152x1152 (1:1)
                // 22: 1024x1536 (2:3 Portrait)
                // 23: 1536x1024 (3:2 Widescreen)
                // 24: 1280x1280 (1:1)
                // 25: 1024x1792 (4:7 Ultra-tall)
                // 26: 1792x1024 (7:4 Ultra-wide)
                // 27: 1536x1536 (1:1 High Quality)
                // 28: 1280x1920 (2:3 Portrait)
                // 29: 1920x1280 (3:2 Widescreen)
                // 30: 1440x2560 (9:16 Mobile)
                // 31: 2560x1440 (16:9 Widescreen)
                // 32: 2048x2048 (1:1 Max)
                int width, height;
                switch (position) {
                    case 0: width = 256; height = 256; break;
                    case 1: width = 320; height = 320; break;
                    case 2: width = 384; height = 384; break;
                    case 3: width = 288; height = 512; break;
                    case 4: width = 512; height = 288; break;
                    case 5: width = 448; height = 448; break;
                    case 6: width = 384; height = 640; break;
                    case 7: width = 640; height = 384; break;
                    case 8: width = 512; height = 512; break;
                    case 9: width = 512; height = 768; break;
                    case 10: width = 768; height = 512; break;
                    case 11: width = 640; height = 640; break;
                    case 12: width = 768; height = 768; break;
                    case 13: width = 512; height = 896; break;
                    case 14: width = 896; height = 512; break;
                    case 15: width = 896; height = 896; break;
                    case 16: width = 768; height = 1024; break;
                    case 17: width = 1024; height = 768; break;
                    case 18: width = 720; height = 1280; break;
                    case 19: width = 1280; height = 720; break;
                    case 20: width = 1024; height = 1024; break;
                    case 21: width = 1152; height = 1152; break;
                    case 22: width = 1024; height = 1536; break;
                    case 23: width = 1536; height = 1024; break;
                    case 24: width = 1280; height = 1280; break;
                    case 25: width = 1024; height = 1792; break;
                    case 26: width = 1792; height = 1024; break;
                    case 27: width = 1536; height = 1536; break;
                    case 28: width = 1280; height = 1920; break;
                    case 29: width = 1920; height = 1280; break;
                    case 30: width = 1440; height = 2560; break;
                    case 31: width = 2560; height = 1440; break;
                    case 32: width = 2048; height = 2048; break;
                    default: width = 512; height = 512; break;
                }

                LogManager.logD(TAG, "[SETTINGS] Diffusion image size changed by user: position=" + position + ", size=" + width + "x" + height);
                ConfigManager.setDiffusionImageWidth(requireContext(), width);
                ConfigManager.setDiffusionImageHeight(requireContext(), height);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerDiffusionMemoryMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isDiffusionMemoryModeSpinnerInitialized) {
                    isDiffusionMemoryModeSpinnerInitialized = true;
                    int currentMode = ConfigManager.getDiffusionMemoryMode(requireContext());
                    LogManager.logD(TAG, "[SETTINGS] Diffusion memory mode spinner initial selection: position=" + position + ", currentMode=" + currentMode);
                    return;
                }
	
                int mode;
                switch (position) {
                    case 0:
                        mode = 0;
                        break;
                    case 1:
                        mode = 1;
                        break;
                    case 2:
                        mode = 2;
                        break;
                    default:
                        mode = 0;
                        break;
                }
                LogManager.logD(TAG, "[SETTINGS] Diffusion memory mode changed by user: position=" + position + ", mode=" + mode);
                ConfigManager.setDiffusionMemoryMode(requireContext(), mode);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // Diffusion precision mode listener
        spinnerDiffusionPrecisionMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isInitialized = false;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitialized) {
                    isInitialized = true;
                    return;
                }
                LogManager.logD(TAG, "[SETTINGS] Diffusion precision mode changed: " + position);
                ConfigManager.setDiffusionPrecisionMode(requireContext(), position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // Diffusion GPU memory mode listener
        spinnerDiffusionGpuMemoryMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isInitialized = false;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitialized) {
                    isInitialized = true;
                    return;
                }
                LogManager.logD(TAG, "[SETTINGS] Diffusion GPU memory mode changed: " + position);
                ConfigManager.setDiffusionGpuMemoryMode(requireContext(), position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerAsrModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String model = parent.getItemAtPosition(position).toString();
                if (!isAsrSpinnerInitialized) {
                    // First selection is from initial binding, do not persist
                    isAsrSpinnerInitialized = true;
                    LogManager.logD(TAG, "[SETTINGS] ASR spinner initial selection: " + model);
                    return;
                }
                LogManager.logD(TAG, "[SETTINGS] ASR model changed by user: " + model);
                // Note: ASR model uses setString directly as it's a simple string value
                ConfigManager.setString(requireContext(), ConfigManager.KEY_ASR_MODEL, model);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerTtsModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String model = parent.getItemAtPosition(position).toString();
                if (!isTtsSpinnerInitialized) {
                    // First selection is from initial binding, do not persist
                    isTtsSpinnerInitialized = true;
                    LogManager.logD(TAG, "[SETTINGS] TTS spinner initial selection: " + model);
                    updateTtsSettingsVisibility(model);
                    return;
                }
                LogManager.logD(TAG, "[SETTINGS] TTS model changed by user: " + model);
                // Note: TTS model uses setString directly as it's a simple string value
                ConfigManager.setString(requireContext(), ConfigManager.KEY_TTS_MODEL, model);
                // Update TTS settings visibility based on selected model
                updateTtsSettingsVisibility(model);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerTtsSpeaker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ConfigManager.setTtsSpeakerId(requireContext(), position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // 设置Switch监听器
        switchShowDebugPerformance.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setShowDebugPerformance(requireContext(), isChecked);
        });
        
        switchDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setDebugMode(requireContext(), isChecked);
        });
        
        switchJsonDatasetSplitting.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setJsonDatasetSplittingEnabled(requireContext(), isChecked);
        });

        switchGraphRagMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setGraphRagEnabled(requireContext(), isChecked);
        });
        
        switchPriorityManualParams.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setPriorityManualParams(requireContext(), isChecked);
        });
        
        switchTtsAutoPlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setTtsAutoPlay(requireContext(), isChecked);
        });
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
    
    private void updateEmbeddingConcurrencyText(int progress) {
        int concurrency = progress + 1;
        textViewEmbeddingConcurrencyValue.setText(String.format("%d", concurrency));
    }
    
    private void updateEmbeddingThreadsText(int progress) {
        int threads = progress + 1;
        textViewEmbeddingThreadsValue.setText(String.format("%d", threads));
    }
    
    private void setupChunkSizeSeekBar() {
        seekBarChunkSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 更新分块大小值显示
                updateChunkSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = (seekBar.getProgress() * 100) + 100;
                ConfigManager.setChunkSize(requireContext(), size);
            }
        });
        
        seekBarOverlapSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateOverlapSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = (seekBar.getProgress() * 20) + 20;
                ConfigManager.setOverlapSize(requireContext(), size);
            }
        });
        
        seekBarMinChunkSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMinChunkSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = (seekBar.getProgress() * 10) + 10;
                ConfigManager.setMinChunkSize(requireContext(), size);
            }
        });
        
        seekBarEmbeddingConcurrency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Update embedding concurrency value text
                updateEmbeddingConcurrencyText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int concurrency = seekBar.getProgress() + 1; // 1-4
                if (concurrency < 1) {
                    concurrency = 1; // Clamp lower bound
                }
                if (concurrency > 4) {
                    concurrency = 4; // Clamp upper bound
                }
                ConfigManager.setEmbeddingConcurrency(requireContext(), concurrency);
            }
        });

        seekBarEmbeddingThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Update embedding threads value text
                updateEmbeddingThreadsText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int threads = seekBar.getProgress() + 1; // 1-4
                if (threads < 1) {
                    threads = 1; // Clamp lower bound
                }
                if (threads > 4) {
                    threads = 4; // Clamp upper bound
                }
                ConfigManager.setEmbeddingThreads(requireContext(), threads);
            }
        });
        
        // Knowledge Graph RAG SeekBars
        seekBarGraphMinEdgeWeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphMinEdgeWeightText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int weight = seekBar.getProgress() + 1; // 1-10
                ConfigManager.setGraphMinEdgeWeight(requireContext(), weight);
            }
        });
        
        seekBarGraphMaxExpandEntities.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphMaxExpandEntitiesText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int entities = (seekBar.getProgress() * 10) + 10; // 10-200
                ConfigManager.setGraphMaxExpandEntities(requireContext(), entities);
            }
        });
        
        seekBarGraphEntityConfidenceThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphEntityConfidenceThresholdText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float threshold = (seekBar.getProgress() + 1) / 10.0f; // 0.1-1.0
                ConfigManager.setGraphEntityConfidenceThreshold(requireContext(), threshold);
            }
        });
        
        seekBarGraphMaxExpandChunks.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphMaxExpandChunksText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int chunks = (seekBar.getProgress() * 10) + 10;
                ConfigManager.setGraphMaxExpandChunks(requireContext(), chunks);
            }
        });

        // Hub threshold (build phase)
        seekBarGraphHubThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphHubThresholdText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int threshold = mapHubProgressToThreshold(seekBar.getProgress());
                ConfigManager.setGraphHubThresholdBuild(requireContext(), threshold);
            }
        });

        // Hub threshold (query-time Graph RAG)
        seekBarGraphHubThresholdQuery.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphHubThresholdQueryText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int threshold = mapHubProgressToThreshold(seekBar.getProgress());
                ConfigManager.setGraphHubThresholdQuery(requireContext(), threshold);
            }
        });
        
        // Graph RAG vector coarse recall expand
        seekBarGraphRagVectorExpand.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGraphRagVectorExpandText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int expand = mapGraphRagVectorExpandProgressToValue(seekBar.getProgress());
                ConfigManager.setGraphRagVectorExpand(requireContext(), expand);
            }
        });
        
        seekBarMaxSequenceLength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMaxSequenceLengthText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int length = (seekBar.getProgress() * 512) + 512;
                ConfigManager.setMaxSequenceLength(requireContext(), length);
            }
        });
        
        seekBarThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateThreadsText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int threads = seekBar.getProgress() + 1;
                ConfigManager.setThreads(requireContext(), threads);
            }
        });
        
        seekBarKvCacheSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateKvCacheSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = (seekBar.getProgress() * 512) + 512;
                ConfigManager.setMaxNewTokens(requireContext(), size);
            }
        });
        
        seekBarHistoryRounds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateHistoryRoundsText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int rounds = seekBar.getProgress();
                ConfigManager.setHistoryRounds(requireContext(), rounds);
            }
        });
        
        seekBarManualTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateManualTemperatureText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float temperature = seekBar.getProgress() / 10.0f;
                ConfigManager.setFloat(requireContext(), ConfigManager.KEY_MANUAL_TEMPERATURE, temperature);
            }
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
            public void onStopTrackingTouch(SeekBar seekBar) {
                float topP = seekBar.getProgress() * 0.05f;
                ConfigManager.setFloat(requireContext(), ConfigManager.KEY_MANUAL_TOP_P, topP);
            }
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
            public void onStopTrackingTouch(SeekBar seekBar) {
                int topK = (seekBar.getProgress() * 10) + 10;
                ConfigManager.setManualTopK(requireContext(), topK);
            }
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
            public void onStopTrackingTouch(SeekBar seekBar) {
                float repeatPenalty = seekBar.getProgress() / 10.0f;
                ConfigManager.setFloat(requireContext(), ConfigManager.KEY_MANUAL_REPEAT_PENALTY, repeatPenalty);
            }
        });
    }
    
    private void updateManualRepeatPenaltyText(int progress) {
        float repeatPenalty = progress / 10.0f;
        textViewManualRepeatPenaltyValue.setText(String.format("%.1f", repeatPenalty));
    }
    
    private void setupImagePreprocessSizeSeekBar() {
        seekBarImagePreprocessSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateImagePreprocessSizeText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = progressToSize(seekBar.getProgress());
                ConfigManager.setImagePreprocessSize(requireContext(), size);
            }
        });
    }
    
    private void updateImagePreprocessSizeText(int progress) {
        // Preset-based mapping: 420-800 + Auto mode
        int size = progressToSize(progress);
        String displayText = (size == 0) ? "Auto" : String.valueOf(size);
        textViewImagePreprocessSizeValue.setText(displayText);
    }
    
    /**
     * Convert SeekBar progress to image size
     * Progress 0-7 maps to: 420, 504, 560, 616, 672, 728, 784, 800
     * Progress 8 maps to: Auto (0) - use model's llm_config.json image_size
     */
    private int progressToSize(int progress) {
        switch (progress) {
            case 0: return ConfigManager.IMAGE_SIZE_420;  // 420
            case 1: return ConfigManager.IMAGE_SIZE_504;  // 504
            case 2: return ConfigManager.IMAGE_SIZE_560;  // 560
            case 3: return ConfigManager.IMAGE_SIZE_616;  // 616
            case 4: return ConfigManager.IMAGE_SIZE_672;  // 672
            case 5: return ConfigManager.IMAGE_SIZE_728;  // 728
            case 6: return ConfigManager.IMAGE_SIZE_784;  // 784
            case 7: return ConfigManager.IMAGE_SIZE_800;  // 800
            case 8: return ConfigManager.IMAGE_SIZE_AUTO; // 0 (Auto)
            default: return ConfigManager.IMAGE_SIZE_AUTO; // Auto (default)
        }
    }
    
    /**
     * Convert image size to SeekBar progress
     * CRITICAL: Must check 0 (Auto mode) FIRST, because 0 < 420 would return wrong progress!
     */
    private int sizeToProgress(int size) {
        // Check Auto mode (0) first - this is the most important fix!
        if (size == ConfigManager.IMAGE_SIZE_AUTO) return 8; // 0 → progress 8 (Auto)
        
        // Then check other sizes in ascending order
        if (size <= ConfigManager.IMAGE_SIZE_420) return 0;  // ≤420 → progress 0
        if (size <= ConfigManager.IMAGE_SIZE_504) return 1;  // ≤504 → progress 1
        if (size <= ConfigManager.IMAGE_SIZE_560) return 2;  // ≤560 → progress 2
        if (size <= ConfigManager.IMAGE_SIZE_616) return 3;  // ≤616 → progress 3
        if (size <= ConfigManager.IMAGE_SIZE_672) return 4;  // ≤672 → progress 4
        if (size <= ConfigManager.IMAGE_SIZE_728) return 5;  // ≤728 → progress 5
        if (size <= ConfigManager.IMAGE_SIZE_784) return 6;  // ≤784 → progress 6
        if (size <= ConfigManager.IMAGE_SIZE_800) return 7;  // ≤800 → progress 7
        
        // Any size > 800 also maps to Auto mode
        return 8; // > 800 or unknown → progress 8 (Auto)
    }
    
    // setupImageEncodingThreadsSeekBar和updateImageEncodingThreadsText方法已移除（MNN不支持独立配置）
    
    private void setupAsrTtsSpinners() {
        Context context = requireContext();
        
        // Setup ASR model spinner
        String asrPath = ConfigManager.getAsrModelPath(context);
        File asrDir = new File(asrPath);
        java.util.List<String> asrModels = new java.util.ArrayList<>();
        asrModels.add(getString(R.string.settings_asr_model_none)); // "无" or "None"
        
        if (asrDir.exists() && asrDir.isDirectory()) {
            File[] asrDirs = asrDir.listFiles(File::isDirectory);
            if (asrDirs != null) {
                for (File dir : asrDirs) {
                    asrModels.add(dir.getName());
                }
            }
        }
        
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(context,
            android.R.layout.simple_spinner_item, asrModels);
        asrAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAsrModel.setAdapter(asrAdapter);
        
        // Setup TTS model spinner
        String ttsPath = ConfigManager.getTtsModelPath(context);
        File ttsDir = new File(ttsPath);
        java.util.List<String> ttsModels = new java.util.ArrayList<>();
        ttsModels.add(getString(R.string.settings_tts_model_none)); // "无" or "None"
        ttsModels.add(getString(R.string.settings_tts_model_system)); // "系统" or "System"
        ttsModels.add(getString(R.string.settings_tts_model_native_omni)); // "原生(Omni)" or "Native (Omni)"
        
        // Dynamically scan TTS model directory and add discovered models
        if (ttsDir.exists() && ttsDir.isDirectory()) {
            File[] ttsDirs = ttsDir.listFiles(File::isDirectory);
            if (ttsDirs != null) {
                for (File dir : ttsDirs) {
                    ttsModels.add(dir.getName());
                }
            }
        }
        
        ArrayAdapter<String> ttsAdapter = new ArrayAdapter<>(context,
            android.R.layout.simple_spinner_item, ttsModels);
        ttsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTtsModel.setAdapter(ttsAdapter);
        
        // Setup TTS speaker spinner
        ArrayAdapter<CharSequence> speakerAdapter = ArrayAdapter.createFromResource(context,
            R.array.tts_speaker_options, android.R.layout.simple_spinner_item);
        speakerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTtsSpeaker.setAdapter(speakerAdapter);
    }
    
    private void setupTtsDitStepsSeekBar() {
        seekBarTtsDitSteps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTtsDitStepsText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int steps = seekBar.getProgress() + 1;
                ConfigManager.setTtsDitSteps(requireContext(), steps);
            }
        });
    }
    
    private void updateTtsDitStepsText(int progress) {
        int steps = progress + 1; // Convert to 1-10 range
        textViewTtsDitStepsValue.setText(getString(R.string.settings_tts_dit_steps_value, steps));
    }
    
    private void setupTtsSpeedSeekBar() {
        seekBarTtsSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTtsSpeedText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float speed = seekBar.getProgress() / 100.0f;
                ConfigManager.setTtsSpeed(requireContext(), speed);
            }
        });
    }
    
    private void updateTtsSpeedText(int progress) {
        float speed = progress / 100.0f; // Convert to 0.5-2.0 range
        textViewTtsSpeedValue.setText(String.format("%.1f", speed));
    }
    
    private void setupTtsPitchSeekBar() {
        seekBarTtsPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTtsPitchText(progress);
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float pitch = seekBar.getProgress() / 100.0f;
                ConfigManager.setTtsPitch(requireContext(), pitch);
            }
        });
    }
    
    private void updateTtsPitchText(int progress) {
        float pitch = progress / 100.0f; // Convert to 0.5-2.0 range
        textViewTtsPitchValue.setText(String.format("%.1f", pitch));
    }
    
    private void updateChunkSizeText(int progress) {
        int size = (progress * 100) + 100;
        textViewChunkSizeValue.setText(String.format("%d", size));
    }
    
    private void updateOverlapSizeText(int progress) {
        int size = (progress * 20) + 20;
        textViewOverlapSizeValue.setText(String.format("%d", size));
    }
    
    private void updateMinChunkSizeText(int progress) {
        int size = (progress * 10) + 10;    
        textViewMinChunkSizeValue.setText(String.format("%d", size));
    }
    
    private void updateGraphMinEdgeWeightText(int progress) {
        int weight = progress + 1; // 1-10
        textViewGraphMinEdgeWeightValue.setText(String.format("%d", weight));
    }
    
    private void updateGraphMaxExpandEntitiesText(int progress) {
        int entities = (progress * 10) + 10; // 10-200
        textViewGraphMaxExpandEntitiesValue.setText(String.format("%d", entities));
    }

    private void updateGraphMaxExpandChunksText(int progress) {
        int chunks = (progress * 10) + 10;
        textViewGraphMaxExpandChunksValue.setText(String.format("%d", chunks));
    }

    private void updateGraphHubThresholdText(int progress) {
        int threshold = mapHubProgressToThreshold(progress);
        textViewGraphHubThresholdValue.setText(String.valueOf(threshold));
    }

    private void updateGraphHubThresholdQueryText(int progress) {
        int threshold = mapHubProgressToThreshold(progress);
        textViewGraphHubThresholdQueryValue.setText(String.valueOf(threshold));
    }

    private int mapHubProgressToThreshold(int progress) {
        if (progress <= 0) {
            return 0;
        }
        if (progress >= HUB_THRESHOLD_PRESETS.length) {
            progress = HUB_THRESHOLD_PRESETS.length - 1;
        }
        return HUB_THRESHOLD_PRESETS[progress];
    }

    private int mapHubThresholdToProgress(int threshold) {
        if (threshold <= 0) {
            return 0;
        }
        int bestIndex = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < HUB_THRESHOLD_PRESETS.length; i++) {
            int v = HUB_THRESHOLD_PRESETS[i];
            int diff = Math.abs(v - threshold);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void updateGraphRagVectorExpandText(int progress) {
        int value = mapGraphRagVectorExpandProgressToValue(progress);
        textViewGraphRagVectorExpandValue.setText(String.valueOf(value));
    }

    private int mapGraphRagVectorExpandProgressToValue(int progress) {
        if (progress < 0) {
            progress = 0;
        }
        if (progress >= GRAPH_RAG_VECTOR_EXPAND_PRESETS.length) {
            progress = GRAPH_RAG_VECTOR_EXPAND_PRESETS.length - 1;
        }
        return GRAPH_RAG_VECTOR_EXPAND_PRESETS[progress];
    }

    private int mapGraphRagVectorExpandValueToProgress(int value) {
        int bestIndex = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < GRAPH_RAG_VECTOR_EXPAND_PRESETS.length; i++) {
            int v = GRAPH_RAG_VECTOR_EXPAND_PRESETS[i];
            int diff = Math.abs(v - value);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        return bestIndex;
    }
    
    private void updateGraphEntityConfidenceThresholdText(int progress) {
        // Map progress 0-10 -> threshold 0.5-1.0 (step 0.05)
        float threshold = 0.5f + (progress * 0.05f);
        textViewGraphEntityConfidenceThresholdValue.setText(String.format("%.1f", threshold));
    }
    
    private void updateMaxSequenceLengthText(int progress) {
        int length = (progress * 512) + 512;
        textViewMaxSequenceLengthValue.setText(String.format("%d", length));
    }
    
    private void updateThreadsText(int progress) {
        int threads = progress + 1;
        textViewThreadsValue.setText(String.format("%d", threads));
    }
    
    private void updateKvCacheSizeText(int progress) {
        int size = (progress * 512) + 512;
        textViewKvCacheSizeValue.setText(String.format("%d", size));
    }
    
    private void updateHistoryRoundsText(int progress) {
        textViewHistoryRoundsValue.setText(String.format("%d", progress));
    }
    
    // Empty setup methods (all SeekBars configured in setupChunkSizeSeekBar)
    private void setupOverlapSizeSeekBar() {}
    private void setupMinChunkSizeSeekBar() {}
    private void setupGraphMinEdgeWeightSeekBar() {}
    private void setupGraphMaxExpandEntitiesSeekBar() {}
    private void setupGraphEntityConfidenceThresholdSeekBar() {}
    private void setupMaxSequenceLengthSeekBar() {}
    private void setupThreadsSeekBar() {}
    private void setupKvCacheSizeSeekBar() {}
    private void setupHistoryRoundsSeekBar() {}
    private void setupManualTemperatureSeekBar() {}
    
    public static String getBackendPreference(Context context) {
        return ConfigManager.getString(context, ConfigManager.KEY_USE_GPU, "CPU");
    }
    
    private void loadSettings() {
        // Load all settings from ConfigManager and set UI controls
        Context ctx = requireContext();
        
        // Data root path
        String dataRootPath = ConfigManager.getDataRootPath(ctx);
        editTextDataRootPath.setText(dataRootPath);
        
        // Backend preference
        String backend = ConfigManager.getString(ctx, ConfigManager.KEY_USE_GPU, "CPU");
        int backendPos = java.util.Arrays.asList(BACKEND_VALUES).indexOf(backend);
        if (backendPos >= 0) spinnerUseGpu.setSelection(backendPos);
        
        // Diffusion settings
        int memMode = ConfigManager.getDiffusionMemoryMode(ctx); // 0=low, 1=enough, 2=balance
        int memModeSelection;
        switch (memMode) {
            case 0:
                memModeSelection = 0;
                break;
            case 1:
                memModeSelection = 1;
                break;
            case 2:
                memModeSelection = 2;
                break;
            default:
                memModeSelection = 0;
                break;
        }
        spinnerDiffusionMemoryMode.setSelection(memModeSelection);

        // Load Diffusion precision mode
        int precisionMode = ConfigManager.getDiffusionPrecisionMode(ctx);
        spinnerDiffusionPrecisionMode.setSelection(precisionMode);

        // Load Diffusion GPU memory mode
        int gpuMemoryMode = ConfigManager.getDiffusionGpuMemoryMode(ctx);
        spinnerDiffusionGpuMemoryMode.setSelection(gpuMemoryMode);

        // Get saved width and height, map to preset index
        int savedWidth = ConfigManager.getDiffusionImageWidth(ctx);
        int savedHeight = ConfigManager.getDiffusionImageHeight(ctx);
        int imageSizeSelection = 8; // Default: 512x512 (index 8)
        // Match against presets: (width, height) -> index
        // Sorted by pixel count from small to large
        if (savedWidth == 256 && savedHeight == 256) imageSizeSelection = 0;
        else if (savedWidth == 320 && savedHeight == 320) imageSizeSelection = 1;
        else if (savedWidth == 384 && savedHeight == 384) imageSizeSelection = 2;
        else if (savedWidth == 288 && savedHeight == 512) imageSizeSelection = 3;
        else if (savedWidth == 512 && savedHeight == 288) imageSizeSelection = 4;
        else if (savedWidth == 448 && savedHeight == 448) imageSizeSelection = 5;
        else if (savedWidth == 384 && savedHeight == 640) imageSizeSelection = 6;
        else if (savedWidth == 640 && savedHeight == 384) imageSizeSelection = 7;
        else if (savedWidth == 512 && savedHeight == 512) imageSizeSelection = 8;
        else if (savedWidth == 512 && savedHeight == 768) imageSizeSelection = 9;
        else if (savedWidth == 768 && savedHeight == 512) imageSizeSelection = 10;
        else if (savedWidth == 640 && savedHeight == 640) imageSizeSelection = 11;
        else if (savedWidth == 768 && savedHeight == 768) imageSizeSelection = 12;
        else if (savedWidth == 512 && savedHeight == 896) imageSizeSelection = 13;
        else if (savedWidth == 896 && savedHeight == 512) imageSizeSelection = 14;
        else if (savedWidth == 896 && savedHeight == 896) imageSizeSelection = 15;
        else if (savedWidth == 768 && savedHeight == 1024) imageSizeSelection = 16;
        else if (savedWidth == 1024 && savedHeight == 768) imageSizeSelection = 17;
        else if (savedWidth == 720 && savedHeight == 1280) imageSizeSelection = 18;
        else if (savedWidth == 1280 && savedHeight == 720) imageSizeSelection = 19;
        else if (savedWidth == 1024 && savedHeight == 1024) imageSizeSelection = 20;
        else if (savedWidth == 1152 && savedHeight == 1152) imageSizeSelection = 21;
        else if (savedWidth == 1024 && savedHeight == 1536) imageSizeSelection = 22;
        else if (savedWidth == 1536 && savedHeight == 1024) imageSizeSelection = 23;
        else if (savedWidth == 1280 && savedHeight == 1280) imageSizeSelection = 24;
        else if (savedWidth == 1024 && savedHeight == 1792) imageSizeSelection = 25;
        else if (savedWidth == 1792 && savedHeight == 1024) imageSizeSelection = 26;
        else if (savedWidth == 1536 && savedHeight == 1536) imageSizeSelection = 27;
        else if (savedWidth == 1280 && savedHeight == 1920) imageSizeSelection = 28;
        else if (savedWidth == 1920 && savedHeight == 1280) imageSizeSelection = 29;
        else if (savedWidth == 1440 && savedHeight == 2560) imageSizeSelection = 30;
        else if (savedWidth == 2560 && savedHeight == 1440) imageSizeSelection = 31;
        else if (savedWidth == 2048 && savedHeight == 2048) imageSizeSelection = 32;
        spinnerDiffusionImageSize.setSelection(imageSizeSelection);
        
        int diffSteps = ConfigManager.getDiffusionSteps(ctx);
        int diffStepsProgress = diffSteps - 1;
        seekBarDiffusionSteps.setProgress(diffStepsProgress);
        textViewDiffusionStepsValue.setText(String.valueOf(diffSteps));
        
        // CFG setting (0~10, step 0.25)
        float diffCfg = ConfigManager.getDiffusionCfg(ctx);
        int cfgProgress = Math.round(diffCfg / 0.25f); // cfg 0.0-10.0 -> progress 0-40
        seekBarDiffusionCfg.setProgress(cfgProgress);
        textViewDiffusionCfgValue.setText(String.format(java.util.Locale.US, "%.2f", diffCfg));
        
        // Load random seed switch state from KEY_DIFFUSION_SEED_RANDOM
        boolean isRandom = ConfigManager.getDiffusionSeedRandom(ctx);
        switchDiffusionSeedRandom.setChecked(isRandom);
        
        // Load seed value
        int diffSeed = ConfigManager.getDiffusionSeed(ctx);
        editTextDiffusionSeed.setText(isRandom ? "" : String.valueOf(diffSeed));
        editTextDiffusionSeed.setEnabled(!isRandom);
        editTextDiffusionSeed.setAlpha(isRandom ? 0.5f : 1.0f);
        
        // Text Encoder on CPU setting
        boolean teOnCpu = ConfigManager.getDiffusionTextEncoderOnCPU(ctx);
        switchDiffusionTeOnCpu.setChecked(teOnCpu);
        
        // RAG settings
        int chunkSize = ConfigManager.getChunkSize(ctx);
        int chunkProgress = Math.round((chunkSize - 100) / 100f);
        chunkProgress = Math.max(0, Math.min(chunkProgress, seekBarChunkSize.getMax()));
        seekBarChunkSize.setProgress(chunkProgress);
        updateChunkSizeText(chunkProgress);
        
        int overlapSize = ConfigManager.getOverlapSize(ctx);
        int overlapProgress = Math.round((overlapSize - 20) / 20f);
        overlapProgress = Math.max(0, Math.min(overlapProgress, seekBarOverlapSize.getMax()));
        seekBarOverlapSize.setProgress(overlapProgress);
        updateOverlapSizeText(overlapProgress);
        
        int minChunkSize = ConfigManager.getMinChunkSize(ctx);
        int minChunkProgress = Math.round((minChunkSize - 10) / 10f);
        minChunkProgress = Math.max(0, Math.min(minChunkProgress, seekBarMinChunkSize.getMax()));
        seekBarMinChunkSize.setProgress(minChunkProgress);
        updateMinChunkSizeText(minChunkProgress);
        
        // Embedding concurrency for knowledge base building
        int embeddingConcurrency = ConfigManager.getEmbeddingConcurrency(ctx);
        if (embeddingConcurrency < 1) {
            embeddingConcurrency = 1; // Clamp lower bound
        }
        if (embeddingConcurrency > 4) {
            embeddingConcurrency = 4; // Clamp upper bound
        }
        int embeddingConcurrencyProgress = embeddingConcurrency - 1;
        embeddingConcurrencyProgress = Math.max(0, Math.min(embeddingConcurrencyProgress, seekBarEmbeddingConcurrency.getMax()));
        seekBarEmbeddingConcurrency.setProgress(embeddingConcurrencyProgress);
        updateEmbeddingConcurrencyText(embeddingConcurrencyProgress);

        // Embedding threads per session for knowledge base building
        int embeddingThreads = ConfigManager.getEmbeddingThreads(ctx);
        if (embeddingThreads < 1) {
            embeddingThreads = 1; // Clamp lower bound
        }
        if (embeddingThreads > 4) {
            embeddingThreads = 4; // Clamp upper bound
        }
        int embeddingThreadsProgress = embeddingThreads - 1;
        embeddingThreadsProgress = Math.max(0, Math.min(embeddingThreadsProgress, seekBarEmbeddingThreads.getMax()));
        seekBarEmbeddingThreads.setProgress(embeddingThreadsProgress);
        updateEmbeddingThreadsText(embeddingThreadsProgress);
        
        // Knowledge Graph RAG settings
        int graphMinEdgeWeight = ConfigManager.getGraphMinEdgeWeight(ctx);
        int graphMinEdgeWeightProgress = graphMinEdgeWeight - 1; // 1-10 -> 0-9
        graphMinEdgeWeightProgress = Math.max(0, Math.min(graphMinEdgeWeightProgress, seekBarGraphMinEdgeWeight.getMax()));
        seekBarGraphMinEdgeWeight.setProgress(graphMinEdgeWeightProgress);
        updateGraphMinEdgeWeightText(graphMinEdgeWeightProgress);
        
        int graphMaxExpandEntities = ConfigManager.getGraphMaxExpandEntities(ctx);
        int graphMaxExpandEntitiesProgress = Math.round((graphMaxExpandEntities - 10) / 10f); // 10-200 -> 0-19
        graphMaxExpandEntitiesProgress = Math.max(0, Math.min(graphMaxExpandEntitiesProgress, seekBarGraphMaxExpandEntities.getMax()));
        seekBarGraphMaxExpandEntities.setProgress(graphMaxExpandEntitiesProgress);
        updateGraphMaxExpandEntitiesText(graphMaxExpandEntitiesProgress);
        
        float graphEntityConfidenceThreshold = ConfigManager.getGraphEntityConfidenceThreshold(ctx);
        // Clamp to [0.5, 1.0] to avoid unreasonable values
        if (graphEntityConfidenceThreshold < 0.5f) graphEntityConfidenceThreshold = 0.5f;
        if (graphEntityConfidenceThreshold > 1.0f) graphEntityConfidenceThreshold = 1.0f;
        int graphEntityConfidenceThresholdProgress = Math.round((graphEntityConfidenceThreshold - 0.5f) / 0.05f);
        graphEntityConfidenceThresholdProgress = Math.max(0, Math.min(graphEntityConfidenceThresholdProgress, seekBarGraphEntityConfidenceThreshold.getMax()));
        seekBarGraphEntityConfidenceThreshold.setProgress(graphEntityConfidenceThresholdProgress);
        updateGraphEntityConfidenceThresholdText(graphEntityConfidenceThresholdProgress);

        int graphMaxExpandChunks = ConfigManager.getGraphMaxExpandChunks(ctx);
        int graphMaxExpandChunksProgress = Math.round((graphMaxExpandChunks - 10) / 10f);
        graphMaxExpandChunksProgress = Math.max(0, Math.min(graphMaxExpandChunksProgress, seekBarGraphMaxExpandChunks.getMax()));
        seekBarGraphMaxExpandChunks.setProgress(graphMaxExpandChunksProgress);
        updateGraphMaxExpandChunksText(graphMaxExpandChunksProgress);

        int graphHubThresholdBuild = ConfigManager.getGraphHubThresholdBuild(ctx);
        int graphHubBuildProgress = mapHubThresholdToProgress(graphHubThresholdBuild);
        graphHubBuildProgress = Math.max(0, Math.min(graphHubBuildProgress, seekBarGraphHubThreshold.getMax()));
        seekBarGraphHubThreshold.setProgress(graphHubBuildProgress);
        updateGraphHubThresholdText(graphHubBuildProgress);

        int graphHubThresholdQuery = ConfigManager.getGraphHubThresholdQuery(ctx);
        int graphHubQueryProgress = mapHubThresholdToProgress(graphHubThresholdQuery);
        graphHubQueryProgress = Math.max(0, Math.min(graphHubQueryProgress, seekBarGraphHubThresholdQuery.getMax()));
        seekBarGraphHubThresholdQuery.setProgress(graphHubQueryProgress);
        updateGraphHubThresholdQueryText(graphHubQueryProgress);

        int graphRagVectorExpand = ConfigManager.getGraphRagVectorExpand(ctx);
        int graphRagVectorExpandProgress = mapGraphRagVectorExpandValueToProgress(graphRagVectorExpand);
        graphRagVectorExpandProgress = Math.max(0, Math.min(graphRagVectorExpandProgress, seekBarGraphRagVectorExpand.getMax()));
        seekBarGraphRagVectorExpand.setProgress(graphRagVectorExpandProgress);
        updateGraphRagVectorExpandText(graphRagVectorExpandProgress);

        boolean graphRagEnabled = ConfigManager.isGraphRagEnabled(ctx);
        switchGraphRagMode.setChecked(graphRagEnabled);

        ArrayAdapter<CharSequence> graphWeightAdapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.graph_rag_weight_presets, android.R.layout.simple_spinner_item);
        graphWeightAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGraphRagWeightPreset.setAdapter(graphWeightAdapter);
        int weightPreset = ConfigManager.getGraphRagWeightPreset(ctx);
        if (weightPreset < 0) {
            weightPreset = 0;
        }
        if (weightPreset >= graphWeightAdapter.getCount()) {
            weightPreset = graphWeightAdapter.getCount() - 1;
        }
        spinnerGraphRagWeightPreset.setSelection(weightPreset, false);
        spinnerGraphRagWeightPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view1, int position, long id) {
                ConfigManager.setGraphRagWeightPreset(requireContext(), position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // LLM settings
        int maxSeqLen = ConfigManager.getMaxSequenceLength(ctx);
        int maxSeqProgress = Math.round((maxSeqLen - 512) / 512f);
        maxSeqProgress = Math.max(0, Math.min(maxSeqProgress, seekBarMaxSequenceLength.getMax()));
        seekBarMaxSequenceLength.setProgress(maxSeqProgress);
        updateMaxSequenceLengthText(maxSeqProgress);
        
        int threads = ConfigManager.getThreads(ctx);
        int threadsProgress = threads - 1;
        threadsProgress = Math.max(0, Math.min(threadsProgress, seekBarThreads.getMax()));
        seekBarThreads.setProgress(threadsProgress);
        updateThreadsText(threadsProgress);
        
        int kvCacheSize = ConfigManager.getMaxNewTokens(ctx);
        int kvCacheProgress = Math.round((kvCacheSize - 512) / 512f);
        kvCacheProgress = Math.max(0, Math.min(kvCacheProgress, seekBarKvCacheSize.getMax()));
        seekBarKvCacheSize.setProgress(kvCacheProgress);
        updateKvCacheSizeText(kvCacheProgress);
        
        int historyRounds = ConfigManager.getHistoryRounds(ctx);
        historyRounds = Math.max(0, Math.min(historyRounds, seekBarHistoryRounds.getMax()));
        seekBarHistoryRounds.setProgress(historyRounds);
        updateHistoryRoundsText(historyRounds);
        
        // Manual params
        float manualTemp = ConfigManager.getFloat(ctx, ConfigManager.KEY_MANUAL_TEMPERATURE, 0.7f);
        int manualTempProgress = Math.round(manualTemp * 10f);
        manualTempProgress = Math.max(0, Math.min(manualTempProgress, seekBarManualTemperature.getMax()));
        seekBarManualTemperature.setProgress(manualTempProgress);
        updateManualTemperatureText(manualTempProgress);
        
        float manualTopP = ConfigManager.getFloat(ctx, ConfigManager.KEY_MANUAL_TOP_P, 0.9f);
        int manualTopPProgress = Math.round(manualTopP / 0.05f);
        manualTopPProgress = Math.max(0, Math.min(manualTopPProgress, seekBarManualTopP.getMax()));
        seekBarManualTopP.setProgress(manualTopPProgress);
        updateManualTopPText(manualTopPProgress);
        
        int manualTopK = ConfigManager.getManualTopK(ctx);
        int manualTopKProgress = Math.round((manualTopK - 10) / 10f);
        manualTopKProgress = Math.max(0, Math.min(manualTopKProgress, seekBarManualTopK.getMax()));
        seekBarManualTopK.setProgress(manualTopKProgress);
        updateManualTopKText(manualTopKProgress);
        
        float manualRepeatPenalty = ConfigManager.getFloat(ctx, ConfigManager.KEY_MANUAL_REPEAT_PENALTY, 1.1f);
        int manualRepeatProgress = Math.round(manualRepeatPenalty * 10f);
        manualRepeatProgress = Math.max(0, Math.min(manualRepeatProgress, seekBarManualRepeatPenalty.getMax()));
        seekBarManualRepeatPenalty.setProgress(manualRepeatProgress);
        updateManualRepeatPenaltyText(manualRepeatProgress);
        
        boolean priorityManualParams = ConfigManager.getPriorityManualParams(ctx);
        switchPriorityManualParams.setChecked(priorityManualParams);
        
        // Image preprocess size
        int imgSize = ConfigManager.getImagePreprocessSize(ctx);
        int imgProgress = sizeToProgress(imgSize);
        seekBarImagePreprocessSize.setProgress(imgProgress);
        updateImagePreprocessSizeText(imgProgress);
        
        // ASR/TTS model selection
        String asrModel = ConfigManager.getString(ctx, ConfigManager.KEY_ASR_MODEL, "");
        if (!asrModel.isEmpty() && spinnerAsrModel.getAdapter() != null) {
            for (int i = 0; i < spinnerAsrModel.getAdapter().getCount(); i++) {
                if (asrModel.equals(spinnerAsrModel.getAdapter().getItem(i))) {
                    spinnerAsrModel.setSelection(i);
                    break;
                }
            }
        }
        
        String ttsModel = ConfigManager.getString(ctx, ConfigManager.KEY_TTS_MODEL, "");
        if (!ttsModel.isEmpty() && spinnerTtsModel.getAdapter() != null) {
            for (int i = 0; i < spinnerTtsModel.getAdapter().getCount(); i++) {
                if (ttsModel.equals(spinnerTtsModel.getAdapter().getItem(i))) {
                    spinnerTtsModel.setSelection(i);
                    break;
                }
            }
        }
        
        // TTS settings
        int ttsDitSteps = ConfigManager.getTtsDitSteps(ctx);
        int ttsDitProgress = ttsDitSteps - 1;
        seekBarTtsDitSteps.setProgress(ttsDitProgress);
        updateTtsDitStepsText(ttsDitProgress);
        
        int ttsSpeakerId = ConfigManager.getTtsSpeakerId(ctx);
        spinnerTtsSpeaker.setSelection(ttsSpeakerId);
        
        float ttsSpeed = ConfigManager.getTtsSpeed(ctx);
        int ttsSpeedProgress = (int)(ttsSpeed * 100);
        seekBarTtsSpeed.setProgress(ttsSpeedProgress);
        updateTtsSpeedText(ttsSpeedProgress);
        
        float ttsPitch = ConfigManager.getTtsPitch(ctx);
        int ttsPitchProgress = (int)(ttsPitch * 100);
        seekBarTtsPitch.setProgress(ttsPitchProgress);
        updateTtsPitchText(ttsPitchProgress);
        
        boolean ttsAutoPlay = ConfigManager.getTtsAutoPlay(ctx);
        switchTtsAutoPlay.setChecked(ttsAutoPlay);
        
        // Update TTS settings visibility based on loaded model
        // Reuse ttsModel variable from above (line 1020)
        updateTtsSettingsVisibility(ttsModel);
        
        // Switches
        boolean showDebugPerformance = ConfigManager.getShowDebugPerformance(ctx);
        switchShowDebugPerformance.setChecked(showDebugPerformance);
        
        boolean debugMode = ConfigManager.getDebugMode(ctx);
        switchDebugMode.setChecked(debugMode);
        
        boolean jsonSplitting = ConfigManager.getJsonDatasetSplittingEnabled(ctx);
        switchJsonDatasetSplitting.setChecked(jsonSplitting);
    }
    
    private void saveSettings() {}
    
    /**
     * Update TTS settings visibility based on selected model
     * @param selectedModel Selected TTS model name
     */
    private void updateTtsSettingsVisibility(String selectedModel) {
        // Get localized strings for comparison
        String noneOption = getString(R.string.settings_tts_model_none);
        String systemOption = getString(R.string.settings_tts_model_system);
        String omniOption = getString(R.string.settings_tts_model_native_omni);
        
        // DiT steps: Only show for Omni (bert-vits2 uses VITS architecture, not Diffusion)
        boolean isOmni = omniOption.equals(selectedModel);
        layoutTtsDitSteps.setVisibility(isOmni ? View.VISIBLE : View.GONE);
        
        // Speaker ID: External TTS models and Omni (currently hidden, to be implemented)
        boolean isExternalTts = !noneOption.equals(selectedModel) && 
                               !systemOption.equals(selectedModel) && 
                               !omniOption.equals(selectedModel);
        // TODO: Enable when speaker ID is implemented in mnn-tts and Omni
        layoutTtsSpeaker.setVisibility(View.GONE); // (isExternalTts || isOmni) ? View.VISIBLE : View.GONE
        
        // Speed/Pitch: Only show for System TTS
        boolean isSystemTts = systemOption.equals(selectedModel);
        layoutTtsSpeed.setVisibility(isSystemTts ? View.VISIBLE : View.GONE);
        layoutTtsPitch.setVisibility(isSystemTts ? View.VISIBLE : View.GONE);
    }
    
    private void handleDirectorySelection(Uri uri, EditText targetEditText) {
        if (uri != null && getContext() != null) {
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                
                DocumentFile docFile = DocumentFile.fromTreeUri(requireContext(), uri);
                if (docFile != null && docFile.isDirectory()) {
                    // CRITICAL: Convert content:// URI to traditional file path
                    // content://com.android.externalstorage.documents/tree/primary%3ADownload%2FOfflineAIData
                    // -> /storage/emulated/0/Download/OfflineAIData
                    String path = UriUtils.getPathFromTreeUri(requireContext(), uri);
                    
                    LogManager.logD(TAG, "Directory selected - URI: " + uri.toString());
                    LogManager.logD(TAG, "Directory selected - Converted path: " + path);
                    
                    targetEditText.setText(path);
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_DATA_ROOT_PATH, path);
                    Toast.makeText(requireContext(), "Data root path saved: " + path, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to handle directory selection", e);
                Toast.makeText(requireContext(), "Failed to set directory", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * Initialize graph custom dictionary spinner
     */
    private void initializeGraphCustomDictionarySpinner() {
        LogManager.logD(TAG, "[DICT_SPINNER] === Initializing graph custom dictionary spinner ===");
        
        // Get data root path
        String dataRootPath = ConfigManager.getString(requireContext(), ConfigManager.KEY_DATA_ROOT_PATH, "");
        LogManager.logD(TAG, "[DICT_SPINNER] Data root path: " + dataRootPath);
        
        if (dataRootPath.isEmpty()) {
            LogManager.logW(TAG, "[DICT_SPINNER] Data root path is EMPTY, cannot scan dictionaries");
            String[] noDictOptions = {getString(R.string.common_none)};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
                android.R.layout.simple_spinner_item, noDictOptions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerGraphCustomDictionary.setAdapter(adapter);
            LogManager.logD(TAG, "[DICT_SPINNER] Set adapter with 'None' only (data root empty)");
            return;
        }
        
        // Scan dictionary directory
        File dictDir = new File(dataRootPath, "dictionary");
        LogManager.logD(TAG, "[DICT_SPINNER] Dictionary directory: " + dictDir.getAbsolutePath());
        LogManager.logD(TAG, "[DICT_SPINNER] Directory exists: " + dictDir.exists());
        
        List<String> dictDisplayOptions = new ArrayList<>();
        final List<String> dictPathOptions = new ArrayList<>();
        
        // First option: None
        dictDisplayOptions.add(getString(R.string.common_none));
        dictPathOptions.add("");
        
        if (dictDir.exists() && dictDir.isDirectory()) {
            File[] dictFiles = dictDir.listFiles((dir, name) -> name.endsWith(".json"));
            LogManager.logD(TAG, "[DICT_SPINNER] Found " + (dictFiles != null ? dictFiles.length : 0) + " .json files");
            
            if (dictFiles != null) {
                for (File dictFile : dictFiles) {
                    LogManager.logD(TAG, "[DICT_SPINNER]   - " + dictFile.getAbsolutePath());
                    // Spinner only shows file name to keep UI concise
                    dictDisplayOptions.add(dictFile.getName());
                    dictPathOptions.add(dictFile.getAbsolutePath());
                }
            }
        } else {
            LogManager.logW(TAG, "[DICT_SPINNER] Dictionary directory does NOT exist or is not a directory");
        }
        
        // Set adapter (display only file names / None)
        String[] optionsArray = dictDisplayOptions.toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, optionsArray);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGraphCustomDictionary.setAdapter(adapter);
        
        // Load saved selection
        String savedDict = ConfigManager.getGraphCustomDictionaryPath(requireContext());
        if (!savedDict.isEmpty()) {
            for (int i = 0; i < dictPathOptions.size(); i++) {
                String path = dictPathOptions.get(i);
                if (savedDict.equals(path)) {
                    spinnerGraphCustomDictionary.setSelection(i);
                    LogManager.logD(TAG, "Auto-selected dictionary: " + savedDict + " at index " + i);
                    break;
                }
            }
        }
        
        // Set listener
        spinnerGraphCustomDictionary.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLabel = parent.getItemAtPosition(position).toString();
                String selectedPath = dictPathOptions.get(position);
                if (position == 0 || selectedPath == null || selectedPath.isEmpty()) {
                    ConfigManager.setGraphCustomDictionaryPath(requireContext(), "");
                    LogManager.logD(TAG, "No dictionary selected");
                } else {
                    ConfigManager.setGraphCustomDictionaryPath(requireContext(), selectedPath);
                    LogManager.logD(TAG, "Selected dictionary: " + selectedPath + " (label=" + selectedLabel + ")");
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        LogManager.logD(TAG, "Dictionary spinner initialized with " + (optionsArray.length - 1) + " dictionaries");
    }

    /**
     * Initialize graph stopwords spinner
     */
    private void initializeGraphStopwordsSpinner() {
        LogManager.logD(TAG, "[STOPWORDS_SPINNER] === Initializing graph stopwords spinner ===");

        String stopwordsDirPath = ConfigManager.getStopwordsDirectoryPath(requireContext());
        File stopwordsDir = new File(stopwordsDirPath);
        LogManager.logD(TAG, "[STOPWORDS_SPINNER] Directory: " + stopwordsDir.getAbsolutePath());

        List<String> displayOptions = new ArrayList<>();
        final List<String> pathOptions = new ArrayList<>();

        // First option: None
        displayOptions.add(getString(R.string.common_none));
        pathOptions.add("");

        if (stopwordsDir.exists() && stopwordsDir.isDirectory()) {
            File[] jsonFiles = stopwordsDir.listFiles((dir, name) -> name.endsWith(".json"));
            LogManager.logD(TAG, "[STOPWORDS_SPINNER] Found " + (jsonFiles != null ? jsonFiles.length : 0) + " .json files");

            if (jsonFiles != null) {
                for (File f : jsonFiles) {
                    displayOptions.add(f.getName());
                    pathOptions.add(f.getAbsolutePath());
                }
            }
        } else {
            LogManager.logW(TAG, "[STOPWORDS_SPINNER] Stopwords directory does NOT exist or is not a directory");
        }

        String[] optionsArray = displayOptions.toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, optionsArray);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGraphStopwords.setAdapter(adapter);

        // Load saved selection
        String savedPath = ConfigManager.getGraphStopwordsPath(requireContext());
        if (!savedPath.isEmpty()) {
            for (int i = 0; i < pathOptions.size(); i++) {
                if (savedPath.equals(pathOptions.get(i))) {
                    spinnerGraphStopwords.setSelection(i);
                    LogManager.logD(TAG, "[STOPWORDS_SPINNER] Auto-selected stopwords: " + savedPath + " at index " + i);
                    break;
                }
            }
        }

        spinnerGraphStopwords.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedPath = pathOptions.get(position);
                if (position == 0 || selectedPath == null || selectedPath.isEmpty()) {
                    ConfigManager.setGraphStopwordsPath(requireContext(), "");
                    LogManager.logD(TAG, "[STOPWORDS_SPINNER] No stopwords file selected");
                } else {
                    ConfigManager.setGraphStopwordsPath(requireContext(), selectedPath);
                    LogManager.logD(TAG, "[STOPWORDS_SPINNER] Selected stopwords file: " + selectedPath);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
    
    /**
     * Initialize Agent TTS switch
     */
    private void initializeAgentTtsSwitch() {
        boolean enabled = ConfigManager.isAgentTtsEnabled(requireContext());
        switchAgentTts.setChecked(enabled);
        switchAgentTts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setAgentTtsEnabled(requireContext(), isChecked);
            LogManager.logD(TAG, "[AGENT_TTS] Agent TTS enabled: " + isChecked);
        });
    }

    /**
     * Initialize Agent Max Steps SeekBar
     * Range: 10-200 (step 5), rightmost position = unlimited (stored as 0)
     * SeekBar max=39: progress 0-38 maps to 10-200, progress 39 = unlimited
     */
    private void initializeAgentMaxStepsSeekBar() {
        int savedValue = ConfigManager.getInt(requireContext(), ConfigManager.KEY_AGENT_MAX_STEPS, 50);
        int progress;
        if (savedValue <= 0) {
            progress = 39; // unlimited
        } else {
            progress = Math.max(0, Math.min(38, (savedValue - 10) / 5));
        }
        seekBarAgentMaxSteps.setProgress(progress);
        updateAgentMaxStepsDisplay(progress);
        
        seekBarAgentMaxSteps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                updateAgentMaxStepsDisplay(p);
                if (fromUser) {
                    int value = (p >= 39) ? 0 : (p * 5 + 10);
                    ConfigManager.setInt(requireContext(), ConfigManager.KEY_AGENT_MAX_STEPS, value);
                    LogManager.logD(TAG, "[AGENT_MAX_STEPS] Set to: " + (value == 0 ? "unlimited" : value));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    
    private void updateAgentMaxStepsDisplay(int progress) {
        if (progress >= 39) {
            textViewAgentMaxStepsValue.setText(getString(R.string.agent_max_steps_unlimited));
        } else {
            int value = progress * 5 + 10;
            textViewAgentMaxStepsValue.setText(String.valueOf(value));
        }
    }

    /**
     * Initialize Agent experience summary switch
     */
    private void initializeAgentExperienceSummarySwitch() {
        LogManager.logD(TAG, "[AGENT_EXP_SUMMARY] Initializing agent experience summary switch");
        
        // Load saved setting
        boolean enabled = ConfigManager.isAgentExperienceSummaryEnabled(requireContext());
        switchAgentExperienceSummary.setChecked(enabled);
        LogManager.logD(TAG, "[AGENT_EXP_SUMMARY] Loaded saved setting: " + enabled);
        
        // Set change listener
        switchAgentExperienceSummary.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setAgentExperienceSummaryEnabled(requireContext(), isChecked);
            LogManager.logD(TAG, "[AGENT_EXP_SUMMARY] Experience summary enabled: " + isChecked);
        });
    }
}
