package dev.logit.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GoalsTest {
    @Test fun weeklyGoalCountsRepeatedLogsAndResetsOnMonday() {
        val habit = Habit("read", "Read", counts = mapOf("2026-11-22" to 4, "2026-11-23" to 3),
            goal = Goal("weekly", 3, "2026-11-23"))
        assertEquals("3 of 3 this week", habit.goalLabel(LocalDate.parse("2026-11-29")))
        assertEquals("0 of 3 this week", habit.goalLabel(LocalDate.parse("2026-11-30")))
    }

    @Test fun backdatedLogAndUndoChangeTheOriginalDaysProgress() {
        val habit = Habit("read", "Read", counts = mapOf("2026-11-24" to 1),
            goal = Goal("daily", 2, "2026-11-23"))
        val yesterday = "2026-11-24"
        val logged = OfflineLogQueue.delta(habit, yesterday, 1)
        assertEquals(2L, logged.goalCount(LocalDate.parse(yesterday)))
        assertEquals(0L, logged.goalCount(LocalDate.parse("2026-11-25")))
        val undone = OfflineLogQueue.delta(logged, yesterday, -1)
        assertEquals(1L, undone.goalCount(LocalDate.parse(yesterday)))
    }

    @Test fun mondayMorningChecksTheWeekThatJustEnded() {
        val now = java.time.ZonedDateTime.parse("2026-11-30T09:05:00+05:30[Asia/Kolkata]")
        val (day, missed) = reminderDate(now)!!
        val habit = Habit("read", "Read", counts = mapOf("2026-11-23" to 2),
            goal = Goal("weekly", 3, "2026-11-23"))
        assertTrue(missed)
        assertEquals(LocalDate.parse("2026-11-29"), day)
        assertTrue(habit.needsReminder(day))
        assertFalse(OfflineLogQueue.delta(habit, "2026-11-24", 1).needsReminder(day))
        assertFalse(habit.needsReminder(day.minusDays(1)))
        assertFalse(habit.copy(goal = Goal("weekly", 3, "2026-11-30")).needsReminder(day))
        assertNull(reminderDate(now.minusHours(1)))
    }

    @Test fun releaseComparisonRejectsPrereleasesAndDowngrades() {
        assertTrue(newerRelease("v0.10.0", "0.9.9"))
        assertFalse(newerRelease("v0.9.9", "0.10.0"))
        assertFalse(newerRelease("v0.3.2", "0.3.2"))
        assertFalse(newerRelease("v1.0.0-rc.1", "0.3.2"))
    }

    @Test fun landscapeHeatmapsCanSplitAndTallHeatmapsStack() {
        val wide = WidgetLayout.fit(340, 230, 3, false)
        assertEquals(2, wide.columns)
        val tall = WidgetLayout.fit(340, 460, 3, false)
        assertEquals(1, tall.columns)
        assertEquals(3, tall.visible)
    }
}
