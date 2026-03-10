package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.db.Song
import com.metrolist.music.desktop.db.Album
import com.metrolist.music.desktop.db.Artist
import com.metrolist.music.desktop.db.Playlist
import com.metrolist.music.desktop.download.DownloadManager
import com.metrolist.music.desktop.download.DownloadStatus
import com.metrolist.music.desktop.media.suppressMediaKeys
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.sync.LibrarySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab {
    Songs, Albums, Artists, Playlists, Downloads
}

enum class SortMode(val label: String) {
    DATE_ADDED("Date Added"),
    NAME("Name"),
    PLAY_TIME("Play Time")
}

@Composable
fun LibraryScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.Songs) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.DATE_ADDED) }
    var sortAscending by remember { mutableStateOf(false) }

    // Collect from database
    val librarySongs by DatabaseHelper.getAllSongs().collectAsState(initial = emptyList())
    val likedSongs by DatabaseHelper.getLikedSongs().collectAsState(initial = emptyList())
    val downloadedSongs by DatabaseHelper.getDownloadedSongs().collectAsState(initial = emptyList())
    val albums by DatabaseHelper.getAllAlbums().collectAsState(initial = emptyList())
    val artists by DatabaseHelper.getAllArtists().collectAsState(initial = emptyList())
    val playlists by DatabaseHelper.getAllPlaylists().collectAsState(initial = emptyList())

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header with sync button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Your Library",
                style = MaterialTheme.typography.headlineMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Sync button
                if (authState.isLoggedIn) {
                    if (syncState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            syncState.progress,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        TextButton(
                            onClick = { LibrarySync.syncLibrary() }
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sync")
                        }
                    }
                }
            }
        }

        // Sync error
        if (syncState.error != null) {
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
                        syncState.error!!,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { LibrarySync.clearError() }) {
                        Icon(Icons.Default.Close, "Dismiss")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().suppressMediaKeys(),
            placeholder = { Text("Search library...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        // Tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp
        ) {
            LibraryTab.entries.forEach { tab ->
                val count = when (tab) {
                    LibraryTab.Songs -> librarySongs.size
                    LibraryTab.Albums -> albums.size
                    LibraryTab.Artists -> artists.size
                    LibraryTab.Playlists -> playlists.size
                    LibraryTab.Downloads -> downloadedSongs.size + activeDownloads.size
                }
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.name)
                            if (count > 0) {
                                Spacer(Modifier.width(4.dp))
                                Badge { Text(count.toString()) }
                            }
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Sort controls (for tabs that support sorting)
        if (selectedTab in listOf(LibraryTab.Songs, LibraryTab.Albums, LibraryTab.Artists)) {
            SortControls(sortMode, sortAscending, onSortModeChange = { sortMode = it }, onToggleDirection = { sortAscending = !sortAscending })
            Spacer(Modifier.height(8.dp))
        }

        // Content
        when (selectedTab) {
            LibraryTab.Songs -> SongsTab(librarySongs, likedSongs, player, artistNamesMap, searchQuery, sortMode, sortAscending)
            LibraryTab.Albums -> AlbumsTab(albums, onAlbumClick, searchQuery, sortMode, sortAscending)
            LibraryTab.Artists -> ArtistsTab(artists, onArtistClick, searchQuery, sortMode, sortAscending)
            LibraryTab.Playlists -> PlaylistsTab(playlists, onPlaylistClick, searchQuery)
            LibraryTab.Downloads -> DownloadsTab(downloadedSongs, activeDownloads, player, artistNamesMap, searchQuery)
        }
    }
}

@Composable
private fun SortControls(
    sortMode: SortMode,
    sortAscending: Boolean,
    onSortModeChange: (SortMode) -> Unit,
    onToggleDirection: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SortMode.entries.forEach { mode ->
            FilterChip(
                selected = sortMode == mode,
                onClick = { onSortModeChange(mode) },
                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
        }
        IconButton(onClick = onToggleDirection, modifier = Modifier.size(32.dp)) {
            Icon(
                if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (sortAscending) "Ascending" else "Descending",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    likedSongs: List<Song>,
    player: DesktopPlayer,
    artistNamesMap: Map<String, String>,
    searchQuery: String,
    sortMode: SortMode,
    sortAscending: Boolean
) {
    val scope = rememberCoroutineScope()
    val playerState by player.state.collectAsState()
    val queueSongs = playerState.queue

    var showLikedOnly by remember { mutableStateOf(false) }
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

    // Sort — DATE_ADDED defaults newest-first (descending), others default ascending
    val displaySongs = remember(filteredSongs, sortMode, sortAscending) {
        val sorted = when (sortMode) {
            SortMode.DATE_ADDED -> filteredSongs.sortedByDescending { it.inLibrary ?: it.likedDate ?: "" }
            SortMode.NAME -> filteredSongs.sortedBy { it.title.lowercase() }
            SortMode.PLAY_TIME -> filteredSongs.sortedByDescending { it.totalPlayTime }
        }
        if (sortAscending) sorted.reversed() else sorted
    }

    Column {
        // Filter chips + Play All / Shuffle All
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !showLikedOnly,
                onClick = { showLikedOnly = false },
                label = { Text("All (${songs.size})") }
            )
            FilterChip(
                selected = showLikedOnly,
                onClick = { showLikedOnly = true },
                label = { Text("Liked (${likedSongs.size})") },
                leadingIcon = if (showLikedOnly) {
                    { Icon(Icons.Default.Favorite, null, Modifier.size(18.dp)) }
                } else null
            )

            Spacer(Modifier.weight(1f))

            if (displaySongs.isNotEmpty()) {
                FilledTonalButton(
                    onClick = {
                        val allSongInfos = displaySongs.map { it.toSongInfo(artistNamesMap) }
                        scope.launch { player.playQueue(allSongInfos) }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play All")
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = {
                        val allSongInfos = displaySongs.map { it.toSongInfo(artistNamesMap) }.shuffled()
                        scope.launch { player.playQueue(allSongInfos) }
                    }
                ) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Shuffle")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (displaySongs.isEmpty() && queueSongs.isEmpty()) {
            EmptyLibraryMessage(
                icon = Icons.Default.MusicNote,
                message = if (searchQuery.isNotBlank()) "No matching songs"
                         else if (showLikedOnly) "No liked songs"
                         else "No songs in your library",
                subMessage = if (searchQuery.isNotBlank()) "Try a different search term"
                            else if (showLikedOnly) "Like songs to see them here"
                            else "Sync your library or play songs to add them"
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Show recent queue as "Recently Played" (only when not searching)
                if (queueSongs.isNotEmpty() && !showLikedOnly && searchQuery.isBlank()) {
                    item {
                        Text(
                            "Recently Played",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(queueSongs.take(10)) { song ->
                        SongListItem(
                            song = song,
                            isPlaying = playerState.currentSong?.id == song.id,
                            isDownloaded = DownloadManager.isDownloaded(song.id),
                            onClick = {
                                scope.launch {
                                    player.playSong(song)
                                }
                            },
                            onDownload = {
                                DownloadManager.queueDownload(song)
                            },
                            onPlayNext = { player.addToQueueNext(song) },
                            onAddToQueue = { player.addToQueue(song) }
                        )
                    }
                }

                if (displaySongs.isNotEmpty()) {
                    item {
                        Text(
                            if (showLikedOnly) "Liked Songs" else "Library",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(displaySongs) { dbSong ->
                        val song = dbSong.toSongInfo(artistNamesMap)
                        SongListItem(
                            song = song,
                            isPlaying = playerState.currentSong?.id == song.id,
                            isDownloaded = dbSong.isDownloaded == 1L,
                            isLiked = dbSong.liked == 1L,
                            onClick = {
                                scope.launch {
                                    player.playSong(song)
                                }
                            },
                            onDownload = {
                                DownloadManager.queueDownload(song)
                            },
                            onToggleLike = {
                                DatabaseHelper.updateSongLiked(dbSong.id, dbSong.liked != 1L)
                            },
                            onPlayNext = { player.addToQueueNext(song) },
                            onAddToQueue = { player.addToQueue(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongListItem(
    song: SongInfo,
    isPlaying: Boolean,
    isDownloaded: Boolean = false,
    isLiked: Boolean = false,
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
    onToggleLike: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isDownloaded) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.DownloadDone,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        leadingContent = {
            Box {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                if (isPlaying) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Playing",
                        modifier = Modifier
                            .size(48.dp)
                            .padding(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        trailingContent = {
            Row {
                if (isLiked) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Liked",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                }
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
                                onPlayNext()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                onAddToQueue()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (isLiked) "Unlike" else "Like") },
                            onClick = {
                                onToggleLike()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    if (isLiked) Icons.Default.HeartBroken else Icons.Default.Favorite,
                                    null
                                )
                            }
                        )
                        if (!isDownloaded) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    onDownload()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Remove Download") },
                                onClick = {
                                    DownloadManager.deleteDownload(song.id)
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    onAlbumClick: (String) -> Unit,
    searchQuery: String,
    sortMode: SortMode,
    sortAscending: Boolean
) {
    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) albums
        else albums.filter { it.title.lowercase().contains(searchQuery.lowercase()) }
    }

    val displayAlbums = remember(filteredAlbums, sortMode, sortAscending) {
        val sorted = when (sortMode) {
            SortMode.DATE_ADDED -> filteredAlbums // already sorted by date from DB
            SortMode.NAME -> filteredAlbums.sortedBy { it.title.lowercase() }
            SortMode.PLAY_TIME -> filteredAlbums.sortedByDescending { it.year ?: 0 } // sort by year as proxy
        }
        if (sortAscending && sortMode != SortMode.DATE_ADDED) sorted else if (sortMode == SortMode.DATE_ADDED) sorted else sorted.reversed()
    }

    if (displayAlbums.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Album,
            message = if (searchQuery.isNotBlank()) "No matching albums" else "No albums saved",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Sync your library to see saved albums"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(displayAlbums) { album ->
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
                    modifier = Modifier.clickable { onAlbumClick(album.id) }
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
    sortAscending: Boolean
) {
    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) artists
        else artists.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
    }

    val displayArtists = remember(filteredArtists, sortMode, sortAscending) {
        val sorted = when (sortMode) {
            SortMode.DATE_ADDED -> filteredArtists // already sorted by date from DB
            SortMode.NAME -> filteredArtists.sortedBy { it.name.lowercase() }
            SortMode.PLAY_TIME -> filteredArtists // no play time for artists, keep order
        }
        if (sortAscending && sortMode == SortMode.NAME) sorted else if (sortMode == SortMode.DATE_ADDED) sorted else sorted.reversed()
    }

    if (displayArtists.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Person,
            message = if (searchQuery.isNotBlank()) "No matching artists" else "No artists followed",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Sync your library to see subscribed artists"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(displayArtists) { artist ->
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
                    modifier = Modifier.clickable { onArtistClick(artist.id) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    onPlaylistClick: (String) -> Unit,
    searchQuery: String
) {
    val filteredPlaylists = remember(playlists, searchQuery) {
        if (searchQuery.isBlank()) playlists
        else playlists.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
    }

    if (filteredPlaylists.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            message = if (searchQuery.isNotBlank()) "No matching playlists" else "No playlists yet",
            subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Sync your library or create a playlist"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredPlaylists) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("${playlist.remoteSongCount ?: 0} songs")
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
                    modifier = Modifier.clickable { onPlaylistClick(playlist.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadsTab(
    downloadedSongs: List<Song>,
    activeDownloads: Map<String, com.metrolist.music.desktop.download.DownloadProgress>,
    player: DesktopPlayer,
    artistNamesMap: Map<String, String>,
    searchQuery: String
) {
    val scope = rememberCoroutineScope()
    val playerState by player.state.collectAsState()

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

    Column {
        // Active downloads
        if (activeDownloads.isNotEmpty()) {
            Text(
                "Downloading",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            activeDownloads.values.forEach { download ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
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
                                        "${download.progress}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                DownloadStatus.ERROR -> {
                                    Text(
                                        download.error ?: "Download failed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {}
                            }
                        }

                        IconButton(onClick = { DownloadManager.cancelDownload(download.songId) }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Downloaded songs
        if (filteredDownloaded.isEmpty() && activeDownloads.isEmpty()) {
            EmptyLibraryMessage(
                icon = Icons.Default.DownloadDone,
                message = if (searchQuery.isNotBlank()) "No matching downloads" else "No downloaded songs",
                subMessage = if (searchQuery.isNotBlank()) "Try a different search term" else "Download songs for offline playback"
            )
        } else if (filteredDownloaded.isNotEmpty()) {
            Text(
                "Downloaded (${filteredDownloaded.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredDownloaded) { dbSong ->
                    val song = dbSong.toSongInfo(artistNamesMap)
                    SongListItem(
                        song = song,
                        isPlaying = playerState.currentSong?.id == song.id,
                        isDownloaded = true,
                        onClick = {
                            scope.launch {
                                val localPath = dbSong.localPath
                                if (localPath != null) {
                                    player.playLocalFile(localPath, song)
                                } else {
                                    player.playSong(song)
                                }
                            }
                        },
                        onPlayNext = { player.addToQueueNext(song) },
                        onAddToQueue = { player.addToQueue(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    subMessage: String
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
        }
    }
}

// Extension function to convert database Song to SongInfo — uses artist names map
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
