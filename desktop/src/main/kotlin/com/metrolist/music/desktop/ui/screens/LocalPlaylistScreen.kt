package com.metrolist.music.desktop.ui.screens

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
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.db.Song
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.media.suppressMediaKeys
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.ui.AutoPlaylistType
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
    val playlist by DatabaseHelper.getPlaylistById(playlistId).collectAsState(initial = null)
    val songs by DatabaseHelper.getSongsForPlaylist(playlistId).collectAsState(initial = emptyList())
    val artistNamesMap = remember(songs) { DatabaseHelper.getAllSongArtistNames() }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

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
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlist?.name}\" will be permanently deleted. Songs themselves are not removed from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch(Dispatchers.IO) {
                        DatabaseHelper.deletePlaylist(playlistId)
                    }
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${songs.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                renameText = playlist?.name ?: ""
                showRenameDialog = true
            }) {
                Icon(Icons.Default.Edit, "Rename")
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Play controls
        if (songs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val infos = songs.map { it.toPlaylistSongInfo(artistNamesMap) }
                    scope.launch { player.playQueue(infos) }
                }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play All")
                }
                OutlinedButton(onClick = {
                    val infos = songs.map { it.toPlaylistSongInfo(artistNamesMap) }.shuffled()
                    scope.launch { player.playQueue(infos) }
                }) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        "Add songs via the context menu on any track",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val playerState by player.state.collectAsState()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(songs.size) { index ->
                    val dbSong = songs[index]
                    val song = dbSong.toPlaylistSongInfo(artistNamesMap)
                    PlaylistSongRow(
                        song = song,
                        index = index,
                        total = songs.size,
                        isPlaying = playerState.currentSong?.id == song.id,
                        player = player,
                        onClick = {
                            val infos = songs.map { it.toPlaylistSongInfo(artistNamesMap) }
                            scope.launch { player.playQueue(infos, index) }
                        },
                        onMoveUp = if (index > 0) {
                            {
                                scope.launch(Dispatchers.IO) {
                                    reorderPlaylistSongs(playlistId, songs, index, index - 1)
                                }
                            }
                        } else null,
                        onMoveDown = if (index < songs.size - 1) {
                            {
                                scope.launch(Dispatchers.IO) {
                                    reorderPlaylistSongs(playlistId, songs, index, index + 1)
                                }
                            }
                        } else null,
                        onRemove = {
                            scope.launch(Dispatchers.IO) {
                                DatabaseHelper.removeSongFromPlaylist(playlistId, song.id)
                            }
                        }
                    )
                }
            }
        }
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
    val likedSongs by DatabaseHelper.getLikedSongs().collectAsState(initial = emptyList())
    val downloadedSongs by DatabaseHelper.getDownloadedSongs().collectAsState(initial = emptyList())
    var mostPlayed by remember { mutableStateOf<List<DatabaseHelper.SongStats>>(emptyList()) }

    LaunchedEffect(type) {
        if (type == AutoPlaylistType.MOST_PLAYED) {
            mostPlayed = withContext(Dispatchers.IO) {
                DatabaseHelper.getMostPlayedSongs(0, System.currentTimeMillis())
            }
        }
    }

    val songInfos: List<SongInfo> = when (type) {
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(type.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${songInfos.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (songInfos.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch { player.playQueue(songInfos) }
                }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play All")
                }
                OutlinedButton(onClick = {
                    scope.launch { player.playQueue(songInfos.shuffled()) }
                }) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (songInfos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (type) {
                        AutoPlaylistType.LIKED -> "No liked songs yet"
                        AutoPlaylistType.DOWNLOADED -> "No downloads yet"
                        AutoPlaylistType.MOST_PLAYED -> "Play some music to build this playlist"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val playerState by player.state.collectAsState()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(songInfos.size) { index ->
                    val song = songInfos[index]
                    PlaylistSongRow(
                        song = song,
                        index = index,
                        total = songInfos.size,
                        isPlaying = playerState.currentSong?.id == song.id,
                        player = player,
                        onClick = {
                            scope.launch { player.playQueue(songInfos, index) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSongRow(
    song: SongInfo,
    index: Int,
    total: Int,
    isPlaying: Boolean,
    player: DesktopPlayer,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        onClick = {
                            player.addToQueueNext(song)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        onClick = {
                            player.addToQueue(song)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Start Radio") },
                        onClick = {
                            showMenu = false
                            scope.launch { player.startRadio(song) }
                        },
                        leadingIcon = { Icon(Icons.Default.Radio, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = {
                            DownloadManager.queueDownload(song)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Download, null) }
                    )
                    if (onMoveUp != null || onMoveDown != null || onRemove != null) {
                        HorizontalDivider()
                    }
                    if (onMoveUp != null) {
                        DropdownMenuItem(
                            text = { Text("Move Up") },
                            onClick = {
                                onMoveUp()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }
                        )
                    }
                    if (onMoveDown != null) {
                        DropdownMenuItem(
                            text = { Text("Move Down") },
                            onClick = {
                                onMoveDown()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from Playlist", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onRemove()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
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
