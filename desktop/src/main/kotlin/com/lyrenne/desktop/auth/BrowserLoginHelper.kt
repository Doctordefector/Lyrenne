package com.lyrenne.desktop.auth

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

    /** Lives under the portable data dir — nothing goes to %APPDATA%. */
    private fun getLoginProfileDir(): File {
        val dir = File(com.lyrenne.desktop.AppPaths.dataDir, "login-profile")
        dir.mkdirs()
        return dir
    }

    /**
     * Strip the login profile down to the part that makes the *next* sign-in bearable.
     *
     * Deleting the whole profile meant every sign-in met a browser that had never seen the
     * account: full email, full password, second factor, every time, no matter how carefully
     * the user had told the browser to remember them. Keeping the profile whole was not the
     * alternative, since the cookie store in it is a second live Google session sitting on disk.
     *
     * So the cookie store goes and `Login Data` stays. That file is the saved password, sealed
     * by DPAPI to this Windows account, along with the `Local State` key that opens it; on its
     * own it cannot authenticate anything, it only spares the typing. Everything else, cache and
     * history and Web Data and Token Service included, is deleted, which also takes the profile
     * from about 87 MB down to a few hundred KB.
     */
    fun pruneLoginProfile() {
        val dir = File(com.lyrenne.desktop.AppPaths.dataDir, "login-profile")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (!f.name.equals("Local State", ignoreCase = true) &&
                !f.name.equals("Default", ignoreCase = true)
            ) {
                f.deleteRecursively()
            }
        }
        File(dir, "Default").listFiles()?.forEach { f ->
            if (!f.name.startsWith("Login Data")) f.deleteRecursively()
        }
        Timber.i("Pruned login profile down to saved passwords")
    }

    /**
     * Delete the login profile outright, saved passwords and all. This is the sign-out path;
     * everywhere else wants [pruneLoginProfile].
     *
     * Safe to call when it does not exist.
     *
     * The profile is single-use scratch space: once the cookies are in credentials.json it has
     * no further purpose, and what it still holds is a second live Google session. Around 87 MB
     * of Chromium profile including the cookie DB and its key. Leaving it behind meant signing
     * out deleted credentials.json and left a working session sitting next to it.
     *
     * Failure is logged, not raised. A browser process still holding a file lock is not a reason
     * to fail a sign-in that already succeeded; the next attempt overwrites the profile anyway.
     */
    fun clearLoginProfile() {
        val dir = File(com.lyrenne.desktop.AppPaths.dataDir, "login-profile")
        if (!dir.exists()) return
        if (dir.deleteRecursively()) {
            Timber.i("Cleared login profile")
        } else {
            Timber.w("Could not fully clear login profile at ${dir.absolutePath}")
        }
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
            // Two things land here, and declining the cookie prompt is the one nobody guesses:
            // reject it and the sign-in cookies are never written at all, so the profile looks
            // the same as one where the user closed the browser without signing in.
            ?: return CookieExtractResult.Error(
                "No sign-in cookies found. This usually means the cookie prompt was declined, " +
                    "or the browser was closed before signing in. Try again and choose " +
                    "\"Accept all\" when the browser asks about cookies."
            )

        val localState = File(profileDir, "Local State")
        if (!localState.exists()) {
            return CookieExtractResult.Error("Browser profile incomplete (no Local State)")
        }

        val result = BrowserCookieExtractor.extractChromiumCookies(cookieDb, localState, browserName)
        // Both entry points funnel through here, and only a Success means the cookies are safely
        // in memory. On anything else the profile has to survive, since the poll path calls this
        // repeatedly while the user is still signing in.
        if (result is CookieExtractResult.Success) pruneLoginProfile()
        return result
    }
}
