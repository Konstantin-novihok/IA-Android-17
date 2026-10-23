package com.iaandroid17.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** История диалога в виде JSON во внутренней памяти приложения. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")

    fun load(): List<ChatTurn> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatTurn(if (o.getString("r") == "u") Role.USER else Role.ASSISTANT, o.getString("t"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(turns: List<ChatTurn>) {
        val arr = JSONArray()
        turns.forEach { arr.put(JSONObject().put("r", if (it.role == Role.USER) "u" else "a").put("t", it.text)) }
        file.writeText(arr.toString())
    }

    fun clear() { file.delete() }
}
