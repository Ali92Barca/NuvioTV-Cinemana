package com.nuvio.tv.updater

import org.junit.Assert.*
import org.junit.Test

class AutomaticUpdatePolicyTest {
    private fun check(last: Long? = null, now: Long = 1000, debug: Boolean = false,
                      enabled: Boolean = true, busy: Boolean = false, downloading: Boolean = false) =
        AutomaticUpdatePolicy.shouldCheck(debug, enabled, busy, downloading, last, now)

    @Test fun `official startup checks but debug and disabled banners do not`() {
        assertTrue(check())
        assertFalse(check(debug = true))
        assertFalse(check(enabled = false))
    }
    @Test fun `foreground checks are throttled and retry after interval`() {
        assertFalse(check(last = 1000, now = 1001))
        assertTrue(check(last = 1000, now = 901000))
    }
    @Test fun `foreground does not interrupt checking or downloading`() {
        assertFalse(check(busy = true))
        assertFalse(check(downloading = true))
    }
    @Test fun `clock moving back does not suppress all future checks`() {
        assertTrue(check(last = 2000, now = 1000))
    }
}
