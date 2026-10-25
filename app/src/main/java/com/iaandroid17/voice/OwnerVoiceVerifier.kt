package com.iaandroid17.voice

/**
 * Compares a fresh live recording with encrypted enrolled CAM++ embeddings.
 *
 * The threshold is deliberately provisional. This returns a speaker-match estimate for an
 * explicit user-initiated check only; it is not sufficient for authorizing commands or actions.
 * Production authorization remains disabled until device/domain-specific FAR/FRR calibration
 * and replay/synthesis detection have been independently validated.
 */
object OwnerVoiceVerifier {
    const val PROVISIONAL_THRESHOLD = 0.55f

    data class Result(
        val acceptedAsLikelyOwner: Boolean,
        val meanSimilarity: Float,
        val bestSimilarity: Float,
        val matchedSamples: Int,
        val totalSamples: Int,
        val threshold: Float,
        val authorizationEligible: Boolean = false
    )

    fun compare(
        candidate: FloatArray,
        enrolled: List<FloatArray>,
        threshold: Float = PROVISIONAL_THRESHOLD
    ): Result {
        require(candidate.size == SpeakerEmbeddingEngine.EMBEDDING_DIM) { "Неверная размерность голоса" }
        require(candidate.all { it.isFinite() }) { "Голосовой вектор содержит недопустимые значения" }
        require(enrolled.isNotEmpty()) { "Сначала извлеките embeddings из голосовых образцов" }
        require(enrolled.all { it.size == candidate.size && it.all(Float::isFinite) }) {
            "Профиль владельца повреждён или имеет несовместимую размерность"
        }
        require(threshold in -1f..1f && threshold.isFinite()) { "Неверный порог сравнения" }

        val scores = enrolled.map { SpeakerEmbeddingMath.cosine(candidate, it) }
        val mean = scores.average().toFloat()
        val best = scores.maxOrNull() ?: error("Нет доступных голосовых образцов")
        val matched = scores.count { it >= threshold }

        // Require both an average match and a majority of independent enrolled samples.
        val majority = enrolled.size / 2 + 1
        return Result(
            acceptedAsLikelyOwner = mean >= threshold && matched >= majority,
            meanSimilarity = mean,
            bestSimilarity = best,
            matchedSamples = matched,
            totalSamples = scores.size,
            threshold = threshold
        )
    }
}
