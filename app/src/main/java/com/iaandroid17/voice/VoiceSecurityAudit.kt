package com.iaandroid17.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded on-device audit. It never records microphone audio or command contents. */
object VoiceSecurityAudit {
    private const val PREFS = "ia_voice_security_audit"
    private const val KEY = "events"
    private const val MAX_EVENTS = 80

    @Synchronized
    fun record(context: Context, event: String, detail: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$timestamp | ${event.take(48)} | ${detail.take(120).replace('|', '/')}"
        val current = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit().putString(KEY, (current + line).takeLast(MAX_EVENTS).joinToString("\n")).apply()
    }

    fun snapshot(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(MAX_EVENTS)
}
