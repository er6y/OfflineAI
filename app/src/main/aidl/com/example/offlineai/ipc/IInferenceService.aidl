package com.example.offlineai.ipc;

import com.example.offlineai.ipc.ILlmCallback;
import com.example.offlineai.ipc.ServiceStatus;
import com.example.offlineai.ipc.IInferenceStatusCallback;
import com.example.offlineai.ipc.RuntimeConfig;
import java.util.List;

interface IInferenceService {
    void runLlmTask(String taskId, String modelName, String prompt,
                    in List<String> imagePaths,
                    in List<String> audioPaths,
                    ILlmCallback callback);

    float[] computeEmbedding(String modelPath, String memoryMode, String text);

    void initKbEmbedding(String modelPath, String runtimeConfig, int concurrencyN);

    float[] computeKbEmbedding(String text);

    float[] rerank(String modelPath, String instruction, String query,
                   in List<String> documents);

    String runAsr(String modelName, String audioPath);

    // TTS synthesis in inference process (External TTS only)
    // Returns the output audio file path on success, null on failure
    String runTts(String modelPath, String text, String outputPath);

    void stopAll();

    // Reset stop flag for LLM inference (call before new query)
    void resetStopFlag();

    ServiceStatus getStatus();

    void registerStatusCallback(IInferenceStatusCallback callback);

    void unregisterStatusCallback(IInferenceStatusCallback callback);

    // Push runtime configuration snapshot from main process to inference process
    void updateRuntimeConfig(in RuntimeConfig config);

    void forceKillSelf();
}
