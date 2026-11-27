package dev.logit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatmapLayoutTest {
    @Test fun fittedHistoryGrowsWithTheAvailableWidth() {
        assertEquals(16, HeatmapLayout.fit(280, 1f, 52).weeks)
        assertEquals(19, HeatmapLayout.fit(330, 1f, 52).weeks)
        assertEquals(25, HeatmapLayout.fit(430, 1f, 52).weeks)
    }

    @Test fun unusedSpaceIsSplitEquallyForFittedAndShorterRanges() {
        val width = 964
        val density = 2.625f
        for (requested in listOf(4, 52)) {
            val layout = HeatmapLayout.fit(width, density, requested)
            val grid = (layout.weeks * 17 - 3) * density
            val rightInset = width - layout.leftInset - grid
            assertEquals(layout.leftInset, rightInset, 0.01f)
        }
        assertEquals(4, HeatmapLayout.fit(width, density, 4).weeks)
    }
}
