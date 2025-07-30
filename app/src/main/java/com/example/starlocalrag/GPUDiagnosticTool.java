package com.example.starlocalrag;

import android.content.Context;
import android.content.pm.PackageManager;
import android.opengl.GLES20;
import android.os.Build;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import android.opengl.EGLExt;

/**
 * GPU诊断工具类
 * 用于检测设备的GPU支持情况和提供诊断信息
 */
public class GPUDiagnosticTool {
    private static final String TAG = "StarLocalRAG_GPUDiag";
    
    /**
     * 执行完整的GPU诊断
     * @param context 应用上下文
     * @return 诊断报告
     */
    public static String performFullDiagnosis(Context context) {
        StringBuilder report = new StringBuilder();
        
        report.append("=== GPU诊断报告 ===\n");
        report.append(getSystemInfo());
        report.append(getHardwareFeatures(context));
        report.append(getOpenGLInfo());
        report.append(getHarmonyOSSpecificInfo());
        report.append(getRecommendations());
        
        return report.toString();
    }
    
    /**
     * 获取系统基本信息
     */
    private static String getSystemInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("\n--- 系统信息 ---\n");
        info.append(String.format("设备制造商: %s\n", Build.MANUFACTURER));
        info.append(String.format("设备型号: %s\n", Build.MODEL));
        info.append(String.format("Android版本: %s (API %d)\n", Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        info.append(String.format("构建版本: %s\n", Build.DISPLAY));
        info.append(String.format("硬件平台: %s\n", Build.HARDWARE));
        info.append(String.format("处理器架构: %s\n", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "未知"));
        
        // 检测是否为HarmonyOS
        boolean isHarmonyOS = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                             Build.MANUFACTURER.toLowerCase().contains("honor") ||
                             Build.DISPLAY.toLowerCase().contains("harmony");
        info.append(String.format("HarmonyOS检测: %s\n", isHarmonyOS ? "是" : "否"));
        
        return info.toString();
    }
    
    /**
     * 获取硬件特性支持情况
     */
    private static String getHardwareFeatures(Context context) {
        StringBuilder info = new StringBuilder();
        PackageManager pm = context.getPackageManager();
        
        info.append("\n--- 硬件特性支持 ---\n");
        
        // 检查OpenGL ES版本支持
        String[] glVersions = {
            "android.hardware.opengles.aep",
            "android.hardware.vulkan.level",
            "android.hardware.vulkan.version",
            "android.hardware.vulkan.compute",
            PackageManager.FEATURE_OPENGLES_EXTENSION_PACK
        };
        
        String[] glNames = {
            "OpenGL ES AEP (高级扩展包)",
            "Vulkan API Level",
            "Vulkan API Version",
            "Vulkan Compute Support",
            "OpenGL ES Extension Pack"
        };
        
        for (int i = 0; i < glVersions.length; i++) {
            boolean supported = pm.hasSystemFeature(glVersions[i]);
            info.append(String.format("%s: %s\n", glNames[i], supported ? "支持" : "不支持"));
            
            // 为Vulkan提供详细信息
            if (glVersions[i].contains("vulkan.level") && supported) {
                try {
                    // 尝试获取Vulkan级别详细信息
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        info.append("  └─ Vulkan硬件级别检测通过，适合GPU计算加速\n");
                    }
                } catch (Exception e) {
                    info.append("  └─ Vulkan详细信息获取失败\n");
                }
            }
        }
        
        // 检查Mali GPU特定特性（针对华为MIS-AL00设备）
        info.append("\n--- Mali GPU特性检测 ---\n");
        boolean isMaliGPU = false;
        try {
            // 通过OpenGL渲染器字符串检测Mali GPU
            String renderer = getGPURenderer();
            if (renderer != null && renderer.toLowerCase().contains("mali")) {
                isMaliGPU = true;
                info.append(String.format("Mali GPU检测: 是 (%s)\n", renderer));
                info.append("Mali GPU优化建议:\n");
                info.append("  - 优先使用OpenCL加速（Mali GPU对OpenCL支持较好）\n");
                info.append("  - Vulkan支持取决于具体Mali版本和驱动\n");
                info.append("  - OpenGL ES计算着色器是通用备选方案\n");
            } else {
                info.append("Mali GPU检测: 否\n");
            }
        } catch (Exception e) {
            info.append("Mali GPU检测: 失败 - " + e.getMessage() + "\n");
        }
        
        return info.toString();
    }
    
    /**
     * 获取GPU渲染器信息（用于Mali GPU检测）
     */
    private static String getGPURenderer() {
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
            // 忽略异常，返回null
        }
        return null;
    }
    
    /**
     * 获取OpenGL信息
     */
    private static String getOpenGLInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("\n--- OpenGL信息 ---\n");
        
        try {
            // 创建EGL上下文来获取OpenGL信息
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            
            if (display != EGL10.EGL_NO_DISPLAY) {
                int[] version = new int[2];
                if (egl.eglInitialize(display, version)) {
                    info.append(String.format("EGL版本: %d.%d\n", version[0], version[1]));
                    
                    // 获取OpenGL ES版本
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
                                    String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
                                    String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
                                    String version_gl = GLES20.glGetString(GLES20.GL_VERSION);
                                    
                                    info.append(String.format("GPU厂商: %s\n", vendor != null ? vendor : "未知"));
                                    info.append(String.format("GPU渲染器: %s\n", renderer != null ? renderer : "未知"));
                                    info.append(String.format("OpenGL ES版本: %s\n", version_gl != null ? version_gl : "未知"));
                                }
                                egl.eglDestroySurface(display, surface);
                            }
                            egl.eglDestroyContext(display, context);
                        }
                    }
                    egl.eglTerminate(display);
                } else {
                    info.append("无法初始化EGL\n");
                }
            } else {
                info.append("无法获取EGL显示\n");
            }
        } catch (Exception e) {
            info.append(String.format("获取OpenGL信息失败: %s\n", e.getMessage()));
        }
        
        return info.toString();
    }
    
    /**
     * 获取HarmonyOS特定信息
     */
    private static String getHarmonyOSSpecificInfo() {
        StringBuilder info = new StringBuilder();
        
        boolean isHarmonyOS = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                             Build.MANUFACTURER.toLowerCase().contains("honor") ||
                             Build.DISPLAY.toLowerCase().contains("harmony");
        
        if (isHarmonyOS) {
            info.append("\n--- HarmonyOS特定信息 ---\n");
            info.append("检测到HarmonyOS系统\n");
            
            // 检测具体设备型号
            String deviceModel = Build.MODEL;
            if (deviceModel.contains("MIS-AL00")) {
                info.append("设备型号: 华为MIS-AL00 (已知Mali-G610 GPU)\n");
                info.append("针对此设备的GPU加速优化建议:\n");
                info.append("  1. Mali-G610 GPU对OpenCL支持最佳\n");
                info.append("  2. Vulkan API支持取决于HarmonyOS版本\n");
                info.append("  3. OpenGL ES 3.2计算着色器可作为备选\n");
                info.append("  4. NNAPI可能受到系统限制\n");
            } else {
                info.append(String.format("设备型号: %s\n", deviceModel));
            }
            
            info.append("\nHarmonyOS GPU加速通用注意事项:\n");
            info.append("1. 系统设置优化:\n");
            info.append("   - 开发者选项 > 强制进行GPU渲染\n");
            info.append("   - 性能模式 > 高性能模式\n");
            info.append("   - 关闭省电模式和超级省电\n");
            info.append("2. 应用权限检查:\n");
            info.append("   - 确保应用在华为设备管理器中未被限制\n");
            info.append("   - 检查后台应用刷新权限\n");
            info.append("3. 系统兼容性:\n");
            info.append("   - 建议HarmonyOS 3.0+以获得更好的GPU支持\n");
            info.append("   - 定期更新系统和GPU驱动\n");
            info.append("4. 加速方案优先级（针对华为设备）:\n");
            info.append("   - 首选: OpenCL (兼容性最佳)\n");
            info.append("   - 次选: Vulkan (需要系统支持)\n");
            info.append("   - 备选: OpenGL ES计算着色器\n");
            info.append("   - 最后: NNAPI (可能受限)\n");
        }
        
        return info.toString();
    }
    
    /**
     * 获取推荐建议
     */
    private static String getRecommendations() {
        StringBuilder info = new StringBuilder();
        
        info.append("\n--- 推荐建议 ---\n");
        info.append("1. 确保在开发者选项中启用'强制进行GPU渲染'\n");
        info.append("2. 检查应用是否在省电模式下运行，省电模式可能限制GPU使用\n");
        info.append("3. 尝试重启设备以清除可能的GPU驱动问题\n");
        info.append("4. 如果问题持续存在，考虑使用CPU模式作为备选方案\n");
        info.append("5. 对于HarmonyOS设备，建议检查华为应用市场的系统更新\n");
        
        return info.toString();
    }
    
    /**
     * 简化的GPU支持检查
     * @param context 应用上下文
     * @return 是否可能支持GPU加速
     */
    public static boolean isGPUAccelerationLikelySupported(Context context) {
        // 检查Android版本（NNAPI需要API 27+）
        if (Build.VERSION.SDK_INT < 27) {
            LogManager.logW(TAG, "Android版本过低，可能不支持NNAPI加速");
            return false;
        }
        
        // 检查是否有基本的OpenGL ES支持
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK)) {
            LogManager.logW(TAG, "设备不支持OpenGL ES扩展包");
        }
        
        return true;
    }
}