package dev.logit.app

internal data class HeatmapLayout(val weeks: Int, val leftInset: Float) {
    companion object {
        const val cellDp = 14f
        const val gapDp = 3f

        fun fit(width: Int, density: Float, maxWeeks: Int): HeatmapLayout {
            val cell = cellDp * density
            val gap = gapDp * density
            val stride = cell + gap
            val weeks = ((width + gap) / stride).toInt().coerceIn(1, maxWeeks)
            return HeatmapLayout(weeks, (width - (weeks * stride - gap)) / 2f)
        }
    }
}
