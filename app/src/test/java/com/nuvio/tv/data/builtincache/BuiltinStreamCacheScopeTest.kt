package com.nuvio.tv.data.builtincache

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinStreamCacheScopeTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun actualCacheKeysSeparateProfilesCredentialsLanguageAndProvidersAcrossRestart() = runBlocking {
        val dir = temporary.newFolder()
        val context = mockk<Context>(); every { context.filesDir } returns dir
        val profile = MutableStateFlow(1)
        val profiles = mockk<ProfileManager>(); every { profiles.activeProfileId } returns profile
        val config = MutableStateFlow(TmdbSettings(apiKey = "fixture-key", language = "ar"))
        val settings = mockk<TmdbSettingsDataStore>(); every { settings.settings } returns config
        val lazySettings = dagger.Lazy { settings }
        fun instance() = BuiltinStreamCache(context, profiles, lazySettings, OkHttpClient())
        var cache = instance()
        var calls = 0
        suspend fun request(host: String = "cee") = withContext(BuiltinPrefetchMode) {
            cache.get("https://$host.nuvio.invalid/stream/movie/tt1.json") {
                calls++; """{"streams":[{"url":"https://video.test/$calls.mp4"}]}"""
            }
        }
        val original = request(); assertEquals(original, request()); assertEquals(1, calls)
        cache = instance(); assertEquals(original, request()); assertEquals(1, calls)
        profile.value = 2; request(); assertEquals(2, calls)
        config.value = config.value.copy(language = "en"); request(); assertEquals(3, calls)
        request("cinemana"); assertEquals(4, calls)
        config.value = config.value.copy(apiKey = "other-key"); request(); assertEquals(5, calls)
    }
}
