// Created by ruoyi.sjd on 2025/1/9.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.example.offlineai.chat.utils

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

class AudioPlayService private constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioPlayerCallback? = null

    private val isMediaPlaying: Boolean
        get() = try {
            mediaPlayer != null && mediaPlayer!!.isPlaying
        } catch (e: IllegalStateException) {
            false
        }

    fun playAudio(audioPath: String?, callback: AudioPlayerCallback?) {
        if (audioPath == null) {
            return
        }
        
        // CRITICAL: Check if file exists before attempting to play
        val audioFile = java.io.File(audioPath)
        android.util.Log.i(TAG, "[PLAY_AUDIO] Attempting to play: $audioPath")
        android.util.Log.i(TAG, "[PLAY_AUDIO] File exists: ${audioFile.exists()}, canRead: ${audioFile.canRead()}, size: ${audioFile.length()}")
        
        if (!audioFile.exists()) {
            android.util.Log.e(TAG, "[PLAY_AUDIO] File not found: $audioPath")
            callback?.onPlayError()
            return
        }
        
        if (isMediaPlaying) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
        }
        this.callback = callback
        this.currentAudioPath = audioPath

        mediaPlayer = MediaPlayer()
        try {
            mediaPlayer!!.setDataSource(audioPath)
            mediaPlayer!!.prepare()
            mediaPlayer!!.start()

            callback?.onPlayStart()

            // Monitor progress
            monitorProgress()

            mediaPlayer!!.setOnCompletionListener { _ ->
                callback?.onPlayFinish()
                resetMediaPlayer()
            }

            mediaPlayer!!.setOnErrorListener { _, _, _ ->
                callback?.onPlayError()
                resetMediaPlayer()
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[PLAY_AUDIO] Error playing audio: ${e.message}", e)
            callback?.onPlayError()
            resetMediaPlayer()
        }
    }

    fun pauseAudio(@Suppress("UNUSED_PARAMETER") audioPath: String?) {
        try {
            if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
            }
        } catch (e: IllegalStateException) {
            // Ignore the exception
        }
    }

    private fun resetMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer!!.release()
            mediaPlayer = null
            currentAudioPath = null
        }
    }

    private fun monitorProgress() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            handler.postDelayed({
                if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    val currentPosition = mediaPlayer!!.currentPosition
                    val duration = mediaPlayer!!.duration
                    val progress = currentPosition.toFloat() / duration
                    if (callback != null) {
                        callback!!.onPlayProgress(progress)
                    }
                    monitorProgress()
                }
            }, 200)
        }
    }

    fun seekAudio(audioPath: String, v: Float) {
        if (audioPath != currentAudioPath) {
            return
        }
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.seekTo((v * mediaPlayer!!.duration).toInt())
        }
    }

    fun destroy() {
        if (mediaPlayer != null) {
            mediaPlayer!!.release()
        }
        handler.removeMessages(0)
    }

    interface AudioPlayerCallback {
        fun onPlayFinish()
        fun onPlayError()
        fun onPlayStart()
        fun onPlayProgress(progress: Float) // progress as percent
    }

    companion object {
        const val TAG: String = "AudioPlayService"

        var instance: AudioPlayService? = null
            // Singleton instance
            get() {
                if (field == null) {
                    synchronized(AudioPlayService::class.java) {
                        if (field == null) {
                            field = AudioPlayService()
                        }
                    }
                }
                return field
            }
            private set
    }
}
