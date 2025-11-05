package com.example.offlineai.api;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;
import com.example.offlineai.R;

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
    private final BlockingQueue<String> sentenceQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean sentenceQueueCompleted = new AtomicBoolean(false);
    private final AtomicBoolean ttsThreadStarted = new AtomicBoolean(false);
    private volatile boolean ttsEnabled = false;
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // TTS parameters
    private String currentTtsModel = null;
    private String outputDir = null;
    private boolean autoPlay = false;
    private TtsCallback callback = null;
    
    // TTS services
    private Object externalTtsService = null;
    private boolean externalTtsLoaded = false;
    private boolean externalTtsLoadFailed = false;
    private final AtomicBoolean externalTtsLoading = new AtomicBoolean(false);
    private SystemTtsHandler systemTtsHandler = null;
    private boolean systemTtsLoaded = false;
    private int ttsSampleRate = 44100;  // Default 44.1kHz, read from config.json
    
    // Audio playback
    private final BlockingQueue<String> audioPlaybackQueue = new LinkedBlockingQueue<>();
    private final List<String> generatedAudioFiles = new CopyOnWriteArrayList<>();
    private volatile String mergedAudioPath = null;  // Store merged audio path for callback
    
    // Threads
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile Thread consumerThread = null;
    private volatile Thread playbackThread = null;
    
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
        
        // Clean temp directory
        cleanTempDirectory();
        
        LogManager.logI(TAG, "[TTS] enable: model=" + ttsModel + ", outputDir=" + outputDir);
    }
    
    public void processToken(String token) {
        if (!ttsEnabled || shouldStop.get()) return;
        if (token == null || token.isEmpty()) return;
        
        // Filter think tags (performance tags filtered by RAG layer)
        String filteredToken = token.replaceAll("</?think>", "");
        
        // Filter Markdown syntax (### ** * etc.)
        filteredToken = filterMarkdown(filteredToken);
        
        if (filteredToken.isEmpty()) return;
        
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
            try {
                sentenceQueue.put(sentenceToQueue.trim());
                LogManager.logI(TAG, "[TTS] Queued sentence: " + sentenceToQueue.trim());
                
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
        if (!ttsEnabled) return;
        
        // Process remaining buffer
        String remaining = sentenceBuffer.toString().trim();
        if (!remaining.isEmpty()) {
            try {
                sentenceQueue.put(remaining);
                LogManager.logI(TAG, "[TTS] Queued final: " + remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        sentenceQueueCompleted.set(true);
        LogManager.logI(TAG, "[TTS] complete() called");
    }
    
    private void startTtsConsumerThread() {
        consumerThread = new Thread(() -> {
            LogManager.logI(TAG, "[TTS] Consumer thread started");
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
                        LogManager.logI(TAG, "[TTS] Generated [" + generatedAudioFiles.size() + "]: " + audioPath);
                        
                        // Add to playback queue for streaming playback
                        if (autoPlay) {
                            try {
                                audioPlaybackQueue.put(audioPath);
                                LogManager.logI(TAG, "[TTS] Added to playback queue [" + generatedAudioFiles.size() + "]");
                            } catch (InterruptedException e) {
                                LogManager.logE(TAG, "[TTS] Failed to add to playback queue", e);
                            }
                        }
                    } else {
                        LogManager.logE(TAG, "[TTS] ERROR: generateTtsForSentence returned null for: " + sentence);
                    }
                }
                
                // Signal playback thread that generation is complete
                if (autoPlay) {
                    try {
                        // Add END marker to playback queue
                        audioPlaybackQueue.put("__END_MARKER__");
                        LogManager.logI(TAG, "[TTS] Added END marker to playback queue");
                    } catch (InterruptedException e) {
                        LogManager.logE(TAG, "[TTS] Failed to add END marker", e);
                    }
                }
                
                // Merge audio files immediately after generation
                LogManager.logI(TAG, "[TTS] ========== MERGE START ==========");
                LogManager.logI(TAG, "[TTS] Total generated files: " + generatedAudioFiles.size());
                for (int i = 0; i < generatedAudioFiles.size(); i++) {
                    LogManager.logI(TAG, "[TTS]   File[" + i + "]: " + generatedAudioFiles.get(i));
                }
                
                if (!shouldStop.get() && !generatedAudioFiles.isEmpty()) {
                    mergedAudioPath = mergeAudioFiles(generatedAudioFiles, outputDir);
                    if (mergedAudioPath != null) {
                        LogManager.logI(TAG, "[TTS] ========== MERGE SUCCESS ==========");
                        LogManager.logI(TAG, "[TTS] Merged file: " + mergedAudioPath);
                        
                        // If NOT auto-playing, TTS is complete now, callback immediately
                        if (!autoPlay && callback != null) {
                            LogManager.logI(TAG, "[TTS] No auto-play, calling onTtsComplete");
                            callback.onTtsComplete(mergedAudioPath, false);
                        }
                        // If auto-playing, playback thread will call onTtsComplete when done
                    } else {
                        LogManager.logE(TAG, "[TTS] ========== MERGE FAILED: returned null! ==========");
                        if (callback != null) {
                            callback.onError("Failed to merge audio files");
                        }
                    }
                } else {
                    LogManager.logW(TAG, "[TTS] ========== MERGE SKIPPED: shouldStop=" + shouldStop.get() + ", isEmpty=" + generatedAudioFiles.isEmpty() + " ==========");
                    if (callback != null) {
                        callback.onError("TTS generation stopped or no audio generated");
                    }
                }
                
            } catch (InterruptedException e) {
                LogManager.logI(TAG, "[TTS] Consumer thread interrupted");
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] Consumer thread error", e);
            }
            
            LogManager.logI(TAG, "[TTS] Consumer thread finished");
        });
        consumerThread.start();
    }
    
    private void startPlaybackThread() {
        playbackThread = new Thread(() -> {
            LogManager.logI(TAG, "[TTS] Playback thread started");
            boolean endMarkerReceived = false;
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
                    
                    LogManager.logI(TAG, "[TTS] Got audio from queue: " + audioPath);
                    
                    // Play audio
                    LogManager.logI(TAG, "[TTS] Playing audio: " + audioPath);
                    // CRITICAL: Keep strong reference to prevent GC
                    final android.media.MediaPlayer mp;
                    try {
                        mp = new android.media.MediaPlayer();
                        final boolean[] playbackCompleted = {false};
                        
                        mp.setDataSource(audioPath);
                        mp.setOnCompletionListener(mediaPlayer -> {
                            playbackCompleted[0] = true;
                            mediaPlayer.release();
                            LogManager.logI(TAG, "[TTS] Audio playback completed: " + audioPath);
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
            }
            
            LogManager.logI(TAG, "[TTS] Playback thread finished");
            
            // Callback with playback complete
            if (callback != null && mergedAudioPath != null) {
                LogManager.logI(TAG, "[TTS] Playback complete, calling onTtsComplete");
                callback.onTtsComplete(mergedAudioPath, true);
            } else {
                LogManager.logE(TAG, "[TTS] ERROR: callback or mergedAudioPath is null!");
            }
        });
        playbackThread.start();
    }
    
    private String generateTtsForSentence(String sentence) {
        try {
            String systemTtsName = context.getString(R.string.settings_tts_model_system);
            boolean useSystemTts = systemTtsName.equals(currentTtsModel);
            
            LogManager.logI(TAG, "[TTS] TTS type check: currentModel='" + currentTtsModel + "', systemName='" + systemTtsName + "', useSystem=" + useSystemTts);
            
            File tempDir = new File(context.getCacheDir(), "tts_temp");
            if (!tempDir.exists()) tempDir.mkdirs();
            
            File tempAudioFile = new File(tempDir, "tts_" + System.currentTimeMillis() + ".wav");
            
            boolean success = false;
            if (useSystemTts) {
                if (!ensureSystemTtsLoaded()) {
                    LogManager.logE(TAG, "[TTS] Failed to load System TTS");
                    return null;
                }
                success = systemTtsHandler.synthesizeToFile(sentence, tempAudioFile, 1.0f, 1.0f);
            } else {
                if (!ensureExternalTtsLoaded()) {
                    LogManager.logE(TAG, "[TTS] Failed to load External TTS");
                    return null;
                }
                
                try {
                    // ✅ CRITICAL: Use getMethod (not getDeclaredMethod) for public methods
                    // Reference: LocalLLMMNNHandler.java Line 2620
                    Class<?> ttsClass = externalTtsService.getClass();
                    java.lang.reflect.Method processMethod = ttsClass.getMethod("process", String.class, int.class);
                    Object result = processMethod.invoke(externalTtsService, sentence, 0);
                    
                    if (result instanceof short[]) {
                        short[] audioSamples = (short[]) result;
                        if (audioSamples.length > 0) {
                            // ✅ CRITICAL: Read sample rate from model's config.json
                            // Reference: config.json "sample_rate": 44100
                            success = saveWavFile(tempAudioFile.getAbsolutePath(), audioSamples, ttsSampleRate);
                            LogManager.logI(TAG, String.format("[TTS] Generated %d samples (%.2f sec @ %dHz)",
                                audioSamples.length, audioSamples.length / (float)ttsSampleRate, ttsSampleRate));
                        }
                    }
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] External TTS process error", e);
                    return null;
                }
            }
            
            if (success && tempAudioFile.exists()) {
                return tempAudioFile.getAbsolutePath();
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[TTS] generateTtsForSentence error", e);
        }
        return null;
    }
    
    private synchronized boolean ensureExternalTtsLoaded() {
        if (externalTtsLoaded && externalTtsService != null) return true;
        if (externalTtsLoadFailed) return false;
        
        if (externalTtsLoading.compareAndSet(false, true)) {
            try {
                LogManager.logI(TAG, "[TTS] ========== Loading External TTS Model ==========");
                LogManager.logI(TAG, "[TTS] Model selection: " + currentTtsModel);
                
                String ttsBasePath = ConfigManager.getTtsModelPath(context);
                LogManager.logI(TAG, "[TTS] TTS base path: " + ttsBasePath);
                
                File ttsModelDir = new File(ttsBasePath, currentTtsModel);
                LogManager.logI(TAG, "[TTS] Full model path: " + ttsModelDir.getAbsolutePath());
                
                if (!ttsModelDir.exists() || !ttsModelDir.isDirectory()) {
                    LogManager.logE(TAG, "[TTS] ❌ Model directory does NOT exist: " + ttsModelDir);
                    externalTtsLoadFailed = true;
                    return false;
                }
                
                // Check for .mnn files
                File[] mnnFiles = ttsModelDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mnn"));
                if (mnnFiles == null || mnnFiles.length == 0) {
                    LogManager.logE(TAG, "[TTS] ❌ No .mnn files found in: " + ttsModelDir);
                    externalTtsLoadFailed = true;
                    return false;
                }
                
                LogManager.logI(TAG, String.format("[TTS] ✅ Found %d .mnn file(s)", mnnFiles.length));
                
                // Create TtsService instance
                LogManager.logI(TAG, "[TTS] Creating TtsService...");
                Class<?> ttsClass = Class.forName("com.taobao.meta.avatar.tts.TtsService");
                externalTtsService = ttsClass.getDeclaredConstructor().newInstance();
                
                // Build cache path: /cache/mnn/<model_name>/tts/
                String modelName = ttsModelDir.getName();
                File cacheDir = new File(context.getCacheDir(), "mnn/" + modelName + "/tts");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                String cachePath = cacheDir.getAbsolutePath();
                LogManager.logI(TAG, "[TTS] Cache path: " + cachePath);
                
                // ✅ CRITICAL: Update config.json with ABSOLUTE cache path
                // Reference: LocalLLMMNNHandler.java Line 2163-2192
                File configFile = new File(ttsModelDir, "config.json");
                if (configFile.exists()) {
                    try {
                        String absoluteCachePath = cacheDir.getAbsolutePath();
                        LogManager.logI(TAG, "[TTS] Using absolute cache path: " + absoluteCachePath);
                        
                        // Read original config
                        String configContent = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));
                        org.json.JSONObject config = new org.json.JSONObject(configContent);
                        
                        // Set absolute path
                        config.put("cache_folder", absoluteCachePath);
                        
                        // Read sample_rate from config
                        if (config.has("sample_rate")) {
                            ttsSampleRate = config.getInt("sample_rate");
                            LogManager.logI(TAG, "[TTS] Read sample_rate from config: " + ttsSampleRate + " Hz");
                        } else {
                            LogManager.logW(TAG, "[TTS] sample_rate not found in config, using default: " + ttsSampleRate + " Hz");
                        }
                        
                        // Write back with proper formatting
                        java.nio.file.Files.write(configFile.toPath(), 
                            config.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        LogManager.logI(TAG, "[TTS] Updated config.json: cache_folder=" + absoluteCachePath);
                    } catch (Exception e) {
                        LogManager.logW(TAG, "[TTS] Failed to update config.json, cache may not work", e);
                    }
                } else {
                    LogManager.logW(TAG, "[TTS] config.json not found, cache configuration skipped");
                }
                
                LogManager.logI(TAG, "[TTS] Initializing TTS service with model dir: " + ttsModelDir.getAbsolutePath());
                
                // ✅ CRITICAL: Use reflection to call nativeLoadResourcesFromFile directly
                // Reference: LocalLLMMNNHandler.java Line 2196-2227
                try {
                    // Get native pointer via reflection
                    java.lang.reflect.Field nativeField = ttsClass.getDeclaredField("ttsServiceNative");
                    nativeField.setAccessible(true);
                    long nativePtr = nativeField.getLong(externalTtsService);
                    
                    // Call nativeLoadResourcesFromFile via reflection
                    java.lang.reflect.Method loadMethod = ttsClass.getDeclaredMethod(
                        "nativeLoadResourcesFromFile", long.class, String.class, String.class, String.class);
                    loadMethod.setAccessible(true);
                    boolean result = (Boolean) loadMethod.invoke(externalTtsService, 
                        nativePtr, ttsModelDir.getAbsolutePath(), "", "");
                    
                    if (!result) {
                        LogManager.logE(TAG, "[TTS] ❌ nativeLoadResourcesFromFile returned false");
                        externalTtsLoadFailed = true;
                        return false;
                    }
                    
                    // Set isLoaded flag via reflection
                    java.lang.reflect.Field loadedField = ttsClass.getDeclaredField("isLoaded");
                    loadedField.setAccessible(true);
                    loadedField.setBoolean(externalTtsService, true);
                    
                    LogManager.logI(TAG, "[TTS] ✅ TtsService initialized successfully");
                    
                } catch (Exception e) {
                    LogManager.logE(TAG, "[TTS] ❌ Failed to initialize TtsService via reflection", e);
                    externalTtsLoadFailed = true;
                    return false;
                }
                
                externalTtsLoaded = true;
                LogManager.logI(TAG, "[TTS] ========== External TTS Loaded Successfully ==========");
                return true;
                
            } catch (Exception e) {
                LogManager.logE(TAG, "[TTS] ❌ Exception during External TTS load", e);
                externalTtsLoadFailed = true;
                return false;
            } finally {
                externalTtsLoading.set(false);
            }
        } else {
            while (externalTtsLoading.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return externalTtsLoaded;
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
    
    private boolean saveWavFile(String filePath, short[] samples, int sampleRate) {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            int numChannels = 1;
            int bitsPerSample = 16;
            int byteRate = sampleRate * numChannels * bitsPerSample / 8;
            int blockAlign = numChannels * bitsPerSample / 8;
            int dataSize = samples.length * 2;
            int fileSize = 36 + dataSize;
            
            // RIFF header
            fos.write("RIFF".getBytes());
            fos.write(intToBytes(fileSize));
            fos.write("WAVE".getBytes());
            
            // fmt chunk
            fos.write("fmt ".getBytes());
            fos.write(intToBytes(16));
            fos.write(shortToBytes((short)1));
            fos.write(shortToBytes((short)numChannels));
            fos.write(intToBytes(sampleRate));
            fos.write(intToBytes(byteRate));
            fos.write(shortToBytes((short)blockAlign));
            fos.write(shortToBytes((short)bitsPerSample));
            
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
                    LogManager.logI(TAG, "[TTS] >>> Merging file [" + fileIndex + "]: " + audioPath);
                    LogManager.logI(TAG, "[TTS] >>> File size: " + fileSize + " bytes");
                    
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
                            LogManager.logI(TAG, "[TTS] >>> First file copied: " + totalBytes + " bytes (expected: " + fileSize + ")");
                            if (totalBytes != fileSize) {
                                LogManager.logE(TAG, "[TTS] >>> ERROR: Size mismatch! Read " + totalBytes + " but file is " + fileSize);
                            }
                            firstFile = false;
                        } else {
                            // Skip WAV header (44 bytes) for subsequent files
                            long skipped = fis.skip(44);
                            LogManager.logI(TAG, "[TTS] >>> Skipped header: " + skipped + " bytes");
                            
                            byte[] buffer = new byte[8192];
                            int read;
                            long totalBytes = 0;
                            while ((read = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                                totalBytes += read;
                            }
                            totalMergedBytes += totalBytes;
                            LogManager.logI(TAG, "[TTS] >>> Appended: " + totalBytes + " bytes (file size: " + fileSize + ", data: " + (fileSize - 44) + ")");
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
    
    /**
     * Filter Markdown syntax from text for TTS
     * Uses CommonMark parser to extract plain text content
     * Preserves semantic content (e.g., "C#", "3*5=15")
     * 
     * @param text Raw text with potential Markdown syntax
     * @return Plain text without Markdown formatting
     */
    private String filterMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        try {
            // Use CommonMark standard parser
            Parser parser = Parser.builder().build();
            TextContentRenderer renderer = TextContentRenderer.builder().build();
            
            // Parse and render to plain text
            String plainText = renderer.render(parser.parse(text));
            
            // CommonMark may add extra newlines, normalize them
            plainText = plainText.replaceAll("\n{3,}", "\n\n");
            
            return plainText;
        } catch (Exception e) {
            // Fallback to original text if parsing fails
            LogManager.logW(TAG, "[TTS] Markdown filtering failed, using original text", e);
            return text;
        }
    }
    
    public boolean isEnabled() {
        return ttsEnabled;
    }
    
    public void stop() {
        shouldStop.set(true);
        ttsEnabled = false;
        
        // Interrupt threads
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
        
        LogManager.logI(TAG, "[TTS] stop() called");
    }
    
    public void release() {
        stop();
        
        // Release TTS services
        if (systemTtsHandler != null) {
            systemTtsHandler.shutdown();
            systemTtsHandler = null;
            systemTtsLoaded = false;
        }
        
        if (externalTtsService != null) {
            try {
                Class<?> ttsClass = externalTtsService.getClass();
                java.lang.reflect.Method destroyMethod = ttsClass.getDeclaredMethod("destroy");
                destroyMethod.invoke(externalTtsService);
            } catch (Exception e) {
                LogManager.logW(TAG, "[TTS] Failed to destroy External TTS", e);
            }
            externalTtsService = null;
            externalTtsLoaded = false;
        }
        
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
