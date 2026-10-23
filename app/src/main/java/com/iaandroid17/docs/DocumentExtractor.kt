package com.iaandroid17.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class Extracted(val name: String, val text: String, val note: String)

/** Достаёт текст из выбранного пользователем файла. Содержимое считается недоверенными данными. */
object DocumentExtractor {
    private const val MAX_FILE_BYTES = 60L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 3_000_000

    private val textExt = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "yaml", "yml", "ini", "toml", "cfg", "conf",
        "html", "htm", "rtf", "tex", "srt", "sql", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs",
        "go", "rs", "sh", "bat", "ps1", "gradle", "kts", "properties"
    )

    suspend fun extract(context: Context, uri: Uri): Extracted = withContext(Dispatchers.IO) {
        var name = "document"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size > MAX_FILE_BYTES) throw IOException("Файл «$name» больше 60 МБ")
        val ext = name.substringAfterLast('.', "").lowercase()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть «$name»")

        input.use { stream ->
            when (ext) {
                "pdf" -> pdf(context, name, stream)
                "docx" -> zipText(name, stream, "docx") { it == "word/document.xml" }
                "pptx" -> zipText(name, stream, "pptx") { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                "xlsx" -> zipText(name, stream, "xlsx (только текстовые ячейки)") { it == "xl/sharedStrings.xml" }
                "odt", "ods", "odp" -> zipText(name, stream, ext) { it == "content.xml" }
                "doc", "xls", "ppt" -> throw IOException("Старый формат .$ext не поддерживается. Сохраните как .docx/.xlsx/.pptx или PDF")
                else -> plain(name, stream, ext)
            }
        }
    }

    private fun plain(name: String, input: InputStream, ext: String): Extracted {
        val bytes = readLimited(input, 12 * 1024 * 1024)
        val head = bytes.copyOf(minOf(bytes.size, 4096))
        if (ext !in textExt && head.any { it == 0.toByte() })
            throw IOException("Формат «$name» пока не поддерживается")
        var text = decode(bytes)
        if (ext == "html" || ext == "htm") text = stripTags(text)
        return finish(name, text, "")
    }

    private fun decode(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun pdf(context: Context, name: String, input: InputStream): Extracted {
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = PDDocument.load(input)
        try {
            val pages = doc.numberOfPages
            val text = PDFTextStripper().getText(doc)
            if (text.isBlank())
                throw IOException("В PDF «$name» нет текстового слоя (похоже на скан). Распознавание (OCR) пока не реализовано")
            return finish(name, text, "PDF, страниц: $pages")
        } finally {
            doc.close()
        }
    }

    private fun zipText(name: String, input: InputStream, kind: String, accept: (String) -> Boolean): Extracted {
        val sb = StringBuilder()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || !accept(e.name)) continue
                val xml = decode(readLimited(zip, 30 * 1024 * 1024))
                sb.append(xmlToText(xml)).append("\n\n")
                if (sb.length > MAX_TEXT_CHARS) break
            }
        }
        if (sb.isBlank()) throw IOException("В «$name» не найден текст")
        return finish(name, sb.toString(), kind)
    }

    private fun xmlToText(xml: String): String {
        var s = xml
        s = s.replace(Regex("</(w:p|a:p|text:p|text:h|si|row)>"), "\n")
        s = s.replace(Regex("<(w:tab|w:br|text:tab|text:line-break)\\s*/>"), " ")
        s = stripTags(s)
        return s.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n")
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    private fun finish(name: String, text: String, note: String): Extracted {
        val clean = text.replace("\u0000", "").trim()
        if (clean.isBlank()) throw IOException("В «$name» нет текста")
        return if (clean.length > MAX_TEXT_CHARS)
            Extracted(name, clean.substring(0, MAX_TEXT_CHARS), (note + " · обрезано до 3 млн символов").trim(' ', '·'))
        else Extracted(name, clean, note)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
