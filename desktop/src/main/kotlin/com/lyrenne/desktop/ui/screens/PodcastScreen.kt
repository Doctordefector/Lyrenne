package com.lyrenne.desktop.ui.screens

import com.lyrenne.desktop.ui.components.SkeletonRows
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.headerScrolledAway
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.DetailHeader
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.select
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import kotlinx.coroutines.launch

@Composable
fun PodcastScreen(
    podcastId: String,
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: App keeps this screen's state while it is under another on the back
    // stack, so going back restores the page and scroll position instead of refetching.
    var podcastPage by rememberSaveable { mutableStateOf<PodcastPage?>(null) }
    var allEpisodes by rememberSaveable { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val playingId by player.select { it.currentSong?.id }

    LaunchedEffect(podcastId) {
        if (podcastPage != null) return@LaunchedEffect // restored from the back stack
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

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    val episodeInfos = remember(allEpisodes) { allEpisodes.map { it.toSongInfo() } }
    val playAll = { scope.launch { player.playQueue(episodeInfos, 0) }; Unit }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, podcastPage?.podcast?.title.orEmpty(), collapsed, onPlay = if (podcastPage != null) playAll else null)

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)) { SkeletonRows() }
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
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)
                ) {
                    item {
                        DetailHeader(
                            thumbnail = page.podcast.thumbnail,
                            kind = "Podcast",
                            title = page.podcast.title,
                            meta = listOfNotNull(page.podcast.author?.name, page.podcast.episodeCountText).joinToString(" • "),
                            onPlay = playAll,
                            onShuffle = { scope.launch { player.playQueue(episodeInfos.shuffled(), 0) } },
                            onDownload = null
                        )
                    }

                    itemsIndexed(allEpisodes) { index, episode ->
                        TrackRow(
                            song = episodeInfos[index],
                            player = player,
                            isPlaying = playingId == episode.id,
                            subtitle = episode.publishDateText ?: episodeInfos[index].artist,
                            onClick = { scope.launch { player.playQueue(episodeInfos, index) } }
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
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
