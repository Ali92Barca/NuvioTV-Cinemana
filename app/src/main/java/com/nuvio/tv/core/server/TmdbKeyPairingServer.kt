package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary local-network handoff for a TMDB v3 API key.
 * Never logs or saves the submitted key.
 * The TV UI must explicitly approve a pending key before persisting it.
 */
class TmdbKeyPairingServer(
    private val stillCurrent: () -> Boolean,
    private val onKeyProposed: (String) -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lifetimeMs: Long = 5 * 60_000L
) : NanoHTTPD(0) {

    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val deadline = now() + lifetimeMs
    private val busy = AtomicBoolean(false)

    @Volatile private var submitted = false

    fun pairingUrl(ip: String): String = "http://$ip:$listeningPort/#$token"

    private fun reply(
        status: Response.Status,
        text: String,
        mime: String = "application/json; charset=utf-8"
    ): Response = newFixedLengthResponse(status, mime, text).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"
        )
    }

    private fun json(status: Response.Status, message: String, ok: Boolean = false): Response =
        reply(status, JSONObject().put("ok", ok).put("message", message).toString())

    override fun serve(session: IHTTPSession): Response {
        if (!stillCurrent() || now() >= deadline) {
            return json(Response.Status.GONE, "Pairing session expired")
        }

        if (session.method == Method.GET && session.uri == "/") {
            return reply(Response.Status.OK, TmdbKeyPairingPage.HTML, "text/html; charset=utf-8")
        }

        if (session.method != Method.POST || session.uri != "/api/key") {
            return json(Response.Status.NOT_FOUND, "Not found")
        }

        val provided = session.headers["x-pairing-token"].orEmpty()
        if (!MessageDigest.isEqual(
                provided.toByteArray(Charsets.UTF_8),
                token.toByteArray(Charsets.UTF_8)
            )
        ) {
            return json(Response.Status.FORBIDDEN, "Invalid pairing token")
        }

        val origin = session.headers["origin"]
        if (origin != null && origin != "http://${session.headers["host"]}") {
            return json(Response.Status.FORBIDDEN, "Invalid request origin")
        }

        if (submitted) return json(Response.Status.CONFLICT, "Key already submitted")

        val size = session.headers["content-length"]?.toLongOrNull()
        if (size == null || size !in 1..4096) {
            return json(Response.Status.BAD_REQUEST, "Invalid request size")
        }

        if (!session.headers["content-type"].orEmpty().startsWith("application/json")) {
            return json(Response.Status.BAD_REQUEST, "Expected JSON")
        }

        if (!busy.compareAndSet(false, true)) {
            return json(Response.Status.CONFLICT, "Submission in progress")
        }

        try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)

            val key = JSONObject(body["postData"].orEmpty())
                .optString("key")
                .trim()

            if (!Regex("^[a-fA-F0-9]{32}$").matches(key)) {
                return json(Response.Status.BAD_REQUEST, "Enter a valid TMDB v3 API key")
            }

            if (!stillCurrent() || now() >= deadline) {
                return json(Response.Status.GONE, "Pairing session expired")
            }

            return if (onKeyProposed(key)) {
                submitted = true
                json(Response.Status.OK, "Sent to TV. Confirm the key on your TV.", true)
            } else {
                json(Response.Status.CONFLICT, "TV is not ready")
            }
        } catch (_: Exception) {
            return json(Response.Status.BAD_REQUEST, "Invalid request")
        } finally {
            busy.set(false)
        }
    }
}