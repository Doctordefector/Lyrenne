package com.lyrenne.desktop.sound.listenbrainz

import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.sound.HttpResult
import com.lyrenne.desktop.sound.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

private val LISTEN = PendingListen(
    listenedAt = 1_731_600_000,
    artist = "Luis Fonsi, Daddy Yankee",
    track = "Despacito",
    release = "VIDA",
    durationMs = 229_000,
    videoId = "kJQP7kiw5Fk",
)

/** A fake ListenBrainz: answers from [answer] and records every request. */
private class FakeServer(var answer: (url: String, body: String?) -> HttpResult) : HttpTransport {
    val requests = mutableListOf<Triple<String, Map<String, String>, String?>>()
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        requests += Triple(url, headers, body)
        return answer(url, body)
    }
}

private fun api(t: HttpTransport) = ListenBrainzApi(t)

private fun tempQueueFile(): File = Files.createTempDirectory("lbqueue").toFile().resolve("listenbrainz-queue.json")

class ListenBrainzTest {
    private val json = Json

    @Test
    fun `single listen payload carries timestamp, names and additional info`() {
        val body = json.parseToJsonElement(ListenPayload.build(ListenType.SINGLE, listOf(LISTEN), "2.20.5")).jsonObject
        assertEquals("single", body["listen_type"]!!.jsonPrimitive.content)
        val listen = body["payload"]!!.jsonArray.single().jsonObject
        assertEquals(1_731_600_000L, listen["listened_at"]!!.jsonPrimitive.long)
        val meta = listen["track_metadata"]!!.jsonObject
        assertEquals("Luis Fonsi, Daddy Yankee", meta["artist_name"]!!.jsonPrimitive.content)
        assertEquals("Despacito", meta["track_name"]!!.jsonPrimitive.content)
        assertEquals("VIDA", meta["release_name"]!!.jsonPrimitive.content)
        val info = meta["additional_info"]!!.jsonObject
        assertEquals(229_000L, info["duration_ms"]!!.jsonPrimitive.long)
        assertEquals("https://music.youtube.com/watch?v=kJQP7kiw5Fk", info["origin_url"]!!.jsonPrimitive.content)
        assertEquals("Lyrenne", info["submission_client"]!!.jsonPrimitive.content)
        assertEquals("2.20.5", info["submission_client_version"]!!.jsonPrimitive.content)
        assertEquals("music.youtube.com", info["music_service"]!!.jsonPrimitive.content)
    }

    @Test
    fun `playing now has no timestamp, and missing album or length are left out`() {
        val bare = LISTEN.copy(release = null, durationMs = null)
        val listen = json.parseToJsonElement(ListenPayload.build(ListenType.PLAYING_NOW, listOf(bare), "1"))
            .jsonObject["payload"]!!.jsonArray.single().jsonObject
        assertNull(listen["listened_at"])
        val meta = listen["track_metadata"]!!.jsonObject
        assertNull(meta["release_name"])
        assertNull(meta["additional_info"]!!.jsonObject["duration_ms"])
    }

    @Test
    fun `import carries every listen, single and playing now exactly one`() {
        val two = listOf(LISTEN, LISTEN.copy(listenedAt = LISTEN.listenedAt + 300))
        val body = json.parseToJsonElement(ListenPayload.build(ListenType.IMPORT, two, "1")).jsonObject
        assertEquals(2, body["payload"]!!.jsonArray.size)
        assertFails { ListenPayload.build(ListenType.SINGLE, two, "1") }
        assertFails { ListenPayload.build(ListenType.SINGLE, emptyList(), "1") }
    }

    @Test
    fun `token validation reads the user name`() {
        val server = FakeServer { url, _ ->
            assertTrue(url.endsWith("/1/validate-token"))
            HttpResult(200, """{"code":200,"message":"Token valid.","valid":true,"user_name":"lyrenne_tester"}""")
        }
        assertEquals(TokenCheck.Valid("lyrenne_tester"), ListenBrainzApi(server).validateToken(" abc "))
        assertEquals("Token abc", server.requests.single().second["Authorization"])
        server.answer = { _, _ -> HttpResult(200, """{"code":200,"message":"Token invalid.","valid":false}""") }
        assertEquals(TokenCheck.Invalid, ListenBrainzApi(server).validateToken("x"))
        val offline = api { _, _, _, _ -> throw IOException("no route") }
        assertTrue(offline.validateToken("x") is TokenCheck.Unreachable)
    }

    @Test
    fun `submission outcomes follow the status code`() {
        fun outcome(code: Int, headers: Map<String, String> = emptyMap()) =
            api { _, _, _, _ -> HttpResult(code, """{"code":$code,"error":"nope"}""", headers) }
                .submit("t", ListenType.SINGLE, listOf(LISTEN), "1")
        assertEquals(SubmitOutcome.Accepted, outcome(200))
        assertEquals(SubmitOutcome.Rejected("nope"), outcome(400))
        assertEquals(SubmitOutcome.Unauthorized, outcome(401))
        assertEquals(SubmitOutcome.TryLater("rate limited", 11_000), outcome(429, mapOf("x-ratelimit-reset-in" to "10")))
        assertTrue(outcome(503) is SubmitOutcome.TryLater)
        val offline = api { _, _, _, _ -> throw IOException("timeout") }
        assertTrue(offline.submit("t", ListenType.SINGLE, listOf(LISTEN), "1") is SubmitOutcome.TryLater)
    }

    @Test
    fun `queue survives a restart and deletes its file once empty`() {
        val file = tempQueueFile()
        ListenQueue(file).apply { add(LISTEN); add(LISTEN.copy(track = "Second")) }
        assertTrue(file.exists())
        val reopened = ListenQueue(file)
        assertEquals(2, reopened.size)
        assertEquals(listOf("Despacito", "Second"), reopened.peek(10).map { it.track })
        reopened.remove(reopened.peek(10))
        assertFalse(file.exists())
        assertEquals(0, ListenQueue(file).size)
    }

    @Test
    fun `a corrupt queue file is set aside, not overwritten`() {
        val file = tempQueueFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val q = ListenQueue(file)
        assertEquals(0, q.size)
        assertTrue(File(file.parentFile, file.name + ".broken").exists())
    }

    @Test
    fun `a flaky network never loses a listen`() {
        val file = tempQueueFile()
        val queue = ListenQueue(file)
        queue.add(LISTEN)
        var online = false
        val server = FakeServer { _, _ -> if (online) HttpResult(200, """{"status":"ok"}""") else throw IOException("offline") }
        val sender = ListenSender(ListenBrainzApi(server), queue, "1")
        assertTrue(sender.flush("t") is ListenSender.Result.Wait)
        // Still there, and still on disk for the next run
        assertEquals(1, ListenQueue(file).size)
        online = true
        assertEquals(ListenSender.Result.Done, sender.flush("t"))
        assertEquals(0, queue.size)
        assertTrue(server.requests.last().third!!.contains("\"single\""))
    }

    @Test
    fun `a refused token keeps every listen`() {
        val queue = ListenQueue(tempQueueFile()).apply { add(LISTEN) }
        val sender = ListenSender(api { _, _, _, _ -> HttpResult(401, "{}") }, queue, "1")
        assertEquals(ListenSender.Result.Unauthorized, sender.flush("t"))
        assertEquals(1, queue.size)
    }

    @Test
    fun `a backlog goes as import batches`() {
        val queue = ListenQueue(tempQueueFile())
        repeat(150) { queue.add(LISTEN.copy(listenedAt = LISTEN.listenedAt + it)) }
        val server = FakeServer { _, _ -> HttpResult(200, "{}") }
        assertEquals(ListenSender.Result.Done, ListenSender(ListenBrainzApi(server), queue, "1").flush("t"))
        assertEquals(2, server.requests.size)
        assertTrue(server.requests.all { it.third!!.contains("\"import\"") })
        assertEquals(0, queue.size)
    }

    @Test
    fun `one bad listen in a batch does not hold back the rest`() {
        val queue = ListenQueue(tempQueueFile())
        queue.add(LISTEN)
        queue.add(LISTEN.copy(track = "", listenedAt = 2))
        queue.add(LISTEN.copy(track = "Fine", listenedAt = 3))
        val server = FakeServer { _, body ->
            if (body!!.contains("\"track_name\":\"\"")) HttpResult(400, """{"error":"track_name empty"}""") else HttpResult(200, "{}")
        }
        assertEquals(ListenSender.Result.Done, ListenSender(ListenBrainzApi(server), queue, "1").flush("t"))
        assertEquals(0, queue.size)
        // The batch, then each of the three on its own
        assertEquals(4, server.requests.size)
        assertEquals(2, server.requests.count { it.third!!.contains("\"single\"") && !it.third!!.contains("\"track_name\":\"\"") })
    }

    @Test
    fun `backoff doubles from 15 s up to 30 minutes and honours the server`() {
        assertEquals(15_000L, retryBackoffMs(1))
        assertEquals(30_000L, retryBackoffMs(2))
        assertEquals(60_000L, retryBackoffMs(3))
        assertEquals(30 * 60_000L, retryBackoffMs(20))
        assertEquals(90_000L, retryBackoffMs(1, 90_000))
    }

    private fun assertFails(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("expected IllegalArgumentException")
    }
}

class ScrobbleThresholdTest {
    private val song = SongInfo("vid", "Despacito", "Luis Fonsi", null, album = "VIDA", duration = 229)

    @Test
    fun `half the track or four minutes, never under thirty seconds`() {
        assertEquals(114_500L, listenThresholdMs(229_000))
        assertEquals(240_000L, listenThresholdMs(600_000))
        assertEquals(15_000L, listenThresholdMs(30_000))
        assertNull(listenThresholdMs(29_999))
        assertEquals(240_000L, listenThresholdMs(0))
    }

    /** Plays [from]..[to] in 200 ms ticks, wall time starting at [wall]. */
    private fun ScrobbleTracker.play(from: Long, to: Long, wall: Long, duration: Long = 229_000, playing: Boolean = true) =
        (from..to step 200).flatMap { p -> onState(song, playing, p, duration, wall + (p - from)) }

    @Test
    fun `now playing once, then a listen at half the track`() {
        val t = ScrobbleTracker()
        val events = t.play(0, 120_000, wall = 1_000_000)
        assertEquals(2, events.size)
        assertTrue(events[0] is ScrobbleTracker.Event.NowPlaying)
        val listen = events[1] as ScrobbleTracker.Event.Listen
        assertEquals(1_000L, listen.listenedAtSec) // when it started, not when it counted
        assertEquals(229_000L, listen.durationMs)
        assertTrue(t.play(120_200, 228_000, 2_000_000).isEmpty())
    }

    @Test
    fun `a pause does not lose the listen`() {
        val t = ScrobbleTracker()
        assertEquals(1, t.play(0, 60_000, 0).size)
        // Paused for ten minutes, position still
        repeat(50) { assertTrue(t.onState(song, false, 60_000, 229_000, 100_000L + it * 12_000).isEmpty()) }
        val after = t.play(60_000, 116_000, 800_000)
        assertEquals(1, after.count { it is ScrobbleTracker.Event.Listen })
    }

    @Test
    fun `seeking ahead does not count as heard`() {
        val t = ScrobbleTracker()
        t.play(0, 10_000, 0)
        // Jump to 3:00 and play to the end: only about 49 s heard after 10 s
        val events = t.play(180_000, 228_800, 50_000)
        assertTrue(events.none { it is ScrobbleTracker.Event.Listen })
    }

    @Test
    fun `a short track never counts`() {
        val t = ScrobbleTracker()
        val events = t.play(0, 25_000, 0, duration = 25_000)
        assertTrue(events.none { it is ScrobbleTracker.Event.Listen })
    }

    @Test
    fun `a long track counts after four minutes`() {
        val t = ScrobbleTracker()
        val events = t.play(0, 240_200, 0, duration = 3_600_000)
        assertEquals(1, events.count { it is ScrobbleTracker.Event.Listen })
    }

    @Test
    fun `repeat one is a second listen`() {
        val t = ScrobbleTracker()
        assertEquals(2, t.play(0, 228_800, 0).size)
        val again = t.play(0, 120_000, 300_000)
        assertEquals(2, again.size)
        assertTrue(again[0] is ScrobbleTracker.Event.NowPlaying)
    }

    @Test
    fun `a new song starts its own count`() {
        val t = ScrobbleTracker()
        t.play(0, 100_000, 0)
        val other = song.copy(id = "other")
        val e = (0L..120_000L step 200).flatMap { t.onState(other, true, it, 229_000, it) }
        assertEquals(listOf("other", "other"), e.map {
            when (it) { is ScrobbleTracker.Event.NowPlaying -> it.song.id; is ScrobbleTracker.Event.Listen -> it.song.id }
        })
    }
}
