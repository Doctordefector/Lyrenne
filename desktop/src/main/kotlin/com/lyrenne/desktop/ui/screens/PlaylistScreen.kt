package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.headerScrolledAway
import com.lyrenne.desktop.ui.components.DetailHeader
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.select
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
import com.lyrenne.desktop.ui.components.CollapsibleSearchField
import com.lyrenne.desktop.ui.components.PlaylistSortMenu
import com.lyrenne.desktop.ui.components.rememberPlaylistSortState
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
    val playingId by player.select { it.currentSong?.id }
    var query by remember { mutableStateOf("") }
    val sortState = rememberPlaylistSortState()
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

    // A search or a sort has to see the whole playlist, not just the pages scrolled to so far
    val needsAllPages = query.isNotBlank() || !sortState.isDefault
    LaunchedEffect(needsAllPages, playlistPage) {
        if (needsAllPages && playlistPage != null) {
            isLoadingMore = true
            try {
                loadAllPages()
            } finally {
                isLoadingMore = false // also when clearing the search cancels this
            }
        }
    }

    // Play All and a click play the songs in the order shown (issue #16)
    val orderedSongs = remember(allSongs, sortState.sort, sortState.reversed, sortState.seed) {
        sortState.apply(allSongs, { it.title }, { song -> song.artists.joinToString { it.name } }, { it.album?.name })
    }
    val shownSongs = remember(orderedSongs, query) {
        orderedSongs.withIndex().filter { (_, song) ->
            matchesQuery(query, song.title, song.album?.name, song.artists.joinToString { it.name })
        }
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    val orderedInfos = remember(orderedSongs) { orderedSongs.map { it.toDesktopSongInfo() } }
    val playAll = { scope.launch { player.playQueue(orderedInfos, 0) }; Unit }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, playlistPage?.playlist?.title.orEmpty(), collapsed, onPlay = if (playlistPage != null) playAll else null)

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
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)
                ) {
                    item {
                        DetailHeader(
                            thumbnail = page.playlist.thumbnail,
                            kind = "Playlist",
                            title = page.playlist.title,
                            meta = listOfNotNull(page.playlist.author?.name, page.playlist.songCountText).joinToString(" • "),
                            onPlay = playAll,
                            onShuffle = { scope.launch { player.playQueue(allSongs.map { it.toDesktopSongInfo() }.shuffled(), 0) } },
                            onDownload = {
                                // Load ALL remaining pages before downloading
                                scope.launch {
                                    loadAllPages()
                                    DownloadManager.queueDownloads(
                                        allSongs.map { it.toDesktopSongInfo() },
                                        subfolder = page.playlist.title
                                    )
                                }
                            },
                            below = { CarExportStatus() },
                            more = { close ->
                                DropdownMenuItem(
                                    text = { Text("Smart shuffle") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                    onClick = {
                                        close()
                                        scope.launch { player.smartShuffle(allSongs.map { it.toDesktopSongInfo() }) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export to folder") },
                                    leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
                                    onClick = {
                                        close()
                                        val targetDir = chooseExportFolder(page.playlist.title)
                                        if (targetDir != null) scope.launch {
                                            loadAllPages()
                                            CarExport.exportSongs(allSongs.map { it.toDesktopSongInfo() }, targetDir)
                                        }
                                    }
                                )
                            }
                        )
                    }

                    // Search and sort share one row at the head of the list.
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            CollapsibleSearchField(query, { query = it }, placeholder = "Search this playlist")
                            PlaylistSortMenu(sortState)
                        }
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
                        TrackRow(
                            song = orderedInfos[index],
                            player = player,
                            explicit = song.explicit,
                            isPlaying = playingId == song.id,
                            onClick = { scope.launch { player.playQueue(orderedInfos, index) } }
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
