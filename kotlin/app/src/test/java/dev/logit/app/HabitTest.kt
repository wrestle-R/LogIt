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

class OfflineProjectionTest {
    @org.junit.Test
    fun queuedLogsAndUndoProjectOverCachedData() {
        val h = Habit("a", "Read")
        val ops =
            listOf(
                LogOp("1", "u", "a", "2026-11-14", 1),
                LogOp("2", "u", "a", "2026-11-14", 1),
                LogOp("3", "u", "a", "2026-11-14", -1),
            )
        org.junit.Assert.assertEquals(
            1L,
            OfflineLogQueue.project(listOf(h), ops).single().count("2026-11-14"),
        )
    }

    @org.junit.Test
    fun acknowledgedServerOperationDoesNotDoubleCount() {
        val h =
            Habit(
                "a",
                "Read",
                counts = mapOf("2026-11-14" to 2),
                appliedOps = setOf("already-applied"),
            )
        val ops = listOf(LogOp("already-applied", "u", "a", "2026-11-14", 1))
        org.junit.Assert.assertEquals(
            2L,
            OfflineLogQueue.project(listOf(h), ops).single().count("2026-11-14"),
        )
    }

    @org.junit.Test
    fun undoAfterMidnightTargetsOriginalDay() {
        val h = Habit("a", "Read", counts = mapOf("2026-11-14" to 1, "2026-11-15" to 2))
        val after = OfflineLogQueue.delta(h, "2026-11-14", -1)
        org.junit.Assert.assertEquals(0L, after.count("2026-11-14"))
        org.junit.Assert.assertEquals(2L, after.count("2026-11-15"))
    }
}

class ActivityColourTest {
    @org.junit.Test fun prefersTheFirstUnusedColour() {
        org.junit.Assert.assertEquals(2, leastUsedColour(listOf(0, 0, 1, 3), 4))
    }

    @org.junit.Test fun reusesTheLeastUsedColourOnceThePaletteIsFull() {
        org.junit.Assert.assertEquals(1, leastUsedColour(listOf(0, 0, 1, 2, 2, 3), 4))
    }
}
