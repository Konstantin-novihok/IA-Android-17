package com.iaandroid17.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative acoustic replay/spoof risk screening for raw PCM16 mono, 16 kHz recordings.
 *
 * This is NOT a trained presentation-attack detector. Hand-crafted signal statistics cannot
 * reliably distinguish a live voice from high-quality replay, TTS, or voice conversion.
 * A suspicious result is a reason to reject/retry; a clear result is never proof of liveness.
 */
object AntiSpoofingAnalyzer {
    data class Report(
        val suspicious: Boolean,
        val riskScore: Float,
        val reasons: List<String>,
        val durationMs: Long,
        val rms: Float,
        val clippingFraction: Float,
        val repeatedFrameFraction: Float,
        val spectralFlatnessMean: Float
    ) {
        val summary: String get() = buildString {
            append(if (suspicious) "ПОДОЗРИТЕЛЬНЫЕ ПРИЗНАКИ" else "Явных простых артефактов не обнаружено")
            append(" · риск-эвристика ")
            append("%.2f".format(java.util.Locale.US, riskScore))
            if (reasons.isNotEmpty()) append(" · ").append(reasons.joinToString("; "))
            append(". Это не доказывает живое присутствие.")
        }
    }

    fun inspect(pcm16: ByteArray): Report {
        require(pcm16.size >= 64_000 && pcm16.size <= 384_000 && pcm16.size % 2 == 0) {
            "Anti-spoof expects 2–12 seconds of raw PCM16 mono at 16 kHz"
        }
        val b = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val x = ShortArray(pcm16.size / 2) { b.short.toInt() }
        val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size) / 32768.0
        val clipped = x.count { abs(it.toInt()) >= 32760 }.toFloat() / x.size
        val frame = 320 // 20 ms at 16 kHz
        val count = x.size / frame
        val energies = DoubleArray(count)
        val fingerprints = LongArray(count)
        for (f in 0 until count) {
            val start = f * frame
            var sum = 0.0
            var hash = 1125899906842597L
            for (i in 0 until frame) {
                val v = x[start + i].toInt()
                sum += v.toDouble() * v
                // Quantized sign/large-change pattern; deliberately a crude repetition indicator.
                val q = when { v < -256 -> 0; v > 256 -> 2; else -> 1 }
                hash = hash * 31 + q
            }
            energies[f] = sqrt(sum / frame)
            fingerprints[f] = hash
        }
        var repeats = 0
        var comparisons = 0
        for (i in 1 until count) {
            if (energies[i] > 150.0 && energies[i - 1] > 150.0) {
                comparisons++
                if (fingerprints[i] == fingerprints[i - 1]) repeats++
            }
        }
        val repeatFraction = if (comparisons == 0) 0f else repeats.toFloat() / comparisons
        val flatness = spectralFlatness(x, frame)
        val reasons = mutableListOf<String>()
        var risk = 0f
        if (rms < 0.008) { risk += 0.25f; reasons += "слишком низкий общий уровень" }
        if (clipped > 0.015f) { risk += 0.30f; reasons += "сильное ограничение амплитуды" }
        if (repeatFraction > 0.12f) { risk += 0.35f; reasons += "повторяющиеся соседние фреймы" }
        if (flatness < 0.015f || flatness > 0.92f) {
            risk += 0.20f
            reasons += "необычная спектральная структура"
        }
        return Report(
            suspicious = risk >= 0.30f,
            riskScore = risk.coerceIn(0f, 1f),
            reasons = reasons,
            durationMs = x.size * 1000L / 16_000L,
            rms = rms.toFloat(),
            clippingFraction = clipped,
            repeatedFrameFraction = repeatFraction,
            spectralFlatnessMean = flatness
        )
    }

    private fun spectralFlatness(samples: ShortArray, frameSize: Int): Float {
        val hop = frameSize
        var total = 0.0
        var frames = 0
        val nBins = 64
        var start = 0
        while (start + frameSize <= samples.size) {
            val power = DoubleArray(nBins)
            for (k in 0 until nBins) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until frameSize) {
                    val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (frameSize - 1))
                    val angle = 2.0 * Math.PI * k * i / frameSize
                    val value = samples[start + i].toDouble() * window
                    re += value * kotlin.math.cos(angle)
                    im -= value * kotlin.math.sin(angle)
                }
                power[k] = re * re + im * im + 1e-12
            }
            val geometric = kotlin.math.exp(power.map { kotlin.math.ln(it) }.average())
            val arithmetic = power.average()
            total += geometric / max(arithmetic, 1e-12)
            frames++
            start += hop
        }
        return if (frames == 0) 0f else (total / frames).toFloat().coerceIn(0f, 1f)
    }
}
