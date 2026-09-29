package com.lyrenne.desktop.backup

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.auth.AuthManager
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

    private val backupFiles = listOf(
        "preferences.properties", "lyrenne.db", "credentials.json", "lyrics-overrides.json"
    )

    private const val ACCOUNTS_FOLDER = "accounts"

    /** A saved account's file name: the 16 hex character id AuthManager derives. */
    private val ACCOUNT_FILE = Regex("""^[0-9a-f]{16}\.json$""")

    private val accountsDir: File get() = File(AppPaths.dataDir, ACCOUNTS_FOLDER)

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
            // Saved accounts for the account switcher. The same plaintext cookies as
            // credentials.json, so a backup that carries one carries these.
            accountsDir.listFiles { f -> f.isFile && ACCOUNT_FILE.matches(f.name) }?.forEach { file ->
                zip.putNextEntry(ZipEntry("$ACCOUNTS_FOLDER/${file.name}"))
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
                val path = entry.name.replace('\\', '/')
                val name = path.substringAfterLast('/')
                if (path == "$ACCOUNTS_FOLDER/$name" && ACCOUNT_FILE.matches(name)) {
                    // Matched against the exact id shape, so an entry name can never climb out
                    // of the accounts folder. An account already saved here is newer than the
                    // backup's copy of it, so that one is left alone.
                    accountsDir.mkdirs()
                    val out = File(accountsDir, name)
                    if (!out.exists()) {
                        FileOutputStream(out).use { zip.copyTo(it) }
                        count++
                        Timber.i("Restored saved account $name")
                    }
                } else if (name in backupFiles) {
                    // The account signed in right now is kept as a saved account rather than
                    // overwritten, so restoring cannot lose it.
                    if (name == "credentials.json") AuthManager.stashActiveBeforeRestore()
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
