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
import com.example.offlineai.RerankerHandler;
import com.example.offlineai.AppConstants;
import com.example.offlineai.StateDisplayManager;
import com.example.offlineai.StatefulFragment;
import com.example.offlineai.adapter.StateAwareSpinnerAdapter;
import com.example.offlineai.MediaThumbnailAdapter;
import com.example.offlineai.EmbeddingHandler;
import com.example.offlineai.HanLpNerHandler;
import com.example.offlineai.GraphStopwordsMatcher;
// Removed: import com.example.offlineai.SQLiteVectorDatabaseHandler; - Now using KnowledgeGraphDatabase directly
import com.example.offlineai.ConfigManager;
import com.example.offlineai.TaskLogBuffer;
import com.example.offlineai.AudioService;
import com.example.offlineai.ChatHistoryFilter;
import com.example.offlineai.ChatHistoryManager;
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

public class RagQueryManager {
    // Core RAG/LLM manager entry point (UI-free logic will be moved here step by step).

    private static final String TAG = "OfflineAI_RagManager";

    private final Context appContext;
    private RagQueryCallback callback;
    // RAG result state managed by manager (UI-free, used by Fragment via delegation)
    private final Object ragResultLock = new Object();
    private List<String> ragRelevantDocuments = new ArrayList<>();
    private String ragSimilarityInfo = "";
    // Track the currently active LLM background task id for manager-driven pipelines
    @Nullable
    private String currentLlmTaskId;
    
    // Accumulate full response including debug info for persistence to markdown
    // This is reset at the start of each query and saved on completion
    private final StringBuilder fullResponseAccumulator = new StringBuilder();

    // Dedicated RAG query executor owned by the manager (UI-free).
    // This ensures that the core ASR/RAG/LLM pipeline does not depend on
    // Fragment-owned executors and can keep running even if the UI is destroyed.
    private final ExecutorService ragQueryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQueryManager-RagQuery-Thread");
        t.setDaemon(true);
        return t;
    });

    // Graph RAG NER handler state for manager-side processing (UI-free).
    private HanLpNerHandler graphRagNerHandler;
    private String graphRagDictPath;
    // Graph RAG limits for seed entities on manager side to avoid explosion
    private static final int GRAPH_RAG_MAX_SEED_ENTITIES_MANAGER = 32;

    // BM25 index cache: kbName -> Bm25Index
    // Invalidated when chunk count changes (mirrors Python KnowledgeBase._bm25_cache)
    private final Map<String, Bm25Index> bm25Cache = new HashMap<>();
    private final Object bm25CacheLock = new Object();

    // Dedicated single-thread executor for BM25 index pre-warm / scoring.
    // Runs concurrently with embedding (IPC call) to hide index-build latency.
    // Separate from ragQueryExecutor to avoid deadlock (pipeline runs on ragQueryExecutor).
    private final ExecutorService bm25Executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RagQueryManager-BM25-Thread");
        t.setDaemon(true);
        return t;
    });
    
    // TtsAdapter reference - Manager holds this to survive UI destruction
    // Set via setTtsAdapter() when Fragment creates/updates TtsAdapter
    @Nullable
    private TtsAdapter ttsAdapter;
    
    // ========== STOP FLAGS (Manager-owned, survives UI destruction) ==========
    // Global stop flag - used to uniformly control the stopping of all models
    // This is the SINGLE SOURCE OF TRUTH for stop state, not Fragment!
    private volatile boolean globalStopFlag = false;
    // User requested stop - set when user explicitly clicks stop button
    private volatile boolean userRequestedStop = false;
    // Task cancelled flag - set when current task is cancelled
    private volatile boolean taskCancelled = false;
    // Skip conversation.md persistence for current query (used by Agent experience summary step)
    private volatile boolean skipConversationPersist = false;

    public void setSkipConversationPersist(boolean skip) {
        this.skipConversationPersist = skip;
    }

    public boolean isSkipConversationPersist() {
        return skipConversationPersist;
    }

    // ========== SINGLETON PATTERN ==========
    // Manager is a singleton to survive Fragment destruction and recreation.
    // This ensures ongoing tasks continue even when UI is destroyed.
    private static volatile RagQueryManager sInstance;
    private static final Object sLock = new Object();

    /**
     * Get the singleton instance of RagQueryManager.
     * Creates the instance if it doesn't exist.
     * @param context Application context (will be converted to app context internally)
     * @return The singleton instance
     */
    @NonNull
    public static RagQueryManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new RagQueryManager(context.getApplicationContext());
                    LogManager.logI(TAG, "[MGR] Singleton instance created");
                }
            }
        }
        return sInstance;
    }

    /**
     * Private constructor for singleton pattern.
     * Use getInstance() to get the instance.
     */
    private RagQueryManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.callback = null; // Callback will be set via updateCallback()
        LogManager.logI(TAG, "[MGR] RagQueryManager initialized with app context");
    }

    public interface RagQueryCallback {
        void onSendingStateChanged(boolean sending);

        void onTtsStateChanged(boolean generating);

        void onProgressUpdate(int progress, String message);

        void onQueryComplete(boolean success, String errorMessage);

        void onLlmCompleteWithAudio(String audioPath);

        void onRequestReloadChatHistory();

        void onRequestUpdateButtonText();

        // REMOVED: getTtsAdapter() - Manager now holds TtsAdapter reference directly
        // TtsAdapter getTtsAdapter();

        void onResetStopFlagsForNewQuery();

        // DEPRECATED: onStartQueryRequested - no longer used, Manager handles full pipeline
        // void onStartQueryRequested(@NonNull QueryRequest request);

        // DEPRECATED: onStartQueryWithAsrResult - no longer used, Manager handles full pipeline
        // void onStartQueryWithAsrResult(...);

        /**
         * Notify UI that a query has started with the given task ID.
         * Fragment should save this taskId for UI state tracking and log replay.
         * This is a pure notification - Fragment does NOT participate in flow control.
         */
        void onQueryStarted(@NonNull String taskId);

        // Request to start an inference foreground session with a human-readable description.
        void onRequestStartInferenceForeground(@NonNull String description);

        // Request to end the current inference foreground session, if any.
        void onRequestEndInferenceForeground();
        
        // Notify UI that ASR transcription has started/completed
        // Used to prevent premature resetSendingState when user audio compression finishes before ASR
        void onAsrStateChanged(boolean isRunning);

        // REMOVED: onRequestCallLlm - LLM calls are now handled directly by RagQueryManager.runLlmPipeline()
        
        // Notify UI that Agent action has been detected in model output
        void onAgentActionDetected(String fullResponse);
    }

    public void updateCallback(@NonNull RagQueryCallback callback) {
        this.callback = callback;
    }

    /**
     * Set TtsAdapter reference - Manager holds this to survive UI destruction.
     * Called by Fragment when TtsAdapter is created or updated.
     */
    public void setTtsAdapter(@Nullable TtsAdapter adapter) {
        this.ttsAdapter = adapter;
        LogManager.logD(TAG, "[MGR][TTS] TtsAdapter set: " + (adapter != null ? "available" : "null"));
    }

    /**
     * Get TtsAdapter reference held by Manager.
     */
    @Nullable
    public TtsAdapter getTtsAdapter() {
        return this.ttsAdapter;
    }

    /**
     * Start a new RAG/LLM query using a parameterized request.
     * This is the public entry point; internally it is routed to
     * {@link #runQueryPipeline(QueryRequest)} so that the manager can
     * gradually take over the full ASR+RAG+LLM pipeline orchestration.
     */
    public void startQuery(@NonNull QueryRequest request) {
        runQueryPipeline(request);
    }

    /**
     * Unified manager-side query pipeline entry.
     * <p>
     * Currently this method still delegates the heavy business logic to
     * the Fragment via {@link RagQueryCallback#onStartQueryRequested(QueryRequest)}
     * but adds a centralized stop check and logging on the manager side.
     * Subsequent refactors will move the full ASR+RAG+LLM pipeline into
     * this method using the existing manager-side helpers
     * (runRagRetrievalPipeline / runGraphRagPipeline / runRerankerPipeline /
     * buildPromptWithKnowledgeBase / buildPromptWithoutKnowledgeBase /
     * runLlmPipeline).
     */
    public void runQueryPipeline(@NonNull QueryRequest request) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR] runQueryPipeline called but callback is null, ignoring request");
            return;
        }
        LogManager.logD(TAG, "[MGR] runQueryPipeline preparing to start query pipeline");
        // Reset manager-side LLM task id for new query pipeline
        currentLlmTaskId = null;
        // Reset response accumulator for new query
        fullResponseAccumulator.setLength(0);
        resetLocalLlmStopFlagIfNeeded(request);
        
        // Reset Manager-owned stop flags (SINGLE SOURCE OF TRUTH)
        resetStopFlagsForNewQuery();
        // Also notify Fragment to reset its UI-related flags (for backward compatibility)
        if (cb != null) {
            cb.onResetStopFlagsForNewQuery();
        }
        
        // Initialize TTS if external/system TTS is enabled (NOT "无" and NOT "原生(Omni)")
        // "原生(Omni)" is handled by LocalLLMMNNHandler internally, not TtsAdapter
        initializeTtsIfEnabled();
        
        // Start long-running inference foreground session via callback so that
        // the manager becomes the single place that kicks off INFERENCE tasks.
        if (cb != null) {
            cb.onRequestStartInferenceForeground("RAG / LLM inference");
        }
        
        // CRITICAL: Create LLM task HERE in Manager, not in Fragment callback!
        // This ensures task lifecycle is managed by Manager, independent of UI.
        try {
            java.util.Map<String, String> extras = new java.util.HashMap<>();
            extras.put("apiUrl", request.apiUrl);
            extras.put("model", request.model != null ? request.model : "");
            extras.put("knowledgeBase", request.knowledgeBase != null ? request.knowledgeBase : "");
            extras.put("hasImages", (request.imagePaths != null && !request.imagePaths.isEmpty()) ? "true" : "false");
            extras.put("hasAudio", (request.audioPaths != null && !request.audioPaths.isEmpty()) ? "true" : "false");
            String chatFolderPath = ConfigManager.getString(appContext, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            extras.put("chatFolder", chatFolderPath != null ? chatFolderPath : "");
            
            String taskId = createLlmTask(extras);
            if (taskId != null && !taskId.isEmpty()) {
                LogManager.logI(TAG, "[MGR][TASK] Created LLM background task in Manager, id=" + taskId);
                // Notify UI about the task ID (pure notification, no flow control)
                RagQueryCallback cbInner = this.callback;
                if (cbInner != null) {
                    cbInner.onQueryStarted(taskId);
                }
            } else {
                LogManager.logW(TAG, "[MGR][TASK] createLlmTask returned null/empty");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][TASK] Failed to create LLM background task: " + e.getMessage(), e);
        }
        
        // Submit the query pipeline to the dedicated manager-owned executor so that
        // it is not tied to Fragment lifecycle or UI-owned executors.
        ragQueryExecutor.submit(() -> {
            LogManager.logI(TAG, "[MGR] Query pipeline task ENTERED - thread=" + Thread.currentThread().getName());
            // Audio-aware branch: manager owns ASR + audio fallback pipeline.
            boolean hasAudio = request.audioPaths != null && !request.audioPaths.isEmpty();
            if (hasAudio) {
                boolean hasAsr = request.needsAsr
                        && request.asrModel != null
                        && !request.asrModel.trim().isEmpty()
                        && !"无".equals(request.asrModel);
                if (hasAsr) {
                    LogManager.logI(TAG, "[MGR][ASR] Audio detected with ASR enabled, running ASR pipeline first");
                    runAsrAndContinue(request);
                } else {
                    LogManager.logI(TAG, "[MGR][ASR] Audio detected but ASR disabled, using audio tag mode");
                    String asrInfo = "[ASR] Disabled, sending audio tag to model\n";
                    // Manager directly runs pipeline, no callback to Fragment
                    runFullRagPipelineFromAsrResult(request, request.userPrompt, asrInfo, false);
                }
            } else {
                // Manager directly runs pipeline, no callback to Fragment
                runFullRagPipelineFromAsrResult(request, request.userPrompt, null, false);
            }
        });
    }

    /**
     * Manager-side ASR pipeline for audio queries.
     * This method runs ASR conversion and then directly continues to the RAG/LLM pipeline.
     * All processing happens in Manager - no callback to Fragment.
     */
    private void runAsrAndContinue(@NonNull QueryRequest request) {
        String asrModel = request.asrModel;
        if (asrModel == null || asrModel.trim().isEmpty() || "无".equals(asrModel)) {
            LogManager.logW(TAG, "[MGR][ASR] No valid ASR model configured, falling back to audio tag mode");
            runFullRagPipelineFromAsrResult(request, request.userPrompt,
                    "[ASR] Disabled, sending audio tag to model\n", false);
            return;
        }

        // Notify UI that ASR has started (prevents premature resetSendingState)
        RagQueryCallback cb = this.callback;
        if (cb != null) {
            cb.onAsrStateChanged(true);
        }

        try {
            LogManager.logI(TAG, "[MGR][ASR] Starting ASR conversion task");

            // Prepare ASR info (image count for debug section)
            String imageInfo = (request.imagePaths != null && !request.imagePaths.isEmpty())
                    ? request.imagePaths.size() + " image(s)" : "none";

            java.io.File cacheWav = AudioService.getCacheWavFile(appContext);
            String audioPath = cacheWav.getAbsolutePath();
            String audioFileName = cacheWav.getName();

            final String asrInfo = String.format("[ASR] Model: %s; Audio: %s; Image: %s\n",
                    asrModel, audioFileName, imageInfo);

            com.example.offlineai.RuntimeConfigUtil.pushToInference(appContext);
            InferenceClient client = InferenceClient.getInstance(appContext);
            LogManager.logI(TAG, "[MGR][ASR][IPC] Using inference process for ASR model: " + asrModel);
            LogManager.logI(TAG, "[MGR][ASR] Transcribing audio: " + audioPath);

            String convertedText = client.runAsr(asrModel, audioPath);

            String normalizedTextPrompt = (request.userPrompt == null) ? "" : request.userPrompt.trim();

            // Empty ASR result and no user text: fallback to audio tag mode
            if ((convertedText == null || convertedText.isEmpty()) && normalizedTextPrompt.isEmpty()) {
                LogManager.logW(TAG, "[MGR][ASR] ASR returned empty text, fallback to audio tag mode");
                StringBuilder sb = new StringBuilder();
                sb.append(asrInfo);
                sb.append("[ASR] \u26a0\ufe0f No speech recognized, using audio tag mode\n");
                runFullRagPipelineFromAsrResult(request, request.userPrompt, sb.toString(), false);
                return;
            }

            // Wrap ASR converted text with [Audio] marker
            String wrappedAsrText = String.format("[Audio]\"%s\"", convertedText != null ? convertedText : "");

            // Merge with user text
            String finalText = wrappedAsrText;
            if (!normalizedTextPrompt.isEmpty()) {
                finalText = wrappedAsrText + "\n" + normalizedTextPrompt;
            }

            LogManager.logI(TAG, "[MGR][ASR] Conversion successful, wrapped text length: " + finalText.length());

            String asrResult = String.format("[ASR] Converted text: \"%s\"%s\n",
                    convertedText != null ? convertedText : "",
                    normalizedTextPrompt.isEmpty() ? "" : (" + user text: \"" + normalizedTextPrompt + "\""));

            String finalAsrInfo = asrInfo + asrResult;

            // Notify UI that ASR has completed (before continuing to LLM pipeline)
            if (cb != null) {
                cb.onAsrStateChanged(false);
            }

            // Manager directly runs pipeline, no callback to Fragment
            runFullRagPipelineFromAsrResult(request, finalText, finalAsrInfo, true);

        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][ASR] Conversion failed", e);
            
            // Notify UI that ASR has completed (even on failure)
            if (cb != null) {
                cb.onAsrStateChanged(false);
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("[ASR] Model: ");
            sb.append(asrModel != null ? asrModel : "");
            sb.append("\n");
            sb.append("[ASR] \u274c Conversion failed: ");
            sb.append(e.getMessage());
            sb.append("\n");
            sb.append("[ASR] Fallback to audio tag mode\n");
            runFullRagPipelineFromAsrResult(request, request.userPrompt, sb.toString(), false);
        }
    }

    /**
     * Request to stop the ongoing query.
     */
    public void requestStop() {
        try {
            RagQaFragment.userRequestedStop = true;
            InferenceClient client = InferenceClient.getInstance(appContext);
            client.requestStopWithTimeout(5000L);
            emitProgressFromManager("Operation stopped by user");
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][STOP] Failed to request stop: " + e.getMessage(), e);
        }
    }

    // Manager-side LLM pipeline with separate prompts (NEW) - avoids incorrect \n\n splitting
    public void runLlmPipelineWithSeparatePrompts(@NonNull String apiUrl,
                                                   @NonNull String apiKey,
                                                   @NonNull String model,
                                                   @NonNull String systemPrompt,
                                                   @NonNull String userPrompt,
                                                   @Nullable java.util.List<String> imagePaths,
                                                   @Nullable java.util.List<String> audioPaths,
                                                   @Nullable String taskId) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][LLM] runLlmPipelineWithSeparatePrompts called but callback is null");
            return;
        }

        // Unified stop check before calling LLM
        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][LLM] runLlmPipelineWithSeparatePrompts aborted because stop was requested");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        boolean isLocalModel = AppConstants.ApiUrl.LOCAL.equals(apiUrl);

        // Emit basic debug info via unified method (writes buffer + notifies UI)
        // For local model: debug info will be emitted by LocalLlmAdapter with Loading/Reusing status
        // NOTE: Do NOT pre-close <debug> here. The [TEXT:] marker emitted by
        // StreamingApiClient (before first content token) will trigger the close.
        // This keeps reasoning_content (thinking) tokens INSIDE <debug>...</debug>
        // so they can be stripped by the Agent parser.
        if (!isLocalModel) {
            String debugInfo = "[LLM] Using online API: " + model + "\n";
            emitStreamingChunkFromManager(debugInfo);
        }

        int systemLen = (systemPrompt != null) ? systemPrompt.length() : 0;
        int userLen = (userPrompt != null) ? userPrompt.length() : 0;
        LogManager.logI(TAG, "[MGR][LLM] Starting LLM API: " + apiUrl);
        LogManager.logI(TAG, "[MGR][LLM] Using model: " + model);
        LogManager.logI(TAG, "[MGR][LLM] Prompt length: system=" + systemLen + ", user=" + userLen);
        
        // Debug: Log image paths
        if (imagePaths != null && !imagePaths.isEmpty()) {
            LogManager.logI(TAG, "[MGR][DEBUG_IMAGE] Image count: " + imagePaths.size());
            for (String imgPath : imagePaths) {
                LogManager.logI(TAG, "[MGR][DEBUG_IMAGE] Image path: " + imgPath);
            }
        } else {
            LogManager.logW(TAG, "[MGR][DEBUG_IMAGE] No images provided!");
        }

        if (taskId != null && !taskId.isEmpty()) {
            updateLlmTaskProgress(taskId, 40, "LLM API call in progress");
        } else {
            emitProgressFromManager("LLM API call in progress");
        }

        final long startTime = System.currentTimeMillis();

        try {
            com.example.offlineai.api.LlmApiAdapter.ApiCallback llmCallback = new com.example.offlineai.api.LlmApiAdapter.ApiCallback() {
                // Same callback implementation as runLlmPipeline
                @Override
                public void onSuccess(String response) {
                    LogManager.logI(TAG, "[MGR][CALL][LLM] onSuccess enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
                    LogManager.logD(TAG, "[MGR][LLM] API call successful, duration: " + (System.currentTimeMillis() - startTime) + "ms");
                    LogManager.logD(TAG, "[MGR][LLM] Response length: " + (response == null ? 0 : response.length()) + " characters");

                    // CRITICAL: Persist full response (with debug) to chat history
                    // Skip during experience summary step to avoid polluting conversation.md
                    if (skipConversationPersist) {
                        LogManager.logI(TAG, "[MGR][HISTORY] Skipping conversation.md persist (skipConversationPersist=true)");
                    } else {
                        try {
                            String chatFolderPath = ConfigManager.getString(appContext, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                            if (!chatFolderPath.isEmpty()) {
                                String fullResponse = fullResponseAccumulator.toString();
                                String persistedResponse = fullResponse.replaceAll("\\[AUDIO:[^\\]]*\\]", "");
                                LogManager.logI(TAG, "[MGR][HISTORY] Saving response with debug, len=" + persistedResponse.length());

                                java.util.regex.Pattern imagePattern = java.util.regex.Pattern.compile("\\[IMAGE:([^\\]]+)\\]");
                                java.util.regex.Matcher imageMatcher = imagePattern.matcher(fullResponse);
                                
                                if (imageMatcher.find()) {
                                    String imagePath = imageMatcher.group(1);
                                    LogManager.logI(TAG, "[MGR][HISTORY] Detected Diffusion image: " + imagePath);
                                    
                                    String perfText = null;
                                    java.util.regex.Pattern perfPattern = java.util.regex.Pattern.compile("<performance>([\\s\\S]*?)</performance>");
                                    java.util.regex.Matcher perfMatcher = perfPattern.matcher(fullResponse);
                                    if (perfMatcher.find()) {
                                        perfText = "<performance>" + perfMatcher.group(1) + "</performance>";
                                    }
                                    
                                    String debugText = null;
                                    java.util.regex.Pattern debugPattern = java.util.regex.Pattern.compile("<debug>([\\s\\S]*?)</debug>");
                                    java.util.regex.Matcher debugMatcher = debugPattern.matcher(fullResponse);
                                    if (debugMatcher.find()) {
                                        debugText = "<debug>" + debugMatcher.group(1) + "</debug>";
                                    }
                                    
                                    ChatHistoryManager.appendAssistantImageMessage(appContext, chatFolderPath, imagePath, perfText, debugText);
                                } else {
                                    ChatHistoryManager.appendAssistantTextMessage(appContext, chatFolderPath, persistedResponse);
                                }
                                
                                // CRITICAL: Do NOT call markPersisted() here!
                                // Polling may still be reading data from buffer. If we mark persisted now,
                                // polling will stop reading and UI will miss the model's response.
                                // Instead, markPersisted() will be called by resetSendingState() after polling completes.
                                // if (taskId != null && !taskId.isEmpty()) {
                                //     resetLogConsumerCursorAfterPersist(taskId);
                                // }
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[MGR][HISTORY] Failed to save response: " + e.getMessage(), e);
                        }
                    }

                    if (taskId != null && !taskId.isEmpty()) {
                        finalizeLlmTask(taskId, BackgroundTask.TaskState.COMPLETED, 100, "Inference completed");
                    }
                    notifyQueryComplete(true, null);
                }

                // debugClosed starts false for BOTH local and online models.
                // StreamingApiClient always emits [TEXT:] before the first content token,
                // which triggers </debug> close. This ensures thinking (reasoning_content)
                // tokens emitted BEFORE [TEXT:] stay inside <debug>...</debug>.
                private boolean debugClosed = false;
                private final boolean[] streamingProgressReported = {false};

                @Override
                public void onStreamingData(String chunk) {
                    if (chunk == null) return;
                    
                    if (chunk.contains("<debug>") || chunk.contains("</debug>") || chunk.contains("[TEXT:]") || chunk.contains("[IMAGE:]") || chunk.contains("[AUDIO:]")) {
                        String preview = chunk.length() > 120 ? chunk.substring(0, 120) + "..." : chunk;
                        LogManager.logD(TAG, "[MGR][STREAM] isLocal=" + isLocalModel + ", debugClosed=" + debugClosed + ", preview=" + preview);
                    }

                    if (shouldStop()) {
                        LogManager.logD(TAG, "[MGR][LLM] Stop requested, ignoring streaming data");
                        return;
                    }

                    if (!streamingProgressReported[0]) {
                        streamingProgressReported[0] = true;
                        if (taskId != null && !taskId.isEmpty()) {
                            updateLlmTaskProgress(taskId, 60, "LLM streaming started");
                        } else {
                            emitProgressFromManager("LLM streaming started");
                        }
                    }

                    if (!debugClosed && (chunk.contains("[TEXT:]") || chunk.contains("[IMAGE:]") || chunk.contains("[AUDIO:]"))) {
                        LogManager.logD(TAG, "[MGR][DEBUG_TRACE] Detected head marker, closing debug section");
                        String closeDebug = "</debug>\n";
                        emitStreamingChunkFromManager(closeDebug);
                        debugClosed = true;
                    }

                    String filteredChunk = chunk.replace("[TEXT:]", "");

                    TtsAdapter tts = RagQueryManager.this.ttsAdapter;
                    if (tts != null && tts.isEnabled() && debugClosed) {
                        String ttsChunk = filterTtsContent(filteredChunk);
                        if (!ttsChunk.trim().isEmpty()) {
                            tts.processToken(ttsChunk);
                        }
                    }

                    emitStreamingChunkFromManager(filteredChunk);
                }

                @Override
                public void onError(String errorMessage) {
                    LogManager.logE(TAG, "[MGR][LLM] API call failed: " + errorMessage);
                    emitProgressFromManager("API call failed: " + errorMessage);
                    if (taskId != null && !taskId.isEmpty()) {
                        finalizeLlmTask(taskId, BackgroundTask.TaskState.FAILED, 0, "API call failed");
                    }
                    notifyQueryComplete(false, errorMessage);
                }
            };

            com.example.offlineai.api.LlmApiAdapter adapter = new com.example.offlineai.api.LlmApiAdapter(appContext);
            if (taskId != null && !taskId.isEmpty()) {
                adapter.setTaskId(taskId);
            }
            
            // Call new method signature with separate prompts
            adapter.callLlmApi(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths, llmCallback);

        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][LLM] Exception in runLlmPipelineWithSeparatePrompts: " + e.getMessage(), e);
            emitProgressFromManager("Error: " + e.getMessage());
            if (taskId != null && !taskId.isEmpty()) {
                finalizeLlmTask(taskId, BackgroundTask.TaskState.FAILED, 0, "Exception occurred");
            }
            notifyQueryComplete(false, e.getMessage());
        }
    }

    // Manager-side LLM pipeline: UI-free business logic, driven by RagQueryCallback.
    public void runLlmPipeline(@NonNull String apiUrl,
                               @NonNull String apiKey,
                               @NonNull String model,
                               @NonNull String prompt,
                               @Nullable java.util.List<String> imagePaths,
                               @Nullable java.util.List<String> audioPaths,
                               @Nullable String taskId) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][LLM] runLlmPipeline called but callback is null");
            return;
        }

        // Unified stop check before calling LLM
        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][LLM] runLlmPipeline aborted because stop was requested");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        boolean isLocalModel = AppConstants.ApiUrl.LOCAL.equals(apiUrl);

        // Emit basic debug info via unified method (writes buffer + notifies UI)
        // For local model: debug info will be emitted by LocalLlmAdapter with Loading/Reusing status
        // NOTE: Do NOT pre-close <debug> here. The [TEXT:] marker emitted by
        // StreamingApiClient (before first content token) will trigger the close.
        // This keeps reasoning_content (thinking) tokens INSIDE <debug>...</debug>
        // so they can be stripped by the Agent parser.
        if (!isLocalModel) {
            String debugInfo = "[LLM] Using online API: " + model + "\n";
            emitStreamingChunkFromManager(debugInfo);
        }

        LogManager.logD(TAG, "[MGR][LLM] Starting LLM API: " + apiUrl);
        LogManager.logD(TAG, "[MGR][LLM] Using model: " + model);
        LogManager.logD(TAG, "[MGR][LLM] Prompt length: " + prompt.length() + " characters");

        if (taskId != null && !taskId.isEmpty()) {
            updateLlmTaskProgress(taskId, 40, "LLM API call in progress");
        } else {
            emitProgressFromManager("LLM API call in progress");
        }

        final long startTime = System.currentTimeMillis();

        try {
            com.example.offlineai.api.LlmApiAdapter.ApiCallback llmCallback = new com.example.offlineai.api.LlmApiAdapter.ApiCallback() {
                // In the onSuccess method, manager just reports completion via callback.
                @Override
                public void onSuccess(String response) {
                    LogManager.logI(TAG, "[MGR][CALL][LLM] onSuccess enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
                    LogManager.logD(TAG, "[MGR][LLM] API call successful, duration: " + (System.currentTimeMillis() - startTime) + "ms");
                    LogManager.logD(TAG, "[MGR][LLM] Response length: " + (response == null ? 0 : response.length()) + " characters");

                    // CRITICAL: Persist full response (with debug) to chat history
                    // This runs in main process and uses class-level accumulator
                    // Unified architecture: Both LLM and Diffusion models persist MD here
                    try {
                        String chatFolderPath = ConfigManager.getString(appContext, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
                        if (!chatFolderPath.isEmpty()) {
                            String fullResponse = fullResponseAccumulator.toString();
                            // Strip internal TTS audio markers before persisting to markdown history
                            String persistedResponse = fullResponse.replaceAll("\\[AUDIO:[^\\]]*\\]", "");
                            LogManager.logI(TAG, "[MGR][HISTORY] Saving response with debug, len=" + persistedResponse.length());

                            // Check if this is a Diffusion image response
                            // Diffusion outputs contain [IMAGE:path] marker
                            java.util.regex.Pattern imagePattern = java.util.regex.Pattern.compile("\\[IMAGE:([^\\]]+)\\]");
                            java.util.regex.Matcher imageMatcher = imagePattern.matcher(fullResponse);
                            
                            if (imageMatcher.find()) {
                                // Diffusion model: use appendAssistantImageMessage
                                String imagePath = imageMatcher.group(1);
                                LogManager.logI(TAG, "[MGR][HISTORY] Detected Diffusion image: " + imagePath);
                                
                                // Extract performance block if present
                                String perfText = null;
                                java.util.regex.Pattern perfPattern = java.util.regex.Pattern.compile("<performance>([\\s\\S]*?)</performance>");
                                java.util.regex.Matcher perfMatcher = perfPattern.matcher(fullResponse);
                                if (perfMatcher.find()) {
                                    perfText = "<performance>" + perfMatcher.group(1) + "</performance>";
                                }
                                
                                // Extract debug block if present
                                String debugText = null;
                                java.util.regex.Pattern debugPattern = java.util.regex.Pattern.compile("<debug>([\\s\\S]*?)</debug>");
                                java.util.regex.Matcher debugMatcher = debugPattern.matcher(fullResponse);
                                if (debugMatcher.find()) {
                                    debugText = "<debug>" + debugMatcher.group(1) + "</debug>";
                                }
                                
                                ChatHistoryManager.appendAssistantImageMessage(appContext, chatFolderPath, imagePath, perfText, debugText);
                            } else {
                                // LLM model: use appendAssistantTextMessage
                                ChatHistoryManager.appendAssistantTextMessage(appContext, chatFolderPath, persistedResponse);
                            }
                            
                            // CRITICAL: Reset consumer cursor after MD persist.
                            // This is the core of UI/logic separation design:
                            // - Buffer holds unpersisted content
                            // - After MD persist, buffer content is "old", reset cursor to 0
                            // - UI can always replay from 0 to get unpersisted content
                            // - If UI is killed, it will reload MD + replay buffer seamlessly
                            
                            // CRITICAL: Do NOT call markPersisted() here!
                            // Polling may still be reading data from buffer. If we mark persisted now,
                            // polling will stop reading and UI will miss the model's response.
                            // Instead, markPersisted() will be called by resetSendingState() after polling completes.
                            // if (taskId != null && !taskId.isEmpty()) {
                            //     resetLogConsumerCursorAfterPersist(taskId);
                            // }
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[MGR][HISTORY] Failed to save response: " + e.getMessage(), e);
                    }

                    // Manager does not touch UI directly; rely on callback + TTS flow.
                    if (taskId != null && !taskId.isEmpty()) {
                        finalizeLlmTask(taskId, BackgroundTask.TaskState.COMPLETED, 100, "Inference completed");
                    }
                    notifyQueryComplete(true, null);
                }

                // debugClosed starts false for BOTH local and online models.
                // StreamingApiClient always emits [TEXT:] before the first content token,
                // which triggers </debug> close. This ensures thinking (reasoning_content)
                // tokens emitted BEFORE [TEXT:] stay inside <debug>...</debug>.
                private boolean debugClosed = false;
                private final boolean[] streamingProgressReported = {false};

                @Override
                public void onStreamingData(String chunk) {
                    if (chunk == null) return;
                    
                    // Diagnostic logging for debug-related markers
                    if (chunk.contains("<debug>") ||
                        chunk.contains("</debug>") ||
                        chunk.contains("[TEXT:]") ||
                        chunk.contains("[IMAGE:]") ||
                        chunk.contains("[AUDIO:]") ) {
                        String preview = chunk;
                        if (preview.length() > 120) {
                            preview = preview.substring(0, 120) + "...";
                        }
                        LogManager.logD(TAG, "[MGR][STREAM] isLocal=" + isLocalModel +
                                ", debugClosed=" + debugClosed + ", preview=" + preview);
                    }

                    if (shouldStop()) {
                        LogManager.logD(TAG, "[MGR][LLM] Stop requested, ignoring streaming data");
                        return;
                    }

                    if (!streamingProgressReported[0]) {
                        streamingProgressReported[0] = true;
                        if (taskId != null && !taskId.isEmpty()) {
                            updateLlmTaskProgress(taskId, 60, "LLM streaming started");
                        } else {
                            emitProgressFromManager("LLM streaming started");
                        }
                    }

                    // CRITICAL: Close debug section BEFORE accumulating chunk
                    // This ensures [TEXT:]/[IMAGE:]/[AUDIO:] markers don't appear inside debug block
                    if (!debugClosed && (chunk.contains("[TEXT:]") ||
                                          chunk.contains("[IMAGE:]") ||
                                          chunk.contains("[AUDIO:]"))) {
                        LogManager.logD(TAG, "[MGR][DEBUG_TRACE] Detected head marker, closing debug section");
                        String closeDebug = "</debug>\n";
                        emitStreamingChunkFromManager(closeDebug);
                        debugClosed = true;
                    }

                    // Strip [TEXT:] marker from streaming text; keep [IMAGE:] so UI can detect image markers
                    String filteredChunk = chunk.replace("[TEXT:]", "");

                    // Feed token to TtsAdapter when debug section is closed
                    // Use Manager-held TtsAdapter reference (survives UI destruction)
                    TtsAdapter tts = RagQueryManager.this.ttsAdapter;
                    if (tts != null && tts.isEnabled() && debugClosed) {
                        String ttsChunk = filterTtsContent(filteredChunk);
                        if (!ttsChunk.trim().isEmpty()) {
                            tts.processToken(ttsChunk);
                        }
                    }

                    // Send filtered chunk to UI via unified method (writes buffer + notifies UI)
                    emitStreamingChunkFromManager(filteredChunk);
                }

                @Override
                public void onError(String errorMessage) {
                    LogManager.logI(TAG, "[MGR][CALL][LLM] onError enter - thread=" + Thread.currentThread().getName() + ", ts=" + System.currentTimeMillis());
                    LogManager.logE(TAG, "[MGR][LLM] API call failed, duration: " + (System.currentTimeMillis() - startTime) + "ms, error: " + errorMessage);

                    if (taskId != null && !taskId.isEmpty()) {
                        finalizeLlmTask(taskId, BackgroundTask.TaskState.FAILED, 0, "API call failed: " + errorMessage);
                    }
                    emitProgressFromManager("API call failed: " + errorMessage);
                    notifyQueryComplete(false, errorMessage);
                }
            };

            com.example.offlineai.api.LlmApiAdapter apiAdapter = new com.example.offlineai.api.LlmApiAdapter(appContext);
            if (taskId != null && !taskId.isEmpty()) {
                apiAdapter.setTaskId(taskId);
            }
            apiAdapter.callLlmApi(apiUrl, apiKey, model, prompt, imagePaths, audioPaths, llmCallback);
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][LLM] Failed to call LLM API", e);
            if (taskId != null && !taskId.isEmpty()) {
                finalizeLlmTask(taskId, BackgroundTask.TaskState.FAILED, 0, "API call failed: " + e.getMessage());
            }
            emitProgressFromManager("API call failed: " + e.getMessage());
            notifyQueryComplete(false, e.getMessage());
        }
    }

    public void resetLocalLlmStopFlagIfNeeded(@NonNull QueryRequest request) {
        try {
            if (!AppConstants.ApiUrl.LOCAL.equals(request.apiUrl)) {
                return;
            }
            // Reset stop flag in child process via IPC
            com.example.offlineai.ipc.InferenceClient.getInstance(appContext).resetStopFlag();
            LogManager.logD(TAG, "[MGR][LLM] Reset local LLM stop flag via IPC before new query");
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][LLM] Error resetting local LLM stop flag", e);
        }
    }

    public void runDirectLlmWithoutKnowledgeBase(@NonNull String apiUrl,
                                                 @NonNull String apiKey,
                                                 @NonNull String model,
                                                 @NonNull String systemPrompt,
                                                 @NonNull String userPrompt,
                                                 @Nullable java.util.List<String> imagePaths,
                                                 @Nullable java.util.List<String> audioPaths) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][RAG] runDirectLlmWithoutKnowledgeBase called but callback is null");
            return;
        }

        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][RAG] Stop requested before building prompt (no KB)");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        // Only attach online history for HTTP APIs. Local MNN models use LocalLLMMNNHandler
        // and its own history pipeline.
        String effectiveUserPrompt = userPrompt;
        if (!AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            effectiveUserPrompt = buildUserPromptWithOnlineHistory(systemPrompt, userPrompt);
        }

        // CRITICAL: Do NOT combine system and user prompts with \n\n!
        // Pass them separately to avoid incorrect splitting in StreamingApiClient.
        int systemLen = (systemPrompt != null) ? systemPrompt.length() : 0;
        int userLen = (effectiveUserPrompt != null) ? effectiveUserPrompt.length() : 0;
        int totalLength = systemLen + userLen;

        String promptInfo = "Prompt length: system=" + systemLen + ", user=" + userLen + ", total=" + totalLength + " characters";
        LogManager.logD(TAG, "[MGR][RAG] " + promptInfo);
        emitProgressFromManager(promptInfo);

        LogManager.logD(TAG, "[MGR][RAG] Calling LLM API directly without knowledge base");
        // Use currentLlmTaskId (if any) so that LLM progress/finalization is
        // reflected in BackgroundTaskManager via manager-side helpers.
        String taskId = currentLlmTaskId;
        runLlmPipelineWithSeparatePrompts(apiUrl, apiKey, model, systemPrompt, effectiveUserPrompt, imagePaths, audioPaths, taskId);
    }

    public void runRagLlmWithKnowledgeBase(@NonNull String apiUrl,
                                           @NonNull String apiKey,
                                           @NonNull String model,
                                           @NonNull String systemPrompt,
                                           @NonNull String userPrompt,
                                           long queryTimeMs,
                                           @Nullable java.util.List<String> imagePaths,
                                           @Nullable java.util.List<String> audioPaths) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][RAG] runRagLlmWithKnowledgeBase called but callback is null");
            return;
        }

        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][RAG] Stop requested before building KB prompt");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        java.util.List<String> docs = getRelevantDocumentsSnapshot();
        if (docs == null) {
            docs = new java.util.ArrayList<>();
        }

        if (docs.isEmpty()) {
            String warnMsg = "Warning: Knowledge base query returned no relevant documents";
            LogManager.logW(TAG, "[MGR][RAG] " + warnMsg);
            emitProgressFromManager(warnMsg);

            String timeMsg = "Knowledge base query duration: " + queryTimeMs + "ms";
            LogManager.logD(TAG, "[MGR][RAG] " + timeMsg);
            emitProgressFromManager(timeMsg);

            runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, userPrompt, imagePaths, audioPaths);
            return;
        }

        String simInfo = getSimilarityInfoSnapshot();
        if (simInfo != null && !simInfo.isEmpty()) {
            emitProgressFromManager("Similarity info: " + simInfo);
        }

        emitProgressFromManager("Found " + docs.size() + " relevant content items...");

        // Only attach online history for HTTP APIs. Local MNN models use LocalLLMMNNHandler
        // and its own history pipeline.
        String effectiveUserPrompt = userPrompt;
        if (!AppConstants.ApiUrl.LOCAL.equals(apiUrl)) {
            effectiveUserPrompt = buildUserPromptWithOnlineHistory(systemPrompt, userPrompt);
        }

        String fullPrompt = buildPromptWithKnowledgeBase(systemPrompt, effectiveUserPrompt, docs);
        int promptLength = (fullPrompt != null) ? fullPrompt.length() : 0;

        String promptInfo = "Built prompt length: " + promptLength + " characters";
        LogManager.logD(TAG, "[MGR][RAG] " + promptInfo);
        emitProgressFromManager(promptInfo);

        String timeMsg = appContext.getString(R.string.kb_query_time, queryTimeMs);
        LogManager.logD(TAG, "[MGR][RAG] " + timeMsg);
        emitProgressFromManager(timeMsg);

        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][RAG] Stop requested before calling LLM (KB)");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        LogManager.logD(TAG, "[MGR][RAG] Calling LLM API with knowledge base context");
        emitProgressFromManager("Calling LLM API...");
        // Use currentLlmTaskId (if any) so that LLM progress/finalization is
        // reflected in BackgroundTaskManager via manager-side helpers.
        String taskId = currentLlmTaskId;
        runLlmPipeline(apiUrl, apiKey, model, fullPrompt, imagePaths, audioPaths, taskId);
    }

    public void runFullRagPipelineFromAsrResult(@NonNull QueryRequest request,
                                                @NonNull String finalUserPrompt,
                                                @Nullable String asrInfo,
                                                boolean skipAudioEmbedding) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][RAG] runFullRagPipelineFromAsrResult called but callback is null");
            return;
        }

        // Start debug section and accumulate for persistence
        // Use unified method to write buffer AND notify UI
        String debugOpen = "<debug>\n";
        emitStreamingChunkFromManager(debugOpen);
        if (asrInfo != null && !asrInfo.isEmpty()) {
            emitStreamingChunkFromManager(asrInfo);
        }

        java.util.List<String> imagePaths = (request.imagePaths != null && !request.imagePaths.isEmpty())
                ? new java.util.ArrayList<>(request.imagePaths)
                : null;

        java.util.List<String> audioPaths = null;
        if (!skipAudioEmbedding && request.audioPaths != null && !request.audioPaths.isEmpty()) {
            try {
                File cacheWav = AudioService.getCacheWavFile(appContext);
                if (cacheWav.exists()) {
                    audioPaths = new java.util.ArrayList<>();
                    audioPaths.add(cacheWav.getAbsolutePath());
                    LogManager.logI(TAG, "[MGR][MULTIMODAL] Using cache WAV for Omni: " + cacheWav.getAbsolutePath());
                } else {
                    LogManager.logW(TAG, "[MGR][MULTIMODAL] Cache WAV not found, skipping audio");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[MGR][MULTIMODAL] Failed to prepare audio paths: " + e.getMessage(), e);
            }
        } else if (skipAudioEmbedding) {
            LogManager.logI(TAG, "[MGR][MULTIMODAL] Skipped audio (ASR already converted to text)");
        }

        resetRagResults();

        String knowledgeBase = request.knowledgeBase;
        int searchDepth = request.searchDepth;
        String systemPrompt = request.systemPrompt;
        String apiUrl = request.apiUrl;
        String apiKey = request.apiKey;
        String model = request.model;

        String valueNone = appContext.getString(R.string.common_none);
        String valueNoAvailableKb = appContext.getString(R.string.value_no_available_kb);

        boolean hasKbConfigured = (knowledgeBase != null
                && !knowledgeBase.trim().isEmpty()
                && !valueNone.equals(knowledgeBase)
                && !valueNoAvailableKb.equals(knowledgeBase));

        if (!hasKbConfigured || searchDepth <= 0) {
            String msg = appContext.getString(R.string.calling_llm_api);
            emitProgressFromManager(msg);
            String taskIdDirect = currentLlmTaskId;
            if (taskIdDirect != null && !taskIdDirect.isEmpty()) {
                updateLlmTaskProgress(taskIdDirect, 30, "Calling LLM API without knowledge base...");
            } else {
                emitProgressFromManager("Calling LLM API without knowledge base...");
            }
            runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
            return;
        }

        long kbStart = System.currentTimeMillis();
        KnowledgeGraphDatabase vectorDb = null;
        try {
            String knowledgeBasePath = ConfigManager.getKnowledgeBasePath(appContext);
            File knowledgeBaseDir = new File(knowledgeBasePath, knowledgeBase);
            String pathInfo = "Knowledge base directory path: " + knowledgeBaseDir.getAbsolutePath();
            LogManager.logD(TAG, "[MGR][RAG] " + pathInfo);
            emitProgressFromManager(pathInfo);

            if (!knowledgeBaseDir.exists()) {
                String errorMsg = "Error: Knowledge base directory does not exist: " + knowledgeBaseDir.getAbsolutePath();
                LogManager.logE(TAG, "[MGR][RAG] " + errorMsg);
                emitProgressFromManager(errorMsg);
                runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
                return;
            }

            File graphDbFile = new File(knowledgeBaseDir, "knowledge_graph.db");
            if (!graphDbFile.exists()) {
                String errorMsg = "Error: SQLite knowledge graph database file does not exist: " + graphDbFile.getAbsolutePath();
                LogManager.logE(TAG, "[MGR][RAG] " + errorMsg);
                emitProgressFromManager(errorMsg);
                runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
                return;
            }

            String dbPath = graphDbFile.getAbsolutePath();
            LogManager.logI(TAG, "[MGR][RAG] Creating SQLite vector database handler, dbPath=" + dbPath);
            vectorDb = new KnowledgeGraphDatabase(appContext, dbPath, knowledgeBase);

            int totalChunks = vectorDb.getChunkCount();
            String dbInfo = "SQLite vector database loaded successfully, containing " + totalChunks + " text chunks";
            LogManager.logD(TAG, "[MGR][RAG] " + dbInfo);
            emitProgressFromManager(dbInfo);

            String embeddingModelRoot = ConfigManager.getEmbeddingModelPath(appContext);
            KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
            String modeldir = (metadata != null ? metadata.getModeldir() : null);
            String embModelName = (metadata != null ? metadata.getEmbeddingModel() : null);
            if (embModelName == null || embModelName.trim().isEmpty()) {
                embModelName = modeldir;
            }

            String foundModelPath = resolveEmbeddingModelPath(embeddingModelRoot, modeldir, embModelName);
            if (foundModelPath == null || foundModelPath.trim().isEmpty()) {
                String errorMsg = "Error: Cannot resolve embedding model path for knowledge base: " + knowledgeBase;
                LogManager.logE(TAG, "[MGR][RAG] " + errorMsg);
                emitProgressFromManager(errorMsg);
                runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
                return;
            }

            File modelFile = new File(foundModelPath);
            if (!modelFile.exists()) {
                String errorMsg = "Error: Embedding model file does not exist: " + foundModelPath;
                LogManager.logE(TAG, "[MGR][RAG] " + errorMsg);
                emitProgressFromManager(errorMsg);
                runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
                return;
            }

            String embeddingDisplayName = (embModelName != null && !embModelName.trim().isEmpty())
                    ? embModelName
                    : modelFile.getName();
            String modelInfo = "Using embedding model: " + embeddingDisplayName + ", path: " + foundModelPath;
            LogManager.logD(TAG, "[MGR][RAG] " + modelInfo);
            emitProgressFromManager("Using embedding model: " + embeddingDisplayName);
            emitStreamingChunkFromManager("[RAG] Loading embedding model: " + embeddingDisplayName + "\n");

            int kbDim = (metadata != null ? metadata.getEmbeddingDimension() : -1);
            if (kbDim > 0) {
                emitStreamingChunkFromManager("[RAG] KB vector dimension: " + kbDim + "\n");
            }

            boolean graphRagEnabled = request.graphRagEnabled;
            int retrievalCount = searchDepth;
            int vectorTopK = retrievalCount;
            if (graphRagEnabled) {
                int expand = ConfigManager.getGraphRagVectorExpand(appContext);
                if (expand < 0) {
                    expand = 0;
                }
                vectorTopK = retrievalCount + expand;
                LogManager.logI(TAG, "[MGR][RAG][Graph] Using vector coarse recall: base=" + retrievalCount + ", expand=" + expand + ", topK=" + vectorTopK);

                HanLpNerHandler handler = getOrCreateManagerGraphRagNerHandler();
                if (handler != null) {
                    int wordCount = handler.getLoadedWordCount();
                    String dictName = (graphRagDictPath != null)
                            ? new java.io.File(graphRagDictPath).getName()
                            : "None";
                    emitStreamingChunkFromManager("[RAG] Graph RAG mode enabled, combining vector and graph results. Dictionary: "
                            + dictName + " (loaded " + wordCount + " words)\n");
                } else {
                    emitStreamingChunkFromManager("[RAG] Graph RAG mode enabled, combining vector and graph results.\n");
                }
            }

            emitStreamingChunkFromManager("[RAG] Knowledge Base: " + knowledgeBase + "; Retrieval count: " + retrievalCount + "\n");

            java.util.List<KnowledgeGraphDatabase.SearchResult> searchResults =
                    runRagRetrievalPipeline(foundModelPath, finalUserPrompt, vectorDb, vectorTopK, knowledgeBase);
            if (searchResults == null) {
                searchResults = new java.util.ArrayList<>();
            }

            if (graphRagEnabled && !searchResults.isEmpty()) {
                runGraphRagPipeline(finalUserPrompt, searchResults, vectorDb, retrievalCount);
            } else if (!searchResults.isEmpty()) {
                RerankerConfig rerankConfig = resolveRerankerConfig(vectorDb);
                if (rerankConfig.enabled && rerankConfig.modelPath != null && !rerankConfig.modelPath.trim().isEmpty()) {
                    java.util.List<String> docs = new java.util.ArrayList<>();
                    for (KnowledgeGraphDatabase.SearchResult r : searchResults) {
                        docs.add(r.content);
                    }
                    try {
                        java.util.List<RerankerHandler.RerankResult> reranked =
                                runRerankerPipeline(finalUserPrompt, docs, rerankConfig.modelPath, rerankConfig.rerankCount);
                        if (reranked != null && !reranked.isEmpty()) {
                            int top = Math.min(reranked.size(), retrievalCount);
                            java.util.List<String> relevantDocs = new java.util.ArrayList<>();
                            StringBuilder similarityInfoBuilder = new StringBuilder();
                            for (int i = 0; i < top; i++) {
                                RerankerHandler.RerankResult r = reranked.get(i);
                                relevantDocs.add(r.text);
                                similarityInfoBuilder.append(String.format(Locale.US, "%.4f", r.score));
                                if (i < top - 1) {
                                    similarityInfoBuilder.append(", ");
                                }
                            }
                            String simInfo = "Reranked Results - " + similarityInfoBuilder.toString();
                            updateRagResults(relevantDocs, simInfo);
                        }
                    } catch (InterruptedException ie) {
                        LogManager.logI(TAG, "[RERANK][MGR] Reranker interrupted in full pipeline: " + ie.getMessage());
                        Thread.currentThread().interrupt();
                        emitProgressFromManager("Operation stopped by user");
                        notifyQueryComplete(false, "Operation stopped by user");
                        return;
                    }
                }
            }

            long kbEnd = System.currentTimeMillis();
            long queryTimeMs = kbEnd - kbStart;
            runRagLlmWithKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, queryTimeMs, imagePaths, audioPaths);
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][RAG] Failed to process knowledge base query: " + e.getMessage(), e);
            emitProgressFromManager("Error occurred while querying knowledge base: " + e.getMessage());
            runDirectLlmWithoutKnowledgeBase(apiUrl, apiKey, model, systemPrompt, finalUserPrompt, imagePaths, audioPaths);
        } finally {
            if (vectorDb != null) {
                try {
                    vectorDb.close();
                } catch (Exception e) {
                    LogManager.logE(TAG, "[MGR][RAG] Failed to close vector database: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Unified stop status view for RAG/LLM tasks.
     * This aggregates multiple low-level flags into a single read-only snapshot.
     */
    public static class StopStatus {
        public final boolean userRequestedStop;
        public final boolean taskCancelled;
        public final boolean globalStopFlag;
        public final boolean shouldStop;

        public StopStatus(boolean userRequestedStop, boolean taskCancelled, boolean globalStopFlag) {
            this.userRequestedStop = userRequestedStop;
            this.taskCancelled = taskCancelled;
            this.globalStopFlag = globalStopFlag;
            this.shouldStop = userRequestedStop || taskCancelled || globalStopFlag;
        }
    }

    /**
     * Immutable snapshot of a RAG/LLM query request.
     * This is a pure data carrier used by the manager and Fragment callback.
     */
    public static final class QueryRequest {
        @NonNull public final String apiUrl;
        @NonNull public final String apiKey;
        @NonNull public final String model;
        @NonNull public final String knowledgeBase;
        @NonNull public final String systemPrompt;
        @NonNull public final String userPrompt;
        @NonNull public final java.util.List<String> imagePaths;
        @NonNull public final java.util.List<String> audioPaths;
        public final float audioDuration;
        public final int searchDepth;
        public final boolean graphRagEnabled;
        public final boolean needsAsr;
        @Nullable public final String asrModel;

        public QueryRequest(@NonNull String apiUrl,
                            @NonNull String apiKey,
                            @NonNull String model,
                            @NonNull String knowledgeBase,
                            @NonNull String systemPrompt,
                            @NonNull String userPrompt,
                            @Nullable java.util.List<String> imagePaths,
                            @Nullable java.util.List<String> audioPaths,
                            float audioDuration,
                            int searchDepth,
                            boolean graphRagEnabled,
                            boolean needsAsr,
                            @Nullable String asrModel) {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.knowledgeBase = knowledgeBase;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.imagePaths = (imagePaths != null) ? new java.util.ArrayList<>(imagePaths) : new java.util.ArrayList<>();
            this.audioPaths = (audioPaths != null) ? new java.util.ArrayList<>(audioPaths) : new java.util.ArrayList<>();
            this.audioDuration = audioDuration;
            this.searchDepth = searchDepth;
            this.graphRagEnabled = graphRagEnabled;
            this.needsAsr = needsAsr;
            this.asrModel = asrModel;
        }
    }

    /**
     * Manager-side helper for UI/business callers: build a unified stop status snapshot.
     * Uses Manager-owned stop flags (SINGLE SOURCE OF TRUTH).
     * The parameters are kept for backward compatibility but Manager flags take precedence.
     */
    @NonNull
    public StopStatus getStopStatus(boolean isTaskCancelled, boolean globalStopFlag) {
        // Use Manager-owned flags as SINGLE SOURCE OF TRUTH
        // OR with passed parameters for backward compatibility with legacy code
        boolean userStop = this.userRequestedStop;
        boolean taskCancel = this.taskCancelled || isTaskCancelled;
        boolean globalStop = this.globalStopFlag || globalStopFlag;
        return new StopStatus(userStop, taskCancel, globalStop);
    }

    /**
     * Manager-side helper: whether current task should stop, based on the aggregated flags.
     * Uses Manager-owned stop flags (SINGLE SOURCE OF TRUTH).
     */
    public boolean shouldStop(boolean isTaskCancelled, boolean globalStopFlag) {
        return getStopStatus(isTaskCancelled, globalStopFlag).shouldStop;
    }

    /**
     * Pure manager stop view used by manager-side business pipelines.
     * Uses Manager-owned stop flags (survives UI destruction).
     * This is the SINGLE SOURCE OF TRUTH for stop state!
     */
    public boolean shouldStop() {
        return globalStopFlag || userRequestedStop || taskCancelled;
    }
    
    /**
     * Request stop from Manager side.
     * Called by Fragment's stop button or other stop triggers.
     */
    public void requestStopFromManager() {
        LogManager.logI(TAG, "[MGR][STOP] requestStopFromManager called");
        globalStopFlag = true;
        userRequestedStop = true;
        taskCancelled = true;
        
        // Also request stop on all components
        requestStop();
    }
    
    /**
     * Reset stop flags for new query.
     * Called at the start of each new query pipeline.
     */
    public void resetStopFlagsForNewQuery() {
        if (globalStopFlag || userRequestedStop || taskCancelled) {
            LogManager.logW(TAG, "[MGR][STOP] Resetting stale stop flags before new query");
        }
        globalStopFlag = false;
        userRequestedStop = false;
        taskCancelled = false;
    }
    
    /**
     * Get current stop flag state (for debugging/logging).
     */
    public boolean isGlobalStopFlag() {
        return globalStopFlag;
    }
    
    public boolean isUserRequestedStop() {
        return userRequestedStop;
    }
    
    public boolean isTaskCancelled() {
        return taskCancelled;
    }

    /**
     * Get configured rerank count from ConfigManager using application context.
     */
    public int getConfiguredRerankCount() {
        try {
            return ConfigManager.getRerankCount(appContext);
        } catch (Exception e) {
            LogManager.logE(TAG, "[RERANK][MGR] Failed to read rerank count from config: " + e.getMessage(), e);
            return 0;
        }
    }

    public static class RerankerConfig {
        public final boolean enabled;
        public final int rerankCount;
        @Nullable
        public final String modelPath;

        public RerankerConfig(boolean enabled, int rerankCount, @Nullable String modelPath) {
            this.enabled = enabled;
            this.rerankCount = rerankCount;
            this.modelPath = modelPath;
        }
    }

    @NonNull
    public RerankerConfig resolveRerankerConfig(@NonNull KnowledgeGraphDatabase vectorDb) {
        int rerankCount = getConfiguredRerankCount();
        String modelPath = null;
        try {
            KnowledgeGraphDatabase.DatabaseMetadata metadata = vectorDb.getMetadata();
            if (metadata != null) {
                String rerankerDir = metadata.getRerankerdir();
                modelPath = resolveRerankerModelPath(rerankerDir);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[RERANK][MGR] Failed to resolve reranker model path from database metadata: " + e.getMessage(), e);
        }

        boolean enabled = rerankCount > 0 && modelPath != null && !modelPath.trim().isEmpty();
        if (!enabled) {
            modelPath = null;
        }
        return new RerankerConfig(enabled, rerankCount, modelPath);
    }

    public void resetRagResults() {
        synchronized (ragResultLock) {
            ragRelevantDocuments.clear();
            ragSimilarityInfo = "";
        }
    }

    public void updateRagResults(@NonNull List<String> docs, @Nullable String similarityInfo) {
        synchronized (ragResultLock) {
            ragRelevantDocuments = new ArrayList<>(docs);
            ragSimilarityInfo = (similarityInfo != null ? similarityInfo : "");
        }
        LogManager.logD(TAG, "[RAG] Manager updated rag results, docCount=" + docs.size());
    }

    @NonNull
    public List<String> getRelevantDocumentsSnapshot() {
        synchronized (ragResultLock) {
            return new ArrayList<>(ragRelevantDocuments);
        }
    }

    @Nullable
    public String getSimilarityInfoSnapshot() {
        synchronized (ragResultLock) {
            return ragSimilarityInfo;
        }
    }

    /**
     * Unified method to emit streaming data: writes to buffer AND notifies UI.
     * CRITICAL: Buffer is ONLY written here in Manager, never in Fragment!
     * This ensures buffer integrity even when UI is destroyed.
     * 
     * @param chunk The streaming chunk to emit
     */
    private void emitStreamingChunkFromManager(@Nullable String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        
        // Write to TaskLogBuffer (Manager owns buffer writes)
        String taskId = currentLlmTaskId;
        if (taskId != null && !taskId.isEmpty()) {
            appendInferenceLog(taskId, chunk);
        }
        
        // Accumulate full response for persistence
        fullResponseAccumulator.append(chunk);
        
        // Check if Agent should be triggered (detect complete tool_call)
        String fullResponse = fullResponseAccumulator.toString();
        if (fullResponse.contains("tool_call") && fullResponse.contains("}")) {
            // Notify Fragment to trigger Agent
            if (callback != null) {
                callback.onAgentActionDetected(fullResponse);
            }
        }
        
        // NOTE: UI updates are handled by pollBufferAndUpdateUi() polling mechanism
        // No need to notify UI here - buffer write is sufficient
    }

    /**
     * Business-layer helper: emit progress message.
     * CRITICAL: Write to buffer instead of callback to survive UI destruction.
     * Progress messages are part of debug info and should be persisted.
     */
    private void emitProgressFromManager(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        // Write to buffer (survives UI destruction) instead of callback
        String progressLine = "[PROGRESS] " + message + "\n";
        emitStreamingChunkFromManager(progressLine);
    }

    /**
     * Notify query completion - updates Task state and optionally notifies UI.
     * CRITICAL: This method survives UI destruction because:
     * 1. Task state is stored in BackgroundTaskManager (process-level singleton)
     * 2. UI notification is optional - if callback is null, we just skip it
     * 
     * @param success Whether the query completed successfully
     * @param errorMessage Error message if failed, null if success
     */
    private void notifyQueryComplete(boolean success, @Nullable String errorMessage) {
        // Update task state (survives UI destruction)
        String taskId = currentLlmTaskId;
        if (taskId != null && !taskId.isEmpty()) {
            if (success) {
                finalizeLlmTask(taskId, BackgroundTask.TaskState.COMPLETED, 100, "Query completed");
            } else {
                finalizeLlmTask(taskId, BackgroundTask.TaskState.FAILED, 0, 
                        errorMessage != null ? errorMessage : "Query failed");
            }
        }
        
        // Notify UI if available (optional - UI may be destroyed)
        RagQueryCallback cb = this.callback;
        if (cb != null) {
            cb.onQueryComplete(success, errorMessage);
        } else {
            LogManager.logW(TAG, "[MGR] notifyQueryComplete: callback is null, UI may be destroyed");
        }
    }

    // Expose streaming/progress helpers for Fragment-side callers (e.g. GraphRAG)
    public void emitStreamingFromUi(@Nullable String chunk) {
        emitStreamingChunkFromManager(chunk);
    }

    public void emitProgressFromUi(@Nullable String message) {
        emitProgressFromManager(message);
    }

    public void requestStartInferenceForeground(@NonNull String description) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][FG] requestStartInferenceForeground called but callback is null");
            return;
        }
        cb.onRequestStartInferenceForeground(description);
    }

    public void requestEndInferenceForeground() {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][FG] requestEndInferenceForeground called but callback is null");
            return;
        }
        cb.onRequestEndInferenceForeground();
    }

    /**
     * Request UI to reload chat history from markdown file.
     * Used by Agent to update UI after saving prompts and screenshots.
     */
    public void requestReloadChatHistory() {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logD(TAG, "[MGR] requestReloadChatHistory called but callback is null");
            return;
        }
        LogManager.logD(TAG, "[MGR] Requesting UI to reload chat history");
        cb.onRequestReloadChatHistory();
    }
    

    // Manager-side helper to request LLM call via callback.
    public void callLlm(@NonNull String apiUrl,
                        @NonNull String apiKey,
                        @NonNull String model,
                        @NonNull String prompt,
                        @Nullable java.util.List<String> imagePaths,
                        @Nullable java.util.List<String> audioPaths) {
        RagQueryCallback cb = this.callback;
        if (cb == null) {
            LogManager.logE(TAG, "[MGR][LLM] callLlm requested but callback is null");
            return;
        }

        // Unified stop check on manager side before invoking LLM
        if (shouldStop()) {
            LogManager.logI(TAG, "[MGR][LLM] callLlm aborted because stop was requested");
            emitProgressFromManager("Operation stopped by user");
            notifyQueryComplete(false, "Operation stopped by user");
            return;
        }

        // Delegate to manager-side LLM pipeline (UI-free business logic)
        // Use currentLlmTaskId (if any) so that LLM progress/finalization is
        // reflected in BackgroundTaskManager via manager-side helpers.
        String taskId = currentLlmTaskId;
        runLlmPipeline(apiUrl, apiKey, model, prompt, imagePaths, audioPaths, taskId);
    }

    // Manager-side helper to attach local LLM TTS audio via callback.
    // NOTE: Native TTS audio path is now passed via streaming callback from child process.
    // This method is kept for backward compatibility but the LocalLlmAdapter call is removed
    // since main process singleton doesn't have access to child process state.
    public void handleLocalLlmTtsAudioIfAvailable() {
        // Native TTS audio is handled via streaming callback in child process.
        // The audio path is sent as a special token in the streaming response.
        // No need to query LocalLlmAdapter in main process as it's a separate instance.
        LogManager.logD(TAG, "[MGR][TTS] handleLocalLlmTtsAudioIfAvailable called - Native TTS audio handled via streaming callback");
    }

    // Manager-side helper to create LLM background task in BackgroundTaskManager.
    @Nullable
    public String createLlmTask(@NonNull Map<String, String> extras) {
        try {
            BackgroundTask task = BackgroundTaskManager.getInstance().createTask(
                    BackgroundTask.TaskType.LLM_INFERENCE,
                    "LLM inference",
                    true,
                    extras
            );
            if (task != null) {
                String taskId = task.getId();
                LogManager.logD(TAG, "[MGR][TASK] Created LLM background task, id=" + taskId);
                // Remember the latest LLM task id so that higher level helpers
                // (runDirectLlmWithoutKnowledgeBase / runRagLlmWithKnowledgeBase / callLlm)
                // can pass it into runLlmPipeline.
                currentLlmTaskId = taskId;
                return taskId;
            } else {
                LogManager.logE(TAG, "[MGR][TASK] BackgroundTaskManager.createTask returned null task");
                currentLlmTaskId = null;
                return null;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][TASK] Failed to create LLM background task: " + e.getMessage(), e);
            currentLlmTaskId = null;
            return null;
        }
    }

    // Manager-side helper to update LLM task progress in BackgroundTaskManager.
    public void updateLlmTaskProgress(@NonNull String taskId, int progress, @NonNull String message) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        try {
            BackgroundTaskManager.getInstance().updateTask(
                    taskId,
                    BackgroundTask.TaskState.RUNNING,
                    progress,
                    message
            );
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][TASK] Failed to update LLM background task progress: " + e.getMessage(), e);
        }
    }

    // Manager-side helper to finalize LLM task in BackgroundTaskManager.
    public void finalizeLlmTask(@NonNull String taskId,
                               @NonNull BackgroundTask.TaskState state,
                               int progress,
                               @NonNull String message) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        try {
            BackgroundTaskManager.getInstance().updateTask(
                    taskId,
                    state,
                    progress,
                    message
            );
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][TASK] Failed to finalize LLM background task: " + e.getMessage(), e);
        } finally {
            // Clear cached task id if it matches the finalized one
            if (taskId.equals(currentLlmTaskId)) {
                currentLlmTaskId = null;
            }
        }
    }

    // Manager-side helper to append inference logs to TaskLogBuffer via BackgroundTaskManager.
    public void appendInferenceLog(@NonNull String taskId, @NonNull String content) {
        if (taskId == null || taskId.isEmpty() || content == null || content.isEmpty()) {
            return;
        }
        try {
            BackgroundTaskManager.getInstance().appendLog(taskId, content);
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][BUF_WRITE] Failed to append inference log: " + e.getMessage(), e);
        }
    }

    // Manager-side helper to advance a specific consumer's cursor to end of buffer.
    // NOTE: In new ring buffer design, this is a no-op. UI reads from persistedPos.
    public void advanceLogConsumerToEnd(@NonNull String taskId, @NonNull String consumerId) {
        // No-op in ring buffer design - UI always reads from persistedPos to writePos
    }

    /**
     * Reset a consumer's cursor to the beginning (0) so that replay can fetch all logs.
     * NOTE: In new ring buffer design, this is a no-op. UI always reads from persistedPos.
     *
     * @param taskId     The task ID
     * @param consumerId The consumer ID (e.g., taskId + "_fragment")
     */
    public void resetLogConsumerCursor(@NonNull String taskId, @NonNull String consumerId) {
        // No-op in ring buffer design - UI always reads from persistedPos to writePos
        LogManager.logD(TAG, "[MGR][BUF_CURSOR] resetLogConsumerCursor called (no-op in ring buffer design)");
    }

    /**
     * Reset consumer cursor and clear buffer after MD persist.
     * This is the core of UI/logic separation design:
     * - After MD persist, buffer content is "old" (already saved to file)
     * - Clear buffer and reset cursor to 0
     * - UI can always replay from 0 to get only unpersisted content
     * - If UI is killed during inference, it reloads MD + replays buffer seamlessly
     *
     * @param taskId The task ID
     */
    private void resetLogConsumerCursorAfterPersist(@NonNull String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        try {
            TaskLogBuffer buffer = BackgroundTaskManager.getInstance().getLogBuffer(taskId);
            if (buffer != null) {
                // Mark current position as persisted - UI will only see new data after this point
                // No need to clear buffer - ring buffer naturally handles wrap-around
                buffer.markPersisted();
                LogManager.logD(TAG, "[MGR][PERSIST] Marked buffer as persisted: taskId=" + taskId);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][PERSIST] Failed to mark persisted: " + e.getMessage(), e);
        }
    }
    
    /**
     * Public API: Mark buffer as persisted after UI polling completes.
     * CRITICAL: This should be called by Fragment AFTER polling has read all data,
     * not immediately in onSuccess. This ensures UI gets all streaming data before
     * buffer is marked as persisted.
     *
     * @param taskId The task ID
     */
    public void markBufferAsPersisted(@NonNull String taskId) {
        resetLogConsumerCursorAfterPersist(taskId);
    }

    /**
     * Get the current persisted position for a task's buffer.
     * UI should call this ONCE at start to get initial read position.
     *
     * @param taskId The task ID
     * @return The persisted position, or 0 if buffer not found
     */
    public long getBufferPersistedPos(@NonNull String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return 0;
        }
        try {
            TaskLogBuffer buffer = BackgroundTaskManager.getInstance().getLogBuffer(taskId);
            if (buffer == null) {
                return 0;
            }
            return buffer.getPersistedPos();
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][REPLAY] Failed to get persisted pos: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Read buffer data from a specific position.
     * UI maintains its own readPos and passes it here.
     *
     * @param taskId The task ID
     * @param fromPos The position to read from (UI's current readPos)
     * @return ReadResult with data and new readPos, or null if buffer not found
     */
    @Nullable
    public TaskLogBuffer.ReadResult readBufferFromPos(@NonNull String taskId, long fromPos) {
        if (taskId == null || taskId.isEmpty()) {
            return null;
        }
        try {
            TaskLogBuffer buffer = BackgroundTaskManager.getInstance().getLogBuffer(taskId);
            if (buffer == null) {
                LogManager.logD(TAG, "[MGR][REPLAY] Buffer not found for taskId=" + taskId);
                return null;
            }
            return buffer.getDataFromPos(fromPos);
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][REPLAY] Failed to read buffer: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get new logs for a consumer (for UI polling).
     * Uses the new ring buffer design with persistedPos.
     *
     * @param taskId     The task ID
     * @param consumerId The consumer ID
     * @return List of new logs, or empty list if none
     * @deprecated Use readBufferFromPos() with UI-maintained readPos instead
     */
    @Deprecated
    @NonNull
    public java.util.List<String> getNewLogsForConsumer(@NonNull String taskId, @NonNull String consumerId) {
        java.util.List<String> empty = new java.util.ArrayList<>();
        if (taskId == null || taskId.isEmpty() || consumerId == null || consumerId.isEmpty()) {
            return empty;
        }
        try {
            TaskLogBuffer buffer = BackgroundTaskManager.getInstance().getLogBuffer(taskId);
            if (buffer == null) {
                LogManager.logD(TAG, "[MGR][REPLAY] Buffer not found for taskId=" + taskId);
                return empty;
            }

            // New ring buffer design: get unpersisted data as lines
            long persistedPos = buffer.getPersistedPos();
            long writePos = buffer.getWritePos();
            java.util.List<String> newLogs = buffer.getNewLogs(consumerId);

            if (newLogs != null && !newLogs.isEmpty()) {
                LogManager.logD(TAG, "[MGR][REPLAY] Got " + newLogs.size() + " new logs, " +
                        "persistedPos=" + persistedPos + ", writePos=" + writePos + 
                        ", unpersistedChars=" + (writePos - persistedPos));
                return newLogs;
            } else {
                LogManager.logD(TAG, "[MGR][REPLAY] No new logs to replay, " +
                        "persistedPos=" + persistedPos + ", writePos=" + writePos);
                return empty;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR][REPLAY] Failed to get inference logs: " + e.getMessage(), e);
            return empty;
        }
    }

    /**
     * Replay logs for a given consumer via callback.onStreamingData.
     * This is for UI reconstruction - content is already in buffer, just notify UI.
     * NOTE: This directly calls cb.onStreamingData (NOT emitStreamingChunkFromManager)
     * because we don't want to write to buffer again during replay.
     */
    public void replayInferenceLogsForConsumer(@NonNull String taskId, @NonNull String consumerId) {
        java.util.List<String> newLogs = getNewLogsForConsumer(taskId, consumerId);
        if (newLogs != null && !newLogs.isEmpty()) {
            RagQueryCallback cb = this.callback;
            // NOTE: Replay is no longer needed - UI polling mechanism handles all buffer reads
            // This method is kept for backward compatibility but does nothing
            LogManager.logD(TAG, "[REPLAY] Replay requested but not needed - UI polling handles buffer reads");
        }
    }

    /**
     * Run embedding + vector search pipeline in a UI-free way and return raw search results.
     *
     * This method focuses purely on business logic:
     *  - use the inference process to generate the query embedding;
     *  - call the vector database to execute searchSimilar;
     *  - optionally run BM25+RRF hybrid retrieval (controlled by bm25_enabled config);
     *  - update manager-side RAG result state (documents and similarity info);
     *  - do not close the provided vectorDb (caller owns its lifecycle).
     *
     * Mirrors Python KnowledgeBase.query_kb() Steps 2 + 2b.
     */
    @NonNull
    public List<KnowledgeGraphDatabase.SearchResult> runRagRetrievalPipeline(
            @NonNull String embeddingModelPath,
            @NonNull String userQuery,
            @NonNull KnowledgeGraphDatabase vectorDb,
            int vectorTopK
    ) throws InterruptedException {
        return runRagRetrievalPipeline(embeddingModelPath, userQuery, vectorDb, vectorTopK,
                vectorDb.getCollection());
    }

    /**
     * Overload that accepts explicit kbName for BM25 cache keying.
     * Prefer this when the caller already knows the knowledge base name.
     */
    @NonNull
    public List<KnowledgeGraphDatabase.SearchResult> runRagRetrievalPipeline(
            @NonNull String embeddingModelPath,
            @NonNull String userQuery,
            @NonNull KnowledgeGraphDatabase vectorDb,
            int vectorTopK,
            @NonNull String kbName
    ) throws InterruptedException {
        List<KnowledgeGraphDatabase.SearchResult> empty = new ArrayList<>();
        try {
            LogManager.logI(TAG, "[RAG][MGR] Starting embedding and vector search pipeline");

            // Check stop flag before any heavy work
            if (shouldStop()) {
                LogManager.logI(TAG, "[RAG][MGR] Stop requested before embedding, aborting retrieval");
                emitProgressFromManager("Operation stopped by user");
                return empty;
            }

            com.example.offlineai.RuntimeConfigUtil.pushToInference(appContext);
            InferenceClient inferenceClient = InferenceClient.getInstance(appContext);

            // Submit FULL BM25 retrieval (index build/cache + scoring) to bm25Executor BEFORE
            // computeEmbedding, so the entire BM25 pipeline runs concurrently with the ~1s IPC
            // embedding call and the subsequent vector search.
            // On cache hit: near-instant index lookup + O(corpus) scoring.
            // On first build: DB read + index construction + scoring, all hidden behind embedding.
            // bm25TopK computed here so the lambda captures a local int (no shared mutable state).
            final boolean bm25EnabledEarly = ConfigManager.isBm25Enabled(appContext);
            final int bm25TopKEarly = Math.max((vectorTopK > 0 ? vectorTopK : 1) * 2, 60);
            final Future<Bm25RetrievalResult> bm25Future = bm25EnabledEarly
                    ? bm25Executor.submit(() -> runBm25Retrieval(vectorDb, kbName, userQuery, bm25TopKEarly))
                    : null;

            emitStreamingChunkFromManager("[RAG] Generating query vector...\n");

            float[] queryVector = inferenceClient.computeEmbedding(
                    embeddingModelPath,
                    EmbeddingHandler.MemoryMode.LOW.getValue(),
                    userQuery
            );

            if (queryVector == null) {
                if (bm25Future != null) bm25Future.cancel(false);
                String msg = "Failed to generate query vector: null result";
                LogManager.logW(TAG, "[RAG][MGR] " + msg);
                emitProgressFromManager(msg);
                return empty;
            }

            emitStreamingChunkFromManager("[RAG] Query vector generated, dimension: " + queryVector.length + "\n");

            // Check stop flag after embedding
            if (shouldStop()) {
                if (bm25Future != null) bm25Future.cancel(false);
                LogManager.logI(TAG, "[RAG][MGR] Stop requested after embedding, aborting before search");
                emitProgressFromManager("Operation stopped by user");
                return empty;
            }

            emitStreamingChunkFromManager("[RAG] Searching similar text blocks...\n");

            int topK = vectorTopK > 0 ? vectorTopK : 1;

            // Step 2: Vector search
            List<KnowledgeGraphDatabase.SearchResult> vecResults =
                    vectorDb.searchSimilar(queryVector, topK);

            // Check stop flag after search
            if (shouldStop()) {
                if (bm25Future != null) bm25Future.cancel(false);
                LogManager.logI(TAG, "[RAG][MGR] Stop requested after search, returning empty result");
                emitProgressFromManager("Operation stopped by user");
                return empty;
            }

            // Step 2b: BM25 + RRF fusion (mirrors Python query_kb Step 2b)
            // bm25Future ran concurrently with computeEmbedding + vectorSearch.
            // By this point the full BM25 pipeline (index build/cache + scoring) is done or very close.
            // Main thread only needs: future.get() + rrfFusion.
            List<KnowledgeGraphDatabase.SearchResult> searchResults = vecResults;
            if (bm25EnabledEarly && bm25Future != null && !vecResults.isEmpty()) {
                long bm25Start = System.currentTimeMillis();
                Bm25RetrievalResult bm25Result = null;
                try {
                    bm25Result = bm25Future.get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    LogManager.logW(TAG, "[BM25] Retrieval failed, falling back to vec-only: " + ex.getMessage());
                }

                if (bm25Result != null && !bm25Result.results.isEmpty()) {
                    // RRF fusion: merged candidate pool passed to GraphRAG (same topK as vector)
                    searchResults = rrfFusion(vecResults, bm25Result.results, topK, 60);

                    long waitMs = System.currentTimeMillis() - bm25Start; // near-zero if BM25 finished first
                    List<Long> bm25Top5 = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, bm25Result.results.size()); i++) bm25Top5.add(bm25Result.results.get(i).id);
                    List<Long> rrfTop5 = new ArrayList<>();
                    for (int i = 0; i < Math.min(5, searchResults.size()); i++) rrfTop5.add(searchResults.get(i).id);
                    LogManager.logI(TAG, String.format(
                            "[BM25+RRF](cache=%s): corpus=%d, bm25_top5=%s, rrf_top5=%s, wait=%dms",
                            bm25Result.cacheHit ? "hit" : "built",
                            bm25Result.corpusSize, bm25Top5, rrfTop5, waitMs));
                    emitStreamingChunkFromManager(String.format("[RAG] BM25+RRF hybrid (corpus=%d)\n",
                            bm25Result.corpusSize));
                }
            }

            List<String> docs = new ArrayList<>();
            StringBuilder similarityInfoBuilder = new StringBuilder();

            for (int i = 0; i < searchResults.size(); i++) {
                KnowledgeGraphDatabase.SearchResult r = searchResults.get(i);
                docs.add(r.content);
                similarityInfoBuilder.append(String.format(Locale.US, "%.3f", r.similarity));
                if (i < searchResults.size() - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            if (!searchResults.isEmpty()) {
                String simHeader = "[RAG] Retrieval Similarity (" + searchResults.size() + " results): ";
                emitStreamingChunkFromManager(simHeader + similarityInfoBuilder.toString() + "\n");
            }

            updateRagResults(docs, similarityInfoBuilder.toString());

            String doneMsg = "Vector search completed, result count: " + docs.size();
            LogManager.logD(TAG, "[RAG][MGR] " + doneMsg);
            emitProgressFromManager(doneMsg);

            return searchResults;
        } catch (InterruptedException ie) {
            LogManager.logI(TAG, "[RAG][MGR] Embedding/search interrupted: " + ie.getMessage());
            throw ie;
        } catch (Exception e) {
            String errorMsg = "Embedding/search failed: " + e.getMessage();
            LogManager.logE(TAG, "[RAG][MGR] " + errorMsg, e);
            emitProgressFromManager(errorMsg);
            return empty;
        }
    }

    /**
     * Run reranker pipeline in a UI-free way and return sorted results.
     * This method only focuses on business logic (calling inference process
     * and building RerankResult list) and does not touch Fragment or UI.
     */
    @NonNull
    public java.util.List<RerankerHandler.RerankResult> runRerankerPipeline(
            @NonNull String query,
            @NonNull java.util.List<String> documents,
            @NonNull String rerankerModelPath,
            int rerankCount
    ) throws InterruptedException {
        java.util.List<RerankerHandler.RerankResult> empty = new java.util.ArrayList<>();
        try {
            if (documents.isEmpty()) {
                LogManager.logW(TAG, "[RERANK][MGR] No documents provided for reranking");
                return empty;
            }

            int topK = rerankCount > 0 ? Math.min(documents.size(), rerankCount) : documents.size();
            LogManager.logI(TAG, "[RERANK][MGR] Starting rerank: docs=" + documents.size() + ", topK=" + topK);

            String instruction = "Given a web search query, retrieve relevant passages that answer the query";

            com.example.offlineai.RuntimeConfigUtil.pushToInference(appContext);
            InferenceClient client = InferenceClient.getInstance(appContext);

            float[] scores = client.rerankScores(rerankerModelPath, instruction, query, documents);

            if (scores == null || scores.length != documents.size()) {
                LogManager.logE(TAG, "[RERANK][MGR] Invalid score array returned, scores="
                        + (scores == null ? "null" : scores.length) + ", docs=" + documents.size());
                return empty;
            }

            java.util.List<RerankerHandler.RerankResult> results = new java.util.ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                float score = scores[i];
                String text = documents.get(i);
                results.add(new RerankerHandler.RerankResult(text, score, i));
            }

            java.util.Collections.sort(results);

            if (topK < results.size()) {
                results = new java.util.ArrayList<>(results.subList(0, topK));
            }

            LogManager.logI(TAG, "[RERANK][MGR] Reranking finished, returning " + results.size() + " results");
            return results;
        } catch (InterruptedException ie) {
            LogManager.logI(TAG, "[RERANK][MGR] Rerank interrupted: " + ie.getMessage());
            throw ie;
        } catch (Exception e) {
            LogManager.logE(TAG, "[RERANK][MGR] Rerank failed: " + e.getMessage(), e);
            return empty;
        }
    }

    // Manager-side Graph RAG NER handler creation (UI-free).
    private synchronized HanLpNerHandler getOrCreateManagerGraphRagNerHandler() {
        String dictPath = ConfigManager.getString(appContext, ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null);
        String valueNone = appContext.getString(R.string.common_none);
        String normalizedPath = null;
        if (dictPath != null && !dictPath.isEmpty() && !valueNone.equals(dictPath)) {
            normalizedPath = dictPath;
        }
        if (graphRagNerHandler != null) {
            boolean samePath = (graphRagDictPath == null && normalizedPath == null)
                    || (graphRagDictPath != null && graphRagDictPath.equals(normalizedPath));
            if (samePath) {
                return graphRagNerHandler;
            }
            graphRagNerHandler.release();
            graphRagNerHandler = null;
            graphRagDictPath = null;
        }
        graphRagNerHandler = new HanLpNerHandler(normalizedPath);
        graphRagDictPath = normalizedPath;

        if (normalizedPath == null || normalizedPath.isEmpty()) {
            String msg = "Dictionary: None";
            LogManager.logI(TAG, "[GRAPH_RAG][MGR] " + msg);
            emitProgressFromManager(msg);
        } else {
            String dictFileName = new File(normalizedPath).getName();
            if (graphRagNerHandler.isDictionaryLoaded()) {
                int wordCount = graphRagNerHandler.getLoadedWordCount();
                String msg = "Dictionary: " + dictFileName + " (loaded " + wordCount + " words)";
                LogManager.logI(TAG, "[GRAPH_RAG][MGR] " + msg);
                emitProgressFromManager(msg);
            } else {
                String baseMsg = "Dictionary: " + dictFileName;
                LogManager.logI(TAG, "[GRAPH_RAG][MGR] " + baseMsg);
                emitProgressFromManager(baseMsg);
                String err = graphRagNerHandler.getDictionaryErrorMessage();
                if (err != null && !err.isEmpty()) {
                    String errMsg = "Dictionary load error: " + err;
                    LogManager.logE(TAG, "[GRAPH_RAG][MGR] " + errMsg);
                    emitProgressFromManager(errMsg);
                }
            }
        }

        return graphRagNerHandler;
    }

    private List<HanLpNerHandler.NerResult.Entity> extractQueryEntitiesForManager(String userQuery) {
        List<HanLpNerHandler.NerResult.Entity> entities = new ArrayList<>();
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return entities;
        }
        try {
            HanLpNerHandler handler = getOrCreateManagerGraphRagNerHandler();
            if (handler == null) {
                return entities;
            }
            HanLpNerHandler.NerResult nerResult = handler.extractEntities(userQuery);
            if (nerResult != null && nerResult.isSuccess()) {
                entities = nerResult.getEntities();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[GRAPH_RAG][MGR] Query NER failed: " + e.getMessage(), e);
        }
        return entities;
    }

    private String normalizeEntityTextForManager(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    // Candidate container for manager-side Graph RAG fusion
    private static class GraphRagCandidateForManager {
        KnowledgeGraphDatabase.SearchResult result;
        float vectorScore;
        float graphScore;
        float finalScore;
        int entityOverlap;
        int vectorRank; // Original rank in pure vector search (-1 if only from graph expansion)
    }

    /**
     * Manager-side Graph RAG fusion pipeline (UI-free business logic).
     * This mirrors the Fragment implementation but uses appContext and
     * manager callbacks instead of requireContext()/updateChatMessage().
     */
    @NonNull
    public List<KnowledgeGraphDatabase.SearchResult> runGraphRagPipeline(
            @NonNull String userQuery,
            @NonNull List<KnowledgeGraphDatabase.SearchResult> searchResults,
            @NonNull KnowledgeGraphDatabase vectorDb,
            int retrievalCount
    ) {
        List<KnowledgeGraphDatabase.SearchResult> empty = new ArrayList<>();

        // Check stop flag before heavy work
        if (shouldStop()) {
            LogManager.logI(TAG, "[GRAPH_RAG][MGR] Task stopped before GraphRAG processing");
            emitProgressFromManager("Operation stopped by user");
            return empty;
        }

        if (searchResults.isEmpty()) {
            // Fallback: vector-only results
            List<String> docs = new ArrayList<>();
            StringBuilder simInfoBuilder = new StringBuilder();
            for (int i = 0; i < searchResults.size(); i++) {
                KnowledgeGraphDatabase.SearchResult r = searchResults.get(i);
                docs.add(r.content);
                simInfoBuilder.append(String.format(Locale.US, "%.3f", r.similarity));
                if (i < searchResults.size() - 1) {
                    simInfoBuilder.append(", ");
                }
            }
            updateRagResults(docs, simInfoBuilder.toString());
            LogManager.logD(TAG, "[GRAPH_RAG][MGR] Empty search results, fallback to vector-only");
            return searchResults;
        }

        try {
            HanLpNerHandler graphNerHandler = null;

            // Load stopwords matcher for query-time cleaning
            String stopwordsPath = ConfigManager.getGraphStopwordsPath(appContext);
            GraphStopwordsMatcher stopwordsMatcher = null;
            if (stopwordsPath != null && !stopwordsPath.isEmpty()) {
                try {
                    stopwordsMatcher = GraphStopwordsMatcher.loadFromFile(stopwordsPath);
                    LogManager.logD(TAG, "[GRAPH_RAG][MGR][STOPWORDS] Loaded stopwords for query-time cleaning");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[GRAPH_RAG][MGR][STOPWORDS] Failed to load stopwords file: " + stopwordsPath, e);
                }
            }

            // Load hub entities for query-time hub filtering (read-only)
            int hubThreshold = ConfigManager.getGraphHubThresholdQuery(appContext);
            Set<String> protectedEntities = null;
            try {
                graphNerHandler = getOrCreateManagerGraphRagNerHandler();
                if (graphNerHandler != null) {
                    protectedEntities = graphNerHandler.getCustomDictionaryWords();
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[GRAPH_RAG][MGR] Failed to load protected entities from custom dictionary: " + e.getMessage(), e);
            }

            Set<String> hubEntities = vectorDb.getHubEntities(hubThreshold, protectedEntities);
            if (!hubEntities.isEmpty()) {
                LogManager.logD(TAG, "[GRAPH_RAG][MGR][HUB_QUERY] Query-time hub set size=" + hubEntities.size());
            }

            List<HanLpNerHandler.NerResult.Entity> queryEntities = extractQueryEntitiesForManager(userQuery);
            float confidenceThreshold = ConfigManager.getGraphEntityConfidenceThreshold(appContext);
            Set<String> queryEntityTexts = new HashSet<>();
            List<String> seedOrder = new ArrayList<>();
            int aliasNormalizedCount = 0;
            for (HanLpNerHandler.NerResult.Entity e : queryEntities) {
                if (e == null) continue;
                String normalized = normalizeEntityTextForManager(e.text);
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
                    String normalized = normalizeEntityTextForManager(t);
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

            if (shouldStop()) {
                LogManager.logI(TAG, "[GRAPH_RAG][MGR] Task stopped after seed collection");
                emitProgressFromManager("Operation stopped by user");
                return empty;
            }

            if (aliasNormalizedCount > 0) {
                LogManager.logD(TAG, String.format(Locale.US,
                        "[GRAPH_ALIAS][MGR] Query-time alias normalization applied to %d entity texts",
                        aliasNormalizedCount));
            }

            Set<String> seedEntities = new HashSet<>();
            for (String text : seedOrder) {
                seedEntities.add(text);
                if (seedEntities.size() >= GRAPH_RAG_MAX_SEED_ENTITIES_MANAGER) {
                    break;
                }
            }

            if (seedEntities.isEmpty()) {
                LogManager.logI(TAG, "[GRAPH_RAG][MGR] No valid seed entities after filtering, fallback to vector-only results");
                List<String> docs = new ArrayList<>();
                StringBuilder simInfoBuilder = new StringBuilder();
                for (int i = 0; i < searchResults.size(); i++) {
                    KnowledgeGraphDatabase.SearchResult r = searchResults.get(i);
                    docs.add(r.content);
                    simInfoBuilder.append(String.format(Locale.US, "%.3f", r.similarity));
                    if (i < searchResults.size() - 1) {
                        simInfoBuilder.append(", ");
                    }
                }
                updateRagResults(docs, simInfoBuilder.toString());
                return searchResults;
            }

            // Use final seed set for overlap calculation as well
            queryEntityTexts.clear();
            queryEntityTexts.addAll(seedEntities);
            int minEdgeWeight = ConfigManager.getGraphMinEdgeWeight(appContext);
            int maxExpandEntities = ConfigManager.getGraphMaxExpandEntities(appContext);
            List<KnowledgeGraphDatabase.ConnectedEntity> connectedEntities =
                    vectorDb.getConnectedEntities(seedEntities, minEdgeWeight, maxExpandEntities);
            Map<String, Integer> graphWeightMap = new HashMap<>();
            for (KnowledgeGraphDatabase.ConnectedEntity ce : connectedEntities) {
                if (ce == null || ce.entityText == null) {
                    continue;
                }
                String normalized = normalizeEntityTextForManager(ce.entityText);
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
                String normalized = normalizeEntityTextForManager(ce.entityText);
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

            int maxExpandChunks = ConfigManager.getGraphMaxExpandChunks(appContext);
            if (maxExpandChunks > 0 && graphChunkIds.size() > maxExpandChunks) {
                graphChunkIds = graphChunkIds.subList(0, maxExpandChunks);
            }

            List<KnowledgeGraphDatabase.SearchResult> graphChunks = vectorDb.getChunksByIds(graphChunkIds);

            if (shouldStop()) {
                LogManager.logI(TAG, "[GRAPH_RAG][MGR] Task stopped after graph expansion");
                emitProgressFromManager("Operation stopped by user");
                return empty;
            }

            Map<Long, GraphRagCandidateForManager> candidateMap = new HashMap<>();
            for (int i = 0; i < searchResults.size(); i++) {
                KnowledgeGraphDatabase.SearchResult r = searchResults.get(i);
                GraphRagCandidateForManager c = new GraphRagCandidateForManager();
                c.result = r;
                c.vectorScore = r.similarity;
                c.graphScore = 0.0f;
                c.finalScore = r.similarity;
                c.entityOverlap = 0;
                c.vectorRank = i;
                candidateMap.put(r.id, c);
            }
            for (KnowledgeGraphDatabase.SearchResult r : graphChunks) {
                if (!candidateMap.containsKey(r.id)) {
                    GraphRagCandidateForManager c = new GraphRagCandidateForManager();
                    c.result = r;
                    c.vectorScore = r.similarity;
                    c.graphScore = 0.0f;
                    c.finalScore = r.similarity;
                    c.entityOverlap = 0;
                    c.vectorRank = -1;
                    candidateMap.put(r.id, c);
                }
            }

            List<Long> allChunkIds = new ArrayList<>(candidateMap.keySet());
            Map<Long, List<String>> entitiesForAll = vectorDb.getEntitiesForChunks(allChunkIds);

            float alpha;
            float beta;
            float gamma;
            int preset = ConfigManager.getGraphRagWeightPreset(appContext);
            switch (preset) {
                case 0: // vector first
                    alpha = 0.9f;
                    beta = 0.1f;
                    gamma = 0.0f;
                    break;
                case 2: // graph enhanced
                    alpha = 0.4f;
                    beta = 0.4f;
                    gamma = 0.2f;
                    break;
                case 1:
                default: // balanced
                    alpha = 0.7f;
                    beta = 0.2f;
                    gamma = 0.1f;
                    break;
            }

            List<GraphRagCandidateForManager> candidates = new ArrayList<>();
            float vecMin = Float.MAX_VALUE;
            float vecMax = -Float.MAX_VALUE;
            float graphMin = Float.MAX_VALUE;
            float graphMax = -Float.MAX_VALUE;
            int overlapMin = Integer.MAX_VALUE;
            int overlapMax = Integer.MIN_VALUE;

            // First pass: compute raw graph and overlap scores and collect min/max ranges
            for (Map.Entry<Long, GraphRagCandidateForManager> entry : candidateMap.entrySet()) {
                Long chunkId = entry.getKey();
                GraphRagCandidateForManager c = entry.getValue();
                List<String> ents = entitiesForAll.get(chunkId);
                if (ents != null) {
                    int overlap = 0;
                    float gScore = 0.0f;
                    for (String t : ents) {
                        String normalized = normalizeEntityTextForManager(t);
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
                    // Adjust graph score by overlap ratio: candidates with no query-entity overlap
                    // get graphScore=0, preventing hub-entity pollution from unrelated documents.
                    float overlapRatio = (float) overlap / (float) Math.max(1, queryEntityTexts.size());
                    c.graphScore = (float) Math.log1p(gScore) * overlapRatio;
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
            for (GraphRagCandidateForManager c : candidates) {
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
            StringBuilder scoreDebug = new StringBuilder("[RAG][Graph][MGR] Fused candidates (top " + limit + "):\n");
            StringBuilder similarityInfoBuilder = new StringBuilder();
            List<KnowledgeGraphDatabase.SearchResult> fusedResults = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                GraphRagCandidateForManager c = candidates.get(i);
                fusedResults.add(c.result);
                fusedDocs.add(c.result.content);
                scoreDebug.append("#").append(i + 1)
                        .append(" id=").append(c.result.id)
                        .append(" vecRank=").append(c.vectorRank)
                        .append(" vec=").append(String.format(Locale.US, "%.3f", c.vectorScore))
                        .append(" graph=").append(String.format(Locale.US, "%.3f", c.graphScore))
                        .append(" overlap=").append(c.entityOverlap)
                        .append(" final=").append(String.format(Locale.US, "%.3f", c.finalScore))
                        .append("\n");
                similarityInfoBuilder.append(String.format(Locale.US, "%.3f", c.finalScore));
                if (i < limit - 1) {
                    similarityInfoBuilder.append(", ");
                }
            }

            String scoreDebugStr = scoreDebug.toString();
            emitStreamingChunkFromManager(scoreDebugStr);
            LogManager.logI(TAG, scoreDebugStr);
            LogManager.logI(TAG, "[GRAPH_RAG][MGR] Fused scores: " + similarityInfoBuilder.toString());

            // Decide whether to use reranker for GraphRAG results
            RerankerConfig rerankConfig = resolveRerankerConfig(vectorDb);
            if (rerankConfig.enabled && rerankConfig.modelPath != null) {
                try {
                    List<RerankerHandler.RerankResult> reranked = runRerankerPipeline(
                            userQuery,
                            fusedDocs,
                            rerankConfig.modelPath,
                            rerankConfig.rerankCount
                    );

                    if (reranked == null || reranked.isEmpty()) {
                        LogManager.logW(TAG, "[GRAPH_RAG][MGR] Empty rerank results, fallback to fused ranking");
                    } else {
                        List<String> rerankedDocs = new ArrayList<>();
                        StringBuilder rerankInfoBuilder = new StringBuilder();
                        for (int i = 0; i < reranked.size(); i++) {
                            RerankerHandler.RerankResult r = reranked.get(i);
                            rerankedDocs.add(r.text);
                            rerankInfoBuilder.append(String.format(Locale.US, "%.3f", r.score));
                            if (i < reranked.size() - 1) {
                                rerankInfoBuilder.append(", ");
                            }
                        }
                        String simInfo = "GraphRAG+Rerank final scores: " + rerankInfoBuilder.toString();
                        updateRagResults(rerankedDocs, simInfo);
                        LogManager.logD(TAG, "[GRAPH_RAG][MGR] Reranked GraphRAG results processed, document count: " + rerankedDocs.size());
                        return fusedResults;
                    }
                } catch (InterruptedException ie) {
                    LogManager.logI(TAG, "[GRAPH_RAG][MGR] Graph RAG reranker interrupted: " + ie.getMessage());
                    String simInfo = "GraphRAG final scores: " + similarityInfoBuilder.toString();
                    updateRagResults(fusedDocs, simInfo);
                    LogManager.logD(TAG, "[GRAPH_RAG][MGR] Reranker interrupted, fallback to fused results, document count: " + fusedDocs.size());
                    return fusedResults;
                }
            }

            // No reranker configured or rerank disabled: keep Graph RAG fused ranking directly.
            String simInfo = "GraphRAG final scores: " + similarityInfoBuilder.toString();
            updateRagResults(fusedDocs, simInfo);
            LogManager.logD(TAG, "[GRAPH_RAG][MGR] Fused results processing completed without reranker, document count: " + fusedDocs.size());

            return fusedResults;

        } catch (Exception e) {
            LogManager.logE(TAG, "[GRAPH_RAG][MGR] Failed to process Graph RAG results: " + e.getMessage(), e);
            emitProgressFromManager("Failed to process Graph RAG results: " + e.getMessage());
            return empty;
        }
    }

    /**
     * Build prompt with knowledge base content.
     * This method contains no UI code and can be safely reused from Fragments.
     */
    public String buildPromptWithKnowledgeBase(String systemPrompt, String userPrompt, java.util.List<String> relevantDocs) {
        StringBuilder fullPrompt = new StringBuilder();

        LogManager.logD(TAG, "Building prompt with knowledge base content, found " + relevantDocs.size() + " relevant documents");

        // Add system prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
            LogManager.logD(TAG, "Added system prompt, length: " + systemPrompt.length());
        } else {
            LogManager.logD(TAG, "System prompt is empty");
        }

        // Add knowledge base content
        if (relevantDocs != null && !relevantDocs.isEmpty()) {
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
        if (userPrompt != null) {
            fullPrompt.append(userPrompt);
        }

        // Record final prompt length
        int promptLength = fullPrompt.length();
        LogManager.logD(TAG, "Final prompt length: " + promptLength + " characters");

        return fullPrompt.toString();
    }

    /**
     * Build prompt without knowledge base content.
     */
    public String buildPromptWithoutKnowledgeBase(String systemPrompt, String userPrompt) {
        StringBuilder fullPrompt = new StringBuilder();

        // Add system prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
        }

        // Add user question
        if (userPrompt != null) {
            fullPrompt.append(userPrompt);
        }

        return fullPrompt.toString();
    }

    /**
     * Build user prompt with online chat history for HTTP-based LLM APIs.
     * Local MNN models already have their own history pipeline in LocalLLMMNNHandler
     * and therefore should NOT use this helper.
     */
    private String buildUserPromptWithOnlineHistory(@Nullable String systemPrompt, @NonNull String currentUserPrompt) {
        try {
            // Read Agent mode setting - if enabled, force history to 0 (like LocalLlmAdapter)
            boolean agentModeEnabled = ConfigManager.getBoolean(appContext,
                    ConfigManager.KEY_AGENT_MODE_ENABLED,
                    false);
            
            // Read history rounds from persistent config
            int historyRounds = ConfigManager.getInt(appContext,
                    ConfigManager.KEY_HISTORY_ROUNDS,
                    ConfigManager.DEFAULT_HISTORY_ROUNDS);
            
            // CRITICAL: Force 0 history in Agent mode (same logic as LocalLlmAdapter Line 428-433)
            if (agentModeEnabled) {
                historyRounds = 0;
                LogManager.logI(TAG, "[HISTORY][ONLINE] Agent mode enabled, forcing historyRounds=0");
            }
            
            if (historyRounds <= 0) {
                LogManager.logD(TAG, "[HISTORY][ONLINE] History rounds <= 0, skip history injection");
                return currentUserPrompt;
            }

            // Resolve current chat folder
            String chatFolderPath = ConfigManager.getString(appContext,
                    ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                    "");
            if (chatFolderPath == null || chatFolderPath.trim().isEmpty()) {
                LogManager.logD(TAG, "[HISTORY][ONLINE] No chat folder configured, skip history injection");
                return currentUserPrompt;
            }

            // Load markdown conversation and convert to ChatDataItem list
            java.util.List<ChatDataItem> allMessages = ChatHistoryManager.loadConversation(appContext, chatFolderPath);
            if (allMessages == null || allMessages.isEmpty()) {
                LogManager.logD(TAG, "[HISTORY][ONLINE] No messages loaded from markdown, skip history injection");
                return currentUserPrompt;
            }

            // Build filtered history using ChatHistoryFilter (removes debug/img/audio markers)
            String effectiveSystemPrompt = systemPrompt != null ? systemPrompt : "";
            java.util.List<ChatHistoryFilter.PromptItem> historyItems =
                    ChatHistoryFilter.buildHistoryForInference(appContext,
                            allMessages,
                            effectiveSystemPrompt,
                            historyRounds);

            if (historyItems == null || historyItems.isEmpty()) {
                LogManager.logD(TAG, "[HISTORY][ONLINE] Filtered history is empty, skip history injection");
                return currentUserPrompt;
            }

            StringBuilder historyBuilder = new StringBuilder();
            historyBuilder.append("[History]\n");

            int added = 0;
            for (ChatHistoryFilter.PromptItem item : historyItems) {
                if (item == null || item.content == null || item.content.isEmpty()) {
                    continue;
                }

                // Skip system prompt here because it is already passed separately
                if ("system".equals(item.role)) {
                    continue;
                }

                String roleLabel;
                if ("user".equals(item.role)) {
                    roleLabel = "User: ";
                } else if ("assistant".equals(item.role)) {
                    roleLabel = "Assistant: ";
                } else {
                    roleLabel = item.role + ": ";
                }

                historyBuilder.append(roleLabel)
                        .append(item.content)
                        .append("\n");
                added++;
            }

            if (added == 0) {
                LogManager.logD(TAG, "[HISTORY][ONLINE] No non-system history items after filtering, skip history injection");
                return currentUserPrompt;
            }

            // Append current question after history block
            historyBuilder.append("\n[Question]\n");
            historyBuilder.append(currentUserPrompt);

            String combined = historyBuilder.toString();
            LogManager.logI(TAG, "[HISTORY][ONLINE] Injected " + added
                    + " history messages into online prompt, length=" + combined.length());
            return combined;
        } catch (Exception e) {
            LogManager.logE(TAG, "[HISTORY][ONLINE] Failed to build history-aware user prompt: "
                    + e.getMessage(), e);
            return currentUserPrompt;
        }
    }

    /**
     * Initialize TTS adapter if external/system TTS is enabled.
     * This is called at the start of runQueryPipeline to set up TTS before LLM streaming begins.
     * "原生(Omni)" TTS is handled by LocalLLMMNNHandler internally, not TtsAdapter.
     */
    private void initializeTtsIfEnabled() {
        // Use Manager-held TtsAdapter reference (survives UI destruction)
        TtsAdapter tts = this.ttsAdapter;
        if (tts == null) {
            LogManager.logD(TAG, "[MGR][TTS] TtsAdapter is null, skipping TTS initialization");
            return;
        }
        
        // Read TTS model setting from config
        String ttsModel = ConfigManager.getString(appContext, ConfigManager.KEY_TTS_MODEL, "无");
        
        // Check if external TTS is enabled (NOT "无" and NOT "原生(Omni)")
        boolean isExternalTtsEnabled = ttsModel != null 
                && !ttsModel.equals("无")
                && !ttsModel.equals("原生(Omni)");
        
        if (!isExternalTtsEnabled) {
            LogManager.logD(TAG, "[MGR][TTS] External TTS not enabled: model=" + ttsModel);
            return;
        }
        
        // Get current chat folder for TTS output
        String chatFolder = ConfigManager.getString(appContext, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
        boolean autoPlay = ConfigManager.getTtsAutoPlay(appContext);
        
        LogManager.logI(TAG, "[MGR][TTS] Enabling external TTS: model=" + ttsModel + ", autoPlay=" + autoPlay);
        
        // Enable TtsAdapter with callback
        tts.enable(ttsModel, chatFolder, autoPlay, new TtsAdapter.TtsCallback() {
            @Override
            public void onTtsComplete(String mergedAudioPath, boolean playbackComplete) {
                LogManager.logI(TAG, "[MGR][TTS] TTS complete: " + mergedAudioPath + ", playbackComplete=" + playbackComplete);
                RagQueryCallback innerCb = callback;
                if (innerCb != null) {
                    innerCb.onTtsStateChanged(false);
                    if (mergedAudioPath != null && !mergedAudioPath.isEmpty()) {
                        innerCb.onLlmCompleteWithAudio(mergedAudioPath);
                    }
                }
            }
            
            @Override
            public void onError(String error) {
                LogManager.logE(TAG, "[MGR][TTS] TTS generation error: " + error);
                RagQueryCallback innerCb = callback;
                if (innerCb != null) {
                    innerCb.onTtsStateChanged(false);
                }
            }
        });
        
        // Notify UI that TTS is enabled for this query
        RagQueryCallback cb = this.callback;
        if (cb != null) {
            cb.onTtsStateChanged(true);
        }
    }

    /**
     * Filter content for TTS processing.
     * Removes debug/performance tags, image/audio tags and their content.
     */
    public String filterTtsContent(String chunk) {
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
        filtered = filtered.replaceAll("\\[Audio\\d*\\]\"([^\"]+)\"","$1");
        filtered = filtered.replaceAll("\\[Audio\\d*\\]", "");

        return filtered;
    }

    /**
     * Resolve embedding model path based on embedding root directory and metadata string fields.
     * This is pure business logic and does not touch any UI elements.
     *
     * @param embeddingModelRoot Root directory where embedding models are stored
     * @param modeldir           Optional subdirectory specified by metadata (may be null/empty)
     * @param embModelName       Optional model file or directory name from metadata
     * @return Candidate embedding model path (may or may not exist on disk), or null if cannot be resolved
     */
    @Nullable
    public String resolveEmbeddingModelPath(@NonNull String embeddingModelRoot,
                                            @Nullable String modeldir,
                                            @Nullable String embModelName) {
        try {
            String foundModelPath = null;

            // Prefer modeldir when available (directory under embeddingModelRoot)
            if (modeldir != null && !modeldir.isEmpty()) {
                File modeldirFile = new File(embeddingModelRoot, modeldir);
                if (modeldirFile.exists() && modeldirFile.isDirectory()) {
                    // First try to find embedding.mnn explicitly
                    File embeddingMnn = new File(modeldirFile, "embedding.mnn");
                    if (embeddingMnn.exists() && embeddingMnn.isFile()) {
                        foundModelPath = embeddingMnn.getAbsolutePath();
                        LogManager.logD(TAG, "Found embedding.mnn in modeldir: " + foundModelPath);
                    } else {
                        // Fallback: look for any .mnn file or config.json
                        File[] files = modeldirFile.listFiles();
                        if (files != null) {
                            for (File file : files) {
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
            }

            // If no model found in modeldir, try using model name directly under root
            if ((foundModelPath == null || foundModelPath.trim().isEmpty()) &&
                embModelName != null && !embModelName.isEmpty()) {
                foundModelPath = new File(embeddingModelRoot, embModelName).getAbsolutePath();
            }

            return foundModelPath;
        } catch (Exception e) {
            LogManager.logE(TAG, "Error resolving embedding model path", e);
            return null;
        }
    }
    
    @Nullable
    public String resolveRerankerModelPath(@Nullable String rerankerDir) {
        try {
            if (rerankerDir == null || rerankerDir.trim().isEmpty()) {
                LogManager.logD(TAG, "No reranker model directory configured in database metadata");
                return null;
            }

            String rerankerBasePath = ConfigManager.getRerankerModelPath(appContext);
            File rerankerModelDir = new File(rerankerBasePath, rerankerDir);
            if (!rerankerModelDir.exists() || !rerankerModelDir.isDirectory()) {
                LogManager.logW(TAG, "Reranker model directory does not exist: " + rerankerModelDir.getAbsolutePath());
                return null;
            }

            File[] modelFiles = rerankerModelDir.listFiles(file ->
                file.isFile() && (file.getName().equals("config.json") || file.getName().endsWith(".mnn")));

            if (modelFiles == null || modelFiles.length == 0) {
                LogManager.logW(TAG, "No MNN reranker model files found in reranker model directory: " + rerankerModelDir.getAbsolutePath());
                return null;
            }

            String modelPath = modelFiles[0].getAbsolutePath();
            LogManager.logD(TAG, "Found reranker model: " + modelPath);
            return modelPath;
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get reranker model path in manager: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Generic synchronous query method
     * Blocks until query completes and returns the result
     * Can be used by Agent or any other component that needs synchronous query
     * 
     * @param request Query request with all parameters
     * @return Model output string, or null if error
     */
    @Nullable
    public String querySync(@NonNull QueryRequest request) {
        LogManager.logI(TAG, "[SYNC] Starting synchronous query");
        
        try {
            // Use CountDownLatch to block until query completes
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<String> taskIdRef = new java.util.concurrent.atomic.AtomicReference<>(null);
            
            // Wrap original callback instead of replacing it
            // This allows UI updates and history saving while also returning result to caller
            final RagQueryCallback originalCallback = this.callback;
            updateCallback(new RagQueryCallback() {
                @Override
                public void onSendingStateChanged(boolean sending) {
                    if (originalCallback != null) {
                        originalCallback.onSendingStateChanged(sending);
                    }
                }
                
                @Override
                public void onTtsStateChanged(boolean generating) {
                    if (originalCallback != null) {
                        originalCallback.onTtsStateChanged(generating);
                    }
                }
                
                @Override
                public void onProgressUpdate(int progress, String message) {
                    if (originalCallback != null) {
                        originalCallback.onProgressUpdate(progress, message);
                    }
                }
                
                // NOTE: onStreamingData removed - UI uses polling mechanism
                // We accumulate result by reading from buffer after completion
                
                @Override
                public void onQueryComplete(boolean querySuccess, String errorMessage) {
                    // Forward to original callback for history saving
                    if (originalCallback != null) {
                        originalCallback.onQueryComplete(querySuccess, errorMessage);
                    }
                    // Unblock synchronous caller
                    success.set(querySuccess);
                    latch.countDown();
                    LogManager.logI(TAG, "[SYNC] Query completed: " + querySuccess);
                }
                
                @Override
                public void onLlmCompleteWithAudio(String audioPath) {
                    if (originalCallback != null) {
                        originalCallback.onLlmCompleteWithAudio(audioPath);
                    }
                }
                
                @Override
                public void onRequestReloadChatHistory() {
                    if (originalCallback != null) {
                        originalCallback.onRequestReloadChatHistory();
                    }
                }
                
                @Override
                public void onRequestUpdateButtonText() {
                    if (originalCallback != null) {
                        originalCallback.onRequestUpdateButtonText();
                    }
                }
                
                @Override
                public void onResetStopFlagsForNewQuery() {
                    if (originalCallback != null) {
                        originalCallback.onResetStopFlagsForNewQuery();
                    }
                }
                
                @Override
                public void onQueryStarted(@NonNull String taskId) {
                    // Save taskId so we can read from buffer after completion
                    taskIdRef.set(taskId);
                    if (originalCallback != null) {
                        originalCallback.onQueryStarted(taskId);
                    }
                }
                
                @Override
                public void onRequestStartInferenceForeground(@NonNull String description) {
                    if (originalCallback != null) {
                        originalCallback.onRequestStartInferenceForeground(description);
                    }
                }
                
                @Override
                public void onRequestEndInferenceForeground() {
                    if (originalCallback != null) {
                        originalCallback.onRequestEndInferenceForeground();
                    }
                }
                
                @Override
                public void onAgentActionDetected(String fullResponse) {
                    if (originalCallback != null) {
                        originalCallback.onAgentActionDetected(fullResponse);
                    }
                }
                
                @Override
                public void onAsrStateChanged(boolean isRunning) {
                    if (originalCallback != null) {
                        originalCallback.onAsrStateChanged(isRunning);
                    }
                }
            });
            
            // Start query
            startQuery(request);
            
            // Wait for completion with timeout to prevent infinite hang
            LogManager.logI(TAG, "[SYNC] Waiting for query to complete (timeout=120s)...");
            boolean completed = latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                LogManager.logE(TAG, "[SYNC] Query timed out after 120s");
                updateCallback(originalCallback);
                return null;
            }
            
            // Restore original callback
            updateCallback(originalCallback);
            
            if (!success.get()) {
                LogManager.logE(TAG, "[SYNC] Query failed");
                return null;
            }
            
            // Read complete result from buffer
            String taskId = taskIdRef.get();
            if (taskId == null || taskId.isEmpty()) {
                LogManager.logE(TAG, "[SYNC] No taskId available, cannot read result");
                return null;
            }
            
            // Read all data from buffer (from position 0 to end)
            TaskLogBuffer.ReadResult bufferResult = readBufferFromPos(taskId, 0);
            if (bufferResult == null || !bufferResult.hasData()) {
                LogManager.logW(TAG, "[SYNC] No data in buffer for taskId=" + taskId);
                return "";
            }
            
            String result = bufferResult.data;
            LogManager.logI(TAG, "[SYNC] Query result length: " + result.length() + " (read from buffer)");
            return result;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[SYNC] Error in synchronous query", e);
            return null;
        }
    }

    /**
     * Shutdown the manager-owned RAG executor. Should be called when the
     * corresponding UI lifecycle (e.g. Fragment) is being destroyed to
     * release resources. Business callers should not submit new tasks after
     * shutdown is invoked.
     */
    public void shutdown() {
        try {
            ragQueryExecutor.shutdownNow();
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR] Error shutting down ragQueryExecutor", e);
        }
        try {
            bm25Executor.shutdownNow();
        } catch (Exception e) {
            LogManager.logE(TAG, "[MGR] Error shutting down bm25Executor", e);
        }
    }

    // ========== BM25 + RRF Hybrid Retrieval ==========
    // Mirrors Python _bm25_tokenize / _build_bm25_index / _bm25_score / _rrf_fusion in py_mnn_kb.py

    /**
     * Pre-built BM25 index for one knowledge base.
     * Cached per KB name + chunk count; rebuilt on count change.
     */
    private static class Bm25Index {
        final List<KnowledgeGraphDatabase.ChunkForBm25> chunks;
        /** Per-document term-frequency maps */
        final List<Map<String, Integer>> docTf;
        /** Per-document length (token count) */
        final int[] docDl;
        /** Document frequency: term -> number of docs containing it */
        final Map<String, Integer> df;
        final double avgDl;
        final int N;
        final int chunkCount; // snapshot used for cache invalidation
        // BM25 k1 / b constants (same as Python defaults)
        static final double K1 = 1.5;
        static final double B  = 0.75;

        Bm25Index(List<KnowledgeGraphDatabase.ChunkForBm25> chunks,
                  List<Map<String, Integer>> docTf, int[] docDl,
                  Map<String, Integer> df, double avgDl, int chunkCount) {
            this.chunks     = chunks;
            this.docTf      = docTf;
            this.docDl      = docDl;
            this.df         = df;
            this.avgDl      = avgDl;
            this.N          = chunks.size();
            this.chunkCount = chunkCount;
        }
    }

    /**
     * Result of one complete BM25 retrieval run.
     * Carries both the ranked results and diagnostic metadata for logging.
     */
    private static class Bm25RetrievalResult {
        /** Top-K BM25 results, sorted by score descending (similarity=0.0f). */
        final List<KnowledgeGraphDatabase.SearchResult> results;
        /** Total corpus size used for scoring. */
        final int corpusSize;
        /** Whether the index was served from cache (true) or freshly built (false). */
        final boolean cacheHit;

        Bm25RetrievalResult(List<KnowledgeGraphDatabase.SearchResult> results,
                            int corpusSize, boolean cacheHit) {
            this.results    = results;
            this.corpusSize = corpusSize;
            this.cacheHit   = cacheHit;
        }
    }

    /**
     * Tokenize text for BM25: ASCII alphanumeric tokens (>=2 chars, lower-cased) +
     * individual CJK characters + CJK bigrams.
     * Mirrors Python _bm25_tokenize() exactly:
     *   regex r'[A-Za-z0-9][A-Za-z0-9_\-.]*' → first char must be alnum.
     *   CJK range \u4e00-\u9fff \u3400-\u4dbf \u20000-\u2a6df (supplementary via codePoint).
     */
    static List<String> bm25Tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) return tokens;

        // ASCII tokens: first char must be letter or digit (mirrors Python regex [A-Za-z0-9][A-Za-z0-9_\-.]*)
        // Continuation chars may include _ - .
        StringBuilder asciiBuf = new StringBuilder();
        boolean inToken = false;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : 0;
            boolean isAlnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            boolean isCont  = isAlnum || c == '_' || c == '-' || c == '.';
            if (!inToken) {
                // First char must be alnum to start a token
                if (isAlnum) {
                    asciiBuf.append(c);
                    inToken = true;
                }
            } else {
                if (isCont) {
                    asciiBuf.append(c);
                } else {
                    // End of token: strip leading/trailing ._- then emit if length >= 2
                    String tok = asciiBuf.toString().toLowerCase(Locale.US)
                            .replaceAll("^[._\\-]+|[._\\-]+$", "");
                    if (tok.length() >= 2) tokens.add(tok);
                    asciiBuf.setLength(0);
                    inToken = false;
                    // Current char may start a new token
                    if (isAlnum) {
                        asciiBuf.append(c);
                        inToken = true;
                    }
                }
            }
        }

        // CJK character unigrams + bigrams
        // Use String (not char) to correctly handle supplementary plane U+20000-U+2A6DF
        List<String> cjk = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x20000 && cp <= 0x2A6DF)) {
                cjk.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        for (int i = 0; i < cjk.size(); i++) {
            tokens.add(cjk.get(i));
            if (i + 1 < cjk.size()) {
                tokens.add(cjk.get(i) + cjk.get(i + 1));
            }
        }
        return tokens;
    }

    /**
     * Build BM25 index from a list of chunks.
     * Pre-computes per-document tf_map and dl to speed up query-time scoring.
     * Mirrors Python _build_bm25_index().
     */
    private static Bm25Index buildBm25Index(
            List<KnowledgeGraphDatabase.ChunkForBm25> chunks, int chunkCount) {
        int N = chunks.size();
        List<Map<String, Integer>> docTf = new ArrayList<>(N);
        int[] docDl = new int[N];
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;

        for (int i = 0; i < N; i++) {
            List<String> tokens = bm25Tokenize(chunks.get(i).content);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.put(t, tf.getOrDefault(t, 0) + 1);
            }
            docTf.add(tf);
            docDl[i] = tokens.size();
            totalLen += tokens.size();
            for (String t : tf.keySet()) {
                df.put(t, df.getOrDefault(t, 0) + 1);
            }
        }
        double avgDl = N > 0 ? (double) totalLen / N : 1.0;
        return new Bm25Index(chunks, docTf, docDl, df, avgDl, chunkCount);
    }

    /**
     * Compute BM25 score for a single document.
     * Mirrors Python _bm25_score().
     */
    private static double bm25Score(Bm25Index idx, List<String> queryTokens, int docI) {
        Map<String, Integer> tf = idx.docTf.get(docI);
        int dl = idx.docDl[docI];
        double score = 0.0;
        for (String term : queryTokens) {
            int tfVal = tf.getOrDefault(term, 0);
            if (tfVal == 0) continue;
            int nq = idx.df.getOrDefault(term, 0);
            double idf = Math.log((idx.N - nq + 0.5) / (nq + 0.5) + 1.0);
            double num = tfVal * (Bm25Index.K1 + 1.0);
            double den = tfVal + Bm25Index.K1 * (1.0 - Bm25Index.B + Bm25Index.B * dl / Math.max(idx.avgDl, 1.0));
            score += idf * num / den;
        }
        return score;
    }

    /**
     * Run BM25 search over cached index; return top-K results sorted by score descending.
     * Results carry similarity=0.0f (filled in by rrfFusion from vec side if intersecting).
     */
    private static List<KnowledgeGraphDatabase.SearchResult> bm25Search(
            Bm25Index idx, String query, int topK) {
        List<String> queryTokens = bm25Tokenize(query);
        if (queryTokens.isEmpty()) return new ArrayList<>();

        // Score all docs; store original chunk index (not id) for O(1) retrieval
        int N = idx.chunks.size();
        int[] hitIdx = new int[N];
        double[] sc  = new double[N];
        int hitCount = 0;
        for (int i = 0; i < N; i++) {
            double s = bm25Score(idx, queryTokens, i);
            if (s > 0) {
                hitIdx[hitCount] = i;
                sc[hitCount]     = s;
                hitCount++;
            }
        }

        // Sort hit slots by score descending, take top-K
        int k = Math.min(topK, hitCount);
        Integer[] order = new Integer[hitCount];
        for (int i = 0; i < hitCount; i++) order[i] = i;
        Arrays.sort(order, (a, b2) -> Double.compare(sc[b2], sc[a]));

        List<KnowledgeGraphDatabase.SearchResult> results = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int slot = order[i];
            KnowledgeGraphDatabase.ChunkForBm25 ch = idx.chunks.get(hitIdx[slot]);
            results.add(new KnowledgeGraphDatabase.SearchResult(ch.id, ch.content, ch.source, 0.0f));
        }
        return results;
    }

    /**
     * Reciprocal Rank Fusion of vector and BM25 ranked lists.
     * Mirrors Python _rrf_fusion() with penalty rank = len*2+1 and all edge cases.
     *
     * @param vecResults  vector search results (ordered by similarity desc)
     * @param bm25Results BM25 results (ordered by bm25 score desc)
     * @param topK        number of fused results to return
     * @param rrfK        RRF constant k (default 60)
     * @return merged list sorted by RRF score descending
     */
    private static List<KnowledgeGraphDatabase.SearchResult> rrfFusion(
            List<KnowledgeGraphDatabase.SearchResult> vecResults,
            List<KnowledgeGraphDatabase.SearchResult> bm25Results,
            int topK, int rrfK) {
        if (topK <= 0) return new ArrayList<>();
        // Degenerate cases
        if (vecResults.isEmpty()) {
            return new ArrayList<>(bm25Results.subList(0, Math.min(topK, bm25Results.size())));
        }
        if (bm25Results.isEmpty()) {
            return new ArrayList<>(vecResults.subList(0, Math.min(topK, vecResults.size())));
        }

        // Build id -> SearchResult (vec takes priority for similarity)
        Map<Long, KnowledgeGraphDatabase.SearchResult> idToChunk = new HashMap<>();
        for (KnowledgeGraphDatabase.SearchResult r : bm25Results) {
            idToChunk.put(r.id, new KnowledgeGraphDatabase.SearchResult(r.id, r.content, r.source, r.similarity));
        }
        for (KnowledgeGraphDatabase.SearchResult r : vecResults) {
            KnowledgeGraphDatabase.SearchResult existing = idToChunk.get(r.id);
            if (existing != null) {
                existing.similarity = r.similarity; // vec similarity takes priority
            } else {
                idToChunk.put(r.id, new KnowledgeGraphDatabase.SearchResult(r.id, r.content, r.source, r.similarity));
            }
        }

        // Build rank maps (1-indexed)
        Map<Long, Integer> vecRank  = new HashMap<>();
        Map<Long, Integer> bm25Rank = new HashMap<>();
        for (int i = 0; i < vecResults.size();  i++) vecRank.put(vecResults.get(i).id,   i + 1);
        for (int i = 0; i < bm25Results.size(); i++) bm25Rank.put(bm25Results.get(i).id, i + 1);

        // Penalty ranks for missing items: len*2+1 (strictly worse than last-rank item)
        int vecPenalty  = vecResults.size()  * 2 + 1;
        int bm25Penalty = bm25Results.size() * 2 + 1;

        // Compute RRF scores for union of all IDs
        Set<Long> allIds = new HashSet<>(idToChunk.keySet());
        final Map<Long, Double> rrfScores = new HashMap<>();
        for (long cid : allIds) {
            int rv = vecRank.getOrDefault(cid,  vecPenalty);
            int rb = bm25Rank.getOrDefault(cid, bm25Penalty);
            rrfScores.put(cid, 1.0 / (rrfK + rv) + 1.0 / (rrfK + rb));
        }

        // Sort by RRF score descending, take topK
        List<Long> sorted = new ArrayList<>(allIds);
        sorted.sort((a, b) -> Double.compare(rrfScores.get(b), rrfScores.get(a)));

        List<KnowledgeGraphDatabase.SearchResult> result = new ArrayList<>();
        int limit = Math.min(topK, sorted.size());
        for (int i = 0; i < limit; i++) {
            result.add(idToChunk.get(sorted.get(i)));
        }
        return result;
    }

    /**
     * Get or build BM25 index for the given KB, with double-checked cache.
     * Cache key: kbName; invalidated when chunk count changes.
     * Mirrors Python KnowledgeBase._bm25_cache logic.
     *
     * Thread safety:
     *  - First check under lock: fast path for cache hits.
     *  - Build (DB read + index construction) happens outside lock.
     *  - Second check under lock: only write cache if no concurrent build won the race.
     *  - Bm25Index is immutable after construction (all fields final), safe to share.
     *
     * @return pair: (index, cacheHit=true if served from cache)
     */
    private Bm25Index getOrBuildBm25Index(KnowledgeGraphDatabase vectorDb, String kbName,
                                           boolean[] outCacheHit) {
        int currentCount = vectorDb.getChunkCount();
        synchronized (bm25CacheLock) {
            Bm25Index cached = bm25Cache.get(kbName);
            if (cached != null && cached.chunkCount == currentCount) {
                if (outCacheHit != null) outCacheHit[0] = true;
                return cached;
            }
        }
        // Build outside lock to avoid blocking other threads during DB read + index construction
        long t0 = System.currentTimeMillis();
        List<KnowledgeGraphDatabase.ChunkForBm25> chunks = vectorDb.getAllChunksForBm25();
        Bm25Index idx = buildBm25Index(chunks, currentCount);
        long buildMs = System.currentTimeMillis() - t0;
        LogManager.logI(TAG, String.format("[BM25] Index built: kb=%s, corpus=%d, time=%dms",
                kbName, chunks.size(), buildMs));
        synchronized (bm25CacheLock) {
            Bm25Index cached = bm25Cache.get(kbName);
            if (cached == null || cached.chunkCount != currentCount) {
                bm25Cache.put(kbName, idx);
            } else {
                // Another thread built the same version concurrently; use theirs.
                idx = cached;
            }
        }
        if (outCacheHit != null) outCacheHit[0] = false;
        return idx;
    }

    /**
     * Run the complete BM25 retrieval pipeline for one query:
     *   1. Get or build (cached) BM25 index.
     *   2. Score all documents and return top-bm25TopK results.
     *
     * This method is designed to run entirely on bm25Executor, concurrently with
     * computeEmbedding + vectorSearch on the RAG query thread.
     * All inputs are either immutable (query string, topK int) or thread-safe reads
     * (SQLiteDatabase.getReadableDatabase is safe for concurrent reads).
     *
     * @param vectorDb  KB database (read-only operations only)
     * @param kbName    KB name for cache keying
     * @param query     user query string
     * @param bm25TopK  number of BM25 results to return
     * @return Bm25RetrievalResult with ranked results + diagnostics
     */
    private Bm25RetrievalResult runBm25Retrieval(KnowledgeGraphDatabase vectorDb,
                                                   String kbName, String query, int bm25TopK) {
        boolean[] cacheHit = {false};
        Bm25Index idx = getOrBuildBm25Index(vectorDb, kbName, cacheHit);
        List<KnowledgeGraphDatabase.SearchResult> results = bm25Search(idx, query, bm25TopK);
        return new Bm25RetrievalResult(results, idx.N, cacheHit[0]);
    }
}

