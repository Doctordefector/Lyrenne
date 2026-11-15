package com.lyrenne.desktop.link

import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.lyrics.LyricsOffsets
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.playback.RepeatMode
import com.lyrenne.desktop.playback.knownDurationMs
import com.lyrenne.desktop.playback.toPlayerSongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.setLiked
import com.lyrenne.desktop.ui.theme.ArtColor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What Settings shows about the server. */
data class LinkStatus(
    val running: Boolean = false,
    val port: Int = -1,
    val lan: Boolean = false,
    /** Set when the saved port was taken and another one is in use. */
    val portNote: String? = null,
    val error: String? = null,
    /** This PC's LAN addresses, best guess first. */
    val lanAddresses: List<String> = emptyList(),
)

/**
 * Lyrenne Link: the stream overlay (localhost) and the phone remote (LAN, off by default), served
 * from inside the running app. Drives the same [DesktopPlayer] and [LyricsManager] the window does.
 * See CLAUDE.md "Lyrenne Link".
 */
object LyrenneLink {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var player: DesktopPlayer? = null
    /** The player Link drives, for the overlay preview in Settings. Null until [initialize]. */
    val activePlayer: DesktopPlayer? get() = player
    @Volatile private var server: LinkServer? = null

    /** Internal so the Settings snapshot can show a running server without opening a port. */
    internal val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    /** Asks the window to show the pairing dialog (Settings, Command Palette). */
    val pairingRequested = MutableStateFlow(false)
    /** A one-line notice for the window to show briefly, e.g. after copying the overlay URL. */
    val notice = MutableStateFlow<String?>(null)

    @Volatile private var accent: Pair<String, String?>? = null // song id to hex
    @Volatile private var liked = false

    fun initialize(player: DesktopPlayer) {
        if (this.player != null) return
        this.player = player
        LinkSettings.load()
        val backend = PlayerBackend(player)
        this.backend = backend

        // Start, stop or rebind the server whenever what it should be changes.
        scope.launch {
            LinkSettings.prefs.map { Triple(it.overlayEnabled || it.remoteEnabled, it.remoteEnabled, it.port) }
                .distinctUntilChanged()
                .collect { (on, lan, port) -> restart(backend, on, lan, port) }
        }
        scope.launch {
            LinkSettings.prefs.map { it.token }.distinctUntilChanged().drop(1).collect {
                server?.dropAuthorizedClients()
            }
        }
        backend.watch(scope)
    }

    private var backend: PlayerBackend? = null

    @Synchronized
    private fun restart(backend: PlayerBackend, on: Boolean, lan: Boolean, port: Int) {
        server?.stop()
        server = null
        if (!on) { _status.value = LinkStatus(); return }
        val s = LinkServer(
            backend, port, lan,
            token = { LinkSettings.prefs.value.token },
            remoteEnabled = { LinkSettings.prefs.value.remoteEnabled },
            lanHosts = { NetworkInfo.localHosts() },
        )
        _status.value = try {
            val actual = s.start()
            server = s
            Timber.i("Lyrenne Link on ${if (lan) "all interfaces" else "127.0.0.1"}:$actual")
            LinkStatus(
                running = true, port = actual, lan = lan,
                portNote = if (actual != port) "Port $port was busy, so Lyrenne is using $actual." else null,
                lanAddresses = if (lan) NetworkInfo.lanAddresses() else emptyList(),
            )
        } catch (e: Exception) {
            Timber.e(e, "Lyrenne Link could not start")
            LinkStatus(error = "Couldn't start: ${e.message}")
        }
    }

    fun release() {
        server?.stop()
        server = null
    }

    // --- What the UI calls ---

    fun overlayUrl(config: OverlayConfig = LinkSettings.prefs.value.overlay): String {
        val port = status.value.port.takeIf { it > 0 } ?: LinkSettings.prefs.value.port
        return "http://127.0.0.1:$port/overlay?${config.query()}"
    }

    /** The URL the QR code carries, or null while the remote is off or no LAN address is known. */
    fun remoteUrl(address: String? = status.value.lanAddresses.firstOrNull()): String? {
        val s = status.value
        if (!s.running || !s.lan || address == null) return null
        return "http://$address:${s.port}/remote#t=${LinkSettings.prefs.value.token}"
    }

    fun setOverlayEnabled(on: Boolean) = LinkSettings.update { it.copy(overlayEnabled = on) }
    fun setRemoteEnabled(on: Boolean) = LinkSettings.update { it.copy(remoteEnabled = on) }
    fun setOverlay(config: OverlayConfig) = LinkSettings.update { it.copy(overlay = config) }
    fun rotateToken() = LinkSettings.rotateToken()

    fun setExport(enabled: Boolean, folder: String?) {
        LinkSettings.update { it.copy(exportEnabled = enabled, exportFolder = folder) }
        backend?.exportNow()
    }

    /** Turns the overlay server on if needed and puts the OBS URL on the clipboard. */
    fun copyOverlayUrl(): String {
        if (!LinkSettings.prefs.value.overlayEnabled) setOverlayEnabled(true)
        // The port is only known once the server is up; wait briefly for it.
        runBlocking { withTimeoutOrNull(2_000) { status.first { it.running || it.error != null } } }
        val url = overlayUrl()
        copyToClipboard(url)
        notice.value = "Overlay URL copied. In OBS, add a Browser Source at 1920 x 1080."
        return url
    }

    fun copyToClipboard(text: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }.onFailure { Timber.w("Clipboard unavailable: ${it.message}") }
    }

    // --- The player behind the server ---

    private class PlayerBackend(private val player: DesktopPlayer) : LinkBackend {
        private val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        private val artCache = object : LinkedHashMap<String, Pair<ByteArray, String>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String>>?) = size > 4
        }
        private var volumeSave: Job? = null
        private var exportJob: Job? = null

        private fun snapshot(s: PlaybackState = player.state.value): LinkSnapshot {
            val song = s.currentSong
            return LinkSnapshot(
                song = song,
                playing = s.isPlaying,
                positionMs = s.position,
                durationMs = if (s.duration > 0) s.duration else song?.knownDurationMs() ?: 0,
                volume = PreferencesManager.preferences.value.volume,
                shuffle = s.shuffleEnabled,
                repeat = s.repeatMode.name,
                liked = liked,
                accent = accent?.takeIf { it.first == song?.id }?.second,
                index = s.currentIndex,
                queueSize = s.queue.size,
                error = s.error,
            )
        }

        override fun state(): JsonObject = buildJsonObject {
            LinkJson.state(snapshot()).forEach { (k, v) -> put(k, v) }
            put("lyricsOffsetMs", player.state.value.currentSong?.let { LyricsOffsets.get(it.id) } ?: 0L)
        }

        override fun lyrics(): JsonObject {
            val id = player.state.value.currentSong?.id
            return LinkJson.lyrics(id, LyricsManager.state.value, id?.let { LyricsOffsets.get(it) } ?: 0L)
        }

        override fun queue(): JsonObject = player.state.value.let { LinkJson.queue(it.queue, it.currentIndex) }

        override fun search(query: String): JsonObject {
            if (query.isBlank()) return buildJsonObject { put("items", JsonArray(emptyList())) }
            val items = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(15_000) { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull() }
            }?.items.orEmpty().filterIsInstance<SongItem>().map { LinkJson.song(it.toPlayerSongInfo()) }
            return buildJsonObject { put("items", JsonArray(items)) }
        }

        /** On the UI thread, as the window's own buttons are. */
        private fun onMain(block: suspend () -> Unit) {
            scope.launch(Dispatchers.Main) { runCatching { block() }.onFailure { Timber.e(it, "Link command failed") } }
        }

        override fun command(op: String, value: JsonElement?): String? {
            val prim = value as? JsonPrimitive
            val s = player.state.value
            when (op) {
                "toggle" -> onMain { player.togglePlayPause() }
                "play" -> onMain { player.play() }
                "pause" -> onMain { player.pause() }
                "next" -> onMain { player.playNext() }
                "prev" -> onMain { player.playPrevious() }
                "seek" -> {
                    val ms = prim?.longOrNull ?: return "seek needs a position"
                    onMain { player.seekTo(ms.coerceAtLeast(0)) }
                }
                "volume" -> {
                    val v = prim?.floatOrNull?.coerceIn(0f, 1f) ?: return "volume needs 0 to 1"
                    onMain {
                        player.setVolume(v)
                        // A slider drag sends many steps; write the settings file once it settles.
                        PreferencesManager.setVolume(v, persist = false)
                        volumeSave?.cancel()
                        volumeSave = scope.launch { delay(800); PreferencesManager.setVolume(PreferencesManager.preferences.value.volume) }
                    }
                }
                "shuffle" -> onMain { player.toggleShuffle() }
                "repeat" -> {
                    val mode = prim?.contentOrNull?.let { m -> RepeatMode.entries.firstOrNull { it.name == m } }
                    onMain { if (mode != null) player.setRepeatMode(mode) else player.toggleRepeat() }
                }
                "like" -> {
                    val song = s.currentSong ?: return "Nothing is playing"
                    val want = prim?.booleanOrNull ?: !liked
                    liked = want
                    onMain { setLiked(song, want, DownloadManager.isDownloaded(song.id)) }
                }
                "jump" -> {
                    val i = prim?.intOrNull ?: return "jump needs an index"
                    if (i !in s.queue.indices) return "That song is no longer in the queue"
                    onMain { player.playAtIndex(i) }
                }
                "playNext", "enqueue" -> {
                    val song = (value as? JsonObject)?.let { LinkJson.songFrom(it) } ?: return "No song"
                    onMain { if (op == "playNext") player.addToQueueNext(song) else player.addToQueue(song) }
                }
                else -> return "Unknown command"
            }
            return null
        }

        override fun play(body: JsonObject): String? {
            val song = (body["song"] as? JsonObject)?.let { LinkJson.songFrom(it) } ?: return "No song"
            onMain { player.playSong(song) }
            return null
        }

        override fun art(songId: String?): Pair<ByteArray, String>? {
            val current = player.state.value.currentSong
            val id = songId ?: current?.id ?: return null
            synchronized(artCache) { artCache[id] }?.let { return it }
            // Only ever the playing song's cover: this is not a general image proxy.
            if (current == null || current.id != id) return null
            val url = current.thumbnailUrl ?: return null
            synchronized(this) {
                synchronized(artCache) { artCache[id] }?.let { return it }
                val fetched = fetchImage(bigArt(url)) ?: fetchImage(url) ?: return null
                synchronized(artCache) { artCache[id] = fetched }
                return fetched
            }
        }

        private fun fetchImage(url: String): Pair<ByteArray, String>? = runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "image/jpeg,image/png;q=0.9").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val type = r.header("Content-Type")?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                r.body?.bytes()?.takeIf { it.isNotEmpty() }?.let { it to type }
            }
        }.getOrNull()

        override fun onSubscriber() {
            scope.launch { ensureLyrics() }
        }

        /**
         * The overlay can be on stream with the lyrics panel closed, and the panel is what normally
         * starts the lookup. Same wait for the live duration as the panel: LrcLib and KuGou match
         * on it.
         */
        private suspend fun ensureLyrics() {
            val song = player.state.value.currentSong ?: return
            if (LyricsManager.state.value.songId == song.id) return
            val durationMs = withTimeoutOrNull(4_000) {
                player.state.first { it.currentSong?.id == song.id && it.duration > 0 }.duration
            } ?: song.knownDurationMs()
            if (player.state.value.currentSong?.id != song.id || LyricsManager.state.value.songId == song.id) return
            LyricsManager.fetchLyrics(song.id, song.title, song.artist, (durationMs / 1000).toInt(), song.album)
        }

        private fun broadcastState() {
            val s = server ?: return
            s.broadcast("state", state())
        }

        fun exportNow() {
            exportJob?.cancel()
            exportJob = scope.launch(Dispatchers.IO) {
                delay(300)
                val p = LinkSettings.prefs.value
                if (!p.exportEnabled || p.exportFolder == null) return@launch
                val snap = snapshot()
                val art = snap.song?.let { art(it.id) }
                runCatching { NowPlayingExport.write(java.io.File(p.exportFolder), snap, art?.first) }
                    .onFailure { Timber.w("Now playing export failed: ${it.message}") }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun watch(scope: CoroutineScope) {
            // Discrete changes go out at once; position only on a seek, plus a heartbeat, and the
            // pages interpolate in between. 200 ms position ticks would flood every phone.
            scope.launch {
                var lastKey: List<Any?>? = null
                var lastQueue: List<String>? = null
                var lastIndex = -2
                var lastPos = 0L
                var lastAt = 0L
                player.state.collect { s ->
                    val key = listOf(s.currentSong?.id, s.isPlaying, s.shuffleEnabled, s.repeatMode, s.currentIndex,
                        s.queue.size, s.error, s.duration > 0)
                    val now = System.currentTimeMillis()
                    val expected = lastPos + if (s.isPlaying) now - lastAt else 0
                    val seeked = lastAt != 0L && abs(s.position - expected) > 1_500
                    if (key != lastKey || seeked) {
                        val songChanged = lastKey?.getOrNull(0) != s.currentSong?.id
                        val playChanged = lastKey?.getOrNull(1) != s.isPlaying
                        lastKey = key
                        broadcastState()
                        if (songChanged) onSongChanged()
                        if (songChanged || playChanged) exportNow()
                    }
                    lastPos = s.position; lastAt = now
                    if (s.currentIndex != lastIndex || s.queue.size != lastQueue?.size ||
                        s.queue.map { it.id } != lastQueue
                    ) {
                        lastIndex = s.currentIndex
                        lastQueue = s.queue.map { it.id }
                        server?.broadcast("queue", queue(), authorizedOnly = true)
                    }
                }
            }
            scope.launch {
                PreferencesManager.preferences.map { it.volume }.distinctUntilChanged().drop(1).collect { broadcastState() }
            }
            scope.launch {
                player.state.map { it.currentSong?.id }.distinctUntilChanged()
                    .flatMapLatest { id -> id?.let { DatabaseHelper.getSongById(it).map { row -> row?.liked == 1L } } ?: flowOf(false) }
                    .distinctUntilChanged()
                    .collect { liked = it; broadcastState() }
            }
            scope.launch {
                LyricsManager.state.collect { server?.broadcast("lyrics", lyrics()) }
            }
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    if ((server?.clientCount ?: 0) > 0) broadcastState()
                }
            }
            scope.launch {
                LinkSettings.prefs.map { it.exportEnabled to it.exportFolder }.distinctUntilChanged().collect { exportNow() }
            }
        }

        private fun onSongChanged() {
            val song = player.state.value.currentSong ?: return
            scope.launch {
                if ((server?.clientCount ?: 0) > 0) ensureLyrics()
            }
            scope.launch {
                val url = song.thumbnailUrl ?: return@launch
                val color = runCatching { ArtColor.of(url) }.getOrNull()
                accent = song.id to color?.let {
                    "#%02x%02x%02x".format((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
                }
                broadcastState()
            }
            // Warm the cover so every page asking for it at once costs one download.
            if (server != null) scope.launch(Dispatchers.IO) { art(song.id) }
        }
    }

    /** `=w120-h120` style sizes in YouTube art URLs; ask for one big enough for a phone screen. */
    internal fun bigArt(url: String): String = url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
}

/** This machine's addresses, for the QR code and for the Host check. */
internal object NetworkInfo {
    private val VIRTUAL = listOf("virtual", "vmware", "vbox", "hyper-v", "vethernet", "docker", "wsl", "bluetooth", "loopback", "tap-", "npcap")
    @Volatile private var hosts: Pair<Long, Set<String>>? = null

    /** Site-local IPv4 addresses of real adapters, home networks (192.168) first. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { nic -> VIRTUAL.none { v -> nic.displayName.orEmpty().lowercase().contains(v) || nic.name.lowercase().contains(v) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
            .sortedBy { when { it.startsWith("192.168.") -> 0; it.startsWith("10.") -> 1; else -> 2 } }
    }.getOrDefault(emptyList())

    /** Every name and address this PC answers to, cached for 30 s (DHCP can change them). */
    fun localHosts(): Set<String> {
        val now = System.currentTimeMillis()
        hosts?.let { (at, set) -> if (now - at < 30_000) return set }
        val set = runCatching {
            val addrs = NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .map { a -> a.hostAddress.substringBefore('%').let { if (a is Inet4Address) it else "[$it]" } }
            val name = runCatching { InetAddress.getLocalHost().hostName.lowercase() }.getOrNull()
            (addrs + listOfNotNull(name, name?.let { "$it.local" })).toSet()
        }.getOrDefault(emptySet())
        hosts = now to set
        return set
    }
}
