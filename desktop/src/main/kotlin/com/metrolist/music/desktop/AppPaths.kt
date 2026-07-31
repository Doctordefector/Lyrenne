package com.metrolist.music.desktop

import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Centralized path resolution for all app data.
 * Everything lives next to the executable in a `data/` subfolder,
 * making the app fully portable.
 */
object AppPaths {
    /** The root data directory: `<app-dir>/data/` */
    val dataDir: File by lazy {
        val dir = File(getAppDirectory(), "data")
        dir.mkdirs()
        // Migrate from old %APPDATA% location on first run
        migrateFromAppData(dir)
        dir
    }

    /** The directory the executable lives in — used to find bundled tools like ffmpeg */
    val appDir: File by lazy { getAppDirectory() }

    /** preferences.properties */
    val preferencesFile: File get() = File(dataDir, "preferences.properties")

    /**
     * metrolist.db (SQLDelight).
     *
     * Keeps its pre-rename filename deliberately. Every existing install has its library in a
     * file with this name, and renaming it would silently orphan all of them. Cosmetics are not
     * worth someone's play counts.
     */
    val databaseFile: File get() = File(dataDir, "metrolist.db")

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
     * For Compose Desktop distributable: Metrolist/app/Metrolist.jar → walks up to Metrolist/
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

    /**
     * One-time migration: copies files from old %APPDATA%/Metrolist location
     * to the new portable data/ directory. Only runs if data/ is empty and
     * old files exist.
     */
    private fun migrateFromAppData(newDataDir: File) {
        // Only migrate if the new data dir has no existing files
        if (newDataDir.listFiles()?.isNotEmpty() == true) return

        val os = System.getProperty("os.name").lowercase()
        val oldDir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: return
                File(appData, "Metrolist")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist")
            }
            else -> {
                File(System.getProperty("user.home"), ".config/metrolist")
            }
        }

        if (!oldDir.exists()) return

        val filesToMigrate = mapOf(
            "preferences.properties" to "preferences.properties",
            "metrolist.db" to "metrolist.db",
            "credentials.json" to "credentials.json"
        )

        var migrated = 0
        for ((oldName, newName) in filesToMigrate) {
            val oldFile = File(oldDir, oldName)
            if (oldFile.exists()) {
                try {
                    Files.copy(
                        oldFile.toPath(),
                        File(newDataDir, newName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    migrated++
                    Timber.i("Migrated $oldName from ${oldDir.absolutePath}")
                } catch (e: Exception) {
                    Timber.w("Failed to migrate $oldName: ${e.message}")
                }
            }
        }

        if (migrated > 0) {
            Timber.i("Migrated $migrated files from old location to ${newDataDir.absolutePath}")
        }
    }
}
