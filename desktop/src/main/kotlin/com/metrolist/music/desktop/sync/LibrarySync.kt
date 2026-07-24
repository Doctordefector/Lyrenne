package com.metrolist.music.desktop.sync

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.db.DatabaseHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.LocalDateTime

data class SyncState(
    val isSyncing: Boolean = false,
    val progress: String = "",
    val lastSyncTime: String? = null,
    val error: String? = null
)

object LibrarySync {
    /** Safety stop for continuation paging, so a malformed response can't loop for ever. */
    private const val MAX_LIBRARY_PAGES = 100
    private const val MAX_LIKED_SONG_PAGES = 200

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
                val playlistsResult = playlistsDeferred.await()
                val albumsResult = albumsDeferred.await()
                val artistsResult = artistsDeferred.await()

                val songs = songsResult.getOrDefault(emptyList())
                val playlists = playlistsResult.getOrDefault(emptyList()).filterIsInstance<PlaylistItem>()
                val albums = albumsResult.getOrDefault(emptyList()).filterIsInstance<AlbumItem>()
                val artists = artistsResult.getOrDefault(emptyList()).filterIsInstance<ArtistItem>()

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
                    // Only ever prune from a fetch that SUCCEEDED — pruning off a failed fetch
                    // would read as "the remote has nothing" and empty the whole library.
                    if (songsResult.isSuccess) pruneSongs(songs)
                    if (albumsResult.isSuccess) pruneAlbums(albums)
                    if (artistsResult.isSuccess) pruneArtists(artists)
                    if (playlistsResult.isSuccess) prunePlaylists(playlists)
                }

                val failures = listOfNotNull(
                    "songs".takeIf { songsResult.isFailure },
                    "playlists".takeIf { playlistsResult.isFailure },
                    "albums".takeIf { albumsResult.isFailure },
                    "artists".takeIf { artistsResult.isFailure }
                )

                _syncState.value = SyncState(
                    isSyncing = false,
                    lastSyncTime = LocalDateTime.now().toString(),
                    progress = "Synced ${songs.size} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists",
                    error = if (failures.isEmpty()) null
                        else "Could not fetch ${failures.joinToString(", ")} — those were left untouched"
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

    fun cancelSync() {
        syncJob?.cancel()
        _syncState.value = SyncState(progress = "Sync cancelled")
    }

    // ============ Network Fetch (no DB writes) ============

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
    private suspend fun fetchLibraryItems(browseId: String, label: String): Result<List<YTItem>> =
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
            items
        }.onFailure { Timber.e("Error fetching $label: ${it.message}") }

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
        if (com.metrolist.music.desktop.settings.PreferencesManager.preferences.value.syncPruneBackupDone) return
        val target = java.io.File(
            com.metrolist.music.desktop.AppPaths.appDir,
            "Metrolist-backup-before-sync-cleanup.zip"
        )
        com.metrolist.music.desktop.backup.BackupManager.exportBackup(target)
            .onSuccess {
                Timber.i("Wrote pre-prune safety backup to ${target.absolutePath}")
                com.metrolist.music.desktop.settings.PreferencesManager.setSyncPruneBackupDone(true)
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
