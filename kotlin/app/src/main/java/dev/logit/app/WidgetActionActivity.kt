package dev.logit.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keeps widget confirmation above the launcher instead of opening the dashboard underneath. */
class WidgetActionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val target =
            if (intent.getBooleanExtra("log", false)) LogActivity::class.java
            else MainActivity::class.java
        startActivity(
            Intent(this, target)
                .putExtra("habit", intent.getStringExtra("habit"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
