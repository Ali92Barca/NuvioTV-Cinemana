package com.nuvio.tv.data.simkl

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SimklTokenRefresherTest {
    @Test fun `expired access is refreshed once and original request uses new bearer`() = runTest {
        val config = SimklApiConfiguration("fixture-client", "nuvio", "1")
        val scope = SimklAuthScope(1, 0)
        var current = SimklAuthorization(scope, "fixture-old")
        var tokens = SimklOAuthTokens("fixture-old", "fixture-refresh", 0L, "media:read media:write")
        val storage = mockk<SimklAuthStorage>()
        every { storage.authorization() } answers { current }
        every { storage.oauthTokens(scope) } answers { tokens }
        every { storage.completeOAuthAuthorization(any(), scope) } answers {
            tokens = firstArg()
            current = SimklAuthorization(scope, tokens.accessToken)
            true
        }
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val replies = ArrayDeque(listOf(
            SimklRawHttpResponse(401, """{"error":"invalid_token"}"""),
            SimklRawHttpResponse(200, """{"access_token":"fixture-new","refresh_token":"fixture-refresh","expires_in":604800,"scope":"media:read media:write"}"""),
            SimklRawHttpResponse(200, "{}")
        ))
        val engine = SimklHttpEngine { _, url, headers, _ ->
            requests += url to headers
            replies.removeFirst()
        }
        val refresher = SimklTokenRefresher(engine, config, storage) { 10L }
        var cleared = false
        val client = SimklApiClient(engine, config, storage::authorization, { cleared = true },
            sleep = {}, retryJitterMs = { 0L }, refreshAuthorization = refresher::refresh)
        assertEquals(200, client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities")).status)
        assertEquals(3, requests.size)
        assertTrue(requests[1].first.contains("/oauth2/token"))
        assertEquals("Bearer fixture-new", requests.last().second["Authorization"])
        assertEquals("fixture-refresh", tokens.refreshToken)
        assertFalse(cleared)
        // A concurrent stale caller reuses the refreshed token, without a second refresh.
        assertEquals(current, refresher.refresh(SimklAuthorization(scope, "fixture-old")))
        assertEquals(3, requests.size)
    }

    @Test fun `refresh cannot follow an authorization into another profile`() = runTest {
        val storage = mockk<SimklAuthStorage>()
        every { storage.authorization() } returns SimklAuthorization(SimklAuthScope(2, 1), "other")
        val engine = SimklHttpEngine { _, _, _, _ -> error("Must not send another profile's credentials") }
        val refresher = SimklTokenRefresher(engine, SimklApiConfiguration("fixture", "nuvio", "1"), storage)
        assertNull(refresher.refresh(SimklAuthorization(SimklAuthScope(1, 0), "old")))
    }
}
