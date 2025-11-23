package com.example.offlineai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * BackgroundTask represents a single long-running or user-visible task.
 * It is designed as an immutable snapshot so that it can be safely
 * shared across threads without additional synchronization.
 */
public final class BackgroundTask {
    public enum TaskType {
        KB_BUILD,
        MODEL_DOWNLOAD,
        LLM_INFERENCE,
        DIFFUSION,
        TTS_GENERATION,
        NOTE_PROCESSING,
        OTHER
    }

    public enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final TaskType type;
    private final TaskState state;
    private final int progress;
    private final String title;
    private final String message;
    private final long createdAtMs;
    private final long updatedAtMs;
    private final boolean requiresForegroundService;
    private final Map<String, String> extras;

    public BackgroundTask(
            String id,
            TaskType type,
            TaskState state,
            int progress,
            String title,
            String message,
            long createdAtMs,
            long updatedAtMs,
            boolean requiresForegroundService,
            Map<String, String> extras
    ) {
        this.id = id;
        this.type = type;
        this.state = state;
        this.progress = progress;
        this.title = title != null ? title : "";
        this.message = message != null ? message : "";
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
        this.requiresForegroundService = requiresForegroundService;
        if (extras != null && !extras.isEmpty()) {
            this.extras = Collections.unmodifiableMap(new HashMap<>(extras));
        } else {
            this.extras = Collections.emptyMap();
        }
    }

    public String getId() {
        return id;
    }

    public TaskType getType() {
        return type;
    }

    public TaskState getState() {
        return state;
    }

    public int getProgress() {
        return progress;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public boolean requiresForegroundService() {
        return requiresForegroundService;
    }

    public Map<String, String> getExtras() {
        return extras;
    }

    /**
     * Get the chat folder path associated with this task (if any).
     * This is a convenience method for LLM/TTS/Diffusion tasks.
     */
    public String getChatFolderPath() {
        return extras.get(EXTRA_CHAT_FOLDER);
    }

    /**
     * Check if this task is associated with a specific chat folder.
     */
    public boolean hasChatFolder() {
        String folder = getChatFolderPath();
        return folder != null && !folder.isEmpty();
    }

    /**
     * Check if this task is still active (PENDING or RUNNING).
     */
    public boolean isActive() {
        return state == TaskState.PENDING || state == TaskState.RUNNING;
    }

    /**
     * Check if this task requires log buffer (inference-related tasks).
     */
    public boolean requiresLogBuffer() {
        return type == TaskType.LLM_INFERENCE || 
               type == TaskType.TTS_GENERATION || 
               type == TaskType.DIFFUSION ||
               type == TaskType.KB_BUILD;
    }

    // Extra keys for common task attributes
    public static final String EXTRA_CHAT_FOLDER = "chatFolder";
    public static final String EXTRA_MODEL_NAME = "model";
    public static final String EXTRA_API_URL = "apiUrl";
    public static final String EXTRA_KB_NAME = "kbName";

    /**
     * Create a new BackgroundTask snapshot with updated state, progress and message.
     */
    public BackgroundTask withUpdate(TaskState newState, int newProgress, String newMessage, long updatedAtMs) {
        return new BackgroundTask(
                this.id,
                this.type,
                newState != null ? newState : this.state,
                newProgress,
                this.title,
                newMessage,
                this.createdAtMs,
                updatedAtMs,
                this.requiresForegroundService,
                this.extras
        );
    }
}
