package com.example.offlineai;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe character ring buffer for task logs.
 * 
 * Design:
 * - 128KB character ring buffer (enough for 4096 tokens * 8 chars = 32KB, 4x margin)
 * - writePos: absolute write position (only increases, use modulo for actual index)
 * - persistedPos: position up to which data has been persisted to MD
 * - UI reads from persistedPos to writePos (unpersisted data)
 * - After MD persist, persistedPos = writePos
 * - Ring buffer naturally handles wrap-around, no explicit clear needed
 * 
 * Thread Safety:
 * - All public methods are synchronized
 * - Manager writes (append), UI reads (getUnpersistedData)
 * - No race condition on clear timing
 */
public class TaskLogBuffer {
    private static final String TAG = "TaskLogBuffer";
    
    // 128KB character ring buffer
    public static final int BUFFER_SIZE = 128 * 1024;
    
    // Ring buffer storage
    private final char[] buffer = new char[BUFFER_SIZE];
    
    // Absolute positions (only increase, use modulo for actual index)
    private long writePos = 0;       // Next write position
    private long persistedPos = 0;   // Data up to here has been persisted to MD
    
    // Debug section state
    private volatile boolean debugSectionOpen = false;
    
    // Streaming content buffer (accumulated model output, separate from log buffer)
    private final StringBuilder streamingContent = new StringBuilder();
    private static final int MAX_STREAMING_CHARS = 512 * 1024;
    
    /**
     * Create a TaskLogBuffer with default settings.
     */
    public TaskLogBuffer() {
        // Default constructor
    }
    
    /**
     * Append a log message to the ring buffer.
     * Automatically manages debug section state based on <debug> tags.
     * 
     * @param message The message to append (can contain newlines)
     */
    public synchronized void appendLog(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        // Auto-detect debug section state
        boolean prevDebugOpen = debugSectionOpen;
        if (message.contains("<debug>")) {
            debugSectionOpen = true;
        }
        if (message.contains("</debug>")) {
            debugSectionOpen = false;
        }
        if (prevDebugOpen != debugSectionOpen) {
            String preview = message.length() > 80 ? message.substring(0, 80) + "..." : message;
            LogManager.logD(TAG, "[BUF][DEBUG_STATE] debugSectionOpen=" + debugSectionOpen + ", preview=" + preview);
        }
        
        // Write to ring buffer
        for (int i = 0; i < message.length(); i++) {
            buffer[(int)(writePos % BUFFER_SIZE)] = message.charAt(i);
            writePos++;
        }
        
        // If writePos wrapped around and caught up with persistedPos, advance persistedPos
        // This means old unpersisted data is being overwritten (shouldn't happen normally)
        if (writePos - persistedPos > BUFFER_SIZE) {
            long oldPersistedPos = persistedPos;
            persistedPos = writePos - BUFFER_SIZE;
            LogManager.logW(TAG, "[BUF][OVERFLOW] Buffer overflow, advancing persistedPos: " + 
                    oldPersistedPos + " -> " + persistedPos);
        }
    }
    
    /**
     * Append streaming content (model output).
     */
    public synchronized void appendStreaming(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        
        streamingContent.append(content);
        
        // Trim from beginning if too large
        if (streamingContent.length() > MAX_STREAMING_CHARS) {
            int excess = streamingContent.length() - MAX_STREAMING_CHARS;
            streamingContent.delete(0, excess);
        }
    }
    
    /**
     * Get unpersisted data (data written after last MD persist).
     * This is what UI should poll to get new streaming data.
     * 
     * @return String containing all unpersisted data, or empty string if none
     */
    public synchronized String getUnpersistedData() {
        long unpersistedLen = writePos - persistedPos;
        if (unpersistedLen <= 0) {
            return "";
        }
        
        // Safety check: shouldn't exceed buffer size
        if (unpersistedLen > BUFFER_SIZE) {
            unpersistedLen = BUFFER_SIZE;
        }
        
        StringBuilder sb = new StringBuilder((int)unpersistedLen);
        for (long pos = persistedPos; pos < writePos; pos++) {
            sb.append(buffer[(int)(pos % BUFFER_SIZE)]);
        }
        return sb.toString();
    }
    
    /**
     * Get the current persisted position.
     * UI can use this to know where to start reading from.
     * 
     * @return The persisted position
     */
    public synchronized long getPersistedPos() {
        return persistedPos;
    }
    
    /**
     * Get the current write position.
     * 
     * @return The write position
     */
    public synchronized long getWritePos() {
        return writePos;
    }
    
    /**
     * Mark current write position as persisted.
     * Call this after successfully saving to MD file.
     */
    public synchronized void markPersisted() {
        long oldPersistedPos = persistedPos;
        persistedPos = writePos;
        LogManager.logD(TAG, "[BUF][PERSIST] Marked persisted: " + oldPersistedPos + " -> " + persistedPos);
    }
    
    /**
     * Result of reading data from a specific position.
     * Contains the data read and the new read position for next call.
     */
    public static class ReadResult {
        public final String data;
        public final long newReadPos;  // Position after this read (= writePos at read time)
        
        public ReadResult(String data, long newReadPos) {
            this.data = data;
            this.newReadPos = newReadPos;
        }
        
        public boolean hasData() {
            return data != null && !data.isEmpty();
        }
    }
    
    /**
     * Read data from a specific position to current writePos.
     * UI should call this with its own maintained readPos.
     * 
     * @param fromPos The position to start reading from
     * @return ReadResult containing data and new readPos for next call
     */
    public synchronized ReadResult getDataFromPos(long fromPos) {
        // Clamp fromPos to valid range
        long minValidPos = writePos > BUFFER_SIZE ? writePos - BUFFER_SIZE : 0;
        if (fromPos < minValidPos) {
            fromPos = minValidPos;
            LogManager.logW(TAG, "[BUF][READ] fromPos too old, clamped to " + minValidPos);
        }
        if (fromPos > writePos) {
            // Nothing to read
            return new ReadResult("", writePos);
        }
        
        long dataLen = writePos - fromPos;
        if (dataLen <= 0) {
            return new ReadResult("", writePos);
        }
        
        StringBuilder sb = new StringBuilder((int)dataLen);
        for (long pos = fromPos; pos < writePos; pos++) {
            sb.append(buffer[(int)(pos % BUFFER_SIZE)]);
        }
        return new ReadResult(sb.toString(), writePos);
    }
    
    /**
     * Get unpersisted data as a list of lines (for backward compatibility).
     * Splits the unpersisted data by newlines.
     * 
     * @return List of lines, or empty list if no unpersisted data
     */
    public synchronized List<String> getNewLogs(String consumerId) {
        // consumerId is ignored in new design - we use persistedPos instead
        String data = getUnpersistedData();
        if (data.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Split by newlines, keeping empty lines
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length(); i++) {
            if (data.charAt(i) == '\n') {
                lines.add(data.substring(start, i + 1)); // Include newline
                start = i + 1;
            }
        }
        // Add remaining content if any (no trailing newline)
        if (start < data.length()) {
            lines.add(data.substring(start));
        }
        
        return lines;
    }
    
    /**
     * Get the current cursor position for a consumer.
     * For backward compatibility - returns persistedPos.
     * 
     * @param consumerId The consumer ID (ignored)
     * @return Current persisted position
     */
    public synchronized int getConsumerCursor(String consumerId) {
        return (int)(persistedPos % BUFFER_SIZE);
    }
    
    /**
     * Get the current streaming content.
     */
    public synchronized String getStreamingContent() {
        return streamingContent.toString();
    }
    
    /**
     * Check if debug section is currently open.
     */
    public boolean isDebugSectionOpen() {
        return debugSectionOpen;
    }
    
    /**
     * Manually set debug section state.
     */
    public void setDebugSectionOpen(boolean open) {
        this.debugSectionOpen = open;
    }
    
    /**
     * Get current buffer size (unpersisted data length).
     */
    public synchronized int size() {
        return (int)(writePos - persistedPos);
    }
    
    /**
     * Get current log count (for backward compatibility).
     * Returns the number of unpersisted characters.
     */
    public synchronized int getLogCount() {
        return size();
    }
    
    /**
     * Get a snapshot of all logs (for backward compatibility).
     * Returns unpersisted data as a list of lines.
     */
    public synchronized List<String> getLogSnapshot() {
        return getNewLogs(null);
    }
    
    /**
     * Get logs from a specific index (for backward compatibility).
     * Note: In ring buffer design, startIndex is ignored - always returns unpersisted data.
     */
    public synchronized List<String> getLogsFromIndex(int startIndex) {
        // In new design, we always return unpersisted data
        // startIndex is ignored for backward compatibility
        return getNewLogs(null);
    }
    
    /**
     * Clear all data and reset positions.
     * Call this when starting a new inference task.
     */
    public synchronized void clear() {
        writePos = 0;
        persistedPos = 0;
        streamingContent.setLength(0);
        debugSectionOpen = false;
        LogManager.logD(TAG, "[BUF][CLEAR] Buffer cleared, positions reset to 0");
    }
    
    /**
     * Get a complete snapshot of the buffer state for UI restoration.
     */
    public synchronized TaskLogSnapshot getSnapshot() {
        String unpersistedData = getUnpersistedData();
        List<String> lines = new ArrayList<>();
        if (!unpersistedData.isEmpty()) {
            // Split by newlines
            int start = 0;
            for (int i = 0; i < unpersistedData.length(); i++) {
                if (unpersistedData.charAt(i) == '\n') {
                    lines.add(unpersistedData.substring(start, i + 1));
                    start = i + 1;
                }
            }
            if (start < unpersistedData.length()) {
                lines.add(unpersistedData.substring(start));
            }
        }
        return new TaskLogSnapshot(
            lines,
            streamingContent.toString(),
            debugSectionOpen
        );
    }
    
    /**
     * Immutable snapshot of task log buffer state.
     */
    public static class TaskLogSnapshot {
        public final List<String> logs;
        public final String streamingContent;
        public final boolean debugSectionOpen;
        
        public TaskLogSnapshot(List<String> logs, String streamingContent, 
                              boolean debugSectionOpen) {
            this.logs = logs;
            this.streamingContent = streamingContent;
            this.debugSectionOpen = debugSectionOpen;
        }
        
        public boolean hasLogs() {
            return logs != null && !logs.isEmpty();
        }
        
        public boolean hasStreamingContent() {
            return streamingContent != null && !streamingContent.isEmpty();
        }
    }
}
