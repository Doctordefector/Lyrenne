package com.metrolist.music.desktop.update

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
 * Auto-updater for Metrolist Desktop.
 *
 * Flow: check GitHub → download ZIP → extract to staging → write PowerShell update script →
 * launch script → exit app → script waits for JVM to die → robocopy files → restart app.
 *
 * Key design decisions:
 * - Uses PowerShell (not batch) for reliable file operations and error handling
 * - Uses robocopy (not xcopy) which handles locked files, retries, and long paths
 * - Waits for JVM process to fully exit before copying (with timeout)
 * - Verifies extracted content has Metrolist.exe before offering install
 * - Logs every step to %APPDATA%/Metrolist/updates/update.log
 */
object AutoUpdater {
    const val CURRENT_VERSION = "1.9.0"
    private const val GITHUB_OWNER = "Doctordefector"
    private const val GITHUB_REPO = "Metrolist-Desktop"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _state.asStateFlow()

    // ==================== Data types ====================

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
            val releaseUrl: String?
        ) : UpdateState()
        data class Downloading(val progress: Float, val version: String) : UpdateState()
        data class ReadyToInstall(val version: String, val sourceDir: File) : UpdateState()
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

                val zipAsset = release.assets.find {
                    it.name.contains("portable", ignoreCase = true) && it.name.endsWith(".zip")
                }
                if (zipAsset == null) {
                    Timber.w("No portable ZIP in release assets: ${release.assets.map { it.name }}")
                    _state.value = UpdateState.Error("No portable download found for v$latestVersion")
                    return@launch
                }

                Timber.i("Update available: v$latestVersion, asset=${zipAsset.name} (${zipAsset.size} bytes)")
                _state.value = UpdateState.UpdateAvailable(
                    version = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = zipAsset.browser_download_url,
                    downloadSize = zipAsset.size,
                    releaseUrl = release.html_url
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

                // Download
                val zipFile = File(updateDir, "update-${current.version}.zip")
                Timber.i("Downloading ${current.downloadUrl}")
                downloadFile(current.downloadUrl, zipFile, current.downloadSize) { progress ->
                    _state.value = UpdateState.Downloading(progress, current.version)
                }
                Timber.i("Download complete: ${zipFile.length()} bytes")

                // Extract
                val stagingDir = File(updateDir, "staging")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                Files.createDirectories(stagingDir.toPath())

                _state.value = UpdateState.Downloading(1f, current.version)
                Timber.i("Extracting to ${stagingDir.absolutePath}")
                extractZip(zipFile, stagingDir)
                zipFile.delete()

                // If ZIP has a single top-level directory, use that
                val contents = stagingDir.listFiles() ?: emptyArray()
                val sourceDir = if (contents.size == 1 && contents[0].isDirectory) {
                    Timber.i("ZIP root dir: ${contents[0].name}")
                    contents[0]
                } else {
                    stagingDir
                }

                // Verify we have the exe
                val hasExe = sourceDir.listFiles()?.any {
                    it.name.equals("Metrolist.exe", ignoreCase = true)
                } == true
                if (!hasExe) {
                    Timber.e("No Metrolist.exe in: ${sourceDir.listFiles()?.map { it.name }}")
                    _state.value = UpdateState.Error("Invalid update package — no Metrolist.exe")
                    return@launch
                }

                Timber.i("Update ready: ${sourceDir.absolutePath}")
                _state.value = UpdateState.ReadyToInstall(current.version, sourceDir)
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
            val appDir = findAppDirectory()
            if (appDir == null) {
                val debug = buildString {
                    append("compose.application.resources.dir=${System.getProperty("compose.application.resources.dir")}, ")
                    append("user.dir=${System.getProperty("user.dir")}")
                }
                Timber.e("Cannot find app directory. $debug")
                _state.value = UpdateState.Error("Cannot determine app directory")
                return
            }

            Timber.i("App dir: ${appDir.absolutePath}")
            Timber.i("Source dir: ${current.sourceDir.absolutePath}")

            val updateDir = getUpdateDirectory()
            val pid = ProcessHandle.current().pid()
            val logFile = File(updateDir, "update.log").absolutePath
            val scriptFile = File(updateDir, "metrolist-update.ps1")

            // Write PowerShell update script
            scriptFile.writeText(buildPowerShellScript(
                pid = pid,
                sourcePath = current.sourceDir.absolutePath,
                destPath = appDir.absolutePath,
                exePath = File(appDir, "Metrolist.exe").absolutePath,
                logPath = logFile,
                stagingRoot = File(updateDir, "staging").absolutePath
            ))
            Timber.i("Update script: ${scriptFile.absolutePath}")

            // Launch PowerShell detached — hidden window, no profile (fast startup)
            val processBuilder = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-File", scriptFile.absolutePath
            )
            processBuilder.directory(updateDir)
            processBuilder.redirectErrorStream(true)
            processBuilder.start()

            Timber.i("Update script launched, exiting for update...")
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Timber.e("Failed to apply update: ${e.message}")
            _state.value = UpdateState.Error("Failed to start update: ${e.message}")
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    // ==================== PowerShell script ====================

    private fun buildPowerShellScript(
        pid: Long,
        sourcePath: String,
        destPath: String,
        exePath: String,
        logPath: String,
        stagingRoot: String
    ): String = """
        # Metrolist Desktop Auto-Update Script
        # Generated by AutoUpdater — do not edit
        ${'$'}ErrorActionPreference = "Continue"

        function Log(${'$'}msg) {
            ${'$'}ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            "${'$'}ts  ${'$'}msg" | Out-File -FilePath "$logPath" -Append -Encoding UTF8
        }

        Log "=== Metrolist Update Started ==="
        Log "PID to wait for: $pid"
        Log "Source: $sourcePath"
        Log "Destination: $destPath"

        # Step 1: Wait for old process to exit (max 60 seconds)
        Log "Waiting for process $pid to exit..."
        ${'$'}waited = 0
        while (${'$'}waited -lt 60) {
            ${'$'}proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if (-not ${'$'}proc) {
                Log "Process exited after ${'$'}waited seconds"
                break
            }
            Start-Sleep -Seconds 1
            ${'$'}waited++
        }
        if (${'$'}waited -ge 60) {
            Log "WARNING: Process did not exit after 60s, attempting update anyway"
        }

        # Step 2: Extra wait for file locks to release
        Start-Sleep -Seconds 2
        Log "File lock grace period done"

        # Step 3: Copy files using robocopy (handles retries, long paths, locked files)
        Log "Copying files with robocopy..."
        ${'$'}robocopyArgs = @(
            "$sourcePath",
            "$destPath",
            "/E",           # Recurse into subdirectories
            "/IS",          # Include same files (overwrite)
            "/IT",          # Include tweaked files
            "/R:5",         # Retry 5 times on failure
            "/W:2",         # Wait 2 seconds between retries
            "/NFL",         # No file list in output
            "/NDL",         # No directory list in output
            "/NP",          # No progress percentage
            "/LOG+:$logPath"
        )
        ${'$'}result = Start-Process -FilePath "robocopy.exe" -ArgumentList ${'$'}robocopyArgs -Wait -NoNewWindow -PassThru
        ${'$'}exitCode = ${'$'}result.ExitCode

        # Robocopy exit codes: 0=no change, 1=copied, 2=extras, 3=copied+extras, 4+=errors
        if (${'$'}exitCode -lt 4) {
            Log "Robocopy succeeded (exit code: ${'$'}exitCode)"
        } elseif (${'$'}exitCode -lt 8) {
            Log "WARNING: Robocopy had some issues (exit code: ${'$'}exitCode) — may still work"
        } else {
            Log "ERROR: Robocopy failed (exit code: ${'$'}exitCode)"
            # Fall back to xcopy as last resort
            Log "Falling back to xcopy..."
            & xcopy "$sourcePath\*" "$destPath\" /E /Y /Q 2>&1 | Out-File -FilePath "$logPath" -Append
        }

        # Step 4: Restart the app
        Log "Starting Metrolist..."
        Start-Process -FilePath "$exePath"

        # Step 5: Cleanup staging directory
        Start-Sleep -Seconds 3
        Log "Cleaning up staging directory..."
        Remove-Item -Path "$stagingRoot" -Recurse -Force -ErrorAction SilentlyContinue

        Log "=== Update Complete ==="

        # Self-delete this script
        Remove-Item -Path ${'$'}MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
    """.trimIndent()

    // ==================== Network ====================

    private fun fetchLatestRelease(): GitHubRelease? {
        val connection = URI(API_URL).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Metrolist-Desktop/$CURRENT_VERSION")
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

    private fun downloadFile(url: String, target: File, totalSize: Long, onProgress: (Float) -> Unit) {
        var currentUrl = url
        var redirects = 0

        // Follow redirects manually (GitHub uses 302 for asset downloads)
        val connection: HttpURLConnection
        while (true) {
            val conn = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Metrolist-Desktop/$CURRENT_VERSION")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw Exception("Redirect without Location header (HTTP $code)")
                conn.disconnect()
                currentUrl = location
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
                val buffer = ByteArray(65536) // 64KB buffer for faster download
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
        Timber.i("Downloaded $downloaded bytes → ${target.name}")
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        var count = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // Zip slip protection
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip slip: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outFile.toPath())
                } else {
                    outFile.parentFile?.let { Files.createDirectories(it.toPath()) }
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Timber.i("Extracted $count files")
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
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "Metrolist/updates")
            }
            os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/Metrolist/updates")
            else -> File(System.getProperty("user.home"), ".local/share/metrolist/updates")
        }
    }

    /**
     * Locates the app installation directory containing Metrolist.exe.
     * Tries multiple strategies since the JVM can be launched from different locations.
     */
    private fun findAppDirectory(): File? {
        // Strategy 1: compose.application.resources.dir → go up to find exe
        System.getProperty("compose.application.resources.dir")?.let { resDir ->
            var dir = File(resDir)
            // Walk up at most 3 levels looking for Metrolist.exe
            repeat(3) {
                dir = dir.parentFile ?: return@let
                if (File(dir, "Metrolist.exe").exists()) return dir
            }
            Timber.w("resources.dir=$resDir but no exe found walking up")
        }

        // Strategy 2: user.dir (working directory)
        File(System.getProperty("user.dir")).let { dir ->
            if (File(dir, "Metrolist.exe").exists()) return dir
        }

        // Strategy 3: jar/class location
        try {
            val codeSource = AutoUpdater::class.java.protectionDomain.codeSource
            if (codeSource != null) {
                var dir = File(codeSource.location.toURI())
                repeat(3) {
                    dir = dir.parentFile ?: return@repeat
                    if (File(dir, "Metrolist.exe").exists()) return dir
                }
            }
        } catch (_: Exception) {}

        // Strategy 4: scan common install locations
        val candidates = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { File(it, "Metrolist") },
            File("C:/Program Files/Metrolist"),
            File("C:/Program Files (x86)/Metrolist")
        )
        candidates.forEach { dir ->
            if (File(dir, "Metrolist.exe").exists()) return dir
        }

        return null
    }
}
