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
import java.util.zip.ZipInputStream

/**
 * Auto-updater that checks GitHub Releases for new versions.
 * Downloads and extracts updates, then launches an update script
 * that replaces the app files after the current process exits.
 */
object AutoUpdater {
    const val CURRENT_VERSION = "1.1.0"
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
                if (!isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    _updateState.value = UpdateState.UpToDate
                    return@launch
                }

                // Find the portable ZIP asset
                val zipAsset = release.assets.find { it.name.contains("portable", ignoreCase = true) && it.name.endsWith(".zip") }
                if (zipAsset == null) {
                    _updateState.value = UpdateState.Error("No portable download found for $latestVersion")
                    return@launch
                }

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
                updateDir.mkdirs()
                val zipFile = File(updateDir, "update-${state.version}.zip")

                // Download the ZIP
                downloadFile(state.downloadUrl, zipFile, state.downloadSize) { progress ->
                    _updateState.value = UpdateState.Downloading(progress, state.version)
                }

                // Extract to a staging directory
                val stagingDir = File(updateDir, "staging")
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                _updateState.value = UpdateState.Downloading(1f, state.version)
                extractZip(zipFile, stagingDir)

                // Clean up the zip
                zipFile.delete()

                _updateState.value = UpdateState.ReadyToInstall(state.version, stagingDir)
            } catch (e: Exception) {
                Timber.e("Download failed: ${e.message}")
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
                _updateState.value = UpdateState.Error("Cannot determine app directory")
                return
            }

            val updateScript = File(getUpdateDirectory(), "update.bat")
            val pid = ProcessHandle.current().pid()

            // Write a batch script that waits for our process to die, then copies files
            updateScript.writeText(buildString {
                appendLine("@echo off")
                appendLine("echo Waiting for Metrolist to close...")
                appendLine(":wait")
                appendLine("tasklist /FI \"PID eq $pid\" 2>NUL | find /I \"$pid\" >NUL")
                appendLine("if not errorlevel 1 (")
                appendLine("    timeout /t 1 /nobreak >NUL")
                appendLine("    goto wait")
                appendLine(")")
                appendLine("echo Installing update...")
                appendLine("xcopy /E /Y /Q \"${state.updateDir.absolutePath}\\*\" \"${appDir.absolutePath}\\\"")
                appendLine("echo Update complete. Restarting...")
                appendLine("start \"\" \"${File(appDir, "Metrolist.exe").absolutePath}\"")
                appendLine("rmdir /S /Q \"${state.updateDir.absolutePath}\"")
                appendLine("del \"%~f0\"")
            })

            // Launch the update script detached
            ProcessBuilder("cmd", "/c", "start", "/min", "", updateScript.absolutePath)
                .directory(getUpdateDirectory())
                .start()

            Timber.i("Update script launched, exiting app for update...")

            // Exit the app so the script can replace files
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Timber.e("Failed to apply update: ${e.message}")
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
            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<GitHubRelease>(body)
            } else {
                Timber.w("GitHub API returned ${connection.responseCode}")
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
            if (code in 300..399) {
                currentUrl = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                if (++redirectCount > 5) throw Exception("Too many redirects")
                continue
            }
            break
        }

        if (connection.responseCode != 200) {
            throw Exception("Download failed: HTTP ${connection.responseCode}")
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
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // Prevent zip slip
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
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
        val baseDir = when {
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
        baseDir.mkdirs()
        return baseDir
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
        }

        // Fallback: check if we're running from a directory with Metrolist.exe
        val userDir = File(System.getProperty("user.dir"))
        if (File(userDir, "Metrolist.exe").exists()) {
            return userDir
        }

        return null
    }
}
