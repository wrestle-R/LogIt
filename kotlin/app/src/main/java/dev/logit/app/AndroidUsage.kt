package dev.logit.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager

internal object AndroidUsage {
    val screenEventsSupported: Boolean get() = Build.VERSION.SDK_INT >= 28

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun read(context: Context, start: Long, end: Long): UsageTimeline? {
        if (!allowed(context) || !context.getSystemService(UserManager::class.java).isUserUnlocked) return null
        val events = try {
            context.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        } catch (_: SecurityException) { return null }
        if (events == null) return null
        val samples = mutableListOf<UsageSample>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            val signal = when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageSignal.ScreenOn
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageSignal.ScreenOff
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageSignal.Locked
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageSignal.Unlocked
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageSignal.Foreground
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageSignal.Background
                UsageEvents.Event.USER_INTERACTION -> UsageSignal.Interaction
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> UsageSignal.Restart
                else -> continue
            }
            samples.add(UsageSample(event.timeStamp, signal, event.packageName.orEmpty(), event.className.orEmpty()))
        }
        return samples.takeIf { it.isNotEmpty() }?.let { UsageTimeline(it, end) }
    }

    fun launchableApps(context: Context): List<UsageApp> {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { UsageApp(it.activityInfo.packageName, it.loadLabel(packages).toString()) }
            .distinctBy(UsageApp::id)
            .sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
    }
}

internal data class UsageApp(val id: String, val label: String)
