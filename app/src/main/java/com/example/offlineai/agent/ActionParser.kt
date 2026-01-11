package com.example.offlineai.agent.parser

import android.content.Context
import com.example.offlineai.ConfigManager
import com.example.offlineai.LogManager
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.AgentResponse
import com.example.offlineai.agent.ActionFormatRegistry
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Action Parser - parses model output to extract thinking and action
 * Supports multiple formats (MAI-UI, AutoGLM, Doubao) via ActionFormatRegistry
 */
object ActionParser {
    
    private const val TAG = "ActionParser"
    
    /**
     * Parse Agent response from model output with format selection based on user settings
     * @param modelOutput The model's output text
     * @param apiUrl API URL to determine which format to use
     * @param modelName Model name (used for local models)
     * @param context Context to read user settings
     * @return AgentResponse or null if parsing fails
     */
    fun parse(modelOutput: String, apiUrl: String, modelName: String = "", context: Context? = null): AgentResponse? {
        try {
            LogManager.logI(TAG, "[PARSE_DEBUG] Input length: ${modelOutput.length}, apiUrl: $apiUrl, model: $modelName")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (first 200 chars): ${modelOutput.take(200)}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (last 200 chars): ${modelOutput.takeLast(200)}")
            
            // Get the appropriate format based on user settings or API URL
            val format = if (context != null) {
                val userFormat = ConfigManager.getAgentActionFormat(context)
                LogManager.logI(TAG, "[PARSE_DEBUG] User selected format: $userFormat")
                
                when (userFormat) {
                    "Auto" -> {
                        // Auto mode: use API-based selection
                        ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                    }
                    "MAI-UI" -> {
                        ActionFormatRegistry.getFormatByName("MAI-UI") 
                            ?: ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                    }
                    "AutoGLM-Phone" -> {
                        ActionFormatRegistry.getFormatByName("AutoGLM") 
                            ?: ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                    }
                    "Doubao-1.5-UI-TARS" -> {
                        ActionFormatRegistry.getFormatByName("Doubao-1.5-UI-TARS") 
                            ?: ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                    }
                    else -> {
                        // Fallback to auto selection
                        ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                    }
                }
            } else {
                // No context provided, use auto selection
                ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
            }
            LogManager.logI(TAG, "[PARSE_DEBUG] Using format: ${format.getFormatName()}")
            
            // Use the format's parser
            val result = format.parseAction(modelOutput)
            if (result == null) {
                LogManager.logE(TAG, "[PARSE_DEBUG] Format ${format.getFormatName()} failed to parse")
                return null
            }
            
            val (thinking, action) = result
            if (action == null) {
                LogManager.logE(TAG, "[PARSE_DEBUG] Action is null after parsing")
                return null
            }
            
            // Convert unified Action to legacy AgentAction
            val agentAction = convertToAgentAction(action)
            if (agentAction == null) {
                LogManager.logE(TAG, "[PARSE_DEBUG] Failed to convert Action to AgentAction")
                return null
            }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully parsed action: ${agentAction.javaClass.simpleName}")
            return AgentResponse(thinking ?: "", agentAction)
        } catch (e: Exception) {
            LogManager.logE(TAG, "[PARSE_DEBUG] Exception during parse", e)
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Legacy parse method (uses MAI-UI format by default)
     * @deprecated Use parse(modelOutput, modelName) instead
     */
    @Deprecated("Use parse(modelOutput, modelName) for dynamic format selection")
    fun parse(modelOutput: String): AgentResponse? {
        try {
            LogManager.logI(TAG, "[PARSE_DEBUG] Input length: ${modelOutput.length}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (first 200 chars): ${modelOutput.take(200)}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (last 200 chars): ${modelOutput.takeLast(200)}")
            
            val normalized = normalizeOutput(modelOutput)
            val thinking = extractThinking(normalized) ?: ""  // Make thinking optional
            val toolCallJson = extractToolCall(normalized)
            
            if (toolCallJson == null) {
                LogManager.logE(TAG, "[PARSE_DEBUG] extractToolCall returned null!")
                return null
            }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully extracted tool_call JSON")
            val action = parseAction(toolCallJson)
            
            if (action == null) {
                LogManager.logE(TAG, "[PARSE_DEBUG] parseAction returned null!")
                return null
            }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully parsed action: ${action.javaClass.simpleName}")
            return AgentResponse(thinking, action)
        } catch (e: Exception) {
            LogManager.logE(TAG, "[PARSE_DEBUG] Exception during parse", e)
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Normalize output format (handle </think> vs </thinking>)
     */
    private fun normalizeOutput(output: String): String {
        var normalized = output
        if (normalized.contains("</think>") && !normalized.contains("</thinking>")) {
            normalized = normalized.replace("</think>", "</thinking>")
            if (!normalized.contains("<thinking>")) {
                normalized = "<thinking>$normalized"
            }
        }
        return normalized
    }
    
    /**
     * Extract thinking content from <thinking> tags
     * Returns null if not found (thinking is optional in MAI-UI design)
     */
    private fun extractThinking(output: String): String? {
        val pattern = Pattern.compile("<thinking>(.*?)</thinking>", Pattern.DOTALL)
        val matcher = pattern.matcher(output)
        return if (matcher.find()) {
            matcher.group(1)?.trim()?.trim('"')
        } else {
            null  // Thinking is optional
        }
    }
    
    /**
     * Extract tool_call JSON from <tool_call> tags
     * Returns null if not found (tool_call is required)
     */
    private fun extractToolCall(output: String): JSONObject? {
        LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Starting extraction...")
        LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] Output contains <tool_call>: ${output.contains("<tool_call>")}")
        LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] Output contains </tool_call>: ${output.contains("</tool_call>")}")
        
        val pattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val matcher = pattern.matcher(output)
        var lastContent: String? = null
        var matchCount = 0
        while (matcher.find()) {
            matchCount++
            lastContent = matcher.group(1)
            LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] Match #$matchCount, content length: ${lastContent?.length ?: 0}")
        }
        
        LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Total matches: $matchCount")
        
        return if (lastContent != null) {
            LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Raw content length: ${lastContent.length}")
            LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] Raw content: $lastContent")
            var content = lastContent.trim()
            LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] After trim, length: ${content.length}")
            
            // Remove leading/trailing quotes if present
            content = content.trim('"')
            LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] After trim quotes, length: ${content.length}")
            
            // Handle case where content starts with "null" followed by actual JSON
            // Example: "null\n{\"name\": \"mobile_use\", ...}"
            if (content.startsWith("null", ignoreCase = true)) {
                // Remove "null" prefix and trim again
                content = content.substring(4).trim()
                LogManager.logW(TAG, "[EXTRACT_TOOL_CALL] Had 'null' prefix, removed it. Remaining: ${content.take(50)}...")
            } else {
                LogManager.logD(TAG, "[EXTRACT_TOOL_CALL] No 'null' prefix detected")
            }
            
            // Check if content is empty or just "null"
            if (content.isEmpty() || content.equals("null", ignoreCase = true)) {
                LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] Content is empty or 'null' after cleanup!")
                LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] isEmpty: ${content.isEmpty()}, equals null: ${content.equals("null", ignoreCase = true)}")
                return null
            }
            
            LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Content validation passed, length: ${content.length}")
            
            // Try to find JSON object pattern if content still looks malformed
            // Look for the first '{' and last '}' to extract JSON
            val jsonStart = content.indexOf('{')
            var jsonEnd = content.lastIndexOf('}')
            
            // Handle extra closing braces: find the matching '}' for the first '{'
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                var braceCount = 0
                var matchingEnd = -1
                for (i in jsonStart until content.length) {
                    when (content[i]) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount == 0) {
                                matchingEnd = i
                                break
                            }
                        }
                    }
                }
                if (matchingEnd > jsonStart) {
                    jsonEnd = matchingEnd
                    if (jsonEnd < content.lastIndexOf('}')) {
                        LogManager.logW(TAG, "Removed extra closing braces after position $jsonEnd")
                    }
                }
            }
            
            val jsonStr = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val extracted = content.substring(jsonStart, jsonEnd + 1)
                LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Extracted JSON from braces, length: ${extracted.length}")
                extracted
            } else {
                LogManager.logW(TAG, "[EXTRACT_TOOL_CALL] No braces found, using full content")
                content
            }
            
            LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Final JSON string: $jsonStr")
            
            try {
                val result = JSONObject(jsonStr)
                LogManager.logI(TAG, "[EXTRACT_TOOL_CALL] Successfully parsed JSON")
                result
            } catch (e: Exception) {
                LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] Failed to parse JSON: $jsonStr", e)
                null
            }
        } else {
            LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] No <tool_call> tags found in output!")
            LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] Output length: ${output.length}")
            LogManager.logE(TAG, "[EXTRACT_TOOL_CALL] Output preview: ${output.take(500)}")
            null  // tool_call is required, return null to trigger parse failure
        }
    }
    
    /**
     * Parse action from tool_call JSON (flat format)
     * Expected format: {"action": "...", ...}
     */
    private fun parseAction(toolCallJson: JSONObject): AgentAction? {
        // Direct flat format: {"action": "click", "coordinate": [x, y]}
        val actionType = toolCallJson.optString("action", "")
        
        if (actionType.isEmpty()) {
            LogManager.logE(TAG, "[PARSE] No 'action' field found in tool_call JSON")
            return null
        }
        
        LogManager.logI(TAG, "[PARSE] Parsing action type: $actionType")
        
        // MAI-UI Standard Action Space
        return when (actionType) {
            "click" -> parseClick(toolCallJson)
            "long_press" -> parseLongPress(toolCallJson)
            "type" -> parseType(toolCallJson)
            "swipe" -> parseSwipe(toolCallJson)
            "open" -> parseOpen(toolCallJson)
            "drag" -> parseDrag(toolCallJson)
            "system_button" -> parseSystemButton(toolCallJson)
            "wait" -> AgentAction.Wait
            "terminate" -> parseTerminate(toolCallJson)
            "answer" -> parseAnswer(toolCallJson)
            else -> {
                LogManager.logW(TAG, "Unknown action type: '$actionType'")
                null
            }
        }
    }
    
    private fun parseClick(args: JSONObject): AgentAction.Click? {
        val coord = args.optJSONArray("coordinate")
        if (coord == null) {
            LogManager.logE(TAG, "[PARSE_CLICK] Missing 'coordinate' field. Expected: {\"action\":\"click\",\"coordinate\":[x,y]}")
            return null
        }
        if (coord.length() < 2) {
            LogManager.logE(TAG, "[PARSE_CLICK] Invalid 'coordinate' array length: ${coord.length()}. Expected 2 elements [x,y]")
            return null
        }
        
        val rawX = coord.getInt(0)
        val rawY = coord.getInt(1)
        val x = normalizeNormalizedCoordinate(rawX, axis = "x")
        val y = normalizeNormalizedCoordinate(rawY, axis = "y")
        
        LogManager.logI(TAG, "[PARSE_CLICK] Normalized coordinates from model: [$rawX, $rawY] -> Sanitized: [$x, $y]")
        
        return AgentAction.Click(x, y)
    }
    
    private fun parseLongPress(args: JSONObject): AgentAction.LongPress? {
        val coord = args.optJSONArray("coordinate")
        if (coord == null) {
            LogManager.logE(TAG, "[PARSE_LONG_PRESS] Missing 'coordinate' field. Expected: {\"action\":\"long_press\",\"coordinate\":[x,y]}")
            return null
        }
        if (coord.length() < 2) {
            LogManager.logE(TAG, "[PARSE_LONG_PRESS] Invalid 'coordinate' array length: ${coord.length()}. Expected 2 elements [x,y]")
            return null
        }
        
        val x = normalizeNormalizedCoordinate(coord.getInt(0), axis = "x")
        val y = normalizeNormalizedCoordinate(coord.getInt(1), axis = "y")
        
        return AgentAction.LongPress(x, y)
    }
    
    private fun parseDoubleClick(args: JSONObject): AgentAction.DoubleClick? {
        val coord = args.optJSONArray("coordinate") ?: return null
        if (coord.length() < 2) return null
        
        val x = normalizeNormalizedCoordinate(coord.getInt(0), axis = "x")
        val y = normalizeNormalizedCoordinate(coord.getInt(1), axis = "y")
        
        return AgentAction.DoubleClick(x, y)
    }
    
    private fun parseType(args: JSONObject): AgentAction.Type? {
        val text = args.optString("text", "")
        if (text.isEmpty()) {
            LogManager.logE(TAG, "[PARSE_TYPE] Missing or empty 'text' field. Expected: {\"action\":\"type\",\"text\":\"your text\"}")
            return null
        }
        return AgentAction.Type(text)
    }
    
    private fun parseSwipe(args: JSONObject): AgentAction.Swipe? {
        val directionStr = args.optString("direction", "")
        if (directionStr.isEmpty()) {
            LogManager.logE(TAG, "[PARSE_SWIPE] Missing 'direction' field. Expected: {\"action\":\"swipe\",\"direction\":\"up/down/left/right\"}")
            return null
        }
        val direction = when (directionStr.lowercase()) {
            "up" -> AgentAction.Swipe.Direction.UP
            "down" -> AgentAction.Swipe.Direction.DOWN
            "left" -> AgentAction.Swipe.Direction.LEFT
            "right" -> AgentAction.Swipe.Direction.RIGHT
            else -> {
                LogManager.logE(TAG, "[PARSE_SWIPE] Invalid 'direction' value: '$directionStr'. Must be one of: up, down, left, right")
                return null
            }
        }
        
        val coord = args.optJSONArray("coordinate")
        val x = coord?.optInt(0)
        val y = coord?.optInt(1)
        
        return if (x != null && y != null) {
            AgentAction.Swipe(direction, normalizeNormalizedCoordinate(x, axis = "x"), normalizeNormalizedCoordinate(y, axis = "y"))
        } else {
            AgentAction.Swipe(direction)
        }
    }
    
    private fun parseOpen(args: JSONObject): AgentAction.Open? {
        val appName = args.optString("text", "")
        if (appName.isEmpty()) {
            LogManager.logE(TAG, "[PARSE_OPEN] Missing or empty 'text' field. Expected: {\"action\":\"open\",\"text\":\"app_name\"}")
            return null
        }
        return AgentAction.Open(appName)
    }
    
    private fun parseDrag(args: JSONObject): AgentAction.Drag? {
        val startCoord = args.optJSONArray("start_coordinate")
        if (startCoord == null) {
            LogManager.logE(TAG, "[PARSE_DRAG] Missing 'start_coordinate' field. Expected: {\"action\":\"drag\",\"start_coordinate\":[x1,y1],\"end_coordinate\":[x2,y2]}")
            return null
        }
        val endCoord = args.optJSONArray("end_coordinate")
        if (endCoord == null) {
            LogManager.logE(TAG, "[PARSE_DRAG] Missing 'end_coordinate' field. Expected: {\"action\":\"drag\",\"start_coordinate\":[x1,y1],\"end_coordinate\":[x2,y2]}")
            return null
        }
        
        if (startCoord.length() < 2) {
            LogManager.logE(TAG, "[PARSE_DRAG] Invalid 'start_coordinate' array length: ${startCoord.length()}. Expected 2 elements [x,y]")
            return null
        }
        if (endCoord.length() < 2) {
            LogManager.logE(TAG, "[PARSE_DRAG] Invalid 'end_coordinate' array length: ${endCoord.length()}. Expected 2 elements [x,y]")
            return null
        }
        
        val startX = normalizeNormalizedCoordinate(startCoord.getInt(0), axis = "x")
        val startY = normalizeNormalizedCoordinate(startCoord.getInt(1), axis = "y")
        val endX = normalizeNormalizedCoordinate(endCoord.getInt(0), axis = "x")
        val endY = normalizeNormalizedCoordinate(endCoord.getInt(1), axis = "y")
        
        return AgentAction.Drag(startX, startY, endX, endY)
    }
    
    private fun parseSystemButton(args: JSONObject): AgentAction.SystemButton? {
        // Support both "button" and "text" fields (model sometimes uses "text")
        val buttonStr = args.optString("button", "").ifEmpty { 
            args.optString("text", "") 
        }
        if (buttonStr.isEmpty()) {
            LogManager.logE(TAG, "[PARSE_SYSTEM_BUTTON] Missing 'button' or 'text' field. Expected: {\"action\":\"system_button\",\"button\":\"back/home/menu/enter\"}")
            LogManager.logE(TAG, "[PARSE_SYSTEM_BUTTON] Note: Some models use 'text' instead of 'button', both are supported")
            return null
        }
        val button = when (buttonStr.lowercase()) {
            "back" -> AgentAction.SystemButton.Button.BACK
            "home" -> AgentAction.SystemButton.Button.HOME
            "menu" -> AgentAction.SystemButton.Button.MENU
            "enter" -> AgentAction.SystemButton.Button.ENTER
            else -> {
                LogManager.logE(TAG, "[PARSE_SYSTEM_BUTTON] Invalid button value: '$buttonStr'. Must be one of: back, home, menu, enter")
                return null
            }
        }
        return AgentAction.SystemButton(button)
    }
    
    private fun parseTerminate(args: JSONObject): AgentAction.Terminate? {
        val statusStr = args.optString("status", "")
        if (statusStr.isEmpty()) {
            LogManager.logE(TAG, "[PARSE_TERMINATE] Missing 'status' field. Expected: {\"action\":\"terminate\",\"status\":\"success/fail\"}")
            return null
        }
        val status = when (statusStr.lowercase()) {
            "success" -> AgentAction.Terminate.Status.SUCCESS
            "fail" -> AgentAction.Terminate.Status.FAIL
            else -> {
                LogManager.logE(TAG, "[PARSE_TERMINATE] Invalid 'status' value: '$statusStr'. Must be either 'success' or 'fail'")
                return null
            }
        }
        return AgentAction.Terminate(status)
    }
    
    private fun parseAnswer(args: JSONObject): AgentAction.Answer? {
        val text = args.optString("text", "") ?: return null
        if (text.isEmpty()) return null
        return AgentAction.Answer(text)
    }
    
    private fun parseAskUser(args: JSONObject): AgentAction.AskUser? {
        val text = args.optString("text", "") ?: return null
        if (text.isEmpty()) return null
        return AgentAction.AskUser(text)
    }
    
    // Store coordinate error for feedback
    private var lastCoordinateError: String? = null
    
    /**
     * Get last coordinate error message (if any)
     */
    fun getLastCoordinateError(): String? = lastCoordinateError
    
    /**
     * Clear last coordinate error
     */
    fun clearCoordinateError() {
        lastCoordinateError = null
    }
    
    private fun normalizeNormalizedCoordinate(modelCoord: Int, axis: String): Int {
        // Model outputs normalized coordinates in [0..999].
        // We clamp here and keep an error message for prompt feedback.
        val sanitized = modelCoord.coerceIn(0, 999)
        if (sanitized != modelCoord) {
            LogManager.logW(TAG, "Normalized coordinate $axis=$modelCoord is out of range, sanitized to $sanitized")
            lastCoordinateError = "⚠️ Previous normalized coordinate was invalid ($axis=$modelCoord). Normalized coordinates must be integers in range [0..999]."
        }
        return sanitized
    }
    
    /**
     * Check if model output contains agent action tags
     */
    fun containsAgentAction(output: String): Boolean {
        return output.contains("<tool_call>") && output.contains("</tool_call>")
    }
    
    /**
     * Convert unified Action to legacy AgentAction
     */
    private fun convertToAgentAction(action: com.example.offlineai.agent.Action): AgentAction? {
        return when (action.type) {
            com.example.offlineai.agent.ActionType.CLICK -> {
                val coord = action.coordinate ?: return null
                AgentAction.Click(coord[0], coord[1])
            }
            com.example.offlineai.agent.ActionType.LONG_PRESS -> {
                val coord = action.coordinate ?: return null
                AgentAction.LongPress(coord[0], coord[1])
            }
            com.example.offlineai.agent.ActionType.DOUBLE_CLICK -> {
                val coord = action.coordinate ?: return null
                AgentAction.DoubleClick(coord[0], coord[1])
            }
            com.example.offlineai.agent.ActionType.TYPE -> {
                val text = action.text ?: return null
                AgentAction.Type(text)
            }
            com.example.offlineai.agent.ActionType.SWIPE -> {
                val direction = action.direction ?: return null
                val dir = when (direction.lowercase()) {
                    "up" -> AgentAction.Swipe.Direction.UP
                    "down" -> AgentAction.Swipe.Direction.DOWN
                    "left" -> AgentAction.Swipe.Direction.LEFT
                    "right" -> AgentAction.Swipe.Direction.RIGHT
                    else -> return null
                }
                val coord = action.coordinate
                if (coord != null && coord.size >= 2) {
                    AgentAction.Swipe(dir, coord[0], coord[1])
                } else {
                    AgentAction.Swipe(dir)
                }
            }
            com.example.offlineai.agent.ActionType.DRAG -> {
                val start = action.startCoordinate ?: return null
                val end = action.endCoordinate ?: return null
                AgentAction.Drag(start[0], start[1], end[0], end[1])
            }
            com.example.offlineai.agent.ActionType.OPEN -> {
                val appName = action.text ?: return null
                AgentAction.Open(appName)
            }
            com.example.offlineai.agent.ActionType.SYSTEM_BUTTON -> {
                val button = action.button ?: return null
                val btn = when (button.lowercase()) {
                    "back" -> AgentAction.SystemButton.Button.BACK
                    "home" -> AgentAction.SystemButton.Button.HOME
                    "menu" -> AgentAction.SystemButton.Button.MENU
                    "enter" -> AgentAction.SystemButton.Button.ENTER
                    else -> return null
                }
                AgentAction.SystemButton(btn)
            }
            com.example.offlineai.agent.ActionType.WAIT -> {
                AgentAction.Wait
            }
            com.example.offlineai.agent.ActionType.TERMINATE -> {
                val status = action.status ?: "fail"
                val st = when (status.lowercase()) {
                    "success" -> AgentAction.Terminate.Status.SUCCESS
                    "fail" -> AgentAction.Terminate.Status.FAIL
                    else -> AgentAction.Terminate.Status.FAIL
                }
                AgentAction.Terminate(st)
            }
            com.example.offlineai.agent.ActionType.ANSWER -> {
                val text = action.text ?: return null
                AgentAction.Answer(text)
            }
            else -> {
                LogManager.logW(TAG, "Unsupported action type for conversion: ${action.type}")
                null
            }
        }
    }
}
