// Copyright (c)  2023  Xiaomi Corporation
// Sherpa-MNN Kotlin API - Unified inference file
// All ASR-related classes consolidated for easier JNI integration
package com.k2fsa.sherpa.mnn

import android.content.res.AssetManager

// ============================================================================
// Feature Extraction Configuration
// ============================================================================

data class FeatureConfig(
    @JvmField var sampleRate: Int = 16000,
    @JvmField var featureDim: Int = 80,
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}

// ============================================================================
// Endpoint Detection Configuration
// ============================================================================

data class EndpointRule(
    @JvmField var mustContainNonSilence: Boolean,
    @JvmField var minTrailingSilence: Float,
    @JvmField var minUtteranceLength: Float,
)

data class EndpointConfig(
    @JvmField var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    @JvmField var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    @JvmField var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f)
)

fun getEndpointConfig(): EndpointConfig {
    return EndpointConfig(
        rule1 = EndpointRule(false, 2.4f, 0.0f),
        rule2 = EndpointRule(true, 1.4f, 0.0f),
        rule3 = EndpointRule(false, 0.0f, 20.0f)
    )
}

// ============================================================================
// Model Configuration
// ============================================================================

data class OnlineTransducerModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
    @JvmField var joiner: String = "",
)

data class OnlineParaformerModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
)

data class OnlineZipformer2CtcModelConfig(
    @JvmField var model: String = "",
)

data class OnlineNeMoCtcModelConfig(
    @JvmField var model: String = "",
)

data class OnlineModelConfig(
    @JvmField var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    @JvmField var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    @JvmField var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    @JvmField var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    @JvmField var tokens: String = "",
    @JvmField var numThreads: Int = 1,
    @JvmField var debug: Boolean = false,
    @JvmField var provider: String = "cpu",
    @JvmField var modelType: String = "",
    @JvmField var modelingUnit: String = "",
    @JvmField var bpeVocab: String = "",
)

data class OnlineLMConfig(
    @JvmField var model: String = "",
    @JvmField var scale: Float = 0.5f,
)

data class OnlineCtcFstDecoderConfig(
    @JvmField var graph: String = "",
    @JvmField var maxActive: Int = 3000,
)

// ============================================================================
// Online Recognizer Configuration
// ============================================================================

data class OnlineRecognizerConfig(
    @JvmField var featConfig: FeatureConfig = FeatureConfig(),
    @JvmField var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    @JvmField var lmConfig: OnlineLMConfig = OnlineLMConfig(),
    @JvmField var ctcFstDecoderConfig: OnlineCtcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
    @JvmField var endpointConfig: EndpointConfig = EndpointConfig(),
    @JvmField var enableEndpoint: Boolean = true,
    @JvmField var decodingMethod: String = "greedy_search",
    @JvmField var maxActivePaths: Int = 4,
    @JvmField var hotwordsFile: String = "",
    @JvmField var hotwordsScore: Float = 1.5f,
    @JvmField var ruleFsts: String = "",
    @JvmField var ruleFars: String = "",
    @JvmField var blankPenalty: Float = 0.0f,
)

data class OnlineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
)

// ============================================================================
// Online Recognizer
// ============================================================================

class OnlineRecognizer(
    assetManager: AssetManager? = null,
    val config: OnlineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        
        // CRITICAL: Check if model loading failed (JNI returns 0 on validation error)
        if (ptr == 0L) {
            throw IllegalStateException(
                "Failed to create OnlineRecognizer. " +
                "Check config validation errors in logcat (search for 'Errors found in config')"
            )
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(hotwords: String = ""): OnlineStream {
        val p = createStream(ptr, hotwords)
        return OnlineStream(p)
    }

    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun isEndpoint(stream: OnlineStream) = isEndpoint(ptr, stream.ptr)
    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)
    fun getResult(stream: OnlineStream): OnlineRecognizerResult {
        val objArray = getResult(ptr, stream.ptr)

        val text = objArray[0] as String
        val tokens = objArray[1] as Array<String>
        val timestamps = objArray[2] as FloatArray

        return OnlineRecognizerResult(text = text, tokens = tokens, timestamps = timestamps)
    }

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OnlineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OnlineRecognizerConfig,
    ): Long

    private external fun createStream(ptr: Long, hotwords: String): Long
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun isEndpoint(ptr: Long, streamPtr: Long): Boolean
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

// ============================================================================
// Online Stream
// ============================================================================

class OnlineStream(var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        inputFinished(ptr)
    }

    protected fun finalize() {
        // Only delete if ptr is valid (not already released)
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() {
        // Only delete if ptr is valid (prevent double-free)
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    private external fun delete(ptr: Long)
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
}

// ============================================================================
// Wave Reader
// ============================================================================

data class WaveData(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WaveData

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

class WaveReader {
    companion object {

        fun readWave(
            assetManager: AssetManager,
            filename: String,
        ): WaveData {
            return readWaveFromAsset(assetManager, filename).let {
                WaveData(it[0] as FloatArray, it[1] as Int)
            }
        }

        fun readWave(
            filename: String,
        ): WaveData {
            return readWaveFromFile(filename).let {
                WaveData(it[0] as FloatArray, it[1] as Int)
            }
        }

        // Read a mono wave file asset
        // The returned array has two entries:
        //  - the first entry contains an 1-D float array
        //  - the second entry is the sample rate
        external fun readWaveFromAsset(
            assetManager: AssetManager,
            filename: String,
        ): Array<Any>

        // Read a mono wave file from disk
        // The returned array has two entries:
        //  - the first entry contains an 1-D float array
        //  - the second entry is the sample rate
        external fun readWaveFromFile(
            filename: String,
        ): Array<Any>

        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}
