package dev.logit.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PhoneTrackingActivity : ComponentActivity() {
    private val screen get() = intent.getStringExtra("trackingScreen")
    private var selectedDay: LocalDate? = null
    private var offerWidget = false
    private var scroll: ScrollView? = null
    private val timeLabel = DateTimeFormatter.ofPattern("h:mm a")
    private val inputTime = DateTimeFormatter.ofPattern("HH:mm")
    private val healthScope = MainScope()
    private var healthAccess: Set<String>? = null
    private var checkingHealth = false
    private var healthJob: Job? = null
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refreshHealth(force = true, announce = false)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedDay = state?.getString("day")?.let(LocalDate::parse)
        offerWidget = state?.getBoolean("offerWidget") == true
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("day", selectedDay?.toString())
        state.putBoolean("offerWidget", offerWidget)
        super.onSaveInstanceState(state)
    }

    override fun onResume() {
        super.onResume()
        PhoneTracking.updated = { if (!isFinishing && !isDestroyed) render() }
        PhoneTracking.schedule()
        PhoneTracking.syncSleep()
        render()
        maybeOfferWidget()
        if (screen == "health") refreshHealth(force = true, announce = false)
    }

    override fun onPause() {
        PhoneTracking.updated = null
        healthJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        healthScope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val y = scroll?.scrollY ?: 0
        val root = column().apply {
            background = null
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        val container = ScrollView(this).apply {
            background = Theme.dashboardBackground()
            isFillViewport = true
            addView(root)
        }
        scroll = container
        setContentView(container)
        container.post { container.scrollTo(0, y) }
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (Theme.light()) flags else 0, flags)
        }
        val title = when (screen) {
            "sleep" -> "Sleep"
            "limits" -> "Screen time"
            "health" -> "Health Connect"
            else -> "App automations"
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(quietAction("‹") { finish() }.apply {
                textSize = 26f
                contentDescription = "Back"
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(label(title, 22f).apply { typeface = Theme.semibold },
                LinearLayout.LayoutParams(0, -2, 1f))
        })

        val owner = Store.trackingOwner()
        if (owner == null) {
            root.addView(label("Sign in to manage tracking.", 14f, Theme.muted))
            return
        }
        when (screen) {
            "sleep" -> showSleep(root, owner)
            "limits" -> showLimits(root, owner)
            "health" -> {
                showHealth(root, owner)
                return
            }
            else -> showAppRules(root, owner)
        }
        root.addCard(permissionCard(owner))
    }

    private fun refreshHealth(force: Boolean = false, announce: Boolean = force) {
        val owner = Store.trackingOwner() ?: return
        healthJob?.cancel()
        checkingHealth = true
        healthJob = healthScope.launch {
            healthAccess = HealthTracking.grants(applicationContext)
            val result = HealthTracking.check(applicationContext, owner, force)
            checkingHealth = false
            if (Store.trackingOwner() != owner) return@launch
            render()
            if (announce) toast(when (result) {
                HealthCheck.CHECKED -> "Health data checked."
                HealthCheck.NEEDS_ACCESS -> "Allow access to check health data."
                HealthCheck.NO_RULES -> "Set up a health rule first."
                HealthCheck.UNAVAILABLE -> "Health Connect is unavailable."
                HealthCheck.FAILED -> "Some health data couldn't be read. Try again."
            })
        }
    }

    private fun showHealth(root: LinearLayout, owner: String) {
        val status = HealthTracking.status(this)
        root.addCard(card().apply {
            addView(label("Log activities from steps, recorded workouts, or sleep when you open LogIt.", 14f, Theme.muted))
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                addAction("Manage health access") {
                    runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                        .onFailure { toast("Couldn't open Health Connect settings.") }
                }
            } else {
                addView(label(if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    "Install or update Health Connect to continue."
                } else "Health Connect is unavailable on this device.", 14f, Theme.muted))
                if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED && Build.VERSION.SDK_INT < 34) {
                    addAction("Health Connect in Google Play") {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                            "https://play.google.com/store/apps/details?id=${HealthTracking.providerPackage}",
                        )))
                    }
                }
            }
        })
        if (status != HealthConnectClient.SDK_AVAILABLE) return

        val habits = Store.habits.associateBy { it.id }
        val sleep = PhoneTracking.sleep(owner)?.takeIf { it.activityId in habits }
        var connected = false
        for (metric in HealthMetric.entries) {
            val rule = HealthTracking.rule(owner, metric)?.takeIf { it.activityId in habits }
            val allowed = metric.permission in healthAccess.orEmpty()
            connected = connected || (rule != null && allowed)
            root.addCard(card().apply {
                addView(title(metric.title))
                val summary = when (metric) {
                    HealthMetric.Steps -> "Log an activity when you reach a daily step target."
                    HealthMetric.Sleep -> "Import bedtime and wake time into your Sleep activity."
                    else -> "Log an activity when a recorded ${metric.title.lowercase(java.util.Locale.ROOT)} session meets your time target."
                }
                val habit = rule?.let { habits[it.activityId] }
                val target = when (metric) {
                    HealthMetric.Steps -> " · ${rule?.target} steps a day"
                    HealthMetric.Sleep -> ""
                    else -> " · ${rule?.target} minutes per session"
                }
                addView(label(if (habit == null) summary else habit.name + target, 14f, Theme.muted))

                if (rule != null && !allowed && healthAccess != null) {
                    val accessLabel = when (metric) {
                        HealthMetric.Steps -> "Allow steps access"
                        HealthMetric.Sleep -> "Allow sleep access"
                        else -> "Allow workout access"
                    }
                    addAction(accessLabel, true) {
                        runCatching { healthPermission.launch(setOf(metric.permission)) }
                            .onFailure { toast("Couldn't open health permissions.") }
                    }
                } else if (rule != null && healthAccess == null && !checkingHealth) {
                    addAction("Retry health access check") { refreshHealth() }
                }
                when {
                    metric != HealthMetric.Sleep -> addAction(if (rule == null) "Set up ${metric.title.lowercase(java.util.Locale.ROOT)}" else "Edit rule") {
                        healthRuleEditor(owner, metric, rule)
                    }
                    rule != null -> addAction("Stop importing sleep") {
                        HealthTracking.saveRule(owner, metric, null)
                        render()
                    }
                    sleep != null -> addAction("Import sleep") {
                        HealthTracking.saveRule(owner, metric, HealthRule(sleep.activityId, 0, LocalDate.parse(Dates.today())))
                        render()
                        refreshHealth()
                    }
                    else -> addAction("Set up Sleep activity") { openTracking("sleep") }
                }
            })
        }
        if (connected) root.addAction("Check health data now") { refreshHealth(force = true) }
        root.addView(label("LogIt syncs activity completions and sleep summaries to your account. Health records are read on this phone.", 12f, Theme.muted))
    }

    private fun healthRuleEditor(owner: String, metric: HealthMetric, existing: HealthRule?) {
        val habits = Store.habits
        if (habits.isEmpty()) { toast("Add an activity first."); return }
        val form = form()
        val activity = activityChoice(form, habits, existing?.activityId, optional = false)
        val target = form.field(if (metric == HealthMetric.Steps) "Steps per day" else "Minutes per session",
            (existing?.target ?: metric.defaultTarget).toString(), number = true, maxLength = 6)
        val error = form.error()
        showForm("${metric.title} rule", form, "Save rule", existing?.let {
            { HealthTracking.saveRule(owner, metric, null); render() }
        }) { dialog ->
            val habit = activity()
            val count = target.text.toString().toIntOrNull()
            if (habit == null || count == null || count !in 1..metric.maxTarget) {
                error.text = "Choose an activity and enter a target from 1 to ${metric.maxTarget}."
                return@showForm
            }
            val since = if (existing?.activityId == habit.id && existing.target == count) existing.since
                else LocalDate.parse(Dates.today())
            HealthTracking.saveRule(owner, metric, HealthRule(habit.id, count, since))
            dialog.dismiss()
            render()
            refreshHealth()
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Theme.bordered(Theme.card, Theme.cardBorder, dp(14).toFloat(), dp(1))
    }

    private fun LinearLayout.addCard(panel: View) {
        addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun title(text: String) = label(text, 16f).apply { typeface = Theme.semibold }

    private fun action(text: String, primary: Boolean = false, onClick: () -> Unit) =
        (if (primary) button(text, onClick) else quietAction(text, onClick)).apply {
            textSize = 14f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (!primary) {
                setTextColor(Theme.text)
                background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
            }
        }

    private fun LinearLayout.addAction(text: String, primary: Boolean = false, onClick: () -> Unit) =
        addView(action(text, primary, onClick), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    private fun permissionCard(owner: String) = card().apply {
        val automatic = PhoneTracking.automaticEnabled(owner)
        val allowed = AndroidUsage.allowed(this@PhoneTrackingActivity)
        val active = automatic && allowed
        addView(title(if (active) "Automatic tracking is on" else "Automatic tracking"))
        addView(label(if (active) "This phone measures usage locally." else
            "Optional. Enable Usage Access on your primary phone to measure phone and app time.", 13f, Theme.muted))
        if (active) {
            addAction("Pause automatic tracking") {
                PhoneTracking.setAutomatic(owner, false)
                render()
            }
        } else addAction(if (allowed) "Turn on automatic tracking" else "Open Usage Access settings") {
            PhoneTracking.setAutomatic(owner, true)
            if (allowed) render()
            else runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure {
                    PhoneTracking.setAutomatic(owner, false)
                    toast("Usage Access settings are unavailable on this device.")
                }
        }
        if (screen == "limits" && active && PhoneTracking.limits(owner).isNotEmpty() &&
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            addAction("Enable limit alerts") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 410) }
        }
    }

    private fun showSleep(root: LinearLayout, owner: String) {
        val settings = PhoneTracking.sleep(owner)
        val habit = Store.habits.find { it.id == settings?.activityId }
        if (settings == null || habit == null) {
            root.addCard(card().apply {
                addView(title("Sleep tracking"))
                addView(label("Keep a Sleep activity and record your bedtime and wake time. Choose automatic estimates if you want them.", 14f, Theme.muted))
                if (!AndroidUsage.screenEventsSupported) {
                    addView(label("Automatic sleep estimates require Android 9 or later. You can still enter sleep manually.", 13f, Theme.muted))
                }
                addAction("Set up Sleep", true) { sleepSetup(owner, null) }
            })
            return
        }
        val today = LocalDate.parse(Dates.today())
        val day = selectedDay ?: today
        val recent = PhoneTracking.summary(owner, habit, day.toString())
        root.addCard(card().apply {
            addView(title(if (day == today) "Last night" else day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))))
            addView(label(if (recent == null) "No sleep recorded" else
                formatUsageMinutes(Duration.ofMillis(recent.wakeTime - recent.bedtime).toMinutes().toInt()), 26f))
            if (recent != null) addView(label("${formatTime(recent.bedtime)} – ${formatTime(recent.wakeTime)}" + when {
                recent.edited -> " · Edited"
                recent.healthConnect -> " · Health Connect"
                else -> " · Estimated from phone use"
            }, 13f, Theme.muted))
            else addView(label("Enter your sleep hours, or try an estimate from phone use.", 13f, Theme.muted))
            addAction(if (recent == null) "Enter sleep" else "Edit sleep") { editNight(owner, habit, day, recent, settings.schedule) }
        })

        root.addCard(card().apply {
            addView(title("Last 7 nights"))
            for (offset in 0L..6L) {
                val date = today.minusDays(offset)
                val record = PhoneTracking.summary(owner, habit, date.toString())
                val name = date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
                val value = record?.let {
                    formatUsageMinutes(Duration.ofMillis(it.wakeTime - it.bedtime).toMinutes().toInt())
                } ?: "No entry"
                addView(quietAction("$name · $value") {
                    selectedDay = date
                    val existing = PhoneTracking.summary(owner, habit, date.toString())
                    editNight(owner, habit, date, existing, settings.schedule)
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    minHeight = dp(44)
                    minimumHeight = dp(44)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        })
        root.addCard(card().apply {
            addView(title("Usual sleep hours"))
            addView(label("${settings.schedule.bedtime.format(timeLabel)} – ${settings.schedule.wakeTime.format(timeLabel)}", 14f, Theme.muted))
            addAction("Change sleep hours") { sleepSetup(owner, settings) }
            addAction("Add Sleep widget") { pinWidget() }
            addAction("Turn off Sleep tracking") {
                PhoneTracking.saveSleep(owner, null)
                offerWidget = false
                render()
            }
        })
    }

    private fun sleepSetup(owner: String, settings: SleepTracking?) {
        val form = form()
        val existing = if (settings == null) Store.habits.firstOrNull { it.name.equals("Sleep", ignoreCase = true) } else null
        val name = form.field("Activity name", existing?.name ?: "Sleep")
        if (settings != null) hideField(form, name)
        val schedule = settings?.schedule ?: SleepSchedule()
        val bed = form.field("Usual bedtime (24-hour)", schedule.bedtime.format(inputTime))
        val wake = form.field("Usual wake time (24-hour)", schedule.wakeTime.format(inputTime))
        val error = form.error()
        showForm(if (settings == null) "Set up Sleep" else "Sleep hours", form,
            if (settings == null) "Enable Sleep" else "Save hours") { dialog ->
            val start = readTime(bed)
            val end = readTime(wake)
            val hours = if (start != null && end != null) overnightMinutes(start, end) else 0
            if (name.text.toString().isBlank() || start == null || end == null || hours !in 180..960) {
                error.text = "Enter a name and sleep hours covering 3 to 16 hours. Use HH:mm for times."
                return@showForm
            }
            val save: (String) -> Unit = { id ->
                val since = if (settings?.schedule == SleepSchedule(start, end)) settings.since else System.currentTimeMillis()
                PhoneTracking.saveSleep(owner, SleepTracking(id, SleepSchedule(start, end), since))
                offerWidget = settings == null
                dialog.dismiss()
                render()
                maybeOfferWidget()
            }
            val current = settings?.activityId ?: existing?.takeIf { it.name == name.text.toString().trim() }?.id
            if (current != null) save(current)
            else {
                dialog.getButton(-1).isEnabled = false
                Store.addActivity(name.text.toString().trim()) { id, problem ->
                    if (id != null && Store.trackingOwner() == owner) save(id)
                    else {
                        dialog.getButton(-1).isEnabled = true
                        error.text = problem ?: "Could not create the Sleep activity."
                    }
                }
            }
        }
    }

    private fun maybeOfferWidget() {
        if (!offerWidget) return
        val owner = Store.trackingOwner() ?: return
        if (PhoneTracking.sleep(owner) == null) return
        offerWidget = false
        PrettyDialog.Builder(this).setTitle("Add the Sleep widget?")
            .setMessage("See your latest sleep estimate on your home screen.")
            .setNegativeButton("Later", null).setPositiveButton("Add widget") { _, _ -> pinWidget() }.show()
    }

    private fun pinWidget() {
        if (!SleepWidget.pin(this)) toast("This launcher couldn't add the Sleep widget.")
    }

    private fun editNight(owner: String, habit: Habit, day: LocalDate, current: SleepSummary?, schedule: SleepSchedule) {
        val form = form()
        val bed = form.field("Bedtime (24-hour)", current?.let { formatInputTime(it.bedtime) } ?: schedule.bedtime.format(inputTime))
        val wake = form.field("Wake time (24-hour)", current?.let { formatInputTime(it.wakeTime) } ?: schedule.wakeTime.format(inputTime))
        val error = form.error()
        showForm("Sleep for ${day.format(DateTimeFormatter.ofPattern("d MMM"))}", form, "Save sleep") { dialog ->
            val start = readTime(bed)
            val end = readTime(wake)
            if (start == null || end == null || overnightMinutes(start, end) !in 1..960) {
                error.text = "Enter valid sleep hours in HH:mm."
                return@showForm
            }
            val bedDate = if (start >= end) day.minusDays(1) else day
            val from = bedDate.atTime(start).atZone(Dates.zone).toInstant().toEpochMilli()
            val to = day.atTime(end).atZone(Dates.zone).toInstant().toEpochMilli()
            if (to > System.currentTimeMillis()) {
                error.text = "Wake time must have already passed."
                return@showForm
            }
            PhoneTracking.saveSummary(owner, habit.id, day.toString(), SleepSummary(from, to, edited = true))
            dialog.dismiss()
            render()
        }
    }

    private fun showAppRules(root: LinearLayout, owner: String) {
        val rules = PhoneTracking.appRules(owner)
        val preselected = intent.getStringExtra("activityId")
        root.addCard(card().apply {
            addView(title("Log an activity from app use"))
            addView(label("Set a minimum daily time in an app. LogIt marks the linked activity once when you reach it.", 14f, Theme.muted))
            addAction("Add app rule", true) { appRuleEditor(owner, null, preselected) }
        })
        val habits = Store.habits.associateBy { it.id }
        for (rule in rules) {
            val habit = habits[rule.activityId] ?: continue
            root.addCard(card().apply {
                addView(title(habit.name))
                addView(label("${rule.app.label} · ${formatUsageMinutes(rule.minutes)} a day", 14f, Theme.muted))
                addAction("Edit rule") { appRuleEditor(owner, rule, null) }
            })
        }
    }

    private fun appRuleEditor(owner: String, rule: AppLogRule?, preselected: String?) {
        val habits = Store.habits
        if (habits.isEmpty()) { toast("Add an activity first."); return }
        val form = form()
        val target = activityChoice(form, habits, rule?.activityId ?: preselected ?: habits.first().id, optional = false)
        var selectedApp = rule?.app
        lateinit var appButton: View
        appButton = action(selectedApp?.label ?: "Choose an app") {
            pickApps(selectedApp?.let(::listOf).orEmpty(), multi = false) {
                selectedApp = it.firstOrNull()
                (appButton as? android.widget.Button)?.text = selectedApp?.label ?: "Choose an app"
            }
        }
        form.addView(appButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val minimum = form.field("Minimum minutes per day", (rule?.minutes ?: 5).toString(), number = true)
        val error = form.error()
        showForm("App rule", form, "Save rule", rule?.let {
            { PhoneTracking.saveAppRules(owner, PhoneTracking.appRules(owner).filterNot { current -> current == rule }); render() }
        }) { dialog ->
            val app = selectedApp
            val count = minimum.text.toString().toIntOrNull()
            val activity = target()?.id
            if (app == null || count == null || count !in 1..1440 || activity == null) {
                error.text = "Choose an activity and app, and enter 1 to 1,440 minutes."
                return@showForm
            }
            val next = AppLogRule(activity, app, count, System.currentTimeMillis())
            val others = PhoneTracking.appRules(owner).filterNot { it == rule || it.activityId == activity && it.app.id == app.id }
            PhoneTracking.saveAppRules(owner, others + next)
            dialog.dismiss()
            render()
        }
    }

    private fun showLimits(root: LinearLayout, owner: String) {
        val limits = PhoneTracking.limits(owner)
        val total = limits.firstOrNull { it.id == ScreenLimit.totalId }
        root.addCard(card().apply {
            addView(title("Daily limits"))
            addView(label("Get a notice at the limit. Link an activity to log completed days below the limit.", 14f, Theme.muted))
            if (total == null) {
                if (AndroidUsage.screenEventsSupported) addAction("Set total phone limit") { limitEditor(owner, null, total = true) }
                else addView(label("Total phone time requires Android 9 or later.", 13f, Theme.muted))
            }
            addAction("Add app group", true) { limitEditor(owner, null, total = false) }
        })
        for (limit in limits) {
            val reading = PhoneTracking.reading(owner, limit.id)?.takeIf {
                it.day == Dates.today() && PhoneTracking.automaticEnabled(owner) && AndroidUsage.allowed(this)
            }
            val yesterday = PhoneTracking.reading(owner, limit.id, yesterday = true)?.takeIf {
                it.day == LocalDate.parse(Dates.today()).minusDays(1).toString() &&
                    PhoneTracking.automaticEnabled(owner) && AndroidUsage.allowed(this)
            }
            root.addCard(card().apply {
                addView(title(limit.name))
                val limitText = formatUsageMinutes(limit.minutes)
                addView(label(if (reading == null) "Daily limit $limitText" else
                    "${formatUsageMinutes(reading.minutes)} used today · $limitText limit", 14f, Theme.muted))
                if (reading != null) addView(android.widget.ProgressBar(this@PhoneTrackingActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = limit.minutes
                    progress = reading.minutes.coerceAtMost(max)
                    progressTintList = android.content.res.ColorStateList.valueOf(
                        if (reading.minutes >= limit.minutes) Theme.destructive else Theme.text)
                    contentDescription = "Today's use towards the daily limit"
                }, LinearLayout.LayoutParams(-1, dp(8)))
                if (yesterday != null) {
                    val result = if (yesterday.minutes < limit.minutes) "under limit" else "limit reached"
                    addView(label("Yesterday · ${formatUsageMinutes(yesterday.minutes)} · $result", 13f, Theme.muted))
                }
                addAction("Edit limit") { limitEditor(owner, limit, total = limit.id == ScreenLimit.totalId) }
            })
        }
        root.addView(label("Daily totals follow India time.", 12f, Theme.muted))
    }

    private fun limitEditor(owner: String, existing: ScreenLimit?, total: Boolean) {
        val form = form()
        val name = if (total) null else form.field("Group name", existing?.name.orEmpty())
        val available = AndroidUsage.launchableApps(this).associateBy { it.id }
        var apps = existing?.apps.orEmpty().map { available[it] ?: UsageApp(it, "Unavailable app") }
        if (!total) {
            lateinit var choose: android.widget.Button
            choose = action(if (apps.isEmpty()) "Choose apps" else "${apps.size} apps selected") {
                pickApps(apps, multi = true) {
                    apps = it
                    choose.text = if (it.isEmpty()) "Choose apps" else "${it.size} apps selected"
                }
            }
            form.addView(choose, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val maximum = form.field("Daily limit in minutes", (existing?.minutes ?: if (total) 360 else 180).toString(), number = true)
        val target = activityChoice(form, Store.habits, existing?.activityId, optional = true)
        val error = form.error()
        showForm(if (total) "Total phone limit" else "App group limit", form, "Save limit", existing?.let {
            { PhoneTracking.saveLimits(owner, PhoneTracking.limits(owner).filterNot { current -> current.id == existing.id }); render() }
        }) { dialog ->
            val count = maximum.text.toString().toIntOrNull()
            val title = if (total) "Total phone use" else name?.text.toString().trim()
            if (count == null || count !in 1..1440 || title.isBlank() || (!total && apps.isEmpty())) {
                error.text = "Enter 1 to 1,440 minutes and complete the group details."
                return@showForm
            }
            val id = existing?.id ?: if (total) ScreenLimit.totalId else ScreenLimit.newId()
            val next = ScreenLimit(id, title, apps.mapTo(mutableSetOf()) { it.id }, count, target()?.id, System.currentTimeMillis())
            PhoneTracking.saveLimits(owner, PhoneTracking.limits(owner).filterNot { it.id == id } + next)
            dialog.dismiss()
            render()
        }
    }

    private fun activityChoice(form: LinearLayout, habits: List<Habit>, id: String?, optional: Boolean): () -> Habit? {
        val options = (if (optional) listOf<Habit?>(null) else emptyList()) + habits
        if (options.isEmpty()) return { null }
        form.addView(label(if (optional) "Activity to log when under limit" else "Activity to log", 13f, Theme.muted))
        val choice = ChoiceView(this, options.map { it?.name ?: "None" }.toTypedArray(),
            options.indexOfFirst { it?.id == id }.coerceAtLeast(0))
        form.addView(choice, LinearLayout.LayoutParams(-1, dp(48)))
        return { options.getOrNull(choice.selectedItemPosition) }
    }

    private fun pickApps(selected: List<UsageApp>, multi: Boolean, onDone: (List<UsageApp>) -> Unit) {
        val apps = AndroidUsage.launchableApps(this)
        val chosen = selected.associateByTo(linkedMapOf()) { it.id }
        val form = form()
        val search = form.field("Find an app", "")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(220)))
        lateinit var dialog: PrettyDialog
        fun populate(query: String) {
            list.removeAllViews()
            val matches = apps.filter { it.label.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) list.addView(label("No apps found", 14f, Theme.muted))
            for (app in matches) {
                val row = quietAction(if (app.id in chosen) "✓  ${app.label}" else app.label) {
                    if (!multi) {
                        onDone(listOf(app))
                        dialog.dismiss()
                    } else {
                        if (app.id in chosen) chosen.remove(app.id) else chosen[app.id] = app
                        populate(search.text.toString())
                    }
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    isSelected = app.id in chosen
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        search.doAfterTextChanged { populate(it.toString()) }
        populate("")
        val builder = PrettyDialog.Builder(this).setTitle(if (multi) "Choose apps" else "Choose an app")
            .setView(form).setNegativeButton("Cancel", null)
        if (multi) builder.setPositiveButton("Done") { _, _ -> onDone(chosen.values.toList()) }
        dialog = builder.create()
        dialog.show()
    }

    private fun showForm(title: String, form: LinearLayout, save: String, remove: (() -> Unit)? = null,
        onSave: (PrettyDialog) -> Unit) {
        lateinit var dialog: PrettyDialog
        if (remove != null) form.addAction("Remove") {
            dialog.dismiss()
            remove()
        }
        dialog = PrettyDialog.Builder(this).setTitle(title).setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton(save, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { onSave(dialog) } }
        dialog.show()
    }

    private fun form() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.field(caption: String, value: String, number: Boolean = false, maxLength: Int = if (number) 4 else 80): EditText {
        addView(label(caption, 13f, Theme.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return EditText(this@PhoneTrackingActivity).apply {
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(value)
            contentDescription = caption
            textSize = 15f
            typeface = Theme.regular
            setTextColor(Theme.text)
            applyMonochromeCursor(this)
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(48)
            addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun hideField(form: LinearLayout, input: EditText) {
        form.getChildAt(form.indexOfChild(input) - 1).visibility = View.GONE
        input.visibility = View.GONE
    }

    private fun LinearLayout.error() = label("", 13f, Theme.destructive).also { addView(it) }

    private fun readTime(field: EditText): LocalTime? = runCatching {
        LocalTime.parse(field.text.toString().trim(), DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT))
    }.getOrNull()

    private fun overnightMinutes(bed: LocalTime, wake: LocalTime): Int =
        ((wake.toSecondOfDay() - bed.toSecondOfDay() + 86400) % 86400) / 60

    private fun formatInputTime(at: Long) = Instant.ofEpochMilli(at).atZone(Dates.zone).format(inputTime)
    private fun formatTime(at: Long) = Instant.ofEpochMilli(at).atZone(Dates.zone).format(timeLabel)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

internal fun Activity.openTracking(screen: String, habitId: String? = null) {
    startActivity(Intent(this, PhoneTrackingActivity::class.java)
        .putExtra("trackingScreen", screen).putExtra("activityId", habitId))
}
