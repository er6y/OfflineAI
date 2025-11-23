package com.example.offlineai.ipc;

import android.content.Context;
import com.k2fsa.sherpa.mnn.*;
import org.json.JSONObject;
import org.json.JSONException;
import com.example.offlineai.LogManager;
import com.example.offlineai.RuntimeConfigHolder;
import com.k2fsa.sherpa.mnn.EndpointConfig;
import com.k2fsa.sherpa.mnn.EndpointRule;
import com.k2fsa.sherpa.mnn.FeatureConfig;
import com.k2fsa.sherpa.mnn.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.mnn.OnlineLMConfig;
import com.k2fsa.sherpa.mnn.OnlineModelConfig;
import com.k2fsa.sherpa.mnn.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.mnn.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.mnn.OnlineRecognizer;
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig;
import com.k2fsa.sherpa.mnn.OnlineRecognizerResult;
import com.k2fsa.sherpa.mnn.OnlineStream;
import com.k2fsa.sherpa.mnn.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.mnn.OnlineZipformer2CtcModelConfig;
import com.k2fsa.sherpa.mnn.WaveData;
import com.k2fsa.sherpa.mnn.WaveReader;

import java.io.File;

/**
 * ASR (Automatic Speech Recognition) Adapter
 * 
 * Unified ASR interface using Sherpa-MNN framework
 * Decoupled from LLM inference engines
 * 
 * Features:
 * - Lazy loading of ASR models
 * - Support for streaming/offline recognition
 * - Model caching and reuse
 * - Automatic resource management
 * 
 * Supported model types:
 * - Transducer (Zipformer)
 * - Paraformer
 * - NeMo CTC
 * 
 * @author OfflineAI Team
 * @version 1.0
 */
public class AsrAdapter {
    private static final String TAG = "AsrAdapter";
    
    // Singleton instance
    private static volatile AsrAdapter instance;
    
    // Context reference
    private final Context context;
    
    // Sherpa-MNN recognizer
    private OnlineRecognizer asrRecognizer;
    private String currentAsrModel;
    private String currentAsrBasePath;
    
    /**
     * Private constructor for singleton
     */
    private AsrAdapter(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Get singleton instance
     */
    public static AsrAdapter getInstance(Context context) {
        if (instance == null) {
            synchronized (AsrAdapter.class) {
                if (instance == null) {
                    instance = new AsrAdapter(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Load ASR model (lazy loading)
     * 
     * @param modelName ASR model name from settings
     * @throws Exception if model loading fails
     */
    public synchronized void loadAsrModel(String modelName) throws Exception {
        // Resolve ASR model base path from RuntimeConfig snapshot
        String asrBasePath = null;
        com.example.offlineai.ipc.RuntimeConfig cfg = RuntimeConfigHolder.get();
        if (cfg != null && cfg.asrModelBasePath != null && !cfg.asrModelBasePath.isEmpty()) {
            asrBasePath = cfg.asrModelBasePath;
        }

        if (asrBasePath == null) {
            throw new Exception("RuntimeConfig.asrModelBasePath is empty. Please configure ASR model path in the main process.");
        }

        // Check if already loaded with same model and base path
        if (asrRecognizer != null && modelName.equals(currentAsrModel)
                && asrBasePath.equals(currentAsrBasePath)) {
            LogManager.logI(TAG, "[ASR] Model already loaded: " + modelName + " (basePath=" + asrBasePath + ")");
            return;
        }

        // If model name is the same but base path changed, log and reload
        if (asrRecognizer != null && modelName.equals(currentAsrModel)
                && !asrBasePath.equals(currentAsrBasePath)) {
            LogManager.logI(TAG, "[ASR] Base path changed from " + currentAsrBasePath + " to " + asrBasePath + ", reloading ASR model");
        }

        LogManager.logI(TAG, "[ASR] Loading model: " + modelName);
        
        try {
            // Release old recognizer
            if (asrRecognizer != null) {
                asrRecognizer.release();
                asrRecognizer = null;
            }
            
            String modelDir = new File(asrBasePath, modelName).getAbsolutePath();
            
            // Check model directory exists
            File modelDirFile = new File(modelDir);
            if (!modelDirFile.exists() || !modelDirFile.isDirectory()) {
                throw new Exception("ASR model directory not found: " + modelDir);
            }
            
            LogManager.logI(TAG, "[ASR] Model directory: " + modelDir);
            
            // Build sherpa-mnn config
            OnlineRecognizerConfig config = buildSherpaMnnConfig(modelDir);
            
            // Create recognizer using sherpa-mnn Kotlin API
            asrRecognizer = new OnlineRecognizer(null, config);
            
            currentAsrModel = modelName;
            currentAsrBasePath = asrBasePath;
            
            LogManager.logI(TAG, "[ASR] ✅ Model loaded successfully: " + modelName);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[ASR] ❌ Failed to load model: " + modelName, e);
            asrRecognizer = null;
            currentAsrModel = null;
            throw new Exception("ASR model load failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build sherpa-mnn configuration
     * CRITICAL: Use Kotlin constructor (like sherpa-mnn example) for JNI compatibility
     * Strategy (like ChatMNN):
     * 1. Try to parse config.json
     * 2. Fallback to smart file detection based on directory name
     * 3. Final fallback to regex pattern matching
     */
    private OnlineRecognizerConfig buildSherpaMnnConfig(String modelDir) {
        // Feature config (use constructor with default values)
        FeatureConfig featConfig = new FeatureConfig(16000, 80);
        
        // Try to get model config from config.json or fallback
        ModelFilePaths modelPaths = getModelFilePaths(modelDir);
        
        // Transducer model config
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
            modelPaths.encoder,
            modelPaths.decoder,
            modelPaths.joiner
        );
        
        // Model config
        OnlineModelConfig modelConfig = new OnlineModelConfig(
            transducer,
            new OnlineParaformerModelConfig("", ""),
            new OnlineZipformer2CtcModelConfig(""),
            new OnlineNeMoCtcModelConfig(""),
            modelPaths.tokens,
            2,      // numThreads
            false,  // debug
            "cpu",  // provider
            "",     // modelType
            "",     // modelingUnit
            ""      // bpeVocab
        );
        
        // Endpoint config
        EndpointRule rule1 = new EndpointRule(false, 2.4f, 0.0f);
        EndpointRule rule2 = new EndpointRule(true, 1.4f, 0.0f);
        EndpointRule rule3 = new EndpointRule(false, 0.0f, 20.0f);
        EndpointConfig endpointConfig = new EndpointConfig(rule1, rule2, rule3);
        
        // LM config
        OnlineLMConfig lmConfig = new OnlineLMConfig("", 0.5f);
        
        // CTC FST decoder config
        OnlineCtcFstDecoderConfig ctcFstConfig = new OnlineCtcFstDecoderConfig("", 3000);
        
        // Create main config using constructor (like sherpa-mnn example)
        OnlineRecognizerConfig config = new OnlineRecognizerConfig(
            featConfig,
            modelConfig,
            lmConfig,
            ctcFstConfig,
            endpointConfig,
            true,   // enableEndpoint
            "greedy_search",  // decodingMethod
            4,      // maxActivePaths
            "",     // hotwordsFile
            1.5f,   // hotwordsScore
            "",     // ruleFsts
            "",     // ruleFars
            0.0f    // blankPenalty
        );
        
        return config;
    }
    
    /**
     * Model file paths holder
     */
    private static class ModelFilePaths {
        String encoder;
        String decoder;
        String joiner;
        String tokens;
        
        ModelFilePaths(String encoder, String decoder, String joiner, String tokens) {
            this.encoder = encoder;
            this.decoder = decoder;
            this.joiner = joiner;
            this.tokens = tokens;
        }
    }
    
    /**
     * Get model file paths from config.json or fallback detection
     * Reference: ChatMNN AsrModelConfig.kt
     */
    private ModelFilePaths getModelFilePaths(String modelDir) {
        // Try to parse config.json first
        File configFile = new File(modelDir, "config.json");
        if (configFile.exists()) {
            try {
                LogManager.logD(TAG, "[ASR] Found config.json, parsing...");
                String configContent = readFileContent(configFile);
                JSONObject configJson = new JSONObject(configContent);
                
                // Parse transducer config
                if (configJson.has("transducer")) {
                    JSONObject transducerJson = configJson.getJSONObject("transducer");
                    String encoder = transducerJson.getString("encoder");
                    String decoder = transducerJson.getString("decoder");
                    String joiner = transducerJson.getString("joiner");
                    String tokens = configJson.getString("tokens");
                    
                    LogManager.logI(TAG, "[ASR] Using config from config.json: encoder=" + encoder);
                    return new ModelFilePaths(
                        new File(modelDir, encoder).getAbsolutePath(),
                        new File(modelDir, decoder).getAbsolutePath(),
                        new File(modelDir, joiner).getAbsolutePath(),
                        new File(modelDir, tokens).getAbsolutePath()
                    );
                }
            } catch (Exception e) {
                LogManager.logW(TAG, "[ASR] Failed to parse config.json: " + e.getMessage());
            }
        }
        
        // Fallback: Use regex to find model files by pattern matching
        LogManager.logW(TAG, "[ASR] config.json not found or invalid, using regex search");
        return findModelFilesByRegex(modelDir);
    }
    
    /**
     * Find model files by regex pattern matching
     * Matches files containing "encoder"/"decoder"/"joiner" anywhere in filename and ending with ".mnn"
     * Supports any naming pattern like:
     * - encoder.mnn
     * - encoder-epoch-99-avg-1.mnn
     * - encoder-epoch-99-avg-1.int8.mnn
     * - model-encoder-v1.mnn (encoder anywhere in name)
     */
    private ModelFilePaths findModelFilesByRegex(String modelDir) {
        LogManager.logD(TAG, "[ASR] Searching for model files using regex...");
        File dir = new File(modelDir);
        File[] files = dir.listFiles();
        if (files == null) {
            throw new RuntimeException("Model directory is empty or not accessible: " + modelDir);
        }
        
        String encoder = null, decoder = null, joiner = null, tokens = null;
        
        // Use regex to match files: .*encoder.*\.mnn$ (encoder anywhere in filename)
        for (File file : files) {
            String name = file.getName();
            
            // Match encoder files: *encoder*.mnn (encoder anywhere in filename)
            if (name.matches(".*encoder.*\\.mnn$")) {
                encoder = file.getAbsolutePath();
                LogManager.logD(TAG, "[ASR] Found encoder: " + name);
            } 
            // Match decoder files: *decoder*.mnn
            else if (name.matches(".*decoder.*\\.mnn$")) {
                decoder = file.getAbsolutePath();
                LogManager.logD(TAG, "[ASR] Found decoder: " + name);
            } 
            // Match joiner files: *joiner*.mnn
            else if (name.matches(".*joiner.*\\.mnn$")) {
                joiner = file.getAbsolutePath();
                LogManager.logD(TAG, "[ASR] Found joiner: " + name);
            } 
            // Match tokens file
            else if (name.equals("tokens.txt")) {
                tokens = file.getAbsolutePath();
                LogManager.logD(TAG, "[ASR] Found tokens: " + name);
            }
        }
        
        // Check if all required files were found
        if (encoder == null || decoder == null || joiner == null || tokens == null) {
            throw new RuntimeException("Could not find all required model files in: " + modelDir + 
                "\n  encoder: " + (encoder != null ? new File(encoder).getName() : "NOT FOUND") +
                "\n  decoder: " + (decoder != null ? new File(decoder).getName() : "NOT FOUND") +
                "\n  joiner: " + (joiner != null ? new File(joiner).getName() : "NOT FOUND") +
                "\n  tokens: " + (tokens != null ? "tokens.txt" : "NOT FOUND"));
        }
        
        LogManager.logI(TAG, "[ASR] ✅ Found all model files by regex:");
        LogManager.logI(TAG, "[ASR]   encoder: " + new File(encoder).getName());
        LogManager.logI(TAG, "[ASR]   decoder: " + new File(decoder).getName());
        LogManager.logI(TAG, "[ASR]   joiner: " + new File(joiner).getName());
        
        return new ModelFilePaths(encoder, decoder, joiner, tokens);
    }
    
    /**
     * Read file content as string
     */
    private String readFileContent(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.FileReader(file)
        );
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } finally {
            reader.close();
        }
        return content.toString();
    }
    
    /**
     * Transcribe audio file to text
     * 
     * @param audioPath WAV file path (16kHz, mono, 16-bit PCM)
     * @return Recognized text
     * @throws Exception if transcription fails
     */
    public String transcribeAudio(String audioPath) throws Exception {
        if (asrRecognizer == null) {
            throw new IllegalStateException("ASR model not loaded");
        }
        
        LogManager.logI(TAG, "[ASR] Transcribing audio: " + audioPath);
        
        OnlineStream stream = null;
        try {
            // Read audio file using sherpa-mnn WaveReader
            WaveData waveData = WaveReader.Companion.readWave(audioPath);
            
            // Create stream
            stream = asrRecognizer.createStream("");
            if (stream == null) {
                throw new Exception("Failed to create ASR stream");
            }
            
            // Feed audio samples
            stream.acceptWaveform(waveData.getSamples(), waveData.getSampleRate());
            stream.inputFinished();
            
            // Decode
            while (asrRecognizer.isReady(stream)) {
                asrRecognizer.decode(stream);
            }
            
            // Get result
            OnlineRecognizerResult result = asrRecognizer.getResult(stream);
            String text = result.getText().trim();
            
            LogManager.logI(TAG, "[ASR] ✅ Transcription result: \"" + text + "\"");
            return text;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[ASR] ❌ Transcription failed", e);
            throw new Exception("ASR transcription failed: " + e.getMessage(), e);
        } finally {
            // CRITICAL: Always release stream to prevent memory leak and GC crash
            if (stream != null) {
                try {
                    stream.release();
                    LogManager.logD(TAG, "[ASR] Stream released");
                } catch (Exception e) {
                    LogManager.logW(TAG, "[ASR] Failed to release stream: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Check if ASR model is loaded
     * 
     * @return true if ASR is loaded
     */
    public boolean isAsrLoaded() {
        return asrRecognizer != null;
    }
    
    /**
     * Get current ASR model name
     * 
     * @return Current ASR model name or null
     */
    public String getCurrentAsrModel() {
        return currentAsrModel;
    }

    /**
     * Release ASR resources
     */
    public synchronized void release() {
        if (asrRecognizer != null) {
            try {
                asrRecognizer.release();
                LogManager.logI(TAG, "[ASR] Resources released");
            } catch (Exception e) {
                LogManager.logE(TAG, "[ASR] Error releasing resources", e);
            }
            asrRecognizer = null;
        }
        currentAsrModel = null;
        currentAsrBasePath = null;
    }
}
