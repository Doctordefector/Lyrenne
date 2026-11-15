package com.lyrenne.desktop.link

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The server end to end on loopback, against a fake player. Uses raw sockets where a test needs a
 * header HttpURLConnection will not send (Host, Origin).
 */
class LinkServerTest {
    private val token = LinkSecurity.newToken()
    private val commands = CopyOnWriteArrayList<String>()
    private var remoteOn = true

    private val backend = object : LinkBackend {
        override fun state() = buildJsonObject { put("song", JsonNull); put("playing", false) }
        override fun lyrics() = buildJsonObject { put("songId", JsonNull) }
        override fun queue() = buildJsonObject { put("index", -1); put("items", JsonArray(emptyList())) }
        override fun search(query: String) = buildJsonObject { put("q", query) }
        override fun command(op: String, value: JsonElement?): String? {
            commands += op
            return if (op == "bad") "Unknown command" else null
        }
        override fun play(body: JsonObject): String? { commands += "play"; return null }
        override fun art(songId: String?): Pair<ByteArray, String>? = null
    }

    private val server = LinkServer(backend, 0, lan = false, token = { token }, remoteEnabled = { remoteOn })
    private val port = server.start()

    @After
    fun stop() = server.stop()

    /** A raw HTTP/1.1 request, so any header can be set. Returns status code and body. */
    private fun raw(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val h = linkedMapOf("Host" to "127.0.0.1:$port", "Connection" to "close")
            h.putAll(headers)
            val bytes = body?.toByteArray()
            if (bytes != null) h["Content-Length"] = bytes.size.toString()
            val req = buildString {
                append("$method $path HTTP/1.1\r\n")
                h.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray() + (bytes ?: ByteArray(0)))
            val text = s.getInputStream().readBytes().decodeToString()
            val code = text.substringAfter(' ').substringBefore(' ').toInt()
            return code to text.substringAfter("\r\n\r\n")
        }
    }

    private val auth get() = mapOf("X-Lyrenne-Token" to token)
    private val jsonType = mapOf("Content-Type" to "application/json")

    @Test
    fun `pages and assets are served without a token`() {
        val (code, body) = raw("GET", "/overlay")
        assertEquals(200, code)
        assertTrue(body.contains("<html"))
        assertEquals(200, raw("GET", "/assets/link-common.js").first)
        assertEquals(200, raw("GET", "/assets/icon-192.png").first)
        assertEquals(404, raw("GET", "/assets/missing.js").first)
        assertEquals(302, raw("GET", "/").first)
    }

    @Test
    fun `read endpoints are open on loopback`() {
        val (code, body) = raw("GET", "/api/now")
        assertEquals(200, code)
        assertTrue(Json.parseToJsonElement(body).jsonObject.containsKey("playing"))
        assertEquals(200, raw("GET", "/api/lyrics").first)
    }

    @Test
    fun `control needs the token`() {
        assertEquals(401, raw("POST", "/api/cmd", jsonType, """{"op":"next"}""").first)
        assertEquals(401, raw("POST", "/api/cmd", jsonType + ("X-Lyrenne-Token" to "wrong"), """{"op":"next"}""").first)
        assertEquals(401, raw("GET", "/api/state").first)
        assertTrue(commands.isEmpty())
        assertEquals(200, raw("POST", "/api/cmd", jsonType + auth, """{"op":"next"}""").first)
        assertEquals(listOf("next"), commands)
        // The token also works as ?t=, which is how EventSource sends it.
        assertEquals(200, raw("GET", "/api/state?t=$token").first)
    }

    @Test
    fun `backend errors come back as 400 with a message`() {
        val (code, body) = raw("POST", "/api/cmd", jsonType + auth, """{"op":"bad"}""")
        assertEquals(400, code)
        assertEquals("Unknown command", Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `control is refused while the remote is off, even with the token`() {
        remoteOn = false
        assertEquals(403, raw("POST", "/api/cmd", jsonType + auth, """{"op":"next"}""").first)
        assertTrue(commands.isEmpty())
        // The overlay keeps working.
        assertEquals(200, raw("GET", "/api/now").first)
    }

    @Test
    fun `a foreign Host header is refused, which blocks DNS rebinding`() {
        assertEquals(403, raw("GET", "/api/now", mapOf("Host" to "evil.example:$port")).first)
        assertEquals(403, raw("GET", "/overlay", mapOf("Host" to "127.0.0.1:1")).first)
        assertEquals(403, raw("POST", "/api/cmd", jsonType + auth + ("Host" to "attacker.test:$port"), """{"op":"next"}""").first)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `cross-site requests are refused and no CORS header is ever sent`() {
        assertEquals(403, raw("POST", "/api/cmd", jsonType + auth + ("Origin" to "https://evil.example"), """{"op":"next"}""").first)
        assertEquals(403, raw("GET", "/api/now", mapOf("Sec-Fetch-Site" to "cross-site")).first)
        assertEquals(403, raw("GET", "/art", mapOf("Sec-Fetch-Site" to "same-site")).first)
        assertTrue(commands.isEmpty())
        // Same origin is fine.
        assertEquals(200, raw("POST", "/api/cmd", jsonType + auth + ("Origin" to "http://127.0.0.1:$port"), """{"op":"next"}""").first)
        val conn = URL("http://127.0.0.1:$port/api/now").openConnection() as HttpURLConnection
        conn.setRequestProperty("Origin", "http://127.0.0.1:$port")
        assertEquals(200, conn.responseCode)
        assertEquals(null, conn.getHeaderField("Access-Control-Allow-Origin"))
        assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
        conn.disconnect()
    }

    @Test
    fun `preflights are not answered`() {
        assertEquals(405, raw("OPTIONS", "/api/cmd", mapOf("Access-Control-Request-Method" to "POST")).first)
    }

    @Test
    fun `control bodies must be JSON, which forces a preflight on any cross-site form`() {
        assertEquals(415, raw("POST", "/api/cmd", auth + ("Content-Type" to "text/plain"), """{"op":"next"}""").first)
        assertEquals(415, raw("POST", "/api/cmd", auth + ("Content-Type" to "application/x-www-form-urlencoded"), "op=next").first)
        assertEquals(400, raw("POST", "/api/cmd", jsonType + auth, "not json").first)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `an address guessing tokens gets locked out`() {
        repeat(10) { raw("POST", "/api/cmd", jsonType + ("X-Lyrenne-Token" to "guess$it"), "{}") }
        assertEquals(429, raw("POST", "/api/cmd", jsonType + auth, """{"op":"next"}""").first)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `the manifest carries the pairing only for a caller that has it`() {
        val anon = Json.parseToJsonElement(raw("GET", "/manifest.webmanifest").second).jsonObject
        assertEquals("/remote", anon["start_url"]!!.jsonPrimitive.content)
        val paired = Json.parseToJsonElement(raw("GET", "/manifest.webmanifest?t=$token").second).jsonObject
        assertEquals("/remote#t=$token", paired["start_url"]!!.jsonPrimitive.content)
    }

    /** Reads SSE lines until [until] has been seen, or fails after the socket timeout. */
    private fun openEvents(path: String): Pair<Socket, BufferedReader> {
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/event-stream\r\n\r\n".toByteArray())
        return s to BufferedReader(InputStreamReader(s.getInputStream()))
    }

    private fun BufferedReader.readUntil(predicate: (String) -> Boolean): List<String> {
        val seen = mutableListOf<String>()
        while (true) {
            val line = readLine() ?: break
            seen += line
            if (predicate(line)) break
        }
        return seen
    }

    @Test
    fun `events start with the full picture and then stream broadcasts`() {
        val (s, r) = openEvents("/events")
        s.use {
            val head = r.readUntil { it.startsWith("event: lyrics") }
            assertTrue(head.first().contains("200"))
            assertTrue(head.any { it.contains("text/event-stream") })
            assertTrue(head.contains("event: state"))
            // Not paired: the queue is not part of what an overlay gets.
            server.broadcast("queue", buildJsonObject { put("secret", true) }, authorizedOnly = true)
            server.broadcast("state", buildJsonObject { put("playing", true) })
            val next = r.readUntil { it.startsWith("data: {\"playing\":true") }
            assertTrue(next.none { it.contains("secret") })
        }
    }

    @Test
    fun `paired pages get the queue, and lose the stream when the token rotates`() {
        val (s, r) = openEvents("/events?t=$token")
        s.use {
            r.readUntil { it.startsWith("event: queue") }
            server.broadcast("queue", buildJsonObject { put("secret", true) }, authorizedOnly = true)
            r.readUntil { it.contains("secret") }
            assertEquals(1, server.clientCount)
            server.dropAuthorizedClients()
            // The server ends the chunked stream (a zero-length chunk), which makes EventSource reconnect.
            r.readUntil { it == "0" }
            Thread.sleep(100)
            assertEquals(0, server.clientCount)
        }
    }
}
