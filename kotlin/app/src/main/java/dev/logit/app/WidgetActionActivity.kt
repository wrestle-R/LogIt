package dev.logit.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Collection widgets require an Activity template to support opening the app as well. NoDisplay
 * dispatches a log synchronously without showing an app screen or dialog.
 */
class WidgetActionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra("log", false)) {
            WidgetLogging.log(
                this,
                intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                intent.getStringExtra("habit"),
                intent.getStringExtra("widgetOwner"),
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("habit", intent.getStringExtra("habit"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
