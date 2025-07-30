package com.example.starlocalrag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 知识库构建服务
 * 用于在后台运行知识库构建过程，防止应用被系统杀死
 */
public class KnowledgeBaseService extends Service {
    private static final String TAG = "StarLocalRAG_Service";
    private static final String CHANNEL_ID = "KnowledgeBaseServiceChannel";
    private static final int NOTIFICATION_ID = 1001;
    
    private final IBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    private boolean isRunning = false;
    
    /**
     * 本地Binder类，用于客户端绑定服务
     */
    public class LocalBinder extends Binder {
        KnowledgeBaseService getService() {
            return KnowledgeBaseService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 创建通知渠道
        createNotificationChannel();
        
        // 获取唤醒锁
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                "StarLocalRAG:KnowledgeBaseWakeLock");
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Knowledge Base Build Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Used to run knowledge base building process in background");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogManager.logD(TAG, "Knowledge base build service started");
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("Building knowledge base..."));
        
        // 获取唤醒锁
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        
        isRunning = true;
        
        // 如果服务被系统杀死，重新启动
        return START_STICKY;
    }
    
    /**
     * 创建通知
     * @param message 通知消息
     * @return 通知对象
     */
    private Notification createNotification(String message) {
        // 创建打开应用的PendingIntent
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        // 创建通知
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("StarLocalRAG")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
    
    /**
     * 更新通知消息
     * @param message 新的通知消息
     */
    public void updateNotification(String message) {
        if (isRunning) {
            NotificationManager notificationManager = 
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
        }
    }
    
    /**
     * 停止服务
     */
    public void stopService() {
        isRunning = false;
        
        // 释放唤醒锁
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 确保释放唤醒锁
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        LogManager.logD(TAG, "Knowledge base build service stopped");
    }
}
