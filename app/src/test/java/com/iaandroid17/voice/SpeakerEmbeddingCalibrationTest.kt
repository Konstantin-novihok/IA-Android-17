package com.iaandroid17.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEmbeddingCalibrationTest {
    @Test
    fun calibrationUsesBothGenuineAndImpostorTrials() {
        val trials = listOf(
            SpeakerEmbeddingMath.Trial(0.91f, true),
            SpeakerEmbeddingMath.Trial(0.87f, true),
            SpeakerEmbeddingMath.Trial(0.82f, true),
            SpeakerEmbeddingMath.Trial(0.76f, true),
            SpeakerEmbeddingMath.Trial(0.21f, false),
            SpeakerEmbeddingMath.Trial(0.18f, false),
            SpeakerEmbeddingMath.Trial(0.33f, false),
            SpeakerEmbeddingMath.Trial(0.45f, false)
        )

        val result = SpeakerEmbeddingMath.calibrate(trials)

        assertEquals(trials.size, result.trials)
        assertTrue(result.threshold > 0.45f)
        assertEquals(0f, result.far, 0.0001f)
        assertEquals(0f, result.frr, 0.0001f)
        assertEquals(0f, result.eerEstimate, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calibrationRejectsUnlabeledSingleClassData() {
        SpeakerEmbeddingMath.calibrate(listOf(
            SpeakerEmbeddingMath.Trial(0.9f, true),
            SpeakerEmbeddingMath.Trial(0.8f, true)
        ))
    }
}
