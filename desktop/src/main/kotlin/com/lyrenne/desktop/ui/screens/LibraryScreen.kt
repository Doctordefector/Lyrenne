package com.lyrenne.desktop.ui.screens

import androidx.compose.material.icons.outlined.Download
import com.lyrenne.desktop.ui.components.PillTab
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.Sort
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.ScreenTitle
import com.lyrenne.desktop.ui.components.SecondaryAction
import com.lyrenne.desktop.ui.components.PrimaryAction
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.CollapsibleSearchField
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.components.ConfirmDialog
import com.lyrenne.desktop.ui.theme.selectedChipColors
import com.lyrenne.desktop.ui.components.select
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.db.Song
import com.lyrenne.desktop.db.Album
import com.lyrenne.desktop.db.Artist
import com.lyrenne.desktop.db.Playlist
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.download.DownloadStatus
import com.lyrenne.desktop.ui.components.PlaylistPickerDialog
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.sync.YouTubeWrites
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.sync.LibrarySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab {
    Songs, Albums, Artists, Playlists, Downloads
}

/**
 * Each mode sorts one way by default ([natural]); the direction toggle reverses it, in every mode.
 * Tabs offer only the modes they can honour: Albums used to "sort by play time" by year, and
 * Artists by reversing whatever order the database returned.
 */
enum class SortMode(val label: String, val natural: String, val reversed: String) {
    DATE_ADDED("Date added", "Newest first", "Oldest first"),
    NAME("Name", "A to Z", "Z to A"),
    PLAY_TIME("Play time", "Most played first", "Least played first"),
    YEAR("Year", "Newest first", "Oldest first");

    companion object {
        fun forTab(tab: LibraryTab): List<SortMode> = when (tab) {
            LibraryTab.Songs -> listOf(DATE_ADDED, NAME, PLAY_TIME)
            LibraryTab.Albums -> listOf(DATE_ADDED, NAME, YEAR)
            LibraryTab.Artists -> listOf(DATE_ADDED, NAME)
            else -> emptyList()
        }
    }
}

@Composable
fun LibraryScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onLocalPlaylistClick: (String) -> Unit = {},
    onAutoPlaylistClick: (com.lyrenne.desktop.ui.AutoPlaylistType) -> Unit = {},
    onSignIn: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.Songs) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.DATE_ADDED) }
    var sortReversed by remember { mutableStateOf(false) }
    var showLikedOnly by remember { mutableStateOf(false) }

    // Collect from database
    val librarySongs by remember { DatabaseHelper.getAllSongs() }.collectAsState(initial = emptyList())
    val likedSongs by remember { DatabaseHelper.getLikedSongs() }.collectAsState(initial = emptyList())
    val downloadedSongs by remember { DatabaseHelper.getDownloadedSongs() }.collectAsState(initial = emptyList())
    val albums by remember { DatabaseHelper.getAllAlbums() }.collectAsState(initial = emptyList())
    val artists by remember { DatabaseHelper.getAllArtists() }.collectAsState(initial = emptyList())
    val playlists by remember { DatabaseHelper.getAllPlaylists() }.collectAsState(initial = emptyList())

    // Load artist names map (batch load for efficiency)
    var artistNamesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(librarySongs) {
        withContext(Dispatchers.IO) {
            artistNamesMap = DatabaseHelper.getAllSongArtistNames()
        }
    }

    // Sync state
    val syncState by LibrarySync.syncState.collectAsState()
    val authState by AuthManager.authState.collectAsState()

    // Download state
    val activeDownloads by DownloadManager.activeDownloads.collectAsState()

    val sortModes = SortMode.forTab(selectedTab)
    val sort = if (sortMode in sortModes) sortMode else SortMode.DATE_ADDED

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding, vertical = 16.dp)) {
        ScreenTitle("Your Library") {
            // Grid/list view toggle: only shown on the tabs it affects (Albums, Artists)
            val prefs by com.lyrenne.desktop.settings.PreferencesManager.preferences.collectAsState()
            val isList = prefs.libraryViewMode == com.lyrenne.desktop.settings.LibraryViewMode.LIST
            if (selectedTab == LibraryTab.Albums || selectedTab == LibraryTab.Artists) {
                Tip(if (isList) "Grid view" else "List view") {
                    IconButton(onClick = {
                        com.lyrenne.desktop.settings.PreferencesManager.setLibraryViewMode(
                            if (isList) com.lyrenne.desktop.settings.LibraryViewMode.GRID
                            else com.lyrenne.desktop.settings.LibraryViewMode.LIST
                        )
                    }) {
                        Icon(
                            if (isList) Icons.Default.GridView else Icons.Default.ViewList,
                            contentDescription = if (isList) "Grid view" else "List view"
                        )
                    }
                }
            }
            if (authState.isLoggedIn) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(syncState.progress, style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = { LibrarySync.syncLibrary(full = true) }) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync")
                    }
                }
            }
        }

        // Sync error, or a library edit that failed to reach YouTube. Writes are
        // fire-and-forget, so without this a failed push would be entirely silent.
        val writeError by YouTubeWrites.lastError.collectAsState()
        val bannerError = syncState.error ?: writeError
        if (bannerError != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        bannerError,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = {
                        LibrarySync.clearError()
                        YouTubeWrites.clearError()
                    }) {
                        Icon(Icons.Default.Close, "Dismiss")
                    }
                }
            }
        }

        if (!authState.isLoggedIn) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to see your YouTube Music library", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Downloads and local playlists work without signing in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // One toolbar: tabs on the left; search, the liked filter and sort on the right. These
        // were five stacked rows, which left room for one song at the minimum window size.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Tabs as pills: the selected one filled, the rest outlined.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryTab.entries.forEach { tab ->
                    val count = when (tab) {
                        LibraryTab.Songs -> librarySongs.size
                        LibraryTab.Albums -> albums.size
                        LibraryTab.Artists -> artists.size
                        LibraryTab.Playlists -> playlists.size
                        LibraryTab.Downloads -> downloadedSongs.size + activeDownloads.size
                    }
                    PillTab(
                        label = tab.name,
                        count = count,
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CollapsibleSearchField(searchQuery, onQueryChange = { searchQuery = it }, placeholder = "Search library")
            if (selectedTab == LibraryTab.Songs) {
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    colors = selectedChipColors(),
                    shape = RoundedCornerShape(50),
                    selected = showLikedOnly,
                    onClick = { showLikedOnly = !showLikedOnly },
                    label = { Text("Liked") },
                    leadingIcon = {
                        Icon(
                            if (showLikedOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null, Modifier.size(18.dp)
                        )
                    }
                )
            }
            if (sortModes.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                SortMenu(sort, sortReversed, sortModes, onSort = { sortMode = it }, onReverse = { sortReversed = it })
            }
        }

        Spacer(Modifier.height(8.dp))

        // Content
        when (selectedTab) {
            LibraryTab.Songs -> SongsTab(librarySongs, likedSongs, showLikedOnly, player, artistNamesMap, searchQuery, sort, sortReversed)
            LibraryTab.Albums -> AlbumsTab(albums, onAlbumClick, searchQuery, sort, sortReversed)
            LibraryTab.Artists -> ArtistsTab(artists, onArtistClick, searchQuery, sort, sortReversed)
            LibraryTab.Playlists -> PlaylistsTab(
                playlists = playlists,
                onPlaylistClick = onPlaylistClick,
                onLocalPlaylistClick = onLocalPlaylistClick,
                onAutoPlaylistClick = onAutoPlaylistClick,
                searchQuery = searchQuery,
                autoCounts = mapOf(
                    com.lyrenne.desktop.ui.AutoPlaylistType.LIKED to likedSongs.size,
                    com.lyrenne.desktop.ui.AutoPlaylistType.DOWNLOADED to downloadedSongs.size
                )
            )
            LibraryTab.Downloads -> DownloadsTab(downloadedSongs, activeDownloads, player, artistNamesMap, searchQuery, onBrowse = { selectedTab = LibraryTab.Songs })
        }
    }
}


/** One sort button ("Date added ▾"); the modes and the direction live in its menu. */
@Composable
private fun SortMenu(
    sort: SortMode,
    reversed: Boolean,
    modes: List<SortMode>,
    onSort: (SortMode) -> Unit,
    onReverse: (Boolean) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sort.label)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { onSort(mode); open = false },
                    trailingIcon = { if (mode == sort) Icon(Icons.Default.Check, null) }
                )
            }
            HorizontalDivider()
            listOf(false to sort.natural, true to sort.reversed).forEach { (rev, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onReverse(rev); open = false },
                    trailingIcon = { if (rev == reversed) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    likedSongs: List<Song>,
    showLikedOnly: Boolean,
    player: DesktopPlayer,
    artistNamesMap: Map<String, String>,
    searchQuery: String,
    sortMode: SortMode,
    sortReversed: Boolean
) {
    val scope = rememberCoroutineScope()
    val playingId by player.select { it.currentSong?.id }
    // What actually played, newest first. This was the head of the live queue, which only looked
    // like history while every click here replaced the queue with a single song.
    val recentSongs = player.select { it.recentlyPlayed }.value

    val baseSongs = if (showLikedOnly) likedSongs else songs

    // Filter by search query
    val filteredSongs = remember(baseSongs, searchQuery, artistNamesMap) {
        if (searchQuery.isBlank()) baseSongs
        else {
            val q = searchQuery.lowercase()
            baseSongs.filter { song ->
                song.title.lowercase().contains(q) ||
                    (song.albumName?.lowercase()?.contains(q) == true) ||
                    (artistNamesMap[song.id]?.lowercase()?.contains(q) == true)
            }
        }
    }

    val displaySongs = remember(filteredSongs, sortMode, sortReversed) {
        val sorted = when (sortMode) {
            SortMode.NAME -> filteredSongs.sortedBy { it.title.lowercase() }
            SortMode.PLAY_TIME -> filteredSongs.sortedByDescending { it.totalPlayTime }
            else -> filteredSongs.sortedByDescending { it.inLibrary ?: it.likedDate ?: "" }
        }
        if (sortReversed) sorted.reversed() else sorted
    }
    val songInfos = remember(displaySongs, artistNamesMap) { displaySongs.map { it.toSongInfo(artistNamesMap) } }
    val showRecent = recentSongs.isNotEmpty() && !showLikedOnly && searchQuery.isBlank()

    if (displaySongs.isEmpty() && !showRecent) {
        EmptyLibraryMessage(
            icon = Icons.Default.MusicNote,
            message = if (searchQuery.isNotBlank()) "No matching songs"
                     else if (showLikedOnly) "No liked songs"
                     else "No songs in your library",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term"
                        else if (showLikedOnly) "Like songs to see them here"
                        else "Sync your library or play songs to add them"
        )
        return
    }

    LazyColumn {
        if (displaySongs.isNotEmpty()) {
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PrimaryAction("Play", Icons.Default.PlayArrow, onClick = { scope.launch { player.playQueue(songInfos) } })
                    SecondaryAction("Shuffle", Icons.Default.Shuffle, onClick = { scope.launch { player.playQueue(songInfos.shuffled()) } })
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${displaySongs.size} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Recently Played, then the library under its own header. Without recents there is
        // nothing to tell apart, so no header (it only repeated the tab's name).
        if (showRecent) {
            item(key = "recent-header") { SectionHeader("Recently played") }
            items(recentSongs) { song ->
                TrackRow(
                    song = song,
                    player = player,
                    isPlaying = playingId == song.id,
                    onClick = { scope.launch { player.playSong(song) } }
                )
            }
            if (displaySongs.isNotEmpty()) item(key = "library-header") { SectionHeader("All songs") }
        }

        itemsIndexed(displaySongs, key = { _, s -> s.id }) { index, dbSong ->
            TrackRow(
                song = songInfos[index],
                player = player,
                isPlaying = playingId == dbSong.id,
                // In the Liked filter every heart would be filled: noise, so none.
                liked = if (showLikedOnly) null else dbSong.liked == 1L,
                downloaded = dbSong.isDownloaded == 1L,
                // The list plays on from here, as it does from an album or a playlist. This
                // played the one song and stopped (issue #11).
                onClick = { scope.launch { player.playQueue(songInfos, index) } }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp)
    )
}

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    onAlbumClick: (String) -> Unit,
    searchQuery: String,
    sortMode: SortMode,
    sortReversed: Boolean
) {
    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) albums
        else albums.filter { it.title.lowercase().contains(searchQuery.lowercase()) }
    }

    val displayAlbums = remember(filteredAlbums, sortMode, sortReversed) {
        val sorted = when (sortMode) {
            SortMode.NAME -> filteredAlbums.sortedBy { it.title.lowercase() }
            SortMode.YEAR -> filteredAlbums.sortedByDescending { it.year ?: 0 }
            else -> filteredAlbums // newest first, as the database returns them
        }
        if (sortReversed) sorted.reversed() else sorted
    }

    val viewMode = com.lyrenne.desktop.settings.PreferencesManager.preferences
        .collectAsState().value.libraryViewMode

    if (displayAlbums.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Album,
            message = if (searchQuery.isNotBlank()) "No matching albums" else "No albums saved",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Sync your library to see saved albums"
        )
    } else if (viewMode == com.lyrenne.desktop.settings.LibraryViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(displayAlbums, key = { it.id }) { album ->
                LibraryGridCard(
                    title = album.title,
                    subtitle = "${album.songCount} songs" + (album.year?.let { " \u2022 $it" } ?: ""),
                    thumbnailUrl = album.thumbnailUrl,
                    onClick = { onAlbumClick(album.id) }
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(displayAlbums, key = { it.id }) { album ->
                ListItem(
                    headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${album.songCount} songs" + (album.year?.let { " \u2022 $it" } ?: ""),
                            maxLines = 1
                        )
                    },
                    leadingContent = {
                        if (album.thumbnailUrl != null) {
                            AsyncImage(
                                model = album.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Album,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onAlbumClick(album.id) }
                )
            }
        }
    }
}

@Composable
private fun LibraryGridCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    isCircular: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(if (isCircular) RoundedCornerShape(50) else RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(if (isCircular) RoundedCornerShape(50) else RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isCircular) Icons.Default.Person else Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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

@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    onArtistClick: (String) -> Unit,
    searchQuery: String,
    sortMode: SortMode,
    sortReversed: Boolean
) {
    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) artists
        else artists.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
    }

    val displayArtists = remember(filteredArtists, sortMode, sortReversed) {
        val sorted = when (sortMode) {
            SortMode.NAME -> filteredArtists.sortedBy { it.name.lowercase() }
            else -> filteredArtists // newest first, as the database returns them
        }
        if (sortReversed) sorted.reversed() else sorted
    }

    val viewMode = com.lyrenne.desktop.settings.PreferencesManager.preferences
        .collectAsState().value.libraryViewMode

    if (displayArtists.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Person,
            message = if (searchQuery.isNotBlank()) "No matching artists" else "No artists followed",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Sync your library to see subscribed artists"
        )
    } else if (viewMode == com.lyrenne.desktop.settings.LibraryViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(displayArtists, key = { it.id }) { artist ->
                LibraryGridCard(
                    title = artist.name,
                    subtitle = "",
                    thumbnailUrl = artist.thumbnailUrl,
                    isCircular = true,
                    onClick = { onArtistClick(artist.id) }
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(displayArtists, key = { it.id }) { artist ->
                ListItem(
                    headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        if (artist.thumbnailUrl != null) {
                            AsyncImage(
                                model = artist.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(50)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onArtistClick(artist.id) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    onPlaylistClick: (String) -> Unit,
    onLocalPlaylistClick: (String) -> Unit,
    onAutoPlaylistClick: (com.lyrenne.desktop.ui.AutoPlaylistType) -> Unit,
    searchQuery: String,
    autoCounts: Map<com.lyrenne.desktop.ui.AutoPlaylistType, Int> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val filteredPlaylists = remember(playlists, searchQuery) {
        // YouTube's own Liked Music (LM) duplicates the local Liked Songs auto playlist above.
        val shown = playlists.filterNot { it.id in YOUTUBE_LIKED_IDS || it.browseId in YOUTUBE_LIKED_IDS }
        if (searchQuery.isBlank()) shown
        else shown.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.suppressMediaKeys()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                YouTubeWrites.createPlaylist(newPlaylistName.trim())
                            }
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Create playlist button
        item {
            ListItem(
                headlineContent = { Text("New playlist", color = MaterialTheme.colorScheme.primary) },
                leadingContent = {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { showCreateDialog = true }
            )
        }

        // Auto playlists (only when not searching)
        if (searchQuery.isBlank()) {
            items(com.lyrenne.desktop.ui.AutoPlaylistType.entries) { type ->
                ListItem(
                    headlineContent = { Text(type.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(autoCounts[type]?.let { "$it songs" } ?: "Your most played songs")
                    },
                    leadingContent = {
                        // A tile, like the playlists' artwork, so the icon does not float.
                        Box(
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                when (type) {
                                    com.lyrenne.desktop.ui.AutoPlaylistType.LIKED -> Icons.Default.Favorite
                                    com.lyrenne.desktop.ui.AutoPlaylistType.DOWNLOADED -> Icons.Default.Download
                                    com.lyrenne.desktop.ui.AutoPlaylistType.MOST_PLAYED -> Icons.Default.TrendingUp
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onAutoPlaylistClick(type) }
                )
            }
        }

        if (filteredPlaylists.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No matching playlists",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(filteredPlaylists, key = { it.id }) { playlist ->
            // Local playlists have no browseId; remote ones navigate to the YouTube playlist screen
            val isLocal = playlist.browseId == null
            var showMenu by remember(playlist.id) { mutableStateOf(false) }
            var confirmDelete by remember(playlist.id) { mutableStateOf(false) }
            var localSongCount by remember(playlist.id) { mutableStateOf<Int?>(null) }

            if (confirmDelete) {
                ConfirmDialog(
                    title = "Delete playlist?",
                    text = "\"${playlist.name}\" is deleted here and on YouTube. The songs stay in your library.",
                    confirmLabel = "Delete",
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            DatabaseHelper.deletePlaylist(playlist.id)
                            YouTubeWrites.deletePlaylist(playlist.id)
                        }
                    },
                    onDismiss = { confirmDelete = false }
                )
            }

            if (isLocal) {
                LaunchedEffect(playlist.id) {
                    localSongCount = withContext(Dispatchers.IO) {
                        try { DatabaseHelper.getPlaylistSongCount(playlist.id) } catch (_: Exception) { 0 }
                    }
                }
            }

            ListItem(
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        if (isLocal) "${localSongCount ?: 0} songs  •  Local"
                        else "${playlist.remoteSongCount ?: 0} songs"
                    )
                },
                leadingContent = {
                    if (playlist.thumbnailUrl != null) {
                        AsyncImage(
                            model = playlist.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                },
                trailingContent = {
                    if (isLocal) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, "More options")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        confirmDelete = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    if (isLocal) onLocalPlaylistClick(playlist.id) else onPlaylistClick(playlist.id)
                }
            )
        }
    }
}

@Composable
private fun DownloadsTab(
    downloadedSongs: List<Song>,
    activeDownloads: Map<String, com.lyrenne.desktop.download.DownloadProgress>,
    player: DesktopPlayer,
    artistNamesMap: Map<String, String>,
    searchQuery: String,
    onBrowse: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playingId by player.select { it.currentSong?.id }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val filteredDownloaded = remember(downloadedSongs, searchQuery, artistNamesMap) {
        if (searchQuery.isBlank()) downloadedSongs
        else {
            val q = searchQuery.lowercase()
            downloadedSongs.filter { song ->
                song.title.lowercase().contains(q) ||
                    (artistNamesMap[song.id]?.lowercase()?.contains(q) == true)
            }
        }
    }

    val active = remember(activeDownloads) { activeDownloads.values.toList() }
    val hasFailed = remember(active) { active.any { it.status == DownloadStatus.ERROR } }
    val hasFinished = remember(active) {
        active.any {
            it.status == DownloadStatus.ERROR ||
                it.status == DownloadStatus.COMPLETED ||
                it.status == DownloadStatus.CANCELLED
        }
    }
    val hasRunning = remember(active) {
        active.any {
            it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.RETRYING
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDialog(
            title = "Delete all downloads?",
            text = "This permanently deletes " + downloadedSongs.size + " downloaded " +
                (if (downloadedSongs.size == 1) "file" else "files") +
                " from disk. Your library and playlists are not affected.",
            confirmLabel = "Delete all",
            onConfirm = { DownloadManager.deleteAllDownloads() },
            onDismiss = { showDeleteAllDialog = false }
        )
    }

    if (filteredDownloaded.isEmpty() && active.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Outlined.Download,
            message = if (searchQuery.isNotBlank()) "No matching downloads" else "No downloaded songs",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term"
                else "Download from any song's menu (⋮ or right-click) to play it offline",
            action = if (searchQuery.isBlank()) {
                { SecondaryAction("Browse your library", Icons.Default.LibraryMusic, onClick = onBrowse) }
            } else null
        )
        return
    }

    // One LazyColumn for both sections. The active list used to be a plain Column of Cards,
    // which composes every entry whether or not it is on screen: queueing a 700-track playlist
    // built 700 Cards up front and froze the tab.
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (active.isNotEmpty()) {
            item(key = "active-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Downloading (" + active.size + ")",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasFailed) {
                        TextButton(onClick = { DownloadManager.retryAllFailed() }) {
                            Text("Retry failed")
                        }
                    }
                    if (hasFinished) {
                        TextButton(onClick = { DownloadManager.clearFinished() }) {
                            Text("Clear finished")
                        }
                    }
                    if (hasRunning) {
                        TextButton(onClick = { DownloadManager.cancelAllDownloads() }) {
                            Text("Cancel all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Keyed on the song id, a database primary key and unique across the map.
            items(active, key = { it.songId }) { download ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                download.songTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            when (download.status) {
                                DownloadStatus.PENDING -> {
                                    Text(
                                        "Waiting...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DownloadStatus.DOWNLOADING -> {
                                    LinearProgressIndicator(
                                        progress = { download.progress / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        download.progress.toString() + "%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                DownloadStatus.RETRYING -> {
                                    Text(
                                        "Retrying, attempt " + download.attempt + " of 3...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DownloadStatus.ERROR -> {
                                    Text(
                                        download.error ?: "Download failed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                DownloadStatus.CANCELLED -> {
                                    Text(
                                        "Cancelled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DownloadStatus.COMPLETED -> {
                                    Text(
                                        "Done",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (download.status == DownloadStatus.ERROR) {
                            IconButton(onClick = { DownloadManager.retryDownload(download.songId) }) {
                                Icon(Icons.Default.Refresh, "Retry")
                            }
                        }

                        IconButton(onClick = { DownloadManager.cancelDownload(download.songId) }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                    }
                }
            }

            item(key = "active-spacer") { Spacer(Modifier.height(16.dp)) }
        }

        if (filteredDownloaded.isNotEmpty()) {
            item(key = "downloaded-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Downloaded (" + filteredDownloaded.size + ")",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showDeleteAllDialog = true }) {
                        Text("Delete all", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            itemsIndexed(filteredDownloaded, key = { _, s -> s.id }) { index, dbSong ->
                val song = dbSong.toSongInfo(artistNamesMap)
                TrackRow(
                    song = song,
                    player = player,
                    isPlaying = playingId == song.id,
                    liked = dbSong.liked == 1L,
                    downloaded = true,
                    onClick = {
                        // The list plays on from here, like the Songs tab. This used to play the
                        // one file on its own. The player finds each song's download itself, so
                        // the songs after it play from disk too.
                        val songInfos = filteredDownloaded.map { it.toSongInfo(artistNamesMap) }
                        scope.launch { player.playQueue(songInfos, index) }
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    subMessage: String,
    action: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

// Extension function to convert database Song to SongInfo — uses artist names map
private val YOUTUBE_LIKED_IDS = setOf("LM", "VLLM")

private fun Song.toSongInfo(artistNamesMap: Map<String, String>): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = artistNamesMap[id] ?: albumName ?: "Unknown Artist",
        album = albumName,
        duration = duration.toInt(),
        thumbnailUrl = thumbnailUrl
    )
}
