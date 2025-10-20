package com.example.offlineai;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import com.example.offlineai.LogManager;
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
import android.view.WindowManager;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import com.example.offlineai.api.LocalLlmHandler;
import com.example.offlineai.api.LocalLlmAdapter;
import com.example.offlineai.ApiUrlAdapter;
import com.example.offlineai.RerankerHandler;
import com.example.offlineai.AppConstants;
import com.example.offlineai.StateDisplayManager;
import com.example.offlineai.adapter.StateAwareSpinnerAdapter;
import com.example.offlineai.ImageThumbnailAdapter;
import com.example.offlineai.EmbeddingHandler;
import com.example.offlineai.SQLiteVectorDatabaseHandler;
import com.example.offlineai.ConfigManager;
import com.example.offlineai.chat.model.ChatDataItem;
import com.example.offlineai.chat.chatlist.ChatRecyclerViewAdapter;
import com.example.offlineai.chat.chatlist.ChatViewHolders;
import com.example.offlineai.chat.utils.CollapsibleTextParser;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RagQaFragment extends Fragment {

    private static final String TAG = "OfflineAI_RagQa"; // Add TAG for log printing
    private static final String LOG_FILE = "api_log.txt"; // Log file name
    private static final int MAX_IMAGES = 3; // Maximum number of images allowed

    private Spinner spinnerApiUrl;
    private EditText editTextApiKey;
    private Spinner spinnerApiModel;
    private Spinner spinnerKnowledgeBase;
    private EditText editTextSystemPrompt;
    private EditText editTextUserPrompt;
    private Button buttonSendStop;
    private Button buttonNewChat;
    private Spinner spinnerSearchDepth; // Search depth dropdown
    private Spinner spinnerRerankCount; // Rerank count dropdown
    private TextView textViewResponse; // Response text view
    private CheckBox checkBoxThinkingMode; // Thinking mode checkbox
    private RecyclerView recyclerViewImageThumbnails; // Image thumbnail container
    private ImageThumbnailAdapter imageThumbnailAdapter; // Image thumbnail adapter
    // 避免程序化设置复选框状态时触发监听器造成误保存
    private boolean isUpdatingUiFromConfig = false;
    
    // Chat UI components
    private RecyclerView recyclerViewChat; // Chat message list
    private ChatRecyclerViewAdapter chatAdapter; // Chat adapter
    private List<ChatDataItem> chatMessages = new ArrayList<>(); // Chat messages
    
    // Image picker launcher for Android 13+
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    // Document picker launcher for Android 11/12
    private ActivityResultLauncher<String[]> pickDocument;
    // Camera launcher for taking photos
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private Uri cameraCaptureUri; // Temporary URI for camera capture
    // Camera permission launcher
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    
    // Markdown renderer
    private Markwon markwon;
    private final StringBuilder answerBuilder = new StringBuilder();
    private final StringBuilder debugBuilder = new StringBuilder();

    private final AtomicBoolean isSending = new AtomicBoolean(false); // Track the state of the send/stop button with atomic operations
    private static final String CONFIG_FILE = ".config"; // Configuration file name
    private List<String> systemPromptHistory = new ArrayList<>(); // System prompt history
    private Map<String, String> apiKeyMap = new HashMap<>(); // API Key mapping
    
    // Whether there is currently a running RAG query task
    private boolean isTaskRunning = false;
    private boolean isTaskCancelled = false;
    
    // Global stop flag - used to uniformly control the stopping of all models
    private volatile boolean globalStopFlag = false;
    private volatile Future<?> ragTaskFuture; // Track RAG task future for cancellation
    
    /**
     * Static stop flag for cross-module communication
     * Used by Embedding/Tokenizer/Reranker to check if user requested stop
     * This replaces GlobalStopManager with a simpler approach
     */
    public static volatile boolean userRequestedStop = false;

    // keep screen on flag
    private boolean isKeepScreenOn = false;
    // track battery optimization status
    private boolean batteryOptimizationDisabled = false;
    
    // RAG query thread pool
    private ExecutorService ragQueryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQa-Query-Thread");
        t.setDaemon(true);
        return t;
    });
    // Main thread Handler
    private Handler mainHandler;

    // Search result documents
    private List<String> relevantDocuments;
    private String similarityInfo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rag_qa, container, false);
        
        // Initialize UI elements
        spinnerApiUrl = view.findViewById(R.id.spinnerApiUrl);
        editTextApiKey = view.findViewById(R.id.editTextApiKey);
        spinnerApiModel = view.findViewById(R.id.spinnerApiModel);
        spinnerKnowledgeBase = view.findViewById(R.id.spinnerKnowledgeBase);
        editTextSystemPrompt = view.findViewById(R.id.editTextSystemPrompt);
        editTextUserPrompt = view.findViewById(R.id.editTextUserPrompt);
        buttonSendStop = view.findViewById(R.id.buttonSendStop);
        buttonNewChat = view.findViewById(R.id.buttonNewChat);
        spinnerSearchDepth = view.findViewById(R.id.spinnerSearchDepth); // Initialize search depth spinner
        spinnerRerankCount = view.findViewById(R.id.spinnerRerankCount); // Initialize rerank count spinner
        checkBoxThinkingMode = view.findViewById(R.id.checkBoxThinkingModeKey); // Initialize thinking mode checkbox
        recyclerViewImageThumbnails = view.findViewById(R.id.recyclerViewImageThumbnails); // Initialize image thumbnail container
        textViewResponse = view.findViewById(R.id.textViewResponse); // Initialize response text view
        recyclerViewChat = view.findViewById(R.id.recyclerViewChat); // Initialize chat RecyclerView
        
        // Initialize chat RecyclerView and adapter
        chatAdapter = new ChatRecyclerViewAdapter(requireContext());
        chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
        
        // Set transfer to note callback
        chatAdapter.setOnTransferToNoteCallback(text -> {
            transferToKnowledgeNote(text);
            return null; // Return Unit for Kotlin compatibility
        });
        
        // Set image preview callback
        chatAdapter.setOnImagePreviewCallback(imagePath -> {
            showImagePreview(imagePath);
            return null; // Return Unit for Kotlin compatibility
        });
        
        // Set image long press callback (copy/save/share menu using ActionMode)
        chatAdapter.setOnImageLongPressCallback(imagePath -> {
            // Start ActionMode on the RecyclerView (since we don't have direct access to ImageView here)
            // The ActionMode will be triggered from ChatViewHolders instead
            return null; // Return Unit for Kotlin compatibility
        });
        
        recyclerViewChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewChat.setAdapter(chatAdapter);
        LogManager.logD(TAG, "Chat RecyclerView initialized with callbacks");
        
        // Initialize image thumbnail adapter and RecyclerView
        imageThumbnailAdapter = new ImageThumbnailAdapter();
        imageThumbnailAdapter.setContext(requireContext()); // Set context for thumbnail loading
        recyclerViewImageThumbnails.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        recyclerViewImageThumbnails.setAdapter(imageThumbnailAdapter);
        
        // Set image action listener
        imageThumbnailAdapter.setOnImageActionListener(new ImageThumbnailAdapter.OnImageActionListener() {
            @Override
            public void onImageClick(String imagePath, int position) {
                // Show full screen image preview
                showImagePreview(imagePath);
            }
            
            @Override
            public void onImageDelete(String imagePath, int position) {
                // Delete image from list
                imageThumbnailAdapter.removeImage(position);
                // Hide RecyclerView if no images
                if (imageThumbnailAdapter.getImageCount() == 0) {
                    recyclerViewImageThumbnails.setVisibility(View.GONE);
                }
                LogManager.logI(TAG, "Deleted image at position: " + position);
            }
        });
        
        // Add enter key listener for user prompt text box
        editTextUserPrompt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                handleSendStopClick();
                return true;
            }
            return false;
        });
        
        // Initialize search depth spinner
        initializeSearchDepthSpinner();
        
        // Initialize rerank count spinner
        initializeRerankCountSpinner();
        
        // Load API URL list, including custom URLs from configuration
        loadApiUrlList();
        
        // Set initial data for other Spinners
        setupSpinner(spinnerApiModel, new String[]{getString(R.string.common_loading)});
        setupSpinner(spinnerKnowledgeBase, new String[]{getString(R.string.common_loading)});
        
        // Add selection listener for API URL Spinner to automatically load corresponding API Key
        spinnerApiUrl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedApiUrl = parent.getItemAtPosition(position).toString();
                
                // Check if "Add New..." option is selected
                if (selectedApiUrl.equals(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW))) {
                    showAddApiUrlDialog();
                    return;
                }
                
                loadApiKeyForUrl(selectedApiUrl);
                fetchModelsForApi(); // Automatically fetch model list
                
                // Save API URL setting
                ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, selectedApiUrl);
                LogManager.logD(TAG, "Saved API URL: " + selectedApiUrl);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        
        // Add focus change listener for API Key to save API Key when focus is lost
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

        // Add touch listener to fetch model list when model dropdown is clicked
        spinnerApiModel.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                fetchModelsForApi();
            }
            return false; // Allow normal spinner behavior
        });
        
        // Add selection listener for model Spinner to save selected model
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
                // Do nothing
            }
        });
        
        // Add touch listener for knowledge base dropdown
        spinnerKnowledgeBase.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                loadKnowledgeBases();
            }
            return false;
        });
        
        // Add selection listener for knowledge base Spinner to save selected knowledge base
        spinnerKnowledgeBase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKnowledgeBase = parent.getItemAtPosition(position).toString();
                if (!StateDisplayManager.isKnowledgeBaseStatusDisplayText(requireContext(), selectedKnowledgeBase)) {
                    // Save to configuration when valid knowledge base is selected
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, selectedKnowledgeBase);
                    LogManager.logD(TAG, "Saved knowledge base name: " + selectedKnowledgeBase);
                } else {
                    // Save empty string to configuration when status display text is selected
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, "");
                    LogManager.logD(TAG, "Selected status display text, clearing config: " + selectedKnowledgeBase);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        
        // Add focus change listener for system prompt to save system prompt when focus is lost
        editTextSystemPrompt.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String systemPrompt = editTextSystemPrompt.getText().toString();
                // Save regardless of empty or not, ensuring user can correctly save when clearing system prompt
                ConfigManager.setSystemPrompt(requireContext(), systemPrompt);
                LogManager.logD(TAG, "Saved system prompt: " + (systemPrompt.isEmpty() ? "[empty]" : systemPrompt));
            }
        });
        
        // Initialize search depth spinner
        initializeSearchDepthSpinner();
        
        // Add selection listener for search depth spinner
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
                // Do nothing
            }
        });
        
        // Add selection listener for rerank count spinner
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
                // Do nothing
            }
        });
        
        // Add listener for thinking mode checkbox
        checkBoxThinkingMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 程序化更新期间不进行持久化写入，避免误保存与抖动
            if (isUpdatingUiFromConfig) {
                LogManager.logD(TAG, "Ignore checkbox change during config-driven UI update");
                return;
            }
            // Note: no_thinking=TRUE unchecks, false checks
            // So logic needs to be inverted here
            boolean noThinking = !isChecked;
            ConfigManager.setNoThinking(requireContext(), noThinking);
            LogManager.logD(TAG, "Saved thinking mode setting: " + (isChecked ? "enabled" : "disabled"));
        });
        
        // Set button listeners
        buttonSendStop.setOnClickListener(v -> handleSendStopClick());
        buttonNewChat.setOnClickListener(v -> handleNewChatClick());
        
        // Load knowledge base list
        loadKnowledgeBases();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize main thread Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Setup custom action mode for long press on input field (must be after view is created)
        setupInputFieldLongPressMenu();
        
        // Initialize Markdown renderer with full plugin support
        LogManager.logD(TAG, "Initializing Markwon renderer");
        markwon = Markwon.builder(requireContext())
                // Add HTML support
                .usePlugin(HtmlPlugin.create())
                // Add table support
                .usePlugin(TablePlugin.create(requireContext()))
                // Add task list support
                .usePlugin(TaskListPlugin.create(requireContext()))
                // Add strikethrough support
                .usePlugin(StrikethroughPlugin.create())
                // Add image support
                .usePlugin(ImagesPlugin.create())
                // Add link support
                .usePlugin(LinkifyPlugin.create())
                // Add movement method plugin for link clicking support
                .usePlugin(MovementMethodPlugin.create(LinkMovementMethod.getInstance()))
                // Add custom plugin to make inline code use monospace font without background color
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void beforeSetText(@NonNull TextView textView, @NonNull Spanned markdown) {
                        // Enable text selection
                        textView.setTextIsSelectable(true);
                        // Set text color to black for better readability
                        textView.setTextColor(Color.BLACK);
                    }
                    
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        // Configure code block style - using modern IDE-style dark theme
                        builder
                            // Code blocks use dark background, similar to VSCode's One Dark theme
                            .codeBlockBackgroundColor(Color.parseColor("#282c34"))
                            // Code blocks use light text for high contrast
                            .codeBlockTextColor(Color.parseColor("#abb2bf"))
                            // Inline code doesn't use background color, set to transparent
                            .codeBackgroundColor(Color.TRANSPARENT)
                            // Inline code uses bold display for enhanced visual effect
                            .codeTextColor(Color.parseColor("#000000"))
                            // Increase code block padding
                            .codeBlockMargin(16)
                            // Increase block spacing
                            .blockMargin(12)
                            // Set quote block style
                            .blockQuoteColor(Color.parseColor("#5c6bc0"));
                    }
                })
                .build();
                
        LogManager.logD(TAG, "Markwon renderer initialized");
        
        // Initialize text scaling helper class
        textViewResponse = view.findViewById(R.id.textViewResponse);
        
        // Apply global font size
        applyGlobalTextSize();
        
        // Initialize log manager
        LogManager.getInstance(requireContext());
        
        // Set up custom text selection menu
        setupCustomTextSelectionMenu();

        // 加载配置以初始化界面控件状态（包含思考模式复选框）
        loadConfig();
    }
    
    // The following methods are copied from MainActivity and adjusted for Fragment needs
    
    private void setupSpinner(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }
    
    /**
     * Initialize search depth dropdown
     */
    private void initializeSearchDepthSpinner() {
        // Create search depth options list
        List<String> searchDepthOptions = Arrays.asList(
            "0", "1", "2", "5", "6", "8", "10", "15", "20", "25", "30", "35", "40"
        );
        
        // Create adapter
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, searchDepthOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // Set adapter
        spinnerSearchDepth.setAdapter(adapter);
        
        // Set default selected item
        int currentSearchDepth = ConfigManager.getSearchDepth(requireContext());
        String currentDepthStr = String.valueOf(currentSearchDepth);
        int position = searchDepthOptions.indexOf(currentDepthStr);
        if (position >= 0) {
            spinnerSearchDepth.setSelection(position);
        } else {
            // If current value is not in options, default to "10"
            int defaultPosition = searchDepthOptions.indexOf("10");
            if (defaultPosition >= 0) {
                spinnerSearchDepth.setSelection(defaultPosition);
            }
        }
        
        LogManager.logD(TAG, "Search depth Spinner initialized, current value: " + currentSearchDepth);
    }
    
    /**
     * Initialize rerank count dropdown
     */
    private void initializeRerankCountSpinner() {
        // Create rerank count options list
        List<String> rerankCountOptions = Arrays.asList(
            "0", "1", "2", "3", "4", "5", "6", "8", "10", "12", "15", "20", "25", "30"
        );
        
        // Create adapter
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, rerankCountOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // Set adapter
        spinnerRerankCount.setAdapter(adapter);
        
        // Set default selected item
        int currentRerankCount = ConfigManager.getRerankCount(requireContext());
        String currentCountStr = String.valueOf(currentRerankCount);
        int position = rerankCountOptions.indexOf(currentCountStr);
        if (position >= 0) {
            spinnerRerankCount.setSelection(position);
        } else {
            // If current value is not in options, default to "5"
            int defaultPosition = rerankCountOptions.indexOf("5");
            if (defaultPosition >= 0) {
                spinnerRerankCount.setSelection(defaultPosition);
            }
        }
        
        LogManager.logD(TAG, "Rerank count Spinner initialized, current value: " + currentRerankCount);
    }
    
    /**
     * Load configuration file
     */
    private void loadConfig() {
        try {
            // Use ConfigManager to load configuration
            
            // Load API URL
            String apiUrl = ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
            if (!apiUrl.isEmpty()) {
                // Convert original API URL to display text before setting selection
                String apiUrlDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), apiUrl);
                setSpinnerSelection(spinnerApiUrl, apiUrlDisplayText);
            }
            
            // Load model name
            String modelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
            if (!modelName.isEmpty()) {
                // Check if it's a state display text, if so use directly, otherwise may need conversion
                // Since model names are usually saved directly, use it directly here
                setSpinnerSelection(spinnerApiModel, modelName);
            }
            
            // Load knowledge base name
            String knowledgeBase = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, "");
            if (!knowledgeBase.isEmpty()) {
                setSpinnerSelection(spinnerKnowledgeBase, knowledgeBase);
            }
            
            // Load system prompt
            String systemPrompt = ConfigManager.getSystemPrompt(requireContext());
            if (!systemPrompt.isEmpty()) {
                editTextSystemPrompt.setText(systemPrompt);
            }
            
            // Load all API Keys
            Map<String, String> apiKeys = ConfigManager.getAllApiKeys(requireContext());
            if (!apiKeys.isEmpty()) {
                apiKeyMap.putAll(apiKeys);
                LogManager.logD(TAG, "Loaded " + apiKeys.size() + " API Keys");
                
                // Load corresponding API Key based on currently selected API URL
            if (!apiUrl.isEmpty()) {
                loadApiKeyForUrl(apiUrl);
            }
        }
        
        // Search depth already loaded in initializeSearchDepthSpinner
        
        // Rerank count already loaded in initializeRerankCountSpinner
        
        // Load thinking mode setting
        // Note: no_thinking=TRUE unchecks, false checks
            isUpdatingUiFromConfig = true;
            boolean noThinking = ConfigManager.getNoThinking(requireContext());
            checkBoxThinkingMode.setChecked(!noThinking);
            LogManager.logD(TAG, "Loaded thinking mode setting: " + (!noThinking ? "enabled" : "disabled"));
            isUpdatingUiFromConfig = false;
            
            LogManager.logD(TAG, "Configuration loading completed");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load configuration", e);
        }
    }
    
    // Save configuration to file
    private void saveConfig() {
        try {
            // Get currently selected values
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String model = spinnerApiModel.getSelectedItem().toString();
            String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = editTextSystemPrompt.getText().toString();
            
            // Save directly to first-level configuration
            ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, apiUrl);
            ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, model);
            ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, knowledgeBase);
            
            // Save API Key to corresponding URL
            if (!apiKey.isEmpty()) {
                ConfigManager.saveApiKey(requireContext(), apiUrl, apiKey);
                LogManager.logD(TAG, "Saved API Key to URL: " + apiUrl);
            }
            
            // Save system prompt (using first-level item)
            // Save regardless of empty or not, ensuring user can correctly save when clearing system prompt
            ConfigManager.setSystemPrompt(requireContext(), systemPrompt);
            LogManager.logD(TAG, "Saved system prompt: " + (systemPrompt.isEmpty() ? "[empty]" : systemPrompt));
            
            // Search depth automatically saved through spinner selection listener
            // Rerank count automatically saved through spinner selection listener
            
            LogManager.logD(TAG, "Configuration saved to .config file");
            Toast.makeText(requireContext(), getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save configuration", e);
            Toast.makeText(requireContext(), getString(R.string.toast_save_settings_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    // Load corresponding API Key based on API URL
    private void loadApiKeyForUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return;
        }
        
        // Use ConfigManager to get API Key
        String apiKey = ConfigManager.getApiKey(requireContext(), apiUrl);
        if (apiKey != null && !apiKey.isEmpty()) {
            editTextApiKey.setText(apiKey);
            LogManager.logD(TAG, "Loaded API Key for URL: " + apiUrl);
        } else {
            // If no corresponding API Key found, clear input field
            editTextApiKey.setText("");
            LogManager.logD(TAG, "No API Key found for URL: " + apiUrl);
        }
    }
    
    // Set Spinner's selected item
    private void setSpinnerSelection(Spinner spinner, String value) {
        if (spinner == null || value == null || value.isEmpty()) {
            return;
        }
        
        // Get adapter without type conversion
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
    
    // Load API URL list, including custom URLs from configuration
    private void loadApiUrlList() {
        LogManager.logD(TAG, "Starting to load API URL list");
        
        // Merge predefined and custom API URL lists
        List<String> apiUrlsList = new ArrayList<>();
        
        // Add "New..." option as the first item
        apiUrlsList.add(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW));
        
        // Add "Local" option as the second item (fixed item, cannot be deleted)
        String localDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.ApiUrl.LOCAL);
        apiUrlsList.add(localDisplayText);
        
        // Check if api_keys configuration exists in configuration manager
        boolean hasApiKeysConfig = ConfigManager.hasApiKeysConfig(requireContext());
        
        if (hasApiKeysConfig) {
            // api_keys configuration exists: use all values from configuration manager
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
            // api_keys configuration does not exist: use hardcoded defaults from code
            LogManager.logD(TAG, "Using predefined API URLs from resources");
            String[] predefinedApiUrls = getResources().getStringArray(R.array.api_urls);
            String newApiUrlText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW);
            for (String apiUrl : predefinedApiUrls) {
                if (!apiUrl.equals(newApiUrlText) && !apiUrl.equals(AppConstants.ApiUrl.LOCAL) && !apiUrlsList.contains(apiUrl)) {
                    apiUrlsList.add(apiUrl);
                }
            }
        }
        
        // Create and set adapter
        ApiUrlAdapter adapter = new ApiUrlAdapter(
                requireContext(),
                apiUrlsList,
                this::deleteApiUrl,
                (apiUrl, position) -> {
                    // Handle API URL selection event
                    if (apiUrl.equals(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW))) {
                        showAddApiUrlDialog();
                    } else {
                        // Convert display text to internal constant value
                        String internalApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrl);
                        LogManager.logD(TAG, "Selected API URL display: " + apiUrl + ", internal: " + internalApiUrl);
                        
                        // Load corresponding API Key
                        loadApiKeyForUrl(apiUrl);
                        // Save currently selected API URL (using internal constant value)
                        ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, internalApiUrl);
                    }
                },
                spinnerApiUrl
        );
        
        spinnerApiUrl.setAdapter(adapter);
        
        // Set currently selected API URL
        String currentApiUrl = ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
        LogManager.logD(TAG, "Current API URL from config: " + currentApiUrl);
        if (!currentApiUrl.isEmpty()) {
            // Convert current API URL to display text for matching
            String currentApiUrlDisplay = StateDisplayManager.getApiUrlDisplayText(requireContext(), currentApiUrl);
            LogManager.logD(TAG, "Current API URL display text: " + currentApiUrlDisplay);
            
            // Find position of current API URL
            boolean found = false;
            for (int i = 0; i < apiUrlsList.size(); i++) {
                String listItem = apiUrlsList.get(i);
                // Try direct display text matching
                if (listItem.equals(currentApiUrlDisplay)) {
                    spinnerApiUrl.setSelection(i);
                    adapter.setSelectedPosition(i);
                    found = true;
                    LogManager.logD(TAG, "Found API URL match at position " + i + ": " + listItem);
                    break;
                }
                // If display text matching fails, try original value matching (compatibility handling)
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
                // Default to first non-"New" option (usually "Local")
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
     * Delete API URL
     * @param apiUrl API URL to delete
     * @param position Position
     */
    private void deleteApiUrl(String apiUrl, int position) {
        LogManager.logD(TAG, "Delete API URL: " + apiUrl);
        
        // Show confirmation dialog
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_delete_api_url))
                .setMessage(getString(R.string.dialog_message_delete_api_url, apiUrl))
               .setPositiveButton(getString(R.string.common_delete), (dialog, which) -> {
                   // Remove API URL from configuration
                   ConfigManager.removeApiUrl(requireContext(), apiUrl);
                   
                   // Reload API URL list
                   loadApiUrlList();
                   
                   // Notify user
                   Toast.makeText(requireContext(), getString(R.string.toast_api_url_deleted), Toast.LENGTH_SHORT).show();
               })
               .setNegativeButton(getString(R.string.common_cancel), null)
               .show();
    }
    
    // Show add API URL dialog
    private void showAddApiUrlDialog() {
        LogManager.logD(TAG, "Show add API URL dialog");
        
        // Create dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_api_url, null);
        EditText editTextNewApiUrl = dialogView.findViewById(R.id.editTextNewApiUrl);
        EditText editTextNewApiKey = dialogView.findViewById(R.id.editTextNewApiKey);
        
        // Create dialog
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_add_api_url_simple))
               .setView(dialogView)
               .setPositiveButton(getString(R.string.common_add), (dialog, which) -> {
                   // Get input API URL and Key
                   String newApiUrl = editTextNewApiUrl.getText().toString().trim();
                   String newApiKey = editTextNewApiKey.getText().toString().trim();
                   
                   // Validate input
                   if (newApiUrl.isEmpty()) {
                       Toast.makeText(requireContext(), getString(R.string.toast_api_url_empty), Toast.LENGTH_SHORT).show();
                       return;
                   }
                   
                   // Add new API URL and Key
                   ConfigManager.addApiUrl(requireContext(), newApiUrl, newApiKey);
                   
                   // Reload API URL list
                   loadApiUrlList();
                   
                   // Select newly added API URL
                   setSpinnerSelection(spinnerApiUrl, newApiUrl);
                   
                   Toast.makeText(requireContext(), getString(R.string.toast_api_url_added), Toast.LENGTH_SHORT).show();
               })
               .setNegativeButton(getString(R.string.common_cancel), null);
        
        // Show dialog
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    // Load knowledge base list
    private void loadKnowledgeBases() {
        LogManager.logD(TAG, "Starting to load knowledge base list");
        // Show loading status
        setupSpinner(spinnerKnowledgeBase, new String[]{StateDisplayManager.getKnowledgeBaseStatusDisplayText(requireContext(), AppConstants.KNOWLEDGE_BASE_STATUS_LOADING)});
        
        // Get knowledge base path from settings
        String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
        LogManager.logD(TAG, "Retrieved knowledge base path from settings: " + knowledgeBasePath);
        
        // Get knowledge base directory
        File knowledgeBaseDir = new File(knowledgeBasePath);
        if (!knowledgeBaseDir.exists()) {
            LogManager.logD(TAG, "Knowledge base directory does not exist, attempting to create: " + knowledgeBaseDir.getAbsolutePath());
            knowledgeBaseDir.mkdirs();
        }
        
        // Get all subdirectories as knowledge bases
        File[] directories = knowledgeBaseDir.listFiles(File::isDirectory);
        if (directories != null && directories.length > 0) {
            // Add an additional "None" option
            String[] knowledgeBases = new String[directories.length + 1];
            knowledgeBases[0] = getString(R.string.common_none); // First option is "None"
            for (int i = 0; i < directories.length; i++) {
                knowledgeBases[i + 1] = directories[i].getName();
            }
            setupSpinner(spinnerKnowledgeBase, knowledgeBases);
            
            // Load last selected knowledge base from configuration file
            loadLastSelectedKnowledgeBase();
            
            LogManager.logD(TAG, "Loaded " + directories.length + " knowledge bases");
        } else {
            // When no knowledge bases exist, only show "None" option
            setupSpinner(spinnerKnowledgeBase, new String[]{getString(R.string.common_none)});
            LogManager.logD(TAG, "No available knowledge bases found, showing only 'None' option");
        }
    }
    
    // Load last selected knowledge base
    private void loadLastSelectedKnowledgeBase() {
        try {
            // Use ConfigManager to get last selected knowledge base
            String lastKnowledgeBase = ConfigManager.getString(requireContext(), 
                    ConfigManager.KEY_KNOWLEDGE_BASE, "");
            
            LogManager.logD(TAG, "Loading last selected knowledge base from ConfigManager: " + 
                    (lastKnowledgeBase.isEmpty() ? "[empty]" : lastKnowledgeBase));
            
            if (!lastKnowledgeBase.isEmpty()) {
                setSpinnerSelection(spinnerKnowledgeBase, lastKnowledgeBase);
            } else {
                // If no saved knowledge base selection, default to "None" option
                String noneText = getString(R.string.common_none);
                setSpinnerSelection(spinnerKnowledgeBase, noneText);
                LogManager.logD(TAG, "No saved knowledge base selection, defaulting to 'None' option");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to load knowledge base selection: " + e.getMessage(), e);
            Toast.makeText(requireContext(), getString(R.string.toast_load_kb_selection_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void enableKeepScreenOn(boolean enable) {
        if (getActivity() == null) {
            return;
        }

        try {
            if (enable) {
                // enable keep screen on
                getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                isKeepScreenOn = true;
                LogManager.logD(TAG, "Keep screen on enabled");
            } else {
                // disable keep screen on
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                isKeepScreenOn = false;
                LogManager.logD(TAG, "Keep screen on disabled");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to set keep screen on state: " + e.getMessage(), e);
        }
    }

    private void handleSendStopClick() {
        // Use atomic operation to check and set sending state, prevent concurrent clicks
        if (isSending.compareAndSet(false, true)) {
            // --- Start sending --- 
            // request to ignore battery optimizations
            if (getActivity() instanceof MainActivity) {
                batteryOptimizationDisabled = ((MainActivity) getActivity()).requestIgnoreBatteryOptimizationIfNeeded();
                if (batteryOptimizationDisabled) {
                    Utils.showToastSafely(requireContext(), getString(R.string.toast_battery_optimization_requested), Toast.LENGTH_SHORT);
                }
            }

            // enable keep screen on
            enableKeepScreenOn(true);
            // --- Start sending --- 
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String model = spinnerApiModel.getSelectedItem().toString();
            String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = editTextSystemPrompt.getText().toString();
            String userPrompt = editTextUserPrompt.getText().toString();
            
            LogManager.logI(TAG, "[SEND][CLICK] Enter handleSendStopClick - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
            LogManager.logD(TAG, "[SEND][PARAM] apiUrl=" + apiUrl + ", model=" + model + ", kb=" + knowledgeBase + ", sys.len=" + (systemPrompt==null?0:systemPrompt.length()) + ", user.len=" + (userPrompt==null?0:userPrompt.length()) + ", apiKey.len=" + (apiKey==null?0:apiKey.length()));
            LogManager.logD(TAG, "User clicked send button, preparing to send request");
            LogManager.logD(TAG, "Request parameters: API URL=" + apiUrl + ", Model=" + model + ", Knowledge Base=" + knowledgeBase);
            // Debug snapshot at send click (English log)
            try {
                boolean ui_isSending = isSending.get();
                boolean ui_isTaskRunning = isTaskRunning;
                boolean ui_isTaskCancelled = isTaskCancelled;
                boolean ui_globalStopFlag = globalStopFlag;
                boolean ui_globalStopRequested = userRequestedStop;
                String ragFutureState = (ragTaskFuture == null ? "null" : (ragTaskFuture.isDone() ? "done" : "not_done"));
                Thread uiThread = Thread.currentThread();

                LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(requireContext());
                String modelState = String.valueOf(adapter.getModelState());
                boolean llmBusy = adapter.isModelBusy();
                boolean llmRunning = adapter.isInferenceRunning();
                boolean llmShouldStop = adapter.getShouldStop();

                LogManager.logI(
                        TAG,
                        "[SNAPSHOT][SEND] isSending=" + ui_isSending
                            + ", isTaskRunning=" + ui_isTaskRunning
                            + ", isTaskCancelled=" + ui_isTaskCancelled
                            + ", globalStopFlag=" + ui_globalStopFlag
                            + ", GlobalStopManager=" + ui_globalStopRequested
                            + ", ragTaskFuture=" + ragFutureState
                            + ", modelState=" + modelState
                            + ", llmBusy=" + llmBusy
                            + ", llmRunning=" + llmRunning
                            + ", llmShouldStop=" + llmShouldStop
                            + ", uiThread=" + uiThread.getName()
                    );
            } catch (Throwable th) {
                LogManager.logE(TAG, "Error collecting send-click snapshot", th);
            }

            // Cleanup leftover RAG Future before new send
            try {
                if (ragTaskFuture != null) {
                    if (!ragTaskFuture.isDone()) {
                        boolean cancelBeforeSend = ragTaskFuture.cancel(true);
                        LogManager.logW(TAG, "Found leftover RAG Future before new send, cancel issued -> " + cancelBeforeSend);
                    }
                    ragTaskFuture = null;
                    LogManager.logD(TAG, "Cleared ragTaskFuture reference (before new send)");
                }
            } catch (Throwable th) {
                LogManager.logE(TAG, "Error while cleaning previous state before new send", th);
            }

            // Basic validation
            if (userPrompt.trim().isEmpty()) {
                LogManager.logW(TAG, "[SEND][VALIDATION] Failed: empty user prompt");
                restoreSendStateAfterValidationFailure("empty user prompt");
                Toast.makeText(requireContext(), getString(R.string.toast_enter_user_question), Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (apiUrl.trim().isEmpty() || 
                StateDisplayManager.isModelStatusDisplayText(requireContext(), model)) {
                LogManager.logW(TAG, "[SEND][VALIDATION] Failed: invalid api url or model status placeholder");
                restoreSendStateAfterValidationFailure("invalid api url or model status placeholder");
                Toast.makeText(requireContext(), getString(R.string.toast_ensure_api_model_set), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // If not local model, need to check API Key
            if (!AppConstants.ApiUrl.LOCAL.equals(apiUrl) && apiKey.trim().isEmpty()) {
                LogManager.logW(TAG, "[SEND][VALIDATION] Failed: empty api key for non-local model");
                restoreSendStateAfterValidationFailure("empty api key for non-local model");
                Toast.makeText(requireContext(), getString(R.string.toast_enter_api_key), Toast.LENGTH_SHORT).show();
                return;
            }

            // Multimodal pre-check: just log image count if present, defer actual validation to model loading time
            if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
                int imageCount = imageThumbnailAdapter != null ? imageThumbnailAdapter.getImageCount() : 0;
                if (imageCount > 0) {
                    LogManager.logI(TAG, String.format(
                        "[MULTIMODAL] User selected %d image(s), will check model capability after loading",
                        imageCount));
                }
            }

            // Save current configuration
            LogManager.logD(TAG, "[SEND] Persisting configuration selection to storage");
            saveConfig();
            
            // Update button state (isSending has already been set to true in compareAndSet)
            buttonSendStop.setText(getString(R.string.button_stop_with_icon));
            
            // Create user message ChatDataItem
            android.net.Uri imageUri = null;
            if (imageThumbnailAdapter != null && imageThumbnailAdapter.getImageCount() > 0) {
                List<String> imagePaths = imageThumbnailAdapter.getOriginalImageFiles();
                if (imagePaths != null && !imagePaths.isEmpty()) {
                    imageUri = Uri.parse(imagePaths.get(0));
                }
            }
            
            ChatDataItem userMsg;
            if (imageUri != null) {
                userMsg = ChatDataItem.Companion.createImageInputData(getCurrentTime(), userPrompt, imageUri);
                LogManager.logD(TAG, "Created user message with image: " + imageUri);
            } else {
                userMsg = new ChatDataItem(getCurrentTime(), ChatViewHolders.USER, userPrompt);
                LogManager.logD(TAG, "Created user message without image");
            }
            
            chatMessages.add(userMsg);
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            recyclerViewChat.smoothScrollToPosition(chatMessages.size() - 1);
            
            // Create AI message placeholder
            ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
            aiMsg.setLoading(true);
            chatMessages.add(aiMsg);
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            recyclerViewChat.smoothScrollToPosition(chatMessages.size() - 1);
            
            LogManager.logD(TAG, "Chat messages created: user + AI placeholder, total=" + chatMessages.size());
            
            // Clear response area and display processing message (keep for old TextView compatibility)
            if (textViewResponse != null) {
                textViewResponse.setText("");
            }
            
            // [Important Fix] Use dedicated RAG query thread pool to execute query tasks
            // Avoid executing model operations in stop check thread, eliminate concurrency conflicts
            LogManager.logI(TAG, "[SEND] Submitting RAG task to executor - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
            
            // CRITICAL: Check if previous task is stuck (cancelled but not done)
            // This happens when native call (e.g. Reranker loading) is blocking the thread
            if (ragTaskFuture != null && ragTaskFuture.isCancelled() && !ragTaskFuture.isDone()) {
                LogManager.logW(TAG, "[EXECUTOR] Previous task is STUCK (cancelled but not done), recreating executor");
                try {
                    ragQueryExecutor.shutdownNow();
                } catch (Exception e) {
                    LogManager.logE(TAG, "[EXECUTOR] Error shutting down stuck executor: " + e.getMessage());
                }
                ragQueryExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "RagQa-Query-Thread");
                    t.setDaemon(true);
                    return t;
                });
                LogManager.logI(TAG, "[EXECUTOR] Executor recreated successfully");
            }
            
            LogManager.logI(TAG, "[EXECUTOR] Before submit - isShutdown=" + ragQueryExecutor.isShutdown() + ", isTerminated=" + ragQueryExecutor.isTerminated());
            ragTaskFuture = ragQueryExecutor.submit(() -> {
                LogManager.logI(TAG, "[EXECUTOR] Task lambda ENTERED - thread=" + Thread.currentThread().getName());
                // Background thread snapshot at RAG task start (English log)
                try {
                    Thread worker = Thread.currentThread();
                    boolean globalStopRequested = userRequestedStop;

                    LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(requireContext());
                    String modelState = String.valueOf(adapter.getModelState());
                    boolean llmBusy = adapter.isModelBusy();
                    boolean llmRunning = adapter.isInferenceRunning();
                    boolean llmShouldStop = adapter.getShouldStop();

                    LogManager.logI(
                        TAG,
                        "[SNAPSHOT][BG_START] RAG-task start - thread=" + worker.getName()
                            + ", interrupted=" + worker.isInterrupted()
                            + ", userRequestedStop=" + globalStopRequested
                            + ", modelState=" + modelState
                            + ", llmBusy=" + llmBusy
                            + ", llmRunning=" + llmRunning
                            + ", llmShouldStop=" + llmShouldStop
                    );
                } catch (Throwable th) {
                    LogManager.logE(TAG, "Error collecting RAG-task start snapshot", th);
                }

                // [Fix] Only reset local LLM stop flag, do not reset global stop flag
                // Global stop flag can only be reset after confirming stop process completion
                String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
                if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
                    try {
                        LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(requireContext());
                        // Only reset local LLM stop flag, do not affect global stop flag
                        localAdapter.resetStopFlag();
                        LogManager.logD(TAG, "Reset local LLM stop flag in RAG query thread (global stop flag unchanged)");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Error resetting local LLM stop flag", e);
                    }
                }
                
                // Display processing message in background thread, avoid UI thread nested calls
                //updateProgressOnUiThread("Starting knowledge base query...");
                executeRagQuery(apiUrl, apiKey, model, knowledgeBase, systemPrompt, userPrompt);
            });
            LogManager.logI(TAG, "[EXECUTOR] After submit - ragTaskFuture=" + (ragTaskFuture == null ? "null" : "not_null") + ", isDone=" + (ragTaskFuture != null && ragTaskFuture.isDone()) + ", isCancelled=" + (ragTaskFuture != null && ragTaskFuture.isCancelled()));

        } else if (isSending.compareAndSet(true, false)) {
            // --- Stop sending --- 
            // restore battery optimization settings
            if (batteryOptimizationDisabled) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).restoreBatteryOptimization();
                    batteryOptimizationDisabled = false;
                    LogManager.logD(TAG, "Restored battery optimization settings on task cancellation");
                }
            }

            // disable keep screen on
            if (isKeepScreenOn) {
                enableKeepScreenOn(false);
                LogManager.logD(TAG, "Disabled keep screen on on task cancellation");
            }
            // --- Stop sending --- 
            LogManager.logD(TAG, "User clicked stop button");
            LogManager.logI(TAG, "[STOP][CLICK] Enter stop flow - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
            LogManager.logD(TAG, "Current state - isSending: " + isSending.get() + ", isTaskRunning: " + isTaskRunning + ", isTaskCancelled: " + isTaskCancelled);
            
            // Set global stop flag and task cancellation flag
            globalStopFlag = true;
            isTaskCancelled = true;
            
            // Set static stop flag for cross-module communication
            userRequestedStop = true;
            LogManager.logI(TAG, "[STOP] Global stop requested - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", isTaskRunning=" + isTaskRunning + ", ragFuture=" + (ragTaskFuture==null?"null":(ragTaskFuture.isDone()?"done":"not_done")));
            
            LogManager.logD(TAG, "Set global stop flag and task cancellation flag to true");
            
            // Stop all components: tokenizer, embedding, reranker, local LLM
            LogManager.logD(TAG, "Starting to stop all components...");
            
            // 1. Stop local LLM inference
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
            
            // 2. Stop Embedding model (if in use)
            try {
                EmbeddingHandler embeddingHandler = EmbeddingHandler.getInstance(getContext());
                if (embeddingHandler != null) {
                    embeddingHandler.stopInference();
                    LogManager.logI(TAG, "✓ Embedding model stop signal sent");
                } else {
                    LogManager.logD(TAG, "Embedding handler is null");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "✗ Error stopping embedding model", e);
            }
            
            // 3. Stop Reranker model (if in use)
            try {
                RerankerHandler rerankerHandler = RerankerHandler.getInstance(getContext());
                if (rerankerHandler != null) {
                    rerankerHandler.stopInference();
                    LogManager.logI(TAG, "✓ Reranker model stop signal sent");
                } else {
                    LogManager.logD(TAG, "Reranker handler is null");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "✗ Error stopping reranker model", e);
            }
            
            // MNN models have built-in tokenizers, no external tokenizer to stop
            LogManager.logI(TAG, "✓ MNN tokenizers are managed internally");
            
            LogManager.logI(TAG, "All component stop signals have been sent");
            
            // CRITICAL FIX: Use cancel(false) instead of cancel(true) for graceful stop
            // cancel(true) calls Thread.interrupt() which is abrupt interruption
            // cancel(false) only sets flag, lets native call finish naturally, then checks mShouldStop
            // This follows LLM's graceful stop pattern: set flag → wait for natural checkpoint → stop
            if (ragTaskFuture != null && !ragTaskFuture.isDone()) {
                boolean cancelResult = ragTaskFuture.cancel(false);  // ✅ false = no interrupt
                LogManager.logI(TAG, "Requested graceful cancellation for RAG task Future (no thread interrupt), result=" + cancelResult);
            } else {
                LogManager.logD(TAG, "No active RAG task Future to cancel");
            }
            
            // FIX: Stop loading animation immediately when user clicks stop
            if (!chatMessages.isEmpty()) {
                ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
                if (lastMsg.getType() == ChatViewHolders.ASSISTANT && lastMsg.getLoading()) {
                    lastMsg.setLoading(false);
                    chatAdapter.notifyItemChanged(chatMessages.size() - 1);
                    LogManager.logD(TAG, "Stopped loading animation for AI message");
                }
            }
            
            Toast.makeText(requireContext(), getString(R.string.toast_request_stopped), Toast.LENGTH_SHORT).show();
            appendToResponse("\n" + getString(R.string.toast_request_stopped) + "。");
            LogManager.logD(TAG, "Stop processing initiated");
        } else {
            // Prevent duplicate clicks
            LogManager.logD(TAG, "Button click ignored - operation already in progress or completed");
        }
    }
    
    
    // Execute RAG query task
    /**
     * Initialize sending state (called when starting new query)
     * [Fix] Do not reset global stop flag, only initialize task state
     */
    private void initializeSendingState() {
        isTaskRunning = true;
        isTaskCancelled = false;
        // [Important] Do not reset global stop flag, maintain previous stop state
        LogManager.logD(TAG, "Initializing sending state - task running: " + isTaskRunning + ", cancelled: " + isTaskCancelled + ", global stop flag unchanged: " + globalStopFlag);
        LogManager.logI(TAG, "[STATE] initializeSendingState - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
        
        // Reset stop flags for embedding and reranker handlers
        try {
            EmbeddingHandler embeddingHandler = EmbeddingHandler.getInstance(getContext());
            if (embeddingHandler != null) {
                embeddingHandler.resetStopFlag();
                LogManager.logD(TAG, "Embedding handler stop flag reset");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error resetting embedding handler stop flag", e);
        }
        
        try {
            RerankerHandler rerankerHandler = RerankerHandler.getInstance(getContext());
            if (rerankerHandler != null) {
                rerankerHandler.resetStopFlag();
                LogManager.logD(TAG, "Reranker handler stop flag reset");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error resetting reranker handler stop flag", e);
        }
    }
    
    /**
     * Reset all sending states
     * Unified management of all state variable resets, ensuring state consistency
     * [Fix] Only reset global stop flag after confirming all tasks have truly stopped
     */
    private void resetSendingState() {
        LogManager.logI(TAG, "[STATE] resetSendingState enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", isSending=" + isSending.get() + ", isTaskRunning=" + isTaskRunning + ", isTaskCancelled=" + isTaskCancelled + ", globalStopFlag=" + globalStopFlag);
        isTaskRunning = false;
        isTaskCancelled = false;
        
        // [Fix] Only reset global stop flag after confirming stop process completion
        // This ensures stop flag is not reset prematurely
        LogManager.logD(TAG, "Resetting sending state - confirming all tasks stopped before resetting global stop flag");
        globalStopFlag = false;
        userRequestedStop = false;
        LogManager.logD(TAG, "Global stop flag reset to false after confirming all tasks stopped");
        
        // Ensure RAG task future is cleaned up and not leaking (English log)
        if (ragTaskFuture != null) {
            if (!ragTaskFuture.isDone()) {
                LogManager.logW(TAG, "RAG task Future not done during reset, forcing cancel");
                ragTaskFuture.cancel(true);
            }
            ragTaskFuture = null;
            LogManager.logD(TAG, "Cleared ragTaskFuture reference");
        }
        
        isSending.set(false); // Use atomic operation to reset sending state
        
        // Auto-collapse collapsible sections after streaming completes
        if (!chatMessages.isEmpty()) {
            ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
            if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                lastMsg.setShowDebug(false);
                lastMsg.setShowThinking(false);
                lastMsg.setShowPerformance(false);
                lastMsg.setLoading(false);
                
                // Update UI
                if (mainHandler != null && chatAdapter != null) {
                    mainHandler.post(() -> {
                        try {
                            if (getActivity() != null && isAdded() && !isDetached()) {
                                chatAdapter.updateRecentItem(lastMsg);
                                LogManager.logD(TAG, "Auto-collapsed collapsible sections after streaming complete");
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to auto-collapse sections", e);
                        }
                    });
                }
            }
        }
        
        // Update button state on UI thread, add Fragment lifecycle check
        if (mainHandler != null && buttonSendStop != null) {
            mainHandler.post(() -> {
                // Check if Fragment is still attached to Activity
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
    
    // Restore UI/flags/state when validation fails before actual send (English log)
    private void restoreSendStateAfterValidationFailure(String reason) {
        try {
            LogManager.logW(TAG, "[SEND][VALIDATION] Restore state due to validation failure - reason=" + reason + ", thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", isKeepScreenOn=" + isKeepScreenOn + ", batteryOptimizationDisabled=" + batteryOptimizationDisabled + ", isSending=" + isSending.get());
            // rollback keep screen on
            if (isKeepScreenOn) {
                enableKeepScreenOn(false);
            }
            // rollback battery optimization request
            if (batteryOptimizationDisabled) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).restoreBatteryOptimization();
                }
                batteryOptimizationDisabled = false;
            }
            // reset sending state and button
            isSending.set(false);
            if (buttonSendStop != null) {
                buttonSendStop.setText(getString(R.string.button_send));
            }
        } catch (Throwable th) {
            LogManager.logE(TAG, "Error restoring state after validation failure", th);
        }
    }
    private void executeRagQuery(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt) {
        // [Fix] Use dedicated initialization method, do not reset global stop flag
        initializeSendingState();
        
        LogManager.logD(TAG, "Starting RAG query execution with preserved global stop flag state");
        
        // Prepare images if user selected any (convert content:// URI to file paths)
        // Java layer only does URI→file conversion, JNI handles all compression
        java.util.List<String> imagePaths = null;
        if (imageThumbnailAdapter != null && imageThumbnailAdapter.getItemCount() > 0) {
            imagePaths = imageThumbnailAdapter.getOriginalImageFiles();
            if (imagePaths != null && !imagePaths.isEmpty()) {
                LogManager.logI(TAG, "[MULTIMODAL] Prepared " + imagePaths.size() + " image(s), JNI will handle compression");
            } else {
                LogManager.logW(TAG, "[MULTIMODAL] User selected images but conversion failed, proceeding with text-only");
                imagePaths = null; // Ensure null for text-only mode
            }
        }
        
        // Save query parameters for recovery
        lastApiUrl = apiUrl;
        lastApiKey = apiKey;
        lastModel = model;
        lastKnowledgeBase = knowledgeBase;
        lastSystemPrompt = systemPrompt;
        lastUserPrompt = userPrompt;
        
        // Initialize relevant documents list
        synchronized (this) {
            relevantDocuments = new ArrayList<>();
            similarityInfo = "";
        }
        
        // Record start time
        final long startTime = System.currentTimeMillis();
        
        // Get retrieval count
        final int searchDepth = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
        LogManager.logD(TAG, "[RAG] Params saved - kb=" + knowledgeBase + ", searchDepth=" + searchDepth + ", sys.len=" + (systemPrompt==null?0:systemPrompt.length()) + ", user.len=" + (userPrompt==null?0:userPrompt.length()));
        
        // Update UI to show query start
        mainHandler.post(() -> {
            buttonSendStop.setText(getString(R.string.button_stop_with_icon));
            isSending.set(true); // Use atomic operation to set sending state
            // [Fix] Task state already set in initializeSendingState, no need to set again here
            
            // Clear response area
            //updateProgressOnUiThread("Querying knowledge base...");
        });
        
        // Execute query synchronously (avoid concurrent conflicts)
        try {
            // Log query information
                String logMessage = "Executing RAG query:\n" +
                        "API URL: " + apiUrl + "\n" +
                        "Model: " + model + "\n" +
                        "Knowledge Base: " + knowledgeBase + "\n" +
                        "Retrieval Count: " + searchDepth + "\n" +
                        "System Prompt: " + systemPrompt + "\n" +
                        "User Question: " + userPrompt;
                LogManager.logD(TAG, logMessage);
                
                // Update UI to show query log
                mainHandler.post(() -> {
                    //updateProgressOnUiThread("Starting knowledge base query...");
                    //updateProgressOnUiThread("Knowledge base: " + knowledgeBase);
                    //updateProgressOnUiThread("Retrieval count: " + searchDepth);
                    updateProgressOnUiThread("\n " + getString(R.string.debug_info_header) + "\n\n" + getString(R.string.user_question, userPrompt));
                });
                
                // Check if knowledge base query is needed
                String valueNone = getString(R.string.common_none);
                String valueNoAvailableKb = getString(R.string.value_no_available_kb);
                if (!valueNone.equals(knowledgeBase) && !valueNoAvailableKb.equals(knowledgeBase) && searchDepth > 0) {
                    String kbInfo = getString(R.string.log_using_kb_for_query, knowledgeBase);
                    LogManager.logD(TAG, kbInfo);
                    //updateProgressOnUiThread(kbInfo);
                    
                    // Query knowledge base for relevant content - only call queryKnowledgeBase, don't use return value
                    queryKnowledgeBase(knowledgeBase, userPrompt);
                    
                    // Wait for query results - get from relevantDocuments member variable (remove timeout mechanism)
                    List<String> relevantDocs = new ArrayList<>();
                    
                    while (true) {
                        if (isTaskCancelled) {
                            String cancelMsg = "RAG query cancelled by user";
                            LogManager.logD(TAG, cancelMsg);
                            updateProgressOnUiThread(cancelMsg);
                            return;
                        }
                        
                        // Check if query results are available
                        synchronized (this) {
                            if (relevantDocuments != null && !relevantDocuments.isEmpty()) {
                                relevantDocs = new ArrayList<>(relevantDocuments);
                                break;
                            }
                        }
                        
                        // Wait 100 milliseconds
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            LogManager.logE(TAG, "Interrupted while waiting for query results", e);
                            // CRITICAL: Return immediately, don't continue to call LLM
                            return;
                        }
                    }
                    
                    // Check query results
                    if (relevantDocs.isEmpty()) {
                        String warnMsg = "Warning: Knowledge base query returned no relevant documents";
                        LogManager.logW(TAG, warnMsg);
                        updateProgressOnUiThread(warnMsg);
                        
                        // Build prompt without knowledge base content
                        String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                        
                        // Log prompt information
                        int promptLength = fullPrompt.length();
                        String promptInfo = "Prompt length: " + promptLength + " characters";
                        LogManager.logD(TAG, promptInfo);
                        updateProgressOnUiThread(promptInfo);
                        
                        // Log warning if prompt is too long
                        if (promptLength > 4000) {
                            String warnMsg2 = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                            LogManager.logW(TAG, warnMsg2);
                            updateProgressOnUiThread(warnMsg2);
                        }
                        
                        // Calculate query duration
                        long queryTime = System.currentTimeMillis() - startTime;
                        String timeMsg = "Knowledge base query duration: " + queryTime + "ms";
                        LogManager.logD(TAG, timeMsg);
                        updateProgressOnUiThread(timeMsg);
                        
                        // Call large model API to get response
                        updateProgressOnUiThread("Calling LLM API...");
                        callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths);
                    } else {
                        // Get similarity information
                        String simInfo = "";
                        synchronized (this) {
                            simInfo = this.similarityInfo;
                        }
                        
                        // Display similarity information (regardless of debug mode)
                        if (!TextUtils.isEmpty(simInfo)) {
                            updateProgressOnUiThread("Similarity info: " + simInfo);
                        }
                        
                        updateProgressOnUiThread("Found " + relevantDocs.size() + " relevant content items...");
                        
                        // Build prompt with knowledge base content
                        //updateProgressOnUiThread("Building prompt");
                        String fullPrompt = buildPromptWithKnowledgeBase(systemPrompt, userPrompt, relevantDocs);
                        
                        // Log prompt information - only show length, not content
                        int promptLength = fullPrompt.length();
                        String promptInfo = "Built prompt length: " + promptLength + " characters";
                        LogManager.logD(TAG, promptInfo);
                        updateProgressOnUiThread(promptInfo);
                        
                        // Log warning if prompt is too long
                        if (promptLength > 4000) {
                            String warnMsg = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                            LogManager.logW(TAG, warnMsg);
                            updateProgressOnUiThread(warnMsg);
                        }
                        
                        // Calculate query duration
                        long queryTime = System.currentTimeMillis() - startTime;
                        String timeMsg = getString(R.string.kb_query_time, queryTime);
                        LogManager.logD(TAG, timeMsg);
                        updateProgressOnUiThread(timeMsg);
                        
                        // Call large model API to get response
                        updateProgressOnUiThread("Calling LLM API...");
                        callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths);
                    }
                } else {
                    // Not using knowledge base or retrieval count is 0, call large model API directly
                    String directMsg = searchDepth == 0 ? "Search depth is 0, skipping knowledge base query, calling LLM directly" : "No knowledge base configured, calling LLM directly";
                    LogManager.logD(TAG, directMsg);
                    updateProgressOnUiThread(directMsg);
                    updateProgressOnUiThread("Generating response...");
                    
                    // Build prompt without knowledge base content
                    String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                    
                    // Log prompt information - only show length, not content
                    int promptLength = fullPrompt.length();
                    String promptInfo = "Prompt length: " + promptLength + " characters";
                    LogManager.logD(TAG, promptInfo);
                    updateProgressOnUiThread(promptInfo);
                    
                    // Log warning if prompt is too long
                    if (promptLength > 4000) {
                        String warnMsg = "Warning: Prompt length exceeds 4000 characters, may be truncated by model";
                        LogManager.logW(TAG, warnMsg);
                        updateProgressOnUiThread(warnMsg);
                    }
                    
                    // Call large model API to get response
                    updateProgressOnUiThread("Calling LLM API...");
                    callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths);
                }
        } catch (Exception e) {
            String errorMsg = "RAG query task execution failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            
            updateResultOnUiThread("Query failed: " + e.getMessage());
            // CRITICAL: Reset state when exception occurs (before LLM is called)
            resetSendingState();
        } finally {
            // CRITICAL: Check if task was cancelled during embedding/reranker phase
            // If cancelled, reset state immediately (LLM was never called)
            // If not cancelled, LLM callback will handle state reset
            if (isTaskCancelled || globalStopFlag) {
                LogManager.logI(TAG, "Task cancelled during embedding/reranker phase, resetting state immediately");
                resetSendingState();
            } else {
                LogManager.logD(TAG, "executeRagQuery completed, waiting for LLM callback to reset state");
            }
        }
    }
    
    // Query knowledge base to get relevant content
    private List<String> queryKnowledgeBase(String knowledgeBase, String query) {
        List<String> relevantDocs = new ArrayList<>();

        try {
            LogManager.logI(TAG, "[CALL][KB] enter queryKnowledgeBase - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", kb=" + knowledgeBase + ", query.len=" + (query==null?0:query.length()));
            // Check global stop flag
            if (globalStopFlag) {
                LogManager.logD(TAG, "Global stop flag is set, aborting knowledge base query");
                return relevantDocs;
            }
            
            // Check if "None" knowledge base is selected
            String valueNone = getString(R.string.common_none);
            String valueNoAvailableKb = getString(R.string.value_no_available_kb);
            if (valueNone.equals(knowledgeBase) || valueNoAvailableKb.equals(knowledgeBase)) {
                LogManager.logD(TAG, "No knowledge base selected (" + knowledgeBase + "), skipping knowledge base query");
                return relevantDocs; // Return empty list, skip knowledge base query
            }
            
            LogManager.logD(TAG, "Starting knowledge base query: " + knowledgeBase + ", query keywords: " + query);
            //updateProgressOnUiThread("Starting knowledge base query: " + knowledgeBase);

            // Get search depth (from UI input field)
            int searchDepth = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
            LogManager.logD(TAG, "Using UI-configured search depth: " + searchDepth);
            
            // Check if knowledge base name is valid
            if (knowledgeBase == null || knowledgeBase.trim().isEmpty()) {
                String errorMsg = "Error: Knowledge base name is empty";
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            }

            // Check if context is available
            if (!isAdded()) {
                String errorMsg = "Error: Fragment not attached to Activity";
                LogManager.logE(TAG, errorMsg);
                return relevantDocs;
            }

            // Get knowledge base directory - use configured knowledge base path
            String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
            LogManager.logD(TAG, "Retrieved knowledge base path from settings: " + knowledgeBasePath);

            // Get knowledge base directory
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            String pathInfo = "Knowledge base directory path: " + knowledgeBaseDir.getAbsolutePath();
            LogManager.logD(TAG, pathInfo);
            updateProgressOnUiThread(pathInfo);

            // Check if knowledge base directory exists
            if (!knowledgeBaseDir.exists()) {
                String errorMsg = "Error: Knowledge base directory does not exist: " + knowledgeBaseDir.getAbsolutePath();
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            }

            // Check SQLite database file
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

            // Check metadata file
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
                
                // Read metadata file content and log - use separate thread to avoid blocking
                try {
                    // Read file in background thread
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
            
            // Query vector database
            // Declare vectorDb variable outside try block so it can be accessed in catch block
            // Declare as final for use in lambda expressions
            final SQLiteVectorDatabaseHandler[] vectorDbRef = new SQLiteVectorDatabaseHandler[1];
            try {
                // Create SQLite vector database handler
                LogManager.logI(TAG, "Starting to create SQLite vector database handler, knowledge base directory: " + knowledgeBaseDir.getAbsolutePath());
                
                try {
                    vectorDbRef[0] = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                    //updateProgressOnUiThread("Loading SQLite vector database...");

                    // Load vector database
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

                // Get database statistics
                int totalChunks = vectorDbRef[0].getChunkCount();
                String dbInfo = "SQLite vector database loaded successfully, containing " + totalChunks + " text chunks";
                LogManager.logD(TAG, dbInfo);
                updateProgressOnUiThread(dbInfo);

                // Get embedding model directory name
                String embModelName = vectorDbRef[0].getMetadata().getModeldir();
                String embeddingModelPath = ConfigManager.getEmbeddingModelPath(requireContext());
                String foundModelPath = null;
                
                // Check if metadata has modeldir configuration
                String modeldir = vectorDbRef[0].getMetadata().getModeldir();
                if (modeldir != null && !modeldir.isEmpty()) {
                    // Use directory specified by modeldir
                    File modeldirFile = new File(embeddingModelPath, modeldir);
                    if (modeldirFile.exists() && modeldirFile.isDirectory()) {
                        // Search for model files in modeldir
                        File[] files = modeldirFile.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                // MNN models use .mnn format or config.json
                                if (file.isFile() && (file.getName().endsWith(".mnn") || 
                                                     file.getName().equals("config.json"))) {
                                    foundModelPath = file.getAbsolutePath();
                                    LogManager.logD(TAG, "Using model from modeldir: " + foundModelPath);
                                    break;
                                }
                            }
                        }
                    }
                }
                
                // If no model found in modeldir, try using embeddingModel directly
                if (foundModelPath == null) {
                    foundModelPath = new File(embeddingModelPath, embModelName).getAbsolutePath();
                }

                // Check if model file exists
                File modelFile = new File(foundModelPath);
                if (!modelFile.exists()) {
                    LogManager.logD(TAG, "Model file does not exist: " + foundModelPath + ", will try to search in embedding model directory");
                    
                    // Try to find model files in embedding model directory
                    File embeddingModelDir = new File(embeddingModelPath);
                    if (embeddingModelDir.exists() && embeddingModelDir.isDirectory()) {
                        // Get all subdirectories for model selection
                        List<String> availableModels = new ArrayList<>();
                        File[] directories = embeddingModelDir.listFiles(File::isDirectory);
                        if (directories != null) {
                            for (File dir : directories) {
                                // Check if directory contains MNN model files
                                File[] modelFiles = dir.listFiles(file -> 
                                    file.isFile() && (file.getName().endsWith(".mnn") || 
                                                     file.getName().equals("config.json")));
                                if (modelFiles != null && modelFiles.length > 0) {
                                    availableModels.add(dir.getName());
                                }
                            }
                        }
                        
                        // Also check for MNN model files in root directory
                        File[] rootModelFiles = embeddingModelDir.listFiles(file -> 
                            file.isFile() && (file.getName().endsWith(".mnn") || 
                                             file.getName().equals("config.json")));
                        if (rootModelFiles != null && rootModelFiles.length > 0) {
                            availableModels.add("Root Directory");
                        }
                        
                        if (!availableModels.isEmpty()) {
                            // Show model selection dialog
                            selectModelAndContinueQuery(embModelName, availableModels, knowledgeBase, embeddingModelPath, vectorDbRef[0]);
                            // Note: Do not close database here as selectModelAndContinueQuery method will continue using it
                            return relevantDocs; // Return early, waiting for user to select model
                        } else {
                            LogManager.logE(TAG, "No available model files found in embedding model directory");
                            updateProgressOnUiThread("Error: No available model files found in embedding model directory");
                            // Close database connection
                            vectorDbRef[0].closeDatabase();
                            return relevantDocs; // Return early as no models are available
                        }
                    }
                }
                
                // Use EmbeddingHandler to check and load embedding model
                EmbeddingHandler.checkAndLoadEmbeddingModel(
                    requireContext(),
                    vectorDbRef[0],
                    modelFoundPath -> {
                        if (modelFoundPath == null) {
                            // Model does not exist or requires user selection, handled by utility class
                            return;
                        }
                        
                        // Model exists, continue processing
                        String modelInfo = "Using embedding model: " + embModelName + ", path: " + modelFoundPath;
                        LogManager.logD(TAG, modelInfo);
                        updateProgressOnUiThread("Using embedding model: " + embModelName);
                        
                        // Load embedding model
                        try {
                            loadModelAndProcessQuery(modelFoundPath, query, vectorDbRef[0]);
                        } catch (InterruptedException ie) {
                            LogManager.logI(TAG, "Model loading interrupted: " + ie.getMessage());
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ie);
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Model loading failed: " + e.getMessage(), e);
                            throw new RuntimeException(e);
                        }
                    },
                    (selectedModel, selectedModelPath) -> {
                        // User selected a model, continue processing
                        String modelInfo = "Using selected embedding model: " + selectedModel + ", path: " + selectedModelPath;
                        LogManager.logD(TAG, modelInfo);
                        updateProgressOnUiThread("Using selected embedding model: " + selectedModel);
                        
                        // Load embedding model
                        try {
                            loadModelAndProcessQuery(selectedModelPath, query, vectorDbRef[0]);
                        } catch (InterruptedException ie) {
                            LogManager.logI(TAG, "Model loading interrupted: " + ie.getMessage());
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ie);
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Model loading failed: " + e.getMessage(), e);
                            throw new RuntimeException(e);
                        }
                    }
                );
                
                // Note: Do not close database here as loadModelAndProcessQuery method will continue to use it
                return relevantDocs;
            } catch (Exception e) {
                String errorMsg = "Error occurred while querying vector database: " + e.getMessage();
                LogManager.logE(TAG, errorMsg, e);
                if (isAdded()) {
                    updateProgressOnUiThread(errorMsg);
                }
                
                // MNN embedding handler manages model lifecycle automatically
                LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
                
                // Close database connection
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
    
    // Build prompt with knowledge base content
    private String buildPromptWithKnowledgeBase(String systemPrompt, String userPrompt, List<String> relevantDocs) {
        StringBuilder fullPrompt = new StringBuilder();
        
        LogManager.logD(TAG, "Building prompt with knowledge base content, found " + relevantDocs.size() + " relevant documents");
        
        // Add system prompt
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
            LogManager.logD(TAG, "Added system prompt, length: " + systemPrompt.length());
        } else {
            LogManager.logD(TAG, "System prompt is empty");
        }
        
        // Add knowledge base content
        if (!relevantDocs.isEmpty()) {
            fullPrompt.append("The following is information related to the question:\n");
            
            for (int i = 0; i < relevantDocs.size(); i++) {
                String docContent = relevantDocs.get(i);
                if (docContent == null || docContent.trim().isEmpty()) {
                    LogManager.logW(TAG, "Document #" + (i + 1) + " content is empty, skipped");
                    continue;
                }
                
                // No longer limit text length, display complete content
                fullPrompt.append("Document").append(i + 1).append(":\n").append(docContent).append("\n\n");
                LogManager.logD(TAG, "Added document #" + (i + 1) + ", length: " + docContent.length());
            }
        } else {
            fullPrompt.append("No information related to the question was found.\n\n");
            LogManager.logW(TAG, "No relevant documents found, prompting model with no relevant information");
        }
        
        // Add user question
        fullPrompt.append(userPrompt);
        
        // Record final prompt length
        int promptLength = fullPrompt.length();
        LogManager.logD(TAG, "Final prompt length: " + promptLength + " characters");
        
        return fullPrompt.toString();
    }
    
    // Build prompt without knowledge base content
    private String buildPromptWithoutKnowledgeBase(String systemPrompt, String userPrompt) {
        StringBuilder fullPrompt = new StringBuilder();
        
        // Add system prompt
        if (!systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
        }
        
        // Add user question
        fullPrompt.append(userPrompt);
        
        return fullPrompt.toString();
    }
    
    // Call LLM API to get answer
    private void callLLMApi(String apiUrl, String apiKey, String model, String prompt) {
        // Delegate to the version with image support (no images)
        callLLMApi(apiUrl, apiKey, model, prompt, null);
    }
    
    // Call LLM API to get answer (with image support)
    private void callLLMApi(String apiUrl, String apiKey, String model, String prompt, java.util.List<String> imagePaths) {
        try {
            // Check global stop flag and task cancelled flag
            if (globalStopFlag || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped/cancelled, aborting LLM API call (globalStopFlag=" + globalStopFlag + ", isTaskCancelled=" + isTaskCancelled + ")");
                resetSendingState();
                return;
            }
            
            LogManager.logD(TAG, "Starting to call LLM API: " + apiUrl);
        LogManager.logD(TAG, "Using model: " + model);
        LogManager.logD(TAG, "Prompt length: " + prompt.length() + " characters");
            
            // Add connection info without clearing previous debug info
            //appendToResponse("Connecting to API server...");
            
            // Safety check: ensure Fragment is attached to Context
            if (!isAdded()) {
                String errorMsg = "Error: Fragment not attached to Context, cannot call API";
                LogManager.logE(TAG, errorMsg);
                updateResultOnUiThread(errorMsg);
                return;
            }
            
            Context context = getContext();
            if (context == null) {
                String errorMsg = "Error: Context is null, cannot call API";
                LogManager.logE(TAG, errorMsg);
                updateResultOnUiThread(errorMsg);
                return;
            }
            
            // Record start time
            final long startTime = System.currentTimeMillis();
            
            // Create callback interface instance
            com.example.offlineai.api.LlmApiAdapter.ApiCallback callback = new com.example.offlineai.api.LlmApiAdapter.ApiCallback() {
                // In the onSuccess method, perform a complete Markdown rendering
                @Override
                public void onSuccess(String response) {
                    LogManager.logI(TAG, "[CALL][LLM] onSuccess enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
                    // Handle complete response
                    LogManager.logD(TAG, "API call successful, duration: " + (System.currentTimeMillis() - startTime) + "ms");
                    LogManager.logD(TAG, "Response length: " + response.length() + " characters");

                    // Check Fragment lifecycle state
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle success, Fragment not attached to Activity");
                        return;
                    }
                    
                    // Perform final Markdown rendering in UI thread
                    mainHandler.post(() -> {
                        try {
                            // Check Fragment state again
                            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                                LogManager.logW(TAG, "Cannot update UI in success callback, Fragment not attached");
                                return;
                            }
                            
                            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
                            if (textViewResponse != null) {
                                // Get currently displayed content
                                String currentText = textViewResponse.getText().toString();
                                
                                // Check and fix code blocks
                                if (hasIncompleteCodeBlock(currentText)) {
                                    currentText = fixCodeBlocks(currentText);
                                }
                                
                                // Use Markwon for final rendering
                                markwon.setMarkdown(textViewResponse, currentText);
                                
                                // Ensure text is selectable
                                textViewResponse.setTextIsSelectable(true);
                                
                                // Ensure links are clickable
                                textViewResponse.setMovementMethod(LinkMovementMethod.getInstance());
                                
                                LogManager.logD(TAG, "Final Markdown rendering completed");
                            }
                            
                            // Use unified state reset method
                            resetSendingState();
                            LogManager.logD(TAG, "Task completed, all states reset");
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Final Markdown rendering failed", e);
                            // Use unified state reset method
                            resetSendingState();
                        }
                    });
                }
                
                // StringBuilder for accumulating streaming responses
                private final StringBuilder responseBuilder = new StringBuilder();
                // Track whether model response title has been added
                private final boolean[] modelTitleAdded = {false};
                // Last displayed response content
                private final String[] lastDisplayedResponse = {""};
                // Detect if it's a Huawei device
                private static boolean isHuaweiDevice() {
                    return Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                           Build.BRAND.toLowerCase().contains("huawei") ||
                           Build.BRAND.toLowerCase().contains("honor");
                }
                
                // Character change threshold, changes smaller than this value won't trigger UI updates
                private static final int MIN_CHAR_CHANGE = 5;
                // Last UI update time
                private long lastUpdateTime = System.currentTimeMillis();
                // Update interval time (milliseconds)
                private static final long UPDATE_INTERVAL = 100;

                // In onStreamingData method, use simple setText method
                @Override
                public void onStreamingData(final String chunk) {
                    // Token-level logging removed to reduce log spam
                    // Check Fragment lifecycle state
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle streaming data, Fragment not attached to Activity");
                        return;
                    }
                    
                    // Check global stop flag
                    if (globalStopFlag) {
                        LogManager.logD(TAG, "Global stop flag is set, ignoring streaming data");
                        return;
                    }
                    
                    // Log received data chunk
                    //LogManager.logD(TAG, "Received data chunk: [" + chunk + "]");
                    
                    // Update chat message with streaming chunk
                    updateChatMessage(chunk);
                    
                    // Accumulate response content (for old TextView compatibility)
                    responseBuilder.append(chunk);
                    final String fullContent = responseBuilder.toString();
                    
                    // Update content in UI thread using plain text method
                    getActivity().runOnUiThread(() -> {
                        try {
                            // Check Fragment state again
                            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                                LogManager.logW(TAG, "Cannot update UI in streaming callback, Fragment not attached");
                                return;
                            }
                            
                            // Get text view and scroll view
                            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
                            ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
                            if (textViewResponse == null || scrollView == null) return;
                            
                            // Check current scroll position
                            boolean wasAtBottom = isScrolledToBottom(scrollView);
                            
                            // Prepare complete content to display
                            String displayContent;
                            long currentTime = System.currentTimeMillis();
                            
                            // If receiving data for the first time, add model response title
                            if (!modelTitleAdded[0]) {
                                modelTitleAdded[0] = true;
                                String currentText = textViewResponse.getText().toString();
                                displayContent = currentText.isEmpty() 
                                    ? "\n\n---\n\n## " + getString(R.string.model_response) + "\n\n" + fullContent 
                                    : currentText + "\n\n---\n\n## " + getString(R.string.model_response) + "\n\n" + fullContent;
                            } else {
                                // Check if content change is large enough or time interval is long enough
                                int charDiff = fullContent.length() - lastDisplayedResponse[0].length();
                                long timeDiff = currentTime - lastUpdateTime;
                                
                                // If change is not large enough and time interval is not long enough, don't update UI
                                if (charDiff < MIN_CHAR_CHANGE && timeDiff < UPDATE_INTERVAL) {
                                    return;
                                }
                                
                                // Get current content and append new content
                                String currentText = textViewResponse.getText().toString();
                                
                                // Find the position of last displayed content and replace with new complete content
                                int lastResponseIndex = currentText.lastIndexOf(lastDisplayedResponse[0]);
                                if (lastResponseIndex >= 0) {
                                    displayContent = currentText.substring(0, lastResponseIndex) + fullContent;
                                } else {
                                    // If last content cannot be found, directly append new content
                                    String incrementalContent = fullContent.substring(lastDisplayedResponse[0].length());
                                    displayContent = currentText + incrementalContent;
                                }
                            }
                            
                            // Update last displayed content and time
                            lastDisplayedResponse[0] = fullContent;
                            lastUpdateTime = currentTime;
                            
                            // Update content using plain text method
                            textViewResponse.setText(displayContent);
                            
                            // If was at bottom before, scroll to bottom
                            if (wasAtBottom) {
                                scrollToBottom(scrollView);
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to update streaming response UI", e);
                        }
                    });
                }
                




                // These variables are no longer used, but kept for potential reference by other methods
                

                
                
                /**
                 * Record Markdown markers in content
                 * @param content Content to check
                 */
                private void logMarkdownMarkers(String content) {
                    if (content == null || content.isEmpty()) return;
                    
                    // Check common Markdown markers
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
                 * Check if there are incomplete code blocks in content
                 * @param content Content to check
                 * @return true if there are incomplete code blocks, false otherwise
                 */
                private boolean hasIncompleteCodeBlock(String content) {
                    if (content == null || content.isEmpty()) return false;
                    
                    // Count code block markers
                    int count = 0;
                    int index = -1;
                    
                    // Use more precise method to detect code block markers
                    while ((index = content.indexOf("```", index + 1)) != -1) {
                        // Check if this is a real code block start/end marker, not text nested in other code blocks
                        boolean isRealCodeBlockMarker = true;
                        
                        // Check if this marker is at the beginning of a line or preceded by a newline
                        if (index > 0) {
                            char prevChar = content.charAt(index - 1);
                            // If the previous character is not a newline or space, it might not be a real code block marker
                            if (prevChar != '\n' && prevChar != ' ' && prevChar != '\t') {
                                // Further check, if there's a newline before, it might be a real code block marker
                                int prevNewlineIndex = content.lastIndexOf('\n', index - 1);
                                if (prevNewlineIndex == -1 || index - prevNewlineIndex > 4) { // Allow small indentation
                                    isRealCodeBlockMarker = false;
                                }
                            }
                        }
                        
                        if (isRealCodeBlockMarker) {
                            count++;
                        }
                    }
                    
                    // If the number of code block markers is odd, there are incomplete code blocks
                    return count % 2 != 0;
                }
                
                /**
                 * Count occurrences of a specified pattern in a string
                 * @param content Content to check
                 * @param pattern Pattern to search for
                 * @return Number of pattern occurrences
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
                 * Fix code block markers in content
                 * @param content Content to fix
                 * @return Fixed content
                 */
                private String fixCodeBlocks(String content) {
                    if (content == null || content.isEmpty()) return content;
                    
                    // Record original content length
                    int originalLength = content.length();
                    
                    // Check and fix code block markers
                    StringBuilder sb = new StringBuilder(content);
                    
                    // Calculate the number and positions of code block markers
                    List<Integer> positions = new ArrayList<>();
                    int index = -1;
                    while ((index = content.indexOf("```", index + 1)) != -1) {
                        positions.add(index);
                    }
                    
                    // If the number of code block markers is odd, add an end marker
                    if (positions.size() % 2 != 0) {
                        LogManager.logD(TAG, "Detected incomplete code block, adding end marker");
                        sb.append("\n```");
                    }
                    
                    // Check the number of inline code markers
                    int inlineCount = 0;
                    index = -1;
                    while ((index = content.indexOf("`", index + 1)) != -1) {
                        // Skip code block markers
                        boolean isCodeBlockMarker = false;
                        for (int pos : positions) {
                            if (Math.abs(index - pos) < 3) { // Allow small error
                                isCodeBlockMarker = true;
                                break;
                            }
                        }
                        if (!isCodeBlockMarker) {
                            inlineCount++;
                        }
                    }
                    
                    // If the number of inline code markers is odd, add an end marker
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
                 * Check if the scroll view has scrolled to the bottom
                 * @param scrollView Scroll view to check
                 * @return true if scrolled to bottom, false otherwise
                 */
                private boolean isScrolledToBottom(ScrollView scrollView) {
                    if (scrollView == null) return false;
                    int scrollY = scrollView.getScrollY();
                    int height = scrollView.getHeight();
                    int scrollViewBottom = scrollY + height;
                    int contentHeight = scrollView.getChildAt(0).getHeight();
                    // Allow 20 pixels error for more reliable bottom detection
                    return (scrollViewBottom >= contentHeight - 20);
                }
                
                /**
                 * Scroll the scroll view to the bottom
                 * @param scrollView View to scroll
                 */
                private void scrollToBottom(ScrollView scrollView) {
                    if (scrollView == null) return;
                    scrollView.post(() -> {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    });
                }
                
                @Override
                public void onError(String errorMessage) {
                    LogManager.logI(TAG, "[CALL][LLM] onError enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", err.len=" + (errorMessage==null?0:errorMessage.length()));
                    // Handle error
                    LogManager.logE(TAG, "API call failed, duration: " + (System.currentTimeMillis() - startTime) + "ms, error: " + errorMessage);
                    
                    // Check Fragment lifecycle state
                    if (getActivity() == null || !isAdded() || isDetached()) {
                        LogManager.logW(TAG, "Cannot handle error, Fragment not attached to Activity");
                        return;
                    }
                    
                    try {
                        // Display error message
                        updateResultOnUiThread("API call failed: " + errorMessage);
                        
                        // Use unified state reset method
                        resetSendingState();
                        LogManager.logD(TAG, "Task error, all states reset");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to handle error callback", e);
                    }
                }
            };
            
            // Create LlmApiAdapter instance and call API
            // imagePaths is passed from method parameter (prepared by caller)
            // JNI will: 1) Load model 2) Check multimodal support 3) Compress to correct size 4) Use or ignore images
            com.example.offlineai.api.LlmApiAdapter apiAdapter = new com.example.offlineai.api.LlmApiAdapter(context);
            apiAdapter.callLlmApi(apiUrl, apiKey, model, prompt, imagePaths, callback);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to call LLM API", e);
            updateResultOnUiThread("API call failed: " + e.getMessage());
            resetSendingState();
            LogManager.logD(TAG, "Task exception, all states reset");
        }
    }
    
    // Save parameters of last query for recovery
    private String lastApiUrl;
    private String lastApiKey;
    private String lastModel;
    private String lastKnowledgeBase;
    private String lastSystemPrompt;
    private String lastUserPrompt;
    private boolean queryNeedsResume = false;
    
    // Update progress information on UI thread with retry mechanism
    private void updateProgressOnUiThread(String progress) {
        updateProgressOnUiThreadWithRetry(progress, 3); // Maximum 3 retries
    }
    
    // Update progress as plain text (no Markdown rendering) to avoid highlighting issues
    private void updateProgressPlainText(String progress) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            return;
        }
        
        getActivity().runOnUiThread(() -> {
            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                return;
            }
            
            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
            ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
            if (textViewResponse == null || scrollView == null) return;
            
            // Append as plain text without Markdown rendering
            CharSequence currentText = textViewResponse.getText();
            String newText = currentText.length() == 0 ? progress : currentText + progress;
            textViewResponse.setText(newText);
            
            // Force UI refresh to prevent buffering (ensure each dot appears immediately)
            textViewResponse.invalidate();
            textViewResponse.requestLayout();
            
            // Auto scroll
            scrollView.post(() -> {
                try {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to scroll to bottom", e);
                }
            });
        });
    }
    
    // UI update method with retry mechanism
    private void updateProgressOnUiThreadWithRetry(String progress, int retryCount) {
        if (retryCount <= 0) {
            LogManager.logW(TAG, "UI update retry attempts exhausted, giving up");
            return;
        }
        
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update UI, Fragment not attached to Activity, will retry in 1 second (remaining retries: " + retryCount + ")");
            // Remove automatic query recovery logic to avoid automatic query execution on app startup
            // queryNeedsResume = true; // Mark query needs recovery
            
            // Retry after 1 second
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                updateProgressOnUiThreadWithRetry(progress, retryCount - 1);
            }, 1000);
            return;
        }
        
        getActivity().runOnUiThread(() -> {
            // Check Fragment state again
            if (getActivity() == null || !isAdded() || isDetached()) {
                LogManager.logW(TAG, "Cannot update UI in progress callback, Fragment not attached");
                return;
            }
            appendToResponse(progress);
        });
    }
    
    // Completely rewritten append content method to solve scrolling and Markdown rendering issues
    private void appendToResponse(String text) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot append response, Fragment not attached to Activity");
            return;
        }
        
        // Check if already in UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already in UI thread, execute directly
            performAppendToResponse(text);
        } else {
            // Not in UI thread, switch to UI thread
            getActivity().runOnUiThread(() -> performAppendToResponse(text));
        }
    }
    
    private void performAppendToResponse(String text) {
        try {
            // Check Fragment state
            if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                LogManager.logW(TAG, "Cannot append response in UI thread, Fragment not attached");
                return;
            }
            
            // Get text view and scroll view
            TextView textViewResponse = getView().findViewById(R.id.textViewResponse);
            ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
            if (textViewResponse == null || scrollView == null) return;
            
            // Save current text
            CharSequence currentText = textViewResponse.getText();
            
            // Prepare new text
            String newText;
            if (currentText.length() == 0) {
                newText = text;
            } else {
                newText = currentText + "\n" + text;
            }
            
            try {
                // Optimized Markdown rendering: set text first, then render
                textViewResponse.setText(newText);
                if (markwon != null) {
                    markwon.setMarkdown(textViewResponse, newText);
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "Markdown rendering failed, using plain text", e);
                textViewResponse.setText(newText);
            }
            
            // Auto scroll to bottom (delayed execution to ensure content is rendered)
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
    
    // Update result on UI thread (replace all content)
    private void updateResultOnUiThread(String result) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update result, Fragment not attached to Activity");
            return;
        }
        
        mainHandler.post(() -> {
            try {
                // Check Fragment state again
                if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                    LogManager.logW(TAG, "Cannot update result in UI thread, Fragment not attached");
                    return;
                }
                
                // Get result text view
                TextView textViewResult = getView().findViewById(R.id.textViewResponse);
                if (textViewResult == null) return;
                
                // Get scroll view
                ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
                if (scrollView == null) return;
                
                // Add debug logs to view text content and Markdown rendering process
                //LogManager.logD(TAG, "DEBUG-updateResult: Text content to render: " + result);
        //LogManager.logD(TAG, "DEBUG-updateResult: Is Markwon instance null: " + (markwon == null ? "yes" : "no"));
                
                try {
                    // Try using different ways to render Markdown
                    // Set plain text first, then try rendering
                    textViewResult.setText(result);
                    
                    // Optimized Markdown rendering logic
                    // Always perform Markdown rendering to ensure correct format display
                    try {
                        // Use full Markdown rendering
                        Spanned spanned = markwon.toMarkdown(result);
                        markwon.setParsedMarkdown(textViewResult, spanned);
                        //LogManager.logD(TAG, "DEBUG-updateResult: Using full Markdown rendering");
                        
                        // Ensure links are clickable
                        if (textViewResult.getMovementMethod() == null) {
                            textViewResult.setMovementMethod(LinkMovementMethod.getInstance());
                        }
                    } catch (Exception e) {
                        // If rendering fails, fallback to simple text setting
                        textViewResult.setText(result);
                        //LogManager.logE(TAG, "DEBUG-updateResult: Markdown rendering failed, fallback to plain text", e);
                    }
                    
                    // Check TextView properties
                    //LogManager.logD(TAG, "DEBUG-updateResult: TextView text selectable state: " + textViewResult.isTextSelectable());
            //LogManager.logD(TAG, "DEBUG-updateResult: TextView MovementMethod: " + textViewResult.getMovementMethod());
                } catch (Exception e) {
                    LogManager.logE(TAG, "DEBUG-updateResult: Markdown rendering failed", e);
                    // If advanced API fails, try using basic method
                    markwon.setMarkdown(textViewResult, result);
                }
                
                // Use multi-level delay to ensure scrolling to bottom
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
    
    // Get model list
    
    // Get model list
    private void fetchModelsForApi() {
        String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
        String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
        String apiKey = editTextApiKey.getText().toString();
        
        // Get currently saved model name for restoring selection
        String savedModelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
        
        // Show loading state
        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_LOADING)});
        
        // If it's a local model, get available model list from local model directory
        String localDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.ApiUrl.LOCAL);
        
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            
            // Get model path from configuration
            String modelPath = ConfigManager.getModelPath(requireContext());
            
            File modelDir = new File(modelPath);
            
            if (!modelDir.exists() || !modelDir.isDirectory()) {
                LogManager.logE(TAG, "Model directory does not exist: " + modelPath);
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_DIRECTORY_NOT_EXIST)});
                Toast.makeText(requireContext(), getString(R.string.toast_model_dir_not_exist, modelPath), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Get all subdirectories in the model directory (each subdirectory represents a model)
            File[] modelDirs = modelDir.listFiles(File::isDirectory);
            
            if (modelDirs == null || modelDirs.length == 0) {
                LogManager.logE(TAG, "No models found in model directory: " + modelPath);
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NOT_FOUND)});
                Toast.makeText(requireContext(), getString(R.string.toast_no_model_found, modelPath), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Extract model names
            List<String> modelsList = new ArrayList<>();
            for (File dir : modelDirs) {
                String modelName = dir.getName();
                modelsList.add(modelName);
            }
            
            // Update UI
            if (modelsList.isEmpty()) {
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NO_AVAILABLE)});
            } else {
                setupSpinner(spinnerApiModel, modelsList.toArray(new String[0]));
                // Restore user's previous selection
                if (!savedModelName.isEmpty()) {
                    setSpinnerSelection(spinnerApiModel, savedModelName);
                    LogManager.logD(TAG, "Restoring local model selection: " + savedModelName);
                }
            }
            
            LogManager.logD(TAG, "Successfully got local model list: " + modelsList.size() + " models");
            return;
        }
        
        // If it's an online model, API Key is required
        if (apiUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.toast_set_api_first), Toast.LENGTH_SHORT).show();
            setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
            return;
        }
        
        // Build request URL, adjust according to different APIs
        String modelsUrl = apiUrl;
        if (!modelsUrl.endsWith("/")) {
            modelsUrl += "/";
        }
        modelsUrl += "models";
        
        // Create request headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        
        // Use Volley to send request
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, modelsUrl, null,
            response -> {
                try {
                    // Parse response, extract model list
                    JSONArray modelsArray = response.getJSONArray("data");
                    List<String> modelsList = new ArrayList<>();
                    
                    for (int i = 0; i < modelsArray.length(); i++) {
                        JSONObject modelObj = modelsArray.getJSONObject(i);
                        String modelId = modelObj.getString("id");
                        modelsList.add(modelId);
                    }
                    
                    // Update UI
                    if (modelsList.isEmpty()) {
                        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NO_AVAILABLE)});
                    } else {
                        setupSpinner(spinnerApiModel, modelsList.toArray(new String[0]));
                        // Restore user's previous selection
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
        
        // Add request to queue
        Volley.newRequestQueue(requireContext()).add(request);
    }
    
    // Handle new chat button click
    private void handleNewChatClick() {
        // New chat debug log removed
        
        updateProgressOnUiThread("");
        editTextUserPrompt.setText("");
        
        // Clear chat messages
        if (chatAdapter != null) {
            chatAdapter.reset();
            LogManager.logD(TAG, "Chat messages cleared");
        }
        
        // Clear answer box
        if (textViewResponse != null) {
            textViewResponse.setText("");
        }
        
        // Reset send/stop button state
        if (isSending.get()) {
            buttonSendStop.setText(getString(R.string.button_send));
            isSending.set(false); // Use atomic operation to reset sending state
            if (isTaskRunning) {
                isTaskCancelled = true;
            }
        }
        
        // Reset model memory - clear KV cache and conversation history
        // [Fix] Move local model operations to background thread to avoid main thread calling model
        String selectedApiDisplay = spinnerApiUrl.getSelectedItem().toString();
        String selectedApi = StateDisplayManager.getApiUrlFromDisplayText(getContext(), selectedApiDisplay);
        
        if (AppConstants.ApiUrl.LOCAL.equals(selectedApi)) {
            // Execute local model reset operation in background thread
            ragTaskFuture = ragQueryExecutor.submit(() -> {
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
            // For online large models, clear local conversation history and state
            // New chat debug log removed
            // Online large models are usually stateless, each request is independent
            // Here mainly clear local UI state and cache
        }
        
        // New chat debug log removed
    }
    
    // Create context menu
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
    }
    
    // Handle context menu item click
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return super.onContextItemSelected(item);
    }


    
    // Load model and process query
    private void loadModelAndProcessQuery(String foundModelPath, String query, SQLiteVectorDatabaseHandler vectorDb) throws InterruptedException {
        try {
            // Start debug section for progress info
            updateChatMessage("<debug>\n");
            
            // Show knowledge base name
            String kbName = spinnerKnowledgeBase != null && spinnerKnowledgeBase.getSelectedItem() != null 
                ? spinnerKnowledgeBase.getSelectedItem().toString() 
                : "Unknown";
            updateChatMessage("Knowledge Base: " + kbName + "\n");
            
            // Update progress
            updateChatMessage("Loading embedding model...");
            
            // Get embedding handler instance
            EmbeddingHandler embeddingHandler = EmbeddingHandler.getInstance(requireContext());
            
            // No Thread.isInterrupted() check - using graceful stop pattern (flag only)
            
            LogManager.logI(TAG, "[LOCK] About to call embeddingHandler.loadModel() - thread=" + Thread.currentThread().getName());
            if (!embeddingHandler.loadModel(foundModelPath)) {
                throw new Exception("Failed to load embedding model");
            }
            LogManager.logI(TAG, "[LOCK] embeddingHandler.loadModel() returned - thread=" + Thread.currentThread().getName());
            
            // Show embedding model name
            String embeddingModelName = embeddingHandler.getEmbeddingModel();
            if (embeddingModelName != null) {
                updateChatMessage("\nEmbedding Model: " + embeddingModelName);
            }
            
            // CRITICAL: Check stop flags after loading
            // If stopped, DON'T throw exception - model is already loaded and should be kept!
            // Just skip inference and return gracefully
            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped after embedding loading - model loaded successfully, skipping inference");
                updateProgressOnUiThread("Model loaded, operation stopped by user");
                return;  // ✅ Graceful return, keep model loaded for next call
            }
            
            // Get model vector dimension
            int embeddingDimension = embeddingHandler.getEmbeddingDimension();
            LogManager.logD(TAG, "Model vector dimension: " + embeddingDimension);
            updateChatMessage("\nModel vector dimension: " + embeddingDimension);

            // Check if vector dimension matches knowledge base
            int dbDimension = vectorDb.getMetadata().getEmbeddingDimension();
            LogManager.logD(TAG, "Knowledge base vector dimension: " + dbDimension + ", model vector dimension: " + embeddingDimension);
            updateChatMessage("\nKnowledge base vector dimension: " + dbDimension);

            if (dbDimension > 0 && dbDimension != embeddingDimension) {
                String warningMsg = "Warning: Vector dimensions do not match! Knowledge base dimension: " + dbDimension + ", model dimension: " + embeddingDimension;
                LogManager.logW(TAG, warningMsg);
                updateChatMessage("\n" + warningMsg);
                updateChatMessage("\nThis may cause search failure, recommend rebuilding knowledge base or using matching model");
            }


            // MNN embedding handler manages model lifecycle automatically
            LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
            //updateProgressOnUiThread("Mark model as in use to prevent auto-unloading");
            
            try {
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting before vector generation");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Generate query vector
                updateChatMessage("\nGenerating query vector...");
                
                // Get user query
                String userQuery = editTextUserPrompt.getText().toString().trim();
                
                // Generate vector
                float[] queryVector;
                try {
                    updateChatMessage(".");
                    queryVector = embeddingHandler.computeEmbedding(userQuery);
                    updateChatMessage(".");
                } catch (InterruptedException ie) {
                    LogManager.logI(TAG, "Embedding computation interrupted by user");
                    updateProgressOnUiThread("Operation stopped by user");
                    // CRITICAL: Set task cancelled flag to prevent further processing
                    isTaskCancelled = true;
                    // Close database before returning
                    try {
                        vectorDb.close();
                        LogManager.logD(TAG, "Vector database closed after embedding interruption");
                    } catch (Exception ex) {
                        LogManager.logE(TAG, "Failed to close vector database: " + ex.getMessage(), ex);
                    }
                    return;
                }
                
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting after vector generation");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Record vector debug information
                String vectorDebugInfo = "Query vector generated, dimension: " + queryVector.length;
                
                // Only display basic vector information in non-debug mode
                boolean isDebugMode = ConfigManager.getBoolean(requireContext(), ConfigManager.KEY_DEBUG_MODE, false);
                
                updateChatMessage("\n" + vectorDebugInfo);

                
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting before database search");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Search similar text blocks
                updateChatMessage("\nSearching similar text blocks...");
                
                // Get retrieval count setting
                int retrievalCount = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
                
                // Search similar text blocks
                List<SQLiteVectorDatabaseHandler.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
                
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting after database search");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Display retrieval result similarity - show immediately with all scores
                if (!searchResults.isEmpty()) {
                    StringBuilder similarityInfo = new StringBuilder("\nRetrieval Similarity (");
                    similarityInfo.append(searchResults.size()).append(" results): ");
                    for (int i = 0; i < searchResults.size(); i++) {
                        similarityInfo.append(String.format("%.3f", searchResults.get(i).similarity));
                        if (i < searchResults.size() - 1) {
                            similarityInfo.append(", ");
                        }
                    }
                    // Use chat message update for smooth streaming display
                    updateChatMessage(similarityInfo.toString());
                    LogManager.logI(TAG, "Retrieval similarity scores: " + similarityInfo.toString());
                }
                
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting before reranking");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Check if reranking is needed
                int rerankCount = ConfigManager.getRerankCount(requireContext());
                String rerankerModelPath = getRerankerModelPath(vectorDb);
                
                if (rerankCount > 0 && rerankerModelPath != null && !rerankerModelPath.isEmpty()) {
                    // Use reranker model
                    LogManager.logI(TAG, "Using reranker model with rerank count: " + rerankCount);
                    updateChatMessage("\nUsing reranker model to optimize results...");
                    try {
                        LogManager.logI(TAG, "[DEBUG] About to call processWithReranker - query.len=" + userQuery.length() + ", results=" + searchResults.size() + ", path=" + rerankerModelPath + ", vectorDb=" + (vectorDb != null ? "not_null" : "null"));
                        processWithReranker(userQuery, searchResults, rerankerModelPath, vectorDb);
                        LogManager.logI(TAG, "[DEBUG] processWithReranker returned successfully");
                    } catch (InterruptedException ie) {
                        LogManager.logI(TAG, "Reranker process interrupted: " + ie.getMessage());
                        // Re-throw to stop the entire flow
                        throw ie;
                    }
                } else {
                    // Do not use reranking, directly process vector search results
                    if (rerankCount == 0) {
                        LogManager.logI(TAG, "Rerank count is 0, skipping reranking and using vector search results directly");
                        updateChatMessage("\nRerank count is 0, skipping reranking");
                    } else {
                        LogManager.logD(TAG, "No reranker model configured, using vector search results");
                    }
                    processVectorSearchResults(searchResults);
                    
                    // Close debug section
                    updateChatMessage("\n</debug>\n");
                    
                    // [Fix] No longer call continueRagQueryAfterReranking to avoid duplicate LLM API calls
                    // executeRagQuery method will wait for relevantDocuments to be set and then call callLLMApi itself
                    // continueRagQueryAfterReranking();
                }

                // MNN embedding handler manages model lifecycle automatically
                LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
                
                // Close database connection after search is complete
                try {
                    vectorDb.close();
                    LogManager.logD(TAG, "Vector database closed successfully after query");
                } catch (Exception ex) {
                    LogManager.logE(TAG, "Failed to close vector database: " + ex.getMessage(), ex);
                }
                
                // Get API information
                String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
                String apiKey = editTextApiKey.getText().toString();
                String apiModel = spinnerApiModel.getSelectedItem().toString();
                
                // No longer directly call API, let executeRagQuery method handle it
                // This avoids duplicate API calls
                // callLLMApi(apiUrl, apiKey, apiModel, buildPromptWithKnowledgeBase(editTextSystemPrompt.getText().toString(), userQuery, relevantDocs));
            } catch (InterruptedException ie) {
                // Task was interrupted - re-throw to stop the entire flow
                LogManager.logI(TAG, "Query processing interrupted: " + ie.getMessage());
                throw ie;
            } catch (Exception e) {
                String errorMsg = "Query processing failed: " + e.getMessage();
                LogManager.logE(TAG, errorMsg, e);
                updateProgressOnUiThread(errorMsg);
            }
        } catch (InterruptedException ie) {
            // Task was interrupted - re-throw to stop the entire flow
            LogManager.logI(TAG, "Model loading interrupted: " + ie.getMessage());
            // Close database before re-throwing
            try {
                vectorDb.close();
                LogManager.logD(TAG, "Vector database closed after interruption");
            } catch (Exception ex) {
                LogManager.logE(TAG, "Failed to close vector database: " + ex.getMessage(), ex);
            }
            throw ie;
        } catch (Exception e) {
            String errorMsg = "Model loading failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
            
            // Close database
            try {
                vectorDb.close();
                LogManager.logD(TAG, "Vector database closed");
            } catch (Exception ex) {
                LogManager.logE(TAG, "Failed to close vector database: " + ex.getMessage(), ex);
            }
        }
        LogManager.logD(TAG, "[MODEL_OP] Model operation finished, waiting for LLM callback to reset state");
    }
    
    /**
     * Get reranker model path
     */
    private String getRerankerModelPath(SQLiteVectorDatabaseHandler vectorDb) {
        try {
            // Get reranker model directory from database metadata
            String rerankerDir = vectorDb.getMetadata().getRerankerdir();
            if (rerankerDir == null || rerankerDir.trim().isEmpty()) {
                LogManager.logD(TAG, "No reranker model directory configured in database metadata");
                return null;
            }
            
            // Get reranker model root path
            String rerankerBasePath = ConfigManager.getRerankerModelPath(requireContext());
            
            // Build complete reranker model path
            File rerankerModelDir = new File(rerankerBasePath, rerankerDir);
            if (!rerankerModelDir.exists() || !rerankerModelDir.isDirectory()) {
                LogManager.logW(TAG, "Reranker model directory does not exist: " + rerankerModelDir.getAbsolutePath());
                return null;
            }
            
            // Find MNN reranker model files (config.json or .mnn)
            File[] modelFiles = rerankerModelDir.listFiles(file -> 
                file.isFile() && (file.getName().equals("config.json") || file.getName().endsWith(".mnn")));
            
            if (modelFiles == null || modelFiles.length == 0) {
                LogManager.logW(TAG, "No MNN reranker model files found in reranker model directory: " + rerankerModelDir.getAbsolutePath());
                return null;
            }
            
            // Return the first found model file path
            String modelPath = modelFiles[0].getAbsolutePath();
            LogManager.logD(TAG, "Found reranker model: " + modelPath);
            return modelPath;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get reranker model path: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Process search results using reranker model
     */
    private void processWithReranker(String query, List<SQLiteVectorDatabaseHandler.SearchResult> searchResults, 
                                   String rerankerModelPath, SQLiteVectorDatabaseHandler vectorDb) throws InterruptedException, Exception {
        LogManager.logI(TAG, "[DEBUG] processWithReranker ENTERED - query.len=" + (query != null ? query.length() : "null"));
        try {
            LogManager.logI(TAG, "[DEBUG] Step 1: Checking stop flags");
            // Check global stop flag and task cancelled flag
            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped/cancelled, aborting reranking process");
                updateProgressOnUiThread("Operation stopped by user");
                throw new InterruptedException("Task stopped/cancelled before reranking");
            }
            
            LogManager.logI(TAG, "[DEBUG] Step 2: About to get RerankerHandler instance");
            // Get reranker handler
            RerankerHandler rerankerHandler = RerankerHandler.getInstance(requireContext());
            LogManager.logI(TAG, "[DEBUG] Step 3: Got RerankerHandler instance - " + (rerankerHandler != null ? "not_null" : "null"));
            
            LogManager.logI(TAG, "[DEBUG] Step 4: About to check isModelLoaded()");
            // Load model if not loaded - MNN one-stop solution
            boolean modelLoaded = rerankerHandler.isModelLoaded();
            LogManager.logI(TAG, "[DEBUG] Step 5: isModelLoaded() returned - " + modelLoaded);
            
            if (!modelLoaded) {
                updateProgressOnUiThread("Loading reranker model...");
                
                // Check stop flags before loading (no Thread.isInterrupted - graceful stop pattern)
                if (userRequestedStop || isTaskCancelled) {
                    LogManager.logI(TAG, "Task stopped before reranker loading, aborting");
                    throw new InterruptedException("Task stopped before reranker loading");
                }
                
                LogManager.logI(TAG, "[LOCK] About to call rerankerHandler.loadModel() - thread=" + Thread.currentThread().getName());
                // MNN will auto-detect reranker type from config.json
                boolean loaded = rerankerHandler.loadModel(rerankerModelPath);
                LogManager.logI(TAG, "[LOCK] rerankerHandler.loadModel() returned - thread=" + Thread.currentThread().getName());
                
                // CRITICAL: Check stop flags after loading
                // If stopped, DON'T throw exception - model is already loaded and should be kept!
                // Just return gracefully - setInstruction will be called on next use
                if (userRequestedStop || isTaskCancelled) {
                    LogManager.logI(TAG, "Task stopped after reranker loading - model loaded successfully, skipping inference");
                    updateProgressOnUiThread("Model loaded, operation stopped by user");
                    return;  // ✅ Graceful return, model ready for next call
                }
                
                if (!loaded) {
                    LogManager.logE(TAG, "Failed to load reranker model, aborting");
                    updateProgressOnUiThread("Reranker model loading failed");
                    throw new Exception("Failed to load reranker model");
                }
            }
            
            // CRITICAL: Set instruction OUTSIDE the if block
            // Only set if not already set or different (like demo: set once, use multiple times)
            String requiredInstruction = "Given a web search query, retrieve relevant passages that answer the query";
            if (rerankerHandler.getInstruction() == null || !rerankerHandler.getInstruction().equals(requiredInstruction)) {
                LogManager.logI(TAG, "Setting reranker instruction for the first time or updating");
                rerankerHandler.setInstruction(requiredInstruction);
            } else {
                LogManager.logI(TAG, "Reranker instruction already set, skipping");
            }
            
            // Extract document text
            List<String> documents = new ArrayList<>();
            for (SQLiteVectorDatabaseHandler.SearchResult result : searchResults) {
                documents.add(result.text);
            }
            
            // Calculate topK value
            int rerankCount = ConfigManager.getRerankCount(requireContext());
            int retrievalCount = ConfigManager.getSearchDepth(requireContext());
            int topK = Math.min(searchResults.size(), retrievalCount);
            
            LogManager.logI(TAG, "Starting rerank: query=" + query + ", documents.size()=" + documents.size() + ", topK=" + topK + ", rerankCount=" + rerankCount);
            
            // CRITICAL FIX: Execute reranking synchronously (like embedding) to prevent state desync
            // Check stop flag before reranking (graceful stop pattern - no thread interrupt)
            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Stop requested before reranking, aborting");
                throw new InterruptedException("Task stopped before reranking");
            }
            
            updateChatMessage("\nReranking documents");
            
            // Show reranker model name
            String modelPath = rerankerHandler.getCurrentModelPath();
            if (modelPath != null) {
                String modelName = new File(modelPath).getName();
                updateChatMessage("\nReranker Model: " + modelName);
            }
            
            // Set progress callback to show real-time progress
            rerankerHandler.setProgressCallback((current, total) -> {
                // Show progress with dots - simple and intuitive
                updateChatMessage(".");
            });
            
            // Set score callback to show real-time reranking scores
            rerankerHandler.setScoreCallback((index, score, text) -> {
                // Log each score as it's computed for debugging
                LogManager.logD(TAG, String.format("Rerank progress [%d]: score=%.4f", index + 1, score));
            });
            
            // Perform reranking synchronously (MNN reranker is thread-safe and synchronized)
            // Now processes one document at a time - can be interrupted and shows progress
            List<RerankerHandler.RerankResult> rerankedResults = rerankerHandler.rerank(query, documents, topK);
            
            // Clear callbacks
            rerankerHandler.setProgressCallback(null);
            rerankerHandler.setScoreCallback(null);
            
            // Check stop flag after reranking (graceful stop pattern)
            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Stop requested after reranking, aborting result processing");
                throw new InterruptedException("Task stopped after reranking");
            }
            
            LogManager.logI(TAG, "Reranking successful, result count: " + rerankedResults.size());
            
            // Process results synchronously (no need for UI thread switch in worker thread)
            processRerankedResults(rerankedResults);
            
        } catch (InterruptedException ie) {
            // Task was interrupted - re-throw to stop the entire flow
            LogManager.logI(TAG, "Reranking interrupted, stopping entire flow: " + ie.getMessage());
            throw ie;
        } catch (Exception e) {
            LogManager.logE(TAG, "Reranking processing exception: " + e.getMessage(), e);
            updateProgressOnUiThread("Reranking processing exception");
            // Re-throw to stop the entire flow
            throw e;
        }
    }
    
    /**
     * Process vector search results (without reranking)
     */
    private void processVectorSearchResults(List<SQLiteVectorDatabaseHandler.SearchResult> searchResults) {
        // CRITICAL: Check stop flag before processing
        if (userRequestedStop || isTaskCancelled) {
            LogManager.logI(TAG, "Task stopped/cancelled, aborting processVectorSearchResults");
            updateProgressOnUiThread("Operation stopped by user");
            return;
        }
        
        try {
            // Extract relevant documents
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder();
            
            for (int i = 0; i < searchResults.size(); i++) {
                SQLiteVectorDatabaseHandler.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.text);
                
                // Log detailed information
                String resultInfo = "Similarity: " + result.similarity + ", Text: " + result.text.substring(0, Math.min(50, result.text.length())) + "...";
                LogManager.logD(TAG, resultInfo);


            }

            // Save similarity information
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
     * Process reranking results
     */
    private void processRerankedResults(List<RerankerHandler.RerankResult> rerankedResults) {
        // CRITICAL: Check stop flag before processing
        if (userRequestedStop || isTaskCancelled) {
            LogManager.logI(TAG, "Task stopped/cancelled, aborting processRerankedResults");
            updateProgressOnUiThread("Operation stopped by user");
            return;
        }
        
        try {
            // Print detailed reranking results - show all results without limiting quantity
            LogManager.logI(TAG, "=== Reranking Results Details ===");
            for (int i = 0; i < rerankedResults.size(); i++) {
                RerankerHandler.RerankResult result = rerankedResults.get(i);
                LogManager.logI(TAG, String.format("Rerank #%d: score=%.6f, originalIndex=%d, textPreview=%s", 
                    i + 1, result.score, result.originalIndex, 
                    result.text.substring(0, Math.min(100, result.text.length())) + "..."));
            }
            LogManager.logI(TAG, "=== Reranking Results Details End ===");
            
            // Get actual rerank count limit
            int rerankCount = ConfigManager.getRerankCount(requireContext());
            int actualResultCount = Math.min(rerankedResults.size(), rerankCount);
            LogManager.logI(TAG, "Actually using top " + actualResultCount + " reranked results for answer generation");
            
            // Extract reranked documents - only use top rerankCount results
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder();
            
            for (int i = 0; i < actualResultCount; i++) {
                RerankerHandler.RerankResult result = rerankedResults.get(i);
                relevantDocs.add(result.text);

                // Add to progress display - show rerank number and score
                similarityInfoBuilder.append(String.format("%.4f", result.score));
                if (i < actualResultCount - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            // Display reranker results immediately
            String rerankerScores = "\nReranker Similarity (" + actualResultCount + " results): " + similarityInfoBuilder.toString();
            updateChatMessage(rerankerScores);
            LogManager.logI(TAG, "Reranker scores: " + rerankerScores);

            // Save rerank information
            synchronized (this) {
                this.similarityInfo = "Reranked Results - " + similarityInfoBuilder.toString();
                this.relevantDocuments = relevantDocs;
            }
            
            LogManager.logD(TAG, "Reranked results processing completed, actual document count used: " + relevantDocs.size());
            
            updateChatMessage("\nReranking optimization completed, found " + relevantDocs.size() + " relevant contents");
            
            // Close debug section
            updateChatMessage("\n</debug>\n");
            
            // [Fix] No longer call continueRagQueryAfterReranking to avoid duplicate LLM API calls
            // executeRagQuery method will wait for relevantDocuments to be set and then call callLLMApi itself
            // continueRagQueryAfterReranking();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to process reranked results: " + e.getMessage(), e);
            updateProgressOnUiThread("Failed to process reranked results: " + e.getMessage());
        }
    }
    
    // Show model selection dialog
    private void selectModelAndContinueQuery(String originalModel, List<String> availableModels, String knowledgeBase, String embeddingModelPath, SQLiteVectorDatabaseHandler vectorDb) {
        // Ensure running on UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // If not on UI thread, switch to UI thread
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> selectModelAndContinueQuery(originalModel, availableModels, knowledgeBase, embeddingModelPath, vectorDb));
            return;
        }
        
        // If no available models, show error message and prompt user to add models
        if (availableModels.isEmpty()) {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
            builder.setTitle(getString(R.string.dialog_title_embedding_model_not_found))
                   .setMessage(getString(R.string.dialog_message_embedding_model_not_found, embeddingModelPath, originalModel))
                   .setPositiveButton(getString(R.string.common_ok), null)
                   .show();
            return;
        }
        
        // Create dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_model_selection, null);
        Spinner spinnerModels = dialogView.findViewById(R.id.spinnerModels);
        CheckBox checkBoxRemember = dialogView.findViewById(R.id.checkBoxRemember);
        TextView textViewInfo = dialogView.findViewById(R.id.textViewInfo);
        
        // Set prompt information
        String infoText = "Original model not found: " + originalModel + "\nPlease select a replacement model from the available models below:";
        textViewInfo.setText(infoText);
        
        // Set model list
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, availableModels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModels.setAdapter(adapter);
        
        // If original model is in the list, select it
        int originalModelIndex = availableModels.indexOf(originalModel);
        if (originalModelIndex >= 0) {
            spinnerModels.setSelection(originalModelIndex);
        }
        
        // Check if there is a saved model mapping (use unified format)
        String savedMapping = ConfigManager.getModelMapping(requireContext(), "model_" + originalModel, null);
        
        if (savedMapping != null && !savedMapping.isEmpty()) {
            // Find the position of saved mapping model in the list
            int savedModelIndex = availableModels.indexOf(savedMapping);
            if (savedModelIndex >= 0) {
                spinnerModels.setSelection(savedModelIndex);
                checkBoxRemember.setChecked(true);
            }
        }
        
        // Create dialog
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dialog_title_select_embedding_model))
               .setView(dialogView)
               .setCancelable(false)
               .setPositiveButton("OK", (dialog, which) -> {
                   // Get selected model
                   String selectedModel = (String) spinnerModels.getSelectedItem();
                   
                   // If "Remember this choice" is checked, save mapping
                   if (checkBoxRemember.isChecked()) {
                       ConfigManager.setModelMapping(requireContext(), "model_" + originalModel, selectedModel);
                   }
                   
                   // Show loading prompt
                   updateProgressOnUiThread("Preparing model...");
                   
                   // Execute time-consuming operations in background thread to avoid UI freezing
                   new Thread(() -> {
                       try {
                           // Continue executing RAG query task
                           continueQueryWithSelectedModel(selectedModel, knowledgeBase, embeddingModelPath, vectorDb);
                       } catch (Exception e) {
                           LogManager.logE(TAG, "Error processing model selection", e);
                           updateProgressOnUiThread("Error: Error processing model selection: " + e.getMessage());
                       }
                   }).start();
               })
               .setNegativeButton(new StateDisplayManager(requireContext()).getButtonDisplay(AppConstants.BUTTON_TEXT_CANCEL), (dialog, which) -> {
                   // User cancelled model selection, show prompt
                   updateProgressOnUiThread("Model selection cancelled");
               });
        
        // Show dialog
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    // Continue executing RAG query task
    private void continueQueryWithSelectedModel(String selectedModel, String knowledgeBase, String embeddingModelPath, SQLiteVectorDatabaseHandler vectorDb) {
        // Get embedding model path
        String foundModelPath = null;
        boolean modelFound = false;
        
        String rootDirectoryText = getString(R.string.embedding_model_root_directory);
        if (selectedModel.equals(rootDirectoryText)) {
            // Search for model files in root directory
            File embeddingModelDir = new File(embeddingModelPath);
            File[] files = embeddingModelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    // MNN models use .mnn format or config.json
                    if (file.isFile() && (file.getName().endsWith(".mnn") || 
                                         file.getName().equals("config.json"))) {
                        foundModelPath = file.getAbsolutePath();
                        modelFound = true;
                        
                        // Update metadata modeldir to empty string (indicating use of root directory)
                        vectorDb.getMetadata().setModeldir("");
                        vectorDb.saveDatabase();
                        LogManager.logD(TAG, "Updated metadata, modeldir set to empty (using root directory)");
                        break;
                    }
                }
            }
        } else {
            // Use selected directory
            File selectedDir = new File(embeddingModelPath, selectedModel);
            if (selectedDir.exists() && selectedDir.isDirectory()) {
                File[] files = selectedDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        // MNN models use .mnn format or config.json
                        if (file.isFile() && (file.getName().endsWith(".mnn") || 
                                             file.getName().equals("config.json"))) {
                            foundModelPath = file.getAbsolutePath();
                            modelFound = true;
                            
                            // Update metadata modeldir to selected directory
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
        
        // Save model mapping
        ConfigManager.setModelMapping(requireContext(), "model_" + vectorDb.getMetadata().getModeldir(), selectedModel);
        
        // Display model information
        String modelInfo = "Using embedding model: " + selectedModel + ", path: " + foundModelPath;
        LogManager.logD(TAG, modelInfo);
        updateProgressOnUiThread(getString(R.string.using_embedding_model, selectedModel));
        
        // Load embedding model
        updateProgressOnUiThread(getString(R.string.loading_embedding_model));
        
        // Use EmbeddingHandler to load model synchronously (MNN is fast enough)
        EmbeddingHandler embeddingHandler = EmbeddingHandler.getInstance(requireContext());
        
        try {
            if (!embeddingHandler.loadModel(foundModelPath)) {
                throw new Exception("Failed to load embedding model");
            }
            
            updateProgressOnUiThread(getString(R.string.embedding_model_loaded_success, embeddingHandler.getEmbeddingModel()));
            LogManager.logI(TAG, "Embedding model loaded successfully");
            
        } catch (Exception e) {
            String errorMsg = "Error: Failed to load embedding model: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
            return;
        }

        // Generate query vector
        try {
            updateProgressOnUiThread("Generating query vector...");
            
            // Get user query
            String userQuery = editTextUserPrompt.getText().toString().trim();
            
            // Generate vector
            float[] queryVector = embeddingHandler.computeEmbedding(userQuery);
            
            // Query vector anomaly handling
            if (queryVector != null && queryVector.length > 0) {
                // Detect query vector anomalies
                VectorAnomalyHandler.AnomalyResult anomalyResult = VectorAnomalyHandler.detectAnomalies(queryVector, -1);
                
                if (anomalyResult.isAnomalous) {
                    LogManager.logW(TAG, String.format("Query vector anomaly detected: %s (severity: %.2f) - %s", 
                            anomalyResult.type.name(), anomalyResult.severity, anomalyResult.description));
                    
                    // Repair query vector anomaly
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
                
                // Final query vector validation
                VectorAnomalyHandler.AnomalyResult finalCheck = VectorAnomalyHandler.detectAnomalies(queryVector, -1);
                if (finalCheck.isAnomalous && finalCheck.severity > 0.8f) {
                    LogManager.logE(TAG, String.format("Critical query vector anomaly remains after repair: %s", finalCheck.description));
                    // For critical anomalies, generate a random unit vector as fallback
                    queryVector = VectorAnomalyHandler.generateRandomUnitVector(queryVector.length);
                    LogManager.logW(TAG, "Generated random unit vector as fallback for query");
                    updateProgressOnUiThread("Critical query vector anomaly, using fallback vector");
                }
            }
            
            // Record vector debug information
            String vectorDebugInfo = "Query vector generated, dimension: " + queryVector.length;
            updateProgressOnUiThread(vectorDebugInfo);
            
            // Search similar text blocks
            updateProgressOnUiThread("Searching for similar text blocks...");
            
            // Get retrieval count setting
            int retrievalCount = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
            
            // Search similar text blocks
            List<SQLiteVectorDatabaseHandler.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
            
            // Extract relevant documents
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder("Found similar text blocks:\n");
            for (int i = 0; i < searchResults.size(); i++) {
                SQLiteVectorDatabaseHandler.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.text);
                
                // Record detailed information to log
                String resultInfo = "Similarity: " + result.similarity + ", text: " + result.text.substring(0, Math.min(50, result.text.length())) + "...";
                LogManager.logD(TAG, resultInfo);

                // Add to progress display - only show match number and similarity value, not text content
                similarityInfoBuilder.append("Match").append(i + 1).append(": ").append(String.format("%.4f", result.similarity));
                similarityInfoBuilder.append("\n");
            }

            // Display similarity information
            if (!searchResults.isEmpty()) {
                updateProgressOnUiThread(similarityInfoBuilder.toString());
            } else {
                updateProgressOnUiThread("Warning: Knowledge base query returned no relevant documents");
            }

            // MNN embedding handler manages model lifecycle automatically
            LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
            
            // Get API information
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String apiModel = spinnerApiModel.getSelectedItem().toString();
            
            // No longer directly call API, let executeRagQuery method handle it
            // This avoids duplicate API calls
            // callLLMApi(apiUrl, apiKey, apiModel, buildPromptWithKnowledgeBase(editTextSystemPrompt.getText().toString(), userQuery, relevantDocs));
            
        } catch (Exception e) {
            String errorMsg = "Query processing failed: " + e.getMessage();
            LogManager.logE(TAG, errorMsg, e);
            updateProgressOnUiThread(errorMsg);
        }
    }
    
    // Get saved model mapping
    private String getModelMapping(String originalModel) {
        try {
            if (originalModel == null || originalModel.isEmpty()) {
                return null;
            }
            
            // Get model mapping from ConfigManager
            return ConfigManager.getModelMapping(requireContext(), "model_" + originalModel, null);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get model mapping", e);
            return null;
        }
    }
    
    // Save model mapping to database metadata
    private void saveModelMapping(String originalModel, String selectedModel, String knowledgeBase) {
        try {
            if (originalModel == null || originalModel.isEmpty() || selectedModel == null || selectedModel.isEmpty() || knowledgeBase == null || knowledgeBase.isEmpty()) {
                return;
            }
            
            // Get knowledge base directory
            String knowledgeBasePath = ConfigManager.getString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE_PATH, ConfigManager.DEFAULT_KNOWLEDGE_BASE_PATH);
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            
            // Update database metadata
            SQLiteVectorDatabaseHandler vectorDb = null;
            try {
                vectorDb = new SQLiteVectorDatabaseHandler(knowledgeBaseDir, "unknown");
                if (vectorDb.loadDatabase()) {
                    // Get selected model file name
                    String selectedModelName = new File(selectedModel).getName();
                    
                    // Update model information in database metadata
                    if (vectorDb.updateEmbeddingModel(selectedModelName)) {
                        LogManager.logD(TAG, "Updated model information in database metadata: " + selectedModelName);
                        
                        // Save database
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
    
    // Get all possible model paths
    private List<String> getPossibleModelPaths() {
        List<String> possiblePaths = new ArrayList<>();
        
        // Get embedding model path from configuration
        String configPath = ConfigManager.getEmbeddingModelPath(requireContext());
        possiblePaths.add(configPath);
        
        // Add possible alternative paths
        File externalStorageDir = android.os.Environment.getExternalStorageDirectory();
        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        
        possiblePaths.add(new File(externalStorageDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(downloadDir, "starragdata/embeddings").getAbsolutePath());
        possiblePaths.add(new File(externalStorageDir, "Download/starragdata/embeddings").getAbsolutePath());
        possiblePaths.add("/storage/emulated/0/Download/starragdata/embeddings");
        possiblePaths.add("/sdcard/Download/starragdata/embeddings");
        
        return possiblePaths;
    }
    
    // Check if spinner is empty
    private boolean isSpinnerEmpty(Spinner spinner) {
        if (spinner == null) return true;
        if (spinner.getAdapter() == null) return true;
        SpinnerAdapter adapter = spinner.getAdapter();
        return adapter.getCount() == 0;
    }
    
    /**
     * Transfer selected text to knowledge base note
     * @param text Text to be converted to note
     */
    private void transferToKnowledgeNote(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.toast_no_selected_text_or_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // Save text to be transferred to temporary variable
            final String textToTransfer = text;
            
            // Get MainActivity instance
            MainActivity activity = (MainActivity) requireActivity();
            
            // Navigate to knowledge base note page
            activity.navigateToKnowledgeNote();
            
            // Add delay to ensure Fragment is fully initialized
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    // Try to get KnowledgeNoteFragment instance
                    Fragment fragment = null;
                    
                    // Try to get Fragment through different tags
                    for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                        if (f instanceof KnowledgeNoteFragment) {
                            fragment = f;
                            break;
                        }
                        
                        // Check child fragments
                        if (f.getChildFragmentManager() != null) {
                            for (Fragment childFragment : f.getChildFragmentManager().getFragments()) {
                                if (childFragment instanceof KnowledgeNoteFragment) {
                                    fragment = childFragment;
                                    break;
                                }
                            }
                        }
                    }
                    
                    // If KnowledgeNoteFragment is found
                    if (fragment instanceof KnowledgeNoteFragment) {
                        KnowledgeNoteFragment knowledgeNoteFragment = (KnowledgeNoteFragment) fragment;
                        
                        // Insert text into knowledge base note content editor
                        knowledgeNoteFragment.insertTextToContentEditor(textToTransfer);
                        
                        // Show prompt message
                        Toast.makeText(requireContext(), getString(R.string.toast_transferred_to_note), Toast.LENGTH_SHORT).show();
                        LogManager.logD(TAG, "Converted text to knowledge base note, length: " + textToTransfer.length());
                    } else {
                        // First attempt failed, retry with delay
                        LogManager.logD(TAG, "First attempt to get KnowledgeNoteFragment failed, will retry in 500ms");
                        
                        // Retry with 500ms delay
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                // Try to get Fragment again
                                Fragment retryFragment = null;
                                for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
                                    if (f instanceof KnowledgeNoteFragment) {
                                        retryFragment = f;
                                        break;
                                    }
                                    
                                    // Check fragments in ViewPager
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
                        }, 500); // Delay 500ms more
                    }
                } catch (Exception e) {
                    Toast.makeText(requireContext(), getString(R.string.toast_transfer_to_note_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    LogManager.logE(TAG, "Convert to note failed", e);
                }
            }, 300); // Initial delay 300ms
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.toast_transfer_to_note_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Convert to note failed", e);
        }
    }
    
    /**
     * Apply global font size settings
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
        // Re-apply font size when page resumes, so it takes effect immediately after modification in settings page
        applyGlobalTextSize();
        
        // Remove automatic query recovery logic to avoid unexpected query execution when app starts
        // If query recovery function is needed, it should be triggered by explicit user action
        /*
        // Check if previous query needs to be resumed
        if (queryNeedsResume && lastUserPrompt != null && !lastUserPrompt.isEmpty()) {
            LogManager.logD(TAG, "Detected query that needs to be resumed, will re-execute after page resume");
            
            // Reset resume flag
            queryNeedsResume = false;
            
            // Show resume prompt on UI thread
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (isAdded() && getActivity() != null) {
                        updateProgressOnUiThread("Resuming previous query: " + lastUserPrompt);
                        
                        // Delay one second before execution to ensure UI is fully initialized
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
    
    // Setup custom text selection menu
    private void setupCustomTextSelectionMenu() {
        if (textViewResponse != null) {
            textViewResponse.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    // Do not interfere with system default menu creation
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    // Check if "Transfer to Note" option already exists in menu
                    boolean hasTransferOption = false;
                    for (int i = 0; i < menu.size(); i++) {
                        MenuItem item = menu.getItem(i);
                        String transferToNoteText = getString(R.string.menu_item_transfer_to_note);
                        if (item.getTitle().equals(transferToNoteText)) {
                            hasTransferOption = true;
                            break;
                        }
                    }
                    
                    // Only add "Transfer to Note" option if it doesn't exist
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
                        // Get selected text
                        String selectedText = "";
                        
                        // Get selected text
                        int start = textViewResponse.getSelectionStart();
                        int end = textViewResponse.getSelectionEnd();
                        
                        if (start >= 0 && end >= 0 && start != end) {
                            // Has selected text
                            selectedText = textViewResponse.getText().toString().substring(start, end);
                        } else {
                            // No selected text, use all content
                            selectedText = textViewResponse.getText().toString();
                        }
                        
                        // Call transfer to note method
                        transferToKnowledgeNote(selectedText);
                        
                        // Close selection mode
                        mode.finish();
                        return true;
                    }
                    return false;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    // No special handling needed
                }
            });
        }
    }
    
    // Continue RAG query process after reranking completion
    private void continueRagQueryAfterReranking() {
        try {
            // Get saved query parameters
            String apiUrl = lastApiUrl;
            String apiKey = lastApiKey;
            String model = lastModel;
            String systemPrompt = lastSystemPrompt;
            String userPrompt = lastUserPrompt;
            
            // Get reranked relevant documents
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
                
                // Build prompt without knowledge base content
                String fullPrompt = buildPromptWithoutKnowledgeBase(systemPrompt, userPrompt);
                
                // Call LLM API to get answer
                updateProgressOnUiThread(getString(R.string.calling_llm_api));
                callLLMApi(apiUrl, apiKey, model, fullPrompt);
            } else {
                // Display similarity information
                if (!TextUtils.isEmpty(simInfo)) {
                    updateProgressOnUiThread(getString(R.string.similarity_info, simInfo));
                }
                
                // Build prompt with knowledge base content
                String fullPrompt = buildPromptWithKnowledgeBase(systemPrompt, userPrompt, relevantDocs);
                
                // Record prompt information
                int promptLength = fullPrompt.length();
                String promptInfo = "Built prompt length: " + promptLength + " characters";
                LogManager.logD(TAG, promptInfo);
                updateProgressOnUiThread(promptInfo);
                
                // Print detailed complete text sent to LLM
                LogManager.logI(TAG, "=== Complete prompt sent to LLM ===");
                LogManager.logI(TAG, "Prompt length: " + promptLength + " characters");
                LogManager.logI(TAG, "Prompt content:");
                LogManager.logI(TAG, fullPrompt);
                LogManager.logI(TAG, "=== LLM prompt end ===");
                
                // Record warning if prompt is too long
                if (promptLength > 4000) {
                    String warnMsg = getString(R.string.warning_prompt_too_long);
                    LogManager.logW(TAG, warnMsg);
                    updateProgressOnUiThread(warnMsg);
                }
                
                // Call LLM API to get answer
                updateProgressOnUiThread(getString(R.string.calling_llm_api));
                callLLMApi(apiUrl, apiKey, model, fullPrompt);
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Continue RAG query after reranking failed: " + e.getMessage(), e);
            updateProgressOnUiThread("Continue query after reranking failed: " + e.getMessage());
            
            // Use unified state reset method
            resetSendingState();
        } finally {
            // Reset state whether successful or failed
            mainHandler.post(() -> {
                // restore battery optimization settings
                if (batteryOptimizationDisabled) {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).restoreBatteryOptimization();
                        batteryOptimizationDisabled = false;
                        LogManager.logD(TAG, "Restored battery optimization settings on task completion");
                    }
                }

                // disable keep screen on
                if (isKeepScreenOn) {
                    enableKeepScreenOn(false);
                    LogManager.logD(TAG, "Disabled keep screen on on task completion");
                }

                // Use unified state reset method
                resetSendingState();
            });
        }
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize image picker launchers - must be done in onCreate before Fragment is attached
        // For Android 13+ (API 33+): Use Photo Picker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia = registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null) {
                            handleImageSelected(uri);
                        } else {
                            LogManager.logI(TAG, "Pick image from selection menu - no image selected");
                        }
                    });
        }
        
        // For Android 11/12: Use OpenDocument
        pickDocument = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        handleImageSelected(uri);
                    } else {
                        LogManager.logI(TAG, "Pick image from selection menu - no image selected");
                    }
                });
        
        // Initialize camera launcher
        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraCaptureUri != null) {
                        handleImageSelected(cameraCaptureUri);
                        LogManager.logI(TAG, "Photo captured successfully: " + cameraCaptureUri);
                    } else {
                        LogManager.logI(TAG, "Photo capture failed or cancelled");
                        Toast.makeText(requireContext(), R.string.toast_take_photo_failed, Toast.LENGTH_SHORT).show();
                    }
                });
        
        // Initialize camera permission launcher
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // Permission granted, launch camera
                        startCameraCapture();
                    } else {
                        // Permission denied
                        Toast.makeText(requireContext(), R.string.toast_camera_permission_denied, Toast.LENGTH_SHORT).show();
                        LogManager.logW(TAG, "Camera permission denied by user");
                    }
                });
    }
    
    /**
     * Setup custom action mode for long press on input field
     * Adds "Image" menu item to both selection and insert menus
     */
    private void setupInputFieldLongPressMenu() {
        // Custom callback for text selection (when text is selected)
        ActionMode.Callback selectionCallback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, android.view.Menu menu) {
                // Add "Image" and "Camera" menu items
                menu.add(0, android.R.id.button1, 0, R.string.menu_pick_image);
                menu.add(0, android.R.id.button2, 1, R.string.menu_take_photo);
                return true;
            }
            
            @Override
            public boolean onPrepareActionMode(ActionMode mode, android.view.Menu menu) {
                return false;
            }
            
            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getTitle().equals(getString(R.string.menu_pick_image))) {
                    // Launch image picker
                    launchImagePicker();
                    mode.finish();
                    return true;
                } else if (item.getTitle().equals(getString(R.string.menu_take_photo))) {
                    // Launch camera
                    launchCamera();
                    mode.finish();
                    return true;
                }
                return false;
            }
            
            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // No action needed
            }
        };
        
        // Set callback for text selection
        editTextUserPrompt.setCustomSelectionActionModeCallback(selectionCallback);
        
        // Set callback for insert mode (when no text is selected, e.g., empty field or cursor position)
        editTextUserPrompt.setCustomInsertionActionModeCallback(selectionCallback);
    }
    
    /**
     * Launch appropriate image picker based on Android version
     */
    private void launchImagePicker() {
        // Check if max images reached
        if (imageThumbnailAdapter.getImageCount() >= MAX_IMAGES) {
            Toast.makeText(requireContext(), R.string.toast_image_too_many, Toast.LENGTH_SHORT).show();
            return;
        }
        
        LogManager.logI(TAG, "Pick image from selection menu");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use Photo Picker for Android 13+
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            // Use OpenDocument for Android 11/12
            pickDocument.launch(new String[]{"image/*"});
        }
    }
    
    /**
     * Handle selected image: save URI for delayed compression
     * Image will be compressed later based on model's actual image size requirement
     */
    private void handleImageSelected(Uri imageUri) {
        try {
            // Add image URI to adapter (compression will happen later)
            imageThumbnailAdapter.addImage(imageUri);
            // Show RecyclerView
            recyclerViewImageThumbnails.setVisibility(View.VISIBLE);
            LogManager.logI(TAG, "Image added (delayed compression): " + imageUri.toString());
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.toast_image_pick_failed, 
                    Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Error handling selected image: " + e.getMessage());
        }
    }
    
    /**
     * Launch camera for taking photo
     * Checks permission first, then starts camera capture
     */
    private void launchCamera() {
        // Check if max images reached
        if (imageThumbnailAdapter.getImageCount() >= MAX_IMAGES) {
            Toast.makeText(requireContext(), R.string.toast_image_too_many, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted, start camera
            startCameraCapture();
        } else {
            // Request camera permission
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }
    
    /**
     * Start camera capture with temporary file
     */
    private void startCameraCapture() {
        try {
            // Create temporary file for photo
            File photoFile = createImageFile();
            if (photoFile != null) {
                // Get URI using FileProvider
                cameraCaptureUri = FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().getPackageName() + ".fileprovider",
                        photoFile);
                
                // Launch camera
                takePictureLauncher.launch(cameraCaptureUri);
                LogManager.logI(TAG, "Camera launched with URI: " + cameraCaptureUri);
            } else {
                Toast.makeText(requireContext(), R.string.toast_take_photo_failed, Toast.LENGTH_SHORT).show();
                LogManager.logE(TAG, "Failed to create temporary file for camera capture");
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.toast_take_photo_failed, Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Error starting camera capture: " + e.getMessage());
        }
    }
    
    /**
     * Create temporary image file for camera capture
     */
    private File createImageFile() throws IOException {
        // Create image file name with timestamp
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        
        // Get cache directory
        File storageDir = requireContext().getCacheDir();
        
        // Create the image file
        return File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
    }
    
    /**
     * Show full screen image preview dialog
     */
    private void showImagePreview(String imagePath) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.dialog_title_image_preview);
        
        // Create ImageView for preview
        android.widget.ImageView imageView = new android.widget.ImageView(requireContext());
        
        try {
            android.graphics.Bitmap bitmap = null;
            
            // Check if path is content:// URI, file:// URI, or plain file path
            if (imagePath.startsWith("content://")) {
                // Load from content URI using ContentResolver
                android.net.Uri uri = android.net.Uri.parse(imagePath);
                try {
                    bitmap = android.graphics.BitmapFactory.decodeStream(
                        requireContext().getContentResolver().openInputStream(uri));
                } catch (java.io.IOException e) {
                    LogManager.logE(TAG, "Failed to load image from content URI: " + imagePath, e);
                }
            } else if (imagePath.startsWith("file://")) {
                // Strip file:// prefix and load from file path
                String filePath = imagePath.substring(7); // Remove "file://"
                bitmap = android.graphics.BitmapFactory.decodeFile(filePath);
                LogManager.logD(TAG, "Loading image from file:// URI: " + filePath);
            } else {
                // Load from plain file path
                bitmap = android.graphics.BitmapFactory.decodeFile(imagePath);
                LogManager.logD(TAG, "Loading image from file path: " + imagePath);
            }
            
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
                imageView.setAdjustViewBounds(true);
                imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                
                // Make ImageView focusable and clickable to properly handle ActionMode dismissal
                imageView.setFocusable(true);
                imageView.setFocusableInTouchMode(true);
                imageView.setClickable(true);
                
                // Add click listener to dismiss ActionMode
                imageView.setOnClickListener(v -> {
                    if (currentImageActionMode != null) {
                        currentImageActionMode.finish();
                        currentImageActionMode = null;
                    }
                });
                
                // Add long press listener to start ActionMode (like text selection)
                String finalImagePath = imagePath;
                imageView.setOnLongClickListener(v -> {
                    startImageActionMode(v, finalImagePath);
                    return true;
                });
            } else {
                LogManager.logE(TAG, "Failed to load image bitmap: " + imagePath);
                android.widget.TextView errorView = new android.widget.TextView(requireContext());
                errorView.setText("Failed to load image");
                errorView.setPadding(50, 50, 50, 50);
                builder.setView(errorView);
                builder.setPositiveButton(android.R.string.ok, null);
                builder.show();
                return;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error loading image preview: " + imagePath, e);
            android.widget.TextView errorView = new android.widget.TextView(requireContext());
            errorView.setText("Error: " + e.getMessage());
            errorView.setPadding(50, 50, 50, 50);
            builder.setView(errorView);
            builder.setPositiveButton(android.R.string.ok, null);
            builder.show();
            return;
        }
        
        builder.setView(imageView);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    }
    
    // Store current ActionMode reference to dismiss it when needed
    private android.view.ActionMode currentImageActionMode = null;
    
    /**
     * Start ActionMode for image operations (copy/save/share) - similar to text selection menu
     */
    private void startImageActionMode(View view, String imagePath) {
        // Strip file:// prefix if present
        String filePath = imagePath;
        if (filePath.startsWith("file://")) {
            filePath = filePath.substring(7);
        }
        
        File imageFile = new File(filePath);
        if (!imageFile.exists()) {
            Toast.makeText(requireContext(), "Image file not found", Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Image file not found: " + filePath);
            return;
        }
        
        // Create Uri for the file
        android.net.Uri imageUri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            requireContext().getPackageName() + ".fileprovider",
            imageFile
        );
        
        // Create ActionMode callback (similar to text selection)
        android.view.ActionMode.Callback callback = new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                // Don't keep system default menu, we'll add custom items
                return true;
            }
            
            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                // Clear any existing items to avoid duplicates
                menu.clear();
                
                // Add custom image operation menu items (COPY, SAVE, SHARE)
                // Use order values to control display order: lower values appear first
                menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 100, 1, getString(R.string.common_copy));
                menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 101, 2, getString(R.string.common_save));
                menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 102, 3, getString(R.string.common_share));
                return true;
            }
            
            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case android.view.Menu.FIRST + 100: // Copy
                        copyImageToClipboard(imageUri);
                        mode.finish();
                        return true;
                    case android.view.Menu.FIRST + 101: // Save
                        saveImageToGallery(imageFile);
                        mode.finish();
                        return true;
                    case android.view.Menu.FIRST + 102: // Share
                        shareImage(imageUri);
                        mode.finish();
                        return true;
                    default:
                        return false;
                }
            }
            
            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
                // Clean up reference
                if (currentImageActionMode == mode) {
                    currentImageActionMode = null;
                }
            }
        };
        
        // Dismiss previous ActionMode if exists
        if (currentImageActionMode != null) {
            currentImageActionMode.finish();
        }
        
        // Start floating ActionMode (like text selection) instead of primary ActionMode (top bar)
        currentImageActionMode = view.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING);
    }
    
    /**
     * Copy image to clipboard
     */
    private void copyImageToClipboard(android.net.Uri imageUri) {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newUri(requireContext().getContentResolver(), "Image", imageUri);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Image copied to clipboard", Toast.LENGTH_SHORT).show();
            LogManager.logI(TAG, "Image copied to clipboard");
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to copy image", Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Failed to copy image to clipboard", e);
        }
    }
    
    /**
     * Save image to gallery
     */
    private void saveImageToGallery(File imageFile) {
        try {
            // Use MediaStore to save image
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "diffusion_" + System.currentTimeMillis() + ".jpg");
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/OfflineAI");
            
            android.net.Uri uri = requireContext().getContentResolver().insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            
            if (uri != null) {
                java.io.OutputStream outputStream = requireContext().getContentResolver().openOutputStream(uri);
                if (outputStream != null) {
                    java.io.FileInputStream inputStream = new java.io.FileInputStream(imageFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    inputStream.close();
                    outputStream.close();
                    
                    Toast.makeText(requireContext(), "Image saved to gallery", Toast.LENGTH_SHORT).show();
                    LogManager.logI(TAG, "Image saved to gallery: " + uri);
                }
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Failed to save image to gallery", e);
        }
    }
    
    /**
     * Share image via system share dialog
     */
    private void shareImage(android.net.Uri imageUri) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/jpeg");
            shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Image"));
            LogManager.logI(TAG, "Share image intent launched");
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to share image", Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Failed to share image", e);
        }
    }
    
    /**
     * Get current model name for chat UI
     */
    private String getCurrentModelName() {
        if (spinnerApiModel != null && spinnerApiModel.getSelectedItem() != null) {
            return spinnerApiModel.getSelectedItem().toString();
        }
        return "Unknown Model";
    }
    
    /**
     * Get current time for chat UI
     */
    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }
    
    /**
     * Update chat message with streaming text
     * This replaces the old appendToResponse for chat UI
     */
    private void updateChatMessage(String chunk) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update chat message, Fragment not attached");
            return;
        }
        
        // Check if already in UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performUpdateChatMessage(chunk);
        } else {
            getActivity().runOnUiThread(() -> performUpdateChatMessage(chunk));
        }
    }
    
    private void performUpdateChatMessage(String chunk) {
        try {
            if (chatMessages.isEmpty()) {
                LogManager.logW(TAG, "No chat messages to update");
                return;
            }
            
            ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
            if (lastMsg.getType() != ChatViewHolders.ASSISTANT) {
                LogManager.logW(TAG, "Last message is not assistant type");
                return;
            }
            
            // Accumulate text
            String currentText = lastMsg.text;
            if (currentText == null) currentText = "";
            String newText = currentText + chunk;
            
            // Check for image marker [IMAGE:path]
            if (newText.contains("[IMAGE:")) {
                int startIdx = newText.indexOf("[IMAGE:");
                int endIdx = newText.indexOf("]", startIdx);
                if (endIdx > startIdx) {
                    String imagePath = newText.substring(startIdx + 7, endIdx);
                    // Set image URI
                    lastMsg.imageUri = Uri.fromFile(new File(imagePath));
                    // Remove marker from text
                    newText = newText.substring(0, startIdx) + newText.substring(endIdx + 1);
                    LogManager.logI(TAG, "Image marker detected, path: " + imagePath);
                }
            }
            
            lastMsg.text = newText;
            
            // Parse collapsible sections
            CollapsibleTextParser.INSTANCE.parseAndPopulate(newText, lastMsg);
            
            lastMsg.setLoading(false);
            
            // Incremental update
            chatAdapter.updateRecentItem(lastMsg);
            
            // Auto scroll
            recyclerViewChat.smoothScrollToPosition(chatMessages.size() - 1);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to update chat message", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Cleanup image cache
        ImageThumbnailAdapter.cleanupCache(requireContext());
        
        // Close thread pool
        if (ragQueryExecutor != null && !ragQueryExecutor.isShutdown()) {
            ragQueryExecutor.shutdown();
            LogManager.logD(TAG, "RagQuery executor shutdown initiated");
            
            // Try to wait for thread pool shutdown
            try {
                if (!ragQueryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    ragQueryExecutor.shutdownNow();
                    LogManager.logW(TAG, "RagQuery executor forced shutdown");
                }
            } catch (InterruptedException e) {
                LogManager.logE(TAG, "Thread pool shutdown interrupted", e);
                ragQueryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        LogManager.logD(TAG, "Thread pools shutdown completed");
    }
}
