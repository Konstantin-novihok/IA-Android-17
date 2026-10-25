package com.iaandroid17.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeakerFbankTest {
    @Test fun producesEightyFiniteFeaturesPerTenMillisecondFrame() {
        val samples = FloatArray(48_000) { i -> (0.2 * sin(2.0 * PI * 440.0 * i / 16_000.0)).toFloat() }
        val frames = SpeakerFbank().compute(samples)
        assertEquals(300, frames.size)
        assertTrue(frames.all { it.size == 80 && it.all(Float::isFinite) })
    }

    @Test fun rejectsTooShortAudio() {
        val failed = runCatching { SpeakerFbank().compute(FloatArray(100)) }
        assertTrue(failed.isFailure)
    }
}
