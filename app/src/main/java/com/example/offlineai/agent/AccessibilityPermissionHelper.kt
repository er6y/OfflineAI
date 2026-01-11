package com.example.offlineai.agent.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.example.offlineai.LogManager

/**
 * Accessibility Permission Helper - helps user enable accessibility service
 */
object AccessibilityPermissionHelper {
    
    private const val TAG = "AccessibilityPermissionHelper"
    
    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "${context.packageName}/com.example.offlineai.agent.service.AgentAccessibilityService"
        
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        val isEnabled = enabledServices?.contains(service) == true
        LogManager.logD(TAG, "Accessibility service enabled: $isEnabled")
        
        return isEnabled
    }
    
    /**
     * Open accessibility settings page
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LogManager.logI(TAG, "Opened accessibility settings")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to open accessibility settings: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Get user-friendly instructions for enabling accessibility service
     */
    fun getEnableInstructions(): String {
        return """
            为了使用Agent功能，需要开启无障碍服务：
            
            1. 点击下方按钮，跳转到无障碍设置
            2. 找到"离线AI"或"OfflineAI"
            3. 打开开关，授予权限
            4. 返回应用即可使用Agent功能
            
            注意：无障碍服务仅用于Agent自动操作，不会收集任何隐私数据。
        """.trimIndent()
    }
}
