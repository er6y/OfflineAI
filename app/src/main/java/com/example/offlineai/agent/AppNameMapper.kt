package com.example.offlineai.agent.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings

/**
 * App Name Mapper - unified app launch strategy manager
 * Priority 1: Intent Action (system apps, best compatibility)
 * Priority 2: Package name mapping (third-party apps)
 * Priority 3: Fuzzy matching (fallback)
 */
object AppNameMapper {
    
    /**
     * App launch strategy sealed class
     */
    sealed class LaunchStrategy {
        data class IntentAction(
            val action: String,
            val uri: Uri? = null,
            val type: String? = null
        ) : LaunchStrategy() {
            fun createIntent(): Intent {
                val intent = Intent(action)
                uri?.let { intent.data = it }
                type?.let { intent.type = it }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return intent
            }
        }
        
        data class PackageName(val packageName: String) : LaunchStrategy()
    }
    
    /**
     * Unified app launch strategy mappings
     * System apps use Intent Actions, third-party apps use package names
     */
    private val APP_LAUNCH_MAP = mapOf<String, LaunchStrategy>(
        // System Apps - Intent Actions (Priority 1)
        "dialer" to LaunchStrategy.IntentAction(Intent.ACTION_DIAL),
        "phone" to LaunchStrategy.IntentAction(Intent.ACTION_DIAL),
        "电话" to LaunchStrategy.IntentAction(Intent.ACTION_DIAL),
        "拨号" to LaunchStrategy.IntentAction(Intent.ACTION_DIAL),
        
        "contacts" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, type = ContactsContract.Contacts.CONTENT_TYPE),
        "联系人" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, type = ContactsContract.Contacts.CONTENT_TYPE),
        
        "camera" to LaunchStrategy.IntentAction(MediaStore.ACTION_IMAGE_CAPTURE),
        "相机" to LaunchStrategy.IntentAction(MediaStore.ACTION_IMAGE_CAPTURE),
        
        "gallery" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, type = "image/*"),
        "图库" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, type = "image/*"),
        
        "settings" to LaunchStrategy.IntentAction(Settings.ACTION_SETTINGS),
        "设置" to LaunchStrategy.IntentAction(Settings.ACTION_SETTINGS),
        
        "calendar" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("content://com.android.calendar/time/")),
        "日历" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("content://com.android.calendar/time/")),
        
        "clock" to LaunchStrategy.IntentAction(AlarmClock.ACTION_SHOW_ALARMS),
        "时钟" to LaunchStrategy.IntentAction(AlarmClock.ACTION_SHOW_ALARMS),
        
        "messages" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("sms:")),
        "sms" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("sms:")),
        "短信" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("sms:")),
        
        "maps" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("geo:0,0?q=")),
        "地图" to LaunchStrategy.IntentAction(Intent.ACTION_VIEW, uri = Uri.parse("geo:0,0?q="))
        
        // Note: "browser"/"浏览器" removed from Intent mappings
        // Will use fuzzy matching to find installed browser apps (Chrome, Edge, Firefox, etc.)
        // This avoids triggering app chooser dialog which blocks Agent automation
        
        // Third-party apps are now dynamically discovered via getAllInstalledAppNames()
        // No hardcoded package names needed - fuzzy matching will handle all installed apps
    )
    
    /**
     * Get launch strategy for app name (Priority 1 & 2)
     * Returns null if not found in predefined mappings
     */
    fun getLaunchStrategy(appName: String): LaunchStrategy? {
        // 1. Exact match (case-sensitive)
        APP_LAUNCH_MAP[appName]?.let { return it }
        
        // 2. Case-insensitive match
        return APP_LAUNCH_MAP.entries.find { 
            it.key.equals(appName, ignoreCase = true) 
        }?.value
    }
    
    /**
     * Get package name for app display name (for backward compatibility)
     * Priority 3: Fuzzy matching on installed apps
     */
    fun getPackageName(context: Context, appName: String): String? {
        // Try fuzzy match on installed apps (Priority 3)
        return fuzzyMatchInstalledApp(context, appName)
    }
    
    /**
     * Fuzzy match app name against installed applications
     */
    private fun fuzzyMatchInstalledApp(context: Context, appName: String): String? {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        // First try exact match
        installedApps.find { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.equals(appName, ignoreCase = true)
        }?.packageName?.let { return it }
        
        // Then try contains match
        installedApps.find { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.contains(appName, ignoreCase = true) || 
            appName.contains(label, ignoreCase = true)
        }?.packageName?.let { return it }
        
        return null
    }
    
    /**
     * Get app display name from package name
     */
    fun getAppName(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    /**
     * Check if app is installed
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Popular apps filter list (China-focused)
     * Only these apps will be included in system prompt to reduce token usage
     */
    private val POPULAR_APPS_FILTER = setOf(
        // Browsers
        "chrome", "edge", "firefox", "opera", "uc", "qq浏览器", "百度", "夸克", "via",
        "浏览器", "browser", "搜狗",
        
        // Social & Communication
        "微信", "wechat", "企业微信", "wecom", "钉钉", "dingtalk", "qq", "tim",
        "telegram", "whatsapp", "微博", "weibo", "小红书", "xhs", "抖音", "douyin",
        "tiktok", "快手", "kuaishou", "bilibili", "b站",
        
        // Shopping & Payment
        "支付宝", "alipay", "淘宝", "taobao", "天猫", "tmall", "京东", "jd", "jingdong",
        "拼多多", "pinduoduo", "美团", "meituan", "饿了么", "eleme", "盒马", "hema",
        "大众点评", "dianping", "闲鱼", "xianyu", "唯品会", "vipshop",
        "瑞幸咖啡", "luckin",
        
        // Maps & Travel
        "高德", "amap", "百度地图", "baidu map", "腾讯地图", "tencent map", "滴滴",
        "didi", "美团打车", "携程", "ctrip", "去哪儿", "qunar", "飞猪", "fliggy",
        "12306", "地图", "maps", "导航",
        
        // Delivery & Logistics
        "菜鸟", "cainiao", "顺丰", "sf", "韵达", "圆通", "中通", "申通",
        
        // News & Reading
        "今日头条", "toutiao", "腾讯新闻", "网易新闻", "搜狐", "知乎", "zhihu",
        "豆瓣", "douban", "得到", "微信读书", "kindle",
        
        // Entertainment
        "网易云音乐", "netease", "qq音乐", "酷狗", "kugou", "酷我", "kuwo",
        "爱奇艺", "iqiyi", "优酷", "youku", "腾讯视频", "芒果tv", "哔哩哔哩",
        
        // Tools & Productivity
        "wps", "office", "钉钉文档", "石墨", "印象笔记", "有道云笔记", "flomo",
        "番茄todo", "forest", "滴答清单", "ticktick", "日历", "calendar",
        "计算器", "calculator", "时钟", "clock", "天气", "weather",
        
        // System Apps
        "设置", "settings", "相机", "camera", "图库", "gallery", "photos",
        "电话", "phone", "dialer", "联系人", "contacts", "短信", "messages", "sms",
        "文件", "files", "下载", "downloads", "音乐", "music", "视频", "video"
    )
    
    /**
     * Check if app name matches popular filter
     * Strategy: Case-insensitive, one-way fuzzy matching (app name contains keyword)
     * Example: "微信" matches "微信", "企业微信", "微信读书"
     *          "chrome" matches "Chrome", "Google Chrome"
     */
    private fun matchesPopularFilter(appName: String): Boolean {
        val lowerName = appName.lowercase()
        return POPULAR_APPS_FILTER.any { filter ->
            // One-way match: app name contains filter keyword
            lowerName.contains(filter.lowercase())
        }
    }
    
    /**
     * Get all launchable app names (apps with LAUNCHER intent)
     * Returns list of (appName, packageName) pairs
     * Filtered to only include popular apps to reduce system prompt size
     */
    fun getAllInstalledAppNames(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        return pm.queryIntentActivities(launchIntent, 0)
            .mapNotNull { resolveInfo ->
                try {
                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val packageName = appInfo.packageName
                    
                    // Filter: only include popular apps
                    if (matchesPopularFilter(label)) {
                        Pair(label, packageName)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.second } // Remove duplicates by package name
            .sortedBy { it.first }
            .take(100) // Limit to max 100 apps
    }
}
