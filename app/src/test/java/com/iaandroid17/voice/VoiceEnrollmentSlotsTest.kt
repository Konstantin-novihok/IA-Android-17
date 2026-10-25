package com.iaandroid17.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceEnrollmentSlotsTest {
    @Test fun startsAtSlotOne() {
        assertEquals(1, VoiceEnrollmentSlots.firstAvailable(emptySet()))
    }

    @Test fun usesFirstHoleRatherThanSampleCount() {
        assertEquals(2, VoiceEnrollmentSlots.firstAvailable(setOf(1, 3)))
    }

    @Test fun preservesStableOccupiedOrder() {
        assertEquals(listOf(1, 3), VoiceEnrollmentSlots.occupied(setOf(3, 1)))
    }

    @Test fun fullProfileHasNoFreeSlot() {
        assertNull(VoiceEnrollmentSlots.firstAvailable(setOf(1, 2, 3)))
    }

    @Test fun ignoresOutOfRangeEntries() {
        assertEquals(listOf(1, 2), VoiceEnrollmentSlots.occupied(setOf(-1, 1, 2, 4)))
        assertEquals(3, VoiceEnrollmentSlots.firstAvailable(setOf(1, 2, 4)))
    }
}
