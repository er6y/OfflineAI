package com.example.offlineai.agent;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.example.offlineai.LogManager;
import com.example.offlineai.agent.core.AgentEngine;
import com.example.offlineai.agent.model.AgentAction;
import com.example.offlineai.agent.model.ExecutionResult;
import com.example.offlineai.agent.parser.ActionParser;
import com.example.offlineai.agent.utils.AccessibilityPermissionHelper;
import com.example.offlineai.agent.utils.ScreenshotCapture;

import org.jetbrains.annotations.NotNull;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/**
 * Agent Manager - Java bridge for Agent functionality
 * Provides easy-to-use Java API for RagQaFragment integration
 */
public class AgentManager {
    
    private static final String TAG = "AgentManager";
    
    private static AgentManager instance;
    
    private final Context context;
    private final AgentEngine engine;
    private final Handler mainHandler;
    private final ScreenshotCapture screenshotCapture;
    
    private AgentCallback callback;
    private String pendingTaskGoal;
    private com.example.offlineai.RagQueryManager pendingRagQueryManager;
    private boolean waitingForMediaProjection = false;
    
    /**
     * Callback interface for agent events
     */
    public interface AgentCallback {
        void onAgentActionDetected(String actionType);
        void onAgentActionCompleted(boolean success, String message);
        void onAgentError(String error);
        void onAgentAnswer(String text);
        void onRequestAccessibilityPermission();
    }
    
    private AgentManager(Context context) {
        this.context = context.getApplicationContext();
        this.engine = new AgentEngine(this.context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.screenshotCapture = new ScreenshotCapture(this.context);
        
        // Set engine callback
        engine.setCallback(new AgentEngine.AgentCallback() {
            @Override
            public void onStepStarted(int stepIndex) {
                // Step start logged in AgentEngine
            }
            
            @Override
            public void onStepCompleted(int stepIndex, @NotNull AgentAction action, @NotNull ExecutionResult result) {
                if (callback != null) {
                    callback.onAgentActionCompleted(result.getSuccess(), result.getMessage());
                }
            }
            
            @Override
            public void onTaskCompleted(boolean success, @NotNull String message) {
                LogManager.logI(TAG, "Task completed: " + (success ? "success" : "fail"));
                if (callback != null) {
                    callback.onAgentActionCompleted(success, message);
                }
            }
            
            @Override
            public void onError(@NotNull String error) {
                LogManager.logE(TAG, "Agent error: " + error);
                if (callback != null) {
                    callback.onAgentError(error);
                }
            }
            
            @Override
            public Object onAskUser(@NotNull String question, @NotNull kotlin.coroutines.Continuation<? super String> continuation) {
                // AgentManager path has no floating window UI; return empty string synchronously
                return "";
            }
        });
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized AgentManager getInstance(Context context) {
        if (instance == null) {
            instance = new AgentManager(context);
        }
        return instance;
    }
    
    /**
     * Set callback for agent events
     */
    public void setCallback(AgentCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Check if accessibility service is enabled
     */
    public boolean isAccessibilityServiceEnabled() {
        return AccessibilityPermissionHelper.INSTANCE.isAccessibilityServiceEnabled(context);
    }
    
    /**
     * Open accessibility settings
     */
    public void openAccessibilitySettings() {
        AccessibilityPermissionHelper.INSTANCE.openAccessibilitySettings(context);
    }
    
    /**
     * Get instructions for enabling accessibility service
     */
    public String getEnableInstructions() {
        return AccessibilityPermissionHelper.INSTANCE.getEnableInstructions();
    }
    
    /**
     * Check if model output contains agent action
     */
    public boolean containsAgentAction(String modelOutput) {
        return ActionParser.INSTANCE.containsAgentAction(modelOutput);
    }
    
    /**
     * Execute agent action from model output
     * This is called when <tool_call> is detected in streaming output
     */
    public void executeFromModelOutput(final String modelOutput, final Bitmap screenshot) {
        if (!isAccessibilityServiceEnabled()) {
            LogManager.logE(TAG, "Accessibility service not enabled");
            if (callback != null) {
                mainHandler.post(() -> callback.onAgentError("需要开启无障碍服务才能使用Agent功能"));
            }
            return;
        }
        
        LogManager.logI(TAG, "Executing agent from model output");
        
        // Execute in background thread
        new Thread(() -> {
            try {
                // Create a simple continuation for suspend function
                Continuation<ExecutionResult> continuation = new Continuation<ExecutionResult>() {
                    @NotNull
                    @Override
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    @Override
                    public void resumeWith(@NotNull Object o) {
                        if (o instanceof ExecutionResult) {
                            ExecutionResult result = (ExecutionResult) o;
                            LogManager.logI(TAG, "Agent execution result: " + result.getMessage());
                        }
                    }
                };
                
                // Call suspend function
                Object result = engine.executeFromModelOutput(modelOutput, screenshot, continuation);
                
                // Handle result if it's not COROUTINE_SUSPENDED
                if (result instanceof ExecutionResult) {
                    ExecutionResult execResult = (ExecutionResult) result;
                    LogManager.logI(TAG, "Agent execution completed: " + execResult.getMessage());
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Agent execution failed: " + e.getMessage());
                e.printStackTrace();
                if (callback != null) {
                    mainHandler.post(() -> callback.onAgentError("执行失败: " + e.getMessage()));
                }
            }
        }).start();
    }
    
    /**
     * Start Agent autonomous loop execution via AgentAccessibilityService
     * This will run Agent in a loop: screenshot -> inference -> action -> repeat
     */
    public void startAgentLoop(final String taskGoal, final com.example.offlineai.RagQueryManager ragQueryManager) {
        if (!isAccessibilityServiceEnabled()) {
            LogManager.logE(TAG, "Accessibility service not enabled");
            if (callback != null) {
                mainHandler.post(() -> {
                    callback.onAgentError("需要开启无障碍服务才能使用Agent功能");
                    // Prompt user to enable accessibility service
                    callback.onRequestAccessibilityPermission();
                });
            }
            return;
        }
        
        // Check if MediaProjection is initialized
        if (!isMediaProjectionInitialized()) {
            LogManager.logI(TAG, "MediaProjection not initialized, requesting permission");
            pendingTaskGoal = taskGoal;
            pendingRagQueryManager = ragQueryManager;
            requestMediaProjectionPermission();
            return;
        }
        
        // Get AgentAccessibilityService instance
        com.example.offlineai.agent.service.AgentAccessibilityService service = 
            com.example.offlineai.agent.service.AgentAccessibilityService.Companion.getInstance();
        
        if (service == null) {
            LogManager.logE(TAG, "AgentAccessibilityService instance is null");
            if (callback != null) {
                mainHandler.post(() -> callback.onAgentError("无障碍服务未运行"));
            }
            return;
        }
        
        LogManager.logI(TAG, "Starting Agent loop execution: " + taskGoal);
        
        // Set ScreenshotCapture in AgentEngine
        engine.setScreenshotCapture(screenshotCapture);
        
        // Set references in Service
        service.setRagQueryManager(ragQueryManager);
        service.setAgentEngine(engine);
        
        // Start Agent loop
        service.startAgentLoop(taskGoal);
    }
    
    /**
     * Check if MediaProjection is initialized
     */
    private boolean isMediaProjectionInitialized() {
        return screenshotCapture.isInitialized();
    }
    
    /**
     * Request MediaProjection permission
     */
    private void requestMediaProjectionPermission() {
        if (waitingForMediaProjection) {
            LogManager.logW(TAG, "Already waiting for MediaProjection permission");
            return;
        }
        
        waitingForMediaProjection = true;
        MediaProjectionPermissionActivity.start(context);
    }
    
    /**
     * Called when MediaProjection permission is granted
     */
    public void onMediaProjectionGranted(int resultCode, Intent data) {
        LogManager.logI(TAG, "MediaProjection permission granted, initializing ScreenshotCapture");
        waitingForMediaProjection = false;
        
        // CRITICAL: Start foreground service with mediaProjection type BEFORE initializing MediaProjection
        // This is required on Android 14+ to avoid SecurityException
        Intent serviceIntent = new Intent(context, com.example.offlineai.UnifiedForegroundService.class);
        serviceIntent.putExtra("media_projection", true);
        context.startForegroundService(serviceIntent);
        LogManager.logI(TAG, "Started foreground service with mediaProjection type");
        
        // Wait a bit for service to start
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Initialize ScreenshotCapture
        screenshotCapture.initMediaProjection(resultCode, data);
        
        // Resume pending Agent loop if exists
        if (pendingTaskGoal != null && pendingRagQueryManager != null) {
            LogManager.logI(TAG, "Resuming pending Agent loop");
            String taskGoal = pendingTaskGoal;
            com.example.offlineai.RagQueryManager ragQueryManager = pendingRagQueryManager;
            pendingTaskGoal = null;
            pendingRagQueryManager = null;
            
            startAgentLoop(taskGoal, ragQueryManager);
        }
    }
    
    /**
     * Called when MediaProjection permission is denied
     */
    public void onMediaProjectionDenied() {
        LogManager.logW(TAG, "MediaProjection permission denied");
        waitingForMediaProjection = false;
        pendingTaskGoal = null;
        pendingRagQueryManager = null;
        
        if (callback != null) {
            mainHandler.post(() -> callback.onAgentError("需要屏幕录制权限才能使用Agent截图功能"));
        }
    }
    
    /**
     * Stop Agent loop execution
     */
    public void stopAgentLoop() {
        com.example.offlineai.agent.service.AgentAccessibilityService service = 
            com.example.offlineai.agent.service.AgentAccessibilityService.Companion.getInstance();
        
        if (service != null) {
            service.stopAgentLoop();
            LogManager.logI(TAG, "Agent loop stopped");
        }
    }
    
    /**
     * Stop current agent execution
     */
    public void stop() {
        engine.stop();
        stopAgentLoop();
    }
    
    /**
     * Release resources
     */
    public void release() {
        engine.release();
    }
    
    /**
     * Get AgentEngine instance (for Service access)
     */
    public AgentEngine getEngine() {
        return engine;
    }
    
    /**
     * Check if agent is currently running (delegates to AgentEngine)
     * This is the single source of truth for agent execution state
     */
    public boolean isAgentRunning() {
        return engine.isRunning();
    }
}
