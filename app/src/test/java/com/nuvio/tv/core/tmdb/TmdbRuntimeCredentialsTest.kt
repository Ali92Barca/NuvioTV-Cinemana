package com.nuvio.tv.core.tmdb

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.TmdbSettings
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbRuntimeCredentialsTest {
    @Test fun changingProfileKeyIsVisibleWithoutRecreatingService() {
        val state = MutableStateFlow(TmdbSettings(apiKey = "fixture-one"))
        val store = mockk<TmdbSettingsDataStore>()
        every { store.settings } returns state
        val service = TmdbService(mockk<TmdbApi>(), Lazy { store })
        assertEquals("fixture-one", service.apiKey())
        state.value = TmdbSettings(apiKey = "fixture-two")
        assertEquals("fixture-two", service.apiKey())
        state.value = TmdbSettings()
        assertEquals(BuildConfig.TMDB_API_KEY, service.apiKey())
    }
}
