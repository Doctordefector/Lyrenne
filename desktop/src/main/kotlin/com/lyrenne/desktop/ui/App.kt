package com.lyrenne.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.media.MediaKeyHandler
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.screens.*
import kotlinx.coroutines.launch
import com.lyrenne.desktop.ui.components.AutoScroll
import com.lyrenne.desktop.ui.components.LyricsPanel
import com.lyrenne.desktop.ui.components.MiniPlayer

enum class Screen(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Explore("Explore", Icons.Outlined.Explore, Icons.Filled.Explore),
    Search("Search", Icons.Outlined.Search, Icons.Filled.Search),
    Library("Library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

sealed class AppScreen {
    data object Main : AppScreen()
    data object Login : AppScreen()
}

// Navigation destinations for detail screens
sealed class DetailScreen {
    data class Album(val browseId: String) : DetailScreen()
    data class Artist(val browseId: String) : DetailScreen()
    data class Playlist(val playlistId: String) : DetailScreen()
    data class LocalPlaylist(val playlistId: String) : DetailScreen()
    data class AutoPlaylist(val type: AutoPlaylistType) : DetailScreen()
    data class Podcast(val podcastId: String) : DetailScreen()
    data class Browse(val browseId: String, val params: String?, val title: String) : DetailScreen()
    data object ListenTogether : DetailScreen()
    data object Recognition : DetailScreen()
    data object Equalizer : DetailScreen()
    data object Stats : DetailScreen()
}

enum class AutoPlaylistType(val title: String) {
    LIKED("Liked Songs"),
    DOWNLOADED("Downloaded"),
    MOST_PLAYED("Most Played")
}

@Composable
fun App(player: DesktopPlayer) {
    var currentAppScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var showQueueScreen by remember { mutableStateOf(false) }
    var showLyricsPanel by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    val authState by AuthManager.authState.collectAsState()
    val scope = rememberCoroutineScope()

    // Navigation stack for detail screens
    val detailStack = remember { mutableStateListOf<DetailScreen>() }

    fun navigateToAlbum(browseId: String) {
        detailStack.add(DetailScreen.Album(browseId))
    }

    fun navigateToArtist(browseId: String) {
        detailStack.add(DetailScreen.Artist(browseId))
    }

    fun navigateToPlaylist(playlistId: String) {
        detailStack.add(DetailScreen.Playlist(playlistId))
    }

    fun navigateToPodcast(podcastId: String) {
        detailStack.add(DetailScreen.Podcast(podcastId))
    }

    fun navigateBack() {
        if (detailStack.isNotEmpty()) {
            detailStack.removeAt(detailStack.lastIndex)
        }
    }

    // Queue screen overlay
    if (showQueueScreen) {
        QueueScreen(
            player = player,
            onDismiss = { showQueueScreen = false }
        )
        return
    }

    when (currentAppScreen) {
        AppScreen.Login -> {
            LoginScreen(
                onBack = { currentAppScreen = AppScreen.Main },
                onLoginSuccess = {
                    currentAppScreen = AppScreen.Main
                    currentScreen = Screen.Home
                }
            )
        }

        AppScreen.Main -> {
            Row(modifier = Modifier.fillMaxSize()
                // TUNNEL phase (parent-first): Escape + Ctrl shortcuts only.
                // These use modifier keys so they don't conflict with text input.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> when {
                            AutoScroll.isActive -> { AutoScroll.stop(); true }
                            showQueueScreen -> { showQueueScreen = false; true }
                            showLyricsPanel -> { showLyricsPanel = false; true }
                            detailStack.isNotEmpty() -> { navigateBack(); true }
                            else -> false
                        }
                        event.isCtrlPressed -> when (event.key) {
                            // Navigation shortcuts stay global.
                            Key.F -> { currentScreen = Screen.Search; detailStack.clear(); true }
                            Key.Q -> { showQueueScreen = !showQueueScreen; true }
                            Key.L -> { showLyricsPanel = !showLyricsPanel; true }
                            Key.K -> { showCommandPalette = !showCommandPalette; true }
                            // Player shortcuts must not steal Ctrl+Left/Right (word jump),
                            // Ctrl+S/P/R etc. while the user is typing in a text field.
                            // Preview runs before the field sees the event, so check explicitly.
                            Key.P -> if (MediaKeyHandler.textInputActive) false
                                else { player.togglePlayPause(); true }
                            Key.S -> if (MediaKeyHandler.textInputActive) false
                                else { player.toggleShuffle(); true }
                            Key.R -> if (MediaKeyHandler.textInputActive) false
                                else { player.toggleRepeat(); true }
                            Key.DirectionRight -> if (MediaKeyHandler.textInputActive) false
                                else { scope.launch { player.playNext() }; true }
                            Key.DirectionLeft -> if (MediaKeyHandler.textInputActive) false
                                else { scope.launch { player.playPrevious() }; true }
                            Key.DirectionUp -> if (MediaKeyHandler.textInputActive) false else {
                                val v = PreferencesManager.preferences.value.volume
                                val nv = (v + 0.05f).coerceAtMost(1f)
                                PreferencesManager.setVolume(nv); player.setVolume(nv); true
                            }
                            Key.DirectionDown -> if (MediaKeyHandler.textInputActive) false else {
                                val v = PreferencesManager.preferences.value.volume
                                val nv = (v - 0.05f).coerceAtLeast(0f)
                                PreferencesManager.setVolume(nv); player.setVolume(nv); true
                            }
                            else -> false
                        }
                        else -> false
                    }
                }
                // BUBBLE phase (child-first): Bare keys (Space, M, arrows).
                // Text fields consume these first (typing characters / moving cursor).
                // Only reaches here if NO text field handled the event.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (event.isCtrlPressed) return@onKeyEvent false // already handled in preview
                    if (MediaKeyHandler.textInputActive) return@onKeyEvent false
                    when (event.key) {
                        Key.Spacebar -> { player.togglePlayPause(); true }
                        Key.M -> { MediaKeyHandler.toggleMute(player); true }
                        Key.DirectionRight -> {
                            val pos = player.state.value.position
                            val dur = player.state.value.duration
                            player.seekTo((pos + 10000).coerceAtMost(dur)); true
                        }
                        Key.DirectionLeft -> {
                            val pos = player.state.value.position
                            player.seekTo((pos - 10000).coerceAtLeast(0)); true
                        }
                        else -> false
                    }
                }
            ) {
                // Side navigation rail
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    header = {
                        Spacer(Modifier.height(8.dp))
                        AccountButton(
                            isLoggedIn = authState.isLoggedIn,
                            accountName = authState.accountInfo?.name,
                            avatarUrl = authState.accountInfo?.avatarUrl,
                            onClick = {
                                if (authState.isLoggedIn) {
                                    currentScreen = Screen.Settings
                                    detailStack.clear()
                                } else {
                                    currentAppScreen = AppScreen.Login
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                ) {
                    Spacer(Modifier.height(8.dp))

                    Screen.entries.forEach { screen ->
                        NavigationRailItem(
                            icon = {
                                Icon(
                                    if (currentScreen == screen && detailStack.isEmpty()) screen.selectedIcon else screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen && detailStack.isEmpty(),
                            onClick = {
                                currentScreen = screen
                                detailStack.clear()
                            }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Equalizer
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Equalizer, "Equalizer") },
                        label = { Text("EQ") },
                        selected = detailStack.lastOrNull() is DetailScreen.Equalizer,
                        onClick = {
                            detailStack.clear()
                            detailStack.add(DetailScreen.Equalizer)
                        }
                    )

                    // Stats
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.BarChart, "Stats") },
                        label = { Text("Stats") },
                        selected = detailStack.lastOrNull() is DetailScreen.Stats,
                        onClick = {
                            detailStack.clear()
                            detailStack.add(DetailScreen.Stats)
                        }
                    )

                    // Music Recognition button
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Mic, "Recognize") },
                        label = { Text("Recognize") },
                        selected = detailStack.lastOrNull() is DetailScreen.Recognition,
                        onClick = {
                            detailStack.clear()
                            detailStack.add(DetailScreen.Recognition)
                        }
                    )

                    // Listen Together button
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Group, "Listen Together") },
                        label = { Text("Together") },
                        selected = detailStack.lastOrNull() is DetailScreen.ListenTogether,
                        onClick = {
                            detailStack.clear()
                            detailStack.add(DetailScreen.ListenTogether)
                        }
                    )

                    if (!authState.isLoggedIn) {
                        NavigationRailItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Login, "Sign In") },
                            label = { Text("Sign In") },
                            selected = false,
                            onClick = { currentAppScreen = AppScreen.Login }
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // Main content
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // Screen content
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val currentDetail = detailStack.lastOrNull()
                            if (currentDetail != null) {
                                when (currentDetail) {
                                    is DetailScreen.Album -> AlbumScreen(
                                        browseId = currentDetail.browseId,
                                        player = player,
                                        onBack = ::navigateBack,
                                        onArtistClick = ::navigateToArtist
                                    )
                                    is DetailScreen.Artist -> ArtistScreen(
                                        browseId = currentDetail.browseId,
                                        player = player,
                                        onBack = ::navigateBack,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist
                                    )
                                    is DetailScreen.Playlist -> PlaylistScreen(
                                        playlistId = currentDetail.playlistId,
                                        player = player,
                                        onBack = ::navigateBack,
                                        onArtistClick = ::navigateToArtist
                                    )
                                    is DetailScreen.LocalPlaylist -> LocalPlaylistScreen(
                                        playlistId = currentDetail.playlistId,
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.AutoPlaylist -> AutoPlaylistScreen(
                                        type = currentDetail.type,
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.Browse -> BrowseScreen(
                                        browseId = currentDetail.browseId,
                                        params = currentDetail.params,
                                        title = currentDetail.title,
                                        player = player,
                                        onBack = ::navigateBack,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist
                                    )
                                    is DetailScreen.Podcast -> PodcastScreen(
                                        podcastId = currentDetail.podcastId,
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.ListenTogether -> ListenTogetherScreen(
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.Recognition -> RecognitionScreen(
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.Equalizer -> EqualizerScreen(
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                    is DetailScreen.Stats -> StatsScreen(
                                        onBack = ::navigateBack,
                                        onArtistClick = ::navigateToArtist,
                                        onAlbumClick = ::navigateToAlbum,
                                        player = player
                                    )
                                }
                            } else {
                                when (currentScreen) {
                                    Screen.Home -> HomeScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist,
                                        onPodcastClick = ::navigateToPodcast
                                    )
                                    Screen.Explore -> ExploreScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist,
                                        onBrowseClick = { browseId, params, title ->
                                            detailStack.add(DetailScreen.Browse(browseId, params, title))
                                        }
                                    )
                                    Screen.Search -> SearchScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist,
                                        onPodcastClick = ::navigateToPodcast
                                    )
                                    Screen.Library -> LibraryScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist,
                                        onLocalPlaylistClick = { id ->
                                            detailStack.add(DetailScreen.LocalPlaylist(id))
                                        },
                                        onAutoPlaylistClick = { type ->
                                            detailStack.add(DetailScreen.AutoPlaylist(type))
                                        }
                                    )
                                    Screen.Settings -> SettingsScreen(
                                        onLoginClick = { currentAppScreen = AppScreen.Login }
                                    )
                                }
                            }
                        }

                        // Lyrics panel (slides in from right)
                        LyricsPanel(
                            player = player,
                            visible = showLyricsPanel,
                            onDismiss = { showLyricsPanel = false }
                        )
                    }

                    MiniPlayer(
                        player = player,
                        modifier = Modifier.fillMaxWidth(),
                        onQueueClick = { showQueueScreen = true },
                        onLyricsClick = { showLyricsPanel = !showLyricsPanel },
                        lyricsActive = showLyricsPanel
                    )
                }

                // Command palette overlay
                CommandPalette(
                    visible = showCommandPalette,
                    player = player,
                    onDismiss = { showCommandPalette = false },
                    onNavigate = { action: PaletteAction ->
                        when (action) {
                            is PaletteAction.GoHome -> { currentScreen = Screen.Home; detailStack.clear() }
                            is PaletteAction.GoSearch -> { currentScreen = Screen.Search; detailStack.clear() }
                            is PaletteAction.GoLibrary -> { currentScreen = Screen.Library; detailStack.clear() }
                            is PaletteAction.GoSettings -> { currentScreen = Screen.Settings; detailStack.clear() }
                            is PaletteAction.GoEqualizer -> { detailStack.clear(); detailStack.add(DetailScreen.Equalizer) }
                            is PaletteAction.GoStats -> { detailStack.clear(); detailStack.add(DetailScreen.Stats) }
                            is PaletteAction.GoQueue -> { showQueueScreen = true }
                            is PaletteAction.GoLyrics -> { showLyricsPanel = !showLyricsPanel }
                            is PaletteAction.NextTrack -> scope.launch { player.playNext() }
                            is PaletteAction.PrevTrack -> scope.launch { player.playPrevious() }
                            is PaletteAction.PlaySong -> scope.launch { player.playSong(action.song) }
                            else -> {} // PlayPause, Shuffle, Repeat handled in executeAction
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AccountButton(
    isLoggedIn: Boolean,
    accountName: String?,
    avatarUrl: String?,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        if (isLoggedIn && avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = accountName ?: "Account",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else if (isLoggedIn) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = accountName?.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = "Sign In",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
