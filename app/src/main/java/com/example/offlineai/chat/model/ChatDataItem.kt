// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.example.offlineai.chat.model

import android.net.Uri
import com.example.offlineai.chat.chatlist.AudioPlayerComponent
import com.example.offlineai.chat.chatlist.ChatViewHolders
import java.io.File

class ChatDataItem {
    var loading: Boolean = false

    @JvmField
    var time: String? = null
    @JvmField
    var audioPlayComponent: AudioPlayerComponent? = null
    @JvmField
    var text: String? = null
    var type: Int
        private set
    @JvmField
    var imageUri: Uri? = null

    @JvmField
    var audioUri: Uri? = null
    
    // Support multiple audio files
    @JvmField
    var audioUris: MutableList<Uri>? = null

    // Support generic file attachments (zip, pdf, ppt, etc.)
    @JvmField
    var fileUris: MutableList<Uri>? = null

    @JvmField
    var benchmarkInfo: String? = null

    var displayText: String? = null
        get() = field?:""

    // Keep markdown rendering once activated during streaming.
    // This prevents payload updates from downgrading rendered formulas to plain text.
    var markdownLocked: Boolean = false

    // Whether current streaming text tail contains unclosed LaTeX formula.
    // When true, UI should keep previous rendered content stable.
    var hasUnclosedLatex: Boolean = false

    // Last stable text snapshot that is safe for markdown rendering.
    // During unclosed formula streaming, render this prefix and keep tail as plain text.
    var stableMarkdownText: String? = null

    // Cache key for the last text passed to markdown.setMarkdown().
    // When the normalized text equals this key, setMarkdown is skipped to avoid redundant LaTeX re-parse.
    @JvmField
    var cachedSpannedKey: String? = null

    // Cached plain tail appended after stable markdown prefix during unclosed LaTeX streaming.
    // Used to append only delta chars and avoid rebuilding previously rendered prefix.
    @JvmField
    var streamingPlainTail: String = ""

    var thinkingText: String? = null

    // Debug section (for debug information)
    var debugText: String? = null
    var showDebug: Boolean = true

    // Performance section (for performance metrics)
    var performanceText: String? = null
    var showPerformance: Boolean = true

    // Agent section (for collapsible agent execution steps)
    var agentText: String? = null
    var showAgent: Boolean = false  // Default collapsed

    var audioDuration = 0f

    private var _hasOmniAudio:Boolean = false

    constructor(time: String?, type: Int, text: String?) {
        this.time = time
        this.type = type
        this.text = text
        this.displayText = text
        this.stableMarkdownText = text
    }

    constructor(type: Int) {
        this.type = type
    }

    var hasOmniAudio:Boolean
        get() = _hasOmniAudio
        set(value) {
            _hasOmniAudio = value
        }

    val audioPath: String?
        get() {
            if (this.audioUri != null) {
                val scheme = audioUri!!.scheme
                val uriString = audioUri.toString()
                
                // Handle different URI formats
                return when (scheme) {
                    "file" -> {
                        // Proper file:// URI, remove the "file://" prefix
                        if (uriString.startsWith("file://")) {
                            uriString.substring(7)
                        } else {
                            audioUri!!.path
                        }
                    }
                    null -> {
                        // Direct path string (from Uri.parse(path)), return as-is
                        uriString
                    }
                    else -> null  // Other schemes (content://, etc.)
                }
            }
            return null
        }
    
    val audioPaths: List<String>?
        get() {
            val paths = mutableListOf<String>()
            // Add primary audio
            audioPath?.let { paths.add(it) }
            // Add additional audios
            audioUris?.forEach { uri ->
                if ("file" == uri.scheme && uri.path != null) {
                    paths.add(uri.path!!)
                }
            }
            return if (paths.isNotEmpty()) paths else null
        }

    var showThinking: Boolean = true

    var thinkingFinishedTime = -1L

    fun toggleThinking() {
        showThinking = !showThinking
    }

    fun toggleDebug() {
        showDebug = !showDebug
    }

    fun togglePerformance() {
        showPerformance = !showPerformance
    }

    fun toggleAgent() {
        showAgent = !showAgent
    }

    companion object {
        fun createImageInputData(timeString: String?, text: String?, imageUri: Uri?): ChatDataItem {
            val result = ChatDataItem(timeString, ChatViewHolders.USER, text)
            result.imageUri = imageUri
            return result
        }

        fun createAudioInputData(
            timeString: String?,
            text: String?,
            audioPath: String,
            duration: Float
        ): ChatDataItem {
            val result = ChatDataItem(timeString, ChatViewHolders.USER, text)
            result.audioUri = Uri.fromFile(File(audioPath))
            result.audioDuration = duration
            return result
        }

        fun createFileInputData(
            timeString: String?,
            text: String?,
            filePaths: List<String>
        ): ChatDataItem {
            val result = ChatDataItem(timeString, ChatViewHolders.USER, text)
            result.fileUris = filePaths.map { Uri.fromFile(File(it)) }.toMutableList()
            return result
        }
    }
}

