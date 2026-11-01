package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.TmdbKeyPairingServer
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val dataStore: TmdbSettingsDataStore,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context,
    private val trailerService: TrailerService,
    private val metaRepository: MetaRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(TmdbSettingsUiState())
    val uiState: StateFlow<TmdbSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    fun onEvent(event: TmdbSettingsEvent) {
        when (event) {
            is TmdbSettingsEvent.SetApiKey -> update {
                dataStore.setApiKey(event.apiKey)
                trailerService.clearCache()
                metaRepository.clearCache()
                cwEnrichmentCache.clearAll()
            }
            is TmdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TmdbSettingsEvent.ToggleModernHomeEnabled -> {
                update { dataStore.setModernHomeEnabled(event.enabled) }
            }
            is TmdbSettingsEvent.ToggleEnrichContinueWatching -> {
                update { dataStore.setEnrichContinueWatching(event.enabled) }
            }
            is TmdbSettingsEvent.SetLanguage -> update {
                val newLanguage = event.language.ifBlank { "en" }
                val currentLanguage = _uiState.value.language.ifBlank { "en" }
                dataStore.setLanguage(newLanguage)
                if (!newLanguage.equals(currentLanguage, ignoreCase = true)) {
                    trailerService.clearCache()
                }
            }
            is TmdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TmdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TmdbSettingsEvent.ToggleDetails -> update { dataStore.setUseDetails(event.enabled) }
            is TmdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TmdbSettingsEvent.ToggleProductions -> update { dataStore.setUseProductions(event.enabled) }
            is TmdbSettingsEvent.ToggleNetworks -> update { dataStore.setUseNetworks(event.enabled) }
            is TmdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TmdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TmdbSettingsEvent.ToggleMoreLikeThis -> update { dataStore.setUseMoreLikeThis(event.enabled) }
            is TmdbSettingsEvent.ToggleCollections -> update { dataStore.setUseCollections(event.enabled) }
        }
    }


    data class QrPairingState(
        val url: String? = null,
        val loading: Boolean = false,
        val pendingKey: String? = null,
        val error: String? = null
    )

    val qrPairing = MutableStateFlow(QrPairingState())
    private var pairingServer: TmdbKeyPairingServer? = null
    private var pairingProfileId: Int? = null
    private var pairingExpiry: Job? = null
    @Volatile private var pairingGeneration = 0L

    fun startQrPairing() {
        stopQrPairing()
        val ticket = pairingGeneration
        val profileId = profileManager.activeProfileId.value
        pairingProfileId = profileId
        qrPairing.value = QrPairingState(loading = true)

        viewModelScope.launch(Dispatchers.IO) {
            var created: TmdbKeyPairingServer? = null
            try {
                val ip = DeviceIpAddress.get(context) ?: error("No local network")
                val candidate = TmdbKeyPairingServer(
                    stillCurrent = {
                        pairingGeneration == ticket &&
                            profileManager.activeProfileId.value == profileId
                    },
                    onKeyProposed = { key ->
                        synchronized(this@TmdbSettingsViewModel) {
                            if (pairingGeneration != ticket ||
                                profileManager.activeProfileId.value != profileId ||
                                qrPairing.value.pendingKey != null
                            ) {
                                false
                            } else {
                                qrPairing.value = qrPairing.value.copy(pendingKey = key)
                                true
                            }
                        }
                    }
                )
                created = candidate
                candidate.start(5_000, false)

                synchronized(this@TmdbSettingsViewModel) {
                    if (pairingGeneration != ticket ||
                        profileManager.activeProfileId.value != profileId
                    ) {
                        candidate.stop()
                        return@launch
                    }

                    pairingServer = candidate
                    qrPairing.value = QrPairingState(url = candidate.pairingUrl(ip))
                    pairingExpiry = viewModelScope.launch {
                        repeat(300) {
                            delay(1_000)
                            if (!isActive || pairingGeneration != ticket) return@launch
                            if (profileManager.activeProfileId.value != profileId) {
                                stopQrPairing()
                                return@launch
                            }
                        }
                        if (pairingGeneration == ticket) {
                            stopQrPairing()
                            qrPairing.value = QrPairingState(error = "QR session expired")
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                created?.stop()
                throw error
            } catch (_: Exception) {
                created?.stop()
                if (pairingGeneration == ticket) {
                    qrPairing.value = QrPairingState(error = "Cannot start local QR server")
                }
            }
        }
    }

    @Synchronized
    fun stopQrPairing() {
        pairingGeneration++
        pairingExpiry?.cancel()
        pairingExpiry = null
        pairingServer?.stop()
        pairingServer = null
        pairingProfileId = null
        qrPairing.value = QrPairingState()
    }

    @Synchronized
    fun confirmQrKey() {
        val pending = qrPairing.value.pendingKey ?: return
        val expectedProfile = pairingProfileId ?: return

        if (profileManager.activeProfileId.value != expectedProfile) {
            stopQrPairing()
            return
        }

        stopQrPairing()
        viewModelScope.launch {
            if (profileManager.activeProfileId.value != expectedProfile) return@launch
            dataStore.setApiKeyForProfile(expectedProfile, pending)
            trailerService.clearCache()
            metaRepository.clearCache()
            cwEnrichmentCache.clearAll()
        }
    }

    override fun onCleared() {
        stopQrPairing()
        super.onCleared()
    }
    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class TmdbSettingsUiState(
    val apiKey: String = "",
    val bundledApiKeyAvailable: Boolean = com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank(),
    val enabled: Boolean = false,
    val modernHomeEnabled: Boolean = false,
    val enrichContinueWatching: Boolean = true,
    val language: String = "en",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useTrailers: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true
) {
    fun fromSettings(settings: TmdbSettings): TmdbSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        modernHomeEnabled = settings.modernHomeEnabled,
        enrichContinueWatching = settings.enrichContinueWatching,
        language = settings.language,
        useArtwork = settings.useArtwork,
        useBasicInfo = settings.useBasicInfo,
        useDetails = settings.useDetails,
        useCredits = settings.useCredits,
        useProductions = settings.useProductions,
        useNetworks = settings.useNetworks,
        useEpisodes = settings.useEpisodes,
        useTrailers = settings.useTrailers,
        useMoreLikeThis = settings.useMoreLikeThis,
        useCollections = settings.useCollections
    )
}

sealed class TmdbSettingsEvent {
    data class SetApiKey(val apiKey: String) : TmdbSettingsEvent()
    data class ToggleEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleModernHomeEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEnrichContinueWatching(val enabled: Boolean) : TmdbSettingsEvent()
    data class SetLanguage(val language: String) : TmdbSettingsEvent()
    data class ToggleArtwork(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleBasicInfo(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleDetails(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCredits(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleProductions(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleNetworks(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEpisodes(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleTrailers(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleMoreLikeThis(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCollections(val enabled: Boolean) : TmdbSettingsEvent()
}
