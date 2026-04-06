package com.example.offlineai.agent.parser

import android.content.Context
import com.example.offlineai.LogManager
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.AgentResponse
import com.example.offlineai.agent.ActionFormatRegistry

/**
 * Action Parser - thin wrapper that delegates to ActionFormat implementations.
 * Unified to OpenAI function-call format.
 */
object ActionParser {
    
    private const val TAG = "ActionParser"
    
    /**
     * Parse model output and return list of actions.
     */
    fun parseActions(modelOutput: String, apiUrl: String, modelName: String = "", context: Context? = null): List<AgentAction>? {
        try {
            LogManager.logI(TAG, "[PARSE_DEBUG] Input length: ${modelOutput.length}, apiUrl: $apiUrl, model: $modelName")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (first 200 chars): ${modelOutput.take(200)}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (last 200 chars): ${modelOutput.takeLast(200)}")
            
            val format = resolveFormat(apiUrl, modelName)
            LogManager.logI(TAG, "[PARSE_DEBUG] Using format: ${format.getFormatName()}")
            
            val actions = format.parseActions(modelOutput)
            if (actions.isEmpty()) {
                LogManager.logE(TAG, "[PARSE_DEBUG] No actions parsed")
                return null
            }
            
            // Validate and clamp coordinates for all actions
            val validatedActions = actions.map { validateCoordinates(it) }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully parsed ${validatedActions.size} actions: ${validatedActions.map { it.javaClass.simpleName }}")
            return validatedActions
        } catch (e: Exception) {
            LogManager.logE(TAG, "[PARSE_DEBUG] Exception during parse", e)
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Legacy single-action parse (for backward compatibility)
     */
    fun parse(modelOutput: String, apiUrl: String, modelName: String = "", context: Context? = null): AgentResponse? {
        val actions = parseActions(modelOutput, apiUrl, modelName, context) ?: return null
        // Return first non-context action (or first action if all are context)
        val mainAction = actions.firstOrNull { it !is AgentAction.Context } ?: actions.firstOrNull() ?: return null
        return AgentResponse(mainAction)
    }
    
    /**
     * Resolve the unified ActionFormat.
     */
    fun resolveFormat(apiUrl: String, modelName: String): com.example.offlineai.agent.ActionFormat {
        LogManager.logI(TAG, "[PARSE_DEBUG] Unified format: OpenAI FuncCall")
        return ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
    }
    
    // Store coordinate error for feedback
    private var lastCoordinateError: String? = null
    
    fun getLastCoordinateError(): String? = lastCoordinateError
    fun clearCoordinateError() { lastCoordinateError = null }
    
    /**
     * Validate and clamp coordinates in AgentAction to [0..999] range.
     */
    private fun validateCoordinates(action: AgentAction): AgentAction {
        fun clamp(v: Int, axis: String): Int {
            val clamped = v.coerceIn(0, 999)
            if (clamped != v) {
                LogManager.logW(TAG, "Coordinate $axis=$v out of range, clamped to $clamped")
                lastCoordinateError = "⚠️ Previous normalized coordinate was invalid ($axis=$v). Must be in [0..999]."
            }
            return clamped
        }
        
        return when (action) {
            is AgentAction.Click -> AgentAction.Click(clamp(action.x, "x"), clamp(action.y, "y"))
            is AgentAction.LongPress -> AgentAction.LongPress(clamp(action.x, "x"), clamp(action.y, "y"))
            is AgentAction.DoubleClick -> AgentAction.DoubleClick(clamp(action.x, "x"), clamp(action.y, "y"))
            is AgentAction.Swipe -> {
                val cx = action.x?.let { clamp(it, "x") }
                val cy = action.y?.let { clamp(it, "y") }
                AgentAction.Swipe(action.direction, cx, cy)
            }
            is AgentAction.Drag -> AgentAction.Drag(
                clamp(action.startX, "startX"), clamp(action.startY, "startY"),
                clamp(action.endX, "endX"), clamp(action.endY, "endY")
            )
            else -> action
        }
    }
    
    /**
     * Check if model output contains OpenAI-style function calls.
     */
    fun containsAgentAction(output: String): Boolean {
        val text = output.trim()
        return text.contains("\"tool_calls\"") ||
                (text.contains("\"name\"") && text.contains("\"parameters\""))
    }
}
