package com.lyrenne.desktop.link

import com.lyrenne.desktop.lyrics.LyricLine
import com.lyrenne.desktop.lyrics.LyricsState
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs
import kotlinx.serialization.json.*

/** Who may call a route. See CLAUDE.md "Lyrenne Link" for why READ is open on loopback. */
enum class Access {
    /** Static pages and assets. Open source anyway, and they do nothing without the API. */
    PUBLIC,
    /** What is playing, lyrics, art. Tokenless from this machine (OBS), token from the LAN. */
    READ,
    /** Anything that changes playback, or shows the queue or search. Token, and the remote on. */
    CONTROL,
}

data class Route(val name: String, val access: Access)

object LinkRoutes {
    private val ASSET = Regex("/assets/([a-z0-9-]+\\.(js|css|png|svg))")

    fun resolve(method: String, path: String): Route? {
        val get = method == "GET" || method == "HEAD"
        return when {
            get && (path == "/" || path == "/remote" || path == "/remote/") -> Route("remote", Access.PUBLIC)
            get && (path == "/overlay" || path == "/overlay/") -> Route("overlay", Access.PUBLIC)
            get && path == "/manifest.webmanifest" -> Route("manifest", Access.PUBLIC)
            get && path == "/favicon.ico" -> Route("favicon", Access.PUBLIC)
            get && ASSET.matches(path) -> Route("asset", Access.PUBLIC)
            get && path == "/api/now" -> Route("now", Access.READ)
            get && path == "/api/lyrics" -> Route("lyrics", Access.READ)
            get && path == "/events" -> Route("events", Access.READ)
            get && path == "/art" -> Route("art", Access.READ)
            get && path == "/api/state" -> Route("state", Access.CONTROL)
            get && path == "/api/queue" -> Route("queue", Access.CONTROL)
            get && path == "/api/search" -> Route("search", Access.CONTROL)
            method == "POST" && path == "/api/cmd" -> Route("cmd", Access.CONTROL)
            method == "POST" && path == "/api/play" -> Route("play", Access.CONTROL)
            else -> null
        }
    }

    /** The resource file behind an `/assets/` path, or null when the path is not one. */
    fun assetName(path: String): String? = ASSET.matchEntire(path)?.groupValues?.get(1)

    fun contentType(name: String): String = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "webmanifest" -> "application/manifest+json"
        "json" -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }

    fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { kv ->
            fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
            dec(kv.substringBefore('=')) to dec(kv.substringAfter('=', ""))
        }
}

/** Everything the overlay and the remote show about the current moment, minus the queue. */
data class LinkSnapshot(
    val song: SongInfo?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val shuffle: Boolean,
    val repeat: String,
    val liked: Boolean,
    /** `#rrggbb` from the album art, or null for a grey cover. */
    val accent: String?,
    val index: Int,
    val queueSize: Int,
    val error: String? = null,
)

object LinkJson {
    /**
     * One Server-Sent Event. A payload with line breaks becomes several `data:` lines, which the
     * browser joins back with `\n`; a raw newline would end the event early.
     */
    fun sse(event: String, data: String): String = buildString {
        append("event: ").append(event.filter { it != '\n' && it != '\r' }).append('\n')
        data.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { append("data: ").append(it).append('\n') }
        append('\n')
    }

    /** A comment line: keeps proxies and phones from timing the stream out, and shows nothing. */
    const val SSE_PING = ": ping\n\n"

    /**
     * Search results can carry an empty artist between the real ones ("Daft Punk, , Pharrell"),
     * which reads as stray commas on a stream.
     */
    fun cleanArtist(artist: String): String =
        artist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    fun song(s: SongInfo) = buildJsonObject {
        put("id", s.id)
        put("title", s.title)
        put("artist", cleanArtist(s.artist))
        put("album", s.album)
        put("thumb", s.thumbnailUrl)
        put("durationMs", s.knownDurationMs())
    }

    fun state(s: LinkSnapshot) = buildJsonObject {
        put("song", s.song?.let { song ->
            buildJsonObject {
                song(song).forEach { (k, v) -> put(k, v) }
                // Proxied, so the pages can read its pixels and a phone needs nothing but the PC.
                put("art", "/art?v=${java.net.URLEncoder.encode(song.id, Charsets.UTF_8)}")
            }
        } ?: JsonNull)
        put("playing", s.playing)
        put("positionMs", s.positionMs)
        put("durationMs", s.durationMs)
        put("volume", s.volume)
        put("shuffle", s.shuffle)
        put("repeat", s.repeat)
        put("liked", s.liked)
        put("accent", s.accent)
        put("index", s.index)
        put("queueSize", s.queueSize)
        put("error", s.error)
    }

    fun queue(queue: List<SongInfo>, index: Int) = buildJsonObject {
        put("index", index)
        put("items", JsonArray(queue.map { song(it) }))
    }

    private fun words(line: LyricLine): JsonElement = line.words?.let { ws ->
        JsonArray(ws.map { w -> buildJsonArray { add(w.startMs); add(w.endMs); add(w.text) } })
    } ?: JsonNull

    /**
     * Lyrics for [songId]. Only lyrics that belong to that song are sent: the shared state can
     * still hold the previous track's while the next one's are looked up.
     */
    fun lyrics(songId: String?, ls: LyricsState, offsetMs: Long) = buildJsonObject {
        put("songId", songId)
        val mine = songId != null && ls.songId == songId
        put("loading", !mine || ls.isLoading)
        put("source", if (mine) ls.source else null)
        put("offsetMs", offsetMs)
        val lines = if (mine) ls.lines else null
        put("synced", lines != null)
        put("lines", lines?.let { ls2 ->
            JsonArray(ls2.map { line ->
                buildJsonObject {
                    put("t", line.timeMs)
                    put("text", line.text)
                    put("w", words(line))
                    if (line.background.isNotEmpty()) put("bg", JsonArray(line.background.map { bg ->
                        buildJsonObject { put("t", bg.timeMs); put("text", bg.text); put("w", words(bg)) }
                    }))
                }
            })
        } ?: JsonNull)
        put("plain", if (mine && lines == null) ls.lyrics else null)
    }

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** A song a page sent back (from search or the queue). Null when it is not a plausible one. */
    fun songFrom(o: JsonObject): SongInfo? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!ID.matches(id)) return null
        fun str(k: String, max: Int = 300) = o[k]?.jsonPrimitive?.contentOrNull?.take(max)
        val thumb = str("thumb", 1000)?.takeIf { it.startsWith("https://") }
        val durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
        return SongInfo(
            id = id, title = str("title").orEmpty(), artist = str("artist").orEmpty(),
            thumbnailUrl = thumb, album = str("album"),
            duration = (durationMs / 1000).toInt().takeIf { it > 0 } ?: -1,
        )
    }
}
