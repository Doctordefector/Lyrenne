package com.lyrenne.desktop.link

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the server needs from the player. Faked in LinkServerTest. */
interface LinkBackend {
    fun state(): JsonObject
    fun lyrics(): JsonObject
    fun queue(): JsonObject
    fun search(query: String): JsonObject
    /** Null on success, else an error message for the page. */
    fun command(op: String, value: JsonElement?): String?
    fun play(body: JsonObject): String?
    /** The cover of the song with [songId] (or of whatever plays), as bytes and a content type. */
    fun art(songId: String?): Pair<ByteArray, String>?
    /** A page subscribed to live events; a chance to start the lyrics lookup. */
    fun onSubscriber() {}
}

/**
 * The Lyrenne Link HTTP server: the overlay and the phone remote's pages, a JSON API, and a
 * Server-Sent Events stream. JDK `com.sun.net.httpserver`, the same as headless mode, so it adds
 * no dependency and the runtime already carries the module.
 *
 * Binds 127.0.0.1 unless [lan] is set, in which case it binds every interface. Every request goes
 * through the Host, Origin and token checks in [LinkSecurity] before it reaches a route.
 */
class LinkServer(
    private val backend: LinkBackend,
    private val preferredPort: Int,
    val lan: Boolean,
    private val token: () -> String,
    private val remoteEnabled: () -> Boolean = { lan },
    private val lanHosts: () -> Set<String> = { emptySet() },
) {
    private var server: HttpServer? = null
    var port: Int = -1
        private set
    private val limiter = FailedAuthLimiter()
    private val clients = CopyOnWriteArrayList<SseClient>()
    private val threadIds = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        MAX_THREADS, MAX_THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(64)
    ) { r -> Thread(r, "link-http-${threadIds.incrementAndGet()}").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    val clientCount: Int get() = clients.size

    /**
     * Binds [preferredPort], or the next free one of the 20 after it, or any free port. Returns the
     * port, which the caller shows when it is not the one asked for.
     */
    fun start(): Int {
        val address = if (lan) null else InetAddress.getLoopbackAddress()
        var bound: HttpServer? = null
        val candidates = if (preferredPort == 0) listOf(0)
            else (preferredPort until preferredPort + 20).filter { it in 1024..65535 } + 0
        var last: Exception? = null
        for (p in candidates) {
            try {
                val sock = if (address == null) InetSocketAddress(p) else InetSocketAddress(address, p)
                bound = HttpServer.create(sock, 0)
                break
            } catch (e: BindException) {
                last = e
            }
        }
        val s = bound ?: throw IOException("No free port near $preferredPort", last)
        s.executor = pool
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
        port = s.address.port
        return port
    }

    fun stop() {
        clients.forEach { it.close() }
        clients.clear()
        runCatching { server?.stop(0) }
        server = null
        pool.shutdownNow()
    }

    /** Sends an event to every live page. [authorizedOnly] keeps the queue to paired phones. */
    fun broadcast(event: String, data: JsonElement, authorizedOnly: Boolean = false) {
        if (clients.isEmpty()) return
        val text = LinkJson.sse(event, data.toString())
        clients.forEach { c -> if (!authorizedOnly || c.authorized) c.send(text) }
    }

    /** After a token rotation: paired pages drop, and reconnect only if they have the new one. */
    fun dropAuthorizedClients() {
        clients.filter { it.authorized }.forEach { it.close(); clients.remove(it) }
    }

    // --- Request handling ---

    private fun handle(ex: HttpExchange) {
        try {
            val headers = ex.responseHeaders
            headers.add("X-Content-Type-Options", "nosniff")
            headers.add("Referrer-Policy", "no-referrer")
            headers.add("Cache-Control", "no-store")
            val ip = ex.remoteAddress?.address
            val ipKey = ip?.hostAddress ?: "?"
            val loopback = LinkSecurity.isLoopback(ip)
            val hosts = if (lan) lanHosts() else emptySet()

            if (limiter.isBlocked(ipKey)) return text(ex, 429, "Too many wrong codes. Try again in a minute.")
            if (!LinkSecurity.hostAllowed(ex.requestHeaders.getFirst("Host"), port, hosts, lan) ||
                !LinkSecurity.originAllowed(ex.requestHeaders.getFirst("Origin"), port, hosts, lan) ||
                !LinkSecurity.fetchSiteAllowed(ex.requestHeaders.getFirst("Sec-Fetch-Site"))
            ) return text(ex, 403, "Forbidden")

            val path = ex.requestURI.path
            val route = LinkRoutes.resolve(ex.requestMethod, path)
                ?: return text(ex, if (ex.requestMethod in setOf("GET", "HEAD", "POST")) 404 else 405, "Not found")
            val query = LinkRoutes.parseQuery(ex.requestURI.rawQuery)
            val given = ex.requestHeaders.getFirst("X-Lyrenne-Token") ?: query["t"]
            val authorized = LinkSecurity.tokenMatches(given, token())

            when (route.access) {
                Access.PUBLIC -> {}
                Access.READ -> if (!loopback && !authorized) return denied(ex, ipKey, given)
                Access.CONTROL -> {
                    if (!remoteEnabled()) return json(ex, 403, error("The phone remote is off in Lyrenne."))
                    if (!authorized) return denied(ex, ipKey, given)
                }
            }
            if (authorized) limiter.recordSuccess(ipKey)

            when (route.name) {
                "remote" -> if (path == "/") redirect(ex, "/remote") else resource(ex, "remote.html")
                "overlay" -> resource(ex, "overlay.html")
                "favicon" -> resource(ex, "icon-192.png")
                "asset" -> resource(ex, LinkRoutes.assetName(path)!!)
                "manifest" -> manifest(ex, if (authorized) given else null)
                "now" -> json(ex, 200, backend.state())
                "state" -> json(ex, 200, buildJsonObject {
                    backend.state().forEach { (k, v) -> put(k, v) }
                    put("queue", backend.queue())
                })
                "lyrics" -> json(ex, 200, backend.lyrics())
                "queue" -> json(ex, 200, backend.queue())
                "search" -> json(ex, 200, backend.search(query["q"].orEmpty().take(200)))
                "art" -> art(ex, query["v"])
                "cmd" -> body(ex)?.let { b ->
                    val op = b["op"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val err = backend.command(op, b["value"])
                    json(ex, if (err == null) 200 else 400, err?.let { error(it) } ?: ok())
                }
                "play" -> body(ex)?.let { b ->
                    val err = backend.play(b)
                    json(ex, if (err == null) 200 else 400, err?.let { error(it) } ?: ok())
                }
                "events" -> events(ex, authorized)
            }
        } catch (e: IOException) {
            // The page went away mid-answer. Nothing to tell anyone.
        } catch (e: Exception) {
            Timber.e(e, "Lyrenne Link ${ex.requestURI.path} failed")
            runCatching { json(ex, 500, error("Something went wrong")) }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun denied(ex: HttpExchange, ipKey: String, given: String?) {
        // Only a wrong code counts against the address. A page that sent none is the overlay or
        // remote being opened without pairing, which is not an attack.
        if (!given.isNullOrEmpty()) limiter.recordFailure(ipKey)
        json(ex, 401, error("Not paired. Scan the QR code in Lyrenne again."))
    }

    /** JSON body, capped at 64 KB and required to say it is JSON. */
    private fun body(ex: HttpExchange): JsonObject? {
        val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        // Forms and text/plain are what a cross-site page can send without a preflight. Requiring
        // JSON means any such attempt needs a preflight, which this server never answers.
        if (!type.startsWith("application/json")) { json(ex, 415, error("Expected JSON")); return null }
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) { json(ex, 413, error("Too large")); return null }
        val text = bytes.decodeToString()
        if (text.isBlank()) return JsonObject(emptyMap())
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        if (parsed == null) json(ex, 400, error("Bad JSON"))
        return parsed
    }

    private fun events(ex: HttpExchange, authorized: Boolean) {
        if (clients.size >= MAX_SSE_CLIENTS) return json(ex, 503, error("Too many pages open"))
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.sendResponseHeaders(200, 0)
        val client = SseClient(authorized)
        val out = ex.responseBody
        // The first answer carries everything, so a reconnecting page is current at once.
        client.send("retry: 2000\n\n")
        client.send(LinkJson.sse("state", backend.state().toString()))
        client.send(LinkJson.sse("lyrics", backend.lyrics().toString()))
        if (authorized) client.send(LinkJson.sse("queue", backend.queue().toString()))
        clients += client
        runCatching { backend.onSubscriber() }
        try {
            // This thread owns the connection: a phone that stops reading stalls only itself.
            while (true) {
                val next = client.queue.poll(PING_MS, TimeUnit.MILLISECONDS) ?: LinkJson.SSE_PING
                if (next === SseClient.CLOSE) break
                out.write(next.toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            clients.remove(client)
            runCatching { out.close() }
        }
    }

    private class SseClient(val authorized: Boolean) {
        val queue = LinkedBlockingQueue<String>(256)
        fun send(text: String) {
            // A page that has fallen 256 events behind is gone; let it reconnect fresh.
            if (!queue.offer(text)) close()
        }
        fun close() { queue.clear(); queue.offer(CLOSE) }
        companion object { val CLOSE = String(charArrayOf('\u0000')) }
    }

    private fun resource(ex: HttpExchange, name: String) {
        val bytes = loadResource(name) ?: return text(ex, 404, "Not found")
        ex.responseHeaders.add("Content-Type", LinkRoutes.contentType(name))
        if (name.endsWith(".html")) {
            ex.responseHeaders.add(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' https: data: blob:; style-src 'self' 'unsafe-inline'; " +
                    "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
            )
        }
        send(ex, 200, bytes)
    }

    private fun manifest(ex: HttpExchange, token: String?) {
        val start = if (token != null) "/remote#t=$token" else "/remote"
        val m = buildJsonObject {
            put("name", "Lyrenne Remote")
            put("short_name", "Lyrenne")
            put("start_url", start)
            put("scope", "/")
            put("display", "standalone")
            put("orientation", "portrait")
            put("background_color", "#121110")
            put("theme_color", "#121110")
            put("icons", buildJsonArray {
                add(buildJsonObject { put("src", "/assets/icon-192.png"); put("sizes", "192x192"); put("type", "image/png") })
                add(buildJsonObject { put("src", "/assets/icon-512.png"); put("sizes", "512x512"); put("type", "image/png"); put("purpose", "any maskable") })
            })
        }
        ex.responseHeaders.add("Content-Type", "application/manifest+json")
        send(ex, 200, m.toString().toByteArray())
    }

    private fun art(ex: HttpExchange, songId: String?) {
        val (bytes, type) = backend.art(songId) ?: return text(ex, 404, "No art")
        ex.responseHeaders.set("Cache-Control", if (songId != null) "private, max-age=86400" else "no-store")
        ex.responseHeaders.add("Content-Type", type)
        send(ex, 200, bytes)
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        ex.sendResponseHeaders(302, -1)
    }

    private fun json(ex: HttpExchange, code: Int, body: JsonElement) {
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        send(ex, code, body.toString().toByteArray())
    }

    private fun text(ex: HttpExchange, code: Int, body: String) {
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        send(ex, code, body.toByteArray())
    }

    private fun send(ex: HttpExchange, code: Int, bytes: ByteArray) {
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(code, -1); return }
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }
    private fun ok() = buildJsonObject { put("ok", true) }

    companion object {
        private const val MAX_THREADS = 40
        const val MAX_SSE_CLIENTS = 24
        private const val MAX_BODY = 64 * 1024
        private const val PING_MS = 15_000L
        private val resourceCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        internal fun loadResource(name: String): ByteArray? = resourceCache[name]
            ?: LinkServer::class.java.getResourceAsStream("/link/$name")?.use { it.readBytes() }
                ?.also { resourceCache[name] = it }
    }
}
