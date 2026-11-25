package dev.logit.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object GoalReminders {
    private const val work = "goal-reminders"
    const val channel = "goal-reminders"
    const val permissionRequest = 91

    private fun owner() = if (Store.demo) "demo" else if (Store.signedIn) Store.auth.currentUser?.uid else null
    private fun key() = "reminders:" + owner()
    fun enabled() = owner() != null && Store.prefs.getBoolean(key(), false)

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!enabled()) {
            manager.cancelUniqueWork(work)
            return
        }
        manager.enqueueUniquePeriodicWork(
            work, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(GoalReminderWorker::class.java, 15, TimeUnit.MINUTES).build(),
        )
    }

    fun offer(activity: Activity) {
        if (!enabled() && !Store.prefs.getBoolean(key() + ":asked", false)) settings(activity)
    }

    fun settings(activity: Activity) {
        Store.prefs.edit().putBoolean(key() + ":asked", true).apply()
        activity.startActivity(Intent(activity, DetailsActivity::class.java).putExtra("screen", "reminders"))
    }

    fun time(missed: Boolean): Int = Store.prefs.getInt(key() + ":time:" + missed, if (missed) 540 else 1200)
    fun setTime(missed: Boolean, minutes: Int) {
        Store.prefs.edit().putInt(key() + ":time:" + missed, minutes).apply()
    }

    fun kindEnabled(missed: Boolean): Boolean = Store.prefs.getBoolean(key() + ":kind:" + missed, true)
    fun setKind(missed: Boolean, value: Boolean) {
        Store.prefs.edit().putBoolean(key() + ":kind:" + missed, value).apply()
    }

    fun slots(now: ZonedDateTime): List<Pair<java.time.LocalDate, Boolean>> =
        listOf(false, true).filter(::kindEnabled).mapNotNull { missed -> reminderSlot(now, time(missed), missed) }

    fun setEnabled(context: Context, value: Boolean) {
        if (owner() == null) return
        Store.prefs.edit().putBoolean(key(), value).putBoolean(key() + ":asked", true).apply()
        schedule(context)
    }

    fun deliver(context: Context, now: ZonedDateTime = ZonedDateTime.now(Dates.zone), habits: List<Habit> = Store.habits) {
        val owner = owner() ?: return
        if (!enabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val slots = slots(now)
        if (slots.isEmpty()) return
        manager.createNotificationChannel(NotificationChannel(channel, "Goal reminders", NotificationManager.IMPORTANCE_DEFAULT))
        for ((day, missed) in slots) for (habit in habits) {
            val goal = habit.goal ?: continue
            if (!habit.needsReminder(day)) continue
            val tag = "$owner:${habit.id}:$day:$missed"
            val ledger = "reminder-delivery:$owner:${habit.id}:$missed"
            if (Store.prefs.getString(ledger, null) == day.toString()) continue
            val open = PendingIntent.getActivity(context, tag.hashCode(), Intent(context, MainActivity::class.java)
                .putExtra("habit", habit.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val message = if (missed) "You came up short. If you forgot to log it, you can still add it."
                else "You're at ${habit.goalCount(day)} of ${goal.target}. There's still time for another log."
            manager.notify(tag, 1, Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_logit_mark)
                .setContentTitle(if (missed) "Missed goal for ${habit.name}" else "A heads-up for ${habit.name}")
                .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build())
            Store.prefs.edit().putString(ledger, day.toString()).commit()
        }
    }
}

class GoalReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!GoalReminders.enabled()) return Result.success()
        if (GoalReminders.slots(ZonedDateTime.now(Dates.zone)).isEmpty()) return Result.success()
        if (Store.signedIn && !Store.demo) {
            // Refresh cross-device logs before deciding a goal was missed.
            val user = Store.auth.currentUser?.uid ?: return Result.success()
            try {
                val latest = com.google.android.gms.tasks.Tasks.await(Store.fetchHabits(user), 10, TimeUnit.SECONDS)
                if (Store.auth.currentUser?.uid != user || Store.demo) return Result.success()
                val habits = OfflineLogQueue.project(latest.documents.map(Store::decodeDocument), OfflineLogQueue.operations(user))
                GoalReminders.deliver(applicationContext, habits = habits)
                return Result.success()
            } catch (_: Exception) {
                return Result.retry()
            }
        }
        GoalReminders.deliver(applicationContext)
        return Result.success()
    }
}
