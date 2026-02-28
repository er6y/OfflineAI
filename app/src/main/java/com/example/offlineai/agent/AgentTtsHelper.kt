package com.example.offlineai.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.offlineai.LogManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Agent-dedicated system TTS helper.
 * Wraps Android TextToSpeech for use in AgentAccessibilityService.
 * Initialized at agent task start, shut down at agent task end.
 * speak() is fire-and-forget (non-blocking).
 */
class AgentTtsHelper(private val context: Context) {

    companion object {
        private const val TAG = "AgentTtsHelper"
    }

    private var tts: TextToSpeech? = null
    private val isReady = AtomicBoolean(false)
    private val pendingTexts = mutableListOf<String>()
    // Tracks the last utterance ID so awaitSpeechDone() knows when to unblock
    // @Volatile ensures visibility across threads (TTS callback thread vs coroutine thread)
    @Volatile private var lastUtteranceId: String? = null
    // Guard protects lastUtteranceId + speechDoneCallback as an atomic unit
    private val speechLock = Any()
    @Volatile private var speechDoneCallback: (() -> Unit)? = null
    // Set to true when onDone fires before awaitSpeechDone registers its callback
    @Volatile private var speechAlreadyDone = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall back to device default locale
                    tts?.setLanguage(Locale.getDefault())
                    LogManager.logW(TAG, "[AGENT_TTS] Chinese locale not supported, falling back to default")
                } else {
                    LogManager.logI(TAG, "[AGENT_TTS] TTS initialized with Chinese locale")
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        LogManager.logD(TAG, "[AGENT_TTS] Done: $utteranceId")
                        synchronized(speechLock) {
                            if (utteranceId == lastUtteranceId) {
                                val cb = speechDoneCallback
                                if (cb != null) {
                                    speechDoneCallback = null
                                    cb.invoke()
                                } else {
                                    // awaitSpeechDone hasn't registered yet; mark done so it returns immediately
                                    speechAlreadyDone = true
                                }
                            }
                        }
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        LogManager.logW(TAG, "[AGENT_TTS] Error: $utteranceId")
                        synchronized(speechLock) {
                            if (utteranceId == lastUtteranceId) {
                                val cb = speechDoneCallback
                                if (cb != null) {
                                    speechDoneCallback = null
                                    cb.invoke()
                                } else {
                                    speechAlreadyDone = true
                                }
                            }
                        }
                    }
                })
                isReady.set(true)
                // Flush any texts queued before init completed
                synchronized(pendingTexts) {
                    pendingTexts.forEach { speakNow(it) }
                    pendingTexts.clear()
                }
            } else {
                LogManager.logE(TAG, "[AGENT_TTS] TTS initialization failed, status=$status")
            }
        }
    }

    /**
     * Speak the given text. Fire-and-forget, non-blocking.
     * Safe to call before TTS is ready; text will be queued and spoken after init.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (isReady.get()) {
            speakNow(text)
        } else {
            synchronized(pendingTexts) {
                pendingTexts.add(text)
            }
            LogManager.logD(TAG, "[AGENT_TTS] Queued (not ready yet): ${text.take(50)}")
        }
    }

    private fun speakNow(text: String) {
        val uid = "agent_${System.currentTimeMillis()}"
        synchronized(speechLock) {
            lastUtteranceId = uid
            speechAlreadyDone = false
            speechDoneCallback = null
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, uid)
        LogManager.logI(TAG, "[AGENT_TTS] Speaking: ${text.take(200)}")
    }

    /**
     * Suspend until the current speech queue is fully done (or TTS is shut down).
     * Thread-safe: uses speechLock to atomically check speechAlreadyDone and register
     * the callback, preventing the race where onDone fires before we register.
     */
    suspend fun awaitSpeechDone() {
        if (!isReady.get() || lastUtteranceId == null) return
        // Check under lock: if already done before we get here, return immediately
        var alreadyDone = false
        synchronized(speechLock) {
            alreadyDone = speechAlreadyDone
        }
        if (alreadyDone) return
        suspendCancellableCoroutine<Unit> { cont ->
            synchronized(speechLock) {
                if (speechAlreadyDone) {
                    // Done between the check above and now - resume immediately
                    if (cont.isActive) cont.resume(Unit)
                } else {
                    speechDoneCallback = { if (cont.isActive) cont.resume(Unit) }
                }
            }
            cont.invokeOnCancellation {
                synchronized(speechLock) { speechDoneCallback = null }
            }
        }
    }

    /**
     * Stop current speech and release TTS resources. Call when agent task ends.
     */
    fun shutdown() {
        // Unblock any pending awaitSpeechDone() before shutting down
        synchronized(speechLock) {
            val cb = speechDoneCallback
            speechDoneCallback = null
            speechAlreadyDone = true
            cb?.invoke()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady.set(false)
        synchronized(pendingTexts) { pendingTexts.clear() }
        LogManager.logI(TAG, "[AGENT_TTS] Shutdown")
    }
}
