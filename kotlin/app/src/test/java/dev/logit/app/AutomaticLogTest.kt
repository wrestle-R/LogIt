package dev.logit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticLogTest {
    private val day = "2026-11-25"
    private val habit = Habit("walk", "Walk")

    @Test
    fun retryAfterUndoDoesNotRepeatTheAutomaticLog() {
        val automatic = OfflineLogQueue.automaticOp("owner", habit, day)
        val undo = OfflineLogQueue.reversal(listOf(automatic), automatic.id)
        val restored = OfflineLogQueue.project(listOf(habit), listOf(automatic, undo)).single()
        val retry = OfflineLogQueue.automaticOp("owner", restored, day)

        assertEquals(0L, restored.count(day))
        assertEquals(automatic.id, retry.id)
        assertEquals(0L, OfflineLogQueue.applyToHabit(restored, retry).count(day))
    }

    @Test
    fun deterministicKeysAreScopedToTheActivityAndDay() {
        val automatic = OfflineLogQueue.automaticOp("owner", habit, day)
        val nextDay = OfflineLogQueue.automaticOp("owner", habit, "2026-11-26")
        val otherHabit = OfflineLogQueue.automaticOp("owner", habit.copy(id = "read"), day)

        val projected = OfflineLogQueue.project(listOf(habit), listOf(automatic, nextDay, automatic)).single()

        assertEquals(1L, projected.count(day))
        assertEquals(1L, projected.count(nextDay.day))
        assertNotEquals(automatic.id, otherHabit.id)
    }

    @Test
    fun existingManualCompletionConsumesTheAutomaticEventEvenIfUndoneBeforeSync() {
        val withManual = habit.copy(completions = listOf(day))
        val automatic = OfflineLogQueue.automaticOp("owner", withManual, day)
        val result = OfflineLogQueue.applyToHabit(habit, automatic)
        val retry = OfflineLogQueue.automaticOp("owner", result, day)

        assertEquals(0L, result.count(day))
        assertEquals(0L, OfflineLogQueue.applyToHabit(result, retry).count(day))
    }

    @Test
    fun undoOfAnOptimisticAutomaticLogPreservesAManualCompletionFromAnotherDevice() {
        val automatic = OfflineLogQueue.automaticOp("owner", habit, day)
        val undo = OfflineLogQueue.reversal(listOf(automatic), automatic.id)
        val remote = habit.copy(counts = mapOf(day to 1L))
        val result = OfflineLogQueue.project(listOf(remote), listOf(automatic, undo)).single()

        assertEquals(1L, result.count(day))
    }

    @Test
    fun undoingTheSameAutomaticLogOnTwoDevicesOnlyDecrementsOnce() {
        val automatic = OfflineLogQueue.automaticOp("owner", habit, day)
        val manual = LogOp("manual", "owner", habit.id, day, 1)
        val firstUndo = OfflineLogQueue.reversal(listOf(automatic), automatic.id)
        val secondUndo = OfflineLogQueue.reversal(listOf(automatic), automatic.id)
        val logs = listOf(automatic, manual, firstUndo, secondUndo)

        assertEquals(1L, OfflineLogQueue.project(listOf(habit), logs).single().count(day))
    }

    @Test
    fun queuedLogsForMissingActivitiesDoNotAffectOtherActivities() {
        val deleted = OfflineLogQueue.automaticOp("owner", habit.copy(id = "deleted"), day)
        val current = OfflineLogQueue.automaticOp("owner", habit, day)

        val projected = OfflineLogQueue.project(listOf(habit), listOf(deleted, current)).single()

        assertEquals(habit.id, projected.id)
        assertEquals(1L, projected.count(day))
    }

    @Test
    fun manualSleepEditsTakePrecedenceOverAutomaticEstimates() {
        val estimate = SleepSummary(1000L, 2000L)
        val manual = SleepSummary(1100L, 2200L, edited = true)

        assertTrue(estimate.canBeReplacedBy(manual))
        assertFalse(manual.canBeReplacedBy(estimate))
        assertTrue(manual.canBeReplacedBy(manual.copy(wakeTime = 2300L)))
    }
}
