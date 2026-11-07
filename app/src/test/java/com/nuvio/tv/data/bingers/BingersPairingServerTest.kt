package com.nuvio.tv.data.bingers

import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BingersPairingServerTest {
    @Test fun `real HTTP JSON request delivers verify link without consuming a real token`() {
        var delivered: String? = null
        val server = BingersPairingServer({ true }, { delivered = it; true })
        server.start(5_000, true)
        try {
            val address = java.net.URI(server.pairingUrl("127.0.0.1"))
            val connection = java.net.URL("http://127.0.0.1:${address.port}/api/link").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Pairing-Token", address.fragment)
            val link = "https://bingers.app/verify?token=fixture-http"
            val body = JSONObject().put("link", link).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue(JSONObject(response).optBoolean("ok"))
            assertEquals(link, delivered)
            assertFalse(response.contains("fixture-http"))
            connection.disconnect()
        } finally { server.stop() }
    }
    @Test fun `current verify email link reaches the TV callback`() {
        var delivered: String? = null
        val server = BingersPairingServer({ true }, { delivered = it; true })
        val link = "https://bingers.app/verify?token=fixture-current&callbackURL=https%3A%2F%2Fbingers.app%2Fimport"
        val response = server.serve(session(server, link))
        assertEquals(200, response.status.requestStatus)
        assertEquals(link, delivered)
        assertFalse(response.data.bufferedReader().readText().contains("fixture-current"))
    }
    @Test fun `both supported paths preserve secure host validation`() {
        for (path in listOf("/verify", "/verify/", "/m", "/m/")) {
            assertEquals("fixture", com.nuvio.android.bingers.BingersClient.token("https://bingers.app$path?token=fixture"))
        }
        for (link in listOf("http://bingers.app/verify?token=fixture", "https://bingers.app.evil.example/verify?token=fixture",
            "https://user@bingers.app/verify?token=fixture", "https://bingers.app/other?token=fixture")) {
            assertThrows(IllegalArgumentException::class.java) { com.nuvio.android.bingers.BingersClient.token(link) }
        }
    }
    private fun session(server: BingersPairingServer, link: String = "https://bingers.app/m?token=fixture",
        token: String = server.pairingUrl("127.0.0.1").substringAfter('#'),
        origin: String? = null): NanoHTTPD.IHTTPSession {
        val request = mockk<NanoHTTPD.IHTTPSession>()
        every { request.uri } returns "/api/link"
        every { request.method } returns NanoHTTPD.Method.POST
        every { request.headers } returns buildMap {
            put("x-pairing-token", token); put("content-length", "100")
            put("content-type", "application/json"); put("host", "127.0.0.1:8080")
            if (origin != null) put("origin", origin)
        }
        every { request.parseBody(any()) } answers {
            firstArg<MutableMap<String, String>>()["postData"] = JSONObject().put("link", link).toString()
        }
        return request
    }

    @Test fun `successful submission is delivered once without echoing link`() {
        var calls = 0
        val server = BingersPairingServer({ true }, { calls++; true })
        val response = server.serve(session(server))
        assertEquals(200, response.status.requestStatus)
        assertFalse(response.data.bufferedReader().readText().contains("fixture"))
        assertEquals(1, calls)
        assertEquals(409, server.serve(session(server)).status.requestStatus)
        assertEquals(1, calls)
    }

    @Test fun `missing token or foreign browser origin cannot link`() {
        val server = BingersPairingServer({ true }, { error("Unauthorized callback") })
        assertEquals(403, server.serve(session(server, token = "wrong")).status.requestStatus)
        assertEquals(403, server.serve(session(server, origin = "https://example.org")).status.requestStatus)
    }

    @Test fun `expired session and switched profile are rejected`() {
        var now = 0L
        var current = true
        val server = BingersPairingServer({ current }, { error("Stale callback") }, { now }, 100)
        current = false
        assertEquals(410, server.serve(session(server)).status.requestStatus)
        current = true
        now = 100
        assertEquals(410, server.serve(session(server)).status.requestStatus)
    }

    @Test fun `invalid links are rejected before contacting Bingers`() {
        val server = BingersPairingServer({ true }, { error("Invalid link callback") })
        assertEquals(400, server.serve(session(server, "https://example.org/m?token=fixture")).status.requestStatus)
        assertEquals(400, server.serve(session(server, "https://bingers.app/m")).status.requestStatus)
    }

    @Test fun `failed login can be retried with a fresh link`() {
        var success = false
        val server = BingersPairingServer({ true }, { success })
        assertEquals(400, server.serve(session(server)).status.requestStatus)
        success = true
        assertEquals(200, server.serve(session(server)).status.requestStatus)
    }

    @Test fun `page only exposes form and keeps credentials out of persistence`() {
        assertTrue(BingersPairingPage.HTML.contains("إرسال إلى التلفزيون"))
        assertTrue(BingersPairingPage.HTML.contains("https://bingers.app/import"))
        assertTrue(BingersPairingPage.HTML.contains("location.hash"))
        assertFalse(BingersPairingPage.HTML.contains("localStorage"))
        assertFalse(BingersPairingPage.HTML.contains("sessionStorage"))
    }
}
