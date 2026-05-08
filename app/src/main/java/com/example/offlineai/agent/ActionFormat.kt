package com.example.offlineai.agent

import com.example.offlineai.agent.model.AgentAction
import org.json.JSONObject

// Literal placeholders for agent path variables (resolved at runtime by UnifiedActionExecutor.resolveVar).
// Using these constants avoids the awkward ${"$"}{SKILL_DIR} escape inside raw strings.
private const val SKILL_DIR = "\${SKILL_DIR}"
private const val WORKSPACE = "\${WORKSPACE}"

/**
 * Action format interface for different prompt styles.
 * Each format defines: prompt template + parsing logic + KB action descriptions.
 * To add a new action: update AgentAction sealed class, add prompt text here, add parse branch here.
 */
interface ActionFormat {
    fun getFormatName(): String
    fun getFormatDescription(): String
    fun getCorrectExample(): String
    fun getErrorHint(): String
    fun getLastParseError(): String?
    
    /**
     * Parse actions from model response.
     * Extracts ALL actions from response and returns final actions (one per type).
     */
    fun parseActions(response: String): List<AgentAction>
    
    /**
     * Parse single action from model response (legacy method).
     * Default implementation: calls parseActions and returns the last action.
     * @return AgentAction or null if parsing failed.
     */
    fun parseAction(response: String): AgentAction? {
        val actions = parseActions(response)
        return actions.lastOrNull()
    }
    
    /**
     * Get KB action format description for experience summary prompts.
     */
    fun getKbActionDescription(): String
}

/**
 * Base class for action formats with common utilities
 */
abstract class BaseActionFormat : ActionFormat {
    
    /**
     * Extract ALL actions from response (to be implemented by subclasses).
     * This should return ALL actions found in the response, without any filtering.
     */
    protected abstract fun extractAllActions(response: String): List<AgentAction>
    
    /**
     * Parse actions: extract all, group by type, take last of each type.
     * This is completely generic and works for any combination of action types.
     */
    override fun parseActions(response: String): List<AgentAction> {
        val allActions = extractAllActions(response)

        val lastByType = linkedMapOf<String, Pair<Int, AgentAction>>()
        allActions.forEachIndexed { index, action ->
            val key = action::class.simpleName ?: "Unknown"
            lastByType[key] = index to action
        }

        return lastByType.values
            .sortedBy { it.first }
            .map { it.second }
    }
}

// Package-level helpers shared by all format implementations and companion objects
internal fun parseSwipeDirection(dir: String): AgentAction.Swipe.Direction? = when (dir.lowercase()) {
    "up" -> AgentAction.Swipe.Direction.UP
    "down" -> AgentAction.Swipe.Direction.DOWN
    "left" -> AgentAction.Swipe.Direction.LEFT
    "right" -> AgentAction.Swipe.Direction.RIGHT
    else -> null
}

internal fun parseSystemButton(btn: String): AgentAction.SystemButton.Button? = when (btn.lowercase()) {
    "back" -> AgentAction.SystemButton.Button.BACK
    "home" -> AgentAction.SystemButton.Button.HOME
    "menu" -> AgentAction.SystemButton.Button.MENU
    "enter" -> AgentAction.SystemButton.Button.ENTER
    else -> null
}

/**
 * OpenAI Function Call Format Implementation
 * Uses OpenAI function call format: {"name":"tool_name", "parameters":{...}}
 * Supports multiple top-level function-call JSON objects in one response
 */
class OpenAiFuncCallFormat : BaseActionFormat() {

    private var lastParseError: String? = null
    
    override fun getFormatName(): String = "OpenAI FuncCall"
    
    override fun getCorrectExample(): String {
        return """{"name":"click","parameters":{"coordinate":[500,500]}}"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }

    override fun getLastParseError(): String? = lastParseError
    
    override fun getFormatDescription(): String {
        return """**输出格式**：OpenAI Function Call 格式
单个调用：{"name":"[function_name]","parameters":{...}}
批量调用：{"name":"context","parameters":{"text":"..."}...{"name":"[function_name]","parameters":{...}}

## 可用OpenAI Function Call函数列表
{"name":"context","parameters":{"fact":"事实记忆，关键信息"，"text":"状态、错误、下一步、策略"}}
{"name":"click","parameters":{"coordinate":[x,y]}}
{"name":"long_press","parameters":{"coordinate":[x,y]}}
{"name":"double_click","parameters":{"coordinate":[x,y]}}
{"name":"type","parameters":{"text":"文本内容","coordinate":[x,y]}}  
{"name":"swipe","parameters":{"direction":"up/down/left/right","coordinate":[x,y]}}
{"name":"drag","parameters":{"start":[x1,y1],"end":[x2,y2]}}
{"name":"open","parameters":{"text":"应用名"}}
{"name":"system_button","parameters":{"button":"back/home/menu/enter"}}
{"name":"wait","parameters":{"seconds":1}}  # seconds: 1~86400 (1s~24h), default 1. Short wait stabilizes UI; long wait (>3s) shows live countdown on floating window and is interruptible by Stop. Use 60~600s for "check-back later" monitoring loops (e.g. order fill, price watch).
{"name":"terminate","parameters":{"status":"success/fail","text":"用户需要的必要信息（Markdown）","size":"small|medium|large","files":["/sdcard/output.png"]}}
{"name":"ask_user","parameters":{"text":"给用户的问题或操作说明"}}
{"name":"ask_user","parameters":{"text":"请登录后点击完成","url":"https://example.com/login"}}
{"name":"get_app_list","parameters":{}}
{"name":"web_open","parameters":{"url":"https://example.com"}}
{"name":"web_get_content","parameters":{}}
{"name":"web_execute_js","parameters":{"script":"document.querySelector('#login').click()"}}
{"name":"data_memory","parameters":{"operation":"set","key":"name","value":"content"}}
{"name":"data_memory","parameters":{"operation":"get","key":"name"}}
{"name":"data_memory","parameters":{"operation":"list"}}
{"name":"show_output","parameters":{"text":"TEXT，## Result\n| Col | Value |\n|---|---|\n| A | 1 |","size":"small|medium|large"}}

Scheduled task management (only when user explicitly requests):
{"name":"schedule_get","parameters":{}}
{"name":"schedule_set","parameters":{"task_id":1,"enabled":true,"one_shot":false,"weekdays":"1,2,3,4,5","start":"09:30","end":"15:30","interval_min":30,"agent_preset":"__AGENT_PRESETS__","prompt":"任务提示词"}}

File & text operations:
{"name":"create_file","parameters":{"path":"/sdcard/new.txt","content":"line1\nline2"}}
{"name":"read_file","parameters":{"path":"/sdcard/file.txt"}}
{"name":"write_file","parameters":{"path":"/sdcard/file.txt","content":"overwrite content"}}
{"name":"read_lines","parameters":{"path":"/sdcard/file.txt","start_line":1,"end_line":50}}
{"name":"edit_lines","parameters":{"path":"/sdcard/file.txt","start_line":5,"end_line":10,"content":"new line 5\nnew line 6"}}
{"name":"grep","parameters":{"path":"/sdcard/file.txt","keyword":"TODO"}}
{"name":"rename_file","parameters":{"old_path":"/sdcard/a.txt","new_path":"/sdcard/b.txt"}}
{"name":"delete_file","parameters":{"path":"/sdcard/file.txt","recursive":false}}
{"name":"copy_file","parameters":{"src":"/sdcard/src.txt","dst":"/sdcard/dst.txt"}}
{"name":"list_dir","parameters":{"path":"/sdcard/"}}
{"name":"mkdir","parameters":{"path":"/sdcard/new_folder"}}
{"name":"search_files","parameters":{"path":"/sdcard/","keyword":".txt"}}

Python execution:
{"name":"python","parameters":{"argv":["-c","print(1+1)"]}}
{"name":"python","parameters":{"argv":["$SKILL_DIR/stock/scripts/stock.py","quote","600519","AAPL"]}}
{"name":"python","parameters":{"argv":["heavy_train.py","--epochs","10"],"timeout_sec":120}}
{"name":"python","parameters":{"argv":["server.py"],"timeout_sec":0}}
{"name":"python_status","parameters":{}}
{"name":"python_kill","parameters":{}}

"""
    }

    override fun getKbActionDescription(): String = 
    """OpenAI Function Call列表：
{"name":"kb_delete","parameters":{"ids":"ID1,ID2,..."}}
{"name":"kb_insert","parameters":{"text":"Your experience summary here"}}
Note: ids is a comma-separated list of document IDs (from the [ID:xxx] tags above)."""
    
    override fun extractAllActions(response: String): List<AgentAction> {
        lastParseError = null
        val actions = mutableListOf<AgentAction>()

        // Use bracket-balance scanning to extract top-level JSON objects of any nesting depth.
        // The old depth-limited regex "\{(?:[^{}]|(?:\{[^{}]*\}))*\}" only handled 2 levels,
        // causing deeply nested JSON or text fields with newlines to be silently dropped.
        for (jsonStr in extractTopLevelJsonObjects(response)) {
            try {
                val json = JSONObject(jsonStr)
                parseFuncCall(json)?.let { actions.add(it) }
            } catch (e: Exception) {
                lastParseError = "Invalid JSON object in model output: ${e.message ?: "unknown parse error"}"
            }
        }

        if (actions.isEmpty() && lastParseError == null) {
            lastParseError = "No valid function call found. Ensure each call uses {\"name\":\"...\",\"parameters\":{...}} format."
        }

        return actions
    }

    /**
     * Extract all top-level JSON objects from text using bracket-balance scanning.
     * Handles arbitrary nesting depth and text fields containing newlines or special characters.
     * Skips content inside quoted strings so braces within JSON values don't confuse the scanner.
     */
    private fun extractTopLevelJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                val start = i
                var depth = 0
                var inString = false
                var escape = false
                while (i < text.length) {
                    val c = text[i]
                    when {
                        escape -> escape = false
                        c == '\\' && inString -> escape = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> {
                            depth--
                            if (depth == 0) {
                                results.add(text.substring(start, i + 1))
                                break
                            }
                        }
                    }
                    i++
                }
            }
            i++
        }
        return results
    }
    
    private fun parseContentText(params: JSONObject, actionName: String): String? {
        if (!params.has("content")) {
            lastParseError = "$actionName missing required field: content (string)"
            return null
        }
        val raw = params.opt("content")
        if (raw !is String) {
            lastParseError = "$actionName.content must be string"
            return null
        }
        return raw
    }

    private fun parseFuncCall(json: JSONObject): AgentAction? {
        try {
            if (!json.has("name")) {
                lastParseError = "Missing required field: name"
                return null
            }
            if (!json.has("parameters")) {
                lastParseError = "Action '${json.optString("name", "unknown")}' missing required field: parameters"
                return null
            }

            val name = json.getString("name")
            val params = json.getJSONObject("parameters")
            
            return when (name) {
                "click" -> {
                    val c = params.getJSONArray("coordinate")
                    AgentAction.Click(c.getInt(0), c.getInt(1))
                }
                "long_press" -> {
                    val c = params.getJSONArray("coordinate")
                    AgentAction.LongPress(c.getInt(0), c.getInt(1))
                }
                "double_click" -> {
                    val c = params.getJSONArray("coordinate")
                    AgentAction.DoubleClick(c.getInt(0), c.getInt(1))
                }
                "type" -> {
                    // coordinate is REQUIRED: all type actions go through
                    // inputTextAtCoordinate (click + directed setText/paste
                    // on the node under point). This is the only reliable
                    // path across standard EditText, WebView inputs, and
                    // self-drawn boxes (e.g. THS code field). Legacy naked
                    // type is deprecated and rejected at parse time.
                    if (!params.has("coordinate")) {
                        lastParseError =
                            "type action 必须带 coordinate:[x,y]，指向目标输入框屏幕坐标（裸 type 已废弃）"
                        return null
                    }
                    val c = params.getJSONArray("coordinate")
                    AgentAction.Type(
                        params.getString("text"),
                        c.getInt(0),
                        c.getInt(1)
                    )
                }
                "swipe" -> {
                    val dir = parseSwipeDirection(params.getString("direction")) ?: return null
                    val c = if (params.has("coordinate")) params.getJSONArray("coordinate") else null
                    AgentAction.Swipe(dir, c?.getInt(0), c?.getInt(1))
                }
                "drag" -> {
                    val s = params.getJSONArray("start")
                    val e = params.getJSONArray("end")
                    AgentAction.Drag(s.getInt(0), s.getInt(1), e.getInt(0), e.getInt(1))
                }
                "open" -> AgentAction.Open(params.getString("text"))
                "system_button" -> {
                    val btnValue = params.getString("button")
                    val btn = parseSystemButton(btnValue)
                    if (btn == null) {
                        lastParseError = "system_button.button must be one of back/home/menu/enter, got '$btnValue'"
                        return null
                    }
                    AgentAction.SystemButton(btn)
                }
                "wait" -> {
                    // Prefer "seconds"; fall back to legacy "duration" for backward compatibility.
                    val sec = when {
                        params.has("seconds") -> params.optInt("seconds", 1)
                        params.has("duration") -> params.optInt("duration", 1)
                        else -> 1
                    }
                    AgentAction.Wait(sec)
                }
                "terminate" -> {
                    val st = when (params.optString("status", "fail").lowercase()) {
                        "success" -> AgentAction.Terminate.Status.SUCCESS
                        else -> AgentAction.Terminate.Status.FAIL
                    }
                    val filesArray = params.optJSONArray("files")
                    val files = if (filesArray != null) {
                        (0 until filesArray.length()).mapNotNull { filesArray.optString(it).takeIf { s -> s.isNotEmpty() } }
                    } else emptyList()
                    // size: same semantics as show_output (small/medium/large); default small
                    val sizeRaw = params.optString("size", "small").lowercase()
                    val size = if (sizeRaw in listOf("small", "medium", "large")) sizeRaw else "small"
                    AgentAction.Terminate(st, params.optString("text", ""), size, files)
                }
                "context" -> {
                    val fact = params.optString("fact", "")
                    val text = params.optString("text", "")
                    if (fact.isEmpty() && text.isEmpty()) {
                        null
                    } else {
                        AgentAction.Context(text = text, fact = fact)
                    }
                }
                "ask_user" -> AgentAction.AskUser(
                    params.getString("text"),
                    url = params.optString("url").takeIf { it.isNotEmpty() }
                )
                "get_app_list" -> AgentAction.GetAppList
                "kb_insert" -> AgentAction.KbInsert(params.optString("text", ""))
                "kb_delete" -> AgentAction.KbDelete(params.optString("ids", ""))
                "web_open" -> AgentAction.WebOpen(params.getString("url"))
                "web_get_content" -> AgentAction.WebGetContent
                "web_execute_js" -> AgentAction.WebExecuteJs(params.getString("script"))
                "data_memory" -> AgentAction.DataMemory(
                    operation = params.optString("operation", "list"),
                    key = params.optString("key").takeIf { it.isNotEmpty() },
                    value = params.opt("value")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotEmpty() }
                )
                "create_file" -> {
                    val content = parseContentText(params, "create_file") ?: return null
                    AgentAction.CreateFile(
                        path = params.getString("path"),
                        content = content
                    )
                }
                "read_file" -> AgentAction.ReadFile(
                    path = params.getString("path")
                )
                "write_file" -> {
                    val content = parseContentText(params, "write_file") ?: return null
                    AgentAction.WriteFile(
                        path = params.getString("path"),
                        content = content
                    )
                }
                "read_lines" -> AgentAction.ReadLines(
                    path = params.getString("path"),
                    startLine = params.optInt("start_line", 1),
                    endLine = params.optInt("end_line", 50)
                )
                "edit_lines" -> {
                    if (!params.has("start_line")) {
                        lastParseError = "edit_lines missing required field: start_line"
                        return null
                    }
                    if (!params.has("end_line")) {
                        lastParseError = "edit_lines missing required field: end_line"
                        return null
                    }
                    val content = parseContentText(params, "edit_lines") ?: return null
                    AgentAction.EditLines(
                        path = params.getString("path"),
                        startLine = params.getInt("start_line"),
                        endLine = params.getInt("end_line"),
                        content = content
                    )
                }
                "grep" -> AgentAction.Grep(
                    path = params.getString("path"),
                    keyword = params.getString("keyword")
                )
                "rename_file" -> AgentAction.RenameFile(
                    oldPath = params.getString("old_path"),
                    newPath = params.getString("new_path")
                )
                "delete_file" -> AgentAction.DeleteFile(
                    path = params.getString("path"),
                    recursive = params.optBoolean("recursive", false)
                )
                "copy_file" -> AgentAction.CopyFile(
                    src = params.getString("src"),
                    dst = params.getString("dst")
                )
                "list_dir" -> AgentAction.ListDir(
                    path = params.getString("path")
                )
                "mkdir" -> AgentAction.Mkdir(params.getString("path"))
                "search_files" -> AgentAction.SearchFiles(
                    path = if (params.has("path")) params.getString("path") else params.getString("dir_path"),
                    keyword = params.getString("keyword")
                )
                "python" -> {
                    // Argv-only: no shell. Semantics like subprocess.run([...])
                    if (!params.has("argv")) {
                        lastParseError = "python requires 'argv' (JSON array). NO SHELL: use argv=[\"script.py\",\"arg1\",...] or argv=[\"-c\",\"code\"]."
                        return null
                    }
                    val argvArray = params.getJSONArray("argv")
                    val argvList = (0 until argvArray.length()).map { argvArray.getString(it) }
                    if (argvList.isEmpty()) {
                        lastParseError = "python argv array cannot be empty"
                        return null
                    }
                    val rawTimeout = params.optInt("timeout_sec", 60)
                    // Guardrail: some long-running skills (e.g. stockquant) need a
                    // realistic sync budget. If the caller picks a too-small value
                    // (>0 and <240s), raise it to 300s so we don't downgrade to
                    // background prematurely. 0 (fire-and-forget async) is kept as-is.
                    val needsBiggerBudget = argvList.any { arg ->
                        val lower = arg.lowercase()
                        lower.endsWith("stockquant.py") || lower.contains("/stockquant/scripts/")
                    }
                    val effectiveTimeout = if (needsBiggerBudget && rawTimeout in 1..239) 300 else rawTimeout
                    AgentAction.Python(
                        argv = argvList,
                        timeoutSec = effectiveTimeout
                    )
                }
                "python_status" -> AgentAction.PythonStatus()
                "python_kill" -> AgentAction.PythonKill()
                "show_output" -> AgentAction.ShowOutput(
                    text = params.optString("text", ""),
                    size = params.optString("size", "small").lowercase().let {
                        if (it in listOf("small", "medium", "large")) it else "small"
                    }
                )
                "schedule_get" -> AgentAction.ScheduleGet
                "schedule_set" -> {
                    if (!params.has("task_id")) {
                        lastParseError = "schedule_set missing required field: task_id (1..4)"
                        return null
                    }
                    // Helper to read optional Boolean: present but non-bool => null (ignored)
                    fun optBoolOrNull(key: String): Boolean? =
                        if (params.has(key)) params.optBoolean(key, false) else null
                    fun optIntOrNull(key: String): Int? =
                        if (params.has(key)) params.optInt(key, 0) else null
                    fun optStrOrNull(key: String): String? =
                        if (params.has(key)) params.optString(key, "") else null
                    // Accept both `agent_preset` (new) and `prompt_file` (legacy) for backward compat.
                    val presetValue = optStrOrNull("agent_preset") ?: optStrOrNull("prompt_file")
                    AgentAction.ScheduleSet(
                        taskId = params.getInt("task_id"),
                        enabled = optBoolOrNull("enabled"),
                        oneShot = optBoolOrNull("one_shot"),
                        weekdays = optStrOrNull("weekdays"),
                        start = optStrOrNull("start"),
                        end = optStrOrNull("end"),
                        intervalMin = optIntOrNull("interval_min"),
                        agentPreset = presetValue,
                        prompt = optStrOrNull("prompt")
                    )
                }
                else -> {
                    lastParseError = "Unknown action name: '$name'"
                    null
                }
            }
        } catch (e: Exception) {
            lastParseError = "Failed to parse action '${json.optString("name", "unknown")}': ${e.message ?: "invalid parameters"}"
            return null
        }
    }
    
}

object ActionFormatRegistry {
    private val openAiFormat: ActionFormat = OpenAiFuncCallFormat()

    fun getFormatForApi(apiUrl: String, modelName: String = ""): ActionFormat {
        return openAiFormat
    }

    fun getFormatByName(name: String): ActionFormat? {
        return if (name.equals(openAiFormat.getFormatName(), ignoreCase = true)) {
            openAiFormat
        } else {
            null
        }
    }

    fun getAllFormats(): List<ActionFormat> {
        return listOf(openAiFormat)
    }
}
