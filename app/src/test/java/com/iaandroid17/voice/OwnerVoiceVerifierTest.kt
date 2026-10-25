package com.iaandroid17.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceVerifierTest {
    @Test
    fun matchingSpeakerVectorPassesProvisionalComparisonButNeverAuthorizes() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val enrolled = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.8f, 0.6f, 0f),
            floatArrayOf(0.7f, 0.0f, kotlin.math.sqrt(0.51f))
        )

        val result = OwnerVoiceVerifier.compare(candidate, enrolled)

        assertTrue(result.acceptedAsLikelyOwner)
        assertTrue(result.meanSimilarity >= OwnerVoiceVerifier.PROVISIONAL_THRESHOLD)
        assertFalse(result.authorizationEligible)
        assertTrue(result.matchedSamples >= 2)
    }

    @Test
    fun unrelatedVectorIsRejected() {
        val candidate = floatArrayOf(0f, 1f, 0f)
        val enrolled = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.99f, 0.1f, 0f),
            floatArrayOf(0.98f, 0f, 0.2f)
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
