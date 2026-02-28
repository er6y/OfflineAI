package com.example.offlineai.agent.parser

import android.content.Context
import com.example.offlineai.ConfigManager
import com.example.offlineai.LogManager
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.AgentResponse
import com.example.offlineai.agent.ActionFormatRegistry

/**
 * Action Parser - thin wrapper that delegates to ActionFormat implementations.
 * Format selection is based on user settings or API URL.
 */
object ActionParser {
    
    private const val TAG = "ActionParser"
    
    /**
     * Parse model output and return thinking + list of actions (supports context + actual action)
     */
    fun parseActions(modelOutput: String, apiUrl: String, modelName: String = "", context: Context? = null): Pair<String, List<AgentAction>>? {
        try {
            LogManager.logI(TAG, "[PARSE_DEBUG] Input length: ${modelOutput.length}, apiUrl: $apiUrl, model: $modelName")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (first 200 chars): ${modelOutput.take(200)}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (last 200 chars): ${modelOutput.takeLast(200)}")
            
            val format = resolveFormat(apiUrl, modelName, context)
            LogManager.logI(TAG, "[PARSE_DEBUG] Using format: ${format.getFormatName()}")
            
            val (thinking, actions) = format.parseActionsWithCoT(modelOutput)
            if (actions.isEmpty()) {
                LogManager.logE(TAG, "[PARSE_DEBUG] No actions parsed")
                return null
            }
            
            // Validate and clamp coordinates for all actions
            val validatedActions = actions.map { validateCoordinates(it) }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully parsed ${validatedActions.size} actions: ${validatedActions.map { it.javaClass.simpleName }}")
            return Pair(thinking ?: "", validatedActions)
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
        val result = parseActions(modelOutput, apiUrl, modelName, context) ?: return null
        val (thinking, actions) = result
        // Return first non-context action (or first action if all are context)
        val mainAction = actions.firstOrNull { it !is AgentAction.Context } ?: actions.firstOrNull() ?: return null
        return AgentResponse(thinking, mainAction)
    }
    
    /**
     * Resolve the ActionFormat based on user settings or API URL.
     */
    fun resolveFormat(apiUrl: String, modelName: String, context: Context?): com.example.offlineai.agent.ActionFormat {
        if (context != null) {
            val userFormat = ConfigManager.getAgentActionFormat(context)
            LogManager.logI(TAG, "[PARSE_DEBUG] User selected format: $userFormat")
            
            if (userFormat != "Auto") {
                val registryName = when (userFormat) {
                    "AutoGLM-Phone" -> "AutoGLM"
                    else -> userFormat
                }
                return ActionFormatRegistry.getFormatByName(registryName) 
                    ?: ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
            }
        }
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
     * Check if model output contains agent action tags
     */
    fun containsAgentAction(output: String): Boolean {
        return output.contains("<tool_call>") && output.contains("</tool_call>")
    }
}
