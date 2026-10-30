package com.lyrenne.desktop.headless

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.SingleInstance
import com.lyrenne.desktop.applyNetworkPreferences
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.auth.BrowserLoginHelper
import com.lyrenne.desktop.auth.CookieExtractResult
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.integration.LastFmManager
import com.lyrenne.desktop.integration.SystemMediaSession
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.RepeatMode
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs
import com.lyrenne.desktop.playback.toPlayerSongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.LibrarySync
import com.lyrenne.desktop.sync.setLiked
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Lyrenne without a window, driven over a localhost HTTP API. Built for the Steam Deck plugin,
 * whose Python backend starts this process and whose Quick Access panel is the only UI.
 *
 * The port and a per-run token go in `headless.json` in the data dir (owner-only), and every
 * request must carry the token, so other local users and web pages cannot drive the player.
 */
/**
 * Entry point for the Steam Deck build, whose launcher is pointed here instead of MainKt: that build
 * ships without Compose, and the JVM cannot even load MainKt without it.
 */
fun main(args: Array<String>) {
    val log = com.lyrenne.desktop.installCrashReporting()
    try {
        Headless.run(args)
    } catch (e: Throwable) {
        com.lyrenne.desktop.reportFatal(e, log)
        exitProcess(1)
    }
}

object Headless {
    const val DEFAULT_PORT = 48711
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var player: DesktopPlayer
    @Volatile private var login: String? = null

    fun run(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val port = args.getOrNull(args.indexOf("--port") + 1)?.toIntOrNull() ?: DEFAULT_PORT

        // Opening the windowed app (Desktop Mode) pokes this process: step aside for it.
        SingleInstance.onActivate = { shutdown() }
        if (!SingleInstance.claim()) {
            System.err.println("Lyrenne is already running")
            exitProcess(2)
        }

        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        applyNetworkPreferences()
        BrowserLoginHelper.pruneLoginProfile()
        DownloadManager.restoreQueue()

        player = DesktopPlayer()
        player.ensureVlcInitialized()
        runCatching { SystemMediaSession.initialize(player) }
        AuthManager.initialize()
        runBlocking { player.restoreQueue() }
        player.setVolume(PreferencesManager.preferences.value.volume)
        LastFmManager.initialize(player)

        scope.launch {
            if (PreferencesManager.preferences.value.autoSyncOnStartup) LibrarySync.syncLibrary()
            // Same as the windowed app: a sign-in (or account switch) resyncs the library.
            AuthManager.authState.map { if (it.isLoggedIn) it.accountId else null }
                .distinctUntilChanged().drop(1)
                .collect { if (it != null) LibrarySync.resync() }
        }

        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        writeHandshake(port, token)
        startServer(port, token)
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { player.release() } })
        Timber.i("Headless Lyrenne listening on 127.0.0.1:$port")
        CountDownLatch(1).await()
    }

    private fun shutdown() {
        Timber.i("Headless Lyrenne shutting down")
        thread3sWatchdog()
        runCatching { player.release() }
        runCatching { SystemMediaSession.release() }
        exitProcess(0)
    }

    // ponytail: same fixed 3 s watchdog as the windowed quit, VLC teardown can hang
    private fun thread3sWatchdog() = Thread {
        Thread.sleep(3_000); Runtime.getRuntime().halt(0)
    }.apply { isDaemon = true; start() }

    private fun writeHandshake(port: Int, token: String) {
        val file = File(AppPaths.dataDir, "headless.json")
        file.delete()
        val path = file.toPath()
        runCatching {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.onFailure { file.createNewFile() } // Windows has no POSIX permissions
        file.writeText(buildJsonObject { put("port", port); put("token", token); put("pid", ProcessHandle.current().pid()) }.toString())
        file.deleteOnExit()
    }

    private fun startServer(port: Int, token: String) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.executor = Executors.newFixedThreadPool(4)
        val expected = token.toByteArray()
        server.createContext("/") { ex ->
            try {
                val given = ex.requestHeaders.getFirst("X-Lyrenne-Token").orEmpty().toByteArray()
                if (!MessageDigest.isEqual(given, expected)) return@createContext respond(ex, 401, error("bad token"))
                val query = parseQuery(ex.requestURI.rawQuery)
                val body = ex.requestBody.readBytes().decodeToString().takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                val result = runBlocking { route(ex.requestMethod, ex.requestURI.path, query, body) }
                respond(ex, if (result == null) 404 else 200, result ?: error("not found"))
            } catch (e: Exception) {
                Timber.e(e, "Headless API ${ex.requestURI.path} failed")
                respond(ex, 500, error(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
        server.start()
    }

    private fun respond(ex: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { kv -> kv.substringBefore('=').let(::decode) to kv.substringAfter('=', "").let(::decode) }

    private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    private fun error(message: String) = buildJsonObject { put("error", message) }
    private fun ok() = buildJsonObject { put("ok", true) }

    private suspend fun route(method: String, path: String, q: Map<String, String>, body: JsonObject): JsonElement? =
        when ("$method $path") {
            "GET /state" -> state()
            "GET /lyrics" -> lyrics()
            "GET /home" -> home()
            "GET /search" -> search(q["q"].orEmpty(), q["type"] ?: "songs")
            "GET /library/liked" -> list(dbSongs(DatabaseHelper.getLikedSongsOnce()).map { songJson(it) })
            "GET /library/playlists" -> list(DatabaseHelper.getAllPlaylistsOnce().map { p ->
                buildJsonObject {
                    put("type", "playlist"); put("id", p.browseId ?: "local:${p.id}"); put("title", p.name)
                    put("subtitle", p.remoteSongCount?.let { "$it songs" } ?: "Playlist"); put("thumb", p.thumbnailUrl)
                }
            })
            "GET /library/downloads" -> list(dbSongs(DatabaseHelper.getDownloadedSongsOnce()).map { songJson(it) })
            "GET /collection" -> collection(q["type"].orEmpty(), q["id"].orEmpty())
            "GET /artist" -> artist(q["id"].orEmpty())
            "POST /play" -> play(body)
            "POST /cmd" -> command(body)
            "POST /login" -> startLogin(body["external"]?.jsonPrimitive?.booleanOrNull == true)
            "GET /login/browser" -> BrowserLoginHelper.loginCommand()?.let { (name, command) ->
                buildJsonObject { put("name", name); put("command", JsonArray(command.map { JsonPrimitive(it) })) }
            } ?: error("No browser found (Lyrenne looks for ${BrowserLoginHelper.supportedBrowserNames}).")
            "POST /login/finish" -> { BrowserLoginHelper.closeLoginBrowser(); ok() }
            "POST /logout" -> { AuthManager.logout(); ok() }
            "POST /quit" -> { scope.launch { delay(100); shutdown() }; ok() }
            else -> null
        }

    // --- Reads ---

    private fun state(): JsonObject {
        val s = player.state.value
        val auth = AuthManager.authState.value
        val song = s.currentSong
        return buildJsonObject {
            put("playing", s.isPlaying)
            put("song", song?.let { songJson(it) } ?: JsonNull)
            put("liked", song?.let { DatabaseHelper.getLikedSongsOnce().any { l -> l.id == it.id } } ?: false)
            put("positionMs", s.position)
            put("durationMs", if (s.duration > 0) s.duration else song?.knownDurationMs() ?: 0)
            put("volume", PreferencesManager.preferences.value.volume)
            put("shuffle", s.shuffleEnabled)
            put("repeat", s.repeatMode.name)
            put("index", s.currentIndex)
            put("queue", JsonArray(s.queue.map { songJson(it) }))
            put("error", s.error)
            put("vlc", s.vlcAvailable)
            put("sleepEndsAt", player.sleepTimer.value?.endsAtMillis)
            put("sleepEndOfTrack", player.sleepTimer.value?.endOfTrack ?: false)
            put("account", if (auth.isLoggedIn) auth.accountInfo?.name ?: "Signed in" else null)
            put("login", login)
            put("downloaded", song?.let { DownloadManager.isDownloaded(it.id) } ?: false)
        }
    }

    private suspend fun lyrics(): JsonObject {
        val song = player.state.value.currentSong ?: return buildJsonObject { put("songId", null) }
        val ls = LyricsManager.state.value
        if (ls.songId != song.id) {
            // Same rule as LyricsPanel: only trust VLC's duration once it belongs to this song.
            val durationMs = withTimeoutOrNull(3_000) {
                player.state.first { it.currentSong?.id == song.id && it.duration > 0 }.duration
            } ?: song.knownDurationMs()
            LyricsManager.fetchLyrics(song.id, song.title, song.artist, (durationMs / 1000).toInt(), song.album)
            return buildJsonObject { put("songId", song.id); put("loading", true) }
        }
        return buildJsonObject {
            put("songId", song.id)
            put("loading", ls.isLoading)
            put("source", ls.source)
            put("plain", ls.lyrics)
            put("lines", ls.lines?.let { lines -> JsonArray(lines.map { buildJsonObject { put("t", it.timeMs); put("text", it.text) } }) } ?: JsonNull)
        }
    }

    private suspend fun home(): JsonObject {
        val page = YouTube.home().getOrThrow()
        return buildJsonObject {
            put("sections", JsonArray(page.sections.map { s ->
                buildJsonObject { put("title", s.title); put("items", JsonArray(s.items.mapNotNull { itemJson(it) })) }
            }.filter { (it["items"] as JsonArray).isNotEmpty() }))
        }
    }

    private suspend fun search(query: String, type: String): JsonObject {
        val filter = when (type) {
            "albums" -> YouTube.SearchFilter.FILTER_ALBUM
            "playlists" -> YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST
            "artists" -> YouTube.SearchFilter.FILTER_ARTIST
            else -> YouTube.SearchFilter.FILTER_SONG
        }
        val result = YouTube.search(query, filter).getOrThrow()
        return list(result.items.mapNotNull { itemJson(it) })
    }

    private suspend fun collection(type: String, id: String): JsonObject {
        val (title, songs) = collectionSongs(type, id)
        return buildJsonObject { put("title", title); put("items", JsonArray(songs.map { songJson(it) })) }
    }

    private suspend fun collectionSongs(type: String, id: String): Pair<String, List<SongInfo>> = when {
        type == "album" -> YouTube.album(id).getOrThrow().let { it.album.title to it.songs.map { s -> s.toPlayerSongInfo() } }
        type == "liked" -> "Liked songs" to dbSongs(DatabaseHelper.getLikedSongsOnce())
        id.startsWith("local:") -> {
            val localId = id.removePrefix("local:")
            val name = DatabaseHelper.getAllPlaylistsOnce().firstOrNull { it.id == localId }?.name ?: "Playlist"
            name to dbSongs(DatabaseHelper.getSongsForPlaylist(localId).first())
        }
        else -> {
            val page = YouTube.playlist(id.removePrefix("VL")).getOrThrow()
            val songs = page.songs.toMutableList()
            var cont = page.songsContinuation
            // ponytail: caps big playlists at ~10 pages (about 1000 songs)
            repeat(10) {
                val c = cont ?: return@repeat
                val next = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                songs += next.songs; cont = next.continuation
            }
            page.playlist.title to songs.map { it.toPlayerSongInfo() }
        }
    }

    private suspend fun artist(id: String): JsonObject {
        val page = YouTube.artist(id).getOrThrow()
        return buildJsonObject {
            put("title", page.artist.title)
            put("thumb", page.artist.thumbnail)
            put("sections", JsonArray(page.sections.map { s ->
                buildJsonObject { put("title", s.title); put("items", JsonArray(s.items.mapNotNull { itemJson(it) })) }
            }))
        }
    }

    // --- Writes ---

    /** `{songs:[...], index}` plays that list; `{type, id, index?, shuffle?}` fetches a collection first. */
    private suspend fun play(body: JsonObject): JsonObject {
        val songs = body["songs"]?.jsonArray?.map { songFromJson(it.jsonObject) }
            ?: collectionSongs(body.str("type") ?: "playlist", body.str("id").orEmpty()).second
        if (songs.isEmpty()) return error("nothing to play")
        val index = body["index"]?.jsonPrimitive?.intOrNull ?: 0
        if (body["radio"]?.jsonPrimitive?.booleanOrNull == true) {
            player.startRadio(songs[index])
        } else {
            player.playQueue(songs, index.coerceIn(songs.indices))
            if (body["shuffle"]?.jsonPrimitive?.booleanOrNull == true && !player.state.value.shuffleEnabled) player.toggleShuffle()
        }
        return ok()
    }

    private suspend fun command(body: JsonObject): JsonObject {
        val value = body["value"]
        when (body.str("op")) {
            "toggle" -> player.togglePlayPause()
            "pause" -> player.pause()
            "play" -> player.play()
            "next" -> player.playNext()
            "prev" -> player.playPrevious()
            "seek" -> player.seekTo(value!!.jsonPrimitive.long)
            "volume" -> {
                val v = value!!.jsonPrimitive.float.coerceIn(0f, 1f)
                player.setVolume(v); PreferencesManager.setVolume(v)
            }
            "shuffle" -> player.toggleShuffle()
            "repeat" -> player.setRepeatMode(RepeatMode.valueOf(value!!.jsonPrimitive.content))
            "jump" -> player.playAtIndex(value!!.jsonPrimitive.int)
            "remove" -> player.removeFromQueue(value!!.jsonPrimitive.int)
            "clear" -> player.clearQueue()
            "enqueue" -> player.addToQueue(songFromJson(value!!.jsonObject))
            "playNext" -> player.addToQueueNext(songFromJson(value!!.jsonObject))
            "radio" -> player.state.value.currentSong?.let { player.startRadio(it) }
            "like" -> player.state.value.currentSong?.let {
                setLiked(it, value!!.jsonPrimitive.boolean, DownloadManager.isDownloaded(it.id))
            }
            "download" -> player.state.value.currentSong?.let { DownloadManager.queueDownload(it) }
            // Batch edits from the library's select mode; each takes {songs: [...]}.
            "downloadAll" -> DownloadManager.queueDownloads(songsOf(value))
            "deleteDownloads" -> songsOf(value).forEach { DownloadManager.deleteDownload(it.id) }
            "unlikeAll" -> songsOf(value).forEach { setLiked(it, false, DownloadManager.isDownloaded(it.id)) }
            "enqueueAll" -> songsOf(value).forEach { player.addToQueue(it) }
            "downloadQueue" -> DownloadManager.queueDownloads(player.state.value.queue)
            "sleep" -> when (val minutes = value!!.jsonPrimitive.int) {
                0 -> player.cancelSleepTimer()
                -1 -> player.setSleepEndOfTrack()
                else -> player.startSleepTimer(minutes)
            }
            "resync" -> LibrarySync.resync()
            else -> return error("unknown op")
        }
        return ok()
    }

    /** Runs the browser sign-in in the background; progress shows up as `login` in /state. */
    /** `{external: true}`: the caller launches the browser (from [BrowserLoginHelper.loginCommand]). */
    private fun startLogin(external: Boolean): JsonObject {
        if (login != null && login?.startsWith("Error") != true && login != "Signed in") return ok()
        login = "Opening browser..."
        scope.launch {
            val result = if (external) BrowserLoginHelper.awaitLogin { login = it }
            else BrowserLoginHelper.loginWithBrowser { login = it }
            login = when (result) {
                is CookieExtractResult.Success -> AuthManager.saveCredentials(result.cookie, "", "")
                    .fold({ "Signed in" }, { "Error: ${it.message}" })
                is CookieExtractResult.Error -> "Error: ${result.message}"
            }
        }
        return ok()
    }

    // --- JSON shapes ---

    private fun list(items: List<JsonElement>) = buildJsonObject { put("items", JsonArray(items)) }

    private fun songJson(s: SongInfo) = buildJsonObject {
        put("type", "song"); put("id", s.id); put("title", s.title); put("subtitle", s.artist)
        put("thumb", s.thumbnailUrl); put("album", s.album); put("durationMs", s.knownDurationMs())
        put("autoplay", s.fromAutoplay)
    }

    // One query for every song's artists, as the library screen does: getArtistNamesForSong per song
    // was 800+ queries for a big library, and threw for a song with no artist rows.
    private fun songInfoFromDb(id: String, title: String, duration: Long, thumb: String?, album: String?,
                               artists: Map<String, String> = DatabaseHelper.getAllSongArtistNames()) = SongInfo(
        id = id, title = title, artist = artists[id] ?: album ?: "Unknown Artist",
        thumbnailUrl = thumb, album = album, duration = duration.toInt()
    )

    private fun dbSongs(songs: List<com.lyrenne.desktop.db.Song>): List<SongInfo> {
        val artists = DatabaseHelper.getAllSongArtistNames()
        return songs.map { songInfoFromDb(it.id, it.title, it.duration, it.thumbnailUrl, it.albumName, artists) }
    }

    private fun songFromJson(o: JsonObject) = SongInfo(
        id = o.str("id")!!, title = o.str("title").orEmpty(), artist = o.str("subtitle").orEmpty(),
        thumbnailUrl = o.str("thumb"), album = o.str("album"),
        duration = ((o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000).toInt().takeIf { it > 0 } ?: -1
    )

    private fun songsOf(value: JsonElement?) = value!!.jsonArray.map { songFromJson(it.jsonObject) }

    private fun itemJson(item: YTItem): JsonObject? = when (item) {
        is SongItem -> songJson(item.toPlayerSongInfo())
        is AlbumItem -> buildJsonObject {
            put("type", "album"); put("id", item.browseId); put("title", item.title); put("thumb", item.thumbnail)
            put("subtitle", listOfNotNull(item.artists?.joinToString { it.name }, item.year?.toString()).joinToString(" · "))
        }
        is PlaylistItem -> buildJsonObject {
            put("type", "playlist"); put("id", item.id); put("title", item.title); put("thumb", item.thumbnail)
            put("subtitle", listOfNotNull(item.author?.name, item.songCountText).joinToString(" · "))
        }
        is ArtistItem -> buildJsonObject {
            put("type", "artist"); put("id", item.id); put("title", item.title); put("thumb", item.thumbnail); put("subtitle", "Artist")
        }
        else -> null
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}
