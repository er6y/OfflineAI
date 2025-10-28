package com.example.offlineai;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import com.example.offlineai.LogManager;

/**
 * Uri utility class
 * Used for handling Uri related operations
 */
public class UriUtils {
    private static final String TAG = "UriUtils";
    
    /**
     * Get filename from Uri
     * @param context Context
     * @param uri File Uri
     * @return Filename, returns Uri string representation if failed to get
     */
    public static String getFileName(Context context, Uri uri) {
        if (uri == null) return "";
        
        String result = null;
        
        try {
            if (uri.getScheme().equals("content")) {
                try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex);
                        }
                    }
                }
            }
            
            if (result == null) {
                result = uri.getPath();
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to get filename: " + e.getMessage(), e);
            result = uri.toString();
        }
        
        return result;
    }
    
    /**
     * Convert content:// tree URI to traditional file path
     * Handles SAF (Storage Access Framework) document tree URIs
     * 
     * @param context Context
     * @param treeUri Tree URI from ACTION_OPEN_DOCUMENT_TREE
     * @return Traditional file path (e.g., /storage/emulated/0/Download/OfflineAIData)
     *         Returns original URI string if conversion fails
     */
    public static String getPathFromTreeUri(Context context, Uri treeUri) {
        if (treeUri == null) return "";
        
        try {
            // Check if it's already a file path
            String uriString = treeUri.toString();
            if (uriString.startsWith("/storage/") || uriString.startsWith("/sdcard/")) {
                return uriString;
            }
            
            // Handle content:// URIs
            if ("content".equalsIgnoreCase(treeUri.getScheme())) {
                // For document tree URIs like: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FOfflineAIData
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    String docId = DocumentsContract.getTreeDocumentId(treeUri);
                    
                    // Handle external storage documents
                    if ("com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
                        // docId format: "primary:Download/OfflineAIData" or "1234-5678:path/to/folder"
                        String[] parts = docId.split(":", 2);
                        if (parts.length >= 2) {
                            String storageId = parts[0];
                            String relativePath = parts[1];
                            
                            // Handle primary storage (internal storage)
                            if ("primary".equalsIgnoreCase(storageId)) {
                                // Use Environment.getExternalStorageDirectory() for primary storage
                                String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
                                String fullPath = basePath + "/" + relativePath;
                                LogManager.logD(TAG, "Converted tree URI to path: " + fullPath);
                                return fullPath;
                            } else {
                                // Handle SD card or other external storage
                                // Format: /storage/<storageId>/<relativePath>
                                String fullPath = "/storage/" + storageId + "/" + relativePath;
                                LogManager.logD(TAG, "Converted tree URI to SD card path: " + fullPath);
                                return fullPath;
                            }
                        }
                    }
                }
                
                // Fallback: return the URI string
                LogManager.logW(TAG, "Could not convert tree URI to path, using URI string: " + uriString);
                return uriString;
            }
            
            // For file:// URIs
            if ("file".equalsIgnoreCase(treeUri.getScheme())) {
                return treeUri.getPath();
            }
            
            // Unknown scheme, return as-is
            return uriString;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to convert tree URI to path: " + e.getMessage(), e);
            return treeUri.toString();
        }
    }
}

