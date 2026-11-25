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
