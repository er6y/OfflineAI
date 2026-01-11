package com.example.offlineai.agent

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.offlineai.LogManager
import com.example.offlineai.agent.executor.ActionExecutor as LegacyActionExecutor
import com.example.offlineai.agent.service.AgentAccessibilityService
import com.example.offlineai.agent.utils.AppNameMapper
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/**
 * Unified Action Executor - supports multiple action formats (MAI-UI, AutoGLM)
 * Executes actions using Accessibility Service
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
    
    private val screenWidth: Int
    private val screenHeight: Int
    
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
     * Execute an action
     */
    suspend fun execute(action: Action): ActionResult {
        if (accessibilityService == null) {
            return ActionResult(
                success = false,
                shouldFinish = false,
                message = "Accessibility service not available"
            )
        }
        
        LogManager.logI(TAG, "Executing action: ${action.type}")
        
        // Hide floating window for screen interactions
        val needsHideFloatingWindow = when (action.type) {
            ActionType.CLICK, ActionType.LONG_PRESS, ActionType.DOUBLE_CLICK,
            ActionType.TYPE, ActionType.SWIPE, ActionType.DRAG,
            ActionType.SYSTEM_BUTTON -> true
            else -> false
        }
        
        if (needsHideFloatingWindow) {
            accessibilityService?.floatingWindow?.temporaryHide()
            delay(50)
        }
        
        val result = try {
            when (action.type) {
                ActionType.CLICK -> executeClick(action)
                ActionType.LONG_PRESS -> executeLongPress(action)
                ActionType.DOUBLE_CLICK -> executeDoubleClick(action)
                ActionType.TYPE -> executeType(action)
                ActionType.SWIPE -> executeSwipe(action)
                ActionType.DRAG -> executeDrag(action)
                ActionType.OPEN -> executeOpen(action)
                ActionType.SYSTEM_BUTTON -> executeSystemButton(action)
                ActionType.WAIT -> executeWait(action)
                ActionType.TERMINATE -> executeTerminate(action)
                ActionType.ANSWER -> executeAnswer(action)
                ActionType.TAKE_OVER -> executeTakeOver(action)
                ActionType.CONFIRM -> executeConfirm(action)
                ActionType.NOTE -> executeNote(action)
                ActionType.CALL_API -> executeCallApi(action)
                ActionType.INTERACT -> executeInteract(action)
            }
        } finally {
            if (needsHideFloatingWindow) {
                accessibilityService?.floatingWindow?.temporaryShow()
            }
        }
        
        if (result.success && action.type != ActionType.WAIT) {
            delay(ACTION_DELAY_MS)
        }
        
        return result
    }
    
    private fun executeClick(action: Action): ActionResult {
        val coord = action.coordinate ?: return ActionResult(false, false, "No coordinate")
        val (screenX, screenY) = normalizedToPixel(coord[0], coord[1])
        
        LogManager.logI(TAG, "[CLICK] Normalized: [${coord[0]}, ${coord[1]}] -> Pixel: ($screenX, $screenY)")
        
        val success = accessibilityService?.clickAtPosition(screenX, screenY) ?: false
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Clicked at ($screenX, $screenY)" else "Failed to click"
        )
    }
    
    private fun executeLongPress(action: Action): ActionResult {
        val coord = action.coordinate ?: return ActionResult(false, false, "No coordinate")
        val (screenX, screenY) = normalizedToPixel(coord[0], coord[1])
        
        val success = accessibilityService?.longPressAtPosition(screenX, screenY) ?: false
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Long pressed at ($screenX, $screenY)" else "Failed to long press"
        )
    }
    
    private fun executeDoubleClick(action: Action): ActionResult {
        val coord = action.coordinate ?: return ActionResult(false, false, "No coordinate")
        val (screenX, screenY) = normalizedToPixel(coord[0], coord[1])
        
        val success = accessibilityService?.doubleClickAtPosition(screenX, screenY) ?: false
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Double clicked at ($screenX, $screenY)" else "Failed to double click"
        )
    }
    
    private fun executeType(action: Action): ActionResult {
        val text = action.text ?: return ActionResult(false, false, "No text")
        val errorReason = accessibilityService?.inputTextWithReason(text)
        val success = (errorReason == null)
        
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Typed: $text" else "Failed to type: $errorReason"
        )
    }
    
    private fun executeSwipe(action: Action): ActionResult {
        val direction = action.direction ?: return ActionResult(false, false, "No direction")
        val (startX, startY, endX, endY) = calculateSwipeCoordinates(direction, action.coordinate)
        
        val success = accessibilityService?.swipe(startX, startY, endX, endY) ?: false
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Swiped $direction" else "Failed to swipe"
        )
    }
    
    private fun executeDrag(action: Action): ActionResult {
        val start = action.startCoordinate ?: return ActionResult(false, false, "No start coordinate")
        val end = action.endCoordinate ?: return ActionResult(false, false, "No end coordinate")
        
        val (startX, startY) = normalizedToPixel(start[0], start[1])
        val (endX, endY) = normalizedToPixel(end[0], end[1])
        
        val success = accessibilityService?.drag(startX, startY, endX, endY) ?: false
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Dragged from ($startX, $startY) to ($endX, $endY)" else "Failed to drag"
        )
    }
    
    private fun executeOpen(action: Action): ActionResult {
        val appName = action.text ?: return ActionResult(false, false, "No app name")
        
        LogManager.logI(TAG, "Opening app: $appName")
        
        // Ensure app list is loaded
        if (installedAppList == null) {
            runBlocking { loadInstalledAppList() }
        }
        
        val appList = installedAppList ?: return ActionResult(
            false, false, "Failed to load app list"
        )
        
        // Find exact match (case-insensitive)
        val matchedApp = appList.find { (name, _) ->
            name.equals(appName, ignoreCase = true)
        }
        
        if (matchedApp == null) {
            return ActionResult(
                false, false,
                "App '$appName' not found. Use exact name from app list."
            )
        }
        
        // Try launch strategy first
        val strategy = AppNameMapper.getLaunchStrategy(appName)
        if (strategy != null) {
            return when (strategy) {
                is AppNameMapper.LaunchStrategy.IntentAction -> {
                    try {
                        context.startActivity(strategy.createIntent())
                        ActionResult(true, false, "App launched")
                    } catch (e: Exception) {
                        ActionResult(false, false, "Failed to launch: ${e.message}")
                    }
                }
                is AppNameMapper.LaunchStrategy.PackageName -> {
                    openViaPackage(matchedApp.second)
                }
            }
        }
        
        return openViaPackage(matchedApp.second)
    }
    
    private fun openViaPackage(packageName: String): ActionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult(true, false, "App launched")
            } else {
                ActionResult(false, false, "No launch intent")
            }
        } catch (e: Exception) {
            ActionResult(false, false, "Failed to launch: ${e.message}")
        }
    }
    
    private fun executeSystemButton(action: Action): ActionResult {
        val button = action.button ?: return ActionResult(false, false, "No button")
        
        val success = when (button.lowercase()) {
            "back" -> accessibilityService?.pressBack()
            "home" -> accessibilityService?.pressHome()
            "menu" -> accessibilityService?.pressRecents()
            "enter" -> accessibilityService?.pressEnter()
            else -> false
        } ?: false
        
        return ActionResult(
            success = success,
            shouldFinish = false,
            message = if (success) "Pressed $button" else "Failed to press button"
        )
    }
    
    private suspend fun executeWait(action: Action): ActionResult {
        val duration = (action.duration ?: 1) * 1000L
        delay(duration)
        return ActionResult(true, false, "Waited ${action.duration}s")
    }
    
    private fun executeTerminate(action: Action): ActionResult {
        return ActionResult(
            success = true,
            shouldFinish = true,
            message = "Task ${action.status}: ${action.text}"
        )
    }
    
    private fun executeAnswer(action: Action): ActionResult {
        return ActionResult(
            success = true,
            shouldFinish = false,
            message = "Answer: ${action.text}"
        )
    }
    
    private fun executeTakeOver(action: Action): ActionResult {
        return ActionResult(
            success = true,
            shouldFinish = false,
            message = "User intervention required: ${action.text}",
            requiresConfirmation = true
        )
    }
    
    private fun executeConfirm(action: Action): ActionResult {
        // Sensitive operation - requires user confirmation
        return ActionResult(
            success = true,
            shouldFinish = false,
            message = "Confirm sensitive operation: ${action.text}",
            requiresConfirmation = true
        )
    }
    
    private fun executeNote(@Suppress("UNUSED_PARAMETER") action: Action): ActionResult {
        // Placeholder for content recording
        return ActionResult(true, false, "Note recorded")
    }
    
    private fun executeCallApi(@Suppress("UNUSED_PARAMETER") action: Action): ActionResult {
        // Placeholder for API call/summarization
        return ActionResult(true, false, "API called")
    }
    
    private fun executeInteract(@Suppress("UNUSED_PARAMETER") action: Action): ActionResult {
        return ActionResult(
            success = true,
            shouldFinish = false,
            message = "User interaction required",
            requiresConfirmation = true
        )
    }
    
    private fun normalizedToPixel(xNorm: Int, yNorm: Int): Pair<Int, Int> {
        val x = ((xNorm / 999f) * (screenWidth - 1)).roundToInt()
        val y = ((yNorm / 999f) * (screenHeight - 1)).roundToInt()
        
        val screenX = x.coerceIn(0, screenWidth - 1)
        val screenY = y.coerceIn(0, screenHeight - 1)
        
        return Pair(screenX, screenY)
    }
    
    private fun calculateSwipeCoordinates(
        direction: String,
        coordinate: IntArray?
    ): SwipeCoordinates {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val swipeDistance = screenHeight / 3
        
        val (startCenterX, startCenterY) = if (coordinate != null && coordinate.size >= 2) {
            normalizedToPixel(coordinate[0], coordinate[1])
        } else {
            Pair(centerX, centerY)
        }
        
        // Direction semantics: "scroll/swipe down" = page content moves down = finger swipes UP
        // "scroll/swipe up" = page content moves up = finger swipes DOWN
        return when (direction.lowercase()) {
            "up" -> SwipeCoordinates(
                startCenterX, startCenterY - swipeDistance / 2,
                startCenterX, startCenterY + swipeDistance / 2
            )
            "down" -> SwipeCoordinates(
                startCenterX, startCenterY + swipeDistance / 2,
                startCenterX, startCenterY - swipeDistance / 2
            )
            "left" -> SwipeCoordinates(
                startCenterX + swipeDistance / 2, startCenterY,
                startCenterX - swipeDistance / 2, startCenterY
            )
            "right" -> SwipeCoordinates(
                startCenterX - swipeDistance / 2, startCenterY,
                startCenterX + swipeDistance / 2, startCenterY
            )
            else -> SwipeCoordinates(centerX, centerY, centerX, centerY)
        }
    }
    
    private data class SwipeCoordinates(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )
}
