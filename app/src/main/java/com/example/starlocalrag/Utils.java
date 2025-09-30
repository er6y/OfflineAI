package com.example.starlocalrag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Utility class providing common static methods
 */
public class Utils {
    private static final String TAG = "StarLocalRAG_Utils";
    
    // Error message constants
    private static final String ERROR_CONTEXT_NULL = "Unable to show Toast: Context is null";
    private static final String ERROR_SHOW_TOAST_FAILED = "Failed to show Toast";
    private static final String ERROR_START_TOAST_THREAD_FAILED = "Failed to start Toast thread";
    
    /**
     * Read file content
     * @param file File to read
     * @return File content
     * @throws IOException If reading fails
     */
    public static String readFile(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            LogManager.logE(TAG, "Failed to read file: " + e.getMessage(), e);
            throw e;
        }
        return content.toString();
    }

    /**
     * Safely display Toast to prevent crashes when Activity is destroyed
     * @param context Context
     * @param message Message to display
     * @param duration Display duration
     */
    public static void showToastSafely(final Context context, final String message, final int duration) {
        if (context == null) {
            LogManager.logE(TAG, ERROR_CONTEXT_NULL);
            return;
        }
        
        try {
            // Use main thread Handler to ensure display on UI thread
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // Use Application Context to avoid memory leaks and ActivityContext destruction issues
                    Context appContext = context.getApplicationContext();
                    Toast.makeText(appContext, message, duration).show();
                } catch (Exception e) {
                    // Catch all possible exceptions to avoid crashes
                    LogManager.logE(TAG, ERROR_SHOW_TOAST_FAILED + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LogManager.logE(TAG, ERROR_START_TOAST_THREAD_FAILED + ": " + e.getMessage());
        }
    }
}
