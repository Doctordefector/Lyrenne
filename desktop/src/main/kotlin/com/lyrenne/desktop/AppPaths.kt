package com.lyrenne.desktop

import timber.log.Timber
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** The one place the OS is decided. Every other file asks here instead of reading `os.name`. */
object Platform {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows = osName.startsWith("windows")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isLinux = osName.contains("linux")
}

/**
 * Centralized path resolution for all app data.
 *
 * Windows: everything lives next to the executable in a `data/` subfolder, making the app fully
 * portable. Linux (and macOS): packages install to a read-only location, so data goes to
 * `$XDG_DATA_HOME/lyrenne` (`~/.local/share/lyrenne`) unless a `portable` marker file sits next
 * to the app, which restores the Windows layout for tarball users.
 *
 * There is deliberately no migration from the pre-2.9.4 layout. The old database filename and
 * the old %APPDATA% directory both carried the previous project name, and carrying them forward
 * would mean keeping that name in the code indefinitely. A user upgrading from 2.9.3 or earlier
 * therefore starts with an empty library unless they move their files across by hand; the
 * release notes explain how. This was a deliberate trade of upgrade smoothness for a clean break.
 */
object AppPaths {
    /** The directory the executable lives in — used to find bundled tools like ffmpeg */
    val appDir: File by lazy { getAppDirectory() }

    /** True when data lives next to the app (Windows, or a `portable` marker on other OSes). */
    val isPortable: Boolean by lazy { Platform.isWindows || File(appDir, "portable").exists() }

    /**
     * Set when the app folder could not be written and data fell back to the working directory.
     * Main shows a one-time explanation, because the fallback depends on how the app was launched.
     */
    @Volatile var appDirNotWritable = false
        private set

    /** The root data directory: `<app-dir>/data/`, or the XDG data dir off Windows. */
    val dataDir: File by lazy {
        val dir = if (isPortable) File(appDir, "data") else File(xdgDataHome(), "lyrenne")
        dir.mkdirs()
        dir
    }

    /** Default downloads folder: next to the app when portable, `~/Music/Lyrenne` otherwise. */
    val defaultDownloadsDir: File get() =
        if (isPortable) File(appDir, "Downloads") else File(xdgMusicDir(), "Lyrenne")

    /** preferences.properties */
    val preferencesFile: File get() = File(dataDir, "preferences.properties")

    /** lyrenne.db (SQLDelight) */
    val databaseFile: File get() = File(dataDir, "lyrenne.db")

    /** credentials.json (auth cookies) */
    val credentialsFile: File get() = File(dataDir, "credentials.json")

    /** Cache directory */
    val cacheDir: File get() {
        val dir = File(dataDir, "cache")
        dir.mkdirs()
        return dir
    }

    /**
     * Writes [text] to a sibling temp file and renames it over [file], so a crash mid-write never
     * leaves a truncated file behind. On POSIX systems the file is created owner-only (rw-------):
     * a permission, not encryption — the content is unchanged plaintext.
     */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!Platform.isWindows) {
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun xdgDataHome(): File =
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")

    /** `xdg-user-dir MUSIC` when available (it is localised, e.g. ~/Musik), else ~/Music. */
    private fun xdgMusicDir(): File {
        val home = System.getProperty("user.home")
        if (Platform.isLinux) {
            runCatching {
                val p = ProcessBuilder("xdg-user-dir", "MUSIC").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                // xdg-user-dir prints $HOME when the dir is unset; that is not a music folder.
                if (out.isNotEmpty() && out != home) return File(out)
            }
        }
        return File(home, "Music")
    }

    /**
     * Returns the application's root directory (where the exe lives).
     * For Compose Desktop distributable: Lyrenne/app/Lyrenne.jar → walks up to Lyrenne/
     */
    private fun getAppDirectory(): File {
        try {
            val codeSource = AppPaths::class.java.protectionDomain?.codeSource
            if (codeSource != null) {
                val jarFile = File(codeSource.location.toURI().path)
                val appDir = if (jarFile.isFile) {
                    // Running from jar — go up from app/ to the root app folder
                    jarFile.parentFile?.parentFile ?: jarFile.parentFile ?: File(".")
                } else {
                    jarFile
                }
                // Off Windows the app dir is only written when portable, so it need not be writable.
                if (appDir.exists() && (!Platform.isWindows || canReallyWrite(appDir))) return appDir
                if (appDir.exists()) appDirNotWritable = true
            }
        } catch (e: Exception) {
            Timber.w("Could not resolve app directory from code source: ${e.message}")
        }
        // Fallback: current working directory
        return File(System.getProperty("user.dir", "."))
    }

    /**
     * `File.canWrite()` on a Windows directory only reflects the read-only attribute, not ACLs, so
     * it says yes for `C:\Program Files\...`. Only an actual write proves it.
     */
    private fun canReallyWrite(dir: File): Boolean = try {
        Files.deleteIfExists(Files.createTempFile(dir.toPath(), ".probe", null))
        true
    } catch (_: Exception) {
        false
    }
}
