package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WatchedSeriesStateHolderProfileTest {

    private lateinit var tempDir: File
    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var holder: WatchedSeriesStateHolder
    private val activeProfileFlow = MutableStateFlow(1)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("watched_series_holder_test").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns tempDir
        }
        factory = ProfileDataStoreFactory(context)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfileFlow
        }
        val identities = mockk<com.nuvio.tv.data.repository.SharedPlaybackIdentity> {
            every { entries } returns MutableStateFlow(emptyList())
            every { expand(any()) } answers { firstArg() }
        }
        holder = WatchedSeriesStateHolder(factory, profileManager, identities)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `legacy Fire completion badge is discarded without clearing watch records`() = runTest {
        val badgeKey=stringSetPreferencesKey("fully_watched_ids")
        val recordKey=stringSetPreferencesKey("watched_items")
        factory.get(1,"watched_series_cache").edit {
            it[badgeKey]=setOf("tt43723646")
            it[intPreferencesKey("validation_reset_version")]=1
        }
        factory.get(1,"watched_items_preferences").edit { it[recordKey]=setOf("preserved episode record") }
        holder.loadFromDisk(1)
        assertEquals(emptySet<String>(),holder.fullyWatchedSeriesIds.value)
        assertEquals(null,factory.get(1,"watched_series_cache").data.first()[badgeKey])
        assertEquals(setOf("preserved episode record"),factory.get(1,"watched_items_preferences").data.first()[recordKey])
    }

    @Test
    fun `unwatch invalidates all supplied badges on disk and protects a different active profile`() = runTest {
        val key = stringSetPreferencesKey("fully_watched_ids")
        factory.get(1,"watched_series_cache").edit { it[key] = setOf("tt1234","cinemana-series:7","unrelated"); it[intPreferencesKey("validation_reset_version")] = 2 }
        factory.get(2,"watched_series_cache").edit { it[key] = setOf("tt1234"); it[intPreferencesKey("validation_reset_version")] = 2 }
        holder.loadFromDisk(1)
        holder.invalidateCompleted(setOf("tt1234","cinemana-series:7"),1)
        assertEquals(setOf("unrelated"),holder.fullyWatchedSeriesIds.value)
        assertEquals(setOf("unrelated"),factory.get(1,"watched_series_cache").data.first()[key])
        activeProfileFlow.value=2
        holder.loadFromDisk(2)
        holder.invalidateCompleted(setOf("unrelated"),1)
        assertEquals(setOf("tt1234"),holder.fullyWatchedSeriesIds.value)
        assertEquals(setOf("tt1234"),factory.get(2,"watched_series_cache").data.first()[key])
    }

    @Test fun `late badge evaluation from the previous profile cannot overwrite the active profile`() = runTest {
        activeProfileFlow.value = 2
        holder.loadFromDisk(2)
        holder.updateWithValidation(setOf("old-profile"), setOf("old-profile"), profileId = 1)
        assertEquals(emptySet<String>(), holder.fullyWatchedSeriesIds.value)
        assertEquals(null, factory.get(2, "watched_series_cache").data.first()[stringSetPreferencesKey("fully_watched_ids")])
    }

    @Test
    fun `loading another profile replaces the previous profile's series`() = runTest {
        factory.get(1, "watched_series_cache").edit { it[stringSetPreferencesKey("fully_watched_ids")] = setOf("tt0000001"); it[intPreferencesKey("validation_reset_version")] = 2 }

        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)

        activeProfileFlow.value = 2
        holder.loadFromDisk(profileId = 2)
        assertEquals(emptySet<String>(), holder.fullyWatchedSeriesIds.value)

        activeProfileFlow.value = 1
        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)
    }
}
