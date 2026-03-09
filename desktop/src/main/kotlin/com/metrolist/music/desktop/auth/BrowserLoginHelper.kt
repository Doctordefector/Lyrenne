package com.metrolist.music.desktop.auth

import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.file.Files

/**
 * Launches Edge/Chrome with a fresh temp profile so the user can sign into
 * YouTube Music. After the browser closes, reads cookies from the unlocked
 * cookie DB — no CDP, no WebSocket, no encryption guessing.
 */
object BrowserLoginHelper {

    fun findBrowserExecutable(): File? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

        val candidates = listOf(
            "$programFilesX86\\Microsoft\\Edge\\Application\\msedge.exe",
            "$programFiles\\Microsoft\\Edge\\Application\\msedge.exe",
            "$localAppData\\Microsoft\\Edge\\Application\\msedge.exe",
            "$programFiles\\Google\\Chrome\\Application\\chrome.exe",
            "$programFilesX86\\Google\\Chrome\\Application\\chrome.exe",
            "$localAppData\\Google\\Chrome\\Application\\chrome.exe",
            "$programFiles\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$programFilesX86\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$localAppData\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        )

        return candidates.map(::File).firstOrNull { it.exists() }
    }

    private fun getBrowserName(browser: File): String = when {
        browser.absolutePath.contains("Edge", ignoreCase = true) -> "Edge"
        browser.absolutePath.contains("Chrome", ignoreCase = true) -> "Chrome"
        browser.absolutePath.contains("Brave", ignoreCase = true) -> "Brave"
        else -> "Browser"
    }

    private fun getLoginProfileDir(): File {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(appData, "Metrolist/login-profile")
        dir.mkdirs()
        return dir
    }

    /**
     * 1. Launch browser with a dedicated profile dir
     * 2. Wait for the user to sign in and close the browser
     * 3. Read cookies from the now-unlocked cookie DB
     */
    suspend fun loginWithBrowser(
        onStatus: (String) -> Unit
    ): CookieExtractResult = withContext(Dispatchers.IO) {
        val browser = findBrowserExecutable()
            ?: return@withContext CookieExtractResult.Error("No browser found (need Edge or Chrome)")

        val browserName = getBrowserName(browser)
        val profileDir = getLoginProfileDir()

        Timber.i("Launching $browserName with profile at ${profileDir.absolutePath}")
        onStatus("Opening $browserName...")

        val process = ProcessBuilder(
            browser.absolutePath,
            "--user-data-dir=${profileDir.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-default-apps",
            "https://music.youtube.com"
        ).redirectErrorStream(true).start()

        // Check if browser exited immediately (< 3 seconds = likely handed off to existing process)
        delay(3000)
        if (!process.isAlive) {
            // Browser handed off to an existing instance or crashed.
            // Wait a bit for cookies to be written, then try reading them anyway.
            Timber.w("$browserName exited quickly (possible handoff). Waiting for user to close browser...")
            onStatus("Sign in to YouTube Music, then close $browserName completely.")

            // Poll for the cookie DB to become readable with auth cookies
            val result = pollForCookiesInProfile(profileDir, onStatus, browserName)
            if (result != null) return@withContext result

            return@withContext CookieExtractResult.Error(
                "$browserName exited unexpectedly. Try closing ALL $browserName windows first, then retry."
            )
        }

        onStatus("Sign in to YouTube Music, then close $browserName.")

        // Wait for the browser to close (user closes it after signing in)
        // Check every 2 seconds, timeout after 10 minutes
        var waited = 0
        while (process.isAlive && waited < 600) {
            delay(2000)
            waited += 2
        }

        if (process.isAlive) {
            process.destroyForcibly()
            return@withContext CookieExtractResult.Error("Timed out waiting for browser to close")
        }

        // Small delay to let the browser flush everything to disk
        delay(1000)

        onStatus("Reading cookies...")
        readCookiesFromProfile(profileDir, browserName)
    }

    /**
     * For the handoff case: poll until the cookie DB exists and has auth cookies,
     * or until we detect all browser processes for this profile are gone.
     */
    private suspend fun pollForCookiesInProfile(
        profileDir: File,
        onStatus: (String) -> Unit,
        browserName: String
    ): CookieExtractResult? {
        // Poll every 5 seconds for up to 5 minutes
        for (attempt in 1..60) {
            delay(5000)

            // Check if any browser process is still using this profile
            val lockFile = File(profileDir, "lockfile")
            val singletonLock = File(profileDir, "SingletonLock")

            // If lock files are gone, browser has fully closed
            if (!lockFile.exists() && !singletonLock.exists()) {
                delay(1000) // Let disk flush
                val result = readCookiesFromProfile(profileDir, browserName)
                if (result is CookieExtractResult.Success) return result
                // If no auth cookies yet, keep waiting (user might not have signed in)
            }

            if (attempt % 6 == 0) {
                onStatus("Still waiting... Sign in and close $browserName when done.")
            }
        }
        return null
    }

    private fun readCookiesFromProfile(profileDir: File, browserName: String): CookieExtractResult {
        val cookieDb = File(profileDir, "Default/Network/Cookies").takeIf { it.exists() }
            ?: File(profileDir, "Default/Cookies").takeIf { it.exists() }
            ?: return CookieExtractResult.Error("No cookies found. Did you sign in to YouTube Music?")

        val localState = File(profileDir, "Local State")
        if (!localState.exists()) {
            return CookieExtractResult.Error("Browser profile incomplete (no Local State)")
        }

        // Reuse the existing Chromium cookie extractor
        val profile = BrowserProfile(
            name = "$browserName (login)",
            userDataDir = profileDir,
            cookieDbPath = cookieDb,
            localStatePath = localState,
            type = BrowserType.CHROMIUM
        )

        return BrowserCookieExtractor.extractYouTubeCookies(profile)
    }
}
