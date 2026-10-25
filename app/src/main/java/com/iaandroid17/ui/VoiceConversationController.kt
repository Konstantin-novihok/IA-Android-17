package com.iaandroid17.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Foreground, screen-scoped voice conversation.
 * ASR explicitly uses Android's on-device recognizer; it never falls back to a network recognizer.
 * LLM generation stays in the existing local llama.cpp engine. TTS uses the installed Android
 * speech engine; offline availability depends on the installed voice data.
 */
class VoiceConversationController(
    context: Context,
    private val vm: AppViewModel,
    private val onStatus: (String) -> Unit
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var active = false
    private var ttsReady = false
    private var waitingForReply = false
    private var retryPending = false

    fun isActive(): Boolean = active

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            onStatus("На устройстве нет доступного офлайн-распознавания речи. Голосовой режим не запущен.")
            return
        }
        active = true
        onStatus("Голосовой диалог включён. Говорите после сигнала готовности.")
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { if (active) onStatus("Слушаю…") }
                    override fun onBeginningOfSpeech() { if (active) onStatus("Распознаю речь…") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (active) onStatus("Обрабатываю запрос локальной моделью…") }
                    override fun onError(error: Int) {
                        if (!active) return
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            onStatus("Нет разрешения на микрофон.")
                            stop()
                        } else {
                            onStatus(errorMessage(error))
                            scheduleListen(if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 500 else 1200)
                        }
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        if (!active || waitingForReply) return
                        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (heard.isBlank()) {
                            scheduleListen(400)
                            return
                        }
                        waitingForReply = true
                        onStatus("Вы: $heard")
                        vm.send(heard) { answer, failed ->
                            waitingForReply = false
                            if (!active) return@send
                            if (failed || answer.isBlank()) {
                                onStatus("Не удалось получить ответ локальной модели. Повторите команду.")
                                scheduleListen(700)
                            } else {
                                speak(answer)
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
        }
        listen()
    }

    fun stop() {
        active = false
        waitingForReply = false
        retryPending = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { tts?.stop() }
        onStatus("Голосовой диалог остановлен.")
    }

    private fun listen() {
        if (!active || waitingForReply) return
        if (vm.generating) {
            scheduleListen(500)
            return
        }
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            r.startListening(intent)
        } catch (_: SecurityException) {
            onStatus("Нет разрешения на микрофон.")
            stop()
        } catch (_: Exception) {
            scheduleListen(900)
        }
    }

    private fun speak(text: String) {
        if (!active) return
        val engine = tts
        if (!ttsReady || engine == null) {
            onStatus("Синтез речи ещё не готов. Ответ сохранён в чате.")
            scheduleListen(700)
            return
        }
        val locale = Locale.getDefault()
        if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            onStatus("Для ${locale.toLanguageTag()} нет установленного голоса TTS. Установите офлайн-голос в Android.")
            scheduleListen(900)
            return
        }
        engine.language = locale
        onStatus("Отвечаю голосом…")
        val result = engine.speak(text.take(6000), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            onStatus("Ошибка синтеза речи. Ответ доступен в чате.")
            scheduleListen(900)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (active && ttsReady && !waitingForReply) scheduleListen(200)
        if (active && !ttsReady) onStatus("Не удалось запустить TTS. Проверьте движок синтеза речи Android.")
    }

    private fun scheduleListen(delayMs: Long) {
        if (!active || retryPending) return
        retryPending = true
        main.postDelayed({
            retryPending = false
            listen()
        }, delayMs)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана. Повторите."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не услышана. Слушаю снова…"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Офлайн-распознавание вернуло сетевую ошибку; проверьте локальные языковые данные."
        else -> "Ошибка распознавания ($error). Повторяю прослушивание…"
    }

    fun close() {
        stop()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val UTTERANCE_ID = "ia-local-voice-reply"
    }

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) main.post {
                    if (active) {
                        onStatus("Ответ завершён. Можно говорить дальше.")
                        scheduleListen(250)
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) main.post {
                    if (active) {
                        onStatus("Ошибка озвучивания. Продолжаю слушать.")
                        scheduleListen(500)
                    }
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
    }
}
