package dev.logit.app

import android.app.Activity
import android.os.Bundle

class HealthPrivacyActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        PrettyDialog.Builder(this)
            .setTitle("Health data in LogIt")
            .setMessage(
                "LogIt reads the steps, workouts, or sleep you allow through Health Connect. " +
                    "It uses them on this phone to apply your rules when you open LogIt. " +
                    "Only the resulting activity completions and sleep summaries sync to your account.\n\n" +
                    "Manage access in Android Health Connect settings, remove rules in LogIt, " +
                    "or delete an activity in LogIt to remove its saved history.",
            )
            .setPositiveButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
