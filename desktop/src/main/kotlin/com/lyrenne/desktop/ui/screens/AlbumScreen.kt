package com.lyrenne.desktop.ui.screens

import com.lyrenne.desktop.ui.components.SkeletonRows
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.ScrollableRow
import com.lyrenne.desktop.ui.components.MediaTile
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.headerScrolledAway
import com.lyrenne.desktop.ui.components.DetailHeader
import com.lyrenne.desktop.ui.components.TrackRow
import com.lyrenne.desktop.ui.components.select
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.lyrenne.desktop.download.CarExport
import com.lyrenne.desktop.download.DownloadManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.ui.components.CarExportStatus
import com.lyrenne.desktop.ui.components.chooseExportFolder
import kotlinx.coroutines.launch

@Composable
fun AlbumScreen(
    browseId: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: App keeps this screen's state while it is under another on the back
    // stack, so going back restores the page and scroll position instead of refetching.
    var albumPage by rememberSaveable { mutableStateOf<AlbumPage?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val playingId by player.select { it.currentSong?.id }

    LaunchedEffect(browseId) {
        if (albumPage != null) return@LaunchedEffect // restored from the back stack
        isLoading = true
        error = null
        YouTube.album(browseId).onSuccess {
            albumPage = it
        }.onFailure {
            error = friendlyErrorMessage(it, "Failed to load album")
        }
        isLoading = false
    }

    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.headerScrolledAway() } }
    val songInfos = remember(albumPage) { albumPage?.songs?.map { it.toDesktopSongInfo() }.orEmpty() }
    val playAll = { scope.launch { player.playQueue(songInfos, 0) }; Unit }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(onBack, albumPage?.album?.title.orEmpty(), collapsed, onPlay = if (albumPage != null) playAll else null)

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)) { SkeletonRows() }
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
                                YouTube.album(browseId).onSuccess { albumPage = it }
                                    .onFailure { error = friendlyErrorMessage(it, "Failed to load album") }
                                isLoading = false
                            }
                        }) { Text("Retry") }
                    }
                }
            }
            albumPage != null -> {
                val page = albumPage!!
                val songs = page.songs

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)
                ) {
                    item {
                        DetailHeader(
                            thumbnail = page.album.thumbnail,
                            kind = "Album",
                            title = page.album.title,
                            meta = listOfNotNull(page.album.year?.toString(), "${songs.size} songs").joinToString(" • "),
                            onPlay = playAll,
                            onShuffle = { scope.launch { player.playQueue(songInfos.shuffled(), 0) } },
                            // The already-downloaded filter used to run here, one database read
                            // per song on the UI thread. queueDownloads does it off-thread now.
                            onDownload = { DownloadManager.queueDownloads(songInfos, subfolder = page.album.title) },
                            subtitle = {
                                page.album.artists?.let { artists -> ArtistLinks(artists, onArtistClick) }
                            },
                            below = { CarExportStatus() },
                            more = { close ->
                                DropdownMenuItem(
                                    text = { Text("Export to folder") },
                                    leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
                                    onClick = {
                                        close()
                                        chooseExportFolder(page.album.title)?.let { CarExport.exportSongs(songInfos, it) }
                                    }
                                )
                            }
                        )
                    }

                    itemsIndexed(songs) { index, song ->
                        TrackRow(
                            song = songInfos[index],
                            player = player,
                            index = index + 1,
                            showArt = false,
                            explicit = song.explicit,
                            isPlaying = playingId == song.id,
                            onClick = { scope.launch { player.playQueue(songInfos, index) } }
                        )
                    }

                    if (page.otherVersions.isNotEmpty()) {
                        item {
                            Text(
                                "Other versions",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                            )
                            ScrollableRow {
                                items(page.otherVersions) { version ->
                                    MediaTile(version, player, onClick = { onAlbumClick(version.browseId) })
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

/** Artist names as links, comma separated. */
@Composable
internal fun ArtistLinks(artists: List<com.metrolist.innertube.models.Artist>, onArtistClick: (String) -> Unit) {
    Row {
        artists.forEachIndexed { index, artist ->
            Text(
                artist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (artist.id != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (artist.id != null) Modifier.clickable { onArtistClick(artist.id!!) } else Modifier
            )
            if (index < artists.size - 1) Text(", ", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

internal fun SongItem.toDesktopSongInfo(): SongInfo {
    return SongInfo(
        id = id,
        title = title,
        artist = artists.joinToString { it.name },
        thumbnailUrl = thumbnail,
        duration = duration ?: -1,
        album = album?.name
    )
}

