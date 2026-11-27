package dev.logit.app

import android.app.*
import android.os.Bundle
import android.widget.Toast

class LogActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id =
            intent.getStringExtra("habit")
                ?: run {
                    finish()
                    return
                }
        val owner = Store.trackingOwner()
        if (owner == null) {
            Toast.makeText(this, "Open LogIt and sign in first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val h =
            Store.habitsFor(owner).find { it.id == id }
                ?: run {
                    Toast.makeText(
                            this,
                            "Activity unavailable. Open LogIt to sync.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    finish()
                    return
                }
        val theme =
            if (Theme.light()) android.R.style.Theme_Material_Light_Dialog_Alert
            else android.R.style.Theme_Material_Dialog_Alert
        val d =
            PrettyDialog.Builder(this, theme)
                .setTitle("Log ${h.name}?")
                .setMessage("Add one log for today. You can undo it afterwards.")
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setPositiveButton("Yes, log it", null)
                .setOnCancelListener { finish() }
                .create()
        d.setOnShowListener {
            d.getButton(-1).setOnClickListener {
                d.getButton(-1).isEnabled = false
                d.getButton(-2).isEnabled = false
                d.setCancelable(false)
                d.setMessage("Saving…")
                val day = Dates.today()
                val previous = Store.lastLog()?.id
                Store.log(id, 1, day, expectedOwner = owner) { e ->
                    if (isFinishing) return@log
                    if (e != null) {
                        d.setMessage(e)
                        d.getButton(-1).isEnabled = true
                        d.getButton(-2).isEnabled = true
                        d.setCancelable(true)
                    } else {
                        d.dismiss()
                        val logged = Store.lastLog()?.takeIf {
                            it.id != previous && it.owner == owner && it.habit == id && it.day == day
                        }
                        val builder = PrettyDialog.Builder(this, theme)
                                .setTitle("Saved ${h.name}")
                                .setMessage(
                                    if (OfflineLogQueue.pendingCount() > 0)
                                        "Saved on this device. Syncs when you are online."
                                    else "One completion added."
                                )
                                .setPositiveButton("Done") { _, _ -> finish() }
                                .setOnCancelListener { finish() }
                        if (logged != null) builder.setNegativeButton("Undo", null)
                        val success = builder.create()
                        success.setOnShowListener {
                            if (logged == null) return@setOnShowListener
                            success.getButton(-2).setOnClickListener {
                                success.getButton(-2).isEnabled = false
                                Store.undoLast(logged.id) { undoError ->
                                    if (undoError == null) finish()
                                    else {
                                        success.setMessage(undoError)
                                        success.getButton(-2).isEnabled = true
                                    }
                                }
                            }
                        }
                        success.show()
                    }
                }
            }
        }
        d.show()
    }
}
