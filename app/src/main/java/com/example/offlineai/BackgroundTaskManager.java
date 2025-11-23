package com.example.offlineai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.offlineai.TaskLogBuffer;
import com.example.offlineai.TaskLogBuffer.TaskLogSnapshot;

/**
 * BackgroundTaskManager is the central in-memory registry for all
 * long-running or user-visible background tasks.
 *
 * It does not perform any I/O or business logic by itself. Instead, it
 * provides a thread-safe way to
 * 1) create tasks,
 * 2) update task state/progress,
 * 3) query active tasks, and
 * 4) notify listeners about task changes.
 */
public class BackgroundTaskManager {
    private static final String TAG = "BackgroundTaskManager";

    public interface TaskListener {
        /**
         * Called whenever a task is created or updated.
         * This method is invoked on the calling thread of the update.
         * UI code should always re-post to the main thread if needed.
         */
        void onTaskChanged(BackgroundTask taskSnapshot);
    }

    private static volatile BackgroundTaskManager sInstance;

    private final ConcurrentHashMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TaskListener> listeners = new CopyOnWriteArrayList<>();
    
    // Log buffers for tasks that require logging (separate from immutable task snapshots)
    private final ConcurrentHashMap<String, TaskLogBuffer> taskLogBuffers = new ConcurrentHashMap<>();
    
    // Listeners for log events (UI components can subscribe to receive real-time log updates)
    private final CopyOnWriteArrayList<LogListener> logListeners = new CopyOnWriteArrayList<>();

    private BackgroundTaskManager() {
    }
    
    /**
     * Listener interface for log events.
     */
    public interface LogListener {
        /**
         * Called when a new log line is appended to a task.
         * This method is invoked on the calling thread.
         */
        void onLogAppended(String taskId, String logLine);
        
        /**
         * Called when streaming content is appended to a task.
         */
        default void onStreamingAppended(String taskId, String content) {}
    }

    public static BackgroundTaskManager getInstance() {
        if (sInstance == null) {
            synchronized (BackgroundTaskManager.class) {
                if (sInstance == null) {
                    sInstance = new BackgroundTaskManager();
                }
            }
        }
        return sInstance;
    }

    /**
     * Register a task listener. Duplicate registrations are ignored.
     */
    public void addListener(TaskListener listener) {
        if (listener == null) {
            return;
        }
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister a task listener.
     */
    public void removeListener(TaskListener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    /**
     * Register a log listener.
     */
    public void addLogListener(LogListener listener) {
        if (listener == null) return;
        if (!logListeners.contains(listener)) {
            logListeners.add(listener);
        }
    }
    
    /**
     * Unregister a log listener.
     */
    public void removeLogListener(LogListener listener) {
        if (listener == null) return;
        logListeners.remove(listener);
    }

    /**
     * Create a new background task and return its snapshot.
     */
    public BackgroundTask createTask(BackgroundTask.TaskType type,
                                     String title,
                                     boolean requiresForegroundService,
                                     Map<String, String> extras) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        BackgroundTask task = new BackgroundTask(
                id,
                type != null ? type : BackgroundTask.TaskType.OTHER,
                BackgroundTask.TaskState.PENDING,
                0,
                title,
                "",
                now,
                now,
                requiresForegroundService,
                extras
        );
        tasks.put(id, task);
        
        // Create log buffer for tasks that need logging
        if (task.requiresLogBuffer()) {
            taskLogBuffers.put(id, new TaskLogBuffer());
            LogManager.logD(TAG, "Created log buffer for task: id=" + id);
        }
        
        notifyListeners(task);
        LogManager.logD(TAG, "Created background task: id=" + id + ", type=" + type + ", title=" + title);
        return task;
    }

    /**
     * Update an existing task. If the task does not exist, this is a no-op.
     */
    public void updateTask(String taskId,
                           BackgroundTask.TaskState newState,
                           int newProgress,
                           String newMessage) {
        if (taskId == null) {
            return;
        }
        BackgroundTask existing = tasks.get(taskId);
        if (existing == null) {
            LogManager.logW(TAG, "updateTask called for non-existing task id=" + taskId);
            return;
        }
        long now = System.currentTimeMillis();
        BackgroundTask updated = existing.withUpdate(newState, clampProgress(newProgress), newMessage, now);
        tasks.put(taskId, updated);
        notifyListeners(updated);
        LogManager.logD(TAG, "Updated background task: id=" + taskId + ", state=" + newState + ", progress=" + newProgress);
    }

    /**
     * Get a snapshot of a task by id.
     */
    public BackgroundTask getTask(String taskId) {
        if (taskId == null) {
            return null;
        }
        return tasks.get(taskId);
    }

    /**
     * Return a snapshot list of all tasks that are still active
     * (PENDING or RUNNING).
     */
    public List<BackgroundTask> getActiveTasks() {
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            BackgroundTask.TaskState state = task.getState();
            if (state == BackgroundTask.TaskState.PENDING || state == BackgroundTask.TaskState.RUNNING) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Return a snapshot list of all active tasks that explicitly require
     * a foreground service for proper execution.
     */
    public List<BackgroundTask> getActiveForegroundTasks() {
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            BackgroundTask.TaskState state = task.getState();
            if ((state == BackgroundTask.TaskState.PENDING || state == BackgroundTask.TaskState.RUNNING)
                    && task.requiresForegroundService()) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Helper to clamp progress into the [0,100] range.
     */
    private int clampProgress(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }

    private void notifyListeners(BackgroundTask task) {
        if (listeners.isEmpty() || task == null) {
            return;
        }
        for (TaskListener listener : listeners) {
            try {
                listener.onTaskChanged(task);
            } catch (Throwable t) {
                LogManager.logE(TAG, "TaskListener error in onTaskChanged: " + t.getMessage(), t);
            }
        }
    }
    
    // ==================== Log Buffer Management ====================
    
    /**
     * Append a log line to a task's log buffer.
     * @param taskId The task ID
     * @param message The log message
     */
    public void appendLog(String taskId, String message) {
        if (taskId == null || message == null) return;
        
        TaskLogBuffer buffer = taskLogBuffers.get(taskId);
        if (buffer == null) {
            LogManager.logW(TAG, "No log buffer for task: " + taskId);
            return;
        }
        
        buffer.appendLog(message);
        
        // Notify log listeners
        for (LogListener listener : logListeners) {
            try {
                listener.onLogAppended(taskId, message);
            } catch (Throwable t) {
                LogManager.logE(TAG, "LogListener error: " + t.getMessage(), t);
            }
        }
    }
    
    /**
     * Append streaming content to a task's log buffer.
     */
    public void appendStreaming(String taskId, String content) {
        if (taskId == null || content == null) return;
        
        TaskLogBuffer buffer = taskLogBuffers.get(taskId);
        if (buffer == null) return;
        
        buffer.appendStreaming(content);
        
        // Notify log listeners
        for (LogListener listener : logListeners) {
            try {
                listener.onStreamingAppended(taskId, content);
            } catch (Throwable t) {
                LogManager.logE(TAG, "LogListener error: " + t.getMessage(), t);
            }
        }
    }
    
    /**
     * Get the log buffer for a task.
     */
    public TaskLogBuffer getLogBuffer(String taskId) {
        if (taskId == null) return null;
        return taskLogBuffers.get(taskId);
    }
    
    /**
     * Get a snapshot of a task's logs.
     */
    public List<String> getLogSnapshot(String taskId) {
        TaskLogBuffer buffer = getLogBuffer(taskId);
        if (buffer == null) return new ArrayList<>();
        return buffer.getLogSnapshot();
    }
    
    /**
     * Get logs from a specific index for a task.
     * @param taskId The task ID
     * @param fromIndex The starting index (0-based)
     * @return Logs from fromIndex to the end
     */
    public List<String> getLogsFromIndex(String taskId, int fromIndex) {
        TaskLogBuffer buffer = getLogBuffer(taskId);
        if (buffer == null) return new ArrayList<>();
        return buffer.getLogsFromIndex(fromIndex);
    }
    
    /**
     * Get complete log snapshot for UI restoration.
     */
    public TaskLogSnapshot getTaskLogSnapshot(String taskId) {
        TaskLogBuffer buffer = getLogBuffer(taskId);
        if (buffer == null) return null;
        return buffer.getSnapshot();
    }
    
    /**
     * Clear log buffer for a task.
     */
    public void clearLogBuffer(String taskId) {
        TaskLogBuffer buffer = taskLogBuffers.get(taskId);
        if (buffer != null) {
            buffer.clear();
        }
    }
    
    /**
     * Remove log buffer for a completed/failed/cancelled task.
     * Call this when task is fully done and logs are no longer needed.
     */
    public void removeLogBuffer(String taskId) {
        taskLogBuffers.remove(taskId);
    }
    
    // ==================== Task Query Methods ====================
    
    /**
     * Find an active task by chat folder path.
     * Returns the first active (PENDING or RUNNING) task for the given folder.
     */
    public BackgroundTask findActiveTaskByChatFolder(String chatFolderPath) {
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return null;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && chatFolderPath.equals(task.getChatFolderPath())) {
                return task;
            }
        }
        return null;
    }
    
    /**
     * Find all active tasks for a chat folder.
     */
    public List<BackgroundTask> findActiveTasksByChatFolder(String chatFolderPath) {
        List<BackgroundTask> result = new ArrayList<>();
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return result;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && chatFolderPath.equals(task.getChatFolderPath())) {
                result.add(task);
            }
        }
        return result;
    }
    
    /**
     * Find active tasks by type.
     */
    public List<BackgroundTask> findActiveTasksByType(BackgroundTask.TaskType type) {
        List<BackgroundTask> result = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && task.getType() == type) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * Find the latest task by type based on updatedAtMs.
     * This is useful for restoring UI state for long-running tasks
     * such as KB_BUILD after Fragment recreation.
     */
    public BackgroundTask findLatestTaskByType(BackgroundTask.TaskType type) {
        BackgroundTask latest = null;
        for (BackgroundTask task : tasks.values()) {
            if (task.getType() != type) {
                continue;
            }
            if (latest == null || task.getUpdatedAtMs() > latest.getUpdatedAtMs()) {
                latest = task;
            }
        }
        return latest;
    }
    
    /**
     * Find active LLM inference task for a chat folder.
     */
    public BackgroundTask findActiveLlmTask(String chatFolderPath) {
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return null;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && 
                task.getType() == BackgroundTask.TaskType.LLM_INFERENCE &&
                chatFolderPath.equals(task.getChatFolderPath())) {
                return task;
            }
        }
        return null;
    }
    
    /**
     * Find active TTS task for a chat folder.
     */
    public BackgroundTask findActiveTtsTask(String chatFolderPath) {
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return null;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && 
                task.getType() == BackgroundTask.TaskType.TTS_GENERATION &&
                chatFolderPath.equals(task.getChatFolderPath())) {
                return task;
            }
        }
        return null;
    }
    
    /**
     * Find active Diffusion task for a chat folder.
     */
    public BackgroundTask findActiveDiffusionTask(String chatFolderPath) {
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return null;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (task.isActive() && 
                task.getType() == BackgroundTask.TaskType.DIFFUSION &&
                chatFolderPath.equals(task.getChatFolderPath())) {
                return task;
            }
        }
        return null;
    }
    
    /**
     * Check if there are any active inference tasks for a chat folder.
     * Includes LLM, TTS, and Diffusion tasks.
     */
    public boolean hasActiveInferenceTask(String chatFolderPath) {
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return false;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (!task.isActive()) continue;
            if (!chatFolderPath.equals(task.getChatFolderPath())) continue;
            
            BackgroundTask.TaskType type = task.getType();
            if (type == BackgroundTask.TaskType.LLM_INFERENCE ||
                type == BackgroundTask.TaskType.TTS_GENERATION ||
                type == BackgroundTask.TaskType.DIFFUSION) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get summary of active tasks for a chat folder.
     * Returns a TaskSummary with flags for each task type.
     */
    public TaskSummary getActiveTaskSummary(String chatFolderPath) {
        TaskSummary summary = new TaskSummary();
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            return summary;
        }
        
        for (BackgroundTask task : tasks.values()) {
            if (!task.isActive()) continue;
            if (!chatFolderPath.equals(task.getChatFolderPath())) continue;
            
            switch (task.getType()) {
                case LLM_INFERENCE:
                    summary.hasLlmTask = true;
                    summary.llmTaskId = task.getId();
                    break;
                case TTS_GENERATION:
                    summary.hasTtsTask = true;
                    summary.ttsTaskId = task.getId();
                    break;
                case DIFFUSION:
                    summary.hasDiffusionTask = true;
                    summary.diffusionTaskId = task.getId();
                    break;
                default:
                    break;
            }
        }
        
        summary.hasAnyTask = summary.hasLlmTask || summary.hasTtsTask || summary.hasDiffusionTask;
        return summary;
    }
    
    /**
     * Summary of active tasks for a chat folder.
     */
    public static class TaskSummary {
        public boolean hasAnyTask = false;
        public boolean hasLlmTask = false;
        public boolean hasTtsTask = false;
        public boolean hasDiffusionTask = false;
        public String llmTaskId = null;
        public String ttsTaskId = null;
        public String diffusionTaskId = null;
    }
    
    /**
     * Clean up completed tasks older than the specified duration.
     * @param maxAgeMs Maximum age in milliseconds for completed tasks
     */
    public void cleanupOldTasks(long maxAgeMs) {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, BackgroundTask> entry : tasks.entrySet()) {
            BackgroundTask task = entry.getValue();
            if (!task.isActive()) {
                long age = now - task.getUpdatedAtMs();
                if (age > maxAgeMs) {
                    toRemove.add(entry.getKey());
                }
            }
        }
        
        for (String id : toRemove) {
            tasks.remove(id);
            taskLogBuffers.remove(id);
            LogManager.logD(TAG, "Cleaned up old task: " + id);
        }
    }
}
