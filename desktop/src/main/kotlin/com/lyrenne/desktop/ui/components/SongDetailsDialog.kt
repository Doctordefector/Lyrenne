package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Album
import kotlinx.coroutines.flow.firstOrNull

/** Big cover plus album, year and length of a song. Opened by clicking the player's album art. */
@Composable
fun SongDetailsDialog(song: SongInfo, onDismiss: () -> Unit) {
    // Album and year: the library row when it has them, else YouTube (queue → album page).
    var album by remember(song.id) { mutableStateOf(song.album) }
    var year by remember(song.id) { mutableStateOf<Int?>(null) }
    // Only runs when the dialog opens, and at most once per song per session: the library row
    // first (no request), then two small requests, cached so reopening asks YouTube nothing.
    LaunchedEffect(song.id) {
        detailsCache[song.id]?.let { (a, y) -> album = a ?: album; year = y; return@LaunchedEffect }
        val db = DatabaseHelper.getSongById(song.id).firstOrNull()
        db?.albumName?.let { album = it }
        year = db?.year?.toInt()
        if (year == null) {
            val albumRef = db?.albumId?.let { Album(album ?: "", it) }
                ?: YouTube.queue(listOf(song.id)).getOrNull()?.firstOrNull()?.album
            if (albumRef != null) {
                if (album == null) album = albumRef.name
                year = YouTube.album(albumRef.id, withSongs = false).getOrNull()?.album?.year
            }
        }
        detailsCache[song.id] = album to year
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(Modifier.width(360.dp)) {
                AsyncImage(
                    model = largeCover(song.thumbnailUrl),
                    contentDescription = "Album art",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(16.dp))
                // One step down from titleLarge: long YouTube titles ("[From ...] (feat. ...)") wrapped
                // into a block that outweighed the cover.
                Text(
                    song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                album?.let { Detail("Album", it) }
                // Music videos have no album, so no year: the row goes rather than reading Unknown.
                year?.let { Detail("Released", it.toString()) }
                val length = song.knownDurationMs()
                if (length > 0) Detail("Length", formatTime(length))
            }
        }
    )
}

// ponytail: unbounded, but one entry per song whose details were opened by hand this session.
private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Int?>>()

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label, Modifier.width(72.dp).alignByBaseline(),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Album art URLs carry their size (`=w120-h120-…`); ask for a big one. Video frames stay as they are. */
internal fun largeCover(url: String?): String? =
    squareCropFriendly(url)?.replace(Regex("=w\\d+-h\\d+"), "=w720-h720")
