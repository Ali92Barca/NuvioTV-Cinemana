package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class CinemanaAvailability {
    Available,
    Unsupported,
    Unknown
}

@Singleton
class CinemanaAvailabilityMonitor @Inject constructor(
    baseClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PROBE_URL =
            "https://cinemana.shabakaty.com/api/android/videoGroups/lang/ar/level/0"

        private const val MIN_PROBE_INTERVAL_MS = 60_000L
    }

    private val client = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .build()

    private val mutex = Mutex()

    private val _availability =
        MutableStateFlow(CinemanaAvailability.Unknown)

    val availability: StateFlow<CinemanaAvailability> =
        _availability.asStateFlow()

    private var lastProbeAtMs = 0L
    private var networkGeneration = 0L
    private var probeInProgress = false

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeJob: Job? = null
    private var lastObservedNetwork: Network? = null
    private var hasObservedNetwork = false

    fun startMonitoring(scope: CoroutineScope) {
        if (networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleNetworkCheck(scope)
            }

            override fun onLost(network: Network) {
                scheduleNetworkCheck(scope)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                scheduleNetworkCheck(scope)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        scheduleNetworkCheck(scope)
    }

    fun stopMonitoring() {
        networkChangeJob?.cancel()
        networkChangeJob = null

        networkCallback?.let { callback ->
            connectivityManager.unregisterNetworkCallback(callback)
        }

        networkCallback = null
        lastObservedNetwork = null
        hasObservedNetwork = false
    }

    private fun scheduleNetworkCheck(scope: CoroutineScope) {
        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            delay(750L)

            val currentNetwork = connectivityManager.activeNetwork
            if (hasObservedNetwork && currentNetwork == lastObservedNetwork) {
                return@launch
            }

            lastObservedNetwork = currentNetwork
            hasObservedNetwork = true

            onNetworkChanged()

            if (currentNetwork != null) {
                refresh(force = true)
            }
        }
    }

    suspend fun onNetworkChanged() {
        mutex.withLock {
            networkGeneration++
            lastProbeAtMs = 0L
            probeInProgress = false
            _availability.value = CinemanaAvailability.Unknown
        }
    }

    suspend fun refresh(force: Boolean = false): CinemanaAvailability {
        val generation = mutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()

            if (probeInProgress) {
                return@withLock null
            }

            if (!force &&
                lastProbeAtMs != 0L &&
                now - lastProbeAtMs < MIN_PROBE_INTERVAL_MS
            ) {
                return@withLock null
            }

            lastProbeAtMs = now
            probeInProgress = true
            networkGeneration
        } ?: return availability.value

        val result = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(PROBE_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", "CinemanaNuvioAndroid/0.1")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        CinemanaAvailability.Unknown
                    } else {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)

                        if (json.optJSONArray("groups") != null) {
                            CinemanaAvailability.Available
                        } else {
                            CinemanaAvailability.Unknown
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mutex.withLock {
                if (generation == networkGeneration) {
                    probeInProgress = false
                }
            }
            throw cancelled
        } catch (_: Exception) {
            CinemanaAvailability.Unknown
        }

        return mutex.withLock {
            if (generation != networkGeneration) {
                return@withLock _availability.value
            }

            probeInProgress = false

            val effectiveResult = if (
                result == CinemanaAvailability.Unknown &&
                _availability.value == CinemanaAvailability.Available
            ) {
                CinemanaAvailability.Available
            } else {
                result
            }

            _availability.value = effectiveResult
            effectiveResult
        }
    }
}