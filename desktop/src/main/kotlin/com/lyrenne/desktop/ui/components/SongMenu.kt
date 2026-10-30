package com.lyrenne.desktop.ui.components

import com.lyrenne.desktop.sync.setLiked
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.YouTubeWrites
import kotlinx.coroutines.launch

/** Where a row's menu opens (the last press inside the row) and whether it is open. */
@Stable
class MenuAnchor {
    var open by mutableStateOf(false)
    var at by mutableStateOf(IntOffset.Zero)
}

@Composable
fun rememberMenuAnchor() = remember { MenuAnchor() }

/**
 * Put on the Box that wraps a row or tile. Remembers where each press lands, so the menu opens at
 * the cursor whether it came from the ⋮ button or a right-click, and opens it on a right-click.
 * Reads presses in the Initial pass so it sees them before the row's own click handling.
 */
fun Modifier.contextMenu(anchor: MenuAnchor): Modifier = pointerInput(anchor) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            anchor.at = event.changes.first().position.round()
            if (event.buttons.isSecondaryPressed) {
                anchor.open = true
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * A menu opened at [anchor]'s cursor point. Must sit inside the same Box that carries
 * [contextMenu]: it anchors a zero-size box at the press position, so the menu drops from the
 * cursor and flips above it near the bottom of the window, as a desktop context menu does.
 */
@Composable
fun AnchoredMenu(anchor: MenuAnchor, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.offset { anchor.at }.size(0.dp)) {
        DropdownMenu(expanded = anchor.open, onDismissRequest = { anchor.open = false }, content = content)
    }
}

/** The ⋮ button that opens [anchor]'s menu. */
@Composable
fun MenuButton(anchor: MenuAnchor, description: String = "More options") {
    Tip(description) {
        IconButton(onClick = { anchor.open = true }) { Icon(Icons.Default.MoreVert, description) }
    }
}

/**
 * The one song menu: every song row, tile and the player use it, so the entries cannot drift
 * apart again (five hand-written copies had: the player had no Like, Search no Remove download).
 * [extra] adds a screen's own entries after the shared ones, e.g. Move up or Remove from playlist.
 */
@Composable
fun SongMenu(
    anchor: MenuAnchor,
    song: SongInfo,
    player: DesktopPlayer,
    extra: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}
) {
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val close = { anchor.open = false }
    AnchoredMenu(anchor) {
        SongMenuItems(song, player, close, onAddToPlaylist = { showPlaylistPicker = true })
        extra(close)
    }
    if (showPlaylistPicker) {
        val playlists by remember { DatabaseHelper.getAllPlaylists() }.collectAsState(initial = emptyList())
        PlaylistPickerDialog(song = song, playlists = playlists, onDismiss = { showPlaylistPicker = false })
    }
}

@Composable
private fun ColumnScope.SongMenuItems(
    song: SongInfo,
    player: DesktopPlayer,
    close: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Only collected while the menu is open: menu content composes on expand.
    val dbSong by remember(song.id) { DatabaseHelper.getSongById(song.id) }.collectAsState(initial = null)
    val liked = dbSong?.liked == 1L
    val downloaded = dbSong?.isDownloaded == 1L || remember(song.id) { DownloadManager.isDownloaded(song.id) }

    DropdownMenuItem(
        text = { Text("Play next") },
        onClick = { player.addToQueueNext(song); close() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
    )
    DropdownMenuItem(
        text = { Text("Add to queue") },
        onClick = { player.addToQueue(song); close() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
    )
    DropdownMenuItem(
        text = { Text("Start radio") },
        onClick = { close(); scope.launch { player.startRadio(song) } },
        leadingIcon = { Icon(Icons.Default.Radio, null) }
    )
    DropdownMenuItem(
        text = { Text("Add to playlist") },
        onClick = { close(); onAddToPlaylist() },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(if (liked) "Remove from liked songs" else "Add to liked songs") },
        onClick = { close(); setLiked(song, !liked, downloaded) },
        leadingIcon = { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) }
    )
    if (downloaded) {
        DropdownMenuItem(
            text = { Text("Remove download") },
            onClick = { DownloadManager.deleteDownload(song.id); close() },
            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
        )
    } else {
        DropdownMenuItem(
            text = { Text("Download") },
            onClick = { DownloadManager.queueDownload(song); close() },
            leadingIcon = { Icon(Icons.Default.Download, null) }
        )
    }
}

