package com.example.offlineai.agent.executor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Action Executor - executes agent actions using Accessibility Service
 * Based on MAI-UI action space
 */
class ActionExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "ActionExecutor"
        private const val ACTION_DELAY_MS = 1000L // Wait after each action for UI to stabilize
    }
    
    // Cached installed app list for validation
    private var installedAppList: List<Pair<String, String>>? = null
    
    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.getInstance()
    
    private val screenWidth: Int
    private val screenHeight: Int
    private var isLoadingAppList = false
    
    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        LogManager.logI(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
        
        // Lazy load app list in background to avoid blocking main thread on startup
        CoroutineScope(Dispatchers.IO).launch {
            loadInstalledAppList()
        }
    }
    
    /**
     * Load installed app list asynchronously
     */
    private suspend fun loadInstalledAppList() {
        if (isLoadingAppList || installedAppList != null) return
        
        isLoadingAppList = true
        try {
            val apps = withContext(Dispatchers.IO) {
                AppNameMapper.getAllInstalledAppNames(context)
            }
            installedAppList = apps
            LogManager.logI(TAG, "Loaded ${apps.size} installed apps for validation")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to load app list: ${e.message}")
        } finally {
            isLoadingAppList = false
        }
    }
    
    /**
     * Execute an agent action
     */
    suspend fun execute(action: AgentAction): ExecutionResult {
        if (accessibilityService == null) {
            return ExecutionResult(
                success = false,
                message = "Accessibility service not available. Please enable it in Settings."
            )
        }
        
        LogManager.logI(TAG, "Executing action: ${action.javaClass.simpleName}")
        
        // Hide floating window before screen interaction actions
        val needsHideFloatingWindow = when (action) {
            is AgentAction.Click, is AgentAction.LongPress, is AgentAction.DoubleClick,
            is AgentAction.Type, is AgentAction.Swipe, is AgentAction.Drag,
            is AgentAction.SystemButton -> true
            else -> false
        }
        
        if (needsHideFloatingWindow) {
            accessibilityService?.floatingWindow?.temporaryHide()
            delay(50) // Brief delay to ensure window is hidden
        }
        
        val result = try {
            when (action) {
                is AgentAction.Click -> executeClick(action)
                is AgentAction.LongPress -> executeLongPress(action)
                is AgentAction.DoubleClick -> executeDoubleClick(action)
                is AgentAction.Type -> executeType(action)
                is AgentAction.Swipe -> executeSwipe(action)
                is AgentAction.Open -> executeOpen(action)
                is AgentAction.Drag -> executeDrag(action)
                is AgentAction.SystemButton -> executeSystemButton(action)
                is AgentAction.Wait -> executeWait()
                is AgentAction.Terminate -> executeTerminate(action)
                is AgentAction.Answer -> executeAnswer(action)
                is AgentAction.AskUser -> executeAskUser(action)
            }
        } finally {
            // Restore floating window after screen interaction
            if (needsHideFloatingWindow) {
                accessibilityService?.floatingWindow?.temporaryShow()
            }
        }
        
        // Wait for UI to stabilize after action
        if (result.success && action !is AgentAction.Wait) {
            delay(ACTION_DELAY_MS)
        }
        
        return result
    }
    
    private fun executeClick(action: AgentAction.Click): ExecutionResult {
        val (screenX, screenY) = normalizedToPixel(action.x, action.y)
        LogManager.logI(
            TAG,
            "[EXECUTE_CLICK] Normalized: [${action.x}, ${action.y}] -> Pixel: ($screenX, $screenY) [Screen: ${screenWidth}x${screenHeight}]"
        )
        val success = accessibilityService?.clickAtPosition(screenX, screenY) ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Clicked at ($screenX, $screenY)" else "Failed to click"
        )
    }
    
    private fun executeLongPress(action: AgentAction.LongPress): ExecutionResult {
        val (screenX, screenY) = normalizedToPixel(action.x, action.y)
        val success = accessibilityService?.longPressAtPosition(screenX, screenY) ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Long pressed at ($screenX, $screenY)" else "Failed to long press"
        )
    }
    
    private fun executeDoubleClick(action: AgentAction.DoubleClick): ExecutionResult {
        val (screenX, screenY) = normalizedToPixel(action.x, action.y)
        val success = accessibilityService?.doubleClickAtPosition(screenX, screenY) ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Double clicked at ($screenX, $screenY)" else "Failed to double click"
        )
    }
    
    private fun executeType(action: AgentAction.Type): ExecutionResult {
        val errorReason = accessibilityService?.inputTextWithReason(action.text)
        val success = (errorReason == null)
        
        return ExecutionResult(
            success = success,
            message = if (success) "Typed: ${action.text}" else "Failed to type text: $errorReason"
        )
    }
    
    private fun executeSwipe(action: AgentAction.Swipe): ExecutionResult {
        val (startX, startY, endX, endY) = calculateSwipeCoordinates(action)
        val success = accessibilityService?.swipe(startX, startY, endX, endY) ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Swiped ${action.direction.value}" else "Failed to swipe"
        )
    }
    
    private fun executeOpen(action: AgentAction.Open): ExecutionResult {
        LogManager.logI(TAG, "Opening app: ${action.appName}")
        
        // Step 1: Validate app exists in installed app list
        // Ensure app list is loaded (will wait if still loading)
        if (installedAppList == null) {
            LogManager.logW(TAG, "App list not loaded yet, loading synchronously...")
            runBlocking {
                loadInstalledAppList()
            }
        }
        
        val appList = installedAppList ?: run {
            LogManager.logE(TAG, "Failed to load app list, cannot validate app name")
            return ExecutionResult(
                success = false,
                message = "Failed to load installed app list"
            )
        }
        
        // Check if app name exists in the list (EXACT match only, case-insensitive)
        val matchedApp = appList.find { (appName, _) ->
            appName.equals(action.appName, ignoreCase = true)
        }
        
        if (matchedApp == null) {
            // App not found in installed list
            LogManager.logW(TAG, "App '${action.appName}' not found in installed app list (exact match required)")
            return ExecutionResult(
                success = false,
                message = "ERROR: App '${action.appName}' not found in installed app list. You MUST use the EXACT app name from the provided app list. Please check the app list in system prompt and use the precise name (case-insensitive). Do NOT use partial names or abbreviations."
            )
        }
        
        LogManager.logI(TAG, "App validated: '${action.appName}' -> '${matchedApp.first}' (${matchedApp.second})")
        
        // Step 2: Priority 1 & 2: Check predefined launch strategy
        val strategy = AppNameMapper.getLaunchStrategy(action.appName)
        
        if (strategy != null) {
            return when (strategy) {
                is AppNameMapper.LaunchStrategy.IntentAction -> {
                    // Priority 1: Use Intent Action (system apps)
                    try {
                        val intent = strategy.createIntent()
                        context.startActivity(intent)
                        LogManager.logI(TAG, "Opened via Intent Action: ${action.appName}")
                        ExecutionResult(
                            success = true,
                            message = "App launched"
                        )
                    } catch (e: Exception) {
                        LogManager.logE(TAG, "Failed to open via Intent: ${action.appName}", e)
                        ExecutionResult(
                            success = false,
                            message = "Failed to launch app. Try navigating home screen to find and click the app icon.",
                            error = e
                        )
                    }
                }
                is AppNameMapper.LaunchStrategy.PackageName -> {
                    // Priority 2: Use package name (third-party apps)
                    openViaPackageName(action.appName, strategy.packageName)
                }
            }
        }
        
        // Step 3: Use matched app's package name
        return openViaPackageName(matchedApp.first, matchedApp.second)
    }
    
    /**
     * Open app via package name
     */
    private fun openViaPackageName(appName: String, packageName: String): ExecutionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                LogManager.logI(TAG, "Opened via package: $appName -> $packageName")
                ExecutionResult(
                    success = true,
                    message = "App launched"
                )
            } else {
                LogManager.logW(TAG, "Cannot launch app: $appName (no launch intent)")
                ExecutionResult(
                    success = false,
                    message = "Cannot launch app. Try navigating home screen to find and click the app icon."
                )
            }
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to open app: $appName", e)
            ExecutionResult(
                success = false,
                message = "Failed to launch app. Try navigating home screen to find and click the app icon.",
                error = e
            )
        }
    }
    
    private fun executeDrag(action: AgentAction.Drag): ExecutionResult {
        val (startX, startY) = normalizedToPixel(action.startX, action.startY)
        val (endX, endY) = normalizedToPixel(action.endX, action.endY)
        val success = accessibilityService?.drag(startX, startY, endX, endY) ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Dragged from ($startX, $startY) to ($endX, $endY)" else "Failed to drag"
        )
    }
    
    private fun executeSystemButton(action: AgentAction.SystemButton): ExecutionResult {
        val success = when (action.button) {
            AgentAction.SystemButton.Button.BACK -> accessibilityService?.pressBack()
            AgentAction.SystemButton.Button.HOME -> accessibilityService?.pressHome()
            AgentAction.SystemButton.Button.MENU -> accessibilityService?.pressRecents()
            AgentAction.SystemButton.Button.ENTER -> accessibilityService?.pressEnter()
        } ?: false
        
        return ExecutionResult(
            success = success,
            message = if (success) "Pressed ${action.button.value} button" else "Failed to press button"
        )
    }
    
    private suspend fun executeWait(): ExecutionResult {
        delay(2000) // Wait 2 seconds for UI to stabilize
        return ExecutionResult(
            success = true,
            message = "Waited for UI to stabilize"
        )
    }
    
    private fun executeTerminate(action: AgentAction.Terminate): ExecutionResult {
        return ExecutionResult(
            success = true,
            message = "Task terminated with status: ${action.status.value}"
        )
    }
    
    private fun executeAnswer(action: AgentAction.Answer): ExecutionResult {
        // Answer action is handled by the UI layer (display to user)
        return ExecutionResult(
            success = true,
            message = "Answer: ${action.text}"
        )
    }
    
    private fun executeAskUser(action: AgentAction.AskUser): ExecutionResult {
        // AskUser action is handled by the UI layer (prompt user for input)
        return ExecutionResult(
            success = true,
            message = "Ask user: ${action.text}"
        )
    }
    
    private fun clampPixel(x: Int, y: Int): Pair<Int, Int> {
        val screenX = x.coerceIn(0, screenWidth - 1)
        val screenY = y.coerceIn(0, screenHeight - 1)

        if (screenX != x || screenY != y) {
            LogManager.logW(TAG, "Pixel coordinate out of bounds: ($x,$y) clamped to ($screenX,$screenY) [Screen: ${screenWidth}x${screenHeight}]")
        }

        return Pair(screenX, screenY)
    }

    private fun normalizedToPixel(xNorm: Int, yNorm: Int): Pair<Int, Int> {
        val x = ((xNorm / 999f) * (screenWidth - 1)).roundToInt()
        val y = ((yNorm / 999f) * (screenHeight - 1)).roundToInt()

        val (screenX, screenY) = clampPixel(x, y)
        if (screenX != x || screenY != y) {
            LogManager.logW(
                TAG,
                "Normalized->Pixel produced out-of-bounds pixel: norm=($xNorm,$yNorm) rawPixel=($x,$y) clampedPixel=($screenX,$screenY) [Screen: ${screenWidth}x${screenHeight}]"
            )
        }
        return Pair(screenX, screenY)
    }
    
    /**
     * Calculate swipe coordinates based on direction
     */
    private fun calculateSwipeCoordinates(action: AgentAction.Swipe): SwipeCoordinates {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val swipeDistance = screenHeight / 3 // Swipe 1/3 of screen height/width
        
        // If specific coordinate provided, use it as center point
        val (startCenterX, startCenterY) = if (action.x != null && action.y != null) {
            normalizedToPixel(action.x, action.y)
        } else {
            Pair(centerX, centerY)
        }
        
        // Direction semantics: "scroll/swipe down" = page content moves down = finger swipes UP
        // "scroll/swipe up" = page content moves up = finger swipes DOWN
        return when (action.direction) {
            AgentAction.Swipe.Direction.UP -> SwipeCoordinates(
                startCenterX, startCenterY - swipeDistance / 2,
                startCenterX, startCenterY + swipeDistance / 2
            )
            AgentAction.Swipe.Direction.DOWN -> SwipeCoordinates(
                startCenterX, startCenterY + swipeDistance / 2,
                startCenterX, startCenterY - swipeDistance / 2
            )
            AgentAction.Swipe.Direction.LEFT -> SwipeCoordinates(
                startCenterX + swipeDistance / 2, startCenterY,
                startCenterX - swipeDistance / 2, startCenterY
            )
            AgentAction.Swipe.Direction.RIGHT -> SwipeCoordinates(
                startCenterX - swipeDistance / 2, startCenterY,
                startCenterX + swipeDistance / 2, startCenterY
            )
        }
    }
    
    private data class SwipeCoordinates(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )
}
