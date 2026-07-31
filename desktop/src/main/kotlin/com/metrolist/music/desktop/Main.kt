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
import com.metrolist.music.desktop.ui.components.AutoScroll
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
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

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

/**
 * Where the log lives: next to the exe, or the temp dir if that folder is not writable.
 * Resolved before anything else runs, so it must not depend on app state.
 */
private fun logFile(): File {
    val next = File(AppPaths.appDir, "lyrenne.log")
    return if (next.parentFile?.canWrite() == true) next
    else File(System.getProperty("java.io.tmpdir"), "lyrenne.log")
}

/**
 * The Windows launcher is a GUI-subsystem exe, so stderr goes nowhere: a crash during startup
 * shows the user an empty desktop and nothing else. Send stderr to a file instead — that also
 * catches SLF4J/Timber output and JNA's native-load failures — and report anything fatal in a
 * dialog, because a user who never sees a window has no other way to find out what broke.
 *
 * ponytail: truncated per launch rather than rotated — one session's log is what's diagnostic.
 */
private fun installCrashReporting(): File {
    val log = logFile()
    try {
        System.setErr(PrintStream(FileOutputStream(log, false), true))
    } catch (e: Exception) {
        // Read-only folder or the file is locked by another instance — keep the default stderr.
    }
    Thread.setDefaultUncaughtExceptionHandler { _, e -> reportFatal(e, log) }
    return log
}

private fun reportFatal(e: Throwable, log: File) {
    try {
        System.err.println("FATAL: ${e.stackTraceToString()}")
    } catch (ignored: Exception) {
    }
    try {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Lyrenne could not start.\n\n${e::class.simpleName}: ${e.message}\n\nDetails: ${log.absolutePath}",
            "Lyrenne",
            javax.swing.JOptionPane.ERROR_MESSAGE
        )
    } catch (ignored: Throwable) {
        // Headless or AWT itself is broken — the log file is the fallback.
    }
}

/**
 * Read a bundled PNG from the classpath, or null if it is missing or unreadable.
 *
 * Callers pick between "icon.png", the full mark with its gold ring, and "icon-small.png", the
 * same lyre without it. The ring dominates once the artwork is scaled to tray or taskbar size.
 */
private fun loadResourceImage(name: String): java.awt.image.BufferedImage? = try {
    Thread.currentThread().contextClassLoader.getResourceAsStream(name)
        ?.use { javax.imageio.ImageIO.read(it) }
        ?: run { Timber.w("$name not found in classpath resources"); null }
} catch (e: Exception) {
    Timber.w("Failed to read $name: ${e.message}")
    null
}

fun main() {
    val log = installCrashReporting()
    try {
        runApp()
    } catch (e: Throwable) {
        reportFatal(e, log)
        exitProcess(1)
    }
}

private fun runApp() {
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
            if (song != null) "♪ ${song.title} — ${song.artist} | Lyrenne" else "Lyrenne"
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
            // Middle-click autoscroll. Registered on the toolkit rather than the window so it
            // fires wherever the pointer is inside the app — Compose consumes mouse events
            // before they reach any listener on the window itself.
            DisposableEffect(window) {
                val listener = java.awt.event.AWTEventListener { event ->
                    val e = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
                    if (e.id != java.awt.event.MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                    if (e.button == java.awt.event.MouseEvent.BUTTON2) {
                        AutoScroll.toggle(e.xOnScreen, e.yOnScreen, window)
                    } else if (AutoScroll.isActive) {
                        AutoScroll.stop()
                    }
                }
                java.awt.Toolkit.getDefaultToolkit()
                    .addAWTEventListener(listener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
                onDispose {
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                    AutoScroll.stop()
                }
            }

            // Set AWT icon images for taskbar/alt-tab (multiple sizes for best quality)
            LaunchedEffect(Unit) {
                try {
                    // Two artworks, picked by size. The full mark has a gold ring that is most of
                    // the pixels once it is scaled to taskbar size, so it reads as a gold box
                    // rather than a lyre. icon-small.png is the same lyre without the ring.
                    val full = loadResourceImage("icon.png")
                    val small = loadResourceImage("icon-small.png") ?: full
                    if (full != null) {
                        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
                        val scaledImages = sizes.map { size ->
                            val source = if (size <= 48) small else full
                            val scaled = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                            val g2d = scaled.createGraphics()
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                            g2d.drawImage(source, 0, 0, size, size, null)
                            g2d.dispose()
                            scaled as java.awt.Image
                        }
                        window.iconImages = scaledImages
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
                title = "Lyrenne",
                // Without this the popup gets Compose's default Java icon, which shows up in the
                // taskbar as a stray coffee cup whenever the tray panel is opened.
                icon = appIcon
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
