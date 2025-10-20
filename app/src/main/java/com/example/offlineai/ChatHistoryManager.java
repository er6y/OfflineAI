package com.example.offlineai;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.example.offlineai.chat.chatlist.ChatViewHolders;
import com.example.offlineai.chat.model.ChatDataItem;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 对话历史管理器
 * 负责对话的保存、加载、删除等操作
 */
public class ChatHistoryManager {
    private static final String TAG = "ChatHistoryManager";
    private static final String CONVERSATION_FILE_NAME = "conversation.md";
    private static final String IMAGE_PREFIX = "img_";
    private static final String SEPARATOR = "\n\n---\n\n";
    
    /**
     * 对话历史列表项
     */
    public static class ChatHistoryItem {
        public String folderPath;
        public String folderName;
        public String preview; // 前20个字符预览
        public long timestamp; // 用于排序
        
        public ChatHistoryItem(String folderPath, String folderName, String preview, long timestamp) {
            this.folderPath = folderPath;
            this.folderName = folderName;
            this.preview = preview;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 创建新的对话文件夹
     * @param context 上下文
     * @return 新创建的文件夹路径，失败返回null
     */
    public static String createNewChatFolder(Context context) {
        try {
            String chatHistoryPath = ConfigManager.getString(context, 
                ConfigManager.KEY_CHAT_HISTORY_PATH, 
                ConfigManager.DEFAULT_CHAT_HISTORY_PATH);
            
            // 确保父目录存在
            File parentDir = new File(chatHistoryPath);
            if (!parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    LogManager.logE(TAG, "Failed to create chat history directory: " + chatHistoryPath);
                    return null;
                }
            }
            
            // 生成文件夹名：chat_YYYYMMDD_HHmmss
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String folderName = "chat_" + sdf.format(new Date());
            
            File chatFolder = new File(parentDir, folderName);
            if (!chatFolder.exists()) {
                if (!chatFolder.mkdirs()) {
                    LogManager.logE(TAG, "Failed to create chat folder: " + chatFolder.getAbsolutePath());
                    return null;
                }
            }
            
            LogManager.logD(TAG, "Created new chat folder: " + chatFolder.getAbsolutePath());
            return chatFolder.getAbsolutePath();
        } catch (Exception e) {
            LogManager.logE(TAG, "Error creating new chat folder", e);
            return null;
        }
    }
    
    /**
     * 保存对话到Markdown文件
     * @param context 上下文
     * @param messages 对话消息列表
     * @param folderPath 对话文件夹路径
     * @return 是否保存成功
     */
    public static boolean saveConversation(Context context, List<ChatDataItem> messages, String folderPath) {
        if (messages == null || messages.isEmpty()) {
            LogManager.logD(TAG, "No messages to save");
            return false;
        }
        
        if (TextUtils.isEmpty(folderPath)) {
            LogManager.logE(TAG, "Folder path is empty");
            return false;
        }
        
        File folder = new File(folderPath);
        if (!folder.exists()) {
            LogManager.logE(TAG, "Folder does not exist: " + folderPath);
            return false;
        }
        
        try {
            File conversationFile = new File(folder, CONVERSATION_FILE_NAME);
            BufferedWriter writer = new BufferedWriter(new FileWriter(conversationFile, false)); // 覆盖模式
            
            int writtenCount = 0; // 记录实际写入的消息数量
            for (int i = 0; i < messages.size(); i++) {
                ChatDataItem item = messages.get(i);
                
                // 跳过HEADER类型
                if (item.getType() == ChatViewHolders.HEADER) {
                    continue;
                }
                
                // 写入分隔符（第一条实际消息除外）
                if (writtenCount > 0) {
                    writer.write(SEPARATOR);
                }
                
                // 写入markdown内容
                String markdown = chatItemToMarkdown(item, folderPath);
                writer.write(markdown);
                writtenCount++;
            }
            
            writer.close();
            LogManager.logD(TAG, "Conversation saved successfully to: " + conversationFile.getAbsolutePath());
            return true;
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Error saving conversation", e);
            return false;
        }
    }
    
    /**
     * 将ChatDataItem转换为Markdown文本
     * @param item ChatDataItem对象
     * @param folderPath 对话文件夹路径
     * @return Markdown文本
     */
    private static String chatItemToMarkdown(ChatDataItem item, String folderPath) {
        StringBuilder markdown = new StringBuilder();
        
        // 添加消息类型标题
        if (item.getType() == ChatViewHolders.USER) {
            markdown.append("## 用户");
        } else if (item.getType() == ChatViewHolders.ASSISTANT) {
            markdown.append("## AI助手");
        }
        
        // 添加时间戳（如果有）
        if (!TextUtils.isEmpty(item.time)) {
            markdown.append(" (").append(item.time).append(")");
        }
        markdown.append("\n\n");
        
        // 处理用户消息
        if (item.getType() == ChatViewHolders.USER) {
            // 用户消息：先输出图片，再输出文本
            // 注意：图片已经由ImageThumbnailAdapter预处理并保存到对话文件夹了，无需复制
            if (item.imageUri != null) {
                String imagePath = item.imageUri.getPath();
                if (!TextUtils.isEmpty(imagePath)) {
                    File imageFile = new File(imagePath);
                    // 只记录图片文件名（相对路径）
                    markdown.append("![](").append(imageFile.getName()).append(")\n\n");
                    LogManager.logD(TAG, "Image reference added: " + imageFile.getName());
                }
            }
            
            // 输出用户文本（优先使用displayText，因为它可能经过处理）
            String userText = !TextUtils.isEmpty(item.getDisplayText()) ? item.getDisplayText() : item.text;
            if (!TextUtils.isEmpty(userText)) {
                markdown.append(userText.trim()).append("\n\n");
            }
        } 
        // 处理AI回复
        else if (item.getType() == ChatViewHolders.ASSISTANT) {
            // AI回复：按顺序输出思考、调试、正文、图片、性能
            
            // 添加思考内容（如果有）
            if (!TextUtils.isEmpty(item.getThinkingText())) {
                markdown.append("<think>\n");
                markdown.append(item.getThinkingText().trim());
                markdown.append("\n</think>\n\n");
            }
            
            // 添加调试信息（如果有）
            if (!TextUtils.isEmpty(item.getDebugText())) {
                markdown.append("<debug>\n");
                markdown.append(item.getDebugText().trim());
                markdown.append("\n</debug>\n\n");
            }
            
            // 添加AI回复正文（只输出displayText，避免与text重复）
            if (!TextUtils.isEmpty(item.getDisplayText())) {
                markdown.append(item.getDisplayText().trim()).append("\n\n");
            }
            
            // 添加图片（如果有，Diffusion生成的图片）
            if (item.imageUri != null) {
                String imagePath = item.imageUri.getPath();
                if (!TextUtils.isEmpty(imagePath)) {
                    File imageFile = new File(imagePath);
                    // 只记录图片文件名（相对路径）
                    markdown.append("![](").append(imageFile.getName()).append(")\n\n");
                    LogManager.logD(TAG, "AI image reference added: " + imageFile.getName());
                }
            }
            
            // 添加性能信息（如果有）
            if (!TextUtils.isEmpty(item.getPerformanceText())) {
                markdown.append("<performance>\n");
                markdown.append(item.getPerformanceText().trim());
                markdown.append("\n</performance>\n\n");
            }
        }
        
        return markdown.toString().trim();
    }
    
    /**
     * 从Markdown文件加载对话
     * @param context 上下文
     * @param folderPath 对话文件夹路径
     * @return 对话消息列表，失败返回null
     */
    public static List<ChatDataItem> loadConversation(Context context, String folderPath) {
        if (TextUtils.isEmpty(folderPath)) {
            LogManager.logE(TAG, "Folder path is empty");
            return null;
        }
        
        File folder = new File(folderPath);
        if (!folder.exists()) {
            LogManager.logE(TAG, "Folder does not exist: " + folderPath);
            return null;
        }
        
        File conversationFile = new File(folder, CONVERSATION_FILE_NAME);
        if (!conversationFile.exists()) {
            LogManager.logD(TAG, "Conversation file does not exist: " + conversationFile.getAbsolutePath());
            return null;
        }
        
        try {
            // 读取整个文件内容
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(conversationFile));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            
            // 按分隔符分割消息
            String[] messageParts = content.toString().split(SEPARATOR);
            List<ChatDataItem> messages = new ArrayList<>();
            
            for (String part : messageParts) {
                if (TextUtils.isEmpty(part.trim())) {
                    continue;
                }
                
                ChatDataItem item = markdownToChatItem(part.trim(), folderPath);
                if (item != null) {
                    messages.add(item);
                }
            }
            
            LogManager.logD(TAG, "Loaded " + messages.size() + " messages from: " + conversationFile.getAbsolutePath());
            return messages;
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Error loading conversation", e);
            return null;
        }
    }
    
    /**
     * 将Markdown文本转换为ChatDataItem
     * @param markdown Markdown文本
     * @param folderPath 对话文件夹路径
     * @return ChatDataItem对象
     */
    private static ChatDataItem markdownToChatItem(String markdown, String folderPath) {
        try {
            // 确定消息类型
            int type = ChatViewHolders.USER;
            String timeStr = null;
            
            // 解析标题行
            String[] lines = markdown.split("\n", 2);
            if (lines.length > 0) {
                String headerLine = lines[0];
                if (headerLine.startsWith("## ")) {
                    String header = headerLine.substring(3).trim();
                    if (header.startsWith("AI助手") || header.startsWith("AI")) {
                        type = ChatViewHolders.ASSISTANT;
                    }
                    
                    // 提取时间戳
                    int timeStart = header.indexOf("(");
                    int timeEnd = header.indexOf(")");
                    if (timeStart >= 0 && timeEnd > timeStart) {
                        timeStr = header.substring(timeStart + 1, timeEnd).trim();
                    }
                }
            }
            
            // 创建ChatDataItem
            ChatDataItem item = new ChatDataItem(type);
            item.time = timeStr;
            
            // 解析消息体内容（去掉标题行）
            String bodyContent = lines.length > 1 ? lines[1].trim() : "";
            
            // 处理用户消息：可能包含图片
            if (type == ChatViewHolders.USER) {
                // 提取图片（如果有）
                if (bodyContent.contains("![](")) {
                    int imgStart = bodyContent.indexOf("![](");
                    int imgEnd = bodyContent.indexOf(")", imgStart);
                    if (imgEnd > imgStart) {
                        String imageFileName = bodyContent.substring(imgStart + 4, imgEnd);
                        File imageFile = new File(folderPath, imageFileName);
                        if (imageFile.exists()) {
                            item.imageUri = Uri.fromFile(imageFile);
                        }
                        
                        // 移除图片markdown语法，获取纯文本
                        String beforeImg = bodyContent.substring(0, imgStart).trim();
                        String afterImg = bodyContent.substring(imgEnd + 1).trim();
                        bodyContent = (beforeImg + "\n" + afterImg).trim();
                    }
                }
                
                item.text = bodyContent;
                item.setDisplayText(bodyContent);
            }
            // 处理AI回复：需要解析各个部分
            else if (type == ChatViewHolders.ASSISTANT) {
                StringBuilder displayText = new StringBuilder();
                
                // 解析思考部分
                if (bodyContent.contains("<think>")) {
                    int thinkStart = bodyContent.indexOf("<think>");
                    int thinkEnd = bodyContent.indexOf("</think>");
                    if (thinkEnd > thinkStart) {
                        String thinkContent = bodyContent.substring(thinkStart + 7, thinkEnd).trim();
                        item.setThinkingText(thinkContent);
                        bodyContent = bodyContent.substring(0, thinkStart) + bodyContent.substring(thinkEnd + 8);
                    }
                }
                
                // 解析调试部分
                if (bodyContent.contains("<debug>")) {
                    int debugStart = bodyContent.indexOf("<debug>");
                    int debugEnd = bodyContent.indexOf("</debug>");
                    if (debugEnd > debugStart) {
                        String debugContent = bodyContent.substring(debugStart + 7, debugEnd).trim();
                        item.setDebugText(debugContent);
                        bodyContent = bodyContent.substring(0, debugStart) + bodyContent.substring(debugEnd + 8);
                    }
                }
                
                // 解析性能部分
                if (bodyContent.contains("<performance>")) {
                    int perfStart = bodyContent.indexOf("<performance>");
                    int perfEnd = bodyContent.indexOf("</performance>");
                    if (perfEnd > perfStart) {
                        String perfContent = bodyContent.substring(perfStart + 13, perfEnd).trim();
                        item.setPerformanceText(perfContent);
                        bodyContent = bodyContent.substring(0, perfStart) + bodyContent.substring(perfEnd + 14);
                    }
                }
                
                // 剩余的是正文内容和图片
                // 提取图片（如果有，Diffusion生成的图片）
                if (bodyContent.contains("![](")) {
                    int imgStart = bodyContent.indexOf("![](");
                    int imgEnd = bodyContent.indexOf(")", imgStart);
                    if (imgEnd > imgStart) {
                        String imageFileName = bodyContent.substring(imgStart + 4, imgEnd);
                        File imageFile = new File(folderPath, imageFileName);
                        if (imageFile.exists()) {
                            item.imageUri = Uri.fromFile(imageFile);
                        }
                        
                        // 移除图片markdown语法，获取纯文本
                        String beforeImg = bodyContent.substring(0, imgStart).trim();
                        String afterImg = bodyContent.substring(imgEnd + 1).trim();
                        bodyContent = (beforeImg + "\n" + afterImg).trim();
                    }
                }
                
                String mainContent = bodyContent.trim();
                item.text = mainContent;
                item.setDisplayText(mainContent);
                
                // 加载时默认折叠 debug 和 performance (showDebug=false 表示折叠)
                item.setShowDebug(false);
                item.setShowPerformance(false);
            }
            
            return item;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error parsing markdown to ChatDataItem", e);
            return null;
        }
    }
    
    /**
     * 获取对话历史列表
     * @param context 上下文
     * @return 对话历史列表
     */
    public static List<ChatHistoryItem> getChatHistoryList(Context context) {
        List<ChatHistoryItem> historyList = new ArrayList<>();
        
        try {
            String chatHistoryPath = ConfigManager.getString(context, 
                ConfigManager.KEY_CHAT_HISTORY_PATH, 
                ConfigManager.DEFAULT_CHAT_HISTORY_PATH);
            
            File parentDir = new File(chatHistoryPath);
            if (!parentDir.exists() || !parentDir.isDirectory()) {
                LogManager.logD(TAG, "Chat history directory does not exist: " + chatHistoryPath);
                return historyList;
            }
            
            File[] folders = parentDir.listFiles();
            if (folders == null || folders.length == 0) {
                LogManager.logD(TAG, "No chat history folders found");
                return historyList;
            }
            
            // 遍历所有文件夹
            for (File folder : folders) {
                if (!folder.isDirectory()) {
                    continue;
                }
                
                File conversationFile = new File(folder, CONVERSATION_FILE_NAME);
                if (!conversationFile.exists()) {
                    continue;
                }
                
                // 读取文件前20个字符作为预览
                String preview = getPreviewText(conversationFile, 20);
                
                // 使用文件夹的最后修改时间作为时间戳
                long timestamp = folder.lastModified();
                
                ChatHistoryItem item = new ChatHistoryItem(
                    folder.getAbsolutePath(),
                    folder.getName(),
                    preview,
                    timestamp
                );
                historyList.add(item);
            }
            
            // 按时间戳降序排序（最新的在前面）
            historyList.sort(new Comparator<ChatHistoryItem>() {
                @Override
                public int compare(ChatHistoryItem o1, ChatHistoryItem o2) {
                    return Long.compare(o2.timestamp, o1.timestamp);
                }
            });
            
            LogManager.logD(TAG, "Found " + historyList.size() + " chat history items");
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error getting chat history list", e);
        }
        
        return historyList;
    }
    
    /**
     * 获取文件的预览文本（前N个字符）
     * @param file 文件
     * @param maxChars 最大字符数
     * @return 预览文本
     */
    private static String getPreviewText(File file, int maxChars) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder preview = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null && preview.length() < maxChars) {
                // 跳过markdown标题行和空行
                if (line.startsWith("#") || line.trim().isEmpty() || line.equals("---")) {
                    continue;
                }
                
                // 跳过标签行
                if (line.startsWith("<think") || line.startsWith("<debug") || 
                    line.startsWith("<performance") || line.startsWith("</")) {
                    continue;
                }
                
                // 跳过图片引用
                if (line.startsWith("![")) {
                    continue;
                }
                
                preview.append(line).append(" ");
            }
            reader.close();
            
            String result = preview.toString().trim();
            if (result.length() > maxChars) {
                result = result.substring(0, maxChars) + "...";
            }
            
            return result.isEmpty() ? "[Empty conversation]" : result;
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Error reading preview text", e);
            return "[Error reading preview]";
        }
    }
    
    /**
     * 删除对话文件夹（包括所有文件）
     * @param folderPath 对话文件夹路径
     * @return 是否删除成功
     */
    public static boolean deleteChatFolder(String folderPath) {
        if (TextUtils.isEmpty(folderPath)) {
            LogManager.logE(TAG, "Folder path is empty");
            return false;
        }
        
        File folder = new File(folderPath);
        if (!folder.exists()) {
            LogManager.logW(TAG, "Folder does not exist: " + folderPath);
            return true; // 已经不存在了，认为删除成功
        }
        
        try {
            // 递归删除文件夹及其所有内容
            deleteRecursive(folder);
            LogManager.logD(TAG, "Deleted chat folder: " + folderPath);
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "Error deleting chat folder: " + folderPath, e);
            return false;
        }
    }
    
    /**
     * 递归删除文件或文件夹
     * @param file 文件或文件夹
     */
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            LogManager.logW(TAG, "Failed to delete: " + file.getAbsolutePath());
        }
    }
    
    /**
     * 删除所有对话历史
     * @param context 上下文
     * @return 删除的对话数量
     */
    public static int deleteAllChatHistory(Context context) {
        List<ChatHistoryItem> historyList = getChatHistoryList(context);
        int deletedCount = 0;
        
        for (ChatHistoryItem item : historyList) {
            if (deleteChatFolder(item.folderPath)) {
                deletedCount++;
            }
        }
        
        LogManager.logD(TAG, "Deleted " + deletedCount + " chat histories");
        return deletedCount;
    }
    
    /**
     * 保存图片到对话文件夹
     * @param sourceImagePath 源图片路径
     * @param chatFolderPath 对话文件夹路径
     * @return 新图片的绝对路径，失败返回null
     */
    public static String saveImageToChatFolder(String sourceImagePath, String chatFolderPath) {
        if (TextUtils.isEmpty(sourceImagePath) || TextUtils.isEmpty(chatFolderPath)) {
            LogManager.logE(TAG, "Source image path or chat folder path is empty");
            return null;
        }
        
        File sourceFile = new File(sourceImagePath);
        if (!sourceFile.exists()) {
            LogManager.logE(TAG, "Source image file does not exist: " + sourceImagePath);
            return null;
        }
        
        File chatFolder = new File(chatFolderPath);
        if (!chatFolder.exists()) {
            if (!chatFolder.mkdirs()) {
                LogManager.logE(TAG, "Failed to create chat folder: " + chatFolderPath);
                return null;
            }
        }
        
        try {
            // 生成新文件名：img_timestamp_index.extension
            String extension = getFileExtension(sourceFile.getName());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            
            // 查找可用的索引
            int index = 1;
            File destFile;
            do {
                String newFileName = String.format("%s%s_%03d.%s", IMAGE_PREFIX, timestamp, index, extension);
                destFile = new File(chatFolder, newFileName);
                index++;
            } while (destFile.exists());
            
            // 复制文件
            copyFile(sourceFile, destFile);
            
            LogManager.logD(TAG, "Image saved to: " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Error saving image to chat folder", e);
            return null;
        }
    }
    
    /**
     * 复制文件
     * @param source 源文件
     * @param dest 目标文件
     * @throws IOException IO异常
     */
    private static void copyFile(File source, File dest) throws IOException {
        FileInputStream inStream = new FileInputStream(source);
        FileOutputStream outStream = new FileOutputStream(dest);
        FileChannel inChannel = inStream.getChannel();
        FileChannel outChannel = outStream.getChannel();
        
        inChannel.transferTo(0, inChannel.size(), outChannel);
        
        inChannel.close();
        outChannel.close();
        inStream.close();
        outStream.close();
    }
    
    /**
     * 获取文件扩展名
     * @param fileName 文件名
     * @return 扩展名（不含点号）
     */
    private static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "jpg"; // 默认扩展名
    }
}
