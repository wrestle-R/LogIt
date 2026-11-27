package dev.logit.app

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** A log tap writes the durable local queue; network work runs independently. */
object WidgetLogging {
    fun log(context: Context, widgetId: Int, habitId: String?, expectedOwner: String?) {
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
        val config = Widgets.configFor(widgetId, owner)
        if (owner != expectedOwner) {
            Widgets.refresh(context)
            return
        }
        val selected = Widgets.selectedIds(config)
        if (habitId !in selected) return
        val habit = Store.habitsFor(owner).find { it.id == habitId } ?: run {
            Widgets.refresh(context)
            Toast.makeText(context, "This activity is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        if (habit.id in PhoneTracking.automatedActivities(owner)) {
            Widgets.refresh(context)
            Toast.makeText(context, "Open LogIt to log ${habit.name} manually", Toast.LENGTH_SHORT).show()
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
            intent.getStringExtra("widgetOwner"),
        )
    }
}
