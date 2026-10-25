package com.iaandroid17.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max

/** Enrollment sanity checks and attempt budget. This is not voice biometrics or spoof detection. */
object VoiceVerificationReadiness {
    data class Quality(val accepted: Boolean, val durationMs: Long, val bytes: Long, val estimatedBytesPerSecond: Long, val reason: String)

    fun inspectRecording(file: File): Quality {
        val size = file.length()
        if (!file.exists() || size < 4096L) return Quality(false, 0, size, 0, "Запись пуста или слишком короткая.")
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { retriever.release() } }
        if (duration !in 1800L..12000L) return Quality(false, duration, size, 0, "Длительность должна быть от 2 до 12 секунд.")
        val rate = size * 1000L / max(1L, duration)
        if (rate < 800L) return Quality(false, duration, size, rate, "Слишком мало аудиоданных. Повторите запись в тихом месте.")
        return Quality(true, duration, size, rate, "Базовая проверка контейнера пройдена. Это не проверка чистоты сигнала и не доказательство личности.")
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