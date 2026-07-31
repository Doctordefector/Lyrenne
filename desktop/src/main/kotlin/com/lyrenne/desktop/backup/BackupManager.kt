package com.lyrenne.desktop.backup

import com.lyrenne.desktop.AppPaths
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup & restore of all app data (preferences, database, credentials)
 * as a single ZIP file. Restore requires an app restart to take effect.
 */
object BackupManager {

    private val backupFiles = listOf("preferences.properties", "lyrenne.db", "credentials.json")

    fun defaultBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        return "Lyrenne-backup-$stamp.zip"
    }

    /** Write a backup ZIP to [target]. Returns the number of files included. */
    fun exportBackup(target: File): Result<Int> = runCatching {
        var count = 0
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for (name in backupFiles) {
                val file = File(AppPaths.dataDir, name)
                if (!file.exists()) continue
                // Forward slashes only — backslash entries break Java extraction
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        if (count == 0) {
            target.delete()
            error("No data files found to back up")
        }
        Timber.i("Backup written to ${target.absolutePath} ($count files)")
        count
    }

    /**
     * Restore from a backup ZIP. Files are written next to the live ones with
     * a `.restore` suffix, then swapped in — the database may be locked by the
     * running app, so the swap of the DB happens via a pending-restore marker
     * applied on next startup when the DB is not yet open.
     *
     * Simpler approach used here: write directly; the SQLite JDBC driver opens
     * connections per-query so overwriting works on Windows in practice once
     * no statement is active. Caller should restart the app afterwards.
     */
    fun importBackup(source: File): Result<Int> = runCatching {
        require(source.exists()) { "Backup file not found" }
        var count = 0
        ZipInputStream(FileInputStream(source)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                if (name in backupFiles) {
                    val outFile = File(AppPaths.dataDir, name)
                    FileOutputStream(outFile).use { zip.copyTo(it) }
                    count++
                    Timber.i("Restored $name")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (count == 0) error("No Lyrenne data found in this ZIP")
        count
    }
}
