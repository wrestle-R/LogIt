package dev.logit.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Signed-out layout follows the original site's definition and compact auth panel. */
fun Activity.loginView(busy: Boolean, error: String?, signIn: () -> Unit): View {
    val border = if (Theme.light()) 0xffd4d4d8.toInt() else 0xff3f3f46.toInt()
    fun surface() = Theme.shape(Theme.card, dp(14).toFloat()).apply { setStroke(dp(1), border) }
    fun block() =
        column().apply {
            background = surface()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
    val content = column().apply { setPadding(0, 0, 0, 0) }
    val definition = block()
    definition.addView(
        label("persistence", 27f).apply {
            typeface = Theme.medium
            letterSpacing = -.025f
            setPadding(0, 0, 0, dp(4))
        }
    )
    definition.addView(label("/pərˈsɪstəns/", 13f, Theme.muted))
    definition.addView(
        label("noun", 11f, Theme.muted).apply {
            background =
                Theme.shape(
                    if (Theme.light()) 0xffe4e4e7.toInt() else 0xff27272a.toInt(),
                    dp(6).toFloat(),
                )
            setPadding(dp(8), dp(3), dp(8), dp(3))
        },
        LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        },
    )
    definition.addView(
        label(
            "the fact of continuing in an opinion or course of action in spite of difficulty or opposition.",
            14f,
        )
    )
    definition.addView(
        View(this).apply { setBackgroundColor(border) },
        LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(10)
        },
    )
    definition.addView(
        label("“Persistence and endurance will make you omnipotent.”", 12f, Theme.muted).apply {
            setTypeface(Theme.regular, Typeface.ITALIC)
        }
    )
    content.addView(definition, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

    val auth = block()
    auth.addView(
        label("Your habits", 19f).apply {
            typeface = Theme.medium
            setPadding(0, 0, 0, dp(4))
        }
    )
    auth.addView(label("Sign in to start tracking your habits.", 14f, Theme.muted))
    val google =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background =
                RippleDrawable(
                    ColorStateList.valueOf(if (Theme.light()) 0x16000000 else 0x20ffffff),
                    surface(),
                    null,
                )
            isClickable = true
            isFocusable = true
            isEnabled = !busy
            alpha = if (busy) .6f else 1f
            contentDescription =
                if (busy) "Opening Google account selection" else "Continue with Google"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { signIn() }
            addView(
                ImageView(this@loginView).apply {
                    setImageResource(R.drawable.ic_google)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) },
            )
            addView(
                label(if (busy) "Opening Google…" else "Continue with Google", 14f).apply {
                    setPadding(0, 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
        }
    auth.addView(google, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    error?.let {
        auth.addView(
            label(it, 13f, if (Theme.light()) 0xffb91c1c.toInt() else 0xfffda4af.toInt()).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
    }
    auth.addView(
        label("Use the same Google account as the website.", 12f, Theme.muted).apply {
            gravity = Gravity.CENTER
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    content.addView(auth, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    return LinearLayout(this).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val available = resources.displayMetrics.widthPixels - dp(36)
        addView(content, LinearLayout.LayoutParams(minOf(available, dp(440)), -2))
    }
}
