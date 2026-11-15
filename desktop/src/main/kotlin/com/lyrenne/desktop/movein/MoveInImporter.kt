package com.lyrenne.desktop.movein

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.db.LyrenneQueries
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime

/** What to bring over. Everything is on except pushing likes to YouTube, which writes to the account. */
data class MoveInOptions(
    val likes: Boolean = true,
    val library: Boolean = true,
    val playlists: Boolean = true,
    val albumsAndArtists: Boolean = true,
    val history: Boolean = true,
    val lyrics: Boolean = true,
    /**
     * Also like the imported songs on the signed-in YouTube account. Off by default: MetroList
     * usually synced its likes there already, the account may not even be the same one, and a
     * thousand likes are not something to undo by hand.
     */
    val likeOnYouTube: Boolean = false,
)

data class MoveInResult(
    val newLikes: Int = 0,
    val alreadyLiked: Int = 0,
    val newLibrarySongs: Int = 0,
    val newPlaylists: Int = 0,
    val mergedPlaylists: Int = 0,
    val playlistSongsAdded: Int = 0,
    val newAlbums: Int = 0,
    val newArtists: Int = 0,
    val newPlays: Int = 0,
    val skippedPlays: Int = 0,
    val lyrics: Int = 0,
    /** Songs liked by this import, newest first: what gets pushed to YouTube if asked. */
    val likedNow: List<String> = emptyList(),
) {
    val nothingNew get() = newLikes + newLibrarySongs + newPlaylists + playlistSongsAdded + newAlbums +
        newArtists + newPlays + lyrics == 0
}

/** Where hand-picked lyrics and timing go. Swapped in tests. */
interface LyricsSink {
    fun hasLyrics(songId: String): Boolean
    fun putLyrics(songId: String, source: String, text: String)
    fun offset(songId: String): Long
    fun setOffset(songId: String, ms: Long)
}

internal object AppLyricsSink : LyricsSink {
    override fun hasLyrics(songId: String) = com.lyrenne.desktop.lyrics.LyricsOverrides.get(songId) != null
    override fun putLyrics(songId: String, source: String, text: String) =
        com.lyrenne.desktop.lyrics.LyricsOverrides.put(songId, com.lyrenne.desktop.lyrics.LyricsCandidate(source, text))
    override fun offset(songId: String) = com.lyrenne.desktop.lyrics.LyricsOffsets.get(songId)
    override fun setOffset(songId: String, ms: Long) = com.lyrenne.desktop.lyrics.LyricsOffsets.set(songId, ms)
}

/**
 * Writes a [MetroListLibrary] into Lyrenne's database as a merge.
 *
 * Nothing that is already here is overwritten, and running it twice adds nothing the second
 * time: songs are INSERT OR IGNORE plus gap filling, likes only touch unliked rows, a play is
 * skipped if one for the same song at the same millisecond exists, playlists get an id derived
 * from MetroList's so a re-import finds and tops up the same one.
 *
 * Imported likes and library songs get `inLibrary` so they show under Library > Songs like
 * MetroList showed them. Sync prunes liked songs with `inLibrary` set that YouTube does not have,
 * so every imported like is recorded in [MoveInLedger], which that prune skips, until YouTube
 * itself has the song liked and owns it from then on. Followed artists go in the ledger for the
 * same reason. Saved albums are kept through `inLibrary`, which sync never prunes. See CLAUDE.md
 * "Move in".
 */
class MoveInImporter(
    private val transaction: ((LyrenneQueries) -> Unit) -> Unit,
    private val lyricsSink: LyricsSink = AppLyricsSink,
    private val ledger: MoveInLedger = MoveInLedger,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    fun import(lib: MetroListLibrary, options: MoveInOptions): MoveInResult {
        var result = MoveInResult()
        val followedHere = ArrayList<String>()
        val protectedLikes = ArrayList<String>()
        transaction { q ->
            result = write(q, lib, options, followedHere, protectedLikes)
        }
        if (followedHere.isNotEmpty()) ledger.addLocalArtists(followedHere)
        if (protectedLikes.isNotEmpty()) ledger.addLocalLikes(protectedLikes)
        if (options.lyrics) result = result.copy(lyrics = importLyrics(lib))
        return result
    }

    private fun write(
        q: LyrenneQueries,
        lib: MetroListLibrary,
        o: MoveInOptions,
        followedHere: MutableList<String>,
        protectedLikes: MutableList<String>,
    ): MoveInResult {
        val stamp = now().toString()

        // Which songs this import needs rows for
        val wanted = LinkedHashSet<String>()
        if (o.likes) lib.likes.forEach { wanted += it.first }
        if (o.library) lib.librarySongs.forEach { wanted += it.first }
        if (o.playlists) lib.localPlaylists.forEach { wanted += it.songIds }
        if (o.history) lib.plays.forEach { wanted += it.songId }
        wanted.retainAll(lib.songs.keys)

        val credited = HashSet<String>()
        for (id in wanted) {
            val s = lib.songs.getValue(id)
            q.moveInInsertSong(
                s.id, s.title, s.durationSec.toLong(), s.thumbnailUrl, s.albumId, s.albumName,
                if (s.explicit) 1L else 0L, s.year?.toLong()
            )
            q.moveInFillSong(s.durationSec.toLong(), s.thumbnailUrl, s.albumId, s.albumName, s.year?.toLong(), s.id)
            // Credits only for a song with none yet, so Lyrenne's own (from YouTube) stand
            if (s.artists.isNotEmpty() && q.moveInSongArtistCount(s.id).executeAsOne() == 0L) {
                s.artists.forEachIndexed { index, a ->
                    val artistId = artistIdFor(a)
                    q.insertArtistIfMissing(artistId, a.name, stamp)
                    q.insertSongArtistMap(s.id, artistId, index.toLong())
                }
                credited += s.id
            }
            // An Album row lets stats group these plays by album
            s.albumId?.let { albumId ->
                lib.albumRows[albumId]?.let { a ->
                    q.moveInInsertAlbum(a.id, a.playlistId, a.title, a.year?.toLong(), a.thumbnailUrl,
                        a.songCount.toLong(), a.durationSec.toLong(), if (a.explicit) 1L else 0L, stamp)
                }
            }
        }

        var newLikes = 0
        var alreadyLiked = 0
        val likedNow = ArrayList<String>()
        if (o.likes) {
            for ((id, at) in lib.likes) {
                if (id !in lib.songs) continue
                val before = q.moveInSongState(id).executeAsOneOrNull()
                if (before?.liked == 1L) { alreadyLiked++; continue }
                q.markSongLiked(at.toString(), id)
                // Shown under Songs, as MetroList showed it, and kept safe from the sync prune
                q.moveInSetInLibrary(at.toString(), id)
                protectedLikes += id
                newLikes++
                likedNow += id
            }
        }

        var newLibrary = 0
        if (o.library) {
            for ((id, at) in lib.librarySongs) {
                if (id !in lib.songs) continue
                val before = q.moveInSongState(id).executeAsOneOrNull() ?: continue
                if (before.inLibrary != null) continue
                q.moveInSetInLibrary(at.toString(), id)
                // A song liked here while signed out had no inLibrary, which is what spared it
                // from the prune. Now it has one, the ledger has to spare it instead.
                if (before.liked == 1L) protectedLikes += id
                newLibrary++
            }
        }

        var newPlaylists = 0
        var merged = 0
        var added = 0
        if (o.playlists) {
            for (p in lib.playlists) {
                if (p.isLocal) {
                    val localId = localPlaylistId(p.id)
                    val exists = q.getPlaylistById(localId).executeAsOneOrNull() != null
                    if (!exists) {
                        q.moveInInsertPlaylist(localId, p.name, null, (p.createdAt ?: now()).toString(), stamp,
                            1L, null, null, p.thumbnailUrl)
                        p.songIds.forEachIndexed { i, sid -> q.insertPlaylistSong(localId, sid, i.toLong()) }
                        newPlaylists++
                        added += p.songIds.size
                    } else {
                        // Top up: songs added in MetroList since the last import go on the end.
                        // Ones already here stay where the user put them.
                        val present = q.moveInPlaylistSongIds(localId).executeAsList().toHashSet()
                        var next = (q.moveInMaxPlaylistPosition(localId).executeAsOneOrNull()?.MAX ?: -1L) + 1
                        var any = false
                        for (sid in p.songIds) {
                            if (!present.add(sid)) continue
                            q.insertPlaylistSong(localId, sid, next++)
                            added++
                            any = true
                        }
                        if (any) merged++
                    }
                } else {
                    // A YouTube playlist: its songs live on YouTube and Lyrenne opens it from
                    // there, so only the reference comes over. Sync owns it from then on.
                    val browseId = p.browseId!!.removePrefix("VL")
                    if (q.getPlaylistById(browseId).executeAsOneOrNull() != null) continue
                    q.moveInInsertPlaylist(browseId, p.name, browseId, (p.createdAt ?: now()).toString(), stamp,
                        if (p.isEditable) 1L else 0L, null, p.songIds.size.takeIf { it > 0 }?.toLong(), p.thumbnailUrl)
                    newPlaylists++
                }
            }
        }

        var newAlbums = 0
        var newArtists = 0
        if (o.albumsAndArtists) {
            for (a in lib.albums) {
                q.moveInInsertAlbum(a.id, a.playlistId, a.title, a.year?.toLong(), a.thumbnailUrl,
                    a.songCount.toLong(), a.durationSec.toLong(), if (a.explicit) 1L else 0L, stamp)
                val row = q.getAlbumById(a.id).executeAsOne()
                if (row.inLibrary != null || row.bookmarkedAt != null) continue
                q.moveInKeepAlbum((a.savedAt ?: now()).toString(), a.id)
                newAlbums++
            }
            for (a in lib.artists) {
                q.insertArtistIfMissing(a.id, a.name, stamp)
                if (q.getArtistById(a.id).executeAsOne().bookmarkedAt != null) continue
                q.moveInFollowArtist(a.bookmarkedAt.toString(), a.thumbnailUrl, a.channelId, a.id)
                followedHere += a.id
                newArtists++
            }
        }

        var newPlays = 0
        var skipped = 0
        if (o.history) {
            for (p in lib.plays) {
                if (q.moveInEventExists(p.epochMs, p.songId).executeAsOne() > 0) { skipped++; continue }
                q.insertEvent(p.songId, p.epochMs, p.playTimeMs)
                q.incrementTotalPlayTime(p.playTimeMs, p.songId)
                newPlays++
            }
        }

        return MoveInResult(
            newLikes = newLikes, alreadyLiked = alreadyLiked, newLibrarySongs = newLibrary,
            newPlaylists = newPlaylists, mergedPlaylists = merged, playlistSongsAdded = added,
            newAlbums = newAlbums, newArtists = newArtists, newPlays = newPlays, skippedPlays = skipped,
            likedNow = likedNow
        )
    }

    private fun importLyrics(lib: MetroListLibrary): Int {
        var count = 0
        for (l in lib.lyrics) {
            if (lyricsSink.hasLyrics(l.songId)) continue
            lyricsSink.putLyrics(l.songId, l.source, l.text)
            count++
        }
        for ((id, ms) in lib.lyricsOffsets) {
            if (lyricsSink.offset(id) != 0L) continue
            // Same sign in both apps: positive shows the lyrics sooner. Lyrenne caps at 60 s.
            lyricsSink.setOffset(id, ms.coerceIn(-60_000L, 60_000L))
        }
        return count
    }

    companion object {
        /**
         * A MetroList playlist's id here. Starts with "LP" so YouTubeWrites treats it as local,
         * and is derived from MetroList's id so the next import finds the same playlist.
         */
        fun localPlaylistId(metroListId: String) = "LPML-$metroListId"

        /** Same rule as LibrarySync: a credit with no YouTube channel id gets a name-based one. */
        fun artistIdFor(a: MlArtistRef): String =
            a.id?.takeIf { it.startsWith("UC") || it.startsWith("MPLA") } ?: "unknown_${a.name.hashCode()}"
    }
}

/**
 * Likes and followed artists that came from an import, which YouTube may not know about. The
 * library sync's prunes skip them; without this the next sync would unlike or unfollow everything
 * the YouTube account does not also have. A like leaves the ledger once sync sees it on YouTube.
 * Lives in `data/`, so Lyrenne's own backups carry it.
 */
object MoveInLedger {
    @Serializable
    private data class State(val localArtists: Set<String> = emptySet(), val localLikes: Set<String> = emptySet())

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** Overridable for tests. */
    var file: File = File(AppPaths.dataDir, "movein.json")
        set(value) { synchronized(lock) { field = value; cache = null } }

    private var cache: State? = null

    private fun load(): State {
        cache?.let { return it }
        val s = runCatching { if (file.isFile) json.decodeFromString(State.serializer(), file.readText()) else State() }
            .onFailure { Timber.w("Could not read ${file.name}: ${it.message}") }
            .getOrDefault(State())
        cache = s
        return s
    }

    fun isLocalArtist(id: String): Boolean = synchronized(lock) { id in load().localArtists }

    fun addLocalArtists(ids: Collection<String>) = update { it.copy(localArtists = it.localArtists + ids) }

    /** True for a song liked by an import that YouTube has not been seen to have liked yet. */
    fun isLocalLike(id: String): Boolean = synchronized(lock) { id in load().localLikes }

    fun addLocalLikes(ids: Collection<String>) = update { it.copy(localLikes = it.localLikes + ids) }

    /**
     * Called by sync with YouTube's liked songs: those are YouTube's now, so an unlike made there
     * is mirrored here like any other.
     */
    fun releaseLikes(onYouTube: Set<String>) = synchronized(lock) {
        val s = load()
        if (s.localLikes.none { it in onYouTube }) return@synchronized
        update { it.copy(localLikes = it.localLikes - onYouTube) }
    }

    private fun update(change: (State) -> State) = synchronized(lock) {
        val next = change(load())
        cache = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(State.serializer(), next))
        }.onFailure { Timber.w("Could not save ${file.name}: ${it.message}") }
    }
}
