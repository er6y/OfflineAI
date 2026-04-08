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
     * Create a new file with full content (single string, supports \n)
     * {"action": "file_new", "path": "/sdcard/new.txt", "new_content": "line1\nline2"}
     */
    data class FileNew(
        val path: String,
        val newContent: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_new")
            put("path", path)
            put("new_content", newContent)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Context - update agent's memory/context for next step
     * {"action": "context", "fact": "append-only stable facts", "text": "current round summary"}
     * This action does not execute anything, only updates currentContext
     * Must be output with every step to maintain memory continuity
     */
    data class Context(
        val text: String = "",
        val fact: String = ""
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "context")
            if (fact.isNotEmpty()) put("fact", fact)
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

    /**
     * Open file for editing
     * {"action": "file_open", "path": "/sdcard/file.txt"}
     */
    data class FileOpen(val path: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_open")
            put("path", path)
        }
        override fun needsScreenshot() = false  // File operation, no screenshot needed
    }

    /**
     * Read lines from opened file
     * {"action": "file_read", "path": "/sdcard/file.txt", "start_line": 1, "read_count": 50}
     */
    data class FileRead(
        val path: String,
        val startLine: Int,
        val readCount: Int
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_read")
            put("path", path)
            put("start_line", startLine)
            put("read_count", readCount)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Replace lines in opened file
     * {"action": "file_edit", "path": "/sdcard/file.txt", "start_line": 5, "end_line": 10, "new_content": "line1\nline2"}
     */
    data class FileEdit(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val newContent: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_edit")
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
            put("new_content", newContent)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search keyword in opened file
     * {"action": "file_search", "path": "/sdcard/file.txt", "keyword": "text", "ignore_case": true}
     */
    data class FileSearch(
        val path: String,
        val keyword: String,
        val ignoreCase: Boolean = true
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_search")
            put("path", path)
            put("keyword", keyword)
            put("ignore_case", ignoreCase)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Save changes to file
     * {"action": "file_save", "path": "/sdcard/file.txt"}
     */
    data class FileSave(val path: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_save")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * List directory contents
     * {"action": "file_list_dir", "path": "/sdcard/", "recursive": false}
     */
    data class FileListDir(
        val path: String,
        val recursive: Boolean = false
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_list_dir")
            put("path", path)
            put("recursive", recursive)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Copy file or directory
     * {"action": "file_copy", "src": "/sdcard/src.txt", "dst": "/sdcard/dst.txt"}
     */
    data class FileCopy(
        val src: String,
        val dst: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_copy")
            put("src", src)
            put("dst", dst)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Delete file or directory
     * {"action": "file_delete", "path": "/sdcard/file.txt", "recursive": false}
     */
    data class FileDelete(
        val path: String,
        val recursive: Boolean = false
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_delete")
            put("path", path)
            put("recursive", recursive)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search files by regex pattern in directory
     * {"action": "file_search_regex", "path": "/sdcard/", "pattern": ".*\\.txt$", "recursive": true}
     */
    data class FileSearchRegex(
        val path: String,
        val pattern: String,
        val recursive: Boolean = true
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_search_regex")
            put("path", path)
            put("pattern", pattern)
            put("recursive", recursive)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Create directory
     * {"action": "file_create_dir", "path": "/sdcard/new_folder"}
     */
    data class FileCreateDir(val path: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "file_create_dir")
            put("path", path)
        }
        override fun needsScreenshot() = false
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
