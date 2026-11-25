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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if (log)
            return PendingIntent.getBroadcast(
                c,
                0,
                Intent(c, WidgetLogReceiver::class.java)
                    .setAction("widget-log:$id:$habit")
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("habit", habit),
                flags,
            )
        return PendingIntent.getActivity(
            c,
            0,
            Intent(c, MainActivity::class.java)
                .setAction("widget-open:$id:$habit")
                .putExtra("habit", habit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
    }

    fun update(c: Context, m: AppWidgetManager, id: Int) {
        if (m.getAppWidgetInfo(id) == null) return
        val options = m.getAppWidgetOptions(id)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val sizes =
                options
                    .getParcelableArrayList<android.util.SizeF>(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES
                    )
                    ?.distinct()
                    ?.filter { it.width > 0 && it.height > 0 }
                    ?.take(4)
            if (!sizes.isNullOrEmpty()) {
                m.updateAppWidget(
                    id,
                    RemoteViews(
                        sizes.associateWith { size ->
                            create(c, m, id, size.width.toInt(), size.height.toInt())
                        }
                    ),
                )
                return
            }
        }
        m.updateAppWidget(
            id,
            create(
                c,
                m,
                id,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180),
            ),
        )
    }

    private fun create(
        c: Context,
        m: AppWidgetManager,
        id: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): RemoteViews {
        val info = m.getAppWidgetInfo(id)!!
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
        val compactGraph = !quick && requestedHeight < 176
        if (quick || compactGraph) {
            val pad = (8 * c.resources.displayMetrics.density).toInt()
            rv.setViewPadding(R.id.root, pad, pad, pad, pad)
        }
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
            val width = requestedWidth.coerceAtLeast(100)
            val height = requestedHeight.coerceAtLeast(60)
            val weeks = 52
            val layout = WidgetLayout.fit(width, height, hs.size, quick)
            val multiGrid = layout.columns == 2
            val graphWidth = if (multiGrid) (width - 40) / 2 else width - 32
            val cellHeight = layout.rowHeight
            val capacity = layout.visible
            val usedRows = (capacity + layout.columns - 1) / layout.columns
            val showOverflow = capacity < hs.size && height - 24 - usedRows * cellHeight >= 24
            val shown = hs.take(capacity)
            rv.setContentDescription(R.id.root, "${shown.size} of ${hs.size} activities")
            if (showOverflow) {
                rv.setViewVisibility(R.id.overflow_hint, View.VISIBLE)
                rv.setTextViewText(R.id.overflow_hint, "+${hs.size - shown.size} more")
                rv.setTextColor(R.id.overflow_hint, muted)
                rv.setOnClickPendingIntent(R.id.overflow_hint, click(c, id, null, false))
            }
            var band: RemoteViews? = null
            shown.forEachIndexed { index, h ->
                if (multiGrid && index % 2 == 0) {
                    band = RemoteViews(c.packageName, R.layout.widget_band)
                }
                val row =
                    RemoteViews(
                        c.packageName,
                        if (quick && cellHeight >= 112)
                            R.layout.widget_quick_tall
                        else if (quick) R.layout.widget_quick_compact
                        else if (hs.size > 1) R.layout.widget_list_row
                        else R.layout.widget_row,
                    )
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    row.setViewLayoutHeight(R.id.row, cellHeight.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                if (quick && hs.size == 1 && android.os.Build.VERSION.SDK_INT >= 31)
                    row.setViewLayoutHeight(
                        R.id.row,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(),
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                    )
                if (quick) {
                    row.setViewVisibility(
                        R.id.quick_hint,
                        if (height < 100) View.GONE else View.VISIBLE,
                    )
                    row.setInt(R.id.quick_log, "setColorFilter", fg)
                    row.setTextColor(R.id.quick_hint, muted)
                    row.setInt(
                        R.id.row,
                        "setBackgroundResource",
                        if (hs.size == 1) android.R.color.transparent
                        else if (light) R.drawable.widget_tile_light else R.drawable.widget_tile,
                    )
                }
                row.setTextViewText(R.id.title, h.name)
                row.setTextViewText(R.id.count, "${h.count(Dates.today())} today")
                row.setTextColor(R.id.title, fg)
                row.setTextColor(R.id.count, muted)
                row.setOnClickPendingIntent(R.id.row, click(c, id, h.id, quick))
                row.setViewVisibility(R.id.quick_log, if (quick) View.VISIBLE else View.GONE)
                row.setViewVisibility(R.id.plot, if (quick) View.GONE else View.VISIBLE)
                row.setViewVisibility(R.id.overlay_log, if (log) View.VISIBLE else View.GONE)
                row.setContentDescription(
                    R.id.row,
                    if (quick) "Log ${h.name} for today" else "Open ${h.name} in LogIt",
                )
                row.setContentDescription(R.id.quick_log, "Open ${h.name} in LogIt")
                row.setContentDescription(R.id.overlay_log, "Log ${h.name} for today")
                row.setOnClickPendingIntent(R.id.quick_log, click(c, id, h.id, false))
                row.setOnClickPendingIntent(R.id.overlay_log, click(c, id, h.id, true))
                if (!quick && android.os.Build.VERSION.SDK_INT >= 31) {
                    val compact = compactGraph || cellHeight < 140
                    if (compact) row.setViewPadding(R.id.row, 0, 0, 0, 0)
                    row.setViewVisibility(
                        R.id.widget_heading,
                        if (compact) View.GONE else View.VISIBLE,
                    )
                    row.setViewLayoutMargin(
                        R.id.plot,
                        RemoteViews.MARGIN_TOP,
                        if (compact) 0f else 8f,
                        android.util.TypedValue.COMPLEX_UNIT_DIP,
                    )
                }
                if (!quick) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        if (multiGrid && log) {
                            val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
                            row.setViewLayoutWidth(R.id.overlay_log, 82f, dip)
                            row.setViewLayoutHeight(R.id.overlay_log, 44f, dip)
                            row.setTextViewTextSize(R.id.overlay_log, android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                        }
                        val cols = minOf(weeks, maxOf(1, (graphWidth + 3) / 17))
                        val inset = ((graphWidth - (cols * 17 - 3)) / 2f).coerceAtLeast(0f)
                        val px = (minOf(inset, 8f) * c.resources.displayMetrics.density).toInt()
                        row.setViewPadding(R.id.widget_heading, px, 0, px, 0)
                        row.setViewVisibility(
                            R.id.widget_footer,
                            if (hs.size == 1 && height >= 196) View.VISIBLE else View.GONE,
                        )
                        row.setTextViewText(R.id.widget_footer, "Last $cols weeks")
                        row.setTextColor(R.id.widget_footer, muted)
                        row.setViewPadding(R.id.widget_footer, px, 0, px, 0)
                        row.setViewLayoutMargin(
                            R.id.overlay_log,
                            RemoteViews.MARGIN_START,
                            inset,
                            android.util.TypedValue.COMPLEX_UNIT_DIP,
                        )
                        row.setViewLayoutMargin(
                            R.id.overlay_log,
                            RemoteViews.MARGIN_TOP,
                            0f,
                            android.util.TypedValue.COMPLEX_UNIT_DIP,
                        )
                    }
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
                            center = true,
                        )
                    row.setImageViewBitmap(R.id.graph, graph)
                    row.setContentDescription(
                        R.id.graph,
                        "${h.name}: recent history, ${h.total()} total logs. Open activity.",
                    )
                    row.setOnClickPendingIntent(R.id.graph, click(c, id, h.id, false))
                }
                if (multiGrid) {
                    // Static cells keep every action independent without a scrolling collection.
                    band!!.addView(if (index % 2 == 0) R.id.left_cell else R.id.right_cell, row)
                    if (index % 2 == 1 || index == shown.lastIndex) {
                        rv.addView(R.id.rows, band)
                    }
                } else rv.addView(R.id.rows, row)
            }
        }
        return rv
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
