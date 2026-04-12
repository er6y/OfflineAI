package com.example.offlineai.agent.model

import org.json.JSONArray
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
     * Create a new file with content. Fails if file already exists.
     * {"name": "create_file", "parameters": {"path": "/sdcard/new.txt", "content": "line1\nline2"}}
     */
    data class CreateFile(
        val path: String,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "create_file")
            put("path", path)
            put("content", content)
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
     * Read entire file content. Returns full text (truncated at 2000 lines).
     * {"name": "read_file", "parameters": {"path": "/sdcard/file.txt"}}
     */
    data class ReadFile(
        val path: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "read_file")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Overwrite file with content. Creates file if not exists, overwrites if exists.
     * {"name": "write_file", "parameters": {"path": "/sdcard/file.txt", "content": "new content"}}
     */
    data class WriteFile(
        val path: String,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "write_file")
            put("path", path)
            put("content", content)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Read lines [start_line, end_line] from file. Out-of-range reads to EOF without error.
     * {"name": "read_lines", "parameters": {"path": "/sdcard/file.txt", "start_line": 1, "end_line": 50}}
     */
    data class ReadLines(
        val path: String,
        val startLine: Int,
        val endLine: Int
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "read_lines")
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Replace lines [start_line, end_line] with new content. Auto-saves.
     * {"name": "edit_lines", "parameters": {"path": "/sdcard/file.txt", "start_line": 5, "end_line": 10, "content": "new line 1\nnew line 2"}}
     */
    data class EditLines(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val content: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "edit_lines")
            put("path", path)
            put("start_line", startLine)
            put("end_line", endLine)
            put("content", content)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search text content in file, returns matching line numbers + content snippets.
     * {"name": "grep", "parameters": {"path": "/sdcard/file.txt", "keyword": "TODO"}}
     */
    data class Grep(
        val path: String,
        val keyword: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "grep")
            put("path", path)
            put("keyword", keyword)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Rename or move a file. Fails if new_path already exists.
     * {"name": "rename_file", "parameters": {"old_path": "/sdcard/a.txt", "new_path": "/sdcard/b.txt"}}
     */
    data class RenameFile(
        val oldPath: String,
        val newPath: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "rename_file")
            put("old_path", oldPath)
            put("new_path", newPath)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Delete file or directory.
     * {"name": "delete_file", "parameters": {"path": "/sdcard/file.txt", "recursive": false}}
     */
    data class DeleteFile(
        val path: String,
        val recursive: Boolean = false
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "delete_file")
            put("path", path)
            put("recursive", recursive)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Copy file or directory. Overwrites destination if exists.
     * {"name": "copy_file", "parameters": {"src": "/sdcard/src.txt", "dst": "/sdcard/dst.txt"}}
     */
    data class CopyFile(
        val src: String,
        val dst: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "copy_file")
            put("src", src)
            put("dst", dst)
        }
        override fun needsScreenshot() = false
    }

    /**
     * List directory contents.
     * {"name": "list_dir", "parameters": {"path": "/sdcard/"}}
     */
    data class ListDir(
        val path: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "list_dir")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Create directory. Silent success if already exists.
     * {"name": "mkdir", "parameters": {"path": "/sdcard/new_folder"}}
     */
    data class Mkdir(val path: String) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "mkdir")
            put("path", path)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Search files by name keyword in directory.
     * {"name": "search_files", "parameters": {"path": "/sdcard/", "keyword": ".txt"}}
     */
    data class SearchFiles(
        val path: String,
        val keyword: String
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "search_files")
            put("path", path)
            put("keyword", keyword)
        }
        override fun needsScreenshot() = false
    }

    /**
     * Run Python code asynchronously via Chaquopy
     * {"action": "python_run", "code": "print('hello')"}
     * {"action": "python_run", "file": "/sdcard/script.py", "args": ["--port", "8000"]}
     */
    data class PythonRun(
        val code: String? = null,
        val file: String? = null,
        val args: List<String> = emptyList()
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python_run")
            code?.let { put("code", it) }
            file?.let { put("file", it) }
            if (args.isNotEmpty()) put("args", JSONArray(args))
        }
        override fun needsScreenshot() = false
    }

    /**
     * Query all Python sessions status
     * {"action": "python_status"}
     */
    data class PythonStatus(
        val dummy: String = ""  // No parameters needed
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python_status")
        }
        override fun needsScreenshot() = false
    }

    /**
     * Kill Python session
     * {"action": "python_kill"}
     */
    data class PythonKill(
        val dummy: String = ""
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "python_kill")
        }
        override fun needsScreenshot() = false
    }

    /**
     * Show media/file output in chat UI
     * References the original file path (no copy) and writes markdown to conversation.md
     *
     * {"name": "show_media", "parameters": {"path": "/sdcard/output.png", "description": "Generated chart"}}
     *
     * Supports: image (jpg/png/gif/webp/bmp), audio (wav/mp3/m4a), generic files (zip/txt/pdf/etc.)
     * Auto-detects type from file extension.
     */
    data class ShowMedia(
        val path: String,
        val description: String = ""
    ) : AgentAction() {
        override fun toJson() = JSONObject().apply {
            put("action", "show_media")
            put("path", path)
            if (description.isNotEmpty()) put("description", description)
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

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "ogg", "flac", "aac")

/**
 * Classify file type by extension.
 * @return "image", "audio", or "file"
 */
fun classifyFileType(ext: String): String = when (ext.lowercase()) {
    in IMAGE_EXTENSIONS -> "image"
    in AUDIO_EXTENSIONS -> "audio"
    else -> "file"
}
