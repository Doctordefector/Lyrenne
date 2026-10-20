package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Collator
import kotlin.random.Random

/**
 * How one playlist's songs are shown (issue #16). DEFAULT is the order the playlist came in:
 * add order for YouTube and local playlists, unless someone moved songs by hand.
 */
enum class PlaylistSort { DEFAULT, TITLE, ARTIST, ALBUM, RANDOM }

class PlaylistSortState {
    var sort by mutableStateOf(PlaylistSort.DEFAULT)
    var reversed by mutableStateOf(false)
    var seed by mutableStateOf(0)

    /** The playlist's own order, so Move Up/Down mean what they say and paging can stop early. */
    val isDefault get() = sort == PlaylistSort.DEFAULT && !reversed

    fun <T> apply(songs: List<T>, title: (T) -> String?, artist: (T) -> String?, album: (T) -> String?): List<T> =
        sortPlaylist(songs, sort, reversed, seed, title, artist, album)
}

@Composable
fun rememberPlaylistSortState() = remember { PlaylistSortState() }

// Primary strength: case and accents don't separate "Él" from "el"
private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

/** Stable, so songs with the same key keep their playlist order. Blank keys go last either way. */
fun <T> sortPlaylist(
    songs: List<T>,
    sort: PlaylistSort,
    reversed: Boolean,
    seed: Int,
    title: (T) -> String?,
    artist: (T) -> String?,
    album: (T) -> String?
): List<T> {
    val key = when (sort) {
        PlaylistSort.DEFAULT -> return if (reversed) songs.reversed() else songs
        PlaylistSort.RANDOM -> return songs.shuffled(Random(seed))
        PlaylistSort.TITLE -> title
        PlaylistSort.ARTIST -> artist
        PlaylistSort.ALBUM -> album
    }
    val byKey = compareBy(collator) { s: T -> key(s).orEmpty() }
    return songs.sortedWith(compareBy<T> { key(it).isNullOrBlank() }.then(if (reversed) byKey.reversed() else byKey))
}

/**
 * One sort button with the orders and the direction in its menu, like Library's. [defaultLabel]
 * and [defaultDirections] name what DEFAULT means for this list, since Liked Songs comes newest
 * first and Most Played by play count.
 */
@Composable
fun PlaylistSortMenu(
    state: PlaylistSortState,
    defaultLabel: String = "Date added",
    defaultDirections: Pair<String, String> = "Oldest first" to "Newest first"
) {
    fun label(sort: PlaylistSort) = when (sort) {
        PlaylistSort.DEFAULT -> defaultLabel
        PlaylistSort.TITLE -> "Song name"
        PlaylistSort.ARTIST -> "Artist"
        PlaylistSort.ALBUM -> "Album"
        PlaylistSort.RANDOM -> "Random"
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label(state.sort))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlaylistSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(label(sort)) },
                    onClick = {
                        // Picking Random again deals a new order
                        if (sort == PlaylistSort.RANDOM) state.seed = Random.nextInt()
                        if (state.sort != sort) state.reversed = false
                        state.sort = sort
                        open = false
                    },
                    trailingIcon = { if (state.sort == sort) Icon(Icons.Default.Check, null) }
                )
            }
            if (state.sort != PlaylistSort.RANDOM) {
                HorizontalDivider()
                val (forward, backward) = if (state.sort == PlaylistSort.DEFAULT) defaultDirections else "A to Z" to "Z to A"
                listOf(false to forward, true to backward).forEach { (rev, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { state.reversed = rev; open = false },
                        trailingIcon = { if (state.reversed == rev) Icon(Icons.Default.Check, null) }
                    )
                }
            }
        }
    }
}
