package com.lyrenne.desktop.link

import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricWord
import com.lyrenne.desktop.lyrics.LyricsState
import com.lyrenne.desktop.playback.SongInfo
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProtocolTest {
    private val song = SongInfo(
        id = "dQw4w9WgXcQ", title = "Never Gonna", artist = "Rick", thumbnailUrl = "https://lh3.example/a=w120-h120",
        album = "Whenever", duration = 213,
    )

    @Test
    fun `routes map to the right access level`() {
        fun access(m: String, p: String) = LinkRoutes.resolve(m, p)?.access
        assertEquals(Access.PUBLIC, access("GET", "/overlay"))
        assertEquals(Access.PUBLIC, access("GET", "/remote"))
        assertEquals(Access.PUBLIC, access("GET", "/"))
        assertEquals(Access.PUBLIC, access("GET", "/assets/overlay.js"))
        assertEquals(Access.PUBLIC, access("HEAD", "/assets/icon-192.png"))
        assertEquals(Access.READ, access("GET", "/api/now"))
        assertEquals(Access.READ, access("GET", "/api/lyrics"))
        assertEquals(Access.READ, access("GET", "/events"))
        assertEquals(Access.READ, access("GET", "/art"))
        assertEquals(Access.CONTROL, access("GET", "/api/state"))
        assertEquals(Access.CONTROL, access("GET", "/api/queue"))
        assertEquals(Access.CONTROL, access("GET", "/api/search"))
        assertEquals(Access.CONTROL, access("POST", "/api/cmd"))
        assertEquals(Access.CONTROL, access("POST", "/api/play"))
    }

    @Test
    fun `unknown paths, wrong methods and path tricks resolve to nothing`() {
        assertNull(LinkRoutes.resolve("GET", "/api/cmd"))
        assertNull(LinkRoutes.resolve("POST", "/api/now"))
        assertNull(LinkRoutes.resolve("DELETE", "/api/cmd"))
        assertNull(LinkRoutes.resolve("OPTIONS", "/api/cmd"))
        assertNull(LinkRoutes.resolve("GET", "/assets/../credentials.json"))
        assertNull(LinkRoutes.resolve("GET", "/assets/sub/overlay.js"))
        assertNull(LinkRoutes.resolve("GET", "/assets/overlay.html"))
        assertNull(LinkRoutes.resolve("GET", "/assets/Overlay.JS"))
        assertNull(LinkRoutes.resolve("GET", "/headless"))
        assertEquals("remote.css", LinkRoutes.assetName("/assets/remote.css"))
    }

    @Test
    fun `query strings decode`() {
        assertEquals(mapOf("q" to "rick astley", "t" to "a-b_c"), LinkRoutes.parseQuery("q=rick+astley&t=a-b_c"))
        assertEquals(mapOf("q" to "日本"), LinkRoutes.parseQuery("q=%E6%97%A5%E6%9C%AC"))
        assertEquals(emptyMap<String, String>(), LinkRoutes.parseQuery(null))
        assertEquals(mapOf("x" to ""), LinkRoutes.parseQuery("x"))
    }

    @Test
    fun `an SSE event splits multi-line data so the event cannot end early`() {
        assertEquals("event: state\ndata: {\"a\":1}\n\n", LinkJson.sse("state", "{\"a\":1}"))
        assertEquals("event: x\ndata: one\ndata: two\ndata: three\n\n", LinkJson.sse("x", "one\ntwo\r\nthree"))
        // A newline smuggled into the event name must not start a new field.
        assertEquals("event: stateinjected\ndata: d\n\n", LinkJson.sse("state\ninjected", "d"))
        assertTrue(LinkJson.SSE_PING.startsWith(":"))
    }

    @Test
    fun `JSON with line breaks round trips through one SSE event`() {
        val payload = buildJsonObject { put("text", "line one\nline two") }.toString()
        val event = LinkJson.sse("lyrics", payload)
        val data = event.lines().filter { it.startsWith("data: ") }.joinToString("\n") { it.removePrefix("data: ") }
        assertEquals(payload, data)
    }

    @Test
    fun `state carries the song, proxied art and playback fields`() {
        val s = LinkJson.state(
            LinkSnapshot(song, playing = true, positionMs = 1234, durationMs = 213_000, volume = 0.5f, shuffle = true,
                repeat = "ALL", liked = true, accent = "#ff8800", index = 2, queueSize = 5)
        )
        val songJson = s["song"]!!.jsonObject
        assertEquals("dQw4w9WgXcQ", songJson["id"]!!.jsonPrimitive.content)
        assertEquals("Never Gonna", songJson["title"]!!.jsonPrimitive.content)
        assertEquals("/art?v=dQw4w9WgXcQ", songJson["art"]!!.jsonPrimitive.content)
        assertEquals(213_000L, songJson["durationMs"]!!.jsonPrimitive.long)
        assertEquals(true, s["playing"]!!.jsonPrimitive.boolean)
        assertEquals(1234L, s["positionMs"]!!.jsonPrimitive.long)
        assertEquals(0.5f, s["volume"]!!.jsonPrimitive.float)
        assertEquals("ALL", s["repeat"]!!.jsonPrimitive.content)
        assertEquals("#ff8800", s["accent"]!!.jsonPrimitive.content)
        assertEquals(2, s["index"]!!.jsonPrimitive.int)
    }

    @Test
    fun `state with nothing playing has a null song`() {
        val s = LinkJson.state(LinkSnapshot(null, false, 0, 0, 1f, false, "OFF", false, null, -1, 0))
        assertEquals(JsonNull, s["song"])
        assertEquals(JsonNull, s["accent"])
    }

    @Test
    fun `lyrics carry word timing and backing vocals`() {
        val lines = listOf(
            LyricLine(1000, "Hello there", listOf(LyricWord("Hello", 1000, 1400), LyricWord("there", 1400, 2000)),
                background = listOf(LyricLine(1200, "(hey)"))),
            LyricLine(3000, "Plain line"),
        )
        val json = LinkJson.lyrics(song.id, LyricsState(songId = song.id, lyrics = "raw", lines = lines, source = "BetterLyrics"), 250)
        assertEquals(false, json["loading"]!!.jsonPrimitive.boolean)
        assertEquals(true, json["synced"]!!.jsonPrimitive.boolean)
        assertEquals(250L, json["offsetMs"]!!.jsonPrimitive.long)
        assertEquals("BetterLyrics", json["source"]!!.jsonPrimitive.content)
        val first = json["lines"]!!.jsonArray[0].jsonObject
        assertEquals(1000L, first["t"]!!.jsonPrimitive.long)
        val w = first["w"]!!.jsonArray
        assertEquals(listOf("1000", "1400", "Hello"), w[0].jsonArray.map { it.jsonPrimitive.content })
        assertEquals("(hey)", first["bg"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["lines"]!!.jsonArray[1].jsonObject["w"])
        assertEquals(JsonNull, json["plain"])
    }

    @Test
    fun `another song's lyrics are never sent as this song's`() {
        val stale = LyricsState(songId = "other", lyrics = "x", lines = listOf(LyricLine(0, "old")), source = "LrcLib")
        val json = LinkJson.lyrics(song.id, stale, 0)
        assertEquals(true, json["loading"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, json["lines"])
        assertEquals(JsonNull, json["source"])
    }

    @Test
    fun `unsynced lyrics come through as plain text`() {
        val json = LinkJson.lyrics(song.id, LyricsState(songId = song.id, lyrics = "a\nb", lines = null, source = "YouTube"), 0)
        assertEquals(false, json["synced"]!!.jsonPrimitive.boolean)
        assertEquals("a\nb", json["plain"]!!.jsonPrimitive.content)
    }

    @Test
    fun `songs sent back by a page are validated`() {
        val ok = LinkJson.songFrom(buildJsonObject {
            put("id", "abc_DEF-123"); put("title", "T"); put("artist", "A"); put("durationMs", 61_000)
            put("thumb", "https://i.ytimg.com/x.jpg")
        })!!
        assertEquals("abc_DEF-123", ok.id)
        assertEquals(61, ok.duration)
        assertEquals("https://i.ytimg.com/x.jpg", ok.thumbnailUrl)
        assertNull(LinkJson.songFrom(buildJsonObject { put("id", "../../etc") }))
        assertNull(LinkJson.songFrom(buildJsonObject { put("title", "no id") }))
        // Only https art: a file: or http: URL from a page is dropped, the song still plays.
        assertNull(LinkJson.songFrom(buildJsonObject { put("id", "abc"); put("thumb", "file:///C:/x") })!!.thumbnailUrl)
    }

    @Test
    fun `overlay query leaves defaults out`() {
        assertEquals("layout=card", OverlayConfig().query())
        assertEquals(
            "layout=lyrics&pos=bc&scale=1.5&accent=ff0000&font=60",
            OverlayConfig(layout = "lyrics", position = "bc", scale = 1.5f, accent = "ff0000", fontSize = 60).query()
        )
        assertEquals("layout=card&scale=0.75&lyrics=0", OverlayConfig(scale = 0.75f, cardLyrics = false).query())
    }

    @Test
    fun `empty artists between commas are dropped`() {
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", LinkJson.cleanArtist("Daft Punk, ,, Pharrell Williams, Nile Rodgers"))
        assertEquals("", LinkJson.cleanArtist(" , "))
        assertEquals("Rick", LinkJson.cleanArtist("Rick"))
    }

    @Test
    fun `big art asks YouTube for a larger size`() {
        assertEquals("https://lh3.example/a=w544-h544-l90", LyrenneLink.bigArt("https://lh3.example/a=w120-h120-l90"))
        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", LyrenneLink.bigArt("https://i.ytimg.com/vi/x/hqdefault.jpg"))
    }

    @Test
    fun `the pairing QR code encodes`() {
        val qr = QrCode.encode("http://192.168.1.20:48712/remote#t=" + LinkSecurity.newToken())
        assertTrue(qr.size in 21..61)
        // Finder pattern corners are always dark.
        assertTrue(qr[0, 0] && qr[qr.size - 1, 0] && qr[0, qr.size - 1])
    }
}
