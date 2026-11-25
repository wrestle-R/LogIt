package dev.logit.app

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTrackingTest {
    private val wakeDay = LocalDate.of(2026, 11, 25)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) =
        day.atTime(hour, minute).atZone(Dates.zone).toInstant()

    @Test fun workoutCountsOnItsStartDateWhenItCrossesMidnight() {
        val dayStart = at(wakeDay, 0)
        val dayEnd = at(wakeDay.plusDays(1), 0)
        val workoutStart = at(wakeDay.minusDays(1), 23, 45)
        val workoutEnd = at(wakeDay, 0, 15)
        val previousDayStart = at(wakeDay.minusDays(1), 0)
        assertTrue(workoutMeetsTarget(workoutStart, workoutEnd, previousDayStart, dayStart, 30))
        assertFalse(workoutMeetsTarget(workoutStart, workoutEnd, previousDayStart, dayStart, 31))
        assertFalse(workoutMeetsTarget(workoutStart, workoutEnd, dayStart, dayEnd, 15))
    }

    @Test fun workoutRulesKeepWalkingAndRunningSeparate() {
        assertTrue(HealthMetric.Running.matchesExercise(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertTrue(HealthMetric.Running.matchesExercise(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        assertFalse(HealthMetric.Running.matchesExercise(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertTrue(HealthMetric.Walking.matchesExercise(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
    }

    @Test fun sleepChoosesTheMainSessionOnTheWakeDate() {
        val main = SleepSummary(at(wakeDay.minusDays(1), 23).toEpochMilli(), at(wakeDay, 5).toEpochMilli(), healthConnect = true)
        val other = SleepSummary(at(wakeDay, 7).toEpochMilli(), at(wakeDay, 14).toEpochMilli(), healthConnect = true)
        val schedule = SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0))
        assertEquals(main, selectHealthSleep(listOf(other, main), wakeDay, at(wakeDay, 15), schedule))
        assertNull(selectHealthSleep(listOf(main), wakeDay, at(wakeDay, 4), schedule))
        assertNull(selectHealthSleep(listOf(main), wakeDay.plusDays(1), at(wakeDay.plusDays(1), 9), schedule))
    }

    @Test fun manualSleepWinsAndAHealthImportWinsOverAnEstimate() {
        val estimate = SleepSummary.parse(1000L, 2000L, false)
        val imported = SleepSummary(1100L, 2100L, healthConnect = true)
        val edited = SleepSummary(1200L, 2200L, edited = true)
        assertTrue(estimate.canBeReplacedBy(imported))
        assertFalse(imported.canBeReplacedBy(estimate!!))
        assertFalse(imported.canBeReplacedBy(imported))
        assertFalse(edited.canBeReplacedBy(imported))
        assertTrue(imported.canBeReplacedBy(edited))
        assertEquals(imported, SleepSummary.parse(1100L, 2100L, false, true))
    }
}
