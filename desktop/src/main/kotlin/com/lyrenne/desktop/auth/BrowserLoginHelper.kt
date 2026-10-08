package com.lyrenne.desktop.auth

import com.lyrenne.desktop.AppPaths
import com.lyrenne.desktop.Platform
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption

enum class BrowserKind { CHROMIUM, FIREFOX }

/** A browser Lyrenne can sign in through, and the throwaway profile it will use. */
data class LoginBrowser(val kind: BrowserKind, val exe: File, val name: String, val profileDir: File)

/**
 * Launches a browser with a fresh temp profile so the user can sign into
 * YouTube Music. After the browser closes, reads cookies from the unlocked
 * cookie DB — no CDP, no WebSocket, no encryption guessing.
 *
 * Discovery order: Windows tries Edge → Chrome → Brave → Firefox. Linux tries Firefox first,
 * because its cookies are unencrypted and it is the default almost everywhere, then the
 * Chromium family launched with `--password-store=basic` so its cookies use a known key.
 */
object BrowserLoginHelper {

    private const val PROFILE_NAME = "login-profile"

    fun findLoginBrowser(): LoginBrowser? = when {
        Platform.isWindows -> findWindowsBrowser()
        Platform.isLinux -> findLinuxBrowser()
        Platform.isMac -> File("/Applications/Firefox.app/Contents/MacOS/firefox").takeIf { it.exists() }
            ?.let { LoginBrowser(BrowserKind.FIREFOX, it, "Firefox", defaultProfileDir()) }
        else -> null
    }

    /** The browsers looked for on this OS, for the "no browser found" message. */
    val supportedBrowserNames: String get() = when {
        Platform.isWindows -> "Edge, Chrome, Brave or Firefox"
        Platform.isLinux -> "Firefox, LibreWolf, Chrome, Chromium, Edge, Brave or Vivaldi"
        else -> "Firefox"
    }

    private fun findWindowsBrowser(): LoginBrowser? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

        val candidates = listOf(
            Triple(BrowserKind.CHROMIUM, "Edge", "$programFilesX86\\Microsoft\\Edge\\Application\\msedge.exe"),
            Triple(BrowserKind.CHROMIUM, "Edge", "$programFiles\\Microsoft\\Edge\\Application\\msedge.exe"),
            Triple(BrowserKind.CHROMIUM, "Edge", "$localAppData\\Microsoft\\Edge\\Application\\msedge.exe"),
            Triple(BrowserKind.CHROMIUM, "Chrome", "$programFiles\\Google\\Chrome\\Application\\chrome.exe"),
            Triple(BrowserKind.CHROMIUM, "Chrome", "$programFilesX86\\Google\\Chrome\\Application\\chrome.exe"),
            Triple(BrowserKind.CHROMIUM, "Chrome", "$localAppData\\Google\\Chrome\\Application\\chrome.exe"),
            Triple(BrowserKind.CHROMIUM, "Brave", "$programFiles\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            Triple(BrowserKind.CHROMIUM, "Brave", "$programFilesX86\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            Triple(BrowserKind.CHROMIUM, "Brave", "$localAppData\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            Triple(BrowserKind.FIREFOX, "Firefox", "$programFiles\\Mozilla Firefox\\firefox.exe"),
            Triple(BrowserKind.FIREFOX, "Firefox", "$programFilesX86\\Mozilla Firefox\\firefox.exe"),
        )
        return candidates.firstOrNull { File(it.third).exists() }
            ?.let { (kind, name, path) -> LoginBrowser(kind, File(path), name, defaultProfileDir()) }
    }

    private fun findLinuxBrowser(): LoginBrowser? {
        val home = System.getProperty("user.home")
        // Snap confinement forbids hidden dirs in $HOME and anything outside it, so a snap
        // browser gets its profile in its own common dir, which Lyrenne (same user) can read.
        fun snapProfile(snap: String) = File(home, "snap/$snap/common/lyrenne-$PROFILE_NAME")

        // Ubuntu's /usr/bin/firefox is a wrapper that runs the snap: go straight to the snap.
        File("/snap/bin/firefox").takeIf { it.exists() }?.let {
            return LoginBrowser(BrowserKind.FIREFOX, it, "Firefox", snapProfile("firefox"))
        }
        val candidates = listOf(
            Triple(BrowserKind.FIREFOX, "Firefox", "firefox"),
            Triple(BrowserKind.FIREFOX, "Firefox", "firefox-esr"),
            Triple(BrowserKind.FIREFOX, "LibreWolf", "librewolf"),
            Triple(BrowserKind.CHROMIUM, "Edge", "microsoft-edge"),
            Triple(BrowserKind.CHROMIUM, "Edge", "microsoft-edge-stable"),
            Triple(BrowserKind.CHROMIUM, "Chrome", "google-chrome"),
            Triple(BrowserKind.CHROMIUM, "Chrome", "google-chrome-stable"),
            Triple(BrowserKind.CHROMIUM, "Chromium", "chromium"),
            Triple(BrowserKind.CHROMIUM, "Chromium", "chromium-browser"),
            Triple(BrowserKind.CHROMIUM, "Brave", "brave-browser"),
            Triple(BrowserKind.CHROMIUM, "Vivaldi", "vivaldi"),
        )
        for ((kind, name, command) in candidates) {
            var exe = onPath(command) ?: continue
            // Snap launchers live in /snap/bin; Ubuntu's chromium-browser is a wrapper for one.
            val snapName = when {
                exe.parent == "/snap/bin" -> exe.name
                command.startsWith("chromium") && File("/snap/bin/chromium").exists() -> "chromium"
                else -> null
            }
            if (snapName != null) exe = File("/snap/bin/$snapName")
            val profile = if (snapName != null) snapProfile(snapName) else defaultProfileDir()
            return LoginBrowser(kind, exe, name, profile)
        }
        return null
    }

    private fun onPath(command: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, command) }
            .firstOrNull { it.isFile && it.canExecute() }

    /** Lives under the app's data dir — nothing goes to %APPDATA%. */
    private fun defaultProfileDir(): File = File(AppPaths.dataDir, PROFILE_NAME)

    /** Every place a login profile may have been made, so prune and clear reach all of them. */
    private fun allProfileDirs(): List<File> {
        val snaps = File(System.getProperty("user.home"), "snap").listFiles().orEmpty()
            .map { File(it, "common/lyrenne-$PROFILE_NAME") }
        return (listOf(defaultProfileDir()) + snaps).filter { it.exists() }
    }

    /**
     * Strip the login profile down to the part that makes the *next* sign-in bearable.
     *
     * Deleting the whole profile meant every sign-in met a browser that had never seen the
     * account: full email, full password, second factor, every time, no matter how carefully
     * the user had told the browser to remember them. Keeping the profile whole was not the
     * alternative, since the cookie store in it is a second live Google session sitting on disk.
     *
     * So the cookie store goes and the saved password stays. Chromium: `Login Data` (sealed by
     * DPAPI to this Windows account) plus the `Local State` key that opens it. Firefox:
     * `logins.json` plus `key4.db`. On its own that cannot authenticate anything, it only spares
     * the typing. Everything else, cache and history included, is deleted, which also takes the
     * profile from about 87 MB down to a few hundred KB.
     */
    fun pruneLoginProfile() {
        for (dir in allProfileDirs()) {
            if (File(dir, "Local State").exists() || File(dir, "Default").exists()) {
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
            } else {
                dir.listFiles()?.forEach { f ->
                    if (f.name != "logins.json" && f.name != "key4.db") f.deleteRecursively()
                }
            }
            Timber.i("Pruned login profile ${dir.name} down to saved passwords")
        }
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
        for (dir in allProfileDirs()) {
            if (dir.deleteRecursively()) {
                Timber.i("Cleared login profile")
            } else {
                Timber.w("Could not fully clear login profile at ${dir.absolutePath}")
            }
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
        val browser = findLoginBrowser()
            ?: return@withContext CookieExtractResult.Error(
                "No browser found (Lyrenne looks for $supportedBrowserNames). " +
                    "You can paste a cookie under Advanced instead."
            )

        val browserName = browser.name
        val profileDir = browser.profileDir
        profileDir.mkdirs()

        Timber.i("Launching $browserName with profile at ${profileDir.absolutePath}")
        onStatus("Opening $browserName...")

        val process = ProcessBuilder(launchArgs(browser)).redirectErrorStream(true).start()

        // Check if browser exited immediately (< 3 seconds = likely handed off to existing process)
        delay(3000)
        if (!process.isAlive) {
            // Browser handed off to an existing instance or crashed.
            // Wait a bit for cookies to be written, then try reading them anyway.
            Timber.w("$browserName exited quickly (possible handoff). Waiting for user to close browser...")
            onStatus("Sign in to YouTube Music, then close $browserName completely.")

            // Poll for the cookie DB to become readable with auth cookies
            val result = pollForCookiesInProfile(browser, onStatus)
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
        readCookiesFromProfile(browser)
    }

    private fun launchArgs(browser: LoginBrowser): List<String> = when (browser.kind) {
        BrowserKind.FIREFOX -> listOfNotNull(
            browser.exe.absolutePath,
            "-profile", browser.profileDir.absolutePath,
            "-no-remote", // separate instance, so process.isAlive tracks *this* window
            // Windows' launcher process otherwise exits as soon as the browser starts.
            "-wait-for-browser".takeIf { Platform.isWindows },
            "https://music.youtube.com"
        )
        BrowserKind.CHROMIUM -> listOfNotNull(
            browser.exe.absolutePath,
            "--user-data-dir=${browser.profileDir.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-default-apps",
            // Linux: keep cookies out of the desktop keyring, under the fixed v10 key instead.
            "--password-store=basic".takeIf { Platform.isLinux },
            "https://music.youtube.com"
        )
    }

    /**
     * For the handoff case: poll until the cookie DB exists and has auth cookies,
     * or until we detect all browser processes for this profile are gone.
     */
    private suspend fun pollForCookiesInProfile(
        browser: LoginBrowser,
        onStatus: (String) -> Unit
    ): CookieExtractResult? {
        // Poll every 5 seconds for up to 5 minutes
        for (attempt in 1..60) {
            delay(5000)

            // If the lock is gone, the browser has fully closed
            if (!isProfileInUse(browser)) {
                delay(1000) // Let disk flush
                val result = readCookiesFromProfile(browser)
                if (result is CookieExtractResult.Success) return result
                // If no auth cookies yet, keep waiting (user might not have signed in)
            }

            if (attempt % 6 == 0) {
                onStatus("Still waiting... Sign in and close ${browser.name} when done.")
            }
        }
        return null
    }

    /**
     * Whether a browser still has [LoginBrowser.profileDir] open.
     *
     * On Linux Chromium's `SingletonLock` and Firefox's `lock` are symlinks to `host-pid`, a target
     * that never exists, so `File.exists()` (which follows links) always said "closed" and the
     * cookies were read too early. The link itself is checked instead.
     */
    internal fun isProfileInUse(browser: LoginBrowser): Boolean {
        val dir = browser.profileDir
        fun linkExists(name: String) = Files.exists(dir.toPath().resolve(name), LinkOption.NOFOLLOW_LINKS)
        return when (browser.kind) {
            BrowserKind.CHROMIUM -> linkExists("SingletonLock") || File(dir, "lockfile").exists()
            BrowserKind.FIREFOX -> {
                if (linkExists("lock")) return true
                // Windows: parent.lock stays on disk, but is held exclusively while Firefox runs.
                val parentLock = File(dir, "parent.lock")
                Platform.isWindows && parentLock.exists() &&
                    runCatching { RandomAccessFile(parentLock, "rw").close() }.isFailure
            }
        }
    }

    private fun readCookiesFromProfile(browser: LoginBrowser): CookieExtractResult {
        val profileDir = browser.profileDir
        val result = if (browser.kind == BrowserKind.FIREFOX) {
            BrowserCookieExtractor.extractFirefoxCookies(profileDir, browser.name)
        } else {
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
            BrowserCookieExtractor.extractChromiumCookies(cookieDb, localState, browser.name)
        }
        // Both entry points funnel through here, and only a Success means the cookies are safely
        // in memory. On anything else the profile has to survive, since the poll path calls this
        // repeatedly while the user is still signing in.
        if (result is CookieExtractResult.Success) pruneLoginProfile()
        return result
    }
}
