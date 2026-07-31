package com.lyrenne.desktop

import timber.log.Timber
import java.io.File

/**
 * Centralized path resolution for all app data.
 * Everything lives next to the executable in a `data/` subfolder,
 * making the app fully portable.
 *
 * There is deliberately no migration from the pre-2.9.4 layout. The old database filename and
 * the old %APPDATA% directory both carried the previous project name, and carrying them forward
 * would mean keeping that name in the code indefinitely. A user upgrading from 2.9.3 or earlier
 * therefore starts with an empty library unless they move their files across by hand; the
 * release notes explain how. This was a deliberate trade of upgrade smoothness for a clean break.
 */
object AppPaths {
    /** The root data directory: `<app-dir>/data/` */
    val dataDir: File by lazy {
        val dir = File(getAppDirectory(), "data")
        dir.mkdirs()
        dir
    }

    /** The directory the executable lives in — used to find bundled tools like ffmpeg */
    val appDir: File by lazy { getAppDirectory() }

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
                if (appDir.exists() && appDir.canWrite()) return appDir
            }
        } catch (e: Exception) {
            Timber.w("Could not resolve app directory from code source: ${e.message}")
        }
        // Fallback: current working directory
        return File(System.getProperty("user.dir", "."))
    }

}
