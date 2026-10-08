package com.lyrenne.desktop.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** The cross-platform sign-in paths: Linux Chromium v10, Firefox, the lock check, pasted cookies. */
class LinuxLoginTest {

    @Test
    fun `linux v10 round trips`() {
        val blob = BrowserCookieExtractor.encryptLinuxV10("abc123-SAPISID".toByteArray())
        assertEquals("abc123-SAPISID", BrowserCookieExtractor.decryptLinuxV10(blob))
    }

    @Test
    fun `linux v10 strips the chromium host hash`() {
        val hash = ByteArray(32) { (it * 7 + 1).toByte() } // non-printable bytes, like SHA-256
        val blob = BrowserCookieExtractor.encryptLinuxV10(hash + "value".toByteArray())
        assertEquals("value", BrowserCookieExtractor.decryptLinuxV10(blob))
    }

    @Test
    fun `v11 is not decrypted with the basic key`() {
        val blob = BrowserCookieExtractor.encryptLinuxV10("x".toByteArray())
        blob[2] = '1'.code.toByte()
        assertNull(BrowserCookieExtractor.decryptLinuxV10(blob))
    }

    @Test
    fun `firefox cookies are read with youtube winning over google`() {
        val dir = Files.createTempDirectory("ff").toFile()
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "cookies.sqlite").absolutePath}").use { c ->
                c.createStatement().execute("CREATE TABLE moz_cookies(name TEXT, value TEXT, host TEXT)")
                c.createStatement().execute(
                    "INSERT INTO moz_cookies VALUES ('SAPISID','google','.google.com'),('SAPISID','yt','.youtube.com'),('SID','s','.google.com'),('X','x','.example.com')"
                )
            }
            val result = BrowserCookieExtractor.extractFirefoxCookies(dir, "Firefox")
            assertTrue(result is CookieExtractResult.Success)
            val cookie = (result as CookieExtractResult.Success).cookie
            assertTrue(cookie, cookie.startsWith("SAPISID=yt"))
            assertTrue(cookie.contains("SID=s"))
            assertFalse(cookie.contains("X=x"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pasted header is accepted only with an auth cookie`() {
        val ok = BrowserCookieExtractor.cookiesFromHeader("cookie: PREF=a; SAPISID=b ; HSID=c")
        assertTrue(ok is CookieExtractResult.Success)
        assertTrue((ok as CookieExtractResult.Success).cookie.startsWith("SAPISID=b"))
        assertTrue(BrowserCookieExtractor.cookiesFromHeader("PREF=a") is CookieExtractResult.Error)
        assertTrue(BrowserCookieExtractor.cookiesFromHeader("garbage") is CookieExtractResult.Error)
    }

    @Test
    fun `a SingletonLock symlink is in use only while its pid lives`() {
        val dir = Files.createTempDirectory("lock").toFile()
        try {
            val browser = LoginBrowser(BrowserKind.CHROMIUM, File("x"), "Chromium", dir)
            assertFalse(BrowserLoginHelper.isProfileInUse(browser))
            val lock = dir.toPath().resolve("SingletonLock")
            // Dangling target, like the real one; this process's pid stands in for a live browser.
            val linked = runCatching {
                Files.createSymbolicLink(lock, File("host-${ProcessHandle.current().pid()}").toPath())
            }.isSuccess
            assumeTrue("symlinks not permitted here", linked)
            assertTrue(BrowserLoginHelper.isProfileInUse(browser))

            // A browser that crashed leaves the link behind with a dead pid: not in use.
            Files.delete(lock)
            Files.createSymbolicLink(lock, File("host-999999999").toPath())
            assertFalse(BrowserLoginHelper.isProfileInUse(browser))
        } finally {
            dir.deleteRecursively()
        }
    }
}
