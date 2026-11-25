package dev.logit.app

import android.app.*
import android.content.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider

class MainActivity : Activity() {
    private var dashboardScroll: ScrollView? = null
    private var page = 0
    private var focus: String? = null
    private var authBusy = false
    private var authError: String? = null

    override fun onCreate(state: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(state)
        splash.setOnExitAnimationListener { view ->
            view.view.animate().alpha(0f).setDuration(220).withEndAction { view.remove() }.start()
        }
        focus = intent.getStringExtra("habit")
        Store.changed = { runOnUiThread { render() } }
        Store.error = { message -> runOnUiThread { toast(message) } }
        Store.listen()
        render()
        routeLog()
        AppUpdates.refresh(this) { render() }
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
        Store.changed = null
        Store.error = null
        super.onDestroy()
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

    private fun dialogTheme() =
        if (Theme.light()) android.R.style.Theme_Material_Light_Dialog_Alert
        else android.R.style.Theme_Material_Dialog_Alert

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
                if (Theme.light()) setBackgroundColor(Theme.bg)
                else background = Theme.dashboardBackground()
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
        fun icon(text: String, description: String, action: () -> Unit) =
            button(text, action).apply {
                background = Theme.shape(Theme.card, dp(24).toFloat())
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
            label("LogIt", 22f).apply { setTypeface(null, 1) },
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
        val updateAvailable = AppUpdates.cachedUpdate(this)
        val more = icon(if (updateAvailable) "↑" else "⋯",
            if (updateAvailable) "More options, update available" else "More options") {}
        more.setOnClickListener { settings(more) }
        header.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        root.addView(header)
        if (!Store.ready) {
            root.addView(loginView(authBusy, authError) { signIn() })
            return
        }
        val section = row()
        section.setPadding(0, dp(10), 0, dp(8))
        val heading =
            LinearLayout(this).apply {
                orientation = 1
                addView(label("Your activities", 22f).apply { setTypeface(null, 1) })
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
        section.addView(
            button("+ Add") { add() }
                .apply {
                    textSize = 14f
                    setPadding(dp(14), 0, dp(14), 0)
                    stateListAnimator = null
                },
            LinearLayout.LayoutParams(-2, dp(44)),
        )
        root.addView(section)
        val last = Store.lastLog()
        val tools = row().apply { setPadding(0, 0, 0, dp(8)) }
        val periods = intArrayOf(4, 8, 16, 19, 26, 52)
        val historyWeeks = Store.prefs.getInt("historyWeeksV2", 19)
        tools.addView(
            ChoiceView(
                    this,
                    periods.map { "$it weeks" }.toTypedArray(),
                    periods.indexOf(historyWeeks).coerceAtLeast(0),
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
        tools.addView(
            button("Undo") {
                    if (last != null)
                        Store.undoLast(last.id) { error -> toast(error ?: "Last log undone") }
                }
                .apply {
                    textSize = 13f
                    setPadding(dp(12), 0, dp(12), 0)
                    background =
                        Theme.shape(Theme.card, dp(10).toFloat()).apply {
                            setStroke(
                                dp(1),
                                if (Theme.light()) 0xffd4d4d8.toInt() else 0xff3f3f46.toInt(),
                            )
                        }
                    setTextColor(Theme.text)
                    stateListAnimator = null
                    isEnabled = last != null
                    alpha = if (last == null) .4f else 1f
                    contentDescription = "Undo a log on this device"
                    tooltipText = "Undo the most recent log from this device"
                },
            LinearLayout.LayoutParams(-2, dp(44)),
        )
        root.addView(tools)
        if (OfflineLogQueue.pendingCount() > 0)
            root.addView(
                label(
                    "${OfflineLogQueue.pendingCount()} logs saved on this device · waiting to sync",
                    12f,
                    Theme.muted,
                )
            )
        if (Store.habits.isEmpty())
            root.addView(label("A clean slate. Add your first activity.", 15f, Theme.muted))
        focus?.let { id ->
            val at = Store.habits.indexOfFirst { it.id == id }
            if (at >= 0) page = at / 4
            focus = null
        }
        page = page.coerceIn(0, ((Store.habits.size - 1) / 4).coerceAtLeast(0))
        Store.habits.drop(page * 4).take(4).forEach { h ->
            val card =
                LinearLayout(this).apply {
                    orientation = 1
                    background =
                        Theme.shape(Theme.card, dp(12).toFloat()).apply {
                            setStroke(
                                dp(1),
                                if (Theme.light()) 0xffe4e4e7.toInt() else 0xff2e2e32.toInt(),
                            )
                        }
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    tag = h.id
                }
            val top = row()
            top.addView(
                label(h.name, 19f).apply {
                    setTypeface(null, 1)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            top.addView(
                icon("⋯", "Options for ${h.name}") { activityOptions(h) }.apply {
                    tooltipText = "Activity options"
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
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
                HeatmapView(this, h, historyWeeks).apply {
                    contentDescription = "${h.name} history, latest weeks that fit"
                },
                LinearLayout.LayoutParams(-1, dp(96)).apply {
                    bottomMargin = dp(2)
                },
            )
            val actions = row()
            actions.addView(
                quietAction("Add for yesterday") { backdate(h) }.apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(0, 0, dp(8), 0)
                    textSize = 12f
                    contentDescription = "Add ${h.name} for yesterday"
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            actions.addView(
                quietAction("+ Log today") {
                        Store.log(h.id, 1) { error -> if (error != null) toast(error) }
                    }
                    .apply {
                        textSize = 13f
                        setTextColor(Theme.text)
                        setPadding(dp(12), 0, dp(12), 0)
                        minHeight = dp(44)
                        minimumHeight = dp(44)
                        val pill = Theme.shape(Theme.bg, dp(16).toFloat()).apply {
                            setStroke(dp(1), Theme.divider)
                        }
                        val ripple = android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                            pill,
                            null,
                        )
                        background = android.graphics.drawable.InsetDrawable(ripple, 0, dp(7), 0, dp(7))
                        setPadding(dp(12), 0, dp(12), 0)
                        contentDescription = "Log ${h.name} for today"
                    },
                LinearLayout.LayoutParams(-2, dp(44)),
            )
            card.addView(actions)
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (Store.habits.size > 4) {
            val nav = row()
            nav.addView(
                button("‹") {
                        page--
                        render()
                    }
                    .apply { isEnabled = page > 0; contentDescription = "Previous activity page" },
                LinearLayout.LayoutParams(dp(48), dp(44)),
            )
            nav.addView(
                label("${page+1} / ${(Store.habits.size+3)/4}", 12f, Theme.muted).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            nav.addView(
                button("›") {
                        page++
                        render()
                    }
                    .apply { isEnabled = (page + 1) * 4 < Store.habits.size; contentDescription = "Next activity page" },
                LinearLayout.LayoutParams(dp(48), dp(44)),
            )
            root.addView(nav)
        }
    }

    private fun activityOptions(habit: Habit) {
        val actions = arrayOf("Edit goal", "Activity colour")
        PrettyDialog.Builder(this)
            .setTitle(habit.name)
            .setItems(actions) { dialog, index ->
                dialog.dismiss()
                if (index == 0) editActivity(habit)
                else PrettyDialog.Builder(this, dialogTheme())
                    .setTitle("Activity colour")
                    .setSingleChoiceItems(Theme.names, habit.color) { colours, colour ->
                        Store.color(habit.id, colour)
                        colours.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun add() = editActivity()

    private fun settings(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val popup = PopupWindow(menu, dp(208), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(Theme.shape(Theme.card, dp(12).toFloat()))
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
        }
        fun option(title: String, action: () -> Unit) {
            menu.addView(quietAction(title) {
                popup.dismiss()
                action()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                setTextColor(Theme.text)
                textSize = 14f
            }, LinearLayout.LayoutParams(-1, dp(44)))
        }
        if (Store.ready) {
            option("Statistics") { openDetails("statistics") }
            option("Goal reminders") { openDetails("reminders") }
            option("Add a widget") { openDetails("widgets") }
        }
        option("Check for updates") { AppUpdates.show(this) }
        if (Store.ready) option("Sign out") {
            if (Store.demo) Store.leaveDemo() else Store.auth.signOut()
            GoalReminders.schedule(this)
            render()
        }
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
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
        if (request == 92) AppUpdates.resumeInstall(this)
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
