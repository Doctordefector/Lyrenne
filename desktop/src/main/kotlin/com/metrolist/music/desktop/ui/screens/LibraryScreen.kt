package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.sync.LibrarySync
import kotlinx.coroutines.launch

enum class LibraryTab {
    Songs, Albums, Artists, Playlists, Downloads
}

@Composable
fun LibraryScreen(
    player: DesktopPlayer,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.Songs) }
    val scope = rememberCoroutineScope()

    // Collect from database
    val librarySongs by DatabaseHelper.getAllSongs().collectAsState(initial = emptyList())
    val likedSongs by DatabaseHelper.getLikedSongs().collectAsState(initial = emptyList())
    val downloadedSongs by DatabaseHelper.getDownloadedSongs().collectAsState(initial = emptyList())
    val albums by DatabaseHelper.getAllAlbums().collectAsState(initial = emptyList())
    val artists by DatabaseHelper.getAllArtists().collectAsState(initial = emptyList())
    val playlists by DatabaseHelper.getAllPlaylists().collectAsState(initial = emptyList())

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

        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(16.dp))

        // Content
        when (selectedTab) {
            LibraryTab.Songs -> SongsTab(librarySongs, likedSongs, player)
            LibraryTab.Albums -> AlbumsTab(albums, onAlbumClick)
            LibraryTab.Artists -> ArtistsTab(artists, onArtistClick)
            LibraryTab.Playlists -> PlaylistsTab(playlists, onPlaylistClick)
            LibraryTab.Downloads -> DownloadsTab(downloadedSongs, activeDownloads, player)
        }
    }
}

@Composable
private fun SongsTab(songs: List<Song>, likedSongs: List<Song>, player: DesktopPlayer) {
    val scope = rememberCoroutineScope()
    val playerState by player.state.collectAsState()
    val queueSongs = playerState.queue

    var showLikedOnly by remember { mutableStateOf(false) }
    val displaySongs = if (showLikedOnly) likedSongs else songs

    Column {
        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        }

        Spacer(Modifier.height(8.dp))

        if (displaySongs.isEmpty() && queueSongs.isEmpty()) {
            EmptyLibraryMessage(
                icon = Icons.Default.MusicNote,
                message = if (showLikedOnly) "No liked songs" else "No songs in your library",
                subMessage = if (showLikedOnly) "Like songs to see them here" else "Sync your library or play songs to add them"
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Show recent queue as "Recently Played"
                if (queueSongs.isNotEmpty() && !showLikedOnly) {
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
                            }
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
                        val song = dbSong.toSongInfo()
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
                            }
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
    onToggleLike: () -> Unit = {}
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
private fun AlbumsTab(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    if (albums.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Album,
            message = "No albums saved",
            subMessage = "Sync your library to see saved albums"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(albums) { album ->
                ListItem(
                    headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${album.songCount} songs" + (album.year?.let { " - $it" } ?: ""),
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
private fun ArtistsTab(artists: List<Artist>, onArtistClick: (String) -> Unit) {
    if (artists.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.Default.Person,
            message = "No artists followed",
            subMessage = "Sync your library to see subscribed artists"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(artists) { artist ->
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
private fun PlaylistsTab(playlists: List<Playlist>, onPlaylistClick: (String) -> Unit) {
    if (playlists.isEmpty()) {
        EmptyLibraryMessage(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            message = "No playlists yet",
            subMessage = "Sync your library or create a playlist"
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(playlists) { playlist ->
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
    player: DesktopPlayer
) {
    val scope = rememberCoroutineScope()
    val playerState by player.state.collectAsState()

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
        if (downloadedSongs.isEmpty() && activeDownloads.isEmpty()) {
            EmptyLibraryMessage(
                icon = Icons.Default.DownloadDone,
                message = "No downloaded songs",
                subMessage = "Download songs for offline playback"
            )
        } else if (downloadedSongs.isNotEmpty()) {
            Text(
                "Downloaded (${downloadedSongs.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(downloadedSongs) { dbSong ->
                    val song = dbSong.toSongInfo()
                    SongListItem(
                        song = song,
                        isPlaying = playerState.currentSong?.id == song.id,
                        isDownloaded = true,
                        onClick = {
                            scope.launch {
                                // Play from local file if available
                                val localPath = dbSong.localPath
                                if (localPath != null) {
                                    player.playLocalFile(localPath, song)
                                } else {
                                    player.playSong(song)
                                }
                            }
                        }
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

// Extension function to convert database Song to SongInfo
private fun Song.toSongInfo(): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = albumName ?: "Unknown Artist", // We'll need to fetch artists separately
        album = albumName,
        duration = duration.toInt(),
        thumbnailUrl = thumbnailUrl
    )
}
