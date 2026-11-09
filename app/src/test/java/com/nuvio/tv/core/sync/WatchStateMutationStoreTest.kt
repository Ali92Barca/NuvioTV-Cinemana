package com.nuvio.tv.core.sync

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.domain.model.mutationKey

class WatchStateMutationStoreTest {
    @Test fun `official 1_0_2 pending JSON survives upgrade and rewrites with stable field names`() = runTest {
        val h = harness()
        val p = progress("legacy-progress", 100L)
        val w = watched("legacy-watched", 200L)
        val gson = Gson()
        val backing = h.factory.get(5, "watch_state_pending_mutations")
        // Actual installed official APK: o9.y0 has a:String/key,b:WatchProgress;
        // o9.z0 has a:WatchedMutationKey,b:WatchedItem. Domain fields are kept.
        backing.edit {
            it[stringSetPreferencesKey("progress_upserts")] = setOf(gson.toJson(mapOf("a" to "legacy-key", "b" to p)))
            it[stringSetPreferencesKey("watched_upserts")] = setOf(gson.toJson(mapOf("a" to w.mutationKey(), "b" to w)))
        }
        assertEquals(mapOf("legacy-key" to p), h.store.pendingProgressUpserts(5))
        assertEquals(mapOf(w.mutationKey() to w), h.store.pendingWatchedUpserts(5))
        assertTrue(h.store.pendingProgressUpserts(6).isEmpty())
        assertTrue(h.store.pendingWatchedUpserts(6).isEmpty())
        val newer = p.copy(lastWatched = 300L)
        h.store.queueProgressUpserts(mapOf("new-key" to newer), 5)
        h.store.queueWatchedUpserts(listOf(w.copy(contentId = "new-watched")), 5)
        assertEquals(p, h.store.pendingProgressUpserts(5)["legacy-key"])
        assertEquals(w, h.store.pendingWatchedUpserts(5)[w.mutationKey()])
        val persisted = backing.data.first()
        assertTrue(persisted[stringSetPreferencesKey("progress_upserts")].orEmpty().all { it.contains("\"key\"") && it.contains("\"progress\"") })
        assertTrue(persisted[stringSetPreferencesKey("watched_upserts")].orEmpty().all { it.contains("\"key\"") && it.contains("\"item\"") })
    }
    @Test
    fun `progress mutations stay isolated by profile`() = runTest {
        val harness = harness()
        val first = progress("first", 100L)
        val second = progress("second", 200L)

        harness.store.queueProgressUpserts(mapOf("first" to first), 1)
        harness.store.queueProgressUpserts(mapOf("second" to second), 2)

        assertEquals(mapOf("first" to first), harness.store.pendingProgressUpserts(1))
        assertEquals(mapOf("second" to second), harness.store.pendingProgressUpserts(2))
    }

    @Test
    fun `stale progress acknowledgement cannot clear a newer write`() = runTest {
        val harness = harness()
        val older = progress("item", 100L)
        val newer = progress("item", 200L)

        harness.store.queueProgressUpserts(mapOf("item" to older), 1)
        harness.store.queueProgressUpserts(mapOf("item" to newer), 1)
        harness.store.acknowledgeProgressUpserts(mapOf("item" to older), 1)

        assertEquals(mapOf("item" to newer), harness.store.pendingProgressUpserts(1))
    }

    @Test
    fun `progress delete and upsert supersede each other`() = runTest {
        val harness = harness()
        val item = progress("item", 100L)

        harness.store.queueProgressUpserts(mapOf("item" to item), 1)
        harness.store.queueProgressDeletes(listOf("item"), 1)

        assertTrue(harness.store.pendingProgressUpserts(1).isEmpty())
        assertEquals(setOf("item"), harness.store.pendingProgressDeletes(1))

        harness.store.queueProgressUpserts(mapOf("item" to item), 1)

        assertEquals(mapOf("item" to item), harness.store.pendingProgressUpserts(1))
        assertTrue(harness.store.pendingProgressDeletes(1).isEmpty())
    }

    @Test
    fun `stale watched acknowledgement cannot clear a newer write`() = runTest {
        val harness = harness()
        val older = watched("show", watchedAt = 100L)
        val newer = watched("show", watchedAt = 200L)

        harness.store.queueWatchedUpserts(listOf(older), 1)
        harness.store.queueWatchedUpserts(listOf(newer), 1)
        harness.store.acknowledgeWatchedUpserts(listOf(older), 1)

        assertEquals(listOf(newer), harness.store.pendingWatchedUpserts(1).values.toList())
    }

    @Test
    fun `watched delete and upsert supersede each other`() = runTest {
        val harness = harness()
        val item = watched("show", watchedAt = 100L)
        val key = WatchedMutationKey("show", 1, 2)

        harness.store.queueWatchedUpserts(listOf(item), 1)
        harness.store.queueWatchedDeletes(listOf(key), 1)

        assertTrue(harness.store.pendingWatchedUpserts(1).isEmpty())
        assertEquals(setOf(key), harness.store.pendingWatchedDeletes(1))

        harness.store.queueWatchedUpserts(listOf(item), 1)

        assertEquals(item, harness.store.pendingWatchedUpserts(1)[key])
        assertTrue(harness.store.pendingWatchedDeletes(1).isEmpty())
    }

    @Test
    fun `pending mutations survive store recreation and clear only after acknowledgement`() = runTest {
        val harness = harness()
        val item = progress("item", 100L)
        val watched = watched("show", watchedAt = 100L)
        harness.store.queueProgressUpserts(mapOf("item" to item), 1)
        harness.store.queueWatchedUpserts(listOf(watched), 1)

        val recreated = WatchStateMutationStore(harness.factory)

        assertTrue(recreated.hasPendingProgressMutations(1))
        assertTrue(recreated.hasPendingWatchedMutations(1))
        recreated.acknowledgeProgressUpserts(mapOf("item" to item), 1)
        recreated.acknowledgeWatchedUpserts(listOf(watched), 1)
        assertFalse(recreated.hasPendingProgressMutations(1))
        assertFalse(recreated.hasPendingWatchedMutations(1))
    }

    @Test fun `old delete acknowledgement cannot consume a later unwatch after rewatch`() = runTest {
        val h = harness()
        val key = WatchedMutationKey("show", 1, 2)
        h.store.queueWatchedDeletes(listOf(key), 1)
        val old = h.store.watchedDeleteVersions(listOf(key), 1)
        h.store.queueWatchedUpserts(listOf(watched("show", 200)), 1)
        h.store.queueWatchedDeletes(listOf(key), 1)
        val latest = h.store.watchedDeleteVersions(listOf(key), 1)
        h.store.acknowledgeWatchedDeletes(listOf(key), 1, old)
        assertEquals(setOf(key), h.store.pendingWatchedDeletes(1))
        assertTrue(latest.getValue(key) > old.getValue(key))
        WatchStateMutationStore(h.factory).acknowledgeWatchedDeletes(listOf(key), 1, latest)
        assertTrue(h.store.pendingWatchedDeletes(1).isEmpty())
    }

    @Test fun `old progress delete acknowledgement is isolated from a later delete and another profile`() = runTest {
        val h = harness()
        h.store.queueProgressDeletes(listOf("item"), 1)
        val old = h.store.progressDeleteVersions(listOf("item"), 1)
        h.store.queueProgressUpserts(mapOf("item" to progress("item", 200)), 1)
        h.store.queueProgressDeletes(listOf("item"), 1)
        h.store.queueProgressDeletes(listOf("item"), 2)
        h.store.acknowledgeProgressDeletes(listOf("item"), 1, old)
        assertEquals(setOf("item"), h.store.pendingProgressDeletes(1))
        assertEquals(setOf("item"), h.store.pendingProgressDeletes(2))
        val latest = h.store.progressDeleteVersions(listOf("item"), 1)
        h.store.acknowledgeProgressDeletes(listOf("item"), 1, latest)
        assertTrue(h.store.pendingProgressDeletes(1).isEmpty())
        assertEquals(setOf("item"), h.store.pendingProgressDeletes(2))
    }

    private fun harness(): Harness {
        val stores = mutableMapOf<Int, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }
        return Harness(WatchStateMutationStore(factory), factory)
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched
    )

    private fun watched(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = 1,
        episode = 2,
        watchedAt = watchedAt
    )

    private data class Harness(
        val store: WatchStateMutationStore,
        val factory: ProfileDataStoreFactory
    )
}
