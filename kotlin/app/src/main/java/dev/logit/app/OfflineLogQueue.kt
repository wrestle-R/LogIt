package dev.logit.app

import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

data class LogOp(
    val id: String,
    val owner: String,
    val habit: String,
    val day: String,
    val delta: Int,
    val automatic: Boolean = false,
    val reversesAutomatic: String? = null,
)

sealed interface LogSyncResult {
    data class Applied(val count: Long, val skippedAutomatic: Boolean) : LogSyncResult
    data object Missing : LogSyncResult
}

object OfflineLogQueue {
    fun withCount(h: Habit, day: String, count: Long): Habit = h.copy(
        counts = h.counts + (day to count),
        completions = if (count > 0) (h.completions + day).distinct() else h.completions - day,
    )

    fun delta(h: Habit, day: String, delta: Int): Habit =
        withCount(h, day, (h.count(day) + delta).coerceAtLeast(0))

    fun automaticOp(owner: String, habit: Habit, day: String): LogOp {
        val id = UUID.nameUUIDFromBytes("${habit.id}:$day".toByteArray(Charsets.UTF_8))
        val delta = if (habit.count(day) > 0) 0 else 1
        return LogOp("auto-$id", owner, habit.id, day, delta, automatic = true)
    }

    fun applyToHabit(h: Habit, op: LogOp): Habit {
        if (op.id in h.appliedOps) return h
        val skipAutomatic = op.automatic && (op.delta == 0 || h.count(op.day) > 0)
        val skipReversal = op.reversesAutomatic?.let {
            it !in h.appliedOps || it in h.skippedAutomaticOps
        } ?: false
        val updated = if (skipAutomatic || skipReversal) h else delta(h, op.day, op.delta)
        return updated.copy(
            appliedOps = h.appliedOps + op.id,
            skippedAutomaticOps = if (skipAutomatic) h.skippedAutomaticOps + op.id else h.skippedAutomaticOps,
        )
    }

    fun project(base: List<Habit>, ops: List<LogOp>): List<Habit> = base.map { h ->
        ops.filter { it.habit == h.id }.fold(h, ::applyToHabit)
    }

    fun reversal(history: List<LogOp>, expectedId: String): LogOp {
        val reversed = UndoHistory.reverse(history, expectedId)
        return if (reversed.automatic) reversed.copy(
            id = "undo-$expectedId",
            automatic = false,
            reversesAutomatic = expectedId,
        ) else reversed
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
                o.optBoolean("automatic"),
                if (o.has("reversesAutomatic")) o.getString("reversesAutomatic") else null,
            )
        }
    }

    private fun persist(owner: String, ops: List<LogOp>, history: List<LogOp>) {
        val pending = JSONArray(ops.map {
            JSONObject()
                .put("id", it.id)
                .put("habit", it.habit)
                .put("day", it.day)
                .put("delta", it.delta)
                .put("automatic", it.automatic)
                .put("reversesAutomatic", it.reversesAutomatic)
        })
        val undo = JSONArray(history.map {
            JSONObject()
                .put("id", it.id)
                .put("habit", it.habit)
                .put("day", it.day)
                .put("automatic", it.automatic)
        })
        check(
            Store.prefs.edit()
                .putString("pending_$owner", pending.toString())
                .putString("undo_history_$owner", undo.toString())
                .remove("last_log_$owner")
                .commit()
        ) { "Local storage unavailable" }
    }

    @Synchronized
    fun enqueue(owner: String, habit: String, day: String, delta: Int) {
        check(Store.habitsFor(owner).any { it.id == habit }) { "Activity no longer exists" }
        val op = LogOp(UUID.randomUUID().toString(), owner, habit, day, delta)
        val history = undoHistory(owner)
        val previous = history.lastOrNull()
        persist(
            owner,
            operations(owner) + op,
            UndoHistory.record(
                history,
                added = op.takeIf { delta > 0 },
                removeLatest = delta < 0 && previous?.habit == habit && previous.day == day,
            ),
        )
        schedule()
    }

    @Synchronized
    fun enqueueAutomatic(owner: String, id: String, day: String) {
        val habit = Store.habitsFor(owner).find { it.id == id }
            ?: error("Activity no longer exists")
        val op = automaticOp(owner, habit, day)
        if (op.id !in habit.appliedOps) {
            val history = undoHistory(owner)
            persist(owner, operations(owner) + op, if (op.delta > 0) history + op else history)
        }
        schedule()
    }

    @Synchronized
    fun undoHistory(owner: String): List<LogOp> {
        val stored = Store.prefs.getString("undo_history_$owner", null)
        val legacy = Store.prefs.getString("last_log_$owner", null)
        val a = if (stored != null) JSONArray(stored)
                else if (legacy != null) JSONArray().put(JSONObject(legacy)) else JSONArray()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LogOp(
                o.getString("id"),
                owner,
                o.getString("habit"),
                o.getString("day"),
                1,
                automatic = o.optBoolean("automatic"),
            )
        }
    }

    @Synchronized
    fun lastLog(owner: String): LogOp? = undoHistory(owner).lastOrNull()

    @Synchronized
    fun undo(owner: String, expectedId: String) {
        val history = undoHistory(owner)
        val reversed = reversal(history, expectedId)
        check(Store.habitsFor(owner).any { it.id == reversed.habit && it.count(reversed.day) > 0 }) {
            "That completion is no longer available"
        }
        persist(owner, operations(owner) + reversed, history.dropLast(1))
        schedule()
    }

    private fun discard(owner: String, shouldDiscard: (LogOp) -> Boolean) {
        val ops = operations(owner)
        val history = undoHistory(owner)
        val remainingOps = ops.filterNot(shouldDiscard)
        val remainingHistory = history.filterNot(shouldDiscard)
        if (remainingOps.size != ops.size || remainingHistory.size != history.size) {
            persist(owner, remainingOps, remainingHistory)
        }
    }

    @Synchronized
    fun discardActivity(owner: String, habit: String) = discard(owner) { it.habit == habit }

    @Synchronized
    fun discardMissing(owner: String, existing: Set<String>) = discard(owner) { it.habit !in existing }

    @Synchronized
    fun finish(op: LogOp, result: LogSyncResult) {
        when (result) {
            LogSyncResult.Missing -> {
                Store.forgetActivity(op.owner, op.habit)
                discardActivity(op.owner, op.habit)
            }
            is LogSyncResult.Applied -> {
                val ops = operations(op.owner)
                if (ops.none { it.id == op.id }) return
                // Persist the acknowledged count and receipt before forgetting the pending operation.
                Store.confirm(op, result)
                val history = undoHistory(op.owner)
                persist(
                    op.owner,
                    ops.filterNot { it.id == op.id },
                    if (result.skippedAutomatic) history.filterNot { it.id == op.id } else history,
                )
            }
        }
    }

    fun pendingCount(): Int = Store.accountOwner()?.let { operations(it).size } ?: 0

    fun schedule() {
        if (pendingCount() == 0) return
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

    private fun apply(op: LogOp) =
        FirebaseFirestore.getInstance().runTransaction { tx ->
            check(Store.accountOwner() == op.owner) { "Sign in to sync this queue" }
            val ref = FirebaseFirestore.getInstance().collection("habits").document(op.habit)
            val d = tx.get(ref)
            check(Store.accountOwner() == op.owner) { "Sign in to sync this queue" }
            if (!d.exists()) return@runTransaction LogSyncResult.Missing
            check(d.getString("userId") == op.owner) { "Activity belongs to another account" }
            val current = Store.decodeDocument(d)
            val updated = applyToHabit(current, op)
            val skipped = op.id in updated.skippedAutomaticOps
            if (updated !== current) {
                val receipt = !skipped
                if (updated.count(op.day) == current.count(op.day)) {
                    tx.update(ref, "logOps.${op.id}", receipt)
                } else {
                    var streak = 0
                    var cursor = LocalDate.parse(Dates.today())
                    while (updated.count(cursor.toString()) > 0) {
                        streak++
                        cursor = cursor.minusDays(1)
                    }
                    tx.update(
                        ref,
                        mapOf(
                            "dailyCounts.${op.day}" to updated.count(op.day),
                            "completions" to updated.completions,
                            "currentStreak" to streak,
                            "longestStreak" to maxOf(d.getLong("longestStreak") ?: 0, streak.toLong()),
                            "logOps.${op.id}" to receipt,
                        ),
                    )
                }
            }
            LogSyncResult.Applied(updated.count(op.day), skipped)
        }

    fun sync(op: LogOp): LogSyncResult {
        try {
            return Tasks.await(apply(op), 45, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val failure = e.cause as? FirebaseFirestoreException ?: throw e
            if (failure.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            if (Store.accountOwner() != op.owner) throw e

            // Owner-only rules can deny a direct read of a deleted document. The
            // same owner-scoped query used by the activity list can prove absence.
            val query = FirebaseFirestore.getInstance().collection("habits")
                .whereEqualTo("userId", op.owner)
                .whereEqualTo(FieldPath.documentId(), op.habit)
            val result = Tasks.await(query.get(Source.SERVER), 45, TimeUnit.SECONDS)
            if (Store.accountOwner() != op.owner || result.metadata.isFromCache ||
                result.metadata.hasPendingWrites() || !result.isEmpty
            ) throw e
            return LogSyncResult.Missing
        }
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val owner = Store.accountOwner() ?: return Result.success()
        for (op in OfflineLogQueue.operations(owner)) {
            if (Store.accountOwner() != owner) return Result.success()
            if (OfflineLogQueue.operations(owner).none { it.id == op.id }) continue
            try {
                OfflineLogQueue.finish(op, OfflineLogQueue.sync(op))
                if (Store.trackingOwner() == owner) Store.notifyChanged()
            } catch (e: Exception) {
                if (Store.accountOwner() != owner) return Result.success()
                Store.prefs
                    .edit()
                    .putString("syncError", e.cause?.message ?: "Waiting to sync")
                    .apply()
                return Result.retry()
            }
        }
        if (Store.accountOwner() == owner) Store.prefs.edit().remove("syncError").apply()
        return Result.success()
    }
}
