package com.example.offlineai.api;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.example.offlineai.LogManager;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android System TTS Service
 * Behavior matches mnn-tts: process() returns short[] audio samples
 */
public class SystemTtsService {
    private static final String TAG = "SystemTtsService";
    
    private Context context;
    private TextToSpeech tts;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isSynthesizing = new AtomicBoolean(false);
    
    public SystemTtsService(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Initialize TTS engine
     * @return true if initialized successfully
     */
    public boolean initialize() {
        if (isInitialized.get()) {
            return true;
        }
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Set language to Chinese (or fallback to default)
                int result = tts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    LogManager.logW(TAG, "Chinese language not supported, using default");
                    tts.setLanguage(Locale.getDefault());
                }
                success.set(true);
                isInitialized.set(true);
                LogManager.logI(TAG, "System TTS initialized successfully");
            } else {
                LogManager.logE(TAG, "Failed to initialize System TTS: status=" + status);
            }
            latch.countDown();
        });
        
        try {
            // Wait for initialization (max 5 seconds)
            if (!latch.await(5, TimeUnit.SECONDS)) {
                LogManager.logE(TAG, "TTS initialization timeout");
                return false;
            }
        } catch (InterruptedException e) {
            LogManager.logE(TAG, "TTS initialization interrupted", e);
            return false;
        }
        
        return success.get();
    }
    
    /**
     * Synthesize text to file (directly generate WAV file)
     * @param text Text to synthesize
     * @param outputFile Output WAV file
     * @param speed Speech rate (0.5 - 2.0, 1.0 = normal)
     * @param pitch Pitch (0.5 - 2.0, 1.0 = normal)
     * @return true if synthesis succeeded
     */
    public boolean synthesizeToFile(String text, File outputFile, float speed, float pitch) {
        if (!isInitialized.get()) {
            LogManager.logE(TAG, "TTS not initialized");
            return false;
        }
        
        if (text == null || text.trim().isEmpty()) {
            LogManager.logW(TAG, "Empty text, skipping synthesis");
            return false;
        }
        
        LogManager.logI(TAG, "synthesizeToFile() called: text='" + 
            (text.length() > 50 ? text.substring(0, 50) + "..." : text) + 
            "' (" + text.length() + " chars), speed=" + speed + ", pitch=" + pitch);
        
        // Retry mechanism (Android TTS may fail on first call after initialization)
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            final int currentAttempt = attempt; // Final variable for inner class
            
            if (attempt > 1) {
                LogManager.logI(TAG, "Retry attempt " + attempt + "/" + maxRetries + " for: " + 
                    (text.length() > 20 ? text.substring(0, 20) + "..." : text));
                try {
                    Thread.sleep(100); // Short delay before retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            isSynthesizing.set(true);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicBoolean errorOccurred = new AtomicBoolean(false);
            
            // Set speech rate and pitch
            tts.setSpeechRate(speed);
            tts.setPitch(pitch);
            
            // Set utterance progress listener
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    LogManager.logI(TAG, "Synthesis started (attempt " + currentAttempt + ")");
                }
                
                @Override
                public void onDone(String utteranceId) {
                    LogManager.logI(TAG, "Synthesis completed (attempt " + currentAttempt + ")");
                    success.set(true);
                    isSynthesizing.set(false);
                    latch.countDown();
                }
                
                @Override
                public void onError(String utteranceId) {
                    LogManager.logE(TAG, "Synthesis error (attempt " + currentAttempt + "): text='" + 
                        (text.length() > 30 ? text.substring(0, 30) + "..." : text) + "'");
                    errorOccurred.set(true);
                    isSynthesizing.set(false);
                    latch.countDown();
                }
            });
            
            // Synthesize to file
            HashMap<String, String> params = new HashMap<>();
            String utteranceId = "tts_" + System.currentTimeMillis() + "_" + attempt;
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            
            LogManager.logI(TAG, "Calling synthesizeToFile() - attempt " + attempt + ", utteranceId=" + utteranceId + ", outputFile=" + outputFile.getAbsolutePath());
            
            int result = tts.synthesizeToFile(text, params, outputFile.getAbsolutePath());
            
            LogManager.logI(TAG, "synthesizeToFile() returned: " + 
                (result == TextToSpeech.SUCCESS ? "SUCCESS" : 
                 result == TextToSpeech.ERROR ? "ERROR" : 
                 "UNKNOWN(" + result + ")"));
            
            if (result != TextToSpeech.SUCCESS) {
                LogManager.logE(TAG, "Failed to start synthesis (attempt " + attempt + "): result=" + result);
                isSynthesizing.set(false);
                if (attempt < maxRetries) {
                    continue; // Retry
                }
                return false;
            }
            
            try {
                // Wait for synthesis to complete (max 30 seconds)
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    LogManager.logE(TAG, "Synthesis timeout (attempt " + attempt + ")");
                    isSynthesizing.set(false);
                    if (attempt < maxRetries) {
                        continue; // Retry
                    }
                    return false;
                }
            } catch (InterruptedException e) {
                LogManager.logE(TAG, "Synthesis interrupted (attempt " + attempt + ")", e);
                isSynthesizing.set(false);
                return false;
            }
            
            boolean result_success = success.get() && outputFile.exists();
            if (result_success) {
                LogManager.logI(TAG, "System TTS generated file: " + outputFile.getAbsolutePath() + " (" + outputFile.length() + " bytes)");
                return true; // Success!
            }
            
            // If error occurred and we have retries left, continue
            if (errorOccurred.get() && attempt < maxRetries) {
                LogManager.logW(TAG, "Synthesis failed, will retry...");
                continue;
            }
            
            // No more retries
            break;
        }
        
        LogManager.logE(TAG, "Synthesis failed after " + maxRetries + " attempts");
        return false;
    }
    
    /**
     * Stop current synthesis
     */
    public void stop() {
        if (tts != null && isSynthesizing.get()) {
            tts.stop();
            isSynthesizing.set(false);
            LogManager.logI(TAG, "Synthesis stopped");
        }
    }
    
    /**
     * Release TTS resources
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            isInitialized.set(false);
            LogManager.logI(TAG, "System TTS shutdown");
        }
    }
    
    /**
     * Check if TTS is currently synthesizing
     */
    public boolean isSynthesizing() {
        return isSynthesizing.get();
    }
}
