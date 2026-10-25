package com.iaandroid17.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Lightweight PCM diagnostics; these are recording-quality indicators, never identity/liveness. */
object AudioSignalQuality {
    data class Report(
        val accepted: Boolean,
        val sampleCount: Int,
        val rms: Float,
        val peak: Float,
        val clippedFraction: Float,
        val zeroCrossingRate: Float,
        val reason: String
    )

    /** Signed 16-bit mono PCM, ideally captured at 16 kHz. */
    fun inspectPcm16(samples: ShortArray, sampleRate: Int = 16_000): Report {
        if (sampleRate != 16_000) return Report(false, samples.size, 0f, 0f, 0f, 0f, "Ожидается PCM 16 кГц.")
        if (samples.size < sampleRate * 2) return Report(false, samples.size, 0f, 0f, 0f, 0f, "Нужно не менее двух секунд сигнала.")
        var squares = 0.0
        var peak = 0f
        var clips = 0
        var crossings = 0
        var prev = samples[0].toInt()
        samples.forEachIndexed { i, raw ->
            val value = raw.toInt() / 32768f
            squares += value.toDouble() * value
            peak = maxOf(peak, abs(value))
            if (raw.toInt() >= 32760 || raw.toInt() <= -32760) clips++
            if (i > 0 && ((raw.toInt() < 0) != (prev < 0))) crossings++
            prev = raw.toInt()
        }
        val rms = sqrt(squares / samples.size).toFloat()
        val clipped = clips.toFloat() / samples.size
        val zcr = crossings.toFloat() / (samples.size - 1)
        val fail = when {
            rms < 0.008f -> "Сигнал слишком тихий или тишина."
            clipped > 0.01f -> "Слишком много клиппинга."
            peak < 0.025f -> "Недостаточный уровень сигнала."
            else -> ""
        }
        return Report(fail.isEmpty(), samples.size, rms, peak, clipped, zcr, if (fail.isEmpty()) "Базовые PCM-проверки пройдены; шум/реверберация и личность не оценивались." else fail)
    }
}
