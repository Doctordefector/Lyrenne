package com.metrolist.music.desktop.ui.screens

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
import com.metrolist.music.desktop.playback.DesktopPlayer
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
    var artistPage by remember { mutableStateOf<ArtistPage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()

    LaunchedEffect(browseId) {
        isLoading = true
        error = null
        YouTube.artist(browseId).onSuccess {
            artistPage = it
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load artist")
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
                "Artist",
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Artist header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = page.artist.thumbnail,
                                contentDescription = page.artist.title,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    page.artist.title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                page.subscriberCountText?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                page.monthlyListenerCount?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Play/Shuffle buttons
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                // Play first section songs
                                                val songs = page.sections.firstOrNull()?.items
                                                    ?.filterIsInstance<SongItem>()
                                                    ?.map { it.toDesktopSongInfo() }
                                                if (!songs.isNullOrEmpty()) {
                                                    player.playQueue(songs, 0)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val songs = page.sections.firstOrNull()?.items
                                                    ?.filterIsInstance<SongItem>()
                                                    ?.map { it.toDesktopSongInfo() }
                                                    ?.shuffled()
                                                if (!songs.isNullOrEmpty()) {
                                                    player.playQueue(songs, 0)
                                                }
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }

                        // Check if section has songs (list view) or other items (carousel)
                        val songItems = section.items.filterIsInstance<SongItem>()
                        if (songItems.isNotEmpty() && section.title.contains("song", ignoreCase = true)) {
                            // Song list view
                            songItems.forEach { song ->
                                item {
                                    ArtistSongItem(
                                        song = song,
                                        isPlaying = playerState.currentSong?.id == song.id,
                                        onClick = {
                                            scope.launch {
                                                val songInfos = songItems.map { it.toDesktopSongInfo() }
                                                val idx = songItems.indexOf(song)
                                                player.playQueue(songInfos, idx)
                                            }
                                        },
                                        onAddToQueue = {
                                            player.addToQueue(song.toDesktopSongInfo())
                                        }
                                    )
                                }
                            }
                        } else {
                            // Carousel view for albums, singles, playlists, related artists
                            item {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(section.items) { ytItem ->
                                        ArtistSectionCard(
                                            item = ytItem,
                                            onClick = {
                                                when (ytItem) {
                                                    is AlbumItem -> onAlbumClick(ytItem.browseId)
                                                    is ArtistItem -> onArtistClick(ytItem.id)
                                                    is PlaylistItem -> onPlaylistClick(ytItem.id)
                                                    is SongItem -> {
                                                        scope.launch {
                                                            player.playSong(ytItem.toDesktopSongInfo())
                                                        }
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        )
                                    }
                                }
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
private fun ArtistSongItem(
    song: SongItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit
) {
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
            IconButton(onClick = onAddToQueue) {
                Icon(Icons.Default.AddToQueue, "Add to queue")
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ArtistSectionCard(
    item: YTItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(
                        if (item is ArtistItem) RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        else RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                    ),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = when (item) {
                    is AlbumItem -> item.year?.toString() ?: "Album"
                    is ArtistItem -> "Artist"
                    is PlaylistItem -> item.author?.name ?: "Playlist"
                    is SongItem -> item.artists.joinToString { it.name }
                    else -> ""
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
