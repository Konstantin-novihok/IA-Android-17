package com.iaandroid17.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class AntiSpoofingAnalyzerTest {
    @Test
    fun rejectsSilenceAsSuspiciousSignal() {
        val silence = ByteArray(2 * 16_000 * 3)
        val report = AntiSpoofingAnalyzer.inspect(silence)
        assertTrue(report.suspicious)
        assertTrue(report.riskScore >= 0.30f)
        assertTrue(report.reasons.isNotEmpty())
    }

    @Test
    fun producesBoundedReportForRealisticSyntheticTone() {
        val buffer = ByteBuffer.allocate(2 * 16_000 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16_000 * 3) { i ->
            val sample = (sin(2.0 * Math.PI * 220.0 * i / 16_000.0) * 7000.0).toInt().toShort()
            buffer.putShort(sample)
        }
        val report = AntiSpoofingAnalyzer.inspect(buffer.array())
        assertTrue(report.riskScore in 0f..1f)
        assertTrue(report.durationMs == 3000L)
        // A pure tone may look unusual, but the heuristic does not claim it is a replay by itself.
        assertFalse(report.reasons.any { it.contains("ограничение амплитуды") })
    }
}
