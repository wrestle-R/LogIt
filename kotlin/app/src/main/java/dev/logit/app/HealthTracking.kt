package dev.logit.app

import android.content.Context
import android.os.SystemClock
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class HealthMetric(val title: String, val permission: String, val defaultTarget: Int, val maxTarget: Int) {
    Steps("Steps", HealthPermission.getReadPermission(StepsRecord::class), 8000, 100000),
    Walking("Walking", HealthPermission.getReadPermission(ExerciseSessionRecord::class), 15, 1440),
    Running("Running", HealthPermission.getReadPermission(ExerciseSessionRecord::class), 15, 1440),
    Sleep("Sleep", HealthPermission.getReadPermission(SleepSessionRecord::class), 0, 0);

    fun matchesExercise(type: Int): Boolean = when (this) {
        Walking -> type == ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        Running -> type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
            type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        else -> false
    }
}

internal data class HealthRule(val activityId: String, val target: Int, val since: LocalDate)

internal enum class HealthCheck { CHECKED, NO_RULES, NEEDS_ACCESS, UNAVAILABLE, FAILED }

internal fun workoutMeetsTarget(
    start: Instant, end: Instant, dayStart: Instant, dayEnd: Instant, minutes: Int,
): Boolean = start >= dayStart && start < dayEnd && Duration.between(start, end) >= Duration.ofMinutes(minutes.toLong())

internal fun selectHealthSleep(
    sessions: List<SleepSummary>,
    day: LocalDate,
    now: Instant,
    schedule: SleepSchedule,
): SleepSummary? {
    val bedtimeDay = if (schedule.bedtime >= schedule.wakeTime) day.minusDays(1) else day
    val usualBedtime = bedtimeDay.atTime(schedule.bedtime).atZone(Dates.zone).toInstant().toEpochMilli()
    val usualWake = day.atTime(schedule.wakeTime).atZone(Dates.zone).toInstant().toEpochMilli()
    return sessions.asSequence().filter { summary ->
        summary.bedtime < summary.wakeTime && summary.wakeTime <= now.toEpochMilli() &&
            Instant.ofEpochMilli(summary.wakeTime).atZone(Dates.zone).toLocalDate() == day
    }.maxWithOrNull(compareBy(
        { (minOf(it.wakeTime, usualWake) - maxOf(it.bedtime, usualBedtime)).coerceAtLeast(0) },
        { it.wakeTime - it.bedtime },
        { it.wakeTime },
    ))
}

internal object HealthTracking {
    const val providerPackage = "com.google.android.apps.healthdata"
    private val syncLock = Mutex()
    private var lastCheck: RecentCheck? = null

    private data class RecentCheck(
        val owner: String,
        val rules: Map<HealthMetric, HealthRule>,
        val day: LocalDate,
        val at: Long,
        val result: HealthCheck,
    )

    private fun key(owner: String, metric: HealthMetric) = "health-connect:$owner:${metric.name}"

    fun rule(owner: String, metric: HealthMetric): HealthRule? {
        val raw = Store.prefs.getString(key(owner, metric), null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            HealthRule(value.getString("activity"), value.getInt("target"), LocalDate.parse(value.getString("since")))
        }.getOrNull()
    }

    fun saveRule(owner: String, metric: HealthMetric, rule: HealthRule?) {
        if (Store.trackingOwner() != owner) return
        val pref = key(owner, metric)
        val editor = Store.prefs.edit()
        if (rule == null) editor.remove(pref)
        else editor.putString(pref, JSONObject().put("activity", rule.activityId).put("target", rule.target)
            .put("since", rule.since.toString()).toString())
        editor.apply()
        lastCheck = null
    }

    fun removeActivity(owner: String, id: String) {
        for (metric in HealthMetric.entries) {
            if (rule(owner, metric)?.activityId == id) saveRule(owner, metric, null)
        }
    }

    fun status(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun grants(context: Context): Set<String>? = readOrNull {
        if (status(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        } else emptySet()
    }

    private fun rules(owner: String, sleep: SleepTracking?): Map<HealthMetric, HealthRule> {
        val activities = Store.habits.mapTo(mutableSetOf()) { it.id }
        return HealthMetric.entries.mapNotNull { metric ->
            val rule = rule(owner, metric) ?: return@mapNotNull null
            if (rule.activityId !in activities || (metric == HealthMetric.Sleep && rule.activityId != sleep?.activityId)) {
                return@mapNotNull null
            }
            metric to rule
        }.toMap()
    }

    suspend fun check(context: Context, owner: String, force: Boolean = false): HealthCheck = syncLock.withLock {
        if (Store.trackingOwner() != owner) return@withLock HealthCheck.NO_RULES
        val sleep = PhoneTracking.sleep(owner)
        val rules = rules(owner, sleep)
        if (rules.isEmpty()) return@withLock HealthCheck.NO_RULES
        val now = Instant.now()
        val today = now.atZone(Dates.zone).toLocalDate()
        val timestamp = SystemClock.elapsedRealtime()
        val recent = lastCheck
        if (!force && recent != null && recent.owner == owner && recent.rules == rules && recent.day == today &&
            timestamp - recent.at < 30_000L
        ) return@withLock recent.result

        val result = readAndLog(context, owner, rules, sleep, now, today)
        lastCheck = RecentCheck(owner, rules, today, timestamp, result)
        result
    }

    private suspend fun readAndLog(
        context: Context, owner: String, rules: Map<HealthMetric, HealthRule>, sleep: SleepTracking?,
        now: Instant, today: LocalDate,
    ): HealthCheck {
        if (status(context) != HealthConnectClient.SDK_AVAILABLE) return HealthCheck.UNAVAILABLE
        val client = readOrNull { HealthConnectClient.getOrCreate(context) } ?: return HealthCheck.FAILED
        val grants = readOrNull { client.permissionController.getGrantedPermissions() } ?: return HealthCheck.FAILED
        val eligible = rules.filterKeys { it.permission in grants }
        if (eligible.isEmpty()) return HealthCheck.NEEDS_ACCESS
        val days = listOf(today.minusDays(1), today)
        val range = TimeRangeFilter.between(startOf(days.first().minusDays(1)), now)
        val exercises = if (eligible.keys.any { it == HealthMetric.Walking || it == HealthMetric.Running }) {
            readOrNull { readAll<ExerciseSessionRecord>(client, range) }
        } else emptyList()
        val sessions = if (HealthMetric.Sleep in eligible) {
            readOrNull { readAll<SleepSessionRecord>(client, range) }?.map {
                SleepSummary(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), healthConnect = true)
            }
        } else emptyList()
        var failed = exercises == null || sessions == null
        for ((metric, rule) in eligible) {
            for (day in days.filter { it >= rule.since }) {
                val start = startOf(day)
                val stop = minOf(startOf(day.plusDays(1)), now)
                if (start >= stop) continue
                val met = when (metric) {
                    HealthMetric.Steps -> {
                        val total = readOrNull { steps(client, start, stop) }
                        if (total == null) failed = true
                        total != null && total >= rule.target
                    }
                    HealthMetric.Walking, HealthMetric.Running -> exercises.orEmpty().any {
                        it.endTime <= now && metric.matchesExercise(it.exerciseType) &&
                            workoutMeetsTarget(it.startTime, it.endTime, start, stop, rule.target)
                    }
                    HealthMetric.Sleep -> {
                        val imported = sleep?.let { selectHealthSleep(sessions.orEmpty(), day, now, it.schedule) }
                        if (imported != null && currentActivity(owner, metric, rule) != null && PhoneTracking.sleep(owner) == sleep) {
                            PhoneTracking.saveSummary(owner, rule.activityId, day.toString(), imported)
                        }
                        false
                    }
                }
                if (met) {
                    val habit = currentActivity(owner, metric, rule) ?: continue
                    val date = day.toString()
                    if (OfflineLogQueue.automaticOp(owner, habit, date).id !in habit.appliedOps) {
                        Store.logAutomatic(habit.id, date, owner) { if (it != null) failed = true }
                    }
                }
            }
        }
        return if (failed) HealthCheck.FAILED else HealthCheck.CHECKED
    }

    private fun currentActivity(owner: String, metric: HealthMetric, rule: HealthRule): Habit? {
        if (Store.trackingOwner() != owner || rule(owner, metric) != rule) return null
        return Store.habits.find { it.id == rule.activityId }
    }

    private fun startOf(day: LocalDate) = day.atStartOfDay(Dates.zone).toInstant()

    private suspend fun steps(client: HealthConnectClient, start: Instant, stop: Instant): Long {
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, stop)))
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    private suspend inline fun <reified T : Record> readAll(
        client: HealthConnectClient, range: TimeRangeFilter,
    ): List<T> = buildList {
        var page: String? = null
        do {
            val result = client.readRecords(ReadRecordsRequest(T::class, range, pageToken = page))
            addAll(result.records)
            page = result.pageToken
        } while (page != null)
    }

    private suspend inline fun <T> readOrNull(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
