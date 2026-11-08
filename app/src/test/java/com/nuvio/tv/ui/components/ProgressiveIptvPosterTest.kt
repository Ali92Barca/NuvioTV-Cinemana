package com.nuvio.tv.ui.components

import org.junit.Assert.*
import org.junit.Test

class ProgressiveIptvPosterTest {
    @Test fun failedReplacementsDoNotRetryOnEveryScrollOrBlockAnotherPoster() {
        var time = 1L
        val backoff = PosterReplacementBackoff { time }
        assertTrue(backoff.allowed("poster-a"))
        backoff.failed("poster-a")
        assertFalse(backoff.allowed("poster-a"))
        assertTrue(backoff.allowed("poster-b"))
        time += 599_999
        assertFalse(backoff.allowed("poster-a"))
        time += 1
        assertTrue(backoff.allowed("poster-a"))
    }
}
