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
        // [IMPROVEMENT 4+5] Cache TTLs. Values picked empirically: web_open
        // is "did the page actually load", which the WebView keeps fresh for
        // ~30s before the LLM might genuinely need a refresh; web_get_content
        // returns DOM-text, which is cheap to recompute but >50% of LLM
        // duplicate-fetch loops happen within a 60s window.
        private const val WEB_OPEN_DEDUP_MS = 30_000L
        private const val WEB_GET_CACHE_TTL_MS = 60_000L
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

    // [IMPROVEMENT 4+5] Per-task web caches.
    //   web_open  : if same URL was opened < WEB_OPEN_DEDUP_MS ago, skip the
    //               actual reload (page is still in WebView).
    //   web_get   : if same URL was fetched < WEB_GET_CACHE_TTL_MS ago, return
    //               the cached content without re-running the JS scrape.
    // Both are reset at task start via resetTaskCaches() so a new task always
    // starts with a clean slate.
    @Volatile private var lastWebOpenUrl: String? = null
    @Volatile private var lastWebOpenTs: Long = 0L
    @Volatile private var lastWebOpenStep: Int = -1
    @Volatile private var lastWebGetUrl: String? = null
    @Volatile private var lastWebGetTs: Long = 0L
    @Volatile private var lastWebGetContent: String? = null
    @Volatile private var lastWebGetStep: Int = -1
    private val webStepCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Clear all per-task caches. Called by AgentEngine.executeTask() at the
     * start of every new agent task so the previous task's state never leaks
     * into the new run.
     */
    fun resetTaskCaches() {
        lastWebOpenUrl = null
        lastWebOpenTs = 0L
        lastWebOpenStep = -1
        lastWebGetUrl = null
        lastWebGetTs = 0L
        lastWebGetContent = null
        lastWebGetStep = -1
        webStepCounter.set(0)
        LogManager.logI(TAG, "[EXEC_RESET] task-scoped web caches cleared")
    }

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
    
    // ============================================================================
    // Path variable substitution: ${SKILL_DIR}, ${WORKSPACE}
    // ============================================================================

    private fun resolveVar(s: String): String {
        var r = s
        if (r.contains("\${SKILL_DIR}")) {
            r = r.replace("\${SKILL_DIR}", ConfigManager.getSkillsPath(context))
        }
        if (r.contains("\${WORKSPACE}")) {
            r = r.replace("\${WORKSPACE}", ConfigManager.ensureAgentWorkspace(context))
        }
        return r
    }

    private fun resolveVariables(action: AgentAction): AgentAction = when (action) {
        is AgentAction.Python -> action.copy(
            argv = action.argv.map { resolveVar(it) }
        )
        is AgentAction.CreateFile -> action.copy(path = resolveVar(action.path))
        is AgentAction.ReadFile -> action.copy(path = resolveVar(action.path))
        is AgentAction.WriteFile -> action.copy(path = resolveVar(action.path))
        is AgentAction.ReadLines -> action.copy(path = resolveVar(action.path))
        is AgentAction.EditLines -> action.copy(path = resolveVar(action.path))
        is AgentAction.Grep -> action.copy(path = resolveVar(action.path))
        is AgentAction.RenameFile -> action.copy(oldPath = resolveVar(action.oldPath), newPath = resolveVar(action.newPath))
        is AgentAction.DeleteFile -> action.copy(path = resolveVar(action.path))
        is AgentAction.CopyFile -> action.copy(src = resolveVar(action.src), dst = resolveVar(action.dst))
        is AgentAction.ListDir -> action.copy(path = resolveVar(action.path))
        is AgentAction.Mkdir -> action.copy(path = resolveVar(action.path))
        is AgentAction.SearchFiles -> action.copy(path = resolveVar(action.path))
        else -> action
    }

    /**
     * Execute an AgentAction.
     */
    suspend fun execute(action: AgentAction): ExecutionResult {
        if (accessibilityService == null) {
            return ExecutionResult(false, "Accessibility service not available")
        }

        // Resolve path variables before validation and execution
        val resolved = resolveVariables(action)

        validateActionParameters(resolved)?.let { errorMsg ->
            LogManager.logW(TAG, "[ACTION_VALIDATION] Rejected ${resolved.javaClass.simpleName}: $errorMsg")
            return ExecutionResult(false, errorMsg)
        }
        
        LogManager.logI(TAG, "Executing action: ${resolved.javaClass.simpleName}")
        
        // Hide floating window for screen interactions
        val needsHide = resolved is AgentAction.Click || resolved is AgentAction.LongPress ||
            resolved is AgentAction.DoubleClick || resolved is AgentAction.Type ||
            resolved is AgentAction.Swipe || resolved is AgentAction.Drag ||
            resolved is AgentAction.SystemButton
        
        if (needsHide) {
            // ROOT-CAUSE FIX: temporaryHide() posts to the main thread and returns
            // immediately. The previous delay(50) was NOT enough for:
            //   1) main-thread post to actually run visibility=GONE,
            //   2) the next frame post {} callback to fire,
            //   3) WindowManager to propagate the visibility change into the
            //      accessibility node tree (rootInActiveWindow becomes null
            //      mid-transition).
            // For Type especially, the executor immediately reads
            // rootInActiveWindow inside inputTextAtCoordinate -- if the floating
            // window is still in transition, root is null and the whole call
            // fails with "no_active_window". Switch to a synchronous latch so
            // we only proceed AFTER the UI frame containing visibility=GONE has
            // actually been rendered. Then add a small settle delay to let the
            // accessibility tree catch up.
            val hideLatch = java.util.concurrent.CountDownLatch(1)
            val hideRequested = accessibilityService?.floatingWindow != null
            if (hideRequested) {
                accessibilityService?.floatingWindow?.temporaryHide { hideLatch.countDown() }
                if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    LogManager.logW(TAG, "[ACTION_HIDE] Timeout(500ms) waiting for floating window to hide; proceeding anyway")
                }
            }
            // Settle delay: WindowManager -> accessibility tree propagation.
            // Empirically rootInActiveWindow can stay null for ~100-200ms after
            // a window-level visibility change on Huawei/Honor; 150ms covers
            // it without noticeably slowing the action.
            delay(150)
            // [B5] For Type actions specifically, we additionally poll the a11y
            // tree for an effective root up to 600ms BEFORE dispatching the
            // gesture. Type is the only action that immediately reads the tree
            // (findFocus, findNodeAtPoint, walkUpToEditable). Click/Swipe/Drag
            // only dispatchGesture and do not depend on root readiness, so the
            // 150ms delay above is sufficient for them. Root 5 times in log2.txt
            // showed rootInActiveWindow null at 11:04:17.816 (+5ms after hide),
            // causing all 5 Type calls to fail -- the wait here eliminates
            // that race condition.
            if (resolved is AgentAction.Type) {
                val t0 = System.currentTimeMillis()
                val ready = accessibilityService?.waitForRootReady(600L) != null
                val waited = System.currentTimeMillis() - t0
                LogManager.logI(TAG, "[ACTION_ROOT_WAIT] type=${resolved.javaClass.simpleName} " +
                    "rootReady=$ready waitedMs=$waited")
            }
        }
        
        val result = try {
            when (resolved) {
                is AgentAction.Click -> executeClick(resolved.x, resolved.y)
                is AgentAction.LongPress -> executeLongPress(resolved.x, resolved.y)
                is AgentAction.DoubleClick -> executeDoubleClick(resolved.x, resolved.y)
                is AgentAction.Type -> executeType(resolved.text, resolved.x, resolved.y)
                is AgentAction.Swipe -> executeSwipe(resolved)
                is AgentAction.Drag -> executeDrag(resolved.startX, resolved.startY, resolved.endX, resolved.endY)
                is AgentAction.Open -> executeOpen(resolved.appName)
                is AgentAction.SystemButton -> executeSystemButton(resolved.button)
                is AgentAction.Wait -> executeWait(resolved.seconds)
                is AgentAction.Terminate -> ExecutionResult(true, "Task ${resolved.status.value}")
                is AgentAction.Context -> ExecutionResult(true, "Context updated")
                is AgentAction.AskUser -> ExecutionResult(true, "AskUser: ${resolved.text}")
                is AgentAction.ShowOutput -> ExecutionResult(true, "ShowOutput: size=${resolved.size}")
                is AgentAction.GetAppList -> executeGetAppList()
                is AgentAction.KbInsert -> {
                    LogManager.logI(TAG, "[KB] kb_insert deferred to experience save flow")
                    ExecutionResult(true, "KB insert deferred")
                }
                is AgentAction.KbDelete -> {
                    LogManager.logI(TAG, "[KB] kb_delete deferred to experience save flow")
                    ExecutionResult(true, "KB delete deferred")
                }
                is AgentAction.WebOpen -> executeWebOpen(resolved.url)
                is AgentAction.WebGetContent -> executeWebGetContent()
                is AgentAction.WebExecuteJs -> executeWebExecuteJs(resolved.script)
                is AgentAction.DataMemory -> ExecutionResult(true, "data_memory handled by AgentEngine")
                is AgentAction.CreateFile -> executeCreateFile(resolved.path, resolved.content)
                is AgentAction.ReadFile -> executeReadFile(resolved.path)
                is AgentAction.WriteFile -> executeWriteFile(resolved.path, resolved.content)
                is AgentAction.ReadLines -> executeReadLines(resolved.path, resolved.startLine, resolved.endLine)
                is AgentAction.EditLines -> executeEditLines(resolved.path, resolved.startLine, resolved.endLine, resolved.content)
                is AgentAction.Grep -> executeGrep(resolved.path, resolved.keyword)
                is AgentAction.RenameFile -> executeRenameFile(resolved.oldPath, resolved.newPath)
                is AgentAction.DeleteFile -> executeDeleteFile(resolved.path, resolved.recursive)
                is AgentAction.CopyFile -> executeCopyFile(resolved.src, resolved.dst)
                is AgentAction.ListDir -> executeListDir(resolved.path)
                is AgentAction.Mkdir -> executeMkdir(resolved.path)
                is AgentAction.SearchFiles -> executeSearchFiles(resolved.path, resolved.keyword)
                is AgentAction.Python -> executePython(resolved)
                is AgentAction.PythonStatus -> executePythonStatus(resolved)
                is AgentAction.PythonKill -> executePythonKill(resolved)
                is AgentAction.ScheduleGet -> executeScheduleGet()
                is AgentAction.ScheduleSet -> executeScheduleSet(resolved)
            }
        } finally {
            if (needsHide) {
                // Synchronous show: matches the synchronous hide above so we
                // never leave the floating window invisible if the next action
                // fires immediately. Same latch + timeout pattern.
                val showLatch = java.util.concurrent.CountDownLatch(1)
                val showRequested = accessibilityService?.floatingWindow != null
                if (showRequested) {
                    accessibilityService?.floatingWindow?.temporaryShow { showLatch.countDown() }
                    if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        LogManager.logW(TAG, "[ACTION_SHOW] Timeout(500ms) waiting for floating window to show")
                    }
                }
            }
        }
        
        if (result.success && resolved !is AgentAction.Wait) {
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
            is AgentAction.Type -> {
                requireNonBlank(action.text, "type.text")
                    ?: run {
                        // coordinate is REQUIRED. Guarantees a single unified
                        // input path (inputTextAtCoordinate) which works on
                        // EditText / WebView / self-drawn boxes alike. Naked
                        // type is deprecated because it silently fails on
                        // real-phone self-drawn inputs (no FOCUS_INPUT).
                        if (action.x == null || action.y == null) {
                            "type.coordinate 必填：必须提供 [x,y] 指向目标输入框屏幕坐标"
                        } else {
                            validateNormalizedCoordinate(action.x, "x")
                                ?: validateNormalizedCoordinate(action.y, "y")
                        }
                    }
            }
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
            is AgentAction.Python -> {
                if (action.argv.isEmpty()) {
                    "python requires non-empty 'argv' array (no shell; use argv=[...] semantics like subprocess.run)"
                } else if (action.timeoutSec < 0) {
                    "python.timeout_sec must be >= 0"
                } else {
                    null
                }
            }
            is AgentAction.PythonStatus -> null  // No parameters needed
            is AgentAction.PythonKill -> null  // No parameters needed
            is AgentAction.ScheduleSet -> validateScheduleSet(action)
            is AgentAction.Wait -> {
                // Allowed range: 1 second ~ 24 hours (86400s)
                if (action.seconds < 1 || action.seconds > 86400) {
                    "wait.seconds must be in [1, 86400] (1s ~ 24h), got ${action.seconds}"
                } else null
            }
            else -> null
        }
    }

    // ============================================================================
    // Schedule task management
    // ============================================================================

    /** Validate schedule_set parameters (task_id range + each optional field). */
    private fun validateScheduleSet(a: AgentAction.ScheduleSet): String? {
        if (a.taskId < 1 || a.taskId > ConfigManager.SCHEDULE_TASK_COUNT) {
            return "schedule_set.task_id must be 1..${ConfigManager.SCHEDULE_TASK_COUNT}, got ${a.taskId}"
        }
        a.weekdays?.let { wd ->
            if (wd.isNotEmpty()) {
                val parts = wd.split(",").map { it.trim() }
                for (p in parts) {
                    val d = p.toIntOrNull()
                    if (d == null || d !in 1..7) {
                        return "schedule_set.weekdays must be comma-separated ints 1..7 (1=Mon..7=Sun), got '$wd'"
                    }
                }
            }
        }
        a.start?.let { parseHHMM(it) ?: return "schedule_set.start must be 'HH:MM' (00:00~23:59), got '$it'" }
        a.end?.let { parseHHMM(it) ?: return "schedule_set.end must be 'HH:MM' (00:00~23:59), got '$it'" }
        a.intervalMin?.let { if (it < 1) return "schedule_set.interval_min must be >= 1, got $it" }
        return null
    }

    /** Parse 'HH:MM' -> Pair<hour, min> or null if invalid. */
    private fun parseHHMM(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /**
     * Build a markdown summary of master switch + 4 task configs.
     * Returned via ExecutionResult.returnData so AgentEngine injects it into the next step.
     */
    private fun executeScheduleGet(): ExecutionResult {
        LogManager.logI(TAG, "[SCHEDULE] schedule_get requested")
        val ctx = context
        val masterEnabled = ConfigManager.getBoolean(ctx, ConfigManager.KEY_SCHEDULE_ENABLED, false)
        val sb = StringBuilder()
        sb.append("# Scheduled Tasks\n\n")
        sb.append("**Master switch (user-controlled only)**: ")
            .append(if (masterEnabled) "ON" else "OFF")
            .append("\n\n")
        if (!masterEnabled) {
            sb.append("> NOTE: master switch is OFF — no task will trigger regardless of per-task settings. ")
                .append("Only the user can toggle this switch in the Settings page.\n\n")
        }
        sb.append("| task | enabled | one_shot | weekdays | start | end | interval_min | agent_preset | prompt |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|\n")
        for (i in 0 until ConfigManager.SCHEDULE_TASK_COUNT) {
            val enabled = ConfigManager.getBoolean(ctx, ConfigManager.scheduleTaskKey(i, "enabled"), false)
            val oneShot = ConfigManager.getBoolean(ctx, ConfigManager.scheduleTaskKey(i, "one_shot"), false)
            val weekdays = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "weekdays"), "1,2,3,4,5")
            val sh = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_hour"), 9)
            val sm = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "start_min"), 0)
            val eh = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_hour"), 17)
            val em = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "end_min"), 0)
            val interval = ConfigManager.getInt(ctx, ConfigManager.scheduleTaskKey(i, "interval"), 30)
            // Stored value includes .txt; strip extension for the agent-facing `agent_preset` name
            val storedPreset = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "prompt_file"), "common_agent.txt")
            val presetName = if (storedPreset.endsWith(".txt")) storedPreset.dropLast(4) else storedPreset
            val prompt = ConfigManager.getString(ctx, ConfigManager.scheduleTaskKey(i, "prompt"), "")
            val promptPreview = if (prompt.length > 60) prompt.substring(0, 60) + "…" else prompt
            // Escape pipe characters that would break the markdown table layout
            val promptEscaped = promptPreview.replace("|", "\\|").replace("\n", " ")
            sb.append("| ").append(i + 1)
                .append(" | ").append(enabled)
                .append(" | ").append(oneShot)
                .append(" | ").append(weekdays)
                .append(" | ").append(String.format("%02d:%02d", sh, sm))
                .append(" | ").append(String.format("%02d:%02d", eh, em))
                .append(" | ").append(interval)
                .append(" | ").append(presetName)
                .append(" | ").append(promptEscaped)
                .append(" |\n")
        }

        // Append the list of available agent_user presets (filename w/o .txt) so the model
        // can pick a valid `agent_preset` value without guessing the path.
        val available = ConfigManager.listAgentUserFiles(ctx)
        sb.append("\n**可用 agent_preset**（填入下面任一值，不要带扩展名，也不要填路径）: ")
        if (available.isEmpty()) {
            sb.append("(none — agent_user/ 目录为空，必须先 create_file 到该目录新建 .txt 文件)")
        } else {
            sb.append(available.joinToString(", ") { if (it.endsWith(".txt")) it.dropLast(4) else it })
        }
        sb.append("\n说明：跨夜时段允许（end < start 表示到次日 end 时刻），`weekdays` 必须覆盖实际触发日期（含跨夜的次日）。\n")

        return ExecutionResult(
            success = true,
            message = "schedule_get: master=$masterEnabled, ${ConfigManager.SCHEDULE_TASK_COUNT} tasks, presets=${available.size}",
            returnData = sb.toString()
        )
    }

    /**
     * Patch-update a single task slot. Only fields present are written.
     * Does NOT touch master switch (user-controlled).
     */
    private fun executeScheduleSet(a: AgentAction.ScheduleSet): ExecutionResult {
        val ctx = context
        val index = a.taskId - 1  // external 1-based, internal 0-based
        val changed = mutableListOf<String>()

        a.enabled?.let {
            ConfigManager.setBoolean(ctx, ConfigManager.scheduleTaskKey(index, "enabled"), it)
            changed.add("enabled=$it")
        }
        a.oneShot?.let {
            ConfigManager.setBoolean(ctx, ConfigManager.scheduleTaskKey(index, "one_shot"), it)
            changed.add("one_shot=$it")
        }
        a.weekdays?.let { wd ->
            // Normalize: strip spaces, keep 1..7 order-preserving
            val normalized = wd.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            ConfigManager.setString(ctx, ConfigManager.scheduleTaskKey(index, "weekdays"), normalized)
            changed.add("weekdays=$normalized")
        }
        a.start?.let { s ->
            parseHHMM(s)?.let { (h, m) ->
                ConfigManager.setInt(ctx, ConfigManager.scheduleTaskKey(index, "start_hour"), h)
                ConfigManager.setInt(ctx, ConfigManager.scheduleTaskKey(index, "start_min"), m)
                changed.add("start=${String.format("%02d:%02d", h, m)}")
            }
        }
        a.end?.let { s ->
            parseHHMM(s)?.let { (h, m) ->
                ConfigManager.setInt(ctx, ConfigManager.scheduleTaskKey(index, "end_hour"), h)
                ConfigManager.setInt(ctx, ConfigManager.scheduleTaskKey(index, "end_min"), m)
                changed.add("end=${String.format("%02d:%02d", h, m)}")
            }
        }
        a.intervalMin?.let {
            ConfigManager.setInt(ctx, ConfigManager.scheduleTaskKey(index, "interval"), it)
            changed.add("interval_min=$it")
        }
        a.agentPreset?.let { pf ->
            // Only validate when non-blank (blank string means "no update", for backward compat with optStr default)
            if (pf.isNotBlank()) {
                // Validate against agent_user directory listing; accept both "stock_agent" and "stock_agent.txt"
                val available = ConfigManager.listAgentUserFiles(ctx)
                val normalizedName = if (pf.endsWith(".txt")) pf else "$pf.txt"
                if (available.isNotEmpty() && normalizedName !in available) {
                    // Strip .txt for display so the error mirrors what the model should fill in next time
                    val availableDisplay = available.joinToString(", ") {
                        if (it.endsWith(".txt")) it.dropLast(4) else it
                    }
                    return ExecutionResult(
                        success = false,
                        message = "schedule_set.agent_preset '$pf' not found in agent_user/. " +
                                "You provided '$pf'. Available presets (填入不带扩展名的名字): $availableDisplay"
                    )
                }
                // Store full filename (with .txt) for UI/service compatibility; agent-facing name has no extension
                ConfigManager.setString(ctx, ConfigManager.scheduleTaskKey(index, "prompt_file"), normalizedName)
                val displayName = if (normalizedName.endsWith(".txt")) normalizedName.dropLast(4) else normalizedName
                changed.add("agent_preset=$displayName")
            }
        }
        a.prompt?.let {
            ConfigManager.setString(ctx, ConfigManager.scheduleTaskKey(index, "prompt"), it)
            changed.add("prompt=(${it.length} chars)")
        }

        if (changed.isEmpty()) {
            return ExecutionResult(
                success = false,
                message = "schedule_set: no fields provided to update for task ${a.taskId}"
            )
        }

        LogManager.logI(TAG, "[SCHEDULE] schedule_set task=${a.taskId} updated: ${changed.joinToString(", ")}")

        // If master switch is OFF, hint the agent so it can surface this to the user.
        val masterEnabled = ConfigManager.getBoolean(ctx, ConfigManager.KEY_SCHEDULE_ENABLED, false)
        val suffix = if (!masterEnabled) {
            " | NOTE: master switch is OFF (user-controlled), changes saved but won't trigger until user enables it in Settings."
        } else ""

        return ExecutionResult(
            success = true,
            message = "schedule_set task ${a.taskId}: ${changed.joinToString(", ")}$suffix"
        )
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
    
    private fun executeType(text: String, xNorm: Int?, yNorm: Int?): ExecutionResult {
        // Unified input path: ALL type actions now go through
        // inputTextAtCoordinate (click + directed setText/paste on the node
        // under point). The validator above already enforces non-null x,y,
        // but we re-check here as a defensive second line -- makes the
        // executor self-contained and surfaces a clear failure message if
        // validation is ever bypassed.
        if (xNorm == null || yNorm == null) {
            return ExecutionResult(
                success = false,
                message = "type action 必须带 coordinate:[x,y]（指向目标输入框屏幕坐标）；裸 type 已废弃"
            )
        }
        val (px, py) = normalizedToPixel(xNorm, yNorm)
        LogManager.logI(TAG, "[TYPE_AT] Normalized: [$xNorm, $yNorm] -> Pixel: ($px, $py), text='$text'")
        val err = accessibilityService?.inputTextAtCoordinate(px, py, text)
        return if (err == null) ExecutionResult(true, "Typed at ($px,$py): $text")
        else ExecutionResult(false, "Failed to type at ($px,$py): $err")
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

    /**
     * Execute wait action with a live countdown on the floating-window status bar.
     *
     * Design (absolute-time anchoring, not drift-accumulating):
     *  - Compute absolute end timestamp once (endMs = now + seconds*1000).
     *  - Each iteration: read current time, compute remaining, update status text,
     *    then delay at most 1 second (capped by actual time-to-end on the last tick).
     *  - Total elapsed wall time = seconds ± ~20ms regardless of JVM jitter / GC.
     *  - `delay()` is a kotlinx.coroutines suspend, so user-triggered job cancel
     *    (stop button) interrupts the wait immediately via CancellationException.
     *
     * The original status text is restored after the wait completes, so subsequent
     * steps are not polluted with stale countdown text.
     */
    private suspend fun executeWait(seconds: Int): ExecutionResult {
        val clamped = seconds.coerceIn(1, 86400)
        // Short waits (≤ 3s) keep the legacy silent behavior for UI stabilization.
        if (clamped <= 3) {
            delay(clamped * 1000L)
            return ExecutionResult(true, "Waited ${clamped}s")
        }

        val floatingWindow = accessibilityService?.floatingWindow
        val startMs = System.currentTimeMillis()
        val endMs = startMs + clamped * 1000L
        val endTimeLabel = formatWallClock(endMs)
        LogManager.logI(TAG, "[WAIT] Starting ${clamped}s countdown (until $endTimeLabel)")

        try {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = endMs - now
                if (remainingMs <= 0L) break
                val remainingSec = ((remainingMs + 999L) / 1000L).toInt()  // ceil to avoid flashing 0
                floatingWindow?.updateStatus("Agent等待剩余 ${formatHms(remainingSec)}，约于 $endTimeLabel 继续")
                // Sleep until the next whole-second boundary, but never past endMs.
                val nextTick = minOf(1000L, remainingMs)
                delay(nextTick)
            }
        } finally {
            // Restore a neutral status so the countdown text doesn't linger into next step.
            floatingWindow?.updateStatus("Agent 等待结束，继续执行…")
        }

        LogManager.logI(TAG, "[WAIT] Countdown finished (waited ${clamped}s)")
        return ExecutionResult(true, "Waited ${clamped}s")
    }

    /** Format seconds as HH:MM:SS for countdown display. */
    private fun formatHms(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return String.format("%02d:%02d:%02d", h, m, sec)
    }

    /** Format an absolute wall-clock timestamp as HH:MM:SS (local time). */
    private fun formatWallClock(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        return String.format(
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
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
        // [IMPROVEMENT 5] Same-URL dedup. Repeated web_open to the identical
        // URL inside WEB_OPEN_DEDUP_MS skips the actual reload (page already
        // sitting fresh in the AgentWebView). The message explicitly tells
        // the LLM "you already opened this", so the model can stop looping.
        val now = System.currentTimeMillis()
        val cachedUrl = lastWebOpenUrl
        if (cachedUrl == url && (now - lastWebOpenTs) < WEB_OPEN_DEDUP_MS) {
            val ageSec = (now - lastWebOpenTs) / 1000.0
            LogManager.logI(TAG, "[WEB_OPEN][CACHE_HIT] reused S${lastWebOpenStep} (age=${"%.1f".format(ageSec)}s)")
            return ExecutionResult(
                true,
                "[WEB_OPEN_REUSED_CACHE] $url already opened ${"%.1f".format(ageSec)}s ago at " +
                    "step S${lastWebOpenStep}; page still loaded in WebView. " +
                    "Skipping reload. Call web_get_content if you need the content again, " +
                    "OR open a different URL if the previous load was stale."
            )
        }
        val webView = getAgentWebView()
            ?: return ExecutionResult(false, "AgentWebView not available (accessibility service not running)")
        val ok = webView.loadUrl(url)
        // Update cache regardless of full load success: even a partial load
        // means the WebView engine is now pointed at this URL.
        lastWebOpenUrl = url
        lastWebOpenTs = System.currentTimeMillis()
        lastWebOpenStep = webStepCounter.incrementAndGet()
        // [IMPROVEMENT 4] Invalidate stale web_get_content cache: any new
        // navigation makes the previous DOM scrape outdated.
        lastWebGetUrl = null
        lastWebGetContent = null
        return if (ok) {
            ExecutionResult(true, "[WEB_OPEN_OK] Opened URL: $url (page loaded)")
        } else {
            // Timeout does NOT mean the page failed to load — the underlying WebView engine
            // (Chromium) continues loading asynchronously. Slow servers (e.g. Jira cold-start)
            // may exceed the wait threshold but eventually deliver onPageFinished.
            // Return success=true so the model proceeds to web_get_content immediately.
            LogManager.logW(TAG, "[WEB_OPEN] Load timeout for $url, but WebView may still be loading. Returning partial success.")
            ExecutionResult(true, "[WEB_OPEN_PARTIAL_TIMEOUT_CONTINUE] URL opened: $url (load timed out waiting, page may still be loading — proceed with web_get_content)")
        }
    }

    private suspend fun executeWebGetContent(): ExecutionResult {
        LogManager.logI(TAG, "[WEB_GET_CONTENT] Extracting page content")
        val webView = getAgentWebView()
            ?: return ExecutionResult(false, "AgentWebView not available (accessibility service not running)")
        // [IMPROVEMENT 4] TTL cache by current URL. Same URL within the TTL
        // window returns the previous content with a [REUSED] marker so the
        // LLM sees identical content==identical URL==no point re-fetching.
        // Observed in flash_dram log: 6 consecutive web_get_content calls
        // returning the same 7089 chars from the same URL.
        val currentUrl = webView.getCurrentUrl()
        val now = System.currentTimeMillis()
        val cacheUrl = lastWebGetUrl
        val cacheContent = lastWebGetContent
        if (cacheUrl != null && cacheUrl == currentUrl &&
            cacheContent != null && (now - lastWebGetTs) < WEB_GET_CACHE_TTL_MS) {
            val ageSec = (now - lastWebGetTs) / 1000.0
            LogManager.logI(TAG, "[WEB_GET_CONTENT][CACHE_HIT] reused S${lastWebGetStep} " +
                "(${cacheContent.length} chars, age=${"%.1f".format(ageSec)}s)")
            return ExecutionResult(
                true,
                "[WEB_GET_REUSED_CACHE] Page content for $currentUrl reused from S${lastWebGetStep} " +
                    "(${cacheContent.length} chars, ${"%.1f".format(ageSec)}s ago). " +
                    "If the page changed, call web_open again to refresh.",
                returnData = cacheContent
            )
        }
        val content = webView.getContent()
        return if (content != null) {
            LogManager.logI(TAG, "[WEB_GET_CONTENT] Got ${content.length} chars")
            // Populate cache for the next call.
            lastWebGetUrl = currentUrl
            lastWebGetContent = content
            lastWebGetTs = System.currentTimeMillis()
            lastWebGetStep = webStepCounter.incrementAndGet()
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
    // Python Operations (via Chaquopy) - PC CLI aligned: sync-by-default, background via flag
    // ============================================================================

    private val pythonStateLock = Any()
    // HEAD truncation: skills (e.g. stockpicker) now print critical LLM-facing
    // content (NEXT_STEP block) at the TOP of their output, so we keep the
    // beginning when truncation is needed. Cap raised to 20000 to comfortably
    // hold stockpicker brief (~11K chars incl. matrix-rich NEXT_STEP + human
    // tables). Matches AgentEngine.formatResultStr's large-payload bucket.
    private val PYTHON_OUTPUT_MAX_CHARS = 20000  // Head truncation cap for status/result output
    private val PYTHON_LOG_MAX_CHARS = 200000    // Absolute max for internal log read
    private val sessionCounter = AtomicInteger(0)
    @Volatile private var activePythonSession: PythonSession? = null
    @Volatile private var lastPythonSession: PythonSession? = null

    // CRITICAL: Chaquopy requires single-threaded Python execution to avoid "frame does not exist" error
    private val pythonExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Python-Chaquopy-SingleThread").apply { isDaemon = true }
    }

    /**
     * Normalize argv: strip leading "python" / "python3" if the model included it.
     * No shell parsing needed (argv-only API).
     */
    private fun normalizePythonTokens(action: AgentAction.Python): Pair<List<String>, String?> {
        val tokens = action.argv
        if (tokens.isEmpty()) return emptyList<String>() to "python argv is empty"
        val stripped = if (tokens[0] == "python" || tokens[0] == "python3") tokens.drop(1) else tokens
        return stripped to null
    }

    private fun executePython(action: AgentAction.Python): ExecutionResult {
        LogManager.logI(TAG, "[PYTHON] Starting: timeout_sec=${action.timeoutSec} (0=async, >0=sync)")

        // Lazy-initialize Chaquopy Python on first use (moved out of Application.onCreate
        // to avoid startup ANRs on slow devices). Safe / idempotent / thread-safe.
        try {
            com.example.offlineai.PythonBootstrapper.ensureStarted(context)
        } catch (t: Throwable) {
            LogManager.logE(TAG, "[PYTHON] PythonBootstrapper.ensureStarted failed: ${t.message}", t)
            return ExecutionResult(false, "Python runtime init failed: ${t.message}")
        }

        // Single-instance gate
        synchronized(pythonStateLock) {
            val running = activePythonSession
            if (running != null && running.state == PythonSession.State.RUNNING) {
                return ExecutionResult(
                    false,
                    "Python single-instance: another run is active. Use python_status to poll or python_kill to abort."
                )
            }
        }

        // Normalize cmd/argv -> tokens
        val (tokens, normErr) = normalizePythonTokens(action)
        if (normErr != null) return ExecutionResult(false, normErr)
        if (tokens.isEmpty()) return ExecutionResult(false, "python command is empty after normalization")

        // Decide: -c inline code vs script file
        data class PreparedCode(val code: String, val scriptPath: String?, val finalArgv: List<String>)
        val prepared: PreparedCode = when {
            tokens[0] == "-c" -> {
                if (tokens.size < 2) {
                    return ExecutionResult(false, "python -c requires code string: argv=[\"-c\",\"your code here\"]")
                }
                // sys.argv convention: ["-c", ...trailing_args...]
                PreparedCode(tokens[1], null, listOf("-c") + tokens.drop(2))
            }
            else -> {
                val path = tokens[0]
                val file = File(path)
                if (!file.exists()) {
                    return ExecutionResult(false, "Python script not found: $path")
                }
                PreparedCode(file.readText(), path, tokens)
            }
        }

        val sessionId = "py_${System.currentTimeMillis()}_${sessionCounter.incrementAndGet()}"
        val logFile = File(getChatHistoryFolder(), "${sessionId}.log")
        val logWriter = java.io.FileWriter(logFile, true)

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

        // Prepare wrapped code: sets sys.argv, __file__, __name__, then captures stdout/stderr
        val wrappedCode = wrapPythonWithOutputCapture(
            code = prepared.code,
            logFilePath = logFile.absolutePath,
            sysArgv = prepared.finalArgv,
            scriptPath = prepared.scriptPath
        )

        // Submit to single-thread executor (Chaquopy is not thread-safe)
        val future = pythonExecutor.submit<java.lang.Void> {
            try {
                session.executionThread = Thread.currentThread()
                LogManager.logI(TAG, "[PYTHON] Session $sessionId exec on ${Thread.currentThread().name}, argv=${prepared.finalArgv}")
                val python = Python.getInstance()

                // Capture CPython thread ident for kill
                try {
                    val threading = python.getModule("threading")
                    val currentThread = threading.callAttr("current_thread")
                    val ident = currentThread.get("ident")
                    session.pythonThreadIdent = ident?.toLong() ?: -1L
                } catch (e: Exception) {
                    LogManager.logW(TAG, "[PYTHON] Failed to get CPython thread ident: ${e.message}")
                }

                val mainModule = python.getModule("__main__")
                val mainDict = mainModule.get("__dict__")
                val builtins = python.builtins

                builtins.callAttr("exec", wrappedCode, mainDict, mainDict)

                if (session.state != PythonSession.State.KILLED) {
                    // Read sys.exit code captured by the wrapper. The wrapper
                    // catches SystemExit and stores its code in a sentinel
                    // attribute on __main__, so we can propagate it back to
                    // the engine and the LLM (instead of swallowing it as 0).
                    val capturedExit = try {
                        val sentinel = mainDict?.callAttr("get", "__pyhelper_exit_code__")
                        sentinel?.toInt() ?: 0
                    } catch (e: Exception) {
                        LogManager.logW(TAG, "[PYTHON] Failed to read __pyhelper_exit_code__: ${e.message}")
                        0
                    }
                    session.exitCode = capturedExit
                    // Non-zero sys.exit() means the script reported failure.
                    // Surface it as FAILED so the agent treats it like a real
                    // error (no stale "Success" log, no fake artifact recording).
                    session.state = if (capturedExit != 0)
                        PythonSession.State.FAILED
                    else
                        PythonSession.State.COMPLETED
                    if (capturedExit != 0 && session.errorMessage == null) {
                        session.errorMessage = "script exited with code $capturedExit"
                    }
                    logWriter.close()
                    LogManager.logI(TAG, "[PYTHON] Session $sessionId completed exitCode=$capturedExit state=${session.state}")
                }
            } catch (e: Exception) {
                if (session.state != PythonSession.State.KILLED) {
                    session.state = PythonSession.State.FAILED
                    session.errorMessage = e.message
                    val stackTrace = e.stackTraceToString()
                    try {
                        logWriter.append("[PYTHON ERROR] ${e.message}\n")
                        logWriter.append("[PYTHON STACKTRACE] $stackTrace\n")
                        logWriter.close()
                    } catch (_: Exception) {}
                    LogManager.logE(TAG, "[PYTHON] Session $sessionId failed: ${e.message}")
                }
            } finally {
                synchronized(pythonStateLock) {
                    if (activePythonSession?.sessionId == sessionId && session.state != PythonSession.State.RUNNING) {
                        activePythonSession = null
                    }
                    lastPythonSession = session
                }
            }
            null
        }

        session.future = future
        // Optional: also wrap as a Job for cooperative scope management
        session.job = CoroutineScope(Dispatchers.IO).launch {
            try { future.get() } catch (_: Exception) {}
        }

        // timeout_sec == 0 : fully async, return immediately with RUNNING
        if (action.timeoutSec == 0) {
            return ExecutionResult(
                true,
                "Python started in background (session=$sessionId, timeout_sec=0). Use python_status to poll or python_kill to abort.",
                returnData = buildPythonStateJson(
                    status = "RUNNING",
                    output = "",
                    exitCode = -1,
                    durationSec = 0.0,
                    logFile = logFile.absolutePath,
                    truncated = false,
                    error = null
                )
            )
        }

        // timeout_sec > 0 : sync, block up to N seconds; on timeout leave job running
        return try {
            future.get(action.timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            buildPythonResult(session)
        } catch (e: java.util.concurrent.TimeoutException) {
            // Auto-downgrade to background - DO NOT kill the job
            LogManager.logW(TAG, "[PYTHON] Sync timeout after ${action.timeoutSec}s, auto-downgrade to background")
            val (output, truncated) = readLogTail(session)
            ExecutionResult(
                true,
                "Python sync timeout after ${action.timeoutSec}s, still running in background. Use python_status/kill.",
                returnData = buildPythonStateJson(
                    status = "TIMEOUT",
                    output = output,
                    exitCode = -1,
                    durationSec = action.timeoutSec.toDouble(),
                    logFile = logFile.absolutePath,
                    truncated = truncated,
                    error = null
                )
            )
        } catch (e: Exception) {
            // future.get threw ExecutionException wrapping user error; session state already updated in runnable
            LogManager.logW(TAG, "[PYTHON] future.get exception: ${e.message}")
            buildPythonResult(session)
        }
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
                "No Python instance has been run in this session.",
                returnData = buildPythonStateJson(
                    status = "NONE",
                    output = "",
                    exitCode = -1,
                    durationSec = 0.0,
                    logFile = "",
                    truncated = false,
                    error = null
                )
            )
        }

        return buildPythonResult(session)
    }

    private fun executePythonKill(action: AgentAction.PythonKill): ExecutionResult {
        val session = synchronized(pythonStateLock) { activePythonSession }
            ?: return ExecutionResult(
                true,
                "Python already stopped (no active instance). Kill is idempotent.",
                returnData = buildPythonStateJson(
                    status = "NONE",
                    output = "",
                    exitCode = -1,
                    durationSec = 0.0,
                    logFile = "",
                    truncated = false,
                    error = "already_stopped"
                )
            )

        return try {
            session.state = PythonSession.State.KILLED
            session.errorMessage = "Killed by python_kill"

            val execThread = session.executionThread
            var threadDead = execThread == null || !execThread.isAlive

            // PyThreadState_SetAsyncExc: inject SystemExit into CPython thread.
            // CPython checks pending async exceptions every ~5ms (sys.getswitchinterval).
            // Works on ANY pure Python code including "while True: pass".
            var asyncExcInjected = false
            if (!threadDead && session.pythonThreadIdent > 0) {
                LogManager.logI(TAG, "[PYTHON_KILL] Inject SystemExit via PyThreadState_SetAsyncExc (ident=${session.pythonThreadIdent})")
                try {
                    val python = Python.getInstance()
                    val ctypes = python.getModule("ctypes")
                    val pythonapi = ctypes.get("pythonapi")
                    val cLong = ctypes.get("c_long")
                    val pyObj = ctypes.get("py_object")
                    val systemExit = python.builtins.get("SystemExit")
                    val res = pythonapi?.get("PyThreadState_SetAsyncExc")
                        ?.call(cLong?.call(session.pythonThreadIdent), pyObj?.call(systemExit))
                    asyncExcInjected = res?.toInt() == 1
                    LogManager.logI(TAG, "[PYTHON_KILL] PyThreadState_SetAsyncExc returned: $res")
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

            synchronized(pythonStateLock) {
                activePythonSession = null
                lastPythonSession = session
            }

            if (threadDead) {
                LogManager.logI(TAG, "[PYTHON_KILL] Python thread confirmed dead")
            } else {
                LogManager.logW(TAG, "[PYTHON_KILL] Thread may still be alive after 5s")
            }

            buildPythonResult(session)
        } catch (e: Exception) {
            LogManager.logE(TAG, "[PYTHON_KILL] Exception: ${e.message}")
            synchronized(pythonStateLock) {
                activePythonSession = null
                lastPythonSession = session
            }
            session.state = PythonSession.State.KILLED
            session.errorMessage = "kill_exception: ${e.message}"
            buildPythonResult(session)
        }
    }

    /**
     * Read session log with HEAD-truncation semantics, respecting PYTHON_OUTPUT_MAX_CHARS.
     * Returns Pair<output, truncated>.
     *
     * Rationale (2026-04-18): skills place their most important LLM-facing
     * content (e.g. stockpicker's NEXT_STEP block with [STATE]/[DATA]/[TASK])
     * at the TOP of stdout. Keeping the head survives downstream truncation.
     * Function name retained for backward compatibility.
     */
    private fun readLogTail(session: PythonSession): Pair<String, Boolean> {
        return try {
            if (!session.logFile.exists()) return "" to false
            // Internal absolute cap: keep the tail here (full_log_file may grow
            // unbounded in background sessions; we only ever expose a window).
            val fullContent = session.logFile.readText().let {
                if (it.length > PYTHON_LOG_MAX_CHARS) it.takeLast(PYTHON_LOG_MAX_CHARS) else it
            }
            if (fullContent.length > PYTHON_OUTPUT_MAX_CHARS) {
                // HEAD truncation: keep the beginning so NEXT_STEP / TASK survive.
                // Model can always fetch full via read_file(log_file) when needed.
                fullContent.take(PYTHON_OUTPUT_MAX_CHARS) to true
            } else {
                fullContent to false
            }
        } catch (e: Exception) {
            "[Error reading log: ${e.message}]" to false
        }
    }

    /**
     * Build unified ExecutionResult from session state.
     * Single source of truth for run/status/kill return format.
     */
    private fun buildPythonResult(session: PythonSession): ExecutionResult {
        val durationMs = System.currentTimeMillis() - session.startTime
        val durationSec = durationMs / 1000.0
        val externalStatus = when (session.state) {
            PythonSession.State.RUNNING -> "RUNNING"
            PythonSession.State.COMPLETED -> "SUCCESS"
            PythonSession.State.FAILED -> "FAILED"
            PythonSession.State.KILLED -> "KILLED"
        }
        val (output, truncated) = readLogTail(session)
        val exitCode = session.exitCode ?: when (session.state) {
            PythonSession.State.COMPLETED -> 0
            PythonSession.State.RUNNING -> -1
            else -> 1
        }

        val msg = buildString {
            append("Python $externalStatus")
            append(" (${"%.2f".format(durationSec)}s")
            if (output.isNotEmpty()) append(", ${output.length} output chars")
            if (truncated) append(", truncated")
            append(")")
            session.errorMessage?.let { append(" - $it") }
        }

        // [IMPROVEMENT 3] Surface FAILED/KILLED as ExecutionResult.success=false
        // so the engine's same-failure counter can break a retry loop. Previously
        // this was always success=true, which let an LLM hammer the same buggy
        // command 11+ times without auto-termination (observed in flash_dram
        // docx generation log: 11 consecutive "exit code 2" failures).
        // RUNNING/SUCCESS still report success=true (engine treats RUNNING as a
        // pending background task, not a hard failure).
        val isSuccessForEngine = when (session.state) {
            PythonSession.State.COMPLETED -> true
            PythonSession.State.RUNNING -> true
            PythonSession.State.FAILED -> false
            PythonSession.State.KILLED -> false
        }
        return ExecutionResult(
            success = isSuccessForEngine,
            message = msg,
            returnData = buildPythonStateJson(
                status = externalStatus,
                output = output,
                exitCode = exitCode,
                durationSec = durationSec,
                logFile = session.logFile.absolutePath,
                truncated = truncated,
                error = session.errorMessage
            )
        )
    }

    /**
     * Build the unified JSON payload for python/python_status/python_kill returns.
     * Schema (stable, model-facing):
     *   {status, output, exit_code, duration_sec, log_file, truncated, error?}
     */
    private fun buildPythonStateJson(
        status: String,
        output: String,
        exitCode: Int,
        durationSec: Double,
        logFile: String,
        truncated: Boolean,
        error: String?
    ): String = buildString {
        append("{")
        append("\"status\":\"$status\",")
        append("\"output\":\"${output.escapeJson()}\",")
        append("\"exit_code\":$exitCode,")
        append("\"duration_sec\":${"%.2f".format(durationSec)},")
        append("\"log_file\":\"${logFile.escapeJson()}\",")
        append("\"truncated\":$truncated")
        if (!error.isNullOrEmpty()) {
            append(",\"error\":\"${error.escapeJson()}\"")
        }
        append("}")
    }

    private fun String.escapeJson(): String {
        return this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Wrap user code with:
     *   1. sys.argv injection (aligned with PC `python` CLI)
     *   2. __file__ setup (for script path; omitted for -c)
     *   3. __name__="__main__" (already the case in __main__ scope)
     *   4. stdout/stderr capture to log file
     *   5. exception trap with traceback to log
     */
    private fun wrapPythonWithOutputCapture(
        code: String,
        logFilePath: String,
        sysArgv: List<String>,
        scriptPath: String?
    ): String {
        val escapedPath = logFilePath.replace("\\", "\\\\").replace("'", "\\'")
        val escapedCode = code.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        // Build Python list literal for sys.argv
        val argvLiteral = sysArgv.joinToString(", ", "[", "]") { arg ->
            "'" + arg.replace("\\", "\\\\").replace("'", "\\'") + "'"
        }

        return buildString {
            append("import sys\n")
            append("import traceback\n\n")

            // -- sys.argv injection (PC CLI alignment) --
            append("sys.argv = $argvLiteral\n")
            if (scriptPath != null) {
                val escapedScript = scriptPath.replace("\\", "\\\\").replace("'", "\\'")
                append("__file__ = '$escapedScript'\n")
            }
            append("\n")

            // -- stdout/stderr file capture --
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
            append("sys.stderr = _FileOutputCapture(sys.stderr, _log_file_path, '[STDERR] ')\n\n")

            // -- execute user code --
            // __pyhelper_exit_code__ is the channel the Kotlin side reads to
            // get the script's real exit code (sys.exit(N) -> SystemExit). Without
            // this, Chaquopy swallows the exit code and the engine wrongly reports
            // SUCCESS even when the script bailed with code != 0.
            append("__pyhelper_exit_code__ = 0\n")
            append("_user_code = '''${escapedCode}'''\n\n")
            append("try:\n")
            append("    exec(_user_code)\n")
            append("except SystemExit as e:\n")
            append("    if e.code is None:\n")
            append("        __pyhelper_exit_code__ = 0\n")
            append("    elif isinstance(e.code, bool):\n")
            append("        __pyhelper_exit_code__ = 1 if e.code else 0\n")
            append("    elif isinstance(e.code, int):\n")
            append("        __pyhelper_exit_code__ = e.code\n")
            append("    else:\n")
            append("        # str / other -> print and treat as failure\n")
            append("        print('[SystemExit] ' + str(e.code))\n")
            append("        __pyhelper_exit_code__ = 1\n")
            append("    if __pyhelper_exit_code__ != 0:\n")
            append("        print('[SystemExit] ' + str(__pyhelper_exit_code__))\n")
            append("except Exception as e:\n")
            append("    print('[PYTHON EXCEPTION] ' + str(type(e).__name__) + ': ' + str(e))\n")
            append("    print('[PYTHON TRACEBACK] ' + traceback.format_exc())\n")
            append("    __pyhelper_exit_code__ = 1\n")
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
