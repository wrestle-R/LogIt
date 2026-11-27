package dev.logit.app

import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutTest {
    @Test fun tallWidgetShowsAllSelectedActivities() {
        val layout = WidgetLayout.fit(340, 650, 4, false)
        assertEquals(4, layout.visible)
        assertTrue(layout.rowHeight >= 152)
    }
    @Test fun narrowWidgetStacksActivities() {
        val layout = WidgetLayout.fit(180, 650, 4, false)
        assertEquals(1, layout.columns)
        assertEquals(4, layout.visible)
    }
    @Test fun shortWidgetLimitsContentWithoutShrinkingHeatmaps() {
        assertEquals(1, WidgetLayout.fit(180, 160, 4, false).visible)
    }
    @Test fun minimumHeatmapHeightKeepsTheSevenRowGrid() {
        val layout = WidgetLayout.fit(180, 132, 4, false)
        assertEquals(1, layout.visible)
        assertTrue(layout.showGraph)
        assertFalse(layout.showOverflow)
    }
    @Test fun overflowHintDoesNotCrowdGraphRows() {
        val layout = WidgetLayout.fit(180, 340, 4, false)
        assertEquals(2, layout.visible)
        assertTrue(layout.rowHeight >= 140)
    }
    @Test fun quickTilesUseExpandedSpace() {
        val layout = WidgetLayout.fit(340, 600, 4, true)
        assertEquals(4, layout.visible)
        assertTrue(layout.rowHeight in 60..120)
        assertEquals(1, layout.columns)
    }
}

class WidgetOverflowRegressionTest {
    @Test fun overflowCountsOnlyRowsThatFitFullHeatmaps() {
        val layout = WidgetLayout.fit(300, 600, 4, false)
        assertEquals(3, layout.visible)
        assertTrue(layout.rowHeight >= 152)
        assertTrue(layout.showOverflow)
    }
}
