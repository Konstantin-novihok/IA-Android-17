package com.iaandroid17.voice

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * On-device AASIST PAD inference.
 * Model input is raw mono float32 at 16 kHz, 64,600 samples (~4.04 s); higher bona-fide logit means live.
 * This score is not a calibrated probability and must not be treated as proof of liveness.
 */
class AntiSpoofingEngine(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    data class Result(
        val bonaFideScore: Float,
        val spoofScore: Float,
        val suspicious: Boolean,
        val note: String
    )

    init {
        check(AntiSpoofModelInstaller.isInstalled(context)) {
            "AASIST model is missing or SHA-256 validation failed."
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        session = env.createSession(AntiSpoofModelInstaller.modelFile(context).absolutePath, options)
        check(session.inputNames.size == 1) { "Unexpected AASIST input count" }
        check(session.outputNames.isNotEmpty()) { "Unexpected AASIST outputs" }
    }

    fun score(pcm16: ByteArray): Result {
        require(pcm16.size >= 64_000 && pcm16.size % 2 == 0) { "AASIST needs at least 2 seconds of PCM16 mono 16 kHz audio." }
        val samplesTotal = pcm16.size / 2
        val values = FloatArray(WINDOW_SAMPLES) // zero-pad short clips; center-crop long clips
        val sourceStart = maxOf(0, (samplesTotal - WINDOW_SAMPLES) / 2)
        val copied = minOf(samplesTotal, WINDOW_SAMPLES)
        val targetStart = maxOf(0, (WINDOW_SAMPLES - samplesTotal) / 2)
        for (i in 0 until copied) {
            val offset = (sourceStart + i) * 2
            val sample = (pcm16[offset].toInt() and 0xff) or (pcm16[offset + 1].toInt() shl 8)
            values[targetStart + i] = sample.toShort().toInt() / 32768.0f
        }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, WINDOW_SAMPLES.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val raw = result.get(session.outputNames.first()).orElseThrow().value
                val logits = when (raw) {
                    is Array<*> -> {
                        val first = raw.firstOrNull()
                        when (first) {
                            is FloatArray -> first
                            is Array<*> -> (first.firstOrNull() as? FloatArray)
                            else -> null
                        }
                    }
                    is FloatArray -> raw
                    else -> null
                } ?: error("Unsupported AASIST output tensor: ${raw?.javaClass?.name}")
                require(logits.size >= 2 && logits.all { it.isFinite() }) {
                    "AASIST must return two finite class logits (spoof, bona-fide)."
                }
                val maxLogit = logits.take(2).maxOrNull()!!
                val a = exp((logits[0] - maxLogit).toDouble())
                val b = exp((logits[1] - maxLogit).toDouble())
                val sum = a + b
                val spoof = (a / sum).toFloat()
                val bona = (b / sum).toFloat()
                // Conservative default operating point. It is not calibrated for this phone/mic/locale.
                val suspicious = bona < DEFAULT_BONAFIDE_THRESHOLD
                return Result(
                    bonaFideScore = bona,
                    spoofScore = spoof,
                    suspicious = suspicious,
                    note = if (suspicious) "AASIST classifies this recording as spoof-risk" else "AASIST did not flag this recording; this is not proof of liveness"
                )
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val WINDOW_SAMPLES = 64_600
        const val DEFAULT_BONAFIDE_THRESHOLD = 0.5f
    }
}
