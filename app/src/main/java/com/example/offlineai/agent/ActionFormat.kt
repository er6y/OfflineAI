package com.example.offlineai.agent

import com.example.offlineai.agent.model.AgentAction
import org.json.JSONObject

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
        
        // Group by action type (using class name as key)
        val groupedByType = allActions.groupBy { it::class.simpleName ?: "Unknown" }
        
        // Take last action from each group
        return groupedByType.values.mapNotNull { it.lastOrNull() }
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
 * Supports both single call and batch calls via tool_calls array
 */
class OpenAiFuncCallFormat : BaseActionFormat() {
    
    override fun getFormatName(): String = "OpenAI FuncCall"
    
    override fun getCorrectExample(): String {
        return """{"name":"click","parameters":{"coordinate":[500,500]}}"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：OpenAI Function Call 格式
单个调用：{"name":"[function_name]","parameters":{...}}
批量调用：{"tool_calls":[{"name":"context","parameters":{"text":"..."}},{"name":"[function_name]","parameters":{...}}]}

## 可用OpenAI Function Call函数列表
{"name":"context","parameters":{"text":"当前状态、关键信息、错误、坐标、策略"}}
{"name":"click","parameters":{"coordinate":[x,y]}}
{"name":"long_press","parameters":{"coordinate":[x,y]}}
{"name":"double_click","parameters":{"coordinate":[x,y]}}
{"name":"type","parameters":{"text":"文本内容"}}
{"name":"swipe","parameters":{"direction":"up/down/left/right","coordinate":[x,y]}}
{"name":"drag","parameters":{"start":[x1,y1],"end":[x2,y2]}}
{"name":"open","parameters":{"text":"应用名"}}
{"name":"system_button","parameters":{"button":"back/home/menu/enter"}}
{"name":"wait","parameters":{"duration":3}}
{"name":"terminate","parameters":{"status":"success/fail","text":"总结"}}
{"name":"ask_user","parameters":{"text":"给用户的问题或操作说明"}}
{"name":"get_app_list","parameters":{}}
{"name":"web_open","parameters":{"url":"https://example.com"}}
{"name":"web_get_content","parameters":{}}
{"name":"web_execute_js","parameters":{"script":"document.querySelector('#login').click()"}}
{"name":"data_memory","parameters":{"operation":"set","key":"name","value":"content"}}
{"name":"data_memory","parameters":{"operation":"get","key":"name"}}
{"name":"data_memory","parameters":{"operation":"list"}}

注意：支持批量调用，使用 tool_calls 数组"""
    }
    
    override fun getKbActionDescription(): String = 
    """OpenAI Function Call列表：
{"name":"kb_delete","parameters":{"ids":"ID1,ID2,..."}}
{"name":"kb_insert","parameters":{"text":"Your experience summary here"}}
Note: ids is a comma-separated list of document IDs (from the [ID:xxx] tags above)."""
    
    override fun extractAllActions(response: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()

        // Use bracket-balance scanning to extract top-level JSON objects of any nesting depth.
        // The old depth-limited regex "\{(?:[^{}]|(?:\{[^{}]*\}))*\}" only handled 2 levels,
        // causing tool_calls JSON (3+ levels deep) or text fields with newlines to be silently dropped.
        for (jsonStr in extractTopLevelJsonObjects(response)) {
            try {
                val json = JSONObject(jsonStr)

                // Handle tool_calls array format
                if (json.has("tool_calls")) {
                    val toolCalls = json.getJSONArray("tool_calls")
                    for (i in 0 until toolCalls.length()) {
                        parseFuncCall(toolCalls.getJSONObject(i))?.let { actions.add(it) }
                    }
                } else {
                    // Handle single function call format
                    parseFuncCall(json)?.let { actions.add(it) }
                }
            } catch (_: Exception) {}
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
    
    private fun parseFuncCall(json: JSONObject): AgentAction? {
        try {
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
                "type" -> AgentAction.Type(params.getString("text"))
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
                    val btn = parseSystemButton(params.getString("button")) ?: return null
                    AgentAction.SystemButton(btn)
                }
                "wait" -> AgentAction.Wait
                "terminate" -> {
                    val st = when (params.optString("status", "fail").lowercase()) {
                        "success" -> AgentAction.Terminate.Status.SUCCESS
                        else -> AgentAction.Terminate.Status.FAIL
                    }
                    AgentAction.Terminate(st, params.optString("text", ""))
                }
                "context" -> AgentAction.Context(params.getString("text"))
                "ask_user" -> AgentAction.AskUser(params.getString("text"))
                "get_app_list" -> AgentAction.GetAppList
                "kb_insert" -> AgentAction.KbInsert(params.optString("text", ""))
                "kb_delete" -> AgentAction.KbDelete(params.optString("ids", ""))
                "web_open" -> AgentAction.WebOpen(params.getString("url"))
                "web_get_content" -> AgentAction.WebGetContent
                "web_execute_js" -> AgentAction.WebExecuteJs(params.getString("script"))
                "data_memory" -> AgentAction.DataMemory(
                    operation = params.optString("operation", "list"),
                    key = params.optString("key").takeIf { it.isNotEmpty() },
                    value = params.optString("value").takeIf { it.isNotEmpty() }
                )
                else -> null
            }
        } catch (e: Exception) {
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
