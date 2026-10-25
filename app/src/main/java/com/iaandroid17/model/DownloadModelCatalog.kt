package com.iaandroid17.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DownloadModelEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val preset: Boolean = false
)

/**
 * Editable catalogue of optional GGUF download links. The catalogue stores URLs only,
 * never downloads automatically. Every download remains an explicit user action.
 */
class DownloadModelCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("ia_model_catalog", Context.MODE_PRIVATE)

    fun list(): List<DownloadModelEntry> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadModelEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    enabled = o.optBoolean("enabled", true),
                    preset = o.optBoolean("preset", false)
                )
            }
        }.getOrElse { defaults() }
    }

    fun save(entries: List<DownloadModelEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("url", e.url)
                .put("enabled", e.enabled).put("preset", e.preset))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun resetPresets() = save(defaults())

    fun add(name: String, url: String) {
        val current = list()
        require(url.trim().startsWith("https://", true)) { "Ссылка должна начинаться с HTTPS" }
        require(url.substringBefore('?').endsWith(".gguf", true)) { "Ожидается прямая ссылка на файл .gguf" }
        val id = "custom_" + java.util.UUID.randomUUID().toString()
        save(current + DownloadModelEntry(id, name.trim().ifBlank { url.substringBefore('?').substringAfterLast('/') }, url.trim(), true, false))
    }

    fun remove(id: String) = save(list().filterNot { it.id == id })

    companion object {
        private const val KEY = "entries_v1"
        fun defaults() = listOf(
            DownloadModelEntry(
                "qwen3_8b", "Qwen3 8B · Q4_K_M · основной",
                "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
                true, true
            ),
            DownloadModelEntry(
                "qwen3_4b", "Qwen3 4B · Q4_K_M · баланс",
                "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
                false, true
            ),
            DownloadModelEntry(
                "qwen3_1_7b", "Qwen3 1.7B · Q4_K_M · компактная",
                "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                false, true
            ),
            DownloadModelEntry(
                "qwen25_3b", "Qwen2.5 3B Instruct · Q4_K_M",
                "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                false, true
            )
        )
    }
}
