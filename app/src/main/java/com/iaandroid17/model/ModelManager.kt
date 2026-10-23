package com.iaandroid17.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Хранение, загрузка (с докачкой), проверка и импорт файлов моделей GGUF. */
class ModelManager(private val context: Context) {

    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }

    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) } ?: emptyArray())
            .sortedBy { it.name }

    fun partialSize(fileName: String): Long = File(dir, "$fileName.part").let { if (it.exists()) it.length() else 0L }

    fun freeBytes(): Long = try { StatFs(dir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    fun delete(file: File) {
        file.delete()
        File(file.path + ".part").delete()
    }

    fun isGguf(file: File): Boolean = try {
        file.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'G'.code.toByte() && b[1] == 'G'.code.toByte() &&
                b[2] == 'U'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (e: Exception) { false }

    fun fileNameFromUrl(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "model.gguf" }

    /** Скачивает [url] в [dir]/[fileName]; прерванную загрузку можно продолжить. */
    suspend fun download(url: String, fileName: String, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = File(dir, fileName)
            val part = File(dir, "$fileName.part")
            var existing = if (part.exists()) part.length() else 0L

            var current = url
            var conn: HttpURLConnection? = null
            var redirects = 0
            var code: Int
            while (true) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "IA-Android-17")
                if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
                code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Перенаправление без адреса")
                    current = URL(URL(current), loc).toString()
                    c.disconnect()
                    if (++redirects > 6) throw IOException("Слишком много перенаправлений")
                    continue
                }
                conn = c
                break
            }
            val c = conn!!
            try {
                if (code == 416 && existing > 0) {       // файл уже докачан целиком
                    part.renameTo(target)
                    onProgress(target.length(), target.length())
                    return@withContext target
                }
                if (code != 200 && code != 206) throw IOException("Сервер ответил HTTP $code")
                val append = code == 206
                if (!append) existing = 0
                val total = if (append) {
                    c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                } else c.contentLengthLong
                if (total > 0 && total - existing > freeBytes() - 100L * 1024 * 1024)
                    throw IOException("Недостаточно места на устройстве")

                var done = existing
                var lastReport = 0L
                FileOutputStream(part, append).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) { onProgress(done, total); lastReport = now }
                        }
                    }
                }
                if (total > 0 && part.length() != total) throw IOException("Загрузка оборвалась, нажмите «Скачать» ещё раз — она продолжится")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл")
                onProgress(target.length(), target.length())
                target
            } finally {
                c.disconnect()
            }
        }

    /** Ожидаемая SHA-256 из API Hugging Face (по ссылке вида .../org/repo/resolve/rev/file). */
    suspend fun expectedSha256(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val m = Regex("^https://huggingface\\.co/([^/]+)/([^/]+)/resolve/([^/]+)/([^?]+)").find(url) ?: return@withContext null
            val (org, repo, rev, path) = m.destructured
            val api = "https://huggingface.co/api/models/$org/$repo/tree/$rev"
            val c = URL(api).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "IA-Android-17")
            val text = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("path") == path) return@withContext o.optJSONObject("lfs")?.optString("oid")?.ifBlank { null }
            }
            null
        } catch (e: Exception) { null }
    }

    suspend fun sha256(file: File, onProgress: (done: Long, total: Long) -> Unit): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        var last = 0L
        file.inputStream().use { s ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 400) { onProgress(done, total); last = now }
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Копирует выбранный пользователем .gguf в папку моделей. */
    suspend fun importFrom(uri: Uri, onProgress: (done: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        var name = "imported-model.gguf"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Нужен файл формата .gguf")
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (size > 0 && size > freeBytes() - 100L * 1024 * 1024) throw IOException("Недостаточно места для копирования")
        val part = File(dir, "$name.part")
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(1024 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 400) { onProgress(done, size); last = now }
                }
            }
        } ?: throw IOException("Не удалось открыть файл")
        if (!isGguf(part)) { part.delete(); throw IOException("Это не GGUF-файл") }
        if (target.exists()) target.delete()
        part.renameTo(target)
        target
    }
}
