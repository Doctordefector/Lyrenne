package com.lyrenne.desktop.auth

import com.lyrenne.desktop.Platform
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class CookieExtractResult {
    data class Success(val cookie: String, val browserName: String) : CookieExtractResult()
    data class Error(val message: String) : CookieExtractResult()
}

/**
 * Reads YouTube cookies out of a browser's cookie database: Chromium-family or Firefox.
 *
 * Only ever pointed at the dedicated login profile that [BrowserLoginHelper] creates —
 * importing from an installed browser was removed, because Chrome/Edge 127+ encrypt every
 * cookie with app-bound (v20) keys that are wrapped in SYSTEM-scoped DPAPI and cannot be
 * read from user space. A fresh profile still writes v10 cookies, which decrypt fine.
 *
 * Chromium's scheme differs per OS. Windows: AES-256-GCM under a DPAPI-wrapped key in
 * `Local State`. Linux: AES-128-CBC under a key derived from a fixed password, *when the browser
 * is launched with `--password-store=basic`* (`v10`); otherwise the key lives in the desktop
 * keyring (`v11`), which this deliberately does not touch. Firefox stores cookies unencrypted.
 */
object BrowserCookieExtractor {

    /** What a Chromium cookie DB yielded, before deciding whether it is a usable sign-in. */
    internal class ChromiumRead(
        val cookies: Map<String, String>,
        val appBoundBlocked: Boolean,
        val keyringBlocked: Boolean
    )

    fun extractChromiumCookies(
        cookieDbPath: File,
        localStatePath: File,
        browserName: String
    ): CookieExtractResult {
        val read = when (val r = readChromiumCookies(cookieDbPath, localStatePath, browserName)) {
            is ChromiumRead -> r
            else -> return r as CookieExtractResult
        }
        val cookieMap = read.cookies
        val hasAuth = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
        if (!hasAuth && read.keyringBlocked) {
            return CookieExtractResult.Error(
                "$browserName saved its cookies in the desktop keyring (it ignored " +
                    "--password-store=basic, often because a launcher script dropped the flag). " +
                    "Sign in with Firefox instead, or paste your cookie under Advanced."
            )
        }
        if (!hasAuth && read.appBoundBlocked) {
            return CookieExtractResult.Error(
                "$browserName wrote app-bound encrypted cookies (v20), which can't be read from " +
                "user space. Try signing in with a different browser (Edge works)."
            )
        }
        return buildCookieResult(cookieMap, browserName)
    }

    /** Decrypted YouTube/Google cookies, or a [CookieExtractResult.Error]. Split out for the e2e test. */
    internal fun readChromiumCookies(cookieDbPath: File, localStatePath: File, browserName: String): Any {
        Timber.i("Extracting cookies from $browserName: db=$cookieDbPath")
        // Linux has no key in Local State: v10 values use the fixed basic-store key instead.
        val masterKey = if (Platform.isWindows) {
            decryptMasterKey(localStatePath)
                ?: return CookieExtractResult.Error("Failed to decrypt $browserName's encryption key.")
        } else null

        val tempDb = copyDatabase(cookieDbPath)
            ?: return CookieExtractResult.Error("Can't access $browserName's cookies. Try closing $browserName and retry.")

        val cookieMap = mutableMapOf<String, String>()
        val cookieDomain = mutableMapOf<String, String>()
        // Set when a v20 (app-bound) cookie can't be decrypted with the Local State key.
        // v20 keys are SYSTEM-DPAPI-scoped, so user-space decryption is impossible by design.
        var appBoundBlocked = false
        // Linux: a v11 cookie means the keyring was used, i.e. --password-store=basic was dropped.
        var keyringBlocked = false
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                val stmt = conn.prepareStatement(
                    """SELECT name, encrypted_value, value, host_key FROM cookies
                       WHERE host_key LIKE '%youtube.com' OR host_key LIKE '%.google.com'
                       ORDER BY CASE WHEN host_key LIKE '%youtube.com' THEN 1 ELSE 2 END"""
                )
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val name = rs.getString("name")
                    val host = rs.getString("host_key")
                    val encryptedValue = rs.getBytes("encrypted_value")
                    val plainValue = rs.getString("value")

                    if (name in cookieMap && cookieDomain[name]?.contains("youtube") == true) continue

                    val value = when {
                        encryptedValue != null && encryptedValue.size > 3 ->
                            if (masterKey != null) decryptCookieValue(encryptedValue, masterKey)
                            else decryptLinuxV10(encryptedValue)
                        !plainValue.isNullOrBlank() -> plainValue
                        else -> null
                    }

                    val prefix = if (encryptedValue != null && encryptedValue.size >= 3) String(encryptedValue, 0, 3) else null
                    if (value == null && prefix == "v20") appBoundBlocked = true
                    if (value == null && masterKey == null && prefix == "v11") keyringBlocked = true

                    if (!value.isNullOrBlank()) {
                        val safe = value.filter { it.code >= 0x20 && it.code != 0x7F }
                        if (safe.isNotEmpty()) {
                            cookieMap[name] = safe
                            cookieDomain[name] = host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return CookieExtractResult.Error("Failed to read cookie database: ${e.message}")
        } finally {
            deleteCopy(tempDb)
        }

        Timber.i("$browserName: found ${cookieMap.size} cookies (keys: ${cookieMap.keys.take(10)})")
        return ChromiumRead(cookieMap, appBoundBlocked, keyringBlocked)
    }

    /**
     * Firefox keeps cookies unencrypted in `cookies.sqlite` on every OS: no key, no keyring, no
     * DPAPI, no app-bound encryption. That is why it is the first choice on Linux.
     */
    fun extractFirefoxCookies(profileDir: File, browserName: String): CookieExtractResult =
        when (val r = readFirefoxCookies(profileDir, browserName)) {
            is CookieExtractResult -> r
            else -> @Suppress("UNCHECKED_CAST") buildCookieResult(r as Map<String, String>, browserName)
        }

    /** The YouTube/Google cookies, or a [CookieExtractResult.Error]. Split out for the e2e test. */
    internal fun readFirefoxCookies(profileDir: File, browserName: String): Any {
        val cookieDb = File(profileDir, "cookies.sqlite")
        if (!cookieDb.exists()) {
            return CookieExtractResult.Error(
                "No sign-in cookies found. This usually means the browser was closed before " +
                    "signing in. Sign in to music.youtube.com, then close $browserName."
            )
        }
        val tempDb = copyDatabase(cookieDb)
            ?: return CookieExtractResult.Error("Can't access $browserName's cookies. Try closing $browserName and retry.")
        val cookieMap = linkedMapOf<String, String>()
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                // .youtube.com first so it wins over the same name on .google.com.
                val rs = conn.prepareStatement(
                    """SELECT name, value, host FROM moz_cookies
                       WHERE host LIKE '%youtube.com' OR host LIKE '%.google.com'
                       ORDER BY CASE WHEN host LIKE '%youtube.com' THEN 1 ELSE 2 END"""
                ).executeQuery()
                while (rs.next()) {
                    val name = rs.getString("name")
                    val value = rs.getString("value")?.filter { it.code >= 0x20 && it.code != 0x7F }
                    if (name !in cookieMap && !value.isNullOrEmpty()) cookieMap[name] = value
                }
            }
        } catch (e: Exception) {
            return CookieExtractResult.Error("Failed to read cookie database: ${e.message}")
        } finally {
            deleteCopy(tempDb)
        }
        Timber.i("$browserName: found ${cookieMap.size} cookies (keys: ${cookieMap.keys.take(10)})")
        return cookieMap
    }

    /**
     * The paste fallback: a `cookie` request header copied out of the browser's DevTools.
     * Works on every platform, including Flatpak-only systems where no browser can be launched
     * against a profile of ours.
     */
    fun cookiesFromHeader(header: String): CookieExtractResult {
        val cleaned = header.trim().removePrefix("cookie:").removePrefix("Cookie:").trim()
        val map = linkedMapOf<String, String>()
        cleaned.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim().filter { it.code >= 0x20 && it.code != 0x7F }
                if (name.isNotEmpty() && value.isNotEmpty() && name !in map) map[name] = value
            }
        }
        if (map.isEmpty()) return CookieExtractResult.Error("That doesn't look like a cookie header.")
        return buildCookieResult(map, "Pasted cookie")
    }

    /**
     * Copies a SQLite DB (and its WAL/SHM, which hold the latest writes) to a temp file, so the
     * browser's lock on the original never matters. Null when it cannot be read at all.
     */
    private fun copyDatabase(db: File): File? {
        val tempDb = Files.createTempFile("ml_cookies_", ".db").toFile()
        return try {
            db.copyTo(tempDb, overwrite = true)
            val walFile = File(db.absolutePath + "-wal")
            val shmFile = File(db.absolutePath + "-shm")
            if (walFile.exists()) walFile.copyTo(File(tempDb.absolutePath + "-wal"), overwrite = true)
            if (shmFile.exists()) shmFile.copyTo(File(tempDb.absolutePath + "-shm"), overwrite = true)
            tempDb
        } catch (_: Exception) {
            // Linux has no mandatory locks, so only Windows ever needs the robocopy route.
            try {
                if (!Platform.isWindows) error("copy failed")
                copyLockedFile(db, tempDb)
                tempDb
            } catch (_: Exception) {
                deleteCopy(tempDb)
                null
            }
        }
    }

    private fun deleteCopy(tempDb: File) {
        tempDb.delete()
        File(tempDb.absolutePath + "-wal").delete()
        File(tempDb.absolutePath + "-shm").delete()
    }

    private fun copyLockedFile(source: File, dest: File) {
        val sourceDir = source.parentFile.absolutePath
        val destDir = dest.parentFile.absolutePath
        val dbName = source.name
        val result = ProcessBuilder(
            "robocopy", sourceDir, destDir, dbName, "/NJH", "/NJS", "/NP"
        ).redirectErrorStream(true).start()
        result.waitFor()
        val copiedFile = File(dest.parentFile, dbName)
        if (copiedFile.exists() && copiedFile != dest) {
            copiedFile.copyTo(dest, overwrite = true)
            copiedFile.delete()
        }
        if (!dest.exists() || dest.length() == 0L) {
            throw Exception("robocopy failed")
        }
    }

    private fun buildCookieResult(cookieMap: Map<String, String>, browserName: String): CookieExtractResult {
        if (cookieMap.isEmpty()) {
            return CookieExtractResult.Error("No YouTube cookies found. Make sure you signed in to music.youtube.com.")
        }

        val hasAuth = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
        if (!hasAuth) {
            return CookieExtractResult.Error("You're not signed in to YouTube Music. Sign in first, then close the browser.")
        }

        val priority = listOf(
            "SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID",
            "SID", "__Secure-1PSID", "__Secure-3PSID",
            "HSID", "SSID", "APISID",
            "SIDCC", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
            "__Secure-1PSIDTS", "__Secure-3PSIDTS",
            "LOGIN_INFO", "PREF", "SOCS"
        )
        val parts = mutableListOf<String>()
        for (key in priority) {
            cookieMap[key]?.let { parts.add("$key=$it") }
        }
        for ((key, value) in cookieMap) {
            if (key !in priority) {
                parts.add("$key=$value")
            }
        }

        return CookieExtractResult.Success(
            cookie = parts.joinToString("; "),
            browserName = browserName
        )
    }

    private fun decryptMasterKey(localStateFile: File): ByteArray? {
        return try {
            val json = localStateFile.readText()
            val match = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex().find(json) ?: return null
            val raw = Base64.getDecoder().decode(match.groupValues[1])

            // Strip "DPAPI" prefix (5 bytes)
            if (raw.size < 6 || String(raw, 0, 5) != "DPAPI") return null
            decryptWithDPAPI(raw.copyOfRange(5, raw.size))
        } catch (e: Exception) {
            Timber.e("Master key decryption failed: ${e.message}")
            null
        }
    }

    /**
     * In-process DPAPI through jna-platform. This used to spawn `powershell.exe` per call: slow
     * to start, once per legacy cookie, and blocked outright where execution policy or AppLocker
     * forbids it.
     */
    private fun decryptWithDPAPI(encrypted: ByteArray): ByteArray? {
        return try {
            com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(encrypted)
        } catch (e: Throwable) {
            Timber.e("DPAPI call failed: ${e.message}")
            null
        }
    }

    private fun decryptCookieValue(encrypted: ByteArray, masterKey: ByteArray): String? {
        return try {
            if (encrypted.size < 16) return null
            val prefix = String(encrypted, 0, 3)

            if (prefix == "v10" || prefix == "v11" || prefix == "v20") {
                // Chromium AES-256-GCM: 3-byte prefix + 12-byte nonce + ciphertext + 16-byte tag
                val nonce = encrypted.copyOfRange(3, 15)
                val ciphertext = encrypted.copyOfRange(15, encrypted.size)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(masterKey, "AES"),
                    GCMParameterSpec(128, nonce)
                )
                val decrypted = cipher.doFinal(ciphertext)
                // Modern Chromium (128+) prepends a 32-byte binding hash.
                // Detect it: if first 32 bytes contain non-printable chars but the
                // rest is valid printable text, strip the hash. Otherwise use as-is.
                stripBindingHash(decrypted)
            } else {
                // Legacy DPAPI-encrypted value
                decryptWithDPAPI(encrypted)?.let { String(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Linux Chromium with `--password-store=basic`: AES-128-CBC, IV of 16 spaces, key from
     * PBKDF2-SHA1("peanuts", "saltysalt", 1 iteration). All fixed in Chromium's source.
     */
    private val linuxV10Key: ByteArray by lazy {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec("peanuts".toCharArray(), "saltysalt".toByteArray(), 1, 128))
            .encoded
    }

    private fun linuxV10Cipher(mode: Int): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(linuxV10Key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
    }

    internal fun decryptLinuxV10(encrypted: ByteArray): String? {
        if (encrypted.size <= 3 || String(encrypted, 0, 3) != "v10") return null
        return try {
            stripBindingHash(linuxV10Cipher(Cipher.DECRYPT_MODE).doFinal(encrypted, 3, encrypted.size - 3))
        } catch (_: Exception) {
            null
        }
    }

    /** What Linux Chromium writes for [plain]; the tests build fixtures with it. */
    internal fun encryptLinuxV10(plain: ByteArray): ByteArray =
        "v10".toByteArray() + linuxV10Cipher(Cipher.ENCRYPT_MODE).doFinal(plain)

    private fun stripBindingHash(decrypted: ByteArray): String {
        val HASH_LEN = 32
        if (decrypted.size <= HASH_LEN) {
            return String(decrypted, Charsets.UTF_8)
        }

        // A SHA-256 binding hash (32 random bytes) will always contain non-printable
        // bytes. If the first 32 bytes have any byte < 0x20 or > 0x7E, it's a hash.
        val hasBindingHash = (0 until HASH_LEN).any { i ->
            val b = decrypted[i].toInt() and 0xFF
            b < 0x20 || b > 0x7E
        }

        return if (hasBindingHash) {
            String(decrypted, HASH_LEN, decrypted.size - HASH_LEN, Charsets.UTF_8)
        } else {
            String(decrypted, Charsets.UTF_8)
        }
    }
}
