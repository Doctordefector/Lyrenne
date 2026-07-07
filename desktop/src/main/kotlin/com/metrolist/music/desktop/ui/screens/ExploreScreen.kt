package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.MoodAndGenres
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.settings.PreferencesManager
import com.metrolist.music.desktop.ui.components.ScrollableRow
import kotlinx.coroutines.launch
import timber.log.Timber

/** In-memory cache to avoid re-fetching explore content on every navigation */
private object ExploreCache {
    var explorePage: ExplorePage? = null
    var moods: List<MoodAndGenres>? = null
}

@Composable
fun ExploreScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onBrowseClick: (browseId: String, params: String?, title: String) -> Unit = { _, _, _ -> }
) {
    val scope = rememberCoroutineScope()
    var explorePage by remember { mutableStateOf(ExploreCache.explorePage) }
    var moods by remember { mutableStateOf(ExploreCache.moods) }
    var isLoading by remember { mutableStateOf(explorePage == null) }
    var error by remember { mutableStateOf<String?>(null) }
    val prefs by PreferencesManager.preferences.collectAsState()

    LaunchedEffect(Unit) {
        if (ExploreCache.explorePage != null) return@LaunchedEffect
        try {
            isLoading = true
            YouTube.explore().onSuccess { page ->
                explorePage = page
                ExploreCache.explorePage = page
                isLoading = false
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e("Explore error: ${e.message?.take(200)}")
                error = friendlyErrorMessage(e, "Failed to load explore page")
                isLoading = false
            }
            // Full moods list loads in background (explore page only has a subset)
            YouTube.moodAndGenres().onSuccess { list ->
                moods = list
                ExploreCache.moods = list
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Timber.w("Moods error: ${it.message?.take(100)}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyErrorMessage(e, "Failed to load explore page")
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            isLoading = true
                            error = null
                            YouTube.explore().onSuccess {
                                explorePage = it
                                ExploreCache.explorePage = it
                            }.onFailure {
                                error = friendlyErrorMessage(it, "Failed to load explore page")
                            }
                            isLoading = false
                        }
                    }) { Text("Retry") }
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item {
                        Text("Explore", style = MaterialTheme.typography.headlineMedium)
                    }

                    // Charts shortcut
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBrowseClick("FEmusic_charts", null, "Charts") },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Charts",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "Top songs, albums and artists right now",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                Icon(
                                    Icons.Filled.TrendingUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // New release albums
                    val newReleases = explorePage?.newReleaseAlbums
                        ?.let { albums -> if (prefs.hideExplicit) albums.filterNot { it.explicit } else albums }
                        .orEmpty()
                    if (newReleases.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    "New releases",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                ScrollableRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(newReleases) { album ->
                                        ExploreAlbumCard(
                                            album = album,
                                            onClick = { onAlbumClick(album.browseId) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Moods & genres
                    val moodSections = moods.orEmpty()
                    if (moodSections.isNotEmpty()) {
                        items(moodSections) { section ->
                            Column {
                                Text(
                                    section.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                // Chip rows, 4 per row
                                section.items.chunked(4).forEach { row ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    ) {
                                        row.forEach { mood ->
                                            MoodChip(
                                                mood = mood,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    val browseId = mood.endpoint.browseId ?: return@MoodChip
                                                    onBrowseClick(browseId, mood.endpoint.params, mood.title)
                                                }
                                            )
                                        }
                                        // Pad incomplete rows so chips align
                                        repeat(4 - row.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (explorePage?.moodAndGenres?.isNotEmpty() == true) {
                        // Fallback to the subset from the explore page
                        item {
                            Column {
                                Text(
                                    "Moods & genres",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                explorePage?.moodAndGenres.orEmpty().chunked(4).forEach { row ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    ) {
                                        row.forEach { mood ->
                                            MoodChip(
                                                mood = mood,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    val browseId = mood.endpoint.browseId ?: return@MoodChip
                                                    onBrowseClick(browseId, mood.endpoint.params, mood.title)
                                                }
                                            )
                                        }
                                        repeat(4 - row.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
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

@Composable
private fun ExploreAlbumCard(
    album: AlbumItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.width(160.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            AsyncImage(
                model = album.thumbnail,
                contentDescription = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    album.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    album.artists?.joinToString { it.name } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MoodChip(
    mood: MoodAndGenres.Item,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val stripeColor = remember(mood.stripeColor) {
        Color(mood.stripeColor.toInt())
    }
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(stripeColor)
            )
            Text(
                mood.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}
