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
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Auto-updater that checks GitHub Releases for new versions.
 * Downloads and extracts updates, then launches an update script
 * that replaces the app files after the current process exits.
 */
object AutoUpdater {
    const val CURRENT_VERSION = "1.9.0"
    private const val GITHUB_OWNER = "Doctordefector"
    private const val GITHUB_REPO = "Metrolist-Desktop"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

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
        data class ReadyToInstall(val version: String, val updateDir: File) : UpdateState()
        data class Error(val message: String) : UpdateState()
        data object UpToDate : UpdateState()
    }

    fun checkForUpdates() {
        scope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    _updateState.value = UpdateState.Error("Could not reach GitHub")
                    return@launch
                }

                if (release.draft || release.prerelease) {
                    _updateState.value = UpdateState.UpToDate
                    return@launch
                }

                val latestVersion = release.tag_name.removePrefix("v")
                Timber.i("Update check: current=$CURRENT_VERSION, latest=$latestVersion")

                if (!isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    _updateState.value = UpdateState.UpToDate
                    return@launch
                }

                // Find the portable ZIP asset
                val zipAsset = release.assets.find {
                    it.name.contains("portable", ignoreCase = true) && it.name.endsWith(".zip")
                }
                if (zipAsset == null) {
                    Timber.w("No portable ZIP found in ${release.assets.map { it.name }}")
                    _updateState.value = UpdateState.Error("No portable download found for $latestVersion")
                    return@launch
                }

                Timber.i("Update available: $latestVersion, asset: ${zipAsset.name} (${zipAsset.size} bytes)")
                _updateState.value = UpdateState.UpdateAvailable(
                    version = latestVersion,
                    releaseNotes = release.body,
                    downloadUrl = zipAsset.browser_download_url,
                    downloadSize = zipAsset.size,
                    releaseUrl = release.html_url
                )
            } catch (e: Exception) {
                Timber.e("Update check failed: ${e.message}")
                _updateState.value = UpdateState.Error("Update check failed: ${e.message}")
            }
        }
    }

    fun downloadAndInstall() {
        val state = _updateState.value
        if (state !is UpdateState.UpdateAvailable) return

        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0f, state.version)

                val updateDir = getUpdateDirectory()
                Files.createDirectories(updateDir.toPath())
                val zipFile = File(updateDir, "update-${state.version}.zip")

                Timber.i("Downloading ${state.downloadUrl} to ${zipFile.absolutePath}")

                // Download the ZIP
                downloadFile(state.downloadUrl, zipFile, state.downloadSize) { progress ->
                    _updateState.value = UpdateState.Downloading(progress, state.version)
                }

                Timber.i("Download complete: ${zipFile.length()} bytes")

                // Extract to a staging directory
                val stagingDir = File(updateDir, "staging")
                if (stagingDir.exists()) {
                    Timber.i("Cleaning old staging dir...")
                    stagingDir.deleteRecursively()
                }
                Files.createDirectories(stagingDir.toPath())

                _updateState.value = UpdateState.Downloading(1f, state.version)
                Timber.i("Extracting to ${stagingDir.absolutePath}...")
                extractZip(zipFile, stagingDir)

                // Clean up the zip
                zipFile.delete()

                // If the ZIP contained a single top-level directory, use that as the source
                val contents = stagingDir.listFiles() ?: emptyArray()
                val sourceDir = if (contents.size == 1 && contents[0].isDirectory) {
                    Timber.i("ZIP has top-level dir: ${contents[0].name}")
                    contents[0]
                } else {
                    Timber.i("ZIP has ${contents.size} top-level entries")
                    stagingDir
                }

                // Verify the extracted content has an exe
                val hasExe = sourceDir.listFiles()?.any { it.name.equals("Metrolist.exe", ignoreCase = true) } == true
                if (!hasExe) {
                    Timber.e("Extracted content has no Metrolist.exe! Contents: ${sourceDir.listFiles()?.map { it.name }}")
                    _updateState.value = UpdateState.Error("Invalid update package (no Metrolist.exe found)")
                    return@launch
                }

                Timber.i("Update extracted successfully, ready to install from ${sourceDir.absolutePath}")
                _updateState.value = UpdateState.ReadyToInstall(state.version, sourceDir)
            } catch (e: Exception) {
                Timber.e("Download/extract failed: ${e.message}", e)
                _updateState.value = UpdateState.Error("Download failed: ${e.message}")
            }
        }
    }

    /**
     * Creates and runs an update script that:
     * 1. Waits for the current process to exit
     * 2. Copies new files over old ones
     * 3. Restarts the app
     */
    fun applyUpdate() {
        val state = _updateState.value
        if (state !is UpdateState.ReadyToInstall) return

        try {
            val appDir = getAppDirectory() ?: run {
                Timber.e("Cannot determine app directory. resources.dir=${System.getProperty("compose.application.resources.dir")}, user.dir=${System.getProperty("user.dir")}")
                _updateState.value = UpdateState.Error("Cannot determine app directory")
                return
            }

            Timber.i("App directory: ${appDir.absolutePath}")
            Timber.i("Update source: ${state.updateDir.absolutePath}")

            val updateDir = getUpdateDirectory()
            val updateScript = File(updateDir, "update.bat")
            val pid = ProcessHandle.current().pid()
            val exePath = File(appDir, "Metrolist.exe").absolutePath
            val sourcePath = state.updateDir.absolutePath
            val destPath = appDir.absolutePath
            val logFile = File(updateDir, "update.log").absolutePath

            // Write a batch script that waits for our process to die, then copies files
            updateScript.writeText(buildString {
                appendLine("@echo off")
                appendLine("echo Metrolist Update Script > \"$logFile\"")
                appendLine("echo Waiting for PID $pid to exit... >> \"$logFile\"")
                appendLine(":wait")
                appendLine("tasklist /FI \"PID eq $pid\" 2>NUL | find /I \"$pid\" >NUL")
                appendLine("if not errorlevel 1 (")
                appendLine("    timeout /t 1 /nobreak >NUL")
                appendLine("    goto wait")
                appendLine(")")
                appendLine("echo Process exited, installing update... >> \"$logFile\"")
                appendLine("timeout /t 2 /nobreak >NUL")
                appendLine("echo Copying from \"$sourcePath\" to \"$destPath\" >> \"$logFile\"")
                appendLine("xcopy /E /Y /Q \"$sourcePath\\*\" \"$destPath\\\" >> \"$logFile\" 2>&1")
                appendLine("if errorlevel 1 (")
                appendLine("    echo ERROR: xcopy failed with errorlevel %errorlevel% >> \"$logFile\"")
                appendLine("    echo Update failed. Starting old version... >> \"$logFile\"")
                appendLine(")")
                appendLine("echo Starting Metrolist... >> \"$logFile\"")
                appendLine("start \"\" \"$exePath\"")
                appendLine("echo Cleaning up... >> \"$logFile\"")
                // Clean the entire staging directory, not just the source subfolder
                appendLine("rmdir /S /Q \"${File(updateDir, "staging").absolutePath}\" >> \"$logFile\" 2>&1")
                appendLine("echo Done. >> \"$logFile\"")
                appendLine("del \"%~f0\"")
            })

            Timber.i("Update script written to ${updateScript.absolutePath}")

            // Launch the update script detached
            ProcessBuilder("cmd", "/c", "start", "/min", "MetrolistUpdate", "\"${updateScript.absolutePath}\"")
                .directory(updateDir)
                .start()

            Timber.i("Update script launched, exiting app for update...")

            // Exit the app so the script can replace files
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Timber.e("Failed to apply update: ${e.message}", e)
            _updateState.value = UpdateState.Error("Failed to apply update: ${e.message}")
        }
    }

    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }

    private fun fetchLatestRelease(): GitHubRelease? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Metrolist-Desktop/$CURRENT_VERSION")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            val code = connection.responseCode
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<GitHubRelease>(body)
            } else {
                Timber.w("GitHub API returned $code")
                // Read error body for debugging
                val errorBody = try { connection.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Timber.w("GitHub error response: ${errorBody?.take(200)}")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File, totalSize: Long, onProgress: (Float) -> Unit) {
        // Follow redirects (GitHub uses 302 for asset downloads)
        var currentUrl = url
        var connection: HttpURLConnection
        var redirectCount = 0

        while (true) {
            connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Metrolist-Desktop/$CURRENT_VERSION")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            val code = connection.responseCode
            Timber.d("HTTP $code for ${currentUrl.take(80)}...")

            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) {
                    throw Exception("Redirect with no Location header (HTTP $code)")
                }
                currentUrl = location
                if (++redirectCount > 5) throw Exception("Too many redirects")
                continue
            }
            break
        }

        if (connection.responseCode != 200) {
            val errorBody = try { connection.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
            throw Exception("Download failed: HTTP ${connection.responseCode} ${errorBody ?: ""}")
        }

        val actualSize = if (totalSize > 0) totalSize else connection.contentLengthLong
        var downloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (actualSize > 0) {
                        onProgress((downloaded.toFloat() / actualSize).coerceIn(0f, 1f))
                    }
                }
            }
        }
        connection.disconnect()
        Timber.i("Downloaded $downloaded bytes to ${target.name}")
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        var fileCount = 0
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // Prevent zip slip
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outFile.toPath())
                } else {
                    outFile.parentFile?.let { Files.createDirectories(it.toPath()) }
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    fileCount++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Timber.i("Extracted $fileCount files to ${targetDir.absolutePath}")
    }

    /**
     * Compares semantic versions. Returns true if [latest] is newer than [current].
     */
    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
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
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist/updates")
            }
            else -> {
                File(System.getProperty("user.home"), ".local/share/metrolist/updates")
            }
        }
    }

    /**
     * Tries to find the app installation directory.
     * For portable: the directory containing Metrolist.exe
     * For MSI install: typically C:\Program Files\Metrolist
     */
    private fun getAppDirectory(): File? {
        // compose.application.resources.dir points to <app>/app/resources
        // Go up two levels to get to the app root
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val appDir = File(resourcesDir).parentFile?.parentFile
            if (appDir != null && File(appDir, "Metrolist.exe").exists()) {
                return appDir
            }
            Timber.w("resources.dir=$resourcesDir but no Metrolist.exe at ${appDir?.absolutePath}")
        }

        // Fallback: check if we're running from a directory with Metrolist.exe
        val userDir = File(System.getProperty("user.dir"))
        if (File(userDir, "Metrolist.exe").exists()) {
            return userDir
        }

        // Fallback 2: check the jar's location
        try {
            val jarDir = File(AutoUpdater::class.java.protectionDomain.codeSource.location.toURI()).parentFile
            val appDir = jarDir.parentFile // go up from /app/ to root
            if (appDir != null && File(appDir, "Metrolist.exe").exists()) {
                return appDir
            }
        } catch (_: Exception) {}

        return null
    }
}
