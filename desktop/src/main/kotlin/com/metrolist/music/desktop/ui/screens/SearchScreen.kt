package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.media.suppressMediaKeys
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.ui.screens.toSongInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onPlaylistClick: (String) -> Unit = {}
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
            try {
                val filter = if (selectedFilter == SearchFilter.All) {
                    YouTube.SearchFilter.FILTER_SONG
                } else {
                    YouTube.SearchFilter(selectedFilter.value)
                }
                YouTube.search(searchQuery, filter).onSuccess { result ->
                    searchResults = result.items
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

@Composable
private fun SearchResultItem(
    item: YTItem,
    player: DesktopPlayer,
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val songInfo = remember(item) { item.toSongInfo() }

    ListItem(
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val subtitle = when (item) {
                is SongItem -> "${item.artists.joinToString { it.name }} • ${item.album?.name ?: ""}"
                is AlbumItem -> "${item.artists?.joinToString { it.name } ?: "Unknown"} • ${item.year ?: ""}"
                is ArtistItem -> "Artist"
                is PlaylistItem -> item.author?.name ?: "Playlist"
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
                    }
                }
            } else {
                val icon = when (item) {
                    is AlbumItem -> Icons.Default.Album
                    is ArtistItem -> Icons.Default.Person
                    is PlaylistItem -> Icons.AutoMirrored.Filled.QueueMusic
                    else -> Icons.Default.MusicNote
                }
                Icon(icon, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
