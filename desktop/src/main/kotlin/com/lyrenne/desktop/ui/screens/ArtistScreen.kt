package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.headerScrolledAway
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.DetailHeader
import com.lyrenne.desktop.ui.components.subtitle
import com.lyrenne.desktop.ui.components.MediaTile
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.select
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.metrolist.innertube.models.*
import com.metrolist.innertube.pages.ArtistPage
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.ui.components.ScrollableRow
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(
    browseId: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: App keeps this screen's state while it is under another on the back
    // stack, so going back restores the page and scroll position instead of refetching.
    var artistPage by rememberSaveable { mutableStateOf<ArtistPage?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val playingId by player.select { it.currentSong?.id }

    LaunchedEffect(browseId) {
        if (artistPage != null) return@LaunchedEffect // restored from the back stack
        isLoading = true
        error = null
        YouTube.artist(browseId).onSuccess {
            artistPage = it
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load artist")
        }
        isLoading = false
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    // Play and Shuffle take the first section, which is the artist's top songs.
    val topSongs = remember(artistPage) {
        artistPage?.sections?.firstOrNull()?.items?.filterIsInstance<SongItem>()?.map { it.toDesktopSongInfo() }.orEmpty()
    }
    val playTop = { if (topSongs.isNotEmpty()) scope.launch { player.playQueue(topSongs, 0) }; Unit }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, artistPage?.artist?.title.orEmpty(), collapsed, onPlay = if (topSongs.isNotEmpty()) playTop else null)

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
                                YouTube.artist(browseId).onSuccess { artistPage = it }
                                    .onFailure { error = friendlyErrorMessage(it, "Failed to load artist") }
                                isLoading = false
                            }
                        }) { Text("Retry") }
                    }
                }
            }
            artistPage != null -> {
                val page = artistPage!!

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)
                ) {
                    item {
                        DetailHeader(
                            thumbnail = page.artist.thumbnail,
                            kind = "Artist",
                            title = page.artist.title,
                            meta = listOfNotNull(page.subscriberCountText, page.monthlyListenerCount).joinToString(" • "),
                            onPlay = playTop,
                            onShuffle = { if (topSongs.isNotEmpty()) scope.launch { player.playQueue(topSongs.shuffled(), 0) } },
                            onDownload = null,
                            circleArt = true
                        )
                    }

                    // Description
                    page.description?.let { desc ->
                        if (desc.isNotBlank()) {
                            item {
                                var expanded by remember { mutableStateOf(false) }
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = !expanded }
                                        .padding(bottom = 16.dp)
                                )
                            }
                        }
                    }

                    // Sections (Songs, Albums, Singles, etc.)
                    page.sections.forEach { section ->
                        item {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        val songItems = section.items.filterIsInstance<SongItem>()
                        if (songItems.isNotEmpty() && section.title.contains("song", ignoreCase = true)) {
                            val songInfos = songItems.map { it.toDesktopSongInfo() }
                            songItems.forEachIndexed { idx, song ->
                                item {
                                    TrackRow(
                                        song = songInfos[idx],
                                        player = player,
                                        explicit = song.explicit,
                                        isPlaying = playingId == song.id,
                                        onClick = { scope.launch { player.playQueue(songInfos, idx) } }
                                    )
                                }
                            }
                        } else {
                            // Shelf for albums, singles, playlists, related artists
                            item {
                                ScrollableRow {
                                    items(section.items) { ytItem ->
                                        MediaTile(
                                            item = ytItem,
                                            player = player,
                                            subtitle = (ytItem as? AlbumItem)?.year?.toString() ?: ytItem.subtitle(),
                                            onClick = {
                                                when (ytItem) {
                                                    is AlbumItem -> onAlbumClick(ytItem.browseId)
                                                    is ArtistItem -> onArtistClick(ytItem.id)
                                                    is PlaylistItem -> onPlaylistClick(ytItem.id)
                                                    is SongItem -> scope.launch { player.playSong(ytItem.toDesktopSongInfo()) }
                                                    else -> {}
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}
