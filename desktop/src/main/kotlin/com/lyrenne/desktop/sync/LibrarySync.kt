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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Quick sync stops paging liked songs after this many already-synced songs in a row, provided
     * YouTube's total also adds up. See [fetchLikedSongs].
     */
    private const val KNOWN_RUN_TO_STOP = 20

    /** songs, playlists, albums, artists. All four failing at once points at the session. */
    private const val TOTAL_SYNC_CATEGORIES = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null

    /**
     * Throws away any sync in flight and starts a fresh one. Used when the signed-in account
     * changes: a sync still fetching for the previous account would otherwise either block the
     * new one (syncLibrary no-ops while one runs) or land the old account's library afterwards.
     * The old job is joined before the new one starts, so its transaction can never interleave.
     */
    fun resync() {
        scope.launch {
            // Serialised, so two switches in quick succession cannot both cancel the same old
            // job and then each start a sync of their own. syncLibrary assigns syncJob before
            // returning, so the next one in line cancels the sync this one started.
            resyncLock.withLock {
                syncJob?.cancelAndJoin()
                _syncState.value = SyncState()
                // The local library is the previous account's, so a quick sync has nothing to go on.
                syncLibrary(full = true)
            }
        }
    }

    private val resyncLock = Mutex()

    /**
     * [full] pages through every liked song. Otherwise only the newest ones are fetched when that
     * provably covers everything new (see [fetchLikedSongs]), and it falls back to full by itself.
     * The Sync button asks for full.
     */
    fun syncLibrary(full: Boolean = false) {
        if (_syncState.value.isSyncing) return

        val authState = AuthManager.authState.value
        // Signed out is a normal state, not a failure: Library shows a sign-in prompt for it.
        if (!authState.isLoggedIn) return

        syncJob = scope.launch {
            _syncState.value = SyncState(isSyncing = true, progress = "Syncing library...")
            // Like edits made here from now on may postdate the snapshot about to be fetched.
            val startedAt = System.currentTimeMillis()
            fun editedHere(songId: String) = YouTubeWrites.likeEditedSince(songId, startedAt)

            try {
                // Liked songs already mirrored from YouTube; quick sync stops once it reaches them.
                val known = if (full) null else DatabaseHelper.getLikedSongsOnce()
                    .filter { it.inLibrary != null }.mapTo(HashSet()) { it.id }

                // Phase 1: Fetch all data from YouTube API (network-bound), in parallel
                val songsDeferred = async {
                    runCatching { fetchLikedSongs(known) }
                        .onFailure { if (it !is CancellationException) Timber.e("Error fetching liked songs: ${it.message}") }
                }
                val playlistsDeferred = async { fetchLibraryItems("FEmusic_liked_playlists", "playlists") }
                val albumsDeferred = async { fetchLibraryItems("FEmusic_liked_albums", "albums") }
                val artistsDeferred = async { fetchLibraryItems("FEmusic_library_corpus_track_artists", "artists") }

                _syncState.update { it.copy(progress = "Fetching library from YouTube...") }

                val songsResult = songsDeferred.await()
                val playlistsFetch = playlistsDeferred.await()
                val albumsFetch = albumsDeferred.await()
                val artistsFetch = artistsDeferred.await()

                fun items(fetch: LibraryFetch) = (fetch as? LibraryFetch.Items)?.items.orEmpty()

                val liked = songsResult.getOrNull()
                val songs = liked?.songs.orEmpty()
                val playlists = items(playlistsFetch).filterIsInstance<PlaylistItem>()
                val albums = items(albumsFetch).filterIsInstance<AlbumItem>()
                val artists = items(artistsFetch).filterIsInstance<ArtistItem>()

                _syncState.update {
                    it.copy(progress = "Saving ${liked?.total ?: 0} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists...")
                }

                // Outside the transaction — it copies the database file off disk.
                backupBeforeFirstPrune()

                // Phase 2: Write everything to DB in a single transaction
                // This means: one disk sync, one flow notification, zero UI flicker
                DatabaseHelper.transaction {
                    val nowTime = LocalDateTime.now()
                    val now = nowTime.toString()

                    songs.forEachIndexed { index, song ->
                        if (editedHere(song.id)) return@forEachIndexed
                        // YouTube lists likes newest first; a second apart keeps that order
                        // for "Date liked". Only newly liked songs take it, see markSongLiked.
                        saveLikedSong(song, likedDate = nowTime.minusSeconds(index.toLong()).toString(), now = now)
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
                    // A quick sync saw only the newest songs, so it must not prune.
                    if (liked?.complete == true) pruneSongs(songs, ::editedHere)
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
                    progress = "Synced ${liked?.total ?: 0} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists",
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
                "likely expired. Sign out and sign in again in Settings. ($detail)"
        } else {
            "Could not fetch ${failures.keys.joinToString(", ")}. Those were left untouched. ($detail)"
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

    /** One page of the Liked Music playlist. [remoteCount] is YouTube's total, first page only. */
    internal class LikedPage(
        val songs: List<SongItem>,
        val continuation: String?,
        val remoteCount: Int? = null
    )

    /** Seam for tests, like `DesktopPlayer.radioFor`. A null continuation asks for the first page. */
    internal var likedPage: suspend (continuation: String?) -> LikedPage = { continuation ->
        if (continuation == null) {
            val page = YouTube.playlist("LM").getOrThrow()
            LikedPage(page.songs, page.songsContinuation ?: page.continuation, parseSongCount(page.playlist.songCountText))
        } else {
            val page = YouTube.playlistContinuation(continuation).getOrThrow()
            LikedPage(page.songs, page.continuation)
        }
    }

    /**
     * [complete] means every remote liked song is in [songs], which is the only case pruning may
     * run on. [total] is the remote count either way, for the status line.
     */
    internal class LikedSongs(val songs: List<SongItem>, val complete: Boolean, val total: Int)

    /**
     * Liked songs, newest first, paginated.
     *
     * With [known] (the ids of liked songs a previous sync already mirrored) this stops early,
     * which is the point: a big library is up to [MAX_LIKED_SONG_PAGES] sequential requests, and
     * a launch sync usually has nothing new past the first page. It stops only when both hold:
     *
     * - [KNOWN_RUN_TO_STOP] known songs in a row, so the new likes at the top are behind us.
     * - YouTube's total equals known + new. Anything unliked on YouTube, or a new like hiding
     *   further down, breaks the sum, and paging carries on to a full fetch that prunes.
     *
     * The one case that slips through is an unlike and a missed like cancelling out exactly;
     * the next full sync (the Sync button, an account switch) catches it. An unparsable total
     * never matches, so the worst case is a full fetch, never data loss.
     *
     * Any failed page fails the whole fetch rather than returning what arrived so far. A
     * partial list is indistinguishable from "you unliked the rest", and pruning would then
     * unlike every song that simply hadn't downloaded yet.
     */
    internal suspend fun fetchLikedSongs(known: Set<String>?): LikedSongs {
        val songs = mutableListOf<SongItem>()
        var page = likedPage(null)
        val remoteCount = page.remoteCount
        var pages = 1
        var knownRun = 0
        var newCount = 0
        while (true) {
            for (song in page.songs) {
                songs += song
                if (known != null && song.id in known) knownRun++ else { knownRun = 0; newCount++ }
            }
            _syncState.update { it.copy(progress = "Fetching liked songs... (${songs.size})") }

            if (known != null && knownRun >= KNOWN_RUN_TO_STOP && remoteCount == known.size + newCount) {
                Timber.i("Liked songs: quick sync, $pages page(s), $newCount new of $remoteCount")
                return LikedSongs(songs, complete = false, total = remoteCount)
            }
            val continuation = page.continuation
            if (continuation == null) {
                if (!known.isNullOrEmpty()) {
                    Timber.i("Liked songs: full fetch, YouTube says $remoteCount, had ${known.size} + $newCount new")
                }
                return LikedSongs(songs, complete = true, total = songs.size)
            }
            if (pages >= MAX_LIKED_SONG_PAGES) {
                // Hit the cap with pages left: treat as incomplete so nothing gets pruned.
                throw IllegalStateException("liked songs exceeded $MAX_LIKED_SONG_PAGES pages")
            }
            page = likedPage(continuation)
            pages++
        }
    }

    /**
     * "1,234 songs", "1.234 Titel" -> 1234. Anything else (an abbreviated "1.2K", a locale
     * whose separator is a space) comes out wrong or null, and a wrong total only costs a full
     * fetch, see [fetchLikedSongs].
     */
    internal fun parseSongCount(text: String?): Int? =
        text?.let { Regex("^[\\d.,]+").find(it.trim()) }?.value?.filter(Char::isDigit)?.toIntOrNull()

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

    private fun saveLikedSong(song: SongItem, likedDate: String, now: String) {
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration ?: -1,
            thumbnailUrl = song.thumbnail,
            albumId = song.album?.id,
            albumName = song.album?.name,
            explicit = song.explicit,
            liked = true,
            likedDate = likedDate,
            inLibrary = now
        )
        // insertSong ignores rows that already exist, so without this a song played or
        // downloaded before it was liked, or re-liked after an unlike, never showed as liked.
        DatabaseHelper.markSongLiked(song.id, likedDate)

        song.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: "unknown_${artist.name.hashCode()}"
            DatabaseHelper.insertArtistIfMissing(id = artistId, name = artist.name)
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
     * liked here whose push to YouTube failed (or was made signed out) has `inLibrary == null`
     * and is left alone, rather than silently unliked on the next sync. Songs whose like was
     * changed here during this sync are skipped too: the snapshot may predate the push.
     */
    private fun pruneSongs(remote: List<SongItem>, editedHere: (String) -> Boolean) {
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val stale = DatabaseHelper.getLikedSongsOnce()
            .filter { it.inLibrary != null && it.id !in remoteIds && !editedHere(it.id) }
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
        // An installed Linux copy's app dir is /opt, which the user cannot write.
        val paths = com.lyrenne.desktop.AppPaths
        val target = java.io.File(
            if (paths.isPortable) paths.appDir else paths.dataDir,
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
