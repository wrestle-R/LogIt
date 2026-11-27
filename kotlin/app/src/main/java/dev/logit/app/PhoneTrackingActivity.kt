package dev.logit.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthConnectManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PhoneTrackingActivity : ComponentActivity() {
    private val screen get() = intent.getStringExtra("trackingScreen") ?: "apps"
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
        selectedDay = (state?.getString("day") ?: intent.getStringExtra("sleepDay"))?.let(LocalDate::parse)
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
        healthScope.launch(Dispatchers.IO) { PhoneTracking.run(applicationContext, onReturn = true) }
        PhoneTracking.syncSleep()
        render()
        maybeOfferWidget()
        intent.getBooleanExtra("editSleepGoal", false).takeIf { it }?.let {
            intent.removeExtra("editSleepGoal")
            val owner = Store.trackingOwner()
            if (owner != null) PhoneTracking.sleep(owner)?.let { sleepSetup(owner, it) }
        }
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
            "tracking" -> "Tracking"
            "sleep" -> "Sleep"
            "limits" -> "Screen time"
            "health" -> "Health Connect"
            else -> "App automations"
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(quietAction("‹") { finish() }.apply {
                textSize = 22f
                setTextColor(Theme.text)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                background = Theme.bordered(Theme.card, Theme.controlBorder, dp(12).toFloat(), dp(1))
                contentDescription = "Back"
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(label(title, 22f).apply { typeface = Theme.semibold },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (screen == "sleep") {
                val owner = Store.trackingOwner()
                val settings = owner?.let(PhoneTracking::sleep)
                if (owner != null && settings != null) {
                    addView(quietAction("Settings") { sleepSettings(owner, settings) }.apply {
                        minimumHeight = dp(44)
                        setTextColor(Theme.text)
                        background = Theme.bordered(Theme.card, Theme.controlBorder, dp(10).toFloat(), dp(1))
                        setPadding(dp(12), 0, dp(12), 0)
                        contentDescription = "Sleep settings"
                    }, LinearLayout.LayoutParams(-2, -2))
                }
            }
        })

        val owner = Store.trackingOwner()
        if (owner == null) {
            root.addView(label("Sign in to manage tracking.", 14f, Theme.muted))
            return
        }
        when (screen) {
            "tracking" -> {
                showTracking(root, owner)
                return
            }
            "sleep" -> {
                showSleep(root, owner)
                Introduction.show(this, screen, root.getChildAt(1))
                return
            }
            "limits" -> showLimits(root, owner)
            "health" -> {
                showHealth(root, owner)
                Introduction.show(this, screen, root.getChildAt(1))
                return
            }
            else -> showAppRules(root, owner)
        }
        root.addCard(permissionCard(owner))
        Introduction.show(this, screen, root.getChildAt(1))
    }

    @Deprecated("Handled by Android for normal navigation")
    override fun onBackPressed() {
        if (!Introduction.dismiss(this, screen)) super.onBackPressed()
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
                addAction("Manage health access") { openHealthAccess() }
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

    private fun openHealthAccess() {
        if (Build.VERSION.SDK_INT >= 34) {
            val permissions = Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            if (runCatching { startActivity(permissions) }.isSuccess) return
        }
        runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            .onFailure { toast("Couldn't open Health Connect settings.") }
    }

    private fun healthRuleEditor(owner: String, metric: HealthMetric, existing: HealthRule?) {
        val form = form()
        val activity = activityChoice(form, owner, existing?.activityId ?: intent.getStringExtra("activityId"),
            optional = false)
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
            "Enable Usage Access on your primary phone to measure phone and app time.", 13f, Theme.muted))
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

    private fun showTracking(root: LinearLayout, owner: String) {
        val sleep = PhoneTracking.sleep(owner)
        root.addCard(card().apply {
            addView(trackingRow("Sleep", sleep?.let { "${formatUsageMinutes(it.goalMinutes)} minimum" }
                ?: "Set a sleep goal and view your nights") { openTracking("sleep") })
            addView(trackingRow("Health Connect", "Import steps, workouts, and sleep") { openTracking("health") })
            addView(trackingRow("App time rules", "Log an activity after using an app") { openTracking("apps") })
            addView(trackingRow("Screen time limits", "Set daily limits for your phone or groups of apps") { openTracking("limits") })
        })
    }

    private fun trackingRow(
        heading: String,
        detail: String,
        trailing: String = "›",
        trailingColor: Int = Theme.muted,
        action: () -> Unit,
    ) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(6), 0, dp(6))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                null, Theme.shape(android.graphics.Color.WHITE, dp(8).toFloat()),
            )
            addView(form().apply {
                addView(label(heading, 14f).apply { typeface = Theme.semibold })
                addView(label(detail, 12f, Theme.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(trailing, if (trailing == "›") 19f else 15f, trailingColor).apply {
                typeface = Theme.semibold
                setPadding(dp(8), 0, 0, 0)
                if (trailing == "›") importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun showSleep(root: LinearLayout, owner: String) {
        val settings = PhoneTracking.sleep(owner)
        val habit = Store.habits.find { it.id == settings?.activityId }
        if (settings == null || habit == null) {
            root.addCard(card().apply {
                addView(title("Set a sleep goal"))
                addView(label("See how long you slept. LogIt completes your Sleep activity on nights that reach your minimum.", 14f, Theme.muted))
                addAction("Set up Sleep", true) { sleepSetup(owner, null) }
            })
            return
        }

        val today = LocalDate.parse(Dates.today())
        val history = (0L..6L).map { offset ->
            val date = today.minusDays(offset)
            date to PhoneTracking.summary(owner, habit, date.toString())
        }
        val day = selectedDay ?: history.firstOrNull { it.second != null }?.first ?: today
        val record = PhoneTracking.summary(owner, habit, day.toString())
        val goal = formatUsageMinutes(settings.goalMinutes)
        val dateLabel = DateTimeFormatter.ofPattern("EEE, d MMM")
        val accent = sleepGoalColor()
        root.addCard(card().apply {
            addView(label(if (day == today) "Last night" else day.format(dateLabel), 14f, Theme.muted))
            if (record != null) {
                addView(label(formatUsageMinutes(record.minutes), 34f).apply { typeface = Theme.semibold })
                val reached = record.minutes >= settings.goalMinutes
                addView(label(if (reached) "Goal met · $goal minimum" else "$goal minimum · ${formatUsageMinutes(settings.goalMinutes - record.minutes)} short",
                    13f, if (reached) accent else Theme.muted))
                addView(android.widget.ProgressBar(this@PhoneTrackingActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = settings.goalMinutes
                    progress = record.minutes.coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Theme.controlBorder)
                    contentDescription = "${formatUsageMinutes(record.minutes)} of $goal"
                }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(9); bottomMargin = dp(6) })
                val source = when {
                    record.edited -> "Manual entry"
                    record.healthConnect -> "Health Connect"
                    else -> "Phone estimate"
                }
                addView(label("${formatTime(record.bedtime)} – ${formatTime(record.wakeTime)} · $source", 13f, Theme.muted))
            } else {
                addView(label("No sleep recorded", 22f).apply { typeface = Theme.semibold })
                addView(label("Your Sleep activity logs when you reach $goal.", 13f, Theme.muted))
            }
            addAction(if (record == null) "Add sleep" else if (day != today && history.first().second == null)
                "Add last night" else "Edit sleep", true) {
                val addLatest = record != null && day != today && history.first().second == null
                editNight(owner, habit, if (addLatest) today else day, if (addLatest) null else record, settings.schedule)
            }
        })

        root.addCard(card().apply {
            addView(title("Recent nights"))
            val recorded = history.mapNotNull { (date, night) -> night?.let { date to it } }
            if (recorded.isEmpty()) {
                addView(label("Your nights will appear here as you add or import them.", 13f, Theme.muted))
            }
            recorded.forEachIndexed { index, (date, night) ->
                if (index > 0) addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)))
                addView(trackingRow(date.format(dateLabel),
                    "${formatTime(night.bedtime)} – ${formatTime(night.wakeTime)}",
                    trailing = formatUsageMinutes(night.minutes),
                    trailingColor = if (night.minutes >= settings.goalMinutes) accent else Theme.text,
                ) { editNight(owner, habit, date, night, settings.schedule) })
            }
            addAction("Add or edit another night") { chooseSleepDate(owner, habit, settings, history) }
        })
        root.addCard(trackingRow("Add Sleep widget", "See your sleep goal on the home screen") { pinWidget() })
    }

    private fun chooseSleepDate(owner: String, habit: Habit, settings: SleepTracking,
        recent: List<Pair<LocalDate, SleepSummary?>>) {
        val dates = (recent.map { it.first } + habit.sleepSummaries.keys.mapNotNull {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }).distinct().sortedDescending()
        val format = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
        val nights = dates.map { date -> PhoneTracking.summary(owner, habit, date.toString()) }
        val labels = dates.zip(nights).map { (date, night) ->
            "${date.format(format)} · ${night?.let { formatUsageMinutes(it.minutes) } ?: "Add entry"}"
        }.toTypedArray()
        PrettyDialog.Builder(this).setTitle("Choose a night").setItems(labels) { _, index ->
            editNight(owner, habit, dates[index], nights[index], settings.schedule)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun sleepSettings(owner: String, settings: SleepTracking) {
        val form = form()
        lateinit var dialog: PrettyDialog
        form.addView(trackingRow("Sleep goal", "${formatUsageMinutes(settings.goalMinutes)} minimum") {
            dialog.dismiss()
            sleepSetup(owner, settings)
        })
        form.addView(trackingRow("Sleep from Health Connect", "Import from a watch or another sleep app") {
            dialog.dismiss()
            openTracking("health")
        })
        form.addView(trackingRow("Usual bedtime and wake time",
            "${settings.schedule.bedtime.format(timeLabel)} – ${settings.schedule.wakeTime.format(timeLabel)}") {
            dialog.dismiss()
            editSleepSchedule(owner, settings)
        })
        if (AndroidUsage.screenEventsSupported) {
            val canEstimate = AndroidUsage.allowed(this) && PhoneTracking.automaticEnabled(owner)
            val estimating = settings.estimateFromPhone && canEstimate
            val detail = if (estimating) "On · uses overnight phone inactivity" else "Off · estimates from overnight phone inactivity"
            form.addView(trackingRow("Phone sleep estimates", detail) {
                dialog.dismiss()
                PhoneTracking.saveSleep(owner, settings.copy(
                    estimateFromPhone = !estimating,
                    since = if (estimating) settings.since else System.currentTimeMillis(),
                ))
                if (!estimating) {
                    if (!PhoneTracking.automaticEnabled(owner)) PhoneTracking.setAutomatic(owner, true)
                    if (!AndroidUsage.allowed(this)) {
                        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                            .onFailure { toast("Usage Access settings are unavailable on this device.") }
                    }
                }
                render()
            })
        }
        form.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)))
        form.addView(trackingRow("Stop Sleep tracking", "Turns off estimates and imports. Keeps your history.") {
            dialog.dismiss()
            PrettyDialog.Builder(this).setTitle("Stop Sleep tracking?")
                .setMessage("Your sleep history and activity completions will stay.")
                .setNegativeButton("Cancel", null).setPositiveButton("Stop tracking") { _, _ ->
                    PhoneTracking.saveSleep(owner, null)
                    offerWidget = false
                    render()
                }.show()
        })
        dialog = PrettyDialog.Builder(this).setTitle("Sleep settings").setView(form)
            .setPositiveButton("Done", null).create()
        dialog.show()
    }

    private fun editSleepSchedule(owner: String, settings: SleepTracking) {
        val form = form()
        val bed = form.field("Usual bedtime (24-hour)", settings.schedule.bedtime.format(inputTime))
        val wake = form.field("Usual wake time (24-hour)", settings.schedule.wakeTime.format(inputTime))
        form.addView(label("Used for phone estimates.", 13f, Theme.muted))
        val error = form.error()
        showForm("Usual sleep hours", form, "Save hours") { dialog ->
            val start = readTime(bed)
            val end = readTime(wake)
            if (start == null || end == null || overnightMinutes(start, end) !in 180..960) {
                error.text = "Enter a window between 3 and 16 hours using HH:mm."
                return@showForm
            }
            val schedule = SleepSchedule(start, end)
            if (schedule != settings.schedule) {
                PhoneTracking.saveSleep(owner, settings.copy(schedule = schedule, since = System.currentTimeMillis()))
            }
            dialog.dismiss()
            render()
        }
    }

    private fun sleepSetup(owner: String, settings: SleepTracking?) {
        val form = form()
        val initialName = intent.getStringExtra("activityName").orEmpty().ifBlank { "Sleep" }
        val name = if (settings == null) form.field("Activity name", initialName) else null
        val initialGoal = settings?.goalMinutes ?: SleepTracking.defaultGoalMinutes
        val hours = form.field("Minimum hours", (initialGoal / 60).toString(), number = true, maxLength = 2)
        val minutes = form.field("Additional minutes", (initialGoal % 60).toString(), number = true, maxLength = 2)
        form.addView(label("Shorter nights appear in your history. Your activity logs once when you meet the minimum.", 13f, Theme.muted))
        val error = form.error()
        showForm(if (settings == null) "Set up Sleep" else "Sleep goal", form,
            if (settings == null) "Enable Sleep" else "Save goal") { dialog ->
            val title = name?.text?.toString()?.trim().orEmpty()
            val fullHours = hours.text.toString().toIntOrNull()
            val extraMinutes = minutes.text.toString().toIntOrNull()
            val goal = (fullHours ?: 0) * 60 + (extraMinutes ?: 0)
            if (name != null && title.isBlank()) {
                error.text = "Enter an activity name."
                return@showForm
            }
            if (fullHours == null || extraMinutes == null || extraMinutes !in 0..59 || goal !in SleepTracking.goalRange) {
                error.text = "Enter a goal between 1 and 16 hours. Minutes must be between 0 and 59."
                return@showForm
            }
            val save: (String) -> Unit = { id ->
                PhoneTracking.saveSleep(owner, settings?.copy(goalMinutes = goal) ?:
                    SleepTracking(id, SleepSchedule(), System.currentTimeMillis(), goal, estimateFromPhone = false))
                offerWidget = settings == null
                dialog.dismiss()
                render()
                maybeOfferWidget()
            }
            val current = settings?.activityId ?: Store.habitsFor(owner)
                .firstOrNull { it.name.equals(title, ignoreCase = true) }?.id
            if (current != null) save(current)
            else {
                dialog.getButton(-1).isEnabled = false
                Store.addActivity(title) { id, problem ->
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
            .setMessage("See your latest sleep and progress toward your goal.")
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
            selectedDay = day
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
            addView(label("Log once when you open an app or reach a daily time threshold.", 14f, Theme.muted))
            addAction("Add app rule", true) { appRuleEditor(owner, null, preselected) }
        })
        val habits = Store.habits.associateBy { it.id }
        for (rule in rules) {
            val habit = habits[rule.activityId] ?: continue
            root.addCard(card().apply {
                addView(title(habit.name))
                addView(label("${rule.app.label} · ${rule.triggerLabel}", 14f, Theme.muted))
                addAction("Edit rule") { appRuleEditor(owner, rule, null) }
            })
        }
    }

    private fun appRuleEditor(owner: String, rule: AppLogRule?, preselected: String?) {
        val form = form()
        val target = activityChoice(form, owner, rule?.activityId ?: preselected, optional = false)
        var selectedApp = rule?.app
        form.addView(label("App to track", 13f, Theme.muted))
        lateinit var appButton: android.widget.Button
        appButton = action(selectedApp?.label ?: "Choose an app") {
            pickApps(selectedApp?.let(::listOf).orEmpty(), multi = false) {
                selectedApp = it.firstOrNull()
                appButton.text = selectedApp?.label ?: "Choose an app"
            }
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        form.addView(appButton, LinearLayout.LayoutParams(-1, -2))
        val presetMinutes = listOf(0, 1, 5, 10, 15, 30, 60)
        val currentMinutes = rule?.minutes ?: 5
        form.addView(label("Log when", 13f, Theme.muted))
        val trigger = ChoiceView(this, (presetMinutes.map { minutes ->
            when (minutes) {
                0 -> "Opened"
                60 -> "1 hour"
                else -> "$minutes min"
            }
        } + "Custom minutes").toTypedArray(), presetMinutes.indexOf(currentMinutes).takeIf { it >= 0 } ?: presetMinutes.size)
        form.addView(trigger, LinearLayout.LayoutParams(-1, dp(48)))
        val custom = form().apply { visibility = if (currentMinutes in presetMinutes) View.GONE else View.VISIBLE }
        val minimum = custom.field("Minimum minutes per day", currentMinutes.coerceAtLeast(1).toString(), number = true)
        form.addView(custom)
        trigger.onChange = { custom.visibility = if (trigger.selectedItemPosition == presetMinutes.size) View.VISIBLE else View.GONE }
        val error = form.error()
        showForm("App rule", form, "Save rule", rule?.let {
            { PhoneTracking.saveAppRules(owner, PhoneTracking.appRules(owner).filterNot { current -> current == rule }); render() }
        }) { dialog ->
            val app = selectedApp
            val count = presetMinutes.getOrNull(trigger.selectedItemPosition) ?: minimum.text.toString().toIntOrNull()
            val activity = target()?.id
            if (activity == null) { error.text = "Choose or create an activity."; return@showForm }
            if (app == null) { error.text = "Choose an app to track."; return@showForm }
            if (count == null || count !in 0..1440 || (count == 0 && trigger.selectedItemPosition != 0)) {
                error.text = "Enter 1 to 1,440 minutes."
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
        val nameError = name?.let { form.error() }
        val available = AndroidUsage.launchableApps(this).associateBy { it.id }
        var apps = existing?.apps.orEmpty().map { available[it] ?: UsageApp(it, "Unavailable app") }
        var appsError: android.widget.TextView? = null
        if (!total) {
            form.addView(label("Apps in group", 13f, Theme.muted))
            lateinit var choose: android.widget.Button
            choose = action(if (apps.isEmpty()) "Choose apps" else "${apps.size} apps selected") {
                pickApps(apps, multi = true) {
                    apps = it
                    choose.text = if (it.isEmpty()) "Choose apps" else "${it.size} apps selected"
                    if (it.isNotEmpty()) appsError?.text = ""
                }
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            form.addView(choose, LinearLayout.LayoutParams(-1, -2))
            appsError = form.error()
        }
        val maximum = form.field("Daily limit in minutes", (existing?.minutes ?: if (total) 360 else 180).toString(), number = true)
        val minutesError = form.error()
        name?.doAfterTextChanged { nameError?.text = "" }
        maximum.doAfterTextChanged { minutesError.text = "" }
        val target = activityChoice(form, owner, existing?.activityId, optional = true)
        showForm(if (total) "Total phone limit" else "App group limit", form, "Save limit", existing?.let {
            { PhoneTracking.saveLimits(owner, PhoneTracking.limits(owner).filterNot { current -> current.id == existing.id }); render() }
        }) { dialog ->
            val count = maximum.text.toString().toIntOrNull()
            val title = if (total) "Total phone use" else name?.text.toString().trim()
            val missingApps = !total && apps.isEmpty()
            val validMinutes = count != null && count in 1..1440
            nameError?.text = if (title.isBlank()) "Give this group a name." else ""
            appsError?.text = if (missingApps) "Choose at least one app." else ""
            minutesError.text = if (validMinutes) "" else "Enter 1 to 1,440 minutes."
            if (title.isBlank() || missingApps || count == null || !validMinutes) return@showForm
            val id = existing?.id ?: if (total) ScreenLimit.totalId else ScreenLimit.newId()
            val next = ScreenLimit(id, title, apps.mapTo(mutableSetOf()) { it.id }, count, target()?.id, System.currentTimeMillis())
            PhoneTracking.saveLimits(owner, PhoneTracking.limits(owner).filterNot { it.id == id } + next)
            dialog.dismiss()
            render()
        }
    }

    private fun activityChoice(form: LinearLayout, owner: String, id: String?, optional: Boolean): () -> Habit? {
        val choices = form()
        form.addView(choices)
        var selectedId = id
        fun populate() {
            choices.removeAllViews()
            val habits = Store.habitsFor(owner)
            if (habits.isEmpty()) return
            val options = listOf<Habit?>(null) + habits
            choices.addView(label(if (optional) "Activity to log when under limit" else "Activity to log", 13f, Theme.muted))
            val choice = ChoiceView(this, options.map { it?.name ?: if (optional) "None" else "Choose an activity" }.toTypedArray(),
                options.indexOfFirst { it?.id == selectedId }.coerceAtLeast(0),
                options.map { it?.let { habit -> Theme.colors[habit.color.coerceIn(0, Theme.colors.lastIndex)] } ?: 0 }
                    .toIntArray()).apply {
                background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            }
            choices.addView(choice, LinearLayout.LayoutParams(-1, dp(48)))
            choice.onChange = { selectedId = options[choice.selectedItemPosition]?.id }
        }
        populate()
        form.addView(quietAction("+ Create activity") {
            if (Store.trackingOwner() == owner) {
                editActivity(initialName = intent.getStringExtra("activityName").orEmpty()) { createdId ->
                    selectedId = createdId
                    intent.removeExtra("activityName")
                    populate()
                }
            }
        }.apply {
            setTextColor(Theme.text)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            minHeight = dp(44)
            minimumHeight = dp(44)
        }, LinearLayout.LayoutParams(-1, -2))
        return { Store.habitsFor(owner).find { it.id == selectedId } }
    }

    private fun pickApps(selected: List<UsageApp>, multi: Boolean, onDone: (List<UsageApp>) -> Unit) {
        val apps = (AndroidUsage.launchableApps(this) + selected).distinctBy(UsageApp::id)
        val chosen = selected.associateByTo(linkedMapOf()) { it.id }
        val form = form().apply { isFocusableInTouchMode = true }
        val search = form.field("Search installed apps", "", showLabel = false).apply {
            hint = contentDescription
            setHintTextColor(Theme.muted)
        }
        val status = label("", 12f, Theme.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        form.addView(status)
        val list = object : ListView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = minOf(dp(360), resources.displayMetrics.heightPixels / 2)
                val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST))
            }
        }.apply {
            divider = null
            dividerHeight = 0
        }
        form.addView(list, LinearLayout.LayoutParams(-1, -2, 1f))
        lateinit var dialog: PrettyDialog
        val repeatedLabels = apps.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
        var matches = apps
        val icons = mutableMapOf<String, android.graphics.drawable.Drawable.ConstantState?>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = matches.size
            override fun getItem(position: Int) = matches[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@PhoneTrackingActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    addView(ImageView(this@PhoneTrackingActivity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    addView(label("", 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                    addView(label("✓", 15f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
                val app = getItem(position)
                val checked = app.id in chosen
                val icon = icons.getOrPut(app.id) {
                    runCatching { packageManager.getApplicationIcon(app.id).constantState }.getOrNull()
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(icon?.newDrawable(resources)
                    ?: packageManager.defaultActivityIcon)
                val name = if (app.label in repeatedLabels) "${app.label}\n${app.id}" else app.label
                (row.getChildAt(1) as android.widget.TextView).text = name
                row.getChildAt(2).visibility = if (checked) View.VISIBLE else View.GONE
                row.isActivated = checked
                row.contentDescription = if (checked) "$name, selected" else name
                row.background = if (checked) Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1)) else null
                return row
            }
        }
        fun updateStatus() {
            status.text = when {
                matches.isEmpty() -> "No apps found"
                multi -> "${chosen.size} selected"
                else -> "Select one app"
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            if (!multi) {
                onDone(listOf(app))
                dialog.dismiss()
            } else {
                if (app.id in chosen) chosen.remove(app.id) else chosen[app.id] = app
                adapter.notifyDataSetChanged()
                updateStatus()
            }
        }
        search.doAfterTextChanged {
            val query = it.toString().trim()
            matches = apps.filter { app -> app.label.contains(query, true) || app.id.contains(query, true) }
            adapter.notifyDataSetChanged()
            list.setSelection(0)
            updateStatus()
        }
        updateStatus()
        val builder = PrettyDialog.Builder(this).setTitle(if (multi) "Choose apps" else "Choose an app")
            .setCompact().setView(form, scroll = false).setNegativeButton("Cancel", null)
        if (multi) builder.setPositiveButton("Done") { _, _ -> onDone(chosen.values.toList()) }
        dialog = builder.create()
        form.requestFocus()
        dialog.show()
    }

    private fun showForm(title: String, form: LinearLayout, save: String, remove: (() -> Unit)? = null,
        onSave: (PrettyDialog) -> Unit) {
        lateinit var dialog: PrettyDialog
        if (remove != null) form.addAction("Remove") {
            dialog.dismiss()
            remove()
        }
        dialog = PrettyDialog.Builder(this).setTitle(title).setCompact().setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton(save, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { onSave(dialog) } }
        dialog.show()
    }

    private fun form() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.field(caption: String, value: String, number: Boolean = false,
        maxLength: Int = if (number) 4 else 80, showLabel: Boolean = true): EditText {
        if (showLabel) addView(label(caption, 13f, Theme.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
            background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(48)
            addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun LinearLayout.error() = label("", 13f, Theme.destructive).also { error ->
        error.visibility = View.GONE
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        error.doAfterTextChanged { error.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE }
        addView(error)
    }

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

internal fun Activity.openTracking(screen: String, habitId: String? = null, activityName: String? = null) {
    startActivity(Intent(this, PhoneTrackingActivity::class.java)
        .putExtra("trackingScreen", screen).putExtra("activityId", habitId).putExtra("activityName", activityName))
}
