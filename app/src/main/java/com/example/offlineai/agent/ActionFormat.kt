package com.example.offlineai.agent

import org.json.JSONObject

/**
 * Action format interface for different prompt styles
 */
interface ActionFormat {
    /**
     * Get the format name (e.g., "MAI-UI", "AutoGLM")
     */
    fun getFormatName(): String
    
    /**
     * Get format description (output format + action list)
     * Note: Does not include thinking tags, those are added by AgentPrompts
     */
    fun getFormatDescription(): String
    
    /**
     * Get correct output example for this format
     */
    fun getCorrectExample(): String
    
    /**
     * Get error hint message when parsing fails
     */
    fun getErrorHint(): String
    
    /**
     * Get thinking tag name for this format
     */
    fun getThinkingTag(): String
    
    /**
     * Parse action from model response
     * @return Pair of (thinking, action) or null if parsing failed
     */
    fun parseAction(response: String): Pair<String?, Action?>?
    
    /**
     * Check if this format should be used for the given model
     */
    fun isCompatibleWith(modelName: String): Boolean
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
}

/**
 * Unified action data class
 */
data class Action(
    val type: ActionType,
    val coordinate: IntArray? = null,
    val startCoordinate: IntArray? = null,
    val endCoordinate: IntArray? = null,
    val text: String? = null,
    val direction: String? = null,
    val button: String? = null,
    val status: String? = null,
    val duration: Int? = null,
    val message: String? = null,
    val isSensitive: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Action

        if (type != other.type) return false
        if (coordinate != null) {
            if (other.coordinate == null) return false
            if (!coordinate.contentEquals(other.coordinate)) return false
        } else if (other.coordinate != null) return false
        if (startCoordinate != null) {
            if (other.startCoordinate == null) return false
            if (!startCoordinate.contentEquals(other.startCoordinate)) return false
        } else if (other.startCoordinate != null) return false
        if (endCoordinate != null) {
            if (other.endCoordinate == null) return false
            if (!endCoordinate.contentEquals(other.endCoordinate)) return false
        } else if (other.endCoordinate != null) return false
        if (text != other.text) return false
        if (direction != other.direction) return false
        if (button != other.button) return false
        if (status != other.status) return false
        if (duration != other.duration) return false
        if (message != other.message) return false
        if (isSensitive != other.isSensitive) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (coordinate?.contentHashCode() ?: 0)
        result = 31 * result + (startCoordinate?.contentHashCode() ?: 0)
        result = 31 * result + (endCoordinate?.contentHashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (direction?.hashCode() ?: 0)
        result = 31 * result + (button?.hashCode() ?: 0)
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + (duration ?: 0)
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + isSensitive.hashCode()
        return result
    }
}

/**
 * Action types
 */
enum class ActionType {
    CLICK,
    LONG_PRESS,
    DOUBLE_CLICK,
    TYPE,
    SWIPE,
    DRAG,
    OPEN,
    SYSTEM_BUTTON,
    WAIT,
    TERMINATE,
    ANSWER,
    TAKE_OVER,
    CONFIRM,
    NOTE,
    CALL_API,
    INTERACT
}

/**
 * Action execution result
 */
data class ActionResult(
    val success: Boolean,
    val shouldFinish: Boolean,
    val message: String? = null,
    val requiresConfirmation: Boolean = false
)

// ============================================================================
// Format Implementations (扩展新格式时复制其中一个作为模板修改)
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
        return """ERROR: Failed to parse your output. Please follow the format strictly:
<tool_call>{"action":"click","coordinate":[500,500]}</tool_call>

Common mistakes:
❌ do(action="click", coordinate=[500,500])  // Wrong format!
❌ 点击坐标[500,500]  // Not JSON!
❌ {"action":"click","coordinate":[500,500]}  // Missing <tool_call> tags!
✅ <tool_call>{"action":"click","coordinate":[500,500]}</tool_call>  // Correct!"""
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<tool_call>{"action":"[Action_Name]","coordinate":[x,y]|"text":"文本"|"button":"back/home/menu/enter"|"duration":3}</tool_call>

## 动作列表
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
{"action":"answer","text":"回答内容"}
{"action":"take_over","text":"需要用户协助的原因"}
{"action":"confirm","coordinate":[x,y],"text":"敏感操作说明"}
严格按照以上JSON格式输出"""
    }
    
    override fun parseAction(response: String): Pair<String?, Action?>? {
        try {
            val thinking = extractThinking(response)
            
            val toolCallMatch = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                .find(response) ?: return null
            
            val jsonStr = toolCallMatch.groupValues[1].trim()
            val json = JSONObject(jsonStr)
            
            val actionType = json.getString("action")
            val action = when (actionType) {
                "click" -> Action(
                    type = ActionType.CLICK,
                    coordinate = json.getJSONArray("coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) }
                )
                "long_press" -> Action(
                    type = ActionType.LONG_PRESS,
                    coordinate = json.getJSONArray("coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) }
                )
                "double_click" -> Action(
                    type = ActionType.DOUBLE_CLICK,
                    coordinate = json.getJSONArray("coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) }
                )
                "type" -> Action(type = ActionType.TYPE, text = json.getString("text"))
                "swipe" -> Action(
                    type = ActionType.SWIPE,
                    direction = json.getString("direction"),
                    coordinate = if (json.has("coordinate")) {
                        json.getJSONArray("coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) }
                    } else null
                )
                "drag" -> Action(
                    type = ActionType.DRAG,
                    startCoordinate = json.getJSONArray("start_coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) },
                    endCoordinate = json.getJSONArray("end_coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) }
                )
                "open" -> Action(type = ActionType.OPEN, text = json.getString("text"))
                "system_button" -> Action(type = ActionType.SYSTEM_BUTTON, button = json.getString("button"))
                "wait" -> Action(type = ActionType.WAIT, duration = if (json.has("duration")) json.getInt("duration") else 1)
                "terminate" -> Action(type = ActionType.TERMINATE, status = json.getString("status"), text = json.getString("text"))
                "answer" -> Action(type = ActionType.ANSWER, text = json.getString("text"))
                "take_over" -> Action(type = ActionType.TAKE_OVER, text = json.getString("text"))
                "confirm" -> Action(
                    type = ActionType.CONFIRM,
                    coordinate = json.getJSONArray("coordinate").let { intArrayOf(it.getInt(0), it.getInt(1)) },
                    text = json.getString("text"),
                    isSensitive = true
                )
                else -> return null
            }
            
            return Pair(thinking, action)
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun isCompatibleWith(modelName: String): Boolean {
        return !modelName.contains("glm", ignoreCase = true) &&
               !modelName.contains("chatglm", ignoreCase = true) &&
               !modelName.contains("autoglm", ignoreCase = true)
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
        return """ERROR: Failed to parse your output. Please follow the format strictly:
<think>推理说明</think>
do(action="Tap", element=[500,500])

Common mistakes:
❌ {"action":"click","coordinate":[500,500]}  // Wrong format! This is MAI-UI format!
❌ 点击坐标[500,500]  // Not a function call!
❌ <answer>do(action="Tap", element=[500,500])</answer>  // Don't use <answer> tags!
✅ <think>推理</think>
do(action="Tap", element=[500,500])  // Correct!"""
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<think>推理说明</think>
do(action="[Action_Name]", element=[x,y]|text="文本内容"|start=[x1,y1], end=[x2,y2]|duration="x seconds"|message="需要用户协助")

## 动作列表
do(action="Launch", app="应用名")
do(action="Tap", element=[x,y])
do(action="Tap", element=[x,y], message="敏感操作说明")
do(action="Type", text="文本内容")
do(action="Swipe", start=[x1,y1], end=[x2,y2])
do(action="Long Press", element=[x,y])
do(action="Double Tap", element=[x,y])
do(action="Back")
do(action="Home")
do(action="Wait", duration="x seconds")
do(action="Take_over", message="需要用户协助")
do(action="Interact")
finish(message="任务完成总结")

注意：直接输出do()或finish()，不要使用<answer>标签！"""
    }
    
    override fun parseAction(response: String): Pair<String?, Action?>? {
        try {
            // Extract thinking first using robust tag detection (handles <think> and <thinking>)
            val thinking = extractThinking(response, "think")
            
            // Rule 1: Check for finish(message=
            if (response.contains("finish(message=")) {
                val parts = response.split("finish(message=", limit = 2)
                val actionStr = "finish(message=" + parts[1]
                val action = parseFinish(actionStr)
                return if (action != null) Pair(thinking, action) else null
            }
            
            // Rule 2: Check for do(action=
            if (response.contains("do(action=")) {
                val parts = response.split("do(action=", limit = 2)
                val actionStr = "do(action=" + parts[1]
                val action = parseDo(actionStr)
                return if (action != null) Pair(thinking, action) else null
            }
            
            // Rule 3: Fallback to legacy <answer> tag parsing
            if (response.contains("<answer>")) {
                val answerMatch = Regex("<answer>\\s*(.+?)\\s*</answer>", RegexOption.DOT_MATCHES_ALL)
                    .find(response) ?: return null
                val actionStr = answerMatch.groupValues[1].trim()
                val action = if (actionStr.startsWith("do(")) {
                    parseDo(actionStr)
                } else if (actionStr.startsWith("finish(")) {
                    parseFinish(actionStr)
                } else null
                return if (action != null) Pair(thinking, action) else null
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseDo(actionStr: String): Action? {
        try {
            val actionMatch = Regex("""action="([^"]+)"""").find(actionStr) ?: return null
            val actionType = actionMatch.groupValues[1]
            
            return when (actionType) {
                "Launch" -> {
                    val appMatch = Regex("""app="([^"]+)"""").find(actionStr) ?: return null
                    Action(type = ActionType.OPEN, text = appMatch.groupValues[1])
                }
                "Tap" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    val x = elementMatch.groupValues[1].toInt()
                    val y = elementMatch.groupValues[2].toInt()
                    val messageMatch = Regex("""message="([^"]+)"""").find(actionStr)
                    Action(
                        type = ActionType.CLICK,
                        coordinate = intArrayOf(x, y),
                        message = messageMatch?.groupValues?.get(1),
                        isSensitive = messageMatch != null
                    )
                }
                "Type" -> {
                    val textMatch = Regex("""text="([^"]+)"""").find(actionStr) ?: return null
                    Action(type = ActionType.TYPE, text = textMatch.groupValues[1])
                }
                "Swipe" -> {
                    val coordMatch = Regex("""start=\[(\d+),(\d+)\],\s*end=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    Action(
                        type = ActionType.DRAG,
                        startCoordinate = intArrayOf(coordMatch.groupValues[1].toInt(), coordMatch.groupValues[2].toInt()),
                        endCoordinate = intArrayOf(coordMatch.groupValues[3].toInt(), coordMatch.groupValues[4].toInt())
                    )
                }
                "Long Press" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    Action(type = ActionType.LONG_PRESS, coordinate = intArrayOf(elementMatch.groupValues[1].toInt(), elementMatch.groupValues[2].toInt()))
                }
                "Double Tap" -> {
                    val elementMatch = Regex("""element=\[(\d+),(\d+)\]""").find(actionStr) ?: return null
                    Action(type = ActionType.DOUBLE_CLICK, coordinate = intArrayOf(elementMatch.groupValues[1].toInt(), elementMatch.groupValues[2].toInt()))
                }
                "Back" -> Action(type = ActionType.SYSTEM_BUTTON, button = "back")
                "Home" -> Action(type = ActionType.SYSTEM_BUTTON, button = "home")
                "Wait" -> {
                    val durationMatch = Regex("""duration="(\d+)\s*seconds?"""").find(actionStr)
                    Action(type = ActionType.WAIT, duration = durationMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                }
                "Take_over" -> {
                    val messageMatch = Regex("""message="([^"]+)"""").find(actionStr) ?: return null
                    Action(type = ActionType.TAKE_OVER, text = messageMatch.groupValues[1])
                }
                "Interact" -> Action(type = ActionType.INTERACT)
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseFinish(actionStr: String): Action? {
        try {
            val messageMatch = Regex("""message="([^"]+)"""").find(actionStr) ?: return null
            return Action(type = ActionType.TERMINATE, status = "success", text = messageMatch.groupValues[1])
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
        return """ERROR: Failed to parse your output. Please follow the format strictly:
<thinking>推理说明</thinking>
Action: click(point='<point>500 500</point>')

Common mistakes:
❌ do(action="Tap", element=[500,500])  // Wrong format! This is AutoGLM format!
❌ {"action":"click","coordinate":[500,500]}  // Wrong format! This is MAI-UI format!
❌ click(500, 500)  // Missing <point> tags!
✅ <thinking>推理</thinking>
Action: click(point='<point>500 500</point>')  // Correct!"""
    }
    
    override fun getFormatDescription(): String {
        return """**输出格式**：
<thinking>推理说明</thinking>
Action: [action_name](point='<point>x y</point>'|content='文本'|start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')

## 动作列表
Action: click(point='<point>x y</point>')
Action: left_double(point='<point>x y</point>')
Action: right_single(point='<point>x y</point>')
Action: drag(start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')
Action: type(content='文本内容')
Action: scroll(point='<point>x y</point>', direction='down|up|left|right')
Action: hotkey(key='ctrl c')
Action: wait()
Action: finished(content='任务完成总结')

注意：坐标使用像素格式，系统会自动转换为归一化坐标"""
    }
    
    override fun parseAction(response: String): Pair<String?, Action?>? {
        try {
            // Extract thinking (use "thinking" tag to be compatible with existing system)
            val thinking = extractThinking(response, "thinking")
            
            // Find Action: line
            val actionPattern = Regex("""Action:\s*(.+)""", RegexOption.MULTILINE)
            val actionMatch = actionPattern.find(response) ?: return null
            val actionStr = actionMatch.groupValues[1].trim()
            
            // Parse different action types
            val action = when {
                actionStr.startsWith("click(") -> parseClick(actionStr)
                actionStr.startsWith("left_double(") -> parseLeftDouble(actionStr)
                actionStr.startsWith("right_single(") -> parseRightSingle(actionStr)
                actionStr.startsWith("drag(") -> parseDrag(actionStr)
                actionStr.startsWith("type(") -> parseType(actionStr)
                actionStr.startsWith("scroll(") -> parseScroll(actionStr)
                actionStr.startsWith("hotkey(") -> parseHotkey(actionStr)
                actionStr.startsWith("wait(") -> Action(type = ActionType.WAIT, duration = 5)
                actionStr.startsWith("finished(") -> parseFinished(actionStr)
                else -> null
            }
            
            return if (action != null) Pair(thinking, action) else null
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseClick(actionStr: String): Action? {
        val pointPattern = Regex("""point='<point>(\d+)\s+(\d+)</point>'""")
        val match = pointPattern.find(actionStr) ?: return null
        val x = match.groupValues[1].toInt()
        val y = match.groupValues[2].toInt()
        // Convert pixel coordinates to 0-999 normalized (assuming screen is ~1000px)
        // For safety, clamp to 0-999 range
        val normalizedX = x.coerceIn(0, 999)
        val normalizedY = y.coerceIn(0, 999)
        return Action(type = ActionType.CLICK, coordinate = intArrayOf(normalizedX, normalizedY))
    }
    
    private fun parseLeftDouble(actionStr: String): Action? {
        val pointPattern = Regex("""point='<point>(\d+)\s+(\d+)</point>'""")
        val match = pointPattern.find(actionStr) ?: return null
        val x = match.groupValues[1].toInt().coerceIn(0, 999)
        val y = match.groupValues[2].toInt().coerceIn(0, 999)
        return Action(type = ActionType.DOUBLE_CLICK, coordinate = intArrayOf(x, y))
    }
    
    private fun parseRightSingle(actionStr: String): Action? {
        val pointPattern = Regex("""point='<point>(\d+)\s+(\d+)</point>'""")
        val match = pointPattern.find(actionStr) ?: return null
        val x = match.groupValues[1].toInt().coerceIn(0, 999)
        val y = match.groupValues[2].toInt().coerceIn(0, 999)
        return Action(type = ActionType.LONG_PRESS, coordinate = intArrayOf(x, y))
    }
    
    private fun parseDrag(actionStr: String): Action? {
        val dragPattern = Regex("""start_point='<point>(\d+)\s+(\d+)</point>',\s*end_point='<point>(\d+)\s+(\d+)</point>'""")
        val match = dragPattern.find(actionStr) ?: return null
        val x1 = match.groupValues[1].toInt().coerceIn(0, 999)
        val y1 = match.groupValues[2].toInt().coerceIn(0, 999)
        val x2 = match.groupValues[3].toInt().coerceIn(0, 999)
        val y2 = match.groupValues[4].toInt().coerceIn(0, 999)
        return Action(
            type = ActionType.DRAG,
            startCoordinate = intArrayOf(x1, y1),
            endCoordinate = intArrayOf(x2, y2)
        )
    }
    
    private fun parseType(actionStr: String): Action? {
        val contentPattern = Regex("""content='([^']+)'""")
        val match = contentPattern.find(actionStr) ?: return null
        return Action(type = ActionType.TYPE, text = match.groupValues[1])
    }
    
    private fun parseScroll(actionStr: String): Action? {
        val pointPattern = Regex("""point='<point>(\d+)\s+(\d+)</point>'""")
        val directionPattern = Regex("""direction='(down|up|left|right)'""")
        val pointMatch = pointPattern.find(actionStr) ?: return null
        val directionMatch = directionPattern.find(actionStr) ?: return null
        
        val x = pointMatch.groupValues[1].toInt().coerceIn(0, 999)
        val y = pointMatch.groupValues[2].toInt().coerceIn(0, 999)
        val direction = directionMatch.groupValues[1]
        
        // Convert scroll to drag action
        // "scroll down" = page content moves down = finger swipes UP (start.y big, end.y small)
        // "scroll up" = page content moves up = finger swipes DOWN (start.y small, end.y big)
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> intArrayOf(x, y + 200, x, y - 200)
            "up" -> intArrayOf(x, y - 200, x, y + 200)
            "left" -> intArrayOf(x + 200, y, x - 200, y)
            "right" -> intArrayOf(x - 200, y, x + 200, y)
            else -> return null
        }
        
        return Action(
            type = ActionType.DRAG,
            startCoordinate = intArrayOf(x1.coerceIn(0, 999), y1.coerceIn(0, 999)),
            endCoordinate = intArrayOf(x2.coerceIn(0, 999), y2.coerceIn(0, 999))
        )
    }
    
    private fun parseHotkey(actionStr: String): Action? {
        val keyPattern = Regex("""key='([^']+)'""")
        val match = keyPattern.find(actionStr) ?: return null
        val keys = match.groupValues[1].lowercase()
        
        // Map hotkeys to system buttons or special actions
        return when {
            keys.contains("back") || keys == "esc" -> Action(type = ActionType.SYSTEM_BUTTON, button = "back")
            keys.contains("home") -> Action(type = ActionType.SYSTEM_BUTTON, button = "home")
            keys.contains("enter") -> Action(type = ActionType.SYSTEM_BUTTON, button = "enter")
            else -> null // Unsupported hotkey
        }
    }
    
    private fun parseFinished(actionStr: String): Action? {
        val contentPattern = Regex("""content='([^']+)'""")
        val match = contentPattern.find(actionStr) ?: return null
        return Action(type = ActionType.TERMINATE, status = "success", text = match.groupValues[1])
    }
    
    override fun isCompatibleWith(modelName: String): Boolean {
        return modelName.contains("doubao", ignoreCase = true) ||
               modelName.contains("seed", ignoreCase = true) ||
               modelName.contains("ui-tars", ignoreCase = true)
    }
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
