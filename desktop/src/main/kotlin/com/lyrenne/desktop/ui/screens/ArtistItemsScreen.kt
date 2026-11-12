package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.ui.components.DetailTopBar
import com.lyrenne.desktop.ui.components.MediaTile
import com.lyrenne.desktop.ui.components.ScreenTitle
import com.lyrenne.desktop.ui.components.ScrollbarLazyGrid
import com.lyrenne.desktop.ui.components.subtitle
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Everything in one artist section: all albums, all singles, all videos. The artist page only
 * carries the first ten or so of each, newest first, so an artist's older albums were unreachable.
 */
@Composable
fun ArtistItemsScreen(
    browseId: String,
    params: String?,
    title: String,
    player: DesktopPlayer,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by rememberSaveable { mutableStateOf<List<YTItem>?>(null) }
    var pageTitle by rememberSaveable { mutableStateOf(title) }
    var continuation by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(browseId, params) {
        if (items != null) return@LaunchedEffect // restored from the back stack
        YouTube.artistItems(BrowseEndpoint(browseId, params)).onSuccess { page ->
            items = page.items
            continuation = page.continuation
            if (page.title.isNotBlank()) pageTitle = page.title
        }.onFailure { error = friendlyErrorMessage(it, "Failed to load") }
    }

    // Next page while the end of the grid is in view
    LaunchedEffect(items != null) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            continuation != null && last >= gridState.layoutInfo.totalItemsCount - 6
        }.collect { nearEnd ->
            val cont = continuation
            if (!nearEnd || cont == null || loadingMore) return@collect
            loadingMore = true
            YouTube.artistItemsContinuation(cont).onSuccess { page ->
                items = items.orEmpty() + page.items
                continuation = page.continuation
            }.onFailure {
                Timber.w("Artist items $browseId: next page failed: ${it.message}")
                continuation = null
            }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(onBack, pageTitle, collapsed = false)
        ScreenTitle(pageTitle, Modifier.padding(horizontal = LyrenneTokens.contentPadding))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize().padding(horizontal = LyrenneTokens.contentPadding)) {
            val shown = items
            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
                shown == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> ScrollbarLazyGrid(
                    columns = GridCells.Adaptive(minSize = 184.dp),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown) { item ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            MediaTile(
                                item = item,
                                player = player,
                                subtitle = (item as? AlbumItem)?.year?.toString() ?: item.subtitle(),
                                onClick = {
                                    when (item) {
                                        is AlbumItem -> onAlbumClick(item.browseId)
                                        is ArtistItem -> onArtistClick(item.id)
                                        is PlaylistItem -> onPlaylistClick(item.id)
                                        is SongItem -> scope.launch { player.playSong(item.toDesktopSongInfo()) }
                                        else -> {}
                                    }
                                }
                            )
                        }
                    }
                    if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}
