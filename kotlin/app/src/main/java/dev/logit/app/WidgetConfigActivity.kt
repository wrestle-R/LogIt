package dev.logit.app

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class WidgetConfigActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = "log"
    private var creating = false
    private var savedSelection: (() -> List<String>)? = null
    private var savedTheme: (() -> Int)? = null
    private var pinRequested = false
    private var draftIds: List<String>? = null
    private var draftTheme: Int? = null
    private var editingOwner: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state?.getString("owner") == Store.trackingOwner()) {
            draftIds = state?.getStringArrayList("selection")
            draftTheme = state?.getInt("theme")
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("owner", editingOwner)
        savedSelection?.invoke()?.let { outState.putStringArrayList("selection", ArrayList(it)) }
        savedTheme?.invoke()?.let { outState.putInt("theme", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (editingOwner == Store.trackingOwner()) return
        draftIds = null
        draftTheme = null
        render()
    }

    private fun render() {
        val owner = Store.trackingOwner()
        editingOwner = owner
        val old = Widgets.configFor(id, owner)
        savedSelection = null
        savedTheme = null
        val page = column().apply { setBackgroundColor(Theme.bg) }
        val root = column()
        root.setPadding(dp(18), dp(18), dp(18), dp(18))
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
            addView(label(if (creating || old.length() == 0) "Set up widget" else "Edit widget", 22f).apply {
                typeface = Theme.semibold
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val habits = owner?.let(Store::habitsFor).orEmpty()
        if (owner == null || habits.isEmpty()) {
            root.addView(label(if (owner == null) "Sign in to set up this widget."
                else "Add an activity in LogIt, then return here to choose it.", 15f, Theme.muted))
            root.addView(
                button("Open LogIt") {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            )
            return
        }
        root.addView(label("Choose the activities to show on your home screen.", 14f, Theme.muted))
        val existing = old.optJSONArray("ids")
        val selected = (draftIds ?: Widgets.selectedIds(old)).toSet()
        val boxes = mutableListOf<CheckBox>()
        var updatePreview: () -> Unit = {}
        val activityList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.bordered(Theme.card, Theme.cardBorder, dp(12).toFloat(), dp(1))
        }
        root.addView(activityList, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        habits.forEachIndexed { index, h ->
            if (index > 0) activityList.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            })
            val check =
                CheckBox(this).apply {
                    text = h.name
                    textSize = 15f
                    typeface = Theme.regular
                    includeFontPadding = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(Theme.text)
                    buttonDrawable = null
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.widget_activity_checkbox, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(Theme.text)
                    compoundDrawablePadding = dp(12)
                    minHeight = dp(48)
                    setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                        null, Theme.shape(Color.WHITE, 0f),
                    )
                    isChecked = if (draftIds == null && existing == null && selected.isEmpty()) index == 0 else h.id in selected
                    setOnCheckedChangeListener { _, _ -> updatePreview() }
                }
            boxes.add(check)
            activityList.addView(check, LinearLayout.LayoutParams(-1, -2))
        }
        fun select(title: String, options: Array<String>, initial: Int, swatches: IntArray? = null): ChoiceView {
            root.addView(label(title, 12f, Theme.muted).apply {
                typeface = Theme.semibold
                setPadding(0, dp(6), 0, dp(4))
            })
            val choice = ChoiceView(this, options, initial, swatches, title)
            root.addView(
                choice,
                LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) },
            )
            return choice
        }
        val theme =
            select(
                "Appearance",
                arrayOf("Dark", "Light"),
                draftTheme ?: if (old.optString("theme") == "light") 1 else 0,
            )
        savedSelection = { habits.mapIndexedNotNull { index, habit -> habit.id.takeIf { boxes[index].isChecked } } }
        savedTheme = { theme.selectedItemPosition }
        root.addView(label("Preview", 12f, Theme.muted).apply {
            typeface = Theme.semibold
            setPadding(0, dp(4), 0, dp(4))
        })
        val manager = AppWidgetManager.getInstance(this)
        val widgetOptions = if (creating) Bundle() else manager.getAppWidgetOptions(id)
        val previewHeight = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            .takeIf { it > 0 }?.coerceIn(60, 240) ?: if (kind == "quick") 160 else 180
        val preview = FrameLayout(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(previewHeight)).apply { bottomMargin = dp(6) })
        root.addView(label("Resize the widget on your home screen to show more activities.", 13f, Theme.muted))
        val refreshPreview = {
            preview.removeAllViews()
            preview.post {
                if (preview.width > 0) {
                    val width = (preview.width / resources.displayMetrics.density).toInt()
                    val widgetWidth = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                        .takeIf { it > 0 }?.coerceAtMost(width) ?: width
                    val selectedIds = savedSelection?.invoke().orEmpty()
                    val view = Widgets.preview(
                        this, kind, widgetWidth, previewHeight, owner, selectedIds,
                        theme.selectedItemPosition == 1,
                    ).apply(this, preview)
                    preview.removeAllViews()
                    preview.addView(view, FrameLayout.LayoutParams(dp(widgetWidth), -1, Gravity.CENTER))
                }
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
                if (Store.trackingOwner() != owner) {
                    Toast.makeText(this, "Your account changed. Open widget setup again.", Toast.LENGTH_LONG).show()
                    finish()
                    return@button
                }
                val chosen = savedSelection?.invoke().orEmpty()
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "Choose at least one activity", Toast.LENGTH_SHORT).show()
                    return@button
                }
                val available = Store.habitsFor(owner).mapTo(mutableSetOf()) { it.id }
                if (chosen.any { it !in available }) {
                    Toast.makeText(this, "An activity was removed. Choose activities again.", Toast.LENGTH_LONG).show()
                    draftIds = chosen.filter { it in available }
                    draftTheme = theme.selectedItemPosition
                    render()
                    return@button
                }
                val config =
                    JSONObject()
                        .put("ids", JSONArray(chosen))
                        .put("owner", owner)
                        .put("theme", if (theme.selectedItemPosition == 0) "dark" else "light")
                if (creating) {
                    if (pinRequested) return@button
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
                            0,
                            Intent(this, PinReceiver::class.java)
                                .setAction("widget-pin:${java.util.UUID.randomUUID()}")
                                .putExtra("provider", cls.name)
                                .putExtra("config", config.toString()),
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_MUTABLE,
                        )
                    val accepted = manager.requestPinAppWidget(
                        android.content.ComponentName(this, cls),
                        Bundle(),
                        callback,
                    )
                    if (accepted) {
                        pinRequested = true
                        finish()
                    }
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
    private val pickerTitle: String = "Choose an option",
) : LinearLayout(a) {
    var selectedItemPosition = initial.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        private set

    var onChange: (() -> Unit)? = null

    private val dot = View(a)
    private val labelView = TextView(a)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(12), 0, a.dp(12), 0)
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
        setDisplayedLabel(choices.getOrElse(selectedItemPosition) { "" })
        val swatch = swatches?.getOrNull(selectedItemPosition) ?: Color.TRANSPARENT
        dot.visibility = if (swatch != Color.TRANSPARENT) View.VISIBLE else View.GONE
        if (swatch != Color.TRANSPARENT) dot.background = Theme.shape(swatch, a.dp(5).toFloat())
    }

    fun setDisplayedLabel(text: String) {
        if (labelView.text.toString() != text) labelView.text = text
    }

    private fun openDropdown() {
        PrettyDialog.Builder(a)
            .setTitle(pickerTitle)
            .setCompact()
            .setSingleChoiceItems(choices, selectedItemPosition, swatches) { _, index ->
                if (selectedItemPosition != index) {
                    selectedItemPosition = index
                    refreshLabel()
                    onChange?.invoke()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
