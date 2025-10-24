package com.example.offlineai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import com.example.offlineai.LogManager;
import com.example.offlineai.api.LocalLlmHandler;
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
    private EditText editTextDataRootPath;
    private Button buttonSelectDataRootPath;
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
    private SeekBar seekBarDiffusionSteps;
    private TextView textViewDiffusionStepsValue;
    private EditText editTextDiffusionSeed;
    private SwitchCompat switchDiffusionSeedRandom;
    
    // ASR语音识别设置
    private Spinner spinnerAsrModel;
    
    // TTS语音合成设置
    private Spinner spinnerTtsModel;
    private SeekBar seekBarTtsDitSteps;
    private TextView textViewTtsDitStepsValue;
    
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
        editTextDataRootPath = view.findViewById(R.id.editTextDataRootPath);
        buttonSelectDataRootPath = view.findViewById(R.id.buttonSelectDataRootPath);
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
        seekBarDiffusionSteps = view.findViewById(R.id.seekBarDiffusionSteps);
        textViewDiffusionStepsValue = view.findViewById(R.id.textViewDiffusionStepsValue);
        editTextDiffusionSeed = view.findViewById(R.id.editTextDiffusionSeed);
        switchDiffusionSeedRandom = view.findViewById(R.id.switchDiffusionSeedRandom);
        
        // ASR/TTS设置控件
        spinnerAsrModel = view.findViewById(R.id.spinnerAsrModel);
        spinnerTtsModel = view.findViewById(R.id.spinnerTtsModel);
        seekBarTtsDitSteps = view.findViewById(R.id.seekBarTtsDitSteps);
        textViewTtsDitStepsValue = view.findViewById(R.id.textViewTtsDitStepsValue);
        
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
        
        // 设置ASR/TTS模型Spinner适配器
        setupAsrTtsSpinners();
        
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
            boolean isRandom = isChecked;
            int seed = isRandom ? -1 : Integer.parseInt(editTextDiffusionSeed.getText().toString().trim());
            ConfigManager.setDiffusionSeed(requireContext(), seed);
        });
        
        // 设置所有SeekBar监听器
        setupChunkSizeSeekBar();
        setupOverlapSizeSeekBar();
        setupMinChunkSizeSeekBar();
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
        
        spinnerDiffusionMemoryMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Use ConfigManager API: 0=low, 1=enough, 2=balance/normal
                int mode = (position == 0) ? 0 : 2; // 0=low, 2=balance(normal)
                ConfigManager.setDiffusionMemoryMode(requireContext(), mode);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerAsrModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String model = parent.getItemAtPosition(position).toString();
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
                // Note: TTS model uses setString directly as it's a simple string value
                ConfigManager.setString(requireContext(), ConfigManager.KEY_TTS_MODEL, model);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // 设置Switch监听器
        switchDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setDebugMode(requireContext(), isChecked);
        });
        
        switchJsonDatasetSplitting.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setJsonDatasetSplittingEnabled(requireContext(), isChecked);
        });
        
        switchPriorityManualParams.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setPriorityManualParams(requireContext(), isChecked);
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
        // New preset-based mapping (all multiples of 28 for VL models)
        int size = progressToSize(progress);
        String displayText = (size == 0) ? "MAX" : String.valueOf(size);
        textViewImagePreprocessSizeValue.setText(displayText);
    }
    
    /**
     * Convert SeekBar progress to image size
     * Progress 0-7 maps to presets: 112, 280, 392, 504, 672, 896, 1008, MAX(0)
     */
    private int progressToSize(int progress) {
        switch (progress) {
            case 0: return ConfigManager.IMAGE_SIZE_MIN;       // 112
            case 1: return ConfigManager.IMAGE_SIZE_SMALL;     // 280
            case 2: return ConfigManager.IMAGE_SIZE_MEDIUM;    // 392
            case 3: return ConfigManager.IMAGE_SIZE_DEFAULT;   // 504 (default)
            case 4: return ConfigManager.IMAGE_SIZE_LARGE;     // 672
            case 5: return ConfigManager.IMAGE_SIZE_XLARGE;    // 896
            case 6: return ConfigManager.IMAGE_SIZE_MAX_RESIZE;// 1008
            case 7: return ConfigManager.IMAGE_SIZE_ORIGINAL;  // 0 (MAX)
            default: return ConfigManager.IMAGE_SIZE_DEFAULT;  // 504
        }
    }
    
    /**
     * Convert image size to SeekBar progress
     * CRITICAL: Must check 0 (MAX mode) FIRST, because 0 <= 112 would return wrong progress!
     */
    private int sizeToProgress(int size) {
        // Check MAX mode (0) first - this is the most important fix!
        if (size == ConfigManager.IMAGE_SIZE_ORIGINAL) return 7; // 0 → progress 7 (MAX)
        
        // Then check other sizes in ascending order
        if (size <= ConfigManager.IMAGE_SIZE_MIN) return 0;       // ≤112 → progress 0
        if (size <= ConfigManager.IMAGE_SIZE_SMALL) return 1;     // ≤280 → progress 1
        if (size <= ConfigManager.IMAGE_SIZE_MEDIUM) return 2;    // ≤392 → progress 2
        if (size <= ConfigManager.IMAGE_SIZE_DEFAULT) return 3;   // ≤504 → progress 3
        if (size <= ConfigManager.IMAGE_SIZE_LARGE) return 4;     // ≤672 → progress 4
        if (size <= ConfigManager.IMAGE_SIZE_XLARGE) return 5;    // ≤896 → progress 5
        if (size <= ConfigManager.IMAGE_SIZE_MAX_RESIZE) return 6;// ≤1008 → progress 6
        
        // Any size > 1008 also maps to MAX mode
        return 7; // > 1008 or unknown → progress 7 (MAX)
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
        spinnerDiffusionMemoryMode.setSelection(memMode == 0 ? 0 : 1); // Spinner: 0=low, 1=normal
        
        int diffSteps = ConfigManager.getDiffusionSteps(ctx);
        int diffStepsProgress = diffSteps - 1;
        seekBarDiffusionSteps.setProgress(diffStepsProgress);
        textViewDiffusionStepsValue.setText(String.valueOf(diffSteps));
        
        int diffSeed = ConfigManager.getDiffusionSeed(ctx);
        boolean isRandom = (diffSeed == -1);
        switchDiffusionSeedRandom.setChecked(isRandom);
        editTextDiffusionSeed.setText(isRandom ? "" : String.valueOf(diffSeed));
        editTextDiffusionSeed.setEnabled(!isRandom);
        editTextDiffusionSeed.setAlpha(isRandom ? 0.5f : 1.0f);
        
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
        
        // Switches
        boolean debugMode = ConfigManager.getDebugMode(ctx);
        switchDebugMode.setChecked(debugMode);
        
        boolean jsonSplitting = ConfigManager.getJsonDatasetSplittingEnabled(ctx);
        switchJsonDatasetSplitting.setChecked(jsonSplitting);
    }
    
    private void saveSettings() {}
    
    private void handleDirectorySelection(Uri uri, EditText targetEditText) {
        if (uri != null && getContext() != null) {
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                
                DocumentFile docFile = DocumentFile.fromTreeUri(requireContext(), uri);
                if (docFile != null && docFile.isDirectory()) {
                    String path = uri.toString();
                    targetEditText.setText(path);
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_DATA_ROOT_PATH, path);
                    Toast.makeText(requireContext(), "Data root path saved", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to handle directory selection", e);
                Toast.makeText(requireContext(), "Failed to set directory", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
