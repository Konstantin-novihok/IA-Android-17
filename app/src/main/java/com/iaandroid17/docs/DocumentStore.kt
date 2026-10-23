package com.iaandroid17.docs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DocInfo(
    val id: String,
    val name: String,
    val chars: Int,
    val chunks: Int,
    val enabled: Boolean,
    val note: String
)

/** Извлечённый текст документов хранится во внутренней памяти приложения (его не видят другие приложения). */
class DocumentStore(context: Context) {
    private val dir = File(context.filesDir, "docs").also { it.mkdirs() }
    private val index = File(dir, "index.json")
    private val chunkCache = HashMap<String, List<Chunk>>()

    @Synchronized
    fun list(): List<DocInfo> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DocInfo(o.getString("id"), o.getString("name"), o.getInt("chars"), o.getInt("chunks"),
                    o.optBoolean("enabled", true), o.optString("note"))
            }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    private fun write(list: List<DocInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("chars", it.chars)
                .put("chunks", it.chunks).put("enabled", it.enabled).put("note", it.note))
        }
        index.writeText(arr.toString())
    }

    @Synchronized
    fun add(name: String, text: String, note: String): DocInfo {
        val id = UUID.randomUUID().toString()
        val chunks = Rag.chunk(name, text)
        File(dir, "$id.txt").writeText(text)
        chunkCache[id] = chunks
        val info = DocInfo(id, name, text.length, chunks.size, true, note)
        write(list() + info)
        return info
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) = write(list().map { if (it.id == id) it.copy(enabled = enabled) else it })

    @Synchronized
    fun remove(id: String) {
        File(dir, "$id.txt").delete()
        chunkCache.remove(id)
        write(list().filter { it.id != id })
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        chunkCache.clear()
    }

    /** Фрагменты всех включённых документов. */
    @Synchronized
    fun enabledChunks(): List<Chunk> =
        list().filter { it.enabled }.flatMap { d ->
            chunkCache.getOrPut(d.id) {
                val f = File(dir, "${d.id}.txt")
                if (f.exists()) Rag.chunk(d.name, f.readText()) else emptyList()
            }
        }
}
