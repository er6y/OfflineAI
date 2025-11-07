package com.example.offlineai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Unified Audio Service
 * Handles recording, playback, and compression
 * 
 * @author OfflineAI Team
 */
public class AudioService {
    private static final String TAG = "AudioService";
    
    // ========== Recording Constants ==========
    private static final int SAMPLE_RATE = 16000;  // 16kHz for ASR/Omni
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;  // Mono
    private static final long MAX_RECORDING_DURATION_MS = 60000;  // 60 seconds
    
    // ========== Compression Constants ==========
    private static final int AAC_SAMPLE_RATE = 16000;  // Keep original sample rate
    private static final int AAC_BITRATE = 64000;      // 64kbps for speech
    private static final int AAC_CHANNEL_COUNT = 1;    // Mono
    private static final String MIME_TYPE_AAC = "audio/mp4a-latm";
    
    // ========== Recording State ==========
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private File outputFile;
    private long recordingStartTime;
    private long lastRecordingDuration = 0;
    private Handler mainHandler;
    private RecordingCallback callback;
    
    // ========== Playback State ==========
    private MediaPlayer mediaPlayer;
    private PlaybackCallback playbackCallback;
    private File currentFile;
    private boolean isPrepared = false;
    
    /**
     * Recording callback interface
     */
    public interface RecordingCallback {
        void onAmplitudeUpdate(int amplitude);
        void onDurationUpdate(long durationMs);
        void onMaxDurationReached();
        void onError(String error);
    }
    
    /**
     * Playback callback interface
     */
    public interface PlaybackCallback {
        default void onPrepared() {}
        void onPlaybackStarted();
        void onProgressUpdate(int currentMs, int totalMs);
        void onPlaybackCompleted();
        void onPlaybackError(String error);
    }
    
    public AudioService() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ========================================
    // Recording Methods
    // ========================================
    
    public boolean startRecording(File outputFile, RecordingCallback callback) {
        if (isRecording) {
            LogManager.logW(TAG, "Already recording");
            return false;
        }
        
        this.outputFile = outputFile;
        this.callback = callback;
        
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            notifyError("Cannot get audio buffer size");
            return false;
        }
        
        bufferSize *= 2;
        
        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError("AudioRecord initialization failed");
                return false;
            }
            
            audioRecord.startRecording();
            isRecording = true;
            recordingStartTime = System.currentTimeMillis();
            
            LogManager.logI(TAG, "Recording started: " + outputFile.getAbsolutePath());
            
            recordingThread = new Thread(new RecordingRunnable(bufferSize), "AudioRecorder");
            recordingThread.start();
            
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to start recording", e);
            notifyError("Recording start failed: " + e.getMessage());
            release();
            return false;
        }
    }
    
    public File stopRecording() {
        if (!isRecording) {
            LogManager.logW(TAG, "Not recording");
            return null;
        }
        
        lastRecordingDuration = System.currentTimeMillis() - recordingStartTime;
        isRecording = false;
        
        try {
            if (recordingThread != null) {
                recordingThread.join(1000);
            }
        } catch (InterruptedException e) {
            LogManager.logW(TAG, "Interrupted while waiting for recording thread", e);
        }
        
        release();
        
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
        
        if (outputFile != null && outputFile.exists()) {
            if (outputFile.delete()) {
                LogManager.logI(TAG, "Recording canceled and file deleted");
            }
        }
        
        outputFile = null;
    }
    
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
    
    private class RecordingRunnable implements Runnable {
        private final int bufferSize;
        
        public RecordingRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }
        
        @Override
        public void run() {
            FileOutputStream fos = null;
            
            try {
                File tempPcmFile = new File(outputFile.getParent(), outputFile.getName() + ".tmp");
                fos = new FileOutputStream(tempPcmFile);
                
                byte[] audioData = new byte[bufferSize];
                long lastAmplitudeUpdate = 0;
                long lastDurationUpdate = 0;
                
                while (isRecording) {
                    int bytesRead = audioRecord.read(audioData, 0, bufferSize);
                    
                    if (bytesRead > 0) {
                        fos.write(audioData, 0, bytesRead);
                        
                        long now = System.currentTimeMillis();
                        if (now - lastAmplitudeUpdate >= 50) {
                            int amplitude = calculateAmplitude(audioData, bytesRead);
                            notifyAmplitude(amplitude);
                            lastAmplitudeUpdate = now;
                        }
                        
                        if (now - lastDurationUpdate >= 100) {
                            long duration = now - recordingStartTime;
                            notifyDuration(duration);
                            lastDurationUpdate = now;
                            
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
                
                convertPcmToWav(tempPcmFile, outputFile);
                tempPcmFile.delete();
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Recording thread error", e);
                notifyError("Recording error: " + e.getMessage());
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
    
    private void convertPcmToWav(File pcmFile, File wavFile) throws IOException {
        FileOutputStream wavOut = null;
        try {
            long pcmSize = pcmFile.length();
            long wavSize = pcmSize + 36;
            
            wavOut = new FileOutputStream(wavFile);
            writeWavHeader(wavOut, pcmSize, wavSize);
            
            byte[] buffer = new byte[4096];
            FileInputStream pcmIn = new FileInputStream(pcmFile);
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
    
    private void writeWavHeader(FileOutputStream out, long pcmDataSize, long wavDataSize) throws IOException {
        byte[] header = new byte[44];
        
        // RIFF chunk
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeInt(header, 4, (int) wavDataSize);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        
        // fmt chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeInt(header, 16, 16);
        writeShort(header, 20, (short) 1);
        writeShort(header, 22, (short) CHANNELS);
        writeInt(header, 24, SAMPLE_RATE);
        writeInt(header, 28, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        writeShort(header, 32, (short) (CHANNELS * BITS_PER_SAMPLE / 8));
        writeShort(header, 34, (short) BITS_PER_SAMPLE);
        
        // data chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeInt(header, 40, (int) pcmDataSize);
        
        out.write(header);
    }
    
    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }
    
    private static void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
    
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
    
    public long getCurrentDuration() {
        if (isRecording) {
            return System.currentTimeMillis() - recordingStartTime;
        }
        return lastRecordingDuration;
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    // ========================================
    // Playback Methods
    // ========================================
    
    public boolean prepare(File audioFile, PlaybackCallback callback) {
        if (audioFile == null || !audioFile.exists()) {
            LogManager.logE(TAG, "Audio file not found: " + audioFile);
            if (callback != null) {
                callback.onPlaybackError("Audio file not found");
            }
            return false;
        }
        
        this.currentFile = audioFile;
        this.playbackCallback = callback;
        
        try {
            releasePlayer();
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
            
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                LogManager.logI(TAG, "Audio prepared: " + audioFile.getName() + 
                    ", duration: " + mp.getDuration() + "ms");
                if (playbackCallback != null) {
                    mainHandler.post(() -> playbackCallback.onPrepared());
                }
            });
            
            mediaPlayer.setOnCompletionListener(mp -> {
                LogManager.logI(TAG, "Playback completed");
                if (playbackCallback != null) {
                    mainHandler.post(() -> playbackCallback.onPlaybackCompleted());
                }
            });
            
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                String error = "Playback error: what=" + what + ", extra=" + extra;
                LogManager.logE(TAG, error);
                if (playbackCallback != null) {
                    mainHandler.post(() -> playbackCallback.onPlaybackError("Playback error"));
                }
                return true;
            });
            
            mediaPlayer.prepareAsync();
            return true;
            
        } catch (IOException e) {
            LogManager.logE(TAG, "Failed to prepare audio", e);
            if (callback != null) {
                callback.onPlaybackError("Audio preparation failed: " + e.getMessage());
            }
            return false;
        }
    }
    
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
            
            if (playbackCallback != null) {
                mainHandler.post(() -> playbackCallback.onPlaybackStarted());
            }
            
            startProgressUpdates();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to start playback", e);
            if (playbackCallback != null) {
                playbackCallback.onPlaybackError("Playback start failed");
            }
        }
    }
    
    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            LogManager.logI(TAG, "Playback paused");
        }
    }
    
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
    
    public void releasePlayer() {
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
    
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
    
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
    
    public void seekTo(int positionMs) {
        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.seekTo(positionMs);
            } catch (Exception e) {
                LogManager.logE(TAG, "Error seeking", e);
            }
        }
    }
    
    private void startProgressUpdates() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying() && playbackCallback != null) {
                    int currentMs = getCurrentPosition();
                    int totalMs = getDuration();
                    playbackCallback.onProgressUpdate(currentMs, totalMs);
                    mainHandler.postDelayed(this, 100);
                }
            }
        });
    }
    
    // ========================================
    // Compression Methods
    // ========================================
    
    /**
     * Compress WAV to M4A using MediaCodec + MediaMuxer
     * @param wavPath Input WAV file path
     * @param m4aPath Output M4A file path
     * @return true if success, false if failed
     */
    public static boolean compressWavToM4a(String wavPath, String m4aPath) {
        LogManager.logI(TAG, "[COMPRESS] Starting: " + wavPath + " -> " + m4aPath);
        
        FileInputStream fis = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        
        try {
            File wavFile = new File(wavPath);
            if (!wavFile.exists()) {
                LogManager.logE(TAG, "[COMPRESS] WAV file not found: " + wavPath);
                return false;
            }
            
            // Read WAV file
            fis = new FileInputStream(wavFile);
            byte[] header = new byte[44];
            fis.read(header);
            
            // Parse WAV header
            int sampleRate = readInt(header, 24);
            int channels = readShort(header, 22);
            
            LogManager.logI(TAG, "[COMPRESS] WAV info: sampleRate=" + sampleRate + ", channels=" + channels);
            
            // Read PCM data
            long pcmSize = wavFile.length() - 44;
            byte[] pcmData = new byte[(int) pcmSize];
            fis.read(pcmData);
            fis.close();
            
            // Create AAC encoder - CRITICAL: Use actual WAV parameters, not hardcoded constants!
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            
            encoder = MediaCodec.createEncoderByType(MIME_TYPE_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            
            // Create muxer
            muxer = new MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // Encode PCM to AAC
            boolean encodingDone = false;
            int trackIndex = -1;
            int inputOffset = 0;
            
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            
            while (!encodingDone) {
                // Feed input
                int inputBufferId = encoder.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferId);
                    inputBuffer.clear();
                    
                    int chunkSize = Math.min(inputBuffer.remaining(), pcmData.length - inputOffset);
                    
                    if (chunkSize > 0) {
                        inputBuffer.put(pcmData, inputOffset, chunkSize);
                        encoder.queueInputBuffer(inputBufferId, 0, chunkSize, 0, 0);
                        inputOffset += chunkSize;
                    } else {
                        encoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
                
                // Get output
                int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(outputFormat);
                    muxer.start();
                    LogManager.logI(TAG, "[COMPRESS] Muxer started, track added");
                    
                } else if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                    
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        if (trackIndex >= 0) {
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                        }
                    }
                    
                    encoder.releaseOutputBuffer(outputBufferId, false);
                    
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encodingDone = true;
                        LogManager.logI(TAG, "[COMPRESS] Encoding complete");
                    }
                }
            }
            
            // Cleanup
            encoder.stop();
            encoder.release();
            muxer.stop();
            muxer.release();
            
            File m4aFile = new File(m4aPath);
            LogManager.logI(TAG, "[COMPRESS] Success: " + m4aPath + " (" + m4aFile.length() + " bytes)");
            return true;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "[COMPRESS] Failed", e);
            return false;
            
        } finally {
            try {
                if (fis != null) fis.close();
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Get audio duration from file (supports WAV/M4A)
     * @param audioPath Audio file path
     * @return duration in seconds
     */
    public static float getAudioDuration(String audioPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audioPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return durationMs / 1000.0f;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get audio duration: " + audioPath, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // Ignore
            }
        }
        return 0;
    }
    
    /**
     * Get cache WAV file path for user voice
     * Always returns: {cacheDir}/audio_cache/user_voice.wav
     */
    public static File getCacheWavFile(Context context) {
        File cacheDir = new File(context.getCacheDir(), "audio_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new File(cacheDir, "user_voice.wav");
    }
    
    /**
     * Append PCM data to existing WAV file or create new WAV file
     * All audio must be 16kHz, mono, 16-bit PCM
     * 
     * @param targetWav Target WAV file (will be created if not exists)
     * @param pcmData PCM data to append
     * @param sampleRate Sample rate (must be 16000)
     * @param channels Channel count (must be 1)
     * @param bitsPerSample Bits per sample (must be 16)
     * @return true if successful
     */
    public static boolean appendToWav(File targetWav, byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        // Validate parameters
        if (sampleRate != 16000 || channels != 1 || bitsPerSample != 16) {
            LogManager.logE(TAG, "Invalid audio format: must be 16kHz, mono, 16-bit. Got: " + 
                sampleRate + "Hz, " + channels + "ch, " + bitsPerSample + "bit");
            return false;
        }
        
        try {
            if (!targetWav.exists()) {
                // Create new WAV file with header
                LogManager.logI(TAG, "[APPEND_WAV] Creating new WAV file: " + targetWav.getAbsolutePath());
                return createWavFile(targetWav, pcmData, sampleRate, channels, bitsPerSample);
            } else {
                // Append to existing WAV file
                LogManager.logI(TAG, "[APPEND_WAV] Appending to existing WAV: " + targetWav.getAbsolutePath() + 
                    ", adding " + pcmData.length + " bytes");
                
                // Read existing file
                FileInputStream fis = new FileInputStream(targetWav);
                byte[] existingData = new byte[(int) targetWav.length()];
                fis.read(existingData);
                fis.close();
                
                // Extract existing PCM data (skip 44-byte header)
                int existingPcmSize = existingData.length - 44;
                byte[] existingPcm = new byte[existingPcmSize];
                System.arraycopy(existingData, 44, existingPcm, 0, existingPcmSize);
                
                // Merge PCM data
                byte[] mergedPcm = new byte[existingPcmSize + pcmData.length];
                System.arraycopy(existingPcm, 0, mergedPcm, 0, existingPcmSize);
                System.arraycopy(pcmData, 0, mergedPcm, existingPcmSize, pcmData.length);
                
                LogManager.logI(TAG, "[APPEND_WAV] Merged PCM size: " + mergedPcm.length + 
                    " (existing: " + existingPcmSize + " + new: " + pcmData.length + ")");
                
                // Create new WAV file with merged data
                return createWavFile(targetWav, mergedPcm, sampleRate, channels, bitsPerSample);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[APPEND_WAV] Failed to append WAV", e);
            return false;
        }
    }
    
    /**
     * Create WAV file with PCM data
     * @param wavFile Output WAV file
     * @param pcmData PCM data
     * @param sampleRate Sample rate
     * @param channels Channel count
     * @param bitsPerSample Bits per sample
     * @return true if successful
     */
    private static boolean createWavFile(File wavFile, byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        try (FileOutputStream fos = new FileOutputStream(wavFile)) {
            // Write WAV header
            int dataSize = pcmData.length;
            int fileSize = 36 + dataSize;
            
            byte[] header = new byte[44];
            
            // RIFF header
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            writeInt(header, 4, fileSize);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            
            // fmt chunk
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            writeInt(header, 16, 16);  // fmt chunk size
            writeShort(header, 20, (short) 1);  // audio format (1 = PCM)
            writeShort(header, 22, (short) channels);
            writeInt(header, 24, sampleRate);
            writeInt(header, 28, sampleRate * channels * bitsPerSample / 8);  // byte rate
            writeShort(header, 32, (short) (channels * bitsPerSample / 8));  // block align
            writeShort(header, 34, (short) bitsPerSample);
            
            // data chunk
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            writeInt(header, 40, dataSize);
            
            fos.write(header);
            fos.write(pcmData);
            
            LogManager.logI(TAG, "[CREATE_WAV] Created WAV file: " + wavFile.getAbsolutePath() + 
                ", size: " + (header.length + pcmData.length) + " bytes, duration: " + 
                String.format("%.1fs", (float) dataSize / (sampleRate * channels * bitsPerSample / 8)));
            
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "[CREATE_WAV] Failed to create WAV file", e);
            return false;
        }
    }
    
    // Helper methods for reading WAV header
    private static int readInt(byte[] data, int offset) {
        return ((data[offset + 3] & 0xFF) << 24) |
               ((data[offset + 2] & 0xFF) << 16) |
               ((data[offset + 1] & 0xFF) << 8) |
               (data[offset] & 0xFF);
    }
    
    private static short readShort(byte[] data, int offset) {
        return (short) (((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }
}
