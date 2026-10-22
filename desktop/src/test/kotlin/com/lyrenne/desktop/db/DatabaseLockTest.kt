package com.lyrenne.desktop.db

import com.lyrenne.desktop.AppPaths
import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library sync's commit used to lock out readers under the rollback journal, and a Library
 * screen read that outwaited the busy timeout crashed the app with SQLITE_BUSY. WAL is the fix,
 * so the database must actually be in WAL mode, and backups must still carry recent writes.
 */
class DatabaseLockTest {
    @Test
    fun databaseRunsInWalAndSnapshotsIt() {
        DatabaseHelper.initialize()
        DatabaseHelper.addSearchHistory("lock-test")

        // WAL is recorded in the file itself, so a plain connection sees it.
        DriverManager.getConnection("jdbc:sqlite:${AppPaths.databaseFile.absolutePath}").use { c ->
            c.createStatement().executeQuery("PRAGMA journal_mode").use { r ->
                r.next()
                assertEquals("wal", r.getString(1))
            }
        }

        val snapshot = File.createTempFile("lyrenne-snapshot", ".db").apply { deleteOnExit() }
        DatabaseHelper.snapshotTo(snapshot)
        assertTrue(snapshot.length() > 0)
    }
}
