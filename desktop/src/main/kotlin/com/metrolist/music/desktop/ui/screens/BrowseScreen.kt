package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Generic browse screen for InnerTube browse endpoints:
 * mood/genre pages, charts (FEmusic_charts), and other browseId destinations.
 */
@Composable
fun BrowseScreen(
    browseId: String,
    params: String?,
    title: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<BrowseResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val prefs by PreferencesManager.preferences.collectAsState()

    LaunchedEffect(browseId, params) {
        try {
            isLoading = true
            error = null
            YouTube.browse(browseId, params).onSuccess { browseResult ->
                result = browseResult.filterExplicit(prefs.hideExplicit)
                isLoading = false
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e("Browse error ($browseId): ${e.message?.take(200)}")
                error = friendlyErrorMessage(e, "Failed to load content")
                isLoading = false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyErrorMessage(e, "Failed to load content")
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                result?.title ?: title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(result?.items.orEmpty()) { section ->
                            if (section.items.isNotEmpty()) {
                                Column {
                                    section.title?.let { sectionTitle ->
                                        Text(
                                            sectionTitle,
                                            style = MaterialTheme.typography.titleLarge,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(section.items) { item ->
                                            BrowseItemCard(
                                                item = item,
                                                onClick = {
                                                    when (item) {
                                                        is SongItem -> scope.launch {
                                                            item.toSongInfo()?.let { player.playSong(it) }
                                                        }
                                                        is AlbumItem -> onAlbumClick(item.browseId)
                                                        is ArtistItem -> onArtistClick(item.id)
                                                        is PlaylistItem -> onPlaylistClick(item.id)
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
}

@Composable
private fun BrowseItemCard(
    item: YTItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.width(160.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = when (item) {
                    is SongItem -> item.artists.joinToString { it.name }
                    is AlbumItem -> item.artists?.joinToString { it.name } ?: ""
                    is ArtistItem -> "Artist"
                    is PlaylistItem -> item.author?.name ?: "Playlist"
                    else -> ""
                }
                if (subtitle.isNotEmpty()) {
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
}
