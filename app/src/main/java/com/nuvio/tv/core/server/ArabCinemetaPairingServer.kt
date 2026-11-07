package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** A temporary LAN-only request handoff; installation requires a TV-side confirmation. */
class ArabCinemetaPairingServer(private val onProposed: (String) -> Unit) : NanoHTTPD(0) {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val expiresAt = System.currentTimeMillis() + 5 * 60_000L
    private val accepted = AtomicBoolean(false)

    companion object {
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("tun") && !it.name.startsWith("tailscale") }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(it) }
        }.getOrNull()
    }

    fun qrUrl(ip: String) = "http://$ip:$listeningPort/#$token"

    private fun reply(status: Response.Status, data: String, contentType: String = "application/json; charset=utf-8") =
        newFixedLengthResponse(status, contentType, data).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Frame-Options", "DENY")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'")
        }
    private fun json(s: Response.Status, msg: String, ok: Boolean = false) =
        reply(s, JSONObject().put("ok", ok).put("message", msg).toString())

    override fun serve(session: IHTTPSession): Response {
        if (System.currentTimeMillis() >= expiresAt) return json(Response.Status.GONE, "Session expired. Reopen TV screen.")
        if (session.method == Method.GET && session.uri == "/") return reply(Response.Status.OK, ArabCinemetaPairingPage.HTML, "text/html; charset=utf-8")
        if (session.method != Method.POST || session.uri != "/api/manifest") return json(Response.Status.NOT_FOUND, "Not found")
        val received = session.headers["x-pairing-token"].orEmpty()
        if (!MessageDigest.isEqual(received.toByteArray(), token.toByteArray())) return json(Response.Status.FORBIDDEN, "Invalid pairing token")
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://${session.headers["host"]}") return json(Response.Status.FORBIDDEN, "Invalid origin")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..4096) return json(Response.Status.BAD_REQUEST, "Invalid body length")
        if (!session.headers["content-type"].orEmpty().startsWith("application/json")) return json(Response.Status.BAD_REQUEST, "Expected JSON")
        if (!accepted.compareAndSet(false, true)) return json(Response.Status.CONFLICT, "Already sent")
        return try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val url = JSONObject(body["postData"].orEmpty()).optString("manifest").trim()
            val parsed = java.net.URI(url)
            if (url.length > 2048 || parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank() ||
                parsed.userInfo != null || parsed.fragment != null || !parsed.path.orEmpty().endsWith("/manifest.json", ignoreCase = true)) {
                accepted.set(false)
                json(Response.Status.BAD_REQUEST, "Use a valid HTTPS manifest.json URL")
            } else {
                onProposed(url)
                json(Response.Status.OK, "Sent. Confirm installation on the television.", true)
            }
        } catch (_: Exception) {
            accepted.set(false)
            json(Response.Status.BAD_REQUEST, "Invalid request")
        }
    }
}
