package dev.logit.app

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** Shared dialog surface. No platform accent, uppercase actions or square grey panel. */
class PrettyDialog(private val owner: Activity) : Dialog(owner) {
    private val content =
        owner.column().apply {
            background = Theme.shape(Theme.card, owner.dp(24).toFloat())
            setPadding(owner.dp(24), owner.dp(24), owner.dp(24), owner.dp(20))
        }
    private val heading =
        owner.label("", 22f).apply {
            setTypeface(null, 1)
            letterSpacing = -.025f
        }
    private val message = owner.label("", 14f, Theme.muted)
    private val actions =
        LinearLayout(owner).apply {
            gravity = Gravity.END
            setPadding(0, owner.dp(22), 0, 0)
        }
    private val buttons = mutableMapOf<Int, Button>()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        content.addView(heading)
        content.addView(
            message,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(8) },
        )
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun setTitle(title: CharSequence?) {
        heading.text = title
    }

    fun setMessage(text: String) {
        message.text = text
        message.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun getButton(which: Int): Button = buttons.getValue(which)

    fun body(view: View) {
        content.addView(
            view,
            LinearLayout.LayoutParams(-1, view.layoutParams?.height ?: -2).apply {
                topMargin = owner.dp(16)
            },
        )
    }

    private fun action(which: Int, text: String, listener: ((DialogInterface, Int) -> Unit)?) {
        val button =
            owner
                .button(text) {
                    listener?.invoke(this, which)
                    dismiss()
                }
                .apply {
                    textSize = 14f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(owner.dp(16), 0, owner.dp(16), 0)
                    if (which == DialogInterface.BUTTON_NEGATIVE) {
                        background = Theme.shape(Theme.card, owner.dp(12).toFloat())
                        setTextColor(Theme.muted)
                    }
                }
        buttons[which] = button
        actions.addView(
            button,
            LinearLayout.LayoutParams(0, owner.dp(48), 1f).apply {
                if (actions.childCount > 1) marginStart = owner.dp(10)
            },
        )
    }

    override fun show() {
        super.show()
        window?.setLayout(
            (owner.resources.displayMetrics.widthPixels - owner.dp(48)).coerceAtLeast(
                owner.dp(260)
            ),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(.68f)
    }

    class Builder(private val a: Activity, unusedTheme: Int = 0) {
        private var title = ""
        private var message = ""
        private var body: View? = null
        private var positive: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var negative: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var cancel: ((DialogInterface) -> Unit)? = null

        fun setTitle(s: String) = apply { title = s }

        fun setMessage(s: String) = apply { message = s }

        fun setView(v: View) = apply { body = v }

        fun setPositiveButton(s: String, f: ((DialogInterface, Int) -> Unit)?) = apply {
            positive = s to f
        }

        fun setNegativeButton(s: String, f: ((DialogInterface, Int) -> Unit)?) = apply {
            negative = s to f
        }

        fun setOnCancelListener(f: (DialogInterface) -> Unit) = apply { cancel = f }

        private var entries: Array<String>? = null
        private var selected = -1
        private var choice: ((DialogInterface, Int) -> Unit)? = null

        fun setItems(items: Array<String>, f: (DialogInterface, Int) -> Unit) = apply {
            entries = items
            choice = f
        }

        fun setSingleChoiceItems(
            items: Array<String>,
            current: Int,
            f: (DialogInterface, Int) -> Unit,
        ) = apply {
            entries = items
            selected = current
            choice = f
        }

        fun create(): PrettyDialog {
            val d = PrettyDialog(a)
            d.setTitle(title)
            d.setMessage(message)
            body?.let { d.body(it) }
            entries?.let { items ->
                val list = LinearLayout(a).apply { orientation = 1 }
                items.forEachIndexed { index, text ->
                    val row =
                        a.label(if (selected == index) "✓  $text" else text, 15f).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(a.dp(12), 0, a.dp(12), 0)
                            if (selected == index) {
                                setTextColor(Theme.text)
                                background = Theme.shape(Theme.bg, 12f)
                            }
                            isFocusable = true
                            setOnClickListener {
                                choice?.invoke(d, index)
                                d.dismiss()
                            }
                        }
                    list.addView(row, LinearLayout.LayoutParams(-1, a.dp(48)))
                }
                val scroll = ScrollView(a)
                scroll.addView(list)
                d.body(
                    scroll.apply {
                        layoutParams =
                            LinearLayout.LayoutParams(-1, minOf(items.size * a.dp(48), a.dp(320)))
                    }
                )
            }
            negative?.let { d.action(-2, it.first, it.second) }
            positive?.let { d.action(-1, it.first, it.second) }
            if (positive != null || negative != null) d.content.addView(d.actions)
            d.setOnCancelListener { cancel?.invoke(it) }
            return d
        }

        fun show() = create().apply { show() }
    }
}
