package dev.logit.app

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** A log tap writes the durable local queue; network work runs independently. */
object WidgetLogging {
    fun log(context: Context, widgetId: Int, habitId: String?) {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return
        if (
            info.provider.className !in
                listOf(QuickWidget::class.java.name, LogGraphWidget::class.java.name)
        )
            return
        val owner = Store.trackingOwner()
        if (owner == null || habitId == null) {
            Toast.makeText(context, "Open LogIt and sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        val config = Widgets.config(widgetId)
        if (config.optString("owner") != owner) return
        val selected = Widgets.selectedIds(config)
        if (habitId !in selected) return
        val habit = Store.habits.find { it.id == habitId } ?: return
        if (habit.id in PhoneTracking.automatedActivities(owner) && habit.count(Dates.today()) > 0) {
            Toast.makeText(context, "${habit.name} already logged today", Toast.LENGTH_SHORT).show()
            return
        }
        Store.log(habitId, 1, expectedOwner = owner) { error ->
            Toast.makeText(
                    context,
                    error ?: "${habit.name} logged · Undo last in LogIt",
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }
}

class WidgetLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetLogging.log(
            context,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
            intent.getStringExtra("habit"),
        )
    }
}
