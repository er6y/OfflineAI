package com.example.offlineai.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent Action Types - MAI-UI Action Space
 * Based on MAI-UI prompt.py action definitions
 */
sealed class AgentAction {
    abstract fun toJson(): JSONObject
    
    /**
     * Whether this action requires a screenshot for the next step.
     * Some actions (like web operations, get_app_list) return structured data
     * and don't need visual information for the next decision.
     */
    open fun needsScreenshot(): Boolean = true
    
    /**
     * Click at coordinate
     * {"action": "click", "coordinate": [x, y]}
     */
    data class Click(val x: Int, val y: Int) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "click")
            put("coordinate", org.json.JSONArray().apply {
                put(x)
                put(y)
            })
        }
    }
    
    /**
     * Long press at coordinate
     * {"action": "long_press", "coordinate": [x, y]}
     */
    data class LongPress(val x: Int, val y: Int) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "long_press")
            put("coordinate", org.json.JSONArray().apply {
                put(x)
                put(y)
            })
        }
    }
    
    /**
     * Double click at coordinate
     * {"action": "double_click", "coordinate": [x, y]}
     */
    data class DoubleClick(val x: Int, val y: Int) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "double_click")
            put("coordinate", org.json.JSONArray().apply {
                put(x)
                put(y)
            })
        }
    }
    
    /**
     * Type text.
     *
     * Two forms:
     *   1) {"action": "type", "text": "..."}                       -- legacy
     *   2) {"action": "type", "coordinate": [x, y], "text": "..."} -- targeted
     *
     * Form 2 clicks at (x, y) first, then sets text directly on the node
     * under that point via Accessibility. This is required for apps whose
     * input boxes don't expose FOCUS_INPUT after a click (common in
     * self-drawn / WebView-based mainland finance apps e.g. THS).
     * When x/y are null, falls back to legacy findFocus(FOCUS_INPUT) path.
     */
    data class Type(
        val text: String,
        val x: Int? = null,
        val y: Int? = null
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "type")
            put("text", text)
            if (x != null && y != null) {
                put("coordinate", org.json.JSONArray().apply {
                    put(x)
                    put(y)
                })
            }
        }
    }
    
    /**
     * Swipe in direction, optionally at specific coordinate
     * {"action": "swipe", "direction": "up/down/left/right", "coordinate": [x, y]}
     */
    data class Swipe(
        val direction: Direction,
        val x: Int? = null,
        val y: Int? = null
    ) : AgentAction() {
        enum class Direction(val value: String) {
            UP("up"),
            DOWN("down"),
            LEFT("left"),
            RIGHT("right")
        }
        
        override fun toJson() = JSONObject().apply {
            put("action", "swipe")
            put("direction", direction.value)
            if (x != null && y != null) {
                put("coordinate", org.json.JSONArray().apply {
                    put(x)
                    put(y)
                })
            }
        }
    }
    
    /**
     * Open app by name
     * {"action": "open", "text": "app_name"}
     */
    data class Open(val appName: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "open")
            put("text", appName)
        }
    }
    
    /**
     * Drag from start to end coordinate
     * {"action": "drag", "start_coordinate": [x1, y1], "end_coordinate": [x2, y2]}
     */
    data class Drag(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "drag")
            put("start_coordinate", org.json.JSONArray().apply {
                put(startX)
                put(startY)
            })
            put("end_coordinate", org.json.JSONArray().apply {
                put(endX)
                put(endY)
            })
        }
    }
    
    /**
     * Press system button
     * {"action": "system_button", "button": "back/home/menu/enter"}
     */
    data class SystemButton(val button: Button) : AgentAction() {
        enum class Button(val value: String) {
            BACK("back"),
            HOME("home"),
            MENU("menu"),
            ENTER("enter")
        }
        
        override fun toJson() = JSONObject().apply {
            put("action", "system_button")
            put("button", button.value)
        }
    }
    
    /**
     * Wait for a given number of seconds.
     * {"action": "wait"}                 -> 1 second (default, used for UI stabilization)
     * {"action": "wait", "seconds": 300} -> 5 minutes (used by long-horizon monitoring loops)
     *
     * Allowed range: [1, 86400] (1 second ~ 24 hours). Values outside the range
     * are clamped by UnifiedActionExecutor.validateActionParameters.
     * During the wait, the floating-window status bar shows a live countdown
     * ("Agent等待剩余 hh:mm:ss，约于 HH:MM:SS 继续") refreshed every 1 second.
     */
    data class Wait(val seconds: Int = 1) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "wait")
            if (seconds != 1) put("seconds", seconds)
        }
    }
    
    /**
     * Get list of available apps
     * {"action": "get_app_list"}
     */
    object GetAppList : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "get_app_list")
        }
        override fun needsScreenshot() = false  // Returns app list, no screenshot needed
    }
    
    /**
     * Terminate task with status.
     * {"action": "terminate", "status": "success/fail", "text": "markdown result", "size": "small|medium|large", "files": ["/path/to/file"]}
     * text:  user-facing essential information in Markdown (not a short summary).
     * size:  floating-window size preset, same semantics as show_output.
     *        "small"  (default, compact 240dp)
     *        "medium" (2/3 screen width, half screen height scrollable)
     *        "large"  (full screen width, 2/3 screen height scrollable)
     * files: optional list of file paths to attach (image/audio/generic file).
     */
    data class Terminate(
        val status: Status,
        val text: String = "",
        val size: String = "small",
        val files: List<String> = emptyList()
    ) : AgentAction() {
        enum class Status(val value: String) {
            SUCCESS("success"),
            FAIL("fail")
        }

        override fun toJson() = JSONObject().apply {
            put("action", "terminate")
            put("status", status.value)
            if (text.isNotEmpty()) put("text", text)
            put("size", size)
            if (files.isNotEmpty()) put("files", JSONArray(files))
        }
        override fun needsScreenshot() = false  // Task ended, no screenshot needed
    }
    
    /**
     * Insert content into knowledge base
     * {"action": "kb_insert", "text": "content to insert"}
     */
    data class KbInsert(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "kb_insert")
            put("text", text)
        }
    }
    
    /**
     * Delete chunks from knowledge base by IDs
     * {"action": "kb_delete", "ids": "42,57,103"}
     */
    data class KbDelete(val ids: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "kb_delete")
            put("ids", ids)
        }
    }
    
    /**
     * Data Memory - structured KV storage for task business data (e.g., extracted email content)
     * Separate from context: context tracks execution state, data_memory stores raw extracted data
     * Only keys are injected into prompt each step; values retrieved on demand via get or {{key}} placeholder
     *
     * {"action": "data_memory", "operation": "set", "key": "name", "value": "content"}
     * {"action": "data_memory", "operation": "get", "key": "name"}
     * {"action": "data_memory", "operation": "delete", "key": "name"}
     * {"action": "data_memory", "operation": "list"}
     * {"action": "data_memory", "operation": "clear"}
     */
    data class DataMemory(
        val operation: String,
        val key: String? = null,
        val value: String? = null
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "data_memory")
            put("operation", operation)
            key?.let { put("key", it) }
            value?.let { put("value", it) }
        }
        override fun needsScreenshot() = false
    }

    /**
     * Create a new file with content. Fails if file already exists.
     * {"name": "create_file", "parameters": {"path": "/sdcard/new.txt", "content": "line1\nline2"}}
     */
    data class CreateFile(
        val path: String,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "create_file")
            put("path", path)
            put("content", content)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Context - update agent's memory/context for next step
     * {"action": "context", "fact": "append-only stable facts", "text": "current round summary"}
     * This action does not execute anything, only updates currentContext
     * Must be output with every step to maintain memory continuity
     */
    data class Context(
        val text: String = "",
        val fact: String = ""
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "context")
            if (fact.isNotEmpty()) put("fact", fact)
            put("text", text)
        }
        override fun needsScreenshot() = false  // Context update doesn't need screenshot
    }
    
    /**
     * Ask user - request user intervention with optional question/instruction
     * {"action": "ask_user", "text": "question or instruction for user"}
     * {"action": "ask_user", "text": "hint text", "url": "https://example.com"}
     * When url is provided: floating window expands to show WebView for user to login/interact
     * After user confirms (with optional text input), agent continues with user response injected
     */
    data class AskUser(val text: String, val url: String? = null) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "ask_user")
            put("text", text)
            url?.let { put("url", it) }
        }
        // No screenshot needed: user handles the task, then confirms to continue
        override fun needsScreenshot() = false
    }
    
    /**
     * Open URL in WebView
     * {"action": "web_open", "url": "https://example.com"}
     */
    data class WebOpen(val url: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "web_open")
            put("url", url)
        }
        override fun needsScreenshot() = false  // Web operations use DOM, not screenshots
    }
    
    /**
     * Get current page content from WebView (DOM structure + text)
     * {"action": "web_get_content"}
     */
    object WebGetContent : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "web_get_content")
        }
        override fun needsScreenshot() = false  // Returns structured DOM data, no screenshot needed
    }
    
    /**
     * Execute JavaScript in WebView
     * {"action": "web_execute_js", "script": "document.querySelector('#login').click()"}
     */
    data class WebExecuteJs(val script: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "web_execute_js")
            put("script", script)
        }
        override fun needsScreenshot() = false  // JS execution, no screenshot needed
    }

    /**
     * Read entire file content. Returns full text (truncated at 2000 lines).
     * {"name": "read_file", "parameters": {"path": "/sdcard/file.txt"}}
     */
    data class ReadFile(
        val path: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "read_file")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Overwrite file with content. Creates file if not exists, overwrites if exists.
     * {"name": "write_file", "parameters": {"path": "/sdcard/file.txt", "content": "new content"}}
     */
    data class WriteFile(
        val path: String,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "write_file")
            put("path", path)
            put("content", content)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Read lines [start_line, end_line] from file. Out-of-range reads to EOF without error.
     * {"name": "read_lines", "parameters": {"path": "/sdcard/file.txt", "start_line": 1, "end_line": 50}}
     */
    data class ReadLines(
        val path: String,
        val startLine: Int,
        val endLine: Int
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "read_lines")
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Replace lines [start_line, end_line] with new content. Auto-saves.
     * {"name": "edit_lines", "parameters": {"path": "/sdcard/file.txt", "start_line": 5, "end_line": 10, "content": "new line 1\nnew line 2"}}
     */
    data class EditLines(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "edit_lines")
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
            put("content", content)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search text content in file, returns matching line numbers + content snippets.
     * {"name": "grep", "parameters": {"path": "/sdcard/file.txt", "keyword": "TODO"}}
     */
    data class Grep(
        val path: String,
        val keyword: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "grep")
            put("path", path)
            put("keyword", keyword)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Rename or move a file. Fails if new_path already exists.
     * {"name": "rename_file", "parameters": {"old_path": "/sdcard/a.txt", "new_path": "/sdcard/b.txt"}}
     */
    data class RenameFile(
        val oldPath: String,
        val newPath: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "rename_file")
            put("old_path", oldPath)
            put("new_path", newPath)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Delete file or directory.
     * {"name": "delete_file", "parameters": {"path": "/sdcard/file.txt", "recursive": false}}
     */
    data class DeleteFile(
        val path: String,
        val recursive: Boolean = false
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "delete_file")
            put("path", path)
            put("recursive", recursive)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Copy file or directory. Overwrites destination if exists.
     * {"name": "copy_file", "parameters": {"src": "/sdcard/src.txt", "dst": "/sdcard/dst.txt"}}
     */
    data class CopyFile(
        val src: String,
        val dst: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "copy_file")
            put("src", src)
            put("dst", dst)
        }
        override fun needsScreenshot() = false
    }

    /**
     * List directory contents.
     * {"name": "list_dir", "parameters": {"path": "/sdcard/"}}
     */
    data class ListDir(
        val path: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "list_dir")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Create directory. Silent success if already exists.
     * {"name": "mkdir", "parameters": {"path": "/sdcard/new_folder"}}
     */
    data class Mkdir(val path: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "mkdir")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search files by name keyword in directory.
     * {"name": "search_files", "parameters": {"path": "/sdcard/", "keyword": ".txt"}}
     */
    data class SearchFiles(
        val path: String,
        val keyword: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "search_files")
            put("path", path)
            put("keyword", keyword)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Run Python. Argv-only API (no shell). Semantics equivalent to
     * `subprocess.run([...])` on PC: no shell expansion, no pipes, no redirects.
     *
     * Forms:
     *   Script:  {"argv":["/path/stock.py","quote","600519"]}
     *   Inline:  {"argv":["-c","print(1+1)"]}
     *   With leading python: {"argv":["python","stock.py","quote","600519"]}   (auto-stripped)
     *
     * Single knob controls sync/async:
     *   timeout_sec > 0  : sync, block up to N seconds; on timeout leave job running in background
     *   timeout_sec = 0  : fully async, return immediately with RUNNING (fire-and-forget)
     *   (default 60)     : sync 60 seconds — covers most cases
     *
     * Backend normalization:
     *   1. Resolve ${SKILL_DIR}/${WORKSPACE} in every argv element
     *   2. Strip leading "python"/"python3" if present
     *   3. If argv[0] == "-c" -> exec inline code; else treat argv[0] as script path
     *   4. Set sys.argv, __file__ (for script), __name__="__main__"
     */
    data class Python(
        val argv: List<String>,
        val timeoutSec: Int = 60
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python")
            put("argv", JSONArray(argv))
            if (timeoutSec != 60) put("timeout_sec", timeoutSec)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Query Python instance status.
     * Returns unified JSON: {status, output, exit_code, duration_sec, log_file, truncated}
     * {"action": "python_status"}
     */
    data class PythonStatus(
        val dummy: String = ""  // No parameters needed
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python_status")
        }
        override fun needsScreenshot() = false
    }

    /**
     * Kill the current running Python instance.
     * {"action": "python_kill"}
     */
    data class PythonKill(
        val dummy: String = ""
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python_kill")
        }
        override fun needsScreenshot() = false
    }

    /**
     * Show output text in the floating window output area, with optional size control.
     * size: "small" (default, compact) / "medium" (2/3 screen width, half height) / "large" (full width, 2/3 height)
     * text supports Markdown (tables, bold, code blocks, lists, etc.)
     * {"name":"show_output","parameters":{"text":"## Result\n| Col1 | Col2 |\n|---|---|\n| a | b |","size":"medium"}}
     */
    data class ShowOutput(
        val text: String,
        val size: String = "small"  // "small" / "medium" / "large"
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "show_output")
            put("text", text)
            put("size", size)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Query status of all 4 scheduled task slots.
     * Returns a markdown table summarizing master switch and per-task config.
     * {"name":"schedule_get","parameters":{}}
     */
    object ScheduleGet : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "schedule_get")
        }
        override fun needsScreenshot() = false
    }

    /**
     * Patch-update a single scheduled task slot.
     * Only fields present in parameters are updated; others keep their existing values.
     * The master switch (schedule_enabled) is NOT controllable by agent — only the user
     * can toggle it via the Settings UI. This action only modifies per-task configuration.
     *
     * taskId: 1..4 (required)
     * Optional fields (all patch-style):
     *   enabled / oneShot: Boolean
     *   weekdays: comma-separated "1,2,3,4,5" (1=Mon .. 7=Sun)
     *   start / end: "HH:MM" string
     *   intervalMin: minutes between triggers (>=1)
     *   agentPreset: preset name (filename without .txt) under agent_user/ (e.g. "stock_agent").
     *                Use schedule_get to discover available presets, or list_dir on agent_user/.
     *                Empty string (unset) keeps the previous value.
     *   prompt: explicit prompt text (overrides agentPreset if both set, same as UI)
     *
     * {"name":"schedule_set","parameters":{"task_id":1,"enabled":true,"start":"09:30","end":"15:30","interval_min":30,"agent_preset":"stock_agent"}}
     */
    data class ScheduleSet(
        val taskId: Int,
        val enabled: Boolean? = null,
        val oneShot: Boolean? = null,
        val weekdays: String? = null,
        val start: String? = null,
        val end: String? = null,
        val intervalMin: Int? = null,
        val agentPreset: String? = null,
        val prompt: String? = null
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "schedule_set")
            put("task_id", taskId)
            enabled?.let { put("enabled", it) }
            oneShot?.let { put("one_shot", it) }
            weekdays?.let { put("weekdays", it) }
            start?.let { put("start", it) }
            end?.let { put("end", it) }
            intervalMin?.let { put("interval_min", it) }
            agentPreset?.let { put("agent_preset", it) }
            prompt?.let { put("prompt", it) }
        }
        override fun needsScreenshot() = false
    }

    // ShowMedia removed - replaced by Terminate.files parameter
}

/**
 * Parsed model response containing action
 */
data class AgentResponse(
    val action: AgentAction
)

/**
 * Execution result for an action
 */
data class ExecutionResult(
    val success: Boolean,
    val message: String = "",
    val error: Throwable? = null,
    val returnData: String? = null
)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "ogg", "flac", "aac")

/**
 * Classify file type by extension.
 * @return "image", "audio", or "file"
 */
fun classifyFileType(ext: String): String = when (ext.lowercase()) {
    in IMAGE_EXTENSIONS -> "image"
    in AUDIO_EXTENSIONS -> "audio"
    else -> "file"
}
