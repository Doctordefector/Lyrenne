package com.metrolist.music.desktop.sync

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
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
            _syncState.value = SyncState(isSyncing = true, progress = "Starting sync...")

            try {
                // Sync liked songs from "LM" (Liked Music) playlist
                syncLikedSongs()

                // Sync library playlists
                syncLibraryPlaylists()

                // Sync library albums
                syncLibraryAlbums()

                // Sync library artists
                syncLibraryArtists()

                _syncState.value = SyncState(
                    isSyncing = false,
                    lastSyncTime = LocalDateTime.now().toString(),
                    progress = "Sync complete!"
                )

                // Clear progress message after delay
                delay(3000)
                _syncState.update { it.copy(progress = "") }

            } catch (e: CancellationException) {
                _syncState.value = SyncState(progress = "Sync cancelled")
            } catch (e: Exception) {
                e.printStackTrace()
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

    private suspend fun syncLikedSongs() {
        _syncState.update { it.copy(progress = "Syncing liked songs...") }

        try {
            // Get the liked music playlist
            val playlistResult = YouTube.playlist("LM")
            val playlist = playlistResult.getOrNull() ?: return

            val songs = playlist.songs
            var count = 0

            songs.forEach { song ->
                saveSongToDatabase(song, liked = true)
                count++
            }

            _syncState.update { it.copy(progress = "Synced $count liked songs") }

            // Fetch all remaining pages — songsContinuation is the inner shelf token (primary),
            // continuation is the outer section token (fallback)
            var continuation = playlist.songsContinuation ?: playlist.continuation
            var pageCount = 1

            while (continuation != null && pageCount < 200) {
                val contResult = YouTube.playlistContinuation(continuation)
                val contPlaylist = contResult.getOrNull() ?: break

                contPlaylist.songs.forEach { song ->
                    saveSongToDatabase(song, liked = true)
                    count++
                }

                continuation = contPlaylist.continuation
                pageCount++
                _syncState.update { it.copy(progress = "Syncing liked songs... ($count songs)") }
            }

        } catch (e: Exception) {
            Timber.e("Error syncing liked songs: ${e.message}")
        }
    }

    private suspend fun syncLibraryPlaylists() {
        _syncState.update { it.copy(progress = "Syncing playlists...") }

        try {
            // Fetch library playlists using the library API
            val libraryResult = YouTube.library("FEmusic_liked_playlists")
            val library = libraryResult.getOrNull() ?: return

            library.items.filterIsInstance<PlaylistItem>().forEach { playlist ->
                savePlaylistToDatabase(playlist)
            }

        } catch (e: Exception) {
            Timber.e("Error syncing playlists: ${e.message}")
        }
    }

    private suspend fun syncLibraryAlbums() {
        _syncState.update { it.copy(progress = "Syncing albums...") }

        try {
            // Fetch library albums
            val libraryResult = YouTube.library("FEmusic_liked_albums")
            val library = libraryResult.getOrNull() ?: return

            library.items.filterIsInstance<AlbumItem>().forEach { album ->
                saveAlbumToDatabase(album, bookmarked = true)
            }

        } catch (e: Exception) {
            Timber.e("Error syncing albums: ${e.message}")
        }
    }

    private suspend fun syncLibraryArtists() {
        _syncState.update { it.copy(progress = "Syncing artists...") }

        try {
            // Fetch library artists (subscriptions)
            val libraryResult = YouTube.library("FEmusic_library_corpus_track_artists")
            val library = libraryResult.getOrNull() ?: return

            library.items.filterIsInstance<ArtistItem>().forEach { artist ->
                saveArtistToDatabase(artist, subscribed = true)
            }

        } catch (e: Exception) {
            Timber.e("Error syncing artists: ${e.message}")
        }
    }

    private fun saveSongToDatabase(song: SongItem, liked: Boolean = false, inLibrary: Boolean = true) {
        val artists = song.artists

        // Insert song
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration ?: -1,
            thumbnailUrl = song.thumbnail,
            albumId = song.album?.id,
            albumName = song.album?.name,
            explicit = song.explicit,
            liked = liked,
            likedDate = if (liked) LocalDateTime.now().toString() else null,
            inLibrary = if (inLibrary) LocalDateTime.now().toString() else null
        )

        // Insert artists and mappings
        artists.forEachIndexed { index, artist ->
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

    private fun saveAlbumToDatabase(album: AlbumItem, bookmarked: Boolean = false) {
        DatabaseHelper.insertAlbum(
            id = album.id,
            title = album.title,
            playlistId = album.playlistId,
            year = album.year,
            thumbnailUrl = album.thumbnail,
            explicit = album.explicit,
            bookmarkedAt = if (bookmarked) LocalDateTime.now().toString() else null,
            inLibrary = LocalDateTime.now().toString()
        )
    }

    private fun saveArtistToDatabase(artist: ArtistItem, subscribed: Boolean = false) {
        DatabaseHelper.insertArtist(
            id = artist.id,
            name = artist.title,
            thumbnailUrl = artist.thumbnail,
            channelId = artist.channelId,
            bookmarkedAt = if (subscribed) LocalDateTime.now().toString() else null
        )
    }

    private fun savePlaylistToDatabase(playlist: PlaylistItem) {
        // Parse songCountText like "50 songs" to get the number
        val songCount = playlist.songCountText?.filter { it.isDigit() }?.toIntOrNull()

        DatabaseHelper.insertPlaylist(
            id = playlist.id,
            name = playlist.title,
            browseId = playlist.id,
            isEditable = playlist.isEditable,
            bookmarkedAt = LocalDateTime.now().toString(),
            remoteSongCount = songCount,
            thumbnailUrl = playlist.thumbnail
        )
    }

    fun clearError() {
        _syncState.update { it.copy(error = null) }
    }
}
