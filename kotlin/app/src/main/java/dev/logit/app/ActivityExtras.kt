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

    val periods = arrayOf("No goal", "Every day", "Every week")
    val values = listOf("none", "daily", "weekly")
    var selected = values.indexOf(habit?.goal?.period).coerceAtLeast(0)

    val segmentTrack = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val segmentButtons = mutableListOf<TextView>()

    val amountLabel = fieldLabel("", topDp = 14)
    val amountError = inlineErrorLabel()
    val amountContainer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
    }
    val amount = EditText(this).apply {
        hint = "1"
        setText(habit?.goal?.target?.toString() ?: "1")
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(3))
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Theme.semibold
        setTextColor(Theme.text)
        setHintTextColor(Theme.muted)
        background = null
        setPadding(dp(8), 0, dp(8), 0)
        imeOptions = EditorInfo.IME_ACTION_DONE
        contentDescription = "Goal target logs"
        applyMonochromeCursor(this)
    }
    fun stepperButton(symbol: String, description: String, step: Int) = quietAction(symbol) {
        val current = amount.text.toString().toIntOrNull() ?: 1
        val next = (current + step).coerceIn(1, 999)
        amount.setText(next.toString())
        amount.setSelection(amount.text.length)
        amountError.visibility = View.GONE
        amountContainer.background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
    }.apply {
        textSize = 19f
        setTextColor(Theme.text)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        contentDescription = description
    }
    amountContainer.addView(stepperButton("−", "Decrease goal logs", -1), LinearLayout.LayoutParams(dp(48), dp(46)))
    amountContainer.addView(themedDivider(), LinearLayout.LayoutParams(dp(1), dp(24)))
    amountContainer.addView(amount, LinearLayout.LayoutParams(0, dp(46), 1f))
    amountContainer.addView(themedDivider(), LinearLayout.LayoutParams(dp(1), dp(24)))
    amountContainer.addView(stepperButton("+", "Increase goal logs", 1), LinearLayout.LayoutParams(dp(48), dp(46)))

    fun refreshSegments() {
        segmentButtons.forEachIndexed { index, tab ->
            val active = index == selected
            tab.isSelected = active
            tab.background = if (active) Theme.shape(Theme.text, dp(7).toFloat()) else null
            tab.setTextColor(if (active) Theme.bg else Theme.muted)
            tab.typeface = if (active) Theme.semibold else Theme.regular
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                tab.stateDescription = if (active) "Selected" else "Not selected"
            }
        }
        amountLabel.text = if (selected == 1) "Logs per day" else "Logs per week"
        val visible = if (selected == 0) View.GONE else View.VISIBLE
        amountLabel.visibility = visible
        amountContainer.visibility = visible
        if (selected == 0) amountError.visibility = View.GONE
        name.imeOptions = if (selected == 0) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
    }

    periods.forEachIndexed { index, title ->
        val tab = quietAction(title) {
            selected = index
            refreshSegments()
        }.apply {
            textSize = 13f
            minHeight = dp(40)
            minimumHeight = dp(40)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Goal frequency: $title"
        }
        segmentButtons.add(tab)
        segmentTrack.addView(tab, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

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
                amountContainer.background = Theme.bordered(Theme.bg, Theme.controlBorder, dp(10).toFloat(), dp(1))
            }
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    fields.addView(fieldLabel("Goal frequency", topDp = if (habit == null) 14 else 0))
    fields.addView(segmentTrack, LinearLayout.LayoutParams(-1, -2))
    fields.addView(amountLabel)
    fields.addView(amountContainer, LinearLayout.LayoutParams(-1, -2))
    fields.addView(amountError)
    refreshSegments()

    lateinit var dialog: PrettyDialog
    if (habit == null && onCreated == null) {
        fields.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(fieldLabel("Templates & tracking").apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(quietAction("Health Connect") {
                dialog.dismiss()
                openTracking("health", activityName = name.text.toString().trim())
            }.apply {
                setTextColor(Theme.text)
                setPadding(dp(8), dp(4), dp(4), dp(4))
                minHeight = dp(44)
                minimumHeight = dp(44)
            }, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val shortcuts = LinearLayout(this)
        val routes = listOf("Sleep" to "sleep", "App use" to "apps", "Screen time" to "limits")
        for ((title, route) in routes) {
            val shortcut = quietAction(title) {
                dialog.dismiss()
                openTracking(route, activityName = name.text.toString().trim())
            }.apply {
                setTextColor(Theme.text)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                minHeight = dp(44)
                minimumHeight = dp(44)
                maxLines = 2
                background = RippleDrawable(
                    ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                    Theme.shape(Theme.track, dp(9).toFloat()),
                    null,
                )
            }
            shortcuts.addView(shortcut, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (shortcuts.childCount > 0) marginStart = dp(6)
            })
        }
        fields.addView(shortcuts, LinearLayout.LayoutParams(-1, -2))
    }
    val action = when {
        onCreated != null -> "Create & select"
        habit == null -> "Add activity"
        else -> "Save changes"
    }
    dialog = PrettyDialog.Builder(this)
        .setTitle(if (onCreated != null) "Create activity" else if (habit == null) "New activity" else "Edit activity")
        .setView(fields)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(action, null)
        .apply { if (onCreated != null) setCompact() }
        .create()

    fun submit() {
        val title = name.text.toString().trim()
        if (title.isEmpty()) {
            nameError.text = "Give this activity a name"
            nameError.visibility = View.VISIBLE
            name.background = Theme.bordered(Theme.bg, Theme.destructive, dp(10).toFloat(), dp(1))
            name.requestFocus()
            return
        }
        val target = amount.text.toString().toIntOrNull()
        if (selected != 0 && (target == null || target !in 1..999)) {
            amountError.text = "Enter a number from 1 to 999"
            amountError.visibility = View.VISIBLE
            amountContainer.background = Theme.bordered(Theme.bg, Theme.destructive, dp(10).toFloat(), dp(1))
            amount.requestFocus()
            return
        }
        val existing = habit?.goal
        val goal = if (selected == 0) null
        else if (existing?.period == values[selected] && existing.target == target) existing
        else Goal(values[selected], target!!, Dates.today())
        val save = dialog.getButton(-1)
        if (!save.isEnabled) return
        save.isEnabled = false
        val done: (String?, String?) -> Unit = { id, problem ->
            if (problem == null) {
                dialog.dismiss()
                if (id != null) onCreated?.invoke(id)
                if (goal != null && onCreated == null) GoalReminders.offer(this)
            } else {
                save.isEnabled = true
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            }
        }
        if (habit == null) Store.addActivity(title, goal, done)
        else Store.edit(habit.id, title, goal) { done(null, it) }
    }

    name.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true }
        else if (actionId == EditorInfo.IME_ACTION_NEXT && selected != 0) { amount.requestFocus(); true }
        else false
    }
    amount.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
    }
    dialog.setOnShowListener {
        dialog.getButton(-1).setOnClickListener { submit() }
        if (habit == null) name.requestFocus()
    }
    dialog.show()
}

fun Activity.backdate(habit: Habit) {
    val today = LocalDate.parse(Dates.today())
    var day = today.minusDays(1)
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

    val yesterdayChip = quietAction("Yesterday") {
        day = today.minusDays(1)
        refreshChips()
    }
    val twoDaysChip = quietAction("2 days ago") {
        day = today.minusDays(2)
        refreshChips()
    }
    val chips = listOf(yesterdayChip to 1L, twoDaysChip to 2L)
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
    quickRow.addView(yesterdayChip, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
    quickRow.addView(twoDaysChip, LinearLayout.LayoutParams(0, dp(38), 1f))
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
