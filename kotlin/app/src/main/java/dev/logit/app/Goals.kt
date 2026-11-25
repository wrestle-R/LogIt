package dev.logit.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class Goal(val period: String, val target: Int, val since: String) {
    fun fields() = mapOf("period" to period, "target" to target, "since" to since)

    companion object {
        fun parse(period: String?, target: Number?, since: String?): Goal? {
            val amount = target?.toInt() ?: return null
            if (period !in listOf("daily", "weekly") || amount !in 1..999 || target.toDouble() != amount.toDouble()) return null
            return Goal(period!!, amount, since.orEmpty())
        }
    }
}

fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun Habit.goalCount(day: LocalDate): Long {
    val start = if (goal?.period == "weekly") day.monday() else day
    return (0..(day.toEpochDay() - start.toEpochDay())).sumOf { count(start.plusDays(it).toString()) }
}

fun Habit.goalLabel(day: LocalDate): String? = goal?.let {
    "${goalCount(day)} of ${it.target} ${if (it.period == "weekly") "this week" else "today"}"
}

internal fun reminderSlot(now: java.time.ZonedDateTime, start: Int, missed: Boolean): Pair<LocalDate, Boolean>? {
    val minutes = now.hour * 60 + now.minute
    if (minutes < start || minutes >= start + 180) return null
    return now.toLocalDate().minusDays(if (missed) 1 else 0) to missed
}

internal fun Habit.needsReminder(day: LocalDate): Boolean {
    val active = goal ?: return false
    if (active.period == "weekly" && day.dayOfWeek != DayOfWeek.SUNDAY) return false
    return active.since <= day.toString() && goalCount(day) < active.target
}
