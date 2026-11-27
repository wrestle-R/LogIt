package dev.logit.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal enum class UsageSignal {
    ScreenOn, ScreenOff, Locked, Unlocked, Foreground, Background, Interaction, Restart
}

internal data class UsageSample(
    val time: Long,
    val signal: UsageSignal,
    val app: String = "",
    val activity: String = "",
)

internal data class UsageSpan(val start: Long, val end: Long, val apps: Set<String>)

internal data class SleepSchedule(
    val bedtime: LocalTime = LocalTime.of(23, 0),
    val wakeTime: LocalTime = LocalTime.of(7, 0),
)

internal data class SleepEstimate(val bedtime: Long, val wakeTime: Long)

/** Reduces Android events to time on screen, without double-counting simultaneous apps. */
internal class UsageTimeline(samples: List<UsageSample>, private val until: Long) {
    private val openings = mutableMapOf<String, MutableList<Long>>()
    private val spans = mutableListOf<UsageSpan>()
    private val interactions = mutableListOf<Long>()
    private val interruptions = mutableListOf<Long>()
    private val restStarts = mutableMapOf<Long, Long>()
    private val evidenceSince = samples.minOfOrNull(UsageSample::time)
    private val hasScreenEvents = samples.any { it.signal == UsageSignal.ScreenOn || it.signal == UsageSignal.ScreenOff }

    init {
        var cursor = evidenceSince ?: until
        var interactive = false
        var locked = false
        var screenStateKnown = false
        var lastInteraction: Long? = null
        var wasUsed = false
        val foreground = mutableSetOf<Pair<String, String>>()
        for (event in samples.sortedBy(UsageSample::time)) {
            if (event.time > until) break
            if (event.time > cursor && interactive && !locked) {
                spans.add(UsageSpan(cursor, event.time, foreground.mapTo(mutableSetOf()) { it.first }))
            }
            cursor = event.time
            when (event.signal) {
                UsageSignal.ScreenOn -> { interactive = true; screenStateKnown = true }
                UsageSignal.ScreenOff -> {
                    if (wasUsed) lastInteraction = event.time
                    interactive = false
                    screenStateKnown = true
                    wasUsed = false
                }
                UsageSignal.Locked -> {
                    if (interactive && wasUsed) lastInteraction = event.time
                    locked = true
                    wasUsed = false
                }
                UsageSignal.Unlocked -> { locked = false; interactive = true; screenStateKnown = true }
                UsageSignal.Foreground -> {
                    foreground.add(event.app to event.activity)
                    if (!screenStateKnown) interactive = true
                    if (!locked && interactive) openings.getOrPut(event.app, ::mutableListOf).add(event.time)
                }
                UsageSignal.Background -> {
                    foreground.remove(event.app to event.activity)
                    if (!screenStateKnown && foreground.isEmpty()) interactive = false
                }
                UsageSignal.Restart -> {
                    interactive = false
                    // A device without a keyguard may send no unlock event after reboot.
                    locked = false
                    screenStateKnown = false
                    wasUsed = false
                    lastInteraction = null
                    foreground.clear()
                    interruptions.add(event.time)
                }
                UsageSignal.Interaction -> Unit
            }
            if (interactive && !locked && event.signal in interactionSignals) {
                if (interactions.lastOrNull() != event.time) {
                    lastInteraction?.let { restStarts[event.time] = it }
                    interactions.add(event.time)
                }
                lastInteraction = event.time
                wasUsed = true
            }
        }
        if (until > cursor && interactive && !locked) {
            spans.add(UsageSpan(cursor, until, foreground.mapTo(mutableSetOf()) { it.first }))
        }
    }

    fun timeUsed(start: Long, end: Long, apps: Set<String> = emptySet()): Long =
        spans.sumOf { span ->
            if (apps.isNotEmpty() && span.apps.none { it in apps }) 0L
            else (minOf(span.end, end) - maxOf(span.start, start)).coerceAtLeast(0)
        }

    fun meetsAppRule(app: String, minutes: Int, start: Long, end: Long): Boolean =
        if (minutes == 0) openings[app]?.any { it in start until end } == true
        else timeUsed(start, end, setOf(app)) >= Duration.ofMinutes(minutes.toLong()).toMillis()

    fun hasFullDayEvidence(dayStart: Long, dayEnd: Long): Boolean = hasScreenEvents &&
        evidenceSince?.let { it <= dayStart } == true && interruptions.none { it in dayStart until dayEnd }

    fun sleep(day: LocalDate, schedule: SleepSchedule, zone: ZoneId, enabledSince: Long): SleepEstimate? {
        val wake = day.atTime(schedule.wakeTime).atZone(zone)
        val bedDay = if (schedule.bedtime >= schedule.wakeTime) day.minusDays(1) else day
        val bed = bedDay.atTime(schedule.bedtime).atZone(zone)
        val earliestBed = maxOf(bed.minusHours(4).toInstant().toEpochMilli(), enabledSince)
        val latestBed = bed.plusHours(4).toInstant().toEpochMilli()
        val earliestWake = wake.minusHours(3).toInstant().toEpochMilli()
        val latestWake = wake.plusHours(5).toInstant().toEpochMilli()
        val midpoint = (bed.toInstant().toEpochMilli() + wake.toInstant().toEpochMilli()) / 2
        return interactions.asSequence().filter { it in earliestWake..latestWake }.mapNotNull { end ->
            val start = restStarts[end] ?: return@mapNotNull null
            if (start !in earliestBed..latestBed || midpoint !in start..end ||
                interruptions.any { it in start..end } ||
                end - start !in minimumRest..maximumRest || until - end < Duration.ofMinutes(5).toMillis()
            ) null else SleepEstimate(start, end)
        }.maxByOrNull { it.wakeTime - it.bedtime }
    }

    private companion object {
        val interactionSignals = setOf(UsageSignal.Unlocked, UsageSignal.Foreground, UsageSignal.Interaction)
        val minimumRest = Duration.ofHours(3).toMillis()
        val maximumRest = Duration.ofHours(16).toMillis()
    }
}
