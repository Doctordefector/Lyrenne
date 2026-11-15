package com.lyrenne.desktop.movein

import java.text.NumberFormat
import java.util.Locale

/** The sentences the Move in windows show, kept apart from the UI so tests can pin them. */
object MoveInSummary {

    fun count(n: Int, one: String, many: String = one + "s", locale: Locale = Locale.getDefault()): String =
        "${NumberFormat.getIntegerInstance(locale).format(n)} ${if (n == 1) one else many}"

    /** "1,240 liked songs, 18 playlists, 9,300 plays since 2024" */
    fun headline(lib: MetroListLibrary, locale: Locale = Locale.getDefault()): String {
        val parts = buildList {
            if (lib.likes.isNotEmpty()) add(count(lib.likes.size, "liked song", locale = locale))
            if (lib.playlists.isNotEmpty()) add(count(lib.playlists.size, "playlist", locale = locale))
            if (lib.plays.isNotEmpty()) {
                val since = lib.firstPlayYear?.let { " since $it" }.orEmpty()
                add(count(lib.plays.size, "play", locale = locale) + since)
            }
            if (isEmpty() && lib.librarySongs.isNotEmpty()) add(count(lib.librarySongs.size, "song", locale = locale))
        }
        return parts.joinToString(", ").ifEmpty { "A library with nothing to bring over" }
    }

    /** One line per thing that changed, for the result screen. */
    fun resultLines(r: MoveInResult, locale: Locale = Locale.getDefault()): List<String> = buildList {
        fun c(n: Int, one: String, many: String = one + "s") = count(n, one, many, locale)
        if (r.newLikes > 0) add("${c(r.newLikes, "song")} liked")
        if (r.alreadyLiked > 0) add("${c(r.alreadyLiked, "song")} you had already liked")
        if (r.newLibrarySongs > 0) add("${c(r.newLibrarySongs, "song")} added to your library")
        if (r.newPlaylists > 0) add("${c(r.newPlaylists, "playlist")} added")
        if (r.mergedPlaylists > 0) add("${c(r.mergedPlaylists, "playlist")} topped up with new songs")
        if (r.newAlbums > 0) add("${c(r.newAlbums, "album")} saved")
        if (r.newArtists > 0) add("${c(r.newArtists, "artist")} followed")
        if (r.newPlays > 0) add("${c(r.newPlays, "play")} added to your history and stats")
        if (r.skippedPlays > 0) add("${c(r.skippedPlays, "play")} already here, skipped")
        if (r.lyrics > 0) add("Your own lyrics for ${c(r.lyrics, "song")}")
    }

    fun matchLine(s: PlaylistImportState, locale: Locale = Locale.getDefault()): String {
        val good = s.matches.count { it?.quality == MatchQuality.GOOD }
        val unsure = s.matches.count { it?.quality == MatchQuality.UNSURE }
        val none = s.matches.count { it != null && it.quality == MatchQuality.NONE }
        return buildList {
            add(count(good, "match", "matches", locale) )
            if (unsure > 0) add("$unsure to check")
            if (none > 0) add("$none not found")
        }.joinToString(", ")
    }
}
