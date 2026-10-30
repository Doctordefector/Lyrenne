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
        Platform.isLinux -> "Firefox, LibreWolf, Chrome, Chromium, Edge, Brave or Vivaldi (native, snap or Flatpak)"
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
        // Flatpak browsers (SteamOS, Silverblue) see only their own ~/.var/app/<id>, so the profile
        // goes there. The exported launcher runs `flatpak run` and stays up while the browser does.
        for ((kind, name, appId) in flatpakBrowsers) {
            val exe = listOf(File(home, ".local/share/flatpak/exports/bin/$appId"), File("/var/lib/flatpak/exports/bin/$appId"))
                .firstOrNull { it.isFile } ?: continue
            return LoginBrowser(kind, exe, name, flatpakProfile(appId))
        }
        return null
    }

    private val flatpakBrowsers = listOf(
        Triple(BrowserKind.FIREFOX, "Firefox", "org.mozilla.firefox"),
        Triple(BrowserKind.FIREFOX, "LibreWolf", "io.gitlab.librewolf-community"),
        Triple(BrowserKind.CHROMIUM, "Chrome", "com.google.Chrome"),
        Triple(BrowserKind.CHROMIUM, "Chromium", "org.chromium.Chromium"),
        Triple(BrowserKind.CHROMIUM, "Edge", "com.microsoft.Edge"),
        Triple(BrowserKind.CHROMIUM, "Brave", "com.brave.Browser"),
    )

    private fun flatpakProfile(appId: String) =
        File(System.getProperty("user.home"), ".var/app/$appId/lyrenne-$PROFILE_NAME")

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
        val flatpaks = flatpakBrowsers.map { flatpakProfile(it.third) }
        return (listOf(defaultProfileDir()) + snaps + flatpaks).filter { it.exists() }
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
    // deleteRecursively() leaves a symlink whose target is gone, and Firefox's `lock` is always one
    // once the browser has closed. Left behind, the next launch could take the profile as in use.
    private fun remove(f: File) {
        if (Files.isSymbolicLink(f.toPath())) Files.deleteIfExists(f.toPath()) else f.deleteRecursively()
    }

    fun pruneLoginProfile() {
        for (dir in allProfileDirs()) {
            // A sign-in still open in the browser (the headless copy restarted under it) keeps its cookies.
            if (liveLock(dir.toPath().resolve("lock")) || liveLock(dir.toPath().resolve("SingletonLock"))) {
                Timber.i("Login profile ${dir.name} is open in a browser, not pruning")
                continue
            }
            if (File(dir, "Local State").exists() || File(dir, "Default").exists()) {
                dir.listFiles()?.forEach { f ->
                    if (!f.name.equals("Local State", ignoreCase = true) &&
                        !f.name.equals("Default", ignoreCase = true)
                    ) {
                        remove(f)
                    }
                }
                File(dir, "Default").listFiles()?.forEach { f ->
                    if (!f.name.startsWith("Login Data")) remove(f)
                }
            } else {
                dir.listFiles()?.forEach { f ->
                    if (f.name != "logins.json" && f.name != "key4.db") remove(f)
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
     * For a browser someone else starts: the Steam Deck plugin has Steam launch it, because only an
     * app Steam launched gets its on-screen keyboard and controls in Game Mode. Returns the browser's
     * name and full command; follow with [awaitLogin].
     */
    fun loginCommand(): Pair<String, List<String>>? = findLoginBrowser()?.let { browser ->
        // 80% scale: at 100% on an 800 px Deck screen, YouTube's cookie consent put "Accept all"
        // just below the fold. Kiosk mode fit it too, but left no visible way out of the browser.
        prepareProfile(browser, scale = "0.8")
        val args = launchArgs(browser)
        browser.name to when (browser.kind) {
            BrowserKind.FIREFOX -> args
            BrowserKind.CHROMIUM -> args.take(1) + "--force-device-scale-factor=0.8" + args.drop(1)
        }
    }

    /**
     * Creates the profile and, for Firefox, skips its first-run screens (the welcome and terms
     * dialogs sat on top of the sign-in page in a fresh profile). [pruneLoginProfile] deletes
     * user.js with everything else, so it is written on every sign-in.
     */
    private fun prepareProfile(browser: LoginBrowser, scale: String? = null) {
        browser.profileDir.mkdirs()
        if (browser.kind != BrowserKind.FIREFOX) return
        val prefs = listOfNotNull(
            "browser.aboutwelcome.enabled" to "false",
            "browser.preonboarding.enabled" to "false",
            "termsofuse.bypassNotification" to "true",
            "datareporting.policy.dataSubmissionPolicyBypassNotification" to "true",
            "browser.shell.checkDefaultBrowser" to "false",
            "browser.startup.homepage_override.mstone" to "\"ignore\"",
            scale?.let { "layout.css.devPixelsPerPx" to "\"$it\"" },
        )
        File(browser.profileDir, "user.js").writeText(prefs.joinToString("") { (k, v) -> "user_pref(\"$k\", $v);\n" })
    }

    /** Waits (10 minutes at most) for the browser from [loginCommand] to sign in and close. */
    suspend fun awaitLogin(onStatus: (String) -> Unit): CookieExtractResult = withContext(Dispatchers.IO) {
        val browser = findLoginBrowser()
            ?: return@withContext CookieExtractResult.Error("No browser found (Lyrenne looks for $supportedBrowserNames).")
        onStatus("Sign in to YouTube Music, then close ${browser.name}.")
        pollForCookiesInProfile(browser, onStatus)
            ?: CookieExtractResult.Error("Timed out waiting for ${browser.name} to close")
    }

    /**
     * Closes the browser running the login profile, politely (SIGTERM lets Firefox flush its
     * cookie store), so a waiting [loginWithBrowser] goes on to read the cookies. For Game Mode on
     * the Steam Deck, where a browser window has no close button.
     */
    fun closeLoginBrowser() {
        val dirs = allProfileDirs().map { it.absolutePath }
        ProcessHandle.allProcesses()
            .filter { p -> p.info().commandLine().map { cmd -> dirs.any { it in cmd } }.orElse(false) }
            .forEach { it.destroy() }
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
        prepareProfile(browser)

        // A sign-in window from an earlier attempt is still open: a second launch would only hit its
        // profile lock, so wait for that one to close instead.
        if (isProfileInUse(browser)) {
            onStatus("Finish signing in, then close $browserName.")
            return@withContext pollForCookiesInProfile(browser, onStatus)
                ?: CookieExtractResult.Error("Timed out waiting for $browserName to close")
        }

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

    internal fun launchArgs(browser: LoginBrowser): List<String> = when (browser.kind) {
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
            // Device Bound Session Credentials (Chrome 146+ on Windows, Edge with it) tie Google's
            // session cookies to a TPM key only this browser holds, and keep them short-lived so
            // the binding is re-proved every few minutes. Copied into credentials.json they cannot
            // be refreshed, and the session died within a day: the "sign in every morning" bug.
            // Unknown feature names are ignored, so listing both spellings is safe.
            "--disable-features=EnableBoundSessionCredentials,DeviceBoundSessions",
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
        // Poll every 5 seconds for up to 10 minutes, the same as a fresh launch waits
        for (attempt in 1..120) {
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
        fun linkExists(name: String) = liveLock(dir.toPath().resolve(name))
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

    /**
     * A lock symlink whose owner is still running. The link outlives a browser that crashed or was
     * killed, so its existence alone would leave the handoff poll waiting forever. Its target names
     * the owner, `host-<pid>` (Chromium) or `<ip>:+<pid>` (Firefox), which is exactly how the
     * browsers themselves tell a stale lock from a live one. Same-host only, which a login profile is.
     */
    private fun liveLock(link: java.nio.file.Path): Boolean {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) return false
        // A Flatpak browser writes the pid from its own pid namespace (often 2, a kernel thread out
        // here), so ask whether any process was started with this profile instead.
        val dir = link.parent.toString()
        if ("/.var/app/" in dir) {
            return ProcessHandle.allProcesses().anyMatch { p -> p.info().commandLine().map { dir in it }.orElse(false) }
        }
        if (!Files.isSymbolicLink(link)) return true
        val target = runCatching { Files.readSymbolicLink(link).toString() }.getOrNull() ?: return true
        val pid = Regex("""(\d+)$""").find(target)?.groupValues?.get(1)?.toLongOrNull() ?: return true
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
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
