package dev.logit.app

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
import java.time.Instant
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
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, SleepWidget::class.java), null, null)
        }

        private fun historyChart(nights: List<Int?>, goal: Int, width: Int, height: Int, density: Float, accent: Int): Bitmap {
            val chartWidth = width.coerceIn(96, 400)
            val bitmap = Bitmap.createBitmap((chartWidth * density).toInt(), (height * density).toInt(),
                Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(density, density)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val peak = maxOf(goal, nights.filterNotNull().maxOrNull() ?: 0) * 1.08f
            val mark = height - goal / peak * height
            paint.color = Theme.controlBorder
            canvas.drawRect(0f, mark, chartWidth.toFloat(), mark + 1f, paint)
            val lastRecorded = nights.indexOfLast { it != null }
            val slot = chartWidth.toFloat() / nights.size
            val bar = (slot - 4f).coerceAtMost(24f)
            nights.forEachIndexed { index, minutes ->
                val left = slot * index + (slot - bar) / 2
                val barHeight = if (minutes == null) 2f else (minutes.toFloat() / peak * height).coerceAtLeast(3f)
                paint.color = when {
                    minutes == null -> Theme.controlBorder
                    minutes >= goal -> accent
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
            val records = dates.map { date ->
                if (owner == null || habit == null) null
                else PhoneTracking.summary(owner, habit, date.toString())
            }
            val nights = records.map { it?.minutes }
            val latest = nights.indexOfLast { it != null }
            val recordedMinutes = nights.getOrNull(latest)
            val open = PendingIntent.getActivity(context, id,
                Intent(context, PhoneTrackingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("trackingScreen", "sleep")
                    .putExtra("sleepDay", dates.getOrNull(latest)?.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val metrics = context.resources.displayMetrics
            val configuration = context.resources.configuration
            val fontScale = configuration.fontScale
            // Launchers have different row heights. Reserve details for a wider widget
            // or a height that uses more than a typical single row of this screen.
            val detailHeight = maxOf(86 * fontScale, configuration.screenHeightDp * 0.17f)
            val dateFormat = DateTimeFormatter.ofPattern("d MMM")
            val goal = sleep?.goalMinutes ?: SleepTracking.defaultGoalMinutes
            val accent = habit?.let { Theme.colors[it.color.coerceIn(Theme.colors.indices)] } ?: sleepGoalColor()
            val date = dates.getOrNull(latest)
            val title = if (date == null || date == today) "Sleep" else "Sleep · ${date.format(dateFormat)}"
            val value = when {
                habit == null -> "Set up Sleep"
                recordedMinutes == null -> "No entry yet"
                else -> formatUsageMinutes(recordedMinutes)
            }
            val timeFormat = DateTimeFormatter.ofPattern("h:mm a").withZone(Dates.zone)
            val interval = records.getOrNull(latest)?.let { night ->
                "${timeFormat.format(Instant.ofEpochMilli(night.bedtime))} – ${timeFormat.format(Instant.ofEpochMilli(night.wakeTime))}"
            } ?: if (habit == null) "Tap to get started" else "${formatUsageMinutes(goal)} goal"
            val goalStatus = if (recordedMinutes != null && recordedMinutes >= goal) "goal met"
                else "${formatUsageMinutes(goal)} goal"

            fun layout(width: Int, height: Int): RemoteViews {
                val chart = recordedMinutes != null && width >= 128 &&
                    height >= maxOf(detailHeight, 77 * fontScale + 39) &&
                    (width >= 208 || height >= 2.05 * width)
                val wide = !chart && width >= 226 * fontScale && height >= 44 * fontScale
                val showInterval = wide || height >= detailHeight
                val tight = !wide && height < 48 * fontScale
                val valueSize = when {
                    tight || recordedMinutes == null -> 15f
                    wide || height >= 76 * fontScale -> 27f
                    else -> 21f
                }
                return RemoteViews(context.packageName, if (wide) R.layout.widget_sleep_wide else R.layout.widget_sleep).apply {
                    setInt(R.id.sleep_widget_root, "setBackgroundResource",
                        if (Theme.light()) R.drawable.widget_bg_light else R.drawable.widget_bg)
                    setTextViewText(R.id.sleep_widget_title, title)
                    setTextColor(R.id.sleep_widget_title, Theme.muted)
                    setViewVisibility(R.id.sleep_widget_title, if (tight) View.GONE else View.VISIBLE)
                    setTextViewText(R.id.sleep_widget_duration, if (tight && recordedMinutes != null) "Sleep $value" else value)
                    setContentDescription(R.id.sleep_widget_duration,
                        if (recordedMinutes != null) "$title, $value, $goalStatus" else "$title, $value")
                    setTextColor(R.id.sleep_widget_duration, Theme.text)
                    setTextViewTextSize(R.id.sleep_widget_duration, TypedValue.COMPLEX_UNIT_SP, valueSize)
                    setTextViewText(R.id.sleep_widget_status, interval)
                    setTextColor(R.id.sleep_widget_status, Theme.muted)
                    setViewVisibility(R.id.sleep_widget_status, if (showInterval) View.VISIBLE else View.GONE)
                    val showProgress = recordedMinutes != null && showInterval
                    setViewVisibility(R.id.sleep_widget_progress, if (showProgress) View.VISIBLE else View.GONE)
                    if (showProgress && recordedMinutes != null) {
                        setProgressBar(R.id.sleep_widget_progress, goal, recordedMinutes.coerceIn(0, goal), false)
                        setContentDescription(R.id.sleep_widget_progress,
                            "${formatUsageMinutes(recordedMinutes)} of ${formatUsageMinutes(goal)} goal")
                    }
                    if (!wide) {
                        setViewVisibility(R.id.sleep_widget_chart, if (chart) View.VISIBLE else View.GONE)
                        setViewVisibility(R.id.sleep_widget_history, if (chart) View.VISIBLE else View.GONE)
                        setTextColor(R.id.sleep_widget_history, Theme.muted)
                        if (chart) {
                            val chartHeight = (height - 76 * fontScale - 42).toInt().coerceIn(20, 240)
                            setImageViewBitmap(R.id.sleep_widget_chart, historyChart(nights, goal, width - 24, chartHeight, metrics.density.coerceAtMost(2f), accent))
                            setContentDescription(R.id.sleep_widget_chart, dates.zip(nights).joinToString(", ") { (day, minutes) ->
                                "${day.format(dateFormat)}, ${minutes?.let(::formatUsageMinutes) ?: "no entry"}"
                            })
                        }
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

internal fun sleepGoalColor(): Int = if (Theme.light()) 0xff356458.toInt() else 0xff9bc8b7.toInt()
