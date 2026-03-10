package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.launch

@Composable
fun AlbumScreen(
    browseId: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var albumPage by remember { mutableStateOf<AlbumPage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()

    LaunchedEffect(browseId) {
        isLoading = true
        error = null
        YouTube.album(browseId).onSuccess {
            albumPage = it
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load album")
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "Album",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            scope.launch {
                                isLoading = true
                                error = null
                                YouTube.album(browseId).onSuccess { albumPage = it }
                                    .onFailure { error = friendlyErrorMessage(it, "Failed to load album") }
                                isLoading = false
                            }
                        }) { Text("Retry") }
                    }
                }
            }
            albumPage != null -> {
                val page = albumPage!!
                val songs = page.songs

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Album header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Album art
                            AsyncImage(
                                model = page.album.thumbnail,
                                contentDescription = page.album.title,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            // Album info
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    page.album.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Artists
                                page.album.artists?.let { artists ->
                                    Row {
                                        artists.forEachIndexed { index, artist ->
                                            Text(
                                                artist.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable {
                                                    artist.id?.let { onArtistClick(it) }
                                                }
                                            )
                                            if (index < artists.size - 1) {
                                                Text(", ", style = MaterialTheme.typography.bodyLarge)
                                            }
                                        }
                                    }
                                }

                                page.album.year?.let { year ->
                                    Text(
                                        "$year",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    "${songs.size} songs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(8.dp))

                                // Action buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val songInfos = songs.map { it.toDesktopSongInfo() }
                                                player.playQueue(songInfos, 0)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play All")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val songInfos = songs.map { it.toDesktopSongInfo() }.shuffled()
                                                player.playQueue(songInfos, 0)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Shuffle")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val songInfos = songs.map { it.toDesktopSongInfo() }
                                            DownloadManager.queueDownloads(songInfos)
                                        }
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Download All")
                                    }
                                }
                            }
                        }
                    }

                    // Divider
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Song list
                    itemsIndexed(songs) { index, song ->
                        AlbumSongItem(
                            song = song,
                            trackNumber = index + 1,
                            isPlaying = playerState.currentSong?.id == song.id,
                            onClick = {
                                scope.launch {
                                    val songInfos = songs.map { it.toDesktopSongInfo() }
                                    player.playQueue(songInfos, index)
                                }
                            },
                            onPlayNext = {
                                player.addToQueueNext(song.toDesktopSongInfo())
                            },
                            onAddToQueue = {
                                player.addToQueue(song.toDesktopSongInfo())
                            },
                            onDownload = {
                                DownloadManager.queueDownload(song.toDesktopSongInfo())
                            }
                        )
                    }

                    // Other versions
                    if (page.otherVersions.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Other Versions",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        page.otherVersions.forEach { version ->
                            item {
                                ListItem(
                                    headlineContent = {
                                        Text(version.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(
                                            version.artists?.joinToString { it.name } ?: "",
                                            maxLines = 1
                                        )
                                    },
                                    leadingContent = {
                                        AsyncImage(
                                            model = version.thumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        // Could navigate to this version
                                    }
                                )
                            }
                        }
                    }

                    // Bottom padding
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AlbumSongItem(
    song: SongItem,
    trackNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                song.artists.joinToString { it.name },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            if (isPlaying) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    "$trackNumber",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp)
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                song.duration?.let { dur ->
                    Text(
                        formatDuration(dur),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (song.explicit) {
                    Icon(
                        Icons.Default.Explicit,
                        contentDescription = "Explicit",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = { onPlayNext(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.QueuePlayNext, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = { onAddToQueue(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.AddToQueue, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = { onDownload(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

internal fun SongItem.toDesktopSongInfo(): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = artists.joinToString { it.name },
        thumbnailUrl = thumbnail,
        duration = duration ?: -1,
        album = album?.name
    )
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
