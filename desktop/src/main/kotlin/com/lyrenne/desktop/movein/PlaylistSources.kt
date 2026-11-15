package com.lyrenne.desktop.movein

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One song as another service describes it, before it is matched on YouTube Music. */
data class SourceTrack(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
) {
    val artistLine get() = artists.joinToString(", ")
}

data class SourcePlaylist(
    val service: SourceService,
    val name: String,
    val tracks: List<SourceTrack>,
    /** What the service says the playlist holds, when it says. */
    val totalTracks: Int? = null,
    /** Shown above the review: why some songs could not be read, in plain words. */
    val limitNote: String? = null,
)

enum class SourceService(val label: String) {
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music"),
    DEEZER("Deezer"),
    TIDAL("TIDAL"),
    SOUNDCLOUD("SoundCloud"),
    TEXT("your list"),
}

enum class LinkKind { PLAYLIST, ALBUM }

data class SourceLink(val service: SourceService, val kind: LinkKind, val id: String, val url: String)

/**
 * Turns a pasted link into a playlist, using only what each service shows anyone without a login
 * or an API key: embed pages, public page data and Deezer's open API.
 *
 * Each service caps what it shows that way, and the cap is passed on as [SourcePlaylist.limitNote]
 * rather than hidden. Every parser here is a pure function of the page text so the tests run on
 * saved copies of real pages in `src/test/resources/movein/` (captured 2026-11-15).
 */
object PlaylistSources {

    private const val EXPORT_HINT = "To bring over the rest, export the playlist as text or CSV " +
        "(Exportify works for Spotify) and paste it instead."

    // ------------------------------------------------------------------ links

    private val spotify = Regex("""(?:open|play)\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?(playlist|album)/([A-Za-z0-9]{10,})""")
    private val spotifyUri = Regex("""spotify:(playlist|album):([A-Za-z0-9]{10,})""")
    private val apple = Regex("""music\.apple\.com/[a-z]{2}/(playlist|album)/(?:[^/?#]+/)?((?:pl\.[A-Za-z0-9.-]+)|\d+)""")
    private val deezer = Regex("""deezer\.com/(?:[a-z]{2}/)?(playlist|album)/(\d+)""")
    private val tidal = Regex("""tidal\.com/(?:browse/)?(playlist|album)s?/([0-9a-fA-F-]{36}|\d+)""")
    private val soundcloud = Regex("""soundcloud\.com/([^/?#\s]+)/sets/([^/?#\s]+)""")
    /** Share links that only redirect to one of the above. */
    private val shortLinks = Regex("""https?://(?:link\.deezer\.com|deezer\.page\.link|on\.soundcloud\.com|spotify\.link|tidal\.link)/\S+""")

    fun isShortLink(text: String) = shortLinks.containsMatchIn(text.trim())

    /** The link in [text], or null if it is not a link to something this can read. */
    fun detect(text: String): SourceLink? {
        val t = text.trim()
        fun kind(s: String) = if (s == "album") LinkKind.ALBUM else LinkKind.PLAYLIST
        spotify.find(t)?.let { return SourceLink(SourceService.SPOTIFY, kind(it.groupValues[1]), it.groupValues[2], t) }
        spotifyUri.find(t)?.let { return SourceLink(SourceService.SPOTIFY, kind(it.groupValues[1]), it.groupValues[2], t) }
        apple.find(t)?.let { return SourceLink(SourceService.APPLE_MUSIC, kind(it.groupValues[1]), it.groupValues[2], t) }
        deezer.find(t)?.let { return SourceLink(SourceService.DEEZER, kind(it.groupValues[1]), it.groupValues[2], t) }
        tidal.find(t)?.let { return SourceLink(SourceService.TIDAL, kind(it.groupValues[1]), it.groupValues[2], t) }
        soundcloud.find(t)?.let { return SourceLink(SourceService.SOUNDCLOUD, LinkKind.PLAYLIST, "${it.groupValues[1]}/sets/${it.groupValues[2]}", t) }
        return null
    }

    /** True when [text] reads as a single URL rather than a pasted list of songs. */
    fun looksLikeLink(text: String): Boolean {
        val t = text.trim()
        return !t.contains('\n') && (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("spotify:"))
    }

    // ------------------------------------------------------------------ fetching

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    class FetchException(message: String) : Exception(message)

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> {
        val request = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) throw FetchException("That playlist wasn't found. Is it public?")
            if (!r.isSuccessful) throw FetchException("The service answered with an error (${r.code}). Try again in a minute.")
            return r.request.url.toString() to (r.body?.string() ?: "")
        }
    }

    /** Follows a share link (link.deezer.com and the like) to the address it stands for. */
    fun resolveShortLink(url: String): String = get(url.trim()).first

    /** Blocking network read of [link]. */
    fun fetch(link: SourceLink): SourcePlaylist = when (link.service) {
        SourceService.SPOTIFY ->
            parseSpotifyEmbed(get("https://open.spotify.com/embed/${link.kind.name.lowercase()}/${link.id}").second)
        SourceService.APPLE_MUSIC -> parseAppleMusic(get(link.url.substringBefore('?')).second)
        SourceService.DEEZER -> fetchDeezer(link)
        SourceService.TIDAL ->
            parseTidalEmbed(get("https://embed.tidal.com/${link.kind.name.lowercase()}s/${link.id}").second)
        SourceService.SOUNDCLOUD -> fetchSoundCloud(link)
        SourceService.TEXT -> error("text has no link")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fetchDeezer(link: SourceLink): SourcePlaylist {
        val type = link.kind.name.lowercase()
        val meta = json.parseToJsonElement(get("https://api.deezer.com/$type/${link.id}").second).jsonObject
        deezerError(meta)
        val pages = ArrayList<String>()
        var next: String? = "https://api.deezer.com/$type/${link.id}/tracks?index=0&limit=100"
        // 2000 songs is past any real playlist; the cap only guards against a loop.
        while (next != null && pages.size < 20) {
            val page = get(next).second
            pages += page
            next = (json.parseToJsonElement(page).jsonObject["next"] as? JsonPrimitive)?.contentOrNull
        }
        return parseDeezer(meta.toString(), pages)
    }

    private fun deezerError(o: JsonObject) {
        val err = o["error"] as? JsonObject ?: return
        val code = (err["code"] as? JsonPrimitive)?.longOrNull
        throw FetchException(if (code == 800L) "That playlist wasn't found. Is it public?" else "Deezer refused the request. Try again in a minute.")
    }

    private fun fetchSoundCloud(link: SourceLink): SourcePlaylist {
        val page = get("https://soundcloud.com/${link.id}").second
        val first = parseSoundCloud(page)
        val missing = first.missingIds
        if (missing.isEmpty() || first.clientId == null) return first.playlist
        // The page holds full details for only the first few songs and bare ids for the rest.
        // The ids are filled in the way SoundCloud's own web player does it, with the public
        // client id that very page hands out. Nothing is stored or shipped.
        val filled = HashMap<Long, SourceTrack>()
        runCatching {
            for (chunk in missing.chunked(50)) {
                val body = get(
                    "https://api-v2.soundcloud.com/tracks?ids=${chunk.joinToString(",")}&client_id=${first.clientId}",
                    mapOf("Origin" to "https://soundcloud.com", "Referer" to "https://soundcloud.com/")
                ).second
                (json.parseToJsonElement(body) as? JsonArray)?.forEach { t ->
                    val o = t as? JsonObject ?: return@forEach
                    val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    soundCloudTrack(o)?.let { filled[id] = it }
                }
            }
        }
        val tracks = first.order.mapNotNull { id -> first.known[id] ?: filled[id] }
        val short = first.order.size - tracks.size
        return first.playlist.copy(
            tracks = tracks,
            limitNote = if (short > 0) "SoundCloud only showed $short of the songs' details. $EXPORT_HINT" else null
        )
    }

    // ------------------------------------------------------------------ parsers

    /** Spotify's embed player page. It lists at most the first 100 songs. */
    fun parseSpotifyEmbed(html: String): SourcePlaylist {
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw FetchException("Spotify didn't share this playlist. Is it public?")
        val entity = json.parseToJsonElement(data).path("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: throw FetchException("Spotify didn't share this playlist. Is it public?")
        val isAlbum = entity.str("type") == "album"
        val list = entity["trackList"] as? JsonArray ?: JsonArray(emptyList())
        val tracks = list.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            SourceTrack(
                title = title,
                artists = splitArtists(o.str("subtitle").orEmpty()),
                album = if (isAlbum) entity.str("name") else null,
                durationMs = o.long("duration")
            )
        }
        return SourcePlaylist(
            service = SourceService.SPOTIFY,
            name = entity.str("name") ?: entity.str("title") ?: "Spotify playlist",
            tracks = tracks,
            limitNote = if (!isAlbum && tracks.size >= 100)
                "Spotify only shows the first 100 songs of a playlist without a login. $EXPORT_HINT" else null
        )
    }

    /** A music.apple.com playlist or album page. */
    fun parseAppleMusic(html: String): SourcePlaylist {
        val ld = Regex("""<script id=schema:music-(?:playlist|album) type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val server = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { json.parseToJsonElement(it) }
            ?: throw FetchException("Apple Music didn't share this playlist. Is it public?")
        val isAlbum = ld?.str("@type") == "MusicAlbum"
        val albumName = if (isAlbum) ld?.str("name") else null
        val tracks = ArrayList<SourceTrack>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    val id = e.str("id")
                    if (id != null && id.startsWith("track-list - ")) {
                        (e["items"] as? JsonArray)?.forEach { item ->
                            val o = item as? JsonObject ?: return@forEach
                            val title = o.str("title") ?: return@forEach
                            val artist = o.str("artistName")
                                ?: (o["subtitleLinks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("title") }?.joinToString(", ")
                                ?: ""
                            tracks += SourceTrack(
                                title = title,
                                artists = splitArtists(artist),
                                album = albumName ?: ((o["tertiaryLinks"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("title"),
                                durationMs = o.long("duration")
                            )
                        }
                    } else e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(server)
        val total = ld?.long("numTracks")?.toInt() ?: (ld?.get("tracks") as? JsonArray)?.size
        return SourcePlaylist(
            service = SourceService.APPLE_MUSIC,
            name = ld?.str("name") ?: "Apple Music playlist",
            tracks = tracks,
            totalTracks = total,
            limitNote = if (total != null && total > tracks.size)
                "Apple Music only shows the first ${tracks.size} of $total songs without a login. $EXPORT_HINT" else null
        )
    }

    /** Deezer's open API: the playlist or album, then its track pages in order. */
    fun parseDeezer(metaJson: String, trackPages: List<String>): SourcePlaylist {
        val meta = json.parseToJsonElement(metaJson).jsonObject
        val isAlbum = meta.str("type") == "album"
        val pages = trackPages.ifEmpty { listOf((meta["tracks"] ?: JsonObject(emptyMap())).toString()) }
        val tracks = pages.flatMap { page ->
            ((json.parseToJsonElement(page) as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                SourceTrack(
                    title = title,
                    artists = listOfNotNull((o["artist"] as? JsonObject)?.str("name")).flatMap(::splitArtists),
                    album = (o["album"] as? JsonObject)?.str("title") ?: meta.str("title").takeIf { isAlbum },
                    durationMs = o.long("duration")?.times(1000)
                )
            }
        }
        val total = meta.long("nb_tracks")?.toInt()
        return SourcePlaylist(
            service = SourceService.DEEZER,
            name = meta.str("title") ?: "Deezer playlist",
            tracks = tracks,
            totalTracks = total,
            limitNote = if (total != null && total > tracks.size)
                "Deezer only shared ${tracks.size} of $total songs." else null
        )
    }

    /** TIDAL's embed player page. It lists the first 50 songs and gives no total. */
    fun parseTidalEmbed(html: String): SourcePlaylist {
        val albums = HashMap<String, String>()
        Regex("""window\.tidalMediaSessionMetadata=(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            runCatching { json.parseToJsonElement(m.groupValues[1]) as JsonObject }.getOrNull()?.forEach { (id, v) ->
                (v as? JsonObject)?.str("album")?.let { albums[id] = it }
            }
        }
        val items = Regex("""<list-item[^>]*product-id="(\d+)"[^>]*product-type="(?:track|video)"[^>]*>(.*?)</list-item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).toList()
        if (items.isEmpty()) throw FetchException("TIDAL didn't share this playlist. Is it public?")
        val tracks = items.mapNotNull { m ->
            val body = m.groupValues[2]
            val title = Regex("""<span slot="title">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescapeHtml)
                ?: return@mapNotNull null
            val artistSlot = Regex("""<span slot="artist">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
            val artists = Regex("""<a [^>]*>(.*?)</a>""").findAll(artistSlot).map { unescapeHtml(it.groupValues[1]) }.toList()
                .ifEmpty { splitArtists(unescapeHtml(artistSlot.replace(Regex("<[^>]+>"), ""))) }
            val duration = Regex("""<time slot="duration">([\d:]+)</time>""").find(body)?.groupValues?.get(1)?.let(::clockToMs)
            SourceTrack(title, artists, albums[m.groupValues[1]], duration)
        }
        val name = Regex("""<h1 class="media-album"[^>]*>(?:<a[^>]*>)?(.*?)(?:</a>)?</h1>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim()
        return SourcePlaylist(
            service = SourceService.TIDAL,
            name = name ?: "TIDAL playlist",
            tracks = tracks,
            limitNote = if (tracks.size >= 50) "TIDAL's public player shows at most 50 songs. If yours has more, export it as text or CSV and paste it instead." else null
        )
    }

    internal data class SoundCloudPage(
        val playlist: SourcePlaylist,
        val order: List<Long>,
        val known: Map<Long, SourceTrack>,
        val clientId: String?,
    ) {
        val missingIds get() = order.filter { it !in known }
    }

    /** A soundcloud.com set page: the first few songs in full, the rest as ids. */
    internal fun parseSoundCloud(html: String): SoundCloudPage {
        val data = Regex("""window\.__sc_hydration = (\[.*?\]);</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw FetchException("SoundCloud didn't share this playlist. Is it public?")
        val all = json.parseToJsonElement(data) as JsonArray
        fun part(name: String) = all.firstOrNull { (it as? JsonObject)?.str("hydratable") == name }?.let { (it as JsonObject)["data"] as? JsonObject }
        val set = part("playlist") ?: throw FetchException("SoundCloud didn't share this playlist. Is it public?")
        val order = ArrayList<Long>()
        val known = HashMap<Long, SourceTrack>()
        (set["tracks"] as? JsonArray)?.forEach { t ->
            val o = t as? JsonObject ?: return@forEach
            val id = o.long("id") ?: return@forEach
            order += id
            soundCloudTrack(o)?.let { known[id] = it }
        }
        val total = set.long("track_count")?.toInt()
        val playlist = SourcePlaylist(
            service = SourceService.SOUNDCLOUD,
            name = set.str("title") ?: "SoundCloud playlist",
            tracks = order.mapNotNull { known[it] },
            totalTracks = total,
            limitNote = if (order.size > known.size)
                "SoundCloud only showed ${known.size} of the songs' details. $EXPORT_HINT" else null
        )
        return SoundCloudPage(playlist, order, known, part("apiClient")?.str("id"))
    }

    private fun soundCloudTrack(o: JsonObject): SourceTrack? {
        var title = o.str("title") ?: return null
        val publisher = (o["publisher_metadata"] as? JsonObject)?.str("artist")?.takeIf { it.isNotBlank() }
        var artists = publisher?.let(::splitArtists)
        if (artists == null) {
            // Uploads are usually titled "Artist - Title"; failing that, the uploader is the artist.
            val dash = title.indexOf(" - ")
            if (dash > 0) {
                artists = splitArtists(title.substring(0, dash))
                title = title.substring(dash + 3)
            } else artists = listOfNotNull((o["user"] as? JsonObject)?.str("username"))
        }
        return SourceTrack(title, artists, null, o.long("duration"))
    }

    // ------------------------------------------------------------------ helpers

    /** "A, B & C" and "A feat. B" into names. Only for artist strings, never titles. */
    fun splitArtists(s: String): List<String> =
        s.replace('\u00a0', ' ')
            .split(Regex("""\s*(?:,|&|\bfeat\b\.?|\bft\b\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    internal fun clockToMs(s: String): Long? {
        val parts = s.trim().split(':').map { it.toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()

    private fun JsonElement.path(vararg keys: String): JsonElement? {
        var e: JsonElement? = this
        for (k in keys) e = (e as? JsonObject)?.get(k)
        return e
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
}
