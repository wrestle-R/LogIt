package dev.logit.app

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTimelineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 25)

    private fun at(hour: Int, minute: Int = 0, previous: Boolean = false): Long =
        (if (previous) day.minusDays(1) else day).atTime(hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun sample(hour: Int, minute: Int, signal: UsageSignal, app: String = "", previous: Boolean = false) =
        UsageSample(at(hour, minute, previous), signal, app, app)

    @Test
    fun overlappingAppsCountOnceAndLockStopsScreenAndAppTime() {
        val timeline = UsageTimeline(listOf(
            sample(9, 0, UsageSignal.ScreenOn),
            sample(9, 0, UsageSignal.Unlocked),
            sample(9, 0, UsageSignal.Foreground, "journal"),
            sample(9, 2, UsageSignal.Foreground, "chat"),
            sample(9, 4, UsageSignal.Background, "journal"),
            sample(9, 5, UsageSignal.Locked),
            sample(9, 6, UsageSignal.ScreenOff),
        ), at(10))
        assertEquals(5 * 60_000L, timeline.timeUsed(at(8), at(10)))
        assertEquals(4 * 60_000L, timeline.timeUsed(at(8), at(10), setOf("journal")))
        assertEquals(5 * 60_000L, timeline.timeUsed(at(8), at(10), setOf("journal", "chat")))
        assertEquals(2 * 60_000L, timeline.timeUsed(at(9, 3), at(10), setOf("journal", "chat")))
    }

    @Test
    fun activeAppAcrossMidnightIsClippedToEachDay() {
        val timeline = UsageTimeline(listOf(
            sample(23, 58, UsageSignal.Unlocked, previous = true),
            sample(23, 58, UsageSignal.Foreground, "journal", previous = true),
            sample(0, 3, UsageSignal.Background, "journal"),
            sample(0, 6, UsageSignal.ScreenOff),
        ), at(8))
        assertEquals(2 * 60_000L, timeline.timeUsed(at(0, previous = true), at(0), setOf("journal")))
        assertEquals(3 * 60_000L, timeline.timeUsed(at(0), at(8), setOf("journal")))
        assertTrue(timeline.hasFullDayEvidence(at(0), at(8)))
    }

    @Test
    fun notificationWakesDoNotEndTheOvernightEstimate() {
        val timeline = UsageTimeline(listOf(
            sample(22, 47, UsageSignal.Unlocked, previous = true),
            sample(22, 50, UsageSignal.Locked, previous = true),
            sample(22, 50, UsageSignal.ScreenOff, previous = true),
            sample(2, 0, UsageSignal.ScreenOn),
            sample(2, 1, UsageSignal.ScreenOff),
            sample(6, 55, UsageSignal.ScreenOn),
            sample(7, 3, UsageSignal.Unlocked),
            sample(7, 5, UsageSignal.Foreground, "journal"),
        ), at(7, 10))
        assertEquals(SleepEstimate(at(22, 50, previous = true), at(7, 3)),
            timeline.sleep(day, SleepSchedule(), zone, 0))
    }

    @Test
    fun noSleepIsInventedBeforeFirstUseOrAfterRestart() {
        val untilFirstUse = listOf(
            sample(23, 1, UsageSignal.Unlocked, previous = true),
            sample(23, 20, UsageSignal.ScreenOff, previous = true),
            sample(7, 5, UsageSignal.ScreenOn),
        )
        assertNull(UsageTimeline(untilFirstUse, at(7, 30)).sleep(day, SleepSchedule(), zone, 0))
        val reboot = untilFirstUse + sample(4, 0, UsageSignal.Restart) + sample(7, 8, UsageSignal.Unlocked)
        val timeline = UsageTimeline(reboot, at(9))
        assertNull(timeline.sleep(day, SleepSchedule(), zone, 0))
        assertFalse(timeline.hasFullDayEvidence(at(0), at(9)))
    }

    @Test
    fun configuredScheduleAndTrackingStartLimitWhichGapCanBeRecorded() {
        val timeline = UsageTimeline(listOf(
            sample(3, 20, UsageSignal.Unlocked),
            sample(3, 30, UsageSignal.ScreenOff),
            sample(11, 30, UsageSignal.Unlocked),
        ), at(12))
        val lateSchedule = SleepSchedule(java.time.LocalTime.of(3, 30), java.time.LocalTime.of(11, 30))
        assertEquals(SleepEstimate(at(3, 30), at(11, 30)), timeline.sleep(day, lateSchedule, zone, 0))
        assertNull(timeline.sleep(day, lateSchedule, zone, at(4)))
        assertNull(timeline.sleep(day, SleepSchedule(), zone, 0))
    }
}
