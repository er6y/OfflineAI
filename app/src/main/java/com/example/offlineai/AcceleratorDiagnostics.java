package com.example.offlineai;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.opengl.GLES20;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Unified Hardware Accelerator Diagnostics Tool
 * Consolidates GPU/NPU capability detection and configuration checks
 * 
 * Integrates functionality from:
 * - GPUConfigChecker: Manifest/permission/hardware feature checks
 * - GPUDiagnosticTool: Runtime GPU info detection
 * - GPUErrorHandler: Window config & Huawei-specific handling
 * 
 * New features:
 * - NPU capability detection (HiAI/CANN DDK)
 * - Unified diagnostic report generation
 * - Extensible for other vendors (Xiaomi/Qualcomm/etc.)
 */
public class AcceleratorDiagnostics {
    private static final String TAG = "AcceleratorDiag";
    
    // Diagnostic report sections
    public static class DiagnosticReport {
        public String systemInfo = "";
        public String gpuInfo = "";
        public String openclInfo = "";
        public String npuInfo = "";
        public String configCheck = "";
        public String recommendations = "";
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Hardware Accelerator Diagnostic Report ===\n\n");
            sb.append(systemInfo);
            sb.append(gpuInfo);
            sb.append(openclInfo);
            sb.append(npuInfo);
            sb.append(configCheck);
            sb.append(recommendations);
            return sb.toString();
        }
    }
    
    /**
     * Generate complete diagnostic report
     * @param context Application context
     * @return Diagnostic report object
     */
    public static DiagnosticReport generateReport(Context context) {
        DiagnosticReport report = new DiagnosticReport();
        
        try {
            report.systemInfo = collectSystemInfo();
            report.gpuInfo = collectGpuInfo(context);
            report.openclInfo = collectOpenCLInfo();
            report.npuInfo = collectNpuInfo(context);
            report.configCheck = checkConfiguration(context);
            report.recommendations = generateRecommendations(context);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to generate diagnostic report: " + e.getMessage(), e);
        }
        
        return report;
    }
    
    /**
     * Collect system information
     */
    private static String collectSystemInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("--- System Information ---\n");
        info.append(String.format("Manufacturer: %s\n", Build.MANUFACTURER));
        info.append(String.format("Model: %s\n", Build.MODEL));
        info.append(String.format("Android Version: %s (API %d)\n", Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        info.append(String.format("Build Display: %s\n", Build.DISPLAY));
        info.append(String.format("Hardware Platform: %s\n", Build.HARDWARE));
        info.append(String.format("CPU Architecture: %s\n", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "Unknown"));
        
        // Detect HarmonyOS/Huawei devices
        boolean isHuaweiDevice = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                                 Build.MANUFACTURER.toLowerCase().contains("honor") ||
                                 Build.BRAND.toLowerCase().contains("huawei") ||
                                 Build.BRAND.toLowerCase().contains("honor") ||
                                 Build.DISPLAY.toLowerCase().contains("harmony");
        info.append(String.format("Huawei/HarmonyOS Device: %s\n", isHuaweiDevice ? "Yes" : "No"));
        
        if (isHuaweiDevice) {
            info.append("  └─ NPU acceleration may be available via HiAI/CANN\n");
        }
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Collect GPU information
     */
    private static String collectGpuInfo(Context context) {
        StringBuilder info = new StringBuilder();
        
        info.append("--- GPU Information ---\n");
        
        // Check hardware features
        PackageManager pm = context.getPackageManager();
        
        // OpenGL ES support
        boolean hasOpenGLES20 = pm.hasSystemFeature("android.hardware.opengles.aep");
        boolean hasOpenGLESExtPack = pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK);
        
        info.append("OpenGL ES Support:\n");
        info.append(String.format("  - OpenGL ES AEP: %s\n", hasOpenGLES20 ? "✓" : "✗"));
        info.append(String.format("  - OpenGL ES Extension Pack: %s\n", hasOpenGLESExtPack ? "✓" : "✗"));
        
        if (hasOpenGLES20) {
            info.append("  └─ Supports OpenGL ES 3.1+ compute shaders\n");
        }
        
        // Vulkan support
        boolean hasVulkan = false;
        boolean hasVulkanCompute = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
            hasVulkanCompute = pm.hasSystemFeature("android.hardware.vulkan.compute");
        }
        info.append("Vulkan Support:\n");
        info.append(String.format("  - Vulkan Hardware Level: %s\n", hasVulkan ? "✓" : "✗"));
        info.append(String.format("  - Vulkan Compute: %s\n", hasVulkanCompute ? "✓" : "✗"));
        
        // Get GPU renderer info
        try {
            String renderer = getGPURenderer();
            if (renderer != null) {
                info.append(String.format("\nGPU Renderer: %s\n", renderer));
                
                // Mali GPU specific hints
                if (renderer.toLowerCase().contains("mali")) {
                    info.append("  └─ Mali GPU detected, OpenCL acceleration recommended\n");
                }
                // Adreno GPU hints
                else if (renderer.toLowerCase().contains("adreno")) {
                    info.append("  └─ Adreno GPU detected, Vulkan/OpenCL supported\n");
                }
            } else {
                info.append("\nGPU Renderer: Unable to detect\n");
            }
        } catch (Exception e) {
            info.append("\nGPU Renderer: Detection failed - " + e.getMessage() + "\n");
        }
        
        // NNAPI support
        if (Build.VERSION.SDK_INT >= 27) {
            info.append("\nNNAPI Support: ✓ (API 27+)\n");
        } else {
            info.append("\nNNAPI Support: ✗ (Requires Android 8.1+)\n");
        }
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Collect OpenCL information
     */
    private static String collectOpenCLInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("--- OpenCL Information ---\n");
        
        try {
            // Call native OpenCL detector
            String openclResult = com.offlineai.llamacpp.OpenCLDetector.detectOpenCL();
            info.append(openclResult);
        } catch (UnsatisfiedLinkError e) {
            info.append("OpenCL Detection: Native library not loaded\n");
            info.append("  └─ Error: " + e.getMessage() + "\n");
        } catch (Exception e) {
            info.append("OpenCL Detection: Failed\n");
            info.append("  └─ Error: " + e.getMessage() + "\n");
        }
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Collect NPU information (HiAI/CANN)
     */
    private static String collectNpuInfo(Context context) {
        StringBuilder info = new StringBuilder();
        
        info.append("--- NPU Information ---\n");
        
        // Check if Huawei device
        boolean isHuaweiDevice = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                                 Build.MANUFACTURER.toLowerCase().contains("honor");
        
        if (!isHuaweiDevice) {
            info.append("NPU Detection: Not a Huawei/Honor device\n");
            info.append("  └─ NPU acceleration via HiAI/CANN unavailable\n\n");
            return info.toString();
        }
        
        info.append("NPU Detection: Huawei/Honor device detected\n");
        
        // Check system libraries
        info.append("\nSystem Library Check:\n");
        boolean hasHiaiLib = checkSystemLibrary("/system/lib64/libhiai.so");
        boolean hasHiaiIrLib = checkSystemLibrary("/system/lib64/libhiai_ir.so");
        boolean hasHiaiAdapterLib = checkSystemLibrary("/system/lib64/libhiai_adapter.so");
        
        info.append(String.format("  - libhiai.so: %s\n", hasHiaiLib ? "✓" : "✗"));
        info.append(String.format("  - libhiai_ir.so: %s\n", hasHiaiIrLib ? "✓" : "✗"));
        info.append(String.format("  - libhiai_adapter.so: %s\n", hasHiaiAdapterLib ? "✓" : "✗"));
        
        // Check for full runtime libraries
        boolean hasRuntimeImpl = checkSystemLibrary("/system/lib64/libhiai_hcl_model_runtime_impl.so");
        boolean hasCpuCl = checkSystemLibrary("/system/lib64/libcpucl.so");
        
        info.append(String.format("  - libhiai_hcl_model_runtime_impl.so: %s\n", hasRuntimeImpl ? "✓" : "✗"));
        info.append(String.format("  - libcpucl.so: %s\n", hasCpuCl ? "✓" : "✗"));

        // Additional HiAI-related libraries often required by DDK/runtime
        boolean hasAiClient = checkSystemLibrary("/system/lib64/libai_client.so");
        boolean hasHiaiIrBuild = checkSystemLibrary("/system/lib64/libhiai_ir_build.so");
        boolean hasHiaiCl = checkSystemLibrary("/system/lib64/libhiai_cl.so");

        info.append(String.format("  - libai_client.so: %s\n", hasAiClient ? "✓" : "✗"));
        info.append(String.format("  - libhiai_ir_build.so: %s\n", hasHiaiIrBuild ? "✓" : "✗"));
        info.append(String.format("  - libhiai_cl.so: %s\n", hasHiaiCl ? "✓" : "✗"));
        
        if (!hasRuntimeImpl || !hasCpuCl || !hasAiClient || !hasHiaiIrBuild || !hasHiaiCl) {
            info.append("  └─ Full NPU runtime not available, app may need to bundle DDK libs\n");
        }
        
        // Check HiAI package
        info.append("\nHiAI Package Check:\n");
        PackageManager pm = context.getPackageManager();
        boolean hasHiaiPackage = false;
        try {
            pm.getPackageInfo("com.huawei.hiai", 0);
            hasHiaiPackage = true;
            info.append("  - com.huawei.hiai: ✓ Installed\n");
        } catch (PackageManager.NameNotFoundException e) {
            info.append("  - com.huawei.hiai: ✗ Not installed\n");
        }
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Check configuration (manifest, permissions, etc.)
     */
    private static String checkConfiguration(Context context) {
        StringBuilder info = new StringBuilder();
        
        info.append("--- Configuration Check ---\n");
        
        // Hardware acceleration flag
        try {
            android.content.pm.ApplicationInfo appInfo = context.getApplicationInfo();
            boolean hardwareAccelerated = (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
            
            info.append(String.format("Hardware Acceleration: %s\n", hardwareAccelerated ? "✓ Enabled" : "✗ Disabled"));
            if (!hardwareAccelerated) {
                info.append("  └─ Add android:hardwareAccelerated=\"true\" in AndroidManifest.xml\n");
            }
        } catch (Exception e) {
            info.append("Hardware Acceleration: Unable to check\n");
        }
        
        // Network permission (may be needed for some GPU operations)
        boolean hasNetworkPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) 
            == PackageManager.PERMISSION_GRANTED;
        info.append(String.format("Network State Permission: %s\n", hasNetworkPermission ? "✓" : "✗"));
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Generate recommendations based on detected capabilities
     */
    private static String generateRecommendations(Context context) {
        StringBuilder info = new StringBuilder();
        
        info.append("--- Recommendations ---\n");
        
        boolean isHuaweiDevice = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                                 Build.MANUFACTURER.toLowerCase().contains("honor");
        
        if (isHuaweiDevice) {
            info.append("Huawei/Honor Device Recommendations:\n");
            info.append("1. Check 'Settings > Developer Options > Force GPU rendering'\n");
            info.append("2. Disable power saving mode for best performance\n");
            info.append("3. NPU acceleration:\n");
            info.append("   - Requires bundled HiAI DDK libraries in app\n");
            info.append("   - Fallback to CPU for unsupported operators\n");
            info.append("4. Acceleration priority: NPU > OpenCL > Vulkan > CPU\n");
        } else {
            info.append("General Device Recommendations:\n");
            info.append("1. Enable GPU rendering in developer options\n");
            info.append("2. Check for system updates to get latest GPU drivers\n");
            info.append("3. Acceleration priority: Vulkan > OpenCL > OpenGL ES > CPU\n");
        }
        
        info.append("\n");
        return info.toString();
    }
    
    /**
     * Check if system library exists
     */
    private static boolean checkSystemLibrary(String path) {
        try {
            File lib = new File(path);
            return lib.exists();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get GPU renderer string via EGL
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
                        EGLContext context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, 
                                                                  new int[]{0x3098, 2, EGL10.EGL_NONE});
                        
                        if (context != EGL10.EGL_NO_CONTEXT) {
                            EGLSurface surface = egl.eglCreatePbufferSurface(display, configs[0], 
                                                                             new int[]{EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE});
                            
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
            LogManager.logW(TAG, "Failed to get GPU renderer: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Initialize GPU error handling (from GPUErrorHandler)
     */
    public static void initializeGPUHandling(Context context, Window window) {
        try {
            // Create cache directories
            createCacheDirectories(context);
            
            // Configure window format
            configureWindowFormat(context, window);
            
            // Handle Huawei-specific issues
            handleHuaweiSpecificSetup();
            
            LogManager.logD(TAG, "GPU handling initialization completed");
        } catch (Exception e) {
            LogManager.logE(TAG, "GPU handling initialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create shader cache directories
     */
    private static void createCacheDirectories(Context context) {
        try {
            File openglCacheDir = new File(context.getCacheDir(), "opengl_cache");
            if (!openglCacheDir.exists()) {
                openglCacheDir.mkdirs();
            }
            
            File skiaCacheDir = new File(context.getCacheDir(), "skia_cache");
            if (!skiaCacheDir.exists()) {
                skiaCacheDir.mkdirs();
            }
            
            try {
                System.setProperty("com.android.opengl.shaders_cache", openglCacheDir.getAbsolutePath());
                System.setProperty("com.android.skia.shaders_cache", skiaCacheDir.getAbsolutePath());
            } catch (Exception e) {
                LogManager.logW(TAG, "Unable to set shader cache properties: " + e.getMessage());
            }
            
            LogManager.logD(TAG, "Cache directories created successfully");
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to create cache directories: " + e.getMessage(), e);
        }
    }
    
    /**
     * Configure window format for GPU rendering
     */
    private static void configureWindowFormat(Context context, Window window) {
        try {
            if (window != null) {
                window.setFormat(android.graphics.PixelFormat.RGBA_8888);
                
                String backendPreference = ConfigManager.getString(context, ConfigManager.KEY_USE_GPU, "CPU");
                boolean useGpu = !"CPU".equals(backendPreference);
                
                if (useGpu) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                    LogManager.logD(TAG, "Hardware acceleration enabled");
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                    LogManager.logD(TAG, "Hardware acceleration disabled");
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to configure window format: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle Huawei-specific setup
     */
    private static void handleHuaweiSpecificSetup() {
        try {
            boolean isHuawei = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                               Build.BRAND.toLowerCase().contains("huawei") ||
                               Build.BRAND.toLowerCase().contains("honor");
            
            if (isHuawei) {
                LogManager.logD(TAG, "Huawei device detected, applying optimizations");
                
                try {
                    System.setProperty("hw_editor_disable", "true");
                    System.setProperty("hw_gpu_check_disable", "true");
                    LogManager.logD(TAG, "Huawei optimization properties set");
                } catch (Exception e) {
                    LogManager.logW(TAG, "Unable to set Huawei optimization properties: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to handle Huawei-specific setup: " + e.getMessage(), e);
        }
    }
    
    /**
     * Quick check if GPU acceleration is supported
     */
    public static boolean isGPUAccelerationSupported(Context context) {
        try {
            android.app.ActivityManager activityManager = 
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (activityManager != null) {
                android.content.pm.ConfigurationInfo configInfo = activityManager.getDeviceConfigurationInfo();
                return configInfo.reqGlEsVersion >= 0x20000;
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to check GPU support: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * Quick check if configuration is valid
     */
    public static boolean isConfigurationValid(Context context) {
        try {
            android.content.pm.ApplicationInfo appInfo = context.getApplicationInfo();
            boolean hardwareAccelerated = (appInfo.flags & android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
            
            PackageManager pm = context.getPackageManager();
            boolean hasOpenGL = pm.hasSystemFeature("android.hardware.opengles.aep");
            
            boolean supportedVersion = Build.VERSION.SDK_INT >= 26;
            
            return hardwareAccelerated && hasOpenGL && supportedVersion;
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Configuration check failed: " + e.getMessage(), e);
            return false;
        }
    }
}

