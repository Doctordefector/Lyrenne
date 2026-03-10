package com.metrolist.music.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import org.jetbrains.skia.Image
import timber.log.Timber

fun main() {
    // Load icon once at startup from classpath resources (512x512 PNG)
    val appIcon = try {
        val bytes = Thread.currentThread().contextClassLoader
            .getResourceAsStream("icon.png")?.readBytes()
        if (bytes != null) {
            BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        } else {
            Timber.w("icon.png not found in classpath resources")
            null
        }
    } catch (e: Exception) {
        Timber.e("Failed to load app icon: ${e.message}")
        null
    }

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
            icon = appIcon,
        ) {
            // Set AWT icon images for taskbar/alt-tab (multiple sizes for best quality)
            LaunchedEffect(Unit) {
                try {
                    val iconStream = Thread.currentThread().contextClassLoader
                        .getResourceAsStream("icon.png")
                    if (iconStream != null) {
                        val awtImage = javax.imageio.ImageIO.read(iconStream)
                        if (awtImage != null) {
                            // Provide multiple sizes for Windows taskbar (small=16/24, large=32/48/256)
                            val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
                            val scaledImages = sizes.map { size ->
                                val scaled = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                val g2d = scaled.createGraphics()
                                g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                                g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                                g2d.drawImage(awtImage, 0, 0, size, size, null)
                                g2d.dispose()
                                scaled as java.awt.Image
                            }
                            window.iconImages = scaledImages
                        }
                    }
                } catch (e: Exception) {
                    Timber.w("Failed to set AWT window icons: ${e.message}")
                }
            }

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
