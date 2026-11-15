package com.lyrenne.desktop.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** The macOS sign-in pieces that run anywhere: the mock-keychain key and the session check. */
class MacLoginTest {

    @Test
    fun `mock keychain key is pbkdf2 of mock_password with 1003 rounds`() {
        // Computed independently: hashlib.pbkdf2_hmac('sha1', b'mock_password', b'saltysalt', 1003, 16)
        val hex = BrowserCookieExtractor.macMockKey.joinToString("") { "%02x".format(it) }
        assertEquals("af0f762aaf6d7d11581b7aa8ce7218de", hex)
    }

    @Test
    fun `mac v10 round trips and strips the host hash`() {
        val key = BrowserCookieExtractor.macMockKey
        val plain = BrowserCookieExtractor.encryptCbcV10("SAPISID-value".toByteArray(), key)
        assertEquals("SAPISID-value", BrowserCookieExtractor.decryptCbcV10(plain, key))
        val hash = ByteArray(32) { (it * 7 + 1).toByte() }
        val hashed = BrowserCookieExtractor.encryptCbcV10(hash + "v".toByteArray(), key)
        assertEquals("v", BrowserCookieExtractor.decryptCbcV10(hashed, key))
    }

    @Test
    fun `a cookie from the real keychain does not open with the mock key`() {
        val other = BrowserCookieExtractor.encryptLinuxV10("secret-value-long-enough".toByteArray())
        val opened = BrowserCookieExtractor.decryptCbcV10(other, BrowserCookieExtractor.macMockKey)
        // CBC with the wrong key almost always fails the padding check; if it does not, it is garbage.
        assertTrue(opened == null || opened != "secret-value-long-enough")
    }

    @Test
    fun `keychain service names follow each browser`() {
        assertEquals("Chrome Safe Storage", BrowserCookieExtractor.macSafeStorageService("Chrome"))
        assertEquals("Microsoft Edge Safe Storage", BrowserCookieExtractor.macSafeStorageService("Edge"))
        assertEquals("Brave Safe Storage", BrowserCookieExtractor.macSafeStorageService("Brave"))
        assertEquals("Chromium Safe Storage", BrowserCookieExtractor.macSafeStorageService("Chromium"))
    }

    @Test
    fun `session check wants the youtube session cookie, not google's`() {
        val dir = Files.createTempDirectory("session").toFile()
        try {
            Class.forName("org.sqlite.JDBC")
            val chromium = File(dir, "Cookies")
            DriverManager.getConnection("jdbc:sqlite:${chromium.absolutePath}").use { c ->
                c.createStatement().execute("CREATE TABLE cookies(name TEXT, encrypted_value BLOB, value TEXT, host_key TEXT)")
                c.createStatement().execute("INSERT INTO cookies VALUES ('SAPISID', x'00', '', '.google.com'), ('PREF', x'00', '', '.youtube.com')")
            }
            assertFalse(BrowserCookieExtractor.hasYouTubeSession(chromium, BrowserKind.CHROMIUM))
            DriverManager.getConnection("jdbc:sqlite:${chromium.absolutePath}").use { c ->
                c.createStatement().execute("INSERT INTO cookies VALUES ('__Secure-3PAPISID', x'00', '', '.youtube.com')")
            }
            assertTrue(BrowserCookieExtractor.hasYouTubeSession(chromium, BrowserKind.CHROMIUM))

            val firefox = File(dir, "cookies.sqlite")
            DriverManager.getConnection("jdbc:sqlite:${firefox.absolutePath}").use { c ->
                c.createStatement().execute("CREATE TABLE moz_cookies(name TEXT, value TEXT, host TEXT)")
                c.createStatement().execute("INSERT INTO moz_cookies VALUES ('SAPISID', 'a', '.youtube.com')")
            }
            assertTrue(BrowserCookieExtractor.hasYouTubeSession(firefox, BrowserKind.FIREFOX))
            assertFalse(BrowserCookieExtractor.hasYouTubeSession(File(dir, "missing.sqlite"), BrowserKind.FIREFOX))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wrong prefix is not decrypted`() {
        val blob = BrowserCookieExtractor.encryptCbcV10("x".toByteArray(), BrowserCookieExtractor.macMockKey)
        blob[2] = '1'.code.toByte()
        assertNull(BrowserCookieExtractor.decryptCbcV10(blob, BrowserCookieExtractor.macMockKey))
    }
}
