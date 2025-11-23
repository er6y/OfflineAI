package com.example.offlineai.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.widget.Toast;

import com.example.offlineai.LogManager;
import com.example.offlineai.api.LlmApiAdapter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IPC client for InferenceService.
 * Bridges LlmApiAdapter local calls to the dedicated inference process.
 */
public class InferenceClient {

    private static final String TAG = "InferenceClient";
    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    private static InferenceClient sInstance;

    private final Context appContext;
    private final Object serviceLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private IInferenceService service;
    private boolean binding;

    private final AtomicBoolean hasActiveTask = new AtomicBoolean(false);
    private volatile String currentTaskId;
    private Runnable stopTimeoutRunnable;
    private IInferenceStatusCallback statusCallback;
    private final CopyOnWriteArrayList<StatusListener> statusListeners = new CopyOnWriteArrayList<>();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IInferenceService svc;
            synchronized (serviceLock) {
                service = IInferenceService.Stub.asInterface(binder);
                binding = false;
                serviceLock.notifyAll();
                svc = service;
            }
            LogManager.logI(TAG, "InferenceService connected");
            registerStatusCallback(svc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (serviceLock) {
                service = null;
                binding = false;
                hasActiveTask.set(false);
                currentTaskId = null;
                serviceLock.notifyAll();
            }
            LogManager.logW(TAG, "InferenceService disconnected");
            // Notify listeners so UI can reset state when service is gone
            notifyModelStateChangedListeners("SERVICE", "", "DISCONNECTED", false, 0);
        }
    };

    private InferenceClient(Context context) {
        this.appContext = context.getApplicationContext();
        bindService();
    }

    public static synchronized InferenceClient getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new InferenceClient(context);
        }
        return sInstance;
    }

    /**
     * Bind to inference service if not already bound.
     */
    public void bindService() {
        synchronized (serviceLock) {
            if (service != null || binding) {
                return;
            }
            Intent intent = new Intent(appContext, InferenceService.class);
            binding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            LogManager.logI(TAG, "bindService called, result=" + binding);
        }
    }

    private boolean waitForService(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (serviceLock) {
            if (service != null) {
                return true;
            }
            if (!binding) {
                Intent intent = new Intent(appContext, InferenceService.class);
                binding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                LogManager.logI(TAG, "waitForService: bindService called, result=" + binding);
                if (!binding) {
                    return false;
                }
            }
            long remaining;
            while (service == null && (remaining = deadline - SystemClock.uptimeMillis()) > 0) {
                try {
                    serviceLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LogManager.logW(TAG, "waitForService interrupted: " + e.getMessage());
                    break;
                }
            }
            boolean connected = service != null;
            if (!connected) {
                LogManager.logE(TAG, "waitForService timeout after " + timeoutMs + " ms");
            }
            return connected;
        }
    }

    private void ensureStatusCallback() {
        if (statusCallback != null) {
            return;
        }
        statusCallback = new IInferenceStatusCallback.Stub() {
            @Override
            public void onModelStateChanged(String component, String modelPath, String state, boolean busy, int threads) {
                LogManager.logI(TAG, "[STATUS] component=" + component
                        + ", state=" + state
                        + ", busy=" + busy
                        + ", threads=" + threads
                        + ", modelPath=" + modelPath);
                notifyModelStateChangedListeners(component, modelPath, state, busy, threads);
            }

            @Override
            public void onRerankProgress(String taskId, int current, int total) {
                LogManager.logI(TAG, "[RERANK_STATUS] taskId=" + taskId
                        + ", progress=" + current + "/" + total);
                notifyRerankProgressListeners(taskId, current, total);
            }
        };
    }

    /**
     * Listener interface for inference status events.
     * Allows UI (e.g., RagQaFragment) to observe model state changes and rerank progress.
     */
    public interface StatusListener {
        void onModelStateChanged(String component, String modelPath, String state, boolean busy, int threads);

        void onRerankProgress(String taskId, int current, int total);
    }

    /**
     * Register a status listener. Duplicate registrations are ignored.
     */
    public void addStatusListener(StatusListener listener) {
        if (listener == null) {
            return;
        }
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    /**
     * Unregister a previously registered status listener.
     */
    public void removeStatusListener(StatusListener listener) {
        if (listener == null) {
            return;
        }
        statusListeners.remove(listener);
    }

    private void notifyModelStateChangedListeners(String component, String modelPath, String state, boolean busy, int threads) {
        if (statusListeners.isEmpty()) {
            return;
        }
        for (StatusListener listener : statusListeners) {
            try {
                listener.onModelStateChanged(component, modelPath, state, busy, threads);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[STATUS] Listener error in onModelStateChanged: " + t.getMessage(), t);
            }
        }
    }

    private void notifyRerankProgressListeners(String taskId, int current, int total) {
        if (statusListeners.isEmpty()) {
            return;
        }
        for (StatusListener listener : statusListeners) {
            try {
                listener.onRerankProgress(taskId, current, total);
            } catch (Throwable t) {
                LogManager.logE(TAG, "[STATUS] Listener error in onRerankProgress: " + t.getMessage(), t);
            }
        }
    }

    private void registerStatusCallback(IInferenceService svc) {
        if (svc == null) {
            return;
        }
        ensureStatusCallback();
        try {
            svc.registerStatusCallback(statusCallback);
            LogManager.logI(TAG, "[STATUS] Status callback registered");
        } catch (RemoteException e) {
            LogManager.logE(TAG, "[STATUS] RemoteException in registerStatusCallback: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "[STATUS] Exception in registerStatusCallback: " + t.getMessage(), t);
        }
    }

    public boolean isServiceAvailable() {
        synchronized (serviceLock) {
            return service != null;
        }
    }

    /**
     * Run LLM task in inference process.
     */
    public void runLlmTask(String modelName,
                           String prompt,
                           List<String> imagePaths,
                           List<String> audioPaths,
                           LlmApiAdapter.ApiCallback callback) {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("runLlmTask");
            if (callback != null) {
                callback.onError("Local inference service timeout, restarting. Please try again later.");
            }
            return;
        }

        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "runLlmTask: service is null after waitForService");
            if (callback != null) {
                callback.onError("Local inference service is not available, please try again later");
            }
            return;
        }

        final String taskId = UUID.randomUUID().toString();
        currentTaskId = taskId;
        hasActiveTask.set(true);

        try {
            ILlmCallback stub = new ILlmCallback.Stub() {
                @Override
                public void onToken(String id, String token) {
                    if (!taskId.equals(id)) {
                        return;
                    }
                    if (callback != null) {
                        callback.onStreamingData(token);
                    }
                }

                @Override
                public void onComplete(String id, String fullResponse) {
                    if (!taskId.equals(id)) {
                        return;
                    }
                    hasActiveTask.set(false);
                    cancelStopTimeout();
                    if (callback != null) {
                        callback.onSuccess(fullResponse);
                    }
                }

                @Override
                public void onError(String id, String errorMessage) {
                    if (!taskId.equals(id)) {
                        return;
                    }
                    hasActiveTask.set(false);
                    cancelStopTimeout();
                    if (callback != null) {
                        callback.onError(errorMessage);
                    }
                }
            };

            svc.runLlmTask(taskId, modelName, prompt, imagePaths, audioPaths, stub);
        } catch (RemoteException e) {
            hasActiveTask.set(false);
            LogManager.logE(TAG, "RemoteException in runLlmTask: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Local inference service error: " + e.getMessage());
            }
            handleRemoteError();
        } catch (Throwable t) {
            hasActiveTask.set(false);
            LogManager.logE(TAG, "Exception in runLlmTask: " + t.getMessage(), t);
            if (callback != null) {
                callback.onError("Local inference client error: " + t.getMessage());
            }
        }
    }

    /**
     * Request cooperative stop and start a timeout. If the task is still active
     * after timeout, force kill inference process.
     */
    public void requestStopWithTimeout(long timeoutMs) {
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logW(TAG, "requestStopWithTimeout: service is null");
            return;
        }

        try {
            LogManager.logI(TAG, "requestStopWithTimeout: calling stopAll()");
            svc.stopAll();
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in stopAll: " + e.getMessage(), e);
            handleRemoteError();
            return;
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in stopAll: " + t.getMessage(), t);
            return;
        }

        if (!hasActiveTask.get()) {
            LogManager.logD(TAG, "No active task when stop requested, skip timeout scheduling");
            return;
        }

        cancelStopTimeout();
        stopTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!hasActiveTask.get()) {
                    LogManager.logD(TAG, "Stop timeout fired but no active task, skip forceKillSelf");
                    return;
                }
                LogManager.logE(TAG, "[STOP] Timeout reached, force killing inference process");
                synchronized (serviceLock) {
                    if (service != null) {
                        try {
                            service.forceKillSelf();
                        } catch (RemoteException e) {
                            LogManager.logE(TAG, "RemoteException in forceKillSelf: " + e.getMessage(), e);
                        } catch (Throwable t) {
                            LogManager.logE(TAG, "Exception in forceKillSelf: " + t.getMessage(), t);
                        }
                    }
                }
            }
        };

        mainHandler.postDelayed(stopTimeoutRunnable, timeoutMs);
    }

    public ServiceStatus safeGetStatus() {
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            ServiceStatus status = new ServiceStatus();
            status.modelState = "DISCONNECTED";
            return status;
        }
        try {
            return svc.getStatus();
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in getStatus: " + e.getMessage(), e);
            handleRemoteError();
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in getStatus: " + t.getMessage(), t);
        }
        ServiceStatus status = new ServiceStatus();
        status.modelState = "ERROR";
        return status;
    }

    /**
     * Push runtime configuration snapshot to inference process.
     */
    public void updateRuntimeConfig(com.example.offlineai.ipc.RuntimeConfig config) {
        if (config == null) {
            return;
        }
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("updateRuntimeConfig");
            return;
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logW(TAG, "updateRuntimeConfig: service is null after waitForService");
            return;
        }
        try {
            svc.updateRuntimeConfig(config);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in updateRuntimeConfig: " + e.getMessage(), e);
            handleRemoteError();
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in updateRuntimeConfig: " + t.getMessage(), t);
        }
    }

    /**
     * Compute embedding vector in inference process.
     */
    public float[] computeEmbedding(String modelPath, String memoryMode, String text) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("computeEmbedding");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "computeEmbedding: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            return svc.computeEmbedding(modelPath, memoryMode, text);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in computeEmbedding: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in computeEmbedding: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    /**
     * Initialize KB-specific multi-session embedding pool in inference process.
     */
    public void initKbEmbedding(String modelPath, String runtimeConfig, int concurrencyN) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("initKbEmbedding");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "initKbEmbedding: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            svc.initKbEmbedding(modelPath, runtimeConfig, concurrencyN);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in initKbEmbedding: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in initKbEmbedding: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    /**
     * Compute embedding vector for KB using the KB multi-session pool in inference process.
     */
    public float[] computeKbEmbedding(String text) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("computeKbEmbedding");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "computeKbEmbedding: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            return svc.computeKbEmbedding(text);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in computeKbEmbedding: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in computeKbEmbedding: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    /**
     * Compute reranker scores in inference process.
     * Returns a score array aligned with the input documents list.
     */
    public float[] rerankScores(String modelPath, String instruction, String query, java.util.List<String> documents) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("rerankScores");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "rerankScores: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            return svc.rerank(modelPath, instruction, query, documents);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in rerankScores: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in rerankScores: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    /**
     * Reset stop flag in inference process before new query.
     */
    public void resetStopFlag() {
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logW(TAG, "resetStopFlag: service is null, skip");
            return;
        }
        try {
            svc.resetStopFlag();
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in resetStopFlag: " + e.getMessage(), e);
            handleRemoteError();
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in resetStopFlag: " + t.getMessage(), t);
        }
    }

    /**
     * Run TTS synthesis in inference process (External TTS only).
     * @param modelPath Full path to TTS model directory
     * @param text Text to synthesize
     * @param outputPath Output WAV file path
     * @return Output file path on success, null on failure
     */
    public String runTts(String modelPath, String text, String outputPath) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("runTts");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "runTts: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            return svc.runTts(modelPath, text, outputPath);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in runTts: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in runTts: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    /**
     * Run ASR in inference process and return recognized text.
     */
    public String runAsr(String modelName, String audioPath) throws Exception {
        if (!waitForService(CONNECT_TIMEOUT_MS)) {
            handleConnectTimeout("runAsr");
            throw new Exception("Local inference service timeout, restarting. Please try again later.");
        }
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logE(TAG, "runAsr: service is null after waitForService");
            throw new Exception("Local inference service is not available, please try again later");
        }
        try {
            return svc.runAsr(modelName, audioPath);
        } catch (RemoteException e) {
            LogManager.logE(TAG, "RemoteException in runAsr: " + e.getMessage(), e);
            handleRemoteError();
            throw new Exception("Local inference service error: " + e.getMessage(), e);
        } catch (Throwable t) {
            LogManager.logE(TAG, "Exception in runAsr: " + t.getMessage(), t);
            throw new Exception("Local inference client error: " + t.getMessage(), t);
        }
    }

    private void cancelStopTimeout() {
        if (stopTimeoutRunnable != null) {
            mainHandler.removeCallbacks(stopTimeoutRunnable);
            stopTimeoutRunnable = null;
        }
    }

    /**
     * Schedule a forced kill of the inference process after the given timeout.
     * This is primarily used by long-running background tasks such as
     * knowledge base building when cooperative cancellation is not sufficient.
     */
    public void requestForceKillAfterTimeout(long timeoutMs) {
        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc == null) {
            LogManager.logW(TAG, "requestForceKillAfterTimeout: service is null");
            return;
        }

        cancelStopTimeout();
        stopTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (serviceLock) {
                    if (service == null) {
                        LogManager.logD(TAG, "requestForceKillAfterTimeout: service cleared before timeout, skip forceKillSelf");
                        return;
                    }
                    try {
                        LogManager.logE(TAG, "[STOP][KB] Timeout reached, force killing inference process from main process");
                        service.forceKillSelf();
                    } catch (RemoteException e) {
                        LogManager.logE(TAG, "RemoteException in forceKillSelf (requestForceKillAfterTimeout): " + e.getMessage(), e);
                    } catch (Throwable t) {
                        LogManager.logE(TAG, "Exception in forceKillSelf (requestForceKillAfterTimeout): " + t.getMessage(), t);
                    }
                }
            }
        };

        mainHandler.postDelayed(stopTimeoutRunnable, timeoutMs);
    }

    private void handleRemoteError() {
        synchronized (serviceLock) {
            // Do not call forceKillSelf here to avoid recursion, just reset local state
            service = null;
            binding = false;
            hasActiveTask.set(false);
            currentTaskId = null;
            serviceLock.notifyAll();
        }
        LogManager.logW(TAG, "handleRemoteError: reset local service reference");
        // Next call will trigger re-bind. Notify listeners so UI can reset state.
        notifyModelStateChangedListeners("SERVICE", "", "ERROR", false, 0);
    }

    private void handleConnectTimeout(String caller) {
        LogManager.logE(TAG, "handleConnectTimeout: timeout in " + caller + ", restarting inference service");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(appContext, "Local inference process timeout, restarting...", Toast.LENGTH_SHORT).show();
            }
        });

        IInferenceService svc;
        synchronized (serviceLock) {
            svc = service;
        }
        if (svc != null) {
            try {
                svc.forceKillSelf();
            } catch (RemoteException e) {
                LogManager.logE(TAG, "RemoteException in forceKillSelf after timeout: " + e.getMessage(), e);
            } catch (Throwable t) {
                LogManager.logE(TAG, "Exception in forceKillSelf after timeout: " + t.getMessage(), t);
            }
        }

        synchronized (serviceLock) {
            service = null;
            binding = false;
            hasActiveTask.set(false);
            currentTaskId = null;
            serviceLock.notifyAll();
        }

        bindService();
    }
}
