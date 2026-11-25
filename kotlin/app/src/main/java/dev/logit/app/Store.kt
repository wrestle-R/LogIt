package dev.logit.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Habit(
    val id: String,
    val name: String,
    val completions: List<String> = emptyList(),
    val counts: Map<String, Long> = emptyMap(),
    val order: Long = 0,
    val color: Int = 0,
    val appliedOps: Set<String> = emptySet(),
    val goal: Goal? = null,
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
    lateinit var context: Context
    @Volatile private var baseHabits = listOf<Habit>()
    val habits: List<Habit>
        get() =
            if (demo || !signedIn) baseHabits
            else
                OfflineLogQueue.project(
                    baseHabits,
                    OfflineLogQueue.operations(auth.currentUser!!.uid),
                )

    var changed: (() -> Unit)? = null
    var error: ((String) -> Unit)? = null
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null
    val prefs
        get() = context.getSharedPreferences("logit", Context.MODE_PRIVATE)

    val demo
        get() = prefs.getBoolean("demo", false)

    val configured
        get() = FirebaseApp.getApps(context).isNotEmpty()

    val auth
        get() = FirebaseAuth.getInstance()

    val signedIn
        get() = configured && auth.currentUser != null

    val ready
        get() = demo || signedIn

    fun init(c: Context) {
        context = c.applicationContext
        loadCache()
        if (configured)
            auth.addAuthStateListener {
                listen()
                OfflineLogQueue.schedule()
                GoalReminders.schedule(context)
            }
    }

    private fun key() =
        if (demo) "demo_habits"
        else "cache_" + (if (signedIn) auth.currentUser!!.uid else "signed_out")

    private fun loadCache() {
        baseHabits = decode(prefs.getString(key(), "[]") ?: "[]")
    }

    fun confirm(op: LogOp) {
        if (!configured || auth.currentUser?.uid != op.owner) return
        baseHabits = baseHabits.map { h ->
            if (h.id == op.habit && op.id !in h.appliedOps)
                OfflineLogQueue.delta(h, op.day, op.delta).copy(appliedOps = h.appliedOps + op.id)
            else h
        }
        save(durable = true)
    }

    fun save(durable: Boolean = false) {
        val editor =
            prefs
                .edit()
                .putString(
                    key(),
                    JSONArray(
                            baseHabits.map { h ->
                                JSONObject()
                                    .put("id", h.id)
                                    .put("name", h.name)
                                    .put("completions", JSONArray(h.completions))
                                    .put("dailyCounts", JSONObject(h.counts))
                                    .put("order", h.order)
                                    .put("color", h.color)
                                    .put("appliedOps", JSONArray(h.appliedOps.toList()))
                                    .put("goal", h.goal?.let { JSONObject(it.fields()) })
                            }
                        )
                        .toString(),
                )
        if (durable) check(editor.commit()) { "Local storage unavailable" } else editor.apply()
        Widgets.refresh(context)
        changed?.invoke()
    }

    private fun decode(s: String): List<Habit> =
        try {
            val a = JSONArray(s)
            (0 until a.length())
                .map { i ->
                    val o = a.getJSONObject(i)
                    val dates = o.optJSONArray("completions") ?: JSONArray()
                    val counts = o.optJSONObject("dailyCounts") ?: JSONObject()
                    Habit(
                        o.getString("id"),
                        o.getString("name"),
                        (0 until dates.length()).map { dates.getString(it) },
                        counts.keys().asSequence().associateWith { counts.optLong(it) },
                        o.optLong("order"),
                        o.optInt("color"),
                        (o.optJSONArray("appliedOps") ?: JSONArray()).let { a ->
                            (0 until a.length()).map { a.getString(it) }.toSet()
                        },
                        o.optJSONObject("goal")?.let { Goal.parse(it.optString("period"), it.optInt("target"), it.optString("since")) },
                    )
                }
                .sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }

    fun useDemo() {
        listener?.remove()
        prefs.edit().putBoolean("demo", true).apply()
        loadCache()
        if (habits.isEmpty()) {
            val today = LocalDate.parse(Dates.today())
            baseHabits =
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
        save()
    }

    fun leaveDemo() {
        prefs.edit().putBoolean("demo", false).apply()
        listen()
    }

    fun listen() {
        listener?.remove()
        loadCache()
        save()
        if (!demo && signedIn) {
            listener =
                FirebaseFirestore.getInstance()
                    .collection("habits")
                    .whereEqualTo("userId", auth.currentUser!!.uid)
                    .addSnapshotListener { snap, e ->
                        if (e != null) {
                            error?.invoke("Could not sync activities: " + e.localizedMessage)
                            return@addSnapshotListener
                        }
                        if (snap != null) {
                            baseHabits =
                                snap.documents
                                    .map(::decodeDocument)
                                    .sortedBy { it.order }
                            save()
                        }
                    }
        }
    }

    fun fetchHabits(uid: String) = FirebaseFirestore.getInstance().collection("habits")
        .whereEqualTo("userId", uid).get(com.google.firebase.firestore.Source.SERVER)

    fun decodeDocument(document: com.google.firebase.firestore.DocumentSnapshot): Habit {
        val dailyCounts = document.get("dailyCounts") as? Map<*, *>
        val goal = document.get("goal") as? Map<*, *>
        return Habit(
            id = document.id,
            name = document.getString("name") ?: "Activity",
            completions = (document.get("completions") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            counts = dailyCounts?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is Number) key to value.toLong() else null
            }?.toMap().orEmpty(),
            order = document.getLong("order") ?: 0,
            color = (document.getLong("color") ?: 0).toInt(),
            appliedOps = (document.get("logOps") as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet().orEmpty(),
            goal = Goal.parse(goal?.get("period") as? String, goal?.get("target") as? Number, goal?.get("since") as? String),
        )
    }

    fun add(name: String, goal: Goal? = null, done: (String?) -> Unit) {
        if (demo) {
            baseHabits =
                habits +
                    Habit(UUID.randomUUID().toString(), name, order = System.currentTimeMillis(), goal = goal)
            save()
            done(null)
            return
        }
        if (!signedIn) {
            done("Sign in first")
            return
        }
        FirebaseFirestore.getInstance()
            .collection("habits")
            .add(
                mapOf(
                    "userId" to auth.currentUser!!.uid,
                    "name" to name,
                    "goal" to goal?.fields(),
                    "completions" to emptyList<String>(),
                    "dailyCounts" to emptyMap<String, Long>(),
                    "order" to System.currentTimeMillis(),
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "currentStreak" to 0,
                    "longestStreak" to 0,
                )
            )
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.localizedMessage) }
    }

    fun setGoal(id: String, goal: Goal?, done: (String?) -> Unit) {
        if (demo) {
            baseHabits = baseHabits.map { if (it.id == id) it.copy(goal = goal) else it }
            save()
            done(null)
        } else if (signedIn) {
            FirebaseFirestore.getInstance().collection("habits").document(id)
                .update("goal", goal?.fields())
                .addOnSuccessListener { done(null) }
                .addOnFailureListener { done(it.localizedMessage ?: "Could not save the goal") }
        } else done("Sign in first")
    }

    fun log(
        id: String,
        delta: Int,
        day: String = Dates.today(),
        expectedOwner: String? = null,
        done: (String?) -> Unit,
    ) {
        val h =
            habits.find { it.id == id }
                ?: run {
                    done("Activity no longer exists")
                    return
                }
        if (demo) {
            baseHabits = habits.map {
                if (it.id == id) OfflineLogQueue.delta(it, day, delta) else it
            }
            if (delta == 1) demoUndo.add(LogOp(UUID.randomUUID().toString(), "demo", id, day, delta))
            save()
            done(null)
            return
        }
        val owner = if (configured) auth.currentUser?.uid else null
        if (owner == null || (expectedOwner != null && expectedOwner != owner)) {
            done("Sign in again before logging")
            return
        }
        try {
            OfflineLogQueue.enqueue(owner, id, day, delta)
            save()
            done(null)
        } catch (e: Exception) {
            done("Could not save on this device: " + e.localizedMessage)
        }
    }

    private val demoUndo = mutableListOf<LogOp>()

    fun lastLog(): LogOp? = when {
        demo -> demoUndo.lastOrNull()
        signedIn -> OfflineLogQueue.lastLog(auth.currentUser!!.uid)
        else -> null
    }

    fun undoLast(expectedId: String, done: (String?) -> Unit) {
        if (!demo && !signedIn) {
            done("Sign in first")
            return
        }
        try {
            val last = lastLog()
            check(last != null && last.id == expectedId) { "That log has already been undone" }
            check(habits.any { it.id == last.habit && it.count(last.day) > 0 }) {
                "That completion is no longer available"
            }
            if (demo) {
                baseHabits = baseHabits.map {
                    if (it.id == last.habit) OfflineLogQueue.delta(it, last.day, -1) else it
                }
                demoUndo.removeAt(demoUndo.lastIndex)
            } else OfflineLogQueue.undo(auth.currentUser!!.uid, expectedId)
            save()
            done(null)
        } catch (e: Exception) {
            done(e.localizedMessage ?: "Could not undo this log")
        }
    }

    fun reorder(ids: List<String>, done: (String?) -> Unit) {
        if (demo) {
            baseHabits = ids.mapIndexedNotNull { i, id ->
                habits.find { it.id == id }?.copy(order = i.toLong())
            }
            save()
            done(null)
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
            baseHabits = habits.map { if (it.id == id) it.copy(color = value) else it }
            save()
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
