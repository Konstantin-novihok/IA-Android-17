package com.iaandroid17.docs

import kotlin.math.ln

data class Chunk(val docName: String, val index: Int, val text: String)

/** Простой поиск по документам без эмбеддингов: BM25 по словам с грубой обрезкой окончаний (для русского и английского). */
object Rag {
    fun chunk(docName: String, text: String, size: Int = 1200, overlap: Int = 150): List<Chunk> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isEmpty()) return emptyList()
        val paragraphs = clean.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = ArrayList<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) {
                parts.add(buf.toString().trim())
                val tail = if (buf.length > overlap) buf.substring(buf.length - overlap) else ""
                buf.setLength(0)
                buf.append(tail)
            }
        }
        for (p in paragraphs) {
            var rest = p
            while (rest.length > size) {          // слишком длинный абзац режем по размеру
                val cut = (size - buf.length).coerceAtLeast(size / 2)
                buf.append(rest, 0, cut)
                rest = rest.substring(cut)
                flush()
            }
            if (buf.length + rest.length + 2 > size) flush()
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(rest)
        }
        if (buf.isNotBlank()) parts.add(buf.toString().trim())
        return parts.distinct().mapIndexed { i, t -> Chunk(docName, i, t) }
    }

    fun terms(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .map { if (it.length > 5) it.substring(0, 5) else it }

    /** Возвращает самые подходящие под [query] фрагменты суммарным размером до [maxChars], в порядке чтения. */
    fun select(chunks: List<Chunk>, query: String, maxChars: Int): List<Chunk> {
        if (chunks.isEmpty() || maxChars <= 0) return emptyList()
        if (chunks.sumOf { it.text.length } <= maxChars) return chunks
        val q = terms(query).toSet()
        val docs = chunks.map { c -> terms(c.text) }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        val n = chunks.size.toDouble()
        val scored = chunks.indices.map { i ->
            val tf = HashMap<String, Int>()
            docs[i].forEach { t -> tf[t] = (tf[t] ?: 0) + 1 }
            var score = 0.0
            for (t in q) {
                val f = tf[t] ?: continue
                val d = (df[t] ?: 0).toDouble()
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                score += idf * (f * 2.5) / (f + 1.5 * (0.25 + 0.75 * docs[i].size / avg))
            }
            i to score
        }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val picked = ArrayList<Int>()
        var used = 0
        for ((i, s) in scored) {
            val len = chunks[i].text.length
            if (used + len > maxChars) continue
            if (s <= 0.0 && picked.isNotEmpty()) break
            picked.add(i); used += len
        }
        if (picked.isEmpty()) picked.add(0)
        return picked.sorted().map { chunks[it] }
    }
}
