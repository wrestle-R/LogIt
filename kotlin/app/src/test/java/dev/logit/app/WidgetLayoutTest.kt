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
    @Test fun overflowHintDoesNotCrowdGraphRows() {
        val layout = WidgetLayout.fit(180, 340, 4, false)
        assertEquals(1, layout.visible)
        assertTrue(layout.rowHeight >= 156)
    }
    @Test fun quickTilesUseExpandedSpace() {
        val layout = WidgetLayout.fit(340, 600, 4, true)
        assertEquals(4, layout.visible)
        assertTrue(layout.rowHeight > 200)
    }
}
