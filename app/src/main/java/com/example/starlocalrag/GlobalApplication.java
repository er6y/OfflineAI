package com.example.starlocalrag;

import android.app.Application;
import android.content.Context;
import android.app.ActivityManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import java.util.Locale;

/**
 * 全局应用类，用于提供应用上下文
 */
public class GlobalApplication extends Application {
    private static final String TAG = "GlobalApplication";
    private static Context appContext;
    
    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        
        // 初始化语言设置
        initLanguageSettings();
        
        // 初始化内存监控
        initMemoryMonitoring();
    }
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(updateBaseContextLocale(base));
    }
    
    /**
     * 初始化语言设置
     */
    private void initLanguageSettings() {
        try {
            String language = ConfigManager.getString(this, ConfigManager.KEY_LANGUAGE, ConfigManager.DEFAULT_LANGUAGE);
            updateAppLocale(language);
        } catch (Exception e) {
            Log.e(TAG, "初始化语言设置失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新应用语言设置
     */
    public static void updateAppLocale(String languageCode) {
        if (appContext == null) return;
        
        try {
            Locale locale;
            if ("ENG".equals(languageCode)) {
                locale = Locale.ENGLISH;
            } else {
                locale = Locale.SIMPLIFIED_CHINESE;
            }
            
            Resources resources = appContext.getResources();
            Configuration config = new Configuration(resources.getConfiguration());
            config.setLocale(locale);
            Context newContext = appContext.createConfigurationContext(config);
            // 更新全局应用上下文的资源配置
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                appContext = newContext;
            } else {
                // 对于旧版本Android，使用createConfigurationContext方法
                appContext = appContext.createConfigurationContext(config);
            }
            
            Log.d(TAG, "语言设置已更新为: " + locale.getDisplayName());
        } catch (Exception e) {
            Log.e(TAG, "更新语言设置失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新Context的语言设置
     */
    private static Context updateBaseContextLocale(Context context) {
        try {
            String language = ConfigManager.getString(context, ConfigManager.KEY_LANGUAGE, ConfigManager.DEFAULT_LANGUAGE);
            
            Locale locale;
            if ("ENG".equals(language)) {
                locale = Locale.ENGLISH;
            } else {
                locale = Locale.SIMPLIFIED_CHINESE;
            }
            
            Configuration config = context.getResources().getConfiguration();
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        } catch (Exception e) {
            Log.e(TAG, "更新Context语言设置失败: " + e.getMessage());
            return context;
        }
    }
    
    /**
     * 初始化内存监控
     */
    private void initMemoryMonitoring() {
        try {
            // 获取内存信息
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            
            // 获取应用的内存类别
            int memoryClass = activityManager.getMemoryClass();
            int largeMemoryClass = activityManager.getLargeMemoryClass();
            
            // 获取JVM内存信息
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            
            Log.i(TAG, "=== 内存配置信息 ===");
            Log.i(TAG, "系统可用内存: " + (memoryInfo.availMem / (1024 * 1024)) + " MB");
            Log.i(TAG, "系统总内存: " + (memoryInfo.totalMem / (1024 * 1024)) + " MB");
            Log.i(TAG, "应用标准内存类别: " + memoryClass + " MB");
            Log.i(TAG, "应用大内存类别: " + largeMemoryClass + " MB");
            Log.i(TAG, "JVM最大内存: " + (maxMemory / (1024 * 1024)) + " MB");
            Log.i(TAG, "JVM当前分配内存: " + (totalMemory / (1024 * 1024)) + " MB");
            Log.i(TAG, "JVM空闲内存: " + (freeMemory / (1024 * 1024)) + " MB");
            Log.i(TAG, "JVM可用内存: " + ((maxMemory - totalMemory + freeMemory) / (1024 * 1024)) + " MB");
            
            // 检查是否启用了largeHeap
            if (largeMemoryClass > memoryClass) {
                Log.i(TAG, "✓ largeHeap已启用，可用内存增加到: " + largeMemoryClass + " MB");
            } else {
                Log.w(TAG, "⚠ largeHeap未生效，当前内存限制: " + memoryClass + " MB");
            }
            
            // 检查是否满足推荐的2GB内存要求
            long jvmMaxMemoryMB = maxMemory / (1024 * 1024);
            if (jvmMaxMemoryMB >= 2048) {
                Log.i(TAG, "✓ 内存配置满足2GB推荐要求，当前JVM最大内存: " + jvmMaxMemoryMB + " MB");
            } else {
                Log.w(TAG, "⚠ 内存配置不足2GB推荐要求，当前JVM最大内存: " + jvmMaxMemoryMB + " MB，建议优化内存配置");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "内存监控初始化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取应用上下文
     * @return 应用上下文
     */
    public static Context getAppContext() {
        return appContext;
    }
    
    /**
     * 获取当前可用内存信息
     * @return 可用内存（MB）
     */
    public static long getAvailableMemoryMB() {
        if (appContext == null) return 0;
        
        try {
            ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            return memoryInfo.availMem / (1024 * 1024);
        } catch (Exception e) {
            Log.e(TAG, "获取可用内存失败: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 获取JVM最大可用内存
     * @return JVM最大可用内存（MB）
     */
    public static long getJVMMaxMemoryMB() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() / (1024 * 1024);
    }
}
