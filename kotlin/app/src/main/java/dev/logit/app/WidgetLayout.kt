package dev.logit.app

/** Geometry in launcher dp. Heatmap cells keep their fixed sizes. */
internal data class WidgetLayout(val columns: Int, val visible: Int, val rowHeight: Int) {
    companion object {
        fun fit(width: Int, height: Int, count: Int, quick: Boolean): WidgetLayout {
            val minimum = if (quick) 60 else 140
            val available = (height - 24).coerceAtLeast(44)
            fun candidate(columns: Int): WidgetLayout {
                val fit = (available / minimum).coerceAtLeast(1) * columns
                val overflow = if (fit < count && available >= 84) 24 else 0
                val body = available - overflow
                // Keep the last fully fitting row if adding a hint would hide it.
                val usable = if (body / minimum < available / minimum) available else body
                val rows = (usable / minimum).coerceAtLeast(1)
                val visible = minOf(count, rows * columns)
                val usedRows = ((visible + columns - 1) / columns).coerceAtLeast(1)
                val rowHeight = (usable / usedRows).coerceIn(44, if (quick) 120 else 174)
                return WidgetLayout(columns, visible, rowHeight)
            }
            val stack = candidate(1)
            if (count <= 1 || width < if (quick) 260 else 320) return stack
            val grid = candidate(2)
            return if (grid.visible > stack.visible) grid else stack
        }
    }
}
