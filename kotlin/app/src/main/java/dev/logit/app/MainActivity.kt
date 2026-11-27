package dev.logit.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private var dashboardScroll: ScrollView? = null
    private var focus: String? = null
    private var authBusy = false
    private var authError: String? = null
    private val healthScope = MainScope()
    private var resumed = false
    private var healthAccess: Pair<String, Set<String>>? = null

    override fun onCreate(state: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(state)
        splash.setOnExitAnimationListener { view ->
            view.view.animate().alpha(0f).setDuration(220).withEndAction { view.remove() }.start()
        }
        focus = intent.getStringExtra("habit")
        Store.changed = { runOnUiThread {
            render()
            if (resumed) checkHealth()
        } }
        Store.error = { message -> runOnUiThread { toast(message) } }
        Store.listen()
        render()
        routeLog()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        PhoneTracking.schedule()
        healthScope.launch(Dispatchers.IO) { PhoneTracking.run(applicationContext, onReturn = true) }
        PhoneTracking.syncSleep()
        AppUpdates.refresh(this) { render() }
        checkHealth(force = true)
        render()
    }

    override fun onPause() {
        resumed = false
        healthScope.coroutineContext.cancelChildren()
        AppUpdates.pause(this)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == GoalReminders.permissionRequest) {
            GoalReminders.setEnabled(this, results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focus = intent.getStringExtra("habit")
        render()
        routeLog()
    }

    override fun onDestroy() {
        healthScope.cancel()
        Store.changed = null
        Store.error = null
        super.onDestroy()
    }

    private fun checkHealth(force: Boolean = false) {
        val owner = Store.trackingOwner() ?: return
        healthScope.launch {
            if ((force || healthAccess?.first != owner) && HealthMetric.entries.any { HealthTracking.rule(owner, it) != null }) {
                val grants = HealthTracking.grants(applicationContext)
                if (Store.trackingOwner() == owner) {
                    val next = grants?.let { owner to it }
                    if (next != healthAccess) {
                        healthAccess = next
                        render()
                    }
                }
            }
            HealthTracking.check(applicationContext, owner, force)
        }
    }

    private fun routeLog() {
        if (intent.getBooleanExtra("log", false)) {
            intent.removeExtra("log")
            startActivity(
                Intent(this, LogActivity::class.java)
                    .putExtra("habit", intent.getStringExtra("habit"))
            )
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    fun render() {
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val flags =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (Theme.light()) flags else 0,
                flags,
            )
        }
        val previousScroll = dashboardScroll?.scrollY ?: 0
        val root = column().apply { background = null }
        root.setPadding(dp(18), dp(8), dp(18), dp(16))
        val scroll =
            ScrollView(this).apply {
                background = Theme.dashboardBackground()
                isFillViewport = true
            }
        scroll.addView(root)
        dashboardScroll = scroll
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, previousScroll) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun icon(text: String, description: String, bordered: Boolean = false, action: () -> Unit) =
            button(text, action).apply {
                background =
                    if (bordered) Theme.bordered(Theme.card, Theme.controlBorder, dp(22).toFloat(), dp(1))
                    else Theme.shape(Theme.card, dp(24).toFloat())
                setTextColor(Theme.text)
                textSize = 20f
                stateListAnimator = null
                elevation = 0f
                contentDescription = description
                tooltipText = description
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }
        val header = row()
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_logit_mark)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) },
        )
        header.addView(
            label("LogIt", 22f).apply { typeface = Theme.semibold },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            ThemeToggle(this) {
                Store.prefs
                    .edit()
                    .putString("theme", if (Theme.light()) "dark" else "light")
                    .apply()
                Widgets.refresh(this)
                render()
            },
            LinearLayout.LayoutParams(dp(80), dp(48)),
        )
        val more = icon("⋯", "More options", bordered = true) {}
        more.setOnClickListener { settings() }
        header.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        root.addView(header)
        AppUpdates.availableVersion(this)?.let { version -> addUpdateBanner(root, version) }
        if (!Store.ready) {
            root.addView(loginView(authBusy, authError) { signIn() })
            return
        }
        val section = row()
        section.setPadding(0, dp(10), 0, dp(8))
        val heading =
            LinearLayout(this).apply {
                orientation = 1
                addView(label("Your activities", 22f).apply { typeface = Theme.semibold })
                addView(
                    label(
                        java.time.LocalDate.parse(Dates.today())
                            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM")) +
                            " · ${Store.habits.size} ${if(Store.habits.size==1)"activity"else"activities"}",
                        12f,
                        Theme.muted,
                    )
                )
            }
        section.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val addButton = button("+ Add") { add() }
                .apply {
                    textSize = 14f
                    setPadding(dp(14), 0, dp(14), 0)
                    stateListAnimator = null
                }
        section.addView(addButton, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(section)
        if (Store.hasPendingDeletes()) root.addView(label("Deleting activity…", 14f, Theme.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        val last = Store.lastLog()
        val tools = row().apply { setPadding(0, 0, 0, dp(8)) }
        val periods = intArrayOf(4, 8, 16, 19, 26, 52)
        val historyWeeks = Store.prefs.getInt("historyWeeksV2", 19)
        tools.addView(
            ChoiceView(
                    this,
                    periods.map { "$it weeks" }.toTypedArray(),
                    periods.indexOf(historyWeeks).coerceAtLeast(0),
                    pickerTitle = "Activity history",
                )
                .apply {
                    onChange = {
                        Store.prefs
                            .edit()
                            .putInt("historyWeeksV2", periods[selectedItemPosition])
                            .apply()
                        render()
                    }
                    contentDescription = "Activity history timeframe"
                },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(12) },
        )
        val redo = Store.lastRedo()
        fun historyAction(title: String, available: LogOp?, apply: (String, (String?) -> Unit) -> Unit) =
            button(title) {
                available?.let { apply(it.id) { error -> toast(error ?: "Last log ${if (title == "Undo") "undone" else "redone"}") } }
            }.apply {
                textSize = 13f
                setPadding(dp(8), 0, dp(8), 0)
                val surface = if (available == null) Theme.track else Theme.card
                val border = if (available == null) Theme.cardBorder else Theme.controlBorder
                background = Theme.bordered(surface, border, dp(10).toFloat(), dp(1))
                setTextColor(if (available == null) Theme.muted else Theme.text)
                stateListAnimator = null
                isEnabled = available != null
                minimumWidth = 0
                minWidth = 0
            }
        tools.addView(historyAction("Undo", last, Store::undoLast), LinearLayout.LayoutParams(dp(64), dp(44)).apply {
            marginEnd = dp(8)
        })
        tools.addView(historyAction("Redo", redo, Store::redoLast), LinearLayout.LayoutParams(dp(64), dp(44)))
        root.addView(tools)
        if (OfflineLogQueue.pendingCount() > 0)
            root.addView(
                label(
                    "${OfflineLogQueue.pendingCount()} logs saved on this device · waiting to sync",
                    12f,
                    Theme.muted,
                )
            )
        if (Store.habits.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.bordered(Theme.card, Theme.cardBorder, dp(12).toFloat(), dp(1))
                setPadding(dp(16), dp(18), dp(16), dp(18))
                addView(label("No activities yet", 17f).apply { typeface = Theme.semibold })
                addView(label("Add an activity to start tracking daily completions.", 13f, Theme.muted))
                addView(button("+ Add activity") { add() }.apply {
                    textSize = 13f
                    stateListAnimator = null
                    background = Theme.shape(Theme.text, dp(10).toFloat())
                    setPadding(dp(16), 0, dp(16), 0)
                }, LinearLayout.LayoutParams(-2, dp(42)).apply { topMargin = dp(10) })
            }
            root.addView(emptyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        var firstActivityOptions: View? = null
        var firstGraph: View? = null
        val owner = Store.trackingOwner()
        val automatedActivities = owner?.let(PhoneTracking::automatedActivities).orEmpty()
        val missingHealthAccess = healthAccess?.takeIf { it.first == owner }?.let { (account, grants) ->
            HealthMetric.entries.filterNot { it.permission in grants }
                .mapNotNullTo(mutableSetOf()) { HealthTracking.rule(account, it)?.activityId }
        }.orEmpty()
        val appRules = owner?.let(PhoneTracking::appRules).orEmpty().groupBy { it.activityId }
        val limits = owner?.let(PhoneTracking::limits).orEmpty().filter { it.enabled }.mapNotNull { it.activityId }.toSet()
        val usageAllowed = if (appRules.isNotEmpty() || limits.isNotEmpty()) AndroidUsage.allowed(this) else false
        val usageEnabled = owner?.let(PhoneTracking::automaticEnabled) == true
        val sleepId = owner?.let(PhoneTracking::sleep)?.activityId
        Store.habits.forEach { h ->
            val automated = h.id in automatedActivities
            val card =
                LinearLayout(this).apply {
                    orientation = 1
                    background = Theme.bordered(Theme.card, Theme.cardBorder, dp(12).toFloat(), dp(1))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    tag = h.id
                }
            val top = row().apply { gravity = Gravity.TOP }
            top.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), dp(4), 0)
                    addView(label(h.name, 19f).apply { typeface = Theme.semibold })
                    addView(label(h.widgetTodayLabel(false), 13f, Theme.muted))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            val options = icon("⋯", "Options for ${h.name}") { activityOptions(h) }.apply {
                val pill = Theme.bordered(Theme.track, Theme.cardBorder, dp(10).toFloat(), dp(1))
                background = android.graphics.drawable.InsetDrawable(pill, dp(4), dp(6), dp(4), dp(6))
                setTextColor(Theme.muted)
                textSize = 18f
                tooltipText = "Activity options"
            }
            if (firstActivityOptions == null) firstActivityOptions = options
            top.addView(options, LinearLayout.LayoutParams(dp(44), dp(44)))
            val grip =
                label("⠿", 24f, Theme.muted).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Hold and drag ${h.name} to reorder"
                    tooltipText = "Hold and drag to reorder"
                    setOnLongClickListener {
                        startDragAndDrop(
                            ClipData.newPlainText("habit", h.id),
                            View.DragShadowBuilder(card),
                            h.id,
                            0,
                        )
                        true
                    }
                }
            top.addView(grip, LinearLayout.LayoutParams(dp(36), dp(44)))
            card.addView(top)
            card.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.label == "habit"
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        card.alpha = .65f
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED,
                    DragEvent.ACTION_DRAG_ENDED -> {
                        card.alpha = 1f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        card.alpha = 1f
                        val id = event.clipData.getItemAt(0).text.toString()
                        val ids = Store.habits.map { it.id }.toMutableList()
                        if (ids.remove(id)) {
                            ids.add(ids.indexOf(h.id).coerceAtLeast(0), id)
                            Store.reorder(ids) { e -> e?.let { toast(it) } }
                        }
                        true
                    }
                    else -> true
                }
            }
            card.addView(
                HeatmapView(this, h, historyWeeks, binary = automated).apply {
                    if (firstGraph == null) firstGraph = this
                    val today = LocalDate.parse(Dates.today())
                    contentDescription = "${h.name} history, ${h.count(today.toString())} today, ${h.count(today.minusDays(1).toString())} yesterday"
                    setOnClickListener { backdate(h, includeToday = true) }
                    val graph = this
                    val gesture = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(event: MotionEvent) = true

                        override fun onDoubleTap(event: MotionEvent): Boolean = graph.performClick()

                        override fun onLongPress(event: MotionEvent) {
                            graph.performClick()
                        }
                    })
                    setOnTouchListener { _, event ->
                        gesture.onTouchEvent(event)
                        true
                    }
                },
                LinearLayout.LayoutParams(-1, dp(116)).apply {
                    bottomMargin = dp(2)
                },
            )
            if (automated) {
                val rules = appRules[h.id].orEmpty()
                val appRule = rules.singleOrNull()
                val route = when {
                    h.id in missingHealthAccess -> "health"
                    rules.isNotEmpty() -> "apps"
                    h.id in limits -> "limits"
                    h.id == sleepId -> "sleep"
                    else -> "health"
                }
                val needsHealthAccess = h.id in missingHealthAccess
                val needsUsageAccess = (route == "apps" || route == "limits") && !usageAllowed
                val isPaused = (route == "apps" || route == "limits") && !usageEnabled
                val status = when {
                    needsHealthAccess -> "Health access needed"
                    needsUsageAccess -> "Usage access needed"
                    isPaused -> "Automatic tracking paused"
                    h.count(Dates.today()) > 0 -> "Tracked automatically"
                    appRule?.minutes == 0 -> "Waiting for ${appRule.app.label} to open"
                    appRule != null -> "Waiting for ${appRule.minutes} min of ${appRule.app.label} today"
                    route == "limits" -> "Checks yesterday’s screen time"
                    else -> "Waiting for today’s data"
                }
                val statusView = if (needsHealthAccess || needsUsageAccess || isPaused) {
                    quietAction("$status ›") { openTracking(route, h.id) }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        minimumHeight = dp(44)
                        setPadding(0, dp(4), 0, dp(4))
                        textSize = 12f
                        setTextColor(Theme.muted)
                    }
                } else label(status, 12f, Theme.muted)
                card.addView(statusView, LinearLayout.LayoutParams(-2, -2))
            } else {
                val actions = row()
                actions.addView(
                    quietAction("Add for yesterday") { backdate(h) }.apply {
                        gravity = Gravity.CENTER
                        minHeight = dp(44)
                        minimumHeight = dp(44)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        val surface = Theme.bordered(Theme.track, Theme.controlBorder, dp(8).toFloat(), dp(1))
                        background = android.graphics.drawable.InsetDrawable(surface, 0, dp(4), 0, dp(4))
                        setTextColor(Theme.text)
                        textSize = 12f
                        contentDescription = "Add ${h.name} for yesterday"
                    },
                    LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) },
                )
                actions.addView(
                    button("+ Log today") {
                            Store.log(h.id, 1) { error -> if (error != null) toast(error) }
                        }
                        .apply {
                            textSize = 13f
                            minHeight = dp(44)
                            minimumHeight = dp(44)
                            minimumWidth = 0
                            minWidth = 0
                            stateListAnimator = null
                            val surface = Theme.shape(Theme.text, dp(8).toFloat())
                            background = android.graphics.drawable.InsetDrawable(surface, 0, dp(4), 0, dp(4))
                            gravity = Gravity.CENTER
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            contentDescription = "Log ${h.name} for today"
                        },
                    LinearLayout.LayoutParams(0, -1, 1f),
                )
                card.addView(actions)
            }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val fromWidget = focus != null || intent.getBooleanExtra("log", false) ||
            intent.action?.startsWith("widget-open:") == true
        focus?.let { id ->
            root.findViewWithTag<View>(id)?.let { card ->
                scroll.post { scroll.scrollTo(0, card.top) }
                focus = null
            }
        }
        if (!fromWidget) Introduction.maybeStart()
        Introduction.show(this, "home", addButton, firstActivityOptions, firstGraph ?: tools)
    }

    @Deprecated("Handled by Android for normal navigation")
    override fun onBackPressed() {
        if (!Introduction.dismiss(this, "home")) super.onBackPressed()
    }

    private fun addUpdateBanner(root: LinearLayout, version: String) {
        val banner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.bordered(Theme.card, Theme.controlBorder, dp(12).toFloat(), dp(1))
            setPadding(dp(12), dp(6), dp(6), dp(6))
        }
        banner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Update available", 14f).apply { typeface = Theme.semibold })
            addView(label("LogIt $version", 12f, Theme.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(button("Update") { AppUpdates.update(this) }.apply {
            background = Theme.shape(Theme.text, dp(8).toFloat())
            minimumWidth = 0
            minWidth = 0
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun activityOptions(habit: Habit) {
        val colorIdx = habit.color.coerceIn(0, Theme.colors.lastIndex)
        val owner = Store.trackingOwner()
        val sleep = owner?.let(PhoneTracking::sleep)?.takeIf { it.activityId == habit.id }
        val automated = owner?.let(PhoneTracking::automatedActivities)?.contains(habit.id) == true
        val goalSummary = sleep?.let { "${formatUsageMinutes(it.goalMinutes)} minimum sleep" }
            ?: habit.goal?.let { "${it.target} ${if (it.period == "weekly") "per week" else "per day"}" }
            ?: "No goal set"
        lateinit var dialog: PrettyDialog

        fun optionRow(title: String, subtitle: String, swatchColor: Int? = null, destructive: Boolean = false, action: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                    null,
                    Theme.shape(Color.WHITE, dp(8).toFloat()),
                )
                isClickable = true
                isFocusable = true
                contentDescription = "$title, $subtitle"
                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 15f, if (destructive) Theme.destructive else Theme.text).apply {
                        typeface = Theme.semibold
                        setPadding(0, 0, 0, 0)
                    })
                    val subRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    swatchColor?.let {
                        subRow.addView(View(this@MainActivity).apply {
                            background = Theme.shape(it, dp(5).toFloat())
                        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) })
                    }
                    subRow.addView(label(subtitle, 13f, Theme.muted).apply { setPadding(0, 0, 0, 0) })
                    addView(subRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                }
                addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                if (!destructive) addView(label("›", 18f, Theme.muted).apply { setPadding(dp(8), 0, 0, 0) })
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addOption(row: View) {
            if (list.childCount > 0) {
                list.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                })
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        addOption(optionRow("Edit activity", goalSummary) { editActivity(habit) })
        addOption(optionRow("Activity colour", Theme.names[colorIdx], Theme.colors[colorIdx]) {
            activityColourPicker(habit)
        })
        if (automated) {
            addOption(optionRow("Log manually", "Choose today or yesterday") { backdate(habit, includeToday = true) })
        }
        if (sleep != null) {
            addOption(optionRow("Sleep history", "Review and edit your sleep") { openTracking("sleep") })
        }
        list.addView(optionRow("Delete activity", "Permanently removes its history", destructive = true) {
            confirmDelete(habit)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        dialog = PrettyDialog.Builder(this)
            .setTitle(habit.name)
            .setCompact()
            .setView(list)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(habit: Habit) {
        val dialog = PrettyDialog.Builder(this)
            .setTitle("Delete ${habit.name}?")
            .setMessage("This permanently deletes the activity and its log history. Any linked screen-time limit will stay in place without this activity.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete activity", null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(-1)
            confirm.background = Theme.shape(Theme.destructive, dp(10).toFloat())
            confirm.setTextColor(if (Theme.light()) 0xffffffff.toInt() else 0xff18181b.toInt())
            confirm.setOnClickListener {
                confirm.isEnabled = false
                dialog.dismiss()
                Store.delete(habit.id) { problem ->
                    if (!isDestroyed) toast(problem ?: "Activity deleted")
                }
            }
        }
        dialog.show()
    }

    private fun activityColourPicker(habit: Habit) {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: PrettyDialog
        val activeColor = habit.color.coerceIn(0, Theme.colors.lastIndex)

        var row: LinearLayout? = null
        Theme.names.forEachIndexed { index, name ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(8)
                })
            }
            val selected = index == activeColor
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = Theme.bordered(
                    if (selected) Theme.card else Theme.bg,
                    if (selected) Theme.text else Theme.controlBorder,
                    dp(10).toFloat(),
                    dp(if (selected) 2 else 1),
                )
                isClickable = true
                isFocusable = true
                contentDescription = if (selected) "$name, selected" else name
                addView(View(this@MainActivity).apply {
                    background = Theme.shape(Theme.colors[index], dp(8).toFloat())
                }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
                addView(label(name, 14f, Theme.text).apply {
                    typeface = if (selected) Theme.semibold else Theme.regular
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (selected) {
                    addView(label("✓", 14f, Theme.text).apply {
                        typeface = Theme.semibold
                        setPadding(dp(4), 0, 0, 0)
                    })
                }
                setOnClickListener {
                    Store.color(habit.id, index)
                    dialog.dismiss()
                }
            }
            row!!.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index % 2 == 0) marginEnd = dp(8)
            })
        }

        dialog = PrettyDialog.Builder(this)
            .setTitle("Activity colour")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun add() = editActivity()

    private fun settings() {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dialog = PrettyDialog.Builder(this).setTitle("Settings").setCompact()
            .setView(menu).setNegativeButton("Close", null).create()
        fun option(title: String, color: Int = Theme.text, badgeColor: Int? = null, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                    null, Theme.shape(Color.WHITE, dp(8).toFloat()),
                )
                isClickable = true
                isFocusable = true
                addView(label(title, 15f, color).apply {
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                badgeColor?.let {
                    addView(View(this@MainActivity).apply {
                        background = Theme.shape(it, dp(4).toFloat())
                    }, LinearLayout.LayoutParams(dp(8), dp(8)))
                }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            menu.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (Store.ready) {
            option("Statistics") { openDetails("statistics") }
            option("Tracking") { openTracking("tracking") }
            option("Goal reminders") { openDetails("reminders") }
            option("Widgets") { openDetails("widgets") }
            option("Introduction") {
                Introduction.start()
                dashboardScroll?.scrollTo(0, 0)
                render()
            }
        }
        val updateDot = if (AppUpdates.cachedUpdate(this)) Theme.colors[0] else null
        option("Check for updates", badgeColor = updateDot) { AppUpdates.show(this) }
        if (Store.ready) {
            menu.addView(themedDivider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            option("Sign out", color = Theme.destructive) {
                if (Store.demo) Store.leaveDemo() else Store.auth.signOut()
                GoalReminders.schedule(this)
                render()
            }
        }
        dialog.show()
    }

    private fun openDetails(screen: String) =
        startActivity(Intent(this, DetailsActivity::class.java).putExtra("screen", screen))

    @Suppress("DEPRECATION")
    private fun signIn() {
        if (authBusy) return
        if (!Store.configured) {
            toast("Sign-in is unavailable: Firebase configuration is missing.")
            return
        }
        val rid = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (rid == 0) {
            toast("OAuth client missing from google-services.json")
            return
        }
        val options =
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(rid))
                .requestEmail()
                .build()
        authBusy = true
        authError = null
        render()
        val client = GoogleSignIn.getClient(this, options)
        // Firebase sign-out does not clear Google's last selected account. Clear only
        // this app's Google session before an explicit sign-in, so the user can choose.
        client.signOut().addOnCompleteListener(this) { cleared ->
            if (!cleared.isSuccessful) {
                authBusy = false
                authError = "Could not open account selection. Please try again."
                render()
                return@addOnCompleteListener
            }
            android.util.Log.i("LogItAuth", "Opening Google account chooser after session clear")
            try {
                startActivityForResult(client.signInIntent, 42)
            } catch (e: Exception) {
                android.util.Log.w(
                    "LogItAuth",
                    "Account chooser launch failed: ${e.javaClass.simpleName}",
                )
                authBusy = false
                authError = "Could not open Google sign-in. Please try again."
                render()
            }
        }
    }

    @Deprecated("Legacy Google sign-in result")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        when (request) {
            AppUpdates.INSTALL_PERMISSION_REQUEST -> AppUpdates.resumeInstall(this)
            AppUpdates.INSTALL_PACKAGE_REQUEST -> AppUpdates.installFinished(this, result)
        }
        if (request == 42) {
            android.util.Log.i("LogItAuth", "Account chooser returned result=$result")
            try {
                val account =
                    GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(ApiException::class.java)
                val token = account.idToken ?: error("Google returned no token")
                Store.auth
                    .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                    .addOnSuccessListener(this) {
                        android.util.Log.i("LogItAuth", "Firebase sign-in completed")
                        authBusy = false
                        render()
                    }
                    .addOnFailureListener(this) {
                        android.util.Log.w(
                            "LogItAuth",
                            "Firebase sign-in failed: ${it.javaClass.simpleName}",
                        )
                        authBusy = false
                        authError = "Sign-in failed. Please check your connection and try again."
                        render()
                    }
            } catch (e: Exception) {
                val status = (e as? ApiException)?.statusCode
                android.util.Log.w(
                    "LogItAuth",
                    "Google sign-in result: status=$status type=${e.javaClass.simpleName}",
                )
                authBusy = false
                if (status != GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                    authError =
                        "Google sign-in failed${status?.let { " (code $it)" } ?: ""}. Please try again."
                }
                render()
            }
        }
    }
}
