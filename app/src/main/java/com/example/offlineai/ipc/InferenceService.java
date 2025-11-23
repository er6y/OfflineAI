package com.example.offlineai.ipc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.example.offlineai.LogManager;
import com.example.offlineai.EmbeddingHandler;
import com.example.offlineai.RerankerHandler;
import com.example.offlineai.ConfigManager;
import com.example.offlineai.RuntimeConfigHolder;
// LocalLlmAdapter, AsrAdapter now in same package (ipc)
import com.example.offlineai.api.LlmApiAdapter;
import com.offlineai.mnn.MnnInference;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * InferenceService runs in a dedicated process and owns all heavy LLM/MNN work.
 * It exposes a small Binder interface for the main process.
 */
public class InferenceService extends Service {

    private static final String TAG = "InferenceService";

    private final AtomicBoolean hasActiveTask = new AtomicBoolean(false);
    private volatile String currentTaskId = null;

    private final CopyOnWriteArrayList<IInferenceStatusCallback> statusCallbacks =
            new CopyOnWriteArrayList<>();

    private final KbEmbeddingManager kbEmbeddingManager = new KbEmbeddingManager();

    private void notifyModelState(String component, String modelPath,
                                  String state, boolean busy, int threads) {
        for (IInferenceStatusCallback cb : statusCallbacks) {
            if (cb == null) {
                continue;
            }
            try {
                cb.onModelStateChanged(component, modelPath, state, busy, threads);
            } catch (RemoteException e) {
                LogManager.logE(TAG, "[STATUS] Error in onModelStateChanged callback: " + e.getMessage(), e);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[STATUS] Exception in onModelStateChanged callback: " + t.getMessage(), t);
            }
        }
    }

    private void notifyRerankProgress(String taskId, int current, int total) {
        for (IInferenceStatusCallback cb : statusCallbacks) {
            if (cb == null) {
                continue;
            }
            try {
                cb.onRerankProgress(taskId, current, total);
            } catch (RemoteException e) {
                LogManager.logE(TAG, "[STATUS] Error in onRerankProgress callback: " + e.getMessage(), e);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[STATUS] Exception in onRerankProgress callback: " + t.getMessage(), t);
            }
        }
    }

    private final IInferenceService.Stub binder = new IInferenceService.Stub() {
        @Override
        public void runLlmTask(String taskId,
                               String modelName,
                               String prompt,
                               List<String> imagePaths,
                               List<String> audioPaths,
                               ILlmCallback callback) {
            // Normalize taskId and keep an effectively-final copy for inner callbacks
            String effectiveTaskId = taskId;
            if (effectiveTaskId == null || effectiveTaskId.isEmpty()) {
                effectiveTaskId = UUID.randomUUID().toString();
            }
            final String finalTaskId = effectiveTaskId;
            currentTaskId = finalTaskId;
            hasActiveTask.set(true);

            LogManager.logI(TAG, "[IPC][LLM] runLlmTask called - taskId=" + taskId
                    + ", model=" + modelName
                    + ", promptLen=" + (prompt == null ? 0 : prompt.length()));

            try {
                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("LLM", modelName, "RUNNING", true, threads);

                LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(getApplicationContext());
                LlmApiAdapter.ApiCallback proxy = new LlmApiAdapter.ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        hasActiveTask.set(false);
                        try {
                            if (callback != null) {
                                callback.onComplete(finalTaskId, response);
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[IPC][LLM] Error in onComplete callback: " + e.getMessage(), e);
                        }
                        int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                        notifyModelState("LLM", modelName, "READY", false, threads);
                    }

                    @Override
                    public void onStreamingData(String chunk) {
                        try {
                            if (callback != null) {
                                callback.onToken(finalTaskId, chunk);
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[IPC][LLM] Error in onToken callback: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        hasActiveTask.set(false);
                        try {
                            if (callback != null) {
                                callback.onError(finalTaskId, errorMessage);
                            }
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[IPC][LLM] Error in onError callback: " + e.getMessage(), e);
                        }
                        int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                        notifyModelState("LLM", modelName, "ERROR", false, threads);
                    }
                };

                adapter.callLocalModel(modelName, prompt, imagePaths, audioPaths, proxy);
            } catch (Throwable t) {
                hasActiveTask.set(false);
                LogManager.logE(TAG, "[IPC][LLM] Exception in runLlmTask: " + t.getMessage(), t);
                try {
                    if (callback != null) {
                        callback.onError(taskId, "InferenceService error: " + t.getMessage());
                    }
                } catch (Exception ignored) {
                }
                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("LLM", modelName, "ERROR", false, threads);
            }
        }

        @Override
        public float[] computeEmbedding(String modelPath, String memoryMode, String text) {
            LogManager.logI(TAG, "[IPC][EMBED] computeEmbedding called - modelPath=" + modelPath);
            try {
                EmbeddingHandler handler = EmbeddingHandler.getInstance(getApplicationContext());

                EmbeddingHandler.MemoryMode mode = EmbeddingHandler.MemoryMode.LOW;
                if (memoryMode != null) {
                    String mm = memoryMode.toLowerCase();
                    if ("high".equals(mm)) {
                        mode = EmbeddingHandler.MemoryMode.HIGH;
                    } else if ("normal".equals(mm)) {
                        mode = EmbeddingHandler.MemoryMode.NORMAL;
                    }
                }

                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("EMBEDDING", modelPath, "RUNNING", true, threads);

                handler.getModel(modelPath, mode);
                float[] result = handler.computeEmbedding(text);

                notifyModelState("EMBEDDING", modelPath, "READY", false, threads);
                return result;
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][EMBED] Exception in computeEmbedding: " + t.getMessage(), t);
                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("EMBEDDING", modelPath, "ERROR", false, threads);
                return null;
            }
        }

        @Override
        public void initKbEmbedding(String modelPath, String runtimeConfig, int concurrencyN) {
            LogManager.logI(TAG, "[IPC][EMBED_KB] initKbEmbedding called - modelPath=" + modelPath + ", concurrency=" + concurrencyN);
            try {
                kbEmbeddingManager.init(modelPath, runtimeConfig, concurrencyN);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][EMBED_KB] Exception in initKbEmbedding: " + t.getMessage(), t);
            }
        }

        @Override
        public float[] computeKbEmbedding(String text) {
            LogManager.logI(TAG, "[IPC][EMBED_KB] computeKbEmbedding called");
            try {
                return kbEmbeddingManager.compute(text);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LogManager.logI(TAG, "[IPC][EMBED_KB] computeKbEmbedding interrupted: " + e.getMessage());
                return null;
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][EMBED_KB] Exception in computeKbEmbedding: " + t.getMessage(), t);
                return null;
            }
        }

        @Override
        public float[] rerank(String modelPath, String instruction, String query, java.util.List<String> documents) {
            LogManager.logI(TAG, "[IPC][RERANK] rerank called - modelPath=" + modelPath + ", docs=" + (documents == null ? 0 : documents.size()));
            if (documents == null || documents.isEmpty()) {
                return new float[0];
            }
            try {
                RerankerHandler handler = RerankerHandler.getInstance(getApplicationContext());
                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("RERANKER", modelPath, "LOADING", true, threads);

                if (!handler.loadModel(modelPath)) {
                    LogManager.logE(TAG, "[IPC][RERANK] Failed to load reranker model: " + modelPath);
                    notifyModelState("RERANKER", modelPath, "ERROR", false, threads);
                    return null;
                }

                if (instruction != null && !instruction.isEmpty()) {
                    handler.setInstruction(instruction);
                }

                final String rerankTaskId = UUID.randomUUID().toString();
                handler.setProgressCallback((current, total) -> {
                    notifyRerankProgress(rerankTaskId, current, total);
                });

                java.util.List<RerankerHandler.RerankResult> results = handler.rerank(query, documents, documents.size());
                float[] scores = new float[documents.size()];
                for (int i = 0; i < results.size(); i++) {
                    RerankerHandler.RerankResult r = results.get(i);
                    if (r.originalIndex >= 0 && r.originalIndex < scores.length) {
                        scores[r.originalIndex] = r.score;
                    }
                }
                notifyModelState("RERANKER", modelPath, "READY", false, threads);
                return scores;
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][RERANK] Exception in rerank: " + t.getMessage(), t);
                int threads = RuntimeConfigHolder.getThreadsOrDefault(ConfigManager.DEFAULT_THREADS);
                notifyModelState("RERANKER", modelPath, "ERROR", false, threads);
                return null;
            }
        }

        @Override
        public String runAsr(String modelName, String audioPath) {
            LogManager.logI(TAG, "[IPC][ASR] runAsr called - model=" + modelName + ", audio=" + audioPath);
            try {
                AsrAdapter adapter = AsrAdapter.getInstance(getApplicationContext());
                adapter.loadAsrModel(modelName);
                return adapter.transcribeAudio(audioPath);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][ASR] Exception in runAsr: " + t.getMessage(), t);
                return null;
            }
        }

        @Override
        public String runTts(String modelPath, String text, String outputPath) {
            LogManager.logI(TAG, "[IPC][TTS] runTts called - modelPath=" + modelPath + ", textLen=" + (text == null ? 0 : text.length()));
            try {
                return TtsAdapter.getInstance(getApplicationContext()).synthesizeExternal(modelPath, text, outputPath);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][TTS] Exception in runTts: " + t.getMessage(), t);
                return null;
            }
        }

        @Override
        public void stopAll() {
            LogManager.logI(TAG, "[IPC][LLM] stopAll called");
            try {
                LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(getApplicationContext());
                adapter.stopGeneration();
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][LLM] Exception in stopAll: " + t.getMessage(), t);
            }
        }

        @Override
        public void resetStopFlag() {
            LogManager.logI(TAG, "[IPC][LLM] resetStopFlag called");
            try {
                LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(getApplicationContext());
                adapter.resetStopFlag();
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][LLM] Exception in resetStopFlag: " + t.getMessage(), t);
            }
        }

        @Override
        public ServiceStatus getStatus() {
            ServiceStatus status = new ServiceStatus();
            status.hasActiveTask = hasActiveTask.get();
            status.currentTaskId = currentTaskId;
            try {
                LocalLlmAdapter adapter = LocalLlmAdapter.getInstance(getApplicationContext());
                status.modelState = String.valueOf(adapter.getModelState());
                status.llmBusy = adapter.isModelBusy();
                status.llmRunning = adapter.isInferenceRunning();
            } catch (Throwable t) {
                LogManager.logE(TAG, "[IPC][LLM] Error querying status: " + t.getMessage(), t);
                status.modelState = "ERROR";
            }
            return status;
        }

        @Override
        public void forceKillSelf() {
            LogManager.logE(TAG, "[IPC][LLM] forceKillSelf requested, killing inference process");
            Process.killProcess(Process.myPid());
        }

        @Override
        public void registerStatusCallback(IInferenceStatusCallback callback) {
            if (callback == null) {
                return;
            }
            statusCallbacks.addIfAbsent(callback);
            LogManager.logI(TAG, "[STATUS] registerStatusCallback, size=" + statusCallbacks.size());
        }

        @Override
        public void unregisterStatusCallback(IInferenceStatusCallback callback) {
            if (callback == null) {
                return;
            }
            statusCallbacks.remove(callback);
            LogManager.logI(TAG, "[STATUS] unregisterStatusCallback, size=" + statusCallbacks.size());
        }

        @Override
        public void updateRuntimeConfig(com.example.offlineai.ipc.RuntimeConfig config) {
            if (config == null) {
                LogManager.logW(TAG, "[CONFIG] updateRuntimeConfig called with null config, ignore");
                return;
            }
            LogManager.logI(TAG, "[CONFIG] RuntimeConfig received in InferenceService, updating holder");
            com.example.offlineai.RuntimeConfigHolder.update(config);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        LogManager.logI(TAG, "onBind called");
        LogManager.logI(TAG, "[STARTUP_TRACE] onBind at " + System.currentTimeMillis() + ", pid=" + Process.myPid());
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        long now = System.currentTimeMillis();
        LogManager.logI(TAG, "InferenceService created in process pid=" + Process.myPid());
        LogManager.logI(TAG, "[STARTUP_TRACE] InferenceService.onCreate at " + now + ", pid=" + Process.myPid());
    }

    @Override
    public void onDestroy() {
        LogManager.logI(TAG, "InferenceService destroyed, pid=" + Process.myPid());
        kbEmbeddingManager.releaseAll();
        super.onDestroy();
    }
    
    private static class KbEmbeddingManager {
        private final Object lock = new Object();
        private String modelPath;
        private String runtimeConfig;
        private int concurrency;
        private List<Long> handles = new ArrayList<>();
        private BlockingQueue<Long> idleQueue;

        void init(String modelPath, String runtimeConfig, int concurrencyN) {
            if (concurrencyN <= 0) {
                concurrencyN = 1;
            }
            synchronized (lock) {
                boolean sameConfig = this.modelPath != null
                        && this.modelPath.equals(modelPath)
                        && this.runtimeConfig != null
                        && this.runtimeConfig.equals(runtimeConfig)
                        && this.concurrency == concurrencyN
                        && idleQueue != null
                        && !handles.isEmpty();

                if (sameConfig) {
                    LogManager.logI(TAG, "[EMBED_KB] Reusing existing KB embedding pool: modelPath=" + modelPath + ", concurrency=" + concurrencyN);
                    return;
                }

                releaseLocked();

                this.modelPath = modelPath;
                this.runtimeConfig = runtimeConfig;
                this.concurrency = concurrencyN;

                idleQueue = new ArrayBlockingQueue<>(concurrencyN);

                for (int i = 0; i < concurrencyN; i++) {
                    long handle = 0L;
                    try {
                        handle = MnnInference.createEmbeddingWithConfig(modelPath, runtimeConfig);
                    } catch (Exception e) {
                        LogManager.logE(TAG, "[EMBED_KB] Failed to create KB embedding session: " + e.getMessage(), e);
                    }
                    if (handle != 0L) {
                        handles.add(handle);
                        idleQueue.offer(handle);
                        LogManager.logI(TAG, "[EMBED_KB] Created KB embedding session handle=" + handle + " (" + (i + 1) + "/" + concurrencyN + ")");
                    } else {
                        LogManager.logE(TAG, "[EMBED_KB] KB embedding session handle is 0, skipping");
                    }
                }
            }
        }

        float[] compute(String text) throws InterruptedException {
            Long handle;
            synchronized (lock) {
                if (idleQueue == null || handles.isEmpty()) {
                    LogManager.logE(TAG, "[EMBED_KB] compute called before init or after release");
                    return null;
                }
            }

            handle = idleQueue.take();
            try {
                return MnnInference.computeEmbedding(handle, text);
            } finally {
                if (idleQueue != null) {
                    idleQueue.offer(handle);
                }
            }
        }

        void releaseAll() {
            synchronized (lock) {
                releaseLocked();
            }
        }

        private void releaseLocked() {
            if (handles != null && !handles.isEmpty()) {
                for (Long handle : handles) {
                    if (handle != null && handle != 0L) {
                        try {
                            MnnInference.releaseEmbedding(handle);
                            LogManager.logD(TAG, "[EMBED_KB] Released KB embedding session handle=" + handle);
                        } catch (Exception e) {
                            LogManager.logE(TAG, "[EMBED_KB] Error releasing KB embedding handle: " + e.getMessage(), e);
                        }
                    }
                }
                handles.clear();
            }
            idleQueue = null;
        }
    }
}
