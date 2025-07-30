package com.example.starlocalrag;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import com.example.starlocalrag.LogManager;

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
}
