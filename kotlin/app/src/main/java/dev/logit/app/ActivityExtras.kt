package dev.logit.app

import android.app.Activity
import android.app.DatePickerDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

fun Activity.quietAction(text: String, action: () -> Unit) = button(text, action).apply {
    background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
        null, Theme.shape(android.graphics.Color.WHITE, dp(8).toFloat()),
    )
    setTextColor(Theme.muted)
    textSize = 13f
    minimumWidth = 0
    minWidth = 0
    stateListAnimator = null
}

fun MainActivity.editActivity(habit: Habit? = null) {
    fun input(hintText: String, initial: String, type: Int) = EditText(this).apply {
        hint = hintText
        setText(initial)
        inputType = type
        setSingleLine()
        setTextColor(Theme.text)
        setHintTextColor(Theme.muted)
        background = Theme.shape(Theme.bg, dp(10).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    val fields = column().apply { background = null; setPadding(0, 0, 0, 0) }
    val name = input("Activity name", habit?.name.orEmpty(), InputType.TYPE_CLASS_TEXT)
    name.filters = arrayOf(android.text.InputFilter.LengthFilter(80))
    if (habit == null) fields.addView(name)
    val periods = arrayOf("No goal", "Every day", "Every week")
    val values = listOf("none", "daily", "weekly")
    var selected = values.indexOf(habit?.goal?.period).coerceAtLeast(0)
    val amount = input("Number of logs", habit?.goal?.target?.toString() ?: "1", InputType.TYPE_CLASS_NUMBER)
    val amountLabel = label("", 13f, Theme.muted)
    val choice = ChoiceView(this, periods, selected)
    fun refresh() {
        amountLabel.text = if (selected == 1) "Logs per day" else "Logs per week"
        amount.visibility = if (selected == 0) View.GONE else View.VISIBLE
        amountLabel.visibility = amount.visibility
    }
    choice.onChange = { selected = choice.selectedItemPosition; refresh() }
    fields.addView(label("Goal", 13f, Theme.muted))
    fields.addView(choice, LinearLayout.LayoutParams(-1, dp(48)))
    fields.addView(amountLabel)
    fields.addView(amount)
    refresh()
    val dialog = PrettyDialog.Builder(this)
        .setTitle(if (habit == null) "New activity" else "Goal for ${habit.name}")
        .setView(fields)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(if (habit == null) "Create" else "Save goal", null)
        .create()
    dialog.setOnShowListener {
        val save = dialog.getButton(-1)
        save.setOnClickListener {
            val title = name.text.toString().trim()
            if (title.isEmpty()) { name.error = "Give it a name"; return@setOnClickListener }
            val target = amount.text.toString().toIntOrNull()
            if (selected != 0 && (target == null || target !in 1..999)) {
                amount.error = "Enter a number from 1 to 999"
                return@setOnClickListener
            }
            val existing = habit?.goal
            val goal = if (selected == 0) null else if (existing?.period == values[selected] && existing.target == target) existing
                else Goal(values[selected], target!!, Dates.today())
            save.isEnabled = false
            val done: (String?) -> Unit = { problem ->
                if (problem == null) {
                    dialog.dismiss()
                    if (goal != null) GoalReminders.offer(this)
                } else {
                    save.isEnabled = true
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                }
            }
            if (habit == null) Store.add(title, goal, done) else Store.setGoal(habit.id, goal, done)
        }
    }
    dialog.show()
}

fun Activity.backdate(habit: Habit) {
    val today = LocalDate.parse(Dates.today())
    var day = today.minusDays(1)
    val date = label("", 15f)
    fun refresh() { date.text = day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) }
    refresh()
    val fields = column().apply {
        background = null
        setPadding(0, 0, 0, 0)
        addView(date)
        addView(quietAction("Choose date") {
            val style = if (Theme.light()) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
            val picker = DatePickerDialog(this@backdate, style, { _, year, month, number ->
                day = LocalDate.of(year, month + 1, number)
                refresh()
            }, day.year, day.monthValue - 1, day.dayOfMonth)
            picker.datePicker.maxDate = Calendar.getInstance().apply {
                clear()
                set(today.year, today.monthValue - 1, today.dayOfMonth)
            }.timeInMillis
            picker.show()
        }.apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
        }, LinearLayout.LayoutParams(-2, dp(48)))
    }
    PrettyDialog.Builder(this).setTitle("Log ${habit.name}?").setView(fields)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Add log") { _, _ ->
            Store.log(habit.id, 1, day.toString()) { error ->
                Toast.makeText(this, error ?: "Added for ${date.text}", Toast.LENGTH_SHORT).show()
            }
        }.show()
}

private fun statCount(value: Long, noun: String) = "$value $noun${if (value == 1L) "" else "s"}"

fun Activity.showStatistics() {
    val today = LocalDate.parse(Dates.today())
    val list = column().apply { background = null; setPadding(0, 0, 0, 0) }
    if (Store.habits.isEmpty()) list.addView(label("Log an activity to start seeing progress here.", 14f, Theme.muted))
    for (habit in Store.habits) {
        val days = (habit.completions.toSet() + habit.counts.keys)
            .filter { it <= today.toString() && habit.count(it) > 0 }
        val last28 = (27 downTo 0).map { habit.count(today.minusDays(it.toLong()).toString()) }
        list.addView(label(habit.name, 16f).apply { setTypeface(null, Typeface.BOLD) })
        list.addView(label(
            if (days.isEmpty()) "Your first log will show up here."
            else "${statCount(last28.sum(), "log")} across ${statCount(last28.count { it > 0 }.toLong(), "day")} · ${statCount(days.size.toLong(), "active day")} overall",
            13f, Theme.muted,
        ))
        if (days.isNotEmpty()) {
            val values = last28.chunked(7).map { it.sum() }
            val chart = LinearLayout(this).apply {
                gravity = android.view.Gravity.BOTTOM
                contentDescription = "Logs in four consecutive seven-day periods, oldest first: ${values.joinToString()}"
            }
            values.forEach { value ->
                val bar = column().apply {
                    addView(label(value.toString(), 12f, Theme.muted).apply { gravity = android.view.Gravity.CENTER })
                    addView(View(this@showStatistics).apply {
                        background = Theme.shape(if (value == 0L) Theme.muted else Theme.colors[habit.color.coerceIn(0, 9)], dp(4).toFloat())
                    }, LinearLayout.LayoutParams(-1, dp(maxOf(2, (48 * value / values.max().coerceAtLeast(1)).toInt()))))
                }
                chart.addView(bar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            }
            list.addView(chart)
            val formatter = DateTimeFormatter.ofPattern("d MMM")
            list.addView(LinearLayout(this).apply {
                addView(label(today.minusDays(27).format(formatter), 11f, Theme.muted), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(today.format(formatter), 11f, Theme.muted))
            })
        }
        list.addView(Space(this), LinearLayout.LayoutParams(1, dp(12)))
    }
    val scroll = ScrollView(this).apply {
        addView(list)
        layoutParams = LinearLayout.LayoutParams(-1, dp(320).coerceAtMost(resources.displayMetrics.heightPixels / 2))
    }
    PrettyDialog.Builder(this).setTitle("Your statistics")
        .setMessage("How often you showed up in the last 28 days.")
        .setView(scroll).setNegativeButton("Close", null).show()
}
