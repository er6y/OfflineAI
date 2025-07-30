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
 * 工具类，提供通用的静态方法
 */
public class Utils {
    private static final String TAG = "StarLocalRAG_Utils";
    
    // 错误消息常量
    private static final String ERROR_CONTEXT_NULL = "Unable to show Toast: Context is null";
    private static final String ERROR_SHOW_TOAST_FAILED = "Failed to show Toast";
    private static final String ERROR_START_TOAST_THREAD_FAILED = "Failed to start Toast thread";
    
    /**
     * 读取文件内容
     * @param file 文件
     * @return 文件内容
     * @throws IOException 如果读取失败
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
     * 安全显示Toast，防止在Activity已销毁的情况下显示Toast导致崩溃
     * @param context 上下文
     * @param message 显示消息
     * @param duration 显示时长
     */
    public static void showToastSafely(final Context context, final String message, final int duration) {
        if (context == null) {
            LogManager.logE(TAG, ERROR_CONTEXT_NULL);
            return;
        }
        
        try {
            // 使用主线程Handler确保在UI线程显示
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // 使用Application Context避免内存泄漏和ActivityContext销毁问题
                    Context appContext = context.getApplicationContext();
                    Toast.makeText(appContext, message, duration).show();
                } catch (Exception e) {
                    // 捕获所有可能的异常，避免崩溃
                    LogManager.logE(TAG, ERROR_SHOW_TOAST_FAILED + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LogManager.logE(TAG, ERROR_START_TOAST_THREAD_FAILED + ": " + e.getMessage());
        }
    }
}
