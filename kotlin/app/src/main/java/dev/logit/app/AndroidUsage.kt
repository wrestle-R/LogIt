package dev.logit.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.os.SystemClock
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.Locale

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

    private data class AppCatalog(val apps: List<UsageApp>, val locale: Locale, val at: Long)
    @Volatile private var catalog: AppCatalog? = null
    private val icons = LruCache<String, Drawable.ConstantState>(96)

    fun cachedApps(): List<UsageApp>? = catalog?.takeIf {
        it.locale == Locale.getDefault() && SystemClock.elapsedRealtime() - it.at < 30_000L
    }?.apps

    // Call from Dispatchers.IO. PackageManager resolves application resources across processes.
    @Synchronized fun launchableApps(context: Context): List<UsageApp> {
        cachedApps()?.let { return it }
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val locale = Locale.getDefault()
        val apps = packages.queryIntentActivities(launcher, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { UsageApp(it.activityInfo.packageName, it.loadLabel(packages).toString()) }
            .sortedBy { it.label.lowercase(locale) }
            .toList()
        icons.evictAll()
        catalog = AppCatalog(apps, locale, SystemClock.elapsedRealtime())
        return apps
    }

    fun cachedIcon(app: UsageApp): Drawable.ConstantState? = icons.get(app.id)

    fun loadIcon(context: Context, app: UsageApp): Drawable.ConstantState? {
        cachedIcon(app)?.let { return it }
        val current = catalog
        val packages = context.packageManager
        val icon = runCatching { packages.getApplicationIcon(app.id) }.getOrElse { packages.defaultActivityIcon }
        return icon.constantState?.also { if (catalog === current) icons.put(app.id, it) }
    }
}

internal data class UsageApp(val id: String, val label: String)
