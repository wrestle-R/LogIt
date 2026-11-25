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
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class DetailsActivity : Activity() {
    private var range = 28
    private var selectedHabitId: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        range = state?.getInt("range", 28) ?: 28
        selectedHabitId = state?.getString("selectedHabitId")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("range", range)
        outState.putString("selectedHabitId", selectedHabitId)
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
        val title = when (screen) { "reminders" -> "Goal reminders"; "widgets" -> "Add a widget"; else -> "Statistics" }
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
        val habits = Store.habits
        val selected = habits.firstOrNull { it.id == selectedHabitId }
        val selectedIndex = selected?.let { habits.indexOf(it) + 1 } ?: 0
        val choices = ChoiceView(this, (listOf("All activities") + habits.map { it.name }).toTypedArray(), selectedIndex)
        choices.onChange = {
            selectedHabitId = habits.getOrNull(choices.selectedItemPosition - 1)?.id
            render()
        }
        root.addView(choices, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(LinearLayout(this).apply {
            for (days in listOf(7, 28, 84)) {
                addView(quietAction("$days days") {
                    if (range != days) {
                        range = days
                        render()
                    }
                }.apply {
                    setPadding(dp(4), 0, dp(4), 0)
                    minHeight = dp(44)
                    minimumHeight = dp(44)
                    isSelected = range == days
                    if (isSelected) {
                        setTextColor(Theme.bg)
                        background = Theme.shape(Theme.text, dp(8).toFloat())
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(10) })

        val today = LocalDate.parse(Dates.today())
        val dates = List(range) { today.minusDays((range - 1 - it).toLong()) }
        val keys = dates.map(LocalDate::toString)
        val dailyCounts = (selected?.let(::listOf) ?: habits).map { habit -> keys.map(habit::count) }
        val totals = dates.indices.map { day -> dailyCounts.sumOf { it[day] } }
        val numbers = NumberFormat.getIntegerInstance()
        val dateFormat = DateTimeFormatter.ofPattern("d MMM")
        val summary = panel()
        summary.addView(label("${dates.first().format(dateFormat)} – ${today.format(dateFormat)}", 13f, Theme.muted))
        val total = totals.sum()
        val activeDays = totals.count { it > 0 }
        summary.addView(label("${numbers.format(total)} ${if (total == 1L) "log" else "logs"}", 24f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
        })
        summary.addView(label("$activeDays of $range days with a log", 14f, Theme.muted))
        if (total == 0L) {
            summary.addView(label("No logs in this period.", 14f, Theme.muted))
        } else {
            val bucketSize = if (range == 7) 1 else 7
            val values = totals.chunked(bucketSize) { it.sum() }
            summary.addView(label(if (bucketSize == 1) "Logs per day" else "Logs per week", 13f).apply {
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val color = selected?.let { Theme.colors[it.color.coerceIn(Theme.colors.indices)] } ?: Theme.text
            summary.addView(StatisticsChart(this, dates, values, bucketSize, color), LinearLayout.LayoutParams(-1, dp(106)))
        }
        root.addView(summary)
        if (selected == null && habits.isNotEmpty()) {
            root.addView(label("By activity", 16f).apply { setTypeface(null, Typeface.BOLD) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            root.addView(panel().apply {
                setPadding(dp(12), dp(6), dp(12), dp(4))
                addView(statisticsRow("Activity", "$range days", "All time", header = true))
                habits.forEachIndexed { index, habit ->
                    addView(View(this@DetailsActivity).apply { setBackgroundColor(Theme.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
                    val periodCount = numbers.format(dailyCounts[index].sum())
                    val allTime = numbers.format(habit.total())
                    addView(statisticsRow(habit.name, periodCount, allTime).apply {
                        contentDescription = "${habit.name}, $periodCount logs in $range days, $allTime all time"
                        isFocusable = true
                        setOnClickListener {
                            selectedHabitId = habit.id
                            render()
                        }
                    })
                }
            })
        } else if (selected != null) {
            root.addView(label("All time: ${numbers.format(selected.total())} logs", 14f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            selected.goalLabel(today)?.let { root.addView(label("Goal: $it", 14f, Theme.muted)) }
        }
    }

    private fun statisticsRow(name: String, period: String, allTime: String, header: Boolean = false) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (header) 36 else 48)
            val color = if (header) Theme.muted else Theme.text
            addView(label(name, if (header) 12f else 14f, color).apply {
                if (!header) setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), dp(8), dp(4))
            }, LinearLayout.LayoutParams(0, -2, 2f))
            for (value in listOf(period, allTime)) {
                addView(label(value, if (header) 12f else 14f, color).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
}
