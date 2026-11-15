package com.lyrenne.desktop.movein

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/**
 * Reads a MetroList backup into plain data, without touching Lyrenne's database.
 *
 * Three shapes reach here, all a ZIP holding a Room database called `song.db`:
 * - **MetroList 13.x and earlier** (Android): `song.db` plus `settings.preferences_pb`, a
 *   DataStore protobuf. Room schema up to version 35.
 * - **MetroList 14.x** (the KMP rewrite, Android, desktop and iOS): `backup.json` (a manifest),
 *   `settings.json`, `song.db`, sometimes `song.db-wal`. Room schema 36 and up. Liked songs moved
 *   out of the database into the `likedSongsJson` setting, newest first.
 * - **InnerTune and its forks** (OuterTune and friends): the same Room family under older versions.
 *
 * Every table and column is looked up before it is read, so a fork that lacks one degrades to
 * importing less rather than failing. Room stores dates as the epoch milliseconds of a
 * LocalDateTime taken as UTC, which is really the phone's wall clock; [wallClock] undoes that.
 *
 * The settings files of both versions carry the MetroList login cookie. They are never written
 * to disk, and only the liked songs key of `settings.json` is ever looked at.
 */
object MetroListBackup {

    /** Larger than any real library; stops a hostile ZIP from filling the disk. */
    private const val MAX_DB_BYTES = 768L * 1024 * 1024
    private const val MAX_SETTINGS_BYTES = 32L * 1024 * 1024

    class NotABackup(message: String) : Exception(message)

    /**
     * Opens [file] (a `.backup`/`.zip` from MetroList, or a bare `song.db`) and reads it.
     * Blocking: call off the UI thread.
     */
    fun read(file: File, zone: ZoneId = ZoneId.systemDefault()): MetroListLibrary {
        val work = Files.createTempDirectory("lyrenne-movein").toFile()
        try {
            val db = File(work, "song.db")
            var settingsJson: String? = null
            var hasManifest = false
            if (isSqlite(file)) {
                file.copyTo(db)
            } else {
                var foundDb = false
                try {
                    ZipInputStream(file.inputStream().buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            // Entry names are matched exactly and the target named here, so
                            // nothing in the archive decides where a file lands.
                            when (entry.name) {
                                "song.db" -> { zip.copyLimited(db, MAX_DB_BYTES); foundDb = true }
                                "song.db-wal" -> zip.copyLimited(File(work, "song.db-wal"), MAX_DB_BYTES)
                                "settings.json" -> settingsJson = zip.readLimited(MAX_SETTINGS_BYTES)
                                "backup.json" -> hasManifest = true
                                // settings.preferences_pb (13.x) is skipped unread: it is the
                                // login cookie plus app settings that mean nothing here.
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } catch (e: java.util.zip.ZipException) {
                    throw NotABackup("This file isn't a MetroList backup.")
                }
                if (!foundDb) throw NotABackup("This file has no MetroList library in it.")
            }
            return readDatabase(db, likedSongsFromSettings(settingsJson), hasManifest, zone)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun isSqlite(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(16)
            input.read(head) == 16 && String(head, Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    }.getOrDefault(false)

    private fun InputStream.copyLimited(target: File, limit: Long) {
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw NotABackup("This backup is larger than any real library.")
                out.write(buffer, 0, n)
            }
        }
    }

    private fun InputStream.readLimited(limit: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw NotABackup("This backup's settings are larger than expected.")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ 14.x liked songs

    /**
     * 14.x keeps likes in `settings.json` as `{"entries":[{"key":"likedSongsJson","value":"[...]"}]}`,
     * the value being a JSON list of song items, newest like first. Anything else in the file is
     * ignored, including the credentials it carries.
     */
    internal fun likedSongsFromSettings(settingsJson: String?): List<MlSong>? {
        if (settingsJson.isNullOrBlank()) return null
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val root = runCatching { json.parseToJsonElement(settingsJson).jsonObject }.getOrNull() ?: return null
        val entries = root["entries"] as? JsonArray ?: return null
        val value = entries.firstNotNullOfOrNull { e ->
            val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o["key"]?.jsonPrimitive?.contentOrNull == "likedSongsJson") o["value"]?.jsonPrimitive?.contentOrNull else null
        } ?: return null
        val items = runCatching { json.parseToJsonElement(value).jsonArray }.getOrNull() ?: return null
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            if (id.isBlank() || id.startsWith("file:") || o.bool("isLocal") == true) return@mapNotNull null
            // A song renamed in MetroList keeps its real title as canonicalTitle.
            val title = o.str("canonicalTitle") ?: o.str("title") ?: return@mapNotNull null
            val artistsKey = if (o["canonicalArtists"] is JsonArray && (o["canonicalArtists"] as JsonArray).isNotEmpty()) "canonicalArtists" else "artists"
            val artists = (o[artistsKey] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                val name = ao.str("name") ?: return@mapNotNull null
                MlArtistRef(ao.str("id"), name)
            }
            val album = o["album"] as? JsonObject
            MlSong(
                id = id,
                title = title,
                artists = artists,
                albumId = album?.str("id")?.takeIf { it.isNotBlank() },
                albumName = album?.str("name")?.takeIf { it.isNotBlank() },
                durationSec = (o["duration"] as? JsonPrimitive)?.longOrNull?.toInt() ?: -1,
                thumbnailUrl = o.str("thumbnail"),
                explicit = o.bool("isExplicit") == true || o.bool("explicit") == true,
                year = (o["year"] as? JsonPrimitive)?.longOrNull?.toInt(),
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    // ------------------------------------------------------------------ the Room database

    internal fun readDatabase(
        db: File,
        settingsLikes: List<MlSong>?,
        hasManifest: Boolean,
        zone: ZoneId
    ): MetroListLibrary {
        val connection = try {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}")
        } catch (e: Exception) {
            throw NotABackup("The library in this backup can't be opened.")
        }
        connection.use { c ->
            val version = c.single("PRAGMA user_version")?.toInt() ?: 0
            val tables = c.list("SELECT name FROM sqlite_master WHERE type='table'") { it.getString(1) }.toSet()
            if ("song" !in tables) {
                throw NotABackup(
                    if ("playlist_song" in tables) "This backup is from a very old InnerTune and can't be read."
                    else "This file has no MetroList library in it."
                )
            }
            val songCols = c.columns("song")
            val flavor = when {
                hasManifest || version >= 36 -> BackupFlavor.METROLIST_14
                "isUploaded" in songCols || "speed_dial_item" in tables -> BackupFlavor.METROLIST_13
                else -> BackupFlavor.INNERTUNE
            }

            fun wall(ms: Long?): LocalDateTime? = ms?.takeIf { it > 0 }?.let { wallClock(it) }

            // Artists, and each song's credits in order
            val artistNames = HashMap<String, String>()
            val followed = ArrayList<MlArtist>()
            if ("artist" in tables) {
                val cols = c.columns("artist")
                c.each("SELECT * FROM artist") { rs ->
                    val id = rs.getString("id") ?: return@each
                    val name = rs.getString("name") ?: return@each
                    artistNames[id] = name
                    val bookmarked = rs.longOrNull(cols, "bookmarkedAt")
                    val podcast = rs.longOrNull(cols, "isPodcastChannel") == 1L
                    val local = rs.longOrNull(cols, "isLocal") == 1L
                    if (bookmarked != null && !podcast && !local) {
                        followed += MlArtist(
                            id = id, name = name,
                            thumbnailUrl = rs.strOrNull(cols, "thumbnailUrl"),
                            channelId = rs.strOrNull(cols, "channelId"),
                            bookmarkedAt = wall(bookmarked) ?: LocalDateTime.now()
                        )
                    }
                }
            }
            val credits = HashMap<String, MutableList<Pair<Long, MlArtistRef>>>()
            if ("song_artist_map" in tables) {
                c.each("SELECT songId, artistId, position FROM song_artist_map") { rs ->
                    val songId = rs.getString(1) ?: return@each
                    val artistId = rs.getString(2) ?: return@each
                    val name = artistNames[artistId] ?: return@each
                    credits.getOrPut(songId) { ArrayList() } += rs.getLong(3) to MlArtistRef(artistId, name)
                }
            }

            // Songs
            val songs = LinkedHashMap<String, MlSong>()
            val likedFromDb = ArrayList<Pair<String, LocalDateTime?>>()
            val library = ArrayList<Pair<String, LocalDateTime>>()
            c.each("SELECT * FROM song") { rs ->
                val id = rs.getString("id") ?: return@each
                if (id.isBlank() || id.startsWith("file:")) return@each
                if (rs.longOrNull(songCols, "isLocal") == 1L) return@each   // a file on the phone
                val song = MlSong(
                    id = id,
                    title = rs.getString("title") ?: return@each,
                    artists = credits[id].orEmpty().sortedBy { it.first }.map { it.second },
                    albumId = rs.strOrNull(songCols, "albumId"),
                    albumName = rs.strOrNull(songCols, "albumName"),
                    durationSec = rs.longOrNull(songCols, "duration")?.toInt() ?: -1,
                    thumbnailUrl = rs.strOrNull(songCols, "thumbnailUrl"),
                    explicit = rs.longOrNull(songCols, "explicit") == 1L,
                    year = rs.longOrNull(songCols, "year")?.toInt(),
                )
                songs[id] = song
                if (rs.longOrNull(songCols, "liked") == 1L) {
                    likedFromDb += id to wall(rs.longOrNull(songCols, "likedDate"))
                }
                rs.longOrNull(songCols, "inLibrary")?.let { at -> wall(at)?.let { library += id to it } }
            }

            // Likes: 14.x's settings list first (it is the authority there), then any the
            // database also marks, newest first. A like with no date gets one just under the
            // previous so the order survives.
            val liked = LinkedHashMap<String, LocalDateTime?>()
            settingsLikes?.forEach { s ->
                songs.putIfAbsent(s.id, s)
                liked[s.id] = null
            }
            likedFromDb.sortedByDescending { it.second ?: LocalDateTime.MIN }.forEach { (id, at) ->
                if (id in liked) { if (liked[id] == null) liked[id] = at } else liked[id] = at
            }
            val likes = datedInOrder(liked.entries.map { it.key to it.value })

            // Albums the user saved
            val albums = ArrayList<MlAlbum>()
            val albumRows = HashMap<String, MlAlbum>()
            if ("album" in tables) {
                val cols = c.columns("album")
                c.each("SELECT * FROM album") { rs ->
                    val id = rs.getString("id") ?: return@each
                    if (rs.longOrNull(cols, "isLocal") == 1L) return@each
                    val saved = rs.longOrNull(cols, "bookmarkedAt") ?: rs.longOrNull(cols, "likedDate")
                        ?: rs.longOrNull(cols, "inLibrary")
                    val album = MlAlbum(
                        id = id,
                        playlistId = rs.strOrNull(cols, "playlistId"),
                        title = rs.getString("title") ?: return@each,
                        year = rs.longOrNull(cols, "year")?.toInt(),
                        thumbnailUrl = rs.strOrNull(cols, "thumbnailUrl"),
                        songCount = rs.longOrNull(cols, "songCount")?.toInt() ?: 0,
                        durationSec = rs.longOrNull(cols, "duration")?.toInt() ?: 0,
                        explicit = rs.longOrNull(cols, "explicit") == 1L,
                        savedAt = wall(saved)
                    )
                    albumRows[id] = album
                    if (album.savedAt != null) albums += album
                }
            }

            // Playlists, in their order
            val playlistSongs = HashMap<String, MutableList<String>>()
            if ("playlist_song_map" in tables) {
                c.each("SELECT playlistId, songId FROM playlist_song_map ORDER BY playlistId, position, id") { rs ->
                    val pid = rs.getString(1) ?: return@each
                    val sid = rs.getString(2) ?: return@each
                    if (sid in songs) playlistSongs.getOrPut(pid) { ArrayList() } += sid
                }
            }
            val playlists = ArrayList<MlPlaylist>()
            if ("playlist" in tables) {
                val cols = c.columns("playlist")
                c.each("SELECT * FROM playlist") { rs ->
                    val id = rs.getString("id") ?: return@each
                    if (id in AUTO_PLAYLISTS) return@each
                    val browseId = rs.strOrNull(cols, "browseId")?.takeIf { it.isNotBlank() }
                    if (browseId != null && browseId.removePrefix("VL") in YOUTUBE_AUTO_PLAYLISTS) return@each
                    playlists += MlPlaylist(
                        id = id,
                        name = rs.getString("name") ?: return@each,
                        browseId = browseId,
                        createdAt = wall(rs.longOrNull(cols, "createdAt")),
                        isEditable = rs.longOrNull(cols, "isEditable") != 0L,
                        thumbnailUrl = rs.strOrNull(cols, "customThumbnailUrl") ?: rs.strOrNull(cols, "thumbnailUrl"),
                        songIds = playlistSongs[id].orEmpty()
                    )
                }
            }

            // Plays. 14.x also stores plays it pulled from YouTube's history, with no listening
            // time; those were never played in MetroList and would only inflate the stats.
            val plays = ArrayList<MlPlay>()
            if ("event" in tables) {
                val cols = c.columns("event")
                val known = if ("playTimeKnown" in cols) " WHERE playTimeKnown = 1" else ""
                c.each("SELECT songId, timestamp, playTime FROM event$known ORDER BY timestamp, id") { rs ->
                    val sid = rs.getString(1) ?: return@each
                    if (sid !in songs) return@each
                    val at = wall(rs.getLong(2)) ?: return@each
                    plays += MlPlay(sid, at.atZone(zone).toInstant().toEpochMilli(), rs.getLong(3).coerceAtLeast(0))
                }
            }

            // Lyrics the user chose or wrote, and per-song timing
            val lyrics = ArrayList<MlLyrics>()
            val offsets = HashMap<String, Long>()
            if ("lyricsOffset" in songCols) {
                c.each("SELECT id, lyricsOffset FROM song WHERE lyricsOffset != 0") { rs ->
                    rs.getString(1)?.let { if (it in songs) offsets[it] = rs.getLong(2) }
                }
            }
            if ("lyrics" in tables) {
                val cols = c.columns("lyrics")
                c.each("SELECT * FROM lyrics") { rs ->
                    val id = rs.getString("id") ?: return@each
                    if (id !in songs) return@each
                    val text = rs.strOrNull(cols, "lyrics")
                    val provider = rs.strOrNull(cols, "provider") ?: "Unknown"
                    val userText = rs.strOrNull(cols, "userLyrics")?.takeIf { it.isNotBlank() }
                    when {
                        // 14.x: written by hand
                        userText != null -> lyrics += MlLyrics(id, EDITED, userText)
                        // 14.x: picked by hand from a search
                        rs.longOrNull(cols, "isUserSelected") == 1L && !text.isNullOrBlank() && text != NOT_FOUND ->
                            lyrics += MlLyrics(id, provider, text)
                        // 13.x: the only lyrics it marks as the user's own. Everything else in
                        // that table is a cache of what the providers sent, which Lyrenne fetches itself.
                        provider == "Manual" && !text.isNullOrBlank() && text != NOT_FOUND ->
                            lyrics += MlLyrics(id, EDITED, text)
                    }
                    rs.longOrNull(cols, "offsetMs")?.takeIf { it != 0L }?.let { offsets[id] = it }
                }
            }

            return MetroListLibrary(
                flavor = flavor,
                databaseVersion = version,
                songs = songs,
                likes = likes,
                librarySongs = library.sortedByDescending { it.second },
                artists = followed.sortedByDescending { it.bookmarkedAt },
                albums = albums.sortedByDescending { it.savedAt },
                albumRows = albumRows,
                playlists = playlists,
                plays = plays,
                lyrics = lyrics,
                lyricsOffsets = offsets
            )
        }
    }

    /** Room's stored value is a wall-clock LocalDateTime written down as if it were UTC. */
    internal fun wallClock(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

    /** Fills missing dates so a newest-first list stays newest first once sorted by date. */
    internal fun datedInOrder(items: List<Pair<String, LocalDateTime?>>): List<Pair<String, LocalDateTime>> {
        // Start far enough above the first real date that the undated ones before it fit in between
        val firstDated = items.indexOfFirst { it.second != null }
        var previous: LocalDateTime =
            if (firstDated < 0) LocalDateTime.now() else items[firstDated].second!!.plusSeconds(firstDated + 1L)
        return items.map { (id, at) ->
            val date = if (at != null && at < previous) at else previous.minusSeconds(1)
            previous = date
            id to date
        }
    }

    internal const val EDITED = "Edited"
    private const val NOT_FOUND = "LYRICS_NOT_FOUND"
    /** MetroList's virtual playlists, if a fork ever wrote them down as rows. */
    private val AUTO_PLAYLISTS = setOf("LP_LIKED", "LP_DOWNLOADED")
    /** YouTube's own Liked Music and Episodes for Later: covered by likes, or not music. */
    private val YOUTUBE_AUTO_PLAYLISTS = setOf("LM", "SE")

    // ------------------------------------------------------------------ JDBC helpers

    private fun Connection.single(sql: String): Long? =
        createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getLong(1) else null } }

    private fun <T> Connection.list(sql: String, map: (ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun Connection.each(sql: String, body: (ResultSet) -> Unit) {
        createStatement().use { st -> st.executeQuery(sql).use { rs -> while (rs.next()) body(rs) } }
    }

    private fun Connection.columns(table: String): Set<String> =
        list("PRAGMA table_info(\"$table\")") { it.getString("name") }.toSet()

    private fun ResultSet.strOrNull(cols: Set<String>, name: String): String? =
        if (name in cols) getString(name)?.takeIf { it.isNotBlank() } else null

    private fun ResultSet.longOrNull(cols: Set<String>, name: String): Long? {
        if (name !in cols) return null
        val v = getLong(name)
        return if (wasNull()) null else v
    }
}

enum class BackupFlavor(val label: String) {
    METROLIST_14("MetroList 14"),
    METROLIST_13("MetroList 13"),
    INNERTUNE("InnerTune or a fork of it"),
}

/** One artist credit. [id] is a YouTube channel id, or something local to MetroList. */
data class MlArtistRef(val id: String?, val name: String)

data class MlSong(
    val id: String,
    val title: String,
    val artists: List<MlArtistRef> = emptyList(),
    val albumId: String? = null,
    val albumName: String? = null,
    val durationSec: Int = -1,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false,
    val year: Int? = null,
)

data class MlArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val channelId: String?,
    val bookmarkedAt: LocalDateTime,
)

data class MlAlbum(
    val id: String,
    val playlistId: String?,
    val title: String,
    val year: Int?,
    val thumbnailUrl: String?,
    val songCount: Int,
    val durationSec: Int,
    val explicit: Boolean,
    /** Null for an album MetroList only knew because a song was on it. */
    val savedAt: LocalDateTime?,
)

data class MlPlaylist(
    val id: String,
    val name: String,
    /** The YouTube playlist this mirrors, or null for one made in MetroList. */
    val browseId: String?,
    val createdAt: LocalDateTime?,
    val isEditable: Boolean,
    val thumbnailUrl: String?,
    val songIds: List<String>,
) {
    val isLocal get() = browseId == null
}

/** A play, at a real epoch time in milliseconds, with how long it was listened to. */
data class MlPlay(val songId: String, val epochMs: Long, val playTimeMs: Long)

data class MlLyrics(val songId: String, val source: String, val text: String)

data class MetroListLibrary(
    val flavor: BackupFlavor,
    val databaseVersion: Int,
    val songs: Map<String, MlSong>,
    /** Newest first. */
    val likes: List<Pair<String, LocalDateTime>>,
    /** Songs added to the library, newest first. */
    val librarySongs: List<Pair<String, LocalDateTime>>,
    val artists: List<MlArtist>,
    val albums: List<MlAlbum>,
    /** Every album row, saved or not, so stats can group plays by album. */
    val albumRows: Map<String, MlAlbum>,
    val playlists: List<MlPlaylist>,
    val plays: List<MlPlay>,
    val lyrics: List<MlLyrics>,
    val lyricsOffsets: Map<String, Long>,
) {
    val localPlaylists get() = playlists.filter { it.isLocal }
    val youTubePlaylists get() = playlists.filterNot { it.isLocal }
    val firstPlayYear: Int? get() = plays.minOfOrNull { it.epochMs }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year
    }
    val isEmpty get() = likes.isEmpty() && librarySongs.isEmpty() && artists.isEmpty() &&
        albums.isEmpty() && playlists.isEmpty() && plays.isEmpty()
}
