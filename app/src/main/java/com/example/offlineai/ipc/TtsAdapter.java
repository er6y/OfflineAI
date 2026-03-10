package com.example.offlineai.ipc;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.R;
import com.example.offlineai.ChatHistoryManager;
import com.example.offlineai.AudioService;
import com.example.offlineai.BackgroundTask;
import com.example.offlineai.BackgroundTaskManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * TTS Adapter for streaming text-to-speech
 * Supports both MNN TTS Framework (External) and Android System TTS
 */
public class TtsAdapter {
    private static final String TAG = "TtsAdapter";
    
    // Singleton
    private static volatile TtsAdapter instance;
    private final Context context;
    
    // Sentence detection
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
        "[^。！？.!?\\r\\n]+[。！？.!?]+|[^。！？.!?\\r\\n]+[\\r\\n]+"
    );
    private static final int MAX_SENTENCE_LENGTH = 100;
    
    // State
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private String lastLoggedTtsModel = null;  // Track last logged model to avoid repeated logs
    private final BlockingQueue<String> sentenceQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean sentenceQueueCompleted = new AtomicBoolean(false);
    private final AtomicBoolean ttsThreadStarted = new AtomicBoolean(false);
    private volatile boolean ttsEnabled = false;
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // Background task id for current TTS generation
    private String ttsTaskId = null;
    
    // TTS parameters
    private String currentTtsModel = null;
    private String outputDir = null;
    private boolean autoPlay = false;
    private TtsCallback callback = null;
    
    // TTS services
    // System TTS handler (Android system service)
    private SystemTtsHandler systemTtsHandler = null;
    private boolean systemTtsLoaded = false;
    
    // External TTS (MNN-based TTS model) - runs in child process
    private Object externalTtsService = null;
    private boolean externalTtsLoaded = false;
    private boolean externalTtsLoadFailed = false;
    private final AtomicBoolean externalTtsLoading = new AtomicBoolean(false);
    private String currentExternalModelPath = null;
    private int ttsSampleRate = 44100;  // Default, read from config.json
    
    // Audio playback
    private final BlockingQueue<String> audioPlaybackQueue = new LinkedBlockingQueue<>();
    private final List<String> generatedAudioFiles = new CopyOnWriteArrayList<>();
    private volatile String mergedAudioPath = null;  // Store merged audio path for callback
    
    // Threads
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile Thread consumerThread = null;
    private volatile Thread playbackThread = null;
    private volatile CountDownLatch playbackDoneLatch = null;
    
    public interface TtsCallback {
        /**
         * TTS完成回调
         * @param mergedAudioPath 合并后的音频文件路径
         * @param playbackComplete 播放是否完成（autoPlay=false时为false）
         */
        void onTtsComplete(String mergedAudioPath, boolean playbackComplete);
        void onError(String error);
    }
    
    private TtsAdapter(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public static TtsAdapter getInstance(Context context) {
        if (instance == null) {
            synchronized (TtsAdapter.class) {
                if (instance == null) {
                    instance = new TtsAdapter(context);
                }
            }
        }
        return instance;
    }
    
    public void enable(String ttsModel, String outputDir, boolean autoPlay, TtsCallback callback) {
        this.currentTtsModel = ttsModel;
        this.outputDir = outputDir;
        this.autoPlay = autoPlay;
        this.callback = callback;
        this.ttsEnabled = true;
        this.shouldStop.set(false);
        
        // Clear state
        sentenceBuffer.setLength(0);
        sentenceQueue.clear();
        audioPlaybackQueue.clear();
        generatedAudioFiles.clear();
        sentenceQueueCompleted.set(false);
        ttsThreadStarted.set(false);
        if (autoPlay) {
            playbackDoneLatch = new CountDownLatch(1);
        } else {
            playbackDoneLatch = null;
        }
        
        // Clean temp directory
        cleanTempDirectory();
        
        LogManager.logI(TAG, "[TTS] enable: model=" + ttsModel + ", outputDir=" + outputDir);
        
        // Create background task snapshot for this TTS generation
        try {
            java.util.Map<String, String> extras = new java.util.HashMap<>();
            extras.put("ttsModel", ttsModel != null ? ttsModel : "");
            extras.put("outputDir", outputDir != null ? outputDir : "");
            BackgroundTask task = BackgroundTaskManager.getInstance().createTask(
                    BackgroundTask.TaskType.TTS_GENERATION,
                    "TTS generation",
                    false,
                    extras
            );
            ttsTaskId = task.getId();
            LogManager.logI(TAG, "[TASK][TTS] Created TTS background task, id=" + ttsTaskId);
        } catch (Exception e) {
            LogManager.logE(TAG, "[TASK][TTS] Failed to create TTS background task: " + e.getMessage(), e);
            ttsTaskId = null;
        }
    }
    
    public void processToken(String token) {
        if (!ttsEnabled || shouldStop.get()) return;
        if (token == null || token.isEmpty()) return;
        
        // Filter simple tags (not Markdown - that's done on complete sentences)
        String filteredToken = token.replaceAll("</?think>", "");
        if (filteredToken.isEmpty()) return;
        
        // Append to buffer WITHOUT Markdown filtering
        sentenceBuffer.append(filteredToken);
        String currentText = sentenceBuffer.toString();
        
        boolean shouldBreak = false;
        String sentenceToQueue = null;
        
        Matcher matcher = SENTENCE_PATTERN.matcher(currentText);
        if (matcher.find() && matcher.start() == 0) {
            sentenceToQueue = matcher.group();
            sentenceBuffer.delete(0, matcher.end());
            shouldBreak = true;
        }
        
        if (!shouldBreak && currentText.length() >= MAX_SENTENCE_LENGTH) {
            sentenceToQueue = currentText;
            sentenceBuffer.setLength(0);
            shouldBreak = true;
        }
        
        if (shouldBreak && sentenceToQueue != null && !sentenceToQueue.trim().isEmpty()) {
            // Filter Markdown on complete sentence
            String cleanedSentence = filterMarkdown(sentenceToQueue.trim());
            if (cleanedSentence.isEmpty()) return;
            
            try {
                sentenceQueue.put(cleanedSentence);
                LogManager.logI(TAG, "[TTS] Queued: " + cleanedSentence);
                
                // Start threads on first sentence
                if (ttsThreadStarted.compareAndSet(false, true)) {
                    LogManager.logI(TAG, "[TTS] First sentence, starting threads");
                    
                    startTtsConsumerThread();
                    if (autoPlay) {
                        startPlaybackThread();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public void complete() {
        if (!ttsEnabled) {
            LogManager.logW(TAG, "[TTS] complete() called but TTS not enabled");
            return;
        }
        
        LogManager.logI(TAG, "[TTS] ========== complete() ENTER ==========");
        
        // Process remaining buffer
        String remaining = sentenceBuffer.toString().trim();
        LogManager.logI(TAG, "[TTS] Remaining buffer length: " + remaining.length());
        
        if (!remaining.isEmpty()) {
            String cleanedSentence = filterMarkdown(remaining);
            LogManager.logI(TAG, "[TTS] After filter: '" + cleanedSentence + "' (length=" + cleanedSentence.length() + ")");
            
            if (!cleanedSentence.isEmpty()) {
                try {
                    sentenceQueue.put(cleanedSentence);
                    LogManager.logI(TAG, "[TTS] Queued final: " + cleanedSentence);
                    
                    // CRITICAL: Start threads if not started yet (for very short text)
                    // This handles the race condition where complete() is called before any sentence break
                    if (ttsThreadStarted.compareAndSet(false, true)) {
                        LogManager.logI(TAG, "[TTS] Starting threads in complete() for short text");
                        startTtsConsumerThread();
                        if (autoPlay) {
                            startPlaybackThread();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                LogManager.logW(TAG, "[TTS] Final buffer filtered to empty, skipping queue");
            }
        } else {
            LogManager.logI(TAG, "[TTS] No remaining buffer to process");
        }

        // If no sentences were ever queued and no TTS threads were started,
        // there will be no consumer thread to trigger callbacks. Handle this
        // as a fast-path no-op so that UI state (e.g. buttons) can be reset.
        if (!ttsThreadStarted.get() && sentenceQueue.isEmpty()) {
            LogManager.logW(TAG, "[TTS] No sentences queued and no TTS threads started, reporting no-op TTS");
            // Finalize background task to avoid leaving a stale PENDING TTS_GENERATION
            // entry in BackgroundTaskManager which would be treated as an active TTS
            // task during UI resync.
            if (ttsTaskId != null) {
                try {
                    BackgroundTaskManager.getInstance().updateTask(
                            ttsTaskId,
                            BackgroundTask.TaskState.FAILED,
                            0,
                            "TTS generation skipped: no content"
                    );
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TASK][TTS] Failed to finalize no-op TTS task: " + e.getMessage(), e);
                } finally {
                    ttsTaskId = null;
                }
            }
            if (callback != null) {
                callback.onError("No TTS content to synthesize");
            }
            return;
        }

        // CRITICAL: ALWAYS set completion flag, even if buffer was empty or filtered
        sentenceQueueCompleted.set(true);
        LogManager.logI(TAG, "[TTS] ✅ complete() finished, sentenceQueueCompleted=true");
    }
    
    private void startTtsConsumerThread() {
        consumerThread = new Thread(() -> {
            LogManager.logI(TAG, "[TTS] Consumer thread started");
            if (ttsTaskId != null) {
                try {
                    BackgroundTaskManager.getInstance().updateTask(
                            ttsTaskId,
                            BackgroundTask.TaskState.RUNNING,
                            0,
                            "TTS generation started"
                    );
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TASK][TTS] Failed to update TTS task to RUNNING: " + e.getMessage(), e);
                }
            }
            try {
                while (!shouldStop.get()) {
                    String sentence = sentenceQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (sentence == null) {
                        if (sentenceQueueCompleted.get() && sentenceQueue.isEmpty()) {
                            LogManager.logI(TAG, "[TTS] Queue completed and empty, exiting consumer loop");
                            break;
                        }
                        continue;
                    }
                    
                    LogManager.logI(TAG, "[TTS] Processing sentence: " + sentence);
                    String audioPath = generateTtsForSentence(sentence);
                    if (audioPath != null) {
                        generatedAudioFiles.add(audioPath);
                        // Removed: per-file generation logs (too verbose)
                        
                        // Add to playback queue for streaming playback
                        if (autoPlay) {
                            try {
                                audioPlaybackQueue.put(audioPath);
                            } catch (InterruptedException e) {
                                LogManager.logE(TAG, "[TTS] Failed to add to playback queue", e);
                            }
                        }
                    } else {
                        LogManager.logE(TAG, "[TTS] ERROR: generateTtsForSentence returned null for: " + sentence);
                    }
                }
                
                // Signal playback thread that generation is complete
                LogManager.logI(TAG, "[TTS] ========== CONSUMER LOOP FINISHED ==========");
                LogManager.logI(TAG, "[TTS] autoPlay=" + autoPlay);
                LogManager.logI(TAG, "[TTS] generatedAudioFiles.size()=" + generatedAudioFiles.size());
                
                if (autoPlay) {
                    try {
                        // Add END marker to playback queue
                        LogManager.logI(TAG, "[TTS] Putting END marker to playback queue...");
                        audioPlaybackQueue.put("__END_MARKER__");
                        LogManager.logI(TAG, "[TTS] ✅ END marker added to playback queue");
                    } catch (InterruptedException e) {
                        LogManager.logE(TAG, "[TTS] ❌ Failed to add END marker", e);
                    }
                } else {
                    LogManager.logI(TAG, "[TTS] autoPlay=false, skipping END marker");
                }
                
                // Merge audio files immediately after generation
                LogManager.logI(TAG, "[TTS] ========== MERGE START: " + generatedAudioFiles.size() + " files ==========");
                
                if (!shouldStop.get() && !generatedAudioFiles.isEmpty()) {
                    mergedAudioPath = mergeAudioFiles(generatedAudioFiles, outputDir);
                    if (mergedAudioPath != null) {
                        LogManager.logI(TAG, "[TTS] ========== MERGE SUCCESS ==========");
                        LogManager.logI(TAG, "[TTS] Merged file: " + mergedAudioPath);

                        // Persist assistant audio line inside the last assistant message
                        // so that the TTS result is available even if the UI Fragment has
                        // been destroyed.
                        try {
                            if (outputDir != null && !outputDir.isEmpty()) {
                                float durationSeconds = AudioService.getAudioDuration(mergedAudioPath);
                                ChatHistoryManager.attachAssistantAudioToLastMessage(
                                        context,
                                        outputDir,
                                        mergedAudioPath,
                                        durationSeconds
                                );
                                LogManager.logI(TAG, "[HISTORY][TTS] Attached assistant audio to last assistant message in chat history");
                            } else {
                                LogManager.logW(TAG, "[HISTORY][TTS] outputDir is empty, skip attaching assistant audio message");
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[HISTORY][TTS] Failed to attach assistant audio message: " + e.getMessage(), e);
                        }

                        if (callback != null) {
                            if (autoPlay) {
                                CountDownLatch latch = playbackDoneLatch;
                                if (latch != null) {
                                    try {
                                        LogManager.logI(TAG, "[TTS] Waiting for playback thread to finish before onTtsComplete (autoPlay=true)");
                                        // Wait up to 35 seconds for playback to finish
                                        latch.await(35, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        LogManager.logW(TAG, "[TTS] Interrupted while waiting for playback to finish", e);
                                    }
                                }
                                if (!shouldStop.get()) {
                                    if (mergedAudioPath != null) {
                                        LogManager.logI(TAG, "[TTS] Auto-play flow complete, calling onTtsComplete (playbackComplete=true)");
                                        callback.onTtsComplete(mergedAudioPath, true);
                                    } else {
                                        LogManager.logE(TAG, "[TTS] mergedAudioPath is null after playback, reporting error instead of success");
                                        callback.onError("TTS merge failed or playback error (mergedAudioPath is null)");
                                    }
                                } else {
                                    LogManager.logW(TAG, "[TTS] shouldStop=true after playback wait, skip onTtsComplete");
                                }
                            } else {
                                // If NOT auto-playing, TTS is complete now, callback immediately
                                LogManager.logI(TAG, "[TTS] No auto-play, calling onTtsComplete");
                                callback.onTtsComplete(mergedAudioPath, false);
                            }
                        }

                        // Update background task as COMPLETED after successful merge
                        if (ttsTaskId != null && !shouldStop.get()) {
                            try {
                                BackgroundTaskManager.getInstance().updateTask(
                                        ttsTaskId,
                                        BackgroundTask.TaskState.COMPLETED,
                                        100,
                                        "TTS generation completed"
                                );
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task as COMPLETED: " + e.getMessage(), e);
                            } finally {
                                ttsTaskId = null;
                            }
                        }
                    } else {
                        LogManager.logE(TAG, "[TTS] ========== MERGE FAILED: returned null! ==========");
                        if (callback != null) {
                            callback.onError("Failed to merge audio files");
                        }
                        if (ttsTaskId != null && !shouldStop.get()) {
                            try {
                                BackgroundTaskManager.getInstance().updateTask(
                                        ttsTaskId,
                                        BackgroundTask.TaskState.FAILED,
                                        0,
                                        "TTS merge failed"
                                );
                            } catch (Exception e) {
                                LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task as FAILED: " + e.getMessage(), e);
                            } finally {
                                ttsTaskId = null;
                            }
                        }
                    }
                } else {
                    LogManager.logW(TAG, "[TTS] ========== MERGE SKIPPED: shouldStop=" + shouldStop.get() + ", isEmpty=" + generatedAudioFiles.isEmpty() + " ==========");
                    if (callback != null) {
                        callback.onError("TTS generation stopped or no audio generated");
                    }
                    if (ttsTaskId != null && !shouldStop.get()) {
                        try {
                            BackgroundTaskManager.getInstance().updateTask(
                                    ttsTaskId,
                                    BackgroundTask.TaskState.FAILED,
                                    0,
                                    "TTS generation stopped or no audio generated"
                            );
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task as FAILED: " + e.getMessage(), e);
                        } finally {
                            ttsTaskId = null;
                        }
                    }
                }
                
            } catch (InterruptedException e) {
                LogManager.logI(TAG, "[TTS] Consumer thread interrupted");
                if (callback != null) {
                    callback.onError("TTS consumer thread interrupted: " + e.getMessage());
                }
                if (ttsTaskId != null && !shouldStop.get()) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                ttsTaskId,
                                BackgroundTask.TaskState.FAILED,
                                0,
                                "TTS consumer thread interrupted"
                        );
                    } catch (Exception ex) {
                        LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task after interrupt: " + ex.getMessage(), ex);
                    } finally {
                        ttsTaskId = null;
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Consumer thread error", e);
                if (callback != null) {
                    callback.onError("TTS consumer thread error: " + e.getMessage());
                }
                if (ttsTaskId != null && !shouldStop.get()) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                ttsTaskId,
                                BackgroundTask.TaskState.FAILED,
                                0,
                                "TTS consumer thread error"
                        );
                    } catch (Exception ex) {
                        LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task after error: " + ex.getMessage(), ex);
                    } finally {
                        ttsTaskId = null;
                    }
                }
            }
            
            LogManager.logI(TAG, "[TTS] Consumer thread finished");
        });
        consumerThread.start();
    }
    
    private void startPlaybackThread() {
        playbackThread = new Thread(() -> {
            LogManager.logI(TAG, "[TTS] Playback thread started");
            boolean endMarkerReceived = false;
            CountDownLatch localLatch = playbackDoneLatch;
            try {
                while (!shouldStop.get()) {
                    String audioPath = audioPlaybackQueue.poll(100, TimeUnit.MILLISECONDS);
                    
                    if (audioPath == null) {
                        // Check if we should exit
                        if (endMarkerReceived) {
                            LogManager.logI(TAG, "[TTS] END marker received and queue empty, exiting");
                            break;
                        }
                        continue;
                    }
                    
                    // Check for END marker
                    if ("__END_MARKER__".equals(audioPath)) {
                        LogManager.logI(TAG, "[TTS] Received END marker, will exit after queue empty");
                        endMarkerReceived = true;
                        continue;
                    }
                    
                    // Removed: per-file playback logs (too verbose)
                    
                    // Play audio
                    // CRITICAL: Keep strong reference to prevent GC
                    final android.media.MediaPlayer mp;
                    try {
                        mp = new android.media.MediaPlayer();
                        final boolean[] playbackCompleted = {false};
                        
                        mp.setDataSource(audioPath);
                        mp.setOnCompletionListener(mediaPlayer -> {
                            playbackCompleted[0] = true;
                            mediaPlayer.release();
                            // Removed: per-file completion logs (too verbose)
                        });
                        mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                            playbackCompleted[0] = true;
                            LogManager.logE(TAG, "[TTS] Playback error: what=" + what + ", extra=" + extra);
                            mediaPlayer.release();
                            return true;
                        });
                        mp.prepare();
                        mp.start();
                        
                        // Wait for playback to finish with timeout
                        int maxWaitTime = 30000; // 30 seconds max
                        int waitedTime = 0;
                        while (!playbackCompleted[0] && waitedTime < maxWaitTime) {
                            Thread.sleep(100);
                            waitedTime += 100;
                            
                            // Check if MediaPlayer is still playing
                            try {
                                if (!mp.isPlaying() && !playbackCompleted[0]) {
                                    LogManager.logW(TAG, "[TTS] MediaPlayer stopped but completion not called, forcing completion");
                                    playbackCompleted[0] = true;
                                    mp.release();
                                }
                            } catch (IllegalStateException e) {
                                // MediaPlayer already released
                                playbackCompleted[0] = true;
                            }
                        }
                        
                        if (waitedTime >= maxWaitTime) {
                            LogManager.logE(TAG, "[TTS] Playback timeout, forcing stop");
                            try {
                                mp.stop();
                                mp.release();
                            } catch (Exception e) {
                                // Ignore
                            }
                        }
                        
                        // Note: Temp files cleaned at enable() time, not per-file
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[TTS] Failed to play audio: " + audioPath, e);
                    }
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Playback thread error", e);
                if (callback != null) {
                    callback.onError("TTS playback thread error: " + e.getMessage());
                }
                // Prevent success callback after a fatal playback error
                mergedAudioPath = null;
            }

            LogManager.logI(TAG, "[TTS] Playback thread finished");
            if (localLatch != null) {
                LogManager.logI(TAG, "[TTS] Signalling playbackDoneLatch from playback thread");
                localLatch.countDown();
            }
        });
        playbackThread.start();
    }
    
    private String generateTtsForSentence(String sentence) {
        try {
            String systemTtsName = context.getString(R.string.settings_tts_model_system);
            boolean useSystemTts = systemTtsName.equals(currentTtsModel);
            
            // Only log when TTS model changes (avoid 35+ repeated logs per session)
            if (!currentTtsModel.equals(lastLoggedTtsModel)) {
                LogManager.logI(TAG, "[TTS] TTS model switched: '" + currentTtsModel + "' (useSystem=" + useSystemTts + ")");
                lastLoggedTtsModel = currentTtsModel;
            }
            
            File tempDir = new File(context.getCacheDir(), "tts_temp");
            if (!tempDir.exists()) tempDir.mkdirs();
            
            File tempAudioFile = new File(tempDir, "tts_" + System.currentTimeMillis() + ".wav");
            
            if (useSystemTts) {
                // System TTS runs in main process (Android system service)
                if (!ensureSystemTtsLoaded()) {
                    LogManager.logE(TAG, "[TTS] Failed to load System TTS");
                    return null;
                }
                boolean success = systemTtsHandler.synthesizeToFile(sentence, tempAudioFile, 1.0f, 1.0f);
                if (success && tempAudioFile.exists()) {
                    return tempAudioFile.getAbsolutePath();
                }
            } else {
                // External TTS runs in child process via IPC
                String ttsBasePath = ConfigManager.getTtsModelPath(context);
                String modelPath = new File(ttsBasePath, currentTtsModel).getAbsolutePath();
                
                try {
                    com.example.offlineai.ipc.InferenceClient client = 
                        com.example.offlineai.ipc.InferenceClient.getInstance(context);
                    String result = client.runTts(modelPath, sentence, tempAudioFile.getAbsolutePath());
                    if (result != null && new File(result).exists()) {
                        return result;
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] External TTS IPC error", e);
                    return null;
                }
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] generateTtsForSentence error", e);
        }
        return null;
    }
    
    /**
     * Synthesize text to audio file using External TTS (MNN-based).
     * This method is called from InferenceService in the child process.
     * @param modelPath Full path to TTS model directory
     * @param text Text to synthesize
     * @param outputPath Output WAV file path
     * @return Output file path on success, null on failure
     */
    public String synthesizeExternal(String modelPath, String text, String outputPath) {
        if (text == null || text.isEmpty()) {
            LogManager.logW(TAG, "[TTS] Empty text, skip synthesis");
            return null;
        }

        // Check if model changed
        if (currentExternalModelPath != null && !currentExternalModelPath.equals(modelPath)) {
            LogManager.logI(TAG, "[TTS] Model path changed, reloading TTS model");
            releaseExternalTts();
        }

        // Load model if needed
        if (!ensureExternalTtsLoaded(modelPath)) {
            LogManager.logE(TAG, "[TTS] Failed to load TTS model: " + modelPath);
            return null;
        }

        try {
            // Call TtsService.process(text, speakerId)
            Class<?> ttsClass = externalTtsService.getClass();
            java.lang.reflect.Method processMethod = ttsClass.getMethod("process", String.class, int.class);
            Object result = processMethod.invoke(externalTtsService, text, 0);

            if (result instanceof short[]) {
                short[] audioSamples = (short[]) result;
                if (audioSamples.length > 0) {
                    // Save to WAV file
                    if (saveWavFile(outputPath, audioSamples, ttsSampleRate)) {
                        LogManager.logI(TAG, String.format("[TTS] Generated %d samples (%.2f sec @ %dHz) -> %s",
                                audioSamples.length, audioSamples.length / (float) ttsSampleRate, ttsSampleRate, outputPath));
                        return outputPath;
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Synthesis error", e);
        }

        return null;
    }

    private synchronized boolean ensureExternalTtsLoaded(String modelPath) {
        if (externalTtsLoaded && externalTtsService != null && modelPath.equals(currentExternalModelPath)) {
            return true;
        }
        if (externalTtsLoadFailed && modelPath.equals(currentExternalModelPath)) {
            return false;
        }

        if (externalTtsLoading.compareAndSet(false, true)) {
            try {
                LogManager.logI(TAG, "[TTS] ========== Loading External TTS Model ==========");
                LogManager.logI(TAG, "[TTS] Model path: " + modelPath);

                File ttsModelDir = new File(modelPath);
                if (!ttsModelDir.exists() || !ttsModelDir.isDirectory()) {
                    LogManager.logE(TAG, "[TTS] Model directory does NOT exist: " + ttsModelDir);
                    externalTtsLoadFailed = true;
                    currentExternalModelPath = modelPath;
                    return false;
                }

                // Validate model has config.json (C++ layer reads it to locate all files)
                // Do NOT modify config.json - let C++ use its original relative paths
                File configFile = new File(ttsModelDir, "config.json");
                if (!configFile.exists()) {
                    LogManager.logE(TAG, "[TTS] No config.json found in: " + ttsModelDir);
                    externalTtsLoadFailed = true;
                    currentExternalModelPath = modelPath;
                    return false;
                }

                // Read sample_rate only (read-only, never write back)
                try {
                    String configContent = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
                    org.json.JSONObject config = new org.json.JSONObject(configContent);
                    if (config.has("sample_rate")) {
                        ttsSampleRate = config.getInt("sample_rate");
                        LogManager.logI(TAG, "[TTS] Read sample_rate from config: " + ttsSampleRate + " Hz");
                    }
                } catch (Exception e) {
                    LogManager.logW(TAG, "[TTS] Failed to read sample_rate from config.json", e);
                }

                // Fix Supertonic model layout: C++ expects unicode_indexer.json under mnn_models/
                // but some model packages place it in the root directory. Copy once if needed.
                File mnnModelsDir = new File(ttsModelDir, "mnn_models");
                File indexerInMnnModels = new File(mnnModelsDir, "unicode_indexer.json");
                File indexerInRoot = new File(ttsModelDir, "unicode_indexer.json");
                if (!indexerInMnnModels.exists() && indexerInRoot.exists() && mnnModelsDir.exists()) {
                    try {
                        java.nio.file.Files.copy(indexerInRoot.toPath(), indexerInMnnModels.toPath());
                        LogManager.logI(TAG, "[TTS] Copied unicode_indexer.json to mnn_models/ for C++ compatibility");
                    } catch (Exception e) {
                        LogManager.logW(TAG, "[TTS] Failed to copy unicode_indexer.json", e);
                    }
                }

                LogManager.logI(TAG, "[TTS] Model root: " + ttsModelDir.getAbsolutePath());

                // Create TtsService instance
                Class<?> ttsClass = Class.forName("com.taobao.meta.avatar.tts.TtsService");
                externalTtsService = ttsClass.getDeclaredConstructor().newInstance();

                // Initialize TtsService via reflection
                try {
                    java.lang.reflect.Field nativeField = ttsClass.getDeclaredField("ttsServiceNative");
                    nativeField.setAccessible(true);
                    long nativePtr = nativeField.getLong(externalTtsService);

                    java.lang.reflect.Method loadMethod = ttsClass.getDeclaredMethod(
                            "nativeLoadResourcesFromFile", long.class, String.class, String.class, String.class);
                    loadMethod.setAccessible(true);
                    // Pass empty strings to let C++ layer use config.json defaults (this is the working version)
                    boolean result = (Boolean) loadMethod.invoke(externalTtsService,
                            nativePtr, ttsModelDir.getAbsolutePath(), "", "");

                    if (!result) {
                        LogManager.logE(TAG, "[TTS] nativeLoadResourcesFromFile returned false");
                        externalTtsLoadFailed = true;
                        currentExternalModelPath = modelPath;
                        return false;
                    }

                    java.lang.reflect.Field loadedField = ttsClass.getDeclaredField("isLoaded");
                    loadedField.setAccessible(true);
                    loadedField.setBoolean(externalTtsService, true);

                    LogManager.logI(TAG, "[TTS] TtsService initialized successfully");

                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] Failed to initialize TtsService via reflection", e);
                    externalTtsLoadFailed = true;
                    currentExternalModelPath = modelPath;
                    return false;
                }

                externalTtsLoaded = true;
                externalTtsLoadFailed = false;
                currentExternalModelPath = modelPath;
                LogManager.logI(TAG, "[TTS] ========== External TTS Loaded Successfully ==========");
                return true;

            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Exception during External TTS load", e);
                externalTtsLoadFailed = true;
                currentExternalModelPath = modelPath;
                return false;
            } finally {
                externalTtsLoading.set(false);
            }
        } else {
            // Wait for loading to complete
            while (externalTtsLoading.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return externalTtsLoaded && modelPath.equals(currentExternalModelPath);
        }
    }

    private void releaseExternalTts() {
        if (externalTtsService != null) {
            try {
                Class<?> ttsClass = externalTtsService.getClass();
                java.lang.reflect.Method destroyMethod = ttsClass.getDeclaredMethod("destroy");
                destroyMethod.invoke(externalTtsService);
                LogManager.logI(TAG, "[TTS] External TTS model released");
            } catch (Exception e) {
                LogManager.logW(TAG, "[TTS] Failed to destroy External TTS", e);
            }
            externalTtsService = null;
        }
        externalTtsLoaded = false;
        externalTtsLoadFailed = false;
        currentExternalModelPath = null;
    }

    private boolean saveWavFile(String filePath, short[] samples, int sampleRate) {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            int channels = 1;
            int bitsPerSample = 16;
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataSize = samples.length * 2;

            // WAV header
            fos.write("RIFF".getBytes());
            fos.write(intToBytes(36 + dataSize));
            fos.write("WAVE".getBytes());

            // fmt chunk
            fos.write("fmt ".getBytes());
            fos.write(intToBytes(16));
            fos.write(shortToBytes((short) 1));
            fos.write(shortToBytes((short) channels));
            fos.write(intToBytes(sampleRate));
            fos.write(intToBytes(byteRate));
            fos.write(shortToBytes((short) blockAlign));
            fos.write(shortToBytes((short) bitsPerSample));

            // data chunk
            fos.write("data".getBytes());
            fos.write(intToBytes(dataSize));
            for (short sample : samples) {
                fos.write(shortToBytes(sample));
            }

            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Failed to save WAV file", e);
            return false;
        }
    }
    
    private boolean ensureSystemTtsLoaded() {
        if (systemTtsLoaded) return true;
        
        try {
            LogManager.logI(TAG, "[TTS] Loading System TTS...");
            systemTtsHandler = new SystemTtsHandler(context);
            systemTtsLoaded = systemTtsHandler.initialize();
            
            if (systemTtsLoaded) {
                LogManager.logI(TAG, "[TTS] System TTS loaded successfully");
            }
            return systemTtsLoaded;
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Failed to load System TTS", e);
            return false;
        }
    }
    
    // NOTE: saveWavFile() removed - External TTS WAV saving now handled in child process
    // See ExternalTtsHandler.saveWavFile() in ipc package
    
    private String mergeAudioFiles(List<String> audioPaths, String outputDir) {
        if (audioPaths.isEmpty()) return null;
        
        try {
            LogManager.logI(TAG, "[TTS] mergeAudioFiles: total files=" + audioPaths.size());
            for (int i = 0; i < audioPaths.size(); i++) {
                LogManager.logI(TAG, "[TTS]   [" + i + "] " + audioPaths.get(i));
            }
            
            if (audioPaths.size() == 1) {
                String singlePath = audioPaths.get(0);
                String timestamp = String.valueOf(System.currentTimeMillis());
                File outputFile = new File(outputDir, "audio_" + timestamp + "_ai.wav");
                
                LogManager.logI(TAG, "[TTS] Single file, copying to: " + outputFile.getAbsolutePath());
                
                // Copy single file to output
                try (FileInputStream fis = new FileInputStream(singlePath);
                     FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                
                // Delete temp file if not auto-playing
                if (!autoPlay) {
                    new File(singlePath).delete();
                }
                
                return outputFile.getAbsolutePath();
            }
            
            // Merge multiple files
            String timestamp = String.valueOf(System.currentTimeMillis());
            File outputFile = new File(outputDir, "audio_" + timestamp + "_ai.wav");
            
            LogManager.logI(TAG, "[TTS] Merging " + audioPaths.size() + " files to: " + outputFile.getAbsolutePath());
            
            // Simple concatenation (assumes same format)
            long totalMergedBytes = 0;
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                boolean firstFile = true;
                int fileIndex = 0;
                for (String audioPath : audioPaths) {
                    File inputFile = new File(audioPath);
                    long fileSize = inputFile.length();
                    // Removed: per-file merge logs (too verbose, 35+ times per session)
                    
                    try (FileInputStream fis = new FileInputStream(audioPath)) {
                        if (firstFile) {
                            // Copy entire first file (including header)
                            byte[] buffer = new byte[8192];
                            int read;
                            long totalBytes = 0;
                            while ((read = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                                totalBytes += read;
                            }
                            totalMergedBytes += totalBytes;
                            if (totalBytes != fileSize) {
                                LogManager.logE(TAG, "[TTS] >>> ERROR: Size mismatch! Read " + totalBytes + " but file is " + fileSize);
                            }
                            firstFile = false;
                        } else {
                            // Skip WAV header (44 bytes) for subsequent files
                            long skipped = fis.skip(44);
                            
                            byte[] buffer = new byte[8192];
                            int read;
                            long totalBytes = 0;
                            while ((read = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                                totalBytes += read;
                            }
                            totalMergedBytes += totalBytes;
                            if (totalBytes != (fileSize - 44)) {
                                LogManager.logE(TAG, "[TTS] >>> ERROR: Size mismatch! Read " + totalBytes + " but expected " + (fileSize - 44));
                            }
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[TTS] >>> ERROR merging file [" + fileIndex + "]: " + audioPath, e);
                        throw e;
                    }
                    fileIndex++;
                }
            }
            
            long finalFileSize = outputFile.length();
            LogManager.logI(TAG, "[TTS] ========== MERGE COMPLETE ==========");
            LogManager.logI(TAG, "[TTS] Output file: " + outputFile.getAbsolutePath());
            LogManager.logI(TAG, "[TTS] Total merged bytes: " + totalMergedBytes);
            LogManager.logI(TAG, "[TTS] Final file size: " + finalFileSize);
            if (totalMergedBytes != finalFileSize) {
                LogManager.logE(TAG, "[TTS] ERROR: Final size mismatch! Merged " + totalMergedBytes + " but file is " + finalFileSize);
            }
            
            // CRITICAL: Update WAV header with correct file size
            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
                long dataSize = finalFileSize - 44;  // Total data size (excluding 44-byte header)
                long fileSize = finalFileSize - 8;   // File size for RIFF header (excluding first 8 bytes)
                
                // Update RIFF chunk size (bytes 4-7)
                raf.seek(4);
                raf.write(intToBytes((int)fileSize));
                
                // Update data chunk size (bytes 40-43)
                raf.seek(40);
                raf.write(intToBytes((int)dataSize));
                
                LogManager.logI(TAG, "[TTS] Updated WAV header: fileSize=" + fileSize + ", dataSize=" + dataSize);
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Failed to update WAV header", e);
            }
            
            // Delete temp files if not auto-playing
            if (!autoPlay) {
                for (String audioPath : audioPaths) {
                    new File(audioPath).delete();
                }
            }
            
            return outputFile.getAbsolutePath();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] Failed to merge audio files", e);
            return null;
        }
    }
    
    private void cleanTempDirectory() {
        try {
            File tempDir = new File(context.getCacheDir(), "tts_temp");
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "[TTS] Failed to clean temp directory", e);
        }
    }
    
    // /**
    //  * Manual Markdown filter - removes common Markdown syntax while preserving text content
    //  * 
    //  * Filters:
    //  * - Headers: # ## ### etc.
    //  * - Bold/Italic: **bold** *italic* __bold__ _italic_
    //  * - Strikethrough: ~~text~~
    //  * - Code: `code` and ``` blocks
    //  * - Lists: - * + 1. 2. etc.
    //  * - Quotes: > text
    //  * - Links: [text](url)
    //  * - Images: ![alt](url)
    //  * - Horizontal rules: --- *** ___
    //  * - Tables: | col1 | col2 |
    //  * 
    //  * Preserves:
    //  * - All text content and spaces
    //  * - Math expressions like 3*5 (not treated as italic)
    //  * - Normal punctuation
    //  */
    private String filterMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        try {
            String result = text;
            
            // Remove LaTeX $ symbols but KEEP formula content (for TTS readability)
            // Example: "$ x $" or "$x$" becomes " x " (TTS will read "x", not "dollar sign")
            // Block formulas: $$...$$ -> " ... "
            result = result.replaceAll("\\$\\$", " ");
            // Inline formulas: $ -> (empty, just remove the dollar sign)
            result = result.replaceAll("\\$", " ");
            
            // Remove code blocks first
            result = result.replaceAll("(?s)```[^\\n]*\\n.*?```", "");
            result = result.replaceAll("(?s)~~~[^\\n]*\\n.*?~~~", "");
            
            // Remove headers
            result = result.replaceAll("(?m)^#{1,6}\\s+", "");
            
            // Remove bold/italic (order matters: ** and __ before * and _)
            result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
            result = result.replaceAll("__([^_]+)__", "$1");
            result = result.replaceAll("(?<!\\w)\\*([^*\\s][^*]*?)\\*(?!\\w)", "$1");
            result = result.replaceAll("(?<!\\w)_([^_\\s][^_]*?)_(?!\\w)", "$1");
            
            // Remove other syntax
            result = result.replaceAll("~~([^~]+)~~", "$1");  // strikethrough
            result = result.replaceAll("`([^`]+)`", "$1");    // inline code
            
            // Remove list markers
            result = result.replaceAll("(?m)^[\\s]*[-*+]\\s+", "");
            result = result.replaceAll("(?m)^[\\s]*\\d+\\.\\s+", "");
            result = result.replaceAll("(?m)^[\\s]*\\[[ xX]\\]\\s+", "");
            
            // Remove blockquote and horizontal rules
            result = result.replaceAll("(?m)^>+\\s*", "");
            result = result.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "");
            
            // Remove links and images
            result = result.replaceAll("!\\[([^\\]]*)\\]\\([^)]+\\)", "$1");
            result = result.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
            result = result.replaceAll("\\[([^\\]]+)\\]\\[[^\\]]*\\]", "$1");
            
            // Remove table syntax
            result = result.replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "");
            result = result.replaceAll("(?m)^\\s*[|:]+[-:| ]+[|:]+\\s*$", "");
            
            // Clean up standalone symbols
            result = result.replaceAll("(?m)^\\*+\\s+", "");
            result = result.replaceAll("(?m)\\s+\\*+$", "");
            result = result.replaceAll("\\s+\\*+\\s+", " ");
            
            // Normalize whitespace
            result = result.replaceAll("[ \\t]{2,}", " ");
            result = result.replaceAll("\\n{3,}", "\n\n");
            result = result.replaceAll("(?m)^[ \\t]+", "");
            result = result.trim();
            
            // Log only if changed
            if (!result.equals(text)) {
                LogManager.logD(TAG, "[MARKDOWN] Filtered: [" + text + "] -> [" + result + "]");
            }
            
            return result;
        } catch (Exception e) {
            // Fallback to original text if filtering fails
            LogManager.logW(TAG, "[MARKDOWN_FILTER] Filtering failed, using original text", e);
            return text;
        }
    }
    
    public boolean isEnabled() {
        return ttsEnabled;
    }
    
    public void stop() {
        shouldStop.set(true);
        ttsEnabled = false;
        CountDownLatch latch = playbackDoneLatch;
        if (latch != null) {
            LogManager.logI(TAG, "[TTS] stop() signalling playbackDoneLatch to unblock waiters");
            latch.countDown();
        }
        
        // Interrupt threads
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
        
        LogManager.logI(TAG, "[TTS] stop() called");

        // Mark background task as cancelled by user
        if (ttsTaskId != null) {
            try {
                BackgroundTaskManager.getInstance().updateTask(
                        ttsTaskId,
                        BackgroundTask.TaskState.CANCELLED,
                        0,
                        "TTS generation cancelled by user"
                );
            } catch (Exception e) {
                LogManager.logE(TAG, "[TASK][TTS] Failed to finalize TTS task as CANCELLED: " + e.getMessage(), e);
            } finally {
                ttsTaskId = null;
            }
        }
    }
    
    public void release() {
        stop();
        
        // Release TTS services
        if (systemTtsHandler != null) {
            systemTtsHandler.shutdown();
            systemTtsHandler = null;
            systemTtsLoaded = false;
        }
        
        // Release External TTS resources
        releaseExternalTts();
        
        executorService.shutdown();
        LogManager.logI(TAG, "[TTS] release() called");
    }
    
    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte)(value & 0xFF),
            (byte)((value >> 8) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 24) & 0xFF)
        };
    }
    
    private byte[] shortToBytes(short value) {
        return new byte[] {
            (byte)(value & 0xFF),
            (byte)((value >> 8) & 0xFF)
        };
    }
    
    /**
     * System TTS Handler (inner class)
     */
    private static class SystemTtsHandler {
        private TextToSpeech tts;
        private final CountDownLatch initLatch = new CountDownLatch(1);
        private boolean initSuccess = false;
        
        public SystemTtsHandler(Context context) {
            tts = new TextToSpeech(context, status -> {
                initSuccess = (status == TextToSpeech.SUCCESS);
                initLatch.countDown();
            });
        }
        
        public boolean initialize() {
            try {
                initLatch.await(5, TimeUnit.SECONDS);
                if (initSuccess) {
                    tts.setLanguage(Locale.CHINESE);
                }
                return initSuccess;
            } catch (InterruptedException e) {
                return false;
            }
        }
        
        public boolean synthesizeToFile(String text, File outputFile, float speed, float pitch) {
            if (tts == null) return false;
            
            tts.setSpeechRate(speed);
            tts.setPitch(pitch);
            
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_" + System.currentTimeMillis());
            
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}
                
                @Override
                public void onDone(String utteranceId) {
                    success[0] = true;
                    latch.countDown();
                }
                
                @Override
                @SuppressWarnings("deprecation")
                public void onError(String utteranceId) {
                    latch.countDown();
                }
                
                @Override
                public void onError(String utteranceId, int errorCode) {
                    latch.countDown();
                }
            });
            
            android.os.Bundle bundle = new android.os.Bundle();
            int result = tts.synthesizeToFile(text, bundle, outputFile, params.get(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID));
            if (result == TextToSpeech.SUCCESS) {
                try {
                    latch.await(30, TimeUnit.SECONDS);
                    return success[0];
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return false;
        }
        
        public void shutdown() {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        }
    }
}
