package com.lyrenne.desktop.ui.screens

import com.lyrenne.desktop.ui.components.Tip
import com.metrolist.innertube.pages.SearchSummary
import com.lyrenne.desktop.ui.components.select
import com.lyrenne.desktop.ui.components.rememberMenuAnchor
import com.lyrenne.desktop.ui.components.contextMenu
import com.lyrenne.desktop.ui.components.SongMenu
import com.lyrenne.desktop.ui.components.SecondaryAction
import com.lyrenne.desktop.ui.components.PrimaryAction
import com.lyrenne.desktop.ui.components.ScrollableRow
import com.lyrenne.desktop.ui.components.MediaTile
import com.lyrenne.desktop.ui.components.TrackRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.lyrenne.desktop.ui.components.ConfirmDialog
import com.lyrenne.desktop.ui.components.squareCropFriendly
import com.lyrenne.desktop.ui.theme.selectedChipColors
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
import androidx.compose.ui.text.style.TextAlign
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
    // "All" shows YouTube's sectioned summary (top result, songs, artists, albums...); the other
    // filters show one flat list. All used to quietly search songs only.
    var summaries by remember { mutableStateOf<List<SearchSummary>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
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
            searchError = null
            val hideExplicit = PreferencesManager.preferences.value.hideExplicit
            try {
                if (selectedFilter == SearchFilter.All) {
                    YouTube.searchSummary(searchQuery).onSuccess { page ->
                        summaries = page.filterExplicit(hideExplicit).summaries
                        searchResults = emptyList()
                    }.onFailure {
                        summaries = emptyList()
                        searchError = friendlyErrorMessage(it, "Search failed")
                    }
                } else {
                    YouTube.search(searchQuery, YouTube.SearchFilter(selectedFilter.value)).onSuccess { result ->
                        searchResults = if (hideExplicit) result.items.filterNot { it.explicit } else result.items
                        summaries = emptyList()
                    }.onFailure {
                        // A connection failure is not "no results": say what happened, offer Retry.
                        searchResults = emptyList()
                        searchError = friendlyErrorMessage(it, "Search failed")
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun openItem(item: YTItem) {
        when (item) {
            is SongItem -> scope.launch { player.playSong(item.toDesktopSongInfo()) }
            is AlbumItem -> onAlbumClick(item.browseId)
            is ArtistItem -> onArtistClick(item.id)
            is PlaylistItem -> onPlaylistClick(item.id)
            is PodcastItem -> onPodcastClick(item.id)
            is EpisodeItem -> scope.launch {
                val songInfo = item.asSongItem()?.toDesktopSongInfo()
                if (songInfo != null) player.playSong(songInfo)
            }
            else -> {}
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
                    Tip("Clear") {
                        IconButton(onClick = {
                            query = ""
                            searchResults = emptyList()
                            summaries = emptyList()
                            searchError = null
                            suggestions = emptyList()
                        }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { performSearch() })
        )

        Spacer(Modifier.height(12.dp))

        // Filter chips. Scrolls rather than clipping at the minimum window width.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchFilter.entries.forEach { filter ->
                FilterChip(
                    colors = selectedChipColors(),
                    shape = RoundedCornerShape(50),
                    selected = selectedFilter == filter,
                    onClick = {
                        selectedFilter = filter
                        if (searchResults.isNotEmpty() || summaries.isNotEmpty() || searchError != null) {
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
                searchError != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CloudOff, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(searchError!!, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        SecondaryAction("Retry", Icons.Default.Refresh, onClick = { performSearch() })
                    }
                }
                summaries.isNotEmpty() -> {
                    SearchSummaryView(
                        summaries = summaries,
                        player = player,
                        open = ::openItem,
                        onSeeAll = { filter -> selectedFilter = filter; performSearch() }
                    )
                }
                searchResults.isNotEmpty() -> {
                    LazyColumn {
                        items(searchResults) { item ->
                            SearchResultItem(item = item, player = player, onClick = { openItem(item) })
                        }
                    }
                }
                query.isNotEmpty() && !isLoading -> {
                    // A bare "No results found" reads as the app being broken, which is exactly
                    // the impression that made the Videos filter look faulty when it was working
                    // correctly all along. The app knows something the user does not: it searches
                    // one catalogue, not all of YouTube. Saying so turns a dead end into an answer.
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No results in YouTube Music",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Lyrenne searches YouTube Music, not all of YouTube, so uploads " +
                                "like marmot cleaning videos or tech reviews won't appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    // Search history (when idle)
                    val searchHistory by remember { DatabaseHelper.getSearchHistory() }
                        .collectAsState(initial = emptyList())

                    var confirmClear by remember { mutableStateOf(false) }
                    if (confirmClear) {
                        ConfirmDialog(
                            title = "Clear search history?",
                            text = "All ${searchHistory.size} recent searches are removed.",
                            confirmLabel = "Clear",
                            onConfirm = { scope.launch(Dispatchers.IO) { DatabaseHelper.clearSearchHistory() } },
                            onDismiss = { confirmClear = false }
                        )
                    }
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
                                TextButton(onClick = { confirmClear = true }) {
                                    Text("Clear all")
                                }
                            }
                            LazyColumn {
                                items(searchHistory) { historyQuery ->
                                    ListItem(
                                        headlineContent = { Text(historyQuery) },
                                        leadingContent = { Icon(Icons.Default.History, null) },
                                        trailingContent = {
                                            Tip("Remove") {
                                                IconButton(onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        DatabaseHelper.deleteSearchHistory(historyQuery)
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(18.dp))
                                                }
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
    val songInfo = remember(item) {
        when (item) {
            is SongItem -> item.toDesktopSongInfo()
            is EpisodeItem -> item.asSongItem()?.toDesktopSongInfo()
            else -> null
        }
    }
    if (songInfo != null) {
        val playingId by player.select { it.currentSong?.id }
        TrackRow(
            song = songInfo,
            player = player,
            isPlaying = playingId == songInfo.id,
            subtitle = itemSubtitle(item),
            onClick = onClick
        )
        return
    }

    ListItem(
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                itemSubtitle(item),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            AsyncImage(
                model = squareCropFriendly(item.thumbnail),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(
                        if (item is ArtistItem) RoundedCornerShape(50)
                        else RoundedCornerShape(4.dp)
                    ),
                contentScale = ContentScale.Crop
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
    )
}

// Every branch used to interpolate a nullable straight into the string with `?: ""`, which left a
// dangling "Artist • " whenever the trailing half was missing. Music videos carry no album, so
// that was every single video result.
private fun parts(vararg segments: String?) = segments.filterNot { it.isNullOrBlank() }.joinToString(" • ")

private fun itemSubtitle(item: YTItem): String = when (item) {
    // Search returns music videos as SongItem, because SongItem is the only track-shaped result
    // type there is. Without saying so, a video was indistinguishable from the audio track of the
    // same song, which made the Videos filter look like it did nothing at all.
    is SongItem -> parts(if (item.isVideoSong) "Video" else null, item.artists.joinToString { it.name })
    is AlbumItem -> parts("Album", item.artists?.joinToString { it.name }, item.year?.toString())
    is ArtistItem -> "Artist"
    is PlaylistItem -> parts("Playlist", item.author?.name)
    is PodcastItem -> parts(item.author?.name ?: "Podcast", item.episodeCountText)
    is EpisodeItem -> parts(item.author?.name ?: item.podcast?.name ?: "Episode", item.publishDateText)
    else -> ""
}

/** Which flat filter a summary section's "See all" opens. Matched on YouTube's English titles. */
// ponytail: English titles only; under another `hl` the sections simply have no See all.
private fun seeAllFilter(title: String): SearchFilter? = when (title.lowercase()) {
    "songs" -> SearchFilter.Songs
    "videos" -> SearchFilter.Videos
    "albums" -> SearchFilter.Albums
    "artists" -> SearchFilter.Artists
    "community playlists", "featured playlists", "playlists" -> SearchFilter.Playlists
    else -> null
}

@Composable
private fun SearchSummaryView(
    summaries: List<SearchSummary>,
    player: DesktopPlayer,
    open: (YTItem) -> Unit,
    onSeeAll: (SearchFilter) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        summaries.forEachIndexed { index, summary ->
            item(key = "h$index") {
                Row(
                    Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(summary.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    seeAllFilter(summary.title)?.let { filter ->
                        TextButton(onClick = { onSeeAll(filter) }) { Text("See all") }
                    }
                }
            }
            val tracks = summary.items.all { it is SongItem || it is EpisodeItem }
            when {
                // YouTube leads with one top result card; any songs under it list as rows.
                index == 0 -> {
                    item(key = "top") { TopResultCard(summary.items.first(), player, open) }
                    items(summary.items.drop(1)) { item ->
                        SearchResultItem(item, player, onClick = { open(item) })
                    }
                }
                tracks -> items(summary.items.take(5)) { item ->
                    SearchResultItem(item, player, onClick = { open(item) })
                }
                else -> item(key = "shelf$index") {
                    ScrollableRow {
                        items(summary.items) { item -> MediaTile(item, player, onClick = { open(item) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopResultCard(item: YTItem, player: DesktopPlayer, open: (YTItem) -> Unit) {
    val anchor = rememberMenuAnchor()
    val song = remember(item) { (item as? SongItem)?.toDesktopSongInfo() }
    Box(if (song != null) Modifier.contextMenu(anchor) else Modifier) {
        Surface(
            onClick = { open(item) },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = squareCropFriendly(item.thumbnail),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp)
                        .clip(if (item is ArtistItem) RoundedCornerShape(50) else RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        itemSubtitle(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (song != null) {
                        Spacer(Modifier.height(12.dp))
                        PrimaryAction("Play", Icons.Default.PlayArrow, onClick = { open(item) })
                    }
                }
            }
        }
        if (song != null) SongMenu(anchor, song, player)
    }
}
