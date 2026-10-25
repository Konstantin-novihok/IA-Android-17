package com.iaandroid17.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalQualityTest {
    @Test fun silenceIsRejected() {
        val q = AudioSignalQuality.inspectPcm16(ShortArray(32000))
        assertFalse(q.accepted)
    }
    @Test fun validLevelNonClippedPcmPassesBasicChecks() {
        val audio = ShortArray(48000) { i -> (kotlin.math.sin(i * 0.09) * 6000).toInt().toShort() }
        assertTrue(AudioSignalQuality.inspectPcm16(audio).accepted)
    }
    @Test fun sampleRateMismatchIsRejected() {
        val q = AudioSignalQuality.inspectPcm16(ShortArray(32000) { 4000 }, 48000)
        assertFalse(q.accepted)
    }
}
