package dev.logit.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal class StatisticsChart(
    private val activity: Activity,
    private val dates: List<LocalDate>,
    private val values: List<Long>,
    private val daysPerBar: Int,
    private val barColor: Int,
) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numbers = NumberFormat.getIntegerInstance()
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM")
    private val axisDates = if (daysPerBar == 1) DateTimeFormatter.ofPattern("EEE") else dateFormat
    private val xLabels = values.indices.map { dates[it * daysPerBar].format(axisDates) }
    private val peak = values.maxOrNull() ?: 0L
    private val middle = peak / 2 + peak % 2
    private val ticks = if (peak < 3) listOf(0L, peak) else listOf(0L, middle, peak)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = values.mapIndexed { index, count ->
            val start = dates[index * daysPerBar].format(dateFormat)
            val end = dates[(index + 1) * daysPerBar - 1].format(dateFormat)
            val period = if (daysPerBar == 1) start else "$start to $end"
            "$period: ${numbers.format(count)} ${if (count == 1L) "log" else "logs"}"
        }.joinToString("; ")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (peak == 0L) return

        val density = resources.displayMetrics.density
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        val axisLeft = ticks.maxOf { paint.measureText(numbers.format(it)) } + activity.dp(7)
        val axisRight = width - activity.dp(2).toFloat()
        val top = (paint.descent() - paint.ascent()) / 2 + density
        val baseline = height - (paint.descent() - paint.ascent()) - activity.dp(5)
        if (axisRight <= axisLeft || baseline <= top) return

        fun y(value: Long) = baseline - ((baseline - top) * (value.toDouble() / peak)).toFloat()
        paint.strokeWidth = density
        for (tick in ticks) {
            val line = y(tick)
            paint.color = Theme.divider
            canvas.drawLine(axisLeft, line, axisRight, line, paint)
            paint.color = Theme.muted
            canvas.drawText(numbers.format(tick), 0f, line - (paint.ascent() + paint.descent()) / 2, paint)
        }

        val slot = (axisRight - axisLeft) / values.size
        val barWidth = minOf(slot * .68f, activity.dp(36).toFloat())
        paint.color = barColor
        values.forEachIndexed { index, value ->
            if (value > 0) {
                val left = axisLeft + slot * index + (slot - barWidth) / 2
                val barTop = minOf(y(value), baseline - density)
                canvas.drawRoundRect(left, barTop, left + barWidth, baseline, 2 * density, 2 * density, paint)
            }
        }

        paint.color = Theme.muted
        val labelBaseline = height - paint.descent()
        val spacing = xLabels.maxOf(paint::measureText) + activity.dp(5)
        val step = ceil(spacing / slot).toInt().coerceAtLeast(1)
        for (index in xLabels.indices step step) {
            val text = xLabels[index]
            val textWidth = paint.measureText(text)
            if (textWidth > axisRight - axisLeft) continue
            val center = axisLeft + slot * (index + .5f)
            val left = (center - textWidth / 2).coerceIn(axisLeft, axisRight - textWidth)
            canvas.drawText(text, left, labelBaseline, paint)
        }
    }
}
