package com.metrolist.music.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.metrolist.music.desktop.ui.components.TRAY_PANEL_HEIGHT
import com.metrolist.music.desktop.ui.components.TRAY_PANEL_WIDTH
import com.metrolist.music.desktop.ui.components.TrayPanel
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.media.MediaKeyHandler
import com.metrolist.music.desktop.settings.PreferencesManager
import com.metrolist.music.desktop.sync.LibrarySync
import com.metrolist.music.desktop.ui.App
import com.metrolist.music.desktop.ui.theme.MetrolistTheme
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.integration.DiscordRPC
import com.metrolist.music.desktop.integration.LastFmManager
import com.metrolist.music.desktop.notification.DesktopNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import timber.log.Timber

/**
 * Apply content locale and proxy settings to the InnerTube client.
 * Called at startup and whenever the user changes these settings.
 */
fun applyNetworkPreferences() {
    val prefs = PreferencesManager.preferences.value
    try {
        val systemLocale = java.util.Locale.getDefault()
        val gl = if (prefs.contentCountry == "system") systemLocale.country.ifEmpty { "US" } else prefs.contentCountry
        val hl = if (prefs.contentLanguage == "system") systemLocale.language.ifEmpty { "en" } else prefs.contentLanguage
        com.metrolist.innertube.YouTube.locale = com.metrolist.innertube.models.YouTubeLocale(gl = gl, hl = hl)

        if (prefs.proxyEnabled && prefs.proxyHost.isNotBlank()) {
            val type = when (prefs.proxyType) {
                com.metrolist.music.desktop.settings.ProxyType.HTTP -> java.net.Proxy.Type.HTTP
                com.metrolist.music.desktop.settings.ProxyType.SOCKS -> java.net.Proxy.Type.SOCKS
            }
            com.metrolist.innertube.YouTube.proxy = java.net.Proxy(
                type,
                java.net.InetSocketAddress(prefs.proxyHost, prefs.proxyPort)
            )
            com.metrolist.innertube.YouTube.proxyAuth = if (prefs.proxyUsername.isNotBlank()) {
                "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("${prefs.proxyUsername}:${prefs.proxyPassword}".toByteArray())
            } else null
        } else {
            com.metrolist.innertube.YouTube.proxy = null
            com.metrolist.innertube.YouTube.proxyAuth = null
        }
    } catch (e: Exception) {
        Timber.e("Failed to apply network preferences: ${e.message}")
    }
}

fun main() {
    // Every remaining Swing dialog (folder pickers, backup/restore) defaults to the
    // cross-platform Metal look — grey 1990s widgets. The system L&F makes them render
    // as native Windows dialogs instead. Must be set before any Swing class loads.
    try {
        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {
        Timber.w("Could not apply system look and feel: ${e.message}")
    }

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

    // Initialize core services before window (fast, no I/O)
    DatabaseHelper.initialize()
    PreferencesManager.initialize()
    applyNetworkPreferences()

    application {
        val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        val player = remember { DesktopPlayer() }
        var windowVisible by remember { mutableStateOf(true) }
        var trayPanelAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        // Initialize VLC, auth, media keys, queue restore, and integrations off the main thread
        LaunchedEffect(player) {
            withContext(Dispatchers.IO) {
                player.ensureVlcInitialized()
            }
            MediaKeyHandler.initialize(player)
            // Auth needs network — run after window is visible
            AuthManager.initialize()
            // Restore queue (metadata only, no stream URL resolution)
            player.restoreQueue()
            player.setVolume(PreferencesManager.preferences.value.volume)
            // Auto-sync library on each launch (no-op if not logged in or already syncing)
            delay(1000) // brief delay so UI settles before network storm
            if (PreferencesManager.preferences.value.autoSyncOnStartup) {
                LibrarySync.syncLibrary()
            }
            // Integrations
            DiscordRPC.initialize(player)
            LastFmManager.initialize(player)
            DesktopNotification.initialize(player)

            // Set up tray callbacks for minimize-to-tray
            DesktopNotification.onShowWindow = {
                windowVisible = true
            }
            DesktopNotification.onTrayMenu = { x, y ->
                trayPanelAt = x to y
            }
            DesktopNotification.onExitApp = {
                DesktopNotification.release()
                LastFmManager.release()
                DiscordRPC.release()
                MediaKeyHandler.release()
                player.release()
                exitApplication()
            }
        }

        val prefs by PreferencesManager.preferences.collectAsState()
        val playerState by player.state.collectAsState()

        val windowTitle = remember(playerState.currentSong) {
            val song = playerState.currentSong
            if (song != null) "♪ ${song.title} — ${song.artist} | Metrolist" else "Metrolist"
        }

        Window(
            onCloseRequest = {
                if (prefs.minimizeToTray) {
                    // Minimize to tray instead of exiting
                    windowVisible = false
                } else {
                    // Actually exit
                    DesktopNotification.release()
                    LastFmManager.release()
                    DiscordRPC.release()
                    MediaKeyHandler.release()
                    player.release()
                    exitApplication()
                }
            },
            visible = windowVisible,
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

        // Tray popup — replaces AWT's unthemeable native PopupMenu.
        trayPanelAt?.let { (clickX, clickY) ->
            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
            // Anchor above-left of the cursor (the tray sits bottom-right), clamped on screen.
            val x = (clickX - TRAY_PANEL_WIDTH / 2).coerceIn(8, screen.width - TRAY_PANEL_WIDTH - 8)
            val y = (clickY - TRAY_PANEL_HEIGHT - 16).coerceIn(8, screen.height - TRAY_PANEL_HEIGHT - 8)

            Window(
                onCloseRequest = { trayPanelAt = null },
                state = rememberWindowState(
                    width = TRAY_PANEL_WIDTH.dp,
                    height = TRAY_PANEL_HEIGHT.dp,
                    position = WindowPosition(x.dp, y.dp)
                ),
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                focusable = true,
                title = "Metrolist"
            ) {
                // Dismiss when the user clicks elsewhere, the way a real menu behaves.
                DisposableEffect(Unit) {
                    val listener = object : java.awt.event.WindowAdapter() {
                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                            trayPanelAt = null
                        }
                    }
                    window.addWindowFocusListener(listener)
                    window.toFront()
                    window.requestFocus()
                    onDispose { window.removeWindowFocusListener(listener) }
                }

                MetrolistTheme(themeMode = prefs.themeMode) {
                    TrayPanel(
                        player = player,
                        onOpenWindow = { windowVisible = true },
                        onQuit = {
                            DesktopNotification.release()
                            LastFmManager.release()
                            DiscordRPC.release()
                            MediaKeyHandler.release()
                            player.release()
                            exitApplication()
                        },
                        onDismiss = { trayPanelAt = null }
                    )
                }
            }
        }
    }
}
