package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.PlaylistPickerDialog
import com.lyrenne.desktop.ui.screens.toSongInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchFilter(val value: String, val label: String) {
    All("", "All"),
    Songs("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", "Songs"),
    Videos("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D", "Videos"),
    Albums("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D", "Albums"),
    Artists("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D", "Artists"),
    Playlists("EgWKAQIoAWoKEAkQChAFEAMQBA%3D%3D", "Playlists")
}

@Composable
fun SearchScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onPodcastClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(SearchFilter.All) }
    var showSuggestions by remember { mutableStateOf(false) }
    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    fun performSearch(searchQuery: String = query) {
        if (searchQuery.isBlank()) return
        scope.launch {
            isLoading = true
            showSuggestions = false
            // Record search history (unless paused in privacy settings)
            if (!PreferencesManager.preferences.value.pauseSearchHistory) {
                withContext(Dispatchers.IO) {
                    try { DatabaseHelper.addSearchHistory(searchQuery.trim()) } catch (_: Exception) {}
                }
            }
            try {
                val filter = if (selectedFilter == SearchFilter.All) {
                    YouTube.SearchFilter.FILTER_SONG
                } else {
                    YouTube.SearchFilter(selectedFilter.value)
                }
                YouTube.search(searchQuery, filter).onSuccess { result ->
                    val hideExplicit = PreferencesManager.preferences.value.hideExplicit
                    searchResults = if (hideExplicit) result.items.filterNot { it.explicit } else result.items
                }.onFailure {
                    searchResults = emptyList()
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun loadSuggestions() {
        if (query.length < 2) {
            suggestions = emptyList()
            return
        }
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            delay(300) // Debounce: wait 300ms before fetching
            YouTube.searchSuggestions(query).onSuccess { result ->
                suggestions = result.queries
                showSuggestions = suggestions.isNotEmpty()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                loadSuggestions()
            },
            modifier = Modifier
                .fillMaxWidth()
                .suppressMediaKeys()
                .onKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                        performSearch()
                        true
                    } else false
                },
            placeholder = { Text("Search songs, artists, albums...") },
            leadingIcon = { Icon(Icons.Default.Search, "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        searchResults = emptyList()
                        suggestions = emptyList()
                    }) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { performSearch() })
        )

        Spacer(Modifier.height(12.dp))

        // Filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = {
                        selectedFilter = filter
                        if (searchResults.isNotEmpty()) {
                            performSearch()
                        }
                    },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Suggestions or Results
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                showSuggestions && suggestions.isNotEmpty() -> {
                    LazyColumn {
                        items(suggestions) { suggestion ->
                            ListItem(
                                headlineContent = { Text(suggestion) },
                                leadingContent = { Icon(Icons.Default.Search, null) },
                                modifier = Modifier.clickable {
                                    query = suggestion
                                    performSearch(suggestion)
                                }
                            )
                        }
                    }
                }
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                searchResults.isNotEmpty() -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(searchResults) { item ->
                            SearchResultItem(
                                item = item,
                                player = player,
                                onClick = {
                                    when (item) {
                                        is SongItem -> {
                                            scope.launch {
                                                player.playSong(item.toSongInfo() ?: return@launch)
                                            }
                                        }
                                        is AlbumItem -> onAlbumClick(item.browseId)
                                        is ArtistItem -> onArtistClick(item.id)
                                        is PlaylistItem -> onPlaylistClick(item.id)
                                        is PodcastItem -> onPodcastClick(item.id)
                                        is EpisodeItem -> {
                                            scope.launch {
                                                val songInfo = item.asSongItem()?.toDesktopSongInfo()
                                                if (songInfo != null) player.playSong(songInfo)
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            )
                        }
                    }
                }
                query.isNotEmpty() && !isLoading -> {
                    Text(
                        "No results found",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // Search history (when idle)
                    val searchHistory by DatabaseHelper.getSearchHistory()
                        .collectAsState(initial = emptyList())

                    if (searchHistory.isNotEmpty()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Recent searches",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) { DatabaseHelper.clearSearchHistory() }
                                }) {
                                    Text("Clear all")
                                }
                            }
                            LazyColumn {
                                items(searchHistory) { historyQuery ->
                                    ListItem(
                                        headlineContent = { Text(historyQuery) },
                                        leadingContent = { Icon(Icons.Default.History, null) },
                                        trailingContent = {
                                            IconButton(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    DatabaseHelper.deleteSearchHistory(historyQuery)
                                                }
                                            }) {
                                                Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            query = historyQuery
                                            performSearch(historyQuery)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Search for your favorite music",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    item: YTItem,
    player: DesktopPlayer,
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val songInfo = remember(item) { item.toSongInfo() }
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            // Every branch used to interpolate a nullable straight into the string with `?: ""`,
            // which left a dangling "Artist • " whenever the trailing half was missing. Music
            // videos carry no album, so that was every single video result.
            fun parts(vararg segments: String?) =
                segments.filterNot { it.isNullOrBlank() }.joinToString(" • ")

            val subtitle = when (item) {
                // Search returns music videos as SongItem, because SongItem is the only
                // track-shaped result type there is. Without saying so, a video was
                // indistinguishable from the audio track of the same song, which made the
                // Videos filter look like it did nothing at all.
                is SongItem -> parts(
                    if (item.isVideoSong) "Video" else null,
                    item.artists.joinToString { it.name },
                    item.album?.name
                )
                is AlbumItem -> parts(
                    item.artists?.joinToString { it.name } ?: "Unknown",
                    item.year?.toString()
                )
                is ArtistItem -> "Artist"
                is PlaylistItem -> item.author?.name ?: "Playlist"
                is PodcastItem -> parts(item.author?.name ?: "Podcast", item.episodeCountText)
                is EpisodeItem -> parts(
                    item.author?.name ?: item.podcast?.name ?: "Episode",
                    item.publishDateText
                )
                else -> ""
            }
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(
                        if (item is ArtistItem) RoundedCornerShape(50)
                        else RoundedCornerShape(4.dp)
                    ),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            if (songInfo != null) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                player.addToQueueNext(songInfo)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                player.addToQueue(songInfo)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Start Radio") },
                            onClick = {
                                showMenu = false
                                scope.launch { player.startRadio(songInfo) }
                            },
                            leadingIcon = { Icon(Icons.Default.Radio, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Playlist") },
                            onClick = {
                                showMenu = false
                                showPlaylistPicker = true
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = {
                                DownloadManager.queueDownload(songInfo)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                    }
                }
                if (showPlaylistPicker) {
                    val playlists by DatabaseHelper.getAllPlaylists().collectAsState(initial = emptyList())
                    PlaylistPickerDialog(
                        song = songInfo,
                        playlists = playlists,
                        onDismiss = { showPlaylistPicker = false }
                    )
                }
            } else {
                val icon = when (item) {
                    is AlbumItem -> Icons.Default.Album
                    is ArtistItem -> Icons.Default.Person
                    is PlaylistItem -> Icons.AutoMirrored.Filled.QueueMusic
                    is PodcastItem -> Icons.Default.Mic
                    else -> Icons.Default.MusicNote
                }
                Icon(icon, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
