package com.example.starlocalrag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * 日志管理器，用于记录应用日志到文件
 */
public class LogManager {
    private static final String TAG = "StarLocalRAG_LogManager";
    public static final String LOG_FILE_NAME = ".log";
    private static final long MAX_LOG_SIZE = 1024 * 1024; // 1MB
    
    // 日志消息常量
    private static final String LOG_FORCE_LOG_TO_FILE_SET = "Force log to file set to";
    
    // 1MB
    
    // 日志级别常量
    public static final int LOG_LEVEL_VERBOSE = 0;
    public static final int LOG_LEVEL_DEBUG = 1;
    public static final int LOG_LEVEL_INFO = 2;
    public static final int LOG_LEVEL_WARNING = 3;
    public static final int LOG_LEVEL_ERROR = 4;
    public static final int LOG_LEVEL_NONE = 5;
    
    // 默认日志级别 - Debug版本显示所有日志，Release版本只显示INFO及以上级别
    private static int currentLogLevel = BuildConfig.DEBUG ? LOG_LEVEL_VERBOSE : LOG_LEVEL_INFO;
    
    // 是否强制记录所有日志到文件，无论日志级别如何
    private static boolean forceLogToFile = false;
    
    private static LogManager instance;
    private final File logFile;
    private final SimpleDateFormat dateFormat;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private Process logcatProcess;
    private boolean isCapturingLogcat = false;
    
    /**
     * 获取LogManager实例
     * @param context 应用上下文
     * @return LogManager实例
     */
    public static synchronized LogManager getInstance(Context context) {
        if (instance == null) {
            instance = new LogManager(context);
        }
        return instance;
    }
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    private LogManager(Context context) {
        // 创建日志文件
        File filesDir = context.getFilesDir();
        logFile = new File(filesDir, LOG_FILE_NAME);
        
        // 初始化日期格式
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        
        // 初始化线程池和Handler
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 检查日志文件大小，如果超过限制则清空
        checkLogFileSize();
        
        // 开始捕获logcat输出

    }

    
    /**
     * 记录调试日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public void d(String tag, String message) {
        if (currentLogLevel <= LOG_LEVEL_DEBUG || forceLogToFile) {
            Log.d(tag, message);
            writeToLogFile("D", tag, message);
        }
    }
    
    /**
     * 记录信息日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public void i(String tag, String message) {
        if (currentLogLevel <= LOG_LEVEL_INFO || forceLogToFile) {
            Log.i(tag, message);
            writeToLogFile("I", tag, message);
        }
    }
    
    /**
     * 记录警告日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public void w(String tag, String message) {
        if (currentLogLevel <= LOG_LEVEL_WARNING || forceLogToFile) {
            Log.w(tag, message);
            writeToLogFile("W", tag, message);
        }
    }
    
    /**
     * 记录警告日志（带异常）
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常
     */
    public void w(String tag, String message, Throwable throwable) {
        if (currentLogLevel <= LOG_LEVEL_WARNING || forceLogToFile) {
            Log.w(tag, message, throwable);
            writeToLogFile("W", tag, message + "\n" + Log.getStackTraceString(throwable));
        }
    }
    
    /**
     * 记录错误日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public void e(String tag, String message) {
        if (currentLogLevel <= LOG_LEVEL_ERROR || forceLogToFile) {
            Log.e(tag, message);
            writeToLogFile("E", tag, message);
        }
    }
    
    /**
     * 记录错误日志（带异常）
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常
     */
    public void e(String tag, String message, Throwable throwable) {
        if (currentLogLevel <= LOG_LEVEL_ERROR || forceLogToFile) {
            Log.e(tag, message, throwable);
            writeToLogFile("E", tag, message + "\n" + Log.getStackTraceString(throwable));
        }
    }
    
    /**
     * 写入日志到文件
     * @param level 日志级别
     * @param tag 日志标签
     * @param message 日志消息
     */
    private synchronized void writeToLogFile(String level, String tag, String message) {
        try {
            // 检查日志文件大小
            checkLogFileSize();
            
            // 格式化日志消息
            String timestamp = dateFormat.format(new Date());
            String logMessage = String.format("%s %s/%s: %s\n", timestamp, level, tag, message);
            
            // 写入日志文件，使用UTF-8编码
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                writer.write(logMessage);
            }
        } catch (IOException e) {
            LogManager.logE(TAG, "Failed to write to log file: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查日志文件大小，如果超过限制则清空
     */
    private void checkLogFileSize() {
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            try {
                // 清空日志文件，使用UTF-8编码
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8))) {
                    writer.write(""); // 清空文件
                }
                
                // 记录清空信息
                String clearMessage = "Log file exceeded 1MB, cleared";
                LogManager.logI(TAG, clearMessage);
                
                // 写入清空信息作为新日志的第一条
                String timestamp = dateFormat.format(new Date());
                String logMessage = String.format("%s I/%s: %s\n", timestamp, TAG, clearMessage);
                
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                    writer.write(logMessage);
                }
            } catch (IOException e) {
                LogManager.logE(TAG, "Failed to clear log file: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 读取日志文件内容
     * @return 日志文件内容
     */
    public String readLogFile() {
        StringBuilder content = new StringBuilder();
        
        if (logFile.exists()) {
            try {
                // 确保所有写入操作已完成，增加超时时间到2秒
                try {
                    executor.submit(() -> {}).get(2000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 如果等待超时，记录警告但继续尝试读取文件
                    LogManager.logW(TAG, "Timeout waiting for write operations to complete, continuing to read file", e);
                }
                
                // 使用 FileInputStream 而不是 FileReader 来避免潜在的缓存问题
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
            } catch (IOException e) {
                LogManager.logE(TAG, "Failed to read log file: " + e.getMessage(), e);
                return "Failed to read log file: " + e.getMessage();
            } catch (InterruptedException | ExecutionException e) {
                LogManager.logE(TAG, "Failed to wait for write operations to complete: " + e.getMessage(), e);
                // 继续尝试读取文件
            }
        } else {
            return "Log file does not exist";
        }
        
        return content.toString();
    }
    
    /**
     * 清空日志文件
     * @return 是否成功清空
     */
    public boolean clearLogFile() {
        if (logFile.exists()) {
            try {
                // 清空日志文件，使用UTF-8编码
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8))) {
                    writer.write(""); // 清空文件
                }
                
                // 记录清空信息
                String clearMessage = "Log file manually cleared by user";
                LogManager.logI(TAG, clearMessage);
                
                // 写入清空信息作为新日志的第一条
                String timestamp = dateFormat.format(new Date());
                String logMessage = String.format("%s I/%s: %s\n", timestamp, TAG, clearMessage);
                
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                    writer.write(logMessage);
                }
                
                return true;
            } catch (IOException e) {
                LogManager.logE(TAG, "Failed to clear log file: " + e.getMessage(), e);
                return false;
            }
        }
        return true; // 如果文件不存在，视为清空成功
    }
    
    /**
     * 关闭日志管理器
     * 在应用退出时调用
     */
    public void close() {
        executor.shutdown();
    }
    
    /**
     * 设置当前日志级别
     * @param logLevel 日志级别
     */
    public static void setLogLevel(int logLevel) {
        if (logLevel >= LOG_LEVEL_VERBOSE && logLevel <= LOG_LEVEL_NONE) {
            currentLogLevel = logLevel;
            LogManager.logI(TAG, "Log level set to: " + logLevel);
        }
    }
    
    /**
     * 获取当前日志级别
     * @return 当前日志级别
     */
    public static int getLogLevel() {
        return currentLogLevel;
    }
    
    /**
     * 设置是否强制记录所有日志到文件
     * @param force 是否强制记录
     */
    public static void setForceLogToFile(boolean force) {
        forceLogToFile = force;
        LogManager.logI(TAG, LOG_FORCE_LOG_TO_FILE_SET + ": " + force);
    }
    
    /**
     * 获取是否强制记录所有日志到文件
     * @return 是否强制记录
     */
    public static boolean getForceLogToFile() {
        return forceLogToFile;
    }
    
    /**
     * 检查指定的日志级别是否可以记录
     * @param tag 日志标签
     * @param level 日志级别
     * @return 是否可以记录
     */
    public static boolean logIsLoggable(String tag, int level) {
        return level >= currentLogLevel;
    }
    
    /**
     * 检查是否启用调试日志
     * @return 是否启用调试日志
     */
    public static boolean isDebugEnabled() {
        return currentLogLevel <= LOG_LEVEL_DEBUG;
    }
    
    /**
     * 强制记录INFO级别日志，无论当前日志级别如何
     * 在Release模式下用于诊断问题
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logForceInfo(String tag, String message) {
        LogManager.logI(tag, message);
        
        // 获取LogManager实例并记录到文件
        if (instance != null) {
            instance.writeToLogFile("I", tag, message);
        }
    }
    
    /**
     * 强制记录ERROR级别日志，无论当前日志级别如何
     * 在Release模式下用于诊断问题
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logForceError(String tag, String message) {
        LogManager.logE(tag, message);
        
        // 获取LogManager实例并记录到文件
        if (instance != null) {
            instance.writeToLogFile("E", tag, message);
        }
    }
    
    /**
     * 强制记录DEBUG级别日志，无论当前日志级别如何
     * 在Release模式下用于诊断问题
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logForceDebug(String tag, String message) {
        LogManager.logD(tag, message);
        
        // 获取LogManager实例并记录到文件
        if (instance != null) {
            instance.writeToLogFile("D", tag, message);
        }
    }
    
    /**
     * 加载日志配置
     * @param context 上下文
     */
    public static void loadLogConfig(Context context) {
        // 从配置中加载日志级别
        int savedLogLevel = ConfigManager.getInt(context, "log_level", BuildConfig.DEBUG ? LOG_LEVEL_VERBOSE : LOG_LEVEL_INFO);
        setLogLevel(savedLogLevel);
        
        // 从配置中加载是否强制记录日志
        boolean savedForceLogToFile = ConfigManager.getBoolean(context, "force_log_to_file", false);
        setForceLogToFile(savedForceLogToFile);
        
        LogManager.logI(TAG, "已加载日志配置: 级别=" + savedLogLevel + ", 强制记录=" + savedForceLogToFile);
    }
    
    /**
     * 保存日志配置
     * @param context 上下文
     */
    public static void saveLogConfig(Context context) {
        // 保存日志级别
        ConfigManager.setInt(context, "log_level", currentLogLevel);
        
        // 保存是否强制记录日志
        ConfigManager.setBoolean(context, "force_log_to_file", forceLogToFile);
        
        LogManager.logI(TAG, "已保存日志配置: 级别=" + currentLogLevel + ", 强制记录=" + forceLogToFile);
    }
    
    /**
     * 静态方法：记录调试日志
     * 会检查debug_mode配置，只有在调试模式开启时才记录
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logD(String tag, String message) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                // 检查debug_mode配置
                boolean debugMode = ConfigManager.getBoolean(context, ConfigManager.KEY_DEBUG_MODE, false);
                if (debugMode) {
                    LogManager logManager = LogManager.getInstance(context);
                    logManager.d(tag, message);
                } else {
                    // 调试模式关闭时，只输出到logcat，不记录到文件
                    Log.d(tag, message);
                }
            } else {
                // 无法获取Context时，降级到标准Log
                Log.d(tag, message);
            }
        } catch (Exception e) {
            // 异常情况下降级到标准Log
            Log.d(tag, message);
        }
    }
    
    /**
     * 静态方法：记录信息日志
     * 会检查debug_mode配置，只有在调试模式开启时才记录到文件
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logI(String tag, String message) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                // 检查debug_mode配置
                boolean debugMode = ConfigManager.getBoolean(context, ConfigManager.KEY_DEBUG_MODE, false);
                if (debugMode) {
                    LogManager logManager = LogManager.getInstance(context);
                    logManager.i(tag, message);
                } else {
                    // 调试模式关闭时，只输出到logcat，不记录到文件
                    Log.i(tag, message);
                }
            } else {
                // 无法获取Context时，降级到标准Log
                Log.i(tag, message);
            }
        } catch (Exception e) {
            // 异常情况下降级到标准Log
            Log.i(tag, message);
        }
    }
    
    /**
     * 静态方法：记录警告日志
     * 始终记录，不受debug_mode配置影响
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logW(String tag, String message) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                LogManager logManager = LogManager.getInstance(context);
                logManager.w(tag, message);
            } else {
                Log.w(tag, message);
            }
        } catch (Exception e) {
            Log.w(tag, message);
        }
    }
    
    /**
     * 静态方法：记录警告日志（带异常）
     * 始终记录，不受debug_mode配置影响
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常
     */
    public static void logW(String tag, String message, Throwable throwable) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                LogManager logManager = LogManager.getInstance(context);
                logManager.w(tag, message, throwable);
            } else {
                Log.w(tag, message, throwable);
            }
        } catch (Exception e) {
            Log.w(tag, message, throwable);
        }
    }
    
    /**
     * 静态方法：记录错误日志
     * 始终记录，不受debug_mode配置影响
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void logE(String tag, String message) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                LogManager logManager = LogManager.getInstance(context);
                logManager.e(tag, message);
            } else {
                Log.e(tag, message);
            }
        } catch (Exception e) {
            Log.e(tag, message);
        }
    }
    
    /**
     * 静态方法：记录错误日志（带异常）
     * 始终记录，不受debug_mode配置影响
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常
     */
    public static void logE(String tag, String message, Throwable throwable) {
        try {
            Context context = getApplicationContext();
            if (context != null) {
                LogManager logManager = LogManager.getInstance(context);
                logManager.e(tag, message, throwable);
            } else {
                Log.e(tag, message, throwable);
            }
        } catch (Exception e) {
            Log.e(tag, message, throwable);
        }
    }

    /**
     * 专门为print方法写入原始内容到日志文件，不添加格式化
     * @param message 原始消息内容
     */
    private synchronized void writeRawToLogFile(String message) {
        try {
            // 检查日志文件大小
            checkLogFileSize();
            
            // 直接写入原始消息，不添加时间戳、级别和标签格式化
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(message);
            }
        } catch (IOException e) {
            LogManager.logE(TAG, "写入原始内容到日志文件失败: " + e.getMessage(), e);
        }
    }



    /**
     * 静态方法：打印消息到控制台并写入日志文件
     * 会检查debug_mode配置，只有在调试模式开启时才记录到文件
     * @param message 消息内容
     */
    public static void print(String message) {
        try {
            // 直接打印到控制台
            System.out.print(message);
            
            // 检查是否需要写入日志文件
            Context context = getApplicationContext();
            if (context != null) {
                // 检查debug_mode配置
                boolean debugMode = ConfigManager.getBoolean(context, ConfigManager.KEY_DEBUG_MODE, false);
                if (debugMode) {
                    LogManager logManager = LogManager.getInstance(context);
                    if (logManager != null) {
                        logManager.writeRawToLogFile(message);
                    }
                }
                // 调试模式关闭时，只输出到控制台，不记录到文件
            }
        } catch (Exception e) {
            // 异常情况下至少保证控制台输出
            System.out.print(message);
            Log.e(TAG, "LogManager.print异常: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取Application Context的辅助方法
     * 通过反射获取当前应用的Context
     * @return Application Context，如果获取失败返回null
     */
    private static Context getApplicationContext() {
        try {
            // 通过反射获取ActivityThread的currentApplication方法
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentApplicationMethod = activityThreadClass.getMethod("currentApplication");
            android.app.Application application = (android.app.Application) currentApplicationMethod.invoke(null);
            return application;
        } catch (Exception e) {
            // 如果反射失败，返回null，调用方会降级处理
            return null;
        }
    }
}
