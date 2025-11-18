
package com.example.offlineai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一前台服务（UnifiedForegroundService）
 * 功能：
 * 1. 保持app进程存活，防止被系统杀死（即使熄屏、切后台）
 * 2. 支持多种任务类型：知识库构建、模型下载、长时间推理等
 * 3. 通过通知栏告知用户任务状态
 * 4. 管理WakeLock，确保长时间任务完成
 * 5. 保存app现场状态
 */
public class UnifiedForegroundService extends Service {
    private static final String TAG = "UnifiedForegroundService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "unified_foreground_channel";
    private static final String CHANNEL_NAME = "离线AI后台服务";
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        IDLE("空闲"),              // 空闲状态，只保持进程存活
        KB_BUILD("知识库构建"),     // 知识库构建任务
        MODEL_DOWNLOAD("模型下载"), // 模型下载任务
        INFERENCE("推理中");        // 长时间推理任务
        
        private final String displayName;
        
        TaskType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    // 当前任务类型
    private TaskType currentTaskType = TaskType.IDLE;
    private boolean hasActiveTask = false;

    // 绑定器
    private final IBinder binder = new LocalBinder();
    
    // 执行器服务
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // 任务取消标志
    private final AtomicBoolean isTaskCancelled = new AtomicBoolean(false);
    
    // 当前进度
    private int currentProgress = 0;
    private String currentStatus = "准备中...";
    
    // Progress manager instance
    private ProgressManager progressManager;
    
    // 唤醒锁
    private PowerManager.WakeLock wakeLock;
    
    // 进度回调接口
    public interface ProgressCallback {
        void onProgressUpdate(int progress, String status);
        
        // 添加知识库构建完成回调
        default void onBuildCompleted(boolean success) {
            // 默认空实现
        }
        
        // 添加文本提取完成回调
        default void onTextExtractionComplete(int chunkCount) {
            // 默认空实现
        }
        
        // 添加向量化完成回调
        default void onVectorizationComplete(int vectorCount) {
            // 默认空实现
        }

        // 统一的日志行回调（用于UI进度框）
        default void onLogLine(String message) {
            // 默认空实现
        }
        
        void onTaskCompleted(boolean success, String message);
    }
    
    private ProgressCallback progressCallback;
    
    // 本地绑定器类
    public class LocalBinder extends Binder {
        public UnifiedForegroundService getService() {
            return UnifiedForegroundService.this;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        LogManager.logD(TAG, "服务onBind()被调用");
        return binder;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        LogManager.logD(TAG, "知识库构建服务已创建");
        
        // 创建通知渠道（Android 8.0及以上需要）
        createNotificationChannel();
        
        // 获取唤醒锁
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK | 
            PowerManager.ON_AFTER_RELEASE, // 增加ON_AFTER_RELEASE标志
            "OfflineAI:KnowledgeBaseBuilderWakeLock"
        );
        wakeLock.setReferenceCounted(false); // 设置为非引用计数模式
        
        LogManager.logD(TAG, "唤醒锁已初始化，类型: PARTIAL_WAKE_LOCK | ON_AFTER_RELEASE");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogManager.logI(TAG, "统一前台服务启动");
        
        // 立即启动前台服务以避免ANR错误
        startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0));
        LogManager.logD(TAG, "前台服务已启动，保持进程存活");
        
        // 不使用START_STICKY，用户主动关闭app时应该清除服务
        // 只在有活动任务时保持运行，空闲时允许被清理
        return START_NOT_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        LogManager.logI(TAG, "统一前台服务已销毁");
        
        // 停止前台服务并移除通知
        // minSdk=26 >= Android N(24)，直接使用新API
        stopForeground(STOP_FOREGROUND_REMOVE);
        
        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            LogManager.logD(TAG, "唤醒锁已释放");
        }
        
        // 关闭执行器
        executor.shutdownNow();
        
        LogManager.logI(TAG, "服务已完全清理，通知已移除");
    }
    
    /**
     * 处理用户从最近任务中清除app的情况
     * 这时应该停止服务，清除通知
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogManager.logI(TAG, "用户从最近任务清除app，停止服务");
        
        // 如果没有活动任务，直接停止服务
        if (!hasActiveTask) {
            stopSelf();
        } else {
            // 有活动任务时，等任务完成后再停止
            LogManager.logI(TAG, "有活动任务运行中，等待任务完成后停止");
        }
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // 使用低重要性避免声音和震动
            );
            channel.setDescription("保持应用在后台运行，支持长时间任务和状态保存");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            LogManager.logD(TAG, "已创建通知渠道");
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private Notification createNotification(String contentText, int progress) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        // 添加标志，确保返回到现有实例而不是创建新实例
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // 根据任务类型设置标题
        String title = hasActiveTask ? 
            "离线AI - " + currentTaskType.getDisplayName() : 
            "离线AI助手";
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级，不打扰用户
            .setOngoing(true) // 设置为持续通知
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE);
        
        if (progress > 0) {
            builder.setProgress(100, progress, false);
        }
        
        return builder.build();
    }
    
    /**
     * 更新通知
     */
    private void updateNotification(String status, int progress) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(status, progress));
    }
    
    /**
     * 更新通知进度
     * 单独用于通知栏的进度更新，与UI进度更新分离
     */
    private void updateNotificationProgress(int processedChunks, int totalChunks, float percentage) {
        int progress = 50 + (int) (percentage / 2); // 向量化占总进度的50%
        String status = "正在生成向量 (" + processedChunks + "/" + totalChunks + ")";
        
        // 只更新通知，不触发UI回调
        this.currentProgress = progress;
        this.currentStatus = status;
        
        // 更新通知 - 已注释掉通知更新
        // updateNotification(status, progress);
        
        LogManager.logD(TAG, "通知进度更新: " + progress + "%, " + status);
    }
    
    /**
     * 更新文本提取通知进度，数值进度使用 ProgressManager 提供的整体百分比。
     */
    private void updateTextExtractionProgress(int processedFiles, int totalFiles, String currentFile, int overallProgress) {
        // 确保分母不为0，避免显示0/0
        int displayTotal = totalFiles > 0 ? totalFiles : 1;
        
        // 使用一致的格式"(已处理文件数/总文件数)"
        String status = String.format(getString(R.string.progress_text_extraction_keyword) + " (%d/%d): %s", 
                processedFiles, displayTotal, currentFile);
        
        // 只更新通知，不触发UI回调
        this.currentProgress = overallProgress;
        this.currentStatus = status;
        
        // 更新通知 - 已注释掉通知更新
        // updateNotification(status, progress);
        
        // 回调进度（确保UI显示正确格式）
        if (progressCallback != null) {
            progressCallback.onProgressUpdate(overallProgress, status);
        }
        
        LogManager.logD(TAG, "通知文本提取进度更新: [" + processedFiles + "/" + displayTotal + "] " + currentFile +
                ", overall=" + overallProgress + "%");
    }
    
    /**
     * 处理应用切换到后台
     * 当应用切换到后台时，系统可能会尝试回收资源，我们需要确保服务继续运行
     */
    public void onAppBackgrounded() {
        LogManager.logD(TAG, "应用切换到后台");
        checkServiceStatus();
        
        // 确保唤醒锁被持有
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 最多持有10小时
            LogManager.logD(TAG, "应用切后台，重新获取唤醒锁");
        }
        
        // 检查前台服务状态
        try {
            // 更新通知以确保前台服务状态 - 已注释掉通知更新
            // updateNotification(currentStatus, currentProgress);
            LogManager.logD(TAG, "应用切后台，已跳过前台服务通知更新");
        } catch (Exception e) {
            LogManager.logE(TAG, "应用切后台，处理失败", e);
        }
    }
    
    /**
     * 处理应用切换到前台
     */
    public void onAppForegrounded() {
        LogManager.logD(TAG, "应用切换到前台");
        checkServiceStatus();
        
        // 更新通知 - 已注释掉通知更新
        try {
            // updateNotification(currentStatus, currentProgress);
            LogManager.logD(TAG, "应用切前台，已跳过通知更新");
        } catch (Exception e) {
            LogManager.logE(TAG, "应用切前台，处理失败", e);
        }
    }
    
    /**
     * 设置进度回调
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }
    
    /**
     * 通用任务开始方法
     * @param taskType 任务类型
     * @param taskDescription 任务描述
     */
    public void startTask(TaskType taskType, String taskDescription) {
        LogManager.logI(TAG, "开始任务: " + taskType.getDisplayName() + " - " + taskDescription);
        currentTaskType = taskType;
        hasActiveTask = true;
        
        // 获取唤醒锁
        acquireWakeLock();
        
        // 更新通知
        updateNotification(taskDescription, 0);
    }
    
    /**
     * 通用任务结束方法
     */
    public void endTask() {
        LogManager.logI(TAG, "任务完成: " + currentTaskType.getDisplayName());
        hasActiveTask = false;
        currentTaskType = TaskType.IDLE;
        
        // 释放唤醒锁
        releaseWakeLock();
        
        // 任务完成后，延迟停止服务，避免通知驻留
        LogManager.logI(TAG, "任务完成，1秒后停止服务并清除通知");
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            stopSelf();
            LogManager.logI(TAG, "服务已停止，通知已清除");
        }, 1000); // 延迟1秒，确保回调完成
    }
    
    /**
     * 获取唤醒锁
     */
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(10 * 60 * 60 * 1000L); // 最长10小时
                LogManager.logD(TAG, "唤醒锁已获取");
            } catch (Exception e) {
                LogManager.logE(TAG, "获取唤醒锁失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 释放唤醒锁
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                LogManager.logD(TAG, "唤醒锁已释放");
            } catch (Exception e) {
                LogManager.logE(TAG, "释放唤醒锁失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 开始构建知识库
     * @param knowledgeBaseName 知识库名称
     * @param embeddingModel 嵌入模型路径
     * @param selectedFiles 选中的文件列表
     */
    public void startBuildKnowledgeBase(String knowledgeBaseName, String embeddingModel, String rerankerModel, List<Uri> selectedFiles) {
        // 重置取消标志
        isTaskCancelled.set(false);
        
        LogManager.logD(TAG, "开始构建知识库: " + knowledgeBaseName + ", 文件数量: " + selectedFiles.size() + ", 模型: " + embeddingModel);
        
        // 使用通用任务管理
        startTask(TaskType.KB_BUILD, "构建知识库: " + knowledgeBaseName);
        
        // 在后台线程中执行构建任务
        executor.execute(() -> {
            LogManager.logD(TAG, "开始执行知识库构建任务，线程ID: " + Thread.currentThread().getId());
            try {
                // 执行知识库构建逻辑
                boolean success = buildKnowledgeBase(knowledgeBaseName, embeddingModel, rerankerModel, selectedFiles);
                
                // 任务完成回调
                if (progressCallback != null) {
                    if (success) {
                        progressCallback.onBuildCompleted(true);
                        progressCallback.onTaskCompleted(true, getString(R.string.kb_build_completed, knowledgeBaseName));
                        LogManager.logD(TAG, getString(R.string.kb_build_success_log, knowledgeBaseName));
                    } else {
                        progressCallback.onBuildCompleted(false);
                        progressCallback.onTaskCompleted(false, getString(R.string.kb_build_cancelled));
                        LogManager.logD(TAG, getString(R.string.kb_build_cancelled_log, knowledgeBaseName));
                    }
                }
                
                // 结束任务
                endTask();
                
            } catch (Exception e) {
                LogManager.logE(TAG, getString(R.string.kb_build_failed_log), e);
                
                // 错误回调
                if (progressCallback != null) {
                    progressCallback.onTaskCompleted(false, "知识库构建失败: " + e.getMessage());
                }
                
                // 结束任务
                endTask();
            } finally {
                // 确保在任何情况下都释放资源
                LogManager.logD(TAG, "知识库构建过程结束，释放资源");
                
                // MNN embedding handler manages model lifecycle automatically
                LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
            }
        });
    }
    
    /**
     * 取消任务
     */
    public void cancelTask() {
        isTaskCancelled.set(true);
        LogManager.logD(TAG, "已请求取消知识库构建任务");
        // updateNotification("正在取消知识库构建...", 0);
    }
    
    /**
     * 实际构建知识库的逻辑
     * @return 是否成功完成（未被取消）
     */
    private boolean buildKnowledgeBase(String knowledgeBaseName, String embeddingModel, String rerankerModel, List<Uri> selectedFiles) {
        LogManager.logD(TAG, "开始构建知识库: " + knowledgeBaseName + ", 模型: " + embeddingModel + ", 文件数: " + selectedFiles.size());
        
        // 清理旧的临时文件，避免累积占用存储空间
        cleanupTempFiles();
        
        // 这里实现知识库构建的核心逻辑
        // 1. 初始化文本处理器
        TextChunkProcessor textChunkProcessor = new TextChunkProcessor(this, isTaskCancelled);
        
        // 2. Initialize progress manager
        progressManager = ProgressManager.getInstance();
        progressManager.reset();

        // 2.1 Initialize build configuration snapshot for UI display
        int chunkSize = ConfigManager.getChunkSize(this);
        int chunkOverlap = ConfigManager.getInt(this, ConfigManager.KEY_OVERLAP_SIZE, ConfigManager.DEFAULT_OVERLAP_SIZE);
        String dictPath = ConfigManager.getString(this, ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null);
        String dictFileName = "";
        try {
            String valueNone = getString(R.string.common_none);
            if (dictPath != null && !dictPath.isEmpty() && !valueNone.equals(dictPath)) {
                dictFileName = new File(dictPath).getName();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to resolve dictionary file name", e);
        }
        progressManager.setBuildConfig(knowledgeBaseName, embeddingModel, rerankerModel, dictFileName, chunkSize, chunkOverlap);

        // 2.2 Emit initial configuration lines to UI (if callback already attached)
        if (progressCallback != null) {
            try {
                String kbLine = "Knowledge base: " + knowledgeBaseName;
                String embedLine = "Embedding model: " + embeddingModel;
                String rerankLine = "Reranker model: " + (rerankerModel == null || rerankerModel.isEmpty() ? "None" : rerankerModel);
                String chunkLine = "Chunk size: " + chunkSize + ", overlap: " + chunkOverlap;
                String dictLine = "Dictionary (configured): " + (dictFileName.isEmpty() ? "None" : dictFileName);
                progressCallback.onLogLine(kbLine);
                progressCallback.onLogLine(embedLine);
                progressCallback.onLogLine(rerankLine);
                progressCallback.onLogLine(chunkLine);
                progressCallback.onLogLine(dictLine);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to emit build configuration lines", e);
            }
        }
        
        // 3. Set progress callback
        textChunkProcessor.setProgressCallback(new TextChunkProcessor.ProgressCallback() {
            @Override
            public void onTextExtractionProgress(int processedFiles, int totalFiles, String currentFile) {
                // Update progress manager
                if (processedFiles == 1 && totalFiles > 0) {
                    progressManager.initFileProcessing(totalFiles);
                }
                progressManager.updateFileProgress(processedFiles, currentFile);

                // Derive overall progress from ProgressManager
                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());

                // Update notification/UI progress using unified overall percentage
                updateTextExtractionProgress(processedFiles, totalFiles, currentFile, overall);

                // Log progress
                LogManager.logD(TAG, "Text extraction progress: " + processedFiles + "/" + totalFiles + ", current file: " + currentFile +
                        ", overall=" + overall + "%");
            }
            
            @Override
            public void onVectorizationProgress(int processedChunks, int totalChunks, float percentage) {
                // Update progress manager
                progressManager.updateVectorizationProgress(processedChunks, totalChunks, percentage);

                // Derive overall progress from ProgressManager (dominant 98% weight during vectorization)
                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());

                // Update numeric progress only; textual status will be delivered via onLogLine
                updateProgress(overall, null);

                LogManager.logD(TAG, "Vectorization progress: " + processedChunks + "/" + totalChunks + " (" + percentage + "%)" +
                        ", overall=" + overall + "%");
            }
            
            @Override
            public void onTextExtractionComplete(int totalChunks) {
                // Initialize vectorization in progress manager
                progressManager.initVectorization(totalChunks);
                
                // Log a human-readable line for debugging; UI uses the consolidated message from TextChunkProcessor
                String status = StateDisplayManager.getProcessingStatusDisplayText(getApplicationContext(), 
                    AppConstants.PROCESSING_STATUS_TEXT_EXTRACTION_COMPLETE) + ", " + 
                    getString(R.string.text_extraction_complete_chunks, totalChunks)+ "..." + getString(R.string.common_generating);
                LogManager.logD(TAG, "Text extraction completed, total chunks: " + totalChunks + ". Starting vectorization...");
                // Update numeric progress using unified overall percentage
                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());
                updateProgress(overall, null);
            }
            
            @Override
            public void onVectorizationComplete(int vectorCount) {
                // Mark completion in progress manager
                progressManager.markCompleted();
                
                // Log and send a human-readable line via onLogLine
                String status = StateDisplayManager.getProcessingStatusDisplayText(getApplicationContext(), 
                    AppConstants.PROCESSING_STATUS_VECTORIZATION_COMPLETE) + ", " + 
                    getString(R.string.vectorization_complete_vectors, vectorCount);
                LogManager.logD(TAG, "Vectorization completed, total vectors: " + vectorCount);
                if (progressCallback != null) {
                    progressCallback.onLogLine(status);
                }
                // Update numeric progress using unified overall percentage
                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());
                updateProgress(overall, null);
            }
            
            @Override
            public void onGraphBuildingProgress(int processedChunks, int totalChunks, float percentage) {
                // Update progress for graph building (hub filtering / knowledge graph post-processing)
                progressManager.markGraphBuilding();
                progressManager.updateHubFilteringProgress(processedChunks, totalChunks, percentage);

                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());

                String status = "Building knowledge graph: " + processedChunks + "/" + totalChunks +
                    " (" + String.format("%.1f%%", percentage) + ")";
                if (progressCallback != null) {
                    progressCallback.onLogLine(status);
                }
                // Use unified overall progress instead of hard-coded 100%
                updateProgress(overall, null);
            }
            
            @Override
            public void onError(String errorMessage) {
                // 处理错误
                LogManager.logE(TAG, "错误: " + errorMessage);
                String uiMessage = getString(R.string.error_message, errorMessage);
                // Send error text via onLogLine only; numeric progress resets to 0 without status text
                if (progressCallback != null) {
                    progressCallback.onLogLine(uiMessage);
                }
                updateProgress(0, null);
            }
            
            @Override
            public void onLog(String message) {
                // 记录日志
                LogManager.logD(TAG, message);
                if (progressCallback != null) {
                    progressCallback.onLogLine(message);
                }
            }
        });
        
        // 设置通知进度回调
        textChunkProcessor.setNotificationProgressCallback(new TextChunkProcessor.NotificationProgressCallback() {
            @Override
            public void onNotificationProgressUpdate(int processedChunks, int totalChunks, float percentage) {
                // updateNotificationProgress(processedChunks, totalChunks, percentage);
            }
        });
        
        try {
            // 3. 处理文件并构建知识库
            boolean result = textChunkProcessor.processFilesAndBuildKnowledgeBase(
                knowledgeBaseName,
                embeddingModel,
                rerankerModel,
                selectedFiles,
                chunkSize,
                chunkOverlap
            );
            
            // 4. 返回结果
            return result && !isTaskCancelled.get();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "知识库构建过程中发生错误", e);
            throw e;
        } finally {
            // 确保在任何情况下都释放资源
            LogManager.logD(TAG, "知识库构建过程结束，释放资源");
            
            // MNN embedding handler manages model lifecycle automatically
            LogManager.logD(TAG, "Model lifecycle managed by MNN embedding handler");
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress(int progress, String status) {
        this.currentProgress = progress;
        this.currentStatus = status;
        
        // 更新通知 - 已注释掉通知更新
        // updateNotification(status, progress);
        
        // 回调进度
        if (progressCallback != null) {
            progressCallback.onProgressUpdate(progress, status);
        }
        
        if (status != null) {
            LogManager.logD(TAG, "进度更新: " + progress + "%, " + status);
        }
    }
    
    /**
     * 检查服务状态并记录日志
     */
    private void checkServiceStatus() {
        boolean isWakeLockHeld = wakeLock != null && wakeLock.isHeld();
        boolean isTaskRunning = !isTaskCancelled.get();
        boolean isExecutorShutdown = executor.isShutdown();
        
        LogManager.logD(TAG, "服务状态检查 - " +
              "唤醒锁状态: " + (isWakeLockHeld ? "持有中" : "未持有") + ", " +
              "任务状态: " + (isTaskRunning ? "运行中" : "已取消") + ", " +
              "执行器状态: " + (isExecutorShutdown ? "已关闭" : "运行中") + ", " +
              "线程ID: " + Thread.currentThread().getId());
    }
    
    /**
     * 清理旧的临时文件
     * 在知识库构建前调用，避免临时文件累积占用存储空间
     */
    private void cleanupTempFiles() {
        try {
            java.io.File cacheDir = getCacheDir();
            if (cacheDir == null || !cacheDir.exists()) {
                LogManager.logW(TAG, "Cache directory does not exist, skip cleanup");
                return;
            }
            
            java.io.File[] files = cacheDir.listFiles();
            if (files == null || files.length == 0) {
                LogManager.logD(TAG, "No files in cache directory, skip cleanup");
                return;
            }
            
            int deletedCount = 0;
            long deletedSize = 0;
            
            for (java.io.File file : files) {
                // Only delete temporary text files created by DocumentParser
                if (file.isFile() && file.getName().startsWith("temp_") && file.getName().endsWith(".txt")) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        deletedCount++;
                        deletedSize += fileSize;
                        LogManager.logD(TAG, "Deleted temp file: " + file.getName() + " (" + (fileSize / 1024) + " KB)");
                    } else {
                        LogManager.logW(TAG, "Failed to delete temp file: " + file.getName());
                    }
                }
            }
            
            if (deletedCount > 0) {
                LogManager.logI(TAG, "Cleaned up " + deletedCount + " temporary files, freed " + (deletedSize / 1024) + " KB");
            } else {
                LogManager.logD(TAG, "No temporary files to clean up");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to cleanup temporary files", e);
            // 清理失败不影响知识库构建，继续执行
        }
    }
    
    /**
     * 检查是否有活动任务
     */
    public boolean hasActiveTask() {
        return hasActiveTask;
    }
    
    /**
     * 获取当前任务类型
     */
    public TaskType getCurrentTaskType() {
        return currentTaskType;
    }
}
