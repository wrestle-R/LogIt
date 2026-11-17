package dev.logit.app

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import java.time.LocalDate

object Theme {
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
        get() = if (light()) Color.rgb(248, 248, 248) else Color.rgb(9, 9, 11)

    val card
        get() = if (light()) Color.WHITE else Color.rgb(24, 24, 27)

    val text
        get() = if (light()) Color.rgb(24, 24, 27) else Color.rgb(250, 250, 250)

    val muted
        get() = if (light()) Color.rgb(82, 82, 91) else Color.rgb(161, 161, 170)

    fun shape(color: Int, radius: Float = 22f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    fun graph(
        h: Habit,
        weeks: Int,
        width: Int,
        height: Int,
        color: Int = h.color,
        light: Boolean = light(),
        gradient: Boolean = false,
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
        val accent = colors[color.coerceIn(0, 9)]
        for (i in 0 until visibleWeeks * 7) {
            val day = today.minusDays((visibleWeeks * 7 - 1 - i).toLong()).toString()
            val n = h.count(day)
            val base = if (light) Color.rgb(228, 228, 231) else Color.rgb(39, 39, 42)
            val f = if (n == 0L) 0f else minOf(1f, .25f + n * .18f)
            paint.color =
                Color.rgb(
                    (Color.red(base) * (1 - f) + Color.red(accent) * f).toInt(),
                    (Color.green(base) * (1 - f) + Color.green(accent) * f).toInt(),
                    (Color.blue(base) * (1 - f) + Color.blue(accent) * f).toInt(),
                )
            paint.shader =
                if (gradient && n > 0)
                    LinearGradient(
                        0f,
                        0f,
                        w.toFloat(),
                        hh.toFloat(),
                        intArrayOf(paint.color, colors[(color + 1) % 10]),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                else null
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
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

fun Activity.button(s: String, action: () -> Unit) =
    Button(this).apply {
        text = s
        isAllCaps = false
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

class HeatmapView(
    context: android.content.Context,
    private val habit: Habit,
    private val weeks: Int = 20,
) : android.view.View(context) {
    private var bitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap = Theme.graph(habit, weeks, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
