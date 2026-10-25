package com.iaandroid17.ui

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iaandroid17.chat.ChatFormat
import com.iaandroid17.chat.Role
import com.iaandroid17.engine.EngineState

@Composable
fun ChatScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    var ttsReady by remember { mutableStateOf(false) }
    var autoSpeak by rememberSaveable { mutableStateOf(false) }
    var lastAutoSpokenId by rememberSaveable { mutableStateOf(-1L) }
    val tts = remember { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
    val latestMessage = vm.messages.lastOrNull()
    LaunchedEffect(latestMessage?.id, latestMessage?.streaming, ttsReady, autoSpeak) {
        val latest = latestMessage
        if (autoSpeak && ttsReady && latest != null && latest.role == Role.ASSISTANT && !latest.streaming && !latest.error && latest.id != lastAutoSpokenId) {
            val answer = ChatFormat.parse(latest.raw).answer
            val locale = Locale.getDefault()
            if (answer.isNotBlank() && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
                tts.speak(answer.take(6000), TextToSpeech.QUEUE_FLUSH, null, "ia-auto-speak")
            }
            lastAutoSpokenId = latest.id
        }
    }
    val speechInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = listOf(input, spoken).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addDocuments(uris)
    }
    val activeDocs = vm.docList.count { it.enabled }

    Column(Modifier.fillMaxSize().imePadding()) {
        EngineStatusCard(vm, onOpenSettings)
        TextButton(onClick = { autoSpeak = !autoSpeak; if (!autoSpeak) lastAutoSpokenId = vm.messages.lastOrNull()?.id ?: -1L }) { Text(if (autoSpeak) "Автоозвучивание: включено" else "Автоозвучивание: выключено") }

        if (vm.banner.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.banner, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.banner = "" }) { Text("OK") }
                }
            }
        }
        if (vm.busyMessage.isNotBlank()) {
            Text(vm.busyMessage, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }

        // Список в обратном порядке: новое сообщение всегда внизу и экран «прилипает» к концу при потоковом выводе.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.messages.asReversed(), key = { it.id }) { m -> MessageBubble(m, onSpeak = { text -> if (ttsReady) { val locale = Locale.getDefault(); if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) { tts.language = locale; tts.speak(text.take(6000), TextToSpeech.QUEUE_FLUSH, null, "ia-chat") } else vm.banner = "TTS не поддерживает язык " + locale.toLanguageTag() + "; установите голосовые данные в настройках Android." } else vm.banner = "Синтез речи недоступен: проверьте TTS-движок Android." }) }
            if (vm.messages.isEmpty()) {
                item(key = "hint") { EmptyHint() }
            }
        }

        if (activeDocs > 0 && vm.settings.useDocuments) {
            Text(
                "📄 Подключено документов: $activeDocs",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📎") }
            TextButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                runCatching { speechInput.launch(intent) }.onFailure { vm.banner = "Распознавание недоступно: " + (it.message ?: "ошибка") }
            }) { Text("🎙") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                maxLines = 5
            )
            Spacer(Modifier.width(6.dp))
            if (vm.generating) {
                Button(onClick = { vm.stop() }) { Text("■") }
            } else {
                Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("➤") }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Локальный помощник", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Напишите вопрос. Первый ответ придёт чуть позже — модель загружается в память. " +
                "Прикрепите документы кнопкой 📎, и помощник будет отвечать с их учётом.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EngineStatusCard(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val st = vm.engineState
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when (st) {
                    is EngineState.Ready -> {
                        Text("● ${st.modelName}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text("Контекст ${st.nCtx} токенов · потоков ${st.threads}", style = MaterialTheme.typography.labelSmall)
                    }
                    is EngineState.Loading -> {
                        Text("Загрузка модели в память…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    is EngineState.Error -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is EngineState.Unloaded -> Text(
                        if (vm.modelFiles.isEmpty()) "Модель не установлена" else "Модель не загружена в память",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            when {
                st is EngineState.Ready -> TextButton(onClick = { vm.unloadModel() }, enabled = !vm.generating) { Text("Выгрузить") }
                st is EngineState.Loading -> {}
                vm.modelFiles.isEmpty() -> TextButton(onClick = onOpenSettings) { Text("Установить") }
                else -> TextButton(onClick = { vm.loadModel() }) { Text("Загрузить") }
            }
            TextButton(onClick = { vm.newChat() }, enabled = !vm.generating) { Text("Новый") }
        }
    }
}

@Composable
private fun MessageBubble(m: UiMessage, onSpeak: (String) -> Unit) {
    val isUser = m.role == Role.USER
    val parsed = remember(m.raw) { ChatFormat.parse(m.raw) }
    var showThinking by remember { mutableStateOf(false) }

    val container = when {
        m.error -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = container)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!isUser && !m.error && parsed.thinking.isNotBlank()) {
                    Text(
                        if (showThinking) "💭 Рассуждения (скрыть)" else if (parsed.thinkingOpen) "💭 Думаю… (показать)" else "💭 Рассуждения (показать)",
                        Modifier.clickable { showThinking = !showThinking },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (showThinking) {
                        SelectionContainer {
                            Text(parsed.thinking, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val body = if (isUser || m.error) m.raw else parsed.answer
                if (body.isNotBlank()) {
                    SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium) }
                    if (!isUser && !m.error) TextButton(onClick = { onSpeak(body) }) { Text("Озвучить") }
                } else if (m.streaming) {
                    Text(if (parsed.thinkingOpen) "Думаю…" else "…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}
