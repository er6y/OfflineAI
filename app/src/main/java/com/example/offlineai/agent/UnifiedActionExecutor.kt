package com.example.offlineai.agent

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.offlineai.LogManager
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.ExecutionResult
import com.example.offlineai.agent.service.AgentAccessibilityService
import com.example.offlineai.agent.utils.AppNameMapper
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/**
 * Unified Action Executor - executes AgentAction using Accessibility Service.
 * Single executor for all action formats (MAI-UI, AutoGLM, Doubao, OpenAI FuncCall).
 */
class UnifiedActionExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "UnifiedActionExecutor"
        private const val ACTION_DELAY_MS = 1000L
    }
    
    private var installedAppList: List<Pair<String, String>>? = null
    private var isLoadingAppList = false
    
    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.getInstance()
    
    val screenWidth: Int
    val screenHeight: Int
    
    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        LogManager.logI(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
        
        CoroutineScope(Dispatchers.IO).launch {
            loadInstalledAppList()
        }
    }
    
    private suspend fun loadInstalledAppList() {
        if (isLoadingAppList || installedAppList != null) return
        isLoadingAppList = true
        try {
            val apps = withContext(Dispatchers.IO) {
                AppNameMapper.getAllInstalledAppNames(context)
            }
            installedAppList = apps
            LogManager.logI(TAG, "Loaded ${apps.size} installed apps")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to load app list: ${e.message}")
        } finally {
            isLoadingAppList = false
        }
    }
    
    /**
     * Execute an AgentAction.
     */
    suspend fun execute(action: AgentAction): ExecutionResult {
        if (accessibilityService == null) {
            return ExecutionResult(false, "Accessibility service not available")
        }
        
        LogManager.logI(TAG, "Executing action: ${action.javaClass.simpleName}")
        
        // Hide floating window for screen interactions
        val needsHide = action is AgentAction.Click || action is AgentAction.LongPress ||
            action is AgentAction.DoubleClick || action is AgentAction.Type ||
            action is AgentAction.Swipe || action is AgentAction.Drag ||
            action is AgentAction.SystemButton
        
        if (needsHide) {
            accessibilityService?.floatingWindow?.temporaryHide()
            delay(50)
        }
        
        val result = try {
            when (action) {
                is AgentAction.Click -> executeClick(action.x, action.y)
                is AgentAction.LongPress -> executeLongPress(action.x, action.y)
                is AgentAction.DoubleClick -> executeDoubleClick(action.x, action.y)
                is AgentAction.Type -> executeType(action.text)
                is AgentAction.Swipe -> executeSwipe(action)
                is AgentAction.Drag -> executeDrag(action.startX, action.startY, action.endX, action.endY)
                is AgentAction.Open -> executeOpen(action.appName)
                is AgentAction.SystemButton -> executeSystemButton(action.button)
                is AgentAction.Wait -> { delay(1000); ExecutionResult(true, "Waited") }
                is AgentAction.Terminate -> ExecutionResult(true, "Task ${action.status.value}")
                is AgentAction.Context -> ExecutionResult(true, "Context updated")
                is AgentAction.AskUser -> ExecutionResult(true, "AskUser: ${action.text}")
                is AgentAction.GetAppList -> executeGetAppList()
                is AgentAction.KbInsert -> {
                    LogManager.logI(TAG, "[KB] kb_insert deferred to experience save flow")
                    ExecutionResult(true, "KB insert deferred")
                }
                is AgentAction.KbDelete -> {
                    LogManager.logI(TAG, "[KB] kb_delete deferred to experience save flow")
                    ExecutionResult(true, "KB delete deferred")
                }
                is AgentAction.WebOpen -> executeWebOpen(action.url)
                is AgentAction.WebGetContent -> executeWebGetContent()
                is AgentAction.WebExecuteJs -> executeWebExecuteJs(action.script)
                is AgentAction.DataMemory -> ExecutionResult(true, "data_memory handled by AgentEngine")
            }
        } finally {
            if (needsHide) {
                accessibilityService?.floatingWindow?.temporaryShow()
            }
        }
        
        if (result.success && action !is AgentAction.Wait) {
            delay(ACTION_DELAY_MS)
        }
        
        return result
    }
    
    private fun executeClick(xNorm: Int, yNorm: Int): ExecutionResult {
        val (sx, sy) = normalizedToPixel(xNorm, yNorm)
        LogManager.logI(TAG, "[CLICK] Normalized: [$xNorm, $yNorm] -> Pixel: ($sx, $sy)")
        val ok = accessibilityService?.clickAtPosition(sx, sy) ?: false
        return ExecutionResult(ok, if (ok) "Clicked ($sx,$sy)" else "Failed to click")
    }
    
    private fun executeLongPress(xNorm: Int, yNorm: Int): ExecutionResult {
        val (sx, sy) = normalizedToPixel(xNorm, yNorm)
        val ok = accessibilityService?.longPressAtPosition(sx, sy) ?: false
        return ExecutionResult(ok, if (ok) "Long pressed ($sx,$sy)" else "Failed to long press")
    }
    
    private fun executeDoubleClick(xNorm: Int, yNorm: Int): ExecutionResult {
        val (sx, sy) = normalizedToPixel(xNorm, yNorm)
        val ok = accessibilityService?.doubleClickAtPosition(sx, sy) ?: false
        return ExecutionResult(ok, if (ok) "Double clicked ($sx,$sy)" else "Failed to double click")
    }
    
    private fun executeType(text: String): ExecutionResult {
        val err = accessibilityService?.inputTextWithReason(text)
        return if (err == null) ExecutionResult(true, "Typed: $text")
        else ExecutionResult(false, "Failed to type: $err")
    }
    
    private fun executeSwipe(action: AgentAction.Swipe): ExecutionResult {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val dist = screenHeight / 5  // Reduced from 1/3 to 1/5 to avoid skipping content
        
        val (cx, cy) = if (action.x != null && action.y != null) {
            normalizedToPixel(action.x, action.y)
        } else {
            Pair(centerX, centerY)
        }
        
        val (sx, sy, ex, ey) = when (action.direction) {
            // UP: finger moves bottom→top (startY > endY), page scrolls up to show content below
            AgentAction.Swipe.Direction.UP -> intArrayOf(cx, cy + dist / 2, cx, cy - dist / 2)
            // DOWN: finger moves top→bottom (startY < endY), page scrolls down to show content above
            AgentAction.Swipe.Direction.DOWN -> intArrayOf(cx, cy - dist / 2, cx, cy + dist / 2)
            // LEFT: finger moves right→left, page scrolls left
            AgentAction.Swipe.Direction.LEFT -> intArrayOf(cx + dist / 2, cy, cx - dist / 2, cy)
            // RIGHT: finger moves left→right, page scrolls right
            AgentAction.Swipe.Direction.RIGHT -> intArrayOf(cx - dist / 2, cy, cx + dist / 2, cy)
        }
        
        val ok = accessibilityService?.swipe(sx, sy, ex, ey) ?: false
        return ExecutionResult(ok, if (ok) "Swiped ${action.direction}" else "Failed to swipe")
    }
    
    private fun executeDrag(sx: Int, sy: Int, ex: Int, ey: Int): ExecutionResult {
        val (psx, psy) = normalizedToPixel(sx, sy)
        val (pex, pey) = normalizedToPixel(ex, ey)
        val ok = accessibilityService?.drag(psx, psy, pex, pey) ?: false
        return ExecutionResult(ok, if (ok) "Dragged ($psx,$psy)->($pex,$pey)" else "Failed to drag")
    }
    
    private fun executeOpen(appName: String): ExecutionResult {
        LogManager.logI(TAG, "Opening app: $appName")
        if (installedAppList == null) runBlocking { loadInstalledAppList() }
        
        val appList = installedAppList ?: return ExecutionResult(false, "Failed to load app list")
        val matched = appList.find { it.first.equals(appName, ignoreCase = true) }
            ?: return ExecutionResult(false, "App '$appName' not found")
        
        val strategy = AppNameMapper.getLaunchStrategy(appName)
        if (strategy != null) {
            return when (strategy) {
                is AppNameMapper.LaunchStrategy.IntentAction -> {
                    try { context.startActivity(strategy.createIntent()); ExecutionResult(true, "App launched") }
                    catch (e: Exception) { ExecutionResult(false, "Failed: ${e.message}") }
                }
                is AppNameMapper.LaunchStrategy.PackageName -> openViaPackage(matched.second)
            }
        }
        return openViaPackage(matched.second)
    }
    
    private fun openViaPackage(pkg: String): ExecutionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ExecutionResult(true, "App launched")
            } else ExecutionResult(false, "No launch intent")
        } catch (e: Exception) { ExecutionResult(false, "Failed: ${e.message}") }
    }
    
    private fun executeSystemButton(button: AgentAction.SystemButton.Button): ExecutionResult {
        val ok = when (button) {
            AgentAction.SystemButton.Button.BACK -> accessibilityService?.pressBack()
            AgentAction.SystemButton.Button.HOME -> accessibilityService?.pressHome()
            AgentAction.SystemButton.Button.MENU -> accessibilityService?.pressRecents()
            AgentAction.SystemButton.Button.ENTER -> accessibilityService?.pressEnter()
        } ?: false
        return ExecutionResult(ok, if (ok) "Pressed $button" else "Failed to press $button")
    }
    
    private suspend fun executeGetAppList(): ExecutionResult {
        if (installedAppList == null) loadInstalledAppList()
        val apps = installedAppList ?: return ExecutionResult(false, "Failed to load app list")
        val names = apps.map { it.first }
        val json = names.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
        LogManager.logI(TAG, "[GET_APP_LIST] Returning ${names.size} apps")
        return ExecutionResult(true, "App list: ${names.size} apps", returnData = json)
    }
    
    fun normalizedToPixel(xNorm: Int, yNorm: Int): Pair<Int, Int> {
        val x = ((xNorm / 999f) * (screenWidth - 1)).roundToInt().coerceIn(0, screenWidth - 1)
        val y = ((yNorm / 999f) * (screenHeight - 1)).roundToInt().coerceIn(0, screenHeight - 1)
        return Pair(x, y)
    }
    
    // ============================================================================
    // Web Operations (using Agent-dedicated background WebView)
    // ============================================================================

    private fun getAgentWebView(): AgentWebView? = accessibilityService?.agentWebView

    private suspend fun executeWebOpen(url: String): ExecutionResult {
        LogManager.logI(TAG, "[WEB_OPEN] Opening URL: $url")
        val webView = getAgentWebView()
            ?: return ExecutionResult(false, "AgentWebView not available (accessibility service not running)")
        val ok = webView.loadUrl(url)
        return if (ok) {
            ExecutionResult(true, "Opened URL: $url (page loaded)")
        } else {
            ExecutionResult(false, "Failed to load URL: $url (timeout or network error)")
        }
    }

    private suspend fun executeWebGetContent(): ExecutionResult {
        LogManager.logI(TAG, "[WEB_GET_CONTENT] Extracting page content")
        val webView = getAgentWebView()
            ?: return ExecutionResult(false, "AgentWebView not available (accessibility service not running)")
        val content = webView.getContent()
        return if (content != null) {
            LogManager.logI(TAG, "[WEB_GET_CONTENT] Got ${content.length} chars")
            ExecutionResult(true, "Page content extracted (${content.length} chars)", returnData = content)
        } else {
            ExecutionResult(false, "Failed to extract page content (timeout)")
        }
    }

    private suspend fun executeWebExecuteJs(script: String): ExecutionResult {
        LogManager.logI(TAG, "[WEB_EXECUTE_JS] Executing: ${script.take(100)}...")
        val webView = getAgentWebView()
            ?: return ExecutionResult(false, "AgentWebView not available (accessibility service not running)")
        val result = webView.executeJs(script)
        return if (result != null) {
            ExecutionResult(true, "JS executed, result: ${result.take(200)}", returnData = result)
        } else {
            ExecutionResult(false, "JS execution timeout")
        }
    }
}
