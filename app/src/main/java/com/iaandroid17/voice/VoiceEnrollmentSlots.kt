package com.iaandroid17.voice

/** Slot-selection rules kept pure for deterministic unit testing. */
object VoiceEnrollmentSlots {
    const val MAX_SAMPLES = 3
    fun occupied(existing: Set<Int>): List<Int> = (1..MAX_SAMPLES).filter { it in existing }
    fun firstAvailable(existing: Set<Int>): Int? = (1..MAX_SAMPLES).firstOrNull { it !in existing }
}
