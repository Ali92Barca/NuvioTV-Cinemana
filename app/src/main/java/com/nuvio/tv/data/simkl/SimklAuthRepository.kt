package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage
    ) : this(apiClient, configuration, storage, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<SimklAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        val response = try {
            apiClient.execute(SimklApiRequest(SimklHttpMethod.GET, "/oauth/pin", requiresAuthentication = false))
        } catch (error: SimklApiException) {
            if (error.status != 400 || error.errorCode != "unauthorized_client") throw SimklAuthException(SimklAuthError.NETWORK, error)
            executeAuthRequest(SimklApiRequest(
                SimklHttpMethod.POST, "/oauth2/device", requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER,
                body = buildJsonObject {
                    put("client_id", configuration.clientId)
                    put("scope", "media:read media:write")
                }.toString()
            ))
        }
        val payload = decodePinResponse(response.body)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (payload.verificationUri ?: payload.verificationUrl)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        val modern = payload.result == null && !payload.deviceCode.isNullOrBlank()
        if ((!modern && payload.result != "OK") || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        val session = SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval,
            deviceCode = payload.deviceCode.takeIf { modern },
            nextPollAtEpochMs = if (modern) nowEpochMs() + interval * 1_000L else 0L
        )
        if (!storage.savePinSession(session, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        session
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        if (session.deviceCode != null) return@withLock pollOAuthDevice(session, authScope)
        if (!PIN_PATTERN.matches(session.userCode)) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin/${session.userCode}",
                requiresAuthentication = false
            )
        )
        val payload = decodePinResponse(response.body)
        if (!payload.deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (payload.result == "OK" && token != null) {
            if (!storage.completePinAuthorization(token, authScope)) {
                return@withLock SimklPinPollResult.Invalidated
            }
            try {
                refreshUserSettings(scope = authScope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
            return@withLock SimklPinPollResult.Authorized
        }
        if (payload.result == "KO") return@withLock SimklPinPollResult.Pending
        throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    private suspend fun pollOAuthDevice(session: SimklPinSession, authScope: SimklAuthScope): SimklPinPollResult {
        if (nowEpochMs() < session.nextPollAtEpochMs) return SimklPinPollResult.Pending
        val response = try {
            apiClient.execute(SimklApiRequest(
                SimklHttpMethod.POST, "/oauth2/token", requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER,
                body = buildJsonObject {
                    put("client_id", configuration.clientId)
                    put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    put("device_code", session.deviceCode)
                }.toString()
            ))
        } catch (error: SimklApiException) {
            when (error.errorCode) {
                "authorization_pending", "slow_down" -> {
                    val interval = session.intervalSeconds + if (error.errorCode == "slow_down") 5 else 0
                    if (!storage.savePinSession(session.copy(intervalSeconds = interval,
                        nextPollAtEpochMs = nowEpochMs() + interval * 1_000L), authScope)) return SimklPinPollResult.Invalidated
                    return SimklPinPollResult.Pending
                }
                "expired_token" -> {
                    storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
                    return SimklPinPollResult.Expired
                }
                "invalid_client", "access_denied" -> {
                    storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
                    return SimklPinPollResult.Invalidated
                }
                else -> throw SimklAuthException(SimklAuthError.NETWORK, error)
            }
        }
        val payload = decodePinResponse(response.body)
        val token = payload.accessToken?.takeIf { it.isNotBlank() }
        val refresh = payload.refreshToken?.takeIf { it.isNotBlank() }
        val granted = payload.scope.orEmpty().split(' ').toSet()
        if (token == null || refresh == null || payload.expiresIn == null || payload.expiresIn <= 0 ||
            "media:read" !in granted || "media:write" !in granted) {
            storage.clearPinSession(SimklAuthError.INVALID_PIN_RESPONSE, authScope)
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        if (!storage.completeOAuthAuthorization(SimklOAuthTokens(token, refresh,
                nowEpochMs() + payload.expiresIn * 1_000L, payload.scope.orEmpty()), authScope)) return SimklPinPollResult.Invalidated
        try { refreshUserSettings(scope = authScope) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { Unit }
        return SimklPinPollResult.Authorized
    }

    fun disconnect() = storage.clearAuth()

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private fun decodePinResponse(body: String): SimklPinResponse =
        runCatching { json.decodeFromString<SimklPinResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        val PIN_PATTERN = Regex("[A-Za-z0-9]{4,12}")
    }
}
