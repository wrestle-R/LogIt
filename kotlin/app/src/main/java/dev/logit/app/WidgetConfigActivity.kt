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
        render()
    }

    private fun render() {
        val root = column()
        root.setPadding(dp(18), dp(28), dp(18), dp(18))
        val sc =
            ScrollView(this).apply {
                setBackgroundColor(Theme.bg)
                isFillViewport = true
            }
        sc.addView(root)
        setContentView(sc)
        root.addView(label("Widget setup", 22f).apply { setTypeface(null, 1) })
        root.addView(label("Choose your activities and make it yours.", 14f, Theme.muted))
        if (!Store.ready || Store.habits.isEmpty()) {
            root.addView(
                label("Open LogIt, sign in and add an activity first. Or explore the local demo.")
            )
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
        Store.habits.forEachIndexed { index, h ->
            val check =
                CheckBox(this).apply {
                    text = h.name
                    setTextColor(Theme.text)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Theme.colors[0])
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
                    }
                }
            boxes.add(check)
            root.addView(check)
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
        val periods = intArrayOf(4, 8, 16, 26, 52)
        val timeframe =
            select(
                "Timeframe",
                arrayOf("4 weeks", "8 weeks", "16 weeks", "26 weeks", "52 weeks"),
                periods.indexOf(old.optInt("weeks", 16)).coerceAtLeast(0),
            )
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
        val sample =
            ImageView(this).apply {
                setImageBitmap(Theme.graph(Store.habits.first(), 16, 600, 160))
                contentDescription = "Sample activity heatmap"
            }
        root.addView(sample, LinearLayout.LayoutParams(-1, dp(110)))
        val refreshPreview = {
            sample.setBackgroundColor(
                if (theme.selectedItemPosition == 0) 0xff101012.toInt() else 0xfffafafa.toInt()
            )
            sample.setImageBitmap(
                Theme.graph(
                    Store.habits.first(),
                    periods[timeframe.selectedItemPosition],
                    600,
                    180,
                    color.selectedItemPosition,
                    theme.selectedItemPosition == 1,
                    gradient.isChecked,
                )
            )
        }
        color.onChange = refreshPreview
        timeframe.onChange = refreshPreview
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
                        .put("weeks", periods[timeframe.selectedItemPosition])
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
                val quick =
                    manager
                        .getAppWidgetInfo(id)
                        ?.provider
                        ?.shortClassName
                        ?.endsWith("QuickWidget") == true
                manager.updateAppWidgetOptions(
                    id,
                    Bundle().apply {
                        putInt(
                            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                            chosen.size * if (quick) 60 else 110,
                        )
                    },
                )
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
        textSize = 15f
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(a.dp(16), 0, a.dp(16), 0)
        background = Theme.shape(Theme.card, a.dp(12).toFloat())
        text = "${choices[initial]}   ⌄"
        setOnClickListener {
            PrettyDialog.Builder(a)
                .setTitle("Choose an option")
                .setSingleChoiceItems(choices, selectedItemPosition) { _, index ->
                    selectedItemPosition = index
                    text = "${choices[index]}   ⌄"
                    onChange?.invoke()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
