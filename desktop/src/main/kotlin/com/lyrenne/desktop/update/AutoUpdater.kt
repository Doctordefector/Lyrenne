package com.lyrenne.desktop.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Auto-updater for Lyrenne.
 *
 * Supports two modes, auto-detected:
 * - **Installed mode**: Downloads EXE installer and launches it. The installer handles everything.
 * - **Portable mode**: Downloads portable ZIP, extracts to staging, uses PowerShell script to
 *   swap files while the app is closed, then restarts.
 *
 * Detection: if the app directory contains an uninstaller (unins000.exe from NSIS) or is under
 * Program Files, it's installed. Otherwise it's portable.
 */
object AutoUpdater {
    const val CURRENT_VERSION = "2.11.1"
    private const val GITHUB_OWNER = "Doctordefector"
    private const val GITHUB_REPO = "Lyrenne"

    /** Launcher filename. Installs older than 2.9.4 are not carried forward: see AppPaths. */
    private val LAUNCHER_NAMES = listOf("Lyrenne.exe")
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _state.asStateFlow()

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
        val html_url: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long = 0
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class UpdateAvailable(
            val version: String,
            val releaseNotes: String?,
            val downloadUrl: String,
            val downloadSize: Long,
            val releaseUrl: String?,
            val isPortable: Boolean
        ) : UpdateState()
        data class Downloading(val progress: Float, val version: String) : UpdateState()
        data class ReadyToInstall(val version: String, val updateFile: File, val isPortable: Boolean) : UpdateState()
        data class Error(val message: String) : UpdateState()
        data object UpToDate : UpdateState()
    }

    // ==================== Public API ====================

    fun checkForUpdates() {
        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    _state.value = UpdateState.Error("Could not reach GitHub")
                    return@launch
                }
                if (release.draft || release.prerelease) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                val latestVersion = release.tag_name.removePrefix("v")
                Timber.i("Update check: current=$CURRENT_VERSION, latest=$latestVersion")

                if (!isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                val portable = isPortableInstall()
                Timber.i("Install mode: ${if (portable) "portable" else "installed"}")

                val asset = if (portable) {
                    // Portable: need the ZIP
                    release.assets.find {
                        it.name.contains("portable", ignoreCase = true) && it.name.endsWith(".zip")
                    }
                } else {
                    // Installed: prefer EXE installer, fall back to MSI
                    release.assets.find { it.name.endsWith(".exe", ignoreCase = true) }
                        ?: release.assets.find { it.name.endsWith(".msi", ignoreCase = true) }
                }

                if (asset == null) {
                    val kind = if (portable) "portable ZIP" else "installer"
                    Timber.w("No $kind in release assets: ${release.assets.map { it.name }}")
                    _state.value = UpdateState.Error("No $kind found for v$latestVersion")
                    return@launch
                }

                Timber.i("Update available: v$latestVersion, asset=${asset.name} (${asset.size} bytes)")
                _state.value = UpdateState.UpdateAvailable(
                    version = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = asset.browser_download_url,
                    downloadSize = asset.size,
                    releaseUrl = release.html_url,
                    isPortable = portable
                )
            } catch (e: Exception) {
                Timber.e("Update check failed: ${e.message}")
                _state.value = UpdateState.Error("Update check failed: ${e.message}")
            }
        }
    }

    fun downloadAndInstall() {
        val current = _state.value
        if (current !is UpdateState.UpdateAvailable) return

        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f, current.version)

                val updateDir = getUpdateDirectory()
                Files.createDirectories(updateDir.toPath())

                val fileName = current.downloadUrl.substringAfterLast("/")
                val downloadFile = File(updateDir, fileName)

                Timber.i("Downloading ${current.downloadUrl} → ${downloadFile.name}")
                downloadFile(current.downloadUrl, downloadFile, current.downloadSize) { progress ->
                    _state.value = UpdateState.Downloading(progress, current.version)
                }
                Timber.i("Download complete: ${downloadFile.length()} bytes")

                if (current.isPortable) {
                    // Extract ZIP to a fresh temp staging dir to avoid leftover locked files
                    val stagingDir = File(updateDir, "staging-${System.currentTimeMillis()}")
                    // Clean up any old staging dirs
                    updateDir.listFiles()?.filter { it.name.startsWith("staging") }?.forEach { old ->
                        try { old.deleteRecursively() } catch (_: Exception) {}
                    }
                    Files.createDirectories(stagingDir.toPath())

                    Timber.i("Extracting to ${stagingDir.absolutePath}")
                    extractZip(downloadFile, stagingDir)
                    downloadFile.delete()

                    // If ZIP has a single top-level directory, use that
                    val contents = stagingDir.listFiles() ?: emptyArray()
                    val sourceDir = if (contents.size == 1 && contents[0].isDirectory) {
                        Timber.i("ZIP root dir: ${contents[0].name}")
                        contents[0]
                    } else {
                        stagingDir
                    }

                    // Verify we have the exe
                    val hasExe = sourceDir.listFiles()?.any { f ->
                        LAUNCHER_NAMES.any { f.name.equals(it, ignoreCase = true) }
                    } == true
                    if (!hasExe) {
                        Timber.e("No launcher in: ${sourceDir.listFiles()?.map { it.name }}")
                        _state.value = UpdateState.Error("Invalid update package, no ${LAUNCHER_NAMES.first()} found")
                        return@launch
                    }

                    _state.value = UpdateState.ReadyToInstall(current.version, sourceDir, isPortable = true)
                } else {
                    // Installer mode: just keep the downloaded EXE/MSI
                    _state.value = UpdateState.ReadyToInstall(current.version, downloadFile, isPortable = false)
                }
            } catch (e: Exception) {
                Timber.e("Download/extract failed: ${e.message}")
                _state.value = UpdateState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun applyUpdate() {
        val current = _state.value
        if (current !is UpdateState.ReadyToInstall) return

        try {
            if (current.isPortable) {
                applyPortableUpdate(current)
            } else {
                applyInstallerUpdate(current)
            }
        } catch (e: Exception) {
            Timber.e("Failed to apply update: ${e.message}")
            _state.value = UpdateState.Error("Failed to start update: ${e.message}")
        }
    }

    private fun applyInstallerUpdate(state: UpdateState.ReadyToInstall) {
        val installer = state.updateFile
        if (!installer.exists()) {
            _state.value = UpdateState.Error("Installer file not found")
            return
        }

        Timber.i("Launching installer: ${installer.absolutePath}")
        if (installer.name.endsWith(".msi", ignoreCase = true)) {
            ProcessBuilder("msiexec", "/i", installer.absolutePath).start()
        } else {
            ProcessBuilder(installer.absolutePath).start()
        }

        Timber.i("Installer launched, exiting app...")
        Runtime.getRuntime().exit(0)
    }

    private fun applyPortableUpdate(state: UpdateState.ReadyToInstall) {
        val sourceDir = state.updateFile
        val appDir = findAppDirectory()
        if (appDir == null) {
            _state.value = UpdateState.Error("Cannot determine app directory")
            return
        }

        Timber.i("Portable update: ${sourceDir.absolutePath} → ${appDir.absolutePath}")

        val updateDir = getUpdateDirectory()
        val pid = ProcessHandle.current().pid()
        val logFile = File(updateDir, "update.log").absolutePath
        val scriptFile = File(updateDir, "lyrenne-update.ps1")

        scriptFile.writeText(buildPortableUpdateScript(
            pid = pid,
            sourcePath = sourceDir.absolutePath,
            destPath = appDir.absolutePath,
            exePath = LAUNCHER_NAMES.map { File(appDir, it) }.firstOrNull { it.exists() }
                ?.absolutePath ?: File(appDir, LAUNCHER_NAMES.first()).absolutePath,
            logPath = logFile,
            stagingRoot = sourceDir.parentFile.absolutePath
        ))

        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", scriptFile.absolutePath
        ).directory(updateDir).redirectErrorStream(true).start()

        Timber.i("Update script launched, exiting...")
        Runtime.getRuntime().exit(0)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    // ==================== Portable update script ====================

    /**
     * Quote a Windows path for a PowerShell *single*-quoted literal.
     *
     * These paths are wherever the user unzipped the app, and `$` is legal in a Windows folder
     * name. Inside "..." PowerShell would expand `$Music` to nothing, so the update would copy to
     * the wrong place and only say so in a log nobody reads. And `$(...)`, also a legal folder
     * name, would be executed outright. Single quotes suppress both; the only escape a
     * single-quoted string needs is a doubled quote.
     */
    private fun psQuote(path: String): String = "'" + path.replace("'", "''") + "'"

    // Quoting the assignment is only half of it: the value still has to survive being handed to
    // a native exe. Start-Process -ArgumentList concatenates without quoting, so robocopy is
    // invoked directly below, where PowerShell quotes each argument itself.

    private fun buildPortableUpdateScript(
        pid: Long, sourcePath: String, destPath: String,
        exePath: String, logPath: String, stagingRoot: String
    ): String = """
        ${'$'}ErrorActionPreference = "Continue"
        ${'$'}SourcePath = ${psQuote(sourcePath)}
        ${'$'}DestPath = ${psQuote(destPath)}
        ${'$'}ExePath = ${psQuote(exePath)}
        ${'$'}LogPath = ${psQuote(logPath)}
        ${'$'}StagingRoot = ${psQuote(stagingRoot)}
        function Log(${'$'}msg) {
            ${'$'}ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            "${'$'}ts  ${'$'}msg" | Out-File -FilePath ${'$'}LogPath -Append -Encoding UTF8
        }
        Log "=== Lyrenne Portable Update ==="
        Log "Source: ${'$'}SourcePath"
        Log "Destination: ${'$'}DestPath"

        # Wait for app to exit (max 60s)
        ${'$'}waited = 0
        while (${'$'}waited -lt 60) {
            ${'$'}proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if (-not ${'$'}proc) { Log "Process exited after ${'$'}waited seconds"; break }
            Start-Sleep -Seconds 1
            ${'$'}waited++
        }
        Start-Sleep -Seconds 2

        # Copy with robocopy
        Log "Copying files..."
        & robocopy.exe ${'$'}SourcePath ${'$'}DestPath /E /IS /IT /R:5 /W:2 /NFL /NDL /NP |
            Out-File -FilePath ${'$'}LogPath -Append -Encoding UTF8
        ${'$'}exitCode = ${'$'}LASTEXITCODE

        if (${'$'}exitCode -lt 8) {
            Log "Copy succeeded (exit code: ${'$'}exitCode)"
        } else {
            Log "ERROR: Copy failed (exit code: ${'$'}exitCode)"
        }

        # Restart
        Log "Starting Lyrenne..."
        Start-Process -FilePath ${'$'}ExePath

        Start-Sleep -Seconds 3
        Remove-Item -Path ${'$'}StagingRoot -Recurse -Force -ErrorAction SilentlyContinue
        Log "=== Update Complete ==="
        Remove-Item -Path ${'$'}MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
    """.trimIndent()

    // ==================== Detection ====================

    /**
     * Detect if running as portable install.
     * Installed: has uninstaller (NSIS) or is under Program Files.
     * Everything else: portable.
     */
    private fun isPortableInstall(): Boolean {
        val appDir = findAppDirectory() ?: return true // default to portable if unknown
        val path = appDir.absolutePath.lowercase()

        // NSIS installer creates unins000.exe
        if (File(appDir, "unins000.exe").exists()) return false

        // MSI installs go to Program Files
        if (path.contains("program files")) return false

        // Check for LocalAppData install location (typical for per-user NSIS installs)
        val localAppData = System.getenv("LOCALAPPDATA")?.lowercase()
        if (localAppData != null && path.startsWith(localAppData)) return false

        return true
    }

    // ==================== Network ====================

    private fun fetchLatestRelease(): GitHubRelease? {
        val connection = URI(API_URL).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Lyrenne/$CURRENT_VERSION")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<GitHubRelease>(body)
            } else {
                val error = try { connection.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                Timber.w("GitHub API HTTP ${connection.responseCode}: $error")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Every hop of the update download has to stay on HTTPS and on GitHub.
     *
     * What arrives here is extracted over the app directory and then executed, so the transport
     * is the whole trust chain, since there is no signature to fall back on. The redirect loop below
     * follows `Location` by hand, and without this check a single hop answering with an
     * `http://` target would turn the updater into a cleartext delivery channel for code that
     * runs as the user. Anything not on github.com is refused for the same reason.
     */
    private fun requireTrustedUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw SecurityException("Malformed update URL: $url")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw SecurityException("Refusing non-HTTPS update URL (scheme: ${uri.scheme})")
        }
        val host = uri.host?.lowercase()
            ?: throw SecurityException("Update URL has no host: $url")
        val trusted = host == "github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
        if (!trusted) {
            throw SecurityException("Refusing update download from untrusted host: $host")
        }
    }

    private fun downloadFile(url: String, target: File, totalSize: Long, onProgress: (Float) -> Unit) {
        var currentUrl = url
        var redirects = 0

        requireTrustedUrl(currentUrl)

        val connection: HttpURLConnection
        while (true) {
            val conn = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Lyrenne/$CURRENT_VERSION")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw Exception("Redirect without Location header (HTTP $code)")
                conn.disconnect()
                // Resolve relative Locations against the current URL before checking, so a
                // relative hop cannot slip past the scheme test by having no scheme at all.
                currentUrl = URI(currentUrl).resolve(location).toString()
                requireTrustedUrl(currentUrl)
                if (++redirects > 10) throw Exception("Too many redirects ($redirects)")
                continue
            }
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { "" }
                conn.disconnect()
                throw Exception("HTTP $code: $err")
            }
            connection = conn
            break
        }

        val actualSize = if (totalSize > 0) totalSize else connection.contentLengthLong.coerceAtLeast(1)
        var downloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    onProgress((downloaded.toFloat() / actualSize).coerceIn(0f, 0.99f))
                }
            }
        }
        connection.disconnect()
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Normalize backslashes — PowerShell Compress-Archive uses \ in entry names,
                // but Java's ZipEntry.isDirectory() only checks for trailing /
                val name = entry.name.replace('\\', '/')
                val outFile = File(targetDir, name)

                // Zip slip protection
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip slip: $name")
                }

                try {
                    if (entry.isDirectory || name.endsWith("/")) {
                        Files.createDirectories(outFile.toPath())
                    } else {
                        // Always ensure parent dirs exist (ZIP may lack intermediate dir entries)
                        outFile.parentFile?.let { Files.createDirectories(it.toPath()) }
                        if (outFile.exists()) outFile.delete()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos, bufferSize = 65536) }
                        count++
                    }
                } catch (e: Exception) {
                    Timber.e("Failed to extract: $name → ${e.message}")
                    throw Exception("Cannot extract '$name': ${e.message}", e)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Timber.i("Extracted $count files to ${targetDir.absolutePath}")
    }

    // ==================== Helpers ====================

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val lParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(lParts.size, cParts.size)) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun getUpdateDirectory(): File {
        // Put updates next to the app, not in Roaming
        val appDir = findAppDirectory()
        if (appDir != null && appDir.canWrite()) {
            return File(appDir, "updates")
        }

        // Fallback: next to working directory
        val userDir = File(System.getProperty("user.dir", "."))
        if (userDir.canWrite()) {
            return File(userDir, "updates")
        }

        // Last resort: temp directory (always writable)
        return File(System.getProperty("java.io.tmpdir"), "lyrenne-updates")
    }

    private fun findAppDirectory(): File? {
        // Strategy 1: compose.application.resources.dir → walk up to find exe
        System.getProperty("compose.application.resources.dir")?.let { resDir ->
            var dir = File(resDir)
            repeat(4) {
                dir = dir.parentFile ?: return@let
                if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
            }
        }

        // Strategy 2: user.dir
        File(System.getProperty("user.dir")).let { dir ->
            if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
        }

        // Strategy 3: code source location
        try {
            val codeSource = AutoUpdater::class.java.protectionDomain.codeSource
            if (codeSource != null) {
                var dir = File(codeSource.location.toURI())
                repeat(4) {
                    dir = dir.parentFile ?: return@repeat
                    if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
                }
            }
        } catch (_: Exception) {}

        // Strategy 4: common install locations
        listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { File(it, "Lyrenne") },
            File("C:/Program Files/Lyrenne"),
            File("C:/Program Files (x86)/Lyrenne")
        ).forEach { dir ->
            if (LAUNCHER_NAMES.any { File(dir, it).exists() }) return dir
        }

        return null
    }
}
