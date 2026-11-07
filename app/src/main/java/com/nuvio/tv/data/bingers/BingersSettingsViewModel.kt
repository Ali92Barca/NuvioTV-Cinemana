package com.nuvio.tv.data.bingers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

data class BingersPairingUiState(val url: String? = null, val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class BingersSettingsViewModel @Inject constructor(
    val service: BingersService,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val pairing = MutableStateFlow(BingersPairingUiState())
    private var server: BingersPairingServer? = null
    @Volatile private var generation = 0L
    private var expiry: Job? = null

    fun startPairing() {
        stopPairing()
        val ticket = generation
        val key = service.preparePhonePairing() ?: return
        pairing.value = BingersPairingUiState(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            var created: BingersPairingServer? = null
            try {
                val ip = DeviceIpAddress.get(context) ?: error("No network")
                val candidate = BingersPairingServer(
                    stillCurrent = { generation == ticket && service.profileKey() == key },
                    submit = { link -> runBlocking(Dispatchers.IO) { service.linkFromPhone(link, key) { generation == ticket } } }
                )
                created = candidate
                candidate.start(5_000, false)
                synchronized(this@BingersSettingsViewModel) {
                    if (generation != ticket) { candidate.stop(); return@launch }
                    server = candidate
                    pairing.value = BingersPairingUiState(url = candidate.pairingUrl(ip))
                    expiry = viewModelScope.launch {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L
                        while (isActive && generation == ticket) {
                            delay(1_000)
                            if (service.profileKey() != key || android.os.SystemClock.elapsedRealtime() >= deadline) {
                                stopPairing()
                                pairing.value = BingersPairingUiState(error = context.getString(R.string.bingers_pairing_expired))
                                break
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                created?.stop()
                throw error
            } catch (_: Exception) {
                created?.stop()
                if (generation == ticket) pairing.value = BingersPairingUiState(error = context.getString(R.string.bingers_pairing_network_error))
            }
        }
    }

    @Synchronized fun stopPairing() {
        generation++
        expiry?.cancel()
        expiry = null
        server?.stop()
        server = null
        pairing.value = BingersPairingUiState()
    }
    override fun onCleared() { stopPairing(); super.onCleared() }
}
