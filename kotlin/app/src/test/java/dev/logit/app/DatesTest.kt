package dev.logit.app

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DatesTest {
    @Test fun readsTheCurrentDeviceZoneEveryTime() {
        val previous = TimeZone.getDefault()
        try {
            for (id in listOf("America/Los_Angeles", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                assertEquals(ZoneId.of(id), Dates.zone)
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun dailyBoundaryUsesCalendarMidnightOnShortAndLongDays() {
        val zone = ZoneId.of("America/Los_Angeles")
        for ((date, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val day = LocalDate.parse(date)
            val start = Dates.startOfDay(day, zone)
            val end = Dates.startOfDay(day.plusDays(1), zone)
            assertEquals(hours, Duration.between(start, end).toHours())
            assertEquals(day, end.minusMillis(1).atZone(zone).toLocalDate())
        }
    }
}
