
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
import java.util.HashMap;
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
    private static UnifiedForegroundService sInstance;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "unified_foreground_channel";
    private static final String CHANNEL_NAME = "离线AI后台服务";
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        IDLE("空闲"),                  // 空闲状态，只保持进程存活
        KB_BUILD("知识库构建"),         // 知识库构建任务
        MODEL_DOWNLOAD("模型下载"),     // 模型下载任务
        NOTE_PROCESSING("知识笔记处理"), // 笔记处理任务
        INFERENCE("推理中"),            // 长时间推理任务
        AGENT_EXECUTING("Agent执行中"); // Agent自动操作任务
        
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

    // Current KB build task id tracked in BackgroundTaskManager
    private String kbBuildTaskId;

    // In-memory log buffer for KB build tasks (legacy, kept for backward compatibility)
    // For inference tasks, use BackgroundTaskManager's unified log buffer
    private static final int MAX_LOG_LINES = 2000;
    private static final int MAX_LOG_CHARS = 1024 * 1024;
    private final List<String> logBuffer = new ArrayList<>();
    private int currentLogChars = 0;
    
    // Current inference task ID for log routing
    private volatile String currentInferenceTaskId = null;
    
    // Schedule heartbeat - AlarmManager only (Doze-proof, 1min interval)
    // lastScheduledRunTime: updated ONLY when Agent is actually triggered. Used for
    //   - interval check (range mode)
    //   - same-local-day check (once-per-day mode, start==end)
    // lastReminderTime: updated when reminder notification is sent (screen off / locked).
    //   Used to throttle reminders to every REMINDER_THROTTLE_MS, independent of lastScheduledRunTime.
    private volatile long[] lastScheduledRunTime = new long[ConfigManager.SCHEDULE_TASK_COUNT];
    private volatile long[] lastReminderTime = new long[ConfigManager.SCHEDULE_TASK_COUNT];
    private static final long REMINDER_THROTTLE_MS = 30L * 60L * 1000L; // 30 minutes
    private android.app.AlarmManager alarmManager;
    private PendingIntent alarmPendingIntent;
    // Whether startForeground() has been called for this service instance.
    // Needed because AlarmManager may start a freshly-restarted process where
    // the alarm-action branch becomes the FIRST onStartCommand invocation.
    private boolean foregroundStarted = false;
    
    // Schedule action and notification constants
    private static final String ACTION_SCHEDULE_ALARM = "com.example.offlineai.SCHEDULE_ALARM";
    private static final String SCHEDULE_REMINDER_CHANNEL_ID = "schedule_reminder_channel";
    private static final int SCHEDULE_REMINDER_NOTIFICATION_ID = 1002;

    // MediaProjection upgrade ready-callback (Android 14+)
    // Because startForegroundService() is asynchronous, callers that depend on the
    // service having been promoted to FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    // (e.g. MediaProjectionManager.getMediaProjection()) must wait until
    // onStartCommand finished the upgrade. We expose a one-shot callback:
    // caller registers via setMediaProjectionReadyCallback(), then starts the
    // service with extra "media_projection"=true. The callback fires on the main
    // thread once upgrade succeeds (or fails).
    private static volatile Runnable sMediaProjectionReadyCallback = null;
    private static volatile boolean sMediaProjectionUpgraded = false;

    public static void setMediaProjectionReadyCallback(Runnable cb) {
        sMediaProjectionReadyCallback = cb;
        sMediaProjectionUpgraded = false;
    }

    public static boolean isMediaProjectionReady() {
        return sMediaProjectionUpgraded;
    }
    
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
        sInstance = this;
        LogManager.logD(TAG, "知识库构建服务已创建");
        
        // 创建通知渠道（Android 8.0及以上需要）
        createNotificationChannel();
        createScheduleReminderChannel();
        
        // Init AlarmManager for Doze-proof heartbeat
        alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        
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
        // Handle AlarmManager heartbeat wakeup (fires every 1 minute; keep logs at DEBUG to avoid flooding).
        if (intent != null && ACTION_SCHEDULE_ALARM.equals(intent.getAction())) {
            // If the process was killed and AlarmManager just revived us, this is the
            // FIRST onStartCommand of a new service instance and we MUST call
            // startForeground() within 5 seconds to avoid system-kill / crash.
            if (!foregroundStarted) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0));
                    }
                    foregroundStarted = true;
                    LogManager.logI(TAG, "[SCHEDULE] Foreground started from alarm wakeup (process restart)");
                } catch (Exception e) {
                    LogManager.logE(TAG, "[SCHEDULE] startForeground in alarm branch failed: " + e.getMessage());
                }
            }
            LogManager.logD(TAG, "[SCHEDULE] AlarmManager heartbeat wakeup");
            handleAlarmHeartbeat();
            // Re-schedule next alarm
            boolean se = ConfigManager.getBoolean(getApplicationContext(), ConfigManager.KEY_SCHEDULE_ENABLED, false);
            if (se) scheduleNextAlarm();
            return se ? START_STICKY : START_NOT_STICKY;
        }

        // Normal startup path (not from heartbeat)
        LogManager.logI(TAG, "统一前台服务启动");
        
        // Check if this is for MediaProjection (Agent screenshot)
        boolean isMediaProjection = false;
        if (intent != null) {
            isMediaProjection = intent.getBooleanExtra("media_projection", false);
        }
        
        // Must call startForeground ASAP to avoid ANR.
        // IMPORTANT (Android 14 / targetSdk=34): since manifest declares
        // foregroundServiceType="dataSync|mediaProjection", calling the 2-arg
        // startForeground(id, notification) would validate BOTH types and crash
        // with SecurityException because mediaProjection requires user's runtime
        // MediaProjection grant. So we MUST pass an explicit type here.
        boolean mediaProjectionUpgradedNow = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isMediaProjection) {
                    // Upgrade path: caller has obtained MediaProjection consent
                    startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    LogManager.logD(TAG, "前台服务已启动（mediaProjection模式），保持进程存活");
                    mediaProjectionUpgradedNow = true;
                } else {
                    // Default path at app startup: dataSync only, no user grant required
                    startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    LogManager.logD(TAG, "前台服务已启动（dataSync模式），保持进程存活");
                }
            } else {
                // Android < 10: no foregroundServiceType concept
                startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0));
                LogManager.logD(TAG, "前台服务已启动（legacy），保持进程存活");
                if (isMediaProjection) {
                    mediaProjectionUpgradedNow = true;
                }
            }
            foregroundStarted = true;
        } catch (SecurityException se) {
            // Defensive fallback: if mediaProjection upgrade fails (e.g. user revoked grant),
            // degrade to dataSync so the service still runs and app doesn't crash.
            LogManager.logE(TAG, "启动前台服务失败，降级为 dataSync: " + se.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, createNotification("应用正在运行", 0));
            }
            foregroundStarted = true;
        }

        // Fire the one-shot MediaProjection-ready callback on the main thread.
        // This guarantees AgentManager calls getMediaProjection() AFTER the FGS
        // has actually been promoted to FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        if (mediaProjectionUpgradedNow) {
            sMediaProjectionUpgraded = true;
            final Runnable cb = sMediaProjectionReadyCallback;
            sMediaProjectionReadyCallback = null;
            if (cb != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        cb.run();
                    } catch (Throwable t) {
                        LogManager.logE(TAG, "MediaProjection ready callback threw: " + t.getMessage());
                    }
                });
            }
        }
        
        // Use START_STICKY when scheduled task is enabled so service restarts if killed
        boolean scheduleEnabled = ConfigManager.getBoolean(getApplicationContext(),
                ConfigManager.KEY_SCHEDULE_ENABLED, false);
        if (scheduleEnabled && alarmPendingIntent == null) {
            startScheduleHeartbeat();
        }
        return scheduleEnabled ? START_STICKY : START_NOT_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        LogManager.logI(TAG, "统一前台服务已销毁");
        
        // 停止前台服务并移除通知
        // minSdk=26 >= Android N(24)，直接使用新API
        stopForeground(STOP_FOREGROUND_REMOVE);
        
        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            LogManager.logD(TAG, "唤醒锁已释放");
        }
        
        // Stop schedule heartbeat
        stopScheduleHeartbeat();
        
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
        LogManager.logI(TAG, "用户从最近任务清除app");
        
        boolean scheduleEnabled = ConfigManager.getBoolean(getApplicationContext(),
                ConfigManager.KEY_SCHEDULE_ENABLED, false);
        
        if (scheduleEnabled) {
            // Schedule enabled: keep service alive for heartbeat
            LogManager.logI(TAG, "定时任务已开启，保持服务运行");
        } else if (!hasActiveTask) {
            // No schedule, no active task: stop service
            stopSelf();
        } else {
            // Has active task: wait for it to finish
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

        // Re-arm the alarm in case it was silently cancelled by Huawei/EMUI when the
        // process returned to foreground. alarmPendingIntent may still be non-null in
        // Java while the actual system alarm was already cancelled, so we always
        // reschedule here to keep the 1-min heartbeat chain intact.
        boolean scheduleEnabled = ConfigManager.getBoolean(getApplicationContext(),
                ConfigManager.KEY_SCHEDULE_ENABLED, false);
        if (scheduleEnabled) {
            scheduleNextAlarm();
            LogManager.logD(TAG, "[SCHEDULE] Alarm re-armed on foreground transition");
        }

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

    public static UnifiedForegroundService getInstance() {
        return sInstance;
    }

    /**
     * Append an inference log line from any client component without depending on UI lifecycle.
     * Routes to BackgroundTaskManager if a task ID is available, otherwise falls back to local buffer.
     */
    public static void appendInferenceLogFromClient(String message) {
        try {
            UnifiedForegroundService svc = sInstance;
            String shortMsg = message;
            if (shortMsg != null && shortMsg.length() > 80) {
                shortMsg = shortMsg.substring(0, 80) + "...";
            }
            if (svc == null) {
                LogManager.logD(TAG, "[FG][LOG] UnifiedForegroundService instance is null when trying to append inference log: " + shortMsg);
                return;
            }
            TaskType type = svc.currentTaskType;
            if (type == TaskType.INFERENCE) {
                // Route to BackgroundTaskManager if task ID is available
                String taskId = svc.currentInferenceTaskId;
                if (taskId != null && !taskId.isEmpty()) {
                    BackgroundTaskManager.getInstance().appendLog(taskId, message);
                    LogManager.logD(TAG, "[FG][LOG] Appended inference log to BackgroundTaskManager: taskId=" + taskId + ", msg=" + shortMsg);
                } else {
                    // Fallback to local buffer
                    svc.appendClientLogLine(message);
                    LogManager.logD(TAG, "[FG][LOG] Appended inference log to local buffer (no taskId): " + shortMsg);
                }
            } else {
                LogManager.logD(TAG, "[FG][LOG] Skip append to UnifiedForegroundService, currentTaskType=" + type + ", msg=" + shortMsg);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[FG] Failed to append inference log to UnifiedForegroundService: " + e.getMessage(), e);
        }
    }
    
    /**
     * Set the current inference task ID for log routing.
     * Called by RagQaFragment when starting an LLM task.
     */
    public void setCurrentInferenceTaskId(String taskId) {
        this.currentInferenceTaskId = taskId;
        LogManager.logD(TAG, "[FG] Set current inference task ID: " + taskId);
    }
    
    /**
     * Get the current inference task ID.
     */
    public String getCurrentInferenceTaskId() {
        return currentInferenceTaskId;
    }

    /**
     * Append important streaming debug/performance chunks for inference tasks.
     * This method intentionally filters out regular tokens to avoid token-level spam.
     */
    public static void appendInferenceDebugChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        // NOTE: Diffusion progress updates are sent as individual chunks like "2..", "3.."
        // We need to match these step progress patterns as well
        boolean interesting =
                chunk.contains("<debug>") ||
                chunk.contains("</debug>") ||
                chunk.contains("<performance>") ||
                chunk.contains("</performance>") ||
                chunk.contains("[IMAGE:") ||
                chunk.contains("[DIFF_DEBUG]") ||
                chunk.contains("[DIFFUSION]") ||
                chunk.contains("UNet Steps") ||
                chunk.contains("UNet: done") ||
                chunk.contains("VAE Decoder") ||
                chunk.contains("Text Encoder") ||
                chunk.matches(".*\\d+\\.\\.$") ||  // Match step progress like "2..", "3.."
                chunk.contains("Completed (");
        if (!interesting) {
            return;
        }
        String shortChunk = chunk;
        if (shortChunk.length() > 80) {
            shortChunk = shortChunk.substring(0, 80) + "...";
        }
        LogManager.logD(TAG, "[FG][PUSH] appendInferenceDebugChunk accepted chunk, len="
                + chunk.length() + ", preview=" + shortChunk);
        appendInferenceLogFromClient(chunk);
    }

    /**
     * Reset in-memory log buffer for current task.
     */
    private synchronized void resetLogBuffer() {
        logBuffer.clear();
        currentLogChars = 0;
    }

    /**
     * Append a single log line into in-memory buffer with simple size cap.
     */
    private synchronized void appendLogLineInternal(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        logBuffer.add(message);
        currentLogChars += message.length();
        while ((logBuffer.size() > MAX_LOG_LINES) || (currentLogChars > MAX_LOG_CHARS)) {
            String removed = logBuffer.remove(0);
            if (removed != null) {
                currentLogChars -= removed.length();
            }
        }
        if (currentLogChars < 0) {
            currentLogChars = 0;
        }
        int size = logBuffer.size();
        LogManager.logD(TAG, "[FG][BUF] appendLogLineInternal after append, size="
                + size + ", chars=" + currentLogChars);
    }

    /**
     * Get a snapshot copy of current log buffer for UI reconnection.
     * For inference tasks with a task ID, returns logs from BackgroundTaskManager.
     * Otherwise falls back to local buffer.
     */
    public synchronized List<String> getLogSnapshot() {
        // For inference tasks, try to get logs from BackgroundTaskManager first
        if (currentTaskType == TaskType.INFERENCE && currentInferenceTaskId != null) {
            List<String> taskLogs = BackgroundTaskManager.getInstance().getLogSnapshot(currentInferenceTaskId);
            if (taskLogs != null && !taskLogs.isEmpty()) {
                LogManager.logD(TAG, "[FG][BUF] getLogSnapshot from BackgroundTaskManager, taskId=" + currentInferenceTaskId + ", size=" + taskLogs.size());
                return taskLogs;
            }
        }
        
        // Fallback to local buffer
        int size = logBuffer.size();
        LogManager.logD(TAG, "[FG][BUF] getLogSnapshot from local buffer, size=" + size
                + ", chars=" + currentLogChars);
        return new ArrayList<>(logBuffer);
    }
    
    /**
     * Get logs from BackgroundTaskManager for a specific task.
     */
    public List<String> getLogSnapshotForTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return new ArrayList<>();
        }
        return BackgroundTaskManager.getInstance().getLogSnapshot(taskId);
    }

    /**
     * Dispatch a log line to both log buffer and UI callback.
     */
    private void dispatchLogLine(String message) {
        appendLogLineInternal(message);
        try {
            if (currentTaskType == TaskType.KB_BUILD && kbBuildTaskId != null && !kbBuildTaskId.isEmpty()) {
                String payload = message;
                if (payload != null && !payload.endsWith("\n")) {
                    payload = payload + "\n";
                }
                BackgroundTaskManager.getInstance().appendLog(kbBuildTaskId, payload);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[KB][BUF_WRITE] Failed to append KB build log: " + e.getMessage(), e);
        }
        if (progressCallback != null) {
            progressCallback.onLogLine(message);
        }
    }

    /**
     * Append a log line from client components (e.g., Fragments) into the
     * unified in-memory log buffer. This allows non-KB tasks such as model
     * download or note processing to reuse the same log replay mechanism.
     */
    public void appendClientLogLine(String message) {
        dispatchLogLine(message);
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
        // Reset log buffer for each new foreground task session
        resetLogBuffer();
        
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
        
        // Check if there are any active TTS tasks before stopping service
        BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
        boolean hasTtsTask = taskManager.hasActiveTasksOfType(BackgroundTask.TaskType.TTS_GENERATION);
        
        if (hasTtsTask) {
            LogManager.logI(TAG, "任务完成，但检测到 TTS 任务正在运行，保持服务运行");
            updateNotification("生成语音中", 0);
            return;
        }
        
        boolean scheduleEnabled = ConfigManager.getBoolean(getApplicationContext(),
                ConfigManager.KEY_SCHEDULE_ENABLED, false);
        
        if (scheduleEnabled) {
            // Schedule enabled: keep service alive, update notification to standby
            LogManager.logI(TAG, "任务完成，定时任务已开启，保持服务待命");
            updateNotification("定时任务待命中", 0);
        } else {
            // No schedule: stop service after delay
            LogManager.logI(TAG, "任务完成，1秒后停止服务并清除通知");
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                boolean stillHasTtsTask = taskManager.hasActiveTasksOfType(BackgroundTask.TaskType.TTS_GENERATION);
                if (stillHasTtsTask) {
                    LogManager.logI(TAG, "检测到 TTS 任务仍在运行，取消停止服务");
                    return;
                }
                if (hasActiveTask) {
                    LogManager.logI(TAG, "检测到新任务已启动，取消停止服务");
                    return;
                }
                stopSelf();
                LogManager.logI(TAG, "服务已停止，通知已清除");
            }, 1000);
        }
    }
    
    /**
     * Check if the app is idle (no active tasks running).
     * Used by schedule heartbeat to determine if a scheduled Agent task can be triggered.
     */
    public boolean isAppIdle() {
        return !hasActiveTask;
    }
    
    // ==================== Schedule Heartbeat Engine (AlarmManager only) ====================
    // Uses AlarmManager.setExactAndAllowWhileIdle for Doze-proof 1-minute heartbeat.
    // Screen on: precise 1min. Doze: ~9min max delay (Android system limit).
    // Each wakeup checks conditions; screen on -> trigger Agent; screen off -> send reminder.
    
    /**
     * Start schedule heartbeat (called when schedule is enabled).
     */
    public void startScheduleHeartbeat() {
        cancelAlarm(); // Ensure no duplicate
        scheduleNextAlarm();
        LogManager.logI(TAG, "[SCHEDULE] Heartbeat started (AlarmManager, 1min)");
        if (!hasActiveTask) {
            updateNotification("定时任务待命中", 0);
        }
    }
    
    /**
     * Stop schedule heartbeat (called when schedule is disabled).
     */
    public void stopScheduleHeartbeat() {
        cancelAlarm();
        // Dismiss any pending reminder notification
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(SCHEDULE_REMINDER_NOTIFICATION_ID);
        LogManager.logI(TAG, "[SCHEDULE] Heartbeat stopped");
    }
    
    /**
     * Schedule next alarm 1 minute from now.
     * In Doze mode, system may delay up to ~9 minutes (Android limit for all apps).
     */
    private void scheduleNextAlarm() {
        if (alarmManager == null) return;
        Intent intent = new Intent(this, UnifiedForegroundService.class);
        intent.setAction(ACTION_SCHEDULE_ALARM);
        alarmPendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + 60 * 1000L; // 1 minute
        try {
            alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent);
        } catch (SecurityException e) {
            // SCHEDULE_EXACT_ALARM permission not granted on Android 12+
            LogManager.logW(TAG, "[SCHEDULE] Cannot set exact alarm: " + e.getMessage());
        }
    }
    
    private void cancelAlarm() {
        if (alarmManager != null && alarmPendingIntent != null) {
            alarmManager.cancel(alarmPendingIntent);
            alarmPendingIntent = null;
        }
    }
    
    /**
     * Handle AlarmManager heartbeat wakeup.
     * Iterates 4 task groups. First due task that meets all conditions wins.
     */
    private void handleAlarmHeartbeat() {
        boolean masterEnabled = ConfigManager.getBoolean(getApplicationContext(),
                ConfigManager.KEY_SCHEDULE_ENABLED, false);
        if (!masterEnabled) {
            // Diagnostic: master switch OFF -> nothing can fire
            LogManager.logD(TAG, "[SCHEDULE] Heartbeat: master switch OFF, skip");
            return;
        }

        // Find the first due task group
        int dueTaskIndex = findDueTaskIndex();
        if (dueTaskIndex < 0) {
            // Diagnostic: no task is due right now (findDueTaskIndex logs per-task reason)
            LogManager.logD(TAG, "[SCHEDULE] Heartbeat: no due task, skip");
            return;
        }

        android.content.Context ctx = getApplicationContext();

        // 2. Check screen on AND unlocked
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean screenOn = pm.isInteractive();
        boolean unlocked = (km != null) && !km.isKeyguardLocked();

        if (!screenOn || !unlocked) {
            // IMPORTANT: do NOT update lastScheduledRunTime here, otherwise once-per-day
            // tasks (start==end) would be marked as "fired today" and never retry after
            // the user unlocks. Use a separate reminder throttle instead.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastReminderTime[dueTaskIndex] < REMINDER_THROTTLE_MS) {
                LogManager.logD(TAG, "[SCHEDULE] Task " + (dueTaskIndex + 1) + " reminder throttled (screenOn=" + screenOn + ", unlocked=" + unlocked + ")");
                return;
            }
            lastReminderTime[dueTaskIndex] = nowMs;
            LogManager.logI(TAG, "[SCHEDULE] Task " + (dueTaskIndex + 1) + " due but not ready (screenOn=" + screenOn + ", unlocked=" + unlocked + "), sending reminder");
            sendScheduleReminderNotification(dueTaskIndex);
            return;
        }

        // 3. Screen on + unlocked: check runtime conditions and trigger Agent
        if (!isAppIdle()) {
            LogManager.logI(TAG, "[SCHEDULE] Heartbeat: app busy (" + currentTaskType.getDisplayName() + "), skip");
            return;
        }

        com.example.offlineai.agent.service.AgentAccessibilityService agentService =
                com.example.offlineai.agent.service.AgentAccessibilityService.Companion.getInstance();
        if (agentService == null) {
            LogManager.logW(TAG, "[SCHEDULE] Heartbeat: AgentAccessibilityService not available, skip");
            return;
        }

        // Build prompt: explicit prompt text, or fallback to prompt_file content
        String explicitPrompt = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(dueTaskIndex, "prompt"), "");
        final String prompt;
        if (!explicitPrompt.isEmpty()) {
            prompt = explicitPrompt;
        } else {
            String promptFile = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(dueTaskIndex, "prompt_file"), "common_agent.txt");
            prompt = com.example.offlineai.agent.AgentPrompts.loadAgentUserPromptFile(ctx, promptFile);
        }
        if (prompt.isEmpty()) {
            LogManager.logW(TAG, "[SCHEDULE] Task " + (dueTaskIndex + 1) + ": prompt is empty, skip");
            return;
        }

        // All conditions met - trigger Agent!
        lastScheduledRunTime[dueTaskIndex] = System.currentTimeMillis();
        LogManager.logI(TAG, "[SCHEDULE] === TRIGGERING SCHEDULED AGENT TASK " + (dueTaskIndex + 1) + " ===");
        LogManager.logI(TAG, "[SCHEDULE] Prompt: " + prompt);

        // One-shot: disable after trigger
        boolean oneShot = ConfigManager.getBoolean(ctx, ConfigManager.scheduleTaskKey(dueTaskIndex, "one_shot"), false);
        if (oneShot) {
            ConfigManager.setBoolean(ctx, ConfigManager.scheduleTaskKey(dueTaskIndex, "enabled"), false);
            LogManager.logI(TAG, "[SCHEDULE] Task " + (dueTaskIndex + 1) + " is one-shot, disabled after trigger");
        }

        // Dismiss any previous reminder
        NotificationManager nmDismiss = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nmDismiss.cancel(SCHEDULE_REMINDER_NOTIFICATION_ID);

        new android.os.Handler(getMainLooper()).post(() -> {
            try {
                agentService.startAgentLoop(prompt, true);
                LogManager.logI(TAG, "[SCHEDULE] Agent loop started for task " + (dueTaskIndex + 1));
            } catch (Exception e) {
                LogManager.logE(TAG, "[SCHEDULE] Failed to start Agent loop: " + e.getMessage());
            }
        });
    }

    /**
     * Find the first task group that is due. Returns -1 if none.
     */
    private int findDueTaskIndex() {
        android.content.Context ctx = getApplicationContext();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);
        int ourDay = (dayOfWeek == java.util.Calendar.SUNDAY) ? 7 : (dayOfWeek - 1);
        int currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int currentMin = cal.get(java.util.Calendar.MINUTE);
        int nowMinutes = currentHour * 60 + currentMin;
        long now = System.currentTimeMillis();

        for (int i = 0; i < ConfigManager.SCHEDULE_TASK_COUNT; i++) {
            // Task enabled?
            if (!ConfigManager.getBoolean(ctx, ConfigManager.scheduleTaskKey(i, "enabled"), false)) {
                LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: disabled");
                continue;
            }

            // Weekday match?
            String weekdays = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "weekdays"), "1,2,3,4,5");
            if (!("," + weekdays + ",").contains("," + ourDay + ",")) {
                // Print full task config for debugging
                boolean oneShot = ConfigManager.getBoolean(ctx, ConfigManager.scheduleTaskKey(i, "one_shot"), false);
                int startH = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_hour"), 9);
                int startM = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_min"), 0);
                int endH = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_hour"), 17);
                int endM = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_min"), 0);
                int interval = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "interval"), 30);
                String agentPreset = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "agent_preset"), "");
                String prompt = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "prompt"), "");
                LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: weekday mismatch (today=" + ourDay + ", set=" + weekdays + ") | FULL_CONFIG: enabled=true, one_shot=" + oneShot + ", weekdays=" + weekdays + ", start=" + startH + ":" + startM + ", end=" + endH + ":" + endM + ", interval=" + interval + ", agent_preset=" + agentPreset + ", prompt=" + prompt);
                continue;
            }

            // Time range check: startHH:MM ~ endHH:MM
            int startH = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_hour"), 9);
            int startM = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_min"), 0);
            int endH = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_hour"), 17);
            int endM = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_min"), 0);
            int startMinutes = startH * 60 + startM;
            int endMinutes = endH * 60 + endM;

            if (endMinutes <= startMinutes) {
                // Same start/end (or inverted range): "fire once a day after start time".
                // Trigger on the first heartbeat at or after startMinutes when today has
                // not fired yet. Doze / screen-off / busy are tolerated: we will keep
                // trying on every subsequent heartbeat until today successfully fires.
                if (nowMinutes < startMinutes) {
                    LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: before start (now=" + nowMinutes + "min, start=" + startMinutes + "min, once-a-day mode)");
                    continue;
                }
                if (isSameLocalDay(lastScheduledRunTime[i], now)) {
                    LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: already fired today (once-a-day mode, lastRun=" + lastScheduledRunTime[i] + ")");
                    continue;
                }
            } else {
                if (nowMinutes < startMinutes || nowMinutes > endMinutes) {
                    LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: outside time range (now=" + nowMinutes + "min, range=" + startMinutes + "~" + endMinutes + "min)");
                    continue;
                }
                // Interval check (only applies to range mode; once-per-day mode uses day-boundary guard)
                int intervalMin = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "interval"), 30);
                long intervalMs = intervalMin * 60L * 1000L;
                if (now - lastScheduledRunTime[i] < intervalMs) {
                    LogManager.logD(TAG, "[SCHEDULE] Task " + (i + 1) + " skip: within interval (sinceLastRun=" + (now - lastScheduledRunTime[i]) + "ms, interval=" + intervalMs + "ms)");
                    continue;
                }
            }

            LogManager.logI(TAG, "[SCHEDULE] Task " + (i + 1) + " is DUE (now=" + nowMinutes + "min, start=" + startMinutes + "min, end=" + endMinutes + "min)");
            return i;
        }
        return -1;
    }

    /**
     * Return true when both timestamps fall on the same local calendar day.
     * Special case: if {@code prevMs} is 0 (never fired), returns false so the first trigger is allowed.
     */
    private static boolean isSameLocalDay(long prevMs, long nowMs) {
        if (prevMs <= 0L) return false;
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.setTimeInMillis(prevMs);
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(nowMs);
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    // --- Reminder notification ---

    private void createScheduleReminderChannel() {
        NotificationChannel channel = new NotificationChannel(
                SCHEDULE_REMINDER_CHANNEL_ID,
                getString(R.string.schedule_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Reminder to unlock screen for scheduled Agent tasks");
        channel.enableVibration(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void sendScheduleReminderNotification(int taskIndex) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        notifIntent.setAction(Intent.ACTION_MAIN);
        notifIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String prompt = ConfigManager.getString(getApplicationContext(), ConfigManager.scheduleTaskKey(taskIndex, "prompt"), "");
        String taskLabel = String.format(getString(R.string.schedule_task_label), taskIndex + 1);
        String title = getString(R.string.schedule_reminder_title) + " - " + taskLabel;
        String text = getString(R.string.schedule_reminder_text);
        if (!prompt.isEmpty()) {
            String snippet = prompt.substring(0, Math.min(prompt.length(), 30));
            text += "\n" + taskLabel + ": " + snippet + "...";
        }

        Notification notification = new NotificationCompat.Builder(this, SCHEDULE_REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVibrate(new long[]{0, 300, 200, 300})
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(SCHEDULE_REMINDER_NOTIFICATION_ID, notification);
        LogManager.logI(TAG, "[SCHEDULE] Reminder notification sent for task " + (taskIndex + 1));
    }
    
    // ==================== End Schedule Heartbeat Engine ====================
    
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
        // Reset log buffer for new KB build session
        resetLogBuffer();
        
        LogManager.logD(TAG, "开始构建知识库: " + knowledgeBaseName + ", 文件数量: " + selectedFiles.size() + ", 模型: " + embeddingModel);

        // Create a background task snapshot for KB build so that
        // UI/notifications can observe progress in a unified way.
        try {
            BackgroundTaskManager taskManager = BackgroundTaskManager.getInstance();
            HashMap<String, String> extras = new HashMap<>();
            extras.put("kbName", knowledgeBaseName);
            extras.put("embeddingModel", embeddingModel);
            if (rerankerModel != null && !rerankerModel.isEmpty()) {
                extras.put("rerankerModel", rerankerModel);
            }
            int fileCount = selectedFiles != null ? selectedFiles.size() : 0;
            extras.put("fileCount", String.valueOf(fileCount));

            BackgroundTask task = taskManager.createTask(
                BackgroundTask.TaskType.KB_BUILD,
                "Build knowledge base: " + knowledgeBaseName,
                true,
                extras
            );
            kbBuildTaskId = task.getId();
            LogManager.logD(TAG, "Background task created for KB build, id=" + kbBuildTaskId);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to create background task for KB build: " + e.getMessage(), e);
            kbBuildTaskId = null;
        }
        
        // 使用通用任务管理
        startTask(TaskType.KB_BUILD, "构建知识库: " + knowledgeBaseName);
        
        // 在后台线程中执行构建任务
        executor.execute(() -> {
            LogManager.logD(TAG, "开始执行知识库构建任务，线程ID: " + Thread.currentThread().getId());
            try {
                // 执行知识库构建逻辑
                boolean success = buildKnowledgeBase(knowledgeBaseName, embeddingModel, rerankerModel, selectedFiles);

                boolean cancelledByUser = isTaskCancelled.get();

                // 任务完成回调
                if (progressCallback != null) {
                    if (success) {
                        progressCallback.onBuildCompleted(true);
                        progressCallback.onTaskCompleted(true, getString(R.string.kb_build_completed, knowledgeBaseName));
                        LogManager.logD(TAG, getString(R.string.kb_build_success_log, knowledgeBaseName));
                    } else {
                        progressCallback.onBuildCompleted(false);
                        if (cancelledByUser) {
                            // User-requested cancellation
                            String msg = getString(R.string.kb_build_cancelled);
                            progressCallback.onTaskCompleted(false, msg);
                            LogManager.logD(TAG, getString(R.string.kb_build_cancelled_log, knowledgeBaseName));
                        } else {
                            // Build failed due to error (including OOM); detailed reason is already logged to UI buffer
                            String msg = getString(R.string.kb_build_failed_log);
                            progressCallback.onTaskCompleted(false, msg);
                            LogManager.logD(TAG, msg);
                        }
                    }
                }

                // Update background task state based on final result
                if (kbBuildTaskId != null) {
                    try {
                        if (success) {
                            String message = getString(R.string.kb_build_completed, knowledgeBaseName);
                            BackgroundTaskManager.getInstance().updateTask(
                                    kbBuildTaskId,
                                    BackgroundTask.TaskState.COMPLETED,
                                    100,
                                    message
                            );
                        } else if (cancelledByUser) {
                            String message = getString(R.string.kb_build_cancelled);
                            BackgroundTaskManager.getInstance().updateTask(
                                    kbBuildTaskId,
                                    BackgroundTask.TaskState.CANCELLED,
                                    currentProgress,
                                    message
                            );
                        } else {
                            String message = getString(R.string.kb_build_failed_log);
                            BackgroundTaskManager.getInstance().updateTask(
                                    kbBuildTaskId,
                                    BackgroundTask.TaskState.FAILED,
                                    currentProgress,
                                    message
                            );
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task on KB build completion: " + e.getMessage(), e);
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

                // Update background task state as FAILED
                if (kbBuildTaskId != null) {
                    try {
                        String message = getString(R.string.kb_build_failed_log);
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.FAILED,
                                currentProgress,
                                message
                        );
                    } catch (Exception inner) {
                        LogManager.logE(TAG, "Failed to update background task on KB build failure: " + inner.getMessage(), inner);
                    }
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

        // Update background task state to CANCELLED
        try {
            if (kbBuildTaskId != null) {
                BackgroundTaskManager.getInstance().updateTask(
                    kbBuildTaskId,
                    BackgroundTask.TaskState.CANCELLED,
                    currentProgress,
                    "Knowledge base build cancelled by user"
                );
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to update background task on cancel: " + e.getMessage(), e);
        }

        // For long-running KB build tasks, also schedule a hard stop
        // for the inference process in case cooperative cancellation
        // (isTaskCancelled + executor shutdown) is not sufficient.
        if (currentTaskType == TaskType.KB_BUILD) {
            try {
                com.example.offlineai.ipc.InferenceClient client =
                        com.example.offlineai.ipc.InferenceClient.getInstance(getApplicationContext());
                LogManager.logD(TAG, "Scheduling forced inference process kill after KB cancel timeout");
                // Use a conservative timeout (10 seconds) to allow
                // in-flight embedding calls to finish gracefully
                // before killing the child process.
                client.requestForceKillAfterTimeout(10_000L);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to schedule forced inference stop for KB cancel: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 实际构建知识库的逻辑
     * @return 是否成功完成（未被取消）
     */
    private boolean buildKnowledgeBase(String knowledgeBaseName, String embeddingModel, String rerankerModel, List<Uri> selectedFiles) {
        try {
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

        // 2.2 Emit initial configuration lines to log buffer and UI
        try {
            String kbLine = "Knowledge base: " + knowledgeBaseName;
            String embedLine = "Embedding model: " + embeddingModel;
            String rerankLine = "Reranker model: " + (rerankerModel == null || rerankerModel.isEmpty() ? "None" : rerankerModel);
            String chunkLine = "Chunk size: " + chunkSize + ", overlap: " + chunkOverlap;
            String dictLine = "Dictionary (configured): " + (dictFileName.isEmpty() ? "None" : dictFileName);
            dispatchLogLine(kbLine);
            dispatchLogLine(embedLine);
            dispatchLogLine(rerankLine);
            dispatchLogLine(chunkLine);
            dispatchLogLine(dictLine);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to emit build configuration lines", e);
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

                // Sync background task snapshot for KB build
                if (kbBuildTaskId != null) {
                    try {
                        String message = String.format(
                                Locale.getDefault(),
                                "%s (%d/%d): %s",
                                getString(R.string.progress_text_extraction_keyword),
                                processedFiles,
                                totalFiles,
                                currentFile
                        );
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.RUNNING,
                                overall,
                                message
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (text extraction): " + e.getMessage(), e);
                    }
                }
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

                // Sync background task snapshot for KB build
                if (kbBuildTaskId != null) {
                    try {
                        String message = "Vectorizing chunks: " + processedChunks + "/" + totalChunks;
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.RUNNING,
                                overall,
                                message
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (vectorization): " + e.getMessage(), e);
                    }
                }
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

                // Sync background task snapshot for KB build (stage transition)
                if (kbBuildTaskId != null) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.RUNNING,
                                overall,
                                status
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (text extraction complete): " + e.getMessage(), e);
                    }
                }
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
                dispatchLogLine(status);
                // Update numeric progress using unified overall percentage
                ProgressManager.ProgressData progressData = progressManager.getCurrentProgress();
                int overall = Math.round(progressData.getOverallProgressPercentage());
                updateProgress(overall, null);

                // Sync background task snapshot for KB build (vectorization stage complete, may continue with graph building)
                if (kbBuildTaskId != null) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.RUNNING,
                                overall,
                                status
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (vectorization complete): " + e.getMessage(), e);
                    }
                }
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
                dispatchLogLine(status);
                // Use unified overall progress instead of hard-coded 100%
                updateProgress(overall, null);

                // Sync background task snapshot for KB build (graph building stage)
                if (kbBuildTaskId != null) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.RUNNING,
                                overall,
                                status
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (graph building): " + e.getMessage(), e);
                    }
                }
            }
            
            @Override
            public void onError(String errorMessage) {
                // 处理错误
                LogManager.logE(TAG, "错误: " + errorMessage);
                String uiMessage = getString(R.string.error_message, errorMessage);
                // Send error text via onLogLine only; numeric progress resets to 0 without status text
                dispatchLogLine(uiMessage);
                updateProgress(0, null);

                // Sync background task snapshot for KB build (error state)
                if (kbBuildTaskId != null) {
                    try {
                        BackgroundTaskManager.getInstance().updateTask(
                                kbBuildTaskId,
                                BackgroundTask.TaskState.FAILED,
                                0,
                                uiMessage
                        );
                    } catch (Exception e) {
                        LogManager.logE(TAG, "Failed to update background task (error): " + e.getMessage(), e);
                    }
                }
            }
            
            @Override
            public void onLog(String message) {
                // 记录日志
                LogManager.logD(TAG, message);
                dispatchLogLine(message);
            }
        });
        
        // 设置通知进度回调（目前仅作为占位，不更新通知栏进度）
        textChunkProcessor.setNotificationProgressCallback(new TextChunkProcessor.NotificationProgressCallback() {
            @Override
            public void onNotificationProgressUpdate(int processedChunks, int totalChunks, float percentage) {
                // Intentionally left blank. Notification progress is driven by
                // unified overall percentage in updateTextExtractionProgress/updateProgress.
                // updateNotificationProgress(processedChunks, totalChunks, percentage);
            }
        });

        // 4. Start processing and return result
        // Use the same chunkSize and chunkOverlap configuration that were
        // computed above and recorded into ProgressManager, so that
        // TextChunkProcessor has a consistent view of chunking settings.
        return textChunkProcessor.processFilesAndBuildKnowledgeBase(
                knowledgeBaseName,
                embeddingModel,
                rerankerModel,
                selectedFiles,
                chunkSize,
                chunkOverlap
        );
        } catch (OutOfMemoryError oom) {
            LogManager.logE(TAG, "[KB_BUILD] OutOfMemoryError during knowledge base build: " + oom.getMessage(), oom);
            try {
                String uiMessage = "构建知识库时内存不足，请减少文件数量或拆分为多个知识库后重试。";
                dispatchLogLine(uiMessage);
                updateProgress(0, null);
                if (kbBuildTaskId != null) {
                    BackgroundTaskManager.getInstance().updateTask(
                            kbBuildTaskId,
                            BackgroundTask.TaskState.FAILED,
                            0,
                            uiMessage
                    );
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "[KB_BUILD] Failed to handle OOM state: " + e.getMessage(), e);
            }
            return false;
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
