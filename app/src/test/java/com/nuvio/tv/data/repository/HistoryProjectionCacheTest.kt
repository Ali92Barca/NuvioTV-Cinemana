package com.nuvio.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class HistoryProjectionCacheTest {
    @Test fun `repeated readers share work but profile layout history and revision changes invalidate it`() {
        val cache = HistoryProjectionCache<String, Any>()
        var calls = 0
        fun project(profile: Int = 5, layout: String = "layout", revision: Int = 1, input: List<String> = listOf("episode")) =
            cache.get(profile, layout, revision, input) { calls++; Any() }
        val first = project()
        repeat(20) { assertSame(first, project(input = listOf("episode"))) }
        assertEquals(1, calls)
        assertNotSame(first, project(profile = 2))
        project(layout = "new-episode-layout")
        project(revision = 2)
        project(input = listOf("unwatched"))
        assertEquals(5, calls)
    }
}
