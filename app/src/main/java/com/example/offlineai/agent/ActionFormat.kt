package com.example.offlineai.agent

import com.example.offlineai.agent.model.AgentAction
import org.json.JSONArray
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
    fun getThinkingTag(): String
    
    /**
     * Parse actions with universal CoT strategy.
     * 
     * Universal CoT Strategy (completely generic, no hardcoded types):
     * 1. Strip thinking tag content (drafts)
     * 2. Extract ALL actions from cleaned content
     * 3. Group actions by type (Click, Type, KbDelete, KbInsert, etc.)
     * 4. Take LAST action from each group
     * 5. Return all final actions (may be multiple types)
     * 
     * Examples:
     * - KB scenario: returns [last KbDelete, last KbInsert]
     * - Step with 1 type: returns [last Click]
     * - Step with N types: returns [last Click, last Type, ...]
     * 
     * @return Pair of (thinking text, list of final actions - one per type)
     */
    fun parseActionsWithCoT(response: String): Pair<String?, List<AgentAction>>
    
    /**
     * Parse action from model response (legacy single-action method).
     * Default implementation: calls parseActionsWithCoT and returns the last action.
     * @return Pair of (thinking, AgentAction) or null if parsing failed.
     */
    fun parseAction(response: String): Pair<String?, AgentAction?>? {
        val (thinking, actions) = parseActionsWithCoT(response)
        return if (actions.isNotEmpty()) Pair(thinking, actions.last()) else null
    }
    
    fun isCompatibleWith(modelName: String): Boolean
    
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
     * Extract thinking from response (common for all formats)
     * Tries the specified tag first, then falls back to common alternatives
     * to handle models that use <think> vs <thinking> interchangeably
     */
    protected fun extractThinking(response: String, thinkTag: String = "thinking"): String? {
        // Try the specified tag first
        val pattern = Regex("<$thinkTag>\\s*(.+?)\\s*</$thinkTag>", RegexOption.DOT_MATCHES_ALL)
        val result = pattern.find(response)?.groupValues?.get(1)?.trim()
        if (!result.isNullOrEmpty()) return result

        // Fallback: try alternative tags (models may use <think> instead of <thinking> or vice versa)
        val fallbackTags = listOf("thinking", "think").filter { it != thinkTag }
        for (tag in fallbackTags) {
            val fallbackPattern = Regex("<$tag>\\s*(.+?)\\s*</$tag>", RegexOption.DOT_MATCHES_ALL)
            val fallbackResult = fallbackPattern.find(response)?.groupValues?.get(1)?.trim()
            if (!fallbackResult.isNullOrEmpty()) return fallbackResult
        }
        return null
    }
    
    /**
     * Strip ALL thinking tag content from response (both <thinking> and <think>).
     * Returns the cleaned text with only action content remaining.
     * This ensures CoT drafts inside thinking tags are never matched as real actions.
     */
    protected fun stripThinking(response: String): String {
        var cleaned = response
        for (tag in listOf("thinking", "think")) {
            cleaned = cleaned.replace(Regex("<$tag>.*?</$tag>", RegexOption.DOT_MATCHES_ALL), "")
            cleaned = cleaned.replace(Regex("<$tag>.*", RegexOption.DOT_MATCHES_ALL), "")
        }
        return cleaned.trim()
    }
    
    /**
     * Extract ALL actions from cleaned response (to be implemented by subclasses).
     * This should return ALL actions found in the response, without any filtering.
     */
    protected abstract fun extractAllActions(cleaned: String): List<AgentAction>
    
    /**
     * Universal CoT implementation: group by action type, take last of each type.
     * This is completely generic and works for any combination of action types.
     */
    override fun parseActionsWithCoT(response: String): Pair<String?, List<AgentAction>> {
        val thinking = extractThinking(response, getThinkingTag())
        val cleaned = stripThinking(response)
        
        // Extract all actions from cleaned content
        val allActions = extractAllActions(cleaned)
        
        // Group by action type (using class name as key)
        val groupedByType = allActions.groupBy { it::class.simpleName ?: "Unknown" }
        
        // Take last action from each group
        val finalActions = groupedByType.values.mapNotNull { it.lastOrNull() }
        
        return Pair(thinking, finalActions)
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

// ============================================================================
// Format Implementations
// To add a new action: 1) Add to AgentAction sealed class  2) Add prompt text in getFormatDescription()
//                       3) Add parse branch in parseAction()  4) Add execute branch in UnifiedActionExecutor
// ============================================================================

/**
 * MAI-UI Format Implementation
 * Uses JSON format: {"action":"click","coordinate":[x,y]}
 */
class MaiUiFormat : BaseActionFormat() {
    
    override fun getFormatName(): String = "MAI-UI"
    
    override fun getThinkingTag(): String = "thinking"
    
    override fun getCorrectExample(): String {
        return """<thinking>观察到屏幕中央有"确定"按钮，位置约50%,50%，坐标[500,500]，需要点击</thinking>
<tool_call>{"action":"click","coordinate":[500,500]}</tool_call>"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<tool_call>{"action":"context","text":"..."}</tool_call>
<tool_call>{"action":"[Action_Name]",...}</tool_call>

${AgentPrompts.CONTEXT_ACTION_REQUIREMENTS}

## 动作列表
{"action":"context","text":"当前状态、关键信息、错误、坐标、策略"}
{"action":"click","coordinate":[x,y]}
{"action":"long_press","coordinate":[x,y]}
{"action":"double_click","coordinate":[x,y]}
{"action":"type","text":"文本"}
{"action":"swipe","direction":"up/down/left/right","coordinate":[x,y]}
{"action":"drag","start_coordinate":[x1,y1],"end_coordinate":[x2,y2]}
{"action":"open","text":"应用名"}
{"action":"system_button","button":"back/home/menu/enter"}
{"action":"wait","duration":3}
{"action":"terminate","status":"success/fail","text":"总结"}
{"action":"ask_user","text":"给用户的问题或操作说明"}
{"action":"get_app_list"}
{"action":"web_open","url":"https://example.com"}
{"action":"web_get_content"}
{"action":"web_execute_js","script":"document.querySelector('#login').click()"}
{"action":"data_memory","operation":"set","key":"name","value":"content"}
{"action":"data_memory","operation":"get","key":"name"}
{"action":"data_memory","operation":"list"}
严格按照以上JSON格式输出"""
    }
    
    override fun getKbActionDescription(): String = """```
{"action":"kb_delete","ids":"ID1,ID2,..."}
{"action":"kb_insert","text":"Your experience summary here"}
```
Note: ids is a comma-separated list of document IDs (from the [ID:xxx] tags above)."""
    
    override fun extractAllActions(cleaned: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        
        // Extract all JSON objects (supports multiple <tool_call> tags)
        val jsonPattern = Regex("\\{\\s*\"action\"\\s*:.*?\\}", RegexOption.DOT_MATCHES_ALL)
        for (match in jsonPattern.findAll(cleaned)) {
            try {
                val json = JSONObject(match.value.trim())
                parseMaiUiJson(json)?.let { actions.add(it) }
            } catch (_: Exception) {}
        }
        
        return actions
    }
    
    override fun isCompatibleWith(modelName: String): Boolean {
        return !modelName.contains("glm", ignoreCase = true) &&
               !modelName.contains("chatglm", ignoreCase = true) &&
               !modelName.contains("autoglm", ignoreCase = true)
    }
    
    companion object {
        /**
         * Parse MAI-UI JSON into AgentAction. Shared by parseAction() and KB action parsing.
         */
        fun parseMaiUiJson(json: JSONObject): AgentAction? {
            val actionType = json.optString("action", "")
            if (actionType.isEmpty()) return null
            
            return when (actionType) {
                "click" -> {
                    val c = json.getJSONArray("coordinate")
                    AgentAction.Click(c.getInt(0), c.getInt(1))
                }
                "long_press" -> {
                    val c = json.getJSONArray("coordinate")
                    AgentAction.LongPress(c.getInt(0), c.getInt(1))
                }
                "double_click" -> {
                    val c = json.getJSONArray("coordinate")
                    AgentAction.DoubleClick(c.getInt(0), c.getInt(1))
                }
                "type" -> AgentAction.Type(json.getString("text"))
                "swipe" -> {
                    val dir = parseSwipeDirection(json.getString("direction")) ?: return null
                    val c = if (json.has("coordinate")) json.getJSONArray("coordinate") else null
                    AgentAction.Swipe(dir, c?.getInt(0), c?.getInt(1))
                }
                "drag" -> {
                    val sc = json.getJSONArray("start_coordinate")
                    val ec = json.getJSONArray("end_coordinate")
                    AgentAction.Drag(sc.getInt(0), sc.getInt(1), ec.getInt(0), ec.getInt(1))
                }
                "open" -> AgentAction.Open(json.getString("text"))
                "system_button" -> {
                    val btn = parseSystemButton(json.optString("button", "").ifEmpty { json.optString("text", "") }) ?: return null
                    AgentAction.SystemButton(btn)
                }
                "wait" -> AgentAction.Wait
                "terminate" -> {
                    val st = when (json.optString("status", "fail").lowercase()) {
                        "success" -> AgentAction.Terminate.Status.SUCCESS
                        else -> AgentAction.Terminate.Status.FAIL
                    }
                    AgentAction.Terminate(st, json.optString("text", ""))
                }
                "context" -> AgentAction.Context(json.getString("text"))
                "ask_user" -> AgentAction.AskUser(json.getString("text"))
                "get_app_list" -> AgentAction.GetAppList
                "kb_insert" -> AgentAction.KbInsert(json.optString("text", ""))
                "kb_delete" -> AgentAction.KbDelete(json.optString("ids", ""))
                "web_open" -> AgentAction.WebOpen(json.getString("url"))
                "web_get_content" -> AgentAction.WebGetContent
                "web_execute_js" -> AgentAction.WebExecuteJs(json.getString("script"))
                "data_memory" -> AgentAction.DataMemory(
                    operation = json.optString("operation", "list"),
                    key = json.optString("key").takeIf { it.isNotEmpty() },
                    value = json.optString("value").takeIf { it.isNotEmpty() }
                )
                else -> null
            }
        }
    }
}

/**
 * AutoGLM Format Implementation
 * Uses function-style format: do(action="Tap", element=[x,y])
 */
class AutoGlmFormat : BaseActionFormat() {
    
    override fun getFormatName(): String = "AutoGLM"
    
    override fun getThinkingTag(): String = "think"
    
    override fun getCorrectExample(): String {
        return """<think>观察到屏幕中央有"确定"按钮，位置约50%,50%，坐标[500,500]，需要点击</think>
do(action="Tap", element=[500,500])"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<think>推理说明</think>
do(action="Context", text="...")
do(action="[Action_Name]", element=[x,y]|text="文本"|"button":"back/home/menu/enter"|"duration":3)

${AgentPrompts.CONTEXT_ACTION_REQUIREMENTS}

## 动作列表
do(action="Context", text="当前状态、关键信息、错误、坐标、策略")
do(action="Launch", app="应用名")
do(action="Tap", element=[x,y])
do(action="Tap", element=[x,y], message="敏感操作说明")
do(action="Type", text="文本")
do(action="Swipe", start=[x1,y1], end=[x2,y2])
do(action="Long Press", element=[x,y])
do(action="Double Tap", element=[x,y])
do(action="Back")
do(action="Home")
do(action="Wait", duration="x seconds")
do(action="Ask_user", message="给用户的问题或操作说明")
do(action="Get_App_List")
do(action="Web_Open", url="https://example.com")
do(action="Web_Get_Content")
do(action="Web_Execute_Js", script="document.querySelector('#login').click()")
do(action="Data_Memory", operation="set", key="name", value="content")
do(action="Data_Memory", operation="get", key="name")
do(action="Data_Memory", operation="list")
finish(message="任务完成总结")

注意：直接输出do()或finish()，不要使用<answer>标签！"""
    }
    
    override fun getKbActionDescription(): String = """```
do(action="KB_Delete", ids="ID1,ID2,...")
do(action="KB_Insert", text="Your experience summary here")
```
Note: ids is a comma-separated list of document IDs (from the [ID:xxx] tags above)."""
    
    override fun extractAllActions(cleaned: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        
        // Extract all do(...) actions
        val doPattern = Regex("""do\(action="[^"]+"[^)]*\)""")
        for (match in doPattern.findAll(cleaned)) {
            parseDo(match.value)?.let { actions.add(it) }
        }
        
        // Extract all finish(...) actions
        val finishPattern = Regex("""finish\(message="[^"]+"\)""")
        for (match in finishPattern.findAll(cleaned)) {
            parseFinish(match.value)?.let { actions.add(it) }
        }
        
        // Extract actions from <answer> tags
        val answerPattern = Regex("<answer>\\s*(.+?)\\s*</answer>", RegexOption.DOT_MATCHES_ALL)
        for (match in answerPattern.findAll(cleaned)) {
            val actionStr = match.groupValues[1].trim()
            when {
                actionStr.startsWith("do(") -> parseDo(actionStr)?.let { actions.add(it) }
                actionStr.startsWith("finish(") -> parseFinish(actionStr)?.let { actions.add(it) }
            }
        }
        
        return actions
    }
    
    private fun parseDo(actionStr: String): AgentAction? {
        try {
            val actionMatch = Regex("""action="([^"]+)""").find(actionStr) ?: return null
            val actionType = actionMatch.groupValues[1]
            
            return when (actionType) {
                "Launch" -> {
                    val appMatch = Regex("""app="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.Open(appMatch.groupValues[1])
                }
                "Tap" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    AgentAction.Click(elementMatch.groupValues[1].toInt(), elementMatch.groupValues[2].toInt())
                }
                "Type" -> {
                    val textMatch = Regex("""text="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.Type(textMatch.groupValues[1])
                }
                "Swipe" -> {
                    val coordMatch = Regex("""start=\[(\d+),(\d+)\],\s*end=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    AgentAction.Drag(
                        coordMatch.groupValues[1].toInt(), coordMatch.groupValues[2].toInt(),
                        coordMatch.groupValues[3].toInt(), coordMatch.groupValues[4].toInt()
                    )
                }
                "Long Press" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    AgentAction.LongPress(elementMatch.groupValues[1].toInt(), elementMatch.groupValues[2].toInt())
                }
                "Double Tap" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    AgentAction.DoubleClick(elementMatch.groupValues[1].toInt(), elementMatch.groupValues[2].toInt())
                }
                "Back" -> AgentAction.SystemButton(AgentAction.SystemButton.Button.BACK)
                "Home" -> AgentAction.SystemButton(AgentAction.SystemButton.Button.HOME)
                "Wait" -> AgentAction.Wait
                "Context" -> {
                    val textMatch = Regex("""text="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.Context(textMatch.groupValues[1])
                }
                "Ask_user" -> {
                    val messageMatch = Regex("""message="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.AskUser(messageMatch.groupValues[1])
                }
                "Get_App_List" -> AgentAction.GetAppList
                "Data_Memory" -> {
                    val opMatch = Regex("""operation="([^"]+)""").find(actionStr)
                    val keyMatch = Regex("""key="([^"]+)""").find(actionStr)
                    val valueMatch = Regex("""value="([^"]+)""").find(actionStr)
                    AgentAction.DataMemory(
                        operation = opMatch?.groupValues?.get(1) ?: "list",
                        key = keyMatch?.groupValues?.get(1),
                        value = valueMatch?.groupValues?.get(1)
                    )
                }
                "KB_Insert" -> {
                    val textMatch = Regex("""text="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.KbInsert(textMatch.groupValues[1])
                }
                "KB_Delete" -> {
                    val idsMatch = Regex("""ids="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.KbDelete(idsMatch.groupValues[1])
                }
                "Web_Open" -> {
                    val urlMatch = Regex("""url="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.WebOpen(urlMatch.groupValues[1])
                }
                "Web_Get_Content" -> AgentAction.WebGetContent
                "Web_Execute_Js" -> {
                    val scriptMatch = Regex("""script="([^"]+)""").find(actionStr) ?: return null
                    AgentAction.WebExecuteJs(scriptMatch.groupValues[1])
                }
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseFinish(actionStr: String): AgentAction? {
        try {
            Regex("""message="([^"]+)""").find(actionStr) ?: return null
            return AgentAction.Terminate(AgentAction.Terminate.Status.SUCCESS)
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun isCompatibleWith(modelName: String): Boolean {
        return modelName.contains("glm", ignoreCase = true) ||
               modelName.contains("chatglm", ignoreCase = true) ||
               modelName.contains("autoglm", ignoreCase = true)
    }
}

/**
 * Doubao-1.5-UI-TARS Format Implementation
 * Uses native Doubao format: Action: click(point='<point>x y</point>')
 * Coordinates are in pixel format and need to be converted to 0-999 normalized coordinates
 */
class DoubaoUiTarsFormat : BaseActionFormat() {
    
    override fun getFormatName(): String = "Doubao-1.5-UI-TARS"
    
    override fun getThinkingTag(): String = "thinking"
    
    override fun getCorrectExample(): String {
        return """<thinking>观察到屏幕中央有"确定"按钮，位置约50%,50%，坐标约500,500，需要点击</thinking>
Action: click(point='<point>500 500</point>')"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<thinking>推理说明</thinking>
Action: context(content='...')
Action: [action_name](point='<point>x y</point>'|content='文本'|start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')

${AgentPrompts.CONTEXT_ACTION_REQUIREMENTS}

## 动作列表
Action: context(content='当前状态、关键信息、错误、坐标、策略')
Action: click(point='<point>x y</point>')
Action: left_double(point='<point>x y</point>')
Action: right_single(point='<point>x y</point>')
Action: drag(start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')
Action: type(content='文本内容')
Action: scroll(point='<point>x y</point>', direction='down|up|left|right')
Action: hotkey(key='ctrl c')
Action: wait()
Action: get_app_list()
Action: web_open(url='https://example.com')
Action: web_get_content()
Action: web_execute_js(script='document.querySelector("#login").click()')
Action: data_memory(operation='set', key='name', value='content')
Action: data_memory(operation='get', key='name')
Action: data_memory(operation='list')
Action: finished(content='任务完成总结')
Action: ask_user(message='给用户的问题或操作说明')

注意：坐标使用像素格式，系统会自动转换为归一化坐标"""
    }
    
    override fun getKbActionDescription(): String = """```
Action: kb_delete(ids='ID1,ID2,...')
Action: kb_insert(content='Your experience summary here')
```
Note: ids is a comma-separated list of document IDs (from the [ID:xxx] tags above)."""
    
    override fun extractAllActions(cleaned: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        
        val actionPattern = Regex("""Action:\s*(.+)""", RegexOption.MULTILINE)
        for (match in actionPattern.findAll(cleaned)) {
            val actionStr = match.groupValues[1].trim()
            
            val action: AgentAction? = when {
                actionStr.startsWith("click(") -> parsePoint(actionStr)?.let { (x, y) -> AgentAction.Click(x, y) }
                actionStr.startsWith("left_double(") -> parsePoint(actionStr)?.let { (x, y) -> AgentAction.DoubleClick(x, y) }
                actionStr.startsWith("right_single(") -> parsePoint(actionStr)?.let { (x, y) -> AgentAction.LongPress(x, y) }
                actionStr.startsWith("drag(") -> parseDragPoints(actionStr)
                actionStr.startsWith("type(") -> {
                    Regex("""content='([^']+)'""").find(actionStr)?.let { AgentAction.Type(it.groupValues[1]) }
                }
                actionStr.startsWith("scroll(") -> parseScroll(actionStr)
                actionStr.startsWith("hotkey(") -> parseHotkey(actionStr)
                actionStr.startsWith("wait(") -> AgentAction.Wait
                actionStr.startsWith("context(") -> {
                    Regex("""content='([^']+)'""").find(actionStr)?.let { AgentAction.Context(it.groupValues[1]) }
                }
                actionStr.startsWith("get_app_list(") -> AgentAction.GetAppList
                actionStr.startsWith("kb_insert(") -> {
                    Regex("""content='([^']+)'""").find(actionStr)?.let { AgentAction.KbInsert(it.groupValues[1]) }
                }
                actionStr.startsWith("kb_delete(") -> {
                    Regex("""ids='([^']+)'""").find(actionStr)?.let { AgentAction.KbDelete(it.groupValues[1]) }
                }
                actionStr.startsWith("web_open(") -> {
                    Regex("""url='([^']+)'""").find(actionStr)?.let { AgentAction.WebOpen(it.groupValues[1]) }
                }
                actionStr.startsWith("web_get_content(") -> AgentAction.WebGetContent
                actionStr.startsWith("web_execute_js(") -> {
                    Regex("""script='([^']+)'""").find(actionStr)?.let { AgentAction.WebExecuteJs(it.groupValues[1]) }
                }
                actionStr.startsWith("data_memory(") -> {
                    val opMatch = Regex("""operation='([^']+)'""").find(actionStr)
                    val keyMatch = Regex("""key='([^']*)'""").find(actionStr)
                    val valueMatch = Regex("""value='([^']*)'""").find(actionStr)
                    AgentAction.DataMemory(
                        operation = opMatch?.groupValues?.get(1) ?: "list",
                        key = keyMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() },
                        value = valueMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                    )
                }
                actionStr.startsWith("finished(") -> {
                    AgentAction.Terminate(AgentAction.Terminate.Status.SUCCESS)
                }
                actionStr.startsWith("ask_user(") -> {
                    Regex("""message='([^']*)'""")
                        .find(actionStr)?.let { AgentAction.AskUser(it.groupValues[1]) }
                        ?: Regex("""message="([^"]*)""""").find(actionStr)?.let { AgentAction.AskUser(it.groupValues[1]) }
                }
                else -> null
            }
            
            action?.let { actions.add(it) }
        }
        
        return actions
    }
    
    private fun parsePoint(actionStr: String): Pair<Int, Int>? {
        val match = Regex("""point='<point>(\d+)\s+(\d+)</point>'""").find(actionStr) ?: return null
        return Pair(match.groupValues[1].toInt().coerceIn(0, 999), match.groupValues[2].toInt().coerceIn(0, 999))
    }
    
    private fun parseDragPoints(actionStr: String): AgentAction? {
        val match = Regex("""start_point='<point>(\d+)\s+(\d+)</point>',\s*end_point='<point>(\d+)\s+(\d+)</point>'""")
            .find(actionStr) ?: return null
        return AgentAction.Drag(
            match.groupValues[1].toInt().coerceIn(0, 999), match.groupValues[2].toInt().coerceIn(0, 999),
            match.groupValues[3].toInt().coerceIn(0, 999), match.groupValues[4].toInt().coerceIn(0, 999)
        )
    }
    
    private fun parseScroll(actionStr: String): AgentAction? {
        val pointMatch = Regex("""point='<point>(\d+)\s+(\d+)</point>'""").find(actionStr) ?: return null
        val dirMatch = Regex("""direction='(down|up|left|right)'""").find(actionStr) ?: return null
        val x = pointMatch.groupValues[1].toInt().coerceIn(0, 999)
        val y = pointMatch.groupValues[2].toInt().coerceIn(0, 999)
        val (x1, y1, x2, y2) = when (dirMatch.groupValues[1]) {
            "down" -> intArrayOf(x, (y + 200).coerceIn(0, 999), x, (y - 200).coerceIn(0, 999))
            "up" -> intArrayOf(x, (y - 200).coerceIn(0, 999), x, (y + 200).coerceIn(0, 999))
            "left" -> intArrayOf((x + 200).coerceIn(0, 999), y, (x - 200).coerceIn(0, 999), y)
            "right" -> intArrayOf((x - 200).coerceIn(0, 999), y, (x + 200).coerceIn(0, 999), y)
            else -> return null
        }
        return AgentAction.Drag(x1, y1, x2, y2)
    }
    
    private fun parseHotkey(actionStr: String): AgentAction? {
        val match = Regex("""key='([^']+)'""").find(actionStr) ?: return null
        val keys = match.groupValues[1].lowercase()
        return when {
            keys.contains("back") || keys == "esc" -> AgentAction.SystemButton(AgentAction.SystemButton.Button.BACK)
            keys.contains("home") -> AgentAction.SystemButton(AgentAction.SystemButton.Button.HOME)
            keys.contains("enter") -> AgentAction.SystemButton(AgentAction.SystemButton.Button.ENTER)
            else -> null
        }
    }
    
    override fun isCompatibleWith(modelName: String): Boolean {
        return modelName.contains("doubao", ignoreCase = true) ||
               modelName.contains("seed", ignoreCase = true) ||
               modelName.contains("ui-tars", ignoreCase = true)
    }
}

/**
 * OpenAI Function Call Format Implementation
 * Uses OpenAI function call format: {"name":"tool_name", "parameters":{...}}
 * Supports both single call and batch calls via tool_calls array
 */
class OpenAiFuncCallFormat : BaseActionFormat() {
    
    override fun getFormatName(): String = "OpenAI FuncCall"
    
    override fun getThinkingTag(): String = "thinking"
    
    override fun getCorrectExample(): String {
        return """<thinking>观察到屏幕中央有"确定"按钮，位置约50%,50%，坐标[500,500]，需要点击</thinking>
{"name":"click","parameters":{"coordinate":[500,500]}}"""
    }
    
    override fun getErrorHint(): String {
        return "ERROR: 解析动作Action失败. 严格按照系统提示词中 ## 动作列表 格式输出！"
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：OpenAI Function Call 格式
单个调用：{"name":"[function_name]","parameters":{...}}
批量调用：{"tool_calls":[{"name":"context","parameters":{"text":"..."}},{"name":"[function_name]","parameters":{...}}]}

${AgentPrompts.CONTEXT_ACTION_REQUIREMENTS}

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
    
    override fun extractAllActions(cleaned: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()

        // Use bracket-balance scanning to extract top-level JSON objects of any nesting depth.
        // The old depth-limited regex "\{(?:[^{}]|(?:\{[^{}]*\}))*\}" only handled 2 levels,
        // causing tool_calls JSON (3+ levels deep) or text fields with newlines to be silently dropped.
        for (jsonStr in extractTopLevelJsonObjects(cleaned)) {
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
    
    override fun isCompatibleWith(modelName: String): Boolean = false
}

// ============================================================================
// Format Registry (格式注册和选择)
// ============================================================================

/**
 * Registry for managing different action formats
 * 
 * Selection Rules:
 * 1. Zhipu API (bigmodel) -> AutoGLM format
 * 2. Aliyun/Qianwen API (dashscope/aliyun) -> MAI-UI format
 * 3. Volcengine/Doubao API (volces/ark) -> Doubao-1.5-UI-TARS format
 * 4. Local model -> Check model name (GLM series -> AutoGLM, Doubao series -> Doubao, others -> MAI-UI)
 * 5. Default -> MAI-UI format
 */
object ActionFormatRegistry {
    private val formats = mutableListOf<ActionFormat>()
    
    init {
        // Register default formats
        registerFormat(MaiUiFormat())
        registerFormat(AutoGlmFormat())
        registerFormat(DoubaoUiTarsFormat())
        registerFormat(OpenAiFuncCallFormat())
    }
    
    /**
     * Register a new action format
     */
    fun registerFormat(format: ActionFormat) {
        formats.add(format)
    }
    
    /**
     * Get the appropriate format based on API URL and model name
     */
    fun getFormatForApi(apiUrl: String, modelName: String = ""): ActionFormat {
        // Rule 1: Zhipu API -> AutoGLM
        if (apiUrl.contains("bigmodel", ignoreCase = true) || 
            apiUrl.contains("zhipu", ignoreCase = true)) {
            return getFormatByName("AutoGLM") ?: AutoGlmFormat()
        }
        
        // Rule 2: Aliyun/Qianwen API -> MAI-UI
        if (apiUrl.contains("dashscope", ignoreCase = true) || 
            apiUrl.contains("aliyun", ignoreCase = true)) {
            return getFormatByName("MAI-UI") ?: MaiUiFormat()
        }
        
        // Rule 3: Volcengine/Doubao API -> Doubao-1.5-UI-TARS
        if (apiUrl.contains("volces", ignoreCase = true) || 
            apiUrl.contains("ark.cn", ignoreCase = true) ||
            apiUrl.contains("volcengine", ignoreCase = true)) {
            return getFormatByName("Doubao-1.5-UI-TARS") ?: DoubaoUiTarsFormat()
        }
        
        // Rule 4: Local model -> check model name
        if (apiUrl.equals(com.example.offlineai.AppConstants.ApiUrl.LOCAL, ignoreCase = true) || 
            apiUrl.equals("local", ignoreCase = true)) {
            if (modelName.contains("glm", ignoreCase = true)) {
                return getFormatByName("AutoGLM") ?: AutoGlmFormat()
            }
            if (modelName.contains("doubao", ignoreCase = true) || 
                modelName.contains("seed", ignoreCase = true)) {
                return getFormatByName("Doubao-1.5-UI-TARS") ?: DoubaoUiTarsFormat()
            }
        }
        
        // Rule 5: Default -> MAI-UI
        return getFormatByName("MAI-UI") ?: MaiUiFormat()
    }
    
    /**
     * Get the appropriate format for a model (legacy method)
     * @deprecated Use getFormatForApi(apiUrl, modelName) instead
     */
    @Deprecated("Use getFormatForApi(apiUrl, modelName) for better API-based selection")
    fun getFormatForModel(modelName: String): ActionFormat {
        // Find the first compatible format
        for (format in formats) {
            if (format.isCompatibleWith(modelName)) {
                return format
            }
        }
        // Default to MAI-UI if no match
        return formats.firstOrNull { it is MaiUiFormat } ?: MaiUiFormat()
    }
    
    /**
     * Get format by name
     */
    fun getFormatByName(name: String): ActionFormat? {
        return formats.firstOrNull { it.getFormatName().equals(name, ignoreCase = true) }
    }
    
    /**
     * Get all registered formats
     */
    fun getAllFormats(): List<ActionFormat> {
        return formats.toList()
    }
}
