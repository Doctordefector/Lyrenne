package com.metrolist.music.desktop.ui

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
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.ui.screens.*
import kotlinx.coroutines.launch
import com.metrolist.music.desktop.ui.components.LyricsPanel
import com.metrolist.music.desktop.ui.components.MiniPlayer

enum class Screen(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
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
    data object ListenTogether : DetailScreen()
}

@Composable
fun App(player: DesktopPlayer) {
    var currentAppScreen by remember { mutableStateOf<AppScreen>(AppScreen.Main) }
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var showQueueScreen by remember { mutableStateOf(false) }
    var showLyricsPanel by remember { mutableStateOf(false) }
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
            Row(modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        // Ctrl+Right = next track
                        (event.key == Key.DirectionRight && event.isCtrlPressed) -> {
                            scope.launch { player.playNext() }
                            true
                        }
                        // Ctrl+Left = previous track
                        (event.key == Key.DirectionLeft && event.isCtrlPressed) -> {
                            scope.launch { player.playPrevious() }
                            true
                        }
                        // Ctrl+F = focus search
                        event.key == Key.F && event.isCtrlPressed -> {
                            currentScreen = Screen.Search
                            detailStack.clear()
                            true
                        }
                        // Ctrl+Q = toggle queue
                        event.key == Key.Q && event.isCtrlPressed -> {
                            showQueueScreen = !showQueueScreen
                            true
                        }
                        // Ctrl+L = toggle lyrics
                        event.key == Key.L && event.isCtrlPressed -> {
                            showLyricsPanel = !showLyricsPanel
                            true
                        }
                        // Escape = go back / close overlays
                        event.key == Key.Escape -> {
                            when {
                                showQueueScreen -> { showQueueScreen = false; true }
                                showLyricsPanel -> { showLyricsPanel = false; true }
                                detailStack.isNotEmpty() -> { navigateBack(); true }
                                else -> false
                            }
                        }
                        else -> false
                    }
                } else false
            }) {
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
                                    is DetailScreen.ListenTogether -> ListenTogetherScreen(
                                        player = player,
                                        onBack = ::navigateBack
                                    )
                                }
                            } else {
                                when (currentScreen) {
                                    Screen.Home -> HomeScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist
                                    )
                                    Screen.Search -> SearchScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist
                                    )
                                    Screen.Library -> LibraryScreen(
                                        player = player,
                                        onAlbumClick = ::navigateToAlbum,
                                        onArtistClick = ::navigateToArtist,
                                        onPlaylistClick = ::navigateToPlaylist
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
