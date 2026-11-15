package com.lyrenne.desktop.movein

import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.sync.YouTubeWrites
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Which Move in window is showing, if any. */
enum class MoveInDialog { NONE, BACKUP, PLAYLIST }

sealed interface BackupState {
    data object Idle : BackupState
    data class Reading(val fileName: String) : BackupState
    data class Preview(val fileName: String, val library: MetroListLibrary, val options: MoveInOptions) : BackupState
    data object Importing : BackupState
    data class Done(val result: MoveInResult, val pushedLikes: Int = 0, val pushTotal: Int = 0, val pushFailed: Int = 0) : BackupState
    data class Failed(val message: String) : BackupState
}

enum class PlaylistPhase { INPUT, FETCHING, MATCHING, REVIEW, SAVING, SAVED }

data class PlaylistImportState(
    val phase: PlaylistPhase = PlaylistPhase.INPUT,
    val input: String = "",
    val error: String? = null,
    val source: SourcePlaylist? = null,
    /** One per source track, null while it is still being searched. */
    val matches: List<TrackMatch?> = emptyList(),
    val name: String = "",
    val createOnYouTube: Boolean = false,
    val saved: Int = 0,
    val savedPlaylistId: String? = null,
    val savedRemotely: Boolean = false,
    val remoteFailures: Int = 0,
) {
    val matchedCount get() = matches.count { it?.chosen != null }
    val doneCount get() = matches.count { it != null }
}

/**
 * State and work behind the Move in windows: the MetroList importer and the playlist importer.
 * Entry points (onboarding, Settings, the command palette) only call [openBackup] or
 * [openPlaylist]; the windows themselves are drawn once by `MoveInHost` in App.
 */
object MoveInController {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _dialog = MutableStateFlow(MoveInDialog.NONE)
    val dialog: StateFlow<MoveInDialog> = _dialog.asStateFlow()

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _playlist = MutableStateFlow(PlaylistImportState())
    val playlist: StateFlow<PlaylistImportState> = _playlist.asStateFlow()

    private var playlistJob: Job? = null
    private var pushJob: Job? = null

    val isSignedIn: Boolean get() = AuthManager.authState.value.isLoggedIn

    // ------------------------------------------------------------------ MetroList backup

    /** Shows the backup window. With a [file], reads it straight away. */
    fun openBackup(file: File? = null) {
        if (_backup.value !is BackupState.Importing && pushJob?.isActive != true) _backup.value = BackupState.Idle
        _dialog.value = MoveInDialog.BACKUP
        file?.let(::readBackup)
    }

    fun readBackup(file: File) {
        _backup.value = BackupState.Reading(file.name)
        scope.launch {
            _backup.value = try {
                val lib = MetroListBackup.read(file)
                if (lib.isEmpty) BackupState.Failed("That backup has nothing to bring over: no likes, playlists or plays.")
                else BackupState.Preview(file.name, lib, MoveInOptions())
            } catch (e: MetroListBackup.NotABackup) {
                BackupState.Failed(e.message ?: "This file isn't a MetroList backup.")
            } catch (e: Exception) {
                Timber.w(e, "Move in: reading backup failed")
                BackupState.Failed("Couldn't read that backup: ${e.message?.take(160)}")
            }
        }
    }

    fun setOptions(options: MoveInOptions) {
        _backup.update { if (it is BackupState.Preview) it.copy(options = options) else it }
    }

    fun runImport() {
        val preview = _backup.value as? BackupState.Preview ?: return
        _backup.value = BackupState.Importing
        scope.launch {
            val result = try {
                MoveInImporter({ body -> DatabaseHelper.moveInTransaction(body) })
                    .import(preview.library, preview.options)
            } catch (e: Exception) {
                Timber.w(e, "Move in: import failed")
                _backup.value = BackupState.Failed("The import stopped and nothing was changed: ${e.message?.take(160)}")
                return@launch
            }
            Timber.i("Move in: imported $result".take(400))
            _backup.value = BackupState.Done(result)
            if (preview.options.likeOnYouTube && isSignedIn && result.likedNow.isNotEmpty()) pushLikes(result.likedNow)
        }
    }

    /**
     * Likes the imported songs on YouTube one at a time. Through [YouTubeWrites.likeSong] so a
     * sync running meanwhile knows these likes are in flight, and paced so a big library does
     * not fire a thousand requests at once. Oldest first, so YouTube's newest-first order ends
     * up matching MetroList's.
     */
    private fun pushLikes(ids: List<String>) {
        pushJob?.cancel()
        pushJob = scope.launch {
            val ordered = ids.reversed()
            _backup.update { if (it is BackupState.Done) it.copy(pushTotal = ordered.size) else it }
            YouTubeWrites.clearError()
            var failed = 0
            for ((i, id) in ordered.withIndex()) {
                if (!isSignedIn) break
                val before = YouTubeWrites.lastError.value
                YouTubeWrites.likeSong(id, true)
                delay(PUSH_SPACING_MS)
                if (YouTubeWrites.lastError.value != null && YouTubeWrites.lastError.value != before) failed++
                _backup.update { if (it is BackupState.Done) it.copy(pushedLikes = i + 1, pushFailed = failed) else it }
            }
        }
    }

    private const val PUSH_SPACING_MS = 350L

    // ------------------------------------------------------------------ playlist link or text

    fun openPlaylist(prefill: String = "") {
        val current = _playlist.value
        if (current.phase == PlaylistPhase.SAVED || current.phase == PlaylistPhase.INPUT) {
            _playlist.value = PlaylistImportState(input = prefill.ifBlank { current.input.takeIf { current.phase == PlaylistPhase.INPUT }.orEmpty() })
        }
        _dialog.value = MoveInDialog.PLAYLIST
    }

    fun setInput(text: String) = _playlist.update { it.copy(input = text, error = null) }
    fun setName(name: String) = _playlist.update { it.copy(name = name) }
    fun setCreateOnYouTube(on: Boolean) = _playlist.update { it.copy(createOnYouTube = on) }

    /** Reads the link or text in the input box, then matches every song. */
    fun startPlaylist() {
        val input = _playlist.value.input.trim()
        if (input.isEmpty()) return
        playlistJob?.cancel()
        playlistJob = scope.launch {
            val source = try {
                if (PlaylistSources.looksLikeLink(input)) {
                    _playlist.update { it.copy(phase = PlaylistPhase.FETCHING, error = null) }
                    val url = if (PlaylistSources.isShortLink(input)) PlaylistSources.resolveShortLink(input) else input
                    val link = PlaylistSources.detect(url)
                        ?: throw PlaylistSources.FetchException(
                            "That link isn't one Lyrenne can read. Paste a playlist or album link from Spotify, " +
                                "Apple Music, Deezer, TIDAL or SoundCloud, or paste the songs as text."
                        )
                    PlaylistSources.fetch(link)
                } else {
                    TextPlaylist.parse(input)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaylistSources.FetchException) {
                _playlist.update { it.copy(phase = PlaylistPhase.INPUT, error = e.message) }
                return@launch
            } catch (e: Exception) {
                Timber.w(e, "Move in: reading playlist failed")
                _playlist.update { it.copy(phase = PlaylistPhase.INPUT, error = "Couldn't read that playlist. Check your connection and try again.") }
                return@launch
            }
            if (source.tracks.isEmpty()) {
                _playlist.update { it.copy(phase = PlaylistPhase.INPUT, error = "No songs found in that. Is the playlist public?") }
                return@launch
            }
            _playlist.update {
                it.copy(
                    phase = PlaylistPhase.MATCHING, source = source, name = source.name,
                    matches = List(source.tracks.size) { null }, error = null
                )
            }
            matchAll(source)
        }
    }

    private suspend fun matchAll(source: SourcePlaylist) {
        // A few searches at once: fast enough that songs pop in steadily, gentle enough not to
        // look like a flood to YouTube.
        val gate = Semaphore(3)
        withContext(Dispatchers.IO) {
            source.tracks.mapIndexed { index, track ->
                async {
                    gate.withPermit {
                        val match = runCatching { TrackMatcher.match(track, ::searchYouTubeMusic) }
                            .getOrElse { e ->
                                if (e is CancellationException) throw e
                                TrackMatch(track, emptyList(), null, MatchQuality.NONE)
                            }
                        _playlist.update { s ->
                            if (s.source !== source) s
                            else s.copy(matches = s.matches.toMutableList().also { it[index] = match })
                        }
                    }
                }
            }.awaitAll()
        }
        _playlist.update { if (it.source === source) it.copy(phase = PlaylistPhase.REVIEW) else it }
    }

    internal suspend fun searchYouTubeMusic(query: String, videos: Boolean): List<Candidate> {
        val filter = if (videos) YouTube.SearchFilter.FILTER_VIDEO else YouTube.SearchFilter.FILTER_SONG
        val result = YouTube.search(query, filter).getOrThrow()
        return result.items.filterIsInstance<SongItem>().filterNot { it.isEpisode }.map { s ->
            Candidate(
                videoId = s.id,
                title = s.title,
                artists = s.artists.map { it.name },
                album = s.album?.name,
                durationSec = s.duration,
                thumbnailUrl = s.thumbnail,
                isSong = !s.isVideoSong,
                isUserVideo = s.musicVideoType == MUSIC_VIDEO_TYPE_UGC,
                explicit = s.explicit
            )
        }
    }

    /** Back to the start, keeping what was pasted. Stops any search still running. */
    fun cancelPlaylist() {
        playlistJob?.cancel()
        _playlist.update { PlaylistImportState(input = it.input) }
    }

    /** Stops searching and reviews what has been found so far. */
    fun stopMatching() {
        playlistJob?.cancel()
        _playlist.update { s ->
            if (s.phase != PlaylistPhase.MATCHING) s
            else s.copy(phase = PlaylistPhase.REVIEW)
        }
    }

    /** Picks [candidate] for row [index], or skips the song with null. */
    fun choose(index: Int, candidate: Candidate?) = _playlist.update { s ->
        val m = s.matches.getOrNull(index) ?: return@update s
        s.copy(matches = s.matches.toMutableList().also { it[index] = m.copy(chosen = candidate) })
    }

    fun savePlaylist() {
        val state = _playlist.value
        if (state.phase != PlaylistPhase.REVIEW) return
        val songs = state.matches.mapNotNull { it?.chosen }
        if (songs.isEmpty()) return
        val name = state.name.trim().ifEmpty { state.source?.name ?: "Imported playlist" }
        val remote = state.createOnYouTube && isSignedIn
        _playlist.update { it.copy(phase = PlaylistPhase.SAVING, saved = 0) }
        playlistJob = scope.launch {
            val id = if (remote) YouTubeWrites.createPlaylist(name) else DatabaseHelper.createLocalPlaylist(name)
            val isRemote = YouTubeWrites.isRemote(id)
            // Local first, in one go, so the playlist is complete here even if YouTube is slow
            DatabaseHelper.transaction {
                songs.forEachIndexed { i, c ->
                    DatabaseHelper.insertSong(
                        id = c.videoId, title = c.title, duration = c.durationSec ?: -1,
                        thumbnailUrl = c.thumbnailUrl, albumName = c.album, explicit = c.explicit
                    )
                    if (DatabaseHelper.getArtistNamesForSong(c.videoId) == "Unknown Artist") {
                        c.artists.forEachIndexed { pos, a ->
                            val artistId = "unknown_${a.hashCode()}"
                            DatabaseHelper.insertArtistIfMissing(artistId, a)
                            DatabaseHelper.insertSongArtistMap(c.videoId, artistId, pos)
                        }
                    }
                    DatabaseHelper.addSongToPlaylist(id, c.videoId, i)
                }
            }
            var failures = 0
            if (isRemote) {
                // One at a time and in order, so the YouTube copy has the same order
                for ((i, c) in songs.withIndex()) {
                    val ok = runCatching { YouTube.addToPlaylist(id, c.videoId).getOrThrow() }.isSuccess
                    if (!ok) failures++
                    _playlist.update { it.copy(saved = i + 1) }
                    delay(150)
                }
            }
            _playlist.update {
                it.copy(
                    phase = PlaylistPhase.SAVED, saved = songs.size, savedPlaylistId = id,
                    savedRemotely = isRemote, remoteFailures = failures
                )
            }
        }
    }

    fun close() {
        _dialog.value = MoveInDialog.NONE
        if (_backup.value !is BackupState.Importing && pushJob?.isActive != true) _backup.value = BackupState.Idle
        // A playlist being matched keeps going and is there again when the window reopens
        if (_playlist.value.phase == PlaylistPhase.SAVED) _playlist.value = PlaylistImportState()
    }

    /** For UiSnapshot and tests: show a window with a prepared state. */
    internal fun showForPreview(dialog: MoveInDialog, backup: BackupState? = null, playlist: PlaylistImportState? = null) {
        backup?.let { _backup.value = it }
        playlist?.let { _playlist.value = it }
        _dialog.value = dialog
    }
}
