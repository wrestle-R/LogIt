package dev.logit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour

    @Test
    fun displaysARecentlyConfirmedUpdateWhileWaitingForAnotherCheck() {
        assertEquals("0.3.8", cachedReleaseVersion("v0.3.8", "0.3.7", "0.3.7", now - 7 * hour, now))
    }

    @Test
    fun hidesAnExpiredOrUnconfirmedUpdate() {
        assertNull(cachedReleaseVersion("v0.3.8", "0.3.7", "0.3.7", now - 24 * hour, now))
        assertNull(cachedReleaseVersion("v0.3.8", "0.3.7", "0.3.7", 0, now))
        assertNull(cachedReleaseVersion("v0.3.8", "0.3.7", "0.3.7", now + hour, now))
    }

    @Test
    fun hidesAnUpdateWhenTheInstalledVersionChanges() {
        assertNull(cachedReleaseVersion("v0.3.8", "0.3.8", "0.3.7", now, now))
        assertNull(cachedReleaseVersion("v0.3.9", "0.3.8", "0.3.7", now, now))
    }
}
