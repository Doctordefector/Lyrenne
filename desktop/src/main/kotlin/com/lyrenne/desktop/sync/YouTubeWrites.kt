package com.lyrenne.desktop.sync

import com.metrolist.innertube.YouTube
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Pushes local library edits up to YouTube.
 *
 * Metrolist was read-only: adding a song to a playlist, liking a track, renaming or deleting a
 * playlist all wrote to the local database and stopped there, so nothing ever appeared on
 * YouTube. InnerTube has had the write endpoints all along — nothing called them.
 *
 * Writes are fire-and-forget so the UI stays instant and edits still work offline; the local DB
 * remains the source of truth for what you see. A failed push is surfaced through [lastError]
 * rather than rolled back, because silently undoing a user's edit is worse than a stale remote.
 */
object YouTubeWrites {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() { _lastError.value = null }

    private val isLoggedIn: Boolean get() = AuthManager.authState.value.isLoggedIn

    /**
     * Playlists created before this existed — or while signed out — carry an "LP" id and have
     * no counterpart on YouTube, so there is nothing to push them to.
     */
    private fun isRemote(playlistId: String) = !playlistId.startsWith(LOCAL_PLAYLIST_PREFIX)

    const val LOCAL_PLAYLIST_PREFIX = "LP"

    private fun push(what: String, block: suspend () -> Result<*>) {
        if (!isLoggedIn) return
        scope.launch {
            runCatching { block() }
                .getOrElse { Result.failure<Unit>(it) }
                .onFailure {
                    Timber.w("YouTube $what failed: ${it.message}")
                    _lastError.value = "Couldn't $what on YouTube: ${it.message?.take(120)}"
                }
                .onSuccess { Timber.i("YouTube $what ok") }
        }
    }

    fun addToPlaylist(playlistId: String, videoId: String) {
        if (!isRemote(playlistId)) return
        push("add to playlist") { YouTube.addToPlaylist(playlistId, videoId) }
    }

    /**
     * Removal needs the playlist-item id (`setVideoId`), which is not the video id and is not
     * stored locally. It is resolved by reading the playlist back — one extra call, but it
     * avoids a schema migration purely to cache an id used only on delete.
     */
    fun removeFromPlaylist(playlistId: String, videoId: String) {
        if (!isRemote(playlistId)) return
        push("remove from playlist") {
            val page = YouTube.playlist(playlistId).getOrThrow()
            val setVideoId = page.songs.firstOrNull { it.id == videoId }?.setVideoId
                ?: return@push Result.failure<Unit>(
                    IllegalStateException("song not found in remote playlist")
                )
            YouTube.removeFromPlaylist(playlistId, videoId, setVideoId)
        }
    }

    /**
     * When each song's like push last settled, [Long.MAX_VALUE] while one is in flight. A sync
     * whose snapshot was fetched before the push landed would otherwise undo the edit: re-like a
     * song just unliked here, or prune one just liked. See [likeEditedSince].
     */
    private val likeSettledAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** True if this song's like was changed here at or after [since], or is still being pushed. */
    fun likeEditedSince(videoId: String, since: Long) = (likeSettledAt[videoId] ?: 0L) >= since

    fun likeSong(videoId: String, liked: Boolean) {
        if (!isLoggedIn) return
        likeSettledAt[videoId] = Long.MAX_VALUE
        push(if (liked) "like song" else "unlike song") {
            try {
                YouTube.likeVideo(videoId, liked)
            } finally {
                likeSettledAt[videoId] = System.currentTimeMillis()
            }
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        if (!isRemote(playlistId)) return
        push("rename playlist") { YouTube.renamePlaylist(playlistId, name) }
    }

    fun deletePlaylist(playlistId: String) {
        if (!isRemote(playlistId)) return
        push("delete playlist") { YouTube.deletePlaylist(playlistId) }
    }

    /**
     * Create a playlist, on YouTube when signed in so later edits can mirror, and insert it
     * locally either way. Returns the id to use locally.
     */
    suspend fun createPlaylist(name: String): String {
        if (isLoggedIn) {
            val remoteId = withContext(Dispatchers.IO) {
                runCatching { YouTube.createPlaylist(name) }
                    .onFailure {
                        Timber.w("YouTube create playlist failed: ${it.message}")
                        _lastError.value = "Couldn't create the playlist on YouTube: ${it.message?.take(120)}"
                    }
                    .getOrNull()
            }
            if (!remoteId.isNullOrBlank()) {
                DatabaseHelper.insertPlaylist(
                    id = remoteId,
                    name = name,
                    browseId = remoteId,
                    isEditable = true,
                    bookmarkedAt = java.time.LocalDateTime.now().toString()
                )
                return remoteId
            }
        }
        // Signed out, or YouTube refused — keep it local so the edit isn't lost.
        return DatabaseHelper.createLocalPlaylist(name)
    }
}

/** Like or unlike locally and on YouTube. The song may not be in the library yet, so add it first. */
fun setLiked(song: SongInfo, liked: Boolean, downloaded: Boolean) {
    DatabaseHelper.insertSong(
        id = song.id,
        title = song.title,
        duration = song.duration,
        thumbnailUrl = song.thumbnailUrl,
        albumName = song.album
    )
    DatabaseHelper.updateSongLiked(song.id, liked)
    YouTubeWrites.likeSong(song.id, liked)
    if (liked && !downloaded && PreferencesManager.preferences.value.autoDownloadOnLike) {
        DownloadManager.queueDownload(song)
    }
}
