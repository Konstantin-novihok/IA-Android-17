package com.iaandroid17.ui

import android.content.Intent
import android.media.MediaRecorder
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.os.CancellationSignal
import com.iaandroid17.voice.OwnerVoiceProfile
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.saveable.rememberSaveable
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
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var tempRecording by remember { mutableStateOf<File?>(null) }
    var profileNotice by remember { mutableStateOf("") }
    var askDeleteVoiceProfile by remember { mutableStateOf(false) }
    val enrollmentMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val slot = (samples + 1).coerceAtMost(3)
            try {
                val tmp = File(context.cacheDir, "owner-enroll-$slot.m4a")
                val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(16000)
                rec.setAudioEncodingBitRate(64000)
                rec.setOutputFile(tmp.absolutePath)
                rec.prepare()
                rec.start()
                tempRecording = tmp
                recorder = rec
                recordingSlot = slot
                profileNotice = "Записывается образец $slot. Произнесите фразу и сохраните."
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
            Text("Статус: speaker embedding, сравнение сходства, калибруемый порог, контроль качества сигнала и anti-spoof пока НЕ реализованы. Зашифрованные записи — лишь материал регистрации. Сценарии заблокированы до внедрения и проверки валидатора. Подделка голоса всё ещё может обходить голосовые проверки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (recordingSlot == null && samples < 3) {
                Button(onClick = { enrollmentMicPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Записать образец ${samples + 1} из 3") }
            }
            if (recordingSlot != null) {
                Text("Идёт запись образца $recordingSlot", style = MaterialTheme.typography.titleSmall)
                Button(onClick = {
                    try {
                        recorder?.stop()
                        recorder?.release()
                        recorder = null
                        val file = tempRecording
                        val slot = recordingSlot
                        if (file != null && slot != null && file.exists() && file.length() > 2048L) {
                            ownerProfile.saveEnrollmentClip(slot, file.readBytes())
                            samples = ownerProfile.sampleCount
                            profileNotice = "Образец зашифрован и сохранён локально."
                            file.delete()
                        } else profileNotice = "Запись слишком короткая. Повторите."
                    } catch (_: Exception) {
                        profileNotice = "Не удалось сохранить. Повторите запись."
                        tempRecording?.delete()
                    } finally {
                        recordingSlot = null
                        tempRecording = null
                    }
                }) { Text("Сохранить образец") }
                OutlinedButton(onClick = {
                    runCatching { recorder?.stop() }
                    recorder?.release()
                    recorder = null
                    recordingSlot = null
                    tempRecording?.delete()
                    tempRecording = null
                    profileNotice = "Запись отменена."
                }) { Text("Отменить") }
            }
            if (samples == 3) Text("Три образца собраны. Это не означает, что Voice ID уже работает.", style = MaterialTheme.typography.bodyMedium)
            if (profileNotice.isNotBlank()) Text(profileNotice, style = MaterialTheme.typography.bodySmall)
            if (samples > 0) OutlinedButton(onClick = { askDeleteVoiceProfile = true }) { Text("Удалить профиль и образцы") }
            Text("Перерегистрация требует системного подтверждения владельца. Шифрование образцов не подтверждает личность.", style = MaterialTheme.typography.labelSmall)
        }
        if (askDeleteVoiceProfile) {
            AlertDialog(
                onDismissRequest = { askDeleteVoiceProfile = false },
                title = { Text("Удалить голосовой профиль?") },
                text = { Text("Будут удалены образцы и ключ шифрования. Это необратимо.") },
                confirmButton = { TextButton(onClick = {
                    val activity = context as? android.app.Activity
                    if (Build.VERSION.SDK_INT >= 30 && activity != null) {
                        val prompt = BiometricPrompt.Builder(context)
                            .setTitle("Подтвердите владельца")
                            .setSubtitle("Повторная регистрация голосового профиля")
                            .setDescription("Подтвердите удаление старых голосовых образцов системной биометрией или PIN-кодом.")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                            .build()
                        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                ownerProfile.deleteProfile()
                                samples = 0
                                profileNotice = "Профиль удалён после подтверждения владельца. Для повторной регистрации запишите новые образцы."
                            }
                            override fun onAuthenticationFailed() {
                                profileNotice = "Проверка владельца не пройдена. Профиль сохранён."
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                profileNotice = "Подтверждение отменено. Профиль сохранён."
                            }
                        })
                    } else {
                        profileNotice = "Не удалось запросить проверку владельца. Профиль сохранён."
                    }
                    askDeleteVoiceProfile = false
                }) { Text("Подтвердить и удалить") },
                dismissButton = { TextButton(onClick = { askDeleteVoiceProfile = false }) { Text("Отмена") } }
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
            Button(
                onClick = {
                    if (s.assistantName.trim().length < 2) return@Button
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    voicePermissionLauncher.launch(perms.toTypedArray())
                },
                enabled = s.assistantName.trim().length >= 2 && !listeningRequested
            ) { Text("Включить голосовое ожидание") }
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
