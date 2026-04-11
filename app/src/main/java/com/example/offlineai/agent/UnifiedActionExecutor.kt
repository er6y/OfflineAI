package com.example.offlineai.agent

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.offlineai.LogManager
import com.example.offlineai.ConfigManager
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.ExecutionResult
import com.example.offlineai.agent.service.AgentAccessibilityService
import com.example.offlineai.agent.utils.AppNameMapper
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import com.chaquo.python.Python

/**
 * Unified Action Executor - executes AgentAction using Accessibility Service.
 * Single executor for all action formats (MAI-UI, AutoGLM, Doubao, OpenAI FuncCall).
 */
class UnifiedActionExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "UnifiedActionExecutor"
        private const val ACTION_DELAY_MS = 1000L
    }

    private fun executeCreateFile(path: String, content: String): ExecutionResult {
        LogManager.logI(TAG, "[CREATE_FILE] Creating $path with content length=${content.length}")
        val file = File(path)
        if (file.exists()) {
            return ExecutionResult(false, "File already exists: $path. Use write_file to overwrite.")
        }

        // Ensure parent directories exist
        file.parentFile?.mkdirs()

        return try {
            file.writeText(content)
            val lineCount = content.count { it == '\n' } + if (content.isNotEmpty()) 1 else 0
            ExecutionResult(
                true,
                "Created $path ($lineCount lines)",
                returnData = "{\"path\":\"$path\",\"line_count\":$lineCount}"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[CREATE_FILE] Failed: $path", e)
            ExecutionResult(false, "Create file failed: ${e.message}")
        }
    }

    private fun executeReadFile(path: String): ExecutionResult {
        LogManager.logI(TAG, "[READ_FILE] Reading entire file: $path")
        val file = File(path)
        if (!file.exists()) {
            return ExecutionResult(false, "File not found: $path")
        }
        if (!file.isFile) {
            return ExecutionResult(false, "Path is not a file: $path")
        }

        return try {
            val lines = file.readLines()
            val totalLines = lines.size
            val maxLines = 2000
            val truncated = totalLines > maxLines
            val outputLines = if (truncated) lines.take(maxLines) else lines
            val contentStr = outputLines.mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n")
            val suffix = if (truncated) "\n[TRUNCATED: showing $maxLines of $totalLines lines. Use read_lines for specific ranges.]" else ""
            ExecutionResult(
                true,
                "Read $path ($totalLines lines${if (truncated) ", truncated to $maxLines" else ""})",
                returnData = "Total lines: $totalLines\n$contentStr$suffix"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[READ_FILE] Failed: $path", e)
            ExecutionResult(false, "Read file failed: ${e.message}")
        }
    }

    private fun executeWriteFile(path: String, content: String): ExecutionResult {
        LogManager.logI(TAG, "[WRITE_FILE] Writing $path with content length=${content.length}")
        val file = File(path)

        // Ensure parent directories exist
        file.parentFile?.mkdirs()

        return try {
            file.writeText(content)
            val lineCount = content.count { it == '\n' } + if (content.isNotEmpty()) 1 else 0
            ExecutionResult(
                true,
                "Written $path ($lineCount lines)",
                returnData = "{\"path\":\"$path\",\"line_count\":$lineCount}"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[WRITE_FILE] Failed: $path", e)
            ExecutionResult(false, "Write file failed: ${e.message}")
        }
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

        validateActionParameters(action)?.let { errorMsg ->
            LogManager.logW(TAG, "[ACTION_VALIDATION] Rejected ${action.javaClass.simpleName}: $errorMsg")
            return ExecutionResult(false, errorMsg)
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
                is AgentAction.CreateFile -> executeCreateFile(action.path, action.content)
                is AgentAction.ReadFile -> executeReadFile(action.path)
                is AgentAction.WriteFile -> executeWriteFile(action.path, action.content)
                is AgentAction.ReadLines -> executeReadLines(action.path, action.startLine, action.endLine)
                is AgentAction.EditLines -> executeEditLines(action.path, action.startLine, action.endLine, action.content)
                is AgentAction.Grep -> executeGrep(action.path, action.keyword)
                is AgentAction.RenameFile -> executeRenameFile(action.oldPath, action.newPath)
                is AgentAction.DeleteFile -> executeDeleteFile(action.path, action.recursive)
                is AgentAction.CopyFile -> executeCopyFile(action.src, action.dst)
                is AgentAction.ListDir -> executeListDir(action.path)
                is AgentAction.Mkdir -> executeMkdir(action.path)
                is AgentAction.SearchFiles -> executeSearchFiles(action.path, action.keyword)
                is AgentAction.PythonRun -> executePythonRun(action)
                is AgentAction.PythonStatus -> executePythonStatus(action)
                is AgentAction.PythonKill -> executePythonKill(action)
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

    private fun validateNormalizedCoordinate(value: Int, name: String): String? {
        return if (value in 0..999) null else "$name must be in [0..999], got $value"
    }

    private fun validateActionParameters(action: AgentAction): String? {
        fun requireNonBlank(value: String, field: String): String? {
            return if (value.isBlank()) "$field must not be blank" else null
        }

        return when (action) {
            is AgentAction.Click -> {
                validateNormalizedCoordinate(action.x, "x") ?: validateNormalizedCoordinate(action.y, "y")
            }
            is AgentAction.LongPress -> {
                validateNormalizedCoordinate(action.x, "x") ?: validateNormalizedCoordinate(action.y, "y")
            }
            is AgentAction.DoubleClick -> {
                validateNormalizedCoordinate(action.x, "x") ?: validateNormalizedCoordinate(action.y, "y")
            }
            is AgentAction.Swipe -> {
                if ((action.x == null) != (action.y == null)) {
                    "swipe coordinate must provide both x and y or neither"
                } else {
                    action.x?.let { validateNormalizedCoordinate(it, "x") }
                        ?: action.y?.let { validateNormalizedCoordinate(it, "y") }
                }
            }
            is AgentAction.Drag -> {
                validateNormalizedCoordinate(action.startX, "startX")
                    ?: validateNormalizedCoordinate(action.startY, "startY")
                    ?: validateNormalizedCoordinate(action.endX, "endX")
                    ?: validateNormalizedCoordinate(action.endY, "endY")
            }
            is AgentAction.Type -> requireNonBlank(action.text, "type.text")
            is AgentAction.Open -> requireNonBlank(action.appName, "open.text")
            is AgentAction.AskUser -> requireNonBlank(action.text, "ask_user.text")
            is AgentAction.WebOpen -> {
                val url = action.url.trim()
                if (url.isEmpty()) {
                    "web_open.url must not be blank"
                } else if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    "web_open.url must start with http:// or https://"
                } else {
                    null
                }
            }
            is AgentAction.WebExecuteJs -> requireNonBlank(action.script, "web_execute_js.script")
            is AgentAction.CreateFile -> requireNonBlank(action.path, "create_file.path")
            is AgentAction.ReadFile -> requireNonBlank(action.path, "read_file.path")
            is AgentAction.WriteFile -> {
                requireNonBlank(action.path, "write_file.path")
            }
            is AgentAction.ReadLines -> {
                requireNonBlank(action.path, "read_lines.path")
                    ?: if (action.startLine < 1) "read_lines.start_line must be >= 1" else null
                    ?: if (action.endLine < action.startLine) "read_lines.end_line must be >= start_line" else null
            }
            is AgentAction.EditLines -> {
                requireNonBlank(action.path, "edit_lines.path")
                    ?: if (action.startLine < 1) "edit_lines.start_line must be >= 1" else null
                    ?: if (action.endLine < action.startLine) "edit_lines.end_line must be >= start_line" else null
            }
            is AgentAction.Grep -> {
                requireNonBlank(action.path, "grep.path")
                    ?: requireNonBlank(action.keyword, "grep.keyword")
            }
            is AgentAction.RenameFile -> {
                requireNonBlank(action.oldPath, "rename_file.old_path")
                    ?: requireNonBlank(action.newPath, "rename_file.new_path")
            }
            is AgentAction.DeleteFile -> requireNonBlank(action.path, "delete_file.path")
            is AgentAction.CopyFile -> {
                requireNonBlank(action.src, "copy_file.src")
                    ?: requireNonBlank(action.dst, "copy_file.dst")
            }
            is AgentAction.ListDir -> requireNonBlank(action.path, "list_dir.path")
            is AgentAction.Mkdir -> requireNonBlank(action.path, "mkdir.path")
            is AgentAction.SearchFiles -> {
                requireNonBlank(action.path, "search_files.path")
                    ?: requireNonBlank(action.keyword, "search_files.keyword")
            }
            is AgentAction.PythonRun -> {
                if (action.code == null && action.file == null) {
                    "python_run requires either 'code' or 'file' parameter"
                } else if (action.code != null && action.file != null) {
                    "python_run cannot have both 'code' and 'file' parameters"
                } else {
                    null
                }
            }
            is AgentAction.PythonStatus -> null  // No parameters needed
            is AgentAction.PythonKill -> null  // No parameters needed
            else -> null
        }
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

    // ============================================================================
    // File Operations (using FileEditor)
    // ============================================================================

    private fun ensureFileSession(path: String, createIfMissing: Boolean = false): Pair<Boolean, String?> {
        val manager = com.example.offlineai.agent.utils.FileEditorManager
        if (manager.getEditor(path) != null) {
            return Pair(false, null)
        }

        val openResult = manager.openFile(path, createIfMissing)
        val success = openResult["success"] as? Boolean ?: false
        val error = openResult["error"] as? String
        return if (success) {
            LogManager.logI(TAG, "[FILE_AUTO_OPEN] Session auto-opened: $path")
            Pair(true, null)
        } else {
            Pair(false, error ?: "Failed to open file")
        }
    }

    private fun closeFileSessionIfNeeded(path: String, openedByExecutor: Boolean) {
        if (openedByExecutor) {
            com.example.offlineai.agent.utils.FileEditorManager.closeFile(path)
            LogManager.logI(TAG, "[FILE_AUTO_CLOSE] Session auto-closed: $path")
        }
    }

    private fun executeReadLines(path: String, startLine: Int, endLine: Int): ExecutionResult {
        LogManager.logI(TAG, "[READ_LINES] Reading $path lines $startLine-$endLine")
        val file = File(path)
        if (!file.exists()) {
            return ExecutionResult(false, "File not found: $path")
        }
        if (!file.isFile) {
            return ExecutionResult(false, "Path is not a file: $path")
        }

        return try {
            val allLines = file.readLines()
            val totalLines = allLines.size
            // Clamp to valid range, out-of-range reads to EOF without error
            val start = startLine.coerceAtLeast(1)
            val end = endLine.coerceAtMost(totalLines)
            if (start > totalLines) {
                return ExecutionResult(
                    true,
                    "Read 0 lines from $path (start_line $start > total $totalLines)",
                    returnData = "Total lines: $totalLines\n(empty: start_line exceeds total)"
                )
            }
            val selectedLines = allLines.subList(start - 1, end)
            val contentStr = selectedLines.mapIndexed { i, line -> "${start + i}: $line" }.joinToString("\n")
            ExecutionResult(
                true,
                "Read ${selectedLines.size} lines from $path (lines $start-$end of $totalLines)",
                returnData = "Lines $start-$end/$totalLines:\n$contentStr"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[READ_LINES] Failed: $path", e)
            ExecutionResult(false, "Read lines failed: ${e.message}")
        }
    }

    private fun executeEditLines(
        path: String,
        startLine: Int,
        endLine: Int,
        content: String
    ): ExecutionResult {
        val replacementLines = splitContentToLines(content)
        LogManager.logI(TAG, "[EDIT_LINES] Replacing lines $startLine-$endLine in $path with ${replacementLines.size} lines")
        val manager = com.example.offlineai.agent.utils.FileEditorManager
        val (autoOpened, openError) = ensureFileSession(path, createIfMissing = false)
        if (openError != null) {
            return ExecutionResult(false, openError)
        }

        val result = manager.editReplace(path, startLine, endLine, replacementLines)
        val success = result["success"] as? Boolean ?: false
        val newTotalLines = result["new_total_lines"] as? Int ?: 0
        val replacedRange = result["replaced_range"] as? String ?: ""
        val error = result["error"] as? String

        if (!success) {
            closeFileSessionIfNeeded(path, autoOpened)
            return ExecutionResult(false, error ?: "Failed to edit file")
        }

        val saveResult = manager.saveFile(path)
        val saveSuccess = saveResult["success"] as? Boolean ?: false
        val saveError = saveResult["error"] as? String
        if (!saveSuccess) {
            closeFileSessionIfNeeded(path, true)
            return ExecutionResult(false, saveError ?: "Failed to save file")
        }

        closeFileSessionIfNeeded(path, autoOpened)
        return ExecutionResult(
            true,
            "Edited $path: replaced $replacedRange, new total: $newTotalLines lines",
            returnData = "{\"new_total_lines\":$newTotalLines, \"replaced_range\":\"$replacedRange\"}"
        )
    }

    private fun splitContentToLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        return content
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
    }

    private fun executeGrep(path: String, keyword: String): ExecutionResult {
        LogManager.logI(TAG, "[GREP] Searching '$keyword' in $path")
        val file = File(path)
        if (!file.exists()) {
            return ExecutionResult(false, "File not found: $path")
        }
        if (!file.isFile) {
            return ExecutionResult(false, "Path is not a file: $path")
        }

        return try {
            val lines = file.readLines()
            val matches = mutableListOf<String>()
            val keywordLower = keyword.lowercase()
            lines.forEachIndexed { index, line ->
                if (line.lowercase().contains(keywordLower)) {
                    matches.add("${index + 1}: $line")
                }
            }
            val matchCount = matches.size
            if (matchCount == 0) {
                return ExecutionResult(
                    true,
                    "No matches found for '$keyword' in $path",
                    returnData = "No matches found."
                )
            }
            // Limit output to avoid excessive data
            val maxMatches = 100
            val truncated = matchCount > maxMatches
            val outputMatches = if (truncated) matches.take(maxMatches) else matches
            val contentStr = outputMatches.joinToString("\n")
            val suffix = if (truncated) "\n[TRUNCATED: showing $maxMatches of $matchCount matches]" else ""
            ExecutionResult(
                true,
                "Found $matchCount matches for '$keyword' in $path",
                returnData = "Matches ($matchCount):\n$contentStr$suffix"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[GREP] Failed: $path", e)
            ExecutionResult(false, "Grep failed: ${e.message}")
        }
    }

    private fun executeRenameFile(oldPath: String, newPath: String): ExecutionResult {
        LogManager.logI(TAG, "[RENAME_FILE] Renaming $oldPath to $newPath")
        return try {
            val oldFile = File(oldPath)
            if (!oldFile.exists()) {
                return ExecutionResult(false, "Source not found: $oldPath")
            }
            val newFile = File(newPath)
            if (newFile.exists()) {
                return ExecutionResult(false, "Target already exists: $newPath")
            }
            // Ensure parent directory exists
            newFile.parentFile?.mkdirs()
            val success = oldFile.renameTo(newFile)
            if (success) {
                ExecutionResult(true, "Renamed $oldPath to $newPath")
            } else {
                ExecutionResult(false, "Failed to rename $oldPath to $newPath")
            }
        } catch (e: Exception) {
            LogManager.logE(TAG, "[RENAME_FILE] Failed: $oldPath -> $newPath", e)
            ExecutionResult(false, "Rename failed: ${e.message}")
        }
    }

    // ============================================================================
    // Directory and File Management Operations
    // ============================================================================

    private fun executeListDir(path: String): ExecutionResult {
        LogManager.logI(TAG, "[LIST_DIR] Listing: $path")
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return ExecutionResult(false, "Directory not found: $path")
            }
            if (!dir.isDirectory) {
                return ExecutionResult(false, "Path is not a directory: $path")
            }

            val items = dir.listFiles()?.map { file ->
                val type = if (file.isDirectory) "dir" else "file"
                val size = if (file.isFile) file.length() else 0
                "{\"type\":\"$type\",\"name\":\"${file.name}\",\"size\":$size}"
            } ?: emptyList()

            ExecutionResult(
                true,
                "Listed ${items.size} items in $path",
                returnData = "[${items.joinToString(",")}]"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[LIST_DIR] Failed: $path", e)
            ExecutionResult(false, "List directory failed: ${e.message}")
        }
    }

    private fun executeCopyFile(src: String, dst: String): ExecutionResult {
        LogManager.logI(TAG, "[COPY_FILE] Copying $src to $dst")
        return try {
            val srcFile = File(src)
            if (!srcFile.exists()) {
                return ExecutionResult(false, "Source not found: $src")
            }

            val dstFile = File(dst)
            dstFile.parentFile?.mkdirs()
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
            }

            ExecutionResult(true, "Copied $src to $dst")
        } catch (e: Exception) {
            LogManager.logE(TAG, "[COPY_FILE] Failed: $src -> $dst", e)
            ExecutionResult(false, "Copy failed: ${e.message}")
        }
    }

    private fun executeDeleteFile(path: String, recursive: Boolean): ExecutionResult {
        LogManager.logI(TAG, "[DELETE_FILE] Deleting: $path (recursive=$recursive)")
        return try {
            val file = File(path)
            if (!file.exists()) {
                return ExecutionResult(false, "File/directory not found: $path")
            }

            if (file.isDirectory && !recursive && (file.listFiles()?.isNotEmpty() == true)) {
                return ExecutionResult(false, "Directory is not empty: $path. Use recursive=true to delete non-empty directories.")
            }
            val deleted = if (file.isDirectory && recursive) {
                file.deleteRecursively()
            } else {
                file.delete()
            }

            if (deleted) {
                ExecutionResult(true, "Deleted: $path")
            } else {
                ExecutionResult(false, "Failed to delete: $path")
            }
        } catch (e: Exception) {
            LogManager.logE(TAG, "[DELETE_FILE] Failed: $path", e)
            ExecutionResult(false, "Delete failed: ${e.message}")
        }
    }

    private fun executeSearchFiles(path: String, keyword: String): ExecutionResult {
        LogManager.logI(TAG, "[SEARCH_FILES] Searching '$keyword' in $path")
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return ExecutionResult(false, "Directory not found: $path")
            }
            if (!dir.isDirectory) {
                return ExecutionResult(false, "Path is not a directory: $path")
            }

            val keywordLower = keyword.lowercase()
            val matches = mutableListOf<String>()

            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.lowercase().contains(keywordLower)) {
                    matches.add(file.absolutePath)
                }
            }

            // Limit output
            val maxMatches = 100
            val truncated = matches.size > maxMatches
            val outputMatches = if (truncated) matches.take(maxMatches) else matches
            val contentStr = outputMatches.joinToString("\n")
            val suffix = if (truncated) "\n[TRUNCATED: showing $maxMatches of ${matches.size} matches]" else ""

            ExecutionResult(
                true,
                "Found ${matches.size} files matching '$keyword' in $path",
                returnData = "Matches (${matches.size}):\n$contentStr$suffix"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[SEARCH_FILES] Failed: $path, keyword=$keyword", e)
            ExecutionResult(false, "Search files failed: ${e.message}")
        }
    }

    private fun executeMkdir(path: String): ExecutionResult {
        LogManager.logI(TAG, "[MKDIR] Creating directory: $path")
        return try {
            val dir = File(path)
            if (dir.exists()) {
                return if (dir.isDirectory) {
                    ExecutionResult(true, "Directory already exists: $path")
                } else {
                    ExecutionResult(false, "Path exists but is not a directory: $path")
                }
            }
            
            val created = dir.mkdirs()
            if (created) {
                ExecutionResult(true, "Created directory: $path")
            } else {
                ExecutionResult(false, "Failed to create directory: $path")
            }
        } catch (e: Exception) {
            LogManager.logE(TAG, "[MKDIR] Failed: $path", e)
            ExecutionResult(false, "Create directory failed: ${e.message}")
        }
    }

    // ============================================================================
    // Python Operations (via Chaquopy)
    // ============================================================================

    private val pythonOutputMemory = mutableMapOf<String, StringBuilder>()
    private val pythonStateLock = Any()
    private val PYTHON_STDOUT_KEY = "python_key"
    private val PYTHON_OUTPUT_MAX_CHARS = 10000
    private val sessionCounter = AtomicInteger(0)
    @Volatile private var activePythonSession: PythonSession? = null
    @Volatile private var lastPythonSession: PythonSession? = null
    
    // CRITICAL: Chaquopy requires single-threaded Python execution to avoid "frame does not exist" error
    private val pythonExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Python-Chaquopy-SingleThread").apply { isDaemon = true }
    }

    private fun executePythonRun(action: AgentAction.PythonRun): ExecutionResult {
        LogManager.logI(TAG, "[PYTHON_RUN] Starting Python session")

        synchronized(pythonStateLock) {
            val running = activePythonSession
            if (running != null && running.state == PythonSession.State.RUNNING) {
                return ExecutionResult(
                    false,
                    "Python single-instance mode: another run is active. Wait via python_status or stop via python_kill."
                )
            }
        }

        // Validate input
        if (action.code == null && action.file == null) {
            return ExecutionResult(false, "python_run requires either 'code' or 'file' parameter")
        }
        if (action.code != null && action.file != null) {
            return ExecutionResult(false, "python_run cannot have both 'code' and 'file' parameters")
        }

        val sessionId = "py_${System.currentTimeMillis()}_${sessionCounter.incrementAndGet()}"

        // Prepare Python code
        val pythonCode = when {
            action.code != null -> action.code
            action.file != null -> {
                val file = File(action.file)
                if (!file.exists()) {
                    return ExecutionResult(false, "Python file not found: ${action.file}")
                }
                file.readText()
            }
            else -> return ExecutionResult(false, "No Python code to execute")
        }

        // Create log file in chat history folder
        val logFile = File(getChatHistoryFolder(), "${sessionId}.log")
        val logWriter = java.io.FileWriter(logFile, true)

        // Create internal single session (ID only for framework debug, not exposed to model)
        val session = PythonSession(
            sessionId = sessionId,
            startTime = System.currentTimeMillis(),
            logFile = logFile,
            logWriter = logWriter
        )
        synchronized(pythonStateLock) {
            activePythonSession = session
            lastPythonSession = session
        }

        // Prepare wrapped code with stdout/stderr redirection to file
        val wrappedCode = wrapPythonWithOutputCapture(pythonCode, logFile.absolutePath)

        // CRITICAL: Use single-threaded executor to prevent Chaquopy "frame does not exist" error
        // Python is not thread-safe, must execute sequentially
        val future = pythonExecutor.submit<java.lang.Void> {
            try {
                session.executionThread = Thread.currentThread()
                LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId started execution on thread: ${Thread.currentThread().name}")
                val python = Python.getInstance()
                LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId got Python instance")

                // Get CPython thread ident for PyThreadState_SetAsyncExc (used by python_kill)
                val mainModule = python.getModule("__main__")
                try {
                    val threading = python.getModule("threading")
                    val currentThread = threading.callAttr("current_thread")
                    val ident = currentThread.get("ident")
                    session.pythonThreadIdent = ident?.toLong() ?: -1L
                    LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId CPython thread ident: ${session.pythonThreadIdent}")
                } catch (e: Exception) {
                    LogManager.logW(TAG, "[PYTHON_RUN] Failed to get CPython thread ident: ${e.message}")
                }

                val mainDict = mainModule.get("__dict__")
                val builtins = python.builtins
                LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId executing...")

                // Log wrapped code preview for debugging (first 200 chars)
                val codePreview = wrappedCode.take(200).replace("\n", "\\n")
                LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId code preview: $codePreview...")

                builtins.callAttr("exec", wrappedCode, mainDict, mainDict)

                // Update session status on completion
                if (session.state != PythonSession.State.KILLED) {
                    session.state = PythonSession.State.COMPLETED
                    session.exitCode = 0
                    logWriter.close()
                    LogManager.logI(TAG, "[PYTHON_RUN] Session $sessionId completed successfully")
                }
            } catch (e: Exception) {
                if (session.state != PythonSession.State.KILLED) {
                    session.state = PythonSession.State.FAILED
                    session.errorMessage = e.message
                    val stackTrace = e.stackTraceToString()
                    logWriter.append("[PYTHON ERROR] ${e.message}\n")
                    logWriter.append("[PYTHON STACKTRACE] $stackTrace\n")
                    logWriter.close()
                    LogManager.logE(TAG, "[PYTHON_RUN] Session $sessionId failed: ${e.message}")
                    LogManager.logE(TAG, "[PYTHON_RUN] Session $sessionId stacktrace: $stackTrace")
                }
            } finally {
                // Sync file content to data_memory for model access
                try {
                    if (logFile.exists()) {
                        val finalContent = logFile.readText()
                        pythonOutputMemory[PYTHON_STDOUT_KEY] = StringBuilder(
                            truncatePythonOutput(finalContent, logFile.absolutePath)
                        )
                    }
                } catch (e: Exception) {
                    LogManager.logE(TAG, "[PYTHON_RUN] Final sync error: ${e.message}")
                } finally {
                    synchronized(pythonStateLock) {
                        if (activePythonSession?.sessionId == sessionId && session.state != PythonSession.State.RUNNING) {
                            activePythonSession = null
                        }
                        lastPythonSession = session
                    }
                }
            }
            null
        }

        // Convert Future to Job-like structure for session management
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                future.get()
            } catch (e: Exception) {
                LogManager.logE(TAG, "[PYTHON_RUN] Session $sessionId future error: ${e.message}")
            }
        }

        session.job = job
        session.future = future

        return ExecutionResult(
            true,
            "Python run started (single-instance mode).",
            returnData = "{\"status\":\"RUNNING\"}"
        )
    }

    private fun getChatHistoryFolder(): File {
        val chatFolderPath = ConfigManager.getString(context, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "")
        return if (chatFolderPath.isNotEmpty()) {
            File(chatFolderPath)
        } else {
            File(context.getExternalFilesDir(null), "chat_history").apply { mkdirs() }
        }
    }

    private fun executePythonStatus(action: AgentAction.PythonStatus): ExecutionResult {
        val session = synchronized(pythonStateLock) {
            activePythonSession ?: lastPythonSession
        }

        if (session == null) {
            return ExecutionResult(
                true,
                "Python status: FAILED (no active instance). Use data_memory get key=python_key for latest synced output.",
                returnData = "{\"status\":\"FAILED\",\"return\":\"no_active_python_instance\",\"recent_output\":\"\",\"hint\":\"Use data_memory get key=python_key\"}"
            )
        }

        val fullOutput = try {
            if (session.logFile.exists()) {
                val content = session.logFile.readText()
                val truncated = truncatePythonOutput(content, session.logFile.absolutePath)
                pythonOutputMemory[PYTHON_STDOUT_KEY] = StringBuilder(truncated)
                truncated
            } else {
                pythonOutputMemory[PYTHON_STDOUT_KEY]?.toString() ?: ""
            }
        } catch (e: Exception) {
            "[Error reading log: ${e.message}]"
        }
        val recentOutput = fullOutput.takeLast(500)
        val returnValue = when (session.state) {
            PythonSession.State.RUNNING -> "running"
            PythonSession.State.COMPLETED -> (session.exitCode?.toString() ?: "0")
            PythonSession.State.FAILED -> (session.errorMessage ?: "failed")
            PythonSession.State.KILLED -> (session.errorMessage ?: "killed")
        }
        val externalStatus = when (session.state) {
            PythonSession.State.RUNNING -> "RUNNING"
            PythonSession.State.COMPLETED -> "SUCCESS"
            PythonSession.State.FAILED -> "FAILED"
            PythonSession.State.KILLED -> "KILLED"
        }

        val result = buildString {
            append("{")
            append("\"status\":\"$externalStatus\",")
            append("\"return\":\"${returnValue.escapeJson()}\",")
            append("\"recent_output\":\"${recentOutput.escapeJson()}\",")
            append("\"hint\":\"Use data_memory get key=python_key\"")
            append("}")
        }

        return ExecutionResult(true, "Python status: $externalStatus. Use data_memory get key=python_key for full output.", returnData = result)
    }

    fun getPythonOutputSnapshot(): Map<String, String> {
        return pythonOutputMemory.mapValues { it.value.toString() }
    }

    private fun executePythonKill(action: AgentAction.PythonKill): ExecutionResult {
        val session = synchronized(pythonStateLock) { activePythonSession }
            ?: return ExecutionResult(
                true,
                "Python already stopped. Kill is idempotent.",
                returnData = "{\"status\":\"SUCCESS\",\"return\":\"already_stopped\",\"hint\":\"Use data_memory get key=python_key\"}"
            )

        return try {
            // Sync log before kill
            try {
                if (session.logFile.exists()) {
                    val content = session.logFile.readText()
                    pythonOutputMemory[PYTHON_STDOUT_KEY] = StringBuilder(
                        truncatePythonOutput(content, session.logFile.absolutePath)
                    )
                    LogManager.logI(TAG, "[PYTHON_KILL] Synced ${content.length} chars from log before kill")
                }
            } catch (e: Exception) {
                LogManager.logW(TAG, "[PYTHON_KILL] Log sync failed: ${e.message}")
            }

            session.state = PythonSession.State.KILLED
            session.errorMessage = "Killed by python_kill"

            val execThread = session.executionThread
            var threadDead = execThread == null || !execThread.isAlive

            // PyThreadState_SetAsyncExc: inject SystemExit into CPython thread.
            // CPython checks pending async exceptions every ~5ms (sys.getswitchinterval).
            // Works on ANY pure Python code including "while True: pass".
            var asyncExcInjected = false
            if (!threadDead && session.pythonThreadIdent > 0) {
                LogManager.logI(TAG, "[PYTHON_KILL] Calling PyThreadState_SetAsyncExc (ident=${session.pythonThreadIdent})")
                try {
                    val python = Python.getInstance()
                    val ctypes = python.getModule("ctypes")
                    val pythonapi = ctypes.get("pythonapi")
                    val cLong = ctypes.get("c_long")
                    val pyObj = ctypes.get("py_object")
                    val systemExit = python.builtins.get("SystemExit")
                    val res = pythonapi?.get("PyThreadState_SetAsyncExc")
                        ?.call(cLong?.call(session.pythonThreadIdent), pyObj?.call(systemExit))
                    LogManager.logI(TAG, "[PYTHON_KILL] PyThreadState_SetAsyncExc returned: $res")
                    asyncExcInjected = res?.toInt() == 1
                } catch (e: Exception) {
                    LogManager.logW(TAG, "[PYTHON_KILL] PyThreadState_SetAsyncExc failed: ${e.message}")
                }

                // Wait up to 5 seconds for thread to die
                for (i in 1..50) {
                    Thread.sleep(100)
                    if (!execThread!!.isAlive) {
                        threadDead = true
                        LogManager.logI(TAG, "[PYTHON_KILL] Thread exited after ${i * 100}ms")
                        break
                    }
                }

                // If PyThreadState_SetAsyncExc returned 1, injection succeeded.
                // Chaquopy JVM thread may lag behind CPython; treat as dead.
                if (!threadDead && asyncExcInjected) {
                    threadDead = true
                    LogManager.logI(TAG, "[PYTHON_KILL] AsyncExc injected (ret=1), treating as dead despite Thread.isAlive")
                }
            }

            session.job.cancel()
            session.future?.cancel(true)

            // Final log sync after kill
            try {
                if (session.logFile.exists()) {
                    val content = session.logFile.readText()
                    pythonOutputMemory[PYTHON_STDOUT_KEY] = StringBuilder(
                        truncatePythonOutput(content, session.logFile.absolutePath)
                    )
                }
            } catch (_: Exception) {}

            synchronized(pythonStateLock) {
                activePythonSession = null
                lastPythonSession = session
            }

            if (threadDead) {
                LogManager.logI(TAG, "[PYTHON_KILL] Python thread confirmed dead")
            } else {
                LogManager.logW(TAG, "[PYTHON_KILL] Thread may still be alive after 5s")
            }

            ExecutionResult(
                true,
                if (threadDead) "Python killed." else "Python kill sent, thread may still be winding down.",
                returnData = "{\"status\":\"SUCCESS\",\"return\":\"killed\",\"thread_dead\":$threadDead,\"hint\":\"Use data_memory get key=python_key\"}"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[PYTHON_KILL] Exception: ${e.message}")
            synchronized(pythonStateLock) {
                activePythonSession = null
                lastPythonSession = session
            }
            session.state = PythonSession.State.KILLED
            ExecutionResult(
                true,
                "Python kill completed (exception: ${e.message}).",
                returnData = "{\"status\":\"SUCCESS\",\"return\":\"killed\",\"hint\":\"Use data_memory get key=python_key\"}"
            )
        }
    }

    /**
     * Truncate Python output to PYTHON_OUTPUT_MAX_CHARS.
     * If content exceeds the limit, keep only the last PYTHON_OUTPUT_MAX_CHARS chars
     * and prepend a warning with the full file path.
     */
    private fun truncatePythonOutput(content: String, filePath: String): String {
        if (content.length <= PYTHON_OUTPUT_MAX_CHARS) return content
        val tail = content.takeLast(PYTHON_OUTPUT_MAX_CHARS)
        val warning = "[WARNING] Output too large (${content.length} chars), only last $PYTHON_OUTPUT_MAX_CHARS chars shown. Full log: $filePath\n"
        LogManager.logW(TAG, "[PYTHON_OUTPUT] Truncated from ${content.length} to $PYTHON_OUTPUT_MAX_CHARS chars, file=$filePath")
        return warning + tail
    }

    private fun String.escapeJson(): String {
        return this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun wrapPythonWithOutputCapture(code: String, logFilePath: String): String {
        val escapedPath = logFilePath.replace("\\", "\\\\").replace("'", "\\'")
        val escapedCode = code.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        // Kill mechanism: PyThreadState_SetAsyncExc only (from Java side via ctypes).
        // Zero overhead during normal execution. CPython checks pending async exceptions
        // every ~5ms, so even "while True: pass" gets killed.
        return buildString {
            append("import sys\n")
            append("import traceback\n\n")

            // -- stdout/stderr file capture (no kill logic, pure logging) --
            append("_log_file_path = '$escapedPath'\n\n")
            append("class _FileOutputCapture:\n")
            append("    def __init__(self, original, filepath, prefix=''):\n")
            append("        self.original = original\n")
            append("        self.filepath = filepath\n")
            append("        self.prefix = prefix\n\n")
            append("    def write(self, s):\n")
            append("        if s and s.strip():\n")
            append("            line = self.prefix + s.rstrip('\\n')\n")
            append("            with open(self.filepath, 'a', encoding='utf-8') as f:\n")
            append("                f.write(line + '\\n')\n")
            append("        self.original.write(s)\n\n")
            append("    def flush(self):\n")
            append("        self.original.flush()\n\n")
            append("sys.stdout = _FileOutputCapture(sys.stdout, _log_file_path)\n")
            append("sys.stderr = _FileOutputCapture(sys.stderr, _log_file_path, '[ERROR] ')\n\n")

            // -- execute user code --
            append("_user_code = '''${escapedCode}'''\n\n")
            append("try:\n")
            append("    exec(_user_code)\n")
            append("except SystemExit as e:\n")
            append("    print(str(e))\n")
            append("except Exception as e:\n")
            append("    print('[PYTHON EXCEPTION] ' + str(type(e).__name__) + ': ' + str(e))\n")
            append("    print('[PYTHON TRACEBACK] ' + traceback.format_exc())\n")
            append("    raise\n")
        }
    }

    // Python session data class
    data class PythonSession(
        val sessionId: String,
        val startTime: Long,
        val logFile: File,
        val logWriter: java.io.FileWriter,
        @Volatile var state: State = State.RUNNING,
        @Volatile var job: Job = Job(),
        @Volatile var future: java.util.concurrent.Future<*>? = null,
        @Volatile var executionThread: Thread? = null,
        @Volatile var pythonThreadIdent: Long = -1L,
        @Volatile var exitCode: Int? = null,
        @Volatile var errorMessage: String? = null
    ) {
        enum class State {
            RUNNING, COMPLETED, FAILED, KILLED
        }
    }
}
