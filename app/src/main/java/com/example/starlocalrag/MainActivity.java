package com.example.starlocalrag;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.content.res.Configuration;
import java.util.Locale;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.EmbeddingModelManager;
import com.example.starlocalrag.api.LocalLlmAdapter;
import com.example.starlocalrag.GPUConfigChecker;
import com.example.starlocalrag.GPUDiagnosticTool;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

public class MainActivity extends AppCompatActivity implements SettingsFragment.SettingsChangeListener {

    private static final String TAG = "StarLocalRAG"; // 添加TAG用于日志打印
    // 使用BuildConfig中的构建时间作为版本号，而不是实时时间
    private static final String BUILD_VERSION = BuildConfig.BUILD_VERSION;
    
    // 权限请求相关常量
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1002;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1003;

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private LogManager logManager;
    private StateDisplayManager stateDisplayManager;
    
    private boolean isInForeground = false;
    private KnowledgeBaseBuilderService knowledgeBaseBuilderService;
    private ServiceConnection serviceConnection;
    
    // ActivityResultLauncher替代startActivityForResult
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private ActivityResultLauncher<Intent> batteryOptimizationLauncher;
    
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(updateBaseContextLocale(newBase));
    }
    
    /**
     * 更新Context的语言设置
     */
    private Context updateBaseContextLocale(Context context) {
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
            LogManager.logE(TAG, "Failed to update Context language settings: " + e.getMessage());
            return context;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 初始化GPU错误处理
        GPUErrorHandler.init(getApplicationContext(), getWindow());
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_tabbed);
        
        // 初始化ActivityResultLauncher
        initializeActivityResultLaunchers();
        
        // 初始化返回按键处理
        initializeOnBackPressedCallback();
        
        // 初始化日志管理器
        logManager = LogManager.getInstance(this);
        
        // 初始化状态显示管理器
        stateDisplayManager = new StateDisplayManager(this);
        
        // 加载日志配置
        LogManager.loadLogConfig(this);
        
        // 如果是Release版本，默认强制记录知识库构建过程的日志
        if (!BuildConfig.DEBUG) {
            LogManager.setForceLogToFile(true);
            LogManager.saveLogConfig(this);
        }
        
        logManager.i(TAG, "应用启动，版本: " + BUILD_VERSION);
        
        // 请求必要的权限
        requestRequiredPermissions();
        
        // 请求忽略电池优化
        requestIgnoreBatteryOptimization();
        
        // 初始化配置
        initializeConfig();
        
        // 执行GPU配置检查
        performGPUConfigCheck();
        
        // 初始化ViewPager2和BottomNavigationView
        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        
        // 设置ViewPager2适配器
        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                // 根据位置返回对应的Fragment
                switch (position) {
                    case 0:
                        return new RagQaFragment();
                    case 1:
                        return new BuildKnowledgeBaseFragment();
                    case 2:
                        return new KnowledgeNoteFragment();
                    default:
                        return new RagQaFragment();
                }
            }
            
            @Override
            public int getItemCount() {
                return 3; // 总共有3个页面
            }
        });
        
        // 禁用ViewPager2的滑动功能，只通过底部导航栏切换
        viewPager.setUserInputEnabled(false);
        
        // 设置底部导航栏的选择监听器
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_rag_qa) {
                viewPager.setCurrentItem(0, false);
                return true;
            } else if (itemId == R.id.navigation_build_kb) {
                viewPager.setCurrentItem(1, false);
                return true;
            } else if (itemId == R.id.navigation_kb_note) {
                viewPager.setCurrentItem(2, false);
                return true;
            }
            return false;
        });
        
        // 设置ViewPager2的页面切换监听器
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                switch (position) {
                    case 0:
                        bottomNavigation.setSelectedItemId(R.id.navigation_rag_qa);
                        break;
                    case 1:
                        bottomNavigation.setSelectedItemId(R.id.navigation_build_kb);
                        break;
                    case 2:
                        bottomNavigation.setSelectedItemId(R.id.navigation_kb_note);
                        break;
                }
            }
        });
        
        // 绑定到知识库构建服务
        bindToKnowledgeBaseBuilderService();
    }
    
    /**
     * 请求必要的权限
     */
    private void requestRequiredPermissions() {
        // 对 Android 11 以下版本（API < 30），仍需请求传统存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 检查是否已经获得了所有权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                
                // 请求存储权限
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            // English log: skip legacy storage permissions on Android 11+
            LogManager.logD(TAG, "Skip legacy READ/WRITE external storage permissions on Android 11+ (MANAGE_EXTERNAL_STORAGE flow only)");
        }
        
        // 对于Android 11及以上版本，需要请求MANAGE_EXTERNAL_STORAGE权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 检查是否已经保存了权限状态
            boolean hasStoragePermission = ConfigManager.getBoolean(this, "has_storage_permission", false);
            
            if (!hasStoragePermission && !Environment.isExternalStorageManager()) {
                try {
                    // 显示一次性权限请求对话框
                    androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
                    builder.setTitle(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_TITLE_NEED_FULL_FILE_ACCESS));
                    builder.setMessage(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_MESSAGE_NEED_FULL_FILE_ACCESS));
                    builder.setPositiveButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_GO_TO_SETTINGS), (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        manageStorageLauncher.launch(intent);
                    });
                    builder.setNegativeButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_CANCEL), (dialog, which) -> {
                        Toast.makeText(this, getString(R.string.toast_app_may_not_work_short), Toast.LENGTH_LONG).show();
                    });
                    builder.setCancelable(false);
                    builder.show();
                } catch (Exception e) {
                    LogManager.logE(TAG, "Cannot open file access permission settings: " + e.getMessage());
                    Toast.makeText(this, getString(R.string.toast_cannot_open_file_permission_settings), Toast.LENGTH_LONG).show();
                }
            } else if (Environment.isExternalStorageManager() && !hasStoragePermission) {
                // 如果已经有权限但没有保存状态，则保存状态
                ConfigManager.setBoolean(this, "has_storage_permission", true);
                LogManager.logD(TAG, "Obtained full file access permission and saved status");
            }
        }
    }
    
    /**
     * 请求忽略电池优化
     */
    private void requestIgnoreBatteryOptimization() {
        // 不再在应用启动时自动请求，而是在需要时才请求
        LogManager.logD(TAG, "Battery optimization status: " + (isIgnoringBatteryOptimizations() ? "ignored" : "not ignored"));
    }
    
    /**
     * 请求忽略电池优化
     * @return 是否成功发起请求
     */
    public boolean requestIgnoreBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations()) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    batteryOptimizationLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to request ignore battery optimization: " + e.getMessage(), e);
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * 恢复电池优化
     * @return 是否成功发起请求
     */
    public boolean restoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isIgnoringBatteryOptimizations()) {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                try {
                    Toast.makeText(this, getString(R.string.toast_please_re_enable_battery_optimization), Toast.LENGTH_LONG).show();
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    LogManager.logE(TAG, "Failed to restore battery optimization: " + e.getMessage(), e);
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * 初始化应用配置
     */
    private void initializeConfig() {
        try {
            // 加载配置，如果不存在会创建默认配置
            JSONObject config = ConfigManager.loadConfig(this);
            LogManager.logD(TAG, "Loading configuration at app startup: " + config.toString(2));
            
            // 确保配置文件存在
            File configFile = new File(getFilesDir(), ".config");
            if (configFile.exists()) {
                LogManager.logD(TAG, "Configuration file exists: " + configFile.getAbsolutePath());
            } else {
                LogManager.logD(TAG, "Configuration file does not exist, creating default configuration");
                ConfigManager.saveConfig(this, config);
            }
            
            // 初始化默认API Keys
            ConfigManager.initializeDefaultApiKeys(this);
            
            // 设置默认分块大小和重叠大小
            if (!config.has(ConfigManager.KEY_CHUNK_SIZE)) {
                ConfigManager.setInt(this, ConfigManager.KEY_CHUNK_SIZE, 1000);
            }
            if (!config.has(ConfigManager.KEY_OVERLAP_SIZE)) {
                ConfigManager.setInt(this, ConfigManager.KEY_OVERLAP_SIZE, 200);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to initialize configuration", e);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                LogManager.logD(TAG, "All permissions granted");
                // 重新加载配置
                initializeConfig();
            } else {
                LogManager.logE(TAG, "Permissions denied");
                Toast.makeText(this, "需要存储权限才能访问模型和知识库文件", Toast.LENGTH_LONG).show();
                
                // 显示权限说明对话框
                showPermissionExplanationDialog();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // 已获得权限，保存状态
                    ConfigManager.setBoolean(this, "has_storage_permission", true);
                    LogManager.logD(TAG, "Obtained full file access permission");
                    Toast.makeText(this, getString(R.string.toast_file_access_permission_granted), Toast.LENGTH_SHORT).show();
                } else {
                    // 未获得权限
                    LogManager.logW(TAG, "Did not obtain full file access permission");
                    Toast.makeText(this, getString(R.string.toast_file_access_permission_denied), Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (isIgnoringBatteryOptimizations()) {
                    LogManager.logD(TAG, "Battery optimization ignored");
                } else {
                    LogManager.logW(TAG, "Battery optimization not ignored");
                }
            }
        }
    }
    
    /**
     * 显示权限说明对话框
     */
    private void showPermissionExplanationDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_TITLE_NEED_STORAGE_PERMISSION));
        builder.setMessage(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_MESSAGE_NEED_STORAGE_PERMISSION));
        builder.setPositiveButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_GO_TO_SETTINGS), (dialog, which) -> {
            // 打开应用设置页面
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });
        builder.setNegativeButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_CANCEL), (dialog, which) -> {
            Toast.makeText(this, getString(R.string.toast_app_may_not_work_short), Toast.LENGTH_SHORT).show();
        });
        builder.setCancelable(false);
        builder.show();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        
        // 动态设置语言切换菜单项的标题
        MenuItem languageItem = menu.findItem(R.id.action_switch_language);
        if (languageItem != null) {
            String currentLanguage = ConfigManager.getString(this, ConfigManager.KEY_LANGUAGE, ConfigManager.DEFAULT_LANGUAGE);
            if ("CHN".equals(currentLanguage)) {
                languageItem.setTitle(stateDisplayManager.getMenuDisplay(AppConstants.MENU_SWITCH_TO_ENGLISH));
            } else {
                languageItem.setTitle(stateDisplayManager.getMenuDisplay(AppConstants.MENU_SWITCH_TO_CHINESE));
            }
        }
        
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_settings) {
            // 打开设置界面
            viewPager.setVisibility(View.GONE);
            findViewById(R.id.container).setVisibility(View.VISIBLE);
            
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.container, new SettingsFragment());
            transaction.addToBackStack("settings");
            transaction.commit();
            return true;
        } else if (id == R.id.action_default_model_download) {
            // 打开默认模型下载界面
            viewPager.setVisibility(View.GONE);
            findViewById(R.id.container).setVisibility(View.VISIBLE);
            
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.container, new ModelDownloadFragment());
            transaction.addToBackStack("model_download");
            transaction.commit();
            return true;
        } else if (id == R.id.action_exit) {
            // 退出应用
            finish();
            return true;
        } else if (id == R.id.action_about) {
            // 显示关于信息
            showAboutDialog();
            return true;
        } else if (id == R.id.action_help) {
            // 打开帮助界面
            viewPager.setVisibility(View.GONE);
            findViewById(R.id.container).setVisibility(View.VISIBLE);
            
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.container, new HelpFragment());
            transaction.addToBackStack("help");
            transaction.commit();
            return true;
        } else if (id == R.id.action_view_log) {
            // 打开日志查看界面
            viewPager.setVisibility(View.GONE);
            findViewById(R.id.container).setVisibility(View.VISIBLE);
            
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.container, new LogViewFragment());
            transaction.addToBackStack("log_view");
            transaction.commit();
            return true;
        } else if (id == R.id.action_switch_language) {
            // 切换语言设置
            String currentLanguage = ConfigManager.getString(this, ConfigManager.KEY_LANGUAGE, ConfigManager.DEFAULT_LANGUAGE);
            String newLanguage = "CHN".equals(currentLanguage) ? "ENG" : "CHN";
            ConfigManager.setString(this, ConfigManager.KEY_LANGUAGE, newLanguage);
            
            // 更新应用语言设置
            GlobalApplication.updateAppLocale(newLanguage);
            
            // 显示切换成功的提示
            String message = "ENG".equals(newLanguage) ? "Language switched to English" : "语言已切换为中文";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            
            // 重新创建Activity以应用新的语言设置
            recreate();
            
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    

    
    private void showAboutDialog() {
        // 组合完整的版本信息
        String versionName = BuildConfig.VERSION_NAME;
        String buildVersion = BUILD_VERSION;
        String versionInfo = String.format("v%s (Build: %s)", versionName, buildVersion);
        
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_TITLE_ABOUT));
        builder.setMessage(String.format(stateDisplayManager.getDialogDisplay(AppConstants.DIALOG_MESSAGE_ABOUT), versionInfo));
        builder.setPositiveButton(stateDisplayManager.getButtonDisplay(AppConstants.BUTTON_TEXT_OK), null);
        builder.show();
    }
    
    @Override
    public void onSettingsChanged() {
        // 设置已更改，刷新相关数据
        LogManager.logD(TAG, "Settings changed, refreshing data");
        
        // 获取最新的GPU设置
        boolean useGpu = ConfigManager.getBoolean(this, ConfigManager.KEY_USE_GPU, false);
        LogManager.logI(TAG, "GPU setting change notification: " + (useGpu ? "enabled" : "disabled") + " GPU acceleration");
        
        // 更新LocalLlmAdapter的GPU设置
        try {
            LocalLlmAdapter localLlmAdapter = LocalLlmAdapter.getInstance(this);
            if (localLlmAdapter != null) {
                localLlmAdapter.updateGpuSetting(useGpu);
            } else {
                LogManager.logW(TAG, "GPU setting change: LocalLlmAdapter instance is null, cannot update GPU settings");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "GPU setting change: Failed to update LocalLlmAdapter GPU settings: " + e.getMessage(), e);
        }
        
        // 更新EmbeddingModelManager的GPU设置
        try {
            EmbeddingModelManager embeddingModelManager = EmbeddingModelManager.getInstance(this);
            if (embeddingModelManager != null) {
                embeddingModelManager.updateGpuSetting(useGpu);
                LogManager.logI(TAG, "GPU setting change: Successfully updated EmbeddingModelManager GPU settings");
            } else {
                LogManager.logW(TAG, "GPU setting change: EmbeddingModelManager instance is null, cannot update GPU settings");
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "GPU setting change: Failed to update EmbeddingModelManager GPU settings: " + e.getMessage(), e);
        }
        
        // 重新加载当前Fragment
        int currentItem = viewPager.getCurrentItem();
        viewPager.setAdapter(viewPager.getAdapter());
        viewPager.setCurrentItem(currentItem);
    }
    
    /**
     * 导航到知识库笔记页面
     */
    public void navigateToKnowledgeNote() {
        // 切换到知识库笔记页面（索引为2）
        viewPager.setCurrentItem(2, false);
        bottomNavigation.setSelectedItemId(R.id.navigation_kb_note);
    }
    
    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            return powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }
        return false;
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        LogManager.logD(TAG, "MainActivity.onStart()");
        isInForeground = true;
        
        // 如果服务正在运行，通知服务应用已切换到前台
        if (knowledgeBaseBuilderService != null) {
            knowledgeBaseBuilderService.onAppForegrounded();
            LogManager.logD(TAG, "Notified knowledge base build service: app switched to foreground");
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        LogManager.logD(TAG, "MainActivity.onStop()");
        isInForeground = false;
        
        // 如果服务正在运行，通知服务应用已切换到后台
        if (knowledgeBaseBuilderService != null) {
            knowledgeBaseBuilderService.onAppBackgrounded();
            LogManager.logD(TAG, "Notified knowledge base build service: app switched to background");
        }
    }
    
    /**
     * 绑定到知识库构建服务
     */
    private void bindToKnowledgeBaseBuilderService() {
        LogManager.logD(TAG, "尝试绑定到知识库构建服务");
        if (serviceConnection == null) {
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    KnowledgeBaseBuilderService.LocalBinder binder = (KnowledgeBaseBuilderService.LocalBinder) service;
                    knowledgeBaseBuilderService = binder.getService();
                    LogManager.logD(TAG, "已成功绑定到知识库构建服务");
                    
                    // 如果应用当前在后台，通知服务
                    if (!isInForeground && knowledgeBaseBuilderService != null) {
                        knowledgeBaseBuilderService.onAppBackgrounded();
                        LogManager.logD(TAG, "绑定后通知服务：应用在后台");
                    }
                }
                
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    LogManager.logD(TAG, "与知识库构建服务的连接已断开");
                    knowledgeBaseBuilderService = null;
                }
            };
        }
        
        Intent intent = new Intent(this, KnowledgeBaseBuilderService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    /**
     * 解绑知识库构建服务
     */
    private void unbindKnowledgeBaseBuilderService() {
        if (serviceConnection != null) {
            try {
                unbindService(serviceConnection);
                LogManager.logD(TAG, "已解绑知识库构建服务");
            } catch (IllegalArgumentException e) {
                LogManager.logE(TAG, "解绑服务失败：" + e.getMessage());
            }
            knowledgeBaseBuilderService = null;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogManager.logD(TAG, "MainActivity.onDestroy()");
        
        // 解绑知识库构建服务
        unbindKnowledgeBaseBuilderService();
    }
    
    /**
     * 执行GPU配置检查
     */
    private void performGPUConfigCheck() {
        // 检测是否为华为设备
        boolean isHuawei = Build.MANUFACTURER.toLowerCase().contains("huawei") || 
                           Build.BRAND.toLowerCase().contains("huawei") ||
                           Build.BRAND.toLowerCase().contains("honor");
        
        if (isHuawei) {
            // 华为设备跳过GPU检查，避免启动卡顿
            LogManager.logI(TAG, "检测到华为设备，跳过GPU配置检查以避免启动卡顿");
            return;
        }
        
        // 在后台线程执行GPU配置检查，避免阻塞主线程
        new Thread(() -> {
            try {
                // 检查GPU配置是否有效
                boolean isConfigValid = GPUConfigChecker.isGPUConfigValid(this);
                
                if (isConfigValid) {
                    LogManager.logI(TAG, "GPU配置检查: 配置有效，支持GPU加速");
                } else {
                    LogManager.logW(TAG, "GPU配置检查: 配置可能存在问题，建议查看详细报告");
                    
                    // 生成详细的配置检查报告
                    String configReport = GPUConfigChecker.performConfigCheck(this);
                    LogManager.logI(TAG, "GPU配置详细报告:\n" + configReport);
                }
                
            } catch (Exception e) {
                LogManager.logE(TAG, "GPU配置检查失败: " + e.getMessage(), e);
            }
        }).start();
    }
    
    /**
     * 初始化ActivityResultLauncher
     */
    private void initializeActivityResultLaunchers() {
        // 管理存储权限的Launcher
        manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        // English log: persist granted status to avoid repeated prompts
                        ConfigManager.setBoolean(MainActivity.this, "has_storage_permission", true);
                        LogManager.logD(TAG, "Full file access permission obtained via launcher; persisted flag");
                        Toast.makeText(MainActivity.this, getString(R.string.toast_file_access_permission_granted), Toast.LENGTH_SHORT).show();
                    } else {
                        // English log: user denied full file access
                        LogManager.logW(TAG, "User denied MANAGE_EXTERNAL_STORAGE, app may not work properly");
                        Toast.makeText(MainActivity.this, getString(R.string.toast_file_access_permission_denied), Toast.LENGTH_LONG).show();
                    }
                }
            }
        );
        
        // 电池优化的Launcher
        batteryOptimizationLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    logManager.i(TAG, "电池优化已忽略");
                } else {
                    logManager.w(TAG, "用户未忽略电池优化");
                }
            }
        );
    }
    
    /**
     * 初始化返回按键处理
     */
    private void initializeOnBackPressedCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
             @Override
             public void handleOnBackPressed() {
                 if (findViewById(R.id.container).getVisibility() == View.VISIBLE) {
                     // 如果设置页面可见，返回时恢复ViewPager2
                     viewPager.setVisibility(View.VISIBLE);
                     findViewById(R.id.container).setVisibility(View.GONE);
                     getSupportFragmentManager().popBackStack();
                 } else {
                     // 否则执行默认的返回操作
                     finish();
                 }
             }
         });
    }
}