package com.iaandroid17.voice

import kotlin.math.sqrt

/**
 * Pure vector operations for evaluation pipelines.
 * Scores are experimental metrics, not identity proofs. This utility is deliberately not
 * connected to the command authorization path.
 */
object SpeakerEmbeddingMath {
    data class Trial(val score: Float, val sameSpeaker: Boolean)
    data class Calibration(val threshold: Float, val far: Float, val frr: Float, val eerEstimate: Float, val trials: Int)

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.isNotEmpty() && a.size == b.size) { "Embedding dimensions must match and be non-empty" }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            require(a[i].isFinite() && b[i].isFinite()) { "Embedding contains invalid values" }
            dot += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        require(aa > 1e-16 && bb > 1e-16) { "Embedding norm is zero" }
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat().coerceIn(-1f, 1f)
    }

    fun normalizedMean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty())
        val dim = vectors.first().size
        require(dim > 0 && vectors.all { it.size == dim })
        val avg = FloatArray(dim)
        vectors.forEach { v -> v.indices.forEach { i ->
            require(v[i].isFinite())
            avg[i] += v[i] / vectors.size
        } }
        val norm = sqrt(avg.sumOf { it.toDouble() * it }.coerceAtLeast(0.0))
        require(norm > 1e-8)
        return FloatArray(dim) { (avg[it] / norm).toFloat() }
    }

    /**
     * Evaluate candidate thresholds on an independent labeled set.
     * This produces a research report only. It must never be used to auto-enable voice actions.
     */
    fun calibrate(trials: List<Trial>): Calibration {
        val genuine = trials.filter { it.sameSpeaker }
        val impostor = trials.filterNot { it.sameSpeaker }
        require(genuine.isNotEmpty() && impostor.isNotEmpty()) { "Need both genuine and impostor trials" }
        val thresholds = (trials.map { it.score } + listOf(-1f, 1f)).distinct().sorted()
        var best: Calibration? = null
        thresholds.forEach { t ->
            val far = impostor.count { it.score >= t }.toFloat() / impostor.size
            val frr = genuine.count { it.score < t }.toFloat() / genuine.size
            val result = Calibration(t, far, frr, (far + frr) / 2f, trials.size)
            if (best == null || result.eerEstimate < best!!.eerEstimate) best = result
        }
        return best!!
    }
}
