package com.starlocalrag.llamacpp;

import android.util.Log;

/**
 * 本地库加载器
 * 负责加载llama.cpp相关的本地库
 */
public class NativeLibraryLoader {
    private static final String TAG = "NativeLibraryLoader";
    
    // 库加载状态
    private static boolean isLoaded = false;
    private static final Object loadLock = new Object();
    
    /**
     * 加载指定的本地库
     * @param libraryName 库名称（不包含lib前缀和.so后缀）
     */
    public static void loadLibrary(String libraryName) {
        synchronized (loadLock) {
            if (isLoaded) {
                return;
            }
            
            try {
                // 加载JNI库（已包含llama.cpp核心功能）
                System.loadLibrary(libraryName);
                Log.d(TAG, "成功加载 lib" + libraryName + ".so");
                
                isLoaded = true;
                Log.i(TAG, "LlamaCpp本地库加载完成");
                
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "加载本地库失败: " + libraryName, e);
                
                // 尝试提供更详细的错误信息
                String errorMsg = e.getMessage();
                if (errorMsg != null) {
                    if (errorMsg.contains("dlopen failed")) {
                        Log.e(TAG, "库文件可能损坏或架构不匹配");
                    } else if (errorMsg.contains("No implementation found")) {
                        Log.e(TAG, "找不到对应架构的库文件");
                    }
                }
                
                throw new RuntimeException("无法加载LlamaCpp本地库: " + libraryName, e);
            } catch (Exception e) {
                Log.e(TAG, "加载本地库时发生未知错误: " + libraryName, e);
                throw new RuntimeException("加载LlamaCpp本地库失败: " + libraryName, e);
            }
        }
    }
    
    /**
     * 检查库是否已加载
     * @return 是否已加载
     */
    public static boolean isLibraryLoaded() {
        return isLoaded;
    }
    
    /**
     * 获取当前设备的ABI架构
     * @return ABI架构字符串
     */
    public static String getCurrentAbi() {
        return android.os.Build.SUPPORTED_ABIS[0];
    }
    
    /**
     * 获取支持的ABI架构列表
     * @return ABI架构数组
     */
    public static String[] getSupportedAbis() {
        return android.os.Build.SUPPORTED_ABIS;
    }
    
    /**
     * 检查是否支持指定的ABI架构
     * @param abi ABI架构
     * @return 是否支持
     */
    public static boolean isSupportedAbi(String abi) {
        String[] supportedAbis = getSupportedAbis();
        for (String supportedAbi : supportedAbis) {
            if (supportedAbi.equals(abi)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 记录设备架构信息
     */
    public static void logDeviceInfo() {
        Log.i(TAG, "设备架构信息:");
        Log.i(TAG, "主要ABI: " + getCurrentAbi());
        Log.i(TAG, "支持的ABI: " + java.util.Arrays.toString(getSupportedAbis()));
        Log.i(TAG, "CPU架构: " + System.getProperty("os.arch"));
        Log.i(TAG, "Android版本: " + android.os.Build.VERSION.RELEASE);
        Log.i(TAG, "API级别: " + android.os.Build.VERSION.SDK_INT);
    }
}