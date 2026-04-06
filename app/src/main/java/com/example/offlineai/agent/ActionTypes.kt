package com.example.offlineai.agent.model

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
     * Type text
     * {"action": "type", "text": "..."}
     */
    data class Type(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "type")
            put("text", text)
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
     * Wait for UI to stabilize
     * {"action": "wait"}
     */
    object Wait : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "wait")
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
     * Terminate task with status
     * {"action": "terminate", "status": "success/fail"}
     */
    data class Terminate(val status: Status, val text: String = "") : AgentAction() {
        enum class Status(val value: String) {
            SUCCESS("success"),
            FAIL("fail")
        }
        
        override fun toJson() = JSONObject().apply {
            put("action", "terminate")
            put("status", status.value)
            if (text.isNotEmpty()) put("text", text)
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
     * Context - update agent's memory/context for next step
     * {"action": "context", "text": "current task state, key info, errors, coordinates, strategy"}
     * This action does not execute anything, only updates currentContext
     * Must be output with every step to maintain memory continuity
     */
    data class Context(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "context")
            put("text", text)
        }
        override fun needsScreenshot() = false  // Context update doesn't need screenshot
    }
    
    /**
     * Ask user - request user intervention with optional question/instruction
     * {"action": "ask_user", "text": "question or instruction for user"}
     * After user confirms (with optional text input), agent continues with user response injected
     */
    data class AskUser(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "ask_user")
            put("text", text)
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
