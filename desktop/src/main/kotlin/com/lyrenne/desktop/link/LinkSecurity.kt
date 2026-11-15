package com.lyrenne.desktop.link

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The checks every Lyrenne Link request goes through. Pure functions, so they are unit tested
 * without a server (LinkSecurityTest).
 */
object LinkSecurity {
    private val random = SecureRandom()

    /** 24 random bytes, base64url: 32 characters, short enough to keep the QR code easy to scan. */
    fun newToken(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Constant time, and length-blind: both sides are hashed first, so neither the position of the
     * first wrong character nor the token's length shows in the timing.
     */
    fun tokenMatches(given: String?, expected: String): Boolean {
        if (given.isNullOrEmpty() || expected.isEmpty()) return false
        val sha = MessageDigest.getInstance("SHA-256")
        val a = sha.digest(given.toByteArray())
        val b = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray())
        return MessageDigest.isEqual(a, b)
    }

    private val loopbackNames = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    /** Splits a Host header into (lower-case host, port or null). Handles `[::1]:80`. */
    internal fun splitHost(host: String): Pair<String, Int?> {
        val h = host.trim().lowercase()
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end < 0) return h to null
            val port = h.substring(end + 1).removePrefix(":").toIntOrNull()
            return h.substring(0, end + 1) to port
        }
        val colon = h.lastIndexOf(':')
        if (colon < 0) return h to null
        return h.substring(0, colon) to h.substring(colon + 1).toIntOrNull()
    }

    /**
     * Blocks DNS rebinding: a page on `evil.example` that re-resolves its name to 127.0.0.1 still
     * sends `Host: evil.example`, so only names this machine answers to are accepted, and only on
     * the port we are on. LAN names count only while the remote is on.
     */
    fun hostAllowed(hostHeader: String?, port: Int, lanHosts: Set<String>, lanEnabled: Boolean): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val (host, p) = splitHost(hostHeader)
        if (p != port) return false
        if (host in loopbackNames) return true
        return lanEnabled && host in lanHosts.map { it.lowercase() }
    }

    /**
     * Requests a browser makes for another site carry that site's Origin. Ours are same-origin, and
     * most same-origin GETs carry none at all. Anything else is refused, and no CORS header is ever
     * sent, so a page elsewhere can neither read our answers nor drive the player.
     */
    fun originAllowed(origin: String?, port: Int, lanHosts: Set<String>, lanEnabled: Boolean): Boolean {
        if (origin == null) return true
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        if (uri.scheme != "http" || uri.host == null) return false
        val host = if (uri.host.contains(':') && !uri.host.startsWith("[")) "[${uri.host}]" else uri.host
        return hostAllowed("$host:${if (uri.port == -1) 80 else uri.port}", port, lanHosts, lanEnabled)
    }

    /**
     * `Sec-Fetch-Site`, when the browser sends it, says outright who asked. `none` is a typed URL,
     * a QR scan or OBS loading the page; `same-origin` is our own pages. Everything else, including
     * `same-site` (another port on localhost), is someone else's page.
     */
    fun fetchSiteAllowed(secFetchSite: String?): Boolean =
        secFetchSite == null || secFetchSite == "same-origin" || secFetchSite == "none"

    fun isLoopback(address: InetAddress?): Boolean = address?.isLoopbackAddress == true
}

/**
 * Locks an address out for a while after too many wrong tokens. A 192-bit token cannot be guessed
 * anyway; this keeps a misbehaving client from spinning on the server and the log.
 */
class FailedAuthLimiter(
    private val maxFailures: Int = 10,
    private val windowMs: Long = 60_000,
    private val blockMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(var windowStart: Long, var failures: Int, var blockedUntil: Long)
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun isBlocked(key: String): Boolean {
        val e = entries[key] ?: return false
        return clock() < e.blockedUntil
    }

    @Synchronized
    fun recordFailure(key: String) {
        val now = clock()
        if (entries.size > 1000) entries.entries.removeIf { now - it.value.windowStart > windowMs && now >= it.value.blockedUntil }
        val e = entries.getOrPut(key) { Entry(now, 0, 0) }
        if (now - e.windowStart > windowMs) { e.windowStart = now; e.failures = 0 }
        e.failures++
        if (e.failures >= maxFailures) { e.blockedUntil = now + blockMs; e.failures = 0; e.windowStart = now }
    }

    @Synchronized
    fun recordSuccess(key: String) {
        entries.remove(key)
    }
}
