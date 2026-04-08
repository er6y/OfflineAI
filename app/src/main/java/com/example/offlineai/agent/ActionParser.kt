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
    private var lastParseError: String? = null
    
    /**
     * Parse model output and return list of actions.
     */
    fun parseActions(modelOutput: String, apiUrl: String, modelName: String = "", context: Context? = null): List<AgentAction>? {
        try {
            lastParseError = null
            LogManager.logI(TAG, "[PARSE_DEBUG] Input length: ${modelOutput.length}, apiUrl: $apiUrl, model: $modelName")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (first 200 chars): ${modelOutput.take(200)}")
            LogManager.logD(TAG, "[PARSE_DEBUG] Input preview (last 200 chars): ${modelOutput.takeLast(200)}")
            
            val format = resolveFormat(apiUrl, modelName)
            LogManager.logI(TAG, "[PARSE_DEBUG] Using format: ${format.getFormatName()}")
            
            val actions = format.parseActions(modelOutput)
            if (actions.isEmpty()) {
                lastParseError = format.getLastParseError() ?: "No actions parsed"
                LogManager.logE(TAG, "[PARSE_DEBUG] No actions parsed: $lastParseError")
                return null
            }
            
            // Validate coordinates only (no auto-clamp to avoid silently changing model intent)
            val validatedActions = actions.map { validateCoordinates(it) }
            
            LogManager.logI(TAG, "[PARSE_DEBUG] Successfully parsed ${validatedActions.size} actions: ${validatedActions.map { it.javaClass.simpleName }}")
            return validatedActions
        } catch (e: Exception) {
            lastParseError = e.message ?: "Unknown parse exception"
            LogManager.logE(TAG, "[PARSE_DEBUG] Exception during parse", e)
            e.printStackTrace()
            return null
        }
    }

    fun getLastParseError(): String? = lastParseError
    
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
     * Validate coordinates in AgentAction to [0..999] range.
     * Do not mutate action parameters. Execution layer will reject invalid actions.
     */
    private fun validateCoordinates(action: AgentAction): AgentAction {
        fun check(v: Int, axis: String) {
            if (v !in 0..999) {
                LogManager.logW(TAG, "Coordinate $axis=$v out of range")
                lastCoordinateError = "⚠️ Previous normalized coordinate was invalid ($axis=$v). Must be in [0..999]."
            }
        }
        
        when (action) {
            is AgentAction.Click -> {
                check(action.x, "x")
                check(action.y, "y")
            }
            is AgentAction.LongPress -> {
                check(action.x, "x")
                check(action.y, "y")
            }
            is AgentAction.DoubleClick -> {
                check(action.x, "x")
                check(action.y, "y")
            }
            is AgentAction.Swipe -> {
                action.x?.let { check(it, "x") }
                action.y?.let { check(it, "y") }
            }
            is AgentAction.Drag -> {
                check(action.startX, "startX")
                check(action.startY, "startY")
                check(action.endX, "endX")
                check(action.endY, "endY")
            }
            else -> Unit
        }
        return action
    }
    
    /**
     * Check if model output contains OpenAI-style function calls.
     */
    fun containsAgentAction(output: String): Boolean {
        val text = output.trim()
        return text.contains("\"name\"") && text.contains("\"parameters\"")
    }
}
