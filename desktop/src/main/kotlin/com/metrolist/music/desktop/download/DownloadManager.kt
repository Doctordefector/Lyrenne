package com.metrolist.music.desktop.download

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val songId: String,
    val songTitle: String,
    val progress: Int,
    val status: DownloadStatus,
    val error: String? = null
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    CANCELLED
}

object DownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val _downloadQueue = MutableStateFlow<List<SongInfo>>(emptyList())
    val downloadQueue: StateFlow<List<SongInfo>> = _downloadQueue.asStateFlow()

    private var isProcessing = false
    private val downloadJobs = mutableMapOf<String, Job>()

    private val downloadDir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val baseDir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "Metrolist/Downloads")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist/Downloads")
            }
            else -> {
                File(System.getProperty("user.home"), ".config/metrolist/downloads")
            }
        }
        baseDir.also { it.mkdirs() }
    }

    fun getDownloadPath(songId: String): File {
        return File(downloadDir, "$songId.m4a")
    }

    fun isDownloaded(songId: String): Boolean {
        return getDownloadPath(songId).exists()
    }

    fun queueDownload(song: SongInfo) {
        // Add to database queue
        DatabaseHelper.addToDownloadQueue(song.id)

        // Also save song info to database if not exists
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration,
            thumbnailUrl = song.thumbnailUrl,
            albumName = song.album
        )

        // Add to in-memory queue
        _downloadQueue.update { queue ->
            if (queue.none { it.id == song.id }) {
                queue + song
            } else queue
        }

        // Update progress state
        _activeDownloads.update { downloads ->
            downloads + (song.id to DownloadProgress(
                songId = song.id,
                songTitle = song.title,
                progress = 0,
                status = DownloadStatus.PENDING
            ))
        }

        // Start processing if not already
        if (!isProcessing) {
            processQueue()
        }
    }

    fun queueDownloads(songs: List<SongInfo>) {
        songs.forEach { queueDownload(it) }
    }

    fun cancelDownload(songId: String) {
        downloadJobs[songId]?.cancel()
        downloadJobs.remove(songId)

        DatabaseHelper.removeFromDownloadQueue(songId)

        _downloadQueue.update { queue ->
            queue.filter { it.id != songId }
        }

        _activeDownloads.update { downloads ->
            downloads - songId
        }
    }

    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()

        _downloadQueue.value.forEach { song ->
            DatabaseHelper.removeFromDownloadQueue(song.id)
        }

        _downloadQueue.value = emptyList()
        _activeDownloads.value = emptyMap()
        isProcessing = false
    }

    fun deleteDownload(songId: String) {
        val file = getDownloadPath(songId)
        if (file.exists()) {
            file.delete()
        }
        DatabaseHelper.updateSongDownloaded(songId, false, null)
    }

    private fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            while (_downloadQueue.value.isNotEmpty()) {
                val song = _downloadQueue.value.firstOrNull() ?: break

                // Update status to downloading
                _activeDownloads.update { downloads ->
                    downloads + (song.id to DownloadProgress(
                        songId = song.id,
                        songTitle = song.title,
                        progress = 0,
                        status = DownloadStatus.DOWNLOADING
                    ))
                }
                DatabaseHelper.updateDownloadProgress(song.id, 0, "downloading")

                val job = launch {
                    try {
                        downloadSong(song)
                    } catch (e: CancellationException) {
                        // Download was cancelled
                        _activeDownloads.update { downloads ->
                            downloads + (song.id to DownloadProgress(
                                songId = song.id,
                                songTitle = song.title,
                                progress = 0,
                                status = DownloadStatus.CANCELLED
                            ))
                        }
                    } catch (e: Exception) {
                        handleDownloadError(song, e.message ?: "Unknown error")
                    }
                }

                downloadJobs[song.id] = job
                job.join()
                downloadJobs.remove(song.id)

                // Remove from queue
                _downloadQueue.update { queue ->
                    queue.filter { it.id != song.id }
                }
            }

            isProcessing = false
        }
    }

    private suspend fun downloadSong(song: SongInfo) {
        // Get stream URL
        val streamUrl = getStreamUrl(song.id) ?: throw Exception("Failed to get stream URL")

        val outputFile = getDownloadPath(song.id)
        val tempFile = File(downloadDir, "${song.id}.tmp")

        try {
            val url = URL(streamUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val totalSize = connection.contentLengthLong
            var downloadedSize = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val progress = if (totalSize > 0) {
                            ((downloadedSize * 100) / totalSize).toInt()
                        } else {
                            -1
                        }

                        // Update progress
                        _activeDownloads.update { downloads ->
                            downloads + (song.id to DownloadProgress(
                                songId = song.id,
                                songTitle = song.title,
                                progress = progress,
                                status = DownloadStatus.DOWNLOADING
                            ))
                        }
                        DatabaseHelper.updateDownloadProgress(song.id, progress, "downloading")

                        // Check for cancellation
                        yield()
                    }
                }
            }

            // Move temp file to final location
            tempFile.renameTo(outputFile)

            // Update database
            DatabaseHelper.updateSongDownloaded(song.id, true, outputFile.absolutePath)
            DatabaseHelper.removeFromDownloadQueue(song.id)

            // Update status
            _activeDownloads.update { downloads ->
                downloads + (song.id to DownloadProgress(
                    songId = song.id,
                    songTitle = song.title,
                    progress = 100,
                    status = DownloadStatus.COMPLETED
                ))
            }

            // Remove from active downloads after a delay
            delay(2000)
            _activeDownloads.update { downloads ->
                downloads - song.id
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private suspend fun getStreamUrl(videoId: String): String? {
        val clients = listOf(
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.IOS,
            YouTubeClient.WEB_REMIX
        )

        for (client in clients) {
            try {
                val playerResponse = YouTube.player(videoId, client = client).getOrNull()
                val format = playerResponse?.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.maxByOrNull { it.bitrate ?: 0 }

                if (format?.url != null) {
                    return format.url
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private fun handleDownloadError(song: SongInfo, error: String) {
        _activeDownloads.update { downloads ->
            downloads + (song.id to DownloadProgress(
                songId = song.id,
                songTitle = song.title,
                progress = 0,
                status = DownloadStatus.ERROR,
                error = error
            ))
        }
        DatabaseHelper.updateDownloadError(song.id, error)
    }

    fun retryDownload(song: SongInfo) {
        // Remove from error state
        _activeDownloads.update { downloads ->
            downloads - song.id
        }

        // Re-queue
        queueDownload(song)
    }
}
