package com.example.offlineai;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.app.ActivityManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

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

        final long startMs = System.currentTimeMillis();
        final String processName = getCurrentProcessName();
        Log.i(TAG, "[STARTUP_TRACE] GlobalApplication.onCreate start, process=" + processName + ", timeMs=" + startMs);

        // NOTE: Chaquopy Python.start() is intentionally NOT called here anymore.
        // It used to block onCreate for 10+ seconds on some devices (userfaultfd /
        // ART GC stalls) and caused "failed to complete startup" ANRs. We now
        // lazy-start Python on first use via PythonBootstrapper.ensureStarted().

        long stepStart = System.currentTimeMillis();
        initLanguageSettings();
        Log.i(TAG, "[STARTUP_TRACE] initLanguageSettings finished, costMs=" + (System.currentTimeMillis() - stepStart));

        stepStart = System.currentTimeMillis();
        initMemoryMonitoring();
        Log.i(TAG, "[STARTUP_TRACE] initMemoryMonitoring finished, costMs=" + (System.currentTimeMillis() - stepStart));

        // CRITICAL: Initialize MNN logger AFTER LogManager is ready
        // This redirects MNN_PRINT/MNN_ERROR to LogManager for file logging
        stepStart = System.currentTimeMillis();
        try {
            com.offlineai.mnn.MnnInference.initMnnLogger();
            Log.i(TAG, "✅ MNN logger initialized - MNN logs will be saved to file");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MNN logger: " + e.getMessage());
        }
        Log.i(TAG, "[STARTUP_TRACE] initMnnLogger finished, costMs=" + (System.currentTimeMillis() - stepStart));

        // CRITICAL: Register custom CPU operators (e.g., CPUGroupNorm for Diffusion)
        // Must be called AFTER MNN library is loaded but BEFORE any model loading
        Log.i(TAG, "🔧 About to register CPUGroupNorm...");
        stepStart = System.currentTimeMillis();
        try {
            com.offlineai.mnn.MnnInference.registerCPUGroupNorm();
            Log.i(TAG, "✅ Custom CPU operators registered (CPUGroupNorm for Diffusion)");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "❌ JNI method not found: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to register custom CPU operators: " + e.getMessage());
        }
        Log.i(TAG, "[STARTUP_TRACE] registerCPUGroupNorm finished, costMs=" + (System.currentTimeMillis() - stepStart));

        // Offload all asset-copy / directory-seed work to a background thread so
        // Application.onCreate returns quickly and avoids startup-timeout ANRs.
        // These operations are idempotent ("copy if not exists") so it is safe
        // even if the UI briefly reaches a state before they finish.
        Thread seedThread = new Thread(() -> {
            long seedStart = System.currentTimeMillis();
            try {
                long t;

                t = System.currentTimeMillis();
                initializeExampleDictionary();
                Log.i(TAG, "[STARTUP_TRACE][ASYNC] initializeExampleDictionary finished, costMs=" + (System.currentTimeMillis() - t));

                t = System.currentTimeMillis();
                initializeTextEditorSeedFiles();
                Log.i(TAG, "[STARTUP_TRACE][ASYNC] initializeTextEditorSeedFiles finished, costMs=" + (System.currentTimeMillis() - t));

                t = System.currentTimeMillis();
                ConfigManager.ensureAgentUserDir(GlobalApplication.this);
                Log.i(TAG, "[STARTUP_TRACE][ASYNC] ensureAgentUserDir finished, costMs=" + (System.currentTimeMillis() - t));

                // Cold-start skills sync. Routed through the single-flight
                // async wrapper so it shares a guard with the foreground-resume
                // hook installed below (no double-copy if both fire close to
                // each other).
                ConfigManager.ensureAssetSkillsInDataRootAsync(GlobalApplication.this, "cold_start");
                Log.i(TAG, "[STARTUP_TRACE][ASYNC] ensureAssetSkillsInDataRootAsync dispatched (cold_start)");
            } catch (Throwable t) {
                Log.e(TAG, "[STARTUP_TRACE][ASYNC] seed thread failed: " + t.getMessage(), t);
            } finally {
                Log.i(TAG, "[STARTUP_TRACE][ASYNC] seed thread done, totalCostMs=" + (System.currentTimeMillis() - seedStart));
            }
        }, "StartupSeedThread");
        seedThread.setDaemon(true);
        seedThread.start();

        // Foreground-resume hook: re-run skills sync every time the app is
        // brought to foreground, so that asset-shipped skill folders that the
        // user (or adb) deleted while the process was still alive get
        // re-seeded automatically. ConfigManager's single-flight guard makes
        // this safe even if the cold-start seed thread is still running.
        registerForegroundSkillsSyncHook();

        long totalCost = System.currentTimeMillis() - startMs;
        Log.i(TAG, "[STARTUP_TRACE] GlobalApplication.onCreate end (sync portion), process=" + processName + ", totalCostMs=" + totalCost);
    }

    /**
     * Counts the number of started (visible) Activities. Transitions:
     *   0 -> 1  : app entered foreground (cold start or returned from bg)
     *   1 -> 0  : app went to background
     */
    private final AtomicInteger startedActivityCount = new AtomicInteger(0);

    /**
     * Register an ActivityLifecycleCallbacks that detects 0->1 transitions
     * of the started-activity counter and triggers a skills sync. We use
     * onActivityStarted/onActivityStopped (not Resumed/Paused) because Started
     * roughly equals "visible to user" without firing on every dialog/popup.
     */
    private void registerForegroundSkillsSyncHook() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {
                int count = startedActivityCount.incrementAndGet();
                if (count == 1) {
                    // 0 -> 1 means app just entered foreground.
                    Log.i(TAG, "[FOREGROUND] app entered foreground, trigger skills sync");
                    ConfigManager.ensureAssetSkillsInDataRootAsync(
                            GlobalApplication.this, "foreground_resume");
                }
            }
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {
                int count = startedActivityCount.decrementAndGet();
                if (count < 0) {
                    // Defensive: if something went wrong with counting, clamp to 0.
                    startedActivityCount.set(0);
                }
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
    
    /**
     * Copy example dictionary from assets to data directory if not exists
     */
    private void initializeExampleDictionary() {
        try {
            String dataRootPath = ConfigManager.getString(this, ConfigManager.KEY_DATA_ROOT_PATH, "");
            if (dataRootPath.isEmpty()) {
                Log.d(TAG, "Data root path not set, skip dictionary initialization");
                return;
            }
            
            java.io.File dictDir = new java.io.File(dataRootPath, "dictionary");
            java.io.File targetFile = new java.io.File(dictDir, "example_terms.json");
            
            if (targetFile.exists()) {
                Log.d(TAG, "Example dictionary already exists: " + targetFile.getAbsolutePath());
                return;
            }
            
            // Create dictionary directory
            if (!dictDir.exists()) {
                if (dictDir.mkdirs()) {
                    Log.i(TAG, "Created dictionary directory: " + dictDir.getAbsolutePath());
                } else {
                    Log.e(TAG, "Failed to create dictionary directory: " + dictDir.getAbsolutePath());
                    return;
                }
            }
            
            // Copy from assets
            try (java.io.InputStream is = getAssets().open("example_terms.json");
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
                Log.i(TAG, "✅ Example dictionary copied to: " + targetFile.getAbsolutePath());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize example dictionary: " + e.getMessage(), e);
        }
    }

    /**
     * Ensure text editor seed files exist in data root.
     * Keep user files untouched if they already exist.
     */
    private void initializeTextEditorSeedFiles() {
        try {
            boolean modelListReady = ConfigManager.ensureAssetFileInDataRoot(this,
                    "ModelDownloadList.txt", "ModelDownloadList.txt");
            Log.i(TAG, "[TEXT_EDITOR_SEED] modelListReady=" + modelListReady);
        } catch (Exception e) {
            Log.e(TAG, "[TEXT_EDITOR_SEED] Failed to initialize text editor seed files: " + e.getMessage(), e);
        }
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

    private static String getCurrentProcessName() {
        if (appContext == null) {
            return null;
        }
        try {
            int pid = android.os.Process.myPid();
            ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                java.util.List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo info : processes) {
                        if (info.pid == pid) {
                            return info.processName;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getCurrentProcessName failed: " + e.getMessage(), e);
        }
        return null;
    }
}

