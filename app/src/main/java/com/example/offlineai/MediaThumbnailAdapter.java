package com.example.offlineai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying media thumbnails (images and audio) in RecyclerView
 * Supports delayed compression for images and audio conversion for audio files
 */
public class MediaThumbnailAdapter extends RecyclerView.Adapter<MediaThumbnailAdapter.ViewHolder> {

    private static final int TYPE_IMAGE = 0;
    private static final int TYPE_AUDIO = 1;
    private static final int MAX_AUDIO_COUNT = 3;

    private final List<MediaItem> mediaItems = new ArrayList<>();
    private OnMediaActionListener listener;
    private Context context;
    private String chatFolderPath;
    private MediaPlayer mediaPlayer; // For audio playback

    /**
     * Base class for media item
     */
    public static abstract class MediaItem {
        protected final Uri originalUri;
        protected String processedPath;
        protected final long timestamp;
        protected boolean isProcessing;

        public MediaItem(Uri originalUri) {
            this.originalUri = originalUri;
            this.processedPath = null;
            this.timestamp = System.currentTimeMillis();
            this.isProcessing = false;
        }

        public Uri getOriginalUri() {
            return originalUri;
        }

        public String getProcessedPath() {
            return processedPath;
        }

        public void setProcessedPath(String processedPath) {
            this.processedPath = processedPath;
            this.isProcessing = false;
        }

        public boolean isProcessed() {
            return processedPath != null;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isProcessing() {
            return isProcessing;
        }

        public void setProcessing(boolean processing) {
            isProcessing = processing;
        }

        public abstract int getType();
    }

    /**
     * Image item
     */
    public static class ImageItem extends MediaItem {
        public ImageItem(Uri originalUri) {
            super(originalUri);
        }

        @Override
        public int getType() {
            return TYPE_IMAGE;
        }
    }

    /**
     * Audio item
     */
    public static class AudioItem extends MediaItem {
        private int durationMs;

        public AudioItem(Uri originalUri) {
            super(originalUri);
            this.durationMs = 0;
        }

        @Override
        public int getType() {
            return TYPE_AUDIO;
        }

        public int getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(int durationMs) {
            this.durationMs = durationMs;
        }
    }

    public interface OnMediaActionListener {
        void onMediaClick(MediaItem item, int position);
        void onMediaDelete(MediaItem item, int position);
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public void setOnMediaActionListener(OnMediaActionListener listener) {
        this.listener = listener;
    }

    public void setChatFolderPath(String chatFolderPath) {
        this.chatFolderPath = chatFolderPath;
    }

    /**
     * Add image by URI
     */
    public void addImage(Uri imageUri) {
        ImageItem item = new ImageItem(imageUri);
        mediaItems.add(item);
        notifyItemInserted(mediaItems.size() - 1);
    }

    /**
     * Add audio by URI (will convert to WAV in background)
     */
    public void addAudio(Uri audioUri) {
        // Check audio count limit
        int audioCount = 0;
        for (MediaItem item : mediaItems) {
            if (item instanceof AudioItem) {
                audioCount++;
            }
        }
        if (audioCount >= MAX_AUDIO_COUNT) {
            LogManager.logW("MediaThumbnailAdapter", "Maximum audio count reached: " + MAX_AUDIO_COUNT);
            return;
        }

        AudioItem item = new AudioItem(audioUri);
        // NOTE: Do NOT convert audio in background anymore
        // Audio will be converted synchronously when user clicks send (in prepareAndSaveUserInput)
        // This avoids chat folder creation issues and unnecessary conversions
        item.setProcessing(false);  // Mark as not processing
        mediaItems.add(item);
        int position = mediaItems.size() - 1;
        notifyItemInserted(position);
        LogManager.logI("MediaThumbnailAdapter", "Audio added, will be converted on send: " + audioUri);
    }

    // NOTE: convertAudioInBackground() removed - audio conversion now happens synchronously
    // in prepareAndSaveUserInput() when user clicks send, avoiding chat folder issues

    /**
     * Progress callback for audio decoding
     */
    public interface AudioDecodeCallback {
        void onProgress(int progress); // 0-100
        void onComplete(float duration); // Duration in seconds
        void onError(String error);
    }
    
    /**
     * Decode audio file and append to cache WAV (user_voice.wav)
     * Process: Decode → Resample to 16kHz mono → Append to cache WAV
     * Supports WAV, MP3, M4A input formats
     * 
     * @param context Context
     * @param audioUri Audio file URI
     * @param callback Progress callback (optional)
     * @return true if successful
     */
    public static boolean decodeAndAppendAudio(Context context, Uri audioUri, AudioDecodeCallback callback) {
        try {
            // Get MIME type
            String mimeType = context.getContentResolver().getType(audioUri);
            LogManager.logI("MediaThumbnailAdapter", "[DECODE_APPEND] Processing audio: " + audioUri + ", MIME: " + mimeType);
            
            if (callback != null) {
                callback.onProgress(10);
            }
            
            // Get cache WAV file
            File cacheWav = AudioService.getCacheWavFile(context);
            
            // Decode audio to PCM
            byte[] pcmData;
            int sampleRate;
            int channels;
            
            if (mimeType != null && (mimeType.equals("audio/wav") || mimeType.equals("audio/x-wav"))) {
                // WAV file: read and extract PCM data
                LogManager.logI("MediaThumbnailAdapter", "[DECODE_APPEND] Audio is WAV, reading PCM data");
                WavInfo wavInfo = readWavFile(context, audioUri);
                pcmData = wavInfo.pcmData;
                sampleRate = wavInfo.sampleRate;
                channels = wavInfo.channels;
            } else {
                // MP3/M4A: decode using MediaCodec
                LogManager.logI("MediaThumbnailAdapter", "[DECODE_APPEND] Decoding " + mimeType + " to PCM");
                DecodedAudio decoded = decodeAudioToPcm(context, audioUri, callback);
                pcmData = decoded.pcmData;
                sampleRate = decoded.sampleRate;
                channels = decoded.channels;
            }
            
            if (callback != null) {
                callback.onProgress(70);
            }
            
            // Resample to 16kHz mono if needed
            if (sampleRate != 16000 || channels != 1) {
                LogManager.logI("MediaThumbnailAdapter", "[DECODE_APPEND] Resampling from " + sampleRate + "Hz " + channels + "ch to 16kHz mono");
                pcmData = resampleTo16kMono(pcmData, sampleRate, channels);
                sampleRate = 16000;
                channels = 1;
            }
            
            if (callback != null) {
                callback.onProgress(90);
            }
            
            // Append to cache WAV
            boolean success = AudioService.appendToWav(cacheWav, pcmData, sampleRate, channels, 16);
            
            if (success) {
                float duration = (float) pcmData.length / (sampleRate * channels * 2); // 16-bit = 2 bytes
                LogManager.logI("MediaThumbnailAdapter", "[DECODE_APPEND] Successfully appended audio, duration: " + String.format("%.1fs", duration));
                
                if (callback != null) {
                    callback.onProgress(100);
                    callback.onComplete(duration);
                }
                return true;
            } else {
                throw new IOException("Failed to append to cache WAV");
            }
            
        } catch (Exception e) {
            LogManager.logE("MediaThumbnailAdapter", "[DECODE_APPEND] Failed to decode and append audio", e);
            if (callback != null) {
                callback.onError(e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * WAV file info
     */
    private static class WavInfo {
        byte[] pcmData;
        int sampleRate;
        int channels;
    }
    
    /**
     * Read WAV file and extract PCM data
     */
    private static WavInfo readWavFile(Context context, Uri audioUri) throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(audioUri)) {
            if (inputStream == null) {
                throw new IOException("Failed to open input stream");
            }
            
            // Read WAV header (44 bytes)
            byte[] header = new byte[44];
            if (inputStream.read(header) != 44) {
                throw new IOException("Invalid WAV file: header too short");
            }
            
            // Parse header
            int sampleRate = ((header[27] & 0xFF) << 24) | ((header[26] & 0xFF) << 16) | 
                           ((header[25] & 0xFF) << 8) | (header[24] & 0xFF);
            int channels = ((header[23] & 0xFF) << 8) | (header[22] & 0xFF);
            int dataSize = ((header[43] & 0xFF) << 24) | ((header[42] & 0xFF) << 16) | 
                         ((header[41] & 0xFF) << 8) | (header[40] & 0xFF);
            
            LogManager.logI("MediaThumbnailAdapter", "[READ_WAV] Sample rate: " + sampleRate + "Hz, channels: " + channels + ", data size: " + dataSize);
            
            // Read PCM data
            byte[] pcmData = new byte[dataSize];
            int totalRead = 0;
            while (totalRead < dataSize) {
                int read = inputStream.read(pcmData, totalRead, dataSize - totalRead);
                if (read < 0) break;
                totalRead += read;
            }
            
            WavInfo info = new WavInfo();
            info.pcmData = pcmData;
            info.sampleRate = sampleRate;
            info.channels = channels;
            return info;
        }
    }
    
    /**
     * Decoded audio info
     */
    private static class DecodedAudio {
        byte[] pcmData;
        int sampleRate;
        int channels;
    }
    
    /**
     * Decode audio to PCM using MediaCodec
     */
    private static DecodedAudio decodeAudioToPcm(Context context, Uri audioUri, AudioDecodeCallback callback) throws IOException {
        // Similar to convertWithMediaCodec, but returns PCM data instead of writing to file
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, audioUri, null);
        
        // Find audio track
        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat trackFormat = extractor.getTrackFormat(i);
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = trackFormat;
                break;
            }
        }
        
        if (trackIndex < 0 || format == null) {
            extractor.release();
            throw new IOException("No audio track found");
        }
        
        extractor.selectTrack(trackIndex);
        
        // Create decoder
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();
        
        // Decode audio
        List<byte[]> pcmChunks = new ArrayList<>();
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        
        LogManager.logI("MediaThumbnailAdapter", "[DECODE_PCM] Sample rate: " + sampleRate + "Hz, channels: " + channels);
        
        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int progress = 30;
        
        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                int inputBufferId = decoder.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    if (inputBuffer != null) {
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }
            }
            
            // Get output
            int outputBufferId = decoder.dequeueOutputBuffer(info, 10000);
            if (outputBufferId >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferId);
                if (outputBuffer != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    outputBuffer.get(chunk);
                    pcmChunks.add(chunk);
                    
                    // Update progress
                    if (callback != null && progress < 60) {
                        progress++;
                        callback.onProgress(progress);
                    }
                }
                decoder.releaseOutputBuffer(outputBufferId, false);
                
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
        
        decoder.stop();
        decoder.release();
        extractor.release();
        
        // Merge PCM chunks
        int totalSize = 0;
        for (byte[] chunk : pcmChunks) {
            totalSize += chunk.length;
        }
        
        byte[] pcmData = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : pcmChunks) {
            System.arraycopy(chunk, 0, pcmData, offset, chunk.length);
            offset += chunk.length;
        }
        
        DecodedAudio decoded = new DecodedAudio();
        decoded.pcmData = pcmData;
        decoded.sampleRate = sampleRate;
        decoded.channels = channels;
        return decoded;
    }
    
    /**
     * Resample PCM data to 16kHz mono
     * Simple linear interpolation resampling
     */
    private static byte[] resampleTo16kMono(byte[] pcmData, int srcSampleRate, int srcChannels) {
        // Convert to 16-bit samples
        int srcSampleCount = pcmData.length / (srcChannels * 2);
        short[] srcSamples = new short[srcSampleCount * srcChannels];
        for (int i = 0; i < srcSamples.length; i++) {
            int byteIndex = i * 2;
            srcSamples[i] = (short) ((pcmData[byteIndex + 1] << 8) | (pcmData[byteIndex] & 0xFF));
        }
        
        // Calculate output sample count
        int dstSampleCount = (int) ((long) srcSampleCount * 16000 / srcSampleRate);
        short[] dstSamples = new short[dstSampleCount];
        
        // Resample and convert to mono
        for (int i = 0; i < dstSampleCount; i++) {
            // Calculate source position
            float srcPos = (float) i * srcSampleRate / 16000;
            int srcIndex = (int) srcPos;
            
            if (srcIndex >= srcSampleCount - 1) {
                srcIndex = srcSampleCount - 1;
            }
            
            // Average channels if stereo
            short sample;
            if (srcChannels == 2) {
                short left = srcSamples[srcIndex * 2];
                short right = srcSamples[srcIndex * 2 + 1];
                sample = (short) ((left + right) / 2);
            } else {
                sample = srcSamples[srcIndex];
            }
            
            dstSamples[i] = sample;
        }
        
        // Convert back to bytes
        byte[] result = new byte[dstSampleCount * 2];
        for (int i = 0; i < dstSampleCount; i++) {
            result[i * 2] = (byte) (dstSamples[i] & 0xFF);
            result[i * 2 + 1] = (byte) ((dstSamples[i] >> 8) & 0xFF);
        }
        
        return result;
    }
    
    /**
     * Convert audio file to M4A format (compressed)
     * Process: Decode to cache WAV → Compress to M4A in chat folder
     * Supports WAV, MP3, M4A input formats
     */
    public static String convertAudioToWav(Context context, Uri audioUri, String chatFolderPath) throws IOException {
        // Get MIME type
        String mimeType = context.getContentResolver().getType(audioUri);
        LogManager.logI("MediaThumbnailAdapter", "Converting audio: " + audioUri + ", MIME: " + mimeType);

        // Chat folder must be provided by caller (prepareAndSaveUserInput)
        if (chatFolderPath == null || chatFolderPath.isEmpty()) {
            LogManager.logE("MediaThumbnailAdapter", "Chat folder path is required");
            throw new IOException("Chat folder path is required");
        }
        
        File outputDir = new File(chatFolderPath);
        if (!outputDir.exists()) {
            LogManager.logE("MediaThumbnailAdapter", "Chat folder doesn't exist: " + chatFolderPath);
            return null;
        }

        // 1. Decode to cache WAV
        File cacheWav = AudioService.getCacheWavFile(context);
        
        if (mimeType != null && (mimeType.equals("audio/wav") || mimeType.equals("audio/x-wav"))) {
            // Copy WAV to cache
            LogManager.logI("MediaThumbnailAdapter", "Audio is WAV, copying to cache");
            try (InputStream inputStream = context.getContentResolver().openInputStream(audioUri);
                 FileOutputStream outputStream = new FileOutputStream(cacheWav)) {
                if (inputStream == null) {
                    throw new IOException("Failed to open input stream");
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                LogManager.logI("MediaThumbnailAdapter", "WAV copied to cache: " + cacheWav.getAbsolutePath());
            }
        } else {
            // Decode MP3/M4A to cache WAV using MediaCodec
            LogManager.logI("MediaThumbnailAdapter", "Decoding " + mimeType + " to cache WAV");
            convertWithMediaCodec(context, audioUri, cacheWav);
        }
        
        // 2. Compress cache WAV to M4A in chat folder
        String timestamp = String.valueOf(System.currentTimeMillis());
        File m4aFile = new File(outputDir, "audio_" + timestamp + "_user.m4a");
        
        LogManager.logI("MediaThumbnailAdapter", "Compressing cache WAV to M4A: " + m4aFile.getAbsolutePath());
        boolean success = AudioService.compressWavToM4a(
            cacheWav.getAbsolutePath(), 
            m4aFile.getAbsolutePath()
        );
        
        if (!success) {
            throw new IOException("Audio compression failed");
        }
        
        LogManager.logI("MediaThumbnailAdapter", "Audio converted and compressed to M4A: " + m4aFile.getAbsolutePath());
        
        // 3. Return M4A path (for MD record)
        return m4aFile.getAbsolutePath();
    }

    /**
     * Convert audio using MediaCodec (MP3/M4A → WAV)
     */
    private static String convertWithMediaCodec(Context context, Uri audioUri, File outputFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, audioUri, null);
        } catch (IOException e) {
            LogManager.logE("MediaThumbnailAdapter", "Failed to set data source", e);
            throw e;
        }

        // Find audio track
        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat trackFormat = extractor.getTrackFormat(i);
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = trackFormat;
                break;
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release();
            throw new IOException("No audio track found");
        }

        extractor.selectTrack(trackIndex);

        // Create decoder
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        // Decode audio
        List<byte[]> pcmData = new ArrayList<>();
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int originalChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        
        // Force mono output to avoid MNN multi-channel warning
        int channelCount = 1;
        
        LogManager.logI("MediaThumbnailAdapter", "Decoding: sampleRate=" + sampleRate + ", originalChannels=" + originalChannelCount + ", outputChannels=" + channelCount + " (forced mono)");

        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                int inputBufferId = decoder.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    if (inputBuffer != null) {
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }
            }

            // Get output
            int outputBufferId = decoder.dequeueOutputBuffer(info, 10000);
            if (outputBufferId >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferId);
                if (outputBuffer != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    outputBuffer.get(chunk);
                    
                    // Convert stereo to mono if needed (16-bit PCM)
                    if (originalChannelCount == 2) {
                        // Average left and right channels
                        byte[] monoChunk = new byte[info.size / 2];
                        for (int i = 0; i < monoChunk.length; i += 2) {
                            // Read left and right samples (16-bit little-endian)
                            int srcIdx = i * 2;
                            short left = (short) ((chunk[srcIdx + 1] << 8) | (chunk[srcIdx] & 0xFF));
                            short right = (short) ((chunk[srcIdx + 3] << 8) | (chunk[srcIdx + 2] & 0xFF));
                            // Average and write to mono
                            short mono = (short) ((left + right) / 2);
                            monoChunk[i] = (byte) (mono & 0xFF);
                            monoChunk[i + 1] = (byte) ((mono >> 8) & 0xFF);
                        }
                        pcmData.add(monoChunk);
                    } else {
                        // Already mono, use as-is
                        pcmData.add(chunk);
                    }
                }
                decoder.releaseOutputBuffer(outputBufferId, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                LogManager.logI("MediaThumbnailAdapter", "Output format changed: " + newFormat);
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        // Calculate total PCM size
        int totalSize = 0;
        for (byte[] chunk : pcmData) {
            totalSize += chunk.length;
        }

        LogManager.logI("MediaThumbnailAdapter", "Decoded PCM data: " + totalSize + " bytes");

        // Write WAV file
        writeWavFile(outputFile, pcmData, sampleRate, channelCount, totalSize);
        
        LogManager.logI("MediaThumbnailAdapter", "Audio converted to WAV: " + outputFile.getAbsolutePath());
        return outputFile.getAbsolutePath();
    }

    /**
     * Write WAV file with PCM data
     */
    private static void writeWavFile(File outputFile, List<byte[]> pcmData, int sampleRate, int channelCount, int dataSize) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            // WAV header
            int byteRate = sampleRate * channelCount * 2; // 16-bit = 2 bytes
            int blockAlign = channelCount * 2;

            // RIFF header
            fos.write("RIFF".getBytes());
            fos.write(intToByteArray(36 + dataSize), 0, 4); // File size - 8
            fos.write("WAVE".getBytes());

            // fmt chunk
            fos.write("fmt ".getBytes());
            fos.write(intToByteArray(16), 0, 4); // fmt chunk size
            fos.write(shortToByteArray((short) 1), 0, 2); // Audio format (1 = PCM)
            fos.write(shortToByteArray((short) channelCount), 0, 2); // Number of channels
            fos.write(intToByteArray(sampleRate), 0, 4); // Sample rate
            fos.write(intToByteArray(byteRate), 0, 4); // Byte rate
            fos.write(shortToByteArray((short) blockAlign), 0, 2); // Block align
            fos.write(shortToByteArray((short) 16), 0, 2); // Bits per sample

            // data chunk
            fos.write("data".getBytes());
            fos.write(intToByteArray(dataSize), 0, 4); // Data size

            // Write PCM data
            for (byte[] chunk : pcmData) {
                fos.write(chunk);
            }
        }
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff),
                (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 24) & 0xff)
        };
    }

    private static byte[] shortToByteArray(short value) {
        return new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff)
        };
    }

    /**
     * Get audio duration in milliseconds
     */
    private int getAudioDuration(String audioPath) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(audioPath);
            mp.prepare();
            int duration = mp.getDuration();
            mp.release();
            return duration;
        } catch (IOException e) {
            LogManager.logE("MediaThumbnailAdapter", "Failed to get audio duration", e);
            return 0;
        }
    }

    /**
     * Play audio file
     */
    public void playAudio(String audioPath) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioPath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            LogManager.logI("MediaThumbnailAdapter", "Playing audio: " + audioPath);
        } catch (IOException e) {
            LogManager.logE("MediaThumbnailAdapter", "Failed to play audio", e);
        }
    }

    /**
     * Stop audio playback
     */
    public void stopAudio() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    public void removeMedia(int position) {
        if (position >= 0 && position < mediaItems.size()) {
            mediaItems.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void clearMedia() {
        int size = mediaItems.size();
        mediaItems.clear();
        notifyItemRangeRemoved(0, size);
        stopAudio();
    }

    public List<MediaItem> getMediaItems() {
        return new ArrayList<>(mediaItems);
    }

    /**
     * Get all processed image files (for sending to model)
     * Thread-safe: creates snapshot to avoid ConcurrentModificationException
     */
    public List<String> getProcessedImageFiles() {
        if (context == null) {
            LogManager.logW("MediaThumbnailAdapter", "Context is null");
            return new ArrayList<>();
        }

        List<String> filePaths = new ArrayList<>();
        // Create snapshot to avoid ConcurrentModificationException
        List<MediaItem> snapshot;
        synchronized (mediaItems) {
            snapshot = new ArrayList<>(mediaItems);
        }
        
        for (MediaItem item : snapshot) {
            if (item instanceof ImageItem) {
                try {
                    if (item.isProcessed()) {
                        filePaths.add(item.getProcessedPath());
                    } else {
                        // Process image on demand
                        String tempPath = processImage(context, item.getOriginalUri(), chatFolderPath);
                        if (tempPath != null) {
                            item.setProcessedPath(tempPath);
                            filePaths.add(tempPath);
                        }
                    }
                } catch (Exception e) {
                    LogManager.logE("MediaThumbnailAdapter", "Error processing image", e);
                }
            }
        }
        return filePaths;
    }

    /**
     * Get all processed audio files (for sending to model)
     * Thread-safe: creates snapshot to avoid ConcurrentModificationException
     */
    public List<String> getProcessedAudioFiles() {
        List<String> filePaths = new ArrayList<>();
        // Create snapshot to avoid ConcurrentModificationException
        List<MediaItem> snapshot;
        synchronized (mediaItems) {
            snapshot = new ArrayList<>(mediaItems);
        }
        
        for (MediaItem item : snapshot) {
            if (item instanceof AudioItem && item.isProcessed()) {
                filePaths.add(item.getProcessedPath());
            }
        }
        return filePaths;
    }

    /**
     * Process image (smart resize and save to chat folder)
     */
    public static String processImage(Context context, Uri sourceUri, String chatFolderPath) {
        try {
            // Load bitmap
            Bitmap originalBitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), sourceUri);
                originalBitmap = ImageDecoder.decodeBitmap(source);
            } else {
                @SuppressWarnings("deprecation")
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), sourceUri);
                originalBitmap = bitmap;
            }

            if (originalBitmap == null) {
                return null;
            }

            // No resize - pass original image to MNN
            // MNN will handle resizing based on image_size parameter in config
            Bitmap processedBitmap = originalBitmap;

            // Save to chat folder
            if (chatFolderPath == null || chatFolderPath.isEmpty()) {
                return null;
            }

            File outputDir = new File(chatFolderPath);
            if (!outputDir.exists()) {
                return null;
            }

            String fileName = "img_" + System.currentTimeMillis() + ".jpg";
            File outputFile = new File(outputDir, fileName);

            FileOutputStream fos = new FileOutputStream(outputFile);
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            fos.flush();
            fos.close();

            processedBitmap.recycle();

            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            LogManager.logE("MediaThumbnailAdapter", "Error processing image", e);
            return null;
        }
    }


    public int getMediaCount() {
        return mediaItems.size();
    }

    public int getAudioCount() {
        int count = 0;
        for (MediaItem item : mediaItems) {
            if (item instanceof AudioItem) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int getItemViewType(int position) {
        return mediaItems.get(position).getType();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_thumbnail, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaItem item = mediaItems.get(position);

        if (item instanceof ImageItem) {
            bindImageItem(holder, (ImageItem) item, position);
        } else if (item instanceof AudioItem) {
            bindAudioItem(holder, (AudioItem) item, position);
        }
    }

    private void bindImageItem(ViewHolder holder, ImageItem item, int position) {
        holder.progressBar.setVisibility(View.GONE);
        holder.imageView.setAlpha(1.0f);

        // Load thumbnail
        Bitmap bitmap = null;
        if (item.isProcessed()) {
            bitmap = BitmapFactory.decodeFile(item.getProcessedPath());
        } else if (context != null) {
            bitmap = loadThumbnailFromUri(context, item.getOriginalUri());
        }

        if (bitmap != null) {
            holder.imageView.setImageBitmap(bitmap);
        } else {
            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        holder.imageView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMediaClick(item, position);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMediaDelete(item, position);
            }
        });
    }

    private void bindAudioItem(ViewHolder holder, AudioItem item, int position) {
        // Show audio icon
        holder.imageView.setImageResource(android.R.drawable.ic_btn_speak_now);

        // Show processing state
        if (item.isProcessing()) {
            holder.progressBar.setVisibility(View.VISIBLE);
            holder.imageView.setAlpha(0.5f);
        } else {
            holder.progressBar.setVisibility(View.GONE);
            holder.imageView.setAlpha(1.0f);
        }

        holder.imageView.setOnClickListener(v -> {
            if (item.isProcessed() && listener != null) {
                listener.onMediaClick(item, position);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMediaDelete(item, position);
            }
        });
    }

    private Bitmap loadThumbnailFromUri(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(uri, new Size(512, 512), null);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setTargetSize(512, 512);
                });
            } else {
                @SuppressWarnings("deprecation")
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
                if (bitmap != null && (bitmap.getWidth() > 512 || bitmap.getHeight() > 512)) {
                    float scale = Math.min(512f / bitmap.getWidth(), 512f / bitmap.getHeight());
                    int newWidth = Math.round(bitmap.getWidth() * scale);
                    int newHeight = Math.round(bitmap.getHeight() * scale);
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                    if (scaled != bitmap) {
                        bitmap.recycle();
                    }
                    return scaled;
                }
                return bitmap;
            }
        } catch (IOException e) {
            LogManager.logE("MediaThumbnailAdapter", "Failed to load thumbnail", e);
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return mediaItems.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ImageButton deleteButton;
        ProgressBar progressBar;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewThumbnail);
            deleteButton = itemView.findViewById(R.id.buttonDeleteImage);
            progressBar = itemView.findViewById(R.id.progressBarConverting);
        }
    }
}
