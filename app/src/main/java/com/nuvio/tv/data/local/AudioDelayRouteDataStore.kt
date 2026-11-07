package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioDelayRouteDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "audio_delay_route_preference"
        private const val DELAY_MS = "delay_ms"
    }

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun key(routeKey: String) = intPreferencesKey("$DELAY_MS|$routeKey")

    suspend fun saveDelayMs(routeKey: String, delayMs: Int?, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            val key = key(routeKey)
            if (delayMs != null && delayMs != 0) {
                prefs[key] = delayMs
            } else {
                prefs.remove(key)
            }
        }
    }

    suspend fun loadDelayMs(routeKey: String, profileId: Int = profileManager.activeProfileId.value): Int? {
        return store(profileId).data.first()[key(routeKey)]
    }

    suspend fun loadTypeDelays(profileId: Int): Pair<Int, Int> {
        val prefs = store(profileId).data.first()
        return (prefs[intPreferencesKey("normal_audio_delay_ms")] ?: 0).coerceIn(-3000, 3000) to
            (prefs[intPreferencesKey("atmos_audio_delay_ms")] ?: 0).coerceIn(-500, 500)
    }

    suspend fun saveTypeDelayMs(profileId: Int, atmos: Boolean, delayMs: Int) {
        store(profileId).edit { prefs ->
            // Zero is a saved value, especially when overriding the Atmos default.
            prefs[intPreferencesKey(if (atmos) "atmos_audio_delay_ms" else "normal_audio_delay_ms")] =
                delayMs.coerceIn(if (atmos) -500 else -3000, if (atmos) 500 else 3000)
        }
    }
}
