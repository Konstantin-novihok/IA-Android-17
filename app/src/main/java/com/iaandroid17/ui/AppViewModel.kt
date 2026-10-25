package com.iaandroid17.ui

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iaandroid17.chat.ChatFormat
import com.iaandroid17.chat.ChatStore
import com.iaandroid17.chat.ChatTurn
import com.iaandroid17.chat.Role
import com.iaandroid17.docs.DocInfo
import com.iaandroid17.docs.DocumentExtractor
import com.iaandroid17.docs.DocumentStore
import com.iaandroid17.docs.Rag
import com.iaandroid17.engine.EngineState
import com.iaandroid17.engine.GenParams
import com.iaandroid17.engine.LlamaNative
import com.iaandroid17.engine.LlmEngine
import com.iaandroid17.engine.TokenCallback
import com.iaandroid17.model.ModelManager
import com.iaandroid17.settings.AppSettings
import com.iaandroid17.settings.DEFAULT_MODEL_URL
import com.iaandroid17.settings.SettingsStore
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class UiMessage(
    val id: Long,
    val role: Role,
    val raw: String,
    val streaming: Boolean = false,
    val error: Boolean = false
)

data class DownloadUi(
    val running: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val message: String = ""
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val settingsStore = SettingsStore(ctx)
    private val chatStore = ChatStore(ctx)
    private val main = Handler(Looper.getMainLooper())

    val engine = LlmEngine(ctx)
    val models = ModelManager(ctx)
    val docs = DocumentStore(ctx)

    var settings by mutableStateOf(settingsStore.load())
        private set
    var engineState by mutableStateOf<EngineState>(EngineState.Unloaded)
        private set
    var generating by mutableStateOf(false)
        private set
    var banner by mutableStateOf("")
    var download by mutableStateOf(DownloadUi())
        private set
    var busyMessage by mutableStateOf("")
        private set
    var systemInfo by mutableStateOf("")
        private set

    val messages = mutableStateListOf<UiMessage>()
    val modelFiles = mutableStateListOf<File>()
    val docList = mutableStateListOf<DocInfo>()

    private var nextId = 1L
    private var genJob: Job? = null
    private var downloadJob: Job? = null
    private var stopRequested = false
    @Volatile private var thermalStopped = false

    init {
        refreshModels()
        refreshDocs()
        if (settings.saveHistory) {
            chatStore.load().forEach { messages.add(UiMessage(nextId++, it.role, it.text)) }
        }
    }

    // ---------- настройки ----------

    fun update(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        settingsStore.save(settings)
    }

    // ---------- модели ----------

    fun refreshModels() {
        modelFiles.clear()
        modelFiles.addAll(models.list())
    }

    private fun activeModelFile(): File? {
        val all = models.list()
        return all.firstOrNull { it.name == settings.activeModel } ?: all.firstOrNull()
    }

    fun selectModel(file: File) {
        update { it.copy(activeModel = file.name) }
        if (engineState is EngineState.Ready) unloadModel()
    }

    fun deleteModel(file: File) {
        if (engineState is EngineState.Ready) unloadModel()
        models.delete(file)
        refreshModels()
    }

    fun memoryInfo(): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem to mi.totalMem
    }

    fun loadModel() {
        if (engineState is EngineState.Loading || generating) return
        viewModelScope.launch { loadModelNow() }
    }

    private suspend fun loadModelNow(): Boolean {
        val file = activeModelFile()
        if (file == null) {
            engineState = EngineState.Error("Модель не установлена. Откройте Настройки → Модель и скачайте или выберите файл GGUF.")
            return false
        }
        val s = settings
        engineState = EngineState.Loading
        val (avail, _) = memoryInfo()
        if (avail < file.length() + 400L * 1024 * 1024) {
            banner = "Мало свободной оперативной памяти (${avail / (1024 * 1024)} МБ). Закройте другие приложения — иначе Android может завершить загрузку."
        }
        val err = engine.load(file.absolutePath, s.contextSize, s.threads)
        return if (err.isEmpty()) {
            val real = engine.onEngine { LlamaNative.nativeContextSize() }
            engineState = EngineState.Ready(file.name, real, s.threads)
            systemInfo = engine.systemInfo()
            true
        } else {
            engineState = EngineState.Error(err)
            false
        }
    }

    fun unloadModel() {
        if (generating) return
        viewModelScope.launch {
            engine.unload()
            engineState = EngineState.Unloaded
        }
    }

    fun reloadModel() {
        if (generating) return
        viewModelScope.launch {
            engine.unload()
            engineState = EngineState.Unloaded
            loadModelNow()
        }
    }

    /** Реакция на нехватку памяти в системе. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !generating && engineState is EngineState.Ready) {
            banner = "Системе не хватает памяти — модель выгружена. Она загрузится снова при следующем сообщении."
            unloadModel()
        }
    }

    // ---------- загрузка и импорт модели ----------

    fun startDownload() {
        if (download.running) return
        if (!settings.allowNetwork) {
            download = DownloadUi(message = "Сначала включите переключатель «Разрешить интернет для загрузки модели» ниже.")
            return
        }
        val url = settings.modelUrl.trim().ifBlank { DEFAULT_MODEL_URL }
        if (!url.startsWith("https://")) {
            download = DownloadUi(message = "Ссылка должна начинаться с https://")
            return
        }
        downloadJob = viewModelScope.launch {
            val name = models.fileNameFromUrl(url).let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            download = DownloadUi(running = true, message = "Подключение…")
            try {
                val file = models.download(url, name) { done, total ->
                    main.post { download = DownloadUi(true, done, total, "Загрузка модели…") }
                }
                if (!models.isGguf(file)) {
                    file.delete()
                    throw IOException("Скачанный файл не является GGUF. Проверьте ссылку.")
                }
                download = DownloadUi(running = true, message = "Проверка контрольной суммы SHA-256…")
                val expected = models.expectedSha256(url)
                val actual = models.sha256(file) { d, t -> main.post { download = DownloadUi(true, d, t, "Проверка контрольной суммы SHA-256…") } }
                if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                    file.delete()
                    throw IOException("Контрольная сумма не совпала — файл повреждён и удалён. Повторите загрузку.")
                }
                update { it.copy(activeModel = file.name) }
                refreshModels()
                download = DownloadUi(
                    message = if (expected != null) "Готово. SHA-256 совпала с опубликованной ✓" else
                        "Готово. Опубликованную сумму получить не удалось. SHA-256: $actual"
                )
            } catch (e: CancellationException) {
                download = DownloadUi(message = "Загрузка остановлена. Нажмите «Скачать» — она продолжится с того же места.")
                throw e
            } catch (e: Exception) {
                download = DownloadUi(message = "Ошибка: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }


    /** Serial installer for user-selected optional GGUF add-ons. */
    fun downloadPackageSet(rawUrls: List<String>) {
        if (download.running) return
        val urls = rawUrls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) { download = DownloadUi(message = "Добавьте HTTPS-ссылки на дополнительные GGUF-файлы."); return }
        if (!settings.allowNetwork) { download = DownloadUi(message = "Сначала разрешите интернет в настройках."); return }
        val invalid = urls.firstOrNull { !it.startsWith("https://", true) || !it.substringBefore('?').endsWith(".gguf", true) }
        if (invalid != null) { download = DownloadUi(message = "Нужны HTTPS-ссылки на .gguf. Ошибка: " + invalid.take(100)); return }
        downloadJob = viewModelScope.launch {
            val doneNames = mutableListOf<String>()
            val errors = mutableListOf<String>()
            download = DownloadUi(running = true, message = "Подготовка комплекта: \${urls.size} файлов")
            try {
                urls.forEachIndexed { index, url ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    var name = models.fileNameFromUrl(url).replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (!name.endsWith(".gguf", true)) name += ".gguf"
                    if (models.list().any { it.name.equals(name, true) }) {
                        doneNames += "$name (уже установлен)"
                        return@forEachIndexed
                    }
                    val label = "[\${index + 1}/\${urls.size}] $name"
                    try {
                        download = DownloadUi(running = true, message = "$label — загрузка")
                        val file = models.download(url, name) { d, t -> main.post { download = DownloadUi(true, d, t, "$label — загрузка") } }
                        if (!models.isGguf(file)) { file.delete(); throw IOException("Файл не распознан как GGUF") }
                        val expected = models.expectedSha256(url)
                        if (expected != null) {
                            download = DownloadUi(running = true, message = "$label — проверка SHA-256")
                            val actual = models.sha256(file) { d, t -> main.post { download = DownloadUi(true, d, t, "$label — SHA-256") } }
                            if (!expected.equals(actual, true)) { file.delete(); throw IOException("SHA-256 не совпал; файл удалён") }
                        }
                        doneNames += name
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors += "$name: \${e.message ?: e.javaClass.simpleName}" }
                }
                refreshModels()
                if (settings.activeModel.isBlank() && modelFiles.isNotEmpty()) update { it.copy(activeModel = modelFiles.first().name) }
                download = DownloadUi(message = "Комплект завершён. Установлено/уже было: \${doneNames.size}; ошибок: \${errors.size}." +
                    if (errors.isEmpty()) "" else "\n" + errors.joinToString("\n").take(1300))
            } catch (e: CancellationException) {
                refreshModels()
                download = DownloadUi(message = "Загрузка остановлена. Готовые файлы сохранены; текущую загрузку можно продолжить.")
                throw e
            } catch (e: Exception) { download = DownloadUi(message = "Ошибка комплекта: \${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun importModel(uri: Uri) {
        if (download.running) return
        downloadJob = viewModelScope.launch {
            download = DownloadUi(running = true, message = "Копирование файла в память приложения…")
            try {
                val f = models.importFrom(uri) { d, t -> main.post { download = DownloadUi(true, d, t, "Копирование файла в память приложения…") } }
                update { it.copy(activeModel = f.name) }
                refreshModels()
                download = DownloadUi(message = "Файл ${f.name} добавлен.")
            } catch (e: CancellationException) {
                download = DownloadUi(message = "Копирование отменено.")
                throw e
            } catch (e: Exception) {
                download = DownloadUi(message = "Ошибка: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- документы ----------

    fun refreshDocs() {
        docList.clear()
        docList.addAll(docs.list())
    }

    fun addDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            val problems = ArrayList<String>()
            var added = 0
            for ((i, uri) in uris.withIndex()) {
                busyMessage = "Чтение файла ${i + 1} из ${uris.size}…"
                try {
                    val ex = withContext(Dispatchers.IO) { DocumentExtractor.extract(ctx, uri) }
                    withContext(Dispatchers.IO) { docs.add(ex.name, ex.text, ex.note) }
                    added++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    problems.add(e.message ?: e.javaClass.simpleName)
                }
            }
            busyMessage = ""
            refreshDocs()
            banner = buildString {
                if (added > 0) append("Добавлено документов: $added. ")
                if (problems.isNotEmpty()) append(problems.joinToString(" ")) 
            }.trim()
        }
    }

    fun setDocEnabled(id: String, enabled: Boolean) { docs.setEnabled(id, enabled); refreshDocs() }
    fun removeDoc(id: String) { docs.remove(id); refreshDocs() }
    fun clearDocs() { docs.clear(); refreshDocs() }

    // ---------- чат ----------

    private fun thermalSevere(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    private fun setMessage(id: Long, f: (UiMessage) -> UiMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    private fun persist() {
        if (!settings.saveHistory) return
        chatStore.save(messages.filter { !it.error && !it.streaming && it.raw.isNotBlank() }
            .map { ChatTurn(it.role, if (it.role == Role.ASSISTANT) ChatFormat.parse(it.raw).answer else it.raw) })
    }

    fun newChat() {
        if (generating) return
        messages.clear()
        chatStore.clear()
        viewModelScope.launch { engine.resetCache() }
    }

    fun stop() {
        stopRequested = true
        engine.cancel()
    }

    fun send(text: String) {
        val question = text.trim()
        if (generating || question.isEmpty()) return
        banner = ""
        messages.add(UiMessage(nextId++, Role.USER, question))
        val reply = UiMessage(nextId++, Role.ASSISTANT, "", streaming = true)
        messages.add(reply)
        generating = true
        stopRequested = false
        thermalStopped = false

        genJob = viewModelScope.launch {
            try {
                if (engineState !is EngineState.Ready) {
                    if (!loadModelNow()) {
                        val msg = (engineState as? EngineState.Error)?.message ?: "Не удалось загрузить модель"
                        setMessage(reply.id) { it.copy(raw = msg, streaming = false, error = true) }
                        return@launch
                    }
                }
                val s = settings
                val history = messages.filter { it.id != reply.id && !it.error && it.raw.isNotBlank() }
                    .map { ChatTurn(it.role, if (it.role == Role.ASSISTANT) ChatFormat.parse(it.raw).answer else it.raw) }
                val chunks = if (s.useDocuments) docs.enabledChunks() else emptyList()

                val prompt = engine.onEngine {
                    val nCtx = LlamaNative.nativeContextSize()
                    val maxNew = s.maxNewTokens.coerceAtMost(nCtx / 2)
                    val budget = nCtx - maxNew - 32
                    val count = { t: String -> LlamaNative.nativeCountTokens(t).coerceAtLeast(0) }
                    var system = s.systemPrompt
                    if (chunks.isNotEmpty()) {
                        var chars = (budget * 0.40 * 2.3).toInt()
                        for (attempt in 0 until 5) {
                            val picked = Rag.select(chunks, question, chars)
                            val body = picked.joinToString("\n\n") { "[Документ: ${it.docName}, фрагмент ${it.index + 1}]\n${it.text}" }
                            system = s.systemPrompt + "\n\nФрагменты документов пользователя:\n\n" + body
                            if (count(system) <= budget * 0.5) break
                            chars = (chars * 0.7).toInt()
                        }
                    }
                    val turns = ChatFormat.fit(system, history, s.thinking, budget, count)
                    ChatFormat.build(system, turns, s.thinking)
                }

                val params = GenParams(
                    maxNew = s.maxNewTokens,
                    temperature = s.temperature,
                    topP = if (s.thinking) 0.95f else 0.8f,
                    topK = 20,
                    minP = 0f,
                    repeatPenalty = 1.05f
                )
                val sb = StringBuilder()
                var lastPost = 0L
                var pieces = 0
                val result = engine.generate(prompt, params, TokenCallback { piece ->
                    sb.append(piece)
                    pieces++
                    if (s.thermalGuard && pieces % 24 == 0 && thermalSevere()) {
                        thermalStopped = true
                        return@TokenCallback false
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 70) {
                        lastPost = now
                        val snap = sb.toString()
                        main.post { setMessage(reply.id) { it.copy(raw = snap) } }
                    }
                    !stopRequested
                })

                val finalText = sb.toString()
                when {
                    result == -1 -> setMessage(reply.id) { it.copy(raw = "Модель не загружена.", streaming = false, error = true) }
                    result == -2 -> setMessage(reply.id) { it.copy(raw = "Запрос не помещается в контекст. Увеличьте «Лимит контекста» в настройках или уменьшите документы.", streaming = false, error = true) }
                    result < 0 -> setMessage(reply.id) { it.copy(raw = "Ошибка вычислений движка. Попробуйте перезагрузить модель.", streaming = false, error = true) }
                    else -> {
                        setMessage(reply.id) { it.copy(raw = finalText, streaming = false) }
                        if (thermalStopped) banner = "Генерация остановлена: телефон сильно нагрелся. Дайте ему остыть."
                    }
                }
            } catch (e: CancellationException) {
                setMessage(reply.id) { it.copy(streaming = false) }
                throw e
            } catch (e: Throwable) {
                setMessage(reply.id) { it.copy(raw = "Ошибка: ${e.message ?: e.javaClass.simpleName}", streaming = false, error = true) }
            } finally {
                generating = false
                setMessage(reply.id) { it.copy(streaming = false) }
                persist()
            }
        }
    }

    override fun onCleared() {
        engine.cancel()
        super.onCleared()
    }
}
