package com.lyrenne.desktop

import com.lyrenne.desktop.settings.PreferencesManager
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

// Startup pieces shared by the windowed app (Main.kt) and headless mode. Kept out of Main.kt on
// purpose: loading MainKt needs Compose on the classpath, and the Steam Deck build has none.

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
 * Where the log lives: next to the exe, or the temp dir if that folder is not writable.
 * Resolved before anything else runs, so it must not depend on app state.
 */
private fun logFile(): File {
    // Next to the app when portable; installed (Linux) copies live in a read-only /opt.
    val next = if (AppPaths.isPortable) File(AppPaths.appDir, "lyrenne.log") else File(AppPaths.dataDir, "lyrenne.log")
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
internal fun installCrashReporting(): File {
    val log = logFile()
    try {
        System.setErr(PrintStream(FileOutputStream(log, false), true))
    } catch (e: Exception) {
        // Read-only folder or the file is locked by another instance — keep the default stderr.
    }
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        // On the UI thread this is usually Compose throwing mid-measure, which leaves the scene
        // broken: the window stays up showing a stale frame and never redraws. Close instead.
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            reportFatal(e, log, "Lyrenne hit an error and has to close.")
            exitProcess(1)
        }
        reportFatal(e, log)
    }
    return log
}

internal fun reportFatal(e: Throwable, log: File, headline: String = "Lyrenne could not start.") {
    try {
        System.err.println("FATAL: ${e.stackTraceToString()}")
    } catch (ignored: Exception) {
    }
    try {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "$headline\n\n${e::class.simpleName}: ${e.message}\n\nDetails: ${log.absolutePath}",
            "Lyrenne",
            javax.swing.JOptionPane.ERROR_MESSAGE
        )
    } catch (ignored: Throwable) {
        // Headless or AWT itself is broken — the log file is the fallback.
    }
}
