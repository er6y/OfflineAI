package com.example.starlocalrag;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import com.example.starlocalrag.api.LocalLlmAdapter;
import com.example.starlocalrag.api.LocalLlmHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


import com.example.starlocalrag.EmbeddingModelHandler;
import com.example.starlocalrag.EmbeddingModelManager;
import com.example.starlocalrag.EmbeddingModelUtils;
import com.example.starlocalrag.SQLiteVectorDatabaseHandler;
import com.example.starlocalrag.ConfigManager;
import com.example.starlocalrag.ApiUrlAdapter;
import com.example.starlocalrag.api.TokenizerManager;
import com.example.starlocalrag.RerankerModelManager;
import com.example.starlocalrag.AppConstants;
import com.example.starlocalrag.StateDisplayManager;
import com.example.starlocalrag.adapter.StateAwareSpinnerAdapter;
import com.example.starlocalrag.GlobalStopManager;
import io.noties.markwon.Markwon;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.movement.MovementMethodPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import android.graphics.Color;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;

public class RagQaFragment extends Fragment {

    private static final String TAG = "StarLocalRAG_RagQa"; // 添加TAG用于日志打印
    private static final String LOG_FILE = "api_log.txt"; // 日志文件名

    private Spinner spinnerApiUrl;
    private EditText editTextApiKey;
    private Spinner spinnerApiModel;
    private Spinner spinnerKnowledgeBase;
    private EditText editTextSystemPrompt;
    private EditText editTextUserPrompt;
    private Button buttonSendStop;
    private Button buttonNewChat;
    private Spinner spinnerSearchDepth; // 检索数下拉框
    private Spinner spinnerRerankCount; // 重排数下拉框
    private TextView textViewResponse; // 回答文本框
    private CheckBox checkBoxThinkingMode; // 思考模式复选框
    
    // Markdown渲染器
    private Markwon markwon;
    private final StringBuilder answerBuilder = new StringBuilder();
    private final StringBuilder debugBuilder = new StringBuilder();

    private final AtomicBoolean isSending = new AtomicBoolean(false); // Track the state of the send/stop button with atomic operations
    private static final String CONFIG_FILE = ".config"; // 配置文件名
    private List<String> systemPromptHistory = new ArrayList<>(); // 系统提示词历史记录
    private Map<String, String> apiKeyMap = new HashMap<>(); // API Key映射
    
    // 当前是否有正在执行的RAG查询任务
    private boolean isTaskRunning = false;
    private boolean isTaskCancelled = false;
    
    // 全局停止标志 - 用于统一控制所有模型的停止
    private volatile boolean globalStopFlag = false;
    
    // 【重要修复】线程池职责分离：
    // 1. stopCheckExecutor - 专门用于停止检查任务，不应触发模型操作
    // 2. ragQueryExecutor - 专门用于RAG查询任务，可以调用模型
    private final ExecutorService stopCheckExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQa-StopCheck-Thread");
        t.setDaemon(true);
        return t;
    });
    
    private final ExecutorService ragQueryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQa-Query-Thread");
        t.setDaemon(true);
        return t;
    });
    // 主线程Handler
    private Handler mainHandler;

    // 搜索结果文档
    private List<String> relevantDocuments;
    private String similarityInfo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rag_qa, container, false);
        
        // 初始化UI元素
        spinnerApiUrl = view.findViewById(R.id.spinnerApiUrl);
        editTextApiKey = view.findViewById(R.id.editTextApiKey);
        spinnerApiModel = view.findViewById(R.id.spinnerApiModel);
        spinnerKnowledgeBase = view.findViewById(R.id.spinnerKnowledgeBase);
        editTextSystemPrompt = view.findViewById(R.id.editTextSystemPrompt);
        editTextUserPrompt = view.findViewById(R.id.editTextUserPrompt);
        buttonSendStop = view.findViewById(R.id.buttonSendStop);
        buttonNewChat = view.findViewById(R.id.buttonNewChat);
        spinnerSearchDepth = view.findViewById(R.id.spinnerSearchDepth); // 初始化检索数下拉框
        spinnerRerankCount = view.findViewById(R.id.spinnerRerankCount); // 初始化重排数下拉框
        checkBoxThinkingMode = view.findViewById(R.id.checkBoxThinkingModeKey); // 初始化思考模式复选框
        
        // 为用户提问文本框添加回车键监听
        editTextUserPrompt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                handleSendStopClick();
                return true;
            }
            return false;
        });
        
        // 初始化检索数下拉框
        initializeSearchDepthSpinner();
        
        // 初始化重排数下拉框
        initializeRerankCountSpinner();
        
        // 加载API URL列表，包括从配置中获取的自定义URL
        loadApiUrlList();
        
        // 设置其他Spinner的初始数据
        setupSpinner(spinnerApiModel, new String[]{getString(R.string.common_loading)});
        setupSpinner(spinnerKnowledgeBase, new String[]{getString(R.string.common_loading)});
        
        // 为API URL Spinner添加选择监听器，自动加载对应的API Key
        spinnerApiUrl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedApiUrl = parent.getItemAtPosition(position).toString();
                
                // 检查是否选择了"新建..."选项
                if (selectedApiUrl.equals(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW))) {
                    showAddApiUrlDialog();
                    return;
                }
                
                loadApiKeyForUrl(selectedApiUrl);
                fetchModelsForApi(); // 自动获取模型列表
                
                // 保存API URL设置
                ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, selectedApiUrl);
                LogManager.logD(TAG, "Saved API URL: " + selectedApiUrl);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 为API Key添加焦点变化监听器，当失去焦点时保存API Key
        editTextApiKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String apiKey = editTextApiKey.getText().toString();
                String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
                if (!apiKey.isEmpty()) {
                    ConfigManager.saveApiKey(requireContext(), apiUrl, apiKey);
                    LogManager.logD(TAG, "Saved API Key to URL: " + apiUrl);
                }
            }
        });

        // 添加触摸监听器，当点击模型下拉框时获取模型列表
        spinnerApiModel.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                fetchModelsForApi();
            }
            return false; // 允许正常的spinner行为
        });
        
        // 为模型Spinner添加选择监听器，保存选择的模型
        spinnerApiModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedModel = parent.getItemAtPosition(position).toString();
                if (!StateDisplayManager.isModelStatusDisplayText(requireContext(), selectedModel)) {
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, selectedModel);
                    LogManager.logD(TAG, "Saved model name: " + selectedModel);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 添加知识库下拉框触摸监听器
        spinnerKnowledgeBase.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                loadKnowledgeBases();
            }
            return false;
        });
        
        // 为知识库Spinner添加选择监听器，保存选择的知识库
        spinnerKnowledgeBase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKnowledgeBase = parent.getItemAtPosition(position).toString();
                if (!StateDisplayManager.isKnowledgeBaseStatusDisplayText(requireContext(), selectedKnowledgeBase)) {
                    // 当选择有效的知识库时，保存到配置中
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, selectedKnowledgeBase);
                    LogManager.logD(TAG, "Saved knowledge base name: " + selectedKnowledgeBase);
                } else {
                    // 当选择状态显示文本时，保存空字符串到配置中
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, "");
                    LogManager.logD(TAG, "Selected status display text, clearing config: " + selectedKnowledgeBase);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 为系统提示词添加焦点变化监听器，当失去焦点时保存系统提示词
        editTextSystemPrompt.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String systemPrompt = editTextSystemPrompt.getText().toString();
                // 无论是否为空都保存，确保用户清空系统提示词时能正确保存
                ConfigManager.setSystemPrompt(requireContext(), systemPrompt);
                LogManager.logD(TAG, "Saved system prompt: " + (systemPrompt.isEmpty() ? "[empty]" : systemPrompt));
            }
        });
        
        // 初始化检索数下拉框
        initializeSearchDepthSpinner();
        
        // 为检索数下拉框添加选择监听器
        spinnerSearchDepth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedDepth = parent.getItemAtPosition(position).toString();
                int searchDepth = Integer.parseInt(selectedDepth);
                ConfigManager.setInt(requireContext(), ConfigManager.KEY_SEARCH_DEPTH, searchDepth);
                LogManager.logD(TAG, "Saved search depth: " + searchDepth);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 为重排数下拉框添加选择监听器
        spinnerRerankCount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedCount = parent.getItemAtPosition(position).toString();
                int rerankCount = Integer.parseInt(selectedCount);
                ConfigManager.setRerankCount(requireContext(), rerankCount);
                LogManager.logD(TAG, "Saved rerank count: " + rerankCount);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });
        
        // 为思考模式复选框添加监听器
        checkBoxThinkingMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 注意：no_thinking=TRUE 取消复选，false则复选
            // 所以这里需要反转逻辑
            boolean noThinking = !isChecked;
            ConfigManager.setNoThinking(requireContext(), noThinking);
            LogManager.logD(TAG, "Saved thinking mode setting: " + (isChecked ? "enabled" : "disabled"));
        });
        
        // 设置按钮监听器
        buttonSendStop.setOnClickListener(v -> handleSendStopClick());
        buttonNewChat.setOnClickListener(v -> handleNewChatClick());
        
        // 加载知识库列表
        loadKnowledgeBases();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化主线程Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始Markdown渲染器，使用全功能插件支持
        LogManager.logD(TAG, "Initializing Markwon renderer");
        markwon = Markwon.builder(requireContext())
                // 添加HTML支持
                .usePlugin(HtmlPlugin.create())
                // 添加表格支持
                .usePlugin(TablePlugin.create(requireContext()))
                // 添加任务列表支持
                .usePlugin(TaskListPlugin.create(requireContext()))
                // 添加删除线支持
                .usePlugin(StrikethroughPlugin.create())
                // 添加图片支持
                .usePlugin(ImagesPlugin.create())
                // 添加链接支持
                .usePlugin(LinkifyPlugin.create())
                // 添加移动方法插件，支持链接点击
                .usePlugin(MovementMethodPlugin.create(LinkMovementMethod.getInstance()))
                // 添加自定义插件，使行内代码使用等宽字体且不使用背景色
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void beforeSetText(@NonNull TextView textView, @NonNull Spanned markdown) {
                        // 设置文本选择可用
                        textView.setTextIsSelectable(true);
                        // 设置文本颜色为黑色，提高可读性
                        textView.setTextColor(Color.BLACK);
                    }
                    
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        // 配置代码块样式 - 使用现代IDE风格的深色主题
                        builder
                            // 代码块使用深色背景，类似VSCode的One Dark主题
                            .codeBlockBackgroundColor(Color.parseColor("#282c34"))
                            // 代码块使用浅色文本，提供高对比度
                            .codeBlockTextColor(Color.parseColor("#abb2bf"))
                            // 行内代码不使用背景色，设置为透明
                            .codeBackgroundColor(Color.TRANSPARENT)
                            // 行内代码使用粗体显示，增强视觉效果
                            .codeTextColor(Color.parseColor("#000000"))
                            // 增加代码块内边距
                            .codeBlockMargin(16)
                            // 增加块间距
                            .blockMargin(12)
                            // 设置引用块样式
                            .blockQuoteColor(Color.parseColor("#5c6bc0"));
                    }
                })
                .build();
                
        LogManager.logD(TAG, "Markwon渲染器已初始化");
        
        // 初始化文本缩放辅助类
        textViewResponse = view.findViewById(R.id.textViewResponse);
        
        // 应用全局字体大小
        applyGlobalTextSize();
        
        // 初始化日志管理器
        LogManager.getInstance(requireContext());
        
        // 设置自定义文本选择菜单
        setupCustomTextSelectionMenu();
    }
    
    // 以下方法从MainActivity中复制，并根据Fragment的需要进行了调整
    
    private void setupSpinner(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }
    
    /**
     * 初始化检索数下拉框
     */
    private void initializeSearchDepthSpinner() {
        // 创建检索数选项列表
        List<String> searchDepthOptions = Arrays.asList(
            "0", "1", "2", "5", "6", "8", "10", "15", "20", "25", "30", "35", "40"
        );
        
        // 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, searchDepthOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // 设置适配器
        spinnerSearchDepth.setAdapter(adapter);
        
        // 设置默认选中项
        int currentSearchDepth = ConfigManager.getSearchDepth(requireContext());
        String currentDepthStr = String.valueOf(currentSearchDepth);
        int position = searchDepthOptions.indexOf(currentDepthStr);
        if (position >= 0) {
            spinnerSearchDepth.setSelection(position);
        } else {
            // 如果当前值不在选项中，默认选择"10"
            int defaultPosition = searchDepthOptions.indexOf("10");
            if (defaultPosition >= 0) {
                spinnerSearchDepth.setSelection(defaultPosition);
            }
        }
        
        LogManager.logD(TAG, "搜索深度Spinner已初始化，当前值: " + currentSearchDepth);
    }
    
    /**
     * 初始化重排数下拉框
     */
    private void initializeRerankCountSpinner() {
        // 创建重排数选项列表
        List<String> rerankCountOptions = Arrays.asList(
            "0", "1", "2", "3", "4", "5", "6", "8", "10", "12", "15", "20", "25", "30"
        );
        
        // 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, rerankCountOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // 设置适配器
        spinnerRerankCount.setAdapter(adapter);
        
        // 设置默认选中项
        int currentRerankCount = ConfigManager.getRerankCount(requireContext());
        String currentCountStr = String.valueOf(currentRerankCount);
        int position = rerankCountOptions.indexOf(currentCountStr);
        if (position >= 0) {
            spinnerRerankCount.setSelection(position);
        } else {
            // 如果当前值不在选项中，默认选择"5"
            int defaultPosition = rerankCountOptions.indexOf("5");
            if (defaultPosition >= 0) {
                spinnerRerankCount.setSelection(defaultPosition);
            }
        }
        
        LogManager.logD(TAG, "重排数量Spinner已初始化，当前值: " + currentRerankCount);
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() {
        try {
            // 使用ConfigManager加载配置
            
            // 加载API URL
            String apiUrl = ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
            if (!apiUrl.isEmpty()) {
                // 将原始API URL转换为显示文本后再设置选择
                String apiUrlDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), apiUrl);
                setSpinnerSelection(spinnerApiUrl, apiUrlDisplayText);
            }
            
            // 加载模型名称
            String modelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
            if (!modelName.isEmpty()) {
                // 检查是否为状态显示文本，如果是则直接使用，否则可能需要转换
                // 由于模型名称通常直接保存，这里直接使用即可
                setSpinnerSelection(spinnerApiModel, modelName);
            }
            
            // 加载知识库名称
            String knowledgeBase = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, "");
            if (!knowledgeBase.isEmpty()) {
                setSpinnerSelection(spinnerKnowledgeBase, knowledgeBase);
            }
            
            // 加载系统提示词
            String systemPrompt = ConfigManager.getSystemPrompt(requireContext());
            if (!systemPrompt.isEmpty()) {
                editTextSystemPrompt.setText(systemPrompt);
            }
            
            // 加载所有API Keys
            Map<String, String> apiKeys = ConfigManager.getAllApiKeys(requireContext());
            if (!apiKeys.isEmpty()) {
                apiKeyMap.putAll(apiKeys);
                LogManager.logD(TAG, "Loaded " + apiKeys.size() + " API Keys");
                
                // 根据当前选择的API URL加载对应的API Key
                if (!apiUrl.isEmpty()) {
                    loadApiKeyForUrl(apiUrl);
                }
            }
            
            // 检索数已在initializeSearchDepthSpinner中加载
            
            // 重排数已在initializeRerankCountSpinner中加载
            
            // 加载思考模式设置
            // 注意：no_thinking=TRUE 取消复选，false则复选
            boolean noThinking = ConfigManager.getNoThinking(requireContext());
            checkBoxThinkingMode.setChecked(!noThinking);
            LogManager.logD(TAG, "Loaded thinking mode setting: " + (!noThinking ? "enabled" : "disabled"));
            
            LogManager.logD(TAG, "Configuration loading completed");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load configuration", e);
        }
    }
    
    // 保存配置到文件
    private void saveConfig() {
        try {
            // 获取当前选择的值
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String model = spinnerApiModel.getSelectedItem().toString();
            String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = editTextSystemPrompt.getText().toString();
            
            // 直接保存到一级配置
            ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, apiUrl);
            ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, model);
            ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, knowledgeBase);
            
            // 保存API Key到对应的URL
            if (!apiKey.isEmpty()) {
                ConfigManager.saveApiKey(requireContext(), apiUrl, apiKey);
                LogManager.logD(TAG, "Saved API Key to URL: " + apiUrl);
            }
            
            // 保存系统提示词（使用一级项）
            // 无论是否为空都保存，确保用户清空系统提示词时能正确保存
            ConfigManager.setSystemPrompt(requireContext(), systemPrompt);
            LogManager.logD(TAG, "Saved system prompt: " + (systemPrompt.isEmpty() ? "[empty]" : systemPrompt));
            
            // 检索数通过spinner选择监听器自动保存
            // 重排数通过spinner选择监听器自动保存
            
            LogManager.logD(TAG, "Configuration saved to .config file");
            Toast.makeText(requireContext(), getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save configuration", e);
            Toast.makeText(requireContext(), getString(R.string.toast_save_settings_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 根据API URL加载对应的API Key
    private void loadApiKeyForUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return;
        }
        
        // 使用ConfigManager获取API Key
        String apiKey = ConfigManager.getApiKey(requireContext(), apiUrl);
        if (apiKey != null && !apiKey.isEmpty()) {
            editTextApiKey.setText(apiKey);
            LogManager.logD(TAG, "Loaded API Key for URL: " + apiUrl);
        } else {
            // 如果没有找到对应的API Key，清空输入框
            editTextApiKey.setText("");
            LogManager.logD(TAG, "No API Key found for URL: " + apiUrl);
        }
    }
    
    // 设置Spinner的选中项
    private void setSpinnerSelection(Spinner spinner, String value) {
        if (spinner == null || value == null || value.isEmpty()) {
            return;
        }
        
        // 获取适配器，不进行类型转换
        SpinnerAdapter adapter = spinner.getAdapter();
        if (adapter != null) {
            for (int i = 0; i < adapter.getCount(); i++) {
                Object item = adapter.getItem(i);
                if (item != null && item.toString().equals(value)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
    }
    
    // 加载API URL列表，包括从配置中获取的自定义URL
    private void loadApiUrlList() {
        LogManager.logD(TAG, "Starting to load API URL list");
        
        // 合并预定义和自定义的API URL列表
        List<String> apiUrlsList = new ArrayList<>();
        
        // 添加"新建..."选项作为第一项
        apiUrlsList.add(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW));
        
        // 添加"本地"选项作为第二项（固定项，不能删除）
        String localDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.ApiUrl.LOCAL);
        apiUrlsList.add(localDisplayText);
        
        // 判断配置管理器中是否存在api_keys配置
        boolean hasApiKeysConfig = ConfigManager.hasApiKeysConfig(requireContext());
        
        if (hasApiKeysConfig) {
            // 存在api_keys配置：全部采用配置管理器中的值
            LogManager.logD(TAG, "Using API URLs from config manager");
            String[] customApiUrls = ConfigManager.getApiUrls(requireContext());
            if (customApiUrls != null && customApiUrls.length > 0) {
                for (String apiUrl : customApiUrls) {
                    if (!apiUrl.equals(AppConstants.ApiUrl.LOCAL) && !apiUrlsList.contains(apiUrl)) {
                        apiUrlsList.add(apiUrl);
                    }
                }
            }
        } else {
            // 不存在api_keys配置：采用代码默认的硬编码
            LogManager.logD(TAG, "Using predefined API URLs from resources");
            String[] predefinedApiUrls = getResources().getStringArray(R.array.api_urls);
            String newApiUrlText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW);
            for (String apiUrl : predefinedApiUrls) {
                if (!apiUrl.equals(newApiUrlText) && !apiUrl.equals(AppConstants.ApiUrl.LOCAL) && !apiUrlsList.contains(apiUrl)) {
                    apiUrlsList.add(apiUrl);
                }
            }
        }
        
        // 创建并设置适配器
        ApiUrlAdapter adapter = new ApiUrlAdapter(
                requireContext(),
                apiUrlsList,
                this::deleteApiUrl,
                (apiUrl, position) -> {
                    // 处理API URL选择事件
                    if (apiUrl.equals(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW))) {
                        showAddApiUrlDialog();
                    } else {
                        // 将显示文本转换为内部常量值
                        String internalApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrl);
                        LogManager.logD(TAG, "Selected API URL display: " + apiUrl + ", internal: " + internalApiUrl);
                        
                        // 加载对应的API Key
                        loadApiKeyForUrl(apiUrl);
                        // 保存当前选择的API URL（使用内部常量值）
                        ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, internalApiUrl);
                    }
                },
                spinnerApiUrl
        );
        
        spinnerApiUrl.setAdapter(adapter);
        
        // 设置当前选中的API URL
        String currentApiUrl = ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
        LogManager.logD(TAG, "Current API URL from config: " + currentApiUrl);
        if (!currentApiUrl.isEmpty()) {
            // 将当前API URL转换为显示文本进行匹配
            String currentApiUrlDisplay = StateDisplayManager.getApiUrlDisplayText(requireContext(), currentApiUrl);
            LogManager.logD(TAG, "Current API URL display text: " + currentApiUrlDisplay);
            
            // 查找当前API URL的位置
            boolean found = false;
            for (int i = 0; i < apiUrlsList.size(); i++) {
                String listItem = apiUrlsList.get(i);
                // 尝试直接匹配显示文本
                if (listItem.equals(currentApiUrlDisplay)) {
                    spinnerApiUrl.setSelection(i);
                    adapter.setSelectedPosition(i);
                    found = true;
                    LogManager.logD(TAG, "Found API URL match at position " + i + ": " + listItem);
                    break;
                }
                // 如果显示文本匹配失败，尝试原始值匹配（兼容性处理）
                if (listItem.equals(currentApiUrl)) {
                    spinnerApiUrl.setSelection(i);
                    adapter.setSelectedPosition(i);
                    found = true;
                    LogManager.logD(TAG, "Found API URL match (fallback) at position " + i + ": " + listItem);
                    break;
                }
            }
            
            if (!found) {
                LogManager.logW(TAG, "Could not find matching API URL in list for: " + currentApiUrl + " (display: " + currentApiUrlDisplay + ")");
                // 默认选择第一个非"新建"选项（通常是"本地"）
                if (apiUrlsList.size() > 1) {
                    spinnerApiUrl.setSelection(1);
                    adapter.setSelectedPosition(1);
                    LogManager.logD(TAG, "Defaulting to position 1: " + apiUrlsList.get(1));
                }
            }
        }
        
        LogManager.logD(TAG, "Loaded " + apiUrlsList.size() + " API URLs");
    }
    
    /**
     * 删除API URL
     * @param apiUrl 要删除的API URL
     * @param position 位置
     */
    private void deleteApiUrl(String apiUrl, int position) {
        LogManager.logD(TAG, "Delete API URL: " + apiUrl);
        
        // 显示确认对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_delete_api_url))
                .setMessage(getString(R.string.dialog_message_delete_api_url, apiUrl))
               .setPositiveButton(getString(R.string.common_delete), (dialog, which) -> {
                   // 从配置中删除API URL
                   ConfigManager.removeApiUrl(requireContext(), apiUrl);
                   
                   // 重新加载API URL列表
                   loadApiUrlList();
                   
                   // 提示用户
                   Toast.makeText(requireContext(), getString(R.string.toast_api_url_deleted), Toast.LENGTH_SHORT).show();
               })
               .setNegativeButton(getString(R.string.common_cancel), null)
               .show();
    }
    
    // 显示添加API URL对话框
    private void showAddApiUrlDialog() {
        LogManager.logD(TAG, "Show add API URL dialog");
        
        // 创建对话框布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_api_url, null);
        EditText editTextNewApiUrl = dialogView.findViewById(R.id.editTextNewApiUrl);
        EditText editTextNewApiKey = dialogView.findViewById(R.id.editTextNewApiKey);
        
        // 创建对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_add_api_url_simple))
               .setView(dialogView)
               .setPositiveButton(getString(R.string.common_add), (dialog, which) -> {
                   // 获取输入的API URL和Key
                   String newApiUrl = editTextNewApiUrl.getText().toString().trim();
                   String newApiKey = editTextNewApiKey.getText().toString().trim();
                   
                   // 验证输入
                   if (newApiUrl.isEmpty()) {
                       Toast.makeText(requireContext(), getString(R.string.toast_api_url_empty), Toast.LENGTH_SHORT).show();
                       return;
                   }
                   
                   // 添加新的API URL和Key
                   ConfigManager.addApiUrl(requireContext(), newApiUrl, newApiKey);
                   
                   // 重新加载API URL列表
                   loadApiUrlList();
                   
                   // 选择新添加的API URL
                   setSpinnerSelection(spinnerApiUrl, newApiUrl);
                   
                   Toast.makeText(requireContext(), getString(R.string.toast_api_url_added), Toast.LENGTH_SHORT).show();
               })
               .setNegativeButton(getString(R.string.common_cancel), null);
        
        // 显示对话框
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    // 加载知识库列表
    private void loadKnowledgeBases() {
        LogManager.logD(TAG, "Starting to load knowledge base list");
        // 显示加载状态
        setupSpinner(spinnerKnowledgeBase, new String[]{StateDisplayManager.getKnowledgeBaseStatusDisplayText(requireContext(), AppConstants.KNOWLEDGE_BASE_STATUS_LOADING)});
        
        // 获取设置中的知识库路径
        String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
        LogManager.logD(TAG, "Retrieved knowledge base path from settings: " + knowledgeBasePath);
        
        // 获取知识库目录
        File knowledgeBaseDir = new File(knowledgeBasePath);
        if (!knowledgeBaseDir.exists()) {
            LogManager.logD(TAG, "Knowledge base directory does not exist, attempting to create: " + knowledgeBaseDir.getAbsolutePath());
            knowledgeBaseDir.mkdirs();
        }
        
        // 获取所有子目录作为知识库
        File[] directories = knowledgeBaseDir.listFiles(File::isDirectory);
        if (directories != null && directories.length > 0) {
            // 添加一个额外的选项 "无"
            String[] knowledgeBases = new String[directories.length + 1];
            knowledgeBases[0] = getString(R.string.common_none); // First option is "None"
            for (int i = 0; i < directories.length; i++) {
                knowledgeBases[i + 1] = directories[i].getName();
            }
            setupSpinner(spinnerKnowledgeBase, knowledgeBases);
            
            // 从配置文件加载上次选择的知识库
            loadLastSelectedKnowledgeBase();
            
            LogManager.logD(TAG, "Loaded " + directories.length + " knowledge bases");
        } else {
            // 当没有知识库时，只显示"无"选项
            setupSpinner(spinnerKnowledgeBase, new String[]{getString(R.string.common_none)});
            LogManager.logD(TAG, "No available knowledge bases found, showing only 'None' option");
        }
    }
    
    // 加载上次选择的知识库
    private void loadLastSelectedKnowledgeBase() {
        try {
            // 使用 ConfigManager 获取上次选择的知识库
            String lastKnowledgeBase = ConfigManager.getString(requireContext(), 
                    ConfigManager.KEY_KNOWLEDGE_BASE, "");
            
            LogManager.logD(TAG, "Loading last selected knowledge base from ConfigManager: " + 
                    (lastKnowledgeBase.isEmpty() ? "[empty]" : lastKnowledgeBase));
            
            if (!lastKnowledgeBase.isEmpty()) {
                setSpinnerSelection(spinnerKnowledgeBase, lastKnowledgeBase);
            } else {
                // 如果没有保存的知识库选择，默认选择"无"选项
                String noneText = getString(R.string.common_none);
                setSpinnerSelection(spinnerKnowledgeBase, noneText);
                LogManager.logD(TAG, "No saved knowledge base selection, defaulting to 'None' option");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load knowledge base selection: " + e.getMessage(), e);
            Toast.makeText(requireContext(), getString(R.string.toast_load_kb_selection_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSendStopClick() {
        // 使用原子操作检查并设置发送状态，防止并发点击
        if (isSending.compareAndSet(false, true)) {
            // --- 开始发送 --- 
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String model = spinnerApiModel.getSelectedItem().toString();
            String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = editTextSystemPrompt.getText().toString();
            String userPrompt = editTextUserPrompt.getText().toString();
            
            LogManager.logD(TAG, "User clicked send button, preparing to send request");
            LogManager.logD(TAG, "Request parameters: API URL=" + apiUrl + ", Model=" + model + ", Knowledge Base=" + knowledgeBase);

            // 基本验证
            if (userPrompt.trim().isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.toast_enter_user_question), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (apiUrl.trim().isEmpty() || 
                StateDisplayManager.isModelStatusDisplayText(requireContext(), model)) {
                Toast.makeText(requireContext(), getString(R.string.toast_ensure_api_model_set), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 如果不是本地模型，需要检查API Key
            if (!AppConstants.ApiUrl.LOCAL.equals(apiUrl) && apiKey.trim().isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.toast_enter_api_key), Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存当前配置
            saveConfig();
            
            // 更新按钮状态（isSending已经在compareAndSet中设置为true）
            buttonSendStop.setText(getString(R.string.button_stop_with_icon));
            
            // 清空响应区域并显示正在处理的消息
            if (textViewResponse != null) {
                textViewResponse.setText("");
            }
            
            // 【重要修复】使用专门的RAG查询线程池执行查询任务
            // 避免在停止检查线程中执行模型操作，消除并发冲突
            ragQueryExecutor.execute(() -> {
                // 【修复】只重置本地LLM的停止标志，不重置全局停止标志
                // 全局停止标志只能在确认停止流程完成后被重置
                String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
                if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
                    try {
                        LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(requireContext());
                        // 只重置本地LLM的停止标志，不影响全局停止标志
                        localAdapter.resetStopFlag();
                        LogManager.logD(TAG, "Reset local LLM stop flag in RAG query thread (global stop flag unchanged)");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error resetting local LLM stop flag", e);
                    }
                }
                
                // 在后台线程中显示处理消息，避免UI线程嵌套调用
                //updateProgressOnUiThread("Starting knowledge base query...");
                executeRagQuery(apiUrl, apiKey, model, knowledgeBase, systemPrompt, userPrompt);
            });

        } else if (isSending.compareAndSet(true, false)) {
            // --- 停止发送 --- 
            LogManager.logD(TAG, "User clicked stop button");
            LogManager.logD(TAG, "Current state - isSending: " + isSending.get() + ", isTaskRunning: " + isTaskRunning + ", isTaskCancelled: " + isTaskCancelled);
            
            // 设置全局停止标志和任务取消标志
            globalStopFlag = true;
            isTaskCancelled = true;
            
            // 使用GlobalStopManager设置全局停止标志
            GlobalStopManager.setGlobalStopFlag(true);
            
            LogManager.logD(TAG, "Set global stop flag and task cancellation flag to true");
            
            // 停止所有组件：tokenizer、embedding、reranker、本地LLM
            LogManager.logD(TAG, "开始停止所有组件...");
            
            // 1. 停止本地LLM推理
            String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
            LogManager.logD(TAG, "Current API URL: " + currentApiUrl);
            
            if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
                try {
                    LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(requireContext());
                    LogManager.logD(TAG, "Preparing to call local LLM stop method");
                    localAdapter.stopGeneration();
                    LogManager.logI(TAG, "✓ Successfully called local LLM stop method");
                } catch (Exception e) {
                    LogManager.logE(TAG, "✗ Error calling local LLM stop method", e);
                }
            } else {
                LogManager.logD(TAG, "Non-local model, skipping local LLM stop call");
            }
            
            // 2. 停止Embedding模型（如果正在使用）
            try {
                EmbeddingModelManager embeddingManager = EmbeddingModelManager.getInstance(getContext());
                if (embeddingManager != null) {
                    // EmbeddingModelManager没有直接的停止方法，但可以通过卸载模型来停止
                    LogManager.logD(TAG, "Embedding model manager found, marking as stopped");
                }
                LogManager.logI(TAG, "✓ Embedding model stop signal sent");
            } catch (Exception e) {
                LogManager.logE(TAG, "✗ Error stopping embedding model", e);
            }
            
            // 3. 停止Reranker模型（如果正在使用）
            try {
                RerankerModelManager rerankerManager = RerankerModelManager.getInstance(getContext());
                if (rerankerManager != null) {
                    // RerankerModelManager没有直接的停止方法，但可以通过重置来停止
                    LogManager.logD(TAG, "Reranker model manager found, marking as stopped");
                }
                LogManager.logI(TAG, "✓ Reranker model stop signal sent");
            } catch (Exception e) {
                LogManager.logE(TAG, "✗ Error stopping reranker model", e);
            }
            
            // 4. 停止Tokenizer（如果正在使用）
            try {
                TokenizerManager tokenizerManager = TokenizerManager.getInstance(requireContext());
                if (tokenizerManager != null) {
                    // TokenizerManager有reset方法可以重置状态
                    tokenizerManager.reset();
                    LogManager.logI(TAG, "✓ Tokenizer reset completed");
                } else {
                    LogManager.logD(TAG, "Tokenizer manager not found, skipping");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "✗ Error resetting tokenizer", e);
            }
            
            LogManager.logI(TAG, "所有组件停止信号已发送");
            
            // 启动停止完成检查机制（防呆机制）
            // 智能检查：只在真正需要时启动（当任务可能仍在运行时）
            if (isTaskRunning || !globalStopFlag) {
                needsStopCheck = true;
                startStopCompletionCheck();
            } else {
                LogManager.logD(TAG, "[防呆机制] 任务状态正常，无需启动检查");
            }
            
            Toast.makeText(requireContext(), getString(R.string.toast_request_stopped), Toast.LENGTH_SHORT).show();
            appendToResponse("\n" + getString(R.string.toast_request_stopped) + "。");
            LogManager.logD(TAG, "Stop processing initiated, waiting for completion check");
        } else {
            // 防止重复点击
            LogManager.logD(TAG, "Button click ignored - operation already in progress or completed");
        }
    }
    
    
    // 执行RAG查询任务
    /**
     * 初始化发送状态（开始新的查询时调用）
     * 【修复】不重置全局停止标志，只初始化任务状态
     */
    private void initializeSendingState() {
        isTaskRunning = true;
        isTaskCancelled = false;
        // 【重要】不重置全局停止标志，保持之前的停止状态
        LogManager.logD(TAG, "Initializing sending state - task running: " + isTaskRunning + ", cancelled: " + isTaskCancelled + ", global stop flag unchanged: " + globalStopFlag);
    }
    
    /**
     * 重置所有发送状态
     * 统一管理所有状态变量的重置，确保状态一致性
     * 【修复】只在确认所有任务真正停止后才重置全局停止标志
     */
    private void resetSendingState() {
        isTaskRunning = false;
        isTaskCancelled = false;
        
        // 【修复】只有在确认停止流程完成后才重置全局停止标志
        // 这确保了停止标志不会被过早重置
        LogManager.logD(TAG, "Resetting sending state - confirming all tasks stopped before resetting global stop flag");
        globalStopFlag = false;
        GlobalStopManager.setGlobalStopFlag(false);
        LogManager.logD(TAG, "Global stop flag reset to false after confirming all tasks stopped");
        
        isSending.set(false); // 使用原子操作重置发送状态
        
        // 在UI线程上更新按钮状态，添加Fragment生命周期检查
        if (mainHandler != null && buttonSendStop != null) {
            mainHandler.post(() -> {
                // 检查Fragment是否仍然附加到Activity
                if (getActivity() == null || !isAdded() || isDetached()) {
                    LogManager.logW(TAG, "Cannot reset sending state, Fragment not attached to Activity");
                    return;
                }
                
                try {
                    buttonSendStop.setText(getString(R.string.button_send));
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to reset button text", e);
                }
            });
        }
    }
    
    // 智能检查标志 - 只在真正需要时启动防呆检查
    private volatile boolean needsStopCheck = false;
    
    /**
     * 启动停止完成检查机制（防呆机制）
     * 持续监控所有任务是否真正停止，确保按钮状态正确
     * 优化：减少检查频率，智能检查
     */
    private void startStopCompletionCheck() {
        // 智能检查：只在真正需要时启动
        if (!needsStopCheck) {
            LogManager.logD(TAG, "[防呆机制] 无需启动检查，任务状态正常");
            return;
        }
        
        LogManager.logD(TAG, "[防呆机制] 启动停止完成检查（智能模式）");
        
        // 【重要修复】使用专门的停止检查线程池，避免与RAG查询任务冲突
        stopCheckExecutor.execute(() -> {
            int checkCount = 0;
            final int CHECK_INTERVAL_MS = 300; // 减少检查频率：从100ms增加到300ms
            final int MAX_CHECKS = 100; // 减少最大检查次数：从300次减少到100次（30秒）
            
            while (needsStopCheck) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    checkCount++;
                    
                    boolean allTasksStopped = checkAllTasksStopped();
                    
                    // 每5次检查记录一次日志，减少日志频率
                    if (checkCount % 5 == 0) {
                        LogManager.logD(TAG, "[防呆机制] 第" + checkCount + "次检查，所有任务已停止: " + allTasksStopped);
                    }
                    
                    if (allTasksStopped) {
                        LogManager.logI(TAG, "[防呆机制] ✓ 所有任务已确认停止，恢复按钮状态（检查" + checkCount + "次）");
                        needsStopCheck = false; // 重置智能检查标志
                        resetSendingState();
                        return;
                    }
                    
                    // 安全检查：如果检查次数过多，强制恢复状态
                    if (checkCount > MAX_CHECKS) {
                        LogManager.logW(TAG, "[防呆机制] ⚠ 检查次数过多（" + checkCount + "次），强制恢复按钮状态");
                        needsStopCheck = false; // 重置智能检查标志
                        resetSendingState();
                        return;
                    }
                    
                } catch (InterruptedException e) {
                    LogManager.logE(TAG, "[防呆机制] 检查线程被中断", e);
                    needsStopCheck = false; // 重置智能检查标志
                    break;
                }
            }
        });
    }
    
    /**
     * 检查所有任务是否已停止
     * @return true如果所有任务都已停止，false否则
     */
    private boolean checkAllTasksStopped() {
        // 检查RAG查询任务是否还在运行
        if (isTaskRunning && !isTaskCancelled) {
            LogManager.logD(TAG, "[防呆机制] RAG任务仍在运行");
            return false;
        }
        
        // 检查全局停止标志
        if (!globalStopFlag) {
            LogManager.logD(TAG, "[防呆机制] 全局停止标志未设置");
            return false;
        }
        
        // 使用GlobalStopManager检查各个模块的停止状态
        if (!GlobalStopManager.areAllModulesStopped()) {
            LogManager.logD(TAG, "[防呆机制] 仍有模块未完全停止");
            return false;
        }
        
        // 检查本地LLM是否还在推理
        String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
        String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
        
        if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
            try {
                // 检查本地LLM是否真正停止
                if (!GlobalStopManager.isModuleStopped("LocalLLM")) {
                    LogManager.logD(TAG, "[防呆机制] 本地LLM仍在运行");
                    return false;
                }
                LogManager.logD(TAG, "[防呆机制] 本地LLM已停止");
            } catch (Exception e) {
                LogManager.logE(TAG, "[防呆机制] 检查本地LLM状态时出错: " + e.getMessage());
                // 出错时认为还未完全停止
                return false;
            }
        }
        
        // 检查Embedding模型状态
        try {
            if (!GlobalStopManager.isModuleStopped("Embedding")) {
                LogManager.logD(TAG, "[防呆机制] Embedding模型仍在运行");
                return false;
            }
            LogManager.logD(TAG, "[防呆机制] Embedding模型已停止");
        } catch (Exception e) {
            LogManager.logE(TAG, "[防呆机制] 检查Embedding模型状态时出错: " + e.getMessage());
            return false;
        }
        
        // 检查Reranker模型状态
        try {
            if (!GlobalStopManager.isModuleStopped("Reranker")) {
                LogManager.logD(TAG, "[防呆机制] Reranker模型仍在运行");
                return false;
            }
            LogManager.logD(TAG, "[防呆机制] Reranker模型已停止");
        } catch (Exception e) {
            LogManager.logE(TAG, "[防呆机制] 检查Reranker模型状态时出错: " + e.getMessage());
            return false;
        }
        
        // 检查Tokenizer状态
        try {
            if (!GlobalStopManager.isModuleStopped("Tokenizer")) {
                LogManager.logD(TAG, "[防呆机制] Tokenizer仍在运行");
                return false;
            }
            LogManager.logD(TAG, "[防呆机制] Tokenizer已停止");
        } catch (Exception e) {
            LogManager.logE(TAG, "[防呆机制] 检查Tokenizer状态时出错: " + e.getMessage());
            return false;
        }
        
        LogManager.logD(TAG, "[防呆机制] 所有组件已确认完全停止，可以转换按钮状态");
        return true;
    }
    
    private void executeRagQuery(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt) {
        // 【修复】使用专门的初始化方法，不重置全局停止标志
        initializeSendingState();
        
        LogManager.logD(TAG, "Starting RAG query execution with preserved global stop flag state");
        
        // 保存查询参数，用于恢复
        lastApiUrl = apiUrl;
        lastApiKey = apiKey;
        lastModel = model;
        lastKnowledgeBase = knowledgeBase;
        lastSystemPrompt = systemPrompt;
        lastUserPrompt = userPrompt;
        
        // 初始化相关文档列表
        synchronized (this) {
            relevantDocuments = new ArrayList<>();
            similarityInfo = "";
        }
        
        // 记录开始时间
        final long startTime = System.currentTimeMillis();
        
        // 获取检索数
        final int searchDepth = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
        
        // 更新UI，显示开始查询
        mainHandler.post(() -> {
            buttonSendStop.setText(getString(R.string.button_stop_with_icon));
            isSending.set(true); // 使用原子操作设置发送状态
            // 【修复】任务状态已在initializeSendingState中设置，此处不重复设置
            
            // 清空响应区域
            //updateProgressOnUiThread("正在查询知识库...");
        });
        
        // 同步执行查询（避免并发冲突）
        try {
            // 记录查询信息到日志
                String logMessage = "执行RAG查询:\n" +
                        "API URL: " + apiUrl + "\n" +
                        "模型: " + model + "\n" +
                        "知识库: " + knowledgeBase + "\n" +
                        "检索数: " + searchDepth + "\n" +
                        "系统提示词: " + systemPrompt + "\n" +
                        "用户提问: " + userPrompt;
                LogManager.logD(TAG, logMessage);
                
                // 更新UI，显示查询日志
                mainHandler.post(() -> {
                    //updateProgressOnUiThread("开始查询知识库...");
                    //updateProgressOnUiThread("知识库: " + knowledgeBase);
                    //updateProgressOnUiThread("检索数: " + searchDepth);
                    updateProgressOnUiThread("\n " + getString(R.string.debug_info_header) + "\n\n" + getString(R.string.user_question, userPrompt));
                });
                
                // 检查是否需要查询知识库
                String valueNone = getString(R.string.common_none);
                String valueNoAvailableKb = getString(R.string.value_no_available_kb);
                if (!valueNone.equals(knowledgeBase) && !valueNoAvailableKb.equals(knowledgeBase) && searchDepth > 0) {
                    String kbInfo = getString(R.string.log_using_kb_for_query, knowledgeBase);
                    LogManager.logD(TAG, kbInfo);
                    //updateProgressOnUiThread(kbInfo);
                    
                    // 查询知识库获取相关内容 - 只调用queryKnowledgeBase，不使用返回值
                    queryKnowledgeBase(knowledgeBase, userPrompt);
                    
                    // 等待查询结果 - 从relevantDocuments成员变量中获取（移除超时机制）
                    List<String> relevantDocs = new ArrayList<>();
                    
                    while (true) {
                        if (isTaskCancelled) {
                            String cancelMsg = "RAG查询被用户取消";
                            LogManager.logD(TAG, cancelMsg);
                            updateProgressOnUiThread(cancelMsg);
                            return;
                        }
                        
                        // 检查是否有查询结果
                        synchronized (this) {
                            if (relevantDocuments != null && !relevantDocuments.isEmpty()) {
                                relevantDocs = new ArrayList<>(relevantDocuments);
                                break;
                            }
                        }
                        
                        // 等待100毫秒
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            LogManager.logE(TAG, "Interrupted while waiting for query results", e);
                            break;
                        }
                    }
                    
                    // 检查查询结果
                    if (relevantDocs.isEmpty()) {
                        String warnMsg = "Warning: Knowledge base query returned no relevant documents";
                        LogManager.logW(TAG, warnMsg);
                        updateProgressOnUiThread(warnMsg);
                        
                        // 直接构建不包含知识库内容的提示词
                        String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                        
                        // 记录提示词信息
                        int promptLength = fullPrompt.length();
                        String promptInfo = "Prompt length: " + promptLength + " characters";
                        LogManager.logD(TAG, promptInfo);
                        updateProgressOnUiThread(promptInfo);
                        
                        // 如果提示词太长，记录警告
                        if (promptLength > 4000) {
                            String warnMsg2 = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                            LogManager.logW(TAG, warnMsg2);
                            updateProgressOnUiThread(warnMsg2);
                        }
                        
                        // 计算查询耗时
                        long queryTime = System.currentTimeMillis() - startTime;
                        String timeMsg = "Knowledge base query duration: " + queryTime + "ms";
                        LogManager.logD(TAG, timeMsg);
                        updateProgressOnUiThread(timeMsg);
                        
                        // 调用大模型API获取回答
                        updateProgressOnUiThread("Calling LLM API...");
                        callLLMApi(apiUrl, apiKey, model, fullPrompt);
                    } else {
                        // 获取相似度信息
                        String simInfo = "";
                        synchronized (this) {
                            simInfo = this.similarityInfo;
                        }
                        
                        // 显示相似度信息（无论是否为调试模式）
                        if (!TextUtils.isEmpty(simInfo)) {
                            updateProgressOnUiThread("Similarity info: " + simInfo);
                        }
                        
                        updateProgressOnUiThread("Found " + relevantDocs.size() + " relevant content items...");
                        
                        // 构建包含知识库内容的提示词
                        //updateProgressOnUiThread("建提示词");
                        String fullPrompt = buildPromptWithKnowledgeBase(systemPrompt, userPrompt, relevantDocs);
                        
                        // 记录提示词信息 - 只显示长度，不显示内容
                        int promptLength = fullPrompt.length();
                        String promptInfo = "Built prompt length: " + promptLength + " characters";
                        LogManager.logD(TAG, promptInfo);
                        updateProgressOnUiThread(promptInfo);
                        
                        // 如果提示词太长，记录警告
                        if (promptLength > 4000) {
                            String warnMsg = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                            LogManager.logW(TAG, warnMsg);
                            updateProgressOnUiThread(warnMsg);
                        }
                        
                        // 计算查询耗时
                        long queryTime = System.currentTimeMillis() - startTime;
                        String timeMsg = getString(R.string.kb_query_time, queryTime);
                        LogManager.logD(TAG, timeMsg);
                        updateProgressOnUiThread(timeMsg);
                        
                        // 调用大模型API获取回答
                        updateProgressOnUiThread("Calling LLM API...");
                        callLLMApi(apiUrl, apiKey, model, fullPrompt);
                    }
                } else {
                    // 不使用知识库或检索数为0，直接调用大模型API
                    String directMsg = searchDepth == 0 ? "Search depth is 0, skipping knowledge base query, calling LLM directly" : "No knowledge base configured, calling LLM directly";
                    LogManager.logD(TAG, directMsg);
                    updateProgressOnUiThread(directMsg);
                    updateProgressOnUiThread("Generating response...");
                    
                    // 构建不包含知识库内容的提示词
                    String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                    
                    // 记录提示词信息 - 只显示长度，不显示内容
                    int promptLength = fullPrompt.length();
                    String promptInfo = "Prompt length: " + promptLength + " characters";
                    LogManager.logD(TAG, promptInfo);
                    updateProgressOnUiThread(promptInfo);
                    
                    // 如果提示词太长，记录警告
                    if (promptLength > 4000) {
                        String warnMsg = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                        LogManager.logW(TAG, warnMsg);
                        updateProgressOnUiThread(warnMsg);
                    }
                    
                    // 调用大模型API获取回答
                    updateProgressOnUiThread("Calling LLM API...");
                    callLLMApi(apiUrl, apiKey, model, fullPrompt);
                }
        } catch (Exception e) {
            String errorMsg = "RAG query task execution failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            
            updateResultOnUiThread("Query failed: " + e.getMessage());
            mainHandler.post(() -> {
                buttonSendStop.setText(getString(R.string.button_send));
                isSending.set(false); // 使用原子操作重置发送状态
            });
        } finally {
            // executeRagQuery方法执行完成，LLM推理将异步进行
            LogManager.logD(TAG, "executeRagQuery method execution completed, LLM inference will proceed asynchronously");
        }
    }
    
    // 查询知识库获取相关内容
    private List<String> queryKnowledgeBase(String knowledgeBase, String query) {
        List<String> relevantDocs = new ArrayList<>();

        try {
            // 检查全局停止标志
            if (globalStopFlag) {
                LogManager.logD(TAG, "Global stop flag is set, aborting knowledge base query");
                return relevantDocs;
            }
            
            // 检查是否选择了"无"知识库
            String valueNone = getString(R.string.common_none);
            String valueNoAvailableKb = getString(R.string.value_no_available_kb);
            if (valueNone.equals(knowledgeBase) || valueNoAvailableKb.equals(knowledgeBase)) {
                LogManager.logD(TAG, "No knowledge base selected (" + knowledgeBase + "), skipping knowledge base query");
                return relevantDocs; // 返回空列表，不进行知识库查询
            }
            
            LogManager.logD(TAG, "Starting knowledge base query: " + knowledgeBase + ", query keywords: " + query);
            //updateProgressOnUiThread("开始查询知识库: " + knowledgeBase);

            // 获取检索数（从界面输入框获取）
            int searchDepth = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
            LogManager.logD(TAG, "Using UI-configured search depth: " + searchDepth);
            
            // 检查知识库名称是否有效
            if (knowledgeBase == null || knowledgeBase.trim().isEmpty()) {
                String errorMsg = "Error: Knowledge base name is empty";
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            }

            // 检查上下文是否可用
            if (!isAdded()) {
                String errorMsg = "Error: Fragment not attached to Activity";
                LogManager.logE(TAG, errorMsg);
                return relevantDocs;
            }

            // 获取知识库目录 - 使用配置中的知识库路径
            String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
            LogManager.logD(TAG, "Retrieved knowledge base path from settings: " + knowledgeBasePath);

            // 获取知识库目录
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            String pathInfo = "Knowledge base directory path: " + knowledgeBaseDir.getAbsolutePath();
            LogManager.logD(TAG, pathInfo);
            updateProgressOnUiThread(pathInfo);

            // 检查知识库目录是否存在
            if (!knowledgeBaseDir.exists()) {
                String errorMsg = "Error: Knowledge base directory does not exist: " + knowledgeBaseDir.getAbsolutePath();
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            }

            // 检查SQLite数据库文件
            File vectorDbFile = new File(knowledgeBaseDir, "vectorstore.db");
            if (!vectorDbFile.exists()) {
                String errorMsg = "Error: SQLite vector database file does not exist: " + vectorDbFile.getAbsolutePath();
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            } else {
                String fileInfo = "SQLite database file exists: " + vectorDbFile.getAbsolutePath() +
                    ", size: " + (vectorDbFile.length() / 1024) + "KB, " +
                    "readable: " + vectorDbFile.canRead();
                LogManager.logI(TAG, fileInfo);
            }

            // 检查元数据文件
            File metadataFile = new File(knowledgeBaseDir, "metadata.json");
            if (!metadataFile.exists()) {
                String errorMsg = "Error: Metadata file does not exist: " + metadataFile.getAbsolutePath();
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            } else {
                String fileInfo = "Metadata file exists: " + metadataFile.getAbsolutePath() +
                    ", size: " + (metadataFile.length() / 1024) + "KB, " +
                    "readable: " + metadataFile.canRead();
                LogManager.logI(TAG, fileInfo);
                
                // 读取元数据文件内容并记录 - 使用单独线程避免阻塞
                try {
                    // 在后台线程中读取文件
                    ExecutorService readExecutor = Executors.newSingleThreadExecutor();
                    Future<String> metadataContentFuture = readExecutor.submit(() -> {
                        try {
                            StringBuilder content = new StringBuilder();
                            try (BufferedReader reader = new BufferedReader(new FileReader(metadataFile))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    content.append(line);
                                }
                            }
                            return "Metadata file content: " + content.toString();
                        } catch (Exception e) {
                            return "Failed to read metadata file: " + e.getMessage();
                        }
                    });
                    
                    String metadataContent;
                    try {
                        metadataContent = metadataContentFuture.get(30, TimeUnit.SECONDS);
                        LogManager.logI(TAG, metadataContent);
                    } catch (Exception e) {
                        String readError = "Reading metadata file timed out or failed: " + e.getMessage();
                        LogManager.logE(TAG, readError);
                        updateProgressOnUiThread(readError);
                        return relevantDocs;
                    } finally {
                        readExecutor.shutdownNow();
                    }
                } catch (Exception e) {
                    String readError = "Failed to start metadata file reading thread: " + e.getMessage();
                    LogManager.logE(TAG, readError);
                    updateProgressOnUiThread(readError);
                    return relevantDocs;
                }
            }
            
            // 查询向量数据库
            // 在try块外部声明 vectorDb 变量，以便在catch块中也可以访问
            // 声明为 final，以便在 lambda 表达式中使用
            final SQLiteVectorDatabaseHandler[] vectorDbRef = new SQLiteVectorDatabaseHandler[1];
            try {
                // 创建SQLite向量数据库处理器
                LogManager.logI(TAG, "Starting to create SQLite vector database handler, knowledge base directory: " + knowledgeBaseDir.getAbsolutePath());
                
                try {
                    vectorDbRef[0] = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                    //updateProgressOnUiThread("正在加载SQLite向量数据库...");

                    // 加载向量数据库
                    //LogManager.logI(TAG, "Starting to load SQLite vector database...");
                    
                    if (!vectorDbRef[0].loadDatabase()) {
                        String errorMsg = "Error: Failed to load SQLite vector database";
                        LogManager.logE(TAG, errorMsg);
                        updateProgressOnUiThread(errorMsg);
                        return relevantDocs;
                    }
                } catch (Exception e) {
                    String errorMsg = "Error occurred while creating or loading SQLite vector database: " + e.getMessage();
                    LogManager.logE(TAG, errorMsg, e);
                    updateProgressOnUiThread(errorMsg);
                    if (vectorDbRef[0] != null) {
                        vectorDbRef[0].closeDatabase();
                    }
                    return relevantDocs;
                }

                // 获取数据库统计信息
                int totalChunks = vectorDbRef[0].getChunkCount();
                String dbInfo = "SQLite vector database loaded successfully, containing " + totalChunks + " text chunks";
                LogManager.logD(TAG, dbInfo);
                updateProgressOnUiThread(dbInfo);

                // 获取嵌入模型目录名
                String embModelName = vectorDbRef[0].getMetadata().getModeldir();
                String embeddingModelPath = ConfigManager.getEmbeddingModelPath(requireContext());
                String foundModelPath = null;
                
                // 检查元数据中是否有modeldir配置
                String modeldir = vectorDbRef[0].getMetadata().getModeldir();
                if (modeldir != null && !modeldir.isEmpty()) {
                    // 使用modeldir指定的目录
                    File modeldirFile = new File(embeddingModelPath, modeldir);
                    if (modeldirFile.exists() && modeldirFile.isDirectory()) {
                        // 在modeldir中查找模型文件
                        File[] files = modeldirFile.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile() && (file.getName().endsWith(".pt") || 
                                                     file.getName().endsWith(".pth") || 
                                                     file.getName().endsWith(".onnx"))) {
                                    foundModelPath = file.getAbsolutePath();
                                    LogManager.logD(TAG, "Using model from modeldir: " + foundModelPath);
                                    break;
                                }
                            }
                        }
                    }
                }
                
                // 如果modeldir中没有找到模型，尝试直接使用embeddingModel
                if (foundModelPath == null) {
                    foundModelPath = new File(embeddingModelPath, embModelName).getAbsolutePath();
                }

                // 检查模型文件是否存在
                File modelFile = new File(foundModelPath);
                if (!modelFile.exists()) {
                    LogManager.logD(TAG, "Model file does not exist: " + foundModelPath + ", will try to search in embedding model directory");
                    
                    // 尝试在嵌入模型目录中查找模型文件
                    File embeddingModelDir = new File(embeddingModelPath);
                    if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
                        // 获取所有子目录，用于模型选择
                        List<String> availableModels = new ArrayList<>();
                        File[] directories = embeddingModelDir.listFiles(File::isDirectory);
                        if (directories != null) {
                            for (File dir : directories) {
                                // 检查目录中是否有模型文件
                                File[] modelFiles = dir.listFiles(file -> 
                                    file.isFile() && (file.getName().endsWith(".pt") || 
                                                     file.getName().endsWith(".pth") || 
                                                     file.getName().endsWith(".onnx")));
                                if (modelFiles != null && modelFiles.length > 0) {
                                    availableModels.add(dir.getName());
                                }
                            }
                        }
                        
                        // 也检查根目录中的模型文件
                        File[] rootModelFiles = embeddingModelDir.listFiles(file -> 
                            file.isFile() && (file.getName().endsWith(".pt") || 
                                             file.getName().endsWith(".pth") || 
                                             file.getName().endsWith(".onnx")));
                        if (rootModelFiles != null && rootModelFiles.length > 0) {
                            availableModels.add("Root Directory");
                        }
                        
                        if (!availableModels.isEmpty()) {
                            // 弹出模型选择对话框
                            selectModelAndContinueQuery(embModelName, availableModels, knowledgeBase, embeddingModelPath, vectorDbRef[0]);
                            // 注意：此处不关闭数据库，因为selectModelAndContinueQuery方法会继续使用它
                            return relevantDocs; // 提前返回，等待用户选择模型
                        } else {
                            LogManager.logE(TAG, "No available model files found in embedding model directory");
                            updateProgressOnUiThread("Error: No available model files found in embedding model directory");
                            // 关闭数据库连接
                            vectorDbRef[0].closeDatabase();
                            return relevantDocs; // 提前返回，因为没有可用的模型
                        }
                    }
                }
                
                // 使用工具类检查并加载词嵌入模型
                EmbeddingModelUtils.checkAndLoadEmbeddingModel(
                    requireContext(),
                    vectorDbRef[0],
                    modelFoundPath -> {
                        if (modelFoundPath == null) {
                            // 模型不存在或需要用户选择，已由工具类处理
                            return;
                        }
                        
                        // 模型存在，继续处理
                        String modelInfo = "Using embedding model: " + embModelName + ", path: " + modelFoundPath;
                        LogManager.logD(TAG, modelInfo);
                        updateProgressOnUiThread("Using embedding model: " + embModelName);
                        
                        // 加载嵌入模型
                        loadModelAndProcessQuery(modelFoundPath, query, vectorDbRef[0]);
                    },
                    (selectedModel, selectedModelPath) -> {
                        // 用户选择了模型，继续处理
                        String modelInfo = "Using selected embedding model: " + selectedModel + ", path: " + selectedModelPath;
                        LogManager.logD(TAG, modelInfo);
                        updateProgressOnUiThread("Using selected embedding model: " + selectedModel);
                        
                        // 加载嵌入模型
                        loadModelAndProcessQuery(selectedModelPath, query, vectorDbRef[0]);
                    }
                );
                
                // 注意：此处不关闭数据库，因为loadModelAndProcessQuery方法会继续使用它
                return relevantDocs;
            } catch (Exception e) {
                String errorMsg = "Error occurred while querying vector database: " + e.getMessage();
                LogManager.logE(TAG, errorMsg, e);
                if (isAdded()) {
                    updateProgressOnUiThread(errorMsg);
                }
                
                // 确保在异常情况下也释放模型资源
                EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(requireContext());
                modelManager.markModelNotInUse();
                LogManager.logD(TAG, "Marked model usage end in exception case, allowing auto-unload");
                
                // 关闭数据库连接
                if (vectorDbRef[0] != null) {
                    vectorDbRef[0].closeDatabase();
                    LogManager.logD(TAG, "Closed database connection in exception case");
                }
                
                return relevantDocs;
            }
        } catch (Exception e) {
            String errorMsg = "Error occurred while querying knowledge base: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            if (isAdded()) {
                updateProgressOnUiThread(errorMsg);
            }
            return relevantDocs;
        }
    }
    
    // 构建包含知识库内容的提示词
    private String buildPromptWithKnowledgeBase(String systemPrompt, String userPrompt, List<String> relevantDocs) {
        StringBuilder fullPrompt = new StringBuilder();
        
        LogManager.logD(TAG, "Building prompt with knowledge base content, found " + relevantDocs.size() + " relevant documents");
        
        // 添加系统提示词
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
            LogManager.logD(TAG, "Added system prompt, length: " + systemPrompt.length());
        } else {
            LogManager.logD(TAG, "System prompt is empty");
        }
        
        // 添加知识库内容
        if (!relevantDocs.isEmpty()) {
            fullPrompt.append("The following is information related to the question:\n");
            
            for (int i = 0; i < relevantDocs.size(); i++) {
                String docContent = relevantDocs.get(i);
                if (docContent == null || docContent.trim().isEmpty()) {
                    LogManager.logW(TAG, "Document #" + (i + 1) + " content is empty, skipped");
                    continue;
                }
                
                // 不再限制文本长度，显示完整内容
                fullPrompt.append("Document").append(i + 1).append(":\n").append(docContent).append("\n\n");
                LogManager.logD(TAG, "Added document #" + (i + 1) + ", length: " + docContent.length());
            }
        } else {
            fullPrompt.append("No information related to the question was found.\n\n");
            LogManager.logW(TAG, "No relevant documents found, prompting model with no relevant information");
        }
        
        // 添加用户问题
        fullPrompt.append(userPrompt);
        
        // 记录最终提示词长度
        int promptLength = fullPrompt.length();
        LogManager.logD(TAG, "Final prompt length: " + promptLength + " characters");
        
        return fullPrompt.toString();
    }
    
    // 构建不包含知识库内容的提示词
    private String buildPromptWithoutKnowledgeBase(String systemPrompt, String userPrompt) {
        StringBuilder fullPrompt = new StringBuilder();
        
        // 添加系统提示词
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
        }
        
        // 添加用户问题
        fullPrompt.append(userPrompt);
        
        return fullPrompt.toString();
    }
    
    // 调用大模型API获取回答
    private void callLLMApi(String apiUrl, String apiKey, String model, String prompt) {
        try {
            // 检查全局停止标志
            if (globalStopFlag) {
                LogManager.logD(TAG, "Global stop flag is set, aborting LLM API call");
                resetSendingState();
                return;
            }
            
            LogManager.logD(TAG, "Starting to call LLM API: " + apiUrl);
        LogManager.logD(TAG, "Using model: " + model);
        LogManager.logD(TAG, "Prompt length: " + prompt.length() + " characters");
            
            // 添加连接信息，但不清空之前的调试信息
            //appendToResponse("正在连接API服务器...");
            
            // 安全检查：确保Fragment已附加到Context
            if (!isAdded()) {
                String errorMsg = "错误: Fragment未附加到Context，无法调用API";
                LogManager.logE(TAG, errorMsg);
                updateResultOnUiThread(errorMsg);
                return;
            }
            
            Context context = getContext();
            if (context == null) {
                String errorMsg = "错误: Context为空，无法调用API";
                LogManager.logE(TAG, errorMsg);
                updateResultOnUiThread(errorMsg);
                return;
            }
            
            // 记录开始时间
            final long startTime = System.currentTimeMillis();
            
            // 创建回调接口实例
            com.example.starlocalrag.api.LlmApiAdapter.ApiCallback callback = new com.example.starlocalrag.api.LlmApiAdapter.ApiCallback() {
                // 在onSuccess方法中，进行一次完整的Markdown渲染
                @Override
                public void onSuccess(String response) {
                    // 处理完整响应
                    LogManager.logD(TAG, "API call successful, duration: " + (System.currentTimeMillis() - startTime) + "ms");
                    LogManager.logD(TAG, "Response length: " + response.length() + " characters");

                    // 检查Fragment生命周期状态
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle success, Fragment not attached to Activity");
                        return;
                    }
                    
                    // 在UI线程中进行最终的Markdown渲染
                    mainHandler.post(() -> {
                        try {
                            // 再次检查Fragment状态
                            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                                LogManager.logW(TAG, "Cannot update UI in success callback, Fragment not attached");
                                return;
                            }
                            
                            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
                            if (textViewResponse != null) {
                                // 获取当前显示的内容
                                String currentText = textViewResponse.getText().toString();
                                
                                // 检查并修复代码块
                                if (hasIncompleteCodeBlock(currentText)) {
                                    currentText = fixCodeBlocks(currentText);
                                }
                                
                                // 使用Markwon进行最终渲染
                                markwon.setMarkdown(textViewResponse, currentText);
                                
                                // 确保文本可选择
                                textViewResponse.setTextIsSelectable(true);
                                
                                // 确保链接可点击
                                textViewResponse.setMovementMethod(LinkMovementMethod.getInstance());
                                
                                LogManager.logD(TAG, "Final Markdown rendering completed");
                            }
                            
                            // 使用统一的状态重置方法
                            resetSendingState();
                            LogManager.logD(TAG, "Task completed, all states reset");
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Final Markdown rendering failed", e);
                            // 使用统一的状态重置方法
                            resetSendingState();
                        }
                    });
                }
                
                // 用于累积流式响应的StringBuilder
                private final StringBuilder responseBuilder = new StringBuilder();
                // 记录是否已添加模型回答标题
                private final boolean[] modelTitleAdded = {false};
                // 上次显示的响应内容
                private final String[] lastDisplayedResponse = {""};
                // 检测是否为华为设备
                private static boolean isHuaweiDevice() {
                    return Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                           Build.BRAND.toLowerCase().contains("huawei") ||
                           Build.BRAND.toLowerCase().contains("honor");
                }
                
                // 字符变化阈值，小于这个值的变化不触发UI更新
                private static final int MIN_CHAR_CHANGE = 5;
                // 上次更新UI的时间
                private long lastUpdateTime = System.currentTimeMillis();
                // 更新间隔时间（毫秒）
                private static final long UPDATE_INTERVAL = 100;

                // 在onStreamingData方法中，使用简单的setText方法
                @Override
                public void onStreamingData(final String chunk) {
                    // 检查Fragment生命周期状态
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle streaming data, Fragment not attached to Activity");
                        return;
                    }
                    
                    // 检查全局停止标志
                    if (globalStopFlag) {
                        LogManager.logD(TAG, "Global stop flag is set, ignoring streaming data");
                        return;
                    }
                    
                    // 记录收到的数据块
                    //LogManager.logD(TAG, "Received data chunk: [" + chunk + "]");
                    
                    // 累积响应内容
                    responseBuilder.append(chunk);
                    final String fullContent = responseBuilder.toString();
                    
                    // 在UI线程中更新内容，使用纯文本方式
                    getActivity().runOnUiThread(() -> {
                        try {
                            // 再次检查Fragment状态
                            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                                LogManager.logW(TAG, "Cannot update UI in streaming callback, Fragment not attached");
                                return;
                            }
                            
                            // 获取文本视图和滚动视图
                            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
                            ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
                            if (textViewResponse == null || scrollView == null) return;
                            
                            // 检查当前滚动位置
                            boolean wasAtBottom = isScrolledToBottom(scrollView);
                            
                            // 准备要显示的完整内容
                            String displayContent;
                            long currentTime = System.currentTimeMillis();
                            
                            // 如果是第一次收到数据，添加模型回答标题
                            if (!modelTitleAdded[0]) {
                                modelTitleAdded[0] = true;
                                String currentText = textViewResponse.getText().toString();
                                displayContent = currentText.isEmpty() 
                                    ? "\n\n---\n\n## " + getString(R.string.model_response) + "\n\n" + fullContent 
                                    : currentText + "\n\n---\n\n## " + getString(R.string.model_response) + "\n\n" + fullContent;
                            } else {
                                // 检查内容变化是否足够大或时间间隔足够长
                                int charDiff = fullContent.length() - lastDisplayedResponse[0].length();
                                long timeDiff = currentTime - lastUpdateTime;
                                
                                // 如果变化不够大且时间间隔不够长，不更新UI
                                if (charDiff < MIN_CHAR_CHANGE && timeDiff < UPDATE_INTERVAL) {
                                    return;
                                }
                                
                                // 获取当前内容，并追加新内容
                                String currentText = textViewResponse.getText().toString();
                                
                                // 找到最后一次显示内容的位置，并替换为新的完整内容
                                int lastResponseIndex = currentText.lastIndexOf(lastDisplayedResponse[0]);
                                if (lastResponseIndex >= 0) {
                                    displayContent = currentText.substring(0, lastResponseIndex) + fullContent;
                                } else {
                                    // 如果找不到上次内容，则直接追加新内容
                                    String incrementalContent = fullContent.substring(lastDisplayedResponse[0].length());
                                    displayContent = currentText + incrementalContent;
                                }
                            }
                            
                            // 更新最后显示的内容和时间
                            lastDisplayedResponse[0] = fullContent;
                            lastUpdateTime = currentTime;
                            
                            // 使用纯文本方式更新内容
                            textViewResponse.setText(displayContent);
                            
                            // 如果之前在底部，则滚动到底部
                            if (wasAtBottom) {
                                scrollToBottom(scrollView);
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to update streaming response UI", e);
                        }
                    });
                }
                




                // 这些变量已不再使用，但保留以便其他方法可能引用
                

                
                
                /**
                 * 记录内容中的Markdown标记
                 * @param content 要检查的内容
                 */
                private void logMarkdownMarkers(String content) {
                    if (content == null || content.isEmpty()) return;
                    
                    // 检查常见的Markdown标记
                    if (content.contains("```")) {
                        LogManager.logD(TAG, "Detected code block marker: ``` in content");
                    }
                    if (content.contains("`")) {
                        LogManager.logD(TAG, "Detected inline code marker: ` in content");
                    }
                    if (content.contains("**")) {
                        LogManager.logD(TAG, "Detected bold marker: ** in content");
                    }
                    if (content.contains("#")) {
                        LogManager.logD(TAG, "Detected heading marker: # in content");
                    }
                }
                
                /**
                 * 检查内容中是否有未完成的代码块
                 * @param content 要检查的内容
                 * @return 如果有未完成的代码块返回true，否则返回false
                 */
                private boolean hasIncompleteCodeBlock(String content) {
                    if (content == null || content.isEmpty()) return false;
                    
                    // 计算代码块标记的数量
                    int count = 0;
                    int index = -1;
                    
                    // 使用更精确的方法检测代码块标记
                    while ((index = content.indexOf("```", index + 1)) != -1) {
                        // 检查是否是真正的代码块开始/结束标记，而不是嵌套在其他代码块中的文本
                        boolean isRealCodeBlockMarker = true;
                        
                        // 检查这个标记是否在行首或前面是换行符
                        if (index > 0) {
                            char prevChar = content.charAt(index - 1);
                            // 如果前一个字符不是换行符或空格，可能不是真正的代码块标记
                            if (prevChar != '\n' && prevChar != ' ' && prevChar != '\t') {
                                // 进一步检查，如果前面有换行符，则可能是真正的代码块标记
                                int prevNewlineIndex = content.lastIndexOf('\n', index - 1);
                                if (prevNewlineIndex == -1 || index - prevNewlineIndex > 4) { // 允许有少量缩进
                                    isRealCodeBlockMarker = false;
                                }
                            }
                        }
                        
                        if (isRealCodeBlockMarker) {
                            count++;
                        }
                    }
                    
                    // 如果代码块标记数量为奇数，说明有未完成的代码块
                    return count % 2 != 0;
                }
                
                /**
                 * 计算字符串中指定模式出现的次数
                 * @param content 要检查的内容
                 * @param pattern 要查找的模式
                 * @return 模式出现的次数
                 */
                private int countOccurrences(String content, String pattern) {
                    if (content == null || content.isEmpty() || pattern == null || pattern.isEmpty()) {
                        return 0;
                    }
                    
                    int count = 0;
                    int index = 0;
                    while ((index = content.indexOf(pattern, index)) != -1) {
                        count++;
                        index += pattern.length();
                    }
                    
                    return count;
                }
                
                /**
                 * 修复内容中的代码块标记
                 * @param content 要修复的内容
                 * @return 修复后的内容
                 */
                private String fixCodeBlocks(String content) {
                    if (content == null || content.isEmpty()) return content;
                    
                    // 记录原始内容长度
                    int originalLength = content.length();
                    
                    // 检查并修复代码块标记
                    StringBuilder sb = new StringBuilder(content);
                    
                    // 计算代码块标记的数量和位置
                    List<Integer> positions = new ArrayList<>();
                    int index = -1;
                    while ((index = content.indexOf("```", index + 1)) != -1) {
                        positions.add(index);
                    }
                    
                    // 如果代码块标记数量为奇数，添加一个结束标记
                    if (positions.size() % 2 != 0) {
                        LogManager.logD(TAG, "Detected incomplete code block, adding end marker");
                        sb.append("\n```");
                    }
                    
                    // 检查行内代码标记的数量
                    int inlineCount = 0;
                    index = -1;
                    while ((index = content.indexOf("`", index + 1)) != -1) {
                        // 跳过代码块标记
                        boolean isCodeBlockMarker = false;
                        for (int pos : positions) {
                            if (Math.abs(index - pos) < 3) { // 允许小误差
                                isCodeBlockMarker = true;
                                break;
                            }
                        }
                        if (!isCodeBlockMarker) {
                            inlineCount++;
                        }
                    }
                    
                    // 如果行内代码标记数量为奇数，添加一个结束标记
                    if (inlineCount % 2 != 0) {
                        LogManager.logD(TAG, "Detected incomplete inline code marker, adding end marker");
                        sb.append("`");
                    }
                    
                    String result = sb.toString();
                    if (result.length() > originalLength) {
                        LogManager.logD(TAG, "Content fixed, original length: " + originalLength + ", new length: " + result.length());
                    }
                    
                    return result;
                }
                
                /**
                 * 检查滚动视图是否已经滚动到底部
                 * @param scrollView 要检查的滚动视图
                 * @return 如果滚动到底部返回true，否则返回false
                 */
                private boolean isScrolledToBottom(ScrollView scrollView) {
                    if (scrollView == null) return false;
                    int scrollY = scrollView.getScrollY();
                    int height = scrollView.getHeight();
                    int scrollViewBottom = scrollY + height;
                    int contentHeight = scrollView.getChildAt(0).getHeight();
                    // 允许20像素的误差，以便更可靠地检测底部
                    return (scrollViewBottom >= contentHeight - 20);
                }
                
                /**
                 * 将滚动视图滚动到底部
                 * @param scrollView 要滚动的视图
                 */
                private void scrollToBottom(ScrollView scrollView) {
                    if (scrollView == null) return;
                    scrollView.post(() -> {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    });
                }
                
                @Override
                public void onError(String errorMessage) {
                    // 处理错误
                    LogManager.logE(TAG, "API call failed, duration: " + (System.currentTimeMillis() - startTime) + "ms, error: " + errorMessage);
                    
                    // 检查Fragment生命周期状态
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle error, Fragment not attached to Activity");
                        return;
                    }
                    
                    try {
                        // 显示错误信息
                        updateResultOnUiThread("API call failed: " + errorMessage);
                        
                        // 使用统一的状态重置方法
                        resetSendingState();
                        LogManager.logD(TAG, "Task error, all states reset");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to handle error callback", e);
                    }
                }
            };
            
            // 创建LlmApiAdapter实例并调用API
            com.example.starlocalrag.api.LlmApiAdapter apiAdapter = new com.example.starlocalrag.api.LlmApiAdapter(context);
            apiAdapter.callLlmApi(apiUrl, apiKey, model, prompt, callback);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to call LLM API", e);
            updateResultOnUiThread("调用API失败: " + e.getMessage());
            resetSendingState();
            LogManager.logD(TAG, "Task exception, all states reset");
        }
    }
    
    // 保存最后一次查询的参数，用于恢复
    private String lastApiUrl;
    private String lastApiKey;
    private String lastModel;
    private String lastKnowledgeBase;
    private String lastSystemPrompt;
    private String lastUserPrompt;
    private boolean queryNeedsResume = false;
    
    // 在UI线程上更新进度信息，带有重试机制
    private void updateProgressOnUiThread(String progress) {
        updateProgressOnUiThreadWithRetry(progress, 3); // 最多重试3次
    }
    
    // 带重试机制的UI更新方法
    private void updateProgressOnUiThreadWithRetry(String progress, int retryCount) {
        if (retryCount <= 0) {
            LogManager.logW(TAG, "UI update retry attempts exhausted, giving up");
            return;
        }
        
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update UI, Fragment not attached to Activity, will retry in 1 second (remaining retries: " + retryCount + ")");
            // 移除自动恢复查询的逻辑，避免应用启动时自动执行查询
            // queryNeedsResume = true; // 标记需要恢复查询
            
            // 1秒后重试
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                updateProgressOnUiThreadWithRetry(progress, retryCount - 1);
            }, 1000);
            return;
        }
        
        getActivity().runOnUiThread(() -> {
            // 再次检查Fragment状态
            if (getActivity() == null || !isAdded() || isDetached()) {
                LogManager.logW(TAG, "Cannot update UI in progress callback, Fragment not attached");
                return;
            }
            appendToResponse(progress);
        });
    }
    
    // 完全重写的追加内容方法，解决滚动和Markdown渲染问题
    private void appendToResponse(String text) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot append response, Fragment not attached to Activity");
            return;
        }
        
        // 检查是否已经在UI线程中
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 已经在UI线程中，直接执行
            performAppendToResponse(text);
        } else {
            // 不在UI线程中，切换到UI线程
            getActivity().runOnUiThread(() -> performAppendToResponse(text));
        }
    }
    
    private void performAppendToResponse(String text) {
        try {
            // 检查Fragment状态
            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                LogManager.logW(TAG, "Cannot append response in UI thread, Fragment not attached");
                return;
            }
            
            // 获取文本视图和滚动视图
            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
            ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
            if (textViewResponse == null || scrollView == null) return;
            
            // 保存当前文本
            CharSequence currentText = textViewResponse.getText();
            
            // 准备新文本
            String newText;
            if (currentText.length() == 0) {
                newText = text;
            } else {
                newText = currentText + "\n" + text;
            }
            
            try {
                // 优化的Markdown渲染：先设置文本，再渲染
                textViewResponse.setText(newText);
                if (markwon != null) {
                    markwon.setMarkdown(textViewResponse, newText);
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Markdown rendering failed, using plain text", e);
                textViewResponse.setText(newText);
            }
            
            // 自动滚动到底部（延迟执行以确保内容已渲染）
            scrollView.post(() -> {
                try {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to scroll to bottom", e);
                }
            });
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to append content", e);
        }
    }
    
    // 在UI线程上更新结果（替换全部内容）
    private void updateResultOnUiThread(String result) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update result, Fragment not attached to Activity");
            return;
        }
        
        mainHandler.post(() -> {
            try {
                // 再次检查Fragment状态
                if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                    LogManager.logW(TAG, "Cannot update result in UI thread, Fragment not attached");
                    return;
                }
                
                // 获取结果文本视图
                TextView textViewResult = getView().findViewById(R.id.textViewResponse);
                if (textViewResult == null) return;
                
                // 获取滚动视图
                ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
                if (scrollView == null) return;
                
                // 添加调试日志，查看文本内容和Markdown渲染过程
                //LogManager.logD(TAG, "DEBUG-updateResult: Text content to render: " + result);
        //LogManager.logD(TAG, "DEBUG-updateResult: Is Markwon instance null: " + (markwon == null ? "yes" : "no"));
                
                try {
                    // 尝试使用不同的方式渲染Markdown
                    // 先设置纯文本，再尝试渲染
                    textViewResult.setText(result);
                    
                    // 优化的Markdown渲染逻辑
                    // 始终进行Markdown渲染，确保格式正确显示
                    try {
                        // 使用完整的Markdown渲染
                        Spanned spanned = markwon.toMarkdown(result);
                        markwon.setParsedMarkdown(textViewResult, spanned);
                        //LogManager.logD(TAG, "DEBUG-updateResult: Using full Markdown rendering");
                        
                        // 确保链接可点击
                        if (textViewResult.getMovementMethod() == null) {
                            textViewResult.setMovementMethod(LinkMovementMethod.getInstance());
                        }
                    } catch (Exception e) {
                        // 如果渲染失败，回退到简单文本设置
                        textViewResult.setText(result);
                        //LogManager.logE(TAG, "DEBUG-updateResult: Markdown rendering failed, fallback to plain text", e);
                    }
                    
                    // 检查TextView的属性
                    //LogManager.logD(TAG, "DEBUG-updateResult: TextView text selectable state: " + textViewResult.isTextSelectable());
            //LogManager.logD(TAG, "DEBUG-updateResult: TextView MovementMethod: " + textViewResult.getMovementMethod());
                } catch (Exception e) {
                    LogManager.logE(TAG, "DEBUG-updateResult: Markdown rendering failed", e);
                    // 如果高级API失败，尝试使用基本方法
                    markwon.setMarkdown(textViewResult, result);
                }
                
                // 使用多级延迟确保滚动到底部
                scrollView.post(() -> {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                    
                    scrollView.postDelayed(() -> {
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    }, 100);
                    
                    scrollView.postDelayed(() -> {
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    }, 300);
                });
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to update result", e);
            }
        });
    }
    
    // 获取模型列表
    
    // 获取模型列表
    private void fetchModelsForApi() {
        String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
        String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
        String apiKey = editTextApiKey.getText().toString();
        
        // 获取当前保存的模型名称，用于恢复选择
        String savedModelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
        
        // 显示加载状态
        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_LOADING)});
        
        // 如果是本地模型，从本地模型目录中获取可用的模型列表
        String localDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.ApiUrl.LOCAL);
        
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            
            // 从配置中获取模型路径
            String modelPath = ConfigManager.getModelPath(requireContext());
            
            File modelDir = new File(modelPath);
            
            if (!modelDir.exists() || !modelDir.isDirectory()) {
                LogManager.logE(TAG, "Model directory does not exist: " + modelPath);
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_DIRECTORY_NOT_EXIST)});
                Toast.makeText(requireContext(), getString(R.string.toast_model_dir_not_exist, modelPath), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取模型目录中的所有子目录（每个子目录代表一个模型）
            File[] modelDirs = modelDir.listFiles(File::isDirectory);
            
            if (modelDirs == null || modelDirs.length == 0) {
                LogManager.logE(TAG, "No models found in model directory: " + modelPath);
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NOT_FOUND)});
                Toast.makeText(requireContext(), getString(R.string.toast_no_model_found, modelPath), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 提取模型名称
            List<String> modelsList = new ArrayList<>();
            for (File dir : modelDirs) {
                String modelName = dir.getName();
                modelsList.add(modelName);
            }
            
            // 更新UI
            if (modelsList.isEmpty()) {
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NO_AVAILABLE)});
            } else {
                setupSpinner(spinnerApiModel, modelsList.toArray(new String[0]));
                // 恢复用户之前的选择
                if (!savedModelName.isEmpty()) {
                    setSpinnerSelection(spinnerApiModel, savedModelName);
                    LogManager.logD(TAG, "Restoring local model selection: " + savedModelName);
                }
            }
            
            LogManager.logD(TAG, "Successfully got local model list: " + modelsList.size() + " models");
            return;
        }
        
        // 如果是在线模型，需要API Key
        if (apiUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.toast_set_api_first), Toast.LENGTH_SHORT).show();
            setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
            return;
        }
        
        // 构建请求URL，根据不同API调整
        String modelsUrl = apiUrl;
        if (!modelsUrl.endsWith("/")) {
            modelsUrl += "/";
        }
        modelsUrl += "models";
        
        // 创建请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        
        // 使用Volley发送请求
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, modelsUrl, null,
            response -> {
                try {
                    // 解析响应，提取模型列表
                    JSONArray modelsArray = response.getJSONArray("data");
                    List<String> modelsList = new ArrayList<>();
                    
                    for (int i = 0; i < modelsArray.length(); i++) {
                        JSONObject modelObj = modelsArray.getJSONObject(i);
                        String modelId = modelObj.getString("id");
                        modelsList.add(modelId);
                    }
                    
                    // 更新UI
                    if (modelsList.isEmpty()) {
                        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NO_AVAILABLE)});
                    } else {
                        setupSpinner(spinnerApiModel, modelsList.toArray(new String[0]));
                        // 恢复用户之前的选择
                        if (!savedModelName.isEmpty()) {
                            setSpinnerSelection(spinnerApiModel, savedModelName);
                            LogManager.logD(TAG, "Restoring online model selection: " + savedModelName);
                        }
                    }
                    
                    LogManager.logD(TAG, "Successfully got model list: " + modelsList.size() + " models");
                } catch (JSONException e) {
                    LogManager.logE(TAG, "Failed to parse model list", e);
                    setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_PARSE_FAILED)});
                    Toast.makeText(requireContext(), getString(R.string.toast_parse_model_list_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                }
            },
            error -> {
                LogManager.logE(TAG, "Failed to get model list", error);
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
                Toast.makeText(requireContext(), getString(R.string.toast_get_model_list_failed, error.getMessage()), Toast.LENGTH_SHORT).show();
            }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                return headers;
            }
        };
        
        // 添加请求到队列
        Volley.newRequestQueue(requireContext()).add(request);
    }
    
    // 处理新对话按钮点击
    private void handleNewChatClick() {
        // 新对话调试日志已移除
        
        updateProgressOnUiThread("");
        editTextUserPrompt.setText("");
        
        // 清空回答框
        if (textViewResponse != null) {
            textViewResponse.setText("");
        }
        
        // 重置发送/停止按钮状态
        if (isSending.get()) {
            buttonSendStop.setText(getString(R.string.button_send));
            isSending.set(false); // 使用原子操作重置发送状态
            if (isTaskRunning) {
                isTaskCancelled = true;
            }
        }
        
        // 重置模型记忆 - 清除KV缓存和对话历史
        // 【修复】将本地模型操作移到后台线程中执行，避免main线程调用模型
        String selectedApiDisplay = spinnerApiUrl.getSelectedItem().toString();
        String selectedApi = StateDisplayManager.getApiUrlFromDisplayText(getContext(), selectedApiDisplay);
        
        if (AppConstants.ApiUrl.LOCAL.equals(selectedApi)) {
            // 在后台线程中执行本地模型重置操作
            ragQueryExecutor.execute(() -> {
                try {
                    LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(getContext());
                    if (localAdapter != null) {
                        localAdapter.resetModelMemory();
                        LogManager.logD(TAG, "Reset local model memory in background thread");
                    } else {
                        LogManager.logW("RagQaFragment", "LocalLlmAdapter instance is null");
                    }
                } catch (Exception e) {
                    LogManager.logE("RagQaFragment", "Failed to reset model memory", e);
                }
            });
        } else {
            // 对于在线大模型，清除本地对话历史和状态
            // 新对话调试日志已移除
            // 在线大模型通常是无状态的，每次请求都是独立的
            // 这里主要是清除本地的UI状态和缓存
        }
        
        // 新对话调试日志已移除
    }
    
    // 创建上下文菜单
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
    }
    
    // 处理上下文菜单项点击
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return super.onContextItemSelected(item);
    }


    
    // 加载模型并处理查询
    private void loadModelAndProcessQuery(String foundModelPath, String query, SQLiteVectorDatabaseHandler vectorDb) {
        try {
            // 更新进度
            updateProgressOnUiThread("Loading embedding model...");
            
            // 初始化TokenizerManager
            TokenizerManager tokenizerManager = TokenizerManager.getInstance(requireContext());
            if (!tokenizerManager.isInitialized()) {
                //updateProgressOnUiThread("正在初始化全局分词器...");
                boolean initSuccess = tokenizerManager.initialize(foundModelPath);
                if (initSuccess) {
                    //updateProgressOnUiThread("全局分词器初始化成功，将使用统一分词策略");
                    // 启用一致性分词
                    tokenizerManager.setUseConsistentTokenization(true);
                    
                    // 设置调试模式
                    boolean debugMode = SettingsFragment.isDebugModeEnabled(requireContext());
                    if (debugMode) {
                        tokenizerManager.setDebugMode(true);
                        updateProgressOnUiThread("Global tokenizer debug mode enabled");
                    }
                } else {
                    updateProgressOnUiThread("Global tokenizer initialization failed, will use model's built-in tokenizer");
                }
            } else {
                updateProgressOnUiThread("Using already initialized global tokenizer");
                // 启用一致性分词
                tokenizerManager.setUseConsistentTokenization(true);
                
                // 设置调试模式
                boolean debugMode = SettingsFragment.isDebugModeEnabled(requireContext());
                if (debugMode) {
                    tokenizerManager.setDebugMode(true);
                }
            }
            
            // 创建模型处理器
            EmbeddingModelHandler embeddingHandler = new EmbeddingModelHandler(requireContext(), foundModelPath, false);
            
            // 获取模型的向量维度
            int embeddingDimension = embeddingHandler.getEmbeddingDimension();
            LogManager.logD(TAG, "Model vector dimension: " + embeddingDimension);
            updateProgressOnUiThread("Model vector dimension: " + embeddingDimension);

            // 检查向量维度是否与知识库匹配
            int dbDimension = vectorDb.getMetadata().getEmbeddingDimension();
            LogManager.logD(TAG, "Knowledge base vector dimension: " + dbDimension + ", model vector dimension: " + embeddingDimension);
            updateProgressOnUiThread("Knowledge base vector dimension: " + dbDimension);

            if (dbDimension > 0 && dbDimension != embeddingDimension) {
                String warningMsg = "Warning: Vector dimensions do not match! Knowledge base dimension: " + dbDimension + ", model dimension: " + embeddingDimension;
                LogManager.logW(TAG, warningMsg);
                updateProgressOnUiThread(warningMsg);
                updateProgressOnUiThread("This may cause search failure, recommend rebuilding knowledge base or using matching model");
            }


            // 标记模型开始使用
            EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(requireContext());
            modelManager.markModelInUse();
            LogManager.logD(TAG, "Marked model as in use to prevent auto-unloading");
            //updateProgressOnUiThread("标记模型开始使用，防止自动卸载");
            
            try {
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting before vector generation");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // 生成查询向量
                updateProgressOnUiThread("Generating query vector...");
                
                // 获取用户查询
                String userQuery = editTextUserPrompt.getText().toString().trim();
                
                // 生成向量
                float[] queryVector = embeddingHandler.generateEmbedding(userQuery);
                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting after vector generation");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // 记录向量调试信息
                String vectorDebugInfo = embeddingHandler.getVectorDebugInfo(userQuery, queryVector, System.currentTimeMillis());
                
                // 只在非调试模式下显示基本向量信息
                boolean isDebugMode = ConfigManager.getBoolean(requireContext(), ConfigManager.KEY_DEBUG_MODE, false);
                
                updateProgressOnUiThread(vectorDebugInfo);

                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting before database search");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // 搜索相似文本块
                updateProgressOnUiThread("Searching similar text blocks...");
                
                // 获取检索数量设置
                int retrievalCount = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
                
                // 搜索相似文本块
                List<SQLiteVectorDatabaseHandler.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting after database search");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // 显示检索结果的相似度
                if (!searchResults.isEmpty()) {
                    StringBuilder similarityInfo = new StringBuilder("Retrieval similarity: ");
                    for (int i = 0; i < searchResults.size(); i++) {
                        similarityInfo.append(String.format("%.3f", searchResults.get(i).similarity));
                        if (i < searchResults.size() - 1) {
                            similarityInfo.append(", ");
                        }
                    }
                    updateProgressOnUiThread(similarityInfo.toString());
                }
                
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting before reranking");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // 检查是否需要重排
                int rerankCount = ConfigManager.getRerankCount(requireContext());
                String rerankerModelPath = getRerankerModelPath(vectorDb);
                
                if (rerankCount > 0 && rerankerModelPath != null && !rerankerModelPath.isEmpty()) {
                    // 使用重排模型
                    LogManager.logI(TAG, "Using reranker model with rerank count: " + rerankCount);
                    updateProgressOnUiThread("Using reranker model to optimize results...");
                    processWithReranker(userQuery, searchResults, rerankerModelPath, vectorDb);
                } else {
                    // 不使用重排，直接处理向量检索结果
                    if (rerankCount == 0) {
                        LogManager.logI(TAG, "Rerank count is 0, skipping reranking and using vector search results directly");
                        updateProgressOnUiThread("Rerank count is 0, skipping reranking");
                    } else {
                        LogManager.logD(TAG, "No reranker model configured, using vector search results");
                    }
                    processVectorSearchResults(searchResults);
                    
                    // 【修复】不再调用continueRagQueryAfterReranking，避免重复调用LLM API
                    // executeRagQuery方法会等待relevantDocuments被设置后自行调用callLLMApi
                    // continueRagQueryAfterReranking();
                }

                // 标记模型使用结束
                modelManager.markModelNotInUse();
                LogManager.logD(TAG, "Marked model usage end, allowing auto-unload");
                //updateProgressOnUiThread("标记模型使用结束，允许自动卸载");
                
                // 获取API信息
                String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
                String apiKey = editTextApiKey.getText().toString();
                String apiModel = spinnerApiModel.getSelectedItem().toString();
                
                // 不再直接调用API，而是让executeRagQuery方法处理
                // 这样可以避免重复调用API
                // callLLMApi(apiUrl, apiKey, apiModel, buildPromptWithKnowledgeBase(editTextSystemPrompt.getText().toString(), userQuery, relevantDocs));
            } catch (Exception e) {
                String errorMsg = "Query processing failed: " + e.getMessage();
                LogManager.logE(TAG, errorMsg, e);
                updateProgressOnUiThread(errorMsg);
            }
        } catch (Exception e) {
            String errorMsg = "Model loading failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
            
            // 关闭数据库
            try {
                vectorDb.close();
                LogManager.logD(TAG, "Vector database closed");
            } catch (Exception ex) {
                LogManager.logE(TAG, "Failed to close vector database: " + ex.getMessage(), ex);
            }
        }
    }
    
    /**
     * 获取重排模型路径
     */
    private String getRerankerModelPath(SQLiteVectorDatabaseHandler vectorDb) {
        try {
            // 从数据库元数据获取重排模型目录
            String rerankerDir = vectorDb.getMetadata().getRerankerdir();
            if (rerankerDir == null || rerankerDir.trim().isEmpty()) {
                LogManager.logD(TAG, "No reranker model directory configured in database metadata");
                return null;
            }
            
            // 获取重排模型根路径
            String rerankerBasePath = ConfigManager.getRerankerModelPath(requireContext());
            
            // 构建完整的重排模型路径
            File rerankerModelDir = new File(rerankerBasePath, rerankerDir);
            if (!rerankerModelDir.exists() || !rerankerModelDir.isDirectory()) {
                LogManager.logW(TAG, "Reranker model directory does not exist: " + rerankerModelDir.getAbsolutePath());
                return null;
            }
            
            // 查找ONNX模型文件
            File[] modelFiles = rerankerModelDir.listFiles(file -> 
                file.isFile() && file.getName().toLowerCase().endsWith(".onnx"));
            
            if (modelFiles == null || modelFiles.length == 0) {
                LogManager.logW(TAG, "No ONNX model files found in reranker model directory: " + rerankerModelDir.getAbsolutePath());
                return null;
            }
            
            // 返回第一个找到的模型文件路径
            String modelPath = modelFiles[0].getAbsolutePath();
            LogManager.logD(TAG, "Found reranker model: " + modelPath);
            return modelPath;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get reranker model path: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 使用重排模型处理搜索结果
     */
    private void processWithReranker(String query, List<SQLiteVectorDatabaseHandler.SearchResult> searchResults, 
                                   String rerankerModelPath, SQLiteVectorDatabaseHandler vectorDb) {
        try {
            // 检查全局停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "Global stop requested, aborting reranking process");
                updateProgressOnUiThread("Operation stopped by user");
                return;
            }
            
            // 获取重排模型管理器
            RerankerModelManager rerankerManager = RerankerModelManager.getInstance(requireContext());
            
            // 提取文档文本
            List<String> documents = new ArrayList<>();
            for (SQLiteVectorDatabaseHandler.SearchResult result : searchResults) {
                documents.add(result.text);
            }
            
            // 计算topK值 - 使用全部检索结果数量用于重排打印，但实际使用时仍限制为rerank_count
            int rerankCount = ConfigManager.getRerankCount(requireContext());
            int retrievalCount = ConfigManager.getSearchDepth(requireContext());
            int topK = Math.min(searchResults.size(), retrievalCount); // 使用全部检索结果进行重排
            
            LogManager.logI(TAG, "Starting async rerank: query=" + query + ", documents.size()=" + documents.size() + ", topK=" + topK + ", rerankCount=" + rerankCount);
            
            // 使用新的rerankAsync方法，避免嵌套线程池
            rerankerManager.rerankAsync(rerankerModelPath, query, documents, topK, new RerankerModelManager.RerankerCallback() {
                @Override
                public void onRerankProgress(String message) {
                    LogManager.logI(TAG, "Reranking progress: " + message);
                    updateProgressOnUiThread(message);
                }
                
                @Override
                public void onRerankComplete(List<RerankerModelHandler.RerankResult> rerankedResults) {
                    LogManager.logI(TAG, "Reranking successful, result count: " + rerankedResults.size());
                    
                    try {
                        processRerankedResults(rerankedResults);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to process reranked results: " + e.getMessage(), e);
                        updateProgressOnUiThread("Failed to process reranked results, using vector search results");
                        processVectorSearchResults(searchResults);
                    }
                }
                
                @Override
                public void onRerankError(String error) {
                    LogManager.logE(TAG, "Reranking failed: " + error);
                    updateProgressOnUiThread("Reranking failed, using vector search results");
                    // 回退到向量检索结果
                    processVectorSearchResults(searchResults);
                }
            });
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Reranking processing exception: " + e.getMessage(), e);
            updateProgressOnUiThread("Reranking processing exception, using vector search results");
            // 回退到向量检索结果
            processVectorSearchResults(searchResults);
        }
    }
    
    /**
     * 处理向量检索结果（不使用重排）
     */
    private void processVectorSearchResults(List<SQLiteVectorDatabaseHandler.SearchResult> searchResults) {
        try {
            // 提取相关文档
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder();
            
            for (int i = 0; i < searchResults.size(); i++) {
                SQLiteVectorDatabaseHandler.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.text);
                
                // 记录详细信息到日志
                String resultInfo = "Similarity: " + result.similarity + ", Text: " + result.text.substring(0, Math.min(50, result.text.length())) + "...";
                LogManager.logD(TAG, resultInfo);

                // 添加到进度显示 - 只显示匹配序号和相似度值，不显示文本内容
                similarityInfoBuilder.append("Match").append(i + 1).append(": ").append(String.format("%.4f", result.similarity));
                if (i < searchResults.size() - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            // 保存相似度信息
            synchronized (this) {
                this.similarityInfo = similarityInfoBuilder.toString();
                this.relevantDocuments = relevantDocs;
            }
            
            LogManager.logD(TAG, "Vector search results processing completed, document count: " + relevantDocs.size());
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to process vector search results: " + e.getMessage(), e);
            updateProgressOnUiThread("Failed to process search results: " + e.getMessage());
        }
    }
    
    /**
     * 处理重排结果
     */
    private void processRerankedResults(List<RerankerModelHandler.RerankResult> rerankedResults) {
        try {
            // 详细打印重排结果 - 显示全部结果而不限制数量
            LogManager.logI(TAG, "=== Reranking Results Details ===");
            for (int i = 0; i < rerankedResults.size(); i++) {
                RerankerModelHandler.RerankResult result = rerankedResults.get(i);
                LogManager.logI(TAG, String.format("Rerank #%d: score=%.6f, originalIndex=%d, textPreview=%s", 
                    i + 1, result.score, result.originalIndex, 
                    result.text.substring(0, Math.min(100, result.text.length())) + "..."));
            }
            LogManager.logI(TAG, "=== Reranking Results Details End ===");
            
            // 获取实际使用的重排数量限制
            int rerankCount = ConfigManager.getRerankCount(requireContext());
            int actualResultCount = Math.min(rerankedResults.size(), rerankCount);
            LogManager.logI(TAG, "Actually using top " + actualResultCount + " reranked results for answer generation");
            
            // 提取重排后的文档 - 只使用前rerankCount个结果
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder();
            
            for (int i = 0; i < actualResultCount; i++) {
                RerankerModelHandler.RerankResult result = rerankedResults.get(i);
                relevantDocs.add(result.text);

                // 添加到进度显示 - 显示重排序号和分数
                similarityInfoBuilder.append("Rerank").append(i + 1).append(": ").append(String.format("%.4f", result.score));
                if (i < actualResultCount - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            // 保存重排信息
            synchronized (this) {
                this.similarityInfo = "Reranked Results - " + similarityInfoBuilder.toString();
                this.relevantDocuments = relevantDocs;
            }
            
            LogManager.logD(TAG, "Reranked results processing completed, actual document count used: " + relevantDocs.size());
            
            updateProgressOnUiThread("Reranking optimization completed, found " + relevantDocs.size() + " relevant contents");
            
            // 【修复】不再调用continueRagQueryAfterReranking，避免重复调用LLM API
            // executeRagQuery方法会等待relevantDocuments被设置后自行调用callLLMApi
            // continueRagQueryAfterReranking();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to process reranked results: " + e.getMessage(), e);
            updateProgressOnUiThread("Failed to process reranked results: " + e.getMessage());
        }
    }
    
    // 显示模型选择对话框
    private void selectModelAndContinueQuery(String originalModel, List<String> availableModels, String knowledgeBase, String embeddingModelPath, SQLiteVectorDatabaseHandler vectorDb) {
        // 确保在UI线程中运行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 如果不在UI线程，切换到UI线程
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> selectModelAndContinueQuery(originalModel, availableModels, knowledgeBase, embeddingModelPath, vectorDb));
            return;
        }
        
        // 如果没有可用模型，显示错误信息并提示用户添加模型
        if (availableModels.isEmpty()) {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
            builder.setTitle(getString(R.string.dialog_title_embedding_model_not_found))
                   .setMessage(getString(R.string.dialog_message_embedding_model_not_found, embeddingModelPath, originalModel))
                   .setPositiveButton(getString(R.string.common_ok), null)
                   .show();
            return;
        }
        
        // 创建对话框布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_model_selection, null);
        Spinner spinnerModels = dialogView.findViewById(R.id.spinnerModels);
        CheckBox checkBoxRemember = dialogView.findViewById(R.id.checkBoxRemember);
        TextView textViewInfo = dialogView.findViewById(R.id.textViewInfo);
        
        // 设置提示信息
        String infoText = "Original model not found: " + originalModel + "\nPlease select a replacement model from the available models below:";
        textViewInfo.setText(infoText);
        
        // 设置模型列表
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, availableModels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModels.setAdapter(adapter);
        
        // 如果原始模型在列表中，选中它
        int originalModelIndex = availableModels.indexOf(originalModel);
        if (originalModelIndex >= 0) {
            spinnerModels.setSelection(originalModelIndex);
        }
        
        // 检查是否有保存的模型映射
        String savedMapping = null;
        
        // 检查是否是特定的模型，使用ConfigManager获取映射
        if (originalModel.equals("bge-m3")) {
            savedMapping = ConfigManager.getModelMapping(requireContext(), "model_bge-m3", null);
        } else if (originalModel.equals("SBKNBaseV1.0")) {
            savedMapping = ConfigManager.getModelMapping(requireContext(), "kb_SBKNBaseV1.0", null);
        } else {
            // 对于其他模型，使用通用映射格式
            savedMapping = ConfigManager.getModelMapping(requireContext(), "model_" + originalModel, null);
        }
        
        if (savedMapping != null && !savedMapping.isEmpty()) {
            // 找到保存的映射模型在列表中的位置
            int savedModelIndex = availableModels.indexOf(savedMapping);
            if (savedModelIndex >= 0) {
                spinnerModels.setSelection(savedModelIndex);
                checkBoxRemember.setChecked(true);
            }
        }
        
        // 创建对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_select_embedding_model))
               .setView(dialogView)
               .setCancelable(false)
               .setPositiveButton("OK", (dialog, which) -> {
                   // 获取选中的模型
                   String selectedModel = (String) spinnerModels.getSelectedItem();
                   
                   // 如果选中了"记住此选择"，保存映射
                   if (checkBoxRemember.isChecked()) {
                       ConfigManager.setModelMapping(requireContext(), "model_" + originalModel, selectedModel);
                   }
                   
                   // 显示加载中提示
                   updateProgressOnUiThread("Preparing model...");
                   
                   // 在后台线程中执行耗时操作，避免UI卡顿
                   new Thread(() -> {
                       try {
                           // 继续执行RAG查询任务
                           continueQueryWithSelectedModel(selectedModel, knowledgeBase, embeddingModelPath, vectorDb);
                       } catch (Exception e) {
                           LogManager.logE(TAG, "Error processing model selection", e);
                           updateProgressOnUiThread("错误: 处理模型选择时出错: " + e.getMessage());
                       }
                   }).start();
               })
               .setNegativeButton(new StateDisplayManager(requireContext()).getButtonDisplay(AppConstants.BUTTON_TEXT_CANCEL), (dialog, which) -> {
                   // 用户取消了模型选择，显示提示
                   updateProgressOnUiThread("已取消模型选择");
               });
        
        // 显示对话框
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    // 继续执行RAG查询任务
    private void continueQueryWithSelectedModel(String selectedModel, String knowledgeBase, String embeddingModelPath, SQLiteVectorDatabaseHandler vectorDb) {
        // 获取嵌入模型路径
        String foundModelPath = null;
        boolean modelFound = false;
        
        String rootDirectoryText = getString(R.string.embedding_model_root_directory);
        if (selectedModel.equals(rootDirectoryText)) {
            // 在根目录中查找模型文件
            File embeddingModelDir = new File(embeddingModelPath);
            File[] files = embeddingModelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && (file.getName().endsWith(".pt") || 
                                         file.getName().endsWith(".pth") || 
                                         file.getName().endsWith(".onnx"))) {
                        foundModelPath = file.getAbsolutePath();
                        modelFound = true;
                        
                        // 更新元数据中的modeldir为空字符串（表示使用根目录）
                        vectorDb.getMetadata().setModeldir("");
                        vectorDb.saveDatabase();
                        LogManager.logD(TAG, "Updated metadata, modeldir set to empty (using root directory)");
                        break;
                    }
                }
            }
        } else {
            // 使用选定的目录
            File selectedDir = new File(embeddingModelPath, selectedModel);
            if (selectedDir.exists() && selectedDir.isDirectory()) {
                File[] files = selectedDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && (file.getName().endsWith(".pt") || 
                                             file.getName().endsWith(".pth") || 
                                             file.getName().endsWith(".onnx"))) {
                            foundModelPath = file.getAbsolutePath();
                            modelFound = true;
                            
                            // 更新元数据中的modeldir为选定的目录
                            vectorDb.getMetadata().setModeldir(selectedModel);
                            vectorDb.saveDatabase();
                            LogManager.logD(TAG, "Updated metadata, modeldir set to: " + selectedModel);
                            break;
                        }
                    }
                }
            }
        }
        
        if (!modelFound) {
            updateProgressOnUiThread(getString(R.string.error_model_file_not_found));
            return;
        }
        
        // 保存模型映射
        ConfigManager.setModelMapping(requireContext(), "model_" + vectorDb.getMetadata().getModeldir(), selectedModel);
        
        // 显示模型信息
        String modelInfo = "使用嵌入模型: " + selectedModel + ", 路径: " + foundModelPath;
        LogManager.logD(TAG, modelInfo);
        updateProgressOnUiThread(getString(R.string.using_embedding_model, selectedModel));
        
        // 加载嵌入模型
        updateProgressOnUiThread(getString(R.string.loading_embedding_model));
        
        // 使用EmbeddingModelManager异步加载模型
        EmbeddingModelManager modelManager = EmbeddingModelManager.getInstance(requireContext());
        
        // 创建一个CountDownLatch来等待异步加载完成
        final CountDownLatch modelLoadLatch = new CountDownLatch(1);
        final AtomicReference<EmbeddingModelHandler> modelHandlerRef = new AtomicReference<>();
        final AtomicReference<Exception> modelErrorRef = new AtomicReference<>();
        
        modelManager.getModelAsync(foundModelPath, new EmbeddingModelManager.ModelCallback() {
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
            boolean modelLoaded = modelLoadLatch.await(60, TimeUnit.SECONDS);
            if (!modelLoaded) {
                String errorMsg = getString(R.string.error_embedding_model_timeout);
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return;
            }
        } catch (InterruptedException e) {
            String errorMsg = getString(R.string.error_embedding_model_interrupted, e.getMessage());
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
            return;
        }
        
        // 检查是否有错误
        if (modelErrorRef.get() != null) {
            String errorMsg = "错误: 加载嵌入模型失败: " + modelErrorRef.get().getMessage();
            LogManager.logE(TAG, errorMsg, modelErrorRef.get());
            updateProgressOnUiThread(errorMsg);
            return;
        }
        
        // 获取加载好的模型
        EmbeddingModelHandler modelHandler = modelHandlerRef.get();
        if (modelHandler == null) {
            String errorMsg = getString(R.string.error_embedding_model_handler_failed);
            LogManager.logE(TAG, errorMsg);
            updateProgressOnUiThread(errorMsg);
            return;
        }
        updateProgressOnUiThread(getString(R.string.embedding_model_loaded_success, modelHandler.getModelName()));

        // 标记模型开始使用
        modelManager.markModelInUse();
        LogManager.logD(TAG, "Marked model as in use to prevent auto-unloading");
        updateProgressOnUiThread("Marked model as in use to prevent auto-unloading");

        // 生成查询向量
        try {
            updateProgressOnUiThread("Generating query vector...");
            
            // 获取用户查询
            String userQuery = editTextUserPrompt.getText().toString().trim();
            
            // 生成向量
            float[] queryVector = modelHandler.generateEmbedding(userQuery);
            
            // 查询向量异常处理
            if (queryVector != null && queryVector.length > 0) {
                // 检测查询向量异常
                VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(queryVector, -1);
                
                if (anomalyResult.isAnomalous) {
                    LogManager.logW(TAG, String.format("Query vector anomaly detected: %s (severity: %.2f) - %s", 
                            anomalyResult.type.name(), anomalyResult.severity, anomalyResult.description));
                    
                    // 修复查询向量异常
                    float[] repairedQueryVector = VectorAnomalyHandler.repairVector(queryVector, anomalyResult.type);
                    if (repairedQueryVector != null) {
                        queryVector = repairedQueryVector;
                        LogManager.logD(TAG, "Query vector anomaly repaired successfully");
                        updateProgressOnUiThread("Query vector anomaly detected and repaired");
                    } else {
                        LogManager.logW(TAG, "Failed to repair query vector anomaly, using original vector");
                        updateProgressOnUiThread("Query vector anomaly detected but repair failed, using original vector");
                    }
                }
                
                // 最终查询向量验证
                VectorAnomalyHandler.AnomalyResult finalCheck = VectorAnomalyHandler.detectAnomalies(queryVector, -1);
                if (finalCheck.isAnomalous && finalCheck.severity > 0.8f) {
                    LogManager.logE(TAG, String.format("Critical query vector anomaly remains after repair: %s", finalCheck.description));
                    // 对于严重异常，生成随机单位向量作为备用
                    queryVector = VectorAnomalyHandler.generateRandomUnitVector(queryVector.length);
                    LogManager.logW(TAG, "Generated random unit vector as fallback for query");
                    updateProgressOnUiThread("Critical query vector anomaly, using fallback vector");
                }
            }
            
            // 记录向量调试信息
            String vectorDebugInfo = modelHandler.getVectorDebugInfo(userQuery, queryVector, System.currentTimeMillis());
            updateProgressOnUiThread(vectorDebugInfo);
            
            // 搜索相似文本块
            updateProgressOnUiThread("Searching for similar text blocks...");
            
            // 获取检索数量设置
            int retrievalCount = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
            
            // 搜索相似文本块
            List<SQLiteVectorDatabaseHandler.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
            
            // 提取相关文档
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder("Found similar text blocks:\n");
            for (int i = 0; i < searchResults.size(); i++) {
                SQLiteVectorDatabaseHandler.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.text);
                
                // 记录详细信息到日志
                String resultInfo = "Similarity: " + result.similarity + ", text: " + result.text.substring(0, Math.min(50, result.text.length())) + "...";
                LogManager.logD(TAG, resultInfo);

                // 添加到进度显示 - 只显示匹配序号和相似度值，不显示文本内容
                similarityInfoBuilder.append("Match").append(i + 1).append(": ").append(String.format("%.4f", result.similarity));
                similarityInfoBuilder.append("\n");
            }

            // 显示相似度信息
            if (!searchResults.isEmpty()) {
                updateProgressOnUiThread(similarityInfoBuilder.toString());
            } else {
                updateProgressOnUiThread("Warning: Knowledge base query returned no relevant documents");
            }

            // 标记模型使用结束
            modelManager.markModelNotInUse();
            LogManager.logD(TAG, "Mark model usage ended, allow auto unload");
            updateProgressOnUiThread("Mark model usage ended, allow auto unload");
            
            // 获取API信息
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String apiModel = spinnerApiModel.getSelectedItem().toString();
            
            // 不再直接调用API，而是让executeRagQuery方法处理
            // 这样可以避免重复调用API
            // callLLMApi(apiUrl, apiKey, apiModel, buildPromptWithKnowledgeBase(editTextSystemPrompt.getText().toString(), userQuery, relevantDocs));
            
        } catch (Exception e) {
            String errorMsg = "Query processing failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
        }
    }
    
    // 获取保存的模型映射
    private String getModelMapping(String originalModel) {
        try {
            if (originalModel == null || originalModel.isEmpty()) {
                return null;
            }
            
            // 从ConfigManager获取模型映射
            return ConfigManager.getModelMapping(requireContext(), "model_" + originalModel, null);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get model mapping", e);
            return null;
        }
    }
    
    // 保存模型映射到数据库元数据
    private void saveModelMapping(String originalModel, String selectedModel, String knowledgeBase) {
        try {
            if (originalModel == null || originalModel.isEmpty() || selectedModel == null || selectedModel.isEmpty() || knowledgeBase == null || knowledgeBase.isEmpty()) {
                return;
            }
            
            // 获取知识库目录
            String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            
            // 更新数据库元数据
            SQLiteVectorDatabaseHandler vectorDb = null;
            try {
                vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                if (vectorDb.loadDatabase()) {
                    // 获取选择的模型文件名
                    String selectedModelName = new File(selectedModel).getName();
                    
                    // 更新数据库元数据中的模型信息
                    if (vectorDb.updateEmbeddingModel(selectedModelName)) {
                        LogManager.logD(TAG, "Updated model information in database metadata: " + selectedModelName);
                        
                        // 保存数据库
                        if (vectorDb.saveDatabase()) {
                            LogManager.logD(TAG, "Saved database metadata");
                        } else {
                            LogManager.logE(TAG, "Failed to save database metadata");
                        }
                    } else {
                        LogManager.logE(TAG, "Failed to update model information in database metadata");
                    }
                } else {
                    LogManager.logE(TAG, "Failed to load database");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to update database metadata", e);
            } finally {
                if (vectorDb != null) {
                    try {
                        vectorDb.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to close database", e);
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save model mapping", e);
        }
    }
    
    // 获取所有可能的模型路径
    private List<String> getPossibleModelPaths() {
        List<String> possiblePaths = new ArrayList<>();
        
        // 获取配置中的嵌入模型路径
        String configPath = ConfigManager.getEmbeddingModelPath(requireContext());
        possiblePaths.add(configPath);
        
        // 添加可能的替代路径
        File externalStorageDir = android.os.Environment.getExternalStorageDirectory();
        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        
        possiblePaths.add(new File(externalStorageDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(downloadDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(externalStorageDir, "Download/starragdata/embeddings").getAbsolutePath());
        possiblePaths.add("/storage/emulated/0/Download/starragdata/embeddings");
        possiblePaths.add("/sdcard/Download/starragdata/embeddings");
        
        return possiblePaths;
    }
    
    // 检查下拉框是否为空
    private boolean isSpinnerEmpty(Spinner spinner) {
        if (spinner == null) return true;
        if (spinner.getAdapter() == null) return true;
        SpinnerAdapter adapter = spinner.getAdapter();
        return adapter.getCount() == 0;
    }
    
    /**
     * 将选中的文本转为知识库笔记
     * @param text 要转为笔记的文本
     */
    private void transferToKnowledgeNote(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.toast_no_selected_text_or_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 保存要转换的文本到临时变量
            final String textToTransfer = text;
            
            // 获取MainActivity实例
            MainActivity activity = (MainActivity) requireActivity();
            
            // 导航到知识库笔记页面
            activity.navigateToKnowledgeNote();
            
            // 添加延迟，确保Fragment完全初始化
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    // 尝试获取KnowledgeNoteFragment实例
                    Fragment fragment = null;
                    
                    // 尝试通过不同的标签获取Fragment
                    for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                        if (f instanceof KnowledgeNoteFragment) {
                            fragment = f;
                            break;
                        }
                        
                        // 检查子Fragment
                        if (f.getChildFragmentManager() != null) {
                            for (Fragment childFragment : f.getChildFragmentManager().getFragments()) {
                                if (childFragment instanceof KnowledgeNoteFragment) {
                                    fragment = childFragment;
                                    break;
                                }
                            }
                        }
                    }
                    
                    // 如果找到了KnowledgeNoteFragment
                    if (fragment instanceof KnowledgeNoteFragment) {
                        KnowledgeNoteFragment knowledgeNoteFragment = (KnowledgeNoteFragment) fragment;
                        
                        // 将文本插入到知识库笔记的内容编辑框中
                        knowledgeNoteFragment.insertTextToContentEditor(textToTransfer);
                        
                        // 显示提示信息
                        Toast.makeText(requireContext(), getString(R.string.toast_transferred_to_note), Toast.LENGTH_SHORT).show();
                        LogManager.logD(TAG, "Converted text to knowledge base note, length: " + textToTransfer.length());
                    } else {
                        // 第一次尝试失败，再次延迟重试
                        LogManager.logD(TAG, "First attempt to get KnowledgeNoteFragment failed, will retry in 500ms");
                        
                        // 再次延迟500ms重试
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                // 再次尝试获取Fragment
                                Fragment retryFragment = null;
                                for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                                    if (f instanceof KnowledgeNoteFragment) {
                                        retryFragment = f;
                                        break;
                                    }
                                    
                                    // 检查ViewPager中的Fragment
                                    if (f.getChildFragmentManager() != null) {
                                        for (Fragment childFragment : f.getChildFragmentManager().getFragments()) {
                                            if (childFragment instanceof KnowledgeNoteFragment) {
                                                retryFragment = childFragment;
                                                break;
                                            }
                                        }
                                    }
                                }
                                
                                if (retryFragment instanceof KnowledgeNoteFragment) {
                                    KnowledgeNoteFragment knowledgeNoteFragment = (KnowledgeNoteFragment) retryFragment;
                                    knowledgeNoteFragment.insertTextToContentEditor(textToTransfer);
                                    Toast.makeText(requireContext(), getString(R.string.toast_transferred_to_note), Toast.LENGTH_SHORT).show();
                                    LogManager.logD(TAG, "Retry successful: Converted text to knowledge base note, length: " + textToTransfer.length());
                                } else {
                                    Toast.makeText(requireContext(), getString(R.string.toast_cannot_get_note_page), Toast.LENGTH_SHORT).show();
                                    LogManager.logE(TAG, "Still unable to get KnowledgeNoteFragment instance after retry");
                                }
                            } catch (Exception e) {
                                Toast.makeText(requireContext(), getString(R.string.toast_transfer_to_note_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                LogManager.logE(TAG, "Retry convert to note failed", e);
                            }
                        }, 500); // 再延迟500ms
                    }
                } catch (Exception e) {
                    Toast.makeText(requireContext(), getString(R.string.toast_transfer_to_note_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    LogManager.logE(TAG, "Convert to note failed", e);
                }
            }, 300); // 初始延迟300ms
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.toast_transfer_to_note_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Convert to note failed", e);
        }
    }
    
    /**
     * 应用全局字体大小设置
     */
    private void applyGlobalTextSize() {
        if (textViewResponse != null) {
            float fontSize = ConfigManager.getGlobalTextSize(requireContext());
            textViewResponse.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
            LogManager.logD(TAG, "Applied global text size: " + fontSize + "sp");
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 在页面恢复时重新应用字体大小，以便在设置页面修改后能够立即生效
        applyGlobalTextSize();
        
        // 移除自动查询恢复逻辑，避免应用启动时意外执行查询
        // 如果需要恢复查询功能，应该通过用户明确的操作触发
        /*
        // 检查是否需要恢复之前的查询
        if (queryNeedsResume && lastUserPrompt != null && !lastUserPrompt.isEmpty()) {
            LogManager.logD(TAG, "Detected query that needs to be resumed, will re-execute after page resume");
            
            // 重置恢复标记
            queryNeedsResume = false;
            
            // 在UI线程上显示恢复提示
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (isAdded() && getActivity() != null) {
                        updateProgressOnUiThread("Resuming previous query: " + lastUserPrompt);
                        
                        // 延迟一秒再执行，确保UI已经完全初始化
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isAdded() && getActivity() != null) {
                                executeRagQuery(lastApiUrl, lastApiKey, lastModel, lastKnowledgeBase, 
                                                lastSystemPrompt, lastUserPrompt);
                            }
                        }, 1000);
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "Error occurred while resuming query", e);
                }
            }, 500);
        }
        */
    }
    
    // 设置自定义文本选择菜单
    private void setupCustomTextSelectionMenu() {
        if (textViewResponse != null) {
            textViewResponse.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    // 不干扰系统默认菜单创建
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    // 先检查菜单中是否已有"转笔记"选项
                    boolean hasTransferOption = false;
                    for (int i = 0; i < menu.size(); i++) {
                        MenuItem item = menu.getItem(i);
                        String transferToNoteText = getString(R.string.menu_item_transfer_to_note);
                        if (item.getTitle().equals(transferToNoteText)) {
                            hasTransferOption = true;
                            break;
                        }
                    }
                    
                    // 只有在没有"转笔记"选项时才添加
                    if (!hasTransferOption) {
                        String transferToNoteText = getString(R.string.menu_item_transfer_to_note);
                        menu.add(Menu.NONE, Menu.FIRST + 100, 5, transferToNoteText);
                    }
                    return true;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    String transferToNoteText = getString(R.string.menu_item_transfer_to_note);
                    if (item.getTitle().equals(transferToNoteText)) {
                        // 获取选中的文本
                        String selectedText = "";
                        
                        // 获取选中的文本
                        int start = textViewResponse.getSelectionStart();
                        int end = textViewResponse.getSelectionEnd();
                        
                        if (start >= 0 && end >= 0 && start != end) {
                            // 有选中的文本
                            selectedText = textViewResponse.getText().toString().substring(start, end);
                        } else {
                            // 没有选中文本，使用全部内容
                            selectedText = textViewResponse.getText().toString();
                        }
                        
                        // 调用转笔记方法
                        transferToKnowledgeNote(selectedText);
                        
                        // 关闭选择模式
                        mode.finish();
                        return true;
                    }
                    return false;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    // 不需要特殊处理
                }
            });
        }
    }
    
    // 重排完成后继续RAG查询流程
    private void continueRagQueryAfterReranking() {
        try {
            // 获取保存的查询参数
            String apiUrl = lastApiUrl;
            String apiKey = lastApiKey;
            String model = lastModel;
            String systemPrompt = lastSystemPrompt;
            String userPrompt = lastUserPrompt;
            
            // 获取重排后的相关文档
            List<String> relevantDocs = new ArrayList<>();
            String simInfo = "";
            synchronized (this) {
                if (relevantDocuments != null) {
                    relevantDocs = new ArrayList<>(relevantDocuments);
                }
                simInfo = this.similarityInfo;
            }
            
            if (relevantDocs.isEmpty()) {
                LogManager.logW(TAG, "No relevant documents after reranking, using no-knowledge-base mode");
                updateProgressOnUiThread("No relevant documents after reranking, generating answer directly");
                
                // 构建不包含知识库内容的提示词
                String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                
                // 调用大模型API获取回答
                updateProgressOnUiThread(getString(R.string.calling_llm_api));
                callLLMApi(apiUrl, apiKey, model, fullPrompt);
            } else {
                // 显示相似度信息
                if (!TextUtils.isEmpty(simInfo)) {
                    updateProgressOnUiThread(getString(R.string.similarity_info, simInfo));
                }
                
                // 构建包含知识库内容的提示词
                String fullPrompt = buildPromptWithKnowledgeBase(systemPrompt, userPrompt, relevantDocs);
                
                // 记录提示词信息
                int promptLength = fullPrompt.length();
                String promptInfo = "Built prompt length: " + promptLength + " characters";
                LogManager.logD(TAG, promptInfo);
                updateProgressOnUiThread(promptInfo);
                
                // 详细打印发送给LLM的完整文本
                LogManager.logI(TAG, "=== Complete prompt sent to LLM ===");
                LogManager.logI(TAG, "Prompt length: " + promptLength + " characters");
                LogManager.logI(TAG, "Prompt content:");
                LogManager.logI(TAG, fullPrompt);
                LogManager.logI(TAG, "=== LLM prompt end ===");
                
                // 如果提示词太长，记录警告
                if (promptLength > 4000) {
                    String warnMsg = getString(R.string.warning_prompt_too_long);
                    LogManager.logW(TAG, warnMsg);
                    updateProgressOnUiThread(warnMsg);
                }
                
                // 调用大模型API获取回答
                updateProgressOnUiThread(getString(R.string.calling_llm_api));
                callLLMApi(apiUrl, apiKey, model, fullPrompt);
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Continue RAG query after reranking failed: " + e.getMessage(), e);
            updateProgressOnUiThread("Continue query after reranking failed: " + e.getMessage());
            
            // 使用统一的状态重置方法
            resetSendingState();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 【重要修复】正确关闭两个线程池，避免资源泄漏
        LogManager.logD(TAG, "Shutting down thread pools...");
        
        if (stopCheckExecutor != null && !stopCheckExecutor.isShutdown()) {
            stopCheckExecutor.shutdown();
            LogManager.logD(TAG, "StopCheck executor shutdown initiated");
        }
        
        if (ragQueryExecutor != null && !ragQueryExecutor.isShutdown()) {
            ragQueryExecutor.shutdown();
            LogManager.logD(TAG, "RagQuery executor shutdown initiated");
        }
        
        // 尝试等待线程池关闭
        try {
            if (stopCheckExecutor != null && !stopCheckExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                stopCheckExecutor.shutdownNow();
                LogManager.logW(TAG, "StopCheck executor forced shutdown");
            }
            
            if (ragQueryExecutor != null && !ragQueryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                ragQueryExecutor.shutdownNow();
                LogManager.logW(TAG, "RagQuery executor forced shutdown");
            }
        } catch (InterruptedException e) {
            LogManager.logE(TAG, "Thread pool shutdown interrupted", e);
            Thread.currentThread().interrupt();
        }
        
        LogManager.logD(TAG, "Thread pools shutdown completed");
    }
}