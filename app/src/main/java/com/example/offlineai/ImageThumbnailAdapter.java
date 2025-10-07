package com.example.offlineai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying image thumbnails in RecyclerView
 * Supports delayed compression: stores original URI and compresses on demand
 */
public class ImageThumbnailAdapter extends RecyclerView.Adapter<ImageThumbnailAdapter.ViewHolder> {

    private final List<ImageItem> imageItems = new ArrayList<>();
    private OnImageActionListener listener;
    private Context context;

    /**
     * Data class for image item
     * Stores both original URI and compressed path for delayed compression
     */
    public static class ImageItem {
        private final Uri originalUri;           // Original image URI from user selection
        private String compressedPath;           // Path to compressed image (null if not yet compressed)
        private final long timestamp;            // Timestamp for cache management
        
        public ImageItem(Uri originalUri) {
            this.originalUri = originalUri;
            this.compressedPath = null;
            this.timestamp = System.currentTimeMillis();
        }
        
        public Uri getOriginalUri() {
            return originalUri;
        }
        
        public String getCompressedPath() {
            return compressedPath;
        }
        
        public void setCompressedPath(String compressedPath) {
            this.compressedPath = compressedPath;
        }
        
        public boolean isCompressed() {
            return compressedPath != null;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        /**
         * Get display path for thumbnail (use compressed if available, otherwise original URI)
         */
        public String getDisplayPath() {
            return compressedPath != null ? compressedPath : originalUri.toString();
        }
    }

    public interface OnImageActionListener {
        void onImageClick(String imagePath, int position);
        void onImageDelete(String imagePath, int position);
    }
    
    /**
     * Smart resize for VL models (Qwen2.5-VL, Gemma 3)
     * @param bitmap Original bitmap
     * @param maxSize Preset from config (112/280/392/504/672/896/1008) or 0 for MAX mode
     * @return Resized bitmap (aligned to 28 for VL models)
     */
    private static android.graphics.Bitmap smartResize(android.graphics.Bitmap bitmap, int maxSize, android.content.Context context) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // MAX mode: bypass resize
        if (maxSize == 0) {
            LogManager.logI("ImageThumbnailAdapter", "[MAX] No resize: " + width + "x" + height);
            return bitmap;
        }
        
        // Don't upscale small images
        if (width <= maxSize && height <= maxSize) {
            LogManager.logI("ImageThumbnailAdapter", "[KEEP] No resize: " + width + "x" + height + " <= " + maxSize);
            return bitmap;
        }
        
        // Simple resize (llama.cpp will handle alignment internally)
        float scale = Math.min((float)maxSize / width, (float)maxSize / height);
        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);
        
        android.graphics.Bitmap resized = android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        
        LogManager.logI("ImageThumbnailAdapter", 
            "[RESIZE] " + width + "x" + height + " → " + newWidth + "x" + newHeight + 
            " (llama.cpp will align to 28)");
        
        return resized;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public void setOnImageActionListener(OnImageActionListener listener) {
        this.listener = listener;
    }

    /**
     * Add image by URI (will compress later based on model requirements)
     */
    public void addImage(Uri imageUri) {
        ImageItem item = new ImageItem(imageUri);
        imageItems.add(item);
        notifyItemInserted(imageItems.size() - 1);
    }

    public void removeImage(int position) {
        if (position >= 0 && position < imageItems.size()) {
            imageItems.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void clearImages() {
        int size = imageItems.size();
        imageItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    /**
     * Get all image items (for delayed compression)
     */
    public List<ImageItem> getImageItems() {
        return new ArrayList<>(imageItems);
    }

    /**
     * Get original image files (convert content:// URI to file paths)
     * Copies URI bytes to temp files without any processing
     * JNI/llama.cpp will handle all compression based on model requirements
     * @return List of temporary file paths
     */
    public List<String> getOriginalImageFiles() {
        if (context == null) {
            LogManager.logW("ImageThumbnailAdapter", "Context is null, cannot convert URIs to files");
            return new ArrayList<>();
        }
        
        List<String> filePaths = new ArrayList<>();
        for (ImageItem item : imageItems) {
            try {
                // Convert content:// URI to file path by copying bytes
                String tempPath = copyUriToTempFile(context, item.getOriginalUri());
                if (tempPath != null) {
                    filePaths.add(tempPath);
                    LogManager.logI("ImageThumbnailAdapter", "Converted URI to file: " + tempPath);
                } else {
                    LogManager.logW("ImageThumbnailAdapter", "Failed to convert URI to file");
                }
            } catch (Exception e) {
                LogManager.logE("ImageThumbnailAdapter", "Error converting URI to file", e);
            }
        }
        return filePaths;
    }
    
    /**
     * Process and save image to temporary file
     * Implements smart resize similar to Qwen2.5-VL's min_pixels/max_pixels logic
     * @param context Application context
     * @param sourceUri Source image URI
     * @return Path to temp file, or null if failed
     */
    private static String copyUriToTempFile(Context context, Uri sourceUri) {
        try {
            // Load bitmap from URI
            android.graphics.Bitmap originalBitmap;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(
                    context.getContentResolver(), sourceUri);
                originalBitmap = android.graphics.ImageDecoder.decodeBitmap(source);
            } else {
                @SuppressWarnings("deprecation")
                android.graphics.Bitmap bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                    context.getContentResolver(), sourceUri);
                originalBitmap = bitmap;
            }
            
            if (originalBitmap == null) {
                LogManager.logE("ImageThumbnailAdapter", "Failed to load bitmap from URI");
                return null;
            }
            
            int origWidth = originalBitmap.getWidth();
            int origHeight = originalBitmap.getHeight();
            int origPixels = origWidth * origHeight;
            LogManager.logI("ImageThumbnailAdapter", "Original image: " + origWidth + "x" + origHeight + " (" + origPixels + " pixels)");
            
            // Smart resize based on config (simulates Qwen2.5-VL's min_pixels/max_pixels)
            int maxSize = ConfigManager.getImagePreprocessSize(context);
            android.graphics.Bitmap processedBitmap = smartResize(originalBitmap, maxSize, context);
            
            if (processedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }
            
            // Create cache directory
            java.io.File cacheDir = new java.io.File(context.getCacheDir(), "multimodal");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            // Save as JPEG with quality 95
            String fileName = "img_" + System.currentTimeMillis() + ".jpg";
            java.io.File outputFile = new java.io.File(cacheDir, fileName);
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile);
            processedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
            fos.flush();
            fos.close();
            
            processedBitmap.recycle();
            
            LogManager.logI("ImageThumbnailAdapter", "Image saved: " + outputFile.getAbsolutePath() + " (" + outputFile.length() + " bytes)");
            return outputFile.getAbsolutePath();
            
        } catch (Exception e) {
            LogManager.logE("ImageThumbnailAdapter", "Error processing image: " + e.getMessage());
            return null;
        }
    }

    public int getImageCount() {
        return imageItems.size();
    }
    
    /**
     * Clean up old cached image files
     * @param context Application context
     */
    public static void cleanupCache(Context context) {
        java.io.File cacheDir = new java.io.File(context.getCacheDir(), "multimodal");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            java.io.File[] files = cacheDir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isFile()) {
                        file.delete();
                    }
                }
                LogManager.logI("ImageThumbnailAdapter", "Cleaned up multimodal cache directory");
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_image_thumbnail, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ImageItem item = imageItems.get(position);
        
        // Load thumbnail image (use compressed if available, otherwise load from URI)
        Bitmap bitmap = null;
        if (item.isCompressed()) {
            bitmap = BitmapFactory.decodeFile(item.getCompressedPath());
        } else if (context != null) {
            // Load thumbnail from original URI (low resolution for preview)
            bitmap = loadThumbnailFromUri(context, item.getOriginalUri());
        }
        
        if (bitmap != null) {
            holder.imageView.setImageBitmap(bitmap);
        } else {
            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        // Set click listeners
        String displayPath = item.getDisplayPath();
        holder.imageView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onImageClick(displayPath, position);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onImageDelete(displayPath, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return imageItems.size();
    }

    /**
     * Load thumbnail from URI using modern API (non-deprecated)
     * @param context Application context
     * @param uri Image URI
     * @return Thumbnail bitmap or null if failed
     */
    private Bitmap loadThumbnailFromUri(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Use loadThumbnail with Size
                return context.getContentResolver().loadThumbnail(uri, new Size(512, 512), null);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9 (API 28): Use ImageDecoder
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    // Set target size for thumbnail
                    decoder.setTargetSize(512, 512);
                });
            } else {
                // Android 7/8 (API 24-27): Use getBitmap (deprecated but necessary for old versions)
                @SuppressWarnings("deprecation")
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
                // Scale down to thumbnail size
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
            LogManager.logE("ImageThumbnailAdapter", "Failed to load thumbnail from URI: " + e.getMessage());
            return null;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ImageButton deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewThumbnail);
            deleteButton = itemView.findViewById(R.id.buttonDeleteImage);
        }
    }
}

