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

    @JvmField
    var benchmarkInfo: String? = null

    var displayText: String? = null
        get() = field?:""

    var thinkingText: String? = null

    // Debug section (for debug information)
    var debugText: String? = null
    var showDebug: Boolean = true

    // Performance section (for performance metrics)
    var performanceText: String? = null
    var showPerformance: Boolean = true

    var audioDuration = 0f

    private var _hasOmniAudio:Boolean = false

    constructor(time: String?, type: Int, text: String?) {
        this.time = time
        this.type = type
        this.text = text
        this.displayText = text
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
    }
}

