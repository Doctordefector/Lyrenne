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
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.pages.PodcastPage
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.launch

@Composable
fun PodcastScreen(
    podcastId: String,
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var podcastPage by remember { mutableStateOf<PodcastPage?>(null) }
    var allEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()

    LaunchedEffect(podcastId) {
        isLoading = true
        error = null
        YouTube.podcast(podcastId).onSuccess { page ->
            podcastPage = page
            allEpisodes = page.episodes
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load podcast")
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
                "Podcast",
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
                                YouTube.podcast(podcastId).onSuccess { page ->
                                    podcastPage = page
                                    allEpisodes = page.episodes
                                }.onFailure { error = friendlyErrorMessage(it, "Failed to load podcast") }
                                isLoading = false
                            }
                        }) { Text("Retry") }
                    }
                }
            }
            podcastPage != null -> {
                val page = podcastPage!!

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Podcast header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            AsyncImage(
                                model = page.podcast.thumbnail,
                                contentDescription = page.podcast.title,
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
                                    page.podcast.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                page.podcast.author?.let { author ->
                                    Text(
                                        author.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                page.podcast.episodeCountText?.let {
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
                                                val songInfos = allEpisodes.map { it.toSongInfo() }
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
                                                val songInfos = allEpisodes.map { it.toSongInfo() }.shuffled()
                                                player.playQueue(songInfos, 0)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Shuffle")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Episodes header
                    item {
                        Text(
                            "Episodes (${allEpisodes.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Episode list
                    itemsIndexed(allEpisodes) { index, episode ->
                        EpisodeListItem(
                            episode = episode,
                            isPlaying = playerState.currentSong?.id == episode.id,
                            onClick = {
                                scope.launch {
                                    val songInfos = allEpisodes.map { it.toSongInfo() }
                                    player.playQueue(songInfos, index)
                                }
                            },
                            onPlayNext = {
                                player.addToQueueNext(episode.toSongInfo())
                            },
                            onAddToQueue = {
                                player.addToQueue(episode.toSongInfo())
                            },
                            onDownload = {
                                DownloadManager.queueDownload(episode.toSongInfo())
                            }
                        )

                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeListItem(
    episode: EpisodeItem,
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
                episode.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                episode.publishDateText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                episode.duration?.let { dur ->
                    Text(
                        formatEpisodeDuration(dur),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            Box {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                if (isPlaying) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Playing",
                        modifier = Modifier.size(56.dp).padding(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        trailingContent = {
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
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun EpisodeItem.toSongInfo(): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = author?.name ?: podcast?.name ?: "Unknown",
        thumbnailUrl = thumbnail,
        duration = duration ?: -1,
        album = podcast?.name
    )
}

private fun formatEpisodeDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
