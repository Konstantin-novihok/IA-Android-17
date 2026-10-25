package com.iaandroid17.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEmbeddingMathTest {
    @Test fun cosineMatchesExpectedGeometry() {
        assertEquals(1f, SpeakerEmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-5f)
        assertEquals(0f, SpeakerEmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-5f)
        assertEquals(-1f, SpeakerEmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-2f, 0f)), 1e-5f)
    }

    @Test fun averageIsUnitLength() {
        val mean = SpeakerEmbeddingMath.normalizedMean(listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.1f)))
        assertEquals(1f, kotlin.math.sqrt(mean.fold(0.0) { acc, v -> acc + v.toDouble() * v.toDouble() }), 1e-5f)
        assertTrue(mean[0] > mean[1])
    }

    @Test fun calibrationNeedsBothLabelClasses() {
        val trials = listOf(
            SpeakerEmbeddingMath.Trial(.91f, true),
            SpeakerEmbeddingMath.Trial(.87f, true),
            SpeakerEmbeddingMath.Trial(.18f, false),
            SpeakerEmbeddingMath.Trial(.23f, false)
        )
        val c = SpeakerEmbeddingMath.calibrate(trials)
        assertTrue(c.far == 0f && c.frr == 0f)
        assertEquals(4, c.trials)
    }
}
