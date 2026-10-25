package com.iaandroid17.ui

import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.os.CancellationSignal
import com.iaandroid17.voice.PcmAudioCapture
import com.iaandroid17.voice.OwnerVoiceProfile
import com.iaandroid17.voice.VoiceSecurityAudit
import com.iaandroid17.voice.VoiceVerificationReadiness
import com.iaandroid17.voice.SpeakerEmbeddingEngine
import com.iaandroid17.voice.SpeakerEmbeddingModelInstaller
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.iaandroid17.voice.VoiceScenarioService
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iaandroid17.engine.EngineState
import com.iaandroid17.settings.DEFAULT_MODEL_URL
import com.iaandroid17.settings.DEFAULT_SYSTEM_PROMPT
import java.io.File
import kotlin.math.roundToInt

private fun fmtBytes(b: Long): String =
    if (b >= 1L shl 30) "%.2f ГБ".format(b / (1024.0 * 1024.0 * 1024.0)) else "%d МБ".format(b / (1024 * 1024))

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val s = vm.settings
    var toDelete by remember { mutableStateOf<File?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var listeningRequested by rememberSaveable { mutableStateOf(false) }
    val ownerProfile = remember { OwnerVoiceProfile(context) }
    var samples by remember { mutableStateOf(ownerProfile.sampleCount) }
    var recordingSlot by remember { mutableStateOf<Int?>(null) }
    var captureStopped by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<PcmAudioCapture?>(null) }
    var tempRecording by remember { mutableStateOf<File?>(null) }
    var profileNotice by remember { mutableStateOf("") }
    var askDeleteVoiceProfile by remember { mutableStateOf(false) }
    var deleteSampleSlot by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteSample by remember { mutableStateOf(false) }
    var embeddingModelReady by remember { mutableStateOf(SpeakerEmbeddingModelInstaller.isInstalled(context)) }
    var embeddingBusy by remember { mutableStateOf(false) }
    var embeddingRevision by remember { mutableStateOf(0) }
    var embeddingNotice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val enrollmentMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val slot = ownerProfile.nextAvailableSlot()
            try {
                if (slot == null) { profileNotice = "Все три слота заполнены. Удалите один из образцов."; return@rememberLauncherForActivityResult }
                val tmp = File(context.cacheDir, "owner-enroll-$slot-${System.currentTimeMillis()}.pcm")
                val rec = PcmAudioCapture()
                rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(16000)
                rec.setAudioEncodingBitRate(64000)
                rec.setOutputFile(tmp.absolutePath)
                rec.prepare()
                rec.start()
                tempRecording = tmp
                recorder = rec
                recordingSlot = slot
                captureStopped = false
                profileNotice = "Идёт запись образца $slot. PCM16, моно, 16 кГц. Когда закончите говорить, нажмите «Остановить запись», затем «Сохранить образец». Длительность 2–12 секунд."
            } catch (e: Exception) {
                recorder?.release()
                recorder = null
                recordingSlot = null
                profileNotice = "Не удалось начать запись: ${e.message ?: "ошибка микрофона"}"
            }
        } else profileNotice = "Для записи нужен доступ к микрофону."
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) {
            context.startForegroundService(Intent(context, VoiceScenarioService::class.java).setAction(VoiceScenarioService.ACTION_START))
            listeningRequested = true
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)

        // ---------- Модель ----------
        Section("Модель") {
            val (avail, total) = vm.memoryInfo()
            Text("Память телефона: свободно ${fmtBytes(avail)} из ${fmtBytes(total)}", style = MaterialTheme.typography.bodySmall)
            val activeName = vm.modelFiles.firstOrNull { it.name == s.activeModel }?.name ?: vm.modelFiles.firstOrNull()?.name
            if (vm.modelFiles.isEmpty()) {
                Text("Установленных моделей нет. Скачайте Qwen3-8B Q4_K_M ниже или выберите свой файл .gguf.", style = MaterialTheme.typography.bodyMedium)
            }
            for (f in vm.modelFiles) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = f.name == activeName, onClick = { vm.selectModel(f) })
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(fmtBytes(f.length()), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { toDelete = f }) { Text("Удалить") }
                }
            }
            val st = vm.engineState
            when (st) {
                is EngineState.Ready -> Text("В памяти: ${st.modelName}, контекст ${st.nCtx}", style = MaterialTheme.typography.bodySmall)
                is EngineState.Error -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st is EngineState.Ready) {
                    OutlinedButton(onClick = { vm.reloadModel() }, enabled = !vm.generating) { Text("Перезагрузить") }
                    OutlinedButton(onClick = { vm.unloadModel() }, enabled = !vm.generating) { Text("Выгрузить") }
                } else if (vm.modelFiles.isNotEmpty() && st !is EngineState.Loading) {
                    Button(onClick = { vm.loadModel() }) { Text("Загрузить в память") }
                }
            }
        }

        // ---------- Загрузка ----------
        Section("Получить модель") {
            Text(
                "Qwen3-8B Q4_K_M — файл около 5 ГБ, нужен Wi-Fi и свободное место. После загрузки приложение сверит SHA-256 с опубликованной на Hugging Face. " +
                    "Загрузка продолжится с того же места, если прервётся. Лицензия модели Qwen3 — Apache 2.0.",
                style = MaterialTheme.typography.bodySmall
            )
            SwitchRow(
                "Разрешить интернет для загрузки модели",
                "Выключено — приложение не обращается в сеть вообще",
                s.allowNetwork
            ) { v -> vm.update { it.copy(allowNetwork = v) } }
            OutlinedTextField(
                value = s.modelUrl,
                onValueChange = { v -> vm.update { it.copy(modelUrl = v) } },
                label = { Text("Ссылка на GGUF (https)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            val d = vm.download
            if (d.running) {
                Text(d.message, style = MaterialTheme.typography.bodySmall)
                if (d.total > 0) {
                    LinearProgressIndicator(progress = { (d.done.toFloat() / d.total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${fmtBytes(d.done)} из ${fmtBytes(d.total)}", style = MaterialTheme.typography.labelSmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Остановить") }
            } else {
                if (d.message.isNotBlank()) Text(d.message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startDownload() }) { Text("Скачать") }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Файл с телефона…") }
                }
                TextButton(onClick = { vm.update { it.copy(modelUrl = DEFAULT_MODEL_URL) } }) { Text("Вернуть ссылку на Qwen3-8B Q4_K_M") }
            }
        }


        Section("Дополнительные модели — комплектная загрузка") {
            var packageUrls by rememberSaveable { mutableStateOf("") }
            Text("Добавьте HTTPS-ссылки на дополнительные GGUF-файлы, по одной на строку. Очередь скачивает их последовательно, пропускает уже установленные файлы и проверяет GGUF. Для Hugging Face сверяется SHA-256, если доступна контрольная сумма.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = packageUrls,
                onValueChange = { packageUrls = it.take(12000) },
                label = { Text("Ссылки на дополнительные .gguf") },
                placeholder = { Text("https://…/model.gguf") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 7
            )
            Button(onClick = { vm.downloadPackageSet(packageUrls.lines().flatMap { it.split(Regex("\\s+")) }) },
                enabled = !vm.download.running && packageUrls.isNotBlank()) { Text("Скачать комплект") }
            Text("Скачаются выбранные вами GGUF-модели. Это пока не установщик всех модальностей: OCR, ASR и Voice ID требуют отдельных runtime и совместимых моделей, которые ещё не интегрированы. Не загружайте случайные веса.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }

        // ---------- Генерация и ресурсы ----------
        Section("Генерация и ресурсы") {
            Text("Лимит контекста: ${s.contextSize} токенов", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.contextSize.toFloat(),
                onValueChange = { v -> vm.update { it.copy(contextSize = ((v / 1024f).roundToInt() * 1024).coerceIn(2048, 16384)) } },
                valueRange = 2048f..16384f,
                steps = 13
            )
            Text(
                "KV-кэш для Qwen3-8B займёт примерно ${(s.contextSize * 144L) / 1024} МБ (плюс сама модель ≈ 5 ГБ). Применяется при следующей загрузке модели.",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Потоков процессора: ${s.threads}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.threads.toFloat(),
                onValueChange = { v -> vm.update { it.copy(threads = v.roundToInt().coerceIn(1, 8)) } },
                valueRange = 1f..8f,
                steps = 6
            )
            Text("Больше потоков — быстрее, но сильнее нагрев и расход батареи. Применяется при следующей загрузке модели.", style = MaterialTheme.typography.labelSmall)
            Text("Максимум токенов в ответе: ${s.maxNewTokens}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.maxNewTokens.toFloat(),
                onValueChange = { v -> vm.update { it.copy(maxNewTokens = ((v / 128f).roundToInt() * 128).coerceIn(128, 4096)) } },
                valueRange = 128f..4096f,
                steps = 30
            )
            Text("Температура: ${"%.2f".format(s.temperature)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.temperature,
                onValueChange = { v -> vm.update { it.copy(temperature = (v * 20f).roundToInt() / 20f) } },
                valueRange = 0f..1.2f
            )
            SwitchRow(
                "Режим рассуждений (thinking)",
                "Модель сначала «думает», ответ точнее, но намного дольше. Рассуждения можно раскрыть в чате.",
                s.thinking
            ) { v -> vm.update { it.copy(thinking = v) } }
        }

        // ---------- Голосовой профиль владельца ----------
        Section("Голосовой профиль владельца") {
            Text("Записано образцов: $samples из 3. Записи хранятся только на устройстве в зашифрованном виде (AES-GCM, Android Keystore).", style = MaterialTheme.typography.bodySmall)
            Text("РЕЖИМ: ТОЛЬКО ЧАТ. Голосовые команды закрыты до интеграции обученного speaker-verifier и anti-spoof. Embedding и сравнение отсутствуют; порог не откалиброван. Добавлена базовая проверка длительности/размера записи (это не оценка SNR). Перерегистрация защищена системной биометрией/учётными данными устройства. Добавлены ограничитель отказов и локальный аудит.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(VoiceVerificationReadiness.status(context), style = MaterialTheme.typography.labelSmall)
            Text("Аудит хранит события безопасности, не звук и не текст команд.", style = MaterialTheme.typography.labelSmall)
            if (recordingSlot == null && samples < 3) {
                Button(onClick = { enrollmentMicPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Записать образец ${samples + 1} из 3") }
            }
            if (recordingSlot != null) {
                Text(
                    if (captureStopped) "Запись образца $recordingSlot остановлена. Проверьте качество, затем сохраните."
                    else "Идёт запись образца $recordingSlot",
                    style = MaterialTheme.typography.titleSmall
                )
                if (!captureStopped) {
                    Button(onClick = {
                        try {
                            recorder?.stop()
                            recorder?.release()
                            recorder = null
                            captureStopped = true
                            val file = tempRecording
                            if (file == null || !file.exists()) {
                                profileNotice = "Временная запись отсутствует. Отмените и повторите."
                            } else {
                                val quality = VoiceVerificationReadiness.inspectRecording(file)
                                profileNotice = if (quality.accepted)
                                    "Запись готова: ${quality.durationMs / 1000.0} сек. ${quality.reason}"
                                else "Запись остановлена, но не прошла проверку: ${quality.reason} Можно отменить и записать заново."
                            }
                        } catch (e: Exception) {
                            profileNotice = "Ошибка остановки записи: ${e.message ?: "неизвестная ошибка"}"
                            runCatching { recorder?.release() }
                            recorder = null
                            captureStopped = true
                        }
                    }) { Text("Остановить запись") }
                } else {
                    Button(onClick = {
                        try {
                            val file = tempRecording
                            val slot = recordingSlot
                            if (file == null || slot == null || !file.exists()) {
                                profileNotice = "Запись отсутствует. Повторите."
                            } else {
                                val quality = VoiceVerificationReadiness.inspectRecording(file)
                                if (quality.accepted) {
                                    ownerProfile.saveEnrollmentClip(slot, file.readBytes())
                                    samples = ownerProfile.sampleCount
                                    profileNotice = "Образец сохранён: ${quality.durationMs / 1000.0} сек., ${quality.bytes} байт. ${quality.reason}"
                                    file.delete()
                                    recordingSlot = null
                                    tempRecording = null
                                    captureStopped = false
                                } else {
                                    profileNotice = "Сохранение отклонено: ${quality.reason} Запишите образец заново."
                                }
                            }
                        } catch (e: Exception) {
                            profileNotice = "Не удалось сохранить образец: ${e.message ?: "ошибка"}"
                        }
                    }, enabled = captureStopped) { Text("Сохранить образец") }
                    OutlinedButton(onClick = {
                        runCatching { recorder?.release() }
                        recorder = null
                        tempRecording?.delete()
                        tempRecording = null
                        recordingSlot = null
                        captureStopped = false
                        profileNotice = "Образец отменён. Можно начать запись заново."
                    }) { Text("Записать заново / отменить") }
                }
            }
            if (samples == 3) Text("Три PCM-образца собраны. После установки модели можно извлечь реальные speaker embeddings; голосовая авторизация команд остаётся отключённой.", style = MaterialTheme.typography.bodyMedium)
            if (samples > 0) {
                Text("Сохранённые образцы", style = MaterialTheme.typography.titleSmall)
                ownerProfile.sampleSlots.forEach { slot ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Образец $slot", style = MaterialTheme.typography.bodyMedium)
                            Text("Зашифрован", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { deleteSampleSlot = slot; confirmDeleteSample = true }) { Text("Удалить") }
                    }
                }
            }
            Text("Реальное извлечение speaker embedding", style = MaterialTheme.typography.titleSmall)
            Text("CAM++ (512 измерений), локальный ONNX Runtime. Модель скачивается отдельно (~30 МБ), проверяется по SHA-256 и после установки работает офлайн. Векторы хранятся зашифрованными ключом Android Keystore.", style = MaterialTheme.typography.bodySmall)
            if (!embeddingModelReady) {
                Button(onClick = {
                    if (!s.allowNetwork) {
                        embeddingNotice = "Разрешите интернет переключателем выше только для загрузки модели."
                    } else {
                        scope.launch {
                            embeddingBusy = true
                            embeddingNotice = "Загрузка модели CAM++ и проверка SHA-256…"
                            try {
                                withContext(Dispatchers.IO) { SpeakerEmbeddingModelInstaller.download(context) }
                                embeddingModelReady = SpeakerEmbeddingModelInstaller.isInstalled(context)
                                embeddingNotice = if (embeddingModelReady) "Модель установлена и SHA-256 подтверждён." else "Модель не прошла проверку."
                            } catch (e: Exception) {
                                embeddingNotice = "Не удалось установить модель: " + (e.message ?: e.javaClass.simpleName)
                            } finally { embeddingBusy = false }
                        }
                    }
                }, enabled = !embeddingBusy && s.allowNetwork) { Text(if (embeddingBusy) "Загрузка…" else "Скачать модель speaker embedding (~30 МБ)") }
            } else {
                Text("Модель установлена; SHA-256 проверяется перед каждым созданием движка.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    scope.launch {
                        embeddingBusy = true
                        embeddingNotice = "Извлечение векторов из сохранённых голосовых образцов…"
                        try {
                            val slots = ownerProfile.sampleSlots
                            check(slots.isNotEmpty()) { "Сначала запишите хотя бы один голосовой образец." }
                            val result = withContext(Dispatchers.IO) {
                                SpeakerEmbeddingEngine(context).use { engine ->
                                    slots.map { slot ->
                                        val vector = engine.embed(ownerProfile.readEnrollmentClip(slot))
                                        ownerProfile.saveEmbedding(slot, vector)
                                        slot to vector.size
                                    }
                                }
                            }
                            embeddingRevision++
                            embeddingNotice = "Реальные embedding извлечены и зашифрованы: " + result.joinToString { "образец " + it.first + " — " + it.second + "D" } + ". Это ещё не разрешает голосовые команды."
                        } catch (e: Exception) {
                            embeddingNotice = "Извлечение не завершено: " + (e.message ?: e.javaClass.simpleName)
                        } finally { embeddingBusy = false }
                    }
                }, enabled = !embeddingBusy && samples > 0) { Text(if (embeddingBusy) "Вычисление…" else "Извлечь embeddings из сохранённых образцов") }
                val stored = ownerProfile.sampleSlots.count { ownerProfile.hasEmbedding(it) }
                Text("Сохранено зашифрованных векторов: " + stored + " из " + ownerProfile.sampleCount, style = MaterialTheme.typography.bodySmall)
            }
            if (embeddingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (embeddingNotice.isNotBlank()) Text(embeddingNotice, style = MaterialTheme.typography.bodySmall)
            Text("Этапы Voice ID", style = MaterialTheme.typography.titleSmall)
            listOf(
                "1. Запись PCM и базовые проверки сигнала" to "Доступно: длительность, уровень, clipping.",
                "2. Обученная модель speaker embedding" to if (embeddingModelReady) "РЕАЛИЗОВАНО: CAM++ ONNX, 512-D, fbank + mean normalization, проверка SHA-256." else "ОЖИДАЕТ УСТАНОВКИ: модель ещё не загружена.",
                "3. Шаблон профиля и сравнение голосов" to if (ownerProfile.sampleSlots.any { ownerProfile.hasEmbedding(it) }) "Частично: извлечённые векторы зашифрованы; сравнение и калибровка ещё не подключены." else "НЕ ГОТОВО: сначала извлеките embedding из образцов."
                "4. Биометрическая калибровка FAR/FRR" to "НЕ ГОТОВО: нет размеченных измерений и подтверждённого порога.",
                "5. Защита от записи/синтеза (anti-spoof)" to "НЕ ГОТОВО: детектор и проверка живости отсутствуют.",
                "6. Допуск голосовых команд" to "ЗАКРЫТ: команды отклоняются до независимой проверки всех этапов."
            ).forEach { (title, detail) ->
                Column {
                    Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = if (title.startsWith("1.")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            if (profileNotice.isNotBlank()) Text(profileNotice, style = MaterialTheme.typography.bodySmall)
            Text("Последние события безопасности:", style = MaterialTheme.typography.titleSmall)
            VoiceSecurityAudit.snapshot(context).takeLast(8).asReversed().forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (samples > 0) OutlinedButton(onClick = { askDeleteVoiceProfile = true }) { Text("Удалить профиль и образцы") }
            Text("Embedding — биометрический признак, а не доказательство личности. Порог не калиброван, anti-spoofing отсутствует; голосовая авторизация команд остаётся заблокированной.", style = MaterialTheme.typography.labelSmall)
        }
        if (confirmDeleteSample && deleteSampleSlot != null) {
            AlertDialog(
                onDismissRequest = { confirmDeleteSample = false; deleteSampleSlot = null },
                title = { Text("Удалить образец #$deleteSampleSlot?") },
                text = { Text("Будет удалён только выбранный образец. Остальные сохранённые записи останутся.") },
                confirmButton = {
                    TextButton(onClick = {
                        val slot = deleteSampleSlot
                        try {
                            if (slot != null && ownerProfile.deleteSample(slot)) {
                                samples = ownerProfile.sampleCount
                                profileNotice = "Образец #$slot удалён."
                            } else profileNotice = "Образец уже отсутствует или не удалось его удалить."
                        } catch (e: Exception) {
                            profileNotice = "Не удалось удалить образец: " + (e.message ?: e.javaClass.simpleName)
                        } finally {
                            confirmDeleteSample = false
                            deleteSampleSlot = null
                        }
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteSample = false; deleteSampleSlot = null }) { Text("Отмена") } }
            )
        }
        if (askDeleteVoiceProfile) {
            AlertDialog(
                onDismissRequest = { askDeleteVoiceProfile = false },
                title = { Text("Удалить голосовой профиль?") },
                text = { Text("Будут удалены образцы и ключ шифрования. Это необратимо.") },
                confirmButton = {
                    TextButton(onClick = {
                        val activity = context as? android.app.Activity
                        if (Build.VERSION.SDK_INT >= 30 && activity != null) {
                            val callback = object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    ownerProfile.deleteProfile()
                                    samples = 0
                                    profileNotice = "Профиль удалён после подтверждения владельца."
                                }
                                override fun onAuthenticationFailed() {
                                    profileNotice = "Проверка владельца не пройдена. Профиль сохранён."
                                }
                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    profileNotice = "Подтверждение отменено. Профиль сохранён."
                                }
                            }
                            try {
                                val prompt = BiometricPrompt.Builder(context)
                                    .setTitle("Подтвердите владельца")
                                    .setSubtitle("Повторная регистрация голосового профиля")
                                    .setDescription("Подтвердите удаление старых образцов системной биометрией или PIN-кодом.")
                                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                                    .build()
                                prompt.authenticate(CancellationSignal(), context.mainExecutor, callback)
                            } catch (e: Exception) {
                                profileNotice = "Системное подтверждение недоступно: " + (e.message ?: e.javaClass.simpleName) + ". Профиль не изменён."
                                VoiceSecurityAudit.record(context, "profile_delete_confirmation_error", e.javaClass.simpleName)
                            }
                        } else {
                            profileNotice = "Не удалось запросить проверку владельца. Профиль сохранён."
                        }
                        askDeleteVoiceProfile = false
                    }) {
                        Text("Подтвердить и удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { askDeleteVoiceProfile = false }) { Text("Отмена") }
                }
            )
        }

        // ---------- Оформление ----------
        Section("Тема и цветовая гамма") {
            listOf("system" to "Системная / динамическая", "indigo" to "Индиго", "forest" to "Лесная", "sunset" to "Закат").forEach { (id, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.themeStyle == id, onClick = { vm.update { it.copy(themeStyle = id) } })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Цвета применяются сразу. Встроенные палитры адаптируются к светлому или тёмному режиму Android.", style = MaterialTheme.typography.labelSmall)
        }

        // ---------- Идентификация и голосовой запуск ----------
        Section("Имя помощника и голосовой запуск") {
            OutlinedTextField(
                value = s.assistantName,
                onValueChange = { v -> vm.update { it.copy(assistantName = v.take(40)) } },
                label = { Text("Имя помощника") },
                supportingText = { Text("Скажите это имя перед кодом: «${s.assistantName.ifBlank { "помощник" }}, код один».") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Для теста доступны три безопасных сценария: 1 — произнести время; 2 — произнести дату; 3 — голосовая самопроверка. Произвольные команды и управление телефоном пока не выполняются.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { profileNotice = "Speaker-verifier не интегрирован. Команды не активированы." }, enabled = false) {
                Text("Голосовые команды заблокированы до Voice ID")
            }
            Text("Фоновая служба не открывает микрофон для команд. Доступен текстовый чат. Распознавание речи не доказывает личность.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = {
                    context.startService(Intent(context, VoiceScenarioService::class.java).setAction(VoiceScenarioService.ACTION_STOP))
                    listeningRequested = false
                },
                enabled = listeningRequested
            ) { Text("Остановить голосовое ожидание") }
            Text("Работает как foreground-сервис с постоянным уведомлением. Разрешите микрофон. Распознавание зависит от движка речи на телефоне; автономная работа не гарантируется. После перезапуска телефона включите ожидание вручную.", style = MaterialTheme.typography.labelSmall)
        }

        // ---------- Голосовые сценарии ----------
        Section("Голосовые сценарии") {
            Text("Длительность короткого вибросигнала: ${s.scenarioPulseMs} мс", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.scenarioPulseMs.toFloat(),
                onValueChange = { v -> vm.update { it.copy(scenarioPulseMs = v.roundToInt().coerceIn(40, 400)) } },
                valueRange = 40f..400f,
                steps = 17
            )
            Text("Ползунок задаёт длительность одного импульса (40–400 мс). Количество импульсов сможет обозначать номер сценария. Сценарии и фоновое распознавание пока не подключены.", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = {
                val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(s.scenarioPulseMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            }) { Text("Проверить вибрацию") }
        }

        // ---------- Безопасность ----------
        Section("Права доступа и безопасность") {
            Text(
                "• Файлы: приложение читает только документы, которые вы сами выбрали через системное окно выбора. Доступа ко всей памяти нет.\n" +
                    "• Интернет: нужен только для загрузки модели и включается переключателем выше.\n" +
                    "• Микрофон, камера, контакты, геолокация: не запрашиваются.\n" +
                    "• Команды и действия на телефоне ИИ выполнять не может: в этой версии он только читает текст и отвечает.\n" +
                    "• Содержимое документов считается недоверенным: инструкции внутри файлов не исполняются.",
                style = MaterialTheme.typography.bodySmall
            )
            SwitchRow("Использовать документы в ответах", "Подключённые документы добавляются в запрос к модели", s.useDocuments) { v -> vm.update { it.copy(useDocuments = v) } }
            SwitchRow("Сохранять историю диалога", "Хранится только в памяти приложения", s.saveHistory) { v -> vm.update { it.copy(saveHistory = v) } }
            SwitchRow("Защита от перегрева", "Остановить ответ, если Android сообщает о сильном нагреве", s.thermalGuard) { v -> vm.update { it.copy(thermalGuard = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Системные настройки") }
                OutlinedButton(onClick = { vm.newChat() }, enabled = !vm.generating) { Text("Очистить диалог") }
            }
        }

        // ---------- Системная инструкция ----------
        Section("Инструкция для помощника") {
            OutlinedTextField(
                value = s.systemPrompt,
                onValueChange = { v -> vm.update { it.copy(systemPrompt = v) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8
            )
            TextButton(onClick = { vm.update { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) } }) { Text("Сбросить") }
        }

        // ---------- О приложении ----------
        Section("О приложении") {
            Text("IA-Android-17 · версия 0.3.0 · движок llama.cpp (b11836)", style = MaterialTheme.typography.bodySmall)
            if (vm.systemInfo.isNotBlank()) Text(vm.systemInfo, style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { showAbout = true }) { Text("О локальной обработке") }
        }
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить модель?") },
            text = { Text("Файл ${del.name} (${fmtBytes(del.length())}) будет удалён с телефона.") },
            confirmButton = { TextButton(onClick = { vm.deleteModel(del); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("О локальной обработке") },
            text = {
                Text(
                    "Нейросеть работает прямо на вашем телефоне: тексты и документы не отправляются на серверы. " +
                        "Интернет используется только при загрузке модели, и только если вы его разрешили. " +
                        "Ответы модели могут содержать ошибки — важные факты проверяйте."
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Понятно") } }
        )
    }
}
