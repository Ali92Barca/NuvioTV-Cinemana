package com.nuvio.tv.data.iptv

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IptvAddonOwnershipTest {
    @Test fun removingFromOneIndependentProfileKeepsOtherProfileAndLastRemovalReleasesCache() = runBlocking {
        val a = UserProfile(1,"A","#000000")
        val b = UserProfile(2,"B","#ffffff")
        val active = MutableStateFlow(1)
        val profiles = MutableStateFlow(listOf(a,b))
        val manager = mockk<ProfileManager> {
            every { activeProfileId } returns active
            every { this@mockk.profiles } returns profiles
            every { activeProfile } answers { profiles.value.first { it.id == active.value } }
        }
        val stores = mutableMapOf<Int,TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "addon_preferences") } answers { stores.getOrPut(firstArg()) { TestPreferencesStore() } }
        }
        val prefs = AddonPreferences(factory,manager)
        val url = "https://addon.example/private-config"
        prefs.addAddon(url)
        active.value=2
        prefs.addAddon(url)
        active.value=1
        prefs.removeAddon(url)
        assertFalse(prefs.installedAddonUrls.first().contains(url))
        assertTrue(prefs.isInstalledInAnyProfile(url))
        active.value=2
        assertTrue(prefs.installedAddonUrls.first().contains(url))
        prefs.removeAddon(url)
        assertFalse(prefs.isInstalledInAnyProfile(url))
    }
}
