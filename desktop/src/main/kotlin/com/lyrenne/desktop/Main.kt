package com.lyrenne.desktop

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.lyrenne.desktop.ui.components.AutoScroll
import com.lyrenne.desktop.ui.components.TRAY_PANEL_HEIGHT
import com.lyrenne.desktop.ui.components.TRAY_PANEL_WIDTH
import com.lyrenne.desktop.ui.components.TrayPanel
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.media.MediaKeyHandler
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.LibrarySync
import com.lyrenne.desktop.ui.App
import com.lyrenne.desktop.update.AutoUpdater
import com.lyrenne.desktop.ui.theme.LyrenneTheme
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.integration.DiscordRPC
import com.lyrenne.desktop.integration.LastFmManager
import com.lyrenne.desktop.notification.DesktopNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
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
                com.lyrenne.desktop.settings.ProxyType.HTTP -> java.net.Proxy.Type.HTTP
                com.lyrenne.desktop.settings.ProxyType.SOCKS -> java.net.Proxy.Type.SOCKS
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
 * Give Coil a disk cache.
 *
 * Off Android, Coil enables no disk cache unless told to, so every thumbnail (home feed, album
 * art, artist photos) was re-fetched from Google's CDN on every launch. A real install measured
 * `data/cache` at 0 bytes for exactly that reason.
 *
 * It also gives the `cacheSize` preference something to govern. That setting was stored, saved
 * and displayed under Settings → Storage while nothing anywhere read it: a 500 MB limit on an
 * empty folder. Coil takes the same number as its eviction bound, so the two now agree.
 *
 * The memory cache is a fixed 64 MB rather than Coil's default percentage of max heap. The heap
 * is capped in build.gradle.kts, and a fixed bound means changing that cap later cannot silently
 * resize the image cache with it.
 */
private fun configureImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(PreferencesManager.getCacheDirectory().absolutePath.toPath())
                    .maxSizeBytes(PreferencesManager.preferences.value.cacheSize)
                    .build()
            }
            .build()
    }
}

/**
 * Opening size, clamped to what the screen can actually display.
 *
 * Compose dp map to Java user-space units, and Windows display scaling shrinks the usable desktop
 * measured in those units. A 1080p screen offers 1920x1032 dp at 100%, but only 1536x826 at 125%
 * and 1280x688 at 150%. The old fixed 1200x800 therefore opened a window 112 dp taller than the
 * screen at 150%, cutting off the bottom of the app, which is where the player controls are. 125%
 * is Windows' recommended setting on most 1080p laptops, so this was the common case rather than
 * an edge case.
 *
 * `maximumWindowBounds` already excludes the taskbar. The 0.92 keeps a margin so the window still
 * reads as a window instead of filling the screen edge to edge, and there is deliberately no lower
 * bound: on a genuinely small display the screen is the constraint, and forcing a minimum would
 * reintroduce the same off-screen problem it is meant to prevent.
 */
private fun defaultWindowSize(): DpSize {
    val usable = try {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    } catch (e: Exception) {
        Timber.w("Could not read screen bounds, using fixed default: ${e.message}")
        null
    }
    val w = usable?.width?.takeIf { it > 0 }?.let { minOf(1200, (it * 0.92).toInt()) } ?: 1200
    val h = usable?.height?.takeIf { it > 0 }?.let { minOf(800, (it * 0.92).toInt()) } ?: 800
    return DpSize(w.dp, h.dp)
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
    configureImageLoader()
    applyNetworkPreferences()

    // Sweep the login profile: cookie store and caches out, saved passwords in.
    //
    // The cookies in it were copied into credentials.json when it was created and are never
    // read again, but nothing used to delete them, so a second live Google session sat next to
    // the app that even signing out did not remove. A real install measured 87 MB, which was
    // 99% of the entire data folder.
    //
    // Safe at startup specifically because no login can be in flight yet. Nobody is signed out
    // by this: credentials.json is what the app authenticates with, and it is untouched.
    com.lyrenne.desktop.auth.BrowserLoginHelper.pruneLoginProfile()

    // Pick up downloads that did not finish before the last close. Rows still marked
    // 'downloading' were interrupted mid-transfer, not failed, and their partial files are
    // still on disk, so this resumes rather than starting the transfers over.
    com.lyrenne.desktop.download.DownloadManager.restoreQueue()

    application {
        // Centred as well as clamped: a window sized to fit is still useless if the platform
        // places it partly off-screen.
        val windowState = rememberWindowState(
            size = defaultWindowSize(),
            position = WindowPosition(Alignment.Center)
        )
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

            /**
             * Sync again whenever someone signs in.
             *
             * The launch sync above is a single shot, and on a first run it fires before there are
             * any credentials, so it bails with "Not logged in". The user then signs in, and
             * nothing synced their library until the next restart: an empty app that looks broken.
             * Onboarding made that the path every new user takes, but the gap was always there for
             * anyone signing in from Settings.
             *
             * `drop(1)` skips the value StateFlow replays on subscribe, which is whatever
             * `AuthManager.initialize()` just settled on and the launch sync has already handled.
             * Only real transitions get here.
             *
             * Deliberately not gated on `autoSyncOnStartup`: that setting is about launch
             * behaviour, and someone who just signed in has taken an explicit action and expects
             * their library to appear. `syncLibrary()` already no-ops if one is in flight.
             */
            launch {
                AuthManager.authState
                    .map { it.isLoggedIn }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { loggedIn ->
                        if (loggedIn) {
                            Timber.i("Signed in — syncing library")
                            LibrarySync.syncLibrary()
                        }
                    }
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
        // Only the current song matters here, so only that is collected. The full playback state
        // carries a position that updates five times a second during playback, and subscribing to
        // it re-ran this whole scope (the one holding the Window) at the same rate to recompute
        // a title that changes once a track.
        val currentSong by remember(player) {
            player.state.map { it.currentSong }.distinctUntilChanged()
        }.collectAsState(null)

        val windowTitle = remember(currentSong) {
            val song = currentSong
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

            /**
             * Check for a new version shortly after launch, if the user wants that.
             *
             * Deliberately delayed rather than run at startup: the window paints at ~1.6s and
             * VLC, auth and the queue all initialise off the main thread just after, so firing a
             * network call into that window would trade startup time for something nobody is
             * waiting on.
             *
             * The result is announced through the tray rather than a dialog. An update is not
             * urgent, and a modal on launch is the kind of thing people learn to dismiss without
             * reading. Settings shows the same state for anyone who goes looking.
             */
            LaunchedEffect(Unit) {
                if (!PreferencesManager.preferences.value.checkUpdatesOnLaunch) return@LaunchedEffect
                delay(10_000)
                AutoUpdater.checkForUpdates()
                // Wait for a settled state; Idle and Checking are both transient here.
                val settled = AutoUpdater.updateState.first {
                    it is AutoUpdater.UpdateState.UpdateAvailable ||
                        it is AutoUpdater.UpdateState.UpToDate ||
                        it is AutoUpdater.UpdateState.Error
                }
                when (settled) {
                    is AutoUpdater.UpdateState.UpdateAvailable -> DesktopNotification.notify(
                        "Lyrenne ${settled.version} is available",
                        "Open Settings to download and install it."
                    )
                    is AutoUpdater.UpdateState.Error ->
                        Timber.i("Launch update check failed: ${settled.message}")
                    else -> Timber.i("Launch update check: up to date")
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

            /**
             * Floor on how small the window can be dragged.
             *
             * The MiniPlayer's controls are a plain Row of fixed-size buttons, and a Row does not
             * wrap: below roughly 630 dp of width the title area is squeezed to nothing and the
             * transport controls start clipping off the edge. Nothing we now open at comes close,
             * but a user can drag there by hand.
             *
             * Clamped against the opening size so it can never exceed what the screen fits. On a
             * small display the screen is still the constraint, and a minimum larger than the
             * desktop would recreate the off-screen window this all exists to prevent.
             */
            LaunchedEffect(windowState.size) {
                try {
                    window.minimumSize = java.awt.Dimension(
                        minOf(720, windowState.size.width.value.toInt()),
                        minOf(520, windowState.size.height.value.toInt())
                    )
                } catch (e: Exception) {
                    Timber.w("Could not set minimum window size: ${e.message}")
                }
            }

            LyrenneTheme(themeMode = prefs.themeMode) {
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

                LyrenneTheme(themeMode = prefs.themeMode) {
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
