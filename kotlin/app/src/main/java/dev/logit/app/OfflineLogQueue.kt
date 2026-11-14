package dev.logit.app

import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

data class LogOp(
    val id: String,
    val owner: String,
    val habit: String,
    val day: String,
    val delta: Int,
)

object OfflineLogQueue {
    fun delta(h: Habit, day: String, delta: Int): Habit {
        val n = (h.count(day) + delta).coerceAtLeast(0)
        return h.copy(
            counts = h.counts + (day to n),
            completions = if (n > 0) (h.completions + day).distinct() else h.completions - day,
        )
    }

    fun project(base: List<Habit>, ops: List<LogOp>): List<Habit> = base.map { h ->
        ops.filter { it.habit == h.id && it.id !in h.appliedOps }
            .fold(h) { current, op -> delta(current, op.day, op.delta) }
    }

    @Synchronized
    fun operations(owner: String): List<LogOp> {
        val a = JSONArray(Store.prefs.getString("pending_$owner", "[]") ?: "[]")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LogOp(
                o.getString("id"),
                owner,
                o.getString("habit"),
                o.getString("day"),
                o.getInt("delta"),
            )
        }
    }

    @Synchronized
    private fun persist(owner: String, ops: List<LogOp>) {
        val a =
            JSONArray(
                ops.map {
                    JSONObject()
                        .put("id", it.id)
                        .put("habit", it.habit)
                        .put("day", it.day)
                        .put("delta", it.delta)
                }
            )
        check(Store.prefs.edit().putString("pending_$owner", a.toString()).commit()) {
            "Local storage unavailable"
        }
    }

    @Synchronized
    fun enqueue(owner: String, habit: String, day: String, delta: Int) {
        persist(
            owner,
            operations(owner) + LogOp(UUID.randomUUID().toString(), owner, habit, day, delta),
        )
        schedule()
    }

    @Synchronized
    fun remove(op: LogOp) {
        Store.confirm(op)
        persist(op.owner, operations(op.owner).filter { it.id != op.id })
        Store.save()
    }

    fun pendingCount(): Int =
        if (Store.signedIn) operations(Store.auth.currentUser!!.uid).size else 0

    fun schedule() {
        if (!Store.signedIn || pendingCount() == 0) return
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(Store.context)
            .enqueueUniqueWork("LogItOfflineSync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun apply(op: LogOp) =
        FirebaseFirestore.getInstance().runTransaction { tx ->
            check(Store.signedIn && Store.auth.currentUser!!.uid == op.owner) {
                "Sign in to sync this queue"
            }
            val ref = FirebaseFirestore.getInstance().collection("habits").document(op.habit)
            val d = tx.get(ref)
            check(d.exists()) { "Activity no longer exists" }
            check(d.getString("userId") == op.owner) { "Activity belongs to another account" }
            val applied = d.get("logOps") as? Map<*, *>
            if (applied?.containsKey(op.id) == true) return@runTransaction op.id
            val dates =
                (d.get("completions") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val counts =
                (d.get("dailyCounts") as? Map<*, *>)
                    ?.entries
                    ?.mapNotNull { (k, v) ->
                        if (k is String && v is Number) k to v.toLong() else null
                    }
                    ?.toMap() ?: emptyMap()
            val h = delta(Habit(op.habit, "", dates, counts), op.day, op.delta)
            var streak = 0
            var cursor = LocalDate.parse(Dates.today())
            while (h.count(cursor.toString()) > 0) {
                streak++
                cursor = cursor.minusDays(1)
            }
            tx.update(
                ref,
                mapOf(
                    "dailyCounts.${op.day}" to h.count(op.day),
                    "completions" to h.completions,
                    "currentStreak" to streak,
                    "longestStreak" to maxOf(d.getLong("longestStreak") ?: 0, streak.toLong()),
                    "logOps.${op.id}" to true,
                ),
            )
            op.id
        }
}

class SyncWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!Store.signedIn) return Result.success()
        val owner = Store.auth.currentUser!!.uid
        for (op in OfflineLogQueue.operations(owner)) {
            try {
                Tasks.await(OfflineLogQueue.apply(op), 45, TimeUnit.SECONDS)
                OfflineLogQueue.remove(op)
            } catch (e: Exception) {
                Store.prefs
                    .edit()
                    .putString("syncError", e.cause?.message ?: "Waiting to sync")
                    .apply()
                return Result.retry()
            }
        }
        Store.prefs.edit().remove("syncError").apply()
        return Result.success()
    }
}
