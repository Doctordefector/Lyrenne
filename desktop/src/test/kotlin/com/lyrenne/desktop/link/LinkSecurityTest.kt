package com.lyrenne.desktop.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSecurityTest {
    private val port = 48712
    private val lan = setOf("192.168.1.20", "mypc", "mypc.local", "[fe80::1]")

    @Test
    fun `tokens are long, url safe and different every time`() {
        val a = LinkSecurity.newToken()
        val b = LinkSecurity.newToken()
        assertEquals(32, a.length)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(a, b)
    }

    @Test
    fun `token compare accepts only the exact token`() {
        val t = LinkSecurity.newToken()
        assertTrue(LinkSecurity.tokenMatches(t, t))
        assertFalse(LinkSecurity.tokenMatches(t.dropLast(1), t))
        assertFalse(LinkSecurity.tokenMatches(t + "x", t))
        assertFalse(LinkSecurity.tokenMatches(t.uppercase(), t.lowercase()))
        assertFalse(LinkSecurity.tokenMatches("", t))
        assertFalse(LinkSecurity.tokenMatches(null, t))
        // An empty expected token (settings not loaded yet) must never match anything.
        assertFalse(LinkSecurity.tokenMatches("", ""))
    }

    @Test
    fun `loopback hosts pass on our port only`() {
        for (h in listOf("127.0.0.1:$port", "localhost:$port", "LOCALHOST:$port", "[::1]:$port")) {
            assertTrue(h, LinkSecurity.hostAllowed(h, port, emptySet(), lanEnabled = false))
        }
        assertFalse(LinkSecurity.hostAllowed("127.0.0.1:1234", port, emptySet(), false))
        assertFalse(LinkSecurity.hostAllowed("127.0.0.1", port, emptySet(), false))
        assertFalse(LinkSecurity.hostAllowed(null, port, emptySet(), false))
        assertFalse(LinkSecurity.hostAllowed("", port, emptySet(), false))
    }

    @Test
    fun `a rebound domain name is refused even though it points here`() {
        assertFalse(LinkSecurity.hostAllowed("evil.example:$port", port, lan, lanEnabled = true))
        assertFalse(LinkSecurity.hostAllowed("127.0.0.1.nip.io:$port", port, lan, true))
        assertFalse(LinkSecurity.hostAllowed("localhost.evil.example:$port", port, lan, true))
    }

    @Test
    fun `LAN names count only while the remote is on`() {
        assertTrue(LinkSecurity.hostAllowed("192.168.1.20:$port", port, lan, lanEnabled = true))
        assertTrue(LinkSecurity.hostAllowed("MyPC.local:$port", port, lan, true))
        assertTrue(LinkSecurity.hostAllowed("[fe80::1]:$port", port, lan, true))
        assertFalse(LinkSecurity.hostAllowed("192.168.1.20:$port", port, lan, lanEnabled = false))
        assertFalse(LinkSecurity.hostAllowed("192.168.1.99:$port", port, lan, true))
    }

    @Test
    fun `same origin passes, other sites and schemes do not`() {
        assertTrue(LinkSecurity.originAllowed(null, port, lan, false))
        assertTrue(LinkSecurity.originAllowed("http://127.0.0.1:$port", port, lan, false))
        assertTrue(LinkSecurity.originAllowed("http://localhost:$port", port, lan, false))
        assertTrue(LinkSecurity.originAllowed("http://192.168.1.20:$port", port, lan, true))
        assertFalse(LinkSecurity.originAllowed("http://192.168.1.20:$port", port, lan, false))
        assertFalse(LinkSecurity.originAllowed("https://evil.example", port, lan, true))
        assertFalse(LinkSecurity.originAllowed("http://localhost:3000", port, lan, true))
        assertFalse(LinkSecurity.originAllowed("https://127.0.0.1:$port", port, lan, true))
        assertFalse(LinkSecurity.originAllowed("null", port, lan, true))
        assertFalse(LinkSecurity.originAllowed("file://", port, lan, true))
    }

    @Test
    fun `fetch metadata allows typed URLs and our own pages only`() {
        assertTrue(LinkSecurity.fetchSiteAllowed(null))
        assertTrue(LinkSecurity.fetchSiteAllowed("none"))
        assertTrue(LinkSecurity.fetchSiteAllowed("same-origin"))
        assertFalse(LinkSecurity.fetchSiteAllowed("same-site"))
        assertFalse(LinkSecurity.fetchSiteAllowed("cross-site"))
    }

    @Test
    fun `splitHost handles IPv6 brackets`() {
        assertEquals("[::1]" to 80, LinkSecurity.splitHost("[::1]:80"))
        assertEquals("[::1]" to null, LinkSecurity.splitHost("[::1]"))
        assertEquals("example.com" to 8080, LinkSecurity.splitHost("Example.COM:8080"))
    }

    @Test
    fun `too many wrong tokens lock an address out for a while`() {
        var now = 0L
        val limiter = FailedAuthLimiter(maxFailures = 3, windowMs = 1_000, blockMs = 5_000, clock = { now })
        repeat(2) { limiter.recordFailure("a") }
        assertFalse(limiter.isBlocked("a"))
        limiter.recordFailure("a")
        assertTrue(limiter.isBlocked("a"))
        assertFalse("other addresses are unaffected", limiter.isBlocked("b"))
        now += 5_001
        assertFalse(limiter.isBlocked("a"))
    }

    @Test
    fun `failures spread out over time do not add up`() {
        var now = 0L
        val limiter = FailedAuthLimiter(maxFailures = 3, windowMs = 1_000, blockMs = 5_000, clock = { now })
        repeat(10) { limiter.recordFailure("a"); now += 600 }
        assertFalse(limiter.isBlocked("a"))
    }
}
