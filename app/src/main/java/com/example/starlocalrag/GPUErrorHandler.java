package com.example.starlocalrag;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.lang.reflect.Method;

/**
 * GPU错误处理工具类
 * 用于处理各种GPU加速相关的错误和警告
 */
public class GPUErrorHandler {
    private static final String TAG = "StarLocalRAG_GPU";
    
    /**
     * 初始化GPU错误处理
     * @param context 应用上下文
     * @param window 窗口对象
     */
    public static void init(Context context, Window window) {
        try {
            // 创建缓存目录
            createCacheDirectories(context);
            
            // 设置窗口格式
            configureWindowFormat(window);
            
            // 处理华为特定问题
            handleHuaweiSpecificIssues();
            
            LogManager.logD(TAG, "GPU error handler initialization completed");
        } catch (Exception e) {
            LogManager.logE(TAG, "GPU error handler initialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建缓存目录
     * @param context 应用上下文
     */
    private static void createCacheDirectories(Context context) {
        try {
            // 创建OpenGL着色器缓存目录
            File openglCacheDir = new File(context.getCacheDir(), "opengl_cache");
            if (!openglCacheDir.exists()) {
                openglCacheDir.mkdirs();
            }
            
            // 创建Skia着色器缓存目录
            File skiaCacheDir = new File(context.getCacheDir(), "skia_cache");
            if (!skiaCacheDir.exists()) {
                skiaCacheDir.mkdirs();
            }
            
            // 设置系统属性（需要root权限，通常无法设置，但尝试一下）
            try {
                System.setProperty("com.android.opengl.shaders_cache", openglCacheDir.getAbsolutePath());
                System.setProperty("com.android.skia.shaders_cache", skiaCacheDir.getAbsolutePath());
            } catch (Exception e) {
                LogManager.logW(TAG, "Unable to set shader cache system properties: " + e.getMessage());
            }
            
            LogManager.logD(TAG, "Cache directories created successfully");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to create cache directories: " + e.getMessage(), e);
        }
    }
    
    /**
     * 配置窗口格式
     * @param window 窗口对象
     */
    private static void configureWindowFormat(Window window) {
        try {
            if (window != null) {
                // 设置窗口格式为RGBA_8888，提高渲染质量
                window.setFormat(android.graphics.PixelFormat.RGBA_8888);
                
                // 根据配置决定是否启用硬件加速
                // Note: use_gpu is now stored as string ("CPU", "Vulkan", etc.) instead of boolean
                String backendPreference = ConfigManager.getString(window.getContext(), ConfigManager.KEY_USE_GPU, "CPU");
                boolean useGpu = !"CPU".equals(backendPreference);
                
                if (useGpu) {
                    // 启用硬件加速
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                    LogManager.logD(TAG, "Hardware acceleration enabled");
                } else {
                    // 禁用硬件加速
                    window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                    LogManager.logD(TAG, "Hardware acceleration disabled");
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to configure window format: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理华为特定问题
     */
    private static void handleHuaweiSpecificIssues() {
        try {
            // 检测是否是华为设备
            boolean isHuawei = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                               Build.BRAND.toLowerCase().contains("huawei") ||
                               Build.BRAND.toLowerCase().contains("honor") ||
                               Build.MODEL.toLowerCase().contains("huawei");
            
            if (isHuawei) {
                LogManager.logD(TAG, "Huawei device detected, applying lightweight fixes");
                
                // 只设置系统属性，避免反射调用可能导致的启动卡顿
                try {
                    System.setProperty("hw_editor_disable", "true");
                    System.setProperty("hw_gpu_check_disable", "true");
                    LogManager.logD(TAG, "Huawei device optimization properties set");
                } catch (Exception e) {
                    LogManager.logW(TAG, "Unable to set Huawei optimization properties: " + e.getMessage());
                }
                
                // 移除可能导致启动卡顿的反射调用
                // 注释掉原有的HwEditorHelperImpl禁用代码，因为可能导致启动无响应
                LogManager.logD(TAG, "Huawei device startup optimization completed");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to handle Huawei-specific issues: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否支持GPU加速
     * @param context 应用上下文
     * @return 是否支持GPU加速
     */
    public static boolean isGPUAccelerationSupported(Context context) {
        try {
            // 检查设备是否支持OpenGL ES 2.0或更高版本
            android.app.ActivityManager activityManager = 
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (activityManager != null) {
                android.content.pm.ConfigurationInfo configInfo = activityManager.getDeviceConfigurationInfo();
                return configInfo.reqGlEsVersion >= 0x20000;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to check GPU acceleration support: " + e.getMessage(), e);
        }
        
        return false;
    }
}
