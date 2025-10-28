package com.example.offlineai;

import android.content.Context;
import android.text.TextUtils;

import com.example.offlineai.chat.model.ChatDataItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chat History Filter
 * Filters markdown history for MNN inference:
 * - Remove emoji, markdown formatting
 * - Remove image/audio tags
 * - Extract plain text only
 */
public class ChatHistoryFilter {
    private static final String TAG = "ChatHistoryFilter";
    
    // Patterns for filtering
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\p{So}\\p{Sk}]");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.*?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*(.*?)\\*");
    private static final Pattern CODE_PATTERN = Pattern.compile("`(.*?)`");
    private static final Pattern IMAGE_MARKDOWN = Pattern.compile("🖼️ \\[图片:.*?\\]");
    // FIXED: Match complete markdown link format: 🎙️ [音频: filename (duration)](filename)
    private static final Pattern AUDIO_MARKDOWN = Pattern.compile("🎙️ \\[音频:.*?\\]\\(.*?\\)");
    private static final Pattern IMG_TAG = Pattern.compile("<img>.*?</img>");
    private static final Pattern AUDIO_TAG = Pattern.compile("<audio>.*?</audio>");
    
    /**
     * Filter text content for MNN inference
     * @param text Raw markdown text
     * @return Filtered plain text
     */
    public static String filterText(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        
        // Remove emoji
        text = EMOJI_PATTERN.matcher(text).replaceAll("");
        
        // Remove markdown formatting (keep content)
        text = BOLD_PATTERN.matcher(text).replaceAll("$1");
        text = ITALIC_PATTERN.matcher(text).replaceAll("$1");
        text = CODE_PATTERN.matcher(text).replaceAll("$1");
        
        // Remove image and audio markdown (🖼️ [图片: xxx])
        text = IMAGE_MARKDOWN.matcher(text).replaceAll("");
        text = AUDIO_MARKDOWN.matcher(text).replaceAll("");
        
        // Remove image and audio tags from history (user requirement)
        // User does NOT want to keep images in history (to avoid re-encoding)
        // Only current round will have <img>/<audio> tags
        text = IMG_TAG.matcher(text).replaceAll("");
        text = AUDIO_TAG.matcher(text).replaceAll("");
        
        // Clean up extra whitespace
        text = text.trim().replaceAll("\\s+", " ");
        
        return text;
    }
    
    /**
     * Build history for MNN inference with sliding window
     * @param context Context
     * @param allMessages All chat messages from markdown
     * @param systemPrompt System prompt (always included)
     * @param maxRounds Maximum rounds to include (0 = no history)
     * @return List of PromptItem for MNN
     */
    public static List<PromptItem> buildHistoryForInference(
            Context context, 
            List<ChatDataItem> allMessages,
            String systemPrompt,
            int maxRounds) {
        
        List<PromptItem> history = new ArrayList<>();
        
        // Always include system prompt first
        if (!TextUtils.isEmpty(systemPrompt)) {
            String filteredSystem = filterText(systemPrompt);
            history.add(new PromptItem("system", filteredSystem));
            LogManager.logD(TAG, "[HISTORY] Added system prompt: " + filteredSystem.substring(0, Math.min(50, filteredSystem.length())) + "...");
        }
        
        // If maxRounds is 0, return only system prompt
        if (maxRounds == 0) {
            LogManager.logD(TAG, "[HISTORY] Max rounds = 0, no history added");
            return history;
        }
        
        // Filter out HEADER types and extract user/assistant messages
        List<ChatDataItem> validMessages = new ArrayList<>();
        for (ChatDataItem item : allMessages) {
            if (item.getType() != com.example.offlineai.chat.chatlist.ChatViewHolders.HEADER) {
                validMessages.add(item);
            }
        }
        
        // Calculate sliding window start index
        int totalMessages = validMessages.size();
        int maxMessages = maxRounds * 2;  // Each round has user + assistant
        int startIndex = Math.max(0, totalMessages - maxMessages);
        
        LogManager.logD(TAG, "[HISTORY] Total messages: " + totalMessages + ", max rounds: " + maxRounds + 
                             ", window: [" + startIndex + ", " + totalMessages + ")");
        
        // Build history from sliding window
        for (int i = startIndex; i < totalMessages; i++) {
            ChatDataItem item = validMessages.get(i);
            
            // Determine role
            boolean isUser = (item.getType() == com.example.offlineai.chat.chatlist.ChatViewHolders.USER);
            String role = isUser ? "user" : "assistant";
            
            // Get and filter content (use displayText which includes text content)
            String content = item.getDisplayText();
            if (TextUtils.isEmpty(content)) {
                LogManager.logW(TAG, "[HISTORY] Empty content at index " + i + ", skipping");
                continue;
            }
            
            // Skip AI messages with generated images (Diffusion models)
            // AI生成的图片对话不加入历史，因为扩散生成与LLM推理无关
            if (!isUser && content.contains("🖼️ [图片:")) {
                LogManager.logI(TAG, "[HISTORY] Skipping AI-generated image message at index " + i);
                continue;
            }
            
            // Filter content: removes markdown, emoji, and <img>/<audio> tags
            // KEEP TEXT CONTENT, only remove multimedia tags
            String filteredContent = filterText(content);
            
            if (TextUtils.isEmpty(filteredContent)) {
                LogManager.logW(TAG, "[HISTORY] Filtered content is empty at index " + i + ", skipping");
                continue;
            }
            
            history.add(new PromptItem(role, filteredContent));
            LogManager.logD(TAG, "[HISTORY] Added " + role + ": " + 
                                 filteredContent.substring(0, Math.min(50, filteredContent.length())) + "...");
        }
        
        // Calculate actual history messages (excluding system prompt)
        int systemPromptCount = TextUtils.isEmpty(systemPrompt) ? 0 : 1;
        int historyMessages = history.size() - systemPromptCount;
        int historyRounds = historyMessages / 2;
        
        LogManager.logI(TAG, "[HISTORY] Built history with " + historyMessages + " messages (" + 
                             historyRounds + " rounds)");
        
        if (historyMessages == 0 && maxRounds > 0) {
            LogManager.logW(TAG, "[HISTORY] No usable history found (all messages contain images or are empty)");
        }
        
        return history;
    }
    
    /**
     * PromptItem: (role, content) pair for MNN history
     * role: "system", "user", or "assistant"
     */
    public static class PromptItem {
        public final String role;
        public final String content;
        
        public PromptItem(String role, String content) {
            this.role = role;
            this.content = content;
        }
        
        @Override
        public String toString() {
            return role + ": " + content.substring(0, Math.min(30, content.length())) + "...";
        }
    }
}
