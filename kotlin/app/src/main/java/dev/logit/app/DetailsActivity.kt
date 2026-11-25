package dev.logit.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class DetailsActivity : Activity() {
    private var range = 28
    private var activityIndex = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        range = state?.getInt("range", 28) ?: 28
        activityIndex = state?.getInt("activityIndex") ?: 0
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("range", range)
        outState.putInt("activityIndex", activityIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GoalReminders.permissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            GoalReminders.setEnabled(this, true)
        render()
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = Theme.shape(Theme.card, dp(14).toFloat())
    }

    private fun render() {
        val screen = intent.getStringExtra("screen")
        val root = column()
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) }
        setContentView(scroll)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (Theme.light()) flags else 0, flags)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        root.addView(quietAction("‹  Back") { finish() }, LinearLayout.LayoutParams(-2, dp(44)))
        val title = when (screen) { "reminders" -> "Goal reminders"; "widgets" -> "Add a widget"; else -> "Your statistics" }
        root.addView(label(title, 27f).apply { setTypeface(null, Typeface.BOLD) })
        when (screen) {
            "reminders" -> reminderControls(root)
            "widgets" -> listOf("Quick log" to "quick", "Heatmap" to "graph", "Heatmap + log" to "log").forEach { (name, kind) ->
                root.addView(quietAction(name) {
                    startActivity(Intent(this, WidgetConfigActivity::class.java).putExtra("pinKind", kind))
                    finish()
                }, LinearLayout.LayoutParams(-1, dp(52)))
            }
            else -> statistics(root)
        }
    }

    private fun reminderControls(root: LinearLayout) {
        root.addView(label("Choose which nudges you want on this phone. Times are in India time.", 14f, Theme.muted))
        val main = panel()
        main.addView(Switch(this).apply {
            text = "Notifications on this phone"
            setTextColor(Theme.text)
            minimumHeight = dp(44)
            isChecked = GoalReminders.enabled()
            setOnCheckedChangeListener { _, enabled ->
                when {
                    !enabled -> GoalReminders.setEnabled(this@DetailsActivity, false)
                    Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), GoalReminders.permissionRequest)
                    !getSystemService(NotificationManager::class.java).areNotificationsEnabled() ->
                        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                    else -> GoalReminders.setEnabled(this@DetailsActivity, true)
                }
            }
        })
        for (missed in listOf(false, true)) {
            main.addView(Switch(this).apply {
                text = if (missed) "After a missed goal" else "Before the day ends"
                setTextColor(Theme.text)
                minimumHeight = dp(44)
                isChecked = GoalReminders.kindEnabled(missed)
                setOnCheckedChangeListener { _, checked -> GoalReminders.setKind(missed, checked) }
            })
            val time = GoalReminders.time(missed)
            val formatted = LocalTime.of(time / 60, time % 60).format(DateTimeFormatter.ofPattern("h:mm a"))
            main.addView(quietAction("Time · $formatted") {
                val theme = if (Theme.light()) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
                TimePickerDialog(this, theme, { _, hours, minutes ->
                    GoalReminders.setTime(missed, hours * 60 + minutes)
                    render()
                }, time / 60, time % 60, false).show()
            }, LinearLayout.LayoutParams(-2, dp(44)))
        }
        root.addView(main, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(label("Weekly goals get a Sunday nudge and a Monday follow-up.", 13f, Theme.muted))
    }

    private fun statistics(root: LinearLayout) {
        val options = listOf(7, 28, 84)
        val habits = Store.habits
        val choices = ChoiceView(this, (listOf("All activities") + habits.map { it.name }).toTypedArray(), activityIndex.coerceIn(0, habits.size))
        choices.onChange = { activityIndex = choices.selectedItemPosition; render() }
        root.addView(choices, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        val periods = ChoiceView(this, options.map { "Last $it days" }.toTypedArray(), options.indexOf(range))
        periods.onChange = { range = options[periods.selectedItemPosition]; render() }
        root.addView(periods, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        val selected = if (activityIndex == 0) habits else listOfNotNull(habits.getOrNull(activityIndex - 1))
        val today = LocalDate.parse(Dates.today())
        val counts = (range - 1 downTo 0).map { offset ->
            selected.sumOf { it.count(today.minusDays(offset.toLong()).toString()) }
        }
        val panel = panel()
        panel.addView(label("Activity over time", 16f).apply { setTypeface(null, Typeface.BOLD) })
        panel.addView(label("${counts.sum()} logs  ·  ${counts.count { it > 0 }} / $range active days", 15f, Theme.muted))
        if (counts.none { it > 0 }) panel.addView(label("Your next log will show up here.", 14f, Theme.muted))
        else {
            val bars = if (range == 7) counts else counts.chunked(7).map { it.sum() }
            val chart = LinearLayout(this).apply {
                gravity = android.view.Gravity.BOTTOM
                contentDescription = "Logs by ${if (range == 7) "day" else "week"}, oldest first: ${bars.joinToString()}"
            }
            for (value in bars) chart.addView(View(this).apply {
                val color = selected.singleOrNull()?.color ?: 0
                background = Theme.shape(if (value == 0L) Theme.muted else Theme.colors[color.coerceIn(0, 9)], dp(3).toFloat())
            }, LinearLayout.LayoutParams(0, dp(maxOf(3, (92 * value / bars.max()).toInt())), 1f).apply { marginEnd = dp(4) })
            panel.addView(chart, LinearLayout.LayoutParams(-1, dp(110)))
            val dates = DateTimeFormatter.ofPattern("d MMM")
            panel.addView(LinearLayout(this).apply {
                addView(label(today.minusDays((range - 1).toLong()).format(dates), 12f, Theme.muted), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(today.format(dates), 12f, Theme.muted))
            })
        }
        root.addView(panel)
        for (habit in selected) {
            val recent = (0 until range).sumOf { habit.count(today.minusDays(it.toLong()).toString()) }
            root.addView(panel().apply {
                addView(label(habit.name, 16f).apply { setTypeface(null, Typeface.BOLD) })
                addView(label("$recent in this period  ·  ${habit.total()} all time", 13f, Theme.muted))
                habit.goalLabel(today)?.let { addView(label(it, 13f, Theme.muted)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }
}
