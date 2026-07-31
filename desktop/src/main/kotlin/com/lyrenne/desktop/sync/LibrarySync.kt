package com.lyrenne.desktop.sync

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.db.DatabaseHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.LocalDateTime

data class SyncState(
    val isSyncing: Boolean = false,
    val progress: String = "",
    val error: String? = null
)

object LibrarySync {
    /** Safety stop for continuation paging, so a malformed response can't loop for ever. */
    private const val MAX_LIBRARY_PAGES = 100
    private const val MAX_LIKED_SONG_PAGES = 200

    /** songs, playlists, albums, artists. All four failing at once points at the session. */
    private const val TOTAL_SYNC_CATEGORIES = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null

    fun syncLibrary() {
        if (_syncState.value.isSyncing) return

        val authState = AuthManager.authState.value
        if (!authState.isLoggedIn) {
            _syncState.value = SyncState(error = "Not logged in")
            return
        }

        syncJob = scope.launch {
            _syncState.value = SyncState(isSyncing = true, progress = "Syncing library...")

            try {
                // Phase 1: Fetch all data from YouTube API (network-bound), in parallel
                val songsDeferred = async { fetchAllLikedSongs() }
                val playlistsDeferred = async { fetchLibraryItems("FEmusic_liked_playlists", "playlists") }
                val albumsDeferred = async { fetchLibraryItems("FEmusic_liked_albums", "albums") }
                val artistsDeferred = async { fetchLibraryItems("FEmusic_library_corpus_track_artists", "artists") }

                _syncState.update { it.copy(progress = "Fetching library from YouTube...") }

                val songsResult = songsDeferred.await()
                val playlistsFetch = playlistsDeferred.await()
                val albumsFetch = albumsDeferred.await()
                val artistsFetch = artistsDeferred.await()

                fun items(fetch: LibraryFetch) = (fetch as? LibraryFetch.Items)?.items.orEmpty()

                val songs = songsResult.getOrDefault(emptyList())
                val playlists = items(playlistsFetch).filterIsInstance<PlaylistItem>()
                val albums = items(albumsFetch).filterIsInstance<AlbumItem>()
                val artists = items(artistsFetch).filterIsInstance<ArtistItem>()

                _syncState.update {
                    it.copy(progress = "Saving ${songs.size} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists...")
                }

                // Outside the transaction — it copies the database file off disk.
                backupBeforeFirstPrune()

                // Phase 2: Write everything to DB in a single transaction
                // This means: one disk sync, one flow notification, zero UI flicker
                DatabaseHelper.transaction {
                    val now = LocalDateTime.now().toString()

                    songs.forEach { song ->
                        saveSongToDatabase(song, liked = true, now = now)
                    }

                    albums.forEach { album ->
                        saveAlbumToDatabase(album, bookmarked = true, now = now)
                    }

                    artists.forEach { artist ->
                        saveArtistToDatabase(artist, subscribed = true, now = now)
                    }

                    playlists.forEach { playlist ->
                        savePlaylistToDatabase(playlist, now = now)
                    }

                    // Phase 3: remove what the remote no longer has, so unliking a song or
                    // deleting a playlist on YouTube is reflected here.
                    //
                    // Only ever prune from a fetch that actually returned a listing. A failure
                    // would read as "the remote has nothing" and empty the whole library, and an
                    // empty category is not solid enough evidence either — see LibraryFetch.
                    if (songsResult.isSuccess) pruneSongs(songs)
                    if (albumsFetch is LibraryFetch.Items) pruneAlbums(albums)
                    if (artistsFetch is LibraryFetch.Items) pruneArtists(artists)
                    if (playlistsFetch is LibraryFetch.Items) prunePlaylists(playlists)
                }

                val failures = buildMap {
                    songsResult.exceptionOrNull()?.let { put("songs", it) }
                    (playlistsFetch as? LibraryFetch.Failed)?.let { put("playlists", it.cause) }
                    (albumsFetch as? LibraryFetch.Failed)?.let { put("albums", it.cause) }
                    (artistsFetch as? LibraryFetch.Failed)?.let { put("artists", it.cause) }
                }

                _syncState.value = SyncState(
                    isSyncing = false,
                    progress = "Synced ${songs.size} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists",
                    error = failures.takeIf { it.isNotEmpty() }?.let { describeFailure(it) }
                )

                // Clear progress message after delay
                delay(5000)
                _syncState.update { it.copy(progress = "") }

            } catch (e: CancellationException) {
                _syncState.value = SyncState(progress = "Sync cancelled")
            } catch (e: Exception) {
                Timber.e("Sync failed: ${e.message}")
                _syncState.value = SyncState(
                    isSyncing = false,
                    error = "Sync failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Turn fetch failures into something actionable.
     *
     * When every category fails at once the cause is almost never four separate faults — it is
     * the shared session. YouTube's SID/SAPISID cookies expire and the __Secure-*PSIDTS pair
     * rotates, so a login left alone for weeks starts failing every authenticated call.
     */
    private fun describeFailure(failures: Map<String, Throwable>): String {
        val detail = failures.values.first().message?.take(160).orEmpty()
        val looksLikeAuth = failures.size == TOTAL_SYNC_CATEGORIES ||
            detail.contains("401") || detail.contains("403") ||
            detail.contains("Unauthorized", true) || detail.contains("login", true)

        return if (looksLikeAuth) {
            "Sync failed for ${failures.keys.joinToString(", ")}. Your YouTube session has most " +
                "likely expired — sign out and sign in again in Settings. ($detail)"
        } else {
            "Could not fetch ${failures.keys.joinToString(", ")} — those were left untouched. ($detail)"
        }
    }

    // ============ Network Fetch (no DB writes) ============

    /**
     * Outcome of fetching one library category.
     *
     * [Empty] is deliberately not the same as `Items(emptyList())`. YouTube answers a category the
     * user has nothing in with a page carrying neither a grid nor a shelf, which the InnerTube
     * client reports as an error. Two things follow from that, and they pull in opposite
     * directions:
     *
     * - It must not surface as a sync failure. Someone with no saved albums was being told their
     *   YouTube session had expired, which was alarming and wrong.
     * - It must not authorise pruning either. An outage or a response-format change produces the
     *   same shape, and pruning on it would wipe that whole category from the local library.
     *   "Found nothing" is not the same as "there is nothing".
     *
     * So [Empty] clears the error and skips the prune. The cost is that genuinely removing your
     * last album on YouTube will not be mirrored locally until you have at least one again, which
     * is a far cheaper failure than deleting a library.
     */
    private sealed interface LibraryFetch {
        data class Items(val items: List<YTItem>) : LibraryFetch
        data object Empty : LibraryFetch
        data class Failed(val cause: Throwable) : LibraryFetch
    }

    /**
     * All liked songs, paginated.
     *
     * Any failed page fails the whole fetch rather than returning what arrived so far. A
     * partial list is indistinguishable from "you unliked the rest", and pruning would then
     * unlike every song that simply hadn't downloaded yet.
     */
    private suspend fun fetchAllLikedSongs(): Result<List<SongItem>> = runCatching {
        val allSongs = mutableListOf<SongItem>()
        val playlist = YouTube.playlist("LM").getOrThrow()

        allSongs.addAll(playlist.songs)
        _syncState.update { it.copy(progress = "Fetching liked songs... (${allSongs.size})") }

        var continuation = playlist.songsContinuation ?: playlist.continuation
        var pageCount = 1

        while (continuation != null && pageCount < MAX_LIKED_SONG_PAGES) {
            val contPlaylist = YouTube.playlistContinuation(continuation).getOrThrow()
            allSongs.addAll(contPlaylist.songs)
            continuation = contPlaylist.continuation
            pageCount++
            _syncState.update { it.copy(progress = "Fetching liked songs... (${allSongs.size})") }
        }
        if (continuation != null) {
            // Hit the cap with pages left: treat as incomplete so nothing gets pruned.
            throw IllegalStateException("liked songs exceeded $MAX_LIKED_SONG_PAGES pages")
        }
        allSongs
    }.onFailure { Timber.e("Error fetching liked songs: ${it.message}") }

    /**
     * Fetch a library tab, following continuations to the end.
     *
     * `YouTube.library()` returns only the FIRST page — roughly 25 items. The previous code
     * ignored `LibraryPage.continuation` entirely, so anyone with more than a page of
     * playlists (or albums, or artists) simply never saw the rest of them.
     *
     * Returns a Result so a network failure is distinguishable from a genuinely empty
     * library. That matters: the old code swallowed errors and returned an empty list, which
     * reported "Synced 0 playlists" as a success — and would now also mean pruning wipes the
     * local library on a transient failure.
     */
    private suspend fun fetchLibraryItems(browseId: String, label: String): LibraryFetch =
        runCatching {
            val items = mutableListOf<YTItem>()
            val first = YouTube.library(browseId).getOrThrow()
            items += first.items
            _syncState.update { it.copy(progress = "Fetching $label... (${items.size})") }

            var continuation = first.continuation
            var pages = 1
            while (continuation != null && pages < MAX_LIBRARY_PAGES) {
                val next = YouTube.libraryContinuation(continuation).getOrThrow()
                items += next.items
                continuation = next.continuation
                pages++
                _syncState.update { it.copy(progress = "Fetching $label... (${items.size})") }
            }
            if (continuation != null) {
                Timber.w("$label: stopped at $MAX_LIBRARY_PAGES pages, more remain")
            }
            items.toList()
        }.fold(
            onSuccess = { LibraryFetch.Items(it) },
            onFailure = { e ->
                if (isEmptyCategory(e)) {
                    Timber.i("$label: nothing saved in this category")
                    LibraryFetch.Empty
                } else {
                    Timber.e("Error fetching $label: ${e.message}")
                    LibraryFetch.Failed(e)
                }
            }
        )

    /**
     * Does this failure just mean "the user has none of these"?
     *
     * `YouTube.library()` throws when the browse response carries neither a `gridRenderer` nor a
     * `musicShelfRenderer`, and that is exactly the shape YouTube returns for a category with
     * nothing in it. Matched on the message rather than fixed at the source because the InnerTube
     * module is upstream's vendored code: patching it would conflict on every future sync.
     *
     * If upstream ever changes that message this stops matching, and the symptom is the old
     * behaviour returning, a spurious sync error, not data loss.
     */
    private fun isEmptyCategory(e: Throwable): Boolean =
        e is IllegalStateException && e.message?.startsWith("No content found for browseId=") == true

    // ============ DB Writes (called inside transaction) ============

    private fun saveSongToDatabase(song: SongItem, liked: Boolean = false, now: String) {
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration ?: -1,
            thumbnailUrl = song.thumbnail,
            albumId = song.album?.id,
            albumName = song.album?.name,
            explicit = song.explicit,
            liked = liked,
            likedDate = if (liked) now else null,
            inLibrary = now
        )

        song.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: "unknown_${artist.name.hashCode()}"
            DatabaseHelper.insertArtist(
                id = artistId,
                name = artist.name
            )
            DatabaseHelper.insertSongArtistMap(
                songId = song.id,
                artistId = artistId,
                position = index
            )
        }
    }

    private fun saveAlbumToDatabase(album: AlbumItem, bookmarked: Boolean = false, now: String) {
        DatabaseHelper.insertAlbum(
            id = album.id,
            title = album.title,
            playlistId = album.playlistId,
            year = album.year,
            thumbnailUrl = album.thumbnail,
            explicit = album.explicit,
            bookmarkedAt = if (bookmarked) now else null,
            inLibrary = now
        )
    }

    private fun saveArtistToDatabase(artist: ArtistItem, subscribed: Boolean = false, now: String) {
        DatabaseHelper.insertArtist(
            id = artist.id,
            name = artist.title,
            thumbnailUrl = artist.thumbnail,
            channelId = artist.channelId,
            bookmarkedAt = if (subscribed) now else null
        )
    }

    // ============ Pruning — mirror removals made on YouTube ============

    /**
     * Unlike songs that are no longer in the remote Liked Music playlist.
     *
     * Restricted to songs with `inLibrary` set, which only a library sync ever writes. A song
     * liked purely inside Metrolist has `inLibrary == null` and is left alone — liking here
     * does not push to YouTube (LibraryScreen writes straight to the DB), so those would
     * otherwise be unliked on the very next sync.
     */
    private fun pruneSongs(remote: List<SongItem>) {
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val stale = DatabaseHelper.getLikedSongsOnce()
            .filter { it.inLibrary != null && it.id !in remoteIds }
        stale.forEach { DatabaseHelper.updateSongLiked(it.id, false) }
        if (stale.isNotEmpty()) Timber.i("Sync: unliked ${stale.size} songs removed on YouTube")
    }

    /** Nothing in the UI bookmarks albums — only sync does — so bookmarkedAt is safe to key off. */
    private fun pruneAlbums(remote: List<AlbumItem>) {
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val stale = DatabaseHelper.getAllAlbumsOnce()
            .filter { it.bookmarkedAt != null && it.id !in remoteIds }
        stale.forEach { DatabaseHelper.updateAlbumBookmarked(it.id, false) }
        if (stale.isNotEmpty()) Timber.i("Sync: removed ${stale.size} albums")
    }

    private fun pruneArtists(remote: List<ArtistItem>) {
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val stale = DatabaseHelper.getAllArtistsOnce()
            .filter { it.bookmarkedAt != null && it.id !in remoteIds }
        stale.forEach { DatabaseHelper.updateArtistBookmarked(it.id, false) }
        if (stale.isNotEmpty()) Timber.i("Sync: unsubscribed ${stale.size} artists")
    }

    /**
     * Playlists created inside Metrolist must survive untouched. They are identified by having
     * no browseId — [DatabaseHelper.createPlaylist] passes `browseId = null` — and no
     * bookmarkedAt either, so both have to hold before anything is dropped.
     */
    private fun prunePlaylists(remote: List<PlaylistItem>) {
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val stale = DatabaseHelper.getAllPlaylistsOnce()
            .filter { it.browseId != null && it.bookmarkedAt != null && it.id !in remoteIds }
        stale.forEach { DatabaseHelper.updatePlaylistBookmarked(it.id, false) }
        if (stale.isNotEmpty()) Timber.i("Sync: removed ${stale.size} playlists")
    }

    /**
     * One-off safety net: sync now deletes local state, and it runs automatically at startup,
     * so the first destructive run happens without the user asking for it. Writes a single
     * backup ZIP next to the app before that can happen. Failure is non-fatal.
     */
    private fun backupBeforeFirstPrune() {
        if (com.lyrenne.desktop.settings.PreferencesManager.preferences.value.syncPruneBackupDone) return
        val target = java.io.File(
            com.lyrenne.desktop.AppPaths.appDir,
            "Lyrenne-backup-before-sync-cleanup.zip"
        )
        com.lyrenne.desktop.backup.BackupManager.exportBackup(target)
            .onSuccess {
                Timber.i("Wrote pre-prune safety backup to ${target.absolutePath}")
                com.lyrenne.desktop.settings.PreferencesManager.setSyncPruneBackupDone(true)
            }
            .onFailure { Timber.w("Could not write pre-prune backup: ${it.message}") }
    }

    private fun savePlaylistToDatabase(playlist: PlaylistItem, now: String) {
        // Take the first run of digits only. Filtering every digit out of the string turned
        // "2 songs • 15 minutes" into 215.
        val songCount = Regex("\\d+").find(playlist.songCountText.orEmpty())?.value?.toIntOrNull()

        DatabaseHelper.insertPlaylist(
            id = playlist.id,
            name = playlist.title,
            browseId = playlist.id,
            isEditable = playlist.isEditable,
            bookmarkedAt = now,
            remoteSongCount = songCount,
            thumbnailUrl = playlist.thumbnail
        )
    }

    fun clearError() {
        _syncState.update { it.copy(error = null) }
    }
}
