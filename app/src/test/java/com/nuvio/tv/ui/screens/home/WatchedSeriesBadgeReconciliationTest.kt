package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedSeriesBadgeReconciliationTest {
    @Test fun `remote removal of the last watched episode clears stale completion aliases`() {
        assertEquals(setOf("other"), reconcileWatchedSeriesBadges(
            setOf("fire", "native-fire", "other"), emptySet(), emptySet(), setOf("other"), true))
    }
    @Test fun `partial tracker loading retains completion cache until history is authoritative`() {
        assertEquals(setOf("fire"), reconcileWatchedSeriesBadges(
            setOf("fire"), emptySet(), emptySet(), emptySet(), false))
    }
    @Test fun `one unmarked episode invalidates completion without removing another series`() {
        assertEquals(setOf("other"), reconcileWatchedSeriesBadges(
            setOf("fire", "other"), emptySet(), setOf("fire"), setOf("fire", "other"), true))
    }
}
