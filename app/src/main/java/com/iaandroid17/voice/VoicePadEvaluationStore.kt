package com.iaandroid17.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only PAD scores and labels, never audio. Test data are diagnostic, not a security certification. */
class VoicePadEvaluationStore(context: Context) {
    data class Trial(val bonaFideScore: Float, val attack: Boolean, val attackKind: String, val timestamp: Long)
    data class Report(
        val genuineCount: Int,
        val attackCount: Int,
        val falseRejectRate: Float?,
        val falseAcceptRate: Float?,
        val note: String
    )

    private val prefs = context.getSharedPreferences("ia_voice_pad_evaluation", Context.MODE_PRIVATE)

    fun add(score: Float, attack: Boolean, attackKind: String = if (attack) "attack" else "live", now: Long = System.currentTimeMillis()) {
        require(score.isFinite() && score in 0f..1f)
        val all = trials().toMutableList()
        all += Trial(score, attack, if (attack) attackKind else "live", now)
        val arr = JSONArray()
        all.takeLast(MAX_TRIALS).forEach {
            arr.put(JSONObject().put("score", it.bonaFideScore.toDouble()).put("attack", it.attack).put("kind", it.attackKind).put("time", it.timestamp))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun trials(): List<Trial> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Trial(obj.getDouble("score").toFloat(), obj.getBoolean("attack"), obj.optString("kind", if (obj.getBoolean("attack")) "attack" else "live"), obj.getLong("time"))
            }.filter { it.bonaFideScore.isFinite() && it.bonaFideScore in 0f..1f }
        }.getOrDefault(emptyList())
    }

    fun report(threshold: Float = AntiSpoofingEngine.DEFAULT_BONAFIDE_THRESHOLD): Report {
        val data = trials()
        val genuine = data.filterNot { it.attack }
        val attacks = data.filter { it.attack }
        val frr = if (genuine.isEmpty()) null else genuine.count { it.bonaFideScore < threshold }.toFloat() / genuine.size
        val far = if (attacks.isEmpty()) null else attacks.count { it.bonaFideScore >= threshold }.toFloat() / attacks.size
        val note = if (genuine.size < 5 || attacks.size < 5) {
            "Для предварительной оценки соберите хотя бы 5 живых и 5 атакующих записей. Это малый тестовый набор, не сертификация."
        } else {
            "На текущем наборе при пороге $threshold. Результат зависит от микрофона, воспроизведения, языка и типа синтеза; не используйте его как единственное средство защиты."
        }
        return Report(genuine.size, attacks.size, frr, far, note)
    }

    fun clear() { prefs.edit().remove(KEY).apply() }

    companion object {
        private const val KEY = "trials_json"
        private const val MAX_TRIALS = 300
    }
}
