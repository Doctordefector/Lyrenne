package com.metrolist.music.desktop.auth

import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BrowserProfile(
    val name: String,
    val userDataDir: File,
    val cookieDbPath: File,
    val localStatePath: File
)

sealed class CookieExtractResult {
    data class Success(val cookie: String, val browserName: String) : CookieExtractResult()
    data class Error(val message: String) : CookieExtractResult()
}

object BrowserCookieExtractor {

    fun detectBrowsers(): List<BrowserProfile> {
        val browsers = mutableListOf<BrowserProfile>()
        val localAppData = System.getenv("LOCALAPPDATA") ?: return browsers
        val appData = System.getenv("APPDATA") ?: return browsers

        val candidates = listOf(
            Pair("Chrome", File(localAppData, "Google/Chrome/User Data")),
            Pair("Edge", File(localAppData, "Microsoft/Edge/User Data")),
            Pair("Opera", File(appData, "Opera Software/Opera Stable")),
            Pair("Opera GX", File(appData, "Opera Software/Opera GX Stable")),
            Pair("Brave", File(localAppData, "BraveSoftware/Brave-Browser/User Data")),
            Pair("Vivaldi", File(localAppData, "Vivaldi/User Data")),
        )

        for ((name, dir) in candidates) {
            if (!dir.exists()) continue
            // Check all possible cookie locations (profile-based and root-level)
            val cookieDb = File(dir, "Default/Network/Cookies").takeIf { it.exists() }
                ?: File(dir, "Default/Cookies").takeIf { it.exists() }
                ?: File(dir, "Network/Cookies").takeIf { it.exists() }
                ?: File(dir, "Cookies").takeIf { it.exists() }
            val localState = File(dir, "Local State")

            if (cookieDb != null && localState.exists()) {
                browsers.add(BrowserProfile(name, dir, cookieDb, localState))
            }
        }

        return browsers
    }

    fun extractYouTubeCookies(browser: BrowserProfile): CookieExtractResult {
        val masterKey = decryptMasterKey(browser.localStatePath)
            ?: return CookieExtractResult.Error("Failed to decrypt ${browser.name}'s encryption key. Make sure ${browser.name} is installed properly.")

        // Copy cookie database to temp file (browser keeps it locked while running)
        val tempDb = Files.createTempFile("ml_cookies_", ".db").toFile()
        val tempWal = File(tempDb.absolutePath + "-wal")
        val tempShm = File(tempDb.absolutePath + "-shm")
        try {
            browser.cookieDbPath.copyTo(tempDb, overwrite = true)
            val walFile = File(browser.cookieDbPath.absolutePath + "-wal")
            val shmFile = File(browser.cookieDbPath.absolutePath + "-shm")
            if (walFile.exists()) walFile.copyTo(tempWal, overwrite = true)
            if (shmFile.exists()) shmFile.copyTo(tempShm, overwrite = true)
        } catch (_: Exception) {
            // File is locked by the browser — try robocopy (Windows) which can copy locked files
            try {
                val sourceDir = browser.cookieDbPath.parentFile.absolutePath
                val destDir = tempDb.parentFile.absolutePath
                val dbName = browser.cookieDbPath.name
                val filesToCopy = buildString {
                    append(dbName)
                    if (File(browser.cookieDbPath.absolutePath + "-journal").exists())
                        append(" $dbName-journal")
                }
                val result = ProcessBuilder(
                    "robocopy", sourceDir, destDir, filesToCopy, "/NJH", "/NJS", "/NP"
                ).redirectErrorStream(true).start()
                result.waitFor()
                // robocopy copies to original filename; rename to our temp name
                val copiedFile = File(tempDb.parentFile, dbName)
                if (copiedFile.exists() && copiedFile != tempDb) {
                    copiedFile.copyTo(tempDb, overwrite = true)
                    copiedFile.delete()
                    val copiedJournal = File(tempDb.parentFile, "$dbName-journal")
                    if (copiedJournal.exists()) copiedJournal.delete()
                }
                if (!tempDb.exists() || tempDb.length() == 0L) {
                    throw Exception("robocopy failed")
                }
            } catch (_: Exception) {
                tempDb.delete(); tempWal.delete(); tempShm.delete()
                return CookieExtractResult.Error("Can't access ${browser.name}'s cookies. Try closing ${browser.name} and retry.")
            }
        }

        val cookieMap = mutableMapOf<String, String>()
        // Track which domain each cookie came from so we prefer .youtube.com
        val cookieDomain = mutableMapOf<String, String>()
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                // Order by host_key so .youtube.com cookies come first (processed first = kept)
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

                    // Skip if we already have this cookie from .youtube.com (preferred domain)
                    if (name in cookieMap && cookieDomain[name]?.contains("youtube") == true) continue

                    val value = when {
                        encryptedValue != null && encryptedValue.size > 3 ->
                            decryptCookieValue(encryptedValue, masterKey)
                        !plainValue.isNullOrBlank() -> plainValue
                        else -> null
                    }

                    if (!value.isNullOrBlank()) {
                        // Strip any remaining non-HTTP-safe characters
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

        if (cookieMap.isEmpty()) {
            return CookieExtractResult.Error("No YouTube cookies found in ${browser.name}. Make sure you're signed in to music.youtube.com.")
        }

        val hasAuth = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
        if (!hasAuth) {
            return CookieExtractResult.Error("You're not signed in to YouTube Music in ${browser.name}. Sign in first, then try again.")
        }

        // Build cookie string with important cookies first
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
            browserName = browser.name
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
                // Modern Chromium browsers prepend a 32-byte binding hash to cookie values.
                // Always strip it. The remaining bytes are the actual cookie value.
                val HASH_PREFIX_LEN = 32
                if (decrypted.size > HASH_PREFIX_LEN) {
                    String(decrypted, HASH_PREFIX_LEN, decrypted.size - HASH_PREFIX_LEN, Charsets.UTF_8)
                } else {
                    String(decrypted, Charsets.UTF_8)
                }
            } else {
                // Legacy DPAPI-encrypted value
                decryptWithDPAPI(encrypted)?.let { String(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
