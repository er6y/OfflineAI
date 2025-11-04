package com.example.offlineai.api;

import com.example.offlineai.LogManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inference Thread Manager
 * Manages all inference-related threads and provides graceful/forceful shutdown
 * 
 * Key Features:
 * - Track all active tasks (LLM, TTS, ASR, Embedding, Reranker, Diffusion)
 * - Force interrupt all threads on stop (prevent crash)
 * - Clear all flags and queues
 * - Used for both user stop and app exit
 */
public class InferenceThreadManager {
    private static final String TAG = "InferenceThreadManager";
    
    // Active tasks tracking
    private final Set<Future<?>> activeTasks = Collections.synchronizedSet(new HashSet<>());
    
    // Global stop flag (shared across all tasks)
    private final AtomicBoolean globalStopFlag = new AtomicBoolean(false);
    
    /**
     * Register a new task
     * @param task Future to track
     */
    public void registerTask(Future<?> task) {
        if (task != null) {
            activeTasks.add(task);
            LogManager.logD(TAG, "Task registered, total active: " + activeTasks.size());
        }
    }
    
    /**
     * Unregister a completed task
     * @param task Future to remove
     */
    public void unregisterTask(Future<?> task) {
        if (task != null) {
            activeTasks.remove(task);
            LogManager.logD(TAG, "Task unregistered, total active: " + activeTasks.size());
        }
    }
    
    /**
     * Get global stop flag
     * All tasks should check this flag periodically
     */
    public AtomicBoolean getGlobalStopFlag() {
        return globalStopFlag;
    }
    
    /**
     * Stop all tasks FORCEFULLY with interrupt
     * This is the aggressive approach to ensure clean state
     * 
     * Strategy:
     * 1. Set global stop flag
     * 2. Cancel all futures with interrupt=true
     * 3. Wait briefly for threads to respond
     * 4. Force clear all tasks
     * 5. Reset all flags
     * 
     * @param timeoutMs Maximum wait time before force clear (recommend 2000ms)
     * @return Number of tasks that were forcefully interrupted
     */
    public int stopAllTasksForcefully(long timeoutMs) {
        LogManager.logI(TAG, "========== FORCE STOPPING ALL TASKS ==========");
        LogManager.logI(TAG, "Active tasks before stop: " + activeTasks.size());
        
        // Step 1: Set global stop flag (all tasks should check this)
        globalStopFlag.set(true);
        
        // Step 2: Force interrupt all tasks
        int interruptedCount = 0;
        for (Future<?> task : activeTasks) {
            if (!task.isDone()) {
                task.cancel(true);  // interrupt=true, FORCE interrupt
                interruptedCount++;
                LogManager.logI(TAG, "Force interrupted task #" + interruptedCount);
            }
        }
        
        LogManager.logI(TAG, "Interrupted " + interruptedCount + " tasks, waiting " + timeoutMs + "ms for cleanup...");
        
        // Step 3: Wait briefly for threads to respond to interrupt
        long startTime = System.currentTimeMillis();
        while (!activeTasks.isEmpty() && (System.currentTimeMillis() - startTime < timeoutMs)) {
            // Remove completed tasks
            activeTasks.removeIf(Future::isDone);
            
            if (!activeTasks.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Step 4: Force clear remaining tasks (don't wait forever)
        int remainingTasks = activeTasks.size();
        if (remainingTasks > 0) {
            LogManager.logW(TAG, "⚠️ " + remainingTasks + " tasks still running after timeout, force clearing");
            activeTasks.clear();
        }
        
        LogManager.logI(TAG, "✅ All tasks stopped, interrupted=" + interruptedCount + ", remaining=" + remainingTasks);
        LogManager.logI(TAG, "========== FORCE STOP COMPLETE ==========");
        
        return interruptedCount;
    }
    
    /**
     * Reset manager for new inference
     * Call this after stopAllTasksForcefully() and before starting new inference
     */
    public void reset() {
        activeTasks.clear();
        globalStopFlag.set(false);
        LogManager.logI(TAG, "Thread manager reset, ready for new inference");
    }
    
    /**
     * Get number of active tasks
     */
    public int getActiveTaskCount() {
        // Clean up done tasks first
        activeTasks.removeIf(Future::isDone);
        return activeTasks.size();
    }
    
    /**
     * Check if any task is running
     */
    public boolean hasActiveTasks() {
        return getActiveTaskCount() > 0;
    }
}
