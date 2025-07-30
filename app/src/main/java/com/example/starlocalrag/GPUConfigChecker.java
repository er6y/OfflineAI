package com.example.starlocalrag;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.opengl.GLES20;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import com.example.starlocalrag.LogManager;

/**
 * GPU配置检查工具
 * 专门用于检查和验证GPU相关配置是否正确
 */
public class GPUConfigChecker {
    private static final String TAG = "GPUConfigChecker";
    
    /**
     * 执行完整的GPU配置检查
     */
    public static String performConfigCheck(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("=== GPU配置检查报告 ===\n");
        
        // 1. 检查AndroidManifest.xml配置
        checkManifestConfig(context, report);
        
        // 2. 检查权限
        checkPermissions(context, report);
        
        // 3. 检查硬件特性
        checkHardwareFeatures(context, report);
        
        // 4. 检查系统兼容性
        checkSystemCompatibility(report);
        
        // 5. 提供修复建议
        provideFixSuggestions(report);
        
        return report.toString();
    }
    
    private static void checkManifestConfig(Context context, StringBuilder report) {
        report.append("\n1. AndroidManifest.xml配置检查:\n");
        
        try {
            // 检查应用是否启用了硬件加速
            android.content.pm.ApplicationInfo appInfo = context.getApplicationInfo();
            boolean hardwareAccelerated = (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
            
            if (hardwareAccelerated) {
                report.append("✓ 应用级硬件加速: 已启用\n");
            } else {
                report.append("✗ 应用级硬件加速: 未启用 (建议在AndroidManifest.xml中添加android:hardwareAccelerated=\"true\")\n");
            }
            
        } catch (Exception e) {
            report.append("✗ 无法检查硬件加速配置: ").append(e.getMessage()).append("\n");
        }
    }
    
    private static void checkPermissions(Context context, StringBuilder report) {
        report.append("\n2. 权限检查:\n");
        
        // 检查网络权限（某些GPU加速可能需要）
        boolean hasNetworkPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) 
            == PackageManager.PERMISSION_GRANTED;
        
        if (hasNetworkPermission) {
            report.append("✓ 网络状态权限: 已授予\n");
        } else {
            report.append("✗ 网络状态权限: 未授予 (可能影响某些GPU加速功能)\n");
        }
    }
    
    private static void checkHardwareFeatures(Context context, StringBuilder report) {
        report.append("\n3. 硬件特性检查:\n");
        
        PackageManager pm = context.getPackageManager();
        
        // 检查OpenGL ES支持
        boolean hasOpenGLES20 = pm.hasSystemFeature("android.hardware.opengles.es_version");
        boolean hasOpenGLESAEP = pm.hasSystemFeature("android.hardware.opengles.aep");
        boolean hasOpenGLESExtPack = pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK);
        
        report.append("OpenGL ES支持:\n");
        report.append("  - OpenGL ES基础: ").append(hasOpenGLES20 ? "✓" : "✗").append("\n");
        report.append("  - OpenGL ES AEP: ").append(hasOpenGLESAEP ? "✓" : "✗").append("\n");
        report.append("  - OpenGL ES扩展包: ").append(hasOpenGLESExtPack ? "✓" : "✗").append("\n");
        
        if (hasOpenGLESAEP) {
            report.append("  └─ 支持OpenGL ES 3.1+计算着色器加速\n");
        }
        
        // 检查Vulkan支持
        boolean hasVulkan = false;
        boolean hasVulkanCompute = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
            hasVulkanCompute = pm.hasSystemFeature("android.hardware.vulkan.compute");
        }
        report.append("Vulkan支持:\n");
        report.append("  - Vulkan硬件级别: ").append(hasVulkan ? "✓" : "✗").append("\n");
        report.append("  - Vulkan计算支持: ").append(hasVulkanCompute ? "✓" : "✗").append("\n");
        
        if (hasVulkan) {
            report.append("  └─ 支持Vulkan API GPU加速\n");
        }
        
        // 检查Mali GPU特定特性
        report.append("\nGPU特定检查:\n");
        try {
            String renderer = getGPURendererInfo();
            if (renderer != null) {
                report.append("  - GPU渲染器: ").append(renderer).append("\n");
                if (renderer.toLowerCase().contains("mali")) {
                    report.append("  └─ 检测到Mali GPU，建议优先使用OpenCL加速\n");
                }
            } else {
                report.append("  - GPU渲染器: 无法检测\n");
            }
        } catch (Exception e) {
            report.append("  - GPU渲染器检测失败: ").append(e.getMessage()).append("\n");
        }
    }
    
    private static void checkSystemCompatibility(StringBuilder report) {
        report.append("\n4. 系统兼容性检查:\n");
        
        // 检查Android版本
        int sdkVersion = Build.VERSION.SDK_INT;
        report.append("Android SDK版本: ").append(sdkVersion);
        
        if (sdkVersion >= 26) {
            report.append(" ✓ (支持NNAPI)\n");
        } else {
            report.append(" ✗ (不支持NNAPI，需要Android 8.0+)\n");
        }
        
        // 检查是否为HarmonyOS
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isHarmonyOS = osName.contains("harmony") || 
                             Build.MANUFACTURER.toLowerCase().contains("huawei") ||
                             Build.BRAND.toLowerCase().contains("huawei") ||
                             Build.BRAND.toLowerCase().contains("honor");
        
        if (isHarmonyOS) {
            report.append("系统类型: HarmonyOS/华为设备\n");
            report.append("注意: HarmonyOS可能对某些GPU加速有特殊限制\n");
        } else {
            report.append("系统类型: 标准Android\n");
        }
    }
    
    private static void provideFixSuggestions(StringBuilder report) {
        report.append("\n5. 修复建议:\n");
        
        report.append("A. AndroidManifest.xml配置:\n");
        report.append("   - 确保<application>标签包含: android:hardwareAccelerated=\"true\"\n");
        report.append("   - 添加权限: <uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />\n");
        report.append("   - 声明硬件特性: <uses-feature android:name=\"android.hardware.vulkan.level\" android:required=\"false\" />\n");
        
        report.append("\nB. 系统设置检查:\n");
        report.append("   - 开发者选项 > 硬件加速渲染 > 强制启用GPU渲染\n");
        report.append("   - 开发者选项 > 硬件加速渲染 > 禁用HW叠加层 (如果有问题可尝试)\n");
        
        report.append("\nC. HarmonyOS特殊处理:\n");
        report.append("   - 检查华为设备管理器中的性能模式设置\n");
        report.append("   - 确认应用在华为应用市场的兼容性\n");
        report.append("   - 尝试在设置中关闭省电模式\n");
        
        report.append("\nD. 应用级优化:\n");
        report.append("   - 更新ONNX Runtime到最新版本\n");
        report.append("   - 检查NDK版本兼容性\n");
        report.append("   - 考虑降级到CPU模式作为备选方案\n");
    }
    
    /**
     * 获取GPU渲染器信息
     */
    private static String getGPURendererInfo() {
        try {
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            if (display != EGL10.EGL_NO_DISPLAY) {
                int[] version = new int[2];
                if (egl.eglInitialize(display, version)) {
                    EGLConfig[] configs = new EGLConfig[1];
                    int[] numConfigs = new int[1];
                    int[] attribs = {
                        EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                        EGL10.EGL_NONE
                    };
                    
                    if (egl.eglChooseConfig(display, attribs, configs, 1, numConfigs) && numConfigs[0] > 0) {
                        EGLContext context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, new int[]{0x3098, 2, EGL10.EGL_NONE});
                        
                        if (context != EGL10.EGL_NO_CONTEXT) {
                            EGLSurface surface = egl.eglCreatePbufferSurface(display, configs[0], new int[]{EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE});
                            
                            if (surface != EGL10.EGL_NO_SURFACE) {
                                if (egl.eglMakeCurrent(display, surface, surface, context)) {
                                    String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
                                    egl.eglDestroySurface(display, surface);
                                    egl.eglDestroyContext(display, context);
                                    egl.eglTerminate(display);
                                    return renderer;
                                }
                                egl.eglDestroySurface(display, surface);
                            }
                            egl.eglDestroyContext(display, context);
                        }
                    }
                    egl.eglTerminate(display);
                }
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "获取GPU渲染器信息失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 快速检查GPU配置是否正确
     */
    public static boolean isGPUConfigValid(Context context) {
        try {
            // 检查基本配置
            android.content.pm.ApplicationInfo appInfo = context.getApplicationInfo();
            boolean hardwareAccelerated = (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
            
            // 检查OpenGL ES支持
            PackageManager pm = context.getPackageManager();
            boolean hasOpenGL = pm.hasSystemFeature("android.hardware.opengles.es_version");
            
            // 检查Android版本
            boolean supportedVersion = Build.VERSION.SDK_INT >= 26;
            
            return hardwareAccelerated && hasOpenGL && supportedVersion;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "GPU配置检查失败: " + e.getMessage(), e);
            return false;
        }
    }
}