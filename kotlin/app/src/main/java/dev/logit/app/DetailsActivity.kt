package dev.logit.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
        background = Theme.bordered(Theme.card, Theme.cardBorder, dp(14).toFloat(), dp(1))
    }

    private fun render() {
        val screen = intent.getStringExtra("screen")
        val root = column()
        root.setPadding(dp(16), dp(8), dp(16), dp(16))
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.bg); isFillViewport = true; addView(root) }
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
        val title = when (screen) { "reminders" -> "Goal reminders"; "widgets" -> "Add a widget"; else -> "Statistics" }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
            addView(quietAction("‹") { finish() }.apply {
                textSize = 22f
                setTextColor(Theme.text)
                background = Theme.bordered(Theme.card, Theme.controlBorder, dp(12).toFloat(), dp(1))
                contentDescription = "Back"
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(label(title, 22f).apply {
                typeface = Theme.semibold
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        when (screen) {
            "reminders" -> reminderControls(root)
            "widgets" -> widgetTypes(root)
            else -> statistics(root)
        }
    }

    private fun widgetTypes(root: LinearLayout) {
        root.addView(label("Pick a layout to configure and add to your home screen.", 14f, Theme.muted))
        val sleepAvailable = Store.trackingOwner()?.let(PhoneTracking::sleep) != null
        val items = listOf(
            Triple("Quick log", "Compact activity tiles for instant one-tap daily logging.", "quick"),
            Triple("Heatmap", "Recent weeks completion grid at a glance.", "graph"),
            Triple("Heatmap + log", "Completion grid with an overlaid + Log button.", "log"),
        ) + if (sleepAvailable) listOf(Triple("Sleep", "Your latest sleep and progress toward your goal.", "sleep")) else emptyList()
        items.forEachIndexed { index, (name, subtitle, kind) ->
            val card = panel().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = "$name, $subtitle"
                val textCol = LinearLayout(this@DetailsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(name, 16f, Theme.text).apply {
                        typeface = Theme.semibold
                        setPadding(0, 0, 0, 0)
                    })
                    addView(label(subtitle, 13f, Theme.muted).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label("›", 20f, Theme.muted).apply { setPadding(dp(12), 0, 0, 0) })
                setOnClickListener {
                    if (kind == "sleep") {
                        if (!SleepWidget.pin(this@DetailsActivity)) {
                            Toast.makeText(this@DetailsActivity, "This launcher couldn't add the Sleep widget.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        startActivity(Intent(this@DetailsActivity, WidgetConfigActivity::class.java).putExtra("pinKind", kind))
                        finish()
                    }
                }
            }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(if (index == 0) 10 else 10)
            })
        }
    }

    private fun reminderControls(root: LinearLayout) {
        root.addView(label("Choose which nudges you want on this phone. Times are in India time.", 14f, Theme.muted))
        val main = panel()

        fun switchRow(text: String, initial: Boolean, onToggle: (ThemedSwitch, Boolean) -> Unit): ThemedSwitch {
            lateinit var toggle: ThemedSwitch
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                addView(label(text, 15f, Theme.text).apply {
                    setPadding(0, 0, dp(12), 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                toggle = ThemedSwitch(this@DetailsActivity, initial, text) { checked ->
                    onToggle(toggle, checked)
                }
                addView(toggle, LinearLayout.LayoutParams(dp(52), dp(44)))
                setOnClickListener { toggle.performClick() }
            }
            main.addView(row, LinearLayout.LayoutParams(-1, -2))
            return toggle
        }

        switchRow("Notifications on this phone", GoalReminders.enabled()) { toggle, enabled ->
            when {
                !enabled -> GoalReminders.setEnabled(this@DetailsActivity, false)
                Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                    toggle.setChecked(false, notify = false)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), GoalReminders.permissionRequest)
                }
                !getSystemService(NotificationManager::class.java).areNotificationsEnabled() -> {
                    toggle.setChecked(false, notify = false)
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                }
                else -> GoalReminders.setEnabled(this@DetailsActivity, true)
            }
        }

        for (missed in listOf(false, true)) {
            main.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            val title = if (missed) "After a missed goal" else "Before the day ends"
            switchRow(title, GoalReminders.kindEnabled(missed)) { _, checked ->
                GoalReminders.setKind(missed, checked)
            }
            val time = GoalReminders.time(missed)
            val formatted = LocalTime.of(time / 60, time % 60).format(DateTimeFormatter.ofPattern("h:mm a"))
            val timeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                addView(label("Time", 14f, Theme.muted).apply {
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(quietAction(formatted) {
                    showThemedTimePicker(title, time) { newMinutes ->
                        GoalReminders.setTime(missed, newMinutes)
                        render()
                    }
                }.apply {
                    textSize = 13f
                    setTextColor(Theme.text)
                    typeface = Theme.semibold
                    background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
                    setPadding(dp(12), 0, dp(12), 0)
                    contentDescription = "Time · $formatted"
                }, LinearLayout.LayoutParams(-2, dp(38)))
            }
            main.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        root.addView(main, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(label("Weekly goals get a Sunday nudge and a Monday follow-up.", 13f, Theme.muted).apply {
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun showThemedTimePicker(title: String, initialMinutes: Int, onPick: (Int) -> Unit) {
        val total = initialMinutes.coerceIn(0, 23 * 60 + 59)
        var hour12 = (total / 60) % 12
        if (hour12 == 0) hour12 = 12
        var minute = total % 60
        var isPm = (total / 60) >= 12

        val body = column().apply { background = null; setPadding(0, 0, 0, 0) }
        val preview = label("", 28f, Theme.text).apply {
            typeface = Theme.semibold
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        body.addView(preview)

        fun adjustBox(labelText: String, valueView: TextView, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(label(labelText, 11f, Theme.muted).apply {
                typeface = Theme.semibold
                setPadding(0, 0, 0, dp(4))
            })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            }
            box.addView(quietAction("−", onMinus).apply {
                textSize = 18f; setTextColor(Theme.text); setPadding(0, 0, 0, 0)
                contentDescription = "Decrease $labelText"
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            box.addView(themedDivider(), LinearLayout.LayoutParams(dp(1), dp(22)))
            box.addView(valueView, LinearLayout.LayoutParams(0, dp(44), 1f))
            box.addView(themedDivider(), LinearLayout.LayoutParams(dp(1), dp(22)))
            box.addView(quietAction("+", onPlus).apply {
                textSize = 18f; setTextColor(Theme.text); setPadding(0, 0, 0, 0)
                contentDescription = "Increase $labelText"
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            col.addView(box, LinearLayout.LayoutParams(-1, -2))
            return col
        }

        val hourView = label("", 16f, Theme.text).apply { typeface = Theme.semibold; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
        val minuteView = label("", 16f, Theme.text).apply { typeface = Theme.semibold; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }
        val amBtn = quietAction("AM") {}
        val pmBtn = quietAction("PM") {}

        fun refresh() {
            val h24 = (hour12 % 12) + if (isPm) 12 else 0
            preview.text = LocalTime.of(h24, minute).format(DateTimeFormatter.ofPattern("h:mm a"))
            hourView.text = hour12.toString()
            minuteView.text = "%02d".format(minute)
            listOf(amBtn to !isPm, pmBtn to isPm).forEach { (btn, active) ->
                btn.isSelected = active
                btn.background = if (active) Theme.shape(Theme.text, dp(7).toFloat()) else null
                btn.setTextColor(if (active) Theme.bg else Theme.muted)
                btn.typeface = if (active) Theme.semibold else Theme.regular
                btn.setPadding(0, 0, 0, 0)
            }
        }

        val steppers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        steppers.addView(
            adjustBox("Hour", hourView,
                { hour12 = if (hour12 == 1) 12 else hour12 - 1; refresh() },
                { hour12 = if (hour12 == 12) 1 else hour12 + 1; refresh() }),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        steppers.addView(
            adjustBox("Minute", minuteView,
                { minute = (minute + 59) % 60; refresh() },
                { minute = (minute + 1) % 60; refresh() }),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        body.addView(steppers)

        val quickMins = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in listOf(0, 15, 30, 45)) {
            val chip = quietAction(":%02d".format(m)) { minute = m; refresh() }.apply {
                textSize = 12f
                setTextColor(Theme.text)
                background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
                setPadding(0, 0, 0, 0)
            }
            quickMins.addView(chip, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (m > 0) marginStart = dp(6)
            })
        }
        body.addView(quickMins, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val amPmTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        amBtn.setOnClickListener { isPm = false; refresh() }
        pmBtn.setOnClickListener { isPm = true; refresh() }
        amPmTrack.addView(amBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        amPmTrack.addView(pmBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        body.addView(amPmTrack, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        refresh()

        PrettyDialog.Builder(this)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set time") { _, _ ->
                val h24 = (hour12 % 12) + if (isPm) 12 else 0
                onPick(h24 * 60 + minute)
            }
            .show()
    }

    private fun statistics(root: LinearLayout) {
        val habits = Store.habits
        val selected = habits.firstOrNull { it.id == selectedHabitId }
        val selectedIndex = selected?.let { habits.indexOf(it) + 1 } ?: 0
        val swatches = intArrayOf(Color.TRANSPARENT) + habits.map { Theme.colors[it.color.coerceIn(Theme.colors.indices)] }.toIntArray()
        val choices = ChoiceView(this, (listOf("All activities") + habits.map { it.name }).toTypedArray(), selectedIndex, swatches)
        choices.onChange = {
            selectedHabitId = habits.getOrNull(choices.selectedItemPosition - 1)?.id
            render()
        }
        root.addView(choices, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        root.addView(LinearLayout(this).apply {
            background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for (days in listOf(7, 28, 84)) {
                val active = range == days
                addView(quietAction("$days days") {
                    if (range != days) {
                        range = days
                        render()
                    }
                }.apply {
                    setPadding(dp(4), 0, dp(4), 0)
                    minHeight = dp(40)
                    minimumHeight = dp(40)
                    isSelected = active
                    setTextColor(if (active) Theme.bg else Theme.muted)
                    typeface = if (active) Theme.semibold else Theme.regular
                    if (active) background = Theme.shape(Theme.text, dp(7).toFloat())
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })

        val today = LocalDate.parse(Dates.today())
        val dates = List(range) { today.minusDays((range - 1 - it).toLong()) }
        val keys = dates.map(LocalDate::toString)
        val chartHabits = selected?.let(::listOf) ?: habits
        val dailyCounts = chartHabits.map { habit -> keys.map(habit::count) }
        val totals = dates.indices.map { day -> dailyCounts.sumOf { it[day] } }
        val numbers = NumberFormat.getIntegerInstance()
        val dateFormat = DateTimeFormatter.ofPattern("d MMM")
        val summary = panel()
        summary.addView(label("${dates.first().format(dateFormat)} – ${today.format(dateFormat)}", 13f, Theme.muted))
        val total = totals.sum()
        val activeDays = totals.count { it > 0 }
        summary.addView(LinearLayout(this).apply {
            for ((value, caption) in listOf(numbers.format(total) to "Logs", "$activeDays / $range" to "Days logged")) {
                addView(LinearLayout(this@DetailsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(value, 26f).apply {
                        typeface = Theme.semibold
                        setPadding(0, dp(2), 0, 0)
                    })
                    addView(label(caption, 12f, Theme.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        if (total == 0L) {
            summary.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
            summary.addView(label("No logs in this period.", 14f, Theme.text).apply {
                typeface = Theme.semibold
                setPadding(0, 0, 0, dp(2))
            })
            summary.addView(label("Log completions from the dashboard to see trends here.", 13f, Theme.muted).apply {
                setPadding(0, 0, 0, dp(2))
            })
        } else {
            val bucketSize = if (range == 7) 1 else 7
            val series = chartHabits.mapIndexed { index, habit ->
                StatisticsSeries(habit.name, dailyCounts[index].chunked(bucketSize) { it.sum() },
                    Theme.colors[habit.color.coerceIn(Theme.colors.indices)])
            }
            summary.addView(label(if (bucketSize == 1) "Logs per day" else "Logs per week", 13f).apply {
                typeface = Theme.semibold
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            summary.addView(StatisticsChart(this, dates, series, bucketSize), LinearLayout.LayoutParams(-1, dp(132)))
        }
        root.addView(summary)
        if (selected == null && habits.isNotEmpty()) {
            root.addView(label("By activity", 16f).apply { typeface = Theme.semibold },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            root.addView(panel().apply {
                setPadding(dp(12), dp(6), dp(12), dp(4))
                addView(statisticsRow("Activity", "$range days", "All time", header = true))
                habits.forEachIndexed { index, habit ->
                    addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)))
                    val periodCount = numbers.format(dailyCounts[index].sum())
                    val allTime = numbers.format(habit.total())
                    addView(statisticsRow(habit.name, periodCount, allTime, activityColor = Theme.colors[habit.color.coerceIn(Theme.colors.indices)]).apply {
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
            val detailCard = panel().apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                fun infoRow(k: String, v: String) = LinearLayout(this@DetailsActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(40)
                    addView(label(k, 14f, Theme.muted).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(label(v, 14f, Theme.text).apply { typeface = Theme.semibold; setPadding(0, 0, 0, 0) })
                }
                addView(infoRow("All time", "${numbers.format(selected.total())} logs"))
                addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) })
                addView(infoRow("Goal", selected.goalLabel(today) ?: "No goal set"))
            }
            root.addView(detailCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private fun statisticsRow(name: String, period: String, allTime: String, header: Boolean = false, activityColor: Int? = null) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (header) 36 else 48)
            val color = if (header) Theme.muted else Theme.text
            addView(label(name, if (header) 12f else 14f, color).apply {
                if (!header) typeface = Theme.semibold
                activityColor?.let {
                    val marker = Theme.shape(it, dp(3).toFloat())
                    marker.setBounds(0, 0, dp(8), dp(8))
                    setCompoundDrawablesRelative(marker, null, null, null)
                    compoundDrawablePadding = dp(8)
                }
                setPadding(0, dp(4), dp(8), dp(4))
            }, LinearLayout.LayoutParams(0, -2, 2f))
            for (value in listOf(period, allTime)) {
                addView(label(value, if (header) 12f else 14f, color).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
}
