package com.example.offlineai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;

/**
 * 音频播放助手
 * 使用 MediaPlayer 播放 WAV 音频
 * 
 * @author OfflineAI Team
 */
public class AudioPlayerHelper {
    private static final String TAG = "AudioPlayerHelper";
    
    private MediaPlayer mediaPlayer;
    private PlaybackCallback callback;
    private Handler mainHandler;
    private File currentFile;
    private boolean isPrepared = false;
    
    /**
     * 播放回调接口
     */
    public interface PlaybackCallback {
        /**
         * 准备完成（可以开始播放）
         */
        default void onPrepared() {}
        
        /**
         * 播放开始
         */
        void onPlaybackStarted();
        
        /**
         * 播放进度更新
         * @param currentMs 当前位置（毫秒）
         * @param totalMs 总时长（毫秒）
         */
        void onProgressUpdate(int currentMs, int totalMs);
        
        /**
         * 播放完成
         */
        void onPlaybackCompleted();
        
        /**
         * 播放错误
         * @param error 错误信息
         */
        void onPlaybackError(String error);
    }
    
    public AudioPlayerHelper() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 准备播放音频文件
     * @param audioFile 音频文件
     * @param callback 回调
     * @return 是否准备成功
     */
    public boolean prepare(File audioFile, PlaybackCallback callback) {
        if (audioFile == null || !audioFile.exists()) {
            LogManager.logE(TAG, "Audio file not found: " + audioFile);
            if (callback != null) {
                callback.onPlaybackError("音频文件不存在");
            }
            return false;
        }
        
        this.currentFile = audioFile;
        this.callback = callback;
        
        try {
            // 释放之前的播放器
            release();
            
            // 创建新的 MediaPlayer
            mediaPlayer = new MediaPlayer();
            
            // 设置音频属性
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
            
            // 设置数据源
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            
            // 设置监听器
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                LogManager.logI(TAG, "Audio prepared: " + audioFile.getName() + 
                    ", duration: " + mp.getDuration() + "ms");
                // Notify callback that preparation is complete
                if (callback != null) {
                    mainHandler.post(() -> callback.onPrepared());
                }
            });
            
            mediaPlayer.setOnCompletionListener(mp -> {
                LogManager.logI(TAG, "Playback completed");
                if (callback != null) {
                    mainHandler.post(() -> callback.onPlaybackCompleted());
                }
            });
            
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                String error = "Playback error: what=" + what + ", extra=" + extra;
                LogManager.logE(TAG, error);
                if (callback != null) {
                    mainHandler.post(() -> callback.onPlaybackError("播放出错"));
                }
                return true;
            });
            
            // 异步准备
            mediaPlayer.prepareAsync();
            
            return true;
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Failed to prepare audio", e);
            if (callback != null) {
                callback.onPlaybackError("音频准备失败: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 开始播放（需先调用prepare）
     */
    public void start() {
        if (mediaPlayer == null) {
            LogManager.logW(TAG, "MediaPlayer not initialized");
            return;
        }
        
        if (!isPrepared) {
            LogManager.logW(TAG, "MediaPlayer not prepared yet");
            return;
        }
        
        try {
            mediaPlayer.start();
            LogManager.logI(TAG, "Playback started");
            
            if (callback != null) {
                mainHandler.post(() -> callback.onPlaybackStarted());
            }
            
            // 开始进度更新
            startProgressUpdates();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to start playback", e);
            if (callback != null) {
                callback.onPlaybackError("播放启动失败");
            }
        }
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            LogManager.logI(TAG, "Playback paused");
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                LogManager.logI(TAG, "Playback stopped");
            } catch (Exception e) {
                LogManager.logE(TAG, "Error stopping playback", e);
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                LogManager.logE(TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
        isPrepared = false;
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
    
    /**
     * 获取当前播放位置（毫秒）
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception e) {
                LogManager.logE(TAG, "Error getting current position", e);
            }
        }
        return 0;
    }
    
    /**
     * 获取总时长（毫秒）
     */
    public int getDuration() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception e) {
                LogManager.logE(TAG, "Error getting duration", e);
            }
        }
        return 0;
    }
    
    /**
     * 跳转到指定位置
     * @param positionMs 位置（毫秒）
     */
    public void seekTo(int positionMs) {
        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.seekTo(positionMs);
            } catch (Exception e) {
                LogManager.logE(TAG, "Error seeking", e);
            }
        }
    }
    
    /**
     * 开始进度更新
     */
    private void startProgressUpdates() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying() && callback != null) {
                    int currentMs = getCurrentPosition();
                    int totalMs = getDuration();
                    callback.onProgressUpdate(currentMs, totalMs);
                    
                    // 每100ms更新一次进度
                    mainHandler.postDelayed(this, 100);
                }
            }
        });
    }
    
    /**
     * 快速播放文件（prepare + start）
     * @param audioFile 音频文件
     * @param callback 回调
     */
    public void playFile(File audioFile, PlaybackCallback callback) {
        if (prepare(audioFile, callback)) {
            // 等待准备完成后自动播放
            mainHandler.postDelayed(() -> {
                if (isPrepared) {
                    start();
                }
            }, 500);  // 给prepare一些时间
        }
    }
}
