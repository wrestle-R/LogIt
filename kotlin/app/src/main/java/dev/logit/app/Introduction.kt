package dev.logit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

internal object Introduction {
    private data class Step(val screen: String, val title: String, val description: String)

    private val steps = listOf(
        Step("home", "Make your first activity", "Tap Add to name something you want to do regularly. Each activity gets its own colour and history."),
        Step("home", "Set a goal", "Use Goal frequency when you add or edit an activity. Pick a daily or weekly target, or leave it open."),
        Step("home", "Log from a graph", "Double-tap or hold an activity graph to choose a date and add a log. Undo takes back your latest log. Redo restores it on the original date."),
        Step("widgets", "Put LogIt on your home screen", "Choose a widget layout, select your activities and add it to your launcher. Resize it to show more history or activities."),
        Step("reminders", "Get goal reminders", "Turn on notifications here to get a nudge before a goal is due, or a follow-up if you miss it."),
        Step("apps", "Automate app use", "Link an app and choose Opened or a time threshold. After you grant usage access, LogIt completes the activity once per day when you meet the rule."),
        Step("limits", "Automate screen time goals", "Set a limit for an app group or your whole phone. Link an activity to mark it complete when a tracked day ends under the limit."),
        Step("sleep", "Track sleep", "Choose a sleep goal. Record a night yourself, estimate it from phone inactivity, or import it through Health Connect."),
        Step("health", "Automate health activity", "Grant Health Connect access and choose targets for steps, workouts, or sleep. Automated graphs show whether each day is complete."),
    )
    private var owner: String? = null
    private var index = 0
    private const val overlayTag = "logit-introduction-overlay"
    private fun key(owner: String) = "introduction-completed:$owner"

    fun maybeStart() {
        val current = Store.trackingOwner() ?: return
        if (owner != current) {
            owner = null
            if (!Store.prefs.getBoolean(key(current), false)) start()
        }
    }

    fun start() {
        owner = Store.trackingOwner()
        index = 0
    }

    @Suppress("DEPRECATION")
    fun show(activity: Activity, screen: String, anchor: View?, goalAnchor: View? = null, graphAnchor: View? = null) {
        if (owner == null || owner != Store.trackingOwner()) return
        val step = steps[index]
        if (step.screen != screen) return
        val target = when (index) {
            1 -> goalAnchor ?: anchor
            2 -> graphAnchor ?: anchor
            else -> anchor
        }
        val frame = activity.findViewById<FrameLayout>(android.R.id.content)
        if (frame.findViewWithTag<View>(overlayTag) != null) return
        val content = frame.getChildAt(0)
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        fun closeOverlay() {
            frame.findViewWithTag<View>(overlayTag)?.let(frame::removeView)
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        fun move(next: Int) {
            if (next < 0) return
            closeOverlay()
            if (next >= steps.size) {
                finish(activity, screen)
                return
            }
            index = next
            val nextScreen = steps[index].screen
            if (nextScreen == screen) {
                show(activity, screen, anchor, goalAnchor, graphAnchor)
            } else if (nextScreen == "home") {
                activity.finish()
                activity.overridePendingTransition(0, 0)
            } else {
                val details = nextScreen in listOf("widgets", "reminders")
                val destination = if (details) DetailsActivity::class.java else PhoneTrackingActivity::class.java
                val extra = if (details) "screen" else "trackingScreen"
                activity.startActivity(Intent(activity, destination).putExtra(extra, nextScreen)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                activity.overridePendingTransition(0, 0)
                if (screen != "home") {
                    activity.finish()
                    activity.overridePendingTransition(0, 0)
                }
            }
        }

        val overlay = FrameLayout(activity).apply { tag = overlayTag }
        overlay.addView(object : View(activity) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val origin = IntArray(2)
                getLocationInWindow(origin)
                val at = IntArray(2)
                target?.getLocationInWindow(at)
                val pad = activity.dp(4)
                val box = if (target != null && target.width > 0 && target.height > 0) {
                    val x = at[0] - origin[0]
                    val y = at[1] - origin[1]
                    RectF((x - pad).toFloat(), (y - pad).toFloat(),
                        (x + target.width + pad).toFloat(), (y + target.height + pad).toFloat())
                } else null
                canvas.save()
                box?.let { canvas.clipOutRect(it) }
                canvas.drawColor(0x9e000000.toInt())
                canvas.restore()
                if (box != null) {
                    paint.color = Theme.colors[0]
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = activity.dp(2).toFloat()
                    val radius = activity.dp(9).toFloat()
                    canvas.drawRoundRect(box, radius, radius, paint)
                }
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> true
                    android.view.MotionEvent.ACTION_UP -> {
                        view.performClick()
                        move(index + if (event.x >= view.width / 2f) 1 else -1)
                        true
                    }
                    else -> true
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        val sheet = activity.column().apply {
            val pad = activity.dp(16)
            setPadding(pad, activity.dp(10), pad, activity.dp(10))
            background = Theme.bordered(Theme.card, Theme.controlBorder, pad.toFloat(), activity.dp(1))
            isClickable = true
            if (android.os.Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = "Introduction"
        }
        sheet.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.label("Introduction · ${index + 1} of ${steps.size}", 13f, Theme.muted),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.quietAction("Skip") {
                closeOverlay()
                finish(activity, screen)
            }.apply { minimumHeight = activity.dp(44) })
        })
        sheet.addView(activity.label(step.title, 21f).apply {
            typeface = Theme.semibold
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        sheet.addView(activity.label(step.description, 15f, Theme.muted))
        sheet.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.quietAction("Previous") { move(index - 1) }.apply {
                isEnabled = index > 0
                alpha = if (isEnabled) 1f else .45f
                minimumHeight = activity.dp(44)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(activity.button(if (index == steps.lastIndex) "Done" else "Next") { move(index + 1) },
                LinearLayout.LayoutParams(-2, activity.dp(44)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        overlay.addView(ScrollView(activity).apply { addView(sheet) }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val margin = activity.dp(14)
            setMargins(margin, margin, margin, margin + (frame.rootWindowInsets?.systemWindowInsetBottom ?: 0))
        })
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        target?.post { overlay.getChildAt(0)?.invalidate() }
    }

    fun dismiss(activity: Activity, screen: String): Boolean {
        val frame = activity.findViewById<FrameLayout>(android.R.id.content)
        val overlay = frame?.findViewWithTag<View>(overlayTag) ?: return false
        frame.removeView(overlay)
        frame.getChildAt(0)?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        finish(activity, screen)
        return true
    }

    private fun finish(activity: Activity, screen: String) {
        owner?.let { Store.prefs.edit().putBoolean(key(it), true).apply() }
        owner = null
        if (screen != "home") activity.finish()
    }
}
