package com.iaandroid17.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioCaptureContractTest {
    @Test fun pcmQualityRejectsClippedSignal() {
        val clipped = ShortArray(32_000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertFalse(AudioSignalQuality.inspectPcm16(clipped).accepted)
    }

    @Test fun pcmQualityRejectsShortAudio() {
        assertFalse(AudioSignalQuality.inspectPcm16(ShortArray(12_000)).accepted)
    }

    @Test fun qualityReportNeverClaimsIdentityOrLiveness() {
        val voiced = ShortArray(48_000) { i -> (kotlin.math.sin(i * .09) * 6000).toInt().toShort() }
        val report = AudioSignalQuality.inspectPcm16(voiced)
        assertTrue(report.accepted)
        assertTrue(report.reason.contains("личность не оценивались"))
    }
}
