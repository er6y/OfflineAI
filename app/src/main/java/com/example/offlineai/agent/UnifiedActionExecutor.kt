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
import java.io.File
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

    private fun executeFileNew(path: String, newContent: String): ExecutionResult {
        LogManager.logI(TAG, "[FILE_NEW] Creating file $path with content length=${newContent.length}")
        val file = File(path)
        if (file.exists()) {
            return ExecutionResult(false, "File already exists: $path")
        }

        val manager = com.example.offlineai.agent.utils.FileEditorManager
        val (autoOpened, openError) = ensureFileSession(path, createIfMissing = true)
        if (openError != null) {
            return ExecutionResult(false, openError)
        }

        val lines = splitContentToLines(newContent)
        if (lines.isNotEmpty()) {
            val result = manager.editReplace(path, 1, 1, lines)
            val success = result["success"] as? Boolean ?: false
            val error = result["error"] as? String
            if (!success) {
                closeFileSessionIfNeeded(path, autoOpened)
                return ExecutionResult(false, error ?: "Failed to initialize new file")
            }
        }

        val saveResult = manager.saveFile(path)
        val saveSuccess = saveResult["success"] as? Boolean ?: false
        val saveError = saveResult["error"] as? String
        if (!saveSuccess) {
            closeFileSessionIfNeeded(path, true)
            return ExecutionResult(false, saveError ?: "Failed to save new file")
        }

        return ExecutionResult(
            true,
            "Created file $path with ${lines.size} lines",
            returnData = "{\"created\":true,\"path\":\"$path\",\"line_count\":${lines.size}}"
        )
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
                is AgentAction.FileOpen -> ExecutionResult(false, "file_open is deprecated. Use file_new/file_read/file_search/file_edit directly.")
                is AgentAction.FileRead -> executeFileRead(action.path, action.startLine, action.readCount)
                is AgentAction.FileNew -> executeFileNew(action.path, action.newContent)
                is AgentAction.FileEdit -> executeFileEdit(action.path, action.startLine, action.endLine, action.newContent)
                is AgentAction.FileSearch -> executeFileSearch(action.path, action.keyword, action.ignoreCase)
                is AgentAction.FileSave -> ExecutionResult(false, "file_save is deprecated. file_edit already saves automatically.")
                is AgentAction.FileListDir -> executeFileListDir(action.path, action.recursive)
                is AgentAction.FileCopy -> executeFileCopy(action.src, action.dst)
                is AgentAction.FileDelete -> executeFileDelete(action.path, action.recursive)
                is AgentAction.FileSearchRegex -> executeFileSearchRegex(action.path, action.pattern, action.recursive)
                is AgentAction.FileCreateDir -> executeFileCreateDir(action.path)
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
            is AgentAction.FileOpen -> requireNonBlank(action.path, "file_open.path")
            is AgentAction.FileRead -> {
                requireNonBlank(action.path, "file_read.path")
                    ?: if (action.startLine < 1) "file_read.start_line must be >= 1" else null
                    ?: if (action.readCount < 1) "file_read.read_count must be >= 1" else null
            }
            is AgentAction.FileNew -> {
                requireNonBlank(action.path, "file_new.path")
            }
            is AgentAction.FileEdit -> {
                requireNonBlank(action.path, "file_edit.path")
                    ?: if (action.startLine < 1) "file_edit.start_line must be >= 1" else null
                    ?: if (action.endLine < action.startLine) "file_edit.end_line must be >= start_line" else null
            }
            is AgentAction.FileSearch -> {
                requireNonBlank(action.path, "file_search.path")
                    ?: requireNonBlank(action.keyword, "file_search.keyword")
            }
            is AgentAction.FileSave -> requireNonBlank(action.path, "file_save.path")
            is AgentAction.FileListDir -> requireNonBlank(action.path, "file_list_dir.path")
            is AgentAction.FileCopy -> {
                requireNonBlank(action.src, "file_copy.src")
                    ?: requireNonBlank(action.dst, "file_copy.dst")
            }
            is AgentAction.FileDelete -> requireNonBlank(action.path, "file_delete.path")
            is AgentAction.FileSearchRegex -> {
                requireNonBlank(action.path, "file_search_regex.path")
                    ?: requireNonBlank(action.pattern, "file_search_regex.pattern")
            }
            is AgentAction.FileCreateDir -> requireNonBlank(action.path, "file_create_dir.path")
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

    private fun executeFileRead(path: String, startLine: Int, readCount: Int): ExecutionResult {
        LogManager.logI(TAG, "[FILE_READ] Reading $path from line $startLine, count $readCount")
        val (autoOpened, openError) = ensureFileSession(path, createIfMissing = false)
        if (openError != null) {
            return ExecutionResult(false, openError)
        }

        val result = com.example.offlineai.agent.utils.FileEditorManager.readLines(path, startLine, readCount)
        val success = result["success"] as? Boolean ?: false
        val totalLines = result["total_lines"] as? Int ?: 0
        val readLines = result["read_lines"] as? List<Map<String, Any>> ?: emptyList()
        val error = result["error"] as? String

        closeFileSessionIfNeeded(path, autoOpened)

        return if (success) {
            val contentStr = readLines.joinToString("\n") { "${it["line"]}: ${it["content"]}" }
            ExecutionResult(
                true,
                "Read ${readLines.size} lines from $path (total: $totalLines)",
                returnData = "Lines $startLine-${startLine + readLines.size - 1}/$totalLines:\n$contentStr"
            )
        } else {
            ExecutionResult(false, error ?: "Failed to read file")
        }
    }

    private fun executeFileEdit(
        path: String,
        startLine: Int,
        endLine: Int,
        newContent: String
    ): ExecutionResult {
        val replacementLines = splitContentToLines(newContent)
        LogManager.logI(TAG, "[FILE_EDIT] Replacing lines $startLine-$endLine in $path with ${replacementLines.size} lines")
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

        return ExecutionResult(
            true,
            "Edited and saved $path: replaced $replacedRange, new total: $newTotalLines lines",
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

    private fun executeFileSearch(path: String, keyword: String, ignoreCase: Boolean): ExecutionResult {
        LogManager.logI(TAG, "[FILE_SEARCH] Searching '$keyword' in $path (ignoreCase=$ignoreCase)")
        val (autoOpened, openError) = ensureFileSession(path, createIfMissing = false)
        if (openError != null) {
            return ExecutionResult(false, openError)
        }

        val result = com.example.offlineai.agent.utils.FileEditorManager.searchKeyword(path, keyword, ignoreCase)
        val success = result["success"] as? Boolean ?: false
        val matchCount = result["match_count"] as? Int ?: 0
        val matchLines = result["match_lines"] as? List<Int> ?: emptyList()
        val error = result["error"] as? String

        closeFileSessionIfNeeded(path, autoOpened)

        return if (success) {
            val linesStr = matchLines.joinToString(",")
            ExecutionResult(
                true,
                "Found $matchCount matches for '$keyword' in $path",
                returnData = "{\"match_count\":$matchCount, \"match_lines\":[$linesStr]}"
            )
        } else {
            ExecutionResult(false, error ?: "Failed to search file")
        }
    }

    // ============================================================================
    // Directory and File Management Operations
    // ============================================================================

    private fun executeFileListDir(path: String, recursive: Boolean): ExecutionResult {
        LogManager.logI(TAG, "[FILE_LIST_DIR] Listing: $path (recursive=$recursive)")
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return ExecutionResult(false, "Directory not found: $path")
            }
            if (!dir.isDirectory) {
                return ExecutionResult(false, "Path is not a directory: $path")
            }

            val items = if (recursive) {
                listFilesRecursive(dir, path)
            } else {
                dir.listFiles()?.map { fileToMap(it, path) } ?: emptyList()
            }

            val itemsJson = items.joinToString(",") { item ->
                val type = item["type"] ?: "file"
                val name = item["name"] ?: ""
                val size = item["size"] ?: 0
                val relPath = item["relative_path"] ?: ""
                "{\"type\":\"$type\",\"name\":\"$name\",\"size\":$size,\"path\":\"$relPath\"}"
            }

            ExecutionResult(
                true,
                "Listed ${items.size} items in $path",
                returnData = "{\"count\":${items.size},\"items\":[$itemsJson]}"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[FILE_LIST_DIR] Failed: $path", e)
            ExecutionResult(false, "List directory failed: ${e.message}")
        }
    }

    private fun listFilesRecursive(dir: File, basePath: String): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        dir.walkTopDown().forEach { file ->
            if (file != dir) {
                result.add(fileToMap(file, basePath))
            }
        }
        return result
    }

    private fun fileToMap(file: File, basePath: String): Map<String, Any> {
        val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/")
        return mapOf(
            "type" to if (file.isDirectory) "dir" else "file",
            "name" to file.name,
            "size" to if (file.isFile) file.length() else 0,
            "relative_path" to relativePath,
            "last_modified" to file.lastModified()
        )
    }

    private fun executeFileCopy(src: String, dst: String): ExecutionResult {
        LogManager.logI(TAG, "[FILE_COPY] Copying $src to $dst")
        return try {
            val srcFile = File(src)
            if (!srcFile.exists()) {
                return ExecutionResult(false, "Source not found: $src")
            }

            val dstFile = File(dst)
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
            }

            ExecutionResult(true, "Copied $src to $dst")
        } catch (e: Exception) {
            LogManager.logE(TAG, "[FILE_COPY] Failed: $src -> $dst", e)
            ExecutionResult(false, "Copy failed: ${e.message}")
        }
    }

    private fun executeFileDelete(path: String, recursive: Boolean): ExecutionResult {
        LogManager.logI(TAG, "[FILE_DELETE] Deleting: $path (recursive=$recursive)")
        return try {
            val file = File(path)
            if (!file.exists()) {
                return ExecutionResult(false, "File/directory not found: $path")
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
            LogManager.logE(TAG, "[FILE_DELETE] Failed: $path", e)
            ExecutionResult(false, "Delete failed: ${e.message}")
        }
    }

    private fun executeFileSearchRegex(path: String, pattern: String, recursive: Boolean): ExecutionResult {
        LogManager.logI(TAG, "[FILE_SEARCH_REGEX] Searching pattern '$pattern' in $path (recursive=$recursive)")
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return ExecutionResult(false, "Directory not found: $path")
            }
            if (!dir.isDirectory) {
                return ExecutionResult(false, "Path is not a directory: $path")
            }

            val regex = Regex(pattern)
            val matches = mutableListOf<Map<String, String>>()

            val files = if (recursive) dir.walkTopDown() else dir.listFiles()?.asSequence() ?: emptySequence()

            files.filter { it.isFile }.forEach { file ->
                if (regex.containsMatchIn(file.name)) {
                    matches.add(mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "size" to file.length().toString()
                    ))
                }
            }

            val matchesJson = matches.joinToString(",") { m ->
                val name = m["name"] ?: ""
                val filePath = m["path"] ?: ""
                val size = m["size"] ?: "0"
                "{\"name\":\"$name\",\"path\":\"$filePath\",\"size\":$size}"
            }

            ExecutionResult(
                true,
                "Found ${matches.size} files matching pattern in $path",
                returnData = "{\"match_count\":${matches.size},\"matches\":[$matchesJson]}"
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "[FILE_SEARCH_REGEX] Failed: $path, pattern=$pattern", e)
            ExecutionResult(false, "Regex search failed: ${e.message}")
        }
    }

    private fun executeFileCreateDir(path: String): ExecutionResult {
        LogManager.logI(TAG, "[FILE_CREATE_DIR] Creating directory: $path")
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
            LogManager.logE(TAG, "[FILE_CREATE_DIR] Failed: $path", e)
            ExecutionResult(false, "Create directory failed: ${e.message}")
        }
    }
}
