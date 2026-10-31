package com.nuvio.tv.data.bingers

import com.nuvio.android.bingers.BingersClient
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived, profile-bound phone handoff. Never logs or persists submitted links. */
class BingersPairingServer(
    private val stillCurrent: () -> Boolean,
    private val submit: (String) -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lifetimeMs: Long = 10 * 60_000L
) : NanoHTTPD(0) {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val deadline = now() + lifetimeMs
    private val busy = AtomicBoolean(false)
    @Volatile private var completed = false

    fun pairingUrl(ip: String): String = "http://$ip:$listeningPort/#$token"

    private fun reply(status: Response.Status, text: String, mime: String = "application/json; charset=utf-8"): Response =
        newFixedLengthResponse(status, mime, text).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
            addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'")
        }

    private fun json(status: Response.Status, message: String, ok: Boolean = false): Response =
        reply(status, JSONObject().put("ok", ok).put("message", message).toString())

    override fun serve(session: IHTTPSession): Response {
        if (now() >= deadline || !stillCurrent()) return json(Response.Status.GONE, "انتهت جلسة الربط. افتح باركوداً جديداً من التلفزيون.")
        if (session.method == Method.GET && session.uri == "/") {
            return reply(Response.Status.OK, BingersPairingPage.HTML, "text/html; charset=utf-8")
        }
        if (session.method != Method.POST || session.uri != "/api/link") return json(Response.Status.NOT_FOUND, "الصفحة غير موجودة")
        val provided = session.headers["x-pairing-token"].orEmpty()
        if (!MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
            return json(Response.Status.FORBIDDEN, "افتح الصفحة من باركود التلفزيون.")
        }
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://${session.headers["host"]}") return json(Response.Status.FORBIDDEN, "طلب غير مسموح")
        if (completed) return json(Response.Status.CONFLICT, "تم ربط الحساب بالفعل.")
        val size = session.headers["content-length"]?.toLongOrNull()
        if (size == null || size !in 1..16_384) return json(Response.Status.BAD_REQUEST, "الرابط غير صالح")
        if (!session.headers["content-type"].orEmpty().startsWith("application/json")) return json(Response.Status.BAD_REQUEST, "طلب غير صالح")
        if (!busy.compareAndSet(false, true)) return json(Response.Status.CONFLICT, "جاري ربط الحساب، انتظر قليلاً.")
        try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val link = JSONObject(body["postData"].orEmpty()).optString("link").trim()
            if (link.length > 8192) return json(Response.Status.BAD_REQUEST, "الرابط طويل جداً")
            BingersClient.token(link)
            if (!stillCurrent() || now() >= deadline) return json(Response.Status.GONE, "انتهت جلسة الربط")
            return if (submit(link)) {
                completed = true
                json(Response.Status.OK, "تم ربط Bingers على التلفزيون. يمكنك إغلاق هذه الصفحة.", true)
            } else json(Response.Status.BAD_REQUEST, "تعذر الربط. اطلب رابط دخول جديداً وأرسله دون فتحه.")
        } catch (_: Exception) {
            return json(Response.Status.BAD_REQUEST, "الصق رابط الدخول الأصلي من رسالة Bingers.")
        } finally {
            busy.set(false)
        }
    }
}
