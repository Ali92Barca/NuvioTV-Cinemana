package com.nuvio.android.bingers

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class BingersClientTest {
    @Test fun typedTmdbCatalogIdsKeepSeriesIdentity() {
        assertEquals(274556L, BingersClient.tmdbId("tmdb:tv:274556"))
        assertEquals(274556L, BingersClient.tmdbId("tmdb:series:274556:2:1"))
        assertEquals(123L, BingersClient.tmdbId("tmdb:movie:123"))
        assertEquals(123L, BingersClient.tmdbId("tmdb:123:1:2"))
        assertNull(BingersClient.tmdbId("cinemana:123"))
        assertNull(BingersClient.tmdbId("tmdb:tv:wrong"))
    }
    @Test fun completionOnlyUsesHostThresholdAndIgnoresSeekStartPause() {
        assertTrue(BingersClient.completed("stop", 80.0))
        assertFalse(BingersClient.completed("pause", 99.0))
        assertFalse(BingersClient.completed("start", 99.0))
        assertFalse(BingersClient.completed("stop", 79.99))
        assertFalse(BingersClient.completed("stop", Double.NaN))
        assertFalse(BingersClient.completed("stop", 200.0))
    }
    @Test fun magicLinkMustBelongToOfficialHttpsHost() {
        assertEquals("test-token", BingersClient.token("https://bingers.app/m?token=test-token"))
        for (link in listOf("https://evil.example/m?token=secret", "http://bingers.app/m?token=secret", "https://bingers.app.evil.example/m?token=secret", "https://user@bingers.app/m?token=secret", "raw-secret"))
            assertFailsWith<IllegalArgumentException> { BingersClient.token(link) }
    }
    @Test fun authRedirectIsNotFollowedAndSessionIsVerified() = runBlocking {
        var requests = 0
        val client = BingersClient { _, url, headers, _ ->
            requests++
            if (url.contains("/magic-link/verify")) {
                assertNull(headers["Cookie"])
                BingersResponse(302, "", listOf("better-auth.session_token=test-cookie; Path=/; Secure; HttpOnly"))
            } else {
                assertEquals("https://api.bingers.app/auth/get-session", url)
                assertEquals("better-auth.session_token=test-cookie", headers["Cookie"])
                BingersResponse(200, """{"user":{"id":"user-one"},"session":{"id":"session-one"}}""")
            }
        }
        assertEquals("user-one", client.login("https://bingers.app/m?token=test-token").userId)
        assertEquals(2, requests)
    }
    @Test fun usedMagicLinkCannotCreateALinkedState() {
        val client = BingersClient { _, _, _, _ -> BingersResponse(302, "") }
        assertFailsWith<BingersFailure> { runBlocking { client.login("https://bingers.app/m?token=test-token") } }
    }
    @Test fun episodeResolvesExternalIdentityAndExactSeasonEpisode() = runBlocking {
        val client = BingersClient { _, url, headers, _ ->
            assertNull(headers["Cookie"])
            val body = when {
                url.contains("/search/titles") -> """{"results":[{"id":"show-one","kind":"show","metadata":"hash-one"},{"id":"wrong-show","kind":"show","metadata":"hash-two"}]}"""
                url.contains("show-one/metadata") -> """{"kind":"show","external_ids":[{"source":"tmdb","id":"123"}]}"""
                url.contains("wrong-show/metadata") -> """{"kind":"show","external_ids":[{"source":"tmdb","id":"999"}]}"""
                url.endsWith("versions.json") -> """{"files":{"seasons":{"2":"season-token"}}}"""
                url.endsWith("season-2@season-token.json") -> """{"episodes":[{"id":"episode-one","n":1},{"id":"episode-two","n":2}]}"""
                else -> error("Unexpected fixture request")
            }
            BingersResponse(200, body)
        }
        assertEquals(BingersEntity("episode", "episode-two"), client.resolve(BingersMedia(listOf("Example"), null, 123, null, 2, 2)))
    }
    @Test fun conflictingExternalIdsNeverFallBackToMatchingTitle() {
        val client = BingersClient { _, url, _, _ -> BingersResponse(200, if (url.contains("/search/"))
            """{"results":[{"id":"wrong","kind":"movie","metadata":"h","card":{"originalTitle":"Example","year":2020}}]}"""
            else """{"kind":"movie","year":2020,"external_ids":[{"source":"tmdb","id":"999"}]}""") }
        assertFailsWith<BingersFailure> { runBlocking { client.resolve(BingersMedia(listOf("Example"), 2020, 123, null, null, null)) } }
    }
    @Test fun failedHistoryReadNeverGeneratesAWrite() {
        var posts = 0
        val client = BingersClient { method, _, _, _ -> if (method == "POST") posts++; BingersResponse(503, "") }
        assertFailsWith<BingersFailure> { runBlocking { client.prepare(BingersEntity("movie", "movie-one"), "test-cookie") } }
        assertEquals(0, posts)
    }
    @Test fun alreadyWatchedEntryDoesNotIncrementPlayCount() = runBlocking {
        val client = BingersClient { _, _, _, _ -> BingersResponse(200, """{"entries":[{"entityKind":"movie","entityId":"movie-one","watched":true,"plays":5}]}""") }
        assertNull(client.prepare(BingersEntity("movie", "movie-one"), "test-cookie"))
    }
    @Test fun unknownHistorySchemaDoesNotOverwriteHistory() {
        val client = BingersClient { _, _, _, _ -> BingersResponse(200, "{}") }
        assertFailsWith<BingersFailure> { runBlocking { client.prepare(BingersEntity("movie", "movie-one"), "test-cookie") } }
    }
    @Test fun retriesReuseSameBatchAndOperationIds() = runBlocking {
        val bodies = mutableListOf<String>()
        val client = BingersClient { method, _, headers, body ->
            assertEquals("test-cookie", headers["Cookie"])
            if (method == "GET") BingersResponse(200, """{"entries":[]}""")
            else { bodies.add(body!!); BingersResponse(if (bodies.size == 1) 503 else 200, "{}") }
        }
        val batch = client.prepare(BingersEntity("movie", "movie-one"), "test-cookie")!!
        val parsed = JSONObject(batch)
        assertEquals(1, parsed.getJSONArray("ops").getJSONObject(0).getJSONObject("fields").getInt("plays"))
        try { client.push("test-cookie", batch) } catch (_: BingersFailure) { }
        client.push("test-cookie", batch)
        assertEquals(bodies[0], bodies[1])
    }
    @Test fun subscriptionErrorsAreSurfacedWithoutReturningSecretResponseText() {
        val client = BingersClient { _, _, _, _ -> BingersResponse(402, """{"error":{"code":"subscription_required","message":"secret-cookie-value"}}""") }
        val failure = assertFailsWith<BingersFailure> { runBlocking { client.push("test-cookie", "{}") } }
        assertTrue(failure.reason.contains("اشتراك"))
        assertFalse(failure.reason.contains("secret-cookie"))
    }
}
