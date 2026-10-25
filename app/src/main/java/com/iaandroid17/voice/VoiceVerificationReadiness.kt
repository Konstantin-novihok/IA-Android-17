package com.iaandroid17.voice

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Recording-quality/attempt controls. Signal checks are not speaker identity or anti-spoof proof. */
object VoiceVerificationReadiness {
    data class Quality(val accepted: Boolean, val durationMs: Long, val bytes: Long, val estimatedBytesPerSecond: Long, val reason: String)

    /** Inspects controlled raw PCM16 mono 16 kHz samples produced by PcmAudioCapture. */
    fun inspectRecording(file: File): Quality {
        val size = file.length()
        if (!file.exists() || size < 64_000L) return Quality(false, 0, size, 0, "Запись короче двух секунд или отсутствует.")
        if (size % 2L != 0L) return Quality(false, 0, size, 0, "Неверный размер PCM-файла.")
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return Quality(false, 0, size, 0, "Не удалось прочитать PCM.")
        }
        val shorts = ShortArray(bytes.size / 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buf.short
        val duration = shorts.size * 1000L / 16_000L
        val report = AudioSignalQuality.inspectPcm16(shorts)
        if (duration !in 2000L..12_000L) return Quality(false, duration, size, size * 1000L / max(1L, duration), "Длительность должна быть от 2 до 12 секунд.")
        val rate = size * 1000L / max(1L, duration)
        if (!report.accepted) return Quality(false, duration, size, rate, report.reason)
        return Quality(true, duration, size, rate, "PCM 16 кГц проверен: RMS %.4f, peak %.3f, clipping %.3f%%. Это проверка качества уровня, не идентификация человека и не anti-spoof.".format(report.rms, report.peak, report.clippedFraction * 100))
    }

    /** Sliding-window denial budget. */
    fun allowAttempt(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = context.getSharedPreferences("ia_voice_attempt_guard", Context.MODE_PRIVATE)
        val until = p.getLong("blocked_until", 0L)
        if (now < until) return false
        if (until != 0L) p.edit().remove("blocked_until").remove("failures").remove("window_start").apply()
        return true
    }

    fun recordDenial(context: Context, reason: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = context.getSharedPreferences("ia_voice_attempt_guard", Context.MODE_PRIVATE)
        if (!allowAttempt(context, now)) {
            VoiceSecurityAudit.record(context, "attempt_blocked", "guard active")
            return false
        }
        val oldStart = p.getLong("window_start", 0L)
        val prior = if (oldStart != 0L && now - oldStart <= WINDOW_MS) p.getInt("failures", 0) else 0
        val start = if (prior == 0) now else oldStart
        val count = prior + 1
        val editor = p.edit().putLong("window_start", start).putInt("failures", count)
        if (count >= MAX_FAILURES) editor.putLong("blocked_until", now + COOLDOWN_MS)
        editor.apply()
        VoiceSecurityAudit.record(context, "verification_denied", "$reason; attempt=$count")
        return count < MAX_FAILURES
    }

    fun status(context: Context, now: Long = System.currentTimeMillis()): String {
        val p = context.getSharedPreferences("ia_voice_attempt_guard", Context.MODE_PRIVATE)
        val until = p.getLong("blocked_until", 0L)
        if (until > now) return "Защита активна: осталось ${(until-now+999)/1000} сек."
        val window = p.getLong("window_start", now)
        val n = if (now-window <= WINDOW_MS) p.getInt("failures", 0) else 0
        return "Отказы: $n/$MAX_FAILURES; блокировка после $MAX_FAILURES неудач."
    }

    private const val MAX_FAILURES = 5
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val COOLDOWN_MS = 10 * 60 * 1000L
}
