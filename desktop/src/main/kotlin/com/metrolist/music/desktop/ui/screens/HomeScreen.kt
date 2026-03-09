package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun HomeScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var homePage by remember { mutableStateOf<HomePage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val authState by AuthManager.authState.collectAsState()

    // Re-fetch when auth state changes (e.g. after login)
    LaunchedEffect(authState.isLoggedIn) {
        try {
            isLoading = true
            error = null
            val result = YouTube.home()
            result.onSuccess { page ->
                var current = page
                Timber.d("Home: loaded ${current.sections.size} sections")

                // Load continuation pages to get more personalized sections
                var continuation = current.continuation
                var attempts = 0
                while (continuation != null && attempts < 5) {
                    attempts++
                    val contResult = YouTube.home(continuation = continuation)
                    contResult.onSuccess { contPage ->
                        current = current.copy(
                            sections = (current.sections + contPage.sections).toMutableList()
                        )
                        continuation = contPage.continuation
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Timber.w("Home continuation error: ${it.message?.take(100)}")
                        continuation = null
                    }
                }
                homePage = current
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e("Home error: ${e.message?.take(200)}")
                error = friendlyErrorMessage(e, "Failed to load home page")
            }
        } finally {
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            isLoading = true
                            error = null
                            YouTube.home().onSuccess { homePage = it }
                                .onFailure { error = friendlyErrorMessage(it, "Failed to load home page") }
                            isLoading = false
                        }
                    }) {
                        Text("Retry")
                    }
                }
            }
            homePage != null -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item {
                        Text(
                            "Welcome to Metrolist",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    homePage?.sections?.forEach { section ->
                        item {
                            HomeSection(
                                title = section.title ?: "Recommended",
                                items = section.items,
                                player = player,
                                onAlbumClick = onAlbumClick,
                                onArtistClick = onArtistClick,
                                onPlaylistClick = onPlaylistClick
                            )
                        }
                    }
                }
            }
            else -> {
                Text(
                    "No content available",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun HomeSection(
    title: String,
    items: List<YTItem>,
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { item ->
                HomeSectionItem(
                    item = item,
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                scope.launch {
                                    val songInfo = item.toSongInfo()
                                    if (songInfo != null) {
                                        player.playSong(songInfo)
                                    }
                                }
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

@Composable
private fun HomeSectionItem(
    item: YTItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitle = when (item) {
                    is SongItem -> item.artists.joinToString { it.name }
                    is AlbumItem -> item.artists?.joinToString { it.name } ?: ""
                    is ArtistItem -> "Artist"
                    is PlaylistItem -> item.author?.name ?: ""
                    else -> ""
                }

                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
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

fun YTItem.toSongInfo(): SongInfo? {
    return when (this) {
        is SongItem -> SongInfo(
            id = id,
            title = title,
            artist = artists.joinToString { it.name },
            thumbnailUrl = thumbnail
        )
        else -> null
    }
}
