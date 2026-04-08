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
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
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

import com.example.offlineai.ipc.LocalLlmHandler;
import com.example.offlineai.ipc.LocalLlmAdapter;
import com.example.offlineai.ipc.InferenceClient;
import com.example.offlineai.ipc.TtsAdapter;
import com.example.offlineai.ApiUrlAdapter;
import com.example.offlineai.agent.AgentManager;
// Removed: import com.example.offlineai.RerankerHandler; - Now handled by RagQueryManager
import com.example.offlineai.AppConstants;
import com.example.offlineai.StateDisplayManager;
import com.example.offlineai.StatefulFragment;
import com.example.offlineai.adapter.StateAwareSpinnerAdapter;
import com.example.offlineai.MediaThumbnailAdapter;
import com.example.offlineai.EmbeddingHandler;
import com.example.offlineai.HanLpNerHandler;
// Removed: import com.example.offlineai.GraphStopwordsMatcher; - Now handled by RagQueryManager
// Removed: import com.example.offlineai.SQLiteVectorDatabaseHandler; - Now using KnowledgeGraphDatabase directly
import com.example.offlineai.ConfigManager;
import com.example.offlineai.TaskLogBuffer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


@SuppressWarnings("deprecation")
public class RagQaFragment extends Fragment implements StatefulFragment {

    public enum PageMode {
        RAG,
        AGENT
    }

    private static final String TAG = "OfflineAI_RagQa"; // Add TAG for log printing
    private static final String LOG_FILE = "api_log.txt"; // Log file name
    private static final int MAX_IMAGES = 3; // Maximum number of images allowed
    
    // Backend preference options (same as SettingsFragment)
    private static final String[] BACKEND_OPTIONS = {"CPU", "Vulkan", "OpenCL", "NNAPI"};
    private static final String[] BACKEND_VALUES = {"CPU", "VULKAN", "OPENCL", "NNAPI"};

    private static final String STATE_KEY_USER_PROMPT = "ragqa_state_user_prompt";
    private static final String STATE_KEY_CHAT_SCROLL = "ragqa_state_chat_scroll";
    private static final String STATE_KEY_USER_SCROLLED_AWAY = "ragqa_state_user_scrolled_away";
    private static final String STATE_KEY_IMAGE_URIS = "ragqa_state_image_uris";
    private static final String STATE_KEY_AUDIO_URIS = "ragqa_state_audio_uris";
    private static final String STATE_KEY_RESPONSE_TEXT = "ragqa_state_response_text";
    private static final String STATE_KEY_LOG_INDEX = "ragqa_state_log_index";
    private static final String STATE_KEY_LLM_TASK_ID = "ragqa_state_llm_task_id";

    private Spinner spinnerApiUrl;
    private EditText editTextApiKey;
    private Spinner spinnerBackendPreference; // Backend preference spinner (replaces API Key for local models)
    private TextView textViewApiKeyLabel; // Label for API Key / Backend Preference
    private Spinner spinnerApiModel;
    private Spinner spinnerKnowledgeBase;
    private TextView textViewKnowledgeBaseLabel;
    private EditText editTextSystemPrompt;
    private TextView textViewSystemPromptLabel;
    private EditText editTextUserPrompt;
    private Button buttonSendStop;
    private Button buttonNewChat;
    private Spinner spinnerSearchDepth; // Search depth dropdown
    private TextView textViewSearchDepthLabel;
    private Spinner spinnerRerankCount; // Rerank count dropdown
    private TextView textViewRerankCountLabel;
    
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
    private CheckBox checkBoxGraphRagMode;
    private TextView textViewGraphRagLabel;
    private RecyclerView recyclerViewImageThumbnails; // Media thumbnail container (images and audio)
    private MediaThumbnailAdapter mediaThumbnailAdapter; // Media thumbnail adapter
    // 避免程序化设置复选框状态时触发监听器造成误保存
    private boolean isUpdatingUiFromConfig = false;
    private boolean isInitializingKnowledgeBaseSpinner = false;
    // Prevent re-entrant switchMode calls triggered by loadConfig spinner updates
    private boolean isSwitchingMode = false;
    
    // Chat UI components
    private RecyclerView recyclerViewChat; // Chat message list
    private ChatRecyclerViewAdapter chatAdapter; // Chat adapter
    private List<ChatDataItem> chatMessages = new ArrayList<>(); // Chat messages
    
    // Current chat folder path for saving images and conversation
    private String currentChatFolderPath = null;
    
    // Image picker launcher for Android 13+ (supports multiple selection)
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    // Document picker launcher for Android 11/12 (supports multiple selection)
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
    private final AtomicBoolean useExternalTtsForCurrentQuery = new AtomicBoolean(false);
    private final AtomicBoolean useOmniTtsForCurrentQuery = new AtomicBoolean(false); // Track native Omni TTS usage for current query
    
    // Agent related
    private AgentManager agentManager;
    private TextView textViewAgentModeLabel;
    private CheckBox checkBoxAgentMode;
    private final AtomicBoolean isAgentEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isAgentExecuting = new AtomicBoolean(false);
    private PageMode currentMode = null; // Will be initialized from ConfigManager in onCreateView
    
    // Audio compression state tracking (for ANR prevention)
    private final AtomicBoolean isUserAudioCompressing = new AtomicBoolean(false); // User audio compression in progress
    private final AtomicBoolean isAiAudioCompressing = new AtomicBoolean(false);   // AI audio compression in progress
    private volatile String pendingUserAudioM4aPath = null;  // User audio M4A path after compression
    private volatile String pendingAiAudioM4aPath = null;    // AI audio M4A path after compression
    
    // ASR state tracking (to prevent premature resetSendingState)
    private final AtomicBoolean isAsrRunning = new AtomicBoolean(false); // ASR transcription in progress
    
    // Query completion tracking (to prevent premature UI collapse before polling finishes)
    private volatile boolean queryCompleted = false; // LLM query completed, waiting for buffer polling to finish
    private volatile long lastDataReadTime = 0; // Last time data was read from buffer (to ensure UI render time)
    // Once a paragraph boundary is detected, keep full markdown rendering enabled
    // for the rest of current streaming query to avoid instant fallback to plain text.
    private volatile boolean markdownStreamingActivated = false;
    
    // Audio decoding state tracking (for selected audio files)
    private final AtomicBoolean isAudioDecoding = new AtomicBoolean(false); // Audio decoding in progress
    private final AtomicInteger decodingProgress = new AtomicInteger(0);    // Decoding progress (0-100)
    
    private static final String CONFIG_FILE = ".config"; // Configuration file name
    private List<String> systemPromptHistory = new ArrayList<>(); // System prompt history
    private Map<String, String> apiKeyMap = new HashMap<>(); // API Key mapping
    // Track last READY model path per component to detect model reuse
    private final Map<String, String> lastReadyModelPathByComponent = new HashMap<>();
    
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
    // Inference status listener for model state and rerank progress
    private InferenceClient.StatusListener inferenceStatusListener;
    
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

    private int lastFgLogIndex = -1;
    private String restoredResponseTextFromState;
    private boolean hasAppliedRestoredResponseFromState = false;
    private Runnable uiStateResyncRunnable;
    private static final long UI_STATE_RESYNC_INTERVAL_MS = 2000L;
    
    // Buffer polling for streaming UI updates (single source of truth)
    // NOTE: 50ms is a balance between responsiveness and CPU usage.
    // Too slow (200ms) may miss data before buffer is cleared after MD persist.
    // Too fast (1ms) wastes CPU. 50ms should catch most streaming data.
    private Runnable bufferPollRunnable;
    private static final long BUFFER_POLL_INTERVAL_MS = 50L;

    private long lastStreamingHistorySaveTs = 0L;
    private static final long STREAMING_HISTORY_SAVE_INTERVAL_MS = 3000L;

    // Track last diffusion task active state for current chat folder so that
    // we can detect completion transitions even if Fragment callbacks were lost.
    private boolean lastDiffusionTaskActive = false;

    // Background task id for LLM/RAG inference
    private String llmTaskId;

    // Saved query parameters for resume after reranking (legacy, kept for compatibility)
    private String lastApiUrl;
    private String lastApiKey;
    private String lastModel;
    private String lastKnowledgeBase;
    private String lastSystemPrompt;
    private String lastUserPrompt;

    // Manager instance for business logic (UI-independent, survives Fragment destruction)
    private RagQueryManager ragQueryManager;

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
        textViewKnowledgeBaseLabel = view.findViewById(R.id.textViewKnowledgeBaseLabel);
        editTextSystemPrompt = view.findViewById(R.id.editTextSystemPrompt);
        textViewSystemPromptLabel = view.findViewById(R.id.textViewSystemPromptLabel);
        editTextUserPrompt = view.findViewById(R.id.editTextUserPrompt);
        buttonSendStop = view.findViewById(R.id.buttonSendStop);
        buttonNewChat = view.findViewById(R.id.buttonNewChat);
        spinnerSearchDepth = view.findViewById(R.id.spinnerSearchDepth); // Initialize search depth spinner
        textViewSearchDepthLabel = view.findViewById(R.id.textViewSearchDepthLabel);
        spinnerRerankCount = view.findViewById(R.id.spinnerRerankCount); // Initialize rerank count spinner
        textViewRerankCountLabel = view.findViewById(R.id.textViewRerankCountLabel);
        checkBoxThinkingMode = view.findViewById(R.id.checkBoxThinkingModeKey); // Initialize thinking mode checkbox
        checkBoxGraphRagMode = view.findViewById(R.id.checkBoxGraphRagMode);
        textViewGraphRagLabel = view.findViewById(R.id.textViewGraphRagLabel);
        textViewAgentModeLabel = view.findViewById(R.id.textViewAgentModeLabel);
        checkBoxAgentMode = view.findViewById(R.id.checkBoxAgentMode); // Initialize Agent mode checkbox
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
        isInitializingKnowledgeBaseSpinner = true;
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

                String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), selectedApiUrl);
                loadApiKeyForUrl(apiUrl);

                // Skip saving and model fetch during programmatic config load or mode switch
                if (isUpdatingUiFromConfig || isSwitchingMode) {
                    return;
                }

                fetchModelsForApi(); // Automatically fetch model list

                // Save API URL setting by current mode
                if (isAgentMode()) {
                    ConfigManager.setAgentApiUrl(requireContext(), apiUrl);
                    LogManager.logD(TAG, "Saved Agent API URL: " + apiUrl);
                } else {
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, apiUrl);
                    LogManager.logD(TAG, "Saved API URL: " + apiUrl);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Sync API URL spinner from current mode config right before popup opens
        spinnerApiUrl.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                syncApiUrlSpinnerFromModeConfig();
            }
            return false;
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
                
                // Check if user clicked "➕ 新建模型"
                if (selectedModel.equals(getString(R.string.common_add_custom_model))) {
                    // Show dialog to add custom model
                    showAddCustomModelDialog();
                    return;
                }
                
                if (!StateDisplayManager.isModelStatusDisplayText(requireContext(), selectedModel)) {
                    if (isAgentMode()) {
                        ConfigManager.setAgentModelName(requireContext(), selectedModel);
                    } else {
                        // Save to global model name (for compatibility)
                        ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, selectedModel);
                    }
                    
                    // Save to current API's last used model
                    String currentApiUrl = spinnerApiUrl.getSelectedItem().toString();
                    String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), currentApiUrl);
                    ConfigManager.setLastModelForApi(requireContext(), apiUrl, selectedModel);
                    
                    LogManager.logD(TAG, "Saved model name: " + selectedModel + " for API: " + apiUrl);
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
                if (isAgentMode()) {
                    return;
                }
                String selectedKnowledgeBase = parent.getItemAtPosition(position).toString();
                LogManager.logD(TAG, "[KB_SPINNER] onItemSelected: " + selectedKnowledgeBase);

                if (isInitializingKnowledgeBaseSpinner) {
                    LogManager.logD(TAG, "[KB_SPINNER] Ignore selection during initialization: " + selectedKnowledgeBase);
                    return;
                }

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
                if (isAgentMode()) {
                    return;
                }
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
                if (isAgentMode()) {
                    return;
                }
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
                if (isAgentMode()) {
                    return;
                }
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
            ConfigManager.setNoThinking(requireContext(), !isChecked, isAgentMode());
            LogManager.logD(TAG, "Thinking mode changed: " + (isChecked ? "enabled" : "disabled"));
        });

        checkBoxGraphRagMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isAgentMode()) {
                return;
            }
            if (isUpdatingUiFromConfig) {
                LogManager.logD(TAG, "Ignore graph RAG checkbox change during config-driven UI update");
                return;
            }
            ConfigManager.setGraphRagEnabled(requireContext(), isChecked);
            LogManager.logD(TAG, "Graph RAG mode changed: " + (isChecked ? "enabled" : "disabled"));
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

        // Default mode visibility before config is loaded
        applyModeUiVisibility();
        
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
        LogManager.logD(TAG, "[UI_TRACE] onViewCreated - debugPerfConfig=" + showDebugPerf + 
                ", showDebugEnabled=" + ChatViewHolders.AssistantViewHolder.showDebugEnabled +
                ", showPerformanceEnabled=" + ChatViewHolders.AssistantViewHolder.showPerformanceEnabled);
        
        // Initialize main thread Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize Agent Manager
        initializeAgentManager();
        
        // Initialize TtsAdapter and set it to Manager
        ttsAdapter = TtsAdapter.getInstance(requireContext());
        LogManager.logI(TAG, "[TTS] TtsAdapter initialized");
        
        // NOTE: TtsAdapter is set to Manager after Manager initialization in initializeRagQueryManager()
        // This ensures proper initialization order

        // Register inference status listener for model state and rerank progress
        try {
            InferenceClient client = InferenceClient.getInstance(requireContext().getApplicationContext());
            inferenceStatusListener = new InferenceClient.StatusListener() {
                @Override
                public void onModelStateChanged(String component, String modelPath, String state, boolean busy, int threads) {
                    handleInferenceModelStateChanged(component, modelPath, state, busy, threads);
                }

                @Override
                public void onRerankProgress(String taskId, int current, int total) {
                    handleInferenceRerankProgress(taskId, current, total);
                }
            };
            client.addStatusListener(inferenceStatusListener);
            LogManager.logI(TAG, "[STATUS] InferenceClient StatusListener registered in RagQaFragment");
        } catch (Exception e) {
            LogManager.logE(TAG, "[STATUS] Failed to register InferenceClient StatusListener: " + e.getMessage(), e);
        }
        
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
        
        // Initialize mode from ConfigManager - this is the single source of truth
        // Mode persistence is handled entirely by ConfigManager
        boolean savedAgentMode = ConfigManager.getBoolean(requireContext(), ConfigManager.KEY_AGENT_MODE_ENABLED, false);
        currentMode = savedAgentMode ? PageMode.AGENT : PageMode.RAG;
        isAgentEnabled.set(isAgentMode());
        LogManager.logI(TAG, "[MODE_INIT] Initialized mode from config: " + currentMode);

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

        // Ensure chat folder mapping follows current mode before loading history
        switchChatFolderByMode();
        
        // Load chat history if available
        loadChatHistory();

        // Get singleton RagQueryManager and update callback to this Fragment.
        // Manager is a singleton that survives Fragment destruction.
        // We always update the callback to ensure it points to the current Fragment instance.
        ragQueryManager = RagQueryManager.getInstance(requireContext());
        ragQueryManager.updateCallback(new RagQueryManager.RagQueryCallback() {
                @Override
                public void onSendingStateChanged(boolean sending) {
                    isSending.set(sending);
                    if (mainHandler != null) {
                        mainHandler.post(() -> updateButtonText());
                    } else {
                        updateButtonText();
                    }
                }

                @Override
                public void onTtsStateChanged(boolean generating) {
                    LogManager.logI(TAG, "[TTS] onTtsStateChanged: generating=" + generating);
                    isTtsGenerating.set(generating);
                    // Track whether external TTS is enabled for this query
                    // When generating=true, TTS was just enabled; when false, TTS completed
                    if (generating) {
                        useExternalTtsForCurrentQuery.set(true);
                        LogManager.logI(TAG, "[TTS] External TTS enabled for this query");
                    } else {
                        // External/System TTS completed for this query
                        // Reset tracking flag so next query starts from a clean state
                        useExternalTtsForCurrentQuery.set(false);
                        // TTS completed - need to reset sending state if LLM is also done
                        LogManager.logI(TAG, "[TTS] TTS completed, checking if should reset sending state");
                    }
                    if (mainHandler != null) {
                        mainHandler.post(() -> {
                            updateButtonText();
                            // When TTS completes, try to reset sending state
                            // (resetSendingState checks isTtsGenerating internally)
                            if (!generating) {
                                resetSendingState();
                            }
                        });
                    } else {
                        updateButtonText();
                        if (!generating) {
                            resetSendingState();
                        }
                    }
                }

                @Override
                public void onProgressUpdate(int progress, String message) {
                    if (mainHandler != null) {
                        mainHandler.post(() -> updateLlmTaskProgress(progress, message));
                    } else {
                        updateLlmTaskProgress(progress, message);
                    }
                }

                @Override
                public void onQueryComplete(boolean success, String errorMessage) {
                    LogManager.logD(TAG, "[MGR][LLM][CB] onQueryComplete success=" + success + ", error=" + errorMessage);

                    if (!success) {
                        if (errorMessage != null) {
                            updateProgressOnUiThread("Error: " + errorMessage);
                        }
                        // Finalize task and reset state on error
                        finalizeLlmTask(BackgroundTask.TaskState.FAILED, 0,
                                errorMessage != null ? ("API call failed: " + errorMessage) : "API call failed");
                        resetSendingState();
                        return;
                    }

                    // Success path: decide whether external/system TTS is enabled for this query
                    boolean hasTtsEnabledForThisQuery = (useExternalTtsForCurrentQuery.get()
                            && ttsAdapter != null
                            && ttsAdapter.isEnabled());

                    if (hasTtsEnabledForThisQuery) {
                        updateLlmTaskProgress(80, "TTS generation started");
                        isTtsGenerating.set(true);
                        if (mainHandler != null) {
                            mainHandler.post(() -> updateButtonText());
                        } else {
                            updateButtonText();
                        }
                        try {
                            ttsAdapter.complete();
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TTS] Error calling TtsAdapter.complete in onQueryComplete", e);
                            resetSendingState();
                        }
                    } else if (useOmniTtsForCurrentQuery.get()) {
                        // Omni native TTS: defer reset until Omni audio compression completes.
                        // resetSendingState() will be called from checkCompressionCompleteAndFinalize()
                        LogManager.logI(TAG, "[TTS] Omni TTS enabled for this query, deferring resetSendingState to audio compression finalization");
                    } else {
                        // No TTS for this query, mark query completed and let polling finish
                        queryCompleted = true;
                        LogManager.logI(TAG, "[STATE] Query completed, waiting for buffer polling to finish before reset");
                    }

                    // NOTE: Do NOT reload chat history here!
                    // The in-memory chatMessages already has the correct data from streaming.
                    // Calling loadChatHistory() would overwrite it with data from md file,
                    // which may lose debug/image/performance info if md parsing has issues.
                    // loadChatHistory() should only be called when UI is recreated (e.g., after
                    // switching from background) and needs to restore state from persistent storage.
                    LogManager.logD(TAG, "[QUERY_COMPLETE] Skipping loadChatHistory - in-memory data is authoritative");
                }

                @Override
                public void onLlmCompleteWithAudio(String audioPath) {
                    try {
                        // Mirror existing logic that attaches audio to last assistant message
                        if (audioPath == null || audioPath.isEmpty()) {
                            return;
                        }
                        final File audioFile = new File(audioPath);
                        if (!audioFile.exists()) {
                            return;
                        }
                        final float duration = AudioService.getAudioDuration(audioPath);

                        Runnable attachAudioTask = () -> {
                            try {
                                if (!chatMessages.isEmpty()) {
                                    ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
                                    if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                                        lastMsg.audioUri = Uri.fromFile(audioFile);
                                        lastMsg.setHasOmniAudio(true);
                                        lastMsg.setAudioDuration(duration);
                                        if (chatAdapter != null) {
                                            chatAdapter.notifyItemChanged(chatMessages.size() - 1);
                                        }
                                        // NOTE: TtsAdapter already persists audio via appendAssistantAudioMessage
                                        // Fragment only updates UI display, no need to save again
                                    }
                                }
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[TTS] Failed to attach audio from manager callback on main thread", e);
                            }
                        };

                        if (mainHandler != null) {
                            mainHandler.post(attachAudioTask);
                        } else {
                            attachAudioTask.run();
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[TTS] Failed to attach audio from manager callback", e);
                    }
                }

                @Override
                public void onRequestReloadChatHistory() {
                    if (mainHandler != null) {
                        mainHandler.post(() -> loadChatHistory());
                    } else {
                        loadChatHistory();
                    }
                }

                @Override
                public void onRequestUpdateButtonText() {
                    if (mainHandler != null) {
                        mainHandler.post(() -> updateButtonText());
                    } else {
                        updateButtonText();
                    }
                }

                // REMOVED: getTtsAdapter() - Manager now holds TtsAdapter reference directly
                // @Override
                // public TtsAdapter getTtsAdapter() {
                //     return ttsAdapter;
                // }

                @Override
                public void onResetStopFlagsForNewQuery() {
                    if (globalStopFlag) {
                        LogManager.logW(TAG, "[FIX] Detected stale globalStopFlag=true before startQuery task, resetting to false");
                        globalStopFlag = false;
                        userRequestedStop = false;
                    }
                }

                /**
                 * Notification from Manager that a query has started.
                 */
                @Override
                public void onQueryStarted(@NonNull String taskId) {
                    LogManager.logI(TAG, "[MGR][QUERY_STARTED] taskId=" + taskId);
                    llmTaskId = taskId;
                    
                    // CRITICAL: Reset queryCompleted flag for new query
                    // In Agent mode, multiple queries share the same Fragment instance
                    // Previous query's queryCompleted=true would cause next query's polling to stop prematurely
                    queryCompleted = false;
                    lastDataReadTime = 0;
                    LogManager.logI(TAG, "[QUERY_STARTED] Reset queryCompleted=false for new query");
                    
                    // Reset Omni TTS flag for new query
                    // Will be set to true by LocalLLMMNNHandler if talker.mnn exists
                    useOmniTtsForCurrentQuery.set(false);
                    
                    // Initialize UI state
                    initializeSendingState();
                    
                    // Save taskId for UI state tracking and log replay
                    llmTaskId = taskId;
                    // Reset UI read position for new task
                    uiBufferReadPos = -1;
                    
                    // CRITICAL: Start buffer polling loop for this query
                    // This ensures UI can read streaming data from buffer
                    // Must be called here because onResume() is only triggered once when Fragment is created
                    // Subsequent queries won't trigger onResume(), so polling must be started here
                    startBufferPollLoop();
                    LogManager.logI(TAG, "[EXECUTOR] Started buffer polling loop for new query");
                    
                    // Bind to foreground service for task tracking
                    try {
                        if (getActivity() instanceof MainActivity && taskId != null && !taskId.isEmpty()) {
                            UnifiedForegroundService service = ((MainActivity) getActivity()).getUnifiedForegroundService();
                            if (service != null) {
                                service.setCurrentInferenceTaskId(taskId);
                            }
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[TASK] Failed to bind taskId to foreground service: " + e.getMessage(), e);
                    }
                }

                @Override
                public void onRequestStartInferenceForeground(@NonNull String description) {
                    enterInferenceForegroundSession(description);
                }

                @Override
                public void onRequestEndInferenceForeground() {
                    leaveInferenceForegroundSession();
                }
                
                @Override
                public void onAgentActionDetected(String actionType) {
                    // Agent loop is now started directly when user sends message in Agent mode
                    // No need to trigger from streaming output (callback ignored)
                }
                
                @Override
                public void onAsrStateChanged(boolean isRunning) {
                    LogManager.logD(TAG, "[ASR] ASR state changed: " + (isRunning ? "running" : "completed"));
                }
                
            });
        
        // Set TtsAdapter to Manager after callback is set (Manager is now initialized)
        // This ensures TtsAdapter survives UI destruction
        if (ttsAdapter != null) {
            ragQueryManager.setTtsAdapter(ttsAdapter);
            LogManager.logD(TAG, "[TTS] TtsAdapter set to Manager after callback update");
        }
    }

    /**
     * Detect whether current text already contains at least one closed LaTeX formula.
     * Supports inline '$...$' and block '$$...$$'. Escaped '\$' is ignored.
     */
    private boolean hasClosedLatexFormula(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '$') {
                continue;
            }
            if (i > 0 && text.charAt(i - 1) == '\\') {
                continue;
            }

            boolean isDouble = (i + 1 < text.length() && text.charAt(i + 1) == '$');
            if (isDouble) {
                if (!inSingle) {
                    inDouble = !inDouble;
                    if (!inDouble) {
                        return true;
                    }
                }
                i++; // Skip second '$' in '$$'
            } else {
                if (!inDouble) {
                    inSingle = !inSingle;
                    if (!inSingle) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Detect whether current text ends in an unclosed LaTeX state.
     * Supports inline '$...$' and block '$$...$$'. Escaped '\$' is ignored.
     */
    private boolean hasUnclosedLatexFormula(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '$') {
                continue;
            }
            if (i > 0 && text.charAt(i - 1) == '\\') {
                continue;
            }

            boolean isDouble = (i + 1 < text.length() && text.charAt(i + 1) == '$');
            if (isDouble) {
                if (!inSingle) {
                    inDouble = !inDouble;
                }
                i++; // Skip second '$' in '$$'
            } else {
                if (!inDouble) {
                    inSingle = !inSingle;
                }
            }
        }

        return inSingle || inDouble;
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
     * Setup listener for API URL spinner — kept for compatibility but no longer overrides the
     * mode-aware listener set in onCreateView. Use isUpdatingUiFromConfig to suppress saves
     * during programmatic selection in loadConfig().
     */
    private void setupApiUrlSwitchListener() {
        // No-op: the full mode-aware listener is already set in onCreateView (spinnerApiUrl.setOnItemSelectedListener).
        // Do NOT set another listener here — it would override the mode-aware one.
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

    private boolean isAgentMode() {
        return currentMode == PageMode.AGENT;
    }

    private boolean isUiReadyForModeOps() {
        return isAdded()
                && spinnerApiUrl != null
                && editTextApiKey != null
                && spinnerApiModel != null
                && spinnerKnowledgeBase != null
                && editTextSystemPrompt != null
                && checkBoxThinkingMode != null;
    }

    public void switchMode(@NonNull PageMode mode) {
        if (currentMode == mode) {
            return;
        }
        if (isSwitchingMode) {
            LogManager.logW(TAG, "[MODE] switchMode re-entry blocked, mode=" + mode);
            return;
        }
        isSwitchingMode = true;
        try {
        boolean uiReady = isUiReadyForModeOps();
        if (uiReady) {
            saveConfig();
        } else {
            LogManager.logW(TAG, "[MODE] UI not ready during switchMode, skip saveConfig and defer UI-bound refresh");
        }
        currentMode = mode;
        isAgentEnabled.set(isAgentMode());
        ConfigManager.setBoolean(requireContext(), ConfigManager.KEY_AGENT_MODE_ENABLED, isAgentMode());
        if (isAgentMode()) {
            ConfigManager.setInt(requireContext(), ConfigManager.KEY_AGENT_HISTORY_ROUNDS, 0);
            ConfigManager.setHistoryRounds(requireContext(), 0);
        } else {
            int ragRounds = ConfigManager.getInt(requireContext(), ConfigManager.KEY_HISTORY_ROUNDS, ConfigManager.DEFAULT_HISTORY_ROUNDS);
            ConfigManager.setHistoryRounds(requireContext(), ragRounds);
        }
        applyModeUiVisibility();
        if (uiReady) {
            loadConfig();
            // Force refresh model list to ensure spinner displays correct model for current mode
            fetchModelsForApi();
            switchChatFolderByMode();
            loadChatHistory();
        } else {
            LogManager.logW(TAG, "[MODE] Deferred loadConfig/fetchModelsForApi/loadChatHistory until view is ready");
        }
        LogManager.logI(TAG, "[MODE] Switched page mode to " + currentMode);
        } finally {
            isSwitchingMode = false;
        }
    }

    private void applyModeUiVisibility() {
        int ragVisibility = isAgentMode() ? View.GONE : View.VISIBLE;
        if (textViewKnowledgeBaseLabel != null) {
            textViewKnowledgeBaseLabel.setVisibility(ragVisibility);
        }
        if (spinnerKnowledgeBase != null) {
            spinnerKnowledgeBase.setVisibility(ragVisibility);
        }
        if (textViewSearchDepthLabel != null) {
            textViewSearchDepthLabel.setVisibility(ragVisibility);
        }
        if (spinnerSearchDepth != null) {
            spinnerSearchDepth.setVisibility(ragVisibility);
        }
        if (textViewRerankCountLabel != null) {
            textViewRerankCountLabel.setVisibility(ragVisibility);
        }
        if (spinnerRerankCount != null) {
            spinnerRerankCount.setVisibility(ragVisibility);
        }
        if (textViewSystemPromptLabel != null) {
            textViewSystemPromptLabel.setVisibility(ragVisibility);
        }
        if (editTextSystemPrompt != null) {
            editTextSystemPrompt.setVisibility(ragVisibility);
        }
        if (textViewGraphRagLabel != null) {
            textViewGraphRagLabel.setVisibility(ragVisibility);
        }
        if (checkBoxGraphRagMode != null) {
            checkBoxGraphRagMode.setVisibility(ragVisibility);
        }
        if (textViewAgentModeLabel != null) {
            textViewAgentModeLabel.setVisibility(View.GONE);
        }
        if (checkBoxAgentMode != null) {
            checkBoxAgentMode.setVisibility(View.GONE);
        }
    }

    private void switchChatFolderByMode() {
        if (isAgentMode()) {
            String agentFolder = ConfigManager.getAgentChatFolder(requireContext());
            if (agentFolder == null || agentFolder.trim().isEmpty()) {
                agentFolder = ConfigManager.getAgentChatFolderPath(requireContext());
                ConfigManager.setAgentChatFolder(requireContext(), agentFolder);
            }
            ConfigManager.setCurrentChatFolder(requireContext(), agentFolder);
            currentChatFolderPath = agentFolder;
        } else {
            String ragFolder = ConfigManager.getString(requireContext(), ConfigManager.KEY_RAG_CHAT_FOLDER, "");
            ConfigManager.setCurrentChatFolder(requireContext(), ragFolder);
            currentChatFolderPath = ragFolder;
        }
    }
    
    /**
     * Load configuration file
     */
    private void loadConfig() {
        if (!isUiReadyForModeOps()) {
            LogManager.logW(TAG, "[LIFECYCLE] loadConfig skipped because UI is not ready");
            return;
        }
        try {
            String apiUrl;
            String modelName;
            if (isAgentMode()) {
                apiUrl = ConfigManager.getAgentApiUrl(requireContext());
                modelName = ConfigManager.getAgentModelName(requireContext());
                editTextSystemPrompt.setText("");
            } else {
                apiUrl = ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
                modelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
            }

            if (!apiUrl.isEmpty()) {
                // Convert original API URL to display text before setting selection
                String apiUrlDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), apiUrl);
                setSpinnerSelection(spinnerApiUrl, apiUrlDisplayText);
            }

            if (!modelName.isEmpty() &&
                !StateDisplayManager.isModelStatusDisplayText(requireContext(), modelName)) {
                // Check if it's a state display text, if so use directly, otherwise may need conversion
                // Since model names are usually saved directly, use it directly here
                setSpinnerSelection(spinnerApiModel, modelName);
            }

            if (!isAgentMode()) {
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
        
        // Load thinking mode and Graph RAG settings
        // Note: no_thinking=TRUE unchecks, false checks
            isUpdatingUiFromConfig = true;
            boolean noThinking = ConfigManager.getNoThinking(requireContext(), isAgentMode());
            checkBoxThinkingMode.setChecked(!noThinking);
            boolean graphRagEnabled = ConfigManager.isGraphRagEnabled(requireContext());
            if (checkBoxGraphRagMode != null) {
                checkBoxGraphRagMode.setChecked(!isAgentMode() && graphRagEnabled);
            }
            isAgentEnabled.set(isAgentMode());
            
            LogManager.logD(TAG, "Loaded thinking mode setting: " + (!noThinking ? "enabled" : "disabled"));
            LogManager.logD(TAG, "Loaded Graph RAG mode setting: " + (graphRagEnabled ? "enabled" : "disabled"));
            LogManager.logD(TAG, "Loaded Agent mode setting: " + (isAgentMode() ? "enabled" : "disabled"));
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
        if (!isUiReadyForModeOps()) {
            LogManager.logW(TAG, "[LIFECYCLE] saveConfig skipped because UI is not ready");
            return;
        }
        try {
            // Get currently selected values
            String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
            String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
            String apiKey = editTextApiKey.getText().toString();
            String model = spinnerApiModel.getSelectedItem().toString();
            String knowledgeBase = spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = editTextSystemPrompt.getText().toString();

            if (isAgentMode()) {
                ConfigManager.setAgentApiUrl(requireContext(), apiUrl);
                if (StateDisplayManager.isModelStatusDisplayText(requireContext(), model)) {
                    ConfigManager.setAgentModelName(requireContext(), "");
                } else {
                    ConfigManager.setAgentModelName(requireContext(), model);
                }
                ConfigManager.setInt(requireContext(), ConfigManager.KEY_AGENT_HISTORY_ROUNDS, 0);
            } else {
                // Save directly to first-level configuration
                ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, apiUrl);
                if (StateDisplayManager.isModelStatusDisplayText(requireContext(), model)) {
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
                } else {
                    ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, model);
                }
                ConfigManager.setString(requireContext(), ConfigManager.KEY_KNOWLEDGE_BASE, knowledgeBase);

                // Save system prompt (using first-level item)
                // Save regardless of empty or not, ensuring user can correctly save when clearing system prompt
                ConfigManager.setSystemPrompt(requireContext(), systemPrompt);
                LogManager.logD(TAG, "Saved system prompt: " + (systemPrompt.isEmpty() ? "[empty]" : systemPrompt));

                if (checkBoxGraphRagMode != null) {
                    boolean graphRagEnabled = checkBoxGraphRagMode.isChecked();
                    ConfigManager.setGraphRagEnabled(requireContext(), graphRagEnabled);
                    LogManager.logD(TAG, "Saved Graph RAG mode: " + (graphRagEnabled ? "enabled" : "disabled"));
                }
            }
            
            // Save API Key to corresponding URL
            if (!apiKey.isEmpty()) {
                ConfigManager.saveApiKey(requireContext(), apiUrl, apiKey);
                LogManager.logD(TAG, "Saved API Key to URL: " + apiUrl);
            }
            
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
                    if (adapter instanceof ApiUrlAdapter) {
                        ((ApiUrlAdapter) adapter).setSelectedPosition(i);
                    }
                    break;
                }
            }
        }
    }

    private void syncApiUrlSpinnerFromModeConfig() {
        try {
            isUpdatingUiFromConfig = true;
            loadApiUrlList();
            String apiUrl = isAgentMode()
                    ? ConfigManager.getAgentApiUrl(requireContext())
                    : ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
            if (apiUrl != null && !apiUrl.isEmpty()) {
                String apiUrlDisplayText = StateDisplayManager.getApiUrlDisplayText(requireContext(), apiUrl);
                setSpinnerSelection(spinnerApiUrl, apiUrlDisplayText);
                updateApiKeyOrBackendDisplay(apiUrlDisplayText);
                loadApiKeyForUrl(apiUrl);
            }
        } finally {
            isUpdatingUiFromConfig = false;
        }
    }

    private void replayInferenceLogsFromUnifiedService() {
        try {
            if (!isAdded() || getActivity() == null) {
                LogManager.logD(TAG, "[FG][REPLAY] Skip, Fragment not attached");
                return;
            }
            
            // NOTE: This method is DEPRECATED. Buffer polling is now handled by pollBufferAndUpdateUi().
            // Kept for backward compatibility but should not be called.
            if (ragQueryManager != null && llmTaskId != null && !llmTaskId.isEmpty()) {
                String consumerId = llmTaskId + "_" + fragmentInstanceId;
                java.util.List<String> newLogs = ragQueryManager.getNewLogsForConsumer(llmTaskId, consumerId);
                if (newLogs != null && !newLogs.isEmpty()) {
                    LogManager.logI(TAG, "[FG][REPLAY] Replaying " + newLogs.size() + " logs from buffer");
                    
                    // CRITICAL FIX: Ensure assistant placeholder exists before replaying
                    // After UI reconstruction, chatMessages is loaded from conversation.md which
                    // doesn't include the in-progress assistant message. We need to create a
                    // placeholder so that updateChatMessage() can append streaming content.
                    ensureAssistantPlaceholderForReplay();
                    
                    for (String line : newLogs) {
                        if (line != null && !line.isEmpty()) {
                            // writeToBuffer=false: content already in buffer, just update UI
                            updateChatMessage(line, false);
                        }
                    }
                }
                return;
            }
            LogManager.logD(TAG, "[FG][REPLAY] Skip replay: manager or llmTaskId not ready");
        } catch (Exception e) {
            LogManager.logE(TAG, "[FG] Failed to replay inference logs: " + e.getMessage(), e);
        }
    }
    
    /**
     * Ensure an assistant placeholder message exists for replay.
     * Called before replaying logs after UI reconstruction.
     * If the last message is not ASSISTANT type, create a new placeholder.
     */
    private void ensureAssistantPlaceholderForReplay() {
        if (chatMessages == null || chatAdapter == null) {
            LogManager.logW(TAG, "[PLACEHOLDER][ERROR] Cannot ensure placeholder: chatMessages=" + (chatMessages != null) + ", adapter=" + (chatAdapter != null));
            return;
        }
        
        // Check if last message is already ASSISTANT type
        if (!chatMessages.isEmpty()) {
            ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
            if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                return;
            }
        }
        
        // Create new assistant placeholder for streaming content
        ChatDataItem placeholder = new ChatDataItem(ChatViewHolders.ASSISTANT);
        placeholder.setLoading(true);
        chatMessages.add(placeholder);
        
        // Notify adapter on UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            smartScrollToBottom();
        } else if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                smartScrollToBottom();
            });
        }
    }


    // ...

    private void loadApiUrlList() {
        LogManager.logD(TAG, "Starting to load API URL list");
        
        // Merge predefined and custom API URL lists
        List<String> apiUrlsList = new ArrayList<>();
        
        // ...
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
                        // Save currently selected API URL by current mode
                        if (isAgentMode()) {
                            ConfigManager.setAgentApiUrl(requireContext(), internalApiUrl);
                        } else {
                            ConfigManager.setString(requireContext(), ConfigManager.KEY_API_URL, internalApiUrl);
                        }
                    }
                },
                spinnerApiUrl
        );
        
        spinnerApiUrl.setAdapter(adapter);
        
        // Set currently selected API URL by current mode
        String currentApiUrl = isAgentMode()
            ? ConfigManager.getAgentApiUrl(requireContext())
            : ConfigManager.getString(requireContext(), ConfigManager.KEY_API_URL, "");
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
        isInitializingKnowledgeBaseSpinner = true;
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
        isInitializingKnowledgeBaseSpinner = false;
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
            String knowledgeBase = isAgentMode() ? "" : spinnerKnowledgeBase.getSelectedItem().toString();
            String systemPrompt = isAgentMode() ? "" : editTextSystemPrompt.getText().toString();
            String userPrompt = editTextUserPrompt.getText().toString();
            
            LogManager.logI(TAG, "[SEND] User clicked send - model=" + model + ", kb=" + knowledgeBase);
            // Debug snapshot at send click (English log)
            try {
                boolean ui_isSending = isSending.get();
                boolean ui_isTaskRunning = isTaskRunning;
                boolean ui_isTaskCancelled = isTaskCancelled;
                String modelState = "";
                boolean llmBusy = false;
                boolean llmRunning = false;
                boolean llmShouldStop = false; // Stop flag is managed in child process now

                // State snapshot removed - too verbose    );
            } catch (Throwable th) {
                LogManager.logE(TAG, "Error collecting send-click snapshot", th);
            }

            // Cleanup leftover RAG Future before new send
            try {
                if (ragTaskFuture != null) {
                    if (!ragTaskFuture.isDone()) {
                        boolean cancelBeforeSend = ragTaskFuture.cancel(false);
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

        // Build QueryRequest snapshot for manager
        int searchDepth = isAgentMode() ? 0 : Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
        boolean graphRagEnabled = !isAgentMode() && (checkBoxGraphRagMode != null && checkBoxGraphRagMode.isChecked());
        if (isAgentMode()) {
            LogManager.logI(TAG, "[AGENT_RAG] Agent mode request snapshot: knowledgeBase=\"\", systemPrompt=\"\", searchDepth=0, graphRag=false");
        }
        boolean needsAsr = false;
        String asrModel = null;
        if (userInput.hasAudio()) {
            asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
            if (!"无".equals(asrModel)) {
                needsAsr = true;
            }
        }

        // CRITICAL: Both Agent mode and Normal mode need to call startQuery to create llmTaskId and enable polling
        // Agent mode adds autonomous loop execution on top of normal LLM query
        if (ragQueryManager == null) {
            LogManager.logE(TAG, "[SEND] ragQueryManager is null, manager-driven pipeline is required; aborting send");
            restoreSendStateAfterValidationFailure("ragQueryManager is null");
            return;
        }
        
        // Create query request (used by both Agent and Normal mode)
        RagQueryManager.QueryRequest request = new RagQueryManager.QueryRequest(
                apiUrl,
                apiKey,
                model,
                knowledgeBase,
                systemPrompt,
                userPrompt,
                userInput.imagePaths,
                userInput.audioPaths,
                userInput.audioDuration,
                searchDepth,
                graphRagEnabled,
                needsAsr,
                asrModel
        );
        
        // CRITICAL: Agent mode vs Normal mode branching
        if (isAgentMode()) {
            // Agent mode: Skip normal LLM query, directly start Agent loop
            LogManager.logI(TAG, "[AGENT] Agent mode enabled, skipping normal query and starting Agent loop");
            
            if (agentManager == null) {
                LogManager.logE(TAG, "[AGENT] agentManager is null, falling back to normal query");
                Toast.makeText(requireContext(), "Agent未初始化，执行普通查询", Toast.LENGTH_SHORT).show();
                ragQueryManager.startQuery(request);
            } else {
                // Save task goal for Agent
                lastUserPrompt = userPrompt;
                
                // Initialize UI state for Agent execution
                initializeSendingState();
                
                // Start Agent loop (will handle LLM calls with proper Agent system prompt)
                agentManager.startAgentLoop(userPrompt, ragQueryManager);
                
                LogManager.logI(TAG, "[AGENT] Agent loop started, task: " + userPrompt);
            }
        } else {
            // Normal mode: Start LLM query (creates llmTaskId, registers callbacks, enables polling)
            LogManager.logI(TAG, "[SEND] Normal mode, delegating query execution to RagQueryManager.startQuery");
            ragQueryManager.startQuery(request);
        }

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
                
                // Set UI-local flags for UI state tracking
                globalStopFlag = true;
                isTaskCancelled = true;
                
                // Delegate stop to RagQueryManager (handles all component stop logic + Manager-owned flags)
                // Manager's requestStopFromManager() sets Manager-owned stop flags (SINGLE SOURCE OF TRUTH)
                ragQueryManager.requestStopFromManager();
                
                // CRITICAL FIX: Use cancel(false) instead of cancel(true) for graceful stop
                // cancel(true) calls Thread.interrupt() which is abrupt interruption
                // cancel(false) only sets flag, lets native call finish naturally, then checks mShouldStop
                // This follows LLM's graceful stop pattern: set flag  wait for natural checkpoint  stop
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
        markdownStreamingActivated = false;
        // [Important] Do not reset global stop flag, maintain previous stop state
        LogManager.logD(TAG, "Initializing sending state - task running: " + isTaskRunning + ", cancelled: " + isTaskCancelled + ", global stop flag unchanged: " + globalStopFlag);
        LogManager.logI(TAG, "[STATE] initializeSendingState - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
        
        // Update button text to show "推理中..."
        if (mainHandler != null) {
            mainHandler.post(this::updateButtonText);
        }
    }
    
    /**
     * Reset all sending states
     * Unified management of all state variable resets, ensuring state consistency
     * [Fix] Only reset global stop flag after confirming all tasks have truly stopped
     */
    private void resetSendingState() {
        LogManager.logI(TAG, "[RESET_STATE][ENTER] thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis() + ", isSending=" + isSending.get() + ", isTaskRunning=" + isTaskRunning + ", isTaskCancelled=" + isTaskCancelled + ", globalStopFlag=" + globalStopFlag + ", queryCompleted=" + queryCompleted);
        
        // Reset queryCompleted flag
        queryCompleted = false;
        lastDataReadTime = 0;
        markdownStreamingActivated = false;
        LogManager.logI(TAG, "[RESET_STATE][FLAGS] Reset queryCompleted=false, lastDataReadTime=0");
        
        // NOTE: For local models (LLM/Diffusion), history is persisted by manager/handler
        // via appendAssistantTextMessage/appendAssistantImageMessage. Fragment only reloads.
        // For remote APIs, history is saved here as they don't have a backend writer.
        // TODO: Clean up this legacy path - remote API should also use manager-driven persistence
        // saveChatHistory(); // Disabled: manager handles persistence, Fragment only displays;
        
        // Check if TTS is still generating
        if (isTtsGenerating.get()) {
            LogManager.logW(TAG, "[RESET_STATE][DEFER] TTS is still generating, conversation saved but state reset deferred");
            return;
        }
        LogManager.logD(TAG, "[RESET_STATE][TTS] TTS not generating, continuing with reset");
        
        // Finalize LLM background task before clearing flags
        if (llmTaskId != null) {
            BackgroundTask.TaskState finalState;
            int finalProgress;
            String finalMessage;

            if (isTaskCancelled || globalStopFlag || userRequestedStop) {
                finalState = BackgroundTask.TaskState.CANCELLED;
                finalProgress = 0;
                finalMessage = "LLM inference cancelled";
            } else {
                finalState = BackgroundTask.TaskState.COMPLETED;
                finalProgress = 100;
                finalMessage = "LLM inference completed";
            }

            finalizeLlmTask(finalState, finalProgress, finalMessage);
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
                ragTaskFuture.cancel(false);
            }
            ragTaskFuture = null;
            LogManager.logD(TAG, "Cleared ragTaskFuture reference");
        }
        
        LogManager.logI(TAG, "[RESET_STATE][BEFORE] isSending=" + isSending.get() + ", isTaskRunning=" + isTaskRunning);
        isSending.set(false); // Use atomic operation to reset sending state
        LogManager.logI(TAG, "[RESET_STATE][AFTER] isSending=" + isSending.get());
        
        // Clear media thumbnails after successful send
        if (mediaThumbnailAdapter != null && mediaThumbnailAdapter.getMediaCount() > 0) {
            mainHandler.post(() -> {
                mediaThumbnailAdapter.clearMedia();
                recyclerViewImageThumbnails.setVisibility(View.GONE);
                LogManager.logD(TAG, "[RESET_STATE][UI] Cleared media thumbnails after send");
            });
        }
        
        // Auto-collapse collapsible sections after streaming completes
        // Note: Parsing is already done in performUpdateChatMessage during streaming
        // Here we only need to collapse the sections (fold, not hide)
        // Section visibility is controlled by ChatViewHolders.AssistantViewHolder static flags:
        // - showDebugEnabled, showThinkingEnabled, showPerformanceEnabled
        // These flags are set from ConfigManager in onViewCreated()
        if (!chatMessages.isEmpty()) {
            ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
            if (lastMsg.getType() == ChatViewHolders.ASSISTANT) {
                LogManager.logD(TAG, "[RESET_STATE][UI] Collapsing assistant message sections, isLoading=" + lastMsg.getLoading());
                
                // Collapse sections (fold, not hide)
                lastMsg.setShowDebug(false);
                lastMsg.setShowThinking(false);
                lastMsg.setShowPerformance(false);
                lastMsg.setLoading(false);
                
                // Update UI
                if (mainHandler != null && chatAdapter != null) {
                    mainHandler.post(() -> {
                        try {
                            if (getActivity() != null && isAdded() && !isDetached()) {
                                // Force full markdown re-render by calling notifyItemChanged WITHOUT payload.
                                // updateRecentItem() uses a payload → plain text only (streaming path).
                                // notifyItemChanged(pos) without payload triggers full bind() → markdown.setMarkdown().
                                int lastPos = chatMessages.size() - 1;
                                if (lastPos >= 0) {
                                    chatAdapter.notifyItemChanged(lastPos);
                                }
                                LogManager.logD(TAG, "Force full markdown render after streaming complete");
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to force markdown render after streaming", e);
                        }
                    });
                }
            }
        }
        
        // Update button state on UI thread, add Fragment lifecycle check
        LogManager.logD(TAG, "[RESET_STATE][UI] Posting updateButtonText and clearKeepScreenOn to main thread");
        if (mainHandler != null) {
            mainHandler.post(() -> {
                if (getActivity() != null && isAdded() && !isDetached()) {
                    LogManager.logD(TAG, "[RESET_STATE][UI] Executing updateButtonText");
                    updateButtonText();
                    LogManager.logD(TAG, "[RESET_STATE][UI] Executing enableKeepScreenOn(false)");
                    enableKeepScreenOn(false);
                } else {
                    LogManager.logW(TAG, "[RESET_STATE][UI] Cannot update UI: activity=" + (getActivity() != null) + ", isAdded=" + isAdded() + ", isDetached=" + isDetached());
                }
                ragQueryManager.requestEndInferenceForeground();
                LogManager.logD(TAG, "[UI_TRACE] resetSendingState - after leaveInferenceForegroundSession");
            });
        }
        
        // CRITICAL: Mark buffer as persisted AFTER polling completes
        // This ensures all streaming data has been read by UI before marking as persisted
        if (ragQueryManager != null && llmTaskId != null && !llmTaskId.isEmpty()) {
            ragQueryManager.markBufferAsPersisted(llmTaskId);
            LogManager.logI(TAG, "[RESET_STATE][PERSIST] Marked buffer as persisted after polling complete, taskId=" + llmTaskId);
        }
    }

    // Lightweight UI state resync: restore basic flags based on active background tasks
    // Returns true if there is any active LLM/TTS/Diffusion task for current chat folder.
    // Uses BackgroundTaskManager.getActiveTaskSummary() for efficient lookup.
    private boolean resyncUiStateWithBackgroundTasks() {
        try {
            String currentFolder = currentChatFolderPath;
            if (TextUtils.isEmpty(currentFolder)) {
                currentFolder = ConfigManager.getString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            }
            if (TextUtils.isEmpty(currentFolder)) {
                return false;
            }

            // Use the new unified API for task summary
            BackgroundTaskManager.TaskSummary summary = BackgroundTaskManager.getInstance().getActiveTaskSummary(currentFolder);
            
            boolean hasLlmTaskForFolder = summary.hasLlmTask;
            boolean hasTtsTaskForFolder = summary.hasTtsTask;
            boolean hasDiffusionTaskForFolder = summary.hasDiffusionTask;
            
            // Update llmTaskId if we found an active LLM task but don't have the ID
            // NOTE: No need to reset cursor here. The new design:
            // - Cursor is reset to 0 by RagQueryManager when MD is persisted
            // - New consumer starts at cursor 0 automatically
            // - replayInferenceLogsFromUnifiedService() will pop all unpersisted content
            if (hasLlmTaskForFolder && (llmTaskId == null || llmTaskId.isEmpty())) {
                llmTaskId = summary.llmTaskId;
                LogManager.logD(TAG, "[TASK] Restored llmTaskId from active task: " + llmTaskId);
            }
            
            // Always sync UnifiedForegroundService's currentInferenceTaskId when there's an active LLM task
            // This ensures log routing works correctly after Fragment recreation
            if (hasLlmTaskForFolder && llmTaskId != null && !llmTaskId.isEmpty()) {
                if (getActivity() instanceof MainActivity) {
                    UnifiedForegroundService service = ((MainActivity) getActivity()).getUnifiedForegroundService();
                    if (service != null) {
                        String currentServiceTaskId = service.getCurrentInferenceTaskId();
                        if (!llmTaskId.equals(currentServiceTaskId)) {
                            LogManager.logD(TAG, "[TASK] Syncing UnifiedForegroundService taskId: " + currentServiceTaskId + " -> " + llmTaskId);
                            service.setCurrentInferenceTaskId(llmTaskId);
                        }
                    }
                }
            }

            // If there is an active LLM or Diffusion task for this folder, restore sending state
            if (hasLlmTaskForFolder || hasDiffusionTaskForFolder) {
                isTaskRunning = true;
                isTaskCancelled = false;
                isSending.set(true);
            } else {
                // No active LLM/Diffusion task - reset sending state if it was set
                if (isSending.get()) {
                    LogManager.logD(TAG, "[TASK] No active LLM/Diffusion task, resetting isSending state");
                    isSending.set(false);
                    isTaskRunning = false;
                }
            }

            // If there is an active TTS task for this folder, restore TTS state
            if (hasTtsTaskForFolder) {
                isTtsGenerating.set(true);
            } else {
                // No active TTS task - reset TTS state if it was set
                if (isTtsGenerating.get()) {
                    LogManager.logD(TAG, "[TASK] No active TTS task, resetting isTtsGenerating state");
                    isTtsGenerating.set(false);
                }
            }

            // Always update button text to reflect current state
            updateButtonText();

            // Detect diffusion completion for current chat folder so that we can
            // reload chat history and surface newly appended image messages even
            // if streaming callbacks were lost during Fragment recreation.
            if (lastDiffusionTaskActive && !hasDiffusionTaskForFolder) {
                LogManager.logD(TAG, "[TASK] Detected diffusion task completion for current folder, reloading chat history");
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            if (getActivity() == null || !isAdded() || isDetached()) {
                                LogManager.logW(TAG, "Cannot reload chat history, Fragment not attached");
                                return;
                            }
                            // Reload conversation from markdown so that diffusion
                            // result image messages and performance info are
                            // reflected in chat UI after small-window switch or
                            // Fragment recreation.
                            loadChatHistory();
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TASK] Failed to reload chat history after diffusion completion", e);
                        }
                    });
                }
            }

            lastDiffusionTaskActive = hasDiffusionTaskForFolder;

            return summary.hasAnyTask;
        } catch (Exception e) {
            LogManager.logE(TAG, "[TASK] Failed to resync UI state with background tasks: " + e.getMessage(), e);
            return false;
        }
    }

    // Apply restored debug response text snapshot from saved state if it has not
    // been applied yet. This is called from onResume before replaying new logs
    // from UnifiedForegroundService to avoid overriding them.
    //
    // CRITICAL: When llmTaskId is available and RingBuffer has data, we should
    // skip this method and rely solely on replayInferenceLogsFromUnifiedService()
    // to restore the UI. Otherwise, we get duplicate content:
    // 1. First from savedInstanceState (this method)
    // 2. Then from RingBuffer replay (replayInferenceLogsFromUnifiedService)
    private void applyRestoredResponseTextFromStateIfNeeded() {
        try {
            if (hasAppliedRestoredResponseFromState) {
                LogManager.logD(TAG, "[UI_TRACE] applyRestoredResponseTextFromStateIfNeeded - already applied, skip");
                return;
            }
            if (restoredResponseTextFromState == null || restoredResponseTextFromState.isEmpty()) {
                LogManager.logD(TAG, "[UI_TRACE] applyRestoredResponseTextFromStateIfNeeded - no restored text, skip");
                return;
            }
            
            // CRITICAL: If llmTaskId is available and RingBuffer has data, skip this method
            // The RingBuffer replay will restore the UI content instead
            if (llmTaskId != null && !llmTaskId.isEmpty()) {
                TaskLogBuffer buffer = BackgroundTaskManager.getInstance().getLogBuffer(llmTaskId);
                if (buffer != null && buffer.getLogCount() > 0) {
                    LogManager.logD(TAG, "[UI_TRACE] applyRestoredResponseTextFromStateIfNeeded - " +
                            "skipping, will use RingBuffer replay instead (taskId=" + llmTaskId + 
                            ", bufferSize=" + buffer.getLogCount() + ")");
                    hasAppliedRestoredResponseFromState = true;
                    restoredResponseTextFromState = null;
                    return;
                }
            }
            
            LogManager.logD(TAG, "[UI_TRACE] applyRestoredResponseTextFromStateIfNeeded - applying restored text, len=" +
                    restoredResponseTextFromState.length());
            updateResultOnUiThread(restoredResponseTextFromState);
            hasAppliedRestoredResponseFromState = true;
            restoredResponseTextFromState = null;
            LogManager.logD(TAG, "[UI_TRACE] applyRestoredResponseTextFromStateIfNeeded - applied and cleared state");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to apply restored response text from state", e);
        }
    }

    // Periodic UI state resync loop used to keep send/stop button state in sync
    // with BackgroundTaskManager even if callbacks were lost due to Fragment
    // recreation.
    private void startUiStateResyncLoop() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        if (uiStateResyncRunnable != null) {
            return;
        }
        uiStateResyncRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isAdded() || getActivity() == null || isDetached()) {
                        uiStateResyncRunnable = null;
                        return;
                    }

                    boolean hasActiveTasksForFolder = resyncUiStateWithBackgroundTasks();

                    // NOTE: Buffer polling is now handled by bufferPollRunnable (200ms interval).
                    // This loop only handles UI state resync (button state, etc.) every 2s.

                    // If no active tasks remain for this folder but UI still thinks
                    // it is sending or TTS generating, perform a safe reset.
                    if (!hasActiveTasksForFolder && (isSending.get() || isTtsGenerating.get() || isTaskRunning)) {
                        LogManager.logD(TAG, "[STATE] Periodic resync detected no active tasks, resetting sending state");
                        resetSendingState();
                    }

                    if (uiStateResyncRunnable != null && mainHandler != null) {
                        mainHandler.postDelayed(uiStateResyncRunnable, UI_STATE_RESYNC_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[STATE] UI state resync loop error: " + e.getMessage(), e);
                }
            }
        };
        mainHandler.postDelayed(uiStateResyncRunnable, UI_STATE_RESYNC_INTERVAL_MS);
    }

    private void stopUiStateResyncLoop() {
        if (mainHandler != null && uiStateResyncRunnable != null) {
            mainHandler.removeCallbacks(uiStateResyncRunnable);
        }
        uiStateResyncRunnable = null;
    }

    // Unique fragment instance ID for buffer consumer cursor.
    // Each Fragment instance gets a unique ID, so UI reconstruction automatically
    // starts with cursor=0 (new consumer, getOrDefault returns 0).
    private final String fragmentInstanceId = java.util.UUID.randomUUID().toString().substring(0, 8);
    
    // UI-maintained read position for ring buffer.
    // -1 means not initialized yet, will be set to persistedPos on first poll.
    private long uiBufferReadPos = -1;
    
    /**
     * Start buffer polling loop for streaming UI updates.
     * This is the SINGLE source of truth for UI updates - all streaming data
     * goes through the buffer, and UI polls from it every 200ms.
     * 
     * NOTE: Each Fragment instance has a unique fragmentInstanceId, so after UI
     * reconstruction, the new Fragment is a new consumer with cursor=0 automatically.
     * No manual reset needed.
     */
    private void startBufferPollLoop() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        if (bufferPollRunnable != null) {
            return; // Already running
        }
        
        bufferPollRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isAdded() || getActivity() == null || isDetached()) {
                        bufferPollRunnable = null;
                        return;
                    }

                    // Poll new logs from buffer and update UI
                    pollBufferAndUpdateUi();

                    // Continue polling if runnable is still active
                    if (bufferPollRunnable != null && mainHandler != null) {
                        mainHandler.postDelayed(bufferPollRunnable, BUFFER_POLL_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[POLL] Buffer poll loop error: " + e.getMessage(), e);
                }
            }
        };
        // Start immediately
        mainHandler.post(bufferPollRunnable);
    }

    private void stopBufferPollLoop() {
        if (mainHandler != null && bufferPollRunnable != null) {
            mainHandler.removeCallbacks(bufferPollRunnable);
        }
        bufferPollRunnable = null;
    }

    /**
     * Poll new logs from buffer and update UI.
     * Called every 50ms by bufferPollRunnable.
     * 
     * UI maintains its own read position (uiBufferReadPos):
     * - First poll: get persistedPos from Manager as starting point
     * - Subsequent polls: read from uiBufferReadPos, update to newReadPos
     * - This avoids re-reading the same data every poll
     */
    private void pollBufferAndUpdateUi() {
        if (ragQueryManager == null || llmTaskId == null || llmTaskId.isEmpty()) {
            // Skip polling if no active task (no log to avoid spam)
            return;
        }
        

        // First poll: initialize uiBufferReadPos to 0 (read all data for UI display)
        // Note: persistedPos indicates data written to markdown, NOT data displayed in UI
        if (uiBufferReadPos < 0) {
            uiBufferReadPos = 0;
            LogManager.logI(TAG, "[POLL][INIT] Initialized uiBufferReadPos=0 (read all data for UI)");
        }
        
        // Read from our maintained position
        TaskLogBuffer.ReadResult result = ragQueryManager.readBufferFromPos(llmTaskId, uiBufferReadPos);
        
        // If no data to read
        if (result == null || !result.hasData()) {
            // Check if query completed - if so, stop polling and reset
            // BUT: ensure at least 1000ms (1 second) have passed since last data read
            // This gives buffer enough time to collect all remaining data and UI enough time to render
            if (queryCompleted) {
                long timeSinceLastRead = System.currentTimeMillis() - lastDataReadTime;
                if (lastDataReadTime == 0 || timeSinceLastRead >= 1000) {
                    //LogManager.logI(TAG, "[POLL][STOP] Query completed and buffer fully read (waited " + timeSinceLastRead + "ms), stopping poll and resetting state");
                    queryCompleted = false;
                    lastDataReadTime = 0;
                    stopBufferPollLoop();
                    resetSendingState();
                } else {
                    // Only log once when waiting starts
                    //if (timeSinceLastRead < 100) {
                        //LogManager.logI(TAG, "[POLL][WAIT] Query completed, waiting for buffer collection (need 1000ms)");
                    //}
                }
            }
            return;
        }
        
        // Update our read position for next poll
        uiBufferReadPos = result.newReadPos;
        lastDataReadTime = System.currentTimeMillis(); // Record data read time

        // Ensure assistant placeholder exists before updating
        ensureAssistantPlaceholderForReplay();

        // Split data into lines and update UI
        String data = result.data;
        int start = 0;
        int lineCount = 0;
        for (int i = 0; i < data.length(); i++) {
            if (data.charAt(i) == '\n') {
                String line = data.substring(start, i + 1);
                if (!line.isEmpty()) {
                    lineCount++;
                    performUpdateChatMessage(line);
                }
                start = i + 1;
            }
        }
        // Handle remaining content without trailing newline
        if (start < data.length()) {
            String line = data.substring(start);
            if (!line.isEmpty()) {
                lineCount++;
                performUpdateChatMessage(line);
            }
        }
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
            if (buttonNewChat != null) {
                buttonNewChat.setEnabled(false);
                buttonNewChat.setAlpha(0.5f);
            }
        } else if (isTtsGenerating.get()) {
            buttonSendStop.setText(getString(R.string.button_generating_tts));
            buttonSendStop.setEnabled(true); // Allow stopping TTS
            if (buttonNewChat != null) {
                buttonNewChat.setEnabled(false);
                buttonNewChat.setAlpha(0.5f);
            }
        } else if (isSending.get()) {
            buttonSendStop.setText(getString(R.string.button_inferring));
            buttonSendStop.setEnabled(true); // Allow stopping inference
            if (buttonNewChat != null) {
                buttonNewChat.setEnabled(false);
                buttonNewChat.setAlpha(0.5f);
            }
        } else {
            buttonSendStop.setText(getString(R.string.button_send));
            buttonSendStop.setEnabled(true); // Allow sending new message
            if (buttonNewChat != null) {
                buttonNewChat.setEnabled(true);
                buttonNewChat.setAlpha(1.0f);
            }
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
    // ============================================
    // DEPRECATED LEGACY METHODS REMOVED
    // ============================================
    // The following legacy methods have been removed as they are no longer called:
    // - executeRagQuery(): Legacy entry point, replaced by RagQueryManager.startQuery()
    // - executeRagQueryInternal(): Legacy ASR decision helper
    // - executeRagQueryWithAsr(): Legacy main implementation (350+ lines)
    // - convertAndSendAsTextInternal(): Legacy ASR conversion
    // - sendAudioToModelInternal(): Legacy audio fallback
    //
    // All query execution now goes through:
    // 1. RagQueryManager.startQuery() - entry point
    // 2. RagQueryManager.runAsrAndContinue() - ASR pipeline
    // 3. RagQueryCallback.onStartQueryWithAsrResult() - callback to Fragment
    // 4. RagQueryManager.runFullRagPipelineFromAsrResult() - main pipeline
    // ============================================

    // NOTE: The following method was the legacy main implementation.
    // It has been replaced by RagQueryManager.runFullRagPipelineFromAsrResult().
    // Keeping a stub here to catch any accidental calls during transition.
    @Deprecated
    private void executeRagQueryWithAsr(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt, String asrInfo, boolean skipAudioEmbedding, UserInput userInput) {
        LogManager.logE(TAG, "[EXECUTOR] FATAL: executeRagQueryWithAsr called - this is a DEPRECATED legacy path");
        LogManager.logE(TAG, "[EXECUTOR] All queries must go through RagQueryManager.startQuery() -> runFullRagPipelineFromAsrResult()");
        mainHandler.post(() -> {
            Toast.makeText(requireContext(), "Internal error: legacy query path called", Toast.LENGTH_SHORT).show();
            resetSendingState();
        });
    }

    // Legacy method stub - replaced by RagQueryManager.startQuery()
    @Deprecated
    private void executeRagQuery(String apiUrl, String apiKey, String model, String knowledgeBase, String systemPrompt, String userPrompt, UserInput userInput) {
        LogManager.logE(TAG, "[EXECUTOR] FATAL: executeRagQuery called - this is a DEPRECATED legacy path");
        mainHandler.post(() -> {
            Toast.makeText(requireContext(), "Internal error: legacy query path called", Toast.LENGTH_SHORT).show();
            resetSendingState();
        });
    }

    // ============================================
    // END OF DEPRECATED LEGACY METHODS
    // ============================================
    // The 350+ line executeRagQueryWithAsr method body has been removed.
    // All its functionality is now in RagQueryManager.runFullRagPipelineFromAsrResult()
    // which calls back to Fragment via onRequestCallLlm() for the actual LLM call.
    // ============================================

    private boolean foregroundInferenceSessionActive = false;

    private void enterInferenceForegroundSession(String description) {
        try {
            if (foregroundInferenceSessionActive) {
                return;
            }
            if (!isAdded() || getActivity() == null) {
                return;
            }
            if (!(getActivity() instanceof MainActivity)) {
                return;
            }
            MainActivity activity = (MainActivity) getActivity();
            UnifiedForegroundService service = activity.getUnifiedForegroundService();
            if (service != null) {
                String desc = (description != null && !description.isEmpty()) ? description : "Inference";
                service.startTask(UnifiedForegroundService.TaskType.INFERENCE, desc);
                LogManager.logD(TAG, "[FG] Started INFERENCE foreground session: " + desc);
                foregroundInferenceSessionActive = true;
            } else {
                LogManager.logW(TAG, "[FG] UnifiedForegroundService not available when starting inference session");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[FG] Failed to start INFERENCE foreground session: " + e.getMessage(), e);
        }
    }

    private void leaveInferenceForegroundSession() {
        try {
            if (!foregroundInferenceSessionActive) {
                return;
            }
            if (!isAdded() || getActivity() == null) {
                foregroundInferenceSessionActive = false;
                return;
            }
            if (!(getActivity() instanceof MainActivity)) {
                foregroundInferenceSessionActive = false;
                return;
            }
            MainActivity activity = (MainActivity) getActivity();
            UnifiedForegroundService service = activity.getUnifiedForegroundService();
            if (service != null && service.getCurrentTaskType() == UnifiedForegroundService.TaskType.INFERENCE) {
                service.endTask();
                LogManager.logD(TAG, "[FG] Ended INFERENCE foreground session");
            } else if (service != null) {
                LogManager.logD(TAG, "[FG] Skip ending foreground session, currentTaskType=" + service.getCurrentTaskType());
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[FG] Failed to end INFERENCE foreground session: " + e.getMessage(), e);
        } finally {
            foregroundInferenceSessionActive = false;
        }
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

        // CRITICAL: Sync current mode with config to ensure correct mode after app returns from background
        // This is needed when Agent service puts app to background and user returns after task completion
        boolean configAgentMode = ConfigManager.getBoolean(requireContext(), ConfigManager.KEY_AGENT_MODE_ENABLED, false);
        if (currentMode == PageMode.RAG && configAgentMode) {
            LogManager.logI(TAG, "[MODE_SYNC] Config shows Agent mode but UI is RAG, switching to Agent mode");
            switchMode(PageMode.AGENT);
        } else if (currentMode == PageMode.AGENT && !configAgentMode) {
            LogManager.logI(TAG, "[MODE_SYNC] Config shows RAG mode but UI is Agent, switching to RAG mode");
            switchMode(PageMode.RAG);
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

        if (checkBoxGraphRagMode != null) {
            isUpdatingUiFromConfig = true;
            boolean graphRagEnabled = ConfigManager.isGraphRagEnabled(getContext());
            checkBoxGraphRagMode.setChecked(graphRagEnabled);
            isUpdatingUiFromConfig = false;
        }
        
        // Apply restored debug response text (if any) before replaying new logs
        applyRestoredResponseTextFromStateIfNeeded();
        
        // Lightweight UI state resync based on active background tasks for current chat folder
        resyncUiStateWithBackgroundTasks();

        // Start buffer poll loop - this is the SINGLE source of truth for streaming UI updates.
        // Polls buffer every 200ms, handles UI reconstruction automatically.
        startBufferPollLoop();

        // Start periodic UI state resync loop to keep button state in sync (every 2s)
        startUiStateResyncLoop();
        
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

    @Override
    public void onPause() {
        super.onPause();
        stopBufferPollLoop();
        stopUiStateResyncLoop();
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
    
    // ========== UI Helper Methods (Pure UI, no business logic) ==========
    
    /**
     * Update progress message (just logs, buffer writes handled by Manager)
     */
    private void updateProgressOnUiThread(String progress) {
        // Just log for now - buffer writes are handled by Manager
        LogManager.logD(TAG, "[PROGRESS] " + progress);
    }
    
    /**
     * Append text to response view
     */
    private void appendToResponse(String text) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot append response, Fragment not attached to Activity");
            return;
        }
        mainHandler.post(() -> {
            try {
                if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                    return;
                }
                TextView tv = getView().findViewById(R.id.textViewResponse);
                if (tv != null) {
                    tv.append(text);
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "appendToResponse error: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Update result text on UI thread with Markdown rendering
     */
    private void updateResultOnUiThread(String result) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            LogManager.logW(TAG, "Cannot update result, Fragment not attached to Activity");
            return;
        }
        
        mainHandler.post(() -> {
            try {
                if (getActivity() == null || !isAdded() || isDetached() || getView() == null) {
                    LogManager.logW(TAG, "Cannot update result in UI thread, Fragment not attached");
                    return;
                }
                
                TextView textViewResult = getView().findViewById(R.id.textViewResponse);
                if (textViewResult == null) return;
                
                ScrollView scrollView = getView().findViewById(R.id.scrollViewResponse);
                if (scrollView == null) return;
                
                // Render Markdown
                if (markwon != null && result != null) {
                    Spanned rendered = markwon.toMarkdown(result);
                    textViewResult.setText(rendered);
                } else {
                    textViewResult.setText(result != null ? result : "");
                }
                
                // Auto scroll to bottom if user hasn't scrolled away
                if (!userScrolledAway) {
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "updateResultOnUiThread error: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Update LLM task progress (delegates to Manager)
     */
    private void updateLlmTaskProgress(int progress, String message) {
        if (llmTaskId == null) {
            return;
        }
        try {
            ragQueryManager.updateLlmTaskProgress(llmTaskId, progress, message);
        } catch (Exception e) {
            LogManager.logE(TAG, "[TASK] Failed to update LLM background task progress: " + e.getMessage(), e);
        }
    }
    
    /**
     * Finalize LLM task (delegates to Manager)
     */
    private void finalizeLlmTask(BackgroundTask.TaskState state, int progress, String message) {
        if (llmTaskId == null) {
            return;
        }
        try {
            // Do a final poll to read any remaining buffer data (e.g., <performance> block)
            // BEFORE clearing llmTaskId
            doFinalBufferPoll();
            
            ragQueryManager.finalizeLlmTask(llmTaskId, state, progress, message);
        } catch (Exception e) {
            LogManager.logE(TAG, "[TASK] Failed to finalize LLM background task: " + e.getMessage(), e);
        } finally {
            llmTaskId = null;  // Now safe to clear
        }
    }
    
    /**
     * Do a final poll to ensure all buffer data (including <performance>) is read.
     * Called before clearing llmTaskId.
     * NOTE: This may be called from Binder thread, so UI updates must be posted to main thread.
     */
    private void doFinalBufferPoll() {
        if (llmTaskId == null || llmTaskId.isEmpty() || ragQueryManager == null) {
            return;
        }
        // Read any remaining data in buffer
        TaskLogBuffer.ReadResult result = ragQueryManager.readBufferFromPos(llmTaskId, uiBufferReadPos);
        if (result != null && result.hasData()) {
            LogManager.logD(TAG, "[POLL][FINAL] Read " + result.data.length() + " chars, pos " + uiBufferReadPos + " -> " + result.newReadPos);
            uiBufferReadPos = result.newReadPos;
            
            // Split data into lines
            final java.util.List<String> lines = new java.util.ArrayList<>();
            String data = result.data;
            int start = 0;
            for (int i = 0; i < data.length(); i++) {
                if (data.charAt(i) == '\n') {
                    String line = data.substring(start, i + 1);
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                    start = i + 1;
                }
            }
            if (start < data.length()) {
                String line = data.substring(start);
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            
            // Post UI updates to main thread
            if (mainHandler != null && !lines.isEmpty()) {
                mainHandler.post(() -> {
                    if (!isAdded() || getActivity() == null) {
                        return;
                    }
                    ensureAssistantPlaceholderForReplay();
                    for (String line : lines) {
                        performUpdateChatMessage(line);
                    }
                });
            }
        }
    }
    
    /**
     * Handle new chat button click - clears UI and resets state
     */
    private void handleNewChatClick() {
        if (isAgentMode()) {
            String agentFolder = ConfigManager.getAgentChatFolderPath(requireContext());
            clearFolderContents(agentFolder);
            ConfigManager.setAgentChatFolder(requireContext(), agentFolder);
            ConfigManager.setCurrentChatFolder(requireContext(), agentFolder);
            currentChatFolderPath = agentFolder;
            LogManager.logD(TAG, "[CHAT_HISTORY] Cleared Agent fixed chat folder for new conversation");
        } else {
            // Clear current RAG conversation mapping (new send will create new folder)
            ConfigManager.setString(getContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            ConfigManager.setString(getContext(), ConfigManager.KEY_RAG_CHAT_FOLDER, "");
            currentChatFolderPath = "";
            LogManager.logD(TAG, "[CHAT_HISTORY] Cleared RAG current chat folder for new conversation");
        }
        
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
            isSending.set(false);
            if (isTaskRunning) {
                isTaskCancelled = true;
            }
        }
        
        // NOTE: Model memory reset is now handled automatically in child process
        // when a new conversation starts. No need to explicitly call resetModelMemory()
        // from main process as it would only affect the main process singleton (useless).
        LogManager.logD(TAG, "Chat cleared, model memory will be reset on next inference in child process");
        
        // Clear media thumbnails
        if (mediaThumbnailAdapter != null) {
            mediaThumbnailAdapter.clearMedia();
        }
        
        Toast.makeText(requireContext(), "New chat started", Toast.LENGTH_SHORT).show();
    }

    private void clearFolderContents(@NonNull String folderPath) {
        try {
            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) {
                return;
            }
            File[] files = folder.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file == null) {
                    continue;
                }
                if (file.isDirectory()) {
                    clearFolderContents(file.getAbsolutePath());
                }
                boolean deleted = file.delete();
                if (!deleted) {
                    LogManager.logW(TAG, "[CHAT_HISTORY] Failed to delete file: " + file.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[CHAT_HISTORY] Failed to clear folder contents: " + folderPath, e);
        }
    }

    @Nullable
    private String ensureCurrentChatFolderForMode() {
        try {
            if (isAgentMode()) {
                String agentFolder = ConfigManager.getAgentChatFolder(requireContext());
                if (agentFolder == null || agentFolder.trim().isEmpty()) {
                    agentFolder = ConfigManager.getAgentChatFolderPath(requireContext());
                }
                File folder = new File(agentFolder);
                if (!folder.exists() && !folder.mkdirs()) {
                    LogManager.logE(TAG, "Failed to create Agent chat folder: " + agentFolder);
                    return null;
                }
                ConfigManager.setAgentChatFolder(requireContext(), agentFolder);
                ConfigManager.setCurrentChatFolder(requireContext(), agentFolder);
                currentChatFolderPath = agentFolder;
                return agentFolder;
            }

            String ragFolder = ConfigManager.getString(requireContext(), ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            if (ragFolder == null || ragFolder.trim().isEmpty()) {
                ragFolder = ChatHistoryManager.createNewChatFolder(requireContext());
                if (ragFolder == null || ragFolder.trim().isEmpty()) {
                    LogManager.logE(TAG, "Failed to create RAG chat folder");
                    return null;
                }
            } else {
                File folder = new File(ragFolder);
                if (!folder.exists()) {
                    ragFolder = ChatHistoryManager.createNewChatFolder(requireContext());
                    if (ragFolder == null || ragFolder.trim().isEmpty()) {
                        LogManager.logE(TAG, "Failed to recreate RAG chat folder");
                        return null;
                    }
                }
            }

            ConfigManager.setCurrentChatFolder(requireContext(), ragFolder);
            ConfigManager.setString(requireContext(), ConfigManager.KEY_RAG_CHAT_FOLDER, ragFolder);
            currentChatFolderPath = ragFolder;
            return ragFolder;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to ensure chat folder for mode " + currentMode, e);
            return null;
        }
    }
    
    /**
     * Show dialog for manual model name input when /models endpoint is not available
     */
    private void showManualModelInputDialog(String apiUrl, String savedModelName) {
        if (!isAdded() || getContext() == null) return;
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Manual Model Input");
        builder.setMessage("Cannot fetch model list from API.\nPlease enter model name manually:");
        
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("e.g. mimo-v2-flash");
        if (!savedModelName.isEmpty() && !savedModelName.startsWith("[")) {
            input.setText(savedModelName);
        }
        builder.setView(input);
        
        builder.setPositiveButton("OK", (dialog, which) -> {
            String modelName = input.getText().toString().trim();
            if (!modelName.isEmpty()) {
                setupSpinner(spinnerApiModel, new String[]{modelName});
                ConfigManager.setString(requireContext(), ConfigManager.KEY_MODEL_NAME, modelName);
                // Cache api-key auth method for APIs that don't support /models endpoint
                // Most such APIs use api-key header (like MiMo)
                com.example.offlineai.api.AuthMethodCache.cacheMethod(requireContext(), apiUrl, 
                        com.example.offlineai.api.AuthMethodCache.AUTH_API_KEY);
                LogManager.logD(TAG, "Manual model name set: " + modelName + ", cached api-key auth for " + apiUrl);
            } else {
                setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
            }
        });
        
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
        });
        
        builder.show();
    }
    
    /**
     * Show dialog for adding custom model name
     */
    private void showAddCustomModelDialog() {
        if (!isAdded() || getContext() == null) return;
        
        String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
        String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.dialog_title_add_custom_model);
        builder.setMessage(R.string.dialog_message_add_custom_model);
        
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.hint_enter_model_name);
        builder.setView(input);
        
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String modelName = input.getText().toString().trim();
            if (modelName.isEmpty()) {
                Toast.makeText(requireContext(), R.string.toast_model_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Check if model already exists
            List<String> customModels = ConfigManager.getCustomModels(requireContext(), apiUrl);
            if (customModels.contains(modelName)) {
                Toast.makeText(requireContext(), getString(R.string.toast_model_already_exists, modelName), Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Add custom model
            boolean success = ConfigManager.addCustomModel(requireContext(), apiUrl, modelName);
            if (success) {
                Toast.makeText(requireContext(), getString(R.string.toast_model_added_success, modelName), Toast.LENGTH_SHORT).show();
                LogManager.logD(TAG, "Custom model added: " + modelName + " for API: " + apiUrl);
                
                // Refresh model list
                fetchModelsForApi();
                
                // Select the newly added model
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    setSpinnerSelection(spinnerApiModel, modelName);
                }, 100);
            }
        });
        
        builder.setNegativeButton(android.R.string.cancel, null);
        
        builder.show();
    }
    
    /**
     * Try to fetch models with a specific auth method, and try next method if it fails
     */
    private void tryFetchModelsWithAuth(String modelsUrl, String apiUrl, String apiKey, 
            String savedModelName, String authMethod, boolean tryNextOnFail) {
        
        Map<String, String> headers = com.example.offlineai.api.AuthMethodCache.getAuthHeaders(apiKey, authMethod);
        
        LogManager.logD(TAG, "Fetching models from: " + modelsUrl + " with auth method: " + authMethod);
        
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, modelsUrl, null,
                response -> {
                    try {
                        JSONArray data = response.getJSONArray("data");
                        List<String> apiModelsList = new ArrayList<>();
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject model = data.getJSONObject(i);
                            apiModelsList.add(model.getString("id"));
                        }
                        
                        // Success! Cache this auth method
                        com.example.offlineai.api.AuthMethodCache.cacheMethod(requireContext(), apiUrl, authMethod);
                        LogManager.logD(TAG, "Auth method " + authMethod + " succeeded, cached for " + apiUrl);
                        LogManager.logI(TAG, "API returned " + apiModelsList.size() + " models: " + apiModelsList);
                        
                        // Get user-defined custom models for this API
                        List<String> customModels = ConfigManager.getCustomModels(requireContext(), apiUrl);
                        
                        // Merge: custom models first, then API models (avoid duplicates)
                        List<String> mergedModels = new ArrayList<>(customModels);
                        for (String apiModel : apiModelsList) {
                            if (!mergedModels.contains(apiModel)) {
                                mergedModels.add(apiModel);
                            }
                        }
                        
                        // Add "➕ 新建模型" option at the end
                        mergedModels.add(getString(R.string.common_add_custom_model));
                        
                        if (mergedModels.size() == 1) {
                            // Only "➕ 新建模型" option, show no available models
                            setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_NO_AVAILABLE)});
                        } else {
                            setupSpinner(spinnerApiModel, mergedModels.toArray(new String[0]));
                            if (!savedModelName.isEmpty()) {
                                setSpinnerSelection(spinnerApiModel, savedModelName);
                            }
                        }
                    } catch (JSONException e) {
                        LogManager.logE(TAG, "Failed to parse model list response", e);
                        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
                    }
                },
                error -> {
                    // Log detailed error information
                    String errorMsg = "Auth method " + authMethod + " failed for " + modelsUrl;
                    if (error.networkResponse != null) {
                        errorMsg += ", HTTP " + error.networkResponse.statusCode;
                        if (error.networkResponse.data != null) {
                            try {
                                String responseBody = new String(error.networkResponse.data, "UTF-8");
                                errorMsg += ", Response: " + responseBody;
                            } catch (Exception e) {
                                errorMsg += ", Response parse error: " + e.getMessage();
                            }
                        }
                    } else {
                        errorMsg += ", Error: " + error.getMessage();
                    }
                    LogManager.logW(TAG, errorMsg);
                    
                    if (tryNextOnFail) {
                        // Try next auth method
                        int currentIndex = com.example.offlineai.api.AuthMethodCache.ALL_AUTH_METHODS.indexOf(authMethod);
                        if (currentIndex >= 0 && currentIndex < com.example.offlineai.api.AuthMethodCache.ALL_AUTH_METHODS.size() - 1) {
                            String nextMethod = com.example.offlineai.api.AuthMethodCache.ALL_AUTH_METHODS.get(currentIndex + 1);
                            LogManager.logD(TAG, "Trying next auth method: " + nextMethod);
                            tryFetchModelsWithAuth(modelsUrl, apiUrl, apiKey, savedModelName, nextMethod, true);
                            return;
                        }
                    }
                    
                    // All methods failed - use saved model name or custom models, don't show dialog
                    LogManager.logE(TAG, "All auth methods failed for " + apiUrl);
                    
                    // Get user-defined custom models for this API
                    List<String> customModels = ConfigManager.getCustomModels(requireContext(), apiUrl);
                    
                    // If we have a saved model name and it's not a status text, add it to the list
                    if (!savedModelName.isEmpty() && !savedModelName.startsWith("[")) {
                        if (!customModels.contains(savedModelName)) {
                            customModels.add(0, savedModelName);  // Add saved model at the beginning
                        }
                    }
                    
                    // Add "➕ 新建模型" option at the end
                    customModels.add(getString(R.string.common_add_custom_model));
                    
                    if (customModels.size() == 1) {
                        // Only "➕ 新建模型" option, show fetch failed status
                        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_FETCH_FAILED)});
                        LogManager.logW(TAG, "No saved model or custom models for " + apiUrl);
                    } else {
                        // Show saved model and custom models
                        setupSpinner(spinnerApiModel, customModels.toArray(new String[0]));
                        if (!savedModelName.isEmpty()) {
                            setSpinnerSelection(spinnerApiModel, savedModelName);
                        }
                        LogManager.logI(TAG, "Using saved/custom models for " + apiUrl + ": " + customModels.size() + " models");
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                return headers;
            }
        };
        
        Volley.newRequestQueue(requireContext()).add(request);
    }
    
    /**
     * Fetch available models for current API selection
     */
    private void fetchModelsForApi() {
        if (!isUiReadyForModeOps()) {
            LogManager.logW(TAG, "[LIFECYCLE] fetchModelsForApi skipped because UI is not ready");
            return;
        }
        String apiUrlDisplay = spinnerApiUrl.getSelectedItem().toString();
        String apiUrl = StateDisplayManager.getApiUrlFromDisplayText(requireContext(), apiUrlDisplay);
        String apiKey = editTextApiKey.getText().toString();
        
        // Get saved model name for restoring selection
        // Priority: 1. Current API's last used model, 2. Global model name
        String savedModelName = ConfigManager.getLastModelForApi(requireContext(), apiUrl);
        if (savedModelName.isEmpty()) {
            savedModelName = ConfigManager.getString(requireContext(), ConfigManager.KEY_MODEL_NAME, "");
            LogManager.logD(TAG, "No last model for API " + apiUrl + ", using global: " + savedModelName);
        } else {
            LogManager.logD(TAG, "Using last model for API " + apiUrl + ": " + savedModelName);
        }
        
        // Show loading state
        setupSpinner(spinnerApiModel, new String[]{StateDisplayManager.getModelStatusDisplayText(requireContext(), AppConstants.MODEL_STATUS_LOADING)});
        
        // If it's a local model, get available model list from local model directory
        if (AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            
            // Check storage permission first (Android 11+ needs MANAGE_EXTERNAL_STORAGE)
            boolean hasStoragePermission;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasStoragePermission = Environment.isExternalStorageManager();
            } else {
                hasStoragePermission = ContextCompat.checkSelfPermission(requireContext(), 
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
            
            if (!hasStoragePermission) {
                LogManager.logW(TAG, "Storage permission not granted, cannot access model directory");
                setupSpinner(spinnerApiModel, new String[]{getString(R.string.toast_need_storage_permission)});
                Toast.makeText(requireContext(), getString(R.string.toast_need_storage_permission), Toast.LENGTH_LONG).show();
                return;
            }
            
            // Get model path from configuration
            String modelPath = ConfigManager.getModelPath(requireContext());
            File modelDir = new File(modelPath);
            
            // Try to create directory if it doesn't exist
            if (!modelDir.exists()) {
                LogManager.logI(TAG, "Model directory does not exist, trying to create: " + modelPath);
                boolean created = modelDir.mkdirs();
                LogManager.logI(TAG, "Create model directory result: " + created);
            }
            
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
        
        // Build request URL for online API
        String modelsUrl = apiUrl;
        if (!modelsUrl.endsWith("/")) {
            modelsUrl += "/";
        }
        modelsUrl += "models";
        
        // Try cached auth method first, or try all methods
        final String finalModelsUrl = modelsUrl;
        final String finalSavedModelName = savedModelName;
        final String finalApiUrl = apiUrl;
        final String finalApiKey = apiKey;
        
        String cachedMethod = com.example.offlineai.api.AuthMethodCache.getCachedMethod(requireContext(), apiUrl);
        if (cachedMethod != null) {
            // Use cached method
            LogManager.logD(TAG, "Using cached auth method: " + cachedMethod + " for " + apiUrl);
            tryFetchModelsWithAuth(finalModelsUrl, finalApiUrl, finalApiKey, finalSavedModelName, cachedMethod, false);
        } else {
            // Try all methods starting with first one
            LogManager.logD(TAG, "No cached auth method, trying all methods for " + apiUrl);
            tryFetchModelsWithAuth(finalModelsUrl, finalApiUrl, finalApiKey, finalSavedModelName, 
                    com.example.offlineai.api.AuthMethodCache.ALL_AUTH_METHODS.get(0), true);
        }
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize image picker launchers - must be done in onCreate before Fragment is attached
        // For Android 13+ (API 33+): Use Photo Picker with multiple selection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia = registerForActivityResult(
                    new ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
                    uris -> {
                        if (uris != null && !uris.isEmpty()) {
                            for (Uri uri : uris) {
                                handleImageSelected(uri);
                            }
                            LogManager.logI(TAG, "Picked " + uris.size() + " images from Photo Picker");
                        } else {
                            LogManager.logI(TAG, "Pick image from selection menu - no image selected");
                        }
                    });
        }
        
        // For Android 11/12: Use OpenMultipleDocuments
        pickDocument = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        for (Uri uri : uris) {
                            handleImageSelected(uri);
                        }
                        LogManager.logI(TAG, "Picked " + uris.size() + " images from Document Picker");
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
     * Get current time for chat UI
     */
    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }
    
    /**
     * Update chat message with streaming text - UI DISPLAY ONLY.
     * 
     * CRITICAL ARCHITECTURE:
     * - Buffer writes are handled EXCLUSIVELY by Manager (emitStreamingChunkFromManager)
     * - Fragment NEVER writes to buffer
     * - This ensures buffer integrity when UI is destroyed/recreated
     * 
     * @param chunk The text chunk to append
     * @param writeToBuffer DEPRECATED - always ignored, kept for API compatibility
     */
    private void updateChatMessage(String chunk, boolean writeToBuffer) {
        // CRITICAL: Fragment does NOT write to buffer!
        // Buffer is written by Manager (emitStreamingChunkFromManager).
        // The writeToBuffer parameter is deprecated and ignored.
        if (writeToBuffer) {
            // Log warning if someone tries to write buffer from Fragment
            LogManager.logW(TAG, "[ARCH] updateChatMessage called with writeToBuffer=true - IGNORED! Buffer writes must go through Manager.");
        }
        
        if (getActivity() == null || !isAdded() || isDetached()) {
            // Fragment not attached, skip UI update
            return;
        }
        
        // Check if already in UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performUpdateChatMessage(chunk);
        } else {
            getActivity().runOnUiThread(() -> performUpdateChatMessage(chunk));
        }
    }
    
    /**
     * Update chat message with streaming text - UI display only, no buffer write.
     */
    private void updateChatMessage(String chunk) {
        updateChatMessage(chunk, false);
    }
    
    private void performUpdateChatMessage(String chunk) {
        try {
            if (chatMessages.isEmpty()) {
                LogManager.logW(TAG, "[UPDATE_MSG][ERROR] No chat messages to update");
                return;
            }
            
            ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
            if (lastMsg.getType() != ChatViewHolders.ASSISTANT) {
                LogManager.logW(TAG, "[UPDATE_MSG][ERROR] Last message is not assistant type, actual type=" + lastMsg.getType());
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

                    // Mark TTS as generating for native Omni TTS so that button stays in TTS state
                    isTtsGenerating.set(true);
                    updateButtonText();

                    // Check if auto-play is enabled
                    boolean autoPlayEnabled = ConfigManager.getTtsAutoPlay(requireContext());

                    if (autoPlayEnabled) {
                        // Auto-play enabled: play first, then compress/update in completion callback
                        LogManager.logI(TAG, "[AUDIO] Auto-play enabled, starting auto-play: " + wavPath);
                        autoPlayAudio(wavPath, lastMsg);
                    } else {
                        // No auto-play: compress immediately and attach audio to UI
                        LogManager.logI(TAG, "[AUDIO] No auto-play, compressing immediately");
                        handleTtsAudioComplete(wavPath);
                    }

                    // Remove marker from text so that it is never persisted to markdown
                    newText = newText.substring(0, startIdx) + newText.substring(endIdx + 1);
                    LogManager.logI(TAG, "[AUDIO] Marker detected: " + wavPath);
                }
            }
            
            String previousDisplayText = lastMsg.getDisplayText();
            if (previousDisplayText == null) {
                previousDisplayText = currentText;
            }

            lastMsg.text = newText;

            // Parse collapsible sections during streaming
            // Even if tags are incomplete, partial parsing is better than no display
            // This ensures debug/thinking/performance sections are visible during streaming
            CollapsibleTextParser.INSTANCE.parseAndPopulate(newText, lastMsg);
            
            lastMsg.setLoading(false);

            String newDisplayText = lastMsg.getDisplayText();
            if (newDisplayText == null) {
                newDisplayText = "";
            }
            
            // Detect paragraph boundaries during streaming.
            // IMPORTANT: \n\n may be split across chunks by polling/splitting logic.
            // Example: previous chunk ends with "\n", current chunk starts with "\n".
            boolean hasDoubleNewlineInChunk = chunk != null && chunk.contains("\n\n");
            boolean hasCrossChunkDoubleNewline = chunk != null
                    && !chunk.isEmpty()
                    && currentText.endsWith("\n")
                    && chunk.charAt(0) == '\n';
            boolean hasClosedLatex = hasClosedLatexFormula(newDisplayText);
            boolean hasUnclosedLatex = hasUnclosedLatexFormula(newDisplayText);
            lastMsg.setHasUnclosedLatex(hasUnclosedLatex);
            if (lastMsg.getStableMarkdownText() == null) {
                lastMsg.setStableMarkdownText(previousDisplayText);
            }
            if (!hasUnclosedLatex) {
                lastMsg.setStableMarkdownText(newDisplayText);
            }

            if ((hasDoubleNewlineInChunk || hasCrossChunkDoubleNewline || hasClosedLatex) && !markdownStreamingActivated) {
                markdownStreamingActivated = true;
                lastMsg.setMarkdownLocked(true);
            }

            boolean shouldFullMarkdownRender = (markdownStreamingActivated
                    || hasDoubleNewlineInChunk
                    || hasCrossChunkDoubleNewline
                    || hasClosedLatex)
                    && !hasUnclosedLatex;

            if (shouldFullMarkdownRender) {
                // Full bind path: notifyItemChanged without payload → markdown.setMarkdown()
                int lastPos = chatMessages.size() - 1;
                if (lastPos >= 0 && chatAdapter != null) {
                    chatAdapter.notifyItemChanged(lastPos);
                }
            } else {
                // Incremental update path; when hasUnclosedLatex=true,
                // ViewHolder keeps previous markdown output stable to avoid flicker.
                chatAdapter.updateRecentItem(lastMsg);
            }
            
            // Smart auto-scroll: only scroll if user is at bottom
            smartScrollToBottom();
            
            // Periodically persist streaming content to markdown so that
            // conversation history is available even if UI is recreated
            maybeSaveChatHistoryForStreaming();
            
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
            
            // Debug trace for last assistant message before saving history
            ChatDataItem lastAssistant = null;
            for (int i = chatMessages.size() - 1; i >= 0; i--) {
                ChatDataItem item = chatMessages.get(i);
                if (item.getType() == ChatViewHolders.ASSISTANT) {
                    lastAssistant = item;
                    break;
                }
            }
            if (lastAssistant != null) {
                int textLen = lastAssistant.getDisplayText() != null
                        ? lastAssistant.getDisplayText().length() : 0;
                LogManager.logI(TAG, "[CHAT_HISTORY_TRACE] Before save - last assistant hasImage="
                        + (lastAssistant.imageUri != null)
                        + ", hasDebug=" + (lastAssistant.getDebugText() != null)
                        + ", hasPerformance=" + (lastAssistant.getPerformanceText() != null)
                        + ", textLen=" + textLen
                        + ", messages=" + chatMessages.size()
                        + ", thread=" + Thread.currentThread().getName());
            } else {
                LogManager.logI(TAG, "[CHAT_HISTORY_TRACE] Before save - no assistant message, messages="
                        + chatMessages.size()
                        + ", thread=" + Thread.currentThread().getName());
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

    // Throttled autosave used during streaming updates.
    // NOTE: For local models, manager/handler handles final persistence via append* methods.
    // This streaming autosave is disabled to avoid conflicts. Fragment only displays.
    // For remote APIs without backend writer, this could be re-enabled conditionally.
    private void maybeSaveChatHistoryForStreaming() {
        // Disabled: manager handles persistence, Fragment only displays
        // If crash recovery is needed, manager should handle it, not UI
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
                // Clear chat messages to avoid showing previous mode's history
                if (!chatMessages.isEmpty()) {
                    chatMessages.clear();
                    if (chatAdapter != null) {
                        chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
                    }
                    LogManager.logI(TAG, "[CHAT_HISTORY] Folder not exist, cleared chat UI");
                }
                return;
            }
            
            // Try to load conversation
            List<ChatDataItem> history = ChatHistoryManager.loadConversation(getContext(), currentFolder);
            LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] history loaded: " + (history != null ? history.size() : "null") + " items");
            
            if (history != null && !history.isEmpty()) {
                chatMessages.clear();
                chatMessages.addAll(history);
                LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatMessages.size() after addAll: " + chatMessages.size());

                if (recyclerViewChat != null) {
                    LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] RecyclerView adapter: " + recyclerViewChat.getAdapter());
                    recyclerViewChat.post(() -> {
                        try {
                            if (chatAdapter != null) {
                                LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] Calling chatAdapter.updateModelNameAndItems() on RecyclerView.post");
                                chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
                                LogManager.logD(TAG, "[CHAT_HISTORY_DEBUG] chatAdapter.getItemCount(): " + chatAdapter.getItemCount());
                            } else {
                                LogManager.logE(TAG, "[CHAT_HISTORY_DEBUG] ❌ chatAdapter is NULL! Cannot update UI!");
                            }

                            // Auto-scroll to bottom after loading history
                            recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
                            LogManager.logD(TAG, "[CHAT_HISTORY] Auto-scrolled to bottom");
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[CHAT_HISTORY] Error updating RecyclerView after history load", e);
                        }
                    });
                } else {
                    LogManager.logE(TAG, "[CHAT_HISTORY_DEBUG] ❌ recyclerViewChat is NULL!");
                }

                LogManager.logI(TAG, "[CHAT_HISTORY] Loaded " + history.size() + " messages from history");
                // Successfully loaded, no toast needed (silent load for better UX)
            } else {
                LogManager.logD(TAG, "[CHAT_HISTORY] No messages in history file, maintaining empty UI");
                // Empty conversation file is valid, clear any previous messages to avoid mode history mixing
                if (!chatMessages.isEmpty()) {
                    chatMessages.clear();
                    if (chatAdapter != null) {
                        chatAdapter.updateModelNameAndItems(getCurrentModelName(), chatMessages);
                    }
                    LogManager.logI(TAG, "[CHAT_HISTORY] Empty history, cleared chat UI");
                }
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
    public Bundle saveState() {
        Bundle state = new Bundle();
        try {
            if (editTextUserPrompt != null) {
                state.putString(STATE_KEY_USER_PROMPT, editTextUserPrompt.getText().toString());
            }

            if (recyclerViewChat != null && recyclerViewChat.getLayoutManager() != null) {
                Parcelable layoutState = recyclerViewChat.getLayoutManager().onSaveInstanceState();
                if (layoutState != null) {
                    state.putParcelable(STATE_KEY_CHAT_SCROLL, layoutState);
                }
            }

            state.putBoolean(STATE_KEY_USER_SCROLLED_AWAY, userScrolledAway);

            if (mediaThumbnailAdapter != null && mediaThumbnailAdapter.getMediaCount() > 0) {
                java.util.ArrayList<String> imageUris = new java.util.ArrayList<>();
                java.util.ArrayList<String> audioUris = new java.util.ArrayList<>();

                java.util.List<MediaThumbnailAdapter.MediaItem> items = mediaThumbnailAdapter.getMediaItems();
                for (MediaThumbnailAdapter.MediaItem item : items) {
                    android.net.Uri originalUri = item.getOriginalUri();
                    if (originalUri == null) {
                        continue;
                    }
                    String uriString = originalUri.toString();
                    if (item instanceof MediaThumbnailAdapter.ImageItem) {
                        imageUris.add(uriString);
                    } else if (item instanceof MediaThumbnailAdapter.AudioItem) {
                        audioUris.add(uriString);
                    }
                }

                if (!imageUris.isEmpty()) {
                    state.putStringArrayList(STATE_KEY_IMAGE_URIS, imageUris);
                }
                if (!audioUris.isEmpty()) {
                    state.putStringArrayList(STATE_KEY_AUDIO_URIS, audioUris);
                }
            }

            TextView responseView = textViewResponse;
            if (responseView == null && getView() != null) {
                responseView = getView().findViewById(R.id.textViewResponse);
            }
            if (responseView != null) {
                CharSequence resp = responseView.getText();
                if (resp != null && resp.length() > 0) {
                    String respText = resp.toString();
                    int maxLen = 20000;
                    if (respText.length() > maxLen) {
                        respText = respText.substring(respText.length() - maxLen);
                    }
                    state.putString(STATE_KEY_RESPONSE_TEXT, respText);
                }
            }
            
            // Save llmTaskId for task restoration after recreation
            // NOTE: lastFgLogIndex is no longer saved - TaskLogBuffer manages cursor internally
            if (llmTaskId != null && !llmTaskId.isEmpty()) {
                state.putString(STATE_KEY_LLM_TASK_ID, llmTaskId);
            }
            
            try {
                String savedPrompt = state.getString(STATE_KEY_USER_PROMPT, "");
                String savedResponse = state.getString(STATE_KEY_RESPONSE_TEXT, null);
                int promptLen = savedPrompt != null ? savedPrompt.length() : 0;
                int responseLen = savedResponse != null ? savedResponse.length() : 0;
                boolean hasImageUris = state.containsKey(STATE_KEY_IMAGE_URIS);
                boolean hasAudioUris = state.containsKey(STATE_KEY_AUDIO_URIS);
                LogManager.logD(TAG, "[UI_TRACE] saveState - prompt.len=" + promptLen +
                        ", response.len=" + responseLen +
                        ", hasImageUris=" + hasImageUris +
                        ", hasAudioUris=" + hasAudioUris +
                        ", llmTaskId=" + llmTaskId);
            } catch (Exception inner) {
                // Swallow inner logging exceptions to avoid impacting state save
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to save RagQaFragment UI state", e);
        }
        return state;
    }

    @Override
    public void restoreState(Bundle state) {
        if (state == null) {
            LogManager.logD(TAG, "[STATE] restoreState called with null Bundle, skip");
            return;
        }

        try {
            if (editTextUserPrompt != null) {
                String prompt = state.getString(STATE_KEY_USER_PROMPT, null);
                if (prompt != null) {
                    editTextUserPrompt.setText(prompt);
                    try {
                        editTextUserPrompt.setSelection(prompt.length());
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to set selection for user prompt", e);
                    }
                }
            }

            if (mediaThumbnailAdapter != null && recyclerViewImageThumbnails != null) {
                java.util.ArrayList<String> imageUris = state.getStringArrayList(STATE_KEY_IMAGE_URIS);
                java.util.ArrayList<String> audioUris = state.getStringArrayList(STATE_KEY_AUDIO_URIS);

                mediaThumbnailAdapter.clearMedia();

                boolean hasMedia = false;

                if (imageUris != null) {
                    for (String uriString : imageUris) {
                        if (uriString == null || uriString.isEmpty()) {
                            continue;
                        }
                        try {
                            android.net.Uri uri = android.net.Uri.parse(uriString);
                            mediaThumbnailAdapter.addImage(uri);
                            hasMedia = true;
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to restore image media item: " + uriString, e);
                        }
                    }
                }

                if (audioUris != null) {
                    for (String uriString : audioUris) {
                        if (uriString == null || uriString.isEmpty()) {
                            continue;
                        }
                        try {
                            android.net.Uri uri = android.net.Uri.parse(uriString);
                            mediaThumbnailAdapter.addAudio(uri);
                            hasMedia = true;
                        } catch (Exception e) {
                            LogManager.logE(TAG, "Failed to restore audio media item: " + uriString, e);
                        }
                    }
                }

                if (hasMedia) {
                    recyclerViewImageThumbnails.setVisibility(View.VISIBLE);
                } else {
                    recyclerViewImageThumbnails.setVisibility(View.GONE);
                }
            }

            userScrolledAway = state.getBoolean(STATE_KEY_USER_SCROLLED_AWAY, false);
            if (recyclerViewChat != null && recyclerViewChat.getLayoutManager() != null) {
                Parcelable layoutState = state.getParcelable(STATE_KEY_CHAT_SCROLL);
                if (layoutState != null) {
                    recyclerViewChat.getLayoutManager().onRestoreInstanceState(layoutState);
                }
            }

            String responseText = state.getString(STATE_KEY_RESPONSE_TEXT, null);
            // Defer applying restored response text until onResume, so it does not
            // override logs replayed from UnifiedForegroundService.
            restoredResponseTextFromState = responseText;
            hasAppliedRestoredResponseFromState = false;
            
            // Restore llmTaskId for task restoration
            // NOTE: No need to reset cursor here. The new design:
            // - Cursor is reset to 0 by RagQueryManager when MD is persisted
            // - New Fragment uses a new consumerId, so cursor starts at 0 automatically
            // - replayInferenceLogsFromUnifiedService() will pop all unpersisted content
            String savedTaskId = state.getString(STATE_KEY_LLM_TASK_ID, null);
            if (savedTaskId != null && !savedTaskId.isEmpty()) {
                llmTaskId = savedTaskId;
                LogManager.logD(TAG, "[FG][RESTORE] Restored llmTaskId=" + llmTaskId);
            }
            
            int responseLen = responseText != null ? responseText.length() : 0;
            LogManager.logD(TAG, "[UI_TRACE] restoreState - restoredResponseTextFromState.len=" + responseLen +
                    ", userScrolledAway=" + userScrolledAway +
                    ", llmTaskId=" + llmTaskId);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to restore RagQaFragment UI state", e);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Release Agent resources
        if (agentManager != null) {
            agentManager.release();
            agentManager = null;
        }
        
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
    String chatFolder = ensureCurrentChatFolderForMode();
    if (chatFolder == null || chatFolder.trim().isEmpty()) {
        LogManager.logE(TAG, "Failed to create chat folder for audio");
        Toast.makeText(requireContext(), "无法创建对话文件夹", Toast.LENGTH_SHORT).show();
        recordingDialog.dismiss();
        return;
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
    String knowledgeBase = isAgentMode() ? "" : spinnerKnowledgeBase.getSelectedItem().toString();
    String systemPrompt = isAgentMode() ? "" : editTextSystemPrompt.getText().toString();
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

    // Create AI message placeholder (same as text send path)
    ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
    aiMsg.setLoading(true);
    chatMessages.add(aiMsg);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    // Immediately scroll to bottom when adding new message (no animation)
    recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
    userScrolledAway = false; // Reset flag for new message

    // Build QueryRequest snapshot for manager (unified with text path)
    int searchDepth = isAgentMode() ? 0 : Integer.parseInt(spinnerSearchDepth.getSelectedItem().toString());
    boolean graphRagEnabled = !isAgentMode() && (checkBoxGraphRagMode != null && checkBoxGraphRagMode.isChecked());
    boolean needsAsr = false;
    String asrModel = null;
    if (userInput.hasAudio()) {
        asrModel = ConfigManager.getString(requireContext(), ConfigManager.KEY_ASR_MODEL, "无");
        if (!"无".equals(asrModel)) {
            needsAsr = true;
        }
    }

    if (ragQueryManager != null) {
        RagQueryManager.QueryRequest request = new RagQueryManager.QueryRequest(
                apiUrl,
                apiKey,
                model,
                knowledgeBase,
                systemPrompt,
                userPrompt,
                userInput.imagePaths,
                userInput.audioPaths,
                userInput.audioDuration,
                searchDepth,
                graphRagEnabled,
                needsAsr,
                asrModel
        );
        if (isAgentMode()) {
            if (agentManager == null) {
                LogManager.logE(TAG, "[AGENT][VOICE] agentManager is null, fallback to normal query");
                ragQueryManager.startQuery(request);
            } else {
                lastUserPrompt = userPrompt;
                initializeSendingState();
                agentManager.startAgentLoop(userPrompt, ragQueryManager);
                LogManager.logI(TAG, "[AGENT][VOICE] Agent loop started, task: " + userPrompt);
            }
        } else {
            LogManager.logI(TAG, "[VOICE] Delegating audio query execution to RagQueryManager.startQuery");
            ragQueryManager.startQuery(request);
        }
    } else {
        // CRITICAL: ragQueryManager must always exist. If null, it's a bug.
        LogManager.logE(TAG, "[VOICE] FATAL: ragQueryManager is null - this is a bug, manager-driven pipeline is required");
        Toast.makeText(requireContext(), "Internal error: query manager not initialized", Toast.LENGTH_SHORT).show();
        resetSendingState();
    }
}

// ============================================
// DEPRECATED: convertAndSendAsTextInternal and sendAudioToModelInternal
// ============================================
// These legacy ASR methods have been removed. All ASR processing is now
// handled by RagQueryManager.runAsrAndContinue() which:
// 1. Runs ASR conversion in the manager executor (UI-free)
// 2. Calls back to Fragment via onStartQueryWithAsrResult()
// 3. Continues to runFullRagPipelineFromAsrResult() for LLM call
// ============================================

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
    String chatFolderPath = ensureCurrentChatFolderForMode();
    if (chatFolderPath == null || chatFolderPath.trim().isEmpty()) {
        LogManager.logE(TAG, "Failed to create chat folder");
        Toast.makeText(requireContext(), "无法创建对话文件夹，请检查存储权限", Toast.LENGTH_SHORT).show();
        return null;
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
                    } else {
                        failedMediaCount++;
                        LogManager.logE(TAG, "Failed to process image");
                    }
                } catch (Exception e) {
                    failedMediaCount++;
                    LogManager.logE(TAG, "Error processing image", e);
                }
            } else if (item instanceof MediaThumbnailAdapter.AudioItem) {
                // Audio already decoded and appended to user_voice.wav in handleAudioFileSelected()
                hasAudioFiles = true;
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
        } else {
            LogManager.logE(TAG, "Cache WAV not found!");
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
    }
    
    // Step 3: Create UserInput structure
    UserInput userInput = new UserInput(textPrompt, imagePaths, audioPaths, audioDuration);
    LogManager.logI(TAG, String.format("User input prepared: text=%d, images=%d, audio=%d", 
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
        // Multi-image support: create one ChatDataItem per image
        // First N-1 images: image only (no text), last image: image + text
        int imageCount = imagePaths.size();
        for (int i = 0; i < imageCount - 1; i++) {
            ChatDataItem imgOnlyMsg = ChatDataItem.Companion.createImageInputData(
                getCurrentTime(),
                null,  // No text for intermediate images
                Uri.parse(imagePaths.get(i))
            );
            chatMessages.add(imgOnlyMsg);
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        }
        // Last image carries the text prompt
        userMsg = ChatDataItem.Companion.createImageInputData(
            getCurrentTime(),
            textPrompt,
            Uri.parse(imagePaths.get(imageCount - 1))
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
    
    // Step 5: Save to conversation.md immediately
    boolean saved = ChatHistoryManager.saveConversation(getContext(), chatMessages, chatFolderPath);
    if (!saved) {
        LogManager.logE(TAG, "Failed to save conversation");
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
        // Prefer manager implementation to keep business logic centralized
        if (ragQueryManager != null) {
            return ragQueryManager.filterTtsContent(chunk);
        }

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
        // CRITICAL: Remove [Audio] prefix markers
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
     * Finalize after all audio compressions complete
     * 1. After inference completes (no TTS)
     * 2. After TTS generation completes (no auto-play)
     * 3. After audio playback completes (with auto-play)
     *
     * Only when all compression tasks are done, update UI and save MD to avoid race conditions
     */
    private void checkCompressionCompleteAndFinalize() {
        // If ASR is still running, do NOT reset state - wait for ASR to complete
        // This prevents premature resetSendingState when user audio compression finishes before ASR
        if (isAsrRunning.get()) {
            LogManager.logD(TAG, "[STATE] ASR still running, skipping compression finalization");
            return;
        }
        
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
        if (pendingAiAudioM4aPath != null) {
            String aiM4aPath = pendingAiAudioM4aPath;
            pendingAiAudioM4aPath = null;

            // Persist Omni TTS audio by attaching an audio markdown line into the
            // last assistant message so that audio is available even if the UI
            // Fragment has been destroyed.
            try {
                Context ctx = getContext() != null ? getContext().getApplicationContext() : null;
                if (ctx != null) {
                    File audioFile = new File(aiM4aPath);
                    if (audioFile.exists()) {
                        String folderPath = audioFile.getParent();
                        if (folderPath != null && !folderPath.isEmpty()) {
                            float durationSeconds = AudioService.getAudioDuration(aiM4aPath);
                            ChatHistoryManager.attachAssistantAudioToLastMessage(
                                    ctx,
                                    folderPath,
                                    aiM4aPath,
                                    durationSeconds
                            );
                            LogManager.logI(TAG, "[TTS][HISTORY] Omni AI audio attached to last assistant message in markdown: " + audioFile.getName());
                        } else {
                            LogManager.logW(TAG, "[TTS][HISTORY] Invalid folderPath for Omni AI audio: " + aiM4aPath);
                        }
                    } else {
                        LogManager.logW(TAG, "[TTS][HISTORY] Omni AI audio file does not exist: " + aiM4aPath);
                    }
                } else {
                    LogManager.logW(TAG, "[TTS][HISTORY] Context is null, skip attaching Omni AI audio to markdown");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS][HISTORY] Failed to attach Omni AI audio to markdown: " + e.getMessage(), e);
            }
        }

        LogManager.logI(TAG, "[TTS] Audio compression complete, UI updated");

        // Reset TTS state (only if TTS was generating - this is for AI audio compression)
        if (isTtsGenerating.get()) {
            isTtsGenerating.set(false);
            updateButtonText();
            LogManager.logI(TAG, "[TTS] TTS state reset, button updated");

            // Only reset sending state after TTS audio compression (not user audio compression)
            // User audio compression finishing should NOT trigger resetSendingState
            if (isSending.get() || isTaskRunning) {
                LogManager.logI(TAG, "[STATE] TTS audio compression done, resetting sending state");
                resetSendingState();
            }
        } else {
            // User audio compression done, but NOT TTS - do NOT reset sending state
            // LLM/ASR may still be running
            LogManager.logD(TAG, "[STATE] User audio compression done, NOT resetting state (no TTS)");
        }
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
            
            // NOTE: For external TTS, TtsAdapter persists audio via appendAssistantAudioMessage.
            // For Omni TTS, markdown persistence is handled in checkCompressionCompleteAndFinalize().
            LogManager.logI(TAG, "[TTS] Audio attached to UI");

        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Error updating chat message with audio", e);
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Release TtsAdapter
        if (ttsAdapter != null && !ttsAdapter.isEnabled()) {
            ttsAdapter.release();
            LogManager.logI(TAG, "[TTS] TtsAdapter released");
        }
        // Unregister inference status listener
        if (inferenceStatusListener != null) {
            try {
                InferenceClient client = InferenceClient.getInstance(requireContext().getApplicationContext());
                client.removeStatusListener(inferenceStatusListener);
                LogManager.logI(TAG, "[STATUS] InferenceClient StatusListener unregistered in RagQaFragment");
            } catch (Exception e) {
                LogManager.logE(TAG, "[STATUS] Failed to unregister InferenceClient StatusListener: " + e.getMessage(), e);
            }
            inferenceStatusListener = null;
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

        // NOTE: Do NOT call ragQueryManager.shutdown() here!
        // RagQueryManager is a singleton and its executor should survive Fragment lifecycle.
        // Calling shutdown() here would terminate the executor, causing RejectedExecutionException
        // when user re-enters the Fragment and tries to submit a new query.
    }
    
    private boolean isDebugSectionOpen() {
        if (chatMessages == null || chatMessages.isEmpty()) {
            return false;
        }
        ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
        if (lastMsg == null || lastMsg.getType() != ChatViewHolders.ASSISTANT) {
            return false;
        }
        String text = lastMsg.text;
        if (text == null || text.isEmpty()) {
            return false;
        }
        int openIdx = text.lastIndexOf("<debug>");
        int closeIdx = text.lastIndexOf("</debug>");
        return openIdx >= 0 && openIdx > closeIdx;
    }
    
    private boolean shouldAppendStatusDebugToUi() {
        if (!isSending.get() && !isTaskRunning) {
            return false;
        }
        return isDebugSectionOpen();
    }
    
    private void handleInferenceModelStateChanged(String component, String modelPath, String state, boolean busy, int threads) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            return;
        }
        
        mainHandler.post(() -> {
            // Handle service disconnect/error to reset UI state
            if ("SERVICE".equalsIgnoreCase(component)) {
                if ("DISCONNECTED".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state)) {
                    updateChatMessage("本地推理服务已中断或出错，已重置发送状态，请稍后重试。\n");
                    resetSendingState();
                }
                return;
            }

            boolean shouldAppend = shouldAppendStatusDebugToUi();

            // Always track READY model path for reuse detection, even if we do not append to UI
            if ("READY".equalsIgnoreCase(state) && modelPath != null && !modelPath.isEmpty()) {
                lastReadyModelPathByComponent.put(component, modelPath);
            }

            if (!shouldAppend) {
                return;
            }

            // Do not show LLM STATUS lines in chat UI, rely on [LLM] logs instead
            if ("LLM".equalsIgnoreCase(component)) {
                return;
            }

            if ("EMBEDDING".equalsIgnoreCase(component) || "RERANKER".equalsIgnoreCase(component)) {
                // Only show RUNNING state for EMBEDDING / RERANKER, without model path or reuse line
                if ("RUNNING".equalsIgnoreCase(state)) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("[STATUS][").append(component).append("] state=").append(state)
                            .append(", busy=").append(busy)
                            .append(", threads=").append(threads)
                            .append("\n");
                    updateChatMessage(builder.toString());
                }
            } else {
                // Generic STATUS output for other components (if any)
                StringBuilder builder = new StringBuilder();
                builder.append("[STATUS][").append(component).append("] state=").append(state)
                        .append(", busy=").append(busy)
                        .append(", threads=").append(threads);
                if (modelPath != null && !modelPath.isEmpty()) {
                    builder.append("\nModel: ").append(modelPath);
                }
                builder.append("\n");
                updateChatMessage(builder.toString());
            }
        });
    }
    
    private void handleInferenceRerankProgress(String taskId, int current, int total) {
        if (getActivity() == null || !isAdded() || isDetached()) {
            return;
        }
        
        mainHandler.post(() -> {
            if (!shouldAppendStatusDebugToUi()) {
                return;
            }
            if (current <= 1) {
                updateChatMessage("[RERANK] Progress ");
            }
            updateChatMessage(".");
            if (total > 0 && current >= total) {
                updateChatMessage("\n");
            }
        });
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
    
    // ==================== Helper Methods ====================
    
    /**
     * Get current selected model name from spinner
     */
    private String getCurrentModelName() {
        if (spinnerApiModel != null && spinnerApiModel.getSelectedItem() != null) {
            return spinnerApiModel.getSelectedItem().toString();
        }
        return "Unknown";
    }
    
    // ==================== Agent Integration Methods ====================
    
    /**
     * Initialize Agent Manager and set up callbacks
     */
    private void initializeAgentManager() {
        try {
            agentManager = AgentManager.getInstance(requireContext());
            LogManager.logI(TAG, "[AGENT] AgentManager initialized");
            
            // Set Agent callback
            agentManager.setCallback(new AgentManager.AgentCallback() {
                @Override
                public void onAgentActionDetected(String actionType) {
                    LogManager.logI(TAG, "[AGENT] Action detected: " + actionType);
                    mainHandler.post(() -> {
                        addSystemMessage("🤖 Agent: " + actionType);
                        updateAgentExecutionState(true, "Executing: " + actionType);
                    });
                }
                
                @Override
                public void onAgentActionCompleted(boolean success, String message) {
                    LogManager.logI(TAG, "[AGENT] Action completed: " + message);
                    mainHandler.post(() -> {
                        addSystemMessage("✓ " + message);
                    });
                }
                
                @Override
                public void onAgentError(String error) {
                    LogManager.logE(TAG, "[AGENT] Error: " + error);
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                        addSystemMessage("❌ Agent error: " + error);
                        updateAgentExecutionState(false, "");
                    });
                }
                
                @Override
                public void onAgentAnswer(String text) {
                    LogManager.logI(TAG, "[AGENT] Final answer: " + text);
                    mainHandler.post(() -> {
                        addAssistantMessage(text);
                        updateAgentExecutionState(false, "");
                        resetSendingState();
                    });
                }
                
                @Override
                public void onRequestAccessibilityPermission() {
                    LogManager.logI(TAG, "[AGENT] Requesting accessibility permission");
                    mainHandler.post(() -> {
                        showAgentPermissionDialog();
                    });
                }
            });
            
            if (checkBoxAgentMode != null) {
                checkBoxAgentMode.setChecked(false);
                checkBoxAgentMode.setVisibility(View.GONE);
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[AGENT] Failed to initialize AgentManager: " + e.getMessage(), e);
        }
    }
    
    /**
     * Show Agent permission dialog to guide user to enable Accessibility Service
     */
    private void showAgentPermissionDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.agent_permission_title)
            .setMessage(R.string.agent_permission_message)
            .setPositiveButton(R.string.agent_permission_confirm, (dialog, which) -> {
                agentManager.openAccessibilitySettings();
            })
            .setNegativeButton(R.string.agent_permission_cancel, null)
            .show();
    }
    
    /**
     * Update Agent execution state and UI
     */
    private void updateAgentExecutionState(boolean executing, String statusText) {
        isAgentExecuting.set(executing);
        
        if (executing) {
            // Update button text
            buttonSendStop.setText(R.string.agent_executing);
            LogManager.logI(TAG, "[AGENT] Execution state: " + statusText);
        } else {
            // Reset to normal state
            if (!isSending.get()) {
                buttonSendStop.setText(R.string.button_send);
            }
        }
    }
    
    /**
     * Add system message to chat (for Agent callbacks)
     */
    private void addSystemMessage(String message) {
        LogManager.logI(TAG, "[AGENT] System message: " + message);
        // Agent messages are shown via floating window, just log here
    }
    
    /**
     * Add assistant message to chat (for Agent callbacks)
     */
    private void addAssistantMessage(String message) {
        LogManager.logI(TAG, "[AGENT] Assistant message: " + message);
        // Agent answer will be shown via floating window, just log here
    }
    
    /**
     * Public API for Agent to add user message to chat UI
     * Agent saves messages directly to conversation.md, but needs to update UI in real-time
     * This method creates a ChatDataItem and adds it to chatMessages, just like prepareAndSaveUserInput does
     * If Fragment is destroyed, it will fail silently (user will see message on next app launch)
     */
    public void addAgentUserMessageToChat(String text, String imagePath) {
        try {
            if (chatMessages == null || chatAdapter == null) {
                LogManager.logW(TAG, "[AGENT_API] Fragment not ready, skip UI update");
                return;
            }
            
            // Create user message like prepareAndSaveUserInput does
            ChatDataItem userMsg = new ChatDataItem(getCurrentTime(), ChatViewHolders.USER, text);
            
            // Set image if provided
            if (imagePath != null && !imagePath.isEmpty()) {
                File imageFile = new File(imagePath);
                if (imageFile.exists()) {
                    userMsg.imageUri = Uri.fromFile(imageFile);
                    LogManager.logD(TAG, "[AGENT_API] Image attached: " + imagePath);
                }
            }
            
            // Add to chatMessages list
            chatMessages.add(userMsg);
            LogManager.logI(TAG, "[AGENT_API] Added Agent user message to chatMessages, total: " + chatMessages.size());
            
            // Notify adapter on main thread (same as prepareAndSaveUserInput)
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                        // Scroll to bottom to show new message
                        if (recyclerViewChat != null && !chatMessages.isEmpty()) {
                            recyclerViewChat.scrollToPosition(chatMessages.size() - 1);
                        }
                        LogManager.logI(TAG, "[AGENT_API] UI updated successfully");
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[AGENT_API] Failed to update UI: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[AGENT_API] Failed to add Agent message: " + e.getMessage());
            // Fail silently - user will see message when they reopen app
        }
    }
    
}  // End of RagQaFragment class



