package com.iaandroid17.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.iaandroid17.settings.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicitly user-started microphone foreground service. Recognized input is never evaluated as code. */
class VoiceScenarioService : Service(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var restarting = false
    private var lastHandledAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.getDefault()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningAndExit()
            return START_NOT_STICKY
        }
        // Fail closed: encrypted enrollment recordings are not speaker embeddings.
        // The manual speaker-match check exists, but no validated threshold or anti-spoof verifier is available for command authorization.
        VoiceVerificationReadiness.recordDenial(this, "verifier missing; start refused")
        startForeground(NOTIFICATION_ID, buildNotification())
        speak("Голосовые команды заблокированы. Ручное сравнение голоса уже доступно в настройках, но калибровка и защита от записи не подтверждены. Доступен только чат.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("На устройстве нет доступной службы распознавания речи")
            stopSelf()
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                    r.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { listening = false }
                        override fun onError(error: Int) {
                            listening = false
                            if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) scheduleRestart()
                        }
                        override fun onResults(results: android.os.Bundle?) {
                            listening = false
                            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            handlePhrase(heard)
                            scheduleRestart()
                        }
                        override fun onPartialResults(partialResults: android.os.Bundle?) {
                            val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            handlePhrase(heard)
                        }
                        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    })
                }
            }
            if (!listening) {
                val req = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer?.startListening(req)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recognition", e)
            scheduleRestart()
        }
    }

    private fun handlePhrase(raw: String) {
        val now = System.currentTimeMillis()
        if (raw.isBlank() || now - lastHandledAt < 6500) return
        if (!VoiceVerificationReadiness.allowAttempt(this)) {
            VoiceSecurityAudit.record(this, "attempt_blocked", "rate limit")
            return
        }
        val s = SettingsStore(this).load()
        val normalized = raw.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val alias = s.assistantName.lowercase(Locale.ROOT).trim()
        if (alias.length < 2 || !normalized.startsWith(alias)) return
        val tail = normalized.removePrefix(alias).trim()
        val number = when {
            Regex("^(код )?(один|1)(\\b.*)?$").matches(tail) -> 1
            Regex("^(код )?(два|2)(\\b.*)?$").matches(tail) -> 2
            Regex("^(код )?(три|3)(\\b.*)?$").matches(tail) -> 3
            else -> return
        }
        lastHandledAt = now
        // A real identity verdict is unavailable: fail closed and account for the denial.
        VoiceVerificationReadiness.recordDenial(this, "owner voice not verified")
        speak("Сценарий отменён. Проверка голоса владельца пока не подключена.")
        return
    }

    /** Safe starter scenarios, independent from LLM output: time, date, service self-test. */
    private fun runScenario(number: Int, pulseMs: Int) {
        vibrateCode(number, pulseMs)
        val message = when (number) {
            1 -> "Сценарий один. Время " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            2 -> "Сценарий два. Дата " + SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
            3 -> "Сценарий три. Проверка голосового управления выполнена"
            else -> return
        }
        speak(message)
    }

    private fun vibrateCode(count: Int, durationMs: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        val ms = durationMs.coerceIn(40, 400).toLong()
        val pattern = LongArray(count * 2 - 1) { i -> if (i % 2 == 0) ms else 140L }
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ia-voice-status")
    }

    private fun scheduleRestart() {
        if (restarting) return
        restarting = true
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (recognizer != null && !listening) beginRecognition()
        }, 900)
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, VoiceScenarioService::class.java).setAction(ACTION_STOP)
        val pending = android.app.PendingIntent.getService(this, 51, stop,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("IA — голосовые сценарии")
                .setContentText("Ожидание обращения по имени помощника")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Остановить", pending).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("IA — голосовые сценарии")
                .setContentText("Ожидание обращения по имени помощника")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", pending)
                .build()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Голосовые сценарии", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun stopListeningAndExit() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.iaandroid17.voice.START"
        const val ACTION_STOP = "com.iaandroid17.voice.STOP"
        private const val TAG = "IAVoiceService"
        private const val CHANNEL_ID = "ia_voice_scenarios"
        private const val NOTIFICATION_ID = 712
    }
}
