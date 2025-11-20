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
import android.media.MediaPlayer;
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
import com.example.offlineai.api.TtsAdapter;
import com.example.offlineai.ApiUrlAdapter;
import com.example.offlineai.RerankerHandler;
import com.example.offlineai.AppConstants;
import com.example.offlineai.StateDisplayManager;
import com.example.offlineai.adapter.StateAwareSpinnerAdapter;
import com.example.offlineai.MediaThumbnailAdapter;
import com.example.offlineai.EmbeddingHandler;
import com.example.offlineai.HanLpNerHandler;
import com.example.offlineai.GraphStopwordsMatcher;
// Removed: import com.example.offlineai.SQLiteVectorDatabaseHandler; - Now using KnowledgeGraphDatabase directly
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
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class RagQaFragment extends Fragment {

    private static final String TAG = "OfflineAI_RagQa"; // Add TAG for log printing
    private static final String LOG_FILE = "api_log.txt"; // Log file name
    private static final int MAX_IMAGES = 3; // Maximum number of images allowed
    
    // Backend preference options (same as SettingsFragment)
    private static final String[] BACKEND_OPTIONS = {"CPU", "Vulkan", "OpenCL", "NNAPI"};
    private static final String[] BACKEND_VALUES = {"CPU", "VULKAN", "OPENCL", "NNAPI"};

    private Spinner spinnerApiUrl;
    private EditText editTextApiKey;
    private Spinner spinnerBackendPreference; // Backend preference spinner (replaces API Key for local models)
    private TextView textViewApiKeyLabel; // Label for API Key / Backend Preference
    private Spinner spinnerApiModel;
    private Spinner spinnerKnowledgeBase;
    private EditText editTextSystemPrompt;
    private EditText editTextUserPrompt;
    private Button buttonSendStop;
    private Button buttonNewChat;
    private Spinner spinnerSearchDepth; // Search depth dropdown
    private Spinner spinnerRerankCount; // Rerank count dropdown
    
    // Voice recording components
    private AudioService audioRecorder;
    private VoiceRecordingDialog recordingDialog;
    private boolean isRecordingVoice = false;
    private long pressStartTime = 0;
    private float initialTouchY = 0;
    private static final int LONG_PRESS_DURATION_MS = 600;  // 长按阈值 600ms
    private static final int CANCEL_THRESHOLD_PX = 180;     // 上滑取消阈值 180px (参考微信)
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private Handler longPressHandler;  // Handler for long press detection
    private TextView textViewResponse; // Response text view
    private CheckBox checkBoxThinkingMode; // Thinking mode checkbox
    private RecyclerView recyclerViewImageThumbnails; // Media thumbnail container (images and audio)
    private MediaThumbnailAdapter mediaThumbnailAdapter; // Media thumbnail adapter
    // 避免程序化设置复选框状态时触发监听器造成误保存
    private boolean isUpdatingUiFromConfig = false;
    
    // Chat UI components
    private RecyclerView recyclerViewChat; // Chat message list
    private ChatRecyclerViewAdapter chatAdapter; // Chat adapter
    private List<ChatDataItem> chatMessages = new ArrayList<>(); // Chat messages
    
    // Current chat folder path for saving images and conversation
    private String currentChatFolderPath = null;
    
    // Image picker launcher for Android 13+
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    // Document picker launcher for Android 11/12
    private ActivityResultLauncher<String[]> pickDocument;
    // Media picker launcher (images, audio, video)
    private ActivityResultLauncher<String[]> pickMediaFile;
    // Camera launcher for taking photos
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private Uri cameraCaptureUri; // Temporary URI for camera capture
    // Camera permission launcher
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    // Audio recording permission launcher
    private ActivityResultLauncher<String> recordAudioPermissionLauncher;
    
    // Markdown renderer
    private Markwon markwon;
    private final StringBuilder answerBuilder = new StringBuilder();
    private final StringBuilder debugBuilder = new StringBuilder();
    private HanLpNerHandler graphNerHandler;
    private String graphNerDictPath;

    private final AtomicBoolean isSending = new AtomicBoolean(false); // Track the state of the send/stop button with atomic operations
    private final AtomicBoolean isTtsGenerating = new AtomicBoolean(false); // Track TTS generation state
    
    // Audio compression state tracking (for ANR prevention)
    private final AtomicBoolean isUserAudioCompressing = new AtomicBoolean(false); // User audio compression in progress
    private final AtomicBoolean isAiAudioCompressing = new AtomicBoolean(false);   // AI audio compression in progress
    private volatile String pendingUserAudioM4aPath = null;  // User audio M4A path after compression
    private volatile String pendingAiAudioM4aPath = null;    // AI audio M4A path after compression
    
    // Audio decoding state tracking (for selected audio files)
    private final AtomicBoolean isAudioDecoding = new AtomicBoolean(false); // Audio decoding in progress
    private final AtomicInteger decodingProgress = new AtomicInteger(0);    // Decoding progress (0-100)
    
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
    
    // TTS auto-play MediaPlayer
    private MediaPlayer autoPlayMediaPlayer;
    
    // TTS Adapter for streaming TTS (System/External TTS)
    private TtsAdapter ttsAdapter = null;
    
    // RAG query thread pool
    private ExecutorService ragQueryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQa-Query-Thread");
        t.setDaemon(true);
        return t;
    });
    
    // Audio compression thread pool (for ANR prevention)
    private ExecutorService audioCompressionExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Audio-Compression-Thread");
        t.setDaemon(true);
        return t;
    });
    
    // Main thread Handler
    private Handler mainHandler;
    
    // Auto-scroll state tracking
    private boolean userScrolledAway = false; // Track if user manually scrolled away from bottom
    private Runnable pendingScrollRunnable = null; // Pending scroll task for debouncing

    // Search result documents
    private List<String> relevantDocuments;
    private String similarityInfo;

    private static class GraphRagCandidate {
        KnowledgeGraphDatabase.SearchResult result;
        float vectorScore;
        float graphScore;
        float finalScore;
        int entityOverlap;
    }

    // Graph RAG limits for seed entities to avoid explosion
    private static final int GRAPH_RAG_MAX_SEED_ENTITIES = 32;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] === onCreateView() called ===");
        View view = inflater.inflate(R.layout.fragment_rag_qa, container, false);
        
        // Initialize UI elements
        spinnerApiUrl = view.findViewById(R.id.spinnerApiUrl);
        editTextApiKey = view.findViewById(R.id.editTextApiKey);
        spinnerBackendPreference = view.findViewById(R.id.spinnerBackendPreference); // Backend preference spinner
        textViewApiKeyLabel = view.findViewById(R.id.textViewApiKeyLabel); // API Key label
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
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] Creating new ChatRecyclerViewAdapter, chatMessages.size=" + chatMessages.size());
        chatAdapter = new ChatRecyclerViewAdapter(requireContext());
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] New chatAdapter created: " + chatAdapter);
        chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] chatAdapter.getItemCount() after init: " + chatAdapter.getItemCount());
        
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
        
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        recyclerViewChat.setLayoutManager(layoutManager);
        recyclerViewChat.setAdapter(chatAdapter);
        
        // Add scroll listener to detect user manual scrolling
        recyclerViewChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                
                // When user starts dragging, mark as scrolled away
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (!isAtBottom(layoutManager)) {
                        userScrolledAway = true;
                        LogManager.logD(TAG, "[SCROLL] User scrolled away from bottom");
                    }
                }
            }
            
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                
                // If user scrolled to bottom, reset the flag
                if (isAtBottom(layoutManager)) {
                    if (userScrolledAway) {
                        userScrolledAway = false;
                        LogManager.logD(TAG, "[SCROLL] User returned to bottom");
                    }
                }
            }
        });
        
        LogManager.logD(TAG, "Chat RecyclerView initialized with callbacks and scroll listener");
        
        // Initialize media thumbnail adapter and RecyclerView
        mediaThumbnailAdapter = new MediaThumbnailAdapter();
        mediaThumbnailAdapter.setContext(requireContext()); // Set context for thumbnail loading
        recyclerViewImageThumbnails.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        recyclerViewImageThumbnails.setAdapter(mediaThumbnailAdapter);
        
        // Set media action listener
        mediaThumbnailAdapter.setOnMediaActionListener(new MediaThumbnailAdapter.OnMediaActionListener() {
            @Override
            public void onMediaClick(MediaThumbnailAdapter.MediaItem item, int position) {
                if (item instanceof MediaThumbnailAdapter.AudioItem) {
                    // Play audio
                    MediaThumbnailAdapter.AudioItem audioItem = (MediaThumbnailAdapter.AudioItem) item;
                    if (audioItem.isProcessed()) {
                        mediaThumbnailAdapter.playAudio(audioItem.getProcessedPath());
                        LogManager.logI(TAG, "Playing audio: " + audioItem.getProcessedPath());
                    }
                } else {
                    // Show full screen image preview
                    LogManager.logI(TAG, "Image clicked: " + item.getProcessedPath());
                }
            }

            @Override
            public void onMediaDelete(MediaThumbnailAdapter.MediaItem item, int position) {
                // Delete media from list
                mediaThumbnailAdapter.removeMedia(position);
                // Hide RecyclerView if no media
                if (mediaThumbnailAdapter.getMediaCount() == 0) {
                    recyclerViewImageThumbnails.setVisibility(View.GONE);
                }
                LogManager.logI(TAG, "Deleted media at position: " + position);
            }
        });
        
        // Initialize rerank count spinner
        initializeRerankCountSpinner();
        
        // Initialize backend preference spinner
        initializeBackendPreferenceSpinner();
        
        // Load API URL list, including custom URLs from configuration
        loadApiUrlList();
        
        // Set initial data for other Spinners
        setupSpinner(spinnerApiModel, new String[]{getString(R.string.common_loading)});
        setupSpinner(spinnerKnowledgeBase, new String[]{getString(R.string.common_loading)});
        
        // Add selection listener for API URL Spinner to automatically load corresponding API Key / Backend Preference
        spinnerApiUrl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedApiUrl = parent.getItemAtPosition(position).toString();
                
                // Check if "Add New..." option is selected
                if (selectedApiUrl.equals(StateDisplayManager.getApiUrlDisplayText(requireContext(), AppConstants.API_URL_NEW))) {
                    showAddApiUrlDialog();
                    return;
                }
                
                // Dynamically switch between API Key and Backend Preference
                updateApiKeyOrBackendDisplay(selectedApiUrl);
                
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
            ConfigManager.setBoolean(requireContext(), ConfigManager.KEY_NO_THINKING, !isChecked);
            LogManager.logD(TAG, "Thinking mode changed: " + (isChecked ? "enabled" : "disabled"));
        });
        
        // Initialize voice recording components
        audioRecorder = new AudioService();
        recordingDialog = new VoiceRecordingDialog(requireContext());
        longPressHandler = new Handler(Looper.getMainLooper());
        
        // Set button listeners - use OnTouchListener for long press recording
        buttonSendStop.setOnTouchListener((v, event) -> handleSendButtonTouch(event));
        buttonNewChat.setOnClickListener(v -> handleNewChatClick());
        
        // Load knowledge base list
        loadKnowledgeBases();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize collapsible section switches based on config
        ChatViewHolders.AssistantViewHolder.showThinkingEnabled = true;  // Always show thinking
        boolean showDebugPerf = ConfigManager.getShowDebugPerformance(requireContext());
        ChatViewHolders.AssistantViewHolder.showDebugEnabled = showDebugPerf;
        ChatViewHolders.AssistantViewHolder.showPerformanceEnabled = showDebugPerf;
        LogManager.logD(TAG, "Collapsible section switches initialized: thinking=true, debug=" + showDebugPerf + ", performance=" + showDebugPerf);
        
        // Initialize main thread Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize TtsAdapter
        ttsAdapter = TtsAdapter.getInstance(requireContext());
        LogManager.logI(TAG, "[TTS] TtsAdapter initialized");
        
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
        
        // Load chat history if available
        loadChatHistory();
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
     * Initialize backend preference dropdown (same as SettingsFragment)
     */
    private void initializeBackendPreferenceSpinner() {
        // Use same options as SettingsFragment
        setupSpinner(spinnerBackendPreference, BACKEND_OPTIONS);
        
        // Load current backend preference from ConfigManager (same as SettingsFragment)
        String backendPreference = ConfigManager.getString(requireContext(), 
            ConfigManager.KEY_USE_GPU, "CPU");
        
        // Compatibility: map deprecated values to CPU (same as SettingsFragment)
        if ("true".equals(backendPreference)) {
            backendPreference = "VULKAN";
            ConfigManager.setString(requireContext(), ConfigManager.KEY_USE_GPU, backendPreference);
        } else if ("false".equals(backendPreference)) {
            backendPreference = "CPU";
            ConfigManager.setString(requireContext(), ConfigManager.KEY_USE_GPU, backendPreference);
        }
        if ("CANN".equals(backendPreference) || "KLEIDIAI-SME".equals(backendPreference)) {
            LogManager.logW(TAG, "Backend '" + backendPreference + "' is deprecated. Fallback to 'CPU'.");
            backendPreference = "CPU";
            ConfigManager.setString(requireContext(), ConfigManager.KEY_USE_GPU, backendPreference);
        }
        
        // Set spinner selection by matching BACKEND_VALUES
        int selectedIndex = 0;
        for (int i = 0; i < BACKEND_VALUES.length; i++) {
            if (BACKEND_VALUES[i].equals(backendPreference)) {
                selectedIndex = i;
                break;
            }
        }
        spinnerBackendPreference.setSelection(selectedIndex);
        
        // Add listener to save preference when changed (same logic as SettingsFragment)
        spinnerBackendPreference.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isUpdatingUiFromConfig) {
                    // Save using BACKEND_VALUES (uppercase)
                    String selectedBackend = (position >= 0 && position < BACKEND_VALUES.length) ? 
                        BACKEND_VALUES[position] : "CPU";
                    ConfigManager.setString(requireContext(), 
                        ConfigManager.KEY_USE_GPU, selectedBackend);
                    LogManager.logI(TAG, "Backend preference changed to: " + selectedBackend);
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        LogManager.logD(TAG, "Backend preference Spinner initialized, current value: " + backendPreference);
    }
    
    /**
     * Setup listener for API URL spinner to dynamically switch between API Key and Backend Preference
     */
    private void setupApiUrlSwitchListener() {
        spinnerApiUrl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedApiUrl = parent.getItemAtPosition(position).toString();
                updateApiKeyOrBackendDisplay(selectedApiUrl);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    
    /**
     * Update display to show API Key input or Backend Preference spinner based on selected API
     */
    private void updateApiKeyOrBackendDisplay(String apiUrlDisplay) {
        String apiUrlValue = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrlValue)) {
            // Local model selected: show backend preference, hide API key
            editTextApiKey.setVisibility(View.GONE);
            spinnerBackendPreference.setVisibility(View.VISIBLE);
            textViewApiKeyLabel.setText(R.string.label_backend_preference);
            LogManager.logD(TAG, "Switched to backend preference mode");
        } else {
            // Online model selected: show API key, hide backend preference
            editTextApiKey.setVisibility(View.VISIBLE);
            spinnerBackendPreference.setVisibility(View.GONE);
            textViewApiKeyLabel.setText(R.string.label_llm_api_key);
            LogManager.logD(TAG, "Switched to API key mode");
        }
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
            
            // Update initial display based on API URL (show API Key or Backend Preference)
            if (!apiUrl.isEmpty()) {
                String apiUrlDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), apiUrl);
                updateApiKeyOrBackendDisplay(apiUrlDisplayText);
            }
            
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
        String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(requireContext());
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

    /**
     * Show a unified confirm dialog before cancelling current operation.
     */
    private void showCancelOperationConfirmDialog(final Runnable onConfirm) {
        if (!isAdded() || getContext() == null) {
            LogManager.logW(TAG, "[DIALOG] Fragment not attached, skip cancel operation dialog");
            if (onConfirm != null) {
                onConfirm.run();
            }
            return;
        }

        String title = getString(R.string.dialog_title_confirm_interrupt);
        String message = getString(R.string.dialog_message_cancel_current_operation);
        String positiveText = getString(R.string.common_confirm);
        String negativeText = getString(R.string.common_cancel);

        new AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText, (dialog, which) -> {
                if (onConfirm != null) {
                    onConfirm.run();
                }
            })
            .setNegativeButton(negativeText, (dialog, which) -> {
                // User cancelled the dialog, do nothing
                LogManager.logD(TAG, "[DIALOG] User cancelled stop operation dialog");
            })
            .show();
    }

    private void handleSendStopClick() {
        // Check if TTS is generating - show confirm dialog before stopping TTS
        if (isTtsGenerating.get()) {
            LogManager.logI(TAG, "[TTS] User requested stop during TTS generation");
            showCancelOperationConfirmDialog(() -> {
                LogManager.logI(TAG, "[TTS] User confirmed stop during TTS generation");
                stopTtsGeneration();
            });
            return;
        }
        
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

            // NOTE: Chat folder creation and media file saving moved to prepareAndSaveUserInput()
            // This ensures all files are saved at the moment user clicks send
            // NOTE: Audio conversion also moved to prepareAndSaveUserInput() (no background conversion)
        
        // Basic validation: Allow empty prompt if media files are present
        boolean hasMedia = mediaThumbnailAdapter != null && mediaThumbnailAdapter.getMediaCount() > 0;
        if (userPrompt.trim().isEmpty() && !hasMedia) {
            LogManager.logW(TAG, "[SEND][VALIDATION] Failed: empty user prompt and no media");
            restoreSendStateAfterValidationFailure("empty user prompt and no media");
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
        
        // Multimodal pre-check: just log media count if present, defer actual validation to model loading time
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            int mediaCount = mediaThumbnailAdapter != null ? mediaThumbnailAdapter.getMediaCount() : 0;
            if (mediaCount > 0) {
                LogManager.logI(TAG, String.format(
                    "[MULTIMODAL] User selected %d media item(s), will check model capability after loading",
                    mediaCount));
            }
        }

        // Save current configuration
        LogManager.logD(TAG, "[SEND] Persisting configuration selection to storage");
        saveConfig();
        
        // Update button state (isSending has already been set to true in compareAndSet)
        buttonSendStop.setText(getString(R.string.button_stop_with_icon));
        
        // CRITICAL: Prepare and save user input (files, message, markdown) at send moment
        UserInput userInput = prepareAndSaveUserInput(userPrompt, null);
        if (userInput == null) {
            // Failed to prepare input (e.g., folder creation failed)
            restoreSendStateAfterValidationFailure("failed to prepare user input");
            return;
        }
        
        LogManager.logI(TAG, String.format("[SEND] User input prepared: text=%s, images=%d, audio=%d",
            userInput.hasText() ? "yes" : "no", 
            userInput.imagePaths.size(), 
            userInput.audioPaths.size()));
        
        // Create AI message placeholder
        ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
        aiMsg.setLoading(true);
        chatMessages.add(aiMsg);
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        // Immediately scroll to bottom when adding new message (no animation)
        recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
        userScrolledAway = false; // Reset flag for new message
        
        LogManager.logD(TAG, "Chat messages created: user + AI placeholder, total=" + chatMessages.size());
        
        // Clear response area and display processing message (keep for old TextView compatibility)
        if (textViewResponse != null) {
            textViewResponse.setText("");
        }
        
        // CRITICAL: Check if audio needs ASR processing before submitting RAG task
        // If user selected ASR model (not "无") and there are audio files, use ASR flow
        boolean needsAsrProcessing = false;
        if (userInput.hasAudio()) {
            String asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
            if (!"无".equals(asrModel)) {
                needsAsrProcessing = true;
                LogManager.logI(TAG, "[SEND] Audio detected with ASR enabled, will use ASR processing flow");
            } else {
                LogManager.logI(TAG, "[SEND] Audio detected but ASR disabled, will use <audio> tag flow");
            }
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
            
            // Choose execution path based on ASR requirement
            if (needsAsrProcessing) {
                // ASR flow: convert audio to text first, then execute RAG query
                String asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
                // CRITICAL: Use cache WAV for ASR, NOT M4A from userInput!
                File cacheWav = AudioService.getCacheWavFile(requireContext());
                String audioPath = cacheWav.getAbsolutePath();
                LogManager.logI(TAG, "[SEND] Routing to ASR flow: model=" + asrModel + ", audio=" + audioPath + " (cache WAV)");
                
                ragTaskFuture = ragQueryExecutor.submit(() -> {
                    LogManager.logI(TAG, "[EXECUTOR] ASR task lambda ENTERED - thread=" + Thread.currentThread().getName());
                    try {
                        // Reset local LLM stop flag
                        String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                        String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
                        if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
                            try {
                                LocalLlmAdapter localAdapter = LocalLlmAdapter.getInstance(requireContext());
                                localAdapter.resetStopFlag();
                                LogManager.logD(TAG, "Reset local LLM stop flag in ASR task thread");
                            } catch (Exception e) {
                                LogManager.logE(TAG, "Error resetting local LLM stop flag", e);
                            }
                        }
                        
                        // Execute ASR conversion and RAG query
                        convertAndSendAsTextInternal(audioPath, userPrompt, asrModel, apiUrl, apiKey, model, knowledgeBase, systemPrompt, userInput);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[ASR] Error in ASR task", e);
                        mainHandler.post(() -> {
                            Toast.makeText(requireContext(), "ASR processing failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            resetSendingState();
                        });
                    }
                });
            } else {
                // Normal flow: execute RAG query directly (audio will be embedded as <audio> tag if present)
                ragTaskFuture = ragQueryExecutor.submit(() -> {
                    LogManager.logI(TAG, "[EXECUTOR] Task lambda ENTERED - thread=" + Thread.currentThread().getName());
                    
                    // ✅ FIX: Force reset globalStopFlag at task start to prevent inherited stop state
                    // This fixes the issue where switching TTS and sending causes immediate abort
                    if (globalStopFlag) {
                        LogManager.logW(TAG, "[FIX] Detected stale globalStopFlag=true, resetting to false for new task");
                        globalStopFlag = false;
                        userRequestedStop = false;
                    }
                    
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
                    // Pass UserInput to RAG query for file paths
                    executeRagQuery(apiUrl, apiKey, model, knowledgeBase, systemPrompt, userPrompt, userInput);
                });
            }
            LogManager.logI(TAG, "[EXECUTOR] After submit - ragTaskFuture=" + (ragTaskFuture == null ? "null" : "not_null") + ", isDone=" + (ragTaskFuture != null && ragTaskFuture.isDone()) + ", isCancelled=" + (ragTaskFuture != null && ragTaskFuture.isCancelled()));

        } else if (isSending.get()) {
            // Already sending - show confirm dialog before stopping
            LogManager.logD(TAG, "[STOP][CLICK] User clicked stop button while sending, showing confirm dialog");
            showCancelOperationConfirmDialog(() -> {
                // Use atomic operation to check and set sending state, prevent concurrent stop calls
                if (!isSending.compareAndSet(true, false)) {
                    LogManager.logD(TAG, "[STOP] Stop confirmed but isSending is already false, skip stop flow");
                    return;
                }

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
                
                // FIX: Stop loading animation immediately when user confirms stop
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
            });
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
        
        // Update button text to show "推理中..."
        if (mainHandler != null) {
            mainHandler.post(this::updateButtonText);
        }
        
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
        
        // CRITICAL: Always save conversation history first, even if TTS is still generating
        // This ensures AI response text is saved immediately after LLM completes
        saveChatHistory();
        
        // Check if TTS is still generating
        if (isTtsGenerating.get()) {
            LogManager.logW(TAG, "[STATE] TTS is still generating, conversation saved but state reset deferred");
            return;
        }
        
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
        
        // Clear media thumbnails after successful send
        if (mediaThumbnailAdapter != null && mediaThumbnailAdapter.getMediaCount() > 0) {
            mainHandler.post(() -> {
                mediaThumbnailAdapter.clearMedia();
                recyclerViewImageThumbnails.setVisibility(View.GONE);
                LogManager.logD(TAG, "Cleared media thumbnails after send");
            });
        }
        
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
                    updateButtonText();
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to reset button text", e);
                }
            });
        }
        
        // CHECKPOINT: Check if all audio compressions are complete
        // Scenarios: 1) No TTS, 2) TTS without auto-play, 4) No user audio
        LogManager.logI(TAG, "[STATE] Inference complete, checking compression status...");
        mainHandler.post(() -> checkCompressionCompleteAndFinalize());
    }
    
    /**
     * Update button text based on current state
     * Priority: Audio decoding > TTS generating > LLM inferring > Send
     */
    private void updateButtonText() {
        if (buttonSendStop == null) return;
        
        if (isAudioDecoding.get()) {
            // Audio decoding in progress - lock button and show progress
            int progress = decodingProgress.get();
            buttonSendStop.setText("解压音频中... " + progress + "%");
            buttonSendStop.setEnabled(false); // Lock button during decoding
        } else if (isTtsGenerating.get()) {
            buttonSendStop.setText(getString(R.string.button_generating_tts));
            buttonSendStop.setEnabled(true); // Allow stopping TTS
        } else if (isSending.get()) {
            buttonSendStop.setText(getString(R.string.button_inferring));
            buttonSendStop.setEnabled(true); // Allow stopping inference
        } else {
            buttonSendStop.setText(getString(R.string.button_send));
            buttonSendStop.setEnabled(true); // Allow sending new message
        }
    }
    
    /**
     * Stop TTS generation
     */
    private void stopTtsGeneration() {
        LogManager.logI(TAG, "[TTS] Stopping TTS generation");
        
        // Stop TtsAdapter
        if (ttsAdapter != null) {
            ttsAdapter.stop();
            LogManager.logI(TAG, "[TTS] TtsAdapter.stop() called");
        }
        
        // Set global stop flag to stop TTS service
        globalStopFlag = true;
        
        // Reset TTS state
        isTtsGenerating.set(false);
        
        // Reset sending state
        resetSendingState();
        
        Toast.makeText(requireContext(), "TTS 生成已停止", Toast.LENGTH_SHORT).show();
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
    // Overload: without ASR info and UserInput
    private void executeRagQuery(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt, UserInput userInput) {
        // ✅ 统一 ASR 检测（无论本地还是在线模型）
        // CRITICAL: Check if audio needs ASR processing before RAG query
        if (userInput != null && userInput.hasAudio()) {
            String asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
            if (!"无".equals(asrModel)) {
                // ASR enabled: convert audio to text first, then continue RAG flow
                // CRITICAL: Use cache WAV for ASR, NOT M4A from userInput!
                File cacheWav = AudioService.getCacheWavFile(requireContext());
                String audioPath = cacheWav.getAbsolutePath();
                LogManager.logI(TAG, "[ASR] Audio detected with ASR enabled, converting to text first");
                LogManager.logI(TAG, "[ASR] Model: " + asrModel + ", Audio: " + audioPath + " (cache WAV)");
                
                // Submit ASR task (will call executeRagQueryWithAsr after conversion)
                ragTaskFuture = ragQueryExecutor.submit(() -> {
                    try {
                        convertAndSendAsTextInternal(audioPath, userPrompt, asrModel, 
                                                     apiUrl, apiKey, model, knowledgeBase, systemPrompt, userInput);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[ASR] Conversion failed", e);
                        mainHandler.post(() -> {
                            Toast.makeText(requireContext(), "ASR processing failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            resetSendingState();
                        });
                    }
                });
                return; // Exit early, ASR will continue the flow
            } else {
                LogManager.logI(TAG, "[ASR] Audio detected but ASR disabled, will use <audio> tag");
            }
        }
        
        // No ASR needed: continue normal RAG flow
        executeRagQueryWithAsr(apiUrl, apiKey, model, knowledgeBase, systemPrompt, userPrompt, null, false, userInput);
    }
    
    // Main implementation: with optional ASR info, audio embedding control, and UserInput
    private void executeRagQueryWithAsr(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt, String asrInfo, boolean skipAudioEmbedding, UserInput userInput) {
        // [Fix] Use dedicated initialization method, do not reset global stop flag
        initializeSendingState();
        
        LogManager.logD(TAG, "Starting RAG query execution with preserved global stop flag state");
        
        // ============================================
        // Initialize TtsAdapter (if System/External TTS enabled)
        // ============================================
        String ttsModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_TTS_MODEL,
                getString(R.string.settings_tts_model_none));
        boolean isLocalModel = AppConstants.ApiUrl.LOCAL.equals(apiUrl);
        String nativeTtsName = getString(R.string.settings_tts_model_native_omni);
        boolean isOmniNativeTts = nativeTtsName.equals(ttsModel);
        String noneOption = getString(R.string.settings_tts_model_none);
        String commonNone = getString(R.string.common_none);
        boolean isTtsNoneOrEmpty = (ttsModel == null
                || ttsModel.trim().isEmpty()
                || noneOption.equals(ttsModel)
                || commonNone.equals(ttsModel)
                || "None".equalsIgnoreCase(ttsModel));
        
        // Enable TtsAdapter for System/External TTS (not Omni Native, not None)
        if (!isTtsNoneOrEmpty && !isOmniNativeTts) {
            try {
                String chatFolderPath = ConfigManager.getString(requireContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                boolean autoPlay = ConfigManager.getTtsAutoPlay(requireContext());
                
                // Create TtsCallback
                TtsAdapter.TtsCallback ttsCallback = new TtsAdapter.TtsCallback() {
                    @Override
                    public void onTtsComplete(String mergedAudioPath, boolean playbackComplete) {
                        LogManager.logI(TAG, "[TTS] ========== CALLBACK RECEIVED ==========");
                        LogManager.logI(TAG, "[TTS] External TTS complete: " + mergedAudioPath);
                        LogManager.logI(TAG, "[TTS] playbackComplete: " + playbackComplete);
                        LogManager.logI(TAG, "[TTS] Current thread: " + Thread.currentThread().getName());
                        
                        // CRITICAL: Ensure callback runs on main thread
                        mainHandler.post(() -> {
                            LogManager.logI(TAG, "[TTS] ✅ Processing TTS completion on main thread");
                            handleTtsAudioComplete(mergedAudioPath);
                            LogManager.logI(TAG, "[TTS] ✅ handleTtsAudioComplete finished");
                        });
                    }
                    
                    @Override
                    public void onError(String error) {
                        LogManager.logE(TAG, "[TTS] External TTS error: " + error);
                        mainHandler.post(() -> {
                            if (getActivity() != null && isAdded()) {
                                Toast.makeText(requireContext(), "TTS Error: " + error, Toast.LENGTH_SHORT).show();
                                
                                // Reset state on error
                                isTtsGenerating.set(false);
                                resetSendingState();
                            }
                        });
                    }
                };
                
                ttsAdapter.enable(ttsModel, chatFolderPath, autoPlay, ttsCallback);
                LogManager.logI(TAG, "[TTS] TtsAdapter enabled: model=" + ttsModel + ", autoPlay=" + autoPlay);
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Failed to enable TtsAdapter", e);
            }
        } else {
            LogManager.logI(TAG, "[TTS] TtsAdapter not enabled: ttsModel=" + ttsModel + ", isOmniNative=" + isOmniNativeTts);
        }
        
        // ============================================
        // START: Unified Debug Section Management
        // ============================================
        // Open debug section at the beginning of the entire flow
        updateChatMessage("<debug>\n");
        
        // Output ASR info if provided (from ASR flow)
        if (asrInfo != null && !asrInfo.isEmpty()) {
            updateChatMessage(asrInfo);
        }
        
        // Use image paths from UserInput (already saved to chat folder)
        java.util.List<String> imagePaths = null;
        if (userInput != null && userInput.hasImages()) {
            imagePaths = userInput.imagePaths;
            LogManager.logI(TAG, "[MULTIMODAL] Using " + imagePaths.size() + " image(s) from UserInput");
        }
        
        // Use audio paths from UserInput (will be handled by JNI layer, similar to images)
        // CRITICAL: Skip audio if ASR already converted audio to text
        // CRITICAL: Use cache WAV for Omni, NOT M4A from userInput!
        java.util.List<String> audioPaths = null;
        final String originalUserPrompt = userPrompt; // Save original prompt for lambda
        if (!skipAudioEmbedding && userInput != null && userInput.hasAudio()) {
            // Use cache WAV for Omni audio understanding
            File cacheWav = AudioService.getCacheWavFile(requireContext());
            if (cacheWav.exists()) {
                audioPaths = new java.util.ArrayList<>();
                audioPaths.add(cacheWav.getAbsolutePath());
                LogManager.logI(TAG, "[MULTIMODAL] Using cache WAV for Omni: " + cacheWav.getAbsolutePath());
            } else {
                LogManager.logW(TAG, "[MULTIMODAL] Cache WAV not found, skipping audio");
            }
            // NOTE: Audio tags will be added by JNI layer (similar to image handling)
            // No need to modify userPrompt here
        } else if (skipAudioEmbedding) {
            LogManager.logI(TAG, "[MULTIMODAL] Skipped audio (ASR already converted to text)");
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
            isSending.set(true); // Use atomic operation to set sending state
            updateButtonText(); // Update button text based on state machine
            // [Fix] Task state already set in initializeSendingState, no need to set again here
            
            // Clear response area
            //updateProgressOnUiThread("Querying knowledge base...");
        });
        
        // Execute query synchronously (avoid concurrent conflicts)
        try {
            // Build media info for logging
            StringBuilder mediaInfo = new StringBuilder();
            if (imagePaths != null && !imagePaths.isEmpty()) {
                mediaInfo.append("; image: ");
                for (int i = 0; i < imagePaths.size(); i++) {
                    if (i > 0) mediaInfo.append(", ");
                    mediaInfo.append(new File(imagePaths.get(i)).getName());
                }
            }
            if (audioPaths != null && !audioPaths.isEmpty()) {
                mediaInfo.append("; audio: ");
                for (int i = 0; i < audioPaths.size(); i++) {
                    if (i > 0) mediaInfo.append(", ");
                    mediaInfo.append(new File(audioPaths.get(i)).getName());
                }
            }
            
            // Log query information
                String logMessage = "Executing RAG query:\n" +
                        "API URL: " + apiUrl + "\n" +
                        "Model: " + model + "\n" +
                        "Knowledge Base: " + knowledgeBase + "\n" +
                        "Retrieval Count: " + searchDepth + "\n" +
                        "System Prompt: " + systemPrompt + "\n" +
                        "User Question: " + userPrompt + mediaInfo.toString();
                LogManager.logD(TAG, logMessage);
                
                // Update UI to show query log
                mainHandler.post(() -> {
                    //updateProgressOnUiThread("Starting knowledge base query...");
                    //updateProgressOnUiThread("Knowledge base: " + knowledgeBase);
                    //updateProgressOnUiThread("Retrieval count: " + searchDepth);
                    updateProgressOnUiThread("\n " + getString(R.string.debug_info_header) + "\n\n" + getString(R.string.user_question, originalUserPrompt));
                });
                
                // Check if knowledge base query is needed
                String valueNone = getString(R.string.common_none);
                String valueNoAvailableKb = getString(R.string.value_no_available_kb);
                if (!valueNone.equals(knowledgeBase) && !valueNoAvailableKb.equals(knowledgeBase) && searchDepth > 0) {
                    String kbInfo = getString(R.string.log_using_kb_for_query, knowledgeBase);
                    LogManager.logD(TAG, kbInfo);
                    // Output RAG info to debug section
                    updateChatMessage("[RAG] Knowledge Base: " + knowledgeBase + "\n");
                    updateChatMessage("[RAG] Retrieval count: " + searchDepth + "\n");
                    
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
                        callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths, audioPaths);
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
                        callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths, audioPaths);
                    }
                } else {
                    // Not using knowledge base or retrieval count is 0, call large model API directly
                    String directMsg = searchDepth == 0 ? "Search depth is 0, skipping knowledge base query, calling LLM directly" : "No knowledge base configured, calling LLM directly";
                    LogManager.logD(TAG, directMsg);
                    // Output bypass info to debug section
                    if (searchDepth == 0) {
                        updateChatMessage("[RAG] Bypassed (search depth = 0)\n");
                    } else {
                        updateChatMessage("[RAG] Bypassed (no knowledge base)\n");
                    }
                    
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
                    callLLMApi(apiUrl, apiKey, model, fullPrompt, imagePaths, audioPaths);
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
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(requireContext());
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

            // Check SQLite database file (unified knowledge graph database)
            File graphDbFile = new File(knowledgeBaseDir, "knowledge_graph.db");
            if (!graphDbFile.exists()) {
                String errorMsg = "Error: SQLite knowledge graph database file does not exist: " + graphDbFile.getAbsolutePath();
                LogManager.logE(TAG, errorMsg);
                updateProgressOnUiThread(errorMsg);
                return relevantDocs;
            } else {
                String fileInfo = "SQLite database file exists: " + graphDbFile.getAbsolutePath() +
                    ", size: " + (graphDbFile.length() / 1024) + "KB, " +
                    "readable: " + graphDbFile.canRead();
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
            final KnowledgeGraphDatabase[] vectorDbRef = new KnowledgeGraphDatabase[1];
            try {
                // Create SQLite vector database handler
                LogManager.logI(TAG, "Starting to create SQLite vector database handler, knowledge base directory: " + knowledgeBaseDir.getAbsolutePath());
                
                try {
                    String dbPath = knowledgeBaseDir.getAbsolutePath() + "/knowledge_graph.db";
                    vectorDbRef[0] = new KnowledgeGraphDatabase(requireContext(), dbPath, knowledgeBase);
                    // KnowledgeGraphDatabase is auto-loaded on construction, no need to call loadDatabase()
                } catch (Exception e) {
                    String errorMsg = "Error occurred while creating or loading SQLite vector database: " + e.getMessage();
                    LogManager.logE(TAG, errorMsg, e);
                    updateProgressOnUiThread(errorMsg);
                    if (vectorDbRef[0] != null) {
                        vectorDbRef[0].close();
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
                            vectorDbRef[0].close();
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
                    vectorDbRef[0].close();
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
        // Delegate to the version with multimodal support (no images/audio)
        callLLMApi(apiUrl, apiKey, model, prompt, null, null);
    }
    
    // Call LLM API to get answer (with multimodal support: images and audio)
    private void callLLMApi(String apiUrl, String apiKey, String model, String prompt, java.util.List<String> imagePaths, java.util.List<String> audioPaths) {
        try {
            // Check global stop flag and task cancelled flag
            if (globalStopFlag || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped/cancelled, aborting LLM API call (globalStopFlag=" + globalStopFlag + ", isTaskCancelled=" + isTaskCancelled + ")");
                resetSendingState();
                return;
            }
            
            // ============================================
            // LLM Section: Check if model needs loading
            // ============================================
            // Check if this is a local model
            boolean isLocalModel = AppConstants.ApiUrl.LOCAL.equals(apiUrl);
            
            if (isLocalModel) {
                // Check if model is already loaded
                com.example.offlineai.api.LocalLlmHandler localHandler = 
                    com.example.offlineai.api.LocalLlmHandler.getInstance(requireContext());
                boolean isModelLoaded = localHandler.isModelReady() && 
                                       model.equals(localHandler.getCurrentModelName());
                
                if (isModelLoaded) {
                    updateChatMessage("[LLM] ReUsing loaded model: " + model + "\n");
                } else {
                    updateChatMessage("[LLM] Loading model: " + model + "\n");
                }
            } else {
                // Online API
                updateChatMessage("[LLM] Using online API: " + model + "\n");
                // CRITICAL: Close debug section for online API (no [TEXT:] marker from online models)
                updateChatMessage("</debug>\n\n");
            }
            
            // ============================================
            // NOTE: For local models, debug section will be closed when [TEXT:]/[IMAGE:]/[AUDIO:] head is detected
            // in onStreamingData callback below
            // For online models, debug section is already closed above
            // ============================================
            
            LogManager.logD(TAG, "Starting to call LLM API: " + apiUrl);
            LogManager.logD(TAG, "Using model: " + model);
            LogManager.logD(TAG, "Prompt length: " + prompt.length() + " characters");
            
            // Debug section will be closed when head marker is detected
            
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
                    
                    // Perform final Markdown rendering and TTS audio setup in UI thread
                    mainHandler.post(() -> {
                        // Check for TTS audio output (local model only)
                        String currentApiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
                        String currentApiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrlDisplay);
                        if (AppConstants.ApiUrl.LOCAL.equals(currentApiUrl)) {
                            try {
                                com.example.offlineai.api.LocalLlmAdapter localAdapter = 
                                    com.example.offlineai.api.LocalLlmAdapter.getInstance(requireContext());
                                String ttsAudioPath = localAdapter.getLastTtsAudioPath();
                                if (ttsAudioPath != null && !chatMessages.isEmpty()) {
                                    // Verify file exists before setting
                                    java.io.File audioFile = new java.io.File(ttsAudioPath);
                                    if (audioFile.exists()) {
                                        ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
                                        if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                                            lastMsg.audioUri = android.net.Uri.fromFile(audioFile);
                                            lastMsg.setHasOmniAudio(true);
                                            LogManager.logI(TAG, "[TTS] Set audio URI to assistant message: " + ttsAudioPath);
                                            // Notify adapter to update the item (safe in UI thread)
                                            if (chatAdapter != null) {
                                                chatAdapter.notifyItemChanged(chatMessages.size() - 1);
                                            }
                                        }
                                    } else {
                                        LogManager.logW(TAG, "[TTS] Audio file not found: " + ttsAudioPath);
                                    }
                                    // Clear it for next inference
                                    localAdapter.clearLastTtsAudioPath();
                                }
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[TTS] Error setting TTS audio to message", e);
                            }
                        }
                        
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
                            
                            // Complete TtsAdapter (if enabled)
                            // CRITICAL: Check both ttsAdapter existence AND enabled state
                            // - ttsAdapter == null: TTS设置为"无"或Omni Native，没有创建adapter
                            // - ttsAdapter.isEnabled() == false: adapter存在但未启用（之前启用过，现在禁用了）
                            boolean hasTtsEnabled = (ttsAdapter != null && ttsAdapter.isEnabled());
                            
                            if (hasTtsEnabled) {
                                // Set TTS generating state and update button
                                isTtsGenerating.set(true);
                                updateButtonText();  // Button shows "生成语音中"
                                LogManager.logI(TAG, "[TTS] TTS generation started, button updated");
                                
                                ttsAdapter.complete();
                                LogManager.logI(TAG, "[TTS] TtsAdapter.complete() called, waiting for callback");
                            } else {
                                // No TTS or TTS not enabled, reset state immediately
                                resetSendingState();
                                LogManager.logD(TAG, "Task completed (no TTS or TTS disabled), all states reset");
                            }
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
                // Track if debug section has been closed
                // CRITICAL: For online API, debug section doesn't exist, so default to true
                // For local API, will be set to false in executeRagQueryWithAsr and closed when [TEXT:] detected
                private boolean debugClosed = !isLocalModel;
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
                    
                    // ============================================
                    // Feed token to TtsAdapter (if enabled)
                    // Filter out debug/performance tags, image/audio tags before sending to TTS
                    // Only send to TTS after debug section is closed (debugClosed=true)
                    // ============================================
                    if (ttsAdapter != null && ttsAdapter.isEnabled() && debugClosed) {
                        String ttsChunk = filterTtsContent(chunk);
                        // Only send to TTS if there's valid text content after filtering
                        if (!ttsChunk.trim().isEmpty()) {
                            ttsAdapter.processToken(ttsChunk);
                        }
                    }
                    
                    // ============================================
                    // CRITICAL: Detect head markers to close debug section
                    // ============================================
                    String filteredChunk = chunk;
                    //LogManager.logD(TAG, "[DEBUG_TRACE] Chunk received: [" + chunk + "], debugClosed=" + debugClosed);
                    
                    // NOTE: TTS state is now managed by callback, no need to handle markers here
                    
                    // Close debug when detecting head markers (local model only)
                    if (!debugClosed && (chunk.contains("[TEXT:]") || 
                                        chunk.contains("[IMAGE:]") || 
                                        chunk.contains("[AUDIO:]"))) {
                        LogManager.logD(TAG, "[DEBUG_TRACE] Detected head marker, closing debug section");
                        updateChatMessage("</debug>\n");
                        debugClosed = true;
                        LogManager.logD(TAG, "[DEBUG_TRACE] Debug section closed, TTS now enabled");
                    }
                    
                    // CRITICAL: Always filter out [TEXT:] and [IMAGE:] markers from ALL chunks
                    // (not just the first one, as online models may send [TEXT:] in response)
                    filteredChunk = filteredChunk.replace("[TEXT:]", "")
                                        .replace("[IMAGE:]", "");
                    // NOTE: [AUDIO:path] is NOT filtered here - it will be handled in updateChatMessage()
                    
                    // Update chat message with filtered chunk
                    updateChatMessage(filteredChunk);
                    
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
                        // CRITICAL: Close debug section if still open (error occurred before head was sent)
                        if (!debugClosed) {
                            updateChatMessage("</debug>\n");
                            debugClosed = true;
                            LogManager.logD(TAG, "Debug section closed due to error");
                        }
                        
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
            // imagePaths and audioPaths are passed from method parameter (prepared by caller)
            // JNI will: 1) Load model 2) Check multimodal support 3) Add tags and merge to prompt 4) Use or ignore media
            com.example.offlineai.api.LlmApiAdapter apiAdapter = new com.example.offlineai.api.LlmApiAdapter(context);
            apiAdapter.callLlmApi(apiUrl, apiKey, model, prompt, imagePaths, audioPaths, callback);
            
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
        
        // Clear current chat folder setting (new conversation will create new folder)
        ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
        LogManager.logD(TAG, "[CHAT_HISTORY] Cleared current chat folder setting for new conversation");
        
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
    private void loadModelAndProcessQuery(String foundModelPath, String query, KnowledgeGraphDatabase vectorDb) throws InterruptedException {
        try {
            // Debug section already opened in executeRagQuery, just continue outputting
            
            // Update progress
            updateChatMessage("[RAG] Loading embedding model...");
            
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
                updateChatMessage("\n[RAG] Embedding Model: " + embeddingModelName);
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
            updateChatMessage("\n[RAG] Model vector dimension: " + embeddingDimension);

            // Check if vector dimension matches knowledge base
            int dbDimension = vectorDb.getMetadata().getEmbeddingDimension();
            LogManager.logD(TAG, "Knowledge base vector dimension: " + dbDimension + ", model vector dimension: " + embeddingDimension);
            updateChatMessage("\n[RAG] Knowledge base vector dimension: " + dbDimension);

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
                updateChatMessage("\n[RAG] Generating query vector...");
                
                // CRITICAL: Use query parameter (from prepareAndSaveUserInput), NOT editTextUserPrompt
                // Input field has been cleared at send moment, reading from it will get empty string
                String userQuery = query;
                
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
                String vectorDebugInfo = "[RAG] Query vector generated, dimension: " + queryVector.length;
                
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
                updateChatMessage("\n[RAG] Searching similar text blocks...");
                
                // Get retrieval count setting
                int retrievalCount = Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
                
                // Search similar text blocks
                List<KnowledgeGraphDatabase.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
                
                // Check global stop flag
                if (userRequestedStop) {
                    LogManager.logD(TAG, "Global stop requested, aborting after database search");
                    updateProgressOnUiThread("Operation stopped by user");
                    return;
                }
                
                // Display retrieval result similarity - show immediately with all scores
                if (!searchResults.isEmpty()) {
                    StringBuilder similarityInfo = new StringBuilder("\n[RAG] Retrieval Similarity (");
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
                
                boolean graphRagEnabled = ConfigManager.isGraphRagEnabled(requireContext());
                if (graphRagEnabled) {
                    updateChatMessage("\n[RAG] Graph RAG mode enabled, combining vector and graph results...");
                    processGraphRagResults(userQuery, searchResults, vectorDb, retrievalCount);
                } else {
                    int rerankCount = ConfigManager.getRerankCount(requireContext());
                    String rerankerModelPath = getRerankerModelPath(vectorDb);

                    if (rerankCount > 0 && rerankerModelPath != null && !rerankerModelPath.isEmpty()) {
                        LogManager.logI(TAG, "Using reranker model with rerank count: " + rerankCount);
                        updateChatMessage("\n[RAG] Using reranker model to optimize results...");
                        try {
                            LogManager.logI(TAG, "[DEBUG] About to call processWithReranker - query.len=" + userQuery.length() + ", results=" + searchResults.size() + ", path=" + rerankerModelPath + ", vectorDb=" + (vectorDb != null ? "not_null" : "null"));
                            processWithReranker(userQuery, searchResults, rerankerModelPath, vectorDb);
                            LogManager.logI(TAG, "[DEBUG] processWithReranker returned successfully");
                        } catch (InterruptedException ie) {
                            LogManager.logI(TAG, "Reranker process interrupted: " + ie.getMessage());
                            throw ie;
                        }
                    } else {
                        if (rerankCount == 0) {
                            LogManager.logI(TAG, "Rerank count is 0, skipping reranking and using vector search results directly");
                            updateChatMessage("\n[RAG] Bypassed reranker (rerank count = 0)");
                        } else {
                            LogManager.logD(TAG, "No reranker model configured, using vector search results");
                            updateChatMessage("\n[RAG] Bypassed reranker (no model configured)");
                        }
                        processVectorSearchResults(searchResults);
                    }
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
    private String getRerankerModelPath(KnowledgeGraphDatabase vectorDb) {
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
    private void processWithReranker(String query, List<KnowledgeGraphDatabase.SearchResult> searchResults, 
                                   String rerankerModelPath, KnowledgeGraphDatabase vectorDb) throws InterruptedException, Exception {
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
            for (KnowledgeGraphDatabase.SearchResult result : searchResults) {
                documents.add(result.content);
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
    
    private synchronized HanLpNerHandler getOrCreateGraphNerHandler() {
        String dictPath = ConfigManager.getString(requireContext(), ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null);
        String valueNone = getString(R.string.common_none);
        String normalizedPath = null;
        if (dictPath != null && !dictPath.isEmpty() && !valueNone.equals(dictPath)) {
            normalizedPath = dictPath;
        }
        if (graphNerHandler != null) {
            boolean samePath = (graphNerDictPath == null && normalizedPath == null)
                    || (graphNerDictPath != null && graphNerDictPath.equals(normalizedPath));
            if (samePath) {
                return graphNerHandler;
            }
            graphNerHandler.release();
            graphNerHandler = null;
            graphNerDictPath = null;
        }
        graphNerHandler = new HanLpNerHandler(normalizedPath);
        graphNerDictPath = normalizedPath;

        if (normalizedPath == null || normalizedPath.isEmpty()) {
            String msg = "Dictionary: None";
            LogManager.logI(TAG, "[GRAPH_RAG] " + msg);
            updateProgressOnUiThread(msg);
        } else {
            String dictFileName = new File(normalizedPath).getName();
            if (graphNerHandler.isDictionaryLoaded()) {
                int wordCount = graphNerHandler.getLoadedWordCount();
                String msg = "Dictionary: " + dictFileName + " (loaded " + wordCount + " words)";
                LogManager.logI(TAG, "[GRAPH_RAG] " + msg);
                updateProgressOnUiThread(msg);
            } else {
                String baseMsg = "Dictionary: " + dictFileName;
                LogManager.logI(TAG, "[GRAPH_RAG] " + baseMsg);
                updateProgressOnUiThread(baseMsg);
                String err = graphNerHandler.getDictionaryErrorMessage();
                if (err != null && !err.isEmpty()) {
                    String errMsg = "Dictionary load error: " + err;
                    LogManager.logE(TAG, "[GRAPH_RAG] " + errMsg);
                    updateProgressOnUiThread(errMsg);
                }
            }
        }

        return graphNerHandler;
    }

    private List<HanLpNerHandler.NerResult.Entity> extractQueryEntities(String userQuery) {
        List<HanLpNerHandler.NerResult.Entity> entities = new ArrayList<>();
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return entities;
        }
        try {
            HanLpNerHandler handler = getOrCreateGraphNerHandler();
            if (handler == null) {
                return entities;
            }
            HanLpNerHandler.NerResult nerResult = handler.extractEntities(userQuery);
            if (nerResult != null && nerResult.isSuccess()) {
                entities = nerResult.getEntities();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[GRAPH_RAG] Query NER failed: " + e.getMessage(), e);
        }
        return entities;
    }

    private String normalizeEntityText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    private void processGraphRagResults(String userQuery, List<KnowledgeGraphDatabase.SearchResult> searchResults,
                                        KnowledgeGraphDatabase vectorDb, int retrievalCount) {
        if (userRequestedStop || isTaskCancelled) {
            LogManager.logI(TAG, "Task stopped/cancelled, aborting processGraphRagResults");
            updateProgressOnUiThread("Operation stopped by user");
            return;
        }
        if (searchResults == null || searchResults.isEmpty()) {
            processVectorSearchResults(searchResults);
            return;
        }
        try {
            HanLpNerHandler graphNerHandler = null;
            // Load stopwords matcher for query-time cleaning
            String stopwordsPath = ConfigManager.getGraphStopwordsPath(requireContext());
            GraphStopwordsMatcher stopwordsMatcher = null;
            if (stopwordsPath != null && !stopwordsPath.isEmpty()) {
                try {
                    stopwordsMatcher = GraphStopwordsMatcher.loadFromFile(stopwordsPath);
                    LogManager.logD(TAG, "[GRAPH_RAG][STOPWORDS] Loaded stopwords for query-time cleaning");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[GRAPH_RAG][STOPWORDS] Failed to load stopwords file: " + stopwordsPath, e);
                }
            }

            // Load hub entities for query-time hub filtering (read-only)
            int hubThreshold = ConfigManager.getGraphHubThresholdQuery(requireContext());
            Set<String> protectedEntities = null;
            try {
                graphNerHandler = getOrCreateGraphNerHandler();
                if (graphNerHandler != null) {
                    protectedEntities = graphNerHandler.getCustomDictionaryWords();
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[GRAPH_RAG] Failed to load protected entities from custom dictionary: " + e.getMessage(), e);
            }

            Set<String> hubEntities = vectorDb.getHubEntities(hubThreshold, protectedEntities);
            if (!hubEntities.isEmpty()) {
                LogManager.logD(TAG, "[GRAPH_RAG][HUB_QUERY] Query-time hub set size=" + hubEntities.size());
            }

            List<HanLpNerHandler.NerResult.Entity> queryEntities = extractQueryEntities(userQuery);
            float confidenceThreshold = ConfigManager.getGraphEntityConfidenceThreshold(requireContext());
            Set<String> queryEntityTexts = new HashSet<>();
            List<String> seedOrder = new ArrayList<>();
            int aliasNormalizedCount = 0;
            for (HanLpNerHandler.NerResult.Entity e : queryEntities) {
                if (e == null) continue;
                String normalized = normalizeEntityText(e.text);
                if (graphNerHandler != null && normalized != null) {
                    String canonical = graphNerHandler.normalizeTextForGraph(normalized);
                    if (canonical == null) {
                        continue;
                    }
                    if (!canonical.equals(normalized)) {
                        aliasNormalizedCount++;
                    }
                    normalized = canonical;
                }
                if (normalized == null) continue;
                if (e.confidence < confidenceThreshold) continue;
                if (stopwordsMatcher != null && stopwordsMatcher.matches(normalized)) continue;
                if (hubEntities.contains(normalized)) continue;
                if (!queryEntityTexts.contains(normalized)) {
                    queryEntityTexts.add(normalized);
                    seedOrder.add(normalized);
                }
            }

            List<Long> topChunkIds = new ArrayList<>();
            int maxEntitySource = Math.min(5, searchResults.size());
            for (int i = 0; i < maxEntitySource; i++) {
                topChunkIds.add(searchResults.get(i).id);
            }
            Map<Long, List<String>> entitiesForTopChunks = vectorDb.getEntitiesForChunks(topChunkIds);
            for (List<String> list : entitiesForTopChunks.values()) {
                if (list == null) continue;
                for (String t : list) {
                    String normalized = normalizeEntityText(t);
                    if (graphNerHandler != null && normalized != null) {
                        String canonical = graphNerHandler.normalizeTextForGraph(normalized);
                        if (canonical == null) {
                            continue;
                        }
                        if (!canonical.equals(normalized)) {
                            aliasNormalizedCount++;
                        }
                        normalized = canonical;
                    }
                    if (normalized == null) continue;
                    if (stopwordsMatcher != null && stopwordsMatcher.matches(normalized)) continue;
                    if (hubEntities.contains(normalized)) continue;
                    if (!queryEntityTexts.contains(normalized)) {
                        queryEntityTexts.add(normalized);
                        seedOrder.add(normalized);
                    }
                }
            }

            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped/cancelled after seed collection");
                updateProgressOnUiThread("Operation stopped by user");
                return;
            }

            if (aliasNormalizedCount > 0) {
                LogManager.logD(TAG, String.format("[GRAPH_ALIAS] Query-time alias normalization applied to %d entity texts", aliasNormalizedCount));
            }

            Set<String> seedEntities = new HashSet<>();
            for (String text : seedOrder) {
                seedEntities.add(text);
                if (seedEntities.size() >= GRAPH_RAG_MAX_SEED_ENTITIES) {
                    break;
                }
            }

            if (seedEntities.isEmpty()) {
                LogManager.logI(TAG, "[GRAPH_RAG] No valid seed entities after filtering, fallback to vector-only results");
                processVectorSearchResults(searchResults);
                return;
            }

            // Use final seed set for overlap calculation as well
            queryEntityTexts.clear();
            queryEntityTexts.addAll(seedEntities);
            int minEdgeWeight = ConfigManager.getGraphMinEdgeWeight(requireContext());
            int maxExpandEntities = ConfigManager.getGraphMaxExpandEntities(requireContext());
            List<KnowledgeGraphDatabase.ConnectedEntity> connectedEntities = vectorDb.getConnectedEntities(seedEntities, minEdgeWeight, maxExpandEntities);
            Map<String, Integer> graphWeightMap = new HashMap<>();
            for (KnowledgeGraphDatabase.ConnectedEntity ce : connectedEntities) {
                if (ce == null || ce.entityText == null) {
                    continue;
                }
                String normalized = normalizeEntityText(ce.entityText);
                if (graphNerHandler != null && normalized != null) {
                    String canonical = graphNerHandler.normalizeTextForGraph(normalized);
                    if (canonical == null) {
                        continue;
                    }
                    if (!canonical.equals(normalized)) {
                        aliasNormalizedCount++;
                    }
                    normalized = canonical;
                }
                if (normalized == null) {
                    continue;
                }
                if (stopwordsMatcher != null && stopwordsMatcher.matches(normalized)) {
                    continue;
                }
                if (hubEntities.contains(normalized)) {
                    continue;
                }
                Integer existing = graphWeightMap.get(normalized);
                if (existing == null || ce.weight > existing) {
                    graphWeightMap.put(normalized, ce.weight);
                }
            }

            Set<String> allEntityTexts = new HashSet<>(seedEntities);
            for (KnowledgeGraphDatabase.ConnectedEntity ce : connectedEntities) {
                if (ce == null || ce.entityText == null) {
                    continue;
                }
                String normalized = normalizeEntityText(ce.entityText);
                if (graphNerHandler != null && normalized != null) {
                    String canonical = graphNerHandler.normalizeTextForGraph(normalized);
                    if (canonical == null) {
                        continue;
                    }
                    if (!canonical.equals(normalized)) {
                        aliasNormalizedCount++;
                    }
                    normalized = canonical;
                }
                if (normalized == null) {
                    continue;
                }
                if (stopwordsMatcher != null && stopwordsMatcher.matches(normalized)) {
                    continue;
                }
                if (hubEntities.contains(normalized)) {
                    continue;
                }
                allEntityTexts.add(normalized);
            }

            List<String> entityTextList = new ArrayList<>(allEntityTexts);
            List<Long> graphChunkIds = vectorDb.getChunkIdsByEntities(entityTextList);

            int maxExpandChunks = ConfigManager.getGraphMaxExpandChunks(requireContext());
            if (maxExpandChunks > 0 && graphChunkIds.size() > maxExpandChunks) {
                graphChunkIds = graphChunkIds.subList(0, maxExpandChunks);
            }

            List<KnowledgeGraphDatabase.SearchResult> graphChunks = vectorDb.getChunksByIds(graphChunkIds);

            if (userRequestedStop || isTaskCancelled) {
                LogManager.logI(TAG, "Task stopped/cancelled after graph expansion");
                updateProgressOnUiThread("Operation stopped by user");
                return;
            }

            Map<Long, GraphRagCandidate> candidateMap = new HashMap<>();
            for (KnowledgeGraphDatabase.SearchResult r : searchResults) {
                GraphRagCandidate c = new GraphRagCandidate();
                c.result = r;
                c.vectorScore = r.similarity;
                c.graphScore = 0.0f;
                c.finalScore = r.similarity;
                c.entityOverlap = 0;
                candidateMap.put(r.id, c);
            }
            for (KnowledgeGraphDatabase.SearchResult r : graphChunks) {
                if (!candidateMap.containsKey(r.id)) {
                    GraphRagCandidate c = new GraphRagCandidate();
                    c.result = r;
                    c.vectorScore = r.similarity;
                    c.graphScore = 0.0f;
                    c.finalScore = r.similarity;
                    c.entityOverlap = 0;
                    candidateMap.put(r.id, c);
                }
            }

            List<Long> allChunkIds = new ArrayList<>(candidateMap.keySet());
            Map<Long, List<String>> entitiesForAll = vectorDb.getEntitiesForChunks(allChunkIds);

            float alpha;
            float beta;
            float gamma;
            int preset = ConfigManager.getGraphRagWeightPreset(requireContext());
            switch (preset) {
                case 0: // 向量优先
                    alpha = 0.9f;
                    beta = 0.1f;
                    gamma = 0.0f;
                    break;
                case 2: // 图谱增强
                    alpha = 0.4f;
                    beta = 0.4f;
                    gamma = 0.2f;
                    break;
                case 1:
                default: // 平衡
                    alpha = 0.7f;
                    beta = 0.2f;
                    gamma = 0.1f;
                    break;
            }

            List<GraphRagCandidate> candidates = new ArrayList<>();
            float vecMin = Float.MAX_VALUE;
            float vecMax = -Float.MAX_VALUE;
            float graphMin = Float.MAX_VALUE;
            float graphMax = -Float.MAX_VALUE;
            int overlapMin = Integer.MAX_VALUE;
            int overlapMax = Integer.MIN_VALUE;

            // First pass: compute raw graph and overlap scores and collect min/max ranges
            for (Map.Entry<Long, GraphRagCandidate> entry : candidateMap.entrySet()) {
                Long chunkId = entry.getKey();
                GraphRagCandidate c = entry.getValue();
                List<String> ents = entitiesForAll.get(chunkId);
                if (ents != null) {
                    int overlap = 0;
                    float gScore = 0.0f;
                    for (String t : ents) {
                        String normalized = normalizeEntityText(t);
                        if (normalized == null) {
                            continue;
                        }
                        if (stopwordsMatcher != null && stopwordsMatcher.matches(normalized)) {
                            continue;
                        }
                        if (hubEntities.contains(normalized)) {
                            continue;
                        }
                        if (queryEntityTexts.contains(normalized)) {
                            overlap++;
                        }
                        Integer w = graphWeightMap.get(normalized);
                        if (w != null) {
                            gScore += w;
                        }
                    }
                    c.entityOverlap = overlap;
                    if (gScore < 0.0f) {
                        gScore = 0.0f;
                    }
                    // Compress graph score to reduce dominance
                    c.graphScore = (float) Math.log1p(gScore);
                } else {
                    c.entityOverlap = 0;
                    c.graphScore = 0.0f;
                }

                if (c.vectorScore < vecMin) vecMin = c.vectorScore;
                if (c.vectorScore > vecMax) vecMax = c.vectorScore;
                if (c.graphScore < graphMin) graphMin = c.graphScore;
                if (c.graphScore > graphMax) graphMax = c.graphScore;
                if (c.entityOverlap < overlapMin) overlapMin = c.entityOverlap;
                if (c.entityOverlap > overlapMax) overlapMax = c.entityOverlap;

                candidates.add(c);
            }

            // Second pass: normalize scores and compute final fused score
            for (GraphRagCandidate c : candidates) {
                float vecNorm = 0.0f;
                if (vecMax > vecMin) {
                    vecNorm = (c.vectorScore - vecMin) / (vecMax - vecMin);
                } else if (vecMax > 0.0f) {
                    vecNorm = 1.0f;
                }

                float graphNorm = 0.0f;
                if (graphMax > graphMin) {
                    graphNorm = (c.graphScore - graphMin) / (graphMax - graphMin);
                } else if (graphMax > 0.0f) {
                    graphNorm = 1.0f;
                }

                float overlapNorm = 0.0f;
                if (overlapMax > overlapMin) {
                    overlapNorm = (float) (c.entityOverlap - overlapMin) / (float) (overlapMax - overlapMin);
                } else if (overlapMax > 0) {
                    overlapNorm = 1.0f;
                }

                c.finalScore = alpha * vecNorm + beta * graphNorm + gamma * overlapNorm;
            }

            candidates.sort((a, b) -> Float.compare(b.finalScore, a.finalScore));
            int limit = Math.min(retrievalCount, candidates.size());
            List<String> fusedDocs = new ArrayList<>();
            StringBuilder scoreDebug = new StringBuilder("\n[RAG][Graph] Fused candidates (top " + limit + "):\n");
            StringBuilder similarityInfoBuilder = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                GraphRagCandidate c = candidates.get(i);
                fusedDocs.add(c.result.content);
                scoreDebug.append("#").append(i + 1)
                        .append(" vec=").append(String.format("%.3f", c.vectorScore))
                        .append(" graph=").append(String.format("%.3f", c.graphScore))
                        .append(" overlap=").append(c.entityOverlap)
                        .append(" final=").append(String.format("%.3f", c.finalScore))
                        .append("\n");
                similarityInfoBuilder.append(String.format("%.3f", c.finalScore));
                if (i < limit - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            updateChatMessage(scoreDebug.toString());
            LogManager.logI(TAG, "[GRAPH_RAG] Fused scores: " + similarityInfoBuilder.toString());

            // Build a SearchResult list in fused order so that we can reuse the existing reranker pipeline.
            List<KnowledgeGraphDatabase.SearchResult> fusedResults = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                GraphRagCandidate c = candidates.get(i);
                fusedResults.add(c.result);
            }

            int rerankCount = ConfigManager.getRerankCount(requireContext());
            String rerankerModelPath = getRerankerModelPath(vectorDb);

            if (rerankCount > 0 && rerankerModelPath != null && !rerankerModelPath.isEmpty()) {
                // Use reranker for Graph RAG as well: 0 = disabled, N > 0 = rerank and keep top N documents.
                updateChatMessage("\n[RAG] Using reranker model to optimize Graph RAG results...");
                try {
                    processWithReranker(userQuery, fusedResults, rerankerModelPath, vectorDb);
                } catch (InterruptedException ie) {
                    LogManager.logI(TAG, "Graph RAG reranker process interrupted: " + ie.getMessage());
                    throw ie;
                }
            } else {
                // No reranker configured or rerank disabled: keep Graph RAG fused ranking directly.
                synchronized (this) {
                    this.similarityInfo = "GraphRAG final scores: " + similarityInfoBuilder.toString();
                    this.relevantDocuments = fusedDocs;
                }
                LogManager.logD(TAG, "Graph RAG fused results processing completed without reranker, document count: " + fusedDocs.size());
            }

        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to process Graph RAG results: " + e.getMessage(), e);
            updateProgressOnUiThread("Failed to process Graph RAG results: " + e.getMessage());
        }
    }

    /**
     * Process vector search results (without reranking)
     */
    private void processVectorSearchResults(List<KnowledgeGraphDatabase.SearchResult> searchResults) {
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
                KnowledgeGraphDatabase.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.content);
                
                // Log detailed information
                String resultInfo = "Similarity: " + result.similarity + ", Text: " + result.content.substring(0, Math.min(50, result.content.length())) + "...";
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
            String rerankerScores = "\n[RAG] Reranker Similarity (" + actualResultCount + " results): " + similarityInfoBuilder.toString();
            updateChatMessage(rerankerScores);
            LogManager.logI(TAG, "Reranker scores: " + rerankerScores);

            // Save rerank information
            synchronized (this) {
                this.similarityInfo = "Reranked Results - " + similarityInfoBuilder.toString();
                this.relevantDocuments = relevantDocs;
            }
            
            LogManager.logD(TAG, "Reranked results processing completed, actual document count used: " + relevantDocs.size());
            
            updateChatMessage("\n[RAG] Reranking optimization completed, found " + relevantDocs.size() + " relevant contents");
            
            // Debug section will be closed in callLLMApi, not here
            
            // [Fix] No longer call continueRagQueryAfterReranking to avoid duplicate LLM API calls
            // executeRagQuery method will wait for relevantDocuments to be set and then call callLLMApi itself
            // continueRagQueryAfterReranking();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to process reranked results: " + e.getMessage(), e);
            updateProgressOnUiThread("Failed to process reranked results: " + e.getMessage());
        }
    }
    
    // Show model selection dialog
    private void selectModelAndContinueQuery(String originalModel, List<String> availableModels, String knowledgeBase, String embeddingModelPath, KnowledgeGraphDatabase vectorDb) {
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
    private void continueQueryWithSelectedModel(String selectedModel, String knowledgeBase, String embeddingModelPath, KnowledgeGraphDatabase vectorDb) {
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
                        KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
                        metadata.setModeldir("");
                        vectorDb.updateMetadata(metadata);
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
                            KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
                            metadata.setModeldir(selectedModel);
                            vectorDb.updateMetadata(metadata);
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
            List<KnowledgeGraphDatabase.SearchResult> searchResults = vectorDb.searchSimilar(queryVector, retrievalCount);
            
            // Extract relevant documents
            List<String> relevantDocs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder("Found similar text blocks:\n");
            for (int i = 0; i < searchResults.size(); i++) {
                KnowledgeGraphDatabase.SearchResult result = searchResults.get(i);
                relevantDocs.add(result.content);
                
                // Record detailed information to log
                String resultInfo = "Similarity: " + result.similarity + ", text: " + result.content.substring(0, Math.min(50, result.content.length())) + "...";
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
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(requireContext());
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            
            // Update database metadata
            KnowledgeGraphDatabase vectorDb = null;
            try {
                String dbPath = knowledgeBaseDir.getAbsolutePath() + "/knowledge_graph.db";
                vectorDb = new KnowledgeGraphDatabase(requireContext(), dbPath, "unknown");
                // KnowledgeGraphDatabase is auto-loaded on construction
                
                // Get selected model file name
                String selectedModelName = new File(selectedModel).getName();
                
                // Update model information in database metadata
                KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
                metadata.setModeldir(selectedModelName);
                if (vectorDb.updateMetadata(metadata)) {
                    LogManager.logD(TAG, "Updated model information in database metadata: " + selectedModelName);
                    LogManager.logD(TAG, "Saved database metadata");
                } else {
                    LogManager.logE(TAG, "Failed to update model information in database metadata");
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
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] === onResume() called ===");
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] chatAdapter: " + chatAdapter);
        LogManager.logD(TAG, "[LIFECYCLE_DEBUG] recyclerViewChat: " + recyclerViewChat);
        if (recyclerViewChat != null) {
            LogManager.logD(TAG, "[LIFECYCLE_DEBUG] recyclerViewChat.getAdapter(): " + recyclerViewChat.getAdapter());
        }
        
        // Re-apply font size when page resumes, so it takes effect immediately after modification in settings page
        applyGlobalTextSize();
        
        // Check if chat folder changed (e.g., user switched conversation from history)
        String configFolder = ConfigManager.getString(getContext(), 
            ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
        if (!configFolder.equals(currentChatFolderPath)) {
            LogManager.logD(TAG, "[CHAT_HISTORY] Detected conversation switch: " + configFolder);
            currentChatFolderPath = configFolder;
            
            // Reload conversation
            loadChatHistory();
            
            // Update media adapter folder path
            if (mediaThumbnailAdapter != null && currentChatFolderPath != null) {
                mediaThumbnailAdapter.setChatFolderPath(currentChatFolderPath);
            }
        }
        
        // Sync backend preference from settings (in case user changed it in SettingsFragment)
        // Always sync, regardless of visibility, so it's ready when user switches to local model
        if (spinnerBackendPreference != null) {
            isUpdatingUiFromConfig = true;
            String backendPreference = ConfigManager.getString(getContext(), 
                ConfigManager.KEY_USE_GPU, "CPU");
            
            // Update spinner selection
            int selectedIndex = 0;
            for (int i = 0; i < BACKEND_VALUES.length; i++) {
                if (BACKEND_VALUES[i].equals(backendPreference)) {
                    selectedIndex = i;
                    break;
                }
            }
            if (spinnerBackendPreference.getSelectedItemPosition() != selectedIndex) {
                spinnerBackendPreference.setSelection(selectedIndex);
                LogManager.logD(TAG, "Synced backend preference from settings: " + backendPreference);
            }
            isUpdatingUiFromConfig = false;
        }
        
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
        
        // Media file picker (images, audio, video)
        pickMediaFile = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        handleMediaFileSelected(uri);
                    } else {
                        LogManager.logI(TAG, "Pick media file - no file selected");
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
        
        // Initialize audio recording permission launcher
        recordAudioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // Permission granted, retry recording
                        startVoiceRecording();
                    } else {
                        // Permission denied
                        Toast.makeText(requireContext(), R.string.permission_record_audio_denied, Toast.LENGTH_LONG).show();
                        LogManager.logW(TAG, "Audio recording permission denied by user");
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
                // Add "Media" and "Camera" menu items
                menu.add(0, android.R.id.button1, 0, R.string.menu_pick_media);
                menu.add(0, android.R.id.button2, 1, R.string.menu_take_photo);
                return true;
            }
            
            @Override
            public boolean onPrepareActionMode(ActionMode mode, android.view.Menu menu) {
                return false;
            }
            
            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getTitle().equals(getString(R.string.menu_pick_media))) {
                    // Launch media picker (images, audio, video)
                    launchMediaPicker();
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
        if (mediaThumbnailAdapter.getMediaCount() >= MAX_IMAGES) {
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
            mediaThumbnailAdapter.addImage(imageUri);
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
     * Launch media picker for images, audio, and video files
     */
    private void launchMediaPicker() {
        LogManager.logI(TAG, "Launch media picker from selection menu");
        
        // Support images, audio (wav, mp3, m4a), and video (mp4)
        String[] mimeTypes = {
            "image/*",
            "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4",
            "video/mp4"
        };
        
        pickMediaFile.launch(mimeTypes);
    }
    
    /**
     * Handle selected media file (image, audio, or video)
     */
    private void handleMediaFileSelected(Uri fileUri) {
        try {
            String mimeType = requireContext().getContentResolver().getType(fileUri);
            LogManager.logI(TAG, "Media file selected: " + fileUri + ", type: " + mimeType);
            
            if (mimeType == null) {
                Toast.makeText(requireContext(), R.string.toast_unsupported_file_type, Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (mimeType.startsWith("image/")) {
                // Handle image
                handleImageSelected(fileUri);
            } else if (mimeType.startsWith("audio/")) {
                // Handle audio file
                handleAudioFileSelected(fileUri);
            } else if (mimeType.startsWith("video/")) {
                // Handle video file (reserved for future)
                Toast.makeText(requireContext(), "Video support coming soon", Toast.LENGTH_SHORT).show();
                LogManager.logI(TAG, "Video file selected (not yet supported): " + fileUri);
            } else {
                Toast.makeText(requireContext(), R.string.toast_unsupported_file_type, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.toast_unsupported_file_type, Toast.LENGTH_SHORT).show();
            LogManager.logE(TAG, "Error handling media file: " + e.getMessage());
        }
    }
    
    /**
     * Handle selected audio file: decode and append to cache WAV asynchronously
     */
    private void handleAudioFileSelected(Uri audioUri) {
        // Check if max audio files reached (3 max)
        if (mediaThumbnailAdapter.getAudioCount() >= 3) {
            Toast.makeText(requireContext(), "最多支持3个音频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check if already decoding
        if (isAudioDecoding.get()) {
            Toast.makeText(requireContext(), "音频解压中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // CRITICAL: If this is the first audio file, clear cache WAV to ensure "全新"
        int currentAudioCount = mediaThumbnailAdapter.getAudioCount();
        if (currentAudioCount == 0) {
            File cacheWav = AudioService.getCacheWavFile(requireContext());
            if (cacheWav.exists()) {
                boolean deleted = cacheWav.delete();
                LogManager.logI(TAG, "[AUDIO_DECODE] First audio file, cleared cache WAV: " + deleted);
            }
        } else {
            LogManager.logI(TAG, "[AUDIO_DECODE] Subsequent audio file (#" + (currentAudioCount + 1) + "), will append to cache WAV");
        }
        
        // Add audio to media adapter (for display)
        mediaThumbnailAdapter.addAudio(audioUri);
        recyclerViewImageThumbnails.setVisibility(View.VISIBLE);
        
        // Start async decoding
        isAudioDecoding.set(true);
        decodingProgress.set(0);
        updateButtonText();
        
        LogManager.logI(TAG, "[AUDIO_DECODE] Starting async decode for: " + audioUri);
        
        audioCompressionExecutor.submit(() -> {
            try {
                boolean success = MediaThumbnailAdapter.decodeAndAppendAudio(
                    requireContext(), 
                    audioUri, 
                    new MediaThumbnailAdapter.AudioDecodeCallback() {
                        @Override
                        public void onProgress(int progress) {
                            decodingProgress.set(progress);
                            mainHandler.post(() -> updateButtonText());
                        }
                        
                        @Override
                        public void onComplete(float duration) {
                            LogManager.logI(TAG, "[AUDIO_DECODE] Decode complete, duration: " + String.format("%.1fs", duration));
                            mainHandler.post(() -> {
                                Toast.makeText(requireContext(), 
                                    "音频已添加 (" + String.format("%.1fs", duration) + ")", 
                                    Toast.LENGTH_SHORT).show();
                            });
                        }
                        
                        @Override
                        public void onError(String error) {
                            LogManager.logE(TAG, "[AUDIO_DECODE] Decode failed: " + error);
                            mainHandler.post(() -> {
                                Toast.makeText(requireContext(), "音频解压失败: " + error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                );
                
                if (!success) {
                    LogManager.logE(TAG, "[AUDIO_DECODE] Decode failed");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[AUDIO_DECODE] Exception during decode", e);
            } finally {
                isAudioDecoding.set(false);
                decodingProgress.set(0);
                mainHandler.post(() -> updateButtonText());
            }
        });
    }
    
    /**
     * Launch camera for taking photo
     * Checks permission first, then starts camera capture
     */
    private void launchCamera() {
        // Check if max images reached
        if (mediaThumbnailAdapter.getMediaCount() >= MAX_IMAGES) {
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
            
            // CRITICAL FIX: Accumulate to lastMsg.text directly, no separate buffer
            // The buffer approach was causing duplicate rendering
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
            
            // Check for audio marker [AUDIO:path] and remove from UI display
            // Audio is handled by TTS callback, marker should not be shown in chat
            if (newText.contains("[AUDIO:")) {
                int startIdx = newText.indexOf("[AUDIO:");
                int endIdx = newText.indexOf("]", startIdx);
                if (endIdx > startIdx) {
                    String wavPath = newText.substring(startIdx + 7, endIdx);
                    
                    // Check if auto-play is enabled
                    boolean autoPlayEnabled = ConfigManager.getTtsAutoPlay(requireContext());
                    
                    if (!autoPlayEnabled) {
                        // No auto-play: compress immediately
                        LogManager.logI(TAG, "[AUDIO] No auto-play, compressing immediately");
                        handleTtsAudioComplete(wavPath);
                    }
                    // If auto-play: will be handled in playAudioDirect's onCompletionListener
                    
                    // Remove marker from text
                    newText = newText.substring(0, startIdx) + newText.substring(endIdx + 1);
                    LogManager.logI(TAG, "[AUDIO] Marker detected: " + wavPath);
                }
            }
            
            lastMsg.text = newText;
            
            // Parse collapsible sections
            CollapsibleTextParser.INSTANCE.parseAndPopulate(newText, lastMsg);
            
            lastMsg.setLoading(false);
            
            // Incremental update
            chatAdapter.updateRecentItem(lastMsg);
            
            // Smart auto-scroll: only scroll if user is at bottom
            smartScrollToBottom();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to update chat message", e);
        }
    }
    
    /**
     * Check if RecyclerView is at bottom
     */
    private boolean isAtBottom(LinearLayoutManager layoutManager) {
        if (layoutManager == null || chatMessages.isEmpty()) return true;
        
        int lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition();
        int lastItemPosition = chatMessages.size() - 1;
        
        // At bottom if last item is completely visible
        return lastVisiblePosition == lastItemPosition;
    }
    /**
     * Smart scroll to bottom with debouncing
     * Only scrolls if user hasn't manually scrolled away
     */
    private void smartScrollToBottom() {
        // Don't scroll if user has manually scrolled away
        if (userScrolledAway) {
            LogManager.logD(TAG, "[SCROLL] Skip auto-scroll: userScrolledAway=true");
            return;
        }
        
        // Scroll immediately without debouncing (for streaming updates)
        try {
            if (!chatMessages.isEmpty()) {
                // Use scrollToPosition for instant scroll (no animation)
                recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
                // Removed: [SCROLL] Auto-scrolled log (too verbose, 1000+ times per session)
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[SCROLL] Failed to scroll to bottom", e);
        }
    }
    
    /**
     * Save chat history to markdown file
     */
// ... (unchanged code)
    private void saveChatHistory() {
        try {
            String currentFolder = ConfigManager.getString(getContext(), 
                ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            
            if (currentFolder.isEmpty()) {
                LogManager.logD(TAG, "[CHAT_HISTORY] No current chat folder, skipping save");
                return;
            }
            
            if (chatMessages == null || chatMessages.isEmpty()) {
                LogManager.logD(TAG, "[CHAT_HISTORY] No messages to save");
                return;
            }
            
            boolean success = ChatHistoryManager.saveConversation(getContext(), chatMessages, currentFolder);
            if (success) {
                LogManager.logD(TAG, "[CHAT_HISTORY] Conversation saved successfully");
            } else {
                LogManager.logE(TAG, "[CHAT_HISTORY] Failed to save conversation");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[CHAT_HISTORY] Error saving conversation", e);
        }
    }
    
    /**
     * Load chat history from markdown file
     * Anti-foolproof mechanism: If folder doesn't exist, silently maintain empty chat UI
     * PUBLIC: Called by ChatHistoryFragment when switching conversations
     */
    public void loadChatHistory() {
        try {
            String currentFolder = ConfigManager.getString(getContext(), 
                ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] === loadChatHistory() called ===");
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] currentFolder: " + currentFolder);
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatMessages.size() before: " + chatMessages.size());
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatAdapter == null: " + (chatAdapter == null));
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] recyclerViewChat == null: " + (recyclerViewChat == null));
            
            if (currentFolder.isEmpty()) {
                LogManager.logD(TAG, "[CHAT_HISTORY] No previous chat history to load");
                return;
            }
            
            // Anti-foolproof: Check if folder exists before loading
            File folder = new File(currentFolder);
            if (!folder.exists()) {
                LogManager.logW(TAG, "[CHAT_HISTORY] Chat folder does not exist, clearing setting and maintaining empty UI");
                ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                // Silently maintain empty chat UI, no toast to avoid annoying user
                return;
            }
            
            // Try to load conversation
            List<ChatDataItem> history = ChatHistoryManager.loadConversation(getContext(), currentFolder);
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] history loaded: " + (history != null ? history.size() : "null") + " items");
            
            if (history != null && !history.isEmpty()) {
                chatMessages.clear();
                chatMessages.addAll(history);
                LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatMessages.size() after addAll: " + chatMessages.size());
                
                if (chatAdapter != null) {
                    LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] Calling chatAdapter.updateModelNameAndItems()");
                    chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
                    LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatAdapter.getItemCount(): " + chatAdapter.getItemCount());
                } else {
                    LogManager.logE(TAG, "[CHAT_HISTORY_DEBUG] ❌ chatAdapter is NULL! Cannot update UI!");
                }
                
                LogManager.logI(TAG, "[CHAT_HISTORY] Loaded " + history.size() + " messages from history");
                
                // Auto-scroll to bottom after loading history
                if (recyclerViewChat != null) {
                    LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] RecyclerView adapter: " + recyclerViewChat.getAdapter());
                    recyclerViewChat.post(() -> {
                        recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
                        LogManager.logD(TAG, "[CHAT_HISTORY] Auto-scrolled to bottom");
                    });
                } else {
                    LogManager.logE(TAG, "[CHAT_HISTORY_DEBUG] ❌ recyclerViewChat is NULL!");
                }
                // Successfully loaded, no toast needed (silent load for better UX)
            } else {
                LogManager.logD(TAG, "[CHAT_HISTORY] No messages in history file, maintaining empty UI");
                // Empty conversation file is valid, silently maintain empty UI
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[CHAT_HISTORY] Error loading conversation", e);
            // Clear the problematic folder setting to prevent repeated failures
            ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            // Only show toast for real errors
            Toast.makeText(getContext(), R.string.toast_chat_history_load_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Cleanup media cache (images and audio)
        // Note: Media files are saved to chat history folder, no separate cache cleanup needed
        
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
        
        // Clean up media adapter resources
        if (mediaThumbnailAdapter != null) {
            mediaThumbnailAdapter.stopAudio();
            LogManager.logD(TAG, "Media adapter audio playback stopped");
        }
        
        // Clean up recording resources
        if (audioRecorder != null && audioRecorder.isRecording()) {
            audioRecorder.cancelRecording();
        }
        if (recordingDialog != null && recordingDialog.isShowing()) {
            recordingDialog.dismiss();
        }
    }
    
    // ==================== Voice Recording Methods ====================
    
    /**
     * Handle send button touch events (for long press voice recording)
     */
    private boolean handleSendButtonTouch(MotionEvent event) {
    switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            pressStartTime = System.currentTimeMillis();
            initialTouchY = event.getRawY();  // 使用屏幕绝对坐标，避免View布局变化影响
            
            // Post delayed task to check for long press
            longPressHandler.postDelayed(() -> {
                long pressDuration = System.currentTimeMillis() - pressStartTime;
                if (pressDuration >= LONG_PRESS_DURATION_MS && !isRecordingVoice) {
                    // Trigger voice recording
                    startVoiceRecording();
                }
            }, LONG_PRESS_DURATION_MS);
            return true;
            
        case MotionEvent.ACTION_MOVE:
            if (isRecordingVoice) {
                // Check for slide up to cancel
                float deltaY = initialTouchY - event.getRawY();  // 使用屏幕绝对坐标
                if (deltaY > CANCEL_THRESHOLD_PX) {
                    recordingDialog.showCancelState();
                } else {
                    recordingDialog.showNormalState();
                }
            }
            return true;
            
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            longPressHandler.removeCallbacksAndMessages(null);
            
            long pressDuration = System.currentTimeMillis() - pressStartTime;
            
            if (isRecordingVoice) {
                // Recording in progress - either send or cancel
                float deltaY = initialTouchY - event.getRawY();  // 使用屏幕绝对坐标
                LogManager.logD(TAG, String.format("[VOICE] ACTION_UP: deltaY=%.1fpx, threshold=%dpx, isRecording=%b, rawY=%.1f, initialY=%.1f", 
                    deltaY, CANCEL_THRESHOLD_PX, isRecordingVoice, event.getRawY(), initialTouchY));
                
                if (deltaY > CANCEL_THRESHOLD_PX) {
                    LogManager.logI(TAG, "[VOICE] Canceling recording (slide up detected)");
                    cancelVoiceRecording();
                } else {
                    LogManager.logI(TAG, "[VOICE] Sending voice message");
                    sendVoiceMessage();
                }
            } else if (pressDuration < LONG_PRESS_DURATION_MS) {
                // Short press - normal send
                LogManager.logD(TAG, String.format("[VOICE] Short press detected (%dms), normal send", pressDuration));
                handleSendStopClick();
            } else {
                LogManager.logW(TAG, String.format("[VOICE] Long press completed but not recording (duration=%dms)", pressDuration));
            }
            return true;
    }
    return false;
}

/**
 * Start voice recording
 */
@SuppressWarnings("deprecation")
private void startVoiceRecording() {
    // Check recording permission
    if (ContextCompat.checkSelfPermission(requireContext(), 
            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        
        // 重置录音状态（防止权限请求dialog卡住UI）
        isRecordingVoice = false;
        longPressHandler.removeCallbacksAndMessages(null);
        
        LogManager.logI(TAG, "Requesting RECORD_AUDIO permission");
        
        // Request permission using new API
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        return;
    }
    
    // Vibrate feedback (加长震动时间到200ms，更容易感知)
    LogManager.logD(TAG, "[VOICE] Attempting vibrate feedback, SDK=" + android.os.Build.VERSION.SDK_INT);
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.os.VibratorManager vibratorManager = (android.os.VibratorManager) 
                requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                android.os.Vibrator vibrator = vibratorManager.getDefaultVibrator();
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                LogManager.logD(TAG, "[VOICE] Vibrated (API 31+)");
            } else {
                LogManager.logW(TAG, "[VOICE] VibratorManager is null");
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Object service = requireContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (service instanceof android.os.Vibrator) {
                android.os.Vibrator vibrator = (android.os.Vibrator) service;
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                LogManager.logD(TAG, "[VOICE] Vibrated (API 26+)");
            } else {
                LogManager.logW(TAG, "[VOICE] Vibrator service is null");
            }
        } else {
            // For API < 26, must use deprecated APIs
            vibrateWithLegacyApi();
        }
    } catch (Exception e) {
        LogManager.logE(TAG, "[VOICE] Vibrate failed: " + e.getMessage(), e);
    }
    
    // Show recording dialog
    recordingDialog.show();
    
    // Ensure chat folder exists
    String chatFolder = ConfigManager.getString(requireContext(), 
        ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
    if (chatFolder.isEmpty()) {
        chatFolder = ChatHistoryManager.createNewChatFolder(requireContext());
        if (chatFolder != null) {
            ConfigManager.setString(requireContext(), 
                ConfigManager.KEY_CURRENT_CHAT_FOLDER, chatFolder);
            currentChatFolderPath = chatFolder;
        } else {
            LogManager.logE(TAG, "Failed to create chat folder for audio");
            Toast.makeText(requireContext(), "无法创建对话文件夹", Toast.LENGTH_SHORT).show();
            recordingDialog.dismiss();
            return;
        }
    } else {
        currentChatFolderPath = chatFolder;
    }
    
    // Record to cache directory (will be compressed to M4A later)
    File cacheWav = AudioService.getCacheWavFile(requireContext());
    
    // Start recording
    boolean started = audioRecorder.startRecording(cacheWav, new AudioService.RecordingCallback() {
        @Override
        public void onAmplitudeUpdate(int amplitude) {
            if (recordingDialog.isShowing()) {
                recordingDialog.updateWaveform(amplitude);
            }
        }
        
        @Override
        public void onDurationUpdate(long durationMs) {
            if (recordingDialog.isShowing()) {
                recordingDialog.updateDuration(durationMs);
            }
        }
        
        @Override
        public void onMaxDurationReached() {
            Toast.makeText(requireContext(), R.string.recording_max_duration, Toast.LENGTH_SHORT).show();
            sendVoiceMessage();
        }
        
        @Override
        public void onError(String error) {
            LogManager.logE(TAG, "Recording error: " + error);
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            cancelVoiceRecording();
        }
    });
    
    if (started) {
        isRecordingVoice = true;
        LogManager.logI(TAG, "Voice recording started to cache: " + cacheWav.getName());
    } else {
        recordingDialog.dismiss();
        Toast.makeText(requireContext(), "录音启动失败", Toast.LENGTH_SHORT).show();
    }
}

/**
 * Send voice message
 */
private void sendVoiceMessage() {
    if (!isRecordingVoice) {
        return;
    }
    
    recordingDialog.dismiss();
    
    // Stop recording and get file
    File audioFile = audioRecorder.stopRecording();
    isRecordingVoice = false;
    
    if (audioFile == null || !audioFile.exists()) {
        LogManager.logW(TAG, "Audio file not found after recording");
        Toast.makeText(requireContext(), "录音文件无效", Toast.LENGTH_SHORT).show();
        return;
    }
    
    // Check minimum duration (0.5 seconds)
    long duration = audioRecorder.getCurrentDuration();
    if (duration < 500) {
        LogManager.logW(TAG, "Recording too short: " + duration + "ms");
        Toast.makeText(requireContext(), R.string.recording_too_short, Toast.LENGTH_SHORT).show();
        audioFile.delete();
        return;
    }
    
    LogManager.logI(TAG, "Voice message recorded: " + audioFile.getName() + 
        ", duration: " + duration + "ms, size: " + audioFile.length() + " bytes");
    
    // Check if already sending (use compareAndSet to avoid race condition)
    if (!isSending.compareAndSet(false, true)) {
        Toast.makeText(requireContext(), "正在生成中，请稍候", Toast.LENGTH_SHORT).show();
        audioFile.delete();  // Clean up recorded file
        return;
    }
    
    // Now we own the sending lock, update button state
    buttonSendStop.setText(getString(R.string.button_stop_with_icon));
    LogManager.logI(TAG, "[VOICE] Acquired sending lock and updated button to STOP");
    
    // CRITICAL: Read all configuration at send moment (same as handleSendStopClick)
    // This ensures configuration snapshot is taken before any async operations
    String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
    String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
    String apiKey = editTextApiKey.getText().toString();
    String model = spinnerApiModel.getSelectedItem().toString();
    String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
    String systemPrompt = editTextSystemPrompt.getText().toString();
    String userPrompt = editTextUserPrompt.getText().toString().trim();
    
    LogManager.logI(TAG, "[VOICE][PARAM] apiUrl=" + apiUrl + ", model=" + model + ", kb=" + knowledgeBase);
    
    // CRITICAL: Prepare and save user input (move audio file, create message, save markdown)
    UserInput userInput = prepareAndSaveUserInput(userPrompt, audioFile);
    if (userInput == null) {
        // Failed to prepare input - restore state
        Toast.makeText(requireContext(), "无法保存录音，请检查存储权限", Toast.LENGTH_SHORT).show();
        audioFile.delete();  // Clean up recorded file
        isSending.set(false);
        buttonSendStop.setText(getString(R.string.button_send));
        LogManager.logE(TAG, "[VOICE] Restored state after prepare input failed");
        return;
    }
    
    LogManager.logI(TAG, String.format("[VOICE] User input prepared: text=%s, audio=%d",
        userInput.hasText() ? "yes" : "no", userInput.audioPaths.size()));
    
    // Trigger audio inference
    // NOTE: isSending already set to true above with compareAndSet
    // CRITICAL: Use cache WAV for ASR/Omni (M4A in userInput is only for MD/ChatUI)
    File cacheWav = AudioService.getCacheWavFile(requireContext());
    if (cacheWav.exists()) {
        sendAudioToModel(cacheWav.getAbsolutePath(), userPrompt, userInput, 
                        apiUrl, apiKey, model, knowledgeBase, systemPrompt);
    } else {
        LogManager.logE(TAG, "[VOICE] Cache WAV not found after recording");
        Toast.makeText(requireContext(), "录音文件丢失", Toast.LENGTH_SHORT).show();
        // Restore state on failure
        isSending.set(false);
        buttonSendStop.setText(getString(R.string.button_send));
    }
}

/**
 * Send audio to model for inference
 * Note: User message with audioUri has already been created and added to chat
 * @param audioPath CRITICAL: Must be WAV file path (cache WAV for ASR/Omni), NOT M4A!
 * @param userInput User input structure containing M4A paths (for MD record) and images
 * @param apiUrl API URL (from configuration snapshot)
 * @param apiKey API Key (from configuration snapshot)
 * @param model Model name (from configuration snapshot)
 * @param knowledgeBase Knowledge base name (from configuration snapshot)
 * @param systemPrompt System prompt (from configuration snapshot)
 */
private void sendAudioToModel(String audioPath, String textPrompt, UserInput userInput,
                              String apiUrl, String apiKey, String model, 
                              String knowledgeBase, String systemPrompt) {
    // CRITICAL: All configuration passed as parameters (snapshot taken at send moment)
    // Do NOT read from UI controls - configuration may have been modified by user
    
    // Only local models support audio for now
    if (!AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
        Toast.makeText(requireContext(), "音频推理目前仅支持本地模型", Toast.LENGTH_LONG).show();
        // Remove the user message
        chatMessages.remove(chatMessages.size() - 1);
        chatAdapter.notifyItemRemoved(chatMessages.size());
        return;
    }
    
    // Create AI placeholder message (CRITICAL: needed for updateChatMessage to work)
    ChatDataItem aiMessage = new ChatDataItem(ChatViewHolders.ASSISTANT);
    aiMessage.setLoading(true);
    chatMessages.add(aiMessage);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    // Immediately scroll to bottom when adding new message (no animation)
    recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
    userScrolledAway = false; // Reset flag for new message
    LogManager.logD(TAG, "[AUDIO] Created AI placeholder message");
    
    // Get ASR model selection
    String asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
    
    LogManager.logI(TAG, "[AUDIO] Sending audio to model: " + audioPath + ", selected model: " + model + ", ASR: " + asrModel);
    
    if (!"无".equals(asrModel)) {
        // ASR enabled, convert audio to text
        // CRITICAL: Pass userInput to preserve image information
        convertAndSendAsText(audioPath, textPrompt, asrModel, apiUrl, apiKey, model, knowledgeBase, systemPrompt, userInput);
    } else {
        // ASR disabled, use <audio> tag (original flow)
        updateChatMessage("[ASR] Disabled, sending audio tag to model\n");
        sendAudioToModelInternal(audioPath, textPrompt, apiUrl, apiKey, model, knowledgeBase, systemPrompt);
    }
}

/**
 * Convert audio to text using ASR and send as text
 * @param userInput User input structure to preserve image information during ASR conversion
 */
private void convertAndSendAsText(String audioPath, String textPrompt, String asrModel,
                                   String apiUrl, String apiKey, String model, 
                                   String knowledgeBase, String systemPrompt, UserInput userInput) {
    // Submit ASR task
    ragTaskFuture = ragQueryExecutor.submit(() -> {
        try {
            // ============================================
            // ASR Section: Will be included in debug section opened by executeRagQuery
            // ============================================
            LogManager.logI(TAG, "[ASR] Starting ASR conversion task");
            
            // Prepare ASR info to be output in executeRagQuery
            // Get image info from UserInput (adapter is already cleared)
            String imageInfo = (userInput != null && userInput.hasImages()) 
                ? userInput.imagePaths.size() + " image(s)" : "none";
            String audioFileName = new File(audioPath).getName();
            final String asrInfo = String.format("[ASR] Model: %s; Audio: %s; Image: %s\n", 
                asrModel, audioFileName, imageInfo);
            
            // Load ASR model (lazy loading) - use AsrAdapter (independent of LLM)
            com.example.offlineai.api.AsrAdapter asrAdapter = 
                com.example.offlineai.api.AsrAdapter.getInstance(requireContext());
            
            if (!asrAdapter.isAsrLoaded() || !asrModel.equals(asrAdapter.getCurrentAsrModel())) {
                LogManager.logI(TAG, "[ASR] Loading model: " + asrModel);
                asrAdapter.loadAsrModel(asrModel);
                LogManager.logI(TAG, "[ASR] Model loaded successfully: " + asrModel);
            } else {
                LogManager.logI(TAG, "[ASR] Using loaded model: " + asrModel);
            }
            
            // Transcribe
            LogManager.logI(TAG, "[ASR] Transcribing audio: " + audioPath);
            String convertedText = asrAdapter.transcribeAudio(audioPath);
            
            // Normalize textPrompt (handle null/empty/whitespace)
            String normalizedTextPrompt = (textPrompt == null) ? "" : textPrompt.trim();
            
            // Check if ASR result is empty - fallback to audio tag instead of error
            if (convertedText.isEmpty() && normalizedTextPrompt.isEmpty()) {
                LogManager.logW(TAG, "[ASR] ⚠️ ASR returned empty text, fallback to audio tag mode");
                mainHandler.post(() -> {
                    updateChatMessage("<debug>\n");
                    updateChatMessage("[ASR] Model: " + asrModel + "\n");
                    updateChatMessage("[ASR] ⚠️ No speech recognized, using audio tag mode\n");
                    updateChatMessage("</debug>\n");
                    sendAudioToModelInternal(audioPath, textPrompt, apiUrl, apiKey, model, knowledgeBase, systemPrompt);
                });
                return; // Exit ASR task, let audio tag flow handle it
            }
            
            // Wrap ASR converted text with [Audio] marker to help model understand it's from speech
            // Format: [Audio]"converted text" or [Audio0]"text0" [Audio1]"text1" for multiple audios
            String wrappedAsrText = String.format("[Audio]\"%s\"", convertedText);
            
            // Merge with user text
            String finalText = wrappedAsrText;
            if (!normalizedTextPrompt.isEmpty()) {
                finalText = wrappedAsrText + "\n" + normalizedTextPrompt;
            }
            
            LogManager.logI(TAG, "[ASR] Conversion successful, wrapped text length: " + finalText.length());
            
            // Prepare ASR result info
            final String asrResult = String.format("[ASR] Converted text: \"%s\"%s\n", 
                convertedText, 
                textPrompt.isEmpty() ? "" : (" + user text: \"" + textPrompt + "\""));
            
            // NOTE: Media adapter already cleared by prepareAndSaveUserInput()
            // Audio file already saved to chat folder, no need to remove from adapter
            
            // Continue with RAG flow (text-only)
            // Pass ASR info to be output in debug section
            // CRITICAL: Set skipAudioEmbedding=true to prevent re-embedding <audio> tag
            // CRITICAL: Pass userInput to preserve image information
            executeRagQueryWithAsr(apiUrl, apiKey, model, knowledgeBase, systemPrompt, finalText, 
                                  asrInfo + asrResult, true, userInput);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[ASR] Conversion failed", e);
            
            // Fallback to <audio> tag
            mainHandler.post(() -> {
                // Open debug section for error info
                updateChatMessage("<debug>\n");
                updateChatMessage("[ASR] Model: " + asrModel + "\n");
                updateChatMessage("[ASR] ❌ Conversion failed: " + e.getMessage() + "\n");
                updateChatMessage("[ASR] Fallback to audio tag mode\n");
                updateChatMessage("</debug>\n");
                sendAudioToModelInternal(audioPath, textPrompt, apiUrl, apiKey, model, knowledgeBase, systemPrompt);
            });
        }
    });
}

/**
 * Internal method for ASR conversion (called from executor, no need to submit again)
 * @param userInput User input structure to preserve image information during ASR conversion
 */
private void convertAndSendAsTextInternal(String audioPath, String textPrompt, String asrModel,
                                          String apiUrl, String apiKey, String model, 
                                          String knowledgeBase, String systemPrompt, UserInput userInput) {
    try {
        // ============================================
        // ASR Section: Will be included in debug section opened by executeRagQuery
        // ============================================
        LogManager.logI(TAG, "[ASR] Starting ASR conversion task");
        
        // Prepare ASR info to be output in executeRagQuery
        // Get image info from UserInput (adapter is already cleared)
        String imageInfo = (userInput != null && userInput.hasImages()) 
            ? userInput.imagePaths.size() + " image(s)" : "none";
        String audioFileName = new File(audioPath).getName();
        final String asrInfo = String.format("[ASR] Model: %s; Audio: %s; Image: %s\n", 
            asrModel, audioFileName, imageInfo);
        
        // Load ASR model (lazy loading) - use AsrAdapter (independent of LLM)
        com.example.offlineai.api.AsrAdapter asrAdapter = 
            com.example.offlineai.api.AsrAdapter.getInstance(requireContext());
        
        if (!asrAdapter.isAsrLoaded() || !asrModel.equals(asrAdapter.getCurrentAsrModel())) {
            LogManager.logI(TAG, "[ASR] Loading model: " + asrModel);
            asrAdapter.loadAsrModel(asrModel);
            LogManager.logI(TAG, "[ASR] Model loaded successfully: " + asrModel);
        } else {
            LogManager.logI(TAG, "[ASR] Using loaded model: " + asrModel);
        }
        
        // Transcribe
        LogManager.logI(TAG, "[ASR] Transcribing audio: " + audioPath);
        String convertedText = asrAdapter.transcribeAudio(audioPath);
        
        // Normalize textPrompt (handle null/empty/whitespace)
        String normalizedTextPrompt = (textPrompt == null) ? "" : textPrompt.trim();
        
        // Check if ASR result is empty - fallback to audio tag instead of error
        if (convertedText.isEmpty() && normalizedTextPrompt.isEmpty()) {
            LogManager.logW(TAG, "[ASR] ⚠️ ASR returned empty text, fallback to audio tag mode");
            mainHandler.post(() -> {
                updateChatMessage("<debug>\n");
                updateChatMessage("[ASR] Model: " + asrModel + "\n");
                updateChatMessage("[ASR] ⚠️ No speech recognized, using audio tag mode\n");
                updateChatMessage("</debug>\n");
                sendAudioToModelInternal(audioPath, textPrompt, apiUrl, apiKey, model, knowledgeBase, systemPrompt);
            });
            return; // Exit ASR task, let audio tag flow handle it
        }
        
        // Wrap ASR converted text with [Audio] marker to help model understand it's from speech
        // Format: [Audio]"converted text" or [Audio0]"text0" [Audio1]"text1" for multiple audios
        String wrappedAsrText = String.format("[Audio]\"%s\"", convertedText);
        
        // Merge with user text
        String finalText = wrappedAsrText;
        if (!normalizedTextPrompt.isEmpty()) {
            finalText = wrappedAsrText + "\n" + normalizedTextPrompt;
        }
        
        LogManager.logI(TAG, "[ASR] Conversion successful, wrapped text length: " + finalText.length());
        
        // Prepare ASR result info
        final String asrResult = String.format("[ASR] Converted text: \"%s\"%s\n", 
            convertedText, 
            normalizedTextPrompt.isEmpty() ? "" : (" + user text: \"" + normalizedTextPrompt + "\""));
        
        // NOTE: Media adapter already cleared by prepareAndSaveUserInput()
        // Audio file already saved to chat folder, no need to remove from adapter
        
        // Continue with RAG flow (text-only)
        // Pass ASR info to be output in debug section
        // CRITICAL: Set skipAudioEmbedding=true to prevent re-embedding <audio> tag
        // CRITICAL: Pass userInput to preserve image information
        executeRagQueryWithAsr(apiUrl, apiKey, model, knowledgeBase, systemPrompt, finalText, 
                              asrInfo + asrResult, true, userInput);
        
    } catch (Exception e) {
        LogManager.logE(TAG, "[ASR] Conversion failed", e);
        
        // Fallback to <audio> tag
        mainHandler.post(() -> {
            // Open debug section for error info
            updateChatMessage("<debug>\n");
            updateChatMessage("[ASR] Model: " + asrModel + "\n");
            updateChatMessage("[ASR] ❌ Conversion failed: " + e.getMessage() + "\n");
            updateChatMessage("[ASR] Fallback to audio tag mode\n");
            updateChatMessage("</debug>\n");
            sendAudioToModelInternal(audioPath, textPrompt, apiUrl, apiKey, model, knowledgeBase, systemPrompt);
        });
    }
}

/**
 * Internal method to send audio after model is confirmed ready (ASR fallback)
 * @param audioPath CRITICAL: Must be WAV file path (cache WAV for Omni), NOT M4A!
 */
private void sendAudioToModelInternal(String audioPath, String textPrompt, String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt) {
    LogManager.logI(TAG, "[AUDIO] Starting audio inference with model: " + model);
    
    // Set sending state
    isSending.set(true);
    updateButtonText(); // Update button text based on state machine
    
    // Create AI response placeholder
    ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
    aiMsg.setLoading(true);
    chatMessages.add(aiMsg);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    // Immediately scroll to bottom when adding new message (no animation)
    recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
    userScrolledAway = false; // Reset flag for new message
    
    // Create UserInput with audio path (unified approach)
    java.util.List<String> audioPaths = new java.util.ArrayList<>();
    audioPaths.add(audioPath);
    UserInput audioUserInput = new UserInput(textPrompt, null, audioPaths, 0);
    
    LogManager.logI(TAG, "[AUDIO] Created UserInput with audio path: " + audioPath);
    
    // Submit RAG task (similar to handleSendStopClick flow)
    LogManager.logI(TAG, "[AUDIO] Submitting audio RAG task to executor");
    
    ragTaskFuture = ragQueryExecutor.submit(() -> {
        LogManager.logI(TAG, "[AUDIO] Audio RAG task started - thread=" + Thread.currentThread().getName());
        
        // Reset local LLM stop flag
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            try {
                com.example.offlineai.api.LocalLlmAdapter localAdapter = com.example.offlineai.api.LocalLlmAdapter.getInstance(requireContext());
                localAdapter.resetStopFlag();
                LogManager.logD(TAG, "[AUDIO] Reset local LLM stop flag");
            } catch (Exception e) {
                LogManager.logE(TAG, "[AUDIO] Error resetting local LLM stop flag", e);
            }
        }
        
        // Execute RAG query with UserInput (audio path will be handled by JNI)
        executeRagQuery(apiUrl, apiKey, model, knowledgeBase, systemPrompt, textPrompt, audioUserInput);
    });
}

/**
 * Prepare and save user input (CRITICAL: called at send button click/release)
 * 1. Ensure chat folder exists
 * 2. Save all media files to chat folder
 * 3. Create UserInput structure
 * 4. Save to conversation.md
 * 5. Create and display user message in UI
 * 6. Clear input fields and media thumbnails
 * 
 * @param textPrompt User text input
 * @param recordedAudioFile Recorded audio file (from long-press), null if not recording
 * @return UserInput structure, or null if failed
 */
private UserInput prepareAndSaveUserInput(String textPrompt, File recordedAudioFile) {
    // Step 1: Ensure chat folder exists
    String chatFolderPath = ConfigManager.getString(getContext(), 
        ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
    
    if (chatMessages.isEmpty() || chatFolderPath.isEmpty()) {
        // Create new chat folder
        String newFolder = ChatHistoryManager.createNewChatFolder(getContext());
        if (newFolder == null) {
            LogManager.logE(TAG, "[PREPARE_INPUT] Failed to create chat folder");
            Toast.makeText(requireContext(), "无法创建对话文件夹，请检查存储权限", Toast.LENGTH_SHORT).show();
            return null;
        }
        ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, newFolder);
        currentChatFolderPath = newFolder;
        chatFolderPath = newFolder;
        LogManager.logI(TAG, "[PREPARE_INPUT] Created new chat folder: " + newFolder);
    } else {
        // Verify existing folder
        File existingFolder = new File(chatFolderPath);
        if (!existingFolder.exists()) {
            LogManager.logW(TAG, "[PREPARE_INPUT] Chat folder doesn't exist, creating new one");
            String newFolder = ChatHistoryManager.createNewChatFolder(getContext());
            if (newFolder == null) {
                LogManager.logE(TAG, "[PREPARE_INPUT] Failed to create chat folder");
                return null;
            }
            ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, newFolder);
            currentChatFolderPath = newFolder;
            chatFolderPath = newFolder;
        }
    }
    
    // Step 2: Save all media files synchronously
    List<String> imagePaths = new ArrayList<>();
    List<String> audioPaths = new ArrayList<>();
    float audioDuration = 0.0f;
    
    // Process recorded audio (from long-press)
    if (recordedAudioFile != null && recordedAudioFile.exists()) {
        // ASYNC COMPRESSION: Start compression in background, don't block main thread
        String timestamp = String.valueOf(System.currentTimeMillis());
        File m4aFile = new File(chatFolderPath, "audio_" + timestamp + "_user.m4a");
        
        // Get duration from cache WAV (quick, no blocking)
        audioDuration = AudioService.getAudioDuration(recordedAudioFile.getAbsolutePath());
        
        // Add placeholder path (will be updated after compression)
        audioPaths.add(m4aFile.getAbsolutePath());
        
        // Start async compression
        isUserAudioCompressing.set(true);
        final String finalChatFolderPath = chatFolderPath;
        audioCompressionExecutor.submit(() -> {
            try {
                LogManager.logI(TAG, "[ASYNC_COMPRESS] Starting user audio compression: " + 
                    recordedAudioFile.getAbsolutePath() + " -> " + m4aFile.getAbsolutePath());
                
                boolean success = AudioService.compressWavToM4a(
                    recordedAudioFile.getAbsolutePath(), 
                    m4aFile.getAbsolutePath()
                );
                
                if (success) {
                    LogManager.logI(TAG, "[ASYNC_COMPRESS] User audio compressed successfully: " + m4aFile.getAbsolutePath());
                    pendingUserAudioM4aPath = m4aFile.getAbsolutePath();
                } else {
                    LogManager.logE(TAG, "[ASYNC_COMPRESS] User audio compression failed");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[ASYNC_COMPRESS] Error compressing user audio", e);
            } finally {
                isUserAudioCompressing.set(false);
                LogManager.logI(TAG, "[ASYNC_COMPRESS] User audio compression done, checking finalization...");
                // Check if all compressions are done
                mainHandler.post(() -> checkCompressionCompleteAndFinalize());
            }
        });
        
        LogManager.logI(TAG, "[PREPARE_INPUT] User audio compression started in background (duration: " + audioDuration + "s)");
    }
    
    // Process media from MediaThumbnailAdapter
    int totalMediaCount = 0;
    int failedMediaCount = 0;
    boolean hasAudioFiles = false;
    
    if (mediaThumbnailAdapter != null && mediaThumbnailAdapter.getMediaCount() > 0) {
        List<MediaThumbnailAdapter.MediaItem> items;
        synchronized (mediaThumbnailAdapter.getMediaItems()) {
            items = new ArrayList<>(mediaThumbnailAdapter.getMediaItems());
        }
        
        totalMediaCount = items.size();
        for (MediaThumbnailAdapter.MediaItem item : items) {
            if (item instanceof MediaThumbnailAdapter.ImageItem) {
                // Process image: resize and save
                try {
                    String imagePath = processImageToChatFolder(item.getOriginalUri(), chatFolderPath);
                    if (imagePath != null) {
                        imagePaths.add(imagePath);
                        LogManager.logI(TAG, "[PREPARE_INPUT] Saved image: " + imagePath);
                    } else {
                        failedMediaCount++;
                        LogManager.logE(TAG, "[PREPARE_INPUT] Failed to process image (returned null)");
                    }
                } catch (Exception e) {
                    failedMediaCount++;
                    LogManager.logE(TAG, "[PREPARE_INPUT] Error processing image", e);
                }
            } else if (item instanceof MediaThumbnailAdapter.AudioItem) {
                // Audio already decoded and appended to user_voice.wav in handleAudioFileSelected()
                // Mark that we have audio files (will compress cache WAV after loop)
                hasAudioFiles = true;
                LogManager.logI(TAG, "[PREPARE_INPUT] Audio file detected (already decoded to cache WAV)");
            }
        }
    }
    
    // Process merged audio (if any audio files were selected)
    if (hasAudioFiles) {
        File cacheWav = AudioService.getCacheWavFile(requireContext());
        if (cacheWav.exists()) {
            // Start async compression of merged audio
            String timestamp = String.valueOf(System.currentTimeMillis());
            File m4aFile = new File(chatFolderPath, "audio_" + timestamp + "_user.m4a");
            
            // Get duration from cache WAV (contains all merged audio)
            audioDuration = AudioService.getAudioDuration(cacheWav.getAbsolutePath());
            
            // Add placeholder path (will be updated after compression)
            audioPaths.add(m4aFile.getAbsolutePath());
            
            // Start async compression
            isUserAudioCompressing.set(true);
            final String finalChatFolderPath = chatFolderPath;
            audioCompressionExecutor.submit(() -> {
                try {
                    LogManager.logI(TAG, "[ASYNC_COMPRESS] Starting user audio compression: " + 
                        cacheWav.getAbsolutePath() + " -> " + m4aFile.getAbsolutePath());
                    
                    boolean success = AudioService.compressWavToM4a(
                        cacheWav.getAbsolutePath(), 
                        m4aFile.getAbsolutePath()
                    );
                    
                    if (success) {
                        LogManager.logI(TAG, "[ASYNC_COMPRESS] User audio compressed successfully: " + m4aFile.getAbsolutePath());
                        pendingUserAudioM4aPath = m4aFile.getAbsolutePath();
                    } else {
                        LogManager.logE(TAG, "[ASYNC_COMPRESS] User audio compression failed");
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[ASYNC_COMPRESS] Error compressing user audio", e);
                } finally {
                    isUserAudioCompressing.set(false);
                    LogManager.logI(TAG, "[ASYNC_COMPRESS] User audio compression done, checking finalization...");
                    // Check if all compressions are done
                    mainHandler.post(() -> checkCompressionCompleteAndFinalize());
                }
            });
            
            LogManager.logI(TAG, "[PREPARE_INPUT] User audio compression started (duration: " + audioDuration + "s)");
        } else {
            LogManager.logE(TAG, "[PREPARE_INPUT] Cache WAV not found!");
        }
    }
    
    // Warn user if some media files failed to process
    if (failedMediaCount > 0) {
        final int failed = failedMediaCount;
        final int total = totalMediaCount;
        mainHandler.post(() -> {
            Toast.makeText(requireContext(), 
                String.format("%d/%d 个媒体文件保存失败", failed, total), 
                Toast.LENGTH_LONG).show();
        });
        LogManager.logW(TAG, String.format("[PREPARE_INPUT] %d/%d media files failed to process", failed, total));
    }
    
    // Step 3: Create UserInput structure
    UserInput userInput = new UserInput(textPrompt, imagePaths, audioPaths, audioDuration);
    LogManager.logI(TAG, String.format("[PREPARE_INPUT] Created UserInput: text=%d chars, images=%d, audio=%d", 
        textPrompt.length(), imagePaths.size(), audioPaths.size()));
    
    // Step 4: Create user message and add to chat
    ChatDataItem userMsg;
    if (userInput.hasAudio()) {
        // Audio message (with optional text and images)
        userMsg = ChatDataItem.Companion.createAudioInputData(
            getCurrentTime(),
            textPrompt,
            audioPaths.get(0),  // Use first audio
            audioDuration
        );
        // TODO: Support multiple images with audio
        if (userInput.hasImages()) {
            userMsg.imageUri = Uri.parse(imagePaths.get(0));
        }
    } else if (userInput.hasImages()) {
        // Image message (with optional text)
        userMsg = ChatDataItem.Companion.createImageInputData(
            getCurrentTime(),
            textPrompt,
            Uri.parse(imagePaths.get(0))  // Use first image
        );
    } else {
        // Text-only message
        userMsg = new ChatDataItem(getCurrentTime(), ChatViewHolders.USER, textPrompt);
    }
    
    chatMessages.add(userMsg);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    // Immediately scroll to bottom when adding user message (no animation)
    recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
    userScrolledAway = false; // Reset flag for new message
    LogManager.logI(TAG, "[PREPARE_INPUT] User message added to chat");
    
    // Step 5: Save to conversation.md immediately
    boolean saved = ChatHistoryManager.saveConversation(getContext(), chatMessages, chatFolderPath);
    if (saved) {
        LogManager.logI(TAG, "[PREPARE_INPUT] Conversation saved to markdown");
    } else {
        LogManager.logE(TAG, "[PREPARE_INPUT] Failed to save conversation to markdown");
        // Warn user but don't fail the operation (message is already in UI)
        mainHandler.post(() -> {
            Toast.makeText(requireContext(), "对话历史保存失败，但消息已发送", Toast.LENGTH_LONG).show();
        });
    }
    
    // Step 6: Clear input fields and media thumbnails
    mainHandler.post(() -> {
        editTextUserPrompt.setText("");
        if (mediaThumbnailAdapter != null) {
            mediaThumbnailAdapter.clearMedia();
            recyclerViewImageThumbnails.setVisibility(View.GONE);
        }
        LogManager.logI(TAG, "[PREPARE_INPUT] Cleared input fields and media thumbnails");
    });
    
    return userInput;
}

/**
 * Process image and save to chat folder (synchronous)
 */
private String processImageToChatFolder(Uri imageUri, String chatFolderPath) throws IOException {
    // Use MediaThumbnailAdapter's processImage method
    return MediaThumbnailAdapter.processImage(getContext(), imageUri, chatFolderPath);
}

/**
 * Convert audio and save to chat folder (synchronous)
 */
private String convertAudioToChatFolder(Uri audioUri, String chatFolderPath) throws IOException {
    // Use MediaThumbnailAdapter's convertAudioToWav method
    return MediaThumbnailAdapter.convertAudioToWav(getContext(), audioUri, chatFolderPath);
}

/**
 * Cancel voice recording
 */
private void cancelVoiceRecording() {
    if (!isRecordingVoice) {
        return;
    }
    
    recordingDialog.dismiss();
    audioRecorder.cancelRecording();
    isRecordingVoice = false;
    
    Toast.makeText(requireContext(), R.string.recording_canceled, Toast.LENGTH_SHORT).show();
    LogManager.logI(TAG, "Voice recording canceled");
}

// Note: onRequestPermissionsResult has been removed in favor of ActivityResultContracts
// See recordAudioPermissionLauncher and cameraPermissionLauncher initialization

/**
 * User input data structure
 * Contains all user input: text, images, audio files
 */
private static class UserInput {
    String textPrompt;
    List<String> imagePaths;
    List<String> audioPaths;
    float audioDuration;  // For single audio file
    
    UserInput(String textPrompt, List<String> imagePaths, List<String> audioPaths, float audioDuration) {
        this.textPrompt = textPrompt != null ? textPrompt : "";
        this.imagePaths = imagePaths != null ? imagePaths : new ArrayList<>();
        this.audioPaths = audioPaths != null ? audioPaths : new ArrayList<>();
        this.audioDuration = audioDuration;
    }
    
    boolean hasImages() {
        return !imagePaths.isEmpty();
    }
    
    boolean hasAudio() {
        return !audioPaths.isEmpty();
    }
    
    boolean hasText() {
        return !textPrompt.trim().isEmpty();
    }
}

    /**
     * Filter content for TTS processing
     * Removes debug/performance tags, image/audio tags and their content
     * @param chunk Raw chunk from LLM/Diffusion
     * @return Filtered text suitable for TTS, or empty string if no valid text
     */
    private String filterTtsContent(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        
        String filtered = chunk;
        
        // 1. Filter debug tags and content (e.g., <debug>...</debug>)
        filtered = filtered.replaceAll("<debug>.*?</debug>", "");
        filtered = filtered.replaceAll("</?debug>", "");
        
        // 2. Filter performance tags and content (e.g., <performance>...</performance>)
        // Use (?s) flag to enable DOTALL mode (. matches newlines)
        filtered = filtered.replaceAll("(?s)<performance>.*?</performance>", "");
        filtered = filtered.replaceAll("</?performance>", "");
        
        // 3. Filter head markers
        filtered = filtered.replace("[TEXT:]", "");
        
        // 4. Filter image tags and paths (e.g., [IMAGE:path/to/image.jpg])
        filtered = filtered.replaceAll("\\[IMAGE:[^\\]]*\\]", "");
        
        // 5. Filter audio tags and paths (e.g., [AUDIO:path/to/audio.wav])
        filtered = filtered.replaceAll("\\[AUDIO:[^\\]]*\\]", "");
        
        // 6. Filter ASR audio markers (e.g., [Audio]"text" or [Audio0]"text")
        // CRITICAL: Remove [Audio] prefix but keep the quoted text content
        filtered = filtered.replaceAll("\\[Audio\\d*\\]\"([^\"]+)\"", "$1");
        filtered = filtered.replaceAll("\\[Audio\\d*\\]", "");
        
        return filtered;
    }
    
    /**
     * Auto-play TTS generated audio
     * Uses direct MediaPlayer playback
     */
    private void autoPlayAudio(String audioPath, ChatDataItem audioItem) {
        // Delay execution to wait for RecyclerView rendering
        recyclerViewChat.postDelayed(() -> {
            // Direct playback (simple and reliable)
            playAudioDirect(audioPath);
            LogManager.logI(TAG, "[TTS] Auto-play triggered for: " + audioPath);
        }, 300);  // 300ms delay for RecyclerView update
    }
    
    /**
     * Direct audio playback using MediaPlayer (fallback method)
     * Only plays the latest audio, stops previous playback
     */
    private void playAudioDirect(String audioPath) {
        try {
            // Stop and release previous MediaPlayer
            if (autoPlayMediaPlayer != null && autoPlayMediaPlayer.isPlaying()) {
                autoPlayMediaPlayer.stop();
                autoPlayMediaPlayer.release();
            }
            
            autoPlayMediaPlayer = new MediaPlayer();
            autoPlayMediaPlayer.setDataSource(audioPath);
            autoPlayMediaPlayer.prepare();
            autoPlayMediaPlayer.start();
            
            // Capture audioPath for completion listener
            final String playedAudioPath = audioPath;
            
            autoPlayMediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                autoPlayMediaPlayer = null;
                
                LogManager.logI(TAG, "[TTS] Omni playback completed");
                
                // Unified handling: compress and update
                handleTtsAudioComplete(playedAudioPath);
            });
            
            LogManager.logI(TAG, "[TTS] Auto-play started (Omni)");
            
        } catch (IOException e) {
            LogManager.logE(TAG, "[TTS] Auto-play failed", e);
            // Even if playback fails, still try to handle the audio file
            handleTtsAudioComplete(audioPath);
        }
    }
    
    /**
     * Handle TTS audio completion: compress WAV to M4A, update UI and save history
     * Unified entry point for both Omni TTS and External TTS
     * 
     * @param wavPath Original WAV file path from TTS generation
     */
    private void handleTtsAudioComplete(String wavPath) {
        if (wavPath == null || wavPath.isEmpty()) {
            LogManager.logE(TAG, "[TTS] handleTtsAudioComplete: wavPath is null");
            return;
        }
        
        LogManager.logI(TAG, "[TTS] handleTtsAudioComplete: " + wavPath);
        
        // ASYNC COMPRESSION: Execute compression in background using dedicated thread pool
        isAiAudioCompressing.set(true);
        audioCompressionExecutor.submit(() -> {
            try {
                File wavFile = new File(wavPath);
                if (!wavFile.exists()) {
                    LogManager.logE(TAG, "[ASYNC_COMPRESS] AI WAV file not found: " + wavPath);
                    return;
                }
                
                // Generate M4A path (same directory, same name, different extension)
                String m4aPath = wavPath.replace(".wav", ".m4a");
                
                LogManager.logI(TAG, "[ASYNC_COMPRESS] Starting AI audio compression: " + wavPath + " -> " + m4aPath);
                boolean success = AudioService.compressWavToM4a(wavPath, m4aPath);
                
                if (success) {
                    // Delete WAV after successful compression
                    if (wavFile.delete()) {
                        LogManager.logI(TAG, "[ASYNC_COMPRESS] AI WAV deleted after compression: " + wavPath);
                    } else {
                        LogManager.logW(TAG, "[ASYNC_COMPRESS] Failed to delete AI WAV: " + wavPath);
                    }
                    
                    LogManager.logI(TAG, "[ASYNC_COMPRESS] AI audio compressed successfully: " + m4aPath);
                    pendingAiAudioM4aPath = m4aPath;
                    
                    // Update UI with M4A path (immediately, don't wait for user audio)
                    mainHandler.post(() -> updateChatMessageWithAudio(m4aPath));
                } else {
                    LogManager.logE(TAG, "[ASYNC_COMPRESS] AI audio compression failed");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[ASYNC_COMPRESS] Error compressing AI audio", e);
            } finally {
                isAiAudioCompressing.set(false);
                LogManager.logI(TAG, "[ASYNC_COMPRESS] AI audio compression done, checking finalization...");
                // Check if all compressions are done
                mainHandler.post(() -> checkCompressionCompleteAndFinalize());
            }
        });
    }
    
    /**
     * Update chat message with audio file path
     * Must be called on main thread
     * 
     * @param audioPath Audio file path (WAV or M4A)
     */
    private void updateChatMessageWithAudio(String audioPath) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "[TTS] Fragment not attached, skipping audio update");
            return;
        }
        
        try {
            File audioFile = new File(audioPath);
            if (!audioFile.exists()) {
                LogManager.logE(TAG, "[TTS] Audio file not found: " + audioPath);
                return;
            }
            
            // Set audio URI to last assistant message
            if (!chatMessages.isEmpty()) {
                ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
                if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                    lastMsg.audioUri = Uri.fromFile(audioFile);
                    lastMsg.setHasOmniAudio(true);
                    
                    // Get audio duration
                    float duration = AudioService.getAudioDuration(audioPath);
                    lastMsg.setAudioDuration(duration);
                    
                    // Notify adapter to refresh UI
                    chatAdapter.notifyItemChanged(chatMessages.size() - 1);
                    
                    LogManager.logI(TAG, "[TTS] Audio URI updated: " + audioPath + 
                        " (duration: " + String.format("%.1fs", duration) + ")");
                } else {
                    LogManager.logW(TAG, "[TTS] Last message is not assistant type");
                }
            } else {
                LogManager.logW(TAG, "[TTS] No messages to update");
            }
            
            // Save chat history with audio
            saveChatHistory();
            LogManager.logI(TAG, "[TTS] Chat history saved with audio");
            
            // Reset TTS state
            isTtsGenerating.set(false);
            updateButtonText();
            LogManager.logI(TAG, "[TTS] TTS state reset, button updated");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Error updating chat message with audio", e);
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Release TtsAdapter
        if (ttsAdapter != null) {
            ttsAdapter.release();
            LogManager.logI(TAG, "[TTS] TtsAdapter released");
        }
        if (graphNerHandler != null) {
            graphNerHandler.release();
            graphNerHandler = null;
            graphNerDictPath = null;
            LogManager.logI(TAG, "[GRAPH_RAG] HanLpNerHandler released");
        }
        
        // Release auto-play MediaPlayer
        if (autoPlayMediaPlayer != null) {
            if (autoPlayMediaPlayer.isPlaying()) {
                autoPlayMediaPlayer.stop();
            }
            autoPlayMediaPlayer.release();
            autoPlayMediaPlayer = null;
        }
        
        // Shutdown audio compression thread pool
        if (audioCompressionExecutor != null) {
            audioCompressionExecutor.shutdown();
            LogManager.logI(TAG, "[ASYNC_COMPRESS] Audio compression executor shutdown");
        }
    }
    
    /**
     * Unified checkpoint: Check if all audio compression tasks are complete
     * Called at key moments:
     * 1. After inference completes (no TTS)
     * 2. After TTS generation completes (no auto-play)
     * 3. After audio playback completes (with auto-play)
     * 
     * Only when all compression tasks are done, update UI and save MD to avoid race conditions
     */
    private void checkCompressionCompleteAndFinalize() {
        // If any compression is still in progress, wait
        if (isUserAudioCompressing.get() || isAiAudioCompressing.get()) {
            return;
        }
        
        // All compressions done, update user message with M4A path if needed
        if (pendingUserAudioM4aPath != null) {
            // Find the last user message and update its audioUri
            for (int i = chatMessages.size() - 1; i >= 0; i--) {
                ChatDataItem item = chatMessages.get(i);
                if (item.getType() == ChatViewHolders.USER) {
                    // CRITICAL: Use Uri.fromFile() to create proper file:// URI with scheme
                    item.audioUri = Uri.fromFile(new File(pendingUserAudioM4aPath));
                    chatAdapter.notifyItemChanged(i);
                    break;
                }
            }
            pendingUserAudioM4aPath = null;
        }
        
        // AI audio M4A path is already updated in updateChatMessageWithAudio()
        String aiAudioPathForAutoPlay = null;
        if (pendingAiAudioM4aPath != null) {
            aiAudioPathForAutoPlay = pendingAiAudioM4aPath;  // Save for auto-play before clearing
            pendingAiAudioM4aPath = null;
        }
        
        // Save chat history (final step)
        saveChatHistory();
        LogManager.logI(TAG, "[COMPRESSION_CHECK] Finalized: chat saved, state reset");
        
        // Reset sending state (CRITICAL: must reset after all done)
        isSending.set(false);
        updateButtonText();
        
        // TODO: Add auto-play feature if needed
        // if (aiAudioPathForAutoPlay != null) {
        //     mainHandler.postDelayed(() -> playAudioDirect(aiAudioPathForAutoPlay), 300);
        // }
    }
    
    @SuppressWarnings("deprecation")
    private void vibrateWithLegacyApi() {
        android.os.Vibrator vibrator = (android.os.Vibrator) 
            requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(200);
            LogManager.logD(TAG, "[VOICE] Vibrated (legacy API)");
        } else {
            LogManager.logW(TAG, "[VOICE] Vibrator service is null");
        }
    }

}  // End of RagQaFragment class
