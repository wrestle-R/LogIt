package dev.logit.app

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.time.LocalDate

object Theme {
    val regular by lazy { Store.context.resources.getFont(R.font.dm_sans) }
    val medium by lazy {
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(regular, 500, false) else regular
    }
    val semibold by lazy {
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(regular, 650, false)
        else Typeface.create(regular, Typeface.BOLD)
    }

    val colors =
        intArrayOf(
            0xff4ade80.toInt(),
            0xff22d3ee.toInt(),
            0xff60a5fa.toInt(),
            0xffa78bfa.toInt(),
            0xfff472b6.toInt(),
            0xfffb7185.toInt(),
            0xfffb923c.toInt(),
            0xfffacc15.toInt(),
            0xffa3e635.toInt(),
            0xff2dd4bf.toInt(),
        )
    val names =
        arrayOf(
            "Green",
            "Cyan",
            "Blue",
            "Violet",
            "Pink",
            "Rose",
            "Orange",
            "Yellow",
            "Lime",
            "Teal",
        )

    fun light() = Store.prefs.getString("theme", "dark") == "light"

    val bg
        get() = if (light()) Color.rgb(245, 244, 242) else Color.rgb(16, 16, 16)

    val card
        get() = if (light()) Color.rgb(252, 251, 249) else Color.rgb(31, 31, 34)

    val track
        get() = if (light()) Color.rgb(235, 234, 232) else Color.rgb(24, 24, 27)

    val text
        get() = if (light()) Color.rgb(24, 24, 27) else Color.rgb(233, 232, 229)

    val muted
        get() = if (light()) Color.rgb(82, 82, 91) else Color.rgb(161, 161, 170)

    val divider
        get() = if (light()) Color.rgb(228, 228, 231) else Color.rgb(57, 57, 61)

    val cardBorder
        get() = if (light()) 0xffe4e4e7.toInt() else 0xff2e2e32.toInt()

    val controlBorder
        get() = if (light()) 0xffd4d4d8.toInt() else 0xff3f3f46.toInt()

    val destructive
        get() = if (light()) Color.rgb(220, 38, 38) else Color.rgb(248, 113, 113)

    private val texture by lazy {
        BitmapFactory.decodeResource(Store.context.resources, R.drawable.t3_background)
    }

    fun dashboardBackground() = BitmapDrawable(Store.context.resources, texture).apply {
        setTileModeXY(Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        isFilterBitmap = true
        if (light()) {
            // Use the charcoal texture's grain to shade a warm paper surface.
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                -.8f, 0f, 0f, 0f, 254f,
                0f, -.8f, 0f, 0f, 253f,
                0f, 0f, -.8f, 0f, 251f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }

    fun shape(color: Int, radius: Float = 22f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    fun bordered(
        color: Int,
        stroke: Int = controlBorder,
        radius: Float = 22f,
        strokeWidthPx: Int = 2,
    ) =
        shape(color, radius).apply {
            setStroke(strokeWidthPx, stroke)
        }

    fun centeredGraphInset(width: Int, density: Float): Int {
        val cell = 14 * density
        val gap = 3 * density
        val columns = ((width + gap) / (cell + gap)).toInt().coerceIn(1, 52)
        return ((width - (columns * (cell + gap) - gap)) / 2).toInt().coerceAtLeast(0)
    }

    fun graph(
        h: Habit,
        weeks: Int,
        width: Int,
        height: Int,
        light: Boolean = light(),
        binary: Boolean = false,
        center: Boolean = false,
    ): Bitmap {
        val density = Store.context.resources.displayMetrics.density
        val w = width.coerceIn(80, 1600)
        val hh = height.coerceIn(60, 700)
        val bitmap = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = 14f * density
        val gap = 3f * density
        val radius = 2f * density
        val visibleWeeks = minOf(weeks, maxOf(1, ((w + gap) / (cell + gap)).toInt()))
        val today = LocalDate.parse(Dates.today())
        val accent = colors[h.color.coerceIn(colors.indices)]
        for (i in 0 until visibleWeeks * 7) {
            val day = today.monday().minusWeeks((visibleWeeks - 1).toLong()).plusDays(i.toLong())
            if (day > today) continue
            val n = h.count(day.toString())
            val base = if (light) Color.rgb(228, 228, 231) else Color.rgb(39, 39, 42)
            val f = when {
                n == 0L -> 0f
                binary -> 1f
                else -> minOf(1f, .25f + n * .18f)
            }
            paint.color =
                Color.rgb(
                    (Color.red(base) * (1 - f) + Color.red(accent) * f).toInt(),
                    (Color.green(base) * (1 - f) + Color.green(accent) * f).toInt(),
                    (Color.blue(base) * (1 - f) + Color.blue(accent) * f).toInt(),
                )
            val x =
                i / 7 * (cell + gap) +
                    if (center) (w - (visibleWeeks * (cell + gap) - gap)) / 2 else 0f
            val y = i % 7 * (cell + gap)
            canvas.drawRoundRect(x, y, x + cell, y + cell, radius, radius, paint)
        }
        return bitmap
    }
}

fun Activity.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

fun Activity.label(s: String, size: Float = 16f, color: Int = Theme.text) =
    TextView(this).apply {
        text = s
        textSize = size
        typeface = Theme.regular
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

fun Activity.button(s: String, action: () -> Unit) =
    Button(this).apply {
        text = s
        isAllCaps = false
        typeface = Theme.medium
        letterSpacing = 0f
        minHeight = dp(48)
        setTextColor(Theme.bg)
        background = Theme.shape(Theme.text, dp(16).toFloat())
        setOnClickListener { action() }
    }

fun Activity.column() =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        setBackgroundColor(Theme.bg)
    }

fun Activity.themedDivider() =
    View(this).apply {
        setBackgroundColor(Theme.divider)
    }

fun Activity.applyMonochromeCursor(input: EditText) {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        input.textCursorDrawable = Theme.shape(Theme.text, 0f).apply { setSize(dp(2), dp(18)) }
    }
}

/** Monochrome pill toggle matching LogIt's zinc palette; replaces Android's teal Switch. */
class ThemedSwitch(
    owner: Activity,
    initial: Boolean,
    private val labelText: String,
    private val onChange: (Boolean) -> Unit,
) : View(owner) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var isChecked: Boolean = initial
        private set
    private var position = if (initial) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = labelText
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            stateDescription = if (isChecked) "On" else "Off"
        }
    }

    fun setChecked(checked: Boolean, notify: Boolean = false) {
        if (isChecked == checked) return
        isChecked = checked
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            stateDescription = if (isChecked) "On" else "Off"
        }
        val target = if (isChecked) 1f else 0f
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            position = target
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(position, target).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (notify) onChange(isChecked)
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!isChecked, notify = true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        val trackW = 46f
        val trackH = 26f
        val left = (w - trackW) / 2f
        val top = (h - trackH) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) Theme.text else Theme.track
        canvas.drawRoundRect(left, top, left + trackW, top + trackH, trackH / 2f, trackH / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (isChecked) Theme.text else Theme.controlBorder
        canvas.drawRoundRect(left, top, left + trackW, top + trackH, trackH / 2f, trackH / 2f, paint)

        val thumbR = 9.5f
        val cx = left + 13f + 20f * position
        val cy = top + trackH / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) Theme.bg else Theme.muted
        canvas.drawCircle(cx, cy, thumbR, paint)
        canvas.restore()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

class HeatmapView(
    context: android.content.Context,
    private val habit: Habit,
    private val weeks: Int = 20,
    private val binary: Boolean = false,
) : android.view.View(context) {
    private var bitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0) bitmap = Theme.graph(habit, weeks, w, h, binary = binary)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
