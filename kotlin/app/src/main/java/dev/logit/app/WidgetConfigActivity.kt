package dev.logit.app

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class WidgetConfigActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = "log"
    private var creating = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        val pinKind = intent.getStringExtra("pinKind")?.takeIf { it in listOf("quick", "graph", "log") }
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && provider?.packageName == packageName) {
            kind = when (provider.shortClassName.substringAfterLast(".")) {
                "QuickWidget" -> "quick"
                "GraphWidget" -> "graph"
                "LogGraphWidget" -> "log"
                else -> { finish(); return }
            }
        } else if (id == AppWidgetManager.INVALID_APPWIDGET_ID && pinKind != null) {
            creating = true
            kind = pinKind
        } else {
            finish()
            return
        }
        render()
    }

    private fun render() {
        val page = column().apply { setBackgroundColor(Theme.bg) }
        val root = column()
        root.setPadding(dp(18), dp(8), dp(18), dp(24))
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (Theme.light()) flags else 0, flags)
        }
        val sc =
            ScrollView(this).apply {
                setBackgroundColor(Theme.bg)
                isFillViewport = true
            }
        page.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        sc.addView(root)
        page.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
            addView(quietAction("‹") { finish() }.apply {
                textSize = 22f
                setTextColor(Theme.text)
                background = Theme.bordered(Theme.card, Theme.controlBorder, dp(12).toFloat(), dp(1))
                contentDescription = "Back"
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(label(if (creating) "Widget setup" else "Edit widget", 22f).apply {
                typeface = Theme.semibold
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(label("Choose activities to display. Resize the widget to see more.", 14f, Theme.muted))
        if (!Store.ready || Store.habits.isEmpty()) {
            root.addView(label("Open LogIt, sign in and add an activity first."))
            root.addView(
                button("Open LogIt") {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            )
            return
        }
        val old = Widgets.config(id)
        val existing = old.optJSONArray("ids")
        val selected = Widgets.selectedIds(old).toSet()
        val boxes = mutableListOf<CheckBox>()
        var updatePreview: () -> Unit = {}
        val activityList = column().apply { setPadding(0, dp(6), 0, dp(6)) }
        root.addView(activityList)
        val habits = Store.habits
        val automated = Store.trackingOwner()?.let(PhoneTracking::automatedActivities).orEmpty()
        habits.forEachIndexed { index, h ->
            val check =
                CheckBox(this).apply {
                    text = h.name
                    textSize = 15f
                    setTextColor(Theme.text)
                    buttonTintList = ColorStateList.valueOf(Theme.text)
                    minHeight = dp(48)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = Theme.bordered(Theme.card, Theme.cardBorder, dp(12).toFloat(), dp(1))
                    isChecked = if (existing == null && selected.isEmpty()) index == 0 else h.id in selected
                    setOnCheckedChangeListener { _, _ -> updatePreview() }
                }
            boxes.add(check)
            activityList.addView(check, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        fun select(title: String, options: Array<String>, initial: Int, swatches: IntArray? = null): ChoiceView {
            root.addView(label(title, 12f, Theme.muted).apply {
                typeface = Theme.semibold
                setPadding(0, dp(6), 0, dp(4))
            })
            val choice = ChoiceView(this, options, initial, swatches)
            root.addView(
                choice,
                LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) },
            )
            return choice
        }
        val theme =
            select(
                "Surface",
                arrayOf("Dark", "Light"),
                if (old.optString("theme") == "light") 1 else 0,
            )
        root.addView(label("Preview", 12f, Theme.muted).apply {
            typeface = Theme.semibold
            setPadding(0, dp(4), 0, dp(4))
        })
        val preview = column().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(16) })
        val refreshPreview = {
            val light = theme.selectedItemPosition == 1
            val foreground = if (light) 0xff18181b.toInt() else 0xfffafafa.toInt()
            val muted = if (light) 0xff52525b.toInt() else 0xffa1a1aa.toInt()
            preview.removeAllViews()
            preview.background = Theme.bordered(
                if (light) 0xfffafafa.toInt() else 0xff101012.toInt(),
                Theme.controlBorder,
                dp(16).toFloat(),
                dp(1),
            )
            val chosen = habits.filterIndexed { i, _ -> boxes[i].isChecked }
            if (chosen.isEmpty()) preview.addView(label("Select an activity to preview", 14f, muted))
            chosen.forEachIndexed { index, habit ->
                if (index > 0) preview.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
                val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                heading.addView(label(habit.name, 15f, foreground).apply { typeface = Theme.semibold; setPadding(0, 0, dp(6), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(label(habit.widgetTodayLabel(habit.id in automated), 12f, muted))
                preview.addView(heading)
                if (kind != "quick") {
                    val graph = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = "${habit.name} widget preview"
                    }
                    preview.addView(graph, LinearLayout.LayoutParams(-1, dp(116)))
                    graph.post {
                        if (graph.width > 0) {
                            val inset = Theme.centeredGraphInset(graph.width, resources.displayMetrics.density)
                            heading.setPadding(inset, 0, inset, 0)
                            graph.setImageBitmap(Theme.graph(habit, 52, graph.width, dp(116), light = light,
                                binary = habit.id in automated, center = true))
                        }
                    }
                }
                if (kind != "graph") preview.addView(TextView(this).apply {
                    text = "+ Log ${habit.name}"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(if (light) 0xfffafafa.toInt() else 0xff18181b.toInt())
                    background = Theme.shape(foreground, dp(10).toFloat())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            Unit
        }
        updatePreview = refreshPreview
        theme.onChange = refreshPreview
        refreshPreview()
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Theme.bg)
        }
        page.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)))
        page.addView(footer, LinearLayout.LayoutParams(-1, -2))
        footer.addView(
            button("Cancel") { finish() }.apply {
                textSize = 14f
                stateListAnimator = null
                minimumWidth = 0
                background = Theme.bordered(Theme.track, Theme.controlBorder, dp(10).toFloat(), dp(1))
                setTextColor(Theme.text)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) },
        )
        footer.addView(
            button(if (creating) "Add widget" else "Save changes") {
                val chosen = habits.mapIndexedNotNull { i, habit ->
                    habit.id.takeIf { boxes[i].isChecked }
                }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "Choose at least one activity", Toast.LENGTH_SHORT).show()
                    return@button
                }
                val config =
                    JSONObject()
                        .put("ids", JSONArray(chosen))
                        .put("owner", Store.trackingOwner() ?: return@button)
                        .put("theme", if (theme.selectedItemPosition == 0) "dark" else "light")
                if (creating) {
                    val manager = AppWidgetManager.getInstance(this)
                    if (!manager.isRequestPinAppWidgetSupported) {
                        Toast.makeText(
                                this,
                                "Add LogIt from your launcher’s widget picker",
                                Toast.LENGTH_LONG,
                            )
                            .show()
                        return@button
                    }
                    val cls =
                        when (kind) {
                            "quick" -> QuickWidget::class.java
                            "graph" -> GraphWidget::class.java
                            else -> LogGraphWidget::class.java
                        }
                    val callback =
                        PendingIntent.getBroadcast(
                            this,
                            config.toString().hashCode(),
                            Intent(this, PinReceiver::class.java)
                                .putExtra("config", config.toString()),
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_MUTABLE,
                        )
                    val accepted = manager.requestPinAppWidget(
                        android.content.ComponentName(this, cls),
                        Bundle(),
                        callback,
                    )
                    if (accepted) finish()
                    else Toast.makeText(this, "The launcher couldn't add this widget", Toast.LENGTH_LONG).show()
                    return@button
                }
                val manager = AppWidgetManager.getInstance(this)
                if (manager.getAppWidgetInfo(id) == null) {
                    Toast.makeText(this, "This widget was removed", Toast.LENGTH_LONG).show()
                    finish()
                    return@button
                }
                Store.prefs.edit().putString("widget_$id", config.toString()).apply()
                Widgets.update(this, manager, id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }.apply {
                textSize = 14f
                typeface = Theme.semibold
                stateListAnimator = null
                background = Theme.shape(Theme.text, dp(10).toFloat())
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
    }
}

class ChoiceView(
    private val a: Activity,
    private val choices: Array<String>,
    initial: Int,
    private val swatches: IntArray? = null,
) : LinearLayout(a) {
    var selectedItemPosition = initial.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        private set

    var onChange: (() -> Unit)? = null

    private val dot = View(a)
    private val labelView = TextView(a)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(14), 0, a.dp(12), 0)
        background = Theme.bordered(Theme.card, Theme.controlBorder, a.dp(10).toFloat(), a.dp(1))
        isClickable = true
        isFocusable = true

        addView(dot, LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(8) })
        labelView.apply {
            setTextColor(Theme.text)
            textSize = 14f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(labelView, LayoutParams(0, -2, 1f))
        val arrow = ImageView(a).apply {
            setImageDrawable(a.getDrawable(R.drawable.ic_chevron_down)!!.mutate().apply { setTint(Theme.muted) })
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(arrow, LayoutParams(a.dp(18), a.dp(18)).apply { marginStart = a.dp(8) })

        refreshLabel()
        setOnClickListener { openDropdown() }
    }

    private fun refreshLabel() {
        val textValue = choices.getOrElse(selectedItemPosition) { "" }
        labelView.text = textValue
        val swatch = swatches?.getOrNull(selectedItemPosition) ?: Color.TRANSPARENT
        dot.visibility = if (swatch != Color.TRANSPARENT) View.VISIBLE else View.GONE
        if (swatch != Color.TRANSPARENT) dot.background = Theme.shape(swatch, a.dp(5).toFloat())
    }

    private fun openDropdown() {
        val list = LinearLayout(a).apply {
            orientation = VERTICAL
            setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
        }
        val scroll = object : ScrollView(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = a.dp(280)
                val capped = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list)
        }
        val popupWidth = maxOf(width, a.dp(200))
        val popup = PopupWindow(scroll, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(Theme.bordered(Theme.card, Theme.controlBorder, a.dp(12).toFloat(), a.dp(1)))
            elevation = a.dp(10).toFloat()
            isOutsideTouchable = true
        }
        choices.forEachIndexed { index, item ->
            val selected = index == selectedItemPosition
            val row = LinearLayout(a).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = a.dp(44)
                setPadding(a.dp(12), 0, a.dp(12), 0)
                background =
                    if (selected) Theme.bordered(Theme.bg, Theme.controlBorder, a.dp(8).toFloat(), a.dp(1))
                    else RippleDrawable(
                        ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                        null, Theme.shape(Color.WHITE, a.dp(8).toFloat()),
                    )
                isClickable = true
                isFocusable = true
                val swatch = swatches?.getOrNull(index) ?: Color.TRANSPARENT
                if (swatch != Color.TRANSPARENT) {
                    addView(View(a).apply {
                        background = Theme.shape(swatch, a.dp(5).toFloat())
                    }, LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(8) })
                }
                addView(TextView(a).apply {
                    text = item
                    textSize = 14f
                    setTextColor(Theme.text)
                    typeface = if (selected) Theme.semibold else Theme.regular
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LayoutParams(0, -2, 1f))
                if (selected) {
                    addView(TextView(a).apply {
                        text = "✓"
                        textSize = 14f
                        setTextColor(Theme.text)
                        typeface = Theme.semibold
                        setPadding(a.dp(8), 0, 0, 0)
                    })
                }
                setOnClickListener {
                    popup.dismiss()
                    if (selectedItemPosition != index) {
                        selectedItemPosition = index
                        refreshLabel()
                        onChange?.invoke()
                    }
                }
            }
            list.addView(row, LayoutParams(-1, a.dp(44)).apply {
                if (index > 0) topMargin = a.dp(2)
            })
        }
        popup.showAsDropDown(this, 0, a.dp(4))
    }
}
