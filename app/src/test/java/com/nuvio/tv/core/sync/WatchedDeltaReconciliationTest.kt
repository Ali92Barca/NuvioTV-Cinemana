package com.nuvio.tv.core.sync

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItemEvent
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class WatchedDeltaReconciliationTest {
    @Test fun `mark then unmark in the same remote page stays unmarked after repeated pulls`() = runTest {
        val events = listOf(event(10, "upsert"), event(11, "delete"))
        val h = harness { _, _ -> Json.encodeToString(events) }
        h.prefs.markAsWatched(item(), 5)
        h.prefs.setDeltaState(0, true, 5)
        repeat(2) { h.service.syncDeltaFromRemote(5).getOrThrow() }
        assertTrue(h.prefs.getAllItems(5).isEmpty())
        assertEquals(11L, h.prefs.getDeltaCursor(5))
    }

    @Test fun `latest event id wins even if the page is returned out of order`() {
        val change = reduceWatchedItemDelta(listOf(event(12, "delete"), event(9, "upsert")))
        assertTrue(change.upserts.isEmpty())
        assertEquals(listOf(Triple("tt38282411", 2, 4)), change.deletes)
        val rewatched = reduceWatchedItemDelta(listOf(event(14, "upsert"), event(12, "delete")))
        assertTrue(rewatched.deletes.isEmpty())
        assertEquals(1, rewatched.upserts.size)
    }

    @Test fun `local unwatch during remote fetch rejects the old page without advancing its cursor`() = runTest {
        lateinit var h: Harness
        var changeDuringRead = true
        h = harness { _, _ ->
            if (changeDuringRead) {
                h.prefs.unmarkAsWatched("tt38282411", 2, 4, 5)
                changeDuringRead = false
                Json.encodeToString(listOf(event(10, "upsert")))
            } else Json.encodeToString(listOf(event(11, "delete")))
        }
        h.prefs.markAsWatched(item(), 5)
        h.prefs.setDeltaState(0, true, 5)
        assertTrue(h.service.syncDeltaFromRemote(5).isFailure)
        assertTrue(h.prefs.getAllItems(5).isEmpty())
        assertEquals(0L, h.prefs.getDeltaCursor(5))
        h.service.syncDeltaFromRemote(5).getOrThrow()
        assertTrue(h.prefs.getAllItems(5).isEmpty())
        assertEquals(11L, h.prefs.getDeltaCursor(5))
    }

    @Test fun `profile switch during fetch only applies events to the requested profile`() = runTest {
        lateinit var h: Harness
        h = harness { _, _ ->
            h.active.value = 2
            h.prefs.markAsWatched(item().copy(contentId = "other-profile"), 2)
            Json.encodeToString(listOf(event(10, "upsert"), event(11, "delete")))
        }
        h.prefs.markAsWatched(item(), 5)
        h.prefs.setDeltaState(0, true, 5)
        h.service.syncDeltaFromRemote(5).getOrThrow()
        assertTrue(h.prefs.getAllItems(5).isEmpty())
        assertEquals("other-profile", h.prefs.getAllItems(2).single().contentId)
    }

    private fun event(id: Long, operation: String) = SupabaseWatchedItemEvent(
        eventId = id, operation = operation, contentId = "tt38282411", contentType = "series",
        title = "ABI", season = 2, episode = 4, watchedAt = 100
    )
    private fun item() = WatchedItem("tt38282411", "series", "ABI", 2, 4, 100)

    private fun harness(reply: suspend (String, JsonObject) -> String): Harness {
        val active = MutableStateFlow(5)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() } }
        }
        val prefs = WatchedItemsPreferences(factory, profiles)
        val mutations = WatchStateMutationStore(factory)
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            PostgrestResult(reply(firstArg(), secondArg()), Headers.Empty, postgrest)
        }
        val settings = mockk<TraktSettingsDataStore> { coEvery { getWatchProgressSource(any()) } returns WatchProgressSource.NUVIO_SYNC }
        val identity = mockk<SyncClientIdentity> { every { currentClientId() } returns "test-client" }
        return Harness(prefs, active, WatchedItemsSyncService(mockk<AuthManager>(relaxed = true), postgrest,
            prefs, TrackingProgressProviderRegistry(emptySet()), settings, profiles, identity, mutations))
    }
    private data class Harness(val prefs: WatchedItemsPreferences, val active: MutableStateFlow<Int>, val service: WatchedItemsSyncService)
}
