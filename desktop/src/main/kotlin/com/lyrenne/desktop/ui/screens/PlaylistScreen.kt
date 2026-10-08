package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.lyrenne.desktop.download.CarExport
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.ui.components.CarExportStatus
import com.lyrenne.desktop.ui.components.PlaylistSearchField
import com.lyrenne.desktop.ui.components.matchesQuery
import com.lyrenne.desktop.ui.components.chooseExportFolder
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun PlaylistScreen(
    playlistId: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: App keeps this screen's state while it is under another on the back
    // stack, so going back restores the page and scroll position instead of refetching.
    var playlistPage by rememberSaveable { mutableStateOf<PlaylistPage?>(null) }
    var allSongs by rememberSaveable { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var continuation by rememberSaveable { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()
    var query by remember { mutableStateOf("") }
    // One page fetch at a time: scrolling, searching and Download All each page through the
    // playlist, and two of them reading the same continuation appended that page twice
    val pageLock = remember { Mutex() }

    LaunchedEffect(playlistId) {
        if (playlistPage != null) return@LaunchedEffect // restored from the back stack
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

    /** Appends the next page. False once there is none, or when it fails to load. */
    suspend fun loadNextPage(): Boolean = pageLock.withLock {
        val cont = continuation ?: return false
        YouTube.playlistContinuation(cont).onSuccess { page ->
            allSongs = allSongs + page.songs
            continuation = page.continuation
        }.isSuccess
    }

    suspend fun loadAllPages() {
        while (loadNextPage()) Unit
    }

    // Load more songs when reaching the end
    fun loadMore() {
        if (continuation == null || isLoadingMore) return
        scope.launch {
            isLoadingMore = true
            loadNextPage()
            isLoadingMore = false
        }
    }

    // A search has to see the whole playlist, not just the pages scrolled to so far
    LaunchedEffect(query.isNotBlank(), playlistPage) {
        if (query.isNotBlank() && playlistPage != null) {
            isLoadingMore = true
            try {
                loadAllPages()
            } finally {
                isLoadingMore = false // also when clearing the search cancels this
            }
        }
    }

    val shownSongs = remember(allSongs, query) {
        allSongs.withIndex().filter { (_, song) ->
            matchesQuery(query, song.title, song.album?.name, song.artists.joinToString { it.name })
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

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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
                                            scope.launch { player.smartShuffle(allSongs.map { it.toDesktopSongInfo() }) }
                                        }
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Smart Shuffle")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            // Load ALL remaining pages before downloading
                                            scope.launch {
                                                loadAllPages()
                                                DownloadManager.queueDownloads(
                                                    allSongs.map { it.toDesktopSongInfo() },
                                                    subfolder = page.playlist.title
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Download All")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val targetDir = chooseExportFolder(page.playlist.title) ?: return@OutlinedButton
                                            scope.launch {
                                                loadAllPages()
                                                CarExport.exportSongs(allSongs.map { it.toDesktopSongInfo() }, targetDir)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.DirectionsCar, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Export to Folder")
                                    }
                                }

                                CarExportStatus()
                            }
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    item {
                        PlaylistSearchField(query, { query = it }, Modifier.padding(bottom = 8.dp))
                    }

                    if (shownSongs.isEmpty() && query.isNotBlank() && !isLoadingMore) {
                        item {
                            Text(
                                "No songs in this playlist match \"${query.trim()}\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    // Song list. A click plays the whole playlist from that song, filtered or not.
                    items(shownSongs) { (index, song) ->
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
