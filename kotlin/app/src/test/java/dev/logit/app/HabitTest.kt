package dev.logit.app

import org.junit.Assert.*
import org.junit.Test

class HabitTest {
    @Test
    fun legacyAndCountsMergeWithoutDoubleCounting() {
        val h =
            Habit(
                "a",
                "Read",
                listOf("2026-11-14", "2026-11-14", "2026-11-13"),
                mapOf("2026-11-14" to 3),
            )
        assertEquals(3L, h.count("2026-11-14"))
        assertEquals(4L, h.total())
    }

    @Test
    fun legacyOverwriteCannotEraseCountedHistory() {
        val h = Habit("a", "Read", listOf("2026-11-13"), mapOf("2026-11-14" to 2))
        assertEquals(2L, h.count("2026-11-14"))
        assertEquals(3L, h.total())
    }

    @Test
    fun missingDayAndZeroCountsAreNotCompletions() {
        val h = Habit("a", "Read", counts = mapOf("2026-11-14" to 0))
        assertEquals(0L, h.count("2026-11-13"))
        assertEquals(0L, h.total())
    }
}
