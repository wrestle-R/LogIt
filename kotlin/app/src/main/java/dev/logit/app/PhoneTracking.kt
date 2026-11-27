package dev.logit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal data class AppLogRule(val activityId: String, val app: UsageApp, val minutes: Int, val since: Long)
internal data class ScreenLimit(
    val id: String,
    val name: String,
    val apps: Set<String>,
    val minutes: Int,
    val activityId: String?,
    val since: Long,
) {
    companion object {
        const val totalId = "total"
        fun newId() = UUID.randomUUID().toString()
    }
}

internal data class SleepTracking(
    val activityId: String,
    val schedule: SleepSchedule,
    val since: Long,
    val goalMinutes: Int = defaultGoalMinutes,
    val estimateFromPhone: Boolean = true,
) {
    companion object {
        const val defaultGoalMinutes = 480
        val goalRange = 60..960
    }
}
internal data class ScreenReading(val minutes: Int, val day: String)

internal object PhoneTracking {
    private const val periodicWork = "logit-phone-usage"
    private const val refreshWork = "logit-phone-usage-refresh"
    private const val notificationChannel = "logit-screen-time"
    private val main = Handler(Looper.getMainLooper())
    private val inFlightSleep = mutableSetOf<String>()
    private val processing = Any()
    var updated: (() -> Unit)? = null

    private fun key(owner: String, suffix: String) = "usage:$owner:$suffix"

    private fun json(owner: String, suffix: String) = runCatching {
        JSONObject(Store.prefs.getString(key(owner, suffix), "{}").orEmpty())
    }.getOrDefault(JSONObject())

    private fun array(owner: String, suffix: String) = runCatching {
        JSONArray(Store.prefs.getString(key(owner, suffix), "[]").orEmpty())
    }.getOrDefault(JSONArray())

    fun sleep(owner: String): SleepTracking? {
        val value = json(owner, "sleep")
        val habit = value.optString("activity")
        return habit.takeIf(String::isNotBlank)?.let {
            SleepTracking(
                it, SleepSchedule(
                    runCatching { LocalTime.parse(value.optString("bedtime")) }.getOrDefault(LocalTime.of(23, 0)),
                    runCatching { LocalTime.parse(value.optString("wake")) }.getOrDefault(LocalTime.of(7, 0)),
                ), value.optLong("since"),
                value.optInt("goalMinutes", SleepTracking.defaultGoalMinutes)
                    .takeIf { minutes -> minutes in SleepTracking.goalRange } ?: SleepTracking.defaultGoalMinutes,
                value.optBoolean("estimateFromPhone", true),
            )
        }
    }

    fun saveSleep(owner: String, settings: SleepTracking?) {
        val editor = Store.prefs.edit()
        if (settings == null) {
            HealthTracking.saveRule(owner, HealthMetric.Sleep, null)
            editor.remove(key(owner, "sleep"))
        } else {
            editor.putString(key(owner, "sleep"), JSONObject()
                .put("activity", settings.activityId).put("since", settings.since)
                .put("goalMinutes", settings.goalMinutes).put("estimateFromPhone", settings.estimateFromPhone)
                .put("bedtime", settings.schedule.bedtime.toString()).put("wake", settings.schedule.wakeTime.toString())
                .toString())
        }
        editor.remove(key(owner, "last-check")).apply()
        schedule()
        SleepWidget.refresh(Store.context)
    }

    fun appRules(owner: String): List<AppLogRule> {
        val array = array(owner, "app-rules")
        return (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            AppLogRule(value.getString("activity"), UsageApp(value.getString("app"), value.getString("label")),
                value.getInt("minutes"), value.getLong("since"))
        }
    }

    fun saveAppRules(owner: String, rules: List<AppLogRule>) {
        val values = rules.map { rule -> JSONObject()
            .put("activity", rule.activityId).put("app", rule.app.id).put("label", rule.app.label)
            .put("minutes", rule.minutes).put("since", rule.since) }
        Store.prefs.edit().putString(key(owner, "app-rules"), JSONArray(values).toString())
            .remove(key(owner, "last-check")).apply()
        schedule()
    }

    fun limits(owner: String): List<ScreenLimit> {
        val array = array(owner, "limits")
        return (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val apps = value.getJSONArray("apps")
            ScreenLimit(value.getString("id"), value.getString("name"),
                (0 until apps.length()).map { apps.getString(it) }.toSet(), value.getInt("minutes"),
                value.optString("activity").ifBlank { null }, value.getLong("since"))
        }
    }

    fun saveLimits(owner: String, limits: List<ScreenLimit>) {
        val values = limits.map { limit -> JSONObject()
            .put("id", limit.id).put("name", limit.name).put("apps", JSONArray(limit.apps.toList()))
            .put("minutes", limit.minutes).put("activity", limit.activityId.orEmpty()).put("since", limit.since) }
        Store.prefs.edit().putString(key(owner, "limits"), JSONArray(values).toString())
            .remove(key(owner, "last-check")).apply()
        schedule()
    }

    fun reading(owner: String, limitId: String, yesterday: Boolean = false): ScreenReading? {
        val value = json(owner, if (yesterday) "yesterday-readings" else "screen-readings")
            .optJSONObject(limitId) ?: return null
        return ScreenReading(value.getInt("minutes"), value.getString("day"))
    }

    fun automaticEnabled(owner: String) = Store.prefs.getBoolean(key(owner, "automatic"), false)

    fun setAutomatic(owner: String, enabled: Boolean) {
        Store.prefs.edit().putBoolean(key(owner, "automatic"), enabled)
            .remove(key(owner, "access-since"))
            .remove(key(owner, "last-check"))
            .remove(key(owner, "screen-readings"))
            .remove(key(owner, "yesterday-readings"))
            .apply()
        schedule()
    }

    fun syncSleep() {
        val owner = Store.trackingOwner() ?: return
        sleep(owner)?.let { syncSummaries(owner, it.activityId) }
    }

    fun schedule() {
        val owner = Store.trackingOwner()
        val manager = WorkManager.getInstance(Store.context)
        if (owner == null || !automaticEnabled(owner) || (sleep(owner)?.estimateFromPhone != true && appRules(owner).isEmpty() && limits(owner).isEmpty())) {
            manager.cancelUniqueWork(periodicWork)
            manager.cancelUniqueWork(refreshWork)
            return
        }
        manager.enqueueUniquePeriodicWork(periodicWork, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PhoneUsageWorker>(15, TimeUnit.MINUTES).build())
        refresh()
    }

    fun refresh() {
        val owner = Store.trackingOwner() ?: return
        if (!automaticEnabled(owner) || (sleep(owner)?.estimateFromPhone != true && appRules(owner).isEmpty() && limits(owner).isEmpty())) return
        WorkManager.getInstance(Store.context).enqueueUniqueWork(refreshWork, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhoneUsageWorker>().build())
    }

    fun removeActivity(owner: String, id: String) {
        HealthTracking.removeActivity(owner, id)
        if (sleep(owner)?.activityId == id) saveSleep(owner, null)
        Store.prefs.edit().remove(key(owner, "pending-sleep:$id")).apply()
        saveAppRules(owner, appRules(owner).filterNot { it.activityId == id })
        saveLimits(owner, limits(owner).map { if (it.activityId == id) it.copy(activityId = null) else it })
    }

    private fun now() = System.currentTimeMillis()
    private fun startOf(day: LocalDate) = day.atStartOfDay(Dates.zone).toInstant().toEpochMilli()
    private fun minutes(milliseconds: Long) = TimeUnit.MILLISECONDS.toMinutes(milliseconds).toInt()

    fun run(context: Context) = synchronized(processing) {
        val owner = Store.trackingOwner() ?: return@synchronized
        if (!automaticEnabled(owner)) return@synchronized
        val sleep = sleep(owner)?.takeIf { it.estimateFromPhone }
        val rules = appRules(owner)
        val limits = limits(owner)
        if (sleep == null && rules.isEmpty() && limits.isEmpty()) return@synchronized
        val last = Store.prefs.getLong(key(owner, "last-check"), 0)
        val end = now()
        if (end - last in 0 until 10_000L) return@synchronized
        if (!AndroidUsage.allowed(context)) {
            // A day with a known break in access does not qualify as an under-limit day.
            Store.prefs.edit().remove(key(owner, "access-since")).apply()
            main.post { updated?.invoke() }
            return@synchronized
        }
        val today = Instant.ofEpochMilli(end).atZone(Dates.zone).toLocalDate()
        val historyStart = today.minusDays(3)
        val timeline = AndroidUsage.read(context, startOf(historyStart), end) ?: return@synchronized
        val accessSince = Store.prefs.getLong(key(owner, "access-since"), end)
        Store.prefs.edit().putLong(key(owner, "access-since"), accessSince)
            .putLong(key(owner, "last-check"), end).apply()

        val readings = JSONObject()
        val yesterday = JSONObject()
        for (daysAgo in 2L downTo 0L) {
            if (Store.trackingOwner() != owner || !automaticEnabled(owner) || !AndroidUsage.allowed(context)) return@synchronized
            val day = today.minusDays(daysAgo)
            val start = startOf(day)
            val stop = minOf(startOf(day.plusDays(1)), end)
            val dayKey = day.toString()
            for (rule in rules) {
                val ruleStart = maxOf(start, rule.since, accessSince)
                if (ruleStart < stop && minutes(timeline.timeUsed(ruleStart, stop, setOf(rule.app.id))) >= rule.minutes) {
                    main.post {
                        if (automaticEnabled(owner) && rule in appRules(owner)) enqueueLog(owner, rule.activityId, dayKey)
                    }
                }
            }
            for (limit in limits) {
                if (limit.apps.isEmpty() && !AndroidUsage.screenEventsSupported) continue
                val time = timeline.timeUsed(start, stop, limit.apps)
                val threshold = TimeUnit.MINUTES.toMillis(limit.minutes.toLong())
                if (daysAgo == 0L) {
                    readings.put(limit.id, JSONObject().put("day", dayKey).put("minutes", minutes(time)))
                    if (time >= threshold) notifyAtLimit(context, owner, dayKey, limit)
                } else if (start >= maxOf(accessSince, limit.since) &&
                    timeline.hasFullDayEvidence(start, stop)) {
                    if (daysAgo == 1L) {
                        yesterday.put(limit.id, JSONObject().put("day", dayKey).put("minutes", minutes(time)))
                    }
                    if (time < threshold) limit.activityId?.let { activity ->
                        main.post {
                            if (automaticEnabled(owner) && limit in limits(owner)) enqueueLog(owner, activity, dayKey)
                        }
                    }
                }
            }
            if (sleep != null && AndroidUsage.screenEventsSupported) {
                val estimate = timeline.sleep(day, sleep.schedule, Dates.zone, maxOf(sleep.since, accessSince))
                if (estimate != null) {
                    val summary = SleepSummary(estimate.bedtime, estimate.wakeTime)
                    main.post {
                        if (Store.trackingOwner() == owner && automaticEnabled(owner) && sleep(owner) == sleep &&
                            pendingSummary(owner, sleep.activityId, dayKey) == null &&
                            Store.habits.find { it.id == sleep.activityId }?.sleepSummaries?.get(dayKey) != summary
                        ) saveSummary(owner, sleep.activityId, dayKey, summary)
                    }
                }
            }
        }
        Store.prefs.edit().putString(key(owner, "screen-readings"), readings.toString())
            .putString(key(owner, "yesterday-readings"), yesterday.toString()).apply()
        main.post {
            val active = sleep(owner)
            if (active != null && Store.trackingOwner() == owner) syncSummaries(owner, active.activityId)
            updated?.invoke()
        }
    }

    private fun enqueueLog(owner: String, activity: String, day: String) {
        if (Store.trackingOwner() == owner) Store.logAutomatic(activity, day, owner) { }
    }

    private fun pending(owner: String, id: String) = json(owner, "pending-sleep:$id")
    private fun pendingSummary(owner: String, id: String, day: String): SleepSummary? =
        pending(owner, id).optJSONObject(day)?.let {
            SleepSummary.parse(it.opt("bed"), it.opt("wake"), it.opt("edited"), it.opt("healthConnect"))
        }

    fun summary(owner: String, habit: Habit?, day: String): SleepSummary? {
        if (habit == null) return null
        val saved = habit.sleepSummaries[day]
        val pending = pendingSummary(owner, habit.id, day)
        return pending?.takeIf { saved.canBeReplacedBy(it) } ?: saved
    }

    fun saveSummary(owner: String, id: String, day: String, value: SleepSummary) {
        if (Store.trackingOwner() != owner || sleep(owner)?.activityId != id) return
        val habit = Store.habits.find { it.id == id } ?: return
        val previous = summary(owner, habit, day)
        if (!previous.canBeReplacedBy(value)) return
        val saved = pending(owner, id).put(day, JSONObject()
            .put("bed", value.bedtime).put("wake", value.wakeTime).put("edited", value.edited)
            .put("healthConnect", value.healthConnect))
        if (!Store.prefs.edit().putString(key(owner, "pending-sleep:$id"), saved.toString()).commit()) return
        syncSummaries(owner, id)
        updated?.invoke()
        SleepWidget.refresh(Store.context)
    }

    private fun enqueueSleepGoal(owner: String, id: String, day: String, summary: SleepSummary): Boolean {
        val settings = sleep(owner)?.takeIf { it.activityId == id } ?: return true
        if (summary.minutes < settings.goalMinutes) return true
        var queued = false
        Store.logAutomatic(id, day, owner) { queued = it == null }
        return queued
    }

    private fun syncSummaries(owner: String, id: String) {
        if (Store.trackingOwner() != owner || Store.habits.none { it.id == id }) return
        val days = pending(owner, id).keys().asSequence().toList()
        for (day in days) {
            val value = pendingSummary(owner, id, day) ?: continue
            val operation = "$owner:$id:$day"
            if (!inFlightSleep.add(operation)) continue
            // Queue offline first. A pending summary can safely retry the same daily log after a restart.
            if (!enqueueSleepGoal(owner, id, day, value)) {
                inFlightSleep.remove(operation)
                continue
            }
            Store.saveSleepSummary(id, day, value, owner) { problem ->
                inFlightSleep.remove(operation)
                if (problem == null && pendingSummary(owner, id, day) == value) {
                    val saved = pending(owner, id).apply { remove(day) }
                    Store.prefs.edit().putString(key(owner, "pending-sleep:$id"), saved.toString()).commit()
                    SleepWidget.refresh(Store.context)
                    updated?.invoke()
                }
            }
        }
    }

    private fun notifyAtLimit(context: Context, owner: String, day: String, limit: ScreenLimit) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ledger = key(owner, "notified:${limit.id}")
        if (Store.prefs.getString(ledger, null) == day || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(notificationChannel, "Screen-time limits",
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 410, Intent(context, PhoneTrackingActivity::class.java)
            .putExtra("trackingScreen", "limits"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify("$owner:${limit.id}:$day", 410, Notification.Builder(context, notificationChannel)
            .setSmallIcon(R.drawable.ic_logit_mark).setContentTitle("${limit.name} limit reached")
            .setContentText("You've reached your ${formatUsageMinutes(limit.minutes)} daily limit.")
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build())
        Store.prefs.edit().putString(ledger, day).commit()
    }
}

internal fun formatUsageMinutes(minutes: Int) = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

class PhoneUsageWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        PhoneTracking.run(applicationContext)
        return Result.success()
    }
}
