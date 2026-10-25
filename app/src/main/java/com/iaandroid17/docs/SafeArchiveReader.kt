package com.iaandroid17.docs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Bounded ZIP reader for untrusted user-selected archives.
 *
 * Only returns regular file entries. It never writes archive paths to disk.
 * Limits are cumulative across this call; callers must apply a separate depth
 * budget when recursively inspecting nested archives.
 */
object SafeArchiveReader {
    data class Limits(
        val maxEntries: Int = 500,
        val maxEntryBytes: Int = 12 * 1024 * 1024,
        val maxTotalBytes: Long = 80L * 1024 * 1024,
        val maxCompressionRatio: Long = 250L
    )

    data class Entry(val path: String, val bytes: ByteArray)

    @Throws(IOException::class)
    fun readZip(source: ByteArray, limits: Limits = Limits()): List<Entry> {
        if (source.isEmpty()) throw IOException("Архив пуст")
        val result = ArrayList<Entry>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(source)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (item.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                count++
                if (count > limits.maxEntries)
                    throw IOException("В архиве слишком много файлов (лимит ${limits.maxEntries})")

                val safePath = normalizePath(item.name)
                if (safePath == null) {
                    zip.closeEntry()
                    continue
                }
                val declared = item.size
                val compressed = item.compressedSize
                if (declared > limits.maxEntryBytes)
                    throw IOException("Файл внутри архива превышает лимит: $safePath")
                if (declared > 0 && compressed > 0 &&
                    declared / compressed.coerceAtLeast(1) > limits.maxCompressionRatio)
                    throw IOException("Подозрительная степень сжатия: $safePath")

                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var entryBytes = 0
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    entryBytes += n
                    total += n
                    if (entryBytes > limits.maxEntryBytes)
                        throw IOException("Распакованный файл превышает лимит: $safePath")
                    if (total > limits.maxTotalBytes)
                        throw IOException("Суммарный размер распаковки превышает лимит")
                    out.write(buf, 0, n)
                }
                // Also enforce ratio when metadata was absent/unreliable.
                val ratio = entryBytes.toLong() / compressed.coerceAtLeast(1)
                if (entryBytes > 1024 * 1024 && ratio > limits.maxCompressionRatio)
                    throw IOException("Подозрительная степень сжатия: $safePath")
                result.add(Entry(safePath, out.toByteArray()))
                zip.closeEntry()
            }
        }
        if (result.isEmpty()) throw IOException("В архиве не найдено файлов")
        return result
    }

    /** Reject traversal, absolute paths, drive prefixes and ambiguous empty segments. */
    internal fun normalizePath(path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\\\')) return null
        if (path.contains('\\\\')) return null
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (parts.first().contains(':')) return null
        if (parts.any { it.any { ch -> ch.code < 0x20 } }) return null
        return parts.joinToString("/")
    }
}
