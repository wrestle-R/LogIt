package dev.logit.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

fun Activity.quietAction(text: String, action: () -> Unit) = button(text, action).apply {
    background = RippleDrawable(
        ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
        null, Theme.shape(android.graphics.Color.WHITE, dp(8).toFloat()),
    )
    setTextColor(Theme.muted)
    textSize = 13f
    minimumWidth = 0
    minWidth = 0
    stateListAnimator = null
}

private fun Activity.fieldLabel(text: String, topDp: Int = 0) = label(text, 13f, Theme.muted).apply {
    typeface = Theme.semibold
    setPadding(0, dp(topDp), 0, dp(6))
}

private fun Activity.inlineErrorLabel() = label("", 12f, Theme.destructive).apply {
    visibility = View.GONE
    setPadding(dp(2), dp(4), 0, 0)
}

fun Activity.editActivity(
    habit: Habit? = null,
    initialName: String = "",
    onCreated: ((String) -> Unit)? = null,
) {
    fun input(hintText: String, initial: String, type: Int) = EditText(this).apply {
        hint = hintText
        setText(initial)
        inputType = type
        setSingleLine()
        textSize = 15f
        typeface = Theme.regular
        setTextColor(Theme.text)
        setHintTextColor(Theme.muted)
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumHeight = dp(48)
        applyMonochromeCursor(this)
    }

    val fields = column().apply { background = null; setPadding(0, 0, 0, 0) }
    val name = input("e.g. Read", habit?.name ?: initialName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
        filters = arrayOf(InputFilter.LengthFilter(80))
        contentDescription = "Activity name"
    }
    val nameError = inlineErrorLabel()
    fields.addView(fieldLabel("Activity name"))
    fields.addView(name, LinearLayout.LayoutParams(-1, -2))
    fields.addView(nameError)

    val values = listOf("none", "daily", "weekly")
    val goalChoice = ChoiceView(
        this,
        arrayOf("No goal", "Every day", "Every week"),
        values.indexOf(habit?.goal?.period).coerceAtLeast(0),
        pickerTitle = "How often?",
    )
    val amountLabel = fieldLabel("", topDp = 12)
    val amountError = inlineErrorLabel()
    val amount = input("1", habit?.goal?.target?.toString() ?: "1", InputType.TYPE_CLASS_NUMBER).apply {
        filters = arrayOf(InputFilter.LengthFilter(3))
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    fun refreshGoal() {
        val selected = goalChoice.selectedItemPosition
        val label = if (selected == 1) "Logs per day" else "Logs per week"
        amountLabel.text = label
        amount.contentDescription = label
        val visibility = if (selected == 0) View.GONE else View.VISIBLE
        amountLabel.visibility = visibility
        amount.visibility = visibility
        if (selected == 0) amountError.visibility = View.GONE
        name.imeOptions = if (selected == 0) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
    }
    goalChoice.onChange = ::refreshGoal

    name.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (nameError.visibility == View.VISIBLE && !s.isNullOrBlank()) {
                nameError.visibility = View.GONE
                name.background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            }
        }
        override fun afterTextChanged(s: Editable?) {}
    })
    amount.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (amountError.visibility == View.VISIBLE) {
                amountError.visibility = View.GONE
                amount.background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            }
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    lateinit var dialog: PrettyDialog
    val sleep = Store.trackingOwner()?.let(PhoneTracking::sleep)?.takeIf { it.activityId == habit?.id }
    if (sleep != null) {
        fields.addView(fieldLabel("Sleep goal", topDp = 8))
        fields.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            val value = "${formatUsageMinutes(sleep.goalMinutes)} minimum"
            addView(label(value, 14f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("Edit", 13f, Theme.muted))
            contentDescription = "Edit Sleep goal, $value"
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                startActivity(android.content.Intent(this@editActivity, PhoneTrackingActivity::class.java)
                    .putExtra("trackingScreen", "sleep").putExtra("editSleepGoal", true))
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    fields.addView(fieldLabel(if (sleep == null) "Goal (optional)" else "Logging goal (optional)", topDp = 16))
    fields.addView(goalChoice, LinearLayout.LayoutParams(-1, dp(48)))
    fields.addView(amountLabel)
    fields.addView(amount, LinearLayout.LayoutParams(-1, -2))
    fields.addView(amountError)
    refreshGoal()

    if (habit == null && onCreated == null) {
        fields.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        fields.addView(quietAction("Set up automatic tracking") {
            val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val picker = PrettyDialog.Builder(this)
                .setTitle("Automatic tracking")
                .setView(choices)
                .setNegativeButton("Back", null)
                .setCompact()
                .create()
            val routes = listOf(
                Triple("App use", "Log when you open an app or use it for a set time.", "apps"),
                Triple("Screen time", "Log days you stay under a screen time limit.", "limits"),
                Triple("Sleep", "Log nights that meet your sleep goal.", "sleep"),
                Triple("Health Connect", "Log steps, workouts, or sleep from health data.", "health"),
            )
            routes.forEachIndexed { index, (title, detail, route) ->
                if (index > 0) choices.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)))
                choices.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                        null, Theme.shape(android.graphics.Color.WHITE, dp(8).toFloat()),
                    )
                    addView(label(title, 15f).apply {
                        typeface = Theme.semibold
                        setPadding(0, 0, 0, 0)
                    })
                    addView(label(detail, 13f, Theme.muted).apply { setPadding(0, dp(2), 0, 0) })
                    setOnClickListener {
                        picker.dismiss()
                        dialog.dismiss()
                        openTracking(route, activityName = name.text.toString().trim())
                    }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            picker.show()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 14f
            typeface = Theme.semibold
            setTextColor(Theme.text)
        }, LinearLayout.LayoutParams(-1, -2))
    }
    val action = when {
        onCreated != null -> "Create & select"
        habit == null -> "Create activity"
        else -> "Save changes"
    }
    dialog = PrettyDialog.Builder(this)
        .setTitle(if (onCreated != null) "Create activity" else if (habit == null) "New activity" else "Edit activity")
        .setView(fields)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(action, null)
        .setCompact()
        .create()

    var saving = false
    fun submit() {
        if (saving) return
        val title = name.text.toString().trim()
        if (title.isEmpty()) {
            nameError.text = "Give this activity a name"
            nameError.visibility = View.VISIBLE
            name.background = Theme.bordered(Theme.bg, Theme.destructive, dp(10).toFloat(), dp(1))
            name.requestFocus()
            return
        }
        val selected = goalChoice.selectedItemPosition
        val target = amount.text.toString().toIntOrNull()
        if (selected != 0 && (target == null || target !in 1..999)) {
            amountError.text = "Enter a number from 1 to 999"
            amountError.visibility = View.VISIBLE
            amount.background = Theme.bordered(Theme.bg, Theme.destructive, dp(10).toFloat(), dp(1))
            amount.requestFocus()
            return
        }
        val existing = habit?.goal
        val goal = if (selected == 0) null
        else if (existing?.period == values[selected] && existing.target == target) existing
        else Goal(values[selected], target!!, Dates.today())
        val save = dialog.getButton(-1)
        if (!save.isEnabled) return
        saving = true
        save.isEnabled = false
        val done: (String?, String?) -> Unit = { id, problem ->
            if (problem == null) {
                dialog.dismiss()
                if (id != null) onCreated?.invoke(id)
                if (goal != null && onCreated == null) GoalReminders.offer(this)
            } else {
                saving = false
                save.isEnabled = habit != null || name.text.isNotBlank()
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            }
        }
        if (habit == null) Store.addActivity(title, goal, done)
        else Store.edit(habit.id, title, goal) { done(null, it) }
    }

    name.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true }
        else if (actionId == EditorInfo.IME_ACTION_NEXT && goalChoice.selectedItemPosition != 0) { amount.requestFocus(); true }
        else false
    }
    amount.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
    }
    dialog.setOnShowListener {
        val save = dialog.getButton(-1)
        dialog.getButton(-2).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                null, Theme.shape(android.graphics.Color.WHITE, dp(10).toFloat()),
            )
            setTextColor(Theme.muted)
        }
        save.setOnClickListener { submit() }
        if (habit == null) {
            fun updateSave() {
                val named = name.text.isNotBlank()
                save.isEnabled = named && !saving
                save.background = if (named) Theme.shape(Theme.text, dp(10).toFloat())
                    else Theme.bordered(Theme.track, Theme.controlBorder, dp(10).toFloat(), dp(1))
                save.setTextColor(if (named) Theme.bg else Theme.muted)
            }
            name.doAfterTextChanged { updateSave() }
            updateSave()
        }
        if (habit == null) name.requestFocus()
    }
    dialog.show()
}

fun Activity.backdate(habit: Habit, includeToday: Boolean = false) {
    val today = LocalDate.parse(Dates.today())
    var day = if (includeToday) today else today.minusDays(1)
    val formatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

    val fields = column().apply { background = null; setPadding(0, 0, 0, 0) }
    val quickRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val dateValue = label("", 14f, Theme.text).apply {
        typeface = Theme.semibold
        setPadding(0, 0, 0, 0)
    }
    lateinit var refreshChips: () -> Unit

    fun chip(title: String, offset: Long) = quietAction(title) {
        day = today.minusDays(offset)
        refreshChips()
    } to offset
    val chips = if (includeToday) listOf(chip("Today", 0), chip("Yesterday", 1))
        else listOf(chip("Yesterday", 1), chip("2 days ago", 2))
    refreshChips = {
        dateValue.text = day.format(formatter)
        chips.forEach { (chip, offset) ->
            val active = day == today.minusDays(offset)
            chip.isSelected = active
            chip.background =
                if (active) Theme.shape(Theme.text, dp(8).toFloat())
                else Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
            chip.setTextColor(if (active) Theme.bg else Theme.muted)
            chip.typeface = if (active) Theme.semibold else Theme.regular
            chip.textSize = 13f
            chip.minHeight = dp(38)
            chip.minimumHeight = dp(38)
            chip.setPadding(dp(12), 0, dp(12), 0)
        }
    }
    chips.forEachIndexed { index, (view, _) ->
        quickRow.addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            if (index < chips.lastIndex) marginEnd = dp(8)
        })
    }
    fields.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

    val dateRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(12), 0)
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
        isClickable = true
        isFocusable = true
        contentDescription = "Choose date"
        addView(label("Date", 13f, Theme.muted).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(dateValue)
        addView(label("›", 18f, Theme.muted).apply { setPadding(dp(8), 0, 0, 0) })
        setOnClickListener {
            showThemedDatePicker(day, today) { picked ->
                day = picked
                refreshChips()
            }
        }
    }
    fields.addView(dateRow, LinearLayout.LayoutParams(-1, dp(48)))
    refreshChips()

    PrettyDialog.Builder(this).setTitle("Log ${habit.name}?")
        .setView(fields)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Add log") { _, _ ->
            if (includeToday && Store.habits.any { it.id == habit.id && it.count(day.toString()) > 0 }) {
                Toast.makeText(this, "${habit.name} already logged for ${dateValue.text}", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            Store.log(habit.id, 1, day.toString()) { error ->
                Toast.makeText(this, error ?: "Added for ${dateValue.text}", Toast.LENGTH_SHORT).show()
            }
        }.show()
}

private fun Activity.showThemedDatePicker(initial: LocalDate, maxDate: LocalDate, onPick: (LocalDate) -> Unit) {
    var cursorMonth = YearMonth.from(initial)
    val maxMonth = YearMonth.from(maxDate)
    var selected = initial

    val root = column().apply { background = null; setPadding(0, 0, 0, 0) }
    val monthHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val monthTitle = label("", 15f, Theme.text).apply {
        typeface = Theme.semibold
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
    }
    val prevBtn = quietAction("‹") { }.apply {
        textSize = 18f
        setTextColor(Theme.text)
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
        contentDescription = "Previous month"
        setPadding(0, 0, 0, 0)
    }
    val nextBtn = quietAction("›") { }.apply {
        textSize = 18f
        setTextColor(Theme.text)
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
        contentDescription = "Next month"
        setPadding(0, 0, 0, 0)
    }
    monthHeader.addView(prevBtn, LinearLayout.LayoutParams(dp(40), dp(36)))
    monthHeader.addView(monthTitle, LinearLayout.LayoutParams(0, -2, 1f))
    monthHeader.addView(nextBtn, LinearLayout.LayoutParams(dp(40), dp(36)))
    root.addView(monthHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

    val weekdays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (wd in listOf("M", "T", "W", "T", "F", "S", "S")) {
        weekdays.addView(label(wd, 11f, Theme.muted).apply {
            gravity = Gravity.CENTER
            typeface = Theme.semibold
            setPadding(0, 0, 0, dp(4))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    root.addView(weekdays, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(grid)

    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
    fun rebuild() {
        monthTitle.text = cursorMonth.format(monthFmt)
        val canNext = cursorMonth < maxMonth
        nextBtn.isEnabled = canNext
        nextBtn.alpha = if (canNext) 1f else .35f

        grid.removeAllViews()
        val firstDay = cursorMonth.atDay(1)
        val leadingEmpty = firstDay.dayOfWeek.value - 1 // Monday=1 -> 0
        val totalDays = cursorMonth.lengthOfMonth()
        val totalCells = ((leadingEmpty + totalDays + 6) / 7) * 7
        var row: LinearLayout? = null
        for (cell in 0 until totalCells) {
            if (cell % 7 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            val dayNum = cell - leadingEmpty + 1
            if (dayNum < 1 || dayNum > totalDays) {
                row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 1f))
                continue
            }
            val date = cursorMonth.atDay(dayNum)
            val enabled = !date.isAfter(maxDate)
            val isSelected = date == selected
            val isToday = date == maxDate
            val cellView = TextView(this).apply {
                text = dayNum.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = if (isSelected || isToday) Theme.semibold else Theme.regular
                setTextColor(
                    when {
                        isSelected -> Theme.bg
                        !enabled -> Theme.muted
                        else -> Theme.text
                    }
                )
                alpha = if (enabled) 1f else .3f
                background =
                    when {
                        isSelected -> Theme.shape(Theme.text, dp(8).toFloat())
                        isToday -> Theme.bordered(Theme.bg, Theme.controlBorder, dp(8).toFloat(), dp(1))
                        else -> null
                    }
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                contentDescription = date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                if (enabled) {
                    setOnClickListener {
                        selected = date
                        rebuild()
                    }
                }
            }
            row!!.addView(cellView, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
    }

    prevBtn.setOnClickListener { cursorMonth = cursorMonth.minusMonths(1); rebuild() }
    nextBtn.setOnClickListener { if (cursorMonth < maxMonth) { cursorMonth = cursorMonth.plusMonths(1); rebuild() } }
    rebuild()

    PrettyDialog.Builder(this)
        .setTitle("Choose date")
        .setView(root)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Select") { _, _ -> onPick(selected) }
        .show()
}
