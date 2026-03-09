package com.metrolist.music.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.media.MediaKeyHandler
import com.metrolist.music.desktop.settings.PreferencesManager
import com.metrolist.music.desktop.ui.App
import com.metrolist.music.desktop.ui.theme.MetrolistTheme
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.integration.DiscordRPC
import com.metrolist.music.desktop.integration.LastFmManager
import com.metrolist.music.desktop.notification.DesktopNotification

fun main() {
    application {
        // Initialize services
        DatabaseHelper.initialize()
        PreferencesManager.initialize()
        AuthManager.initialize()

        val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        val player = remember { DesktopPlayer() }

        // Initialize media key handler after player is created
        LaunchedEffect(player) {
            MediaKeyHandler.initialize(player)
        }

        // Restore queue, apply saved volume, and initialize integrations
        LaunchedEffect(Unit) {
            player.restoreQueue()
            player.setVolume(PreferencesManager.preferences.value.volume)
            DiscordRPC.initialize(player)
            LastFmManager.initialize(player)
            DesktopNotification.initialize(player)
        }

        val prefs by PreferencesManager.preferences.collectAsState()
        val playerState by player.state.collectAsState()

        val windowTitle = remember(playerState.currentSong) {
            val song = playerState.currentSong
            if (song != null) "♪ ${song.title} — ${song.artist} | Metrolist" else "Metrolist"
        }

        Window(
            onCloseRequest = {
                DesktopNotification.release()
                LastFmManager.release()
                DiscordRPC.release()
                MediaKeyHandler.release()
                player.release()
                exitApplication()
            },
            title = windowTitle,
            state = windowState,
            icon = androidx.compose.ui.res.painterResource("icon.png"),
        ) {
            MetrolistTheme(themeMode = prefs.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    App(player = player)
                }
            }
        }
    }
}
