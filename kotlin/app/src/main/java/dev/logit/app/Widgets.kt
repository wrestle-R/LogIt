package dev.logit.app

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

open class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { Widgets.update(c, m, it) }
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) {
        Widgets.update(c, m, id)
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        val e = Store.prefs.edit()
        ids.forEach { e.remove("widget_$it") }
        e.apply()
    }
}

class QuickWidget : BaseWidget()

class GraphWidget : BaseWidget()

class LogGraphWidget : BaseWidget()

object Widgets {
    fun refresh(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        listOf(QuickWidget::class.java, GraphWidget::class.java, LogGraphWidget::class.java)
            .forEach { cls ->
                m.getAppWidgetIds(ComponentName(c, cls)).forEach { update(c, m, it) }
            }
    }

    private fun click(c: Context, id: Int, habit: String?, log: Boolean): PendingIntent {
        val i =
            Intent(c, if (log) LogActivity::class.java else MainActivity::class.java)
                .setAction("widget:$id:$habit:$log")
                .putExtra("habit", habit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            c,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun update(c: Context, m: AppWidgetManager, id: Int) {
        val info = m.getAppWidgetInfo(id) ?: return
        val kind = info.provider.shortClassName
        val quick = kind.endsWith("QuickWidget")
        val log = kind.endsWith("LogGraphWidget")
        val config = org.json.JSONObject(Store.prefs.getString("widget_$id", "{}") ?: "{}")
        val owner =
            if (Store.demo) "demo" else if (Store.signedIn) Store.auth.currentUser!!.uid else ""
        val accountMatches = config.optString("owner") == owner && owner.isNotBlank()
        val ids = config.optJSONArray("ids")
        val selected =
            if (ids == null) emptyList() else (0 until ids.length()).map { ids.getString(it) }
        val modePage = config.optInt("pageIndex", -1)
        val selectedIds =
            if (modePage >= 0) Store.habits.drop(modePage * 4).take(4).map { it.id } else selected
        val hs =
            if (Store.ready && accountMatches)
                selectedIds.mapNotNull { wanted -> Store.habits.find { it.id == wanted } }.take(4)
            else emptyList()
        val rv = RemoteViews(c.packageName, R.layout.widget)
        rv.removeAllViews(R.id.rows)
        rv.setOnClickPendingIntent(R.id.root, click(c, id, null, false))
        val light = config.optString("theme", "dark") == "light"
        val fg = if (light) 0xff18181b.toInt() else 0xfffafafa.toInt()
        val muted = if (light) 0xff52525b.toInt() else 0xffa1a1aa.toInt()
        rv.setInt(
            R.id.root,
            "setBackgroundResource",
            if (light) R.drawable.widget_bg_light else R.drawable.widget_bg,
        )
        if (hs.isEmpty()) {
            val row = RemoteViews(c.packageName, R.layout.widget_row)
            row.setTextViewText(
                R.id.title,
                if (Store.ready) "Configure LogIt widget" else "Open LogIt to sign in",
            )
            row.setTextColor(R.id.title, fg)
            row.setViewVisibility(R.id.plot, View.GONE)
            row.setViewVisibility(R.id.quick_log, View.GONE)
            rv.addView(R.id.rows, row)
        } else {
            val opts = m.getAppWidgetOptions(id)
            val width =
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(160)
            val height =
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200).coerceAtLeast(80)
            val weeks = config.optInt("weeks", 16).coerceIn(4, 52)
            val collection =
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    RemoteViews.RemoteCollectionItems.Builder()
                        .setHasStableIds(true)
                        .setViewTypeCount(1)
                else null
            val multiGrid = !quick && hs.size > 1
            val rowHeight = if (quick) 60 else 148
            val graphWidth = if (multiGrid) (width - 40) / 2 else width - 32
            hs.forEachIndexed { index, h ->
                val row =
                    RemoteViews(
                        c.packageName,
                        if (quick && hs.size == 1 && height >= 132) R.layout.widget_quick_tall
                        else if (quick) R.layout.widget_quick_compact
                        else if (collection != null) R.layout.widget_list_row
                        else R.layout.widget_row,
                    )
                if (quick && hs.size == 1 && android.os.Build.VERSION.SDK_INT >= 31)
                    row.setViewLayoutHeight(
                        R.id.row,
                        (height - 24).coerceAtLeast(56).toFloat(),
                        android.util.TypedValue.COMPLEX_UNIT_DIP,
                    )
                row.setTextViewText(R.id.title, h.name)
                row.setTextViewText(R.id.count, "${h.count(Dates.today())} today")
                row.setTextColor(R.id.title, fg)
                row.setTextColor(R.id.count, muted)
                row.setOnClickPendingIntent(R.id.row, click(c, id, h.id, false))
                row.setViewVisibility(R.id.quick_log, if (quick) View.VISIBLE else View.GONE)
                row.setViewVisibility(R.id.plot, if (quick) View.GONE else View.VISIBLE)
                row.setViewVisibility(R.id.overlay_log, if (log) View.VISIBLE else View.GONE)
                row.setOnClickPendingIntent(R.id.quick_log, click(c, id, h.id, true))
                row.setOnClickPendingIntent(R.id.overlay_log, click(c, id, h.id, true))
                if (!quick) {
                    val graph =
                        Theme.graph(
                            h,
                            weeks,
                            (graphWidth * c.resources.displayMetrics.density)
                                .toInt()
                                .coerceAtMost(1600),
                            (116 * c.resources.displayMetrics.density).toInt(),
                            config.optInt("color", h.color),
                            light,
                            config.optBoolean("gradient", false),
                        )
                    row.setImageViewBitmap(R.id.graph, graph)
                    row.setContentDescription(
                        R.id.graph,
                        "${h.name}: $weeks weeks, ${h.total()} total logs. Open activity.",
                    )
                    row.setOnClickPendingIntent(R.id.graph, click(c, id, h.id, false))
                }
                if (collection != null && android.os.Build.VERSION.SDK_INT >= 31) {
                    row.setViewLayoutHeight(
                        R.id.plot,
                        (rowHeight - 32).toFloat(),
                        android.util.TypedValue.COMPLEX_UNIT_DIP,
                    )
                    row.setOnClickFillInIntent(
                        R.id.row,
                        Intent().putExtra("habit", h.id).putExtra("log", false),
                    )
                    row.setOnClickFillInIntent(
                        R.id.graph,
                        Intent().putExtra("habit", h.id).putExtra("log", false),
                    )
                    row.setOnClickFillInIntent(
                        R.id.quick_log,
                        Intent().putExtra("habit", h.id).putExtra("log", true),
                    )
                    row.setOnClickFillInIntent(
                        R.id.overlay_log,
                        Intent().putExtra("habit", h.id).putExtra("log", true),
                    )
                    collection.addItem(index.toLong(), row)
                } else rv.addView(R.id.rows, row)
            }
            if (collection != null && android.os.Build.VERSION.SDK_INT >= 31) {
                rv.setViewVisibility(R.id.rows, View.GONE)
                val collectionId = if (multiGrid) R.id.widget_grid else R.id.widget_list
                rv.setViewVisibility(collectionId, View.VISIBLE)
                rv.setRemoteAdapter(collectionId, collection.build())
                val template =
                    PendingIntent.getActivity(
                        c,
                        id,
                        Intent(c, MainActivity::class.java)
                            .setAction("widget-list:$id")
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                rv.setPendingIntentTemplate(collectionId, template)
            }
        }
        m.updateAppWidget(id, rv)
    }
}

class PinReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id =
            i.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val config = i.getStringExtra("config") ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        Store.prefs.edit().putString("widget_$id", config).apply()
        Widgets.update(c, AppWidgetManager.getInstance(c), id)
    }
}
