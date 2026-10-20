package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.ConfirmDialog
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.headerScrolledAway
import com.lyrenne.desktop.ui.components.DetailHeader
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.select
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.db.Song
import com.lyrenne.desktop.download.CarExport
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.sync.YouTubeWrites
import com.lyrenne.desktop.ui.AutoPlaylistType
import com.lyrenne.desktop.ui.components.CarExportStatus
import com.lyrenne.desktop.ui.components.CollapsibleSearchField
import com.lyrenne.desktop.ui.components.PlaylistSortMenu
import com.lyrenne.desktop.ui.components.rememberPlaylistSortState
import com.lyrenne.desktop.ui.components.matchesQuery
import com.lyrenne.desktop.ui.components.chooseExportFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detail screen for user-created local playlists: rename, delete, reorder, remove songs. */
@Composable
fun LocalPlaylistScreen(
    playlistId: String,
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playlist by remember(playlistId) { DatabaseHelper.getPlaylistById(playlistId) }.collectAsState(initial = null)
    val songs by remember(playlistId) { DatabaseHelper.getSongsForPlaylist(playlistId) }.collectAsState(initial = emptyList())
    val artistNamesMap = remember(songs) { DatabaseHelper.getAllSongArtistNames() }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val sortState = rememberPlaylistSortState()
    // Play All and a click play the songs in the order shown (issue #16)
    val ordered = remember(songs, artistNamesMap, sortState.sort, sortState.reversed, sortState.seed) {
        sortState.apply(songs.map { it.toPlaylistSongInfo(artistNamesMap) }, { it.title }, { it.artist }, { it.album })
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.suppressMediaKeys()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                DatabaseHelper.renamePlaylist(playlistId, renameText.trim())
                                YouTubeWrites.renamePlaylist(playlistId, renameText.trim())
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete playlist?",
            text = "\"${playlist?.name}\" is deleted here and on YouTube. The songs stay in your library.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    DatabaseHelper.deletePlaylist(playlistId)
                    YouTubeWrites.deletePlaylist(playlistId)
                }
                onBack()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    val playAll = { scope.launch { player.playQueue(ordered) }; Unit }
    val name = playlist?.name ?: "Playlist"
    val playingId by player.select { it.currentSong?.id }
    val searching = query.isNotBlank()
    // Moving a song only means something in the playlist's own order
    val canMove = !searching && sortState.isDefault
    val shown = remember(ordered, query) {
        ordered.withIndex().filter { (_, song) -> matchesQuery(query, song.title, song.artist, song.album) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, name, collapsed, onPlay = if (songs.isNotEmpty()) playAll else null)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)) {
            item {
                DetailHeader(
                    thumbnail = playlist?.thumbnailUrl ?: ordered.firstOrNull()?.thumbnailUrl,
                    kind = "Playlist",
                    title = name,
                    meta = "${songs.size} songs",
                    onPlay = playAll,
                    onShuffle = { scope.launch { player.playQueue(ordered.shuffled()) } },
                    onDownload = { DownloadManager.queueDownloads(ordered, subfolder = playlist?.name) },
                    below = { CarExportStatus() },
                    more = { close ->
                        DropdownMenuItem(
                            text = { Text("Smart shuffle") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = { close(); scope.launch { player.smartShuffle(ordered) } }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to folder") },
                            leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
                            onClick = {
                                close()
                                chooseExportFolder(name)?.let { CarExport.exportSongs(ordered, it) }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { close(); renameText = playlist?.name ?: ""; showRenameDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { close(); showDeleteDialog = true }
                        )
                    }
                )
            }

            if (songs.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "This playlist is empty",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Add songs from any song's menu: ⋮ or right-click",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item { ListToolbar(query, { query = it }) { PlaylistSortMenu(sortState) } }
                if (searching && shown.isEmpty()) item { NoMatches(query) }
                // A click plays the whole playlist from that song, filtered or not
                items(shown.size) { position ->
                    val (index, song) = shown[position]
                    PlaylistSongRow(
                        song = song,
                        isPlaying = playingId == song.id,
                        player = player,
                        onClick = { scope.launch { player.playQueue(ordered, index) } },
                        onMoveUp = if (index > 0 && canMove) {
                            { scope.launch(Dispatchers.IO) { reorderPlaylistSongs(playlistId, songs, index, index - 1) } }
                        } else null,
                        onMoveDown = if (index < songs.size - 1 && canMove) {
                            { scope.launch(Dispatchers.IO) { reorderPlaylistSongs(playlistId, songs, index, index + 1) } }
                        } else null,
                        onRemove = {
                            scope.launch(Dispatchers.IO) {
                                DatabaseHelper.removeSongFromPlaylist(playlistId, song.id)
                                YouTubeWrites.removeFromPlaylist(playlistId, song.id)
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** Search and sort in one row at the head of a song list. */
@Composable
private fun ListToolbar(query: String, onQueryChange: (String) -> Unit, sort: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        CollapsibleSearchField(query, onQueryChange, placeholder = "Search this playlist")
        sort()
    }
}

/** Auto playlists: Liked Songs, Downloaded, Most Played — read-only system playlists. */
@Composable
fun AutoPlaylistScreen(
    type: AutoPlaylistType,
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val artistNamesMap = remember { DatabaseHelper.getAllSongArtistNames() }

    // Liked + Downloaded are reactive flows; Most Played is a one-shot stats query
    val likedSongs by remember { DatabaseHelper.getLikedSongs() }.collectAsState(initial = emptyList())
    val downloadedSongs by remember { DatabaseHelper.getDownloadedSongs() }.collectAsState(initial = emptyList())
    var mostPlayed by remember { mutableStateOf<List<DatabaseHelper.SongStats>>(emptyList()) }

    LaunchedEffect(type) {
        if (type == AutoPlaylistType.MOST_PLAYED) {
            mostPlayed = withContext(Dispatchers.IO) {
                DatabaseHelper.getMostPlayedSongs(0, System.currentTimeMillis())
            }
        }
    }

    var query by remember { mutableStateOf("") }
    val sortState = rememberPlaylistSortState()
    val unsorted: List<SongInfo> = when (type) {
        AutoPlaylistType.LIKED -> likedSongs.map { it.toPlaylistSongInfo(artistNamesMap) }
        AutoPlaylistType.DOWNLOADED -> downloadedSongs.map { it.toPlaylistSongInfo(artistNamesMap) }
        AutoPlaylistType.MOST_PLAYED -> mostPlayed.map {
            SongInfo(
                id = it.id,
                title = it.title,
                artist = artistNamesMap[it.id] ?: it.albumName ?: "",
                album = it.albumName,
                duration = it.duration.toInt(),
                thumbnailUrl = it.thumbnailUrl
            )
        }
    }
    val songInfos = remember(unsorted, sortState.sort, sortState.reversed, sortState.seed) {
        sortState.apply(unsorted, { it.title }, { it.artist }, { it.album })
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    val playAll = { scope.launch { player.playQueue(songInfos) }; Unit }
    val playingId by player.select { it.currentSong?.id }
    val shown = remember(songInfos, query) {
        songInfos.withIndex().filter { (_, song) -> matchesQuery(query, song.title, song.artist, song.album) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, type.title, collapsed, onPlay = if (songInfos.isNotEmpty()) playAll else null)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)) {
            item {
                DetailHeader(
                    thumbnail = songInfos.firstOrNull()?.thumbnailUrl,
                    kind = "Auto playlist",
                    title = type.title,
                    meta = "${songInfos.size} songs",
                    onPlay = playAll,
                    onShuffle = { scope.launch { player.playQueue(songInfos.shuffled()) } },
                    onDownload = if (type == AutoPlaylistType.DOWNLOADED) null else {
                        { DownloadManager.queueDownloads(songInfos, subfolder = type.title) }
                    },
                    more = { close ->
                        DropdownMenuItem(
                            text = { Text("Smart shuffle") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = { close(); scope.launch { player.smartShuffle(songInfos) } }
                        )
                    }
                )
            }
            if (songInfos.isEmpty()) {
                item {
                    Text(
                        when (type) {
                            AutoPlaylistType.LIKED -> "No liked songs yet"
                            AutoPlaylistType.DOWNLOADED -> "No downloads yet"
                            AutoPlaylistType.MOST_PLAYED -> "Play some music to build this playlist"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp)
                    )
                }
            } else {
                item {
                    ListToolbar(query, { query = it }) {
                        when (type) {
                            AutoPlaylistType.MOST_PLAYED -> PlaylistSortMenu(sortState, "Play count", "Most played" to "Least played")
                            else -> PlaylistSortMenu(sortState, type.dateLabel, "Newest first" to "Oldest first")
                        }
                    }
                }
                if (query.isNotBlank() && shown.isEmpty()) item { NoMatches(query) }
                items(shown.size) { position ->
                    val (index, song) = shown[position]
                    PlaylistSongRow(
                        song = song,
                        isPlaying = playingId == song.id,
                        player = player,
                        onClick = { scope.launch { player.playQueue(songInfos, index) } }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private val AutoPlaylistType.dateLabel get() = if (this == AutoPlaylistType.DOWNLOADED) "Date downloaded" else "Date liked"

@Composable
private fun NoMatches(query: String) {
    Text(
        "No songs in this playlist match \"${query.trim()}\"",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun PlaylistSongRow(
    song: SongInfo,
    isPlaying: Boolean,
    player: DesktopPlayer,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    TrackRow(song = song, player = player, isPlaying = isPlaying, onClick = onClick) { close ->
        if (onMoveUp != null || onMoveDown != null || onRemove != null) HorizontalDivider()
        if (onMoveUp != null) {
            DropdownMenuItem(
                text = { Text("Move up") },
                onClick = { onMoveUp(); close() },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }
            )
        }
        if (onMoveDown != null) {
            DropdownMenuItem(
                text = { Text("Move down") },
                onClick = { onMoveDown(); close() },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }
            )
        }
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text("Remove from playlist", color = MaterialTheme.colorScheme.error) },
                onClick = { onRemove(); close() },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

/** Swap positions of two songs in a local playlist, then rewrite all positions sequentially. */
private fun reorderPlaylistSongs(playlistId: String, songs: List<Song>, from: Int, to: Int) {
    val reordered = songs.toMutableList()
    val item = reordered.removeAt(from)
    reordered.add(to, item)
    DatabaseHelper.transaction {
        reordered.forEachIndexed { position, song ->
            DatabaseHelper.movePlaylistSong(playlistId, song.id, position)
        }
    }
}

private fun Song.toPlaylistSongInfo(artistNamesMap: Map<String, String>): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = artistNamesMap[id] ?: albumName ?: "Unknown Artist",
        album = albumName,
        duration = duration.toInt(),
        thumbnailUrl = thumbnailUrl
    )
}
