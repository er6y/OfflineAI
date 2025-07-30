package com.example.starlocalrag;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库构建服务
 * 作为前台服务运行，确保在应用切换到后台或屏幕关闭时能继续构建知识库
 */
public class KnowledgeBaseBuilderService extends Service {
    private static final String TAG = "KnowledgeBaseService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "knowledge_base_builder_channel";
    private static final String CHANNEL_NAME = "知识库构建服务";

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
        
        void onTaskCompleted(boolean success, String message);
    }
    
    private ProgressCallback progressCallback;
    
    // 本地绑定器类
    public class LocalBinder extends Binder {
        public KnowledgeBaseBuilderService getService() {
            return KnowledgeBaseBuilderService.this;
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
            "StarLocalRAG:KnowledgeBaseBuilderWakeLock"
        );
        wakeLock.setReferenceCounted(false); // 设置为非引用计数模式
        
        LogManager.logD(TAG, "唤醒锁已初始化，类型: PARTIAL_WAKE_LOCK | ON_AFTER_RELEASE");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogManager.logD(TAG, "服务启动命令接收");
        
        // 立即启动前台服务以避免ANR错误
        startForeground(NOTIFICATION_ID, createNotification("准备构建知识库...", 0));
        LogManager.logD(TAG, "前台服务已启动");
        
        return START_NOT_STICKY; // 服务被杀死后不自动重启
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        LogManager.logD(TAG, "知识库构建服务已销毁");
        
        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            LogManager.logD(TAG, "唤醒锁已释放");
        }
        
        // 关闭执行器
        executor.shutdownNow();
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
            channel.setDescription("用于在后台构建知识库的通知");
            
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
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("知识库构建服务")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 提高优先级
            .setOngoing(true) // 设置为持续通知
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // 立即显示前台服务通知
            .setCategory(NotificationCompat.CATEGORY_SERVICE); // 设置为服务类别
        
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
     * 更新文本提取通知进度
     */
    private void updateTextExtractionProgress(int processedFiles, int totalFiles, String currentFile) {
        // 确保分母不为0，避免显示0/0
        int displayTotal = totalFiles > 0 ? totalFiles : 1;
        
        // 使用一致的格式"(已处理文件数/总文件数)"
        String status = String.format(getString(R.string.progress_text_extraction_keyword) + " (%d/%d): %s", 
                processedFiles, displayTotal, currentFile);
        
        // 为通知栏保留百分比进度
        int progress = (int) ((float) processedFiles / displayTotal * 50); // 文本提取占总进度的50%
        
        // 只更新通知，不触发UI回调
        this.currentProgress = progress;
        this.currentStatus = status;
        
        // 更新通知 - 已注释掉通知更新
        // updateNotification(status, progress);
        
        // 回调进度（确保UI显示正确格式）
        if (progressCallback != null) {
            progressCallback.onProgressUpdate(progress, status);
        }
        
        LogManager.logD(TAG, "通知文本提取进度更新: [" + processedFiles + "/" + displayTotal + "] " + currentFile);
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
     * 开始构建知识库
     * @param knowledgeBaseName 知识库名称
     * @param embeddingModel 嵌入模型路径
     * @param selectedFiles 选中的文件列表
     */
    public void startBuildKnowledgeBase(String knowledgeBaseName, String embeddingModel, String rerankerModel, List<Uri> selectedFiles) {
        // 重置取消标志
        isTaskCancelled.set(false);
        
        LogManager.logD(TAG, "开始构建知识库: " + knowledgeBaseName + ", 文件数量: " + selectedFiles.size() + ", 模型: " + embeddingModel);
        
        // 获取唤醒锁，设置超时时间为10小时，确保长时间任务能够完成
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 最多持有10小时
            LogManager.logD(TAG, "已获取唤醒锁，超时时间设置为10小时，wakeLock状态: " + (wakeLock.isHeld() ? "已持有" : "未持有"));
        } else {
            LogManager.logD(TAG, "唤醒锁已经持有，无需重新获取");
        }
        
        // 更新前台服务通知内容
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, createNotification("开始构建知识库: " + knowledgeBaseName, 0));
            LogManager.logD(TAG, "已更新前台服务通知内容");
        } catch (Exception e) {
            LogManager.logE(TAG, "更新通知失败: " + e.getMessage());
        }
        
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
                
                // 更新最终通知 - 已注释掉通知更新
                String finalStatus = success ? getString(R.string.kb_build_completed, knowledgeBaseName) : getString(R.string.kb_build_cancelled);
                // updateNotification(finalStatus, success ? 100 : 0);
                
                // 延迟停止服务
                stopSelfDelayed();
                
            } catch (Exception e) {
                LogManager.logE(TAG, getString(R.string.kb_build_failed_log), e);
                
                // 错误回调
                if (progressCallback != null) {
                    progressCallback.onTaskCompleted(false, "知识库构建失败: " + e.getMessage());
                }
                
                // 更新错误通知 - 已注释掉通知更新
                // updateNotification("知识库构建失败: " + e.getMessage(), 0);
                
                // 延迟停止服务
                stopSelfDelayed();
            } finally {
                // 确保在任何情况下都释放资源
                LogManager.logD(TAG, "知识库构建过程结束，释放资源");
                
                // 释放嵌入模型
                try {
                    EmbeddingModelManager.getInstance(this).markModelNotInUse();
                    LogManager.logD(TAG, "已释放嵌入模型资源");
                } catch (Exception e) {
                    LogManager.logE(TAG, "释放嵌入模型资源时出错", e);
                }
            }
        });
    }
    
    /**
     * 延迟停止服务
     */
    private void stopSelfDelayed() {
        // 立即停止前台服务，移除通知栏 - 已注释掉因为没有前台服务
        // stopForeground(STOP_FOREGROUND_REMOVE);
        LogManager.logD(TAG, "知识库构建服务准备停止");
        
        // 延迟1秒后停止服务，确保资源释放
        android.os.Handler mainHandler = new android.os.Handler(getMainLooper());
        mainHandler.postDelayed(() -> {
            // 确保唤醒锁已释放
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LogManager.logD(TAG, "停止服务前确保唤醒锁已释放");
            }
            
            stopSelf();
            LogManager.logD(TAG, "服务已完全停止");
        }, 1000); // 将延迟时间从5秒减少到1秒
    }
    
    /**
     * 取消当前任务
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
        
        // 这里实现知识库构建的核心逻辑
        // 1. 初始化文本处理器
        TextChunkProcessor textChunkProcessor = new TextChunkProcessor(this, isTaskCancelled);
        
        // 2. Initialize progress manager
        progressManager = ProgressManager.getInstance();
        progressManager.reset();
        
        // 3. Set progress callback
        textChunkProcessor.setProgressCallback(new TextChunkProcessor.ProgressCallback() {
            @Override
            public void onTextExtractionProgress(int processedFiles, int totalFiles, String currentFile) {
                // Update progress manager
                if (processedFiles == 1 && totalFiles > 0) {
                    progressManager.initFileProcessing(totalFiles);
                }
                progressManager.updateFileProgress(processedFiles, currentFile);
                
                // Update notification progress
                updateTextExtractionProgress(processedFiles, totalFiles, currentFile);
                
                // Log progress
                LogManager.logD(TAG, "Text extraction progress: " + processedFiles + "/" + totalFiles + ", current file: " + currentFile);
            }
            
            @Override
            public void onVectorizationProgress(int processedChunks, int totalChunks, float percentage) {
                // Update progress manager
                progressManager.updateVectorizationProgress(processedChunks, totalChunks, percentage);
                
                // Calculate overall progress (50-100%)
                int progress = 50 + (int) (percentage / 2);
                
                // Update progress with localized status
                //String status = StateDisplayManager.getProcessingStatusDisplayText(getApplicationContext(), 
                //    AppConstants.PROCESSING_STATUS_GENERATING_VECTORS) + " (" + processedChunks + "/" + totalChunks + ")";
                updateProgress(progress, null);
                
                LogManager.logD(TAG, "Vectorization progress: " + processedChunks + "/" + totalChunks + " (" + percentage + "%)");
            }
            
            @Override
            public void onTextExtractionComplete(int totalChunks) {
                // Initialize vectorization in progress manager
                progressManager.initVectorization(totalChunks);
                
                // Update progress with localized status
                String status = StateDisplayManager.getProcessingStatusDisplayText(getApplicationContext(), 
                    AppConstants.PROCESSING_STATUS_TEXT_EXTRACTION_COMPLETE) + ", " + 
                    getString(R.string.text_extraction_complete_chunks, totalChunks)+ "..." + getString(R.string.common_generating);
                
                LogManager.logD(TAG, "Text extraction completed, total chunks: " + totalChunks + ". Starting vectorization...");
                updateProgress(50, status);
            }
            
            @Override
            public void onVectorizationComplete(int vectorCount) {
                // Mark completion in progress manager
                progressManager.markCompleted();
                
                // Update progress with localized status
                String status = StateDisplayManager.getProcessingStatusDisplayText(getApplicationContext(), 
                    AppConstants.PROCESSING_STATUS_VECTORIZATION_COMPLETE) + ", " + 
                    getString(R.string.vectorization_complete_vectors, vectorCount);
                
                LogManager.logD(TAG, "Vectorization completed, total vectors: " + vectorCount);
                updateProgress(100, status);
            }
            
            @Override
            public void onError(String errorMessage) {
                // 处理错误
                LogManager.logE(TAG, "错误: " + errorMessage);
                updateProgress(0, getString(R.string.error_message, errorMessage));
            }
            
            @Override
            public void onLog(String message) {
                // 记录日志
                LogManager.logD(TAG, message);
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
                ConfigManager.getChunkSize(this),
                ConfigManager.getInt(this, ConfigManager.KEY_OVERLAP_SIZE, ConfigManager.DEFAULT_OVERLAP_SIZE)
            );
            
            // 4. 返回结果
            return result && !isTaskCancelled.get();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "知识库构建过程中发生错误", e);
            throw e;
        } finally {
            // 确保在任何情况下都释放资源
            LogManager.logD(TAG, "知识库构建过程结束，释放资源");
            
            // 释放嵌入模型
            try {
                EmbeddingModelManager.getInstance(this).markModelNotInUse();
                LogManager.logD(TAG, "已释放嵌入模型资源");
            } catch (Exception e) {
                LogManager.logE(TAG, "释放嵌入模型资源时出错", e);
            }
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
        
        LogManager.logD(TAG, "进度更新: " + progress + "%, " + status);
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
}
