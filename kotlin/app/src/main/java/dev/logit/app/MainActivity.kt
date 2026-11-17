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
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        Store.changed = { runOnUiThread { render() } }
        Store.error = { message -> runOnUiThread { toast(message) } }
        Store.listen()
        render()
        routeLog()
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
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val flags =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (Theme.light()) flags else 0,
                flags,
            )
        }
        val root = column()
        root.setPadding(dp(18), dp(8), dp(18), dp(16))
        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(Theme.bg)
                isFillViewport = true
            }
        scroll.addView(root)
        setContentView(scroll)
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
            icon(
                if (Theme.light()) "☾" else "☼",
                if (Theme.light()) "Use dark theme" else "Use light theme",
            ) {
                Store.prefs
                    .edit()
                    .putString("theme", if (Theme.light()) "dark" else "light")
                    .apply()
                Widgets.refresh(this)
                render()
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        if (Store.ready)
            header.addView(
                icon("⋯", "More options") { settings() },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) },
            )
        root.addView(header)
        if (!Store.ready) {
            root.addView(loginView(authBusy, authError) { signIn() })
            return
        }
        val section = row()
        section.setPadding(0, dp(14), 0, dp(10))
        val heading =
            LinearLayout(this).apply {
                orientation = 1
                addView(label("Your activities", 18f).apply { setTypeface(null, 1) })
                addView(
                    label(
                        "${Store.habits.size} ${if(Store.habits.size==1)"activity"else"activities"} · ${Store.habits.sumOf{it.count(Dates.today())}} today",
                        12f,
                        Theme.muted,
                    )
                )
            }
        section.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(
            icon("+", "Add activity") { add() },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        root.addView(section)
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
                    background = Theme.shape(Theme.card, dp(20).toFloat())
                    setPadding(dp(17), dp(14), dp(17), dp(16))
                    tag = h.id
                }
            val top = row()
            val titles =
                LinearLayout(this).apply {
                    orientation = 1
                    addView(
                        label(h.name, 19f).apply {
                            setTypeface(null, 1)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    )
                    addView(
                        label(
                            "${h.count(Dates.today())} today · ${h.total()} total",
                            12f,
                            Theme.muted,
                        )
                    )
                }
            top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(
                icon("●", "Colour for ${h.name}") {
                        PrettyDialog.Builder(this, dialogTheme())
                            .setTitle("Activity colour")
                            .setSingleChoiceItems(Theme.names, h.color) { d, n ->
                                Store.color(h.id, n)
                                d.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .apply {
                        setTextColor(Theme.colors[h.color.coerceIn(0, 9)])
                        textSize = 18f
                    },
                LinearLayout.LayoutParams(dp(40), dp(44)),
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
            val plot = FrameLayout(this)
            plot.addView(
                HeatmapView(this, h, 20).apply {
                    contentDescription = "${h.name} history, latest weeks that fit"
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            plot.addView(
                button("+ Log") {
                        startActivity(Intent(this, LogActivity::class.java).putExtra("habit", h.id))
                    }
                    .apply {
                        textSize = 14f
                        minHeight = dp(44)
                        setPadding(dp(16), 0, dp(16), 0)
                        elevation = dp(3).toFloat()
                    },
                FrameLayout.LayoutParams(dp(100), dp(46), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(3)
                    topMargin = dp(3)
                },
            )
            card.addView(plot, LinearLayout.LayoutParams(-1, dp(116)).apply { topMargin = dp(16) })
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (Store.habits.size > 4) {
            val nav = row()
            nav.addView(
                button("‹") {
                        page--
                        render()
                    }
                    .apply { isEnabled = page > 0 },
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
                    .apply { isEnabled = (page + 1) * 4 < Store.habits.size },
                LinearLayout.LayoutParams(dp(48), dp(44)),
            )
            root.addView(nav)
        }
        root.addView(
            label("Hold ⠿ to reorder. One day at a time.", 11f, Theme.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            }
        )
    }

    private fun add() {
        val input =
            EditText(this).apply {
                hint = "Activity name"
                background = Theme.shape(Theme.bg, dp(12).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(Theme.text)
                setHintTextColor(Theme.muted)
                setSingleLine()
                filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            }
        val d =
            PrettyDialog.Builder(this, dialogTheme())
                .setTitle("New activity")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create()
        d.setOnShowListener {
            d.getButton(-1).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) input.error = "Give it a name"
                else {
                    d.getButton(-1).isEnabled = false
                    Store.add(name) { e ->
                        if (e == null) d.dismiss()
                        else {
                            input.error = e
                            d.getButton(-1).isEnabled = true
                        }
                    }
                }
            }
        }
        d.show()
    }

    private fun settings() {
        PrettyDialog.Builder(this, dialogTheme())
            .setTitle("More options")
            .setItems(arrayOf("Add a widget", "Sign out")) { _, n ->
                if (n == 0) {
                    PrettyDialog.Builder(this)
                        .setTitle("Add a widget")
                        .setItems(arrayOf("Quick log", "Heatmap", "Heatmap + log")) { _, kind ->
                            startActivity(
                                Intent(this, WidgetConfigActivity::class.java)
                                    .putExtra("pinKind", arrayOf("quick", "graph", "log")[kind])
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    if (Store.demo) Store.leaveDemo() else Store.auth.signOut()
                    render()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

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
