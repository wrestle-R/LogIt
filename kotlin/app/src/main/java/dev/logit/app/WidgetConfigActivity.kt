package dev.logit.app

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class WidgetConfigActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pinKind: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        pinKind = intent.getStringExtra("pinKind")
        if (pinKind !in listOf("quick", "graph", "log")) pinKind = null
        if (
            pinKind == null &&
                (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
                    AppWidgetManager.getInstance(this)
                        .getAppWidgetInfo(id)
                        ?.provider
                        ?.packageName != packageName)
        ) {
            finish()
            return
        }
        if (pinKind == null) pinKind = when (AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.shortClassName?.substringAfterLast(".")) {
            "QuickWidget" -> "quick"
            "GraphWidget" -> "graph"
            else -> "log"
        }
        render()
    }

    private fun render() {
        val root = column()
        root.setPadding(dp(20), dp(12), dp(20), dp(24))
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        window.decorView.systemUiVisibility = if (Theme.light()) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val sc =
            ScrollView(this).apply {
                setBackgroundColor(Theme.bg)
                isFillViewport = true
            }
        sc.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        sc.addView(root)
        setContentView(sc)
        root.addView(label("Widget setup", 22f).apply { setTypeface(null, 1) })
        root.addView(label("Choose your activities and make it yours.", 14f, Theme.muted))
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
        val old = JSONObject(Store.prefs.getString("widget_$id", "{}") ?: "{}")
        val existing = old.optJSONArray("ids")
        val selected = mutableSetOf<String>()
        if (existing != null)
            (0 until existing.length()).forEach { selected.add(existing.getString(it)) }
        val boxes = mutableListOf<CheckBox>()
        var updatePreview: () -> Unit = {}
        val activityList = column().apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(activityList)
        Store.habits.forEachIndexed { index, h ->
            val check =
                CheckBox(this).apply {
                    text = h.name
                    setTextColor(Theme.text)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Theme.text)
                    minHeight = dp(48)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = Theme.shape(Theme.card, dp(12).toFloat())
                    isChecked = if (existing == null) index == 0 else selected.contains(h.id)
                    setOnCheckedChangeListener { b, checked ->
                        if (checked && boxes.count { it.isChecked } > 4) {
                            b.isChecked = false
                            Toast.makeText(
                                    this@WidgetConfigActivity,
                                    "Choose up to four activities",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        updatePreview()
                    }
                }
            boxes.add(check)
            activityList.addView(check, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        fun select(title: String, options: Array<String>, initial: Int): ChoiceView {
            root.addView(label(title, 13f, Theme.muted))
            val choice = ChoiceView(this, options, initial)
            root.addView(
                choice,
                LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(6) },
            )
            return choice
        }
        val mode =
            select(
                "Display",
                arrayOf("Selected activities", "App page"),
                if (old.optInt("pageIndex", -1) >= 0) 1 else 0,
            )
        val pages =
            select(
                "Page",
                Array(maxOf(1, (Store.habits.size + 3) / 4)) { "Page ${it+1}" },
                old.optInt("pageIndex", 0).coerceIn(0, maxOf(0, (Store.habits.size - 1) / 4)),
            )
        val color = select("Colour", Theme.names, old.optInt("color", 0).coerceIn(0, 9))
        val theme =
            select(
                "Surface",
                arrayOf("Dark", "Light"),
                if (old.optString("theme") == "light") 1 else 0,
            )
        val gradient =
            Switch(this).apply {
                text = "Preset gradient"
                setTextColor(Theme.text)
                thumbTintList = android.content.res.ColorStateList.valueOf(Theme.colors[0])
                isChecked = old.optBoolean("gradient")
            }
        root.addView(gradient)
        root.addView(label("Preview", 13f, Theme.muted))
        val preview = column().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(16) })
        val refreshPreview = {
            val light = theme.selectedItemPosition == 1
            val foreground = if (light) 0xff18181b.toInt() else 0xfffafafa.toInt()
            val muted = if (light) 0xff52525b.toInt() else 0xffa1a1aa.toInt()
            activityList.visibility = if (mode.selectedItemPosition == 0) android.view.View.VISIBLE else android.view.View.GONE
            pages.visibility = if (mode.selectedItemPosition == 1) android.view.View.VISIBLE else android.view.View.GONE
            root.getChildAt(root.indexOfChild(pages) - 1).visibility = pages.visibility
            preview.removeAllViews()
            preview.background = Theme.shape(if (light) 0xfffafafa.toInt() else 0xff101012.toInt(), dp(20).toFloat())
            val chosen = if (mode.selectedItemPosition == 1)
                Store.habits.drop(pages.selectedItemPosition * 4).take(4)
            else Store.habits.filterIndexed { i, _ -> boxes[i].isChecked }
            if (chosen.isEmpty()) preview.addView(label("Select an activity to preview", 14f, muted))
            chosen.forEach { habit ->
                val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                heading.addView(label(habit.name, 15f, foreground).apply { setTypeface(null, 1) }, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(label("${habit.count(Dates.today())} today", 12f, muted))
                preview.addView(heading)
                if (pinKind != "quick") {
                    val graph = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = "${habit.name} widget preview"
                    }
                    preview.addView(graph, LinearLayout.LayoutParams(-1, dp(116)))
                    graph.post {
                        if (graph.width > 0) graph.setImageBitmap(Theme.graph(habit, 52, graph.width, dp(116), color.selectedItemPosition, light, gradient.isChecked, center = true))
                    }
                }
                if (pinKind != "graph") preview.addView(TextView(this).apply {
                    text = "+ Log ${habit.name}"
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (light) 0xfffafafa.toInt() else 0xff18181b.toInt())
                    background = Theme.shape(foreground, dp(12).toFloat())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
            }
            Unit
        }
        updatePreview = refreshPreview
        mode.onChange = refreshPreview
        pages.onChange = refreshPreview
        color.onChange = refreshPreview
        theme.onChange = refreshPreview
        gradient.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        refreshPreview()
        root.addView(
            button("Save widget") {
                val chosen =
                    if (mode.selectedItemPosition == 1)
                        Store.habits.drop(pages.selectedItemPosition * 4).take(4).map { it.id }
                    else
                        boxes.mapIndexedNotNull { i, b ->
                            if (b.isChecked) Store.habits[i].id else null
                        }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "Choose at least one activity", Toast.LENGTH_SHORT).show()
                    return@button
                }
                val config =
                    JSONObject()
                        .put("ids", JSONArray(chosen))
                        .put(
                            "pageIndex",
                            if (mode.selectedItemPosition == 1) pages.selectedItemPosition else -1,
                        )
                        .put("owner", if (Store.demo) "demo" else Store.auth.currentUser!!.uid)
                        .put("color", color.selectedItemPosition)
                        .put("fitWidth", true)
                        .put("weeks", 52)
                        .put("theme", if (theme.selectedItemPosition == 0) "dark" else "light")
                        .put("gradient", gradient.isChecked)
                if (pinKind != null) {
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
                        when (pinKind) {
                            "quick" -> QuickWidget::class.java
                            "graph" -> GraphWidget::class.java
                            else -> LogGraphWidget::class.java
                        }
                    val callback =
                        android.app.PendingIntent.getBroadcast(
                            this,
                            config.toString().hashCode(),
                            Intent(this, PinReceiver::class.java)
                                .putExtra("config", config.toString()),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_MUTABLE,
                        )
                    manager.requestPinAppWidget(
                        android.content.ComponentName(this, cls),
                        Bundle(),
                        callback,
                    )
                    finish()
                    return@button
                }
                Store.prefs.edit().putString("widget_$id", config.toString()).apply()
                val manager = AppWidgetManager.getInstance(this)
                Widgets.update(this, manager, id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        )
        root.addView(
            button("Cancel") { finish() }
                .apply {
                    background = Theme.shape(Theme.bg)
                    setTextColor(Theme.muted)
                },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) },
        )
    }
}

class ChoiceView(private val a: Activity, private val choices: Array<String>, initial: Int) :
    TextView(a) {
    var selectedItemPosition = initial
        private set

    var onChange: (() -> Unit)? = null

    init {
        setTextColor(Theme.text)
        textSize = 14f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        compoundDrawablePadding = a.dp(8)
        val arrow =
            a.getDrawable(R.drawable.ic_chevron_down)!!.mutate().apply { setTint(Theme.muted) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(a.dp(16), 0, a.dp(16), 0)
        background = Theme.shape(Theme.card, a.dp(12).toFloat())
        text = choices[initial]
        setOnClickListener {
            PrettyDialog.Builder(a)
                .setTitle("Choose an option")
                .setSingleChoiceItems(choices, selectedItemPosition) { _, index ->
                    selectedItemPosition = index
                    text = choices[index]
                    onChange?.invoke()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
