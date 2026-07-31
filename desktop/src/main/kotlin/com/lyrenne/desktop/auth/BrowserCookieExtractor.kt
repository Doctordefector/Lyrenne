package com.lyrenne.desktop.auth

import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class CookieExtractResult {
    data class Success(val cookie: String, val browserName: String) : CookieExtractResult()
    data class Error(val message: String) : CookieExtractResult()
}

/**
 * Reads YouTube cookies out of a Chromium cookie database.
 *
 * Only ever pointed at the dedicated login profile that [BrowserLoginHelper] creates —
 * importing from an installed browser was removed, because Chrome/Edge 127+ encrypt every
 * cookie with app-bound (v20) keys that are wrapped in SYSTEM-scoped DPAPI and cannot be
 * read from user space. A fresh profile still writes v10 cookies, which decrypt fine.
 */
object BrowserCookieExtractor {

    fun extractChromiumCookies(
        cookieDbPath: File,
        localStatePath: File,
        browserName: String
    ): CookieExtractResult {
        Timber.i("Extracting cookies from $browserName: db=$cookieDbPath")
        val masterKey = decryptMasterKey(localStatePath)
            ?: return CookieExtractResult.Error("Failed to decrypt $browserName's encryption key.")

        val tempDb = Files.createTempFile("ml_cookies_", ".db").toFile()
        val tempWal = File(tempDb.absolutePath + "-wal")
        val tempShm = File(tempDb.absolutePath + "-shm")
        try {
            cookieDbPath.copyTo(tempDb, overwrite = true)
            val walFile = File(cookieDbPath.absolutePath + "-wal")
            val shmFile = File(cookieDbPath.absolutePath + "-shm")
            if (walFile.exists()) walFile.copyTo(tempWal, overwrite = true)
            if (shmFile.exists()) shmFile.copyTo(tempShm, overwrite = true)
        } catch (_: Exception) {
            try {
                copyLockedFile(cookieDbPath, tempDb)
            } catch (_: Exception) {
                tempDb.delete(); tempWal.delete(); tempShm.delete()
                return CookieExtractResult.Error("Can't access $browserName's cookies. Try closing $browserName and retry.")
            }
        }

        val cookieMap = mutableMapOf<String, String>()
        val cookieDomain = mutableMapOf<String, String>()
        // Set when a v20 (app-bound) cookie can't be decrypted with the Local State key.
        // v20 keys are SYSTEM-DPAPI-scoped, so user-space decryption is impossible by design.
        var appBoundBlocked = false
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
                            decryptCookieValue(encryptedValue, masterKey)
                        !plainValue.isNullOrBlank() -> plainValue
                        else -> null
                    }

                    if (value == null && encryptedValue != null && encryptedValue.size >= 3 &&
                        String(encryptedValue, 0, 3) == "v20") {
                        appBoundBlocked = true
                    }

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
            tempDb.delete(); tempWal.delete(); tempShm.delete()
        }

        Timber.i("$browserName: found ${cookieMap.size} cookies (keys: ${cookieMap.keys.take(10)})")

        val hasAuth = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
        if (!hasAuth && appBoundBlocked) {
            return CookieExtractResult.Error(
                "$browserName wrote app-bound encrypted cookies (v20), which can't be read from " +
                "user space. Try signing in with a different browser (Edge works)."
            )
        }

        return buildCookieResult(cookieMap, browserName)
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

    private fun decryptWithDPAPI(encrypted: ByteArray): ByteArray? {
        return try {
            val b64 = Base64.getEncoder().encodeToString(encrypted)
            val ps = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                "[Convert]::ToBase64String(" +
                "[System.Security.Cryptography.ProtectedData]::Unprotect(" +
                "[Convert]::FromBase64String('$b64'),\$null," +
                "[System.Security.Cryptography.DataProtectionScope]::CurrentUser))"
            ).redirectErrorStream(true).start()

            val output = ps.inputStream.bufferedReader().readText().trim()
            val exitCode = ps.waitFor()
            if (exitCode != 0 || output.isBlank()) return null
            Base64.getDecoder().decode(output.lines().last().trim())
        } catch (e: Exception) {
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
