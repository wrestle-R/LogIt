package dev.logit.app

/** Geometry in launcher dp. Heatmap cells retain their own fixed geometry. */
internal data class WidgetLayout(val columns: Int, val visible: Int, val rowHeight: Int) {
    companion object {
        fun fit(width: Int, height: Int, count: Int, quick: Boolean): WidgetLayout {
            val columns = if (count > 1 && width >= if (quick) 260 else 320) 2 else 1
            val minimum = if (quick) 60 else 156
            val available = (height - 24).coerceAtLeast(44)
            val overflow = if ((available / minimum).coerceAtLeast(1) * columns < count && available >= 84) 24 else 0
            val rows = ((available - overflow) / minimum).coerceAtLeast(1)
            val visible = minOf(count, rows * columns)
            val usedRows = ((visible + columns - 1) / columns).coerceAtLeast(1)
            return WidgetLayout(columns, visible, ((available - overflow) / usedRows).coerceAtLeast(44))
        }
    }
}
