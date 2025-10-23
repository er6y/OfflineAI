package com.example.offlineai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * 音频播放服务 - 单例
 * 管理所有音频文件的播放
 * 
 * @author OfflineAI Team
 */
public class AudioPlaybackService {
    private static final String TAG = "AudioPlaybackService";
    private static AudioPlaybackService instance;
    
    private Map<String, AudioPlayerState> players = new HashMap<>();
    private Handler mainHandler;
    
    /**
     * 播放回调接口
     */
    public interface AudioPlayerCallback {
        void onPlayStart();
        void onPlayProgress(float progress);  // 0.0 - 1.0
        void onPlayFinish();
        void onPlayError(String error);
    }
    
    /**
     * 播放器状态
     */
    private static class AudioPlayerState {
        String audioPath;
        AudioTrack audioTrack;
        Thread playbackThread;
        boolean isPlaying = false;
        boolean isPaused = false;
        AudioPlayerCallback callback;
        long totalFrames = 0;
        long currentFrame = 0;
    }
    
    private AudioPlaybackService() {
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized AudioPlaybackService getInstance() {
        if (instance == null) {
            instance = new AudioPlaybackService();
        }
        return instance;
    }
    
    /**
     * 播放音频文件
     */
    public void playAudio(String audioPath, AudioPlayerCallback callback) {
        if (audioPath == null || audioPath.isEmpty()) {
            if (callback != null) {
                callback.onPlayError("Audio path is null");
            }
            return;
        }
        
        // 停止之前的播放
        stopAudio(audioPath);
        
        File audioFile = new File(audioPath);
        if (!audioFile.exists()) {
            if (callback != null) {
                callback.onPlayError("Audio file not found");
            }
            return;
        }
        
        AudioPlayerState player = new AudioPlayerState();
        player.audioPath = audioPath;
        player.callback = callback;
        players.put(audioPath, player);
        
        // 在后台线程播放
        player.playbackThread = new Thread(() -> {
            try {
                playAudioInBackground(player);
            } catch (Exception e) {
                LogManager.logE(TAG, "Error playing audio: " + audioPath, e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onPlayError(e.getMessage()));
                }
                players.remove(audioPath);
            }
        });
        player.playbackThread.start();
    }
    
    /**
     * 后台播放音频
     */
    private void playAudioInBackground(AudioPlayerState player) throws IOException {
        File audioFile = new File(player.audioPath);
        FileInputStream fis = new FileInputStream(audioFile);
        
        // 跳过WAV文件头（44字节）
        byte[] header = new byte[44];
        fis.read(header);
        
        // 从WAV头读取参数
        int sampleRate = readInt(header, 24);
        int channels = readShort(header, 22);
        int bitsPerSample = readShort(header, 34);
        
        LogManager.logI(TAG, String.format("Playing audio: sampleRate=%d, channels=%d, bits=%d", 
            sampleRate, channels, bitsPerSample));
        
        // 配置AudioTrack
        int channelConfig = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        
        player.audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build();
        
        player.audioTrack.play();
        player.isPlaying = true;
        
        // 通知开始播放
        if (player.callback != null) {
            mainHandler.post(() -> player.callback.onPlayStart());
        }
        
        // 计算总帧数
        long fileSize = audioFile.length() - 44;  // 减去头
        player.totalFrames = fileSize / (bitsPerSample / 8 * channels);
        
        // 读取并播放数据
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = fis.read(buffer)) != -1 && player.isPlaying) {
            if (!player.isPaused) {
                player.audioTrack.write(buffer, 0, bytesRead);
                
                // 更新进度
                player.currentFrame += bytesRead / (bitsPerSample / 8 * channels);
                float progress = (float) player.currentFrame / player.totalFrames;
                
                if (player.callback != null) {
                    mainHandler.post(() -> player.callback.onPlayProgress(progress));
                }
            } else {
                // 暂停时休眠
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        
        // 清理
        fis.close();
        if (player.audioTrack != null) {
            player.audioTrack.stop();
            player.audioTrack.release();
        }
        
        // 通知完成
        if (player.callback != null && player.isPlaying) {
            mainHandler.post(() -> player.callback.onPlayFinish());
        }
        
        players.remove(player.audioPath);
    }
    
    /**
     * 暂停播放
     */
    public void pauseAudio(String audioPath) {
        AudioPlayerState player = players.get(audioPath);
        if (player != null) {
            player.isPaused = true;
            if (player.audioTrack != null) {
                player.audioTrack.pause();
            }
        }
    }
    
    /**
     * 恢复播放
     */
    public void resumeAudio(String audioPath) {
        AudioPlayerState player = players.get(audioPath);
        if (player != null) {
            player.isPaused = false;
            if (player.audioTrack != null) {
                player.audioTrack.play();
            }
        }
    }
    
    /**
     * 停止播放
     */
    public void stopAudio(String audioPath) {
        AudioPlayerState player = players.get(audioPath);
        if (player != null) {
            player.isPlaying = false;
            if (player.audioTrack != null) {
                player.audioTrack.stop();
                player.audioTrack.release();
            }
            if (player.playbackThread != null) {
                player.playbackThread.interrupt();
            }
            players.remove(audioPath);
        }
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying(String audioPath) {
        AudioPlayerState player = players.get(audioPath);
        return player != null && player.isPlaying && !player.isPaused;
    }
    
    /**
     * 跳转到指定进度
     */
    public void seekAudio(String audioPath, float progress) {
        // TODO: 实现音频跳转
        LogManager.logW(TAG, "Audio seek not implemented yet");
    }
    
    /**
     * 停止所有播放
     */
    public void stopAll() {
        for (String audioPath : players.keySet().toArray(new String[0])) {
            stopAudio(audioPath);
        }
    }
    
    // Helper methods to read WAV header
    private int readInt(byte[] data, int offset) {
        return ((data[offset + 3] & 0xFF) << 24) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 1] & 0xFF) << 8) |
               (data[offset] & 0xFF);
    }
    
    private short readShort(byte[] data, int offset) {
        return (short) (((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }
}
