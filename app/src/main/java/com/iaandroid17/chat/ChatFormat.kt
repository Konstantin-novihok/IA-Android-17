package com.iaandroid17.chat

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

/** Формат ChatML, которым пользуется Qwen3, и разбор блока <think>. */
object ChatFormat {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    private const val EMPTY_THINK = "<think>\n\n</think>\n\n"

    /** Служебные токены в пользовательском тексте и документах не должны превращаться в настоящие. */
    fun sanitize(text: String): String = text.replace("<|", "< |").replace("|>", "| >")

    fun build(system: String, turns: List<ChatTurn>, thinking: Boolean): String {
        val sb = StringBuilder()
        sb.append(IM_START).append("system\n").append(sanitize(system)).append(IM_END).append('\n')
        for (t in turns) {
            val role = if (t.role == Role.USER) "user" else "assistant"
            sb.append(IM_START).append(role).append('\n').append(sanitize(t.text)).append(IM_END).append('\n')
        }
        sb.append(IM_START).append("assistant\n")
        if (!thinking) sb.append(EMPTY_THINK)
        return sb.toString()
    }

    /**
     * Оставляет столько последних реплик, сколько помещается в [budgetTokens].
     * [count] считает токены строки (в приложении — настоящий токенайзер модели).
     * Если не помещается даже последняя реплика, её текст обрезается.
     */
    fun fit(
        system: String,
        turns: List<ChatTurn>,
        thinking: Boolean,
        budgetTokens: Int,
        count: (String) -> Int
    ): List<ChatTurn> {
        if (turns.isEmpty()) return turns
        val overhead = count(build(system, emptyList(), thinking))
        var remaining = budgetTokens - overhead
        val kept = ArrayList<ChatTurn>()
        for (i in turns.indices.reversed()) {
            val t = turns[i]
            val cost = count(sanitize(t.text)) + 6
            if (cost <= remaining) {
                kept.add(t)
                remaining -= cost
            } else {
                if (kept.isEmpty()) {
                    // последняя реплика одна не помещается — обрезаем её
                    val ratio = (remaining.coerceAtLeast(16)).toDouble() / cost.toDouble()
                    val keepChars = (t.text.length * ratio * 0.9).toInt().coerceAtLeast(32)
                    kept.add(ChatTurn(t.role, t.text.take(keepChars) + "\n[…текст обрезан: не помещается в контекст…]"))
                }
                break
            }
        }
        kept.reverse()
        // диалог должен начинаться с реплики пользователя
        while (kept.size > 1 && kept.first().role == Role.ASSISTANT) kept.removeAt(0)
        return kept
    }

    data class Parsed(val thinking: String, val answer: String, val thinkingOpen: Boolean)

    /** Делит сырой вывод модели на «рассуждения» и ответ; работает и на неполном (потоковом) тексте. */
    fun parse(raw: String): Parsed {
        val open = raw.indexOf("<think>")
        val close = raw.indexOf("</think>")
        if (open < 0) {
            return if (close >= 0) {
                Parsed(raw.substring(0, close).trim(), raw.substring(close + 8).trimStart(), false)
            } else {
                Parsed("", raw, false)
            }
        }
        val before = raw.substring(0, open)
        if (close < 0) return Parsed(raw.substring(open + 7).trim(), before.trim(), true)
        val thinking = raw.substring(open + 7, close).trim()
        val answer = (before + raw.substring(close + 8)).trimStart()
        return Parsed(thinking, answer, false)
    }
}
