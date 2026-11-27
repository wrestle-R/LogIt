package dev.logit.app

/** Sizes in launcher dp, including every row and the optional overflow label. */
internal data class WidgetLayout(
    val columns: Int,
    val visible: Int,
    val rowHeight: Int,
    val showOverflow: Boolean,
    val showGraph: Boolean,
) {
    companion object {
        const val graphHeight = 116
        const val overflowHeight = 24
        const val fullGraphRow = 152

        fun edgePadding(height: Int, quick: Boolean) = if (quick || height < 176) 8 else 12

        fun graphRowHeight(fontScale: Float) = fullGraphRow + kotlin.math.ceil(22 * (fontScale - 1).coerceAtLeast(0f)).toInt()

        fun fit(width: Int, height: Int, count: Int, quick: Boolean, fontScale: Float = 1f): WidgetLayout {
            val available = (height - 2 * edgePadding(height, quick)).coerceAtLeast(1)
            val minimum = if (quick) 60 else graphRowHeight(fontScale)

            fun candidate(columns: Int): WidgetLayout {
                val completeRows = available / minimum
                val visible = minOf(count, maxOf(1, completeRows) * columns)
                val rows = ((visible + columns - 1) / columns).coerceAtLeast(1)
                val minHeight = if (completeRows > 0) minimum
                    else if (!quick && available >= graphHeight) graphHeight
                    else minOf(44, available)
                val overflow = visible < count && available - rows * minHeight >= overflowHeight
                val body = available - if (overflow) overflowHeight else 0
                val rowHeight = (body / rows).coerceIn(1, if (quick) 120 else maxOf(174, minimum))
                return WidgetLayout(columns, visible, rowHeight, overflow, !quick && rowHeight >= graphHeight)
            }

            val stack = candidate(1)
            if (count <= 1 || width < (if (quick) 260 else 320) || available < minimum) return stack
            val grid = candidate(2)
            return if (grid.visible > stack.visible) grid else stack
        }
    }
}
