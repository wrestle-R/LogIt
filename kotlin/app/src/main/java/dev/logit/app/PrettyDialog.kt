package dev.logit.app

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** Shared dialog surface. No platform accent, uppercase actions or square grey panel. */
class PrettyDialog(private val owner: Activity) : Dialog(owner) {
    private val content =
        owner.column().apply {
            background = Theme.bordered(Theme.card, Theme.controlBorder, owner.dp(18).toFloat(), owner.dp(1))
            setPadding(owner.dp(20), owner.dp(18), owner.dp(20), owner.dp(16))
        }
    private val heading =
        owner.label("", 22f).apply {
            typeface = Theme.semibold
            letterSpacing = -.015f
            setPadding(0, 0, 0, 0)
        }
    private val message = owner.label("", 15f, Theme.muted).apply {
        setPadding(0, 0, 0, 0)
    }
    private val bodyContainer = LinearLayout(owner).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val bodyScroll = object : ScrollView(owner) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = (owner.resources.displayMetrics.heightPixels * 0.62f).toInt()
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val available = View.MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (mode == View.MeasureSpec.UNSPECIFIED) max else minOf(max, available)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        visibility = View.GONE
        addView(bodyContainer)
    }
    private val actions =
        LinearLayout(owner).apply {
            gravity = Gravity.END
            val stack = owner.resources.configuration.fontScale > 1.25f ||
                owner.resources.configuration.screenWidthDp < 340
            orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0, owner.dp(12), 0, 0)
        }
    private val buttons = mutableMapOf<Int, Button>()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        content.addView(heading)
        content.addView(
            message,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(6) },
        )
        content.addView(
            bodyScroll,
            LinearLayout.LayoutParams(-1, -2, 1f).apply { topMargin = owner.dp(14) },
        )
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun setTitle(title: CharSequence?) {
        heading.text = title
    }

    fun setMessage(text: String) {
        message.text = text
        message.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun getButton(which: Int): Button = buttons.getValue(which)

    fun compactForm() {
        content.setPadding(owner.dp(16), owner.dp(14), owner.dp(16), owner.dp(14))
        heading.textSize = 20f
        (bodyScroll.layoutParams as? LinearLayout.LayoutParams)?.topMargin = owner.dp(10)
        actions.setPadding(0, owner.dp(12), 0, 0)
    }

    fun body(view: View, scroll: Boolean = true) {
        if (!scroll) {
            val index = content.indexOfChild(bodyScroll)
            content.removeViewAt(index)
            content.addView(view, index, LinearLayout.LayoutParams(-1, -2, 1f).apply {
                topMargin = owner.dp(10)
            })
            return
        }
        bodyScroll.visibility = View.VISIBLE
        bodyContainer.addView(
            view,
            LinearLayout.LayoutParams(-1, view.layoutParams?.height ?: -2),
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
                    minHeight = owner.dp(48)
                    minimumHeight = owner.dp(48)
                    stateListAnimator = null
                    setPadding(owner.dp(12), owner.dp(8), owner.dp(12), owner.dp(8))
                    if (which == DialogInterface.BUTTON_NEGATIVE) {
                        background = Theme.bordered(
                            Theme.track,
                            Theme.controlBorder,
                            owner.dp(10).toFloat(),
                            owner.dp(1),
                        )
                        setTextColor(Theme.text)
                    } else {
                        background = Theme.shape(Theme.text, owner.dp(10).toFloat())
                        setTextColor(Theme.bg)
                        typeface = Theme.semibold
                    }
                }
        buttons[which] = button
        actions.addView(
            button,
            if (actions.orientation == LinearLayout.VERTICAL) {
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (actions.childCount > 0) topMargin = owner.dp(8)
                }
            } else LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (actions.childCount > 0) marginStart = owner.dp(8)
            },
        )
    }

    override fun show() {
        super.show()
        val width = (owner.resources.displayMetrics.widthPixels - owner.dp(32)).coerceIn(1, owner.dp(460))
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(.68f)
    }

    class Builder(private val a: Activity, unusedTheme: Int = 0) {
        private var title = ""
        private var message = ""
        private var body: View? = null
        private var scrollBody = true
        private var compact = false
        private var positive: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var negative: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var cancel: ((DialogInterface) -> Unit)? = null

        fun setTitle(s: String) = apply { title = s }

        fun setMessage(s: String) = apply { message = s }

        fun setView(v: View, scroll: Boolean = true) = apply {
            body = v
            scrollBody = scroll
        }

        fun setCompact() = apply { compact = true }

        fun setPositiveButton(s: String, f: ((DialogInterface, Int) -> Unit)?) = apply {
            positive = s to f
        }

        fun setNegativeButton(s: String, f: ((DialogInterface, Int) -> Unit)?) = apply {
            negative = s to f
        }

        fun setOnCancelListener(f: (DialogInterface) -> Unit) = apply { cancel = f }

        private var entries: Array<String>? = null
        private var selected = -1
        private var swatches: IntArray? = null
        private var choice: ((DialogInterface, Int) -> Unit)? = null

        fun setItems(items: Array<String>, f: (DialogInterface, Int) -> Unit) = apply {
            entries = items
            choice = f
        }

        fun setSingleChoiceItems(
            items: Array<String>,
            current: Int,
            colours: IntArray? = null,
            f: (DialogInterface, Int) -> Unit,
        ) = apply {
            entries = items
            selected = current
            swatches = colours
            choice = f
        }

        fun create(): PrettyDialog {
            val d = PrettyDialog(a)
            d.setTitle(title)
            d.setMessage(message)
            body?.let { d.body(it, scrollBody) }
            entries?.let { items ->
                val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                items.forEachIndexed { index, text ->
                    val isSelected = selected == index
                    val row =
                        LinearLayout(a).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = a.dp(48)
                            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
                            val swatch = swatches?.getOrNull(index) ?: Color.TRANSPARENT
                            if (swatch != Color.TRANSPARENT) addView(View(a).apply {
                                background = Theme.shape(swatch, a.dp(5).toFloat())
                            }, LinearLayout.LayoutParams(a.dp(10), a.dp(10)).apply { marginEnd = a.dp(8) })
                            addView(a.label(text, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                            if (isSelected) addView(a.label("✓", 16f).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            })
                            background =
                                if (isSelected)
                                    Theme.bordered(Theme.bg, Theme.controlBorder, a.dp(10).toFloat(), a.dp(1))
                                else null
                            foreground = RippleDrawable(ColorStateList.valueOf(Theme.muted and 0x00ffffff or 0x22000000),
                                null, Theme.shape(Color.WHITE, a.dp(10).toFloat()))
                            this.isSelected = isSelected
                            isFocusable = true
                            setOnClickListener {
                                choice?.invoke(d, index)
                                d.dismiss()
                            }
                        }
                    list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        if (index > 0) topMargin = a.dp(4)
                    })
                }
                d.body(list)
            }
            negative?.let { d.action(-2, it.first, it.second) }
            positive?.let { d.action(-1, it.first, it.second) }
            if (positive != null || negative != null) d.content.addView(d.actions)
            if (compact) d.compactForm()
            d.setOnCancelListener { cancel?.invoke(it) }
            return d
        }

        fun show() = create().apply { show() }
    }
}
