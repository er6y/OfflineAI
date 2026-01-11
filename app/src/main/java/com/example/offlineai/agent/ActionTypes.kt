package com.example.offlineai.agent.model

import org.json.JSONObject

/**
 * Agent Action Types - MAI-UI Action Space
 * Based on MAI-UI prompt.py action definitions
 */
sealed class AgentAction {
    abstract fun toJson(): JSONObject
    
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
     * Terminate task with status
     * {"action": "terminate", "status": "success/fail"}
     */
    data class Terminate(val status: Status) : AgentAction() {
        enum class Status(val value: String) {
            SUCCESS("success"),
            FAIL("fail")
        }
        
        override fun toJson() = JSONObject().apply {
            put("action", "terminate")
            put("status", status.value)
        }
    }
    
    /**
     * Answer user with text
     * {"action": "answer", "text": "..."}
     */
    data class Answer(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "answer")
            put("text", text)
        }
    }
    
    /**
     * Ask user for more information
     * {"action": "ask_user", "text": "..."}
     */
    data class AskUser(val text: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "ask_user")
            put("text", text)
        }
    }
}

/**
 * Parsed model response containing thinking and action
 */
data class AgentResponse(
    val thinking: String,
    val action: AgentAction
)

/**
 * Execution result for an action
 */
data class ExecutionResult(
    val success: Boolean,
    val message: String = "",
    val error: Throwable? = null
)
