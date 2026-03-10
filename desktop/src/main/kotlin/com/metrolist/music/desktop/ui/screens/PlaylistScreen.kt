package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(
    playlistId: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var playlistPage by remember { mutableStateOf<PlaylistPage?>(null) }
    var allSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var continuation by remember { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()

    LaunchedEffect(playlistId) {
        isLoading = true
        error = null
        YouTube.playlist(playlistId).onSuccess { page ->
            playlistPage = page
            allSongs = page.songs
            continuation = page.songsContinuation
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load playlist")
        }
        isLoading = false
    }

    // Load more songs when reaching the end
    fun loadMore() {
        val cont = continuation ?: return
        if (isLoadingMore) return
        scope.launch {
            isLoadingMore = true
            YouTube.playlistContinuation(cont).onSuccess { page ->
                allSongs = allSongs + page.songs
                continuation = page.continuation
            }
            isLoadingMore = false
        }
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
                "Playlist",
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
                                YouTube.playlist(playlistId).onSuccess { page ->
                                    playlistPage = page
                                    allSongs = page.songs
                                    continuation = page.songsContinuation
                                }.onFailure { error = friendlyErrorMessage(it, "Failed to load playlist") }
                                isLoading = false
                            }
                        }) { Text("Retry") }
                    }
                }
            }
            playlistPage != null -> {
                val page = playlistPage!!

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Playlist header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            AsyncImage(
                                model = page.playlist.thumbnail,
                                contentDescription = page.playlist.title,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    page.playlist.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                page.playlist.author?.let { author ->
                                    Text(
                                        author.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                page.playlist.songCountText?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val songInfos = allSongs.map { it.toDesktopSongInfo() }
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
                                                val songInfos = allSongs.map { it.toDesktopSongInfo() }.shuffled()
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
                                            val songInfos = allSongs.map { it.toDesktopSongInfo() }
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

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Song list
                    itemsIndexed(allSongs) { index, song ->
                        PlaylistSongItem(
                            song = song,
                            isPlaying = playerState.currentSong?.id == song.id,
                            onClick = {
                                scope.launch {
                                    val songInfos = allSongs.map { it.toDesktopSongInfo() }
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
                            },
                            onArtistClick = { artistId ->
                                onArtistClick(artistId)
                            }
                        )

                        // Load more when near end
                        if (index == allSongs.size - 5 && continuation != null) {
                            LaunchedEffect(index) { loadMore() }
                        }
                    }

                    // Loading more indicator
                    if (isLoadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSongItem(
    song: SongItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onArtistClick: (String) -> Unit
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
            Row {
                song.artists.forEachIndexed { index, artist ->
                    Text(
                        artist.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (artist.id != null) Modifier.clickable {
                            artist.id?.let { onArtistClick(it) }
                        } else Modifier
                    )
                    if (index < song.artists.size - 1) {
                        Text(", ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        leadingContent = {
            Box {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                if (isPlaying) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Playing",
                        modifier = Modifier.size(48.dp).padding(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                song.duration?.let { dur ->
                    Text(
                        formatPlaylistDuration(dur),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = { onAddToQueue(); showMenu = false },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
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

private fun formatPlaylistDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
