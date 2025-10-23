package com.example.offlineai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频录制助手
 * 使用 AudioRecord 录制 WAV 格式音频
 * 
 * 格式：PCM 16bit, 16kHz, Mono（符合 MNN Omni 模型要求）
 * 
 * @author OfflineAI Team
 */
public class AudioRecorderHelper {
    private static final String TAG = "AudioRecorderHelper";
    
    // 音频参数 - 符合 Qwen2.5-Omni-3B-MNN 要求
    private static final int SAMPLE_RATE = 16000;  // 16kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;  // Mono
    
    // 录音时长限制（毫秒）
    private static final long MAX_RECORDING_DURATION_MS = 60000;  // 60秒
    
    // 录音状态
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private File outputFile;
    private long recordingStartTime;
    private long lastRecordingDuration = 0;  // 保存最后一次录音时长
    private Handler mainHandler;
    
    // 回调接口
    private RecordingCallback callback;
    
    /**
     * 录音回调接口
     */
    public interface RecordingCallback {
        /**
         * 录音振幅更新（用于波形显示）
         * @param amplitude 振幅值 (0-32767)
         */
        void onAmplitudeUpdate(int amplitude);
        
        /**
         * 录音时长更新
         * @param durationMs 时长（毫秒）
         */
        void onDurationUpdate(long durationMs);
        
        /**
         * 达到最大录音时长
         */
        void onMaxDurationReached();
        
        /**
         * 录音错误
         * @param error 错误信息
         */
        void onError(String error);
    }
    
    public AudioRecorderHelper() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 开始录音
     * @param outputFile 输出文件
     * @param callback 回调
     * @return 是否成功开始
     */
    public boolean startRecording(File outputFile, RecordingCallback callback) {
        if (isRecording) {
            LogManager.logW(TAG, "Already recording");
            return false;
        }
        
        this.outputFile = outputFile;
        this.callback = callback;
        
        // 计算缓冲区大小
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            notifyError("无法获取音频缓冲区大小");
            return false;
        }
        
        // 使用2倍缓冲区大小以避免数据丢失
        bufferSize *= 2;
        
        try {
            // 创建 AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError("AudioRecord 初始化失败");
                return false;
            }
            
            // 开始录音
            audioRecord.startRecording();
            isRecording = true;
            recordingStartTime = System.currentTimeMillis();
            
            LogManager.logI(TAG, "Recording started: " + outputFile.getAbsolutePath());
            
            // 启动录音线程
            recordingThread = new Thread(new RecordingRunnable(bufferSize), "AudioRecorder");
            recordingThread.start();
            
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to start recording", e);
            notifyError("录音启动失败: " + e.getMessage());
            release();
            return false;
        }
    }
    
    /**
     * 停止录音并保存文件
     * @return 录音文件，失败返回null
     */
    public File stopRecording() {
        if (!isRecording) {
            LogManager.logW(TAG, "Not recording");
            return null;
        }
        
        // 保存录音时长（在设置isRecording=false之前）
        lastRecordingDuration = System.currentTimeMillis() - recordingStartTime;
        
        isRecording = false;
        
        try {
            // 等待录音线程结束
            if (recordingThread != null) {
                recordingThread.join(1000);
            }
        } catch (InterruptedException e) {
            LogManager.logW(TAG, "Interrupted while waiting for recording thread", e);
        }
        
        release();
        
        // 验证文件
        if (outputFile != null && outputFile.exists() && outputFile.length() > 44) {
            LogManager.logI(TAG, String.format("Recording stopped: %s, duration: %.1fs, size: %d bytes",
                outputFile.getName(), lastRecordingDuration / 1000.0, outputFile.length()));
            return outputFile;
        } else {
            LogManager.logE(TAG, "Recording file is invalid or too short");
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
            return null;
        }
    }
    
    /**
     * 取消录音（删除文件）
     */
    public void cancelRecording() {
        if (!isRecording) {
            return;
        }
        
        isRecording = false;
        
        try {
            if (recordingThread != null) {
                recordingThread.join(1000);
            }
        } catch (InterruptedException e) {
            LogManager.logW(TAG, "Interrupted while canceling", e);
        }
        
        release();
        
        // 删除文件
        if (outputFile != null && outputFile.exists()) {
            if (outputFile.delete()) {
                LogManager.logI(TAG, "Recording canceled and file deleted");
            }
        }
        
        outputFile = null;
    }
    
    /**
     * 释放资源
     */
    private void release() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                LogManager.logE(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
    }
    
    /**
     * 录音线程
     */
    private class RecordingRunnable implements Runnable {
        private final int bufferSize;
        
        public RecordingRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }
        
        @Override
        public void run() {
            FileOutputStream fos = null;
            
            try {
                // 创建临时文件用于写入PCM数据
                File tempPcmFile = new File(outputFile.getParent(), outputFile.getName() + ".tmp");
                fos = new FileOutputStream(tempPcmFile);
                
                byte[] audioData = new byte[bufferSize];
                long lastAmplitudeUpdate = 0;
                long lastDurationUpdate = 0;
                
                while (isRecording) {
                    // 读取音频数据
                    int bytesRead = audioRecord.read(audioData, 0, bufferSize);
                    
                    if (bytesRead > 0) {
                        // 写入PCM数据
                        fos.write(audioData, 0, bytesRead);
                        
                        // 计算振幅（用于波形显示）
                        long now = System.currentTimeMillis();
                        if (now - lastAmplitudeUpdate >= 50) {  // 每50ms更新一次
                            int amplitude = calculateAmplitude(audioData, bytesRead);
                            notifyAmplitude(amplitude);
                            lastAmplitudeUpdate = now;
                        }
                        
                        // 更新时长
                        if (now - lastDurationUpdate >= 100) {  // 每100ms更新一次
                            long duration = now - recordingStartTime;
                            notifyDuration(duration);
                            lastDurationUpdate = now;
                            
                            // 检查是否达到最大时长
                            if (duration >= MAX_RECORDING_DURATION_MS) {
                                LogManager.logI(TAG, "Max recording duration reached");
                                notifyMaxDurationReached();
                                isRecording = false;
                                break;
                            }
                        }
                    } else if (bytesRead < 0) {
                        LogManager.logE(TAG, "Audio read error: " + bytesRead);
                        break;
                    }
                }
                
                fos.close();
                
                // 转换为WAV格式
                convertPcmToWav(tempPcmFile, outputFile);
                tempPcmFile.delete();
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Recording thread error", e);
                notifyError("录音过程出错: " + e.getMessage());
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        LogManager.logE(TAG, "Error closing file", e);
                    }
                }
            }
        }
    }
    
    /**
     * 计算音频振幅
     */
    private int calculateAmplitude(byte[] audioData, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            if (i + 1 < length) {
                short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
                sum += Math.abs(sample);
            }
        }
        return sum / (length / 2);
    }
    
    /**
     * 将PCM数据转换为WAV格式
     */
    private void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
        FileOutputStream wavOut = null;
        try {
            long pcmSize = pcmFile.length();
            long wavSize = pcmSize + 36;  // PCM数据 + WAV头(44字节 - 8字节)
            
            wavOut = new FileOutputStream(wavFile);
            
            // 写入WAV文件头
            writeWavHeader(wavOut, pcmSize, wavSize);
            
            // 复制PCM数据
            byte[] buffer = new byte[4096];
            java.io.FileInputStream pcmIn = new java.io.FileInputStream(pcmFile);
            int bytesRead;
            while ((bytesRead = pcmIn.read(buffer)) != -1) {
                wavOut.write(buffer, 0, bytesRead);
            }
            pcmIn.close();
            
            LogManager.logI(TAG, "WAV file created: " + wavFile.getAbsolutePath());
            
        } finally {
            if (wavOut != null) {
                wavOut.close();
            }
        }
    }
    
    /**
     * 写入WAV文件头
     */
    private void writeWavHeader(FileOutputStream out, long pcmDataSize, long wavDataSize) throws IOException {
        byte[] header = new byte[44];
        
        // RIFF chunk
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeInt(header, 4, (int) wavDataSize);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        
        // fmt chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeInt(header, 16, 16);  // fmt chunk size
        writeShort(header, 20, (short) 1);  // audio format (1 = PCM)
        writeShort(header, 22, (short) CHANNELS);
        writeInt(header, 24, SAMPLE_RATE);
        writeInt(header, 28, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);  // byte rate
        writeShort(header, 32, (short) (CHANNELS * BITS_PER_SAMPLE / 8));  // block align
        writeShort(header, 34, (short) BITS_PER_SAMPLE);
        
        // data chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeInt(header, 40, (int) pcmDataSize);
        
        out.write(header);
    }
    
    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }
    
    private void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
    
    // 回调通知方法（在主线程执行）
    
    private void notifyAmplitude(int amplitude) {
        if (callback != null) {
            mainHandler.post(() -> callback.onAmplitudeUpdate(amplitude));
        }
    }
    
    private void notifyDuration(long duration) {
        if (callback != null) {
            mainHandler.post(() -> callback.onDurationUpdate(duration));
        }
    }
    
    private void notifyMaxDurationReached() {
        if (callback != null) {
            mainHandler.post(() -> callback.onMaxDurationReached());
        }
    }
    
    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }
    
    /**
     * 获取当前录音时长（毫秒）
     * 如果正在录音，返回实时时长；如果已停止，返回最后一次录音时长
     */
    public long getCurrentDuration() {
        if (isRecording) {
            return System.currentTimeMillis() - recordingStartTime;
        }
        return lastRecordingDuration;
    }
    
    /**
     * 是否正在录音
     */
    public boolean isRecording() {
        return isRecording;
    }
}
