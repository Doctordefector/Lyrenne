package com.metrolist.music.desktop.auth

import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class BrowserType {
    CHROMIUM, FIREFOX
}

data class BrowserProfile(
    val name: String,
    val userDataDir: File,
    val cookieDbPath: File,
    val localStatePath: File,
    val type: BrowserType = BrowserType.CHROMIUM
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

        // Chromium-based browsers
        val chromiumCandidates = listOf(
            Pair("Chrome", File(localAppData, "Google/Chrome/User Data")),
            Pair("Edge", File(localAppData, "Microsoft/Edge/User Data")),
            Pair("Opera", File(appData, "Opera Software/Opera Stable")),
            Pair("Opera GX", File(appData, "Opera Software/Opera GX Stable")),
            Pair("Brave", File(localAppData, "BraveSoftware/Brave-Browser/User Data")),
            Pair("Vivaldi", File(localAppData, "Vivaldi/User Data")),
        )

        for ((name, dir) in chromiumCandidates) {
            if (!dir.exists()) continue
            val cookieDb = File(dir, "Default/Network/Cookies").takeIf { it.exists() }
                ?: File(dir, "Default/Cookies").takeIf { it.exists() }
                ?: File(dir, "Network/Cookies").takeIf { it.exists() }
                ?: File(dir, "Cookies").takeIf { it.exists() }
            val localState = File(dir, "Local State")

            if (cookieDb != null && localState.exists()) {
                browsers.add(BrowserProfile(name, dir, cookieDb, localState, BrowserType.CHROMIUM))
            }
        }

        // Firefox
        detectFirefoxProfiles(appData)?.let { browsers.add(it) }

        return browsers
    }

    private fun detectFirefoxProfiles(appData: String): BrowserProfile? {
        val firefoxDir = File(appData, "Mozilla/Firefox")
        val profilesIni = File(firefoxDir, "profiles.ini")
        if (!profilesIni.exists()) return null

        // Parse profiles.ini to find the default profile
        var defaultProfilePath: String? = null
        var currentPath: String? = null
        var currentIsDefault = false

        for (line in profilesIni.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && currentPath != null) {
                if (currentIsDefault) {
                    defaultProfilePath = currentPath
                    break
                }
                currentPath = null
                currentIsDefault = false
            }
            when {
                trimmed.startsWith("Path=", ignoreCase = true) ->
                    currentPath = trimmed.substringAfter("=")
                trimmed.equals("Default=1", ignoreCase = true) ->
                    currentIsDefault = true
            }
        }
        // Check last section
        if (defaultProfilePath == null && currentIsDefault && currentPath != null) {
            defaultProfilePath = currentPath
        }

        // If no explicit default, try the first profile with cookies
        if (defaultProfilePath == null) {
            // Try installs.ini default or just find any profile with cookies
            val installsIni = File(firefoxDir, "installs.ini")
            if (installsIni.exists()) {
                for (line in installsIni.readLines()) {
                    if (line.trim().startsWith("Default=", ignoreCase = true)) {
                        defaultProfilePath = line.trim().substringAfter("=")
                        break
                    }
                }
            }
        }

        // Resolve profile directory
        val profileDir = if (defaultProfilePath != null) {
            val path = defaultProfilePath.replace("\\", "/")
            if (File(path).isAbsolute) File(path) else File(firefoxDir, path)
        } else {
            // Last resort: find any profile directory with cookies.sqlite
            firefoxDir.resolve("Profiles").listFiles()
                ?.firstOrNull { File(it, "cookies.sqlite").exists() }
        }

        val cookieDb = profileDir?.let { File(it, "cookies.sqlite") }
        if (cookieDb != null && cookieDb.exists()) {
            return BrowserProfile(
                name = "Firefox",
                userDataDir = profileDir,
                cookieDbPath = cookieDb,
                localStatePath = profileDir, // not used for Firefox
                type = BrowserType.FIREFOX
            )
        }
        return null
    }

    fun extractYouTubeCookies(browser: BrowserProfile): CookieExtractResult {
        return when (browser.type) {
            BrowserType.CHROMIUM -> extractChromiumCookies(browser)
            BrowserType.FIREFOX -> extractFirefoxCookies(browser)
        }
    }

    private fun extractFirefoxCookies(browser: BrowserProfile): CookieExtractResult {
        val tempDb = Files.createTempFile("ml_ff_cookies_", ".db").toFile()
        try {
            browser.cookieDbPath.copyTo(tempDb, overwrite = true)
        } catch (_: Exception) {
            try {
                copyLockedFile(browser.cookieDbPath, tempDb)
            } catch (_: Exception) {
                tempDb.delete()
                return CookieExtractResult.Error("Can't access Firefox cookies. Try closing Firefox and retry.")
            }
        }

        val cookieMap = mutableMapOf<String, String>()
        val cookieDomain = mutableMapOf<String, String>()
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${tempDb.absolutePath}").use { conn ->
                val stmt = conn.prepareStatement(
                    """SELECT name, value, host FROM moz_cookies
                       WHERE host LIKE '%youtube.com' OR host LIKE '%.google.com'
                       ORDER BY CASE WHEN host LIKE '%youtube.com' THEN 1 ELSE 2 END"""
                )
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val name = rs.getString("name")
                    val host = rs.getString("host")
                    val value = rs.getString("value")

                    if (name in cookieMap && cookieDomain[name]?.contains("youtube") == true) continue

                    if (!value.isNullOrBlank()) {
                        cookieMap[name] = value
                        cookieDomain[name] = host
                    }
                }
            }
        } catch (e: Exception) {
            return CookieExtractResult.Error("Failed to read Firefox cookie database: ${e.message}")
        } finally {
            tempDb.delete()
        }

        return buildCookieResult(cookieMap, browser.name)
    }

    private fun extractChromiumCookies(browser: BrowserProfile): CookieExtractResult {
        Timber.i("Extracting cookies from ${browser.name}: db=${browser.cookieDbPath}, localState=${browser.localStatePath}")
        val masterKey = decryptMasterKey(browser.localStatePath)
            ?: return CookieExtractResult.Error("Failed to decrypt ${browser.name}'s encryption key. Make sure ${browser.name} is installed properly.")

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
            try {
                copyLockedFile(browser.cookieDbPath, tempDb)
            } catch (_: Exception) {
                tempDb.delete(); tempWal.delete(); tempShm.delete()
                return CookieExtractResult.Error("Can't access ${browser.name}'s cookies. Try closing ${browser.name} and retry.")
            }
        }

        val cookieMap = mutableMapOf<String, String>()
        val cookieDomain = mutableMapOf<String, String>()
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

        Timber.i("${browser.name}: found ${cookieMap.size} cookies (keys: ${cookieMap.keys.take(10)})")
        return buildCookieResult(cookieMap, browser.name)
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
            return CookieExtractResult.Error("No YouTube cookies found in $browserName. Make sure you're signed in to music.youtube.com.")
        }

        val hasAuth = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
        if (!hasAuth) {
            return CookieExtractResult.Error("You're not signed in to YouTube Music in $browserName. Sign in first, then try again.")
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
