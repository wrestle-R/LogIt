package dev.logit.app

import android.app.Application
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SleepSummary(
    val bedtime: Long,
    val wakeTime: Long,
    val edited: Boolean = false,
    val healthConnect: Boolean = false,
) {
    internal fun fields() = mapOf(
        "bedtime" to bedtime, "wakeTime" to wakeTime, "edited" to edited, "healthConnect" to healthConnect,
    )

    internal val sourcePriority get() = when {
        edited -> 2
        healthConnect -> 1
        else -> 0
    }

    companion object {
        internal fun parse(bedtime: Any?, wakeTime: Any?, edited: Any?, healthConnect: Any? = null): SleepSummary? {
            val start = (bedtime as? Number)?.toLong() ?: return null
            val end = (wakeTime as? Number)?.toLong() ?: return null
            return if (end > start) SleepSummary(start, end, edited == true, healthConnect == true) else null
        }
    }
}

internal fun SleepSummary?.canBeReplacedBy(summary: SleepSummary): Boolean =
    this == null || (this != summary && summary.sourcePriority >= sourcePriority)

data class Habit(
    val id: String,
    val name: String,
    val completions: List<String> = emptyList(),
    val counts: Map<String, Long> = emptyMap(),
    val order: Long = 0,
    val color: Int = 0,
    val appliedOps: Set<String> = emptySet(),
    val goal: Goal? = null,
    val sleepSummaries: Map<String, SleepSummary> = emptyMap(),
    val skippedAutomaticOps: Set<String> = emptySet(),
) {
    fun count(day: String) = maxOf(counts[day] ?: 0, if (completions.contains(day)) 1L else 0L)

    fun total() = (completions.toSet() + counts.keys).sumOf { count(it) }
}

object Dates {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    fun today(): String = LocalDate.now(zone).toString()
}

class LogItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

object Store {
    private const val DEMO = "demo"

    private data class Cache(val owner: String?, val habits: List<Habit>)
    private val cacheLock = Any()
    @Volatile private var cache = Cache(null, emptyList())
    private val demoUndo = mutableListOf<LogOp>()

    lateinit var context: Context
    val habits: List<Habit>
        get() = trackingOwner()?.let(::habitsFor).orEmpty()

    internal fun habitsFor(owner: String): List<Habit> {
        if (trackingOwner() != owner) return emptyList()
        // Read the queue first. A synced op is written to the cache before it leaves the queue.
        val pending = if (owner == DEMO) emptyList() else OfflineLogQueue.operations(owner)
        val current = cache
        if (current.owner != owner || trackingOwner() != owner) return emptyList()
        return if (pending.isEmpty()) current.habits else OfflineLogQueue.project(current.habits, pending)
    }

    var changed: (() -> Unit)? = null
    var error: ((String) -> Unit)? = null
    private var listener: ListenerRegistration? = null
    private var listenerGeneration = 0

    val prefs
        get() = context.getSharedPreferences("logit", Context.MODE_PRIVATE)

    val demo
        get() = prefs.getBoolean("demo", false)

    val configured
        get() = FirebaseApp.getApps(context).isNotEmpty()

    val auth
        get() = FirebaseAuth.getInstance()

    internal fun accountOwner(): String? = if (configured) auth.currentUser?.uid else null

    fun trackingOwner(): String? = if (demo) DEMO else accountOwner()

    val signedIn
        get() = accountOwner() != null

    val ready
        get() = trackingOwner() != null

    fun init(c: Context) {
        context = c.applicationContext
        loadCache(trackingOwner())
        if (configured)
            auth.addAuthStateListener {
                listen()
                OfflineLogQueue.schedule()
                GoalReminders.schedule(context)
            }
    }

    private fun cacheKey(owner: String?) =
        if (owner == DEMO) "demo_habits" else "cache_" + (owner ?: "signed_out")

    private fun loadCache(owner: String?) = synchronized(cacheLock) {
        if (trackingOwner() == owner) {
            cache = Cache(owner, decode(prefs.getString(cacheKey(owner), "[]") ?: "[]"))
        }
    }

    private fun updateCache(
        owner: String,
        durable: Boolean = false,
        change: (List<Habit>) -> List<Habit>,
    ): Boolean = synchronized(cacheLock) {
        val current = cache
        if (trackingOwner() != owner || current.owner != owner) return@synchronized false
        val habits = change(current.habits)
        if (habits != current.habits) {
            val editor = prefs.edit().putString(cacheKey(owner), encode(habits))
            if (durable) check(editor.commit()) { "Local storage unavailable" } else editor.apply()
            cache = Cache(owner, habits)
        }
        true
    }

    internal fun confirm(op: LogOp, result: LogSyncResult.Applied) {
        updateCache(op.owner, durable = true) { habits ->
            habits.map { h ->
                if (h.id != op.habit || op.id in h.appliedOps) h
                else OfflineLogQueue.withCount(h, op.day, result.count).copy(
                    appliedOps = h.appliedOps + op.id,
                    skippedAutomaticOps = if (result.skippedAutomatic) {
                        h.skippedAutomaticOps + op.id
                    } else h.skippedAutomaticOps,
                )
            }
        }
    }

    internal fun forgetActivity(owner: String, id: String) {
        updateCache(owner, durable = true) { habits -> habits.filterNot { it.id == id } }
    }

    internal fun notifyChanged() {
        Widgets.refresh(context)
        changed?.invoke()
    }

    private fun encode(habits: List<Habit>): String = JSONArray(habits.map { h ->
        JSONObject()
            .put("id", h.id)
            .put("name", h.name)
            .put("completions", JSONArray(h.completions))
            .put("dailyCounts", JSONObject(h.counts))
            .put("order", h.order)
            .put("color", h.color)
            .put("appliedOps", JSONArray(h.appliedOps.toList()))
            .put("skippedAutomaticOps", JSONArray(h.skippedAutomaticOps.toList()))
            .put("goal", h.goal?.let { JSONObject(it.fields()) })
            .put("sleepSummaries", JSONObject(h.sleepSummaries.mapValues { JSONObject(it.value.fields()) }))
    }).toString()

    private fun decode(s: String): List<Habit> =
        try {
            val a = JSONArray(s)
            (0 until a.length())
                .map { i ->
                    val o = a.getJSONObject(i)
                    val dates = o.optJSONArray("completions") ?: JSONArray()
                    val counts = o.optJSONObject("dailyCounts") ?: JSONObject()
                    val summaries = o.optJSONObject("sleepSummaries") ?: JSONObject()
                    Habit(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        completions = (0 until dates.length()).map { dates.getString(it) },
                        counts = counts.keys().asSequence().associateWith { counts.optLong(it) },
                        order = o.optLong("order"),
                        color = o.optInt("color"),
                        appliedOps = stringSet(o.optJSONArray("appliedOps")),
                        goal = o.optJSONObject("goal")?.let {
                            Goal.parse(it.optString("period"), it.optInt("target"), it.optString("since"))
                        },
                        sleepSummaries = summaries.keys().asSequence().mapNotNull { day ->
                            val fields = summaries.optJSONObject(day) ?: return@mapNotNull null
                            SleepSummary.parse(
                                fields.opt("bedtime"), fields.opt("wakeTime"), fields.opt("edited"), fields.opt("healthConnect"),
                            )?.let { day to it }
                        }.toMap(),
                        skippedAutomaticOps = stringSet(o.optJSONArray("skippedAutomaticOps")),
                    )
                }
                .sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }

    private fun stringSet(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()

    fun useDemo() {
        listener?.remove()
        listenerGeneration++
        val hasDemo = prefs.contains(cacheKey(DEMO))
        prefs.edit().putBoolean("demo", true).apply()
        loadCache(DEMO)
        if (!hasDemo) {
            val today = LocalDate.parse(Dates.today())
            updateCache(DEMO) {
                listOf("Read", "Walk", "Meditate", "Practice").mapIndexed { i, n ->
                    val counts =
                        (0..111)
                            .filter { (it + i) % 4 != 0 }
                            .associate {
                                today.minusDays(it.toLong()).toString() to
                                    ((it + i) % 4 + 1).toLong()
                            }
                    Habit("demo-$i", n, counts.keys.toList(), counts, i.toLong(), i)
                }
            }
        }
        notifyChanged()
    }

    fun leaveDemo() {
        prefs.edit().putBoolean("demo", false).apply()
        listen()
    }

    fun listen() {
        listener?.remove()
        val generation = ++listenerGeneration
        val owner = trackingOwner()
        loadCache(owner)
        notifyChanged()
        if (owner != null && owner != DEMO) {
            listener = FirebaseFirestore.getInstance().collection("habits")
                .whereEqualTo("userId", owner)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
                    if (trackingOwner() != owner || generation != listenerGeneration) {
                        return@addSnapshotListener
                    }
                    if (e != null) {
                        error?.invoke("Could not sync activities: " + e.localizedMessage)
                        return@addSnapshotListener
                    }
                    if (snap != null) {
                        val latest = snap.documents.map(::decodeDocument).sortedBy { it.order }
                        updateCache(owner) { latest }
                        if (!snap.metadata.isFromCache && !snap.metadata.hasPendingWrites()) {
                            try {
                                OfflineLogQueue.discardMissing(owner, latest.map(Habit::id).toSet())
                            } catch (e: Exception) {
                                error?.invoke(e.localizedMessage ?: "Could not update local log history")
                            }
                        }
                        notifyChanged()
                    }
                }
        }
    }

    fun fetchHabits(uid: String) = FirebaseFirestore.getInstance().collection("habits")
        .whereEqualTo("userId", uid).get(Source.SERVER)

    private fun decodeSummary(value: Any?): SleepSummary? {
        val fields = value as? Map<*, *> ?: return null
        return SleepSummary.parse(fields["bedtime"], fields["wakeTime"], fields["edited"], fields["healthConnect"])
    }

    fun decodeDocument(document: DocumentSnapshot): Habit {
        val dailyCounts = document.get("dailyCounts") as? Map<*, *>
        val goal = document.get("goal") as? Map<*, *>
        val logs = document.get("logOps") as? Map<*, *>
        val summaries = document.get("sleepSummaries") as? Map<*, *>
        return Habit(
            id = document.id,
            name = document.getString("name") ?: "Activity",
            completions = (document.get("completions") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            counts = dailyCounts?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is Number) key to value.toLong() else null
            }?.toMap().orEmpty(),
            order = document.getLong("order") ?: 0,
            color = (document.getLong("color") ?: 0).toInt(),
            appliedOps = logs?.keys?.filterIsInstance<String>()?.toSet().orEmpty(),
            goal = Goal.parse(goal?.get("period") as? String, goal?.get("target") as? Number, goal?.get("since") as? String),
            sleepSummaries = summaries?.entries?.mapNotNull { (day, value) ->
                if (day is String) decodeSummary(value)?.let { day to it } else null
            }?.toMap().orEmpty(),
            skippedAutomaticOps = logs?.entries?.mapNotNull { (id, value) ->
                if (id is String && value == false) id else null
            }?.toSet().orEmpty(),
        )
    }

    fun add(name: String, goal: Goal? = null, done: (String?) -> Unit) {
        addActivity(name, goal) { _, problem -> done(problem) }
    }

    fun addActivity(name: String, goal: Goal? = null, done: (id: String?, problem: String?) -> Unit) {
        val owner = trackingOwner()
        val order = System.currentTimeMillis()
        if (owner == DEMO) {
            val id = UUID.randomUUID().toString()
            localChange({ problem -> done(id.takeIf { problem == null }, problem) }) {
                check(updateCache(owner, durable = true) { it + Habit(id, name, order = order, goal = goal) }) {
                    "Account changed"
                }
            }
            return
        }
        if (owner == null) {
            done(null, "Sign in first")
            return
        }
        FirebaseFirestore.getInstance().collection("habits")
            .add(
                mapOf(
                    "userId" to owner,
                    "name" to name,
                    "goal" to goal?.fields(),
                    "completions" to emptyList<String>(),
                    "dailyCounts" to emptyMap<String, Long>(),
                    "order" to order,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "currentStreak" to 0,
                    "longestStreak" to 0,
                )
            )
            .addOnSuccessListener { ref ->
                if (trackingOwner() != owner) {
                    done(null, "Account changed")
                } else {
                    updateCache(owner) { habits ->
                        if (habits.any { it.id == ref.id }) habits
                        else (habits + Habit(ref.id, name, order = order, goal = goal)).sortedBy { it.order }
                    }
                    notifyChanged()
                    done(ref.id, null)
                }
            }
            .addOnFailureListener { done(null, it.localizedMessage ?: "Could not add the activity") }
    }

    private fun ownedTransaction(
        owner: String,
        id: String,
        update: (Transaction, DocumentReference, DocumentSnapshot) -> Unit,
    ): Task<Boolean> {
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection("habits").document(id)
        return db.runTransaction { tx ->
            check(trackingOwner() == owner) { "Account changed" }
            val document = tx.get(ref)
            check(trackingOwner() == owner) { "Account changed" }
            if (!document.exists()) return@runTransaction false
            check(document.getString("userId") == owner) { "Activity belongs to another account" }
            update(tx, ref, document)
            true
        }
    }

    private fun localChange(done: (String?) -> Unit, change: () -> Unit) {
        val problem = try {
            change()
            notifyChanged()
            null
        } catch (e: Exception) {
            e.localizedMessage ?: "Could not save on this device"
        }
        done(problem)
    }

    private fun editDemo(id: String, done: (String?) -> Unit, change: (Habit) -> Habit) {
        localChange(done) {
            check(updateCache(DEMO, durable = true) { habits ->
                check(habits.any { it.id == id }) { "Activity no longer exists" }
                habits.map { if (it.id == id) change(it) else it }
            }) { "Account changed" }
        }
    }

    fun edit(id: String, name: String, goal: Goal?, done: (String?) -> Unit) {
        when (val owner = trackingOwner()) {
            null -> done("Sign in first")
            DEMO -> editDemo(id, done) { it.copy(name = name, goal = goal) }
            else -> ownedTransaction(owner, id) { tx, ref, _ ->
                tx.update(ref, mapOf("name" to name, "goal" to goal?.fields()))
            }.addOnSuccessListener { exists ->
                done(activityProblem(owner, exists))
            }.addOnFailureListener {
                done(it.localizedMessage ?: "Could not edit the activity")
            }
        }
    }

    fun delete(id: String, done: (String?) -> Unit) {
        when (val owner = trackingOwner()) {
            null -> done("Sign in first")
            DEMO -> localChange(done) {
                check(updateCache(owner, durable = true) { habits ->
                    demoUndo.removeAll { it.habit == id }
                    habits.filterNot { it.id == id }
                }) { "Account changed" }
            }
            else -> ownedTransaction(owner, id) { tx, ref, _ ->
                tx.delete(ref)
            }.addOnSuccessListener {
                localChange(done) {
                    forgetActivity(owner, id)
                    OfflineLogQueue.discardActivity(owner, id)
                    check(trackingOwner() == owner) { "Account changed" }
                }
            }.addOnFailureListener {
                done(it.localizedMessage ?: "Could not delete the activity")
            }
        }
    }

    fun saveSleepSummary(
        id: String,
        day: String,
        summary: SleepSummary,
        expectedOwner: String,
        done: (String?) -> Unit,
    ) {
        if (trackingOwner() != expectedOwner) {
            done("Sign in again before saving sleep")
            return
        }
        if (!isDay(day) || summary.wakeTime <= summary.bedtime) {
            done("Invalid sleep interval")
            return
        }
        if (expectedOwner == DEMO) {
            editDemo(id, done) { habit ->
                if (habit.sleepSummaries[day].canBeReplacedBy(summary)) {
                    habit.copy(sleepSummaries = habit.sleepSummaries + (day to summary))
                } else habit
            }
            return
        }
        val field = FieldPath.of("sleepSummaries", day)
        ownedTransaction(expectedOwner, id) { tx, ref, doc ->
            if (decodeSummary(doc.get(field)).canBeReplacedBy(summary)) {
                tx.update(ref, field, summary.fields())
            }
        }.addOnSuccessListener { exists ->
            done(activityProblem(expectedOwner, exists))
        }.addOnFailureListener {
            done(it.localizedMessage ?: "Could not save sleep")
        }
    }

    private fun activityProblem(owner: String, exists: Boolean): String? = when {
        trackingOwner() != owner -> "Account changed"
        !exists -> "Activity no longer exists"
        else -> null
    }

    private fun isDay(day: String): Boolean =
        day.length == 10 && runCatching { LocalDate.parse(day) }.isSuccess

    fun setGoal(id: String, goal: Goal?, done: (String?) -> Unit) {
        when (trackingOwner()) {
            null -> done("Sign in first")
            DEMO -> editDemo(id, done) { it.copy(goal = goal) }
            else -> FirebaseFirestore.getInstance().collection("habits").document(id)
                .update("goal", goal?.fields())
                .addOnSuccessListener { done(null) }
                .addOnFailureListener { done(it.localizedMessage ?: "Could not save the goal") }
        }
    }

    fun log(
        id: String,
        delta: Int,
        day: String = Dates.today(),
        expectedOwner: String? = null,
        done: (String?) -> Unit,
    ) {
        val owner = trackingOwner()
        if (owner == null || (expectedOwner != null && expectedOwner != owner)) {
            done("Sign in again before logging")
            return
        }
        localChange(done) {
            if (owner == DEMO) {
                changeDemoLog(id) { h ->
                    if (delta > 0) {
                        demoUndo.add(LogOp(UUID.randomUUID().toString(), owner, id, day, delta))
                    } else if (delta < 0 && demoUndo.lastOrNull()?.let { it.habit == id && it.day == day } == true) {
                        demoUndo.removeAt(demoUndo.lastIndex)
                    }
                    OfflineLogQueue.delta(h, day, delta)
                }
            } else OfflineLogQueue.enqueue(owner, id, day, delta)
        }
    }

    fun logAutomatic(id: String, day: String, expectedOwner: String, done: (String?) -> Unit) {
        if (trackingOwner() != expectedOwner) {
            done("Sign in again before logging")
            return
        }
        if (!isDay(day)) {
            done("Invalid log date")
            return
        }
        localChange(done) {
            if (expectedOwner == DEMO) {
                changeDemoLog(id) { h ->
                    val op = OfflineLogQueue.automaticOp(expectedOwner, h, day)
                    if (op.id !in h.appliedOps && op.delta > 0) demoUndo.add(op)
                    OfflineLogQueue.applyToHabit(h, op)
                }
            } else OfflineLogQueue.enqueueAutomatic(expectedOwner, id, day)
        }
    }

    private fun changeDemoLog(id: String, change: (Habit) -> Habit) {
        check(updateCache(DEMO, durable = true) { habits ->
            check(habits.any { it.id == id }) { "Activity no longer exists" }
            habits.map { if (it.id == id) change(it) else it }
        }) { "Account changed" }
    }

    fun lastLog(): LogOp? = when (val owner = trackingOwner()) {
        null -> null
        DEMO -> synchronized(cacheLock) { demoUndo.lastOrNull() }
        else -> OfflineLogQueue.lastLog(owner)
    }

    fun undoLast(expectedId: String, done: (String?) -> Unit) {
        val owner = trackingOwner()
        if (owner == null) {
            done("Sign in first")
            return
        }
        localChange(done) {
            if (owner == DEMO) {
                check(updateCache(owner, durable = true) { habits ->
                    val op = OfflineLogQueue.reversal(demoUndo, expectedId)
                    check(habits.any { it.id == op.habit && it.count(op.day) > 0 }) {
                        "That completion is no longer available"
                    }
                    demoUndo.removeAt(demoUndo.lastIndex)
                    habits.map { if (it.id == op.habit) OfflineLogQueue.applyToHabit(it, op) else it }
                }) { "Account changed" }
            } else OfflineLogQueue.undo(owner, expectedId)
        }
    }

    fun reorder(ids: List<String>, done: (String?) -> Unit) {
        if (demo) {
            localChange(done) {
                check(updateCache(DEMO) { habits ->
                    ids.mapIndexedNotNull { i, id -> habits.find { it.id == id }?.copy(order = i.toLong()) }
                }) { "Account changed" }
            }
            return
        }
        if (!signedIn) {
            done("Sign in first")
            return
        }
        val db = FirebaseFirestore.getInstance()
        val batch = db.batch()
        ids.forEachIndexed { i, id ->
            batch.update(db.collection("habits").document(id), "order", i.toLong())
        }
        batch
            .commit()
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.localizedMessage) }
    }

    fun color(id: String, value: Int) {
        if (demo) {
            updateCache(DEMO) { habits ->
                habits.map { if (it.id == id) it.copy(color = value) else it }
            }
            notifyChanged()
        } else if (signedIn)
            FirebaseFirestore.getInstance()
                .collection("habits")
                .document(id)
                .update("color", value)
                .addOnFailureListener {
                    error?.invoke(it.localizedMessage ?: "Could not save colour")
                }
    }
}
