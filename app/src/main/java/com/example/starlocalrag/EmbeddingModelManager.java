package com.example.starlocalrag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 词嵌入模型管理器单例类
 * 负责模型的加载、缓存和卸载，避免重复加载模型
 * 模型只在超时后卸载，其他情况下保持加载状态
 */
public class EmbeddingModelManager {
    private static final String TAG = "EmbeddingModelManager";
    
    // 单例实例
    private static EmbeddingModelManager instance;
    
    // 当前加载的模型
    private EmbeddingModelHandler currentModel;
    
    // 当前模型路径
    private String currentModelPath;
    
    // 最后访问时间
    private long lastAccessTime;
    
    // 卸载定时器
    private Timer unloadTimer;
    
    // 线程池
    private final ExecutorService executor;
    
    // 主线程Handler
    private final Handler mainHandler;
    
    // 模型是否正在加载
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    
    // 模型是否正在使用中（被某个操作占用）
    private final AtomicBoolean isModelBusy = new AtomicBoolean(false);
    
    // 模型卸载超时（毫秒）- 5分钟
    private static final long UNLOAD_TIMEOUT_MS = 5 * 60 * 1000;
    
    // 上下文
    private Context applicationContext;
    
    // 后端偏好设置
    private String useGpu = "CPU";
    
    /**
     * 模型回调接口
     */
    public interface ModelCallback {
        void onModelReady(EmbeddingModelHandler model);
        void onModelError(Exception e);
    }
    
    /**
     * 模型加载状态监听接口
     */
    public interface ModelLoadListener {
        void onLoadStarted(String modelPath);
        void onLoadProgress(String modelPath, String message);
        void onLoadComplete(String modelPath);
        void onLoadError(String modelPath, Exception e);
    }
    
    // 模型加载监听器
    private ModelLoadListener loadListener;
    
    /**
     * 私有构造函数
     */
    private EmbeddingModelManager() {
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized EmbeddingModelManager getInstance(Context context) {
        if (instance == null) {
            instance = new EmbeddingModelManager();
        }
        
        if (context != null) {
            instance.applicationContext = context.getApplicationContext();
            
            // 初始化后端偏好设置
            instance.useGpu = SettingsFragment.getBackendPreference(context);
            LogManager.logD(TAG, "EmbeddingModelManager初始化: 后端偏好设置为 " + instance.useGpu);
        }
        
        return instance;
    }
    
    /**
     * 设置模型加载监听器
     */
    public void setModelLoadListener(ModelLoadListener listener) {
        this.loadListener = listener;
    }
    
    /**
     * 获取模型实例（同步方法）
     * 如果模型已加载且路径匹配，直接返回
     * 否则加载新模型
     * 如果模型正在被其他操作使用，则抛出异常
     */
    public synchronized EmbeddingModelHandler getModel(String modelPath) throws Exception {
        // 检查模型是否繁忙
        if (isModelBusy.get()) {
            throw new Exception("模型正在被其他操作使用，请稍后再试");
        }
        
        try {
            // 标记模型为繁忙状态
            isModelBusy.set(true);
            
            // 更新最后访问时间
            lastAccessTime = System.currentTimeMillis();
            
            // 取消定时卸载任务
            cancelUnloadTask();
            
            // 检查模型是否需要更换或重新加载
            boolean needReload = false;
            
            // 获取当前后端偏好设置
            String currentBackendPreference = SettingsFragment.getBackendPreference(applicationContext);
            boolean currentGpuSetting = !"CPU".equals(currentBackendPreference);
            
            // 检查当前模型是否有效
            if (currentModel != null) {
                try {
                    // 尝试进行一个简单的操作来验证模型是否有效
                    currentModel.getModelName();
                    
                    // 检查模型路径是否匹配
                    if (!currentModelPath.equals(modelPath)) {
                        LogManager.logD(TAG, "模型路径不匹配，需要重新加载。当前路径: " + currentModelPath + "，请求路径: " + modelPath);
                        LogManager.getInstance(applicationContext).i(TAG, "模型路径不匹配，需要重新加载。当前模型: " + 
                            new File(currentModelPath).getName() + "，请求模型: " + new File(modelPath).getName());
                        needReload = true;
                    } else {
                        // 检查后端偏好是否变更，如果变更则需要重新加载
                        if (!this.useGpu.equals(currentBackendPreference)) {
                            String gpuStatusMsg = String.format("当前后端偏好: %s，已加载模型设置: %s", 
                                currentBackendPreference, 
                                this.useGpu);
                            
                            LogManager.logD(TAG, gpuStatusMsg + "，需要重新加载模型");
                            LogManager.getInstance(applicationContext).i(TAG, gpuStatusMsg + "，需要重新加载模型");
                            needReload = true;
                        } else {
                            String gpuStatusMsg = String.format("当前后端偏好: %s，已加载模型设置: %s", 
                                currentBackendPreference, 
                                this.useGpu);
                            
                            LogManager.logD(TAG, gpuStatusMsg + "，直接使用现有模型");
                            LogManager.getInstance(applicationContext).d(TAG, gpuStatusMsg + 
                                "，直接使用现有模型: " + new File(currentModelPath).getName());
                        }
                    }
                } catch (Exception e) {
                    String gpuStatusMsg = String.format("当前后端偏好: %s，已加载模型状态: 无效", 
                        currentBackendPreference);
                    
                    LogManager.logE(TAG, gpuStatusMsg + "，需要重新加载: " + e.getMessage(), e);
                    LogManager.getInstance(applicationContext).e(TAG, gpuStatusMsg + "，需要重新加载: " + e.getMessage());
                    needReload = true;
                }
            } else {
                String gpuStatusMsg = String.format("当前后端偏好: %s，已加载模型状态: 未加载", 
                    currentBackendPreference);
                
                LogManager.logD(TAG, gpuStatusMsg + "，需要加载模型: " + modelPath);
                LogManager.getInstance(applicationContext).i(TAG, gpuStatusMsg + 
                    "，需要加载模型: " + new File(modelPath).getName());
                needReload = true;
            }
            
            // 如果需要重新加载模型
            if (needReload) {
                LogManager.logD(TAG, "开始加载模型: " + modelPath);
                
                // 关闭现有模型
                if (currentModel != null) {
                    try {
                        String modelName = new File(currentModelPath).getName();
                        LogManager.logD(TAG, "关闭现有模型: " + modelName);
                        LogManager.getInstance(applicationContext).i(TAG, "关闭现有模型: " + modelName);
                        currentModel.close();
                    } catch (Exception e) {
                        LogManager.logE(TAG, "关闭现有模型失败: " + e.getMessage(), e);
                        LogManager.getInstance(applicationContext).e(TAG, "关闭现有模型失败: " + e.getMessage());
                    }
                    currentModel = null;
                } else {
                    LogManager.getInstance(applicationContext).i(TAG, "未加载模型，无需关闭");
                }
                
                // 尝试加载模型
                String loadMsg = String.format("尝试使用%s后端加载模型: %s", 
                    currentBackendPreference, 
                    new File(modelPath).getName());
                LogManager.getInstance(applicationContext).i(TAG, loadMsg);
                
                currentModel = loadModel(modelPath, currentGpuSetting);
                currentModelPath = modelPath;
                this.useGpu = currentBackendPreference;
                
                // 设置调试模式
                boolean debugMode = SettingsFragment.isDebugModeEnabled(applicationContext);
                if (currentModel != null) {
                    currentModel.setDebugMode(debugMode);
                    LogManager.logD(TAG, "模型加载完成，设置调试模式: " + (debugMode ? "启用" : "禁用"));
                    LogManager.getInstance(applicationContext).d(TAG, "模型 " + new File(modelPath).getName() + 
                        " 加载完成，设置调试模式: " + (debugMode ? "启用" : "禁用"));
                }
            }
            
            return currentModel;
        } finally {
            // 安排定时卸载任务
            scheduleModelUnload();
            
            // 标记模型为非繁忙状态
            isModelBusy.set(false);
        }
    }
    
    /**
     * 异步获取模型
     * 如果模型正在被其他操作使用，则通过回调返回错误
     */
    public void getModelAsync(final String modelPath, final ModelCallback callback) {
        // 检查参数
        if (modelPath == null || modelPath.isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onModelError(new IllegalArgumentException("模型路径不能为空")));
            }
            return;
        }
        
        // 检查模型是否繁忙
        if (isModelBusy.get()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onModelError(new Exception("模型正在被其他操作使用，请稍后再试")));
            }
            return;
        }
        
        // 标记模型为繁忙状态
        isModelBusy.set(true);
        
        // 更新最后访问时间
        lastAccessTime = System.currentTimeMillis();
        
        // 取消定时卸载任务
        cancelUnloadTask();
        
        // 获取当前后端偏好设置
        String currentBackendPreference = SettingsFragment.getBackendPreference(applicationContext);
        boolean currentGpuSetting = !"CPU".equals(currentBackendPreference);
        
        // 检查是否需要重新加载模型
        boolean needReload = false;
        
        // 检查当前模型是否有效
        if (currentModel != null) {
            try {
                // 尝试进行一个简单的操作来验证模型是否有效
                currentModel.getModelName();
                
                // 检查模型路径是否匹配
                if (!currentModelPath.equals(modelPath)) {
                    LogManager.logD(TAG, "异步加载 - 模型路径不匹配，需要重新加载。当前路径: " + currentModelPath + "，请求路径: " + modelPath);
                    LogManager.getInstance(applicationContext).i(TAG, "异步加载 - 模型路径不匹配，需要重新加载。当前模型: " + 
                        new File(currentModelPath).getName() + "，请求模型: " + new File(modelPath).getName());
                    needReload = true;
                } else {
                    // 检查后端偏好是否变更，如果变更则需要重新加载
                    if (!this.useGpu.equals(currentBackendPreference)) {
                        String gpuStatusMsg = String.format("异步加载 - 当前后端偏好: %s，已加载模型设置: %s", 
                            currentBackendPreference, 
                            this.useGpu);
                        
                        LogManager.logD(TAG, gpuStatusMsg + "，需要重新加载模型");
                        LogManager.getInstance(applicationContext).i(TAG, gpuStatusMsg + "，需要重新加载模型");
                        needReload = true;
                    } else {
                        String gpuStatusMsg = String.format("异步加载 - 当前后端偏好: %s，已加载模型设置: %s", 
                            currentBackendPreference, 
                            this.useGpu);
                        
                        LogManager.logD(TAG, gpuStatusMsg + "，直接使用现有模型");
                        LogManager.getInstance(applicationContext).d(TAG, gpuStatusMsg + 
                            "，直接使用现有模型: " + new File(currentModelPath).getName());
                    }
                }
            } catch (Exception e) {
                String gpuStatusMsg = String.format("异步加载 - 当前后端偏好: %s，已加载模型状态: 无效", 
                    currentBackendPreference);
                
                LogManager.logE(TAG, gpuStatusMsg + "，需要重新加载: " + e.getMessage(), e);
                LogManager.getInstance(applicationContext).e(TAG, gpuStatusMsg + "，需要重新加载: " + e.getMessage());
                needReload = true;
            }
        } else {
            String gpuStatusMsg = String.format("异步加载 - 当前后端偏好: %s，已加载模型状态: 未加载", 
                currentBackendPreference);
            
            LogManager.logD(TAG, gpuStatusMsg + "，需要加载模型: " + modelPath);
            LogManager.getInstance(applicationContext).i(TAG, gpuStatusMsg + 
                "，需要加载模型: " + new File(modelPath).getName());
            needReload = true;
        }
        
        // 在后台线程中执行，避免阻塞UI线程
        final boolean finalNeedReload = needReload;
        final boolean finalGpuSetting = currentGpuSetting;
        
        executor.execute(() -> {
            try {
                // 检查全局停止标志
                if (GlobalStopManager.isGlobalStopRequested()) {
                    LogManager.logD(TAG, "Global stop requested, aborting model loading");
                    mainHandler.post(() -> callback.onModelError(new Exception("Operation stopped by user")));
                    return;
                }
                
                // 更新最后访问时间
                lastAccessTime = System.currentTimeMillis();
                
                // 取消定时卸载任务
                cancelUnloadTask();
                
                // 如果需要重新加载模型
                if (finalNeedReload) {
                    LogManager.logD(TAG, "异步加载 - 开始加载模型: " + modelPath);
                    
                    // 关闭现有模型
                    if (currentModel != null) {
                        try {
                            String modelName = new File(currentModelPath).getName();
                            LogManager.logD(TAG, "关闭现有模型: " + modelName);
                            LogManager.getInstance(applicationContext).i(TAG, "关闭现有模型: " + modelName);
                            currentModel.close();
                        } catch (Exception e) {
                            LogManager.logE(TAG, "关闭现有模型失败: " + e.getMessage(), e);
                            LogManager.getInstance(applicationContext).e(TAG, "关闭现有模型失败: " + e.getMessage());
                        }
                        currentModel = null;
                    } else {
                        LogManager.getInstance(applicationContext).i(TAG, "未加载模型，无需关闭");
                    }
                    
                    // 再次检查全局停止标志
                    if (GlobalStopManager.isGlobalStopRequested()) {
                        LogManager.logD(TAG, "Global stop requested, aborting model loading before load");
                        mainHandler.post(() -> callback.onModelError(new Exception("Operation stopped by user")));
                        return;
                    }
                    
                    // 尝试加载模型
                    String loadMsg = String.format("异步加载 - 尝试使用%s后端加载模型: %s", 
                        currentBackendPreference, 
                        new File(modelPath).getName());
                    LogManager.getInstance(applicationContext).i(TAG, loadMsg);
                    
                    currentModel = loadModel(modelPath, finalGpuSetting);
                    currentModelPath = modelPath;
                    useGpu = currentBackendPreference;
                    
                    // 设置调试模式
                    boolean debugMode = SettingsFragment.isDebugModeEnabled(applicationContext);
                    if (currentModel != null) {
                        currentModel.setDebugMode(debugMode);
                        LogManager.logD(TAG, "模型加载完成，设置调试模式: " + (debugMode ? "启用" : "禁用"));
                        LogManager.getInstance(applicationContext).d(TAG, "模型 " + new File(modelPath).getName() + 
                            " 加载完成，设置调试模式: " + (debugMode ? "启用" : "禁用"));
                    }
                } else {
                    LogManager.logD(TAG, "异步加载 - 使用现有模型，无需重新加载");
                }
                
                // 通知模型加载完成
                final EmbeddingModelHandler model = currentModel;
                mainHandler.post(() -> callback.onModelReady(model));
            } catch (Exception e) {
                LogManager.logE(TAG, "异步加载 - 加载模型失败: " + e.getMessage(), e);
                LogManager.getInstance(applicationContext).e(TAG, "异步加载 - 加载模型失败: " + e.getMessage());
                mainHandler.post(() -> callback.onModelError(e));
            } finally {
                // 安排定时卸载任务
                scheduleModelUnload();
                
                // 标记模型为非繁忙状态
                isModelBusy.set(false);
            }
        });
    }
    
    /**
     * 加载嵌入模型
     * @param modelPath 模型文件路径
     * @param useGpu 是否使用GPU加速
     * @return 加载的模型处理器
     * @throws Exception 如果加载失败
     */
    private EmbeddingModelHandler loadModel(String modelPath, boolean useGpu) throws Exception {
        LogManager.logD(TAG, "开始加载模型: " + modelPath + ", 使用GPU: " + useGpu);
        
        // 通知加载开始
        notifyLoadStarted(modelPath);
        
        try {
            String loadMsg = String.format("加载模型: %s (GPU: %s)", new File(modelPath).getName(), useGpu ? "启用" : "禁用");
            LogManager.logD(TAG, loadMsg);
            notifyLoadProgress(modelPath, "正在加载模型...");
            LogManager.getInstance(applicationContext).i(TAG, loadMsg);
            
            // 在创建EmbeddingModelHandler实例前检查全局停止标志
            if (GlobalStopManager.isGlobalStopRequested()) {
                LogManager.logD(TAG, "Global stop requested, aborting EmbeddingModelHandler creation");
                throw new Exception("Operation stopped by user");
            }
            
            // 直接使用EmbeddingModelHandler，它内部会处理GPU回退逻辑
            EmbeddingModelHandler model = new EmbeddingModelHandler(applicationContext, modelPath, useGpu);
            
            LogManager.logD(TAG, "模型加载成功: " + new File(modelPath).getName());
            LogManager.getInstance(applicationContext).i(TAG, "模型加载成功 - " + new File(modelPath).getName());
            notifyLoadComplete(modelPath);
            return model;
        } catch (Exception e) {
            notifyLoadError(modelPath, e);
            throw e;
        }
    }
    
    /**
     * 取消定时卸载任务
     */
    private synchronized void cancelUnloadTask() {
        if (unloadTimer != null) {
            unloadTimer.cancel();
            unloadTimer = null;
        }
    }
    
    /**
     * 安排模型卸载定时任务
     * 已禁用超时卸载机制，模型将常驻内存
     */
    private synchronized void scheduleModelUnload() {
        // 取消现有任务
        cancelUnloadTask();
        
        // 记录日志，模型将常驻内存
        LogManager.logD(TAG, "已禁用模型超时卸载机制，模型将常驻内存");
        
        // 不创建定时卸载任务，模型将一直保持在内存中
    }
    
    /**
     * 标记模型开始使用
     * 更新最后访问时间并取消定时卸载任务
     */
    public synchronized void markModelInUse() {
        // 更新最后访问时间
        lastAccessTime = System.currentTimeMillis();
        
        // 取消定时卸载任务
        cancelUnloadTask();
        
        //LogManager.logD(TAG, "标记模型开始使用");
    }
    
    /**
     * 标记模型使用结束
     * 更新最后访问时间并安排定时卸载任务
     */
    public synchronized void markModelNotInUse() {
        // 更新最后访问时间
        lastAccessTime = System.currentTimeMillis();
        
        // 安排定时卸载任务
        scheduleModelUnload();
        
        LogManager.logD(TAG, "标记模型使用结束，安排定时卸载任务");
    }
    
    /**
     * 显示Toast消息
     */
    private void showToast(final String message) {
        if (applicationContext != null) {
            mainHandler.post(() -> {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    /**
     * 通知模型加载开始
     */
    private void notifyLoadStarted(String modelPath) {
        if (loadListener != null) {
            mainHandler.post(() -> loadListener.onLoadStarted(modelPath));
        }
    }
    
    /**
     * 通知模型加载进度
     */
    private void notifyLoadProgress(String modelPath, String message) {
        if (loadListener != null) {
            mainHandler.post(() -> loadListener.onLoadProgress(modelPath, message));
        }
    }
    
    /**
     * 通知模型加载完成
     */
    private void notifyLoadComplete(String modelPath) {
        if (loadListener != null) {
            mainHandler.post(() -> loadListener.onLoadComplete(modelPath));
        }
    }
    
    /**
     * 通知模型加载错误
     */
    private void notifyLoadError(String modelPath, Exception e) {
        if (loadListener != null) {
            mainHandler.post(() -> loadListener.onLoadError(modelPath, e));
        }
    }
    
    /**
     * 更新GPU设置
     * @param useGpu 是否使用GPU
     */
    public synchronized void updateGpuSetting(String useGpu) {
        LogManager.logD(TAG, "EmbeddingModel 后端偏好更新: " + useGpu);
        
        // 更新后端偏好设置
        this.useGpu = useGpu;
        
        // 如果当前有加载的模型，记录提示信息
        if (currentModel != null) {
            LogManager.logW(TAG, "EmbeddingModel 后端偏好: 后端偏好已更新，但当前模型仍在使用中。新的后端偏好将在下次加载模型时生效。");
            LogManager.logI(TAG, "EmbeddingModel 后端偏好: 如需立即应用新的后端偏好，请重新加载模型或重启应用。");
        } else {
            LogManager.logI(TAG, "EmbeddingModel 后端偏好: 后端偏好已更新，将在下次加载模型时生效。");
        }
    }
}
