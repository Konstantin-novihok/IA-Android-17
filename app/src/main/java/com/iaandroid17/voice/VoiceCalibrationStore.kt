package com.iaandroid17.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only labeled similarity scores, not calibration audio or transcripts. */
class VoiceCalibrationStore(context: Context) {
    data class Record(val score: Float, val sameSpeaker: Boolean, val createdAt: Long)

    private val prefs = context.getSharedPreferences("ia_voice_calibration", Context.MODE_PRIVATE)

    fun records(): List<Record> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Record(o.getDouble("score").toFloat(), o.getBoolean("same"), o.getLong("at"))
        }.filter { it.score.isFinite() && it.score in -1f..1f }
    }.getOrDefault(emptyList())

    fun add(score: Float, sameSpeaker: Boolean, now: Long = System.currentTimeMillis()) {
        require(score.isFinite() && score in -1f..1f)
        val records = records() + Record(score, sameSpeaker, now)
        val arr = JSONArray()
        records.takeLast(MAX_RECORDS).forEach { r ->
            arr.put(JSONObject().put("score", r.score.toDouble()).put("same", r.sameSpeaker).put("at", r.createdAt))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() { prefs.edit().remove(KEY).apply() }

    fun report(): Report {
        val all = records()
        val genuine = all.filter { it.sameSpeaker }
        val impostor = all.filterNot { it.sameSpeaker }
        if (genuine.isEmpty() || impostor.isEmpty()) return Report(
            genuineCount = genuine.size, impostorCount = impostor.size,
            threshold = null, far = null, frr = null, eer = null,
            note = "Нужны реальные записи обеих групп: владельца и другого человека."
        )
        val calibration = SpeakerEmbeddingMath.calibrate(all.map { SpeakerEmbeddingMath.Trial(it.score, it.sameSpeaker) })
        val note = if (genuine.size < MIN_PER_CLASS || impostor.size < MIN_PER_CLASS)
            "Предварительный отчёт: менее $MIN_PER_CLASS записей в одной из групп; статистика нестабильна."
        else
            "Исследовательская оценка по этому набору записей; не доказывает безопасность на других людях, устройствах или условиях."
        return Report(genuine.size, impostor.size, calibration.threshold, calibration.far, calibration.frr, calibration.eerEstimate, note)
    }

    data class Report(
        val genuineCount: Int,
        val impostorCount: Int,
        val threshold: Float?,
        val far: Float?,
        val frr: Float?,
        val eer: Float?,
        val note: String
    )

    companion object {
        private const val KEY = "labeled_scores_v1"
        private const val MAX_RECORDS = 500
        private const val MIN_PER_CLASS = 10
    }
}
