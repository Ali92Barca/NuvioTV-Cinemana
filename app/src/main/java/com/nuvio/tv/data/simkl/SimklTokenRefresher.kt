package com.nuvio.tv.data.simkl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Called directly by the API client, never recursively through its request mutex. */
class SimklTokenRefresher(
    private val engine: SimklHttpEngine,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun refresh(previous: SimklAuthorization): SimklAuthorization? = mutex.withLock {
        val current = storage.authorization()?.takeIf { it.scope == previous.scope } ?: return@withLock null
        if (current.accessToken != previous.accessToken) return@withLock current
        val stored = storage.oauthTokens(previous.scope) ?: return@withLock null
        val response = engine.execute("POST", buildSimklApiUrl(configuration, "/oauth2/token"),
            simklRequestHeaders(configuration, contentTypeJson = true),
            buildJsonObject {
                put("grant_type", "refresh_token")
                put("client_id", configuration.clientId)
                put("refresh_token", stored.refreshToken)
            }.toString())
        if (response.status == 400 || response.status == 401) return@withLock null
        if (response.status !in 200..299) throw SimklApiException(response.status, "refresh_failed", "Simkl token refresh failed")
        val result = runCatching { json.decodeFromString<SimklPinResponse>(response.body) }.getOrNull()
            ?: throw SimklApiException(response.status, "refresh_failed", "Invalid Simkl token response")
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw SimklApiException(response.status, "refresh_failed", "Missing Simkl access token")
        val expiresIn = result.expiresIn?.takeIf { it > 0 }
            ?: throw SimklApiException(response.status, "refresh_failed", "Missing Simkl token expiry")
        val tokens = SimklOAuthTokens(token, result.refreshToken?.takeIf { it.isNotBlank() } ?: stored.refreshToken,
            nowEpochMs() + expiresIn * 1_000L, result.scope ?: stored.scope)
        if (!storage.completeOAuthAuthorization(tokens, previous.scope)) return@withLock null
        storage.authorization()?.takeIf { it.scope == previous.scope }
    }
}
