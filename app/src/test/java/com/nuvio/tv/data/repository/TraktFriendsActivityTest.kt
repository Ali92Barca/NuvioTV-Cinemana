package com.nuvio.tv.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktFriendsActivityTest {
    @Test fun scopeFailureFallsBackToWatchingAndHistoryForFollowedPeople() = runBlocking {
        val auth = mockk<TraktAuthService>()
        coEvery { auth.socialActivity(match { it.contains("/activities?") }) } returns
            Response.error(401, "{}".toResponseBody())
        coEvery { auth.socialActivity("users/me/following?extended=full") } returns
            Response.success("""[{"user":{"name":"One","ids":{"slug":"one"},"images":{"avatar":{"full":"https://example.com/one.png"}}}},{"user":{"name":"Two","ids":{"slug":"two"}}}]""".toResponseBody())
        coEvery { auth.socialActivity("users/one/watching?extended=full") } returns
            Response.success("""{"started_at":"2026-11-17T01:23:00Z","show":{"title":"Show","ids":{"imdb":"tt123"}},"episode":{"season":2,"number":4}}""".toResponseBody())
        coEvery { auth.socialActivity("users/two/watching?extended=full") } returns Response.success(null)
        coEvery { auth.socialActivity("users/two/history?limit=1&extended=full") } returns
            Response.success("""[{"watched_at":"2026-11-16T10:00:00Z","movie":{"title":"Film","ids":{"tmdb":42}}}]""".toResponseBody())

        val activity = auth.friendsActivity().orEmpty()
        assertEquals(listOf("tt123", "tmdb:42"), activity.map { it.id })
        assertEquals(listOf("One", "Two"), activity.map { it.friend })
        assertEquals(listOf(true, false), activity.map { it.watching })
        assertEquals("S2E4", activity.first().episode)
        assertTrue(activity.first().series)
        assertEquals("https://example.com/one.png", activity.first().avatar)
        assertEquals("2026-11-17T01:23:00Z", activity.first().timestamp)
        assertEquals("2026-11-16T10:00:00Z", activity.last().timestamp)
        assertEquals(null, activity.last().avatar)
    }
}
