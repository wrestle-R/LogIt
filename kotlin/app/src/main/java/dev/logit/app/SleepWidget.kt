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
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate

class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id)
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

        private fun historyChart(nights: List<Int?>): Bitmap {
            val bitmap = Bitmap.createBitmap(280, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val peak = maxOf(8 * 60, nights.filterNotNull().maxOrNull() ?: 0)
            nights.forEachIndexed { index, minutes ->
                val left = index * 40f + 4f
                val size = if (minutes == null) 3f else (minutes.toFloat() / peak * 60f).coerceAtLeast(3f)
                paint.color = if (minutes == null) Theme.controlBorder else Theme.text
                canvas.drawRoundRect(left, 64f - size, left + 32f, 64f, 4f, 4f, paint)
            }
            return bitmap
        }

        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val owner = Store.trackingOwner()
            val sleep = owner?.let(PhoneTracking::sleep)
            val habit = Store.habits.find { it.id == sleep?.activityId }
            val active = habit != null
            val today = LocalDate.parse(Dates.today())
            val summaries = if (owner != null && active) (6L downTo 0L).map { offset ->
                PhoneTracking.summary(owner, habit, today.minusDays(offset).toString())
            } else emptyList()
            val lastNight = summaries.lastOrNull()
            val options = manager.getAppWidgetOptions(id)
            val expanded = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= 210 &&
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 116
            val views = RemoteViews(context.packageName, R.layout.widget_sleep)
            views.setInt(R.id.sleep_widget_root, "setBackgroundResource",
                if (Theme.light()) R.drawable.widget_bg_light else R.drawable.widget_bg)
            views.setTextColor(R.id.sleep_widget_title, Theme.text)
            views.setTextColor(R.id.sleep_widget_duration, Theme.text)
            views.setTextColor(R.id.sleep_widget_history, Theme.muted)
            views.setTextViewText(R.id.sleep_widget_duration, when {
                !active -> "Tracking off"
                lastNight == null -> "No estimate yet"
                else -> formatUsageMinutes(Duration.ofMillis(lastNight.wakeTime - lastNight.bedtime).toMinutes().toInt())
            })
            if (expanded && active) {
                val nights = summaries.map { it?.let { summary ->
                    Duration.ofMillis(summary.wakeTime - summary.bedtime).toMinutes().toInt()
                } }
                views.setImageViewBitmap(R.id.sleep_widget_chart, historyChart(nights))
                val labels = nights.mapIndexed { index, minutes ->
                    val date = today.minusDays((6 - index).toLong())
                    "$date, ${minutes?.let(::formatUsageMinutes) ?: "no entry"}"
                }
                views.setContentDescription(R.id.sleep_widget_chart, labels.joinToString(", "))
                views.setViewVisibility(R.id.sleep_widget_chart, View.VISIBLE)
                views.setTextViewText(R.id.sleep_widget_history, "Last 7 nights")
                views.setViewVisibility(R.id.sleep_widget_history, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.sleep_widget_chart, View.GONE)
                views.setViewVisibility(R.id.sleep_widget_history, View.GONE)
            }
            val open = PendingIntent.getActivity(context, id,
                Intent(context, PhoneTrackingActivity::class.java).putExtra("trackingScreen", "sleep"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.sleep_widget_root, open)
            manager.updateAppWidget(id, views)
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
