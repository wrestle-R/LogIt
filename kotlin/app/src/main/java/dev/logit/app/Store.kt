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
) {
    fun count(day: String) = maxOf(counts[day] ?: 0, if (completions.contains(day)) 1L else 0L)

    fun total() = (completions.toSet() + counts.keys).sumOf { count(it) }
}

object Dates {
    fun today(): String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
}

class LogItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

object Store {
    lateinit var context: Context
    var habits = listOf<Habit>()
        private set

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
        if (configured) auth.addAuthStateListener { listen() }
    }

    private fun key() =
        if (demo) "demo_habits"
        else "cache_" + (if (signedIn) auth.currentUser!!.uid else "signed_out")

    private fun loadCache() {
        habits = decode(prefs.getString(key(), "[]") ?: "[]")
    }

    fun save() {
        prefs
            .edit()
            .putString(
                key(),
                JSONArray(
                        habits.map { h ->
                            JSONObject()
                                .put("id", h.id)
                                .put("name", h.name)
                                .put("completions", JSONArray(h.completions))
                                .put("dailyCounts", JSONObject(h.counts))
                                .put("order", h.order)
                                .put("color", h.color)
                        }
                    )
                    .toString(),
            )
            .apply()
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
            habits =
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
                            habits =
                                snap.documents
                                    .map { d ->
                                        Habit(
                                            d.id,
                                            d.getString("name") ?: "Activity",
                                            (d.get("completions") as? List<*>)?.filterIsInstance<
                                                String
                                            >() ?: emptyList(),
                                            (d.get("dailyCounts") as? Map<*, *>)
                                                ?.entries
                                                ?.mapNotNull { (k, v) ->
                                                    if (k is String && v is Number) k to v.toLong()
                                                    else null
                                                }
                                                ?.toMap() ?: emptyMap(),
                                            d.getLong("order") ?: 0,
                                            (d.getLong("color") ?: 0).toInt(),
                                        )
                                    }
                                    .sortedBy { it.order }
                            save()
                        }
                    }
        }
    }

    fun add(name: String, done: (String?) -> Unit) {
        if (demo) {
            habits =
                habits +
                    Habit(UUID.randomUUID().toString(), name, order = System.currentTimeMillis())
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

    // Both clients use online transactions. Undo targets the date of the original log, even after
    // midnight.
    fun log(id: String, delta: Int, day: String = Dates.today(), done: (String?) -> Unit) {
        val h = habits.find { it.id == id }
        if (h == null) {
            done("Activity no longer exists")
            return
        }
        if (demo) {
            val n = (h.count(day) + delta).coerceAtLeast(0)
            val dates = if (n > 0) (h.completions + day).distinct() else h.completions - day
            habits = habits.map {
                if (it.id == id) it.copy(completions = dates, counts = it.counts + (day to n))
                else it
            }
            save()
            done(null)
            return
        }
        if (!signedIn) {
            done("Sign in to log")
            return
        }
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection("habits").document(id)
        db.runTransaction { tx ->
                val d = tx.get(ref)
                check(d.exists()) { "Activity no longer exists" }
                check(d.getString("userId") == auth.currentUser!!.uid) {
                    "Activity belongs to another account"
                }
                val dates =
                    (d.get("completions") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val counts = (d.get("dailyCounts") as? Map<*, *>) ?: emptyMap<Any, Any>()
                val count =
                    maxOf((counts[day] as? Number)?.toLong() ?: 0, if (day in dates) 1L else 0L)
                val n = (count + delta).coerceAtLeast(0)
                val nextDates = if (n > 0) (dates + day).distinct() else dates - day
                val active =
                    (nextDates +
                            counts
                                .filter { (k, v) ->
                                    k != day && ((v as? Number)?.toLong() ?: 0) > 0
                                }
                                .keys
                                .filterIsInstance<String>())
                        .toSet()
                var streak = 0
                var cursor = LocalDate.parse(Dates.today())
                while (cursor.toString() in active) {
                    streak++
                    cursor = cursor.minusDays(1)
                }
                tx.update(
                    ref,
                    mapOf(
                        "dailyCounts.$day" to n,
                        "completions" to nextDates,
                        "currentStreak" to streak,
                        "longestStreak" to maxOf(d.getLong("longestStreak") ?: 0, streak.toLong()),
                    ),
                )
                null
            }
            .addOnSuccessListener { done(null) }
            .addOnFailureListener {
                done("Could not save. Check your connection and retry. " + it.localizedMessage)
            }
    }

    fun reorder(ids: List<String>, done: (String?) -> Unit) {
        if (demo) {
            habits = ids.mapIndexedNotNull { i, id ->
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
            habits = habits.map { if (it.id == id) it.copy(color = value) else it }
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
