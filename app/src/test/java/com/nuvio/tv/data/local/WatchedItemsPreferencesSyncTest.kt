package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedItemsPreferencesSyncTest {
    @Test
    fun `snapshot is authoritative for non-pending items`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val remote = item("remote", 200L)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.replaceWithRemoteItems(listOf(remote), profileId = 1)

        assertEquals(listOf(remote), harness.preferences.getAllItems(1))
    }

    @Test
    fun `empty snapshot clears non-pending watched items`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("local", 100L), 1)

        harness.preferences.replaceWithRemoteItems(emptyList(), profileId = 1)

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `pending watched upsert survives snapshot`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        val preserved = harness.preferences.replaceWithRemoteItems(
            remoteItems = emptyList(),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertTrue(preserved)
        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `pending watched delete suppresses snapshot upsert`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.replaceWithRemoteItems(
            remoteItems = listOf(remote),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `remote delete cannot remove pending watched upsert`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.applyRemoteChanges(
            upserts = emptyList(),
            deletes = listOf(Triple("local", 1, 2)),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `remote upsert cannot restore pending watched delete`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.applyRemoteChanges(
            upserts = listOf(remote),
            deletes = emptyList(),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `explicit watched flow remains bound after active profile changes`() = runTest {
        val harness = harness()
        val firstProfileItem = item("first", 100L)
        val secondProfileItem = item("second", 200L)
        harness.preferences.markAsWatched(firstProfileItem, 1)
        harness.preferences.markAsWatched(secondProfileItem, 2)
        val fixedProfileFlow = harness.preferences.observeAllItems(1)

        harness.activeProfile.value = 2

        assertEquals(listOf(firstProfileItem), fixedProfileFlow.first())
        assertFalse(fixedProfileFlow.first().contains(secondProfileItem))
    }

    @Test
    fun `unchanged entries reuse decoded items across reads`() = runTest {
        val harness = harness()
        val first = item("first", 100L)
        val second = item("second", 200L)
        harness.preferences.markAsWatched(first, 1)
        val before = harness.preferences.getAllItems(1).single()

        harness.preferences.markAsWatched(second, 1)
        harness.preferences.advanceLastSuccessfulPushMs(300L, 1)
        val after = harness.preferences.getAllItems(1)

        assertEquals(setOf(first, second), after.toSet())
        assertSame(before, after.single { it.contentId == "first" })
        assertEquals(after.toSet(), harness.preferences.observeAllItems(1).first().toSet())
    }

    @Test
    fun `decoded items follow updates to the same entry`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("show", 100L), 1)
        harness.preferences.getAllItems(1)

        harness.preferences.markAsWatched(item("show", 200L), 1)

        assertEquals(listOf(item("show", 200L)), harness.preferences.getAllItems(1))
    }

    @Test
    fun `entries that fail to parse are skipped on every read`() = runTest {
        val store = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val preferences = WatchedItemsPreferences(factory, profileManager)
        val valid = item("valid", 100L)
        preferences.markAsWatched(valid, 1)
        store.edit { prefs ->
            val key = stringSetPreferencesKey("watched_items")
            prefs[key] = prefs[key].orEmpty() + "{not json"
        }

        val first = preferences.getAllItems(1)
        val second = preferences.getAllItems(1)

        assertEquals(listOf(valid), first)
        assertEquals(listOf(valid), second)
        assertSame(first.single(), second.single())
    }

    @Test fun `old snapshot cannot restore a locally unmarked episode even after its pending delete was acknowledged`() = runTest {
        val h = harness()
        val mark = item("episode", 100)
        h.preferences.markAsWatched(mark, 1)
        val revision = h.preferences.getLocalRevision(1)
        h.preferences.unmarkAsWatched("episode", 1, 2, 1)
        val result = runCatching {
            h.preferences.replaceWithRemoteItems(listOf(mark), profileId = 1, expectedLocalRevision = revision)
        }
        assertTrue(result.exceptionOrNull() is StaleWatchStatePullException)
        assertTrue(h.preferences.getAllItems(1).isEmpty())
    }

    @Test fun `another profile change does not invalidate this profiles incoming snapshot`() = runTest {
        val h = harness()
        val revision = h.preferences.getLocalRevision(1)
        h.preferences.markAsWatched(item("private", 100), 2)
        h.preferences.replaceWithRemoteItems(listOf(item("remote", 200)), profileId = 1, expectedLocalRevision = revision)
        assertEquals("remote", h.preferences.getAllItems(1).single().contentId)
        assertEquals("private", h.preferences.getAllItems(2).single().contentId)
    }

    @Test fun `opaque IDs equal to JSON field names can be unmarked without touching another episode`() = runTest {
        val h = harness()
        for (id in listOf("episode", "season", "contentId", "title")) {
            h.preferences.markAsWatched(item(id, 100), 1)
            h.preferences.markAsWatched(item(id, 100).copy(episode = 3), 1)
            h.preferences.unmarkAsWatched(id, 1, 2, 1)
            assertEquals(listOf(3), h.preferences.getAllItems(1).filter { it.contentId == id }.map { it.episode })
        }
    }

    @Test fun `upgrade reconciles a legacy delta cursor once without clearing local history`() = runTest {
        val h = harness()
        val watched = item("existing", 100)
        h.preferences.markAsWatched(watched, 1)
        h.stores.getValue(1).edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("watched_items_delta_initialized")] = true
            it[androidx.datastore.preferences.core.longPreferencesKey("watched_items_delta_cursor")] = 999L
        }
        h.preferences.ensureOrderedDeltaVersion(1)
        assertFalse(h.preferences.isDeltaInitialized(1))
        assertEquals(0L, h.preferences.getDeltaCursor(1))
        assertEquals(listOf(watched), h.preferences.getAllItems(1))
        h.preferences.setDeltaState(1001, true, 1)
        h.preferences.ensureOrderedDeltaVersion(1)
        assertTrue(h.preferences.isDeltaInitialized(1))
        assertEquals(1001L, h.preferences.getDeltaCursor(1))
    }

    private fun harness(): Harness {
        val activeProfile = MutableStateFlow(1)
        val stores = mutableMapOf<Int, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        return Harness(
            preferences = WatchedItemsPreferences(factory, profileManager),
            activeProfile = activeProfile,
            stores = stores
        )
    }

    private fun item(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = 1,
        episode = 2,
        watchedAt = watchedAt
    )

    private data class Harness(
        val preferences: WatchedItemsPreferences,
        val activeProfile: MutableStateFlow<Int>,
        val stores: Map<Int, TestPreferencesStore>
    )
}
