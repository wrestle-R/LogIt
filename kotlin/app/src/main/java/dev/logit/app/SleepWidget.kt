package dev.logit.app

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id, options)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, SleepWidget::class.java))
                .forEach { render(context, manager, it) }
        }

        fun pin(context: Context): Boolean {
            val owner = Store.trackingOwner() ?: return false
            val sleep = PhoneTracking.sleep(owner) ?: return false
            if (Store.habits.none { it.id == sleep.activityId }) return false
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, SleepWidget::class.java), null, null)
        }

        private fun historyChart(nights: List<Int?>, width: Int, height: Int, density: Float): Bitmap {
            val chartWidth = width.coerceIn(96, 400)
            val bitmap = Bitmap.createBitmap((chartWidth * density).toInt(), (height * density).toInt(),
                Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(density, density)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val peak = maxOf(8 * 60, nights.filterNotNull().maxOrNull() ?: 0)
            val lastRecorded = nights.indexOfLast { it != null }
            val slot = chartWidth.toFloat() / nights.size
            val bar = (slot - 4f).coerceAtMost(24f)
            nights.forEachIndexed { index, minutes ->
                val left = slot * index + (slot - bar) / 2
                val barHeight = if (minutes == null) 2f else (minutes.toFloat() / peak * height).coerceAtLeast(3f)
                paint.color = when {
                    minutes == null -> Theme.controlBorder
                    index == lastRecorded -> Theme.text
                    else -> Theme.muted
                }
                canvas.drawRoundRect(left, height - barHeight, left + bar, height.toFloat(), 3f, 3f, paint)
            }
            return bitmap
        }

        fun render(context: Context, manager: AppWidgetManager, id: Int, options: Bundle = manager.getAppWidgetOptions(id)) {
            val owner = Store.trackingOwner()
            val sleep = owner?.let(PhoneTracking::sleep)
            val habit = owner?.let(Store::habitsFor)?.find { it.id == sleep?.activityId }
            val today = LocalDate.parse(Dates.today())
            val dates = (6L downTo 0L).map(today::minusDays)
            val nights = dates.map { date ->
                if (owner == null || habit == null) null
                else PhoneTracking.summary(owner, habit, date.toString())?.let { summary ->
                    Duration.ofMillis(summary.wakeTime - summary.bedtime).toMinutes().toInt()
                }
            }
            val latest = nights.indexOfLast { it != null }
            val recordedMinutes = nights.getOrNull(latest)
            val open = PendingIntent.getActivity(context, id,
                Intent(context, PhoneTrackingActivity::class.java)
                    .putExtra("trackingScreen", "sleep")
                    .putExtra("sleepDay", dates.getOrNull(latest)?.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val metrics = context.resources.displayMetrics
            val fontScale = context.resources.configuration.fontScale
            val dateFormat = DateTimeFormatter.ofPattern("d MMM")
            val title = if (recordedMinutes == null) "Sleep" else "Sleep · ${dates[latest].format(dateFormat)}"
            val value = when {
                habit == null -> "Set up Sleep"
                recordedMinutes == null -> "No sleep logged"
                else -> formatUsageMinutes(recordedMinutes)
            }

            fun layout(width: Int, height: Int): RemoteViews {
                val expanded = recordedMinutes != null && width >= 144 && height >= 62 + 48 * fontScale
                val sideBySide = recordedMinutes != null && !expanded && width >= 244 * fontScale
                val showChart = expanded || sideBySide
                val largeValue = width >= 156 * fontScale && (expanded || height >= 56 * fontScale)
                val layoutId = if (sideBySide) R.layout.widget_sleep_wide else R.layout.widget_sleep
                return RemoteViews(context.packageName, layoutId).apply {
                    setInt(R.id.sleep_widget_root, "setBackgroundResource",
                        if (Theme.light()) R.drawable.widget_bg_light else R.drawable.widget_bg)
                    setTextViewText(R.id.sleep_widget_title, title)
                    setTextColor(R.id.sleep_widget_title, Theme.muted)
                    setTextViewText(R.id.sleep_widget_duration,
                        if (habit != null && recordedMinutes == null && width < 120 * fontScale) "No entry" else value)
                    setTextColor(R.id.sleep_widget_duration, Theme.text)
                    setTextViewTextSize(R.id.sleep_widget_duration, TypedValue.COMPLEX_UNIT_SP,
                        if (recordedMinutes == null) 14f else if (largeValue) 24f else 17f)
                    setTextColor(R.id.sleep_widget_history, Theme.muted)
                    setViewVisibility(R.id.sleep_widget_chart, if (showChart) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.sleep_widget_history, if (expanded) View.VISIBLE else View.GONE)
                    if (showChart) {
                        val chartWidth = width - 24 - if (sideBySide) (104 * fontScale).toInt() else 0
                        val chartHeight = if (expanded && Build.VERSION.SDK_INT >= 31) {
                            (height - 71 * fontScale).toInt().coerceIn(32, 72)
                        } else 32
                        if (Build.VERSION.SDK_INT >= 31) {
                            setViewLayoutHeight(R.id.sleep_widget_chart, chartHeight.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                        }
                        setImageViewBitmap(R.id.sleep_widget_chart, historyChart(nights, chartWidth, chartHeight, metrics.density))
                        val labels = dates.zip(nights).joinToString(", ") { (date, minutes) ->
                            "${date.format(dateFormat)}, ${minutes?.let(::formatUsageMinutes) ?: "no entry"}"
                        }
                        setContentDescription(R.id.sleep_widget_chart, labels)
                    }
                    setOnClickPendingIntent(R.id.sleep_widget_root, open)
                }
            }

            if (Build.VERSION.SDK_INT >= 31) {
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    .orEmpty().distinct().filter { it.width > 0 && it.height > 0 }.take(4)
                if (sizes.isNotEmpty()) {
                    manager.updateAppWidget(id, RemoteViews(sizes.associateWith {
                        layout(it.width.toInt(), it.height.toInt())
                    }))
                    return
                }
            }
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 144)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
            val portrait = layout(minWidth, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight))
            val landscape = layout(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth), minHeight)
            manager.updateAppWidget(id, RemoteViews(landscape, portrait))
        }
    }
}


class SleepWidgetConfigActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val owner = Store.trackingOwner()
        val sleep = owner?.let(PhoneTracking::sleep)
        if (manager.getAppWidgetInfo(id)?.provider == ComponentName(this, SleepWidget::class.java) &&
            sleep != null && Store.habits.any { it.id == sleep.activityId }) {
            SleepWidget.render(this, manager, id)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }
        finish()
    }
}
