package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioTypeDelayStorageTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(data.value)
            data.value = next
            return next
        }
    }
    @Test fun `profiles retain independent normal and Atmos values including zero`() = runTest {
        val stores = mutableMapOf<Int, MemoryStore>()
        val active = MutableStateFlow(1)
        val factory = mockk<ProfileDataStoreFactory>()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns active
        every { factory.get(any(), any()) } answers { stores.getOrPut(firstArg()) { MemoryStore() } }
        val settings = AudioDelayRouteDataStore(factory, profiles)
        assertEquals(0 to 0, settings.loadTypeDelays(1))
        settings.saveTypeDelayMs(1, false, 50)
        settings.saveTypeDelayMs(1, true, -225)
        active.value = 2
        assertEquals(0 to 0, settings.loadTypeDelays(2))
        settings.saveTypeDelayMs(2, true, 0)
        // An old playback controller writes only to its captured profile after a switch.
        settings.saveTypeDelayMs(1, false, 75)
        assertEquals(75 to -225, settings.loadTypeDelays(1))
        assertEquals(0 to 0, settings.loadTypeDelays(2))
    }
    @Test fun `type presets do not overwrite normal device offsets and values are bounded`() = runTest {
        val store = MemoryStore()
        val factory = mockk<ProfileDataStoreFactory>()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        every { factory.get(any(), any()) } returns store
        val settings = AudioDelayRouteDataStore(factory, profiles)
        settings.saveDelayMs("hdmi", 100, 1)
        settings.saveTypeDelayMs(1, true, -9000)
        assertEquals(100, settings.loadDelayMs("hdmi", 1))
        assertEquals(0 to -500, settings.loadTypeDelays(1))
    }
}
