package com.iaandroid17.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OwnerVoiceVerifierTest {
    private fun vector(vararg values: Float): FloatArray =
        FloatArray(SpeakerEmbeddingEngine.EMBEDDING_DIM).also { out ->
            values.copyInto(out, endIndex = minOf(values.size, out.size))
        }

    @Test
    fun matchingSpeakerVectorPassesProvisionalComparisonButNeverAuthorizes() {
        val candidate = vector(1f, 0f, 0f)
        val enrolled = listOf(
            vector(1f, 0f, 0f),
            vector(0.8f, 0.6f, 0f),
            vector(0.7f, 0f, sqrt(0.51f))
        )

        val result = OwnerVoiceVerifier.compare(candidate, enrolled)

        assertTrue(result.acceptedAsLikelyOwner)
        assertTrue(result.meanSimilarity >= OwnerVoiceVerifier.PROVISIONAL_THRESHOLD)
        assertFalse(result.authorizationEligible)
        assertTrue(result.matchedSamples >= 2)
    }

    @Test
    fun unrelatedVectorIsRejected() {
        val candidate = vector(0f, 1f, 0f)
        val enrolled = listOf(
            vector(1f, 0f, 0f),
            vector(0.99f, 0.1f, 0f),
            vector(0.98f, 0f, 0.2f)
        )

        val result = OwnerVoiceVerifier.compare(candidate, enrolled)

        assertFalse(result.acceptedAsLikelyOwner)
        assertFalse(result.authorizationEligible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompatibleEmbeddingDimensions() {
        OwnerVoiceVerifier.compare(floatArrayOf(1f, 0f), listOf(floatArrayOf(1f, 0f, 0f)))
    }
}
