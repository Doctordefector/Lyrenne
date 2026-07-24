package com.metrolist.music.desktop.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

object DatabaseHelper {
    private lateinit var database: MetrolistDatabase
    private val queries get() = database.metrolistQueries

    fun initialize() {
        val dbFile = getDatabaseFile()
        dbFile.parentFile?.toPath()?.let { Files.createDirectories(it) }

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        if (!dbFile.exists() || dbFile.length() == 0L) {
            MetrolistDatabase.Schema.create(driver)
        } else {
            // Migrate existing databases: ensure all tables exist
            migrateIfNeeded(driver)
        }

        database = MetrolistDatabase(driver)
    }

    private fun migrateIfNeeded(driver: JdbcSqliteDriver) {
        val existingTables = mutableSetOf<String>()
        // Use driver.execute to query tables — getConnection() returns a pooled connection
        // that SQLDelight manages, so we use it within a use block to avoid leaks
        driver.getConnection().let { connection ->
            connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    existingTables.add(rs.getString(1))
                }
            }
        }

        if ("PlayQueue" !in existingTables) {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS PlayQueue (
                    position INTEGER NOT NULL PRIMARY KEY,
                    songId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    thumbnailUrl TEXT,
                    durationMs INTEGER NOT NULL,
                    album TEXT,
                    durationSec INTEGER NOT NULL
                )
            """.trimIndent(), 0)
        }

        if ("Event" !in existingTables) {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS Event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    songId TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    playTime INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (songId) REFERENCES Song(id) ON DELETE CASCADE
                )
            """.trimIndent(), 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS event_song_idx ON Event(songId)", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS event_timestamp_idx ON Event(timestamp)", 0)
        }

        if ("RecognitionHistory" !in existingTables) {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS RecognitionHistory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    videoId TEXT,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    thumbnailUrl TEXT,
                    recognizedAt INTEGER NOT NULL
                )
            """.trimIndent(), 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS recognition_time_idx ON RecognitionHistory(recognizedAt)", 0)
        }

        if ("PlayQueueState" !in existingTables) {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS PlayQueueState (
                    id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
                    currentIndex INTEGER NOT NULL DEFAULT 0,
                    shuffleEnabled INTEGER NOT NULL DEFAULT 0,
                    repeatMode TEXT NOT NULL DEFAULT 'OFF',
                    positionMs INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(), 0)
        }
    }

    private fun getDatabaseFile(): File = com.metrolist.music.desktop.AppPaths.databaseFile

    // ============ Transaction Support ============

    /**
     * Run a block of DB operations inside a single SQLite transaction.
     * SQLDelight reactive flows are only notified ONCE when the transaction commits,
     * avoiding per-row UI recompositions during bulk inserts.
     */
    fun <T> transaction(body: () -> T): T {
        return database.transactionWithResult {
            body()
        }
    }

    // ============ Song Operations ============

    fun getAllSongs() =
        queries.getAllSongs { id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath, _rowOrder ->
            Song(id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath)
        }.asFlow().mapToList(Dispatchers.IO)

    fun getLikedSongs() =
        queries.getLikedSongs { id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath, _rowOrder ->
            Song(id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath)
        }.asFlow().mapToList(Dispatchers.IO)

    fun getDownloadedSongs() =
        queries.getDownloadedSongs { id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath, _rowOrder ->
            Song(id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath)
        }.asFlow().mapToList(Dispatchers.IO)

    fun getSongById(id: String) =
        queries.getSongById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun getSongLocalPath(songId: String): String? =
        queries.getSongLocalPath(songId).executeAsOneOrNull()?.localPath

    fun getSongsByAlbum(albumId: String) =
        queries.getSongsByAlbum(albumId).asFlow().mapToList(Dispatchers.IO)

    fun insertSong(
        id: String,
        title: String,
        duration: Int = -1,
        thumbnailUrl: String? = null,
        albumId: String? = null,
        albumName: String? = null,
        explicit: Boolean = false,
        year: Int? = null,
        liked: Boolean = false,
        likedDate: String? = null,
        totalPlayTime: Long = 0,
        inLibrary: String? = null,
        dateDownload: String? = null,
        isLocal: Boolean = false,
        isDownloaded: Boolean = false,
        localPath: String? = null
    ) {
        // INSERT OR IGNORE: only inserts if song doesn't exist yet
        queries.insertSong(
            id = id,
            title = title,
            duration = duration.toLong(),
            thumbnailUrl = thumbnailUrl,
            albumId = albumId,
            albumName = albumName,
            explicit = if (explicit) 1L else 0L,
            year = year?.toLong(),
            liked = if (liked) 1L else 0L,
            likedDate = likedDate,
            totalPlayTime = totalPlayTime,
            inLibrary = inLibrary,
            dateDownload = dateDownload,
            isLocal = if (isLocal) 1L else 0L,
            isDownloaded = if (isDownloaded) 1L else 0L,
            localPath = localPath
        )
        // Update metadata for existing songs (preserves download/liked state)
        queries.updateSongMetadata(
            title = title,
            duration = duration.toLong(),
            value = thumbnailUrl,
            value_ = albumId,
            value__ = albumName,
            explicit = if (explicit) 1L else 0L,
            year = year?.toLong(),
            value___ = inLibrary,
            id = id
        )
    }

    fun updateSongLiked(id: String, liked: Boolean) {
        queries.updateSongLiked(
            liked = if (liked) 1L else 0L,
            likedDate = if (liked) LocalDateTime.now().toString() else null,
            id = id
        )
    }

    fun updateSongInLibrary(id: String, inLibrary: Boolean) {
        queries.updateSongInLibrary(
            inLibrary = if (inLibrary) LocalDateTime.now().toString() else null,
            id = id
        )
    }

    fun updateSongDownloaded(id: String, isDownloaded: Boolean, localPath: String?) {
        queries.updateSongDownloaded(
            isDownloaded = if (isDownloaded) 1L else 0L,
            dateDownload = if (isDownloaded) LocalDateTime.now().toString() else null,
            localPath = localPath,
            id = id
        )
    }

    fun deleteSong(id: String) {
        queries.deleteSong(id)
    }

    // ============ Album Operations ============

    fun getAllAlbums() =
        queries.getAllAlbums().asFlow().mapToList(Dispatchers.IO)

    fun getAlbumById(id: String) =
        queries.getAlbumById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun insertAlbum(
        id: String,
        title: String,
        playlistId: String? = null,
        year: Int? = null,
        thumbnailUrl: String? = null,
        songCount: Int = 0,
        duration: Int = 0,
        explicit: Boolean = false,
        bookmarkedAt: String? = null,
        inLibrary: String? = null
    ) {
        queries.insertAlbum(
            id = id,
            playlistId = playlistId,
            title = title,
            year = year?.toLong(),
            thumbnailUrl = thumbnailUrl,
            songCount = songCount.toLong(),
            duration = duration.toLong(),
            explicit = if (explicit) 1L else 0L,
            lastUpdateTime = LocalDateTime.now().toString(),
            bookmarkedAt = bookmarkedAt,
            inLibrary = inLibrary
        )
    }

    fun updateAlbumBookmarked(id: String, bookmarked: Boolean) {
        queries.updateAlbumBookmarked(
            bookmarkedAt = if (bookmarked) LocalDateTime.now().toString() else null,
            id = id
        )
    }

    fun deleteAlbum(id: String) {
        queries.deleteAlbum(id)
    }

    // ============ Artist Operations ============

    fun getAllArtists() =
        queries.getAllArtists { id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt ->
            Artist(id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt)
        }.asFlow().mapToList(Dispatchers.IO)

    fun getArtistById(id: String) =
        queries.getArtistById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun getArtistsForSong(songId: String) =
        queries.getArtistsForSong(songId).asFlow().mapToList(Dispatchers.IO)

    fun insertArtist(
        id: String,
        name: String,
        thumbnailUrl: String? = null,
        channelId: String? = null,
        bookmarkedAt: String? = null
    ) {
        queries.insertArtist(
            id = id,
            name = name,
            thumbnailUrl = thumbnailUrl,
            channelId = channelId,
            lastUpdateTime = LocalDateTime.now().toString(),
            bookmarkedAt = bookmarkedAt
        )
    }

    fun updateArtistBookmarked(id: String, bookmarked: Boolean) {
        queries.updateArtistBookmarked(
            bookmarkedAt = if (bookmarked) LocalDateTime.now().toString() else null,
            id = id
        )
    }

    fun deleteArtist(id: String) {
        queries.deleteArtist(id)
    }

    // ============ Playlist Operations ============

    fun getAllPlaylists() =
        queries.getAllPlaylists().asFlow().mapToList(Dispatchers.IO)

    fun getPlaylistById(id: String) =
        queries.getPlaylistById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    // Synchronous snapshots — sync uses these to work out what the remote no longer has.
    fun getAllPlaylistsOnce() = queries.getAllPlaylists().executeAsList()

    fun getAllAlbumsOnce() = queries.getAllAlbums().executeAsList()

    fun getAllArtistsOnce() = queries.getAllArtists().executeAsList()

    fun getLikedSongsOnce() = queries.getLikedSongs { id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath, _rowOrder ->
        Song(id, title, duration, thumbnailUrl, albumId, albumName, explicit, year, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal, isDownloaded, localPath)
    }.executeAsList()

    fun updatePlaylistBookmarked(id: String, bookmarked: Boolean) {
        queries.updatePlaylistBookmarked(
            bookmarkedAt = if (bookmarked) LocalDateTime.now().toString() else null,
            id = id
        )
    }

    fun getSongsForPlaylist(playlistId: String) =
        queries.getSongsForPlaylist(playlistId).asFlow().mapToList(Dispatchers.IO)

    fun insertPlaylist(
        id: String,
        name: String,
        browseId: String? = null,
        isEditable: Boolean = true,
        bookmarkedAt: String? = null,
        remoteSongCount: Int? = null,
        thumbnailUrl: String? = null
    ) {
        queries.insertPlaylist(
            id = id,
            name = name,
            browseId = browseId,
            createdAt = LocalDateTime.now().toString(),
            lastUpdateTime = LocalDateTime.now().toString(),
            isEditable = if (isEditable) 1L else 0L,
            bookmarkedAt = bookmarkedAt,
            remoteSongCount = remoteSongCount?.toLong(),
            thumbnailUrl = thumbnailUrl
        )
    }

    fun addSongToPlaylist(playlistId: String, songId: String, position: Int) {
        queries.insertPlaylistSong(playlistId, songId, position.toLong())
    }

    fun isSongInPlaylist(playlistId: String, songId: String): Boolean {
        return queries.isSongInPlaylist(playlistId, songId).executeAsOne() > 0
    }

    fun getPlaylistSongCount(playlistId: String): Int {
        return queries.getPlaylistSongCount(playlistId).executeAsOne().toInt()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        queries.deletePlaylistSong(playlistId, songId)
    }

    fun deletePlaylist(id: String) {
        queries.deletePlaylist(id)
    }

    /** Create a local (non-YouTube) playlist. Returns the new playlist id. */
    fun createLocalPlaylist(name: String): String {
        val id = "LP" + System.currentTimeMillis()
        insertPlaylist(id = id, name = name, browseId = null, isEditable = true)
        return id
    }

    fun renamePlaylist(id: String, newName: String) {
        queries.renamePlaylist(newName, id)
    }

    fun movePlaylistSong(playlistId: String, songId: String, newPosition: Int) {
        queries.movePlaylistSong(newPosition.toLong(), playlistId, songId)
    }

    // ============ Song-Artist Name Lookup ============

    /** Get joined artist names for a single song (blocking) */
    fun getArtistNamesForSong(songId: String): String {
        return queries.getArtistNamesForSong(songId).executeAsOneOrNull()
            ?: "Unknown Artist"
    }

    /** Get all song→artist name mappings at once (for efficient library display) */
    fun getAllSongArtistNames(): Map<String, String> {
        return queries.getAllSongArtistNames { songId, artistNames ->
            songId to artistNames
        }.executeAsList().toMap()
    }

    // ============ Search ============

    fun searchSongsWithArtists(query: String) =
        queries.searchSongsWithArtists(query, query, query).executeAsList()

    fun searchAlbums(query: String) =
        queries.searchAlbums(query).executeAsList()

    fun searchArtists(query: String) =
        queries.searchArtists(query).executeAsList()

    fun searchPlaylists(query: String) =
        queries.searchPlaylists(query).executeAsList()

    // ============ Song-Artist Mapping ============

    fun insertSongArtistMap(songId: String, artistId: String, position: Int) {
        queries.insertSongArtistMap(songId, artistId, position.toLong())
    }

    fun deleteSongArtistMaps(songId: String) {
        queries.deleteSongArtistMaps(songId)
    }

    // ============ Download Queue ============

    fun getPendingDownloads() =
        queries.getPendingDownloads().asFlow().mapToList(Dispatchers.IO)

    fun getDownloadingItems() =
        queries.getDownloadingItems().asFlow().mapToList(Dispatchers.IO)

    fun addToDownloadQueue(songId: String) {
        queries.insertDownloadQueue(
            songId = songId,
            status = "pending",
            progress = 0L,
            addedAt = LocalDateTime.now().toString(),
            error = null
        )
    }

    fun updateDownloadProgress(songId: String, progress: Int, status: String) {
        queries.updateDownloadProgress(progress.toLong(), status, songId)
    }

    fun updateDownloadError(songId: String, error: String) {
        queries.updateDownloadError(error, songId)
    }

    fun removeFromDownloadQueue(songId: String) {
        queries.deleteFromDownloadQueue(songId)
    }

    // ============ Search History ============

    fun getSearchHistory() =
        queries.getSearchHistory().asFlow().mapToList(Dispatchers.IO)

    fun addSearchHistory(query: String) {
        queries.insertSearchHistory(query, LocalDateTime.now().toString())
    }

    fun deleteSearchHistory(query: String) {
        queries.deleteSearchHistory(query)
    }

    fun clearSearchHistory() {
        queries.clearSearchHistory()
    }

    // ============ Play Queue Persistence ============

    data class QueueItem(
        val songId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
        val durationMs: Long,
        val album: String?,
        val durationSec: Int
    )

    data class QueueState(
        val currentIndex: Int,
        val shuffleEnabled: Boolean,
        val repeatMode: String,
        val positionMs: Long
    )

    fun savePlayQueue(items: List<QueueItem>, state: QueueState) {
        queries.clearPlayQueue()
        items.forEachIndexed { index, item ->
            queries.insertPlayQueueItem(
                position = index.toLong(),
                songId = item.songId,
                title = item.title,
                artist = item.artist,
                thumbnailUrl = item.thumbnailUrl,
                durationMs = item.durationMs,
                album = item.album,
                durationSec = item.durationSec.toLong()
            )
        }
        queries.savePlayQueueState(
            currentIndex = state.currentIndex.toLong(),
            shuffleEnabled = if (state.shuffleEnabled) 1L else 0L,
            repeatMode = state.repeatMode,
            positionMs = state.positionMs
        )
    }

    fun getPlayQueue(): List<QueueItem> {
        return queries.getPlayQueue().executeAsList().map {
            QueueItem(
                songId = it.songId,
                title = it.title,
                artist = it.artist,
                thumbnailUrl = it.thumbnailUrl,
                durationMs = it.durationMs,
                album = it.album,
                durationSec = it.durationSec.toInt()
            )
        }
    }

    fun getPlayQueueState(): QueueState? {
        return queries.getPlayQueueState().executeAsOneOrNull()?.let {
            QueueState(
                currentIndex = it.currentIndex.toInt(),
                shuffleEnabled = it.shuffleEnabled == 1L,
                repeatMode = it.repeatMode,
                positionMs = it.positionMs
            )
        }
    }

    // ============ Events / Stats ============

    data class PlayEvent(
        val eventId: Long,
        val songId: String,
        val timestamp: Long,
        val playTime: Long,
        val title: String,
        val thumbnailUrl: String?,
        val albumName: String?,
        val albumId: String?
    )

    data class SongStats(
        val id: String,
        val title: String,
        val thumbnailUrl: String?,
        val albumName: String?,
        val albumId: String?,
        val duration: Long,
        val playCount: Long,
        val totalTime: Long
    )

    data class ArtistStats(
        val id: String,
        val name: String,
        val thumbnailUrl: String?,
        val playCount: Long,
        val totalTime: Long
    )

    data class AlbumStats(
        val id: String,
        val title: String,
        val thumbnailUrl: String?,
        val year: Long?,
        val playCount: Long,
        val totalTime: Long
    )

    fun recordEvent(songId: String, playTimeMs: Long) {
        val now = System.currentTimeMillis()
        queries.insertEvent(songId, now, playTimeMs)
        queries.incrementTotalPlayTime(playTimeMs, songId)
    }

    fun getAllEvents(): List<PlayEvent> {
        return queries.getAllEvents { eventId, songId, timestamp, playTime, title, thumbnailUrl, albumName, albumId ->
            PlayEvent(eventId, songId, timestamp, playTime, title, thumbnailUrl, albumName, albumId)
        }.executeAsList()
    }

    fun getEventCount(): Long {
        return queries.getEventCount().executeAsOne()
    }

    fun getFirstEventTimestamp(): Long? {
        return queries.getFirstEventTimestamp().executeAsOneOrNull()?.MIN
    }

    fun clearAllEvents() {
        queries.clearAllEvents()
    }

    fun getMostPlayedSongs(fromTimestamp: Long, toTimestamp: Long): List<SongStats> {
        return queries.mostPlayedSongs(fromTimestamp, toTimestamp) { id, title, thumbnailUrl, albumName, albumId, duration, playCount, totalTime ->
            SongStats(id, title, thumbnailUrl, albumName, albumId, duration, playCount, totalTime ?: 0)
        }.executeAsList()
    }

    fun getMostPlayedArtists(fromTimestamp: Long, toTimestamp: Long): List<ArtistStats> {
        return queries.mostPlayedArtists(fromTimestamp, toTimestamp) { id, name, thumbnailUrl, playCount, totalTime ->
            ArtistStats(id, name, thumbnailUrl, playCount, totalTime ?: 0)
        }.executeAsList()
    }

    fun getMostPlayedAlbums(fromTimestamp: Long, toTimestamp: Long): List<AlbumStats> {
        return queries.mostPlayedAlbums(fromTimestamp, toTimestamp) { id, title, thumbnailUrl, year, playCount, totalTime ->
            AlbumStats(id, title, thumbnailUrl, year, playCount, totalTime ?: 0)
        }.executeAsList()
    }

    fun getTotalPlayTimeInRange(fromTimestamp: Long, toTimestamp: Long): Long {
        return queries.getTotalPlayTimeInRange(fromTimestamp, toTimestamp).executeAsOne()
    }

    fun getUniqueSongCountInRange(fromTimestamp: Long, toTimestamp: Long): Long {
        return queries.getUniqueSongCountInRange(fromTimestamp, toTimestamp).executeAsOne()
    }

    fun getUniqueArtistCountInRange(fromTimestamp: Long, toTimestamp: Long): Long {
        return queries.getUniqueArtistCountInRange(fromTimestamp, toTimestamp).executeAsOne()
    }

    // ============ Recognition History ============

    data class Recognition(
        val id: Long,
        val videoId: String?,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
        val recognizedAt: Long
    )

    fun insertRecognition(videoId: String?, title: String, artist: String, thumbnailUrl: String?) {
        queries.insertRecognition(videoId, title, artist, thumbnailUrl, System.currentTimeMillis())
    }

    fun getRecognitionHistory(): List<Recognition> {
        return queries.getRecognitionHistory { id, videoId, title, artist, thumbnailUrl, recognizedAt ->
            Recognition(id, videoId, title, artist, thumbnailUrl, recognizedAt)
        }.executeAsList()
    }

    fun deleteRecognition(id: Long) {
        queries.deleteRecognition(id)
    }

    fun clearRecognitionHistory() {
        queries.clearRecognitionHistory()
    }
}
