package com.iaandroid17.voice

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Real local speaker embedding extraction using the pinned CAM++ ONNX model.
 * Input: raw PCM16 mono 16 kHz; output: L2-normalized 512-D vector.
 * Embeddings are voice features, not proof of identity or liveness.
 */
class SpeakerEmbeddingEngine(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val fbank = SpeakerFbank()

    init {
        val file = SpeakerEmbeddingModelInstaller.modelFile(context)
        check(SpeakerEmbeddingModelInstaller.isInstalled(context)) {
            "Speaker model is missing or failed SHA-256 validation. Install it from Voice ID settings."
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        session = env.createSession(file.absolutePath, options)
        check(session.inputNames.contains("x")) { "Unexpected CAM++ input tensor; model rejected" }
        check(session.outputNames.contains("embedding")) { "Unexpected CAM++ output tensor; model rejected" }
    }

    fun embed(pcm16: ByteArray): FloatArray {
        require(pcm16.size in 64_000..384_000 && pcm16.size % 2 == 0) {
            "Expected 2–12 seconds of PCM16 mono at 16 kHz"
        }
        val samples = FloatArray(pcm16.size / 2) { i ->
            val lo = pcm16[i * 2].toInt() and 0xff
            val hi = pcm16[i * 2 + 1].toInt()
            ((hi shl 8) or lo) / 32768f
        }
        val frames = fbank.compute(samples)
        require(frames.size >= 20 && frames.all { it.size == 80 }) { "Invalid filterbank feature tensor" }
        val mean = FloatArray(80)
        for (frame in frames) for (i in 0 until 80) mean[i] += frame[i]
        for (i in mean.indices) mean[i] /= frames.size
        val flat = FloatArray(frames.size * 80)
        for (t in frames.indices) for (i in 0 until 80) flat[t * 80 + i] = frames[t][i] - mean[i]

        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, frames.size.toLong(), 80L)).use { input ->
            session.run(mapOf("x" to input)).use { result ->
                val value = result.get("embedding").orElseThrow().value
                @Suppress("UNCHECKED_CAST")
                val vector = (value as Array<FloatArray>).firstOrNull()
                    ?: error("Model returned an empty embedding")
                require(vector.size == EMBEDDING_DIM && vector.all { it.isFinite() }) {
                    "Model output is not a valid 512-dimensional embedding"
                }
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                require(norm.isFinite() && norm > 1e-8) { "Model returned a zero-norm embedding" }
                return FloatArray(vector.size) { vector[it] / norm.toFloat() }
            }
        }
    }

    override fun close() {
        session.close()
        // OrtEnvironment is process-wide; do not close the singleton from a short-lived engine.
    }

    companion object { const val EMBEDDING_DIM = 512 }
}
