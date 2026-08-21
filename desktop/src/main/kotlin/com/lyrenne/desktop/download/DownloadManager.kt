package com.lyrenne.desktop.download

import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertube.strategy.ContentHints
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

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

    /** Max concurrent downloads — matches Android Metrolist's maxParallelDownloads */
    private const val MAX_PARALLEL_DOWNLOADS = 3
    private val downloadSemaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val _downloadQueue = MutableStateFlow<List<SongInfo>>(emptyList())
    val downloadQueue: StateFlow<List<SongInfo>> = _downloadQueue.asStateFlow()

    private var isProcessing = false
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Shared OkHttp client with connection pooling for fast downloads */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadDir: File
        get() {
            val dir = PreferencesManager.getDownloadDirectory()
            if (!dir.exists()) Files.createDirectories(dir.toPath())
            return dir
        }

    /**
     * Generate a download file path using "Artist - Title.m4a" naming.
     * Falls back to songId.m4a if artist/title are unavailable.
     * Handles filename conflicts by appending (2), (3), etc.
     */
    fun getDownloadPath(song: SongInfo): File {
        val baseName = sanitizeFilename("${song.artist} - ${song.title}")
        var candidate = File(downloadDir, "$baseName.m4a")
        var counter = 2
        while (candidate.exists() && !isSameSong(candidate, song.id)) {
            candidate = File(downloadDir, "$baseName ($counter).m4a")
            counter++
        }
        return candidate
    }

    /** Legacy path lookup by songId — checks DB localPath first, then old songId.m4a format. */
    fun getDownloadPathById(songId: String): File? {
        val dbPath: String? = DatabaseHelper.getSongLocalPath(songId)
        if (dbPath != null) {
            val f = File(dbPath)
            if (f.exists()) return f
        }
        // Fallback: old naming scheme
        val legacyFile = File(downloadDir, "$songId.m4a")
        if (legacyFile.exists()) return legacyFile
        return null
    }

    fun isDownloaded(songId: String): Boolean {
        return getDownloadPathById(songId) != null
    }

    internal fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
            .ifBlank { "Unknown" }
    }

    private fun isSameSong(file: File, songId: String): Boolean {
        val dbPath: String = DatabaseHelper.getSongLocalPath(songId) ?: return false
        return File(dbPath).absolutePath == file.absolutePath
    }

    fun queueDownload(song: SongInfo) {
        DatabaseHelper.addToDownloadQueue(song.id)

        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration,
            thumbnailUrl = song.thumbnailUrl,
            albumName = song.album
        )

        _downloadQueue.update { queue ->
            if (queue.none { it.id == song.id }) {
                queue + song
            } else queue
        }

        _activeDownloads.update { downloads ->
            downloads + (song.id to DownloadProgress(
                songId = song.id,
                songTitle = song.title,
                progress = 0,
                status = DownloadStatus.PENDING
            ))
        }

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
        val file = getDownloadPathById(songId)
        if (file != null && file.exists()) {
            file.delete()
        }
        DatabaseHelper.updateSongDownloaded(songId, false, null)
    }

    /**
     * Process the download queue with up to MAX_PARALLEL_DOWNLOADS concurrent downloads.
     * Uses a semaphore to limit concurrency while launching all queued items immediately.
     */
    private fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            // Keep launching downloads as long as there are items in the queue
            while (_downloadQueue.value.isNotEmpty()) {
                // Take all pending songs and launch them concurrently (semaphore limits actual parallelism)
                val pendingSongs = _downloadQueue.value.filter { song ->
                    !downloadJobs.containsKey(song.id)
                }

                if (pendingSongs.isEmpty()) {
                    // All queued songs already have active jobs — wait for one to finish
                    delay(200)
                    continue
                }

                for (song in pendingSongs) {
                    val job = launch {
                        downloadSemaphore.withPermit {
                            _activeDownloads.update { downloads ->
                                downloads + (song.id to DownloadProgress(
                                    songId = song.id,
                                    songTitle = song.title,
                                    progress = 0,
                                    status = DownloadStatus.DOWNLOADING
                                ))
                            }
                            DatabaseHelper.updateDownloadProgress(song.id, 0, "downloading")

                            try {
                                downloadSong(song)
                            } catch (e: CancellationException) {
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
                            } finally {
                                downloadJobs.remove(song.id)
                                _downloadQueue.update { queue ->
                                    queue.filter { it.id != song.id }
                                }
                            }
                        }
                    }
                    downloadJobs[song.id] = job
                }

                // Wait for all current batch jobs to complete before checking for more
                downloadJobs.values.toList().forEach { it.join() }
            }

            isProcessing = false
        }
    }

    /**
     * Resolve stream URL and content length for a video.
     * Walks upstream's client chain and deobfuscates the format URL.
     * Returns the URL with &range= appended and the content length.
     */
    internal data class StreamInfo(val url: String, val contentLength: Long)

    // ponytail: upstream's client chooser, same as DesktopPlayer
    private val fallbackStrategy = ContentAwareFallbackStrategy()

    internal suspend fun getStreamInfo(videoId: String): StreamInfo? {
        for (client in fallbackStrategy.resolveClients(ContentHints())) {
            if (client.requirePoToken) continue
            if (client.loginRequired && YouTube.cookie == null) continue
            try {
                val signatureTimestamp = if (client.useSignatureTimestamp) {
                    withContext(Dispatchers.IO) { NewPipeUtils.getSignatureTimestamp(videoId).getOrNull() }
                } else null
                val playerResponse = YouTube.player(
                    videoId,
                    client = client,
                    signatureTimestamp = signatureTimestamp
                ).getOrNull()
                val format = playerResponse?.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.maxByOrNull { it.bitrate }

                val baseUrl = format?.let {
                    withContext(Dispatchers.IO) { NewPipeUtils.getStreamUrl(it, videoId).getOrNull() }
                }
                if (!baseUrl.isNullOrEmpty()) {
                    val contentLength = format?.contentLength ?: 0L
                    // Append range parameter like Android does to bypass YouTube CDN throttling
                    val url = if (contentLength > 0) {
                        val separator = if ("?" in baseUrl) "&" else "?"
                        "$baseUrl${separator}range=0-$contentLength"
                    } else {
                        baseUrl
                    }
                    Timber.d("Stream resolved via ${client.clientName}: contentLength=$contentLength")
                    return StreamInfo(url, contentLength)
                }
            } catch (e: Exception) {
                Timber.d("Client ${client.clientName} failed for $videoId: ${e.message}")
                continue
            }
        }
        return null
    }

    private suspend fun downloadSong(song: SongInfo) {
        Timber.d("Downloading song: id=${song.id}, artist=${song.artist}, title=${song.title}")

        val streamInfo = getStreamInfo(song.id) ?: throw Exception("Failed to get stream URL")

        val outputFile = getDownloadPath(song)
        val tempFile = File(downloadDir, "${song.id}.tmp")
        Timber.d("Download output: ${outputFile.absolutePath}")

        try {
            val request = Request.Builder()
                .url(streamInfo.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body!!
            // Use content length from format metadata (more reliable) or fall back to HTTP header
            val totalSize = if (streamInfo.contentLength > 0) streamInfo.contentLength else body.contentLength()
            var downloadedSize = 0L
            var lastProgressUpdate = 0

            body.byteStream().buffered(524288).use { input ->
                FileOutputStream(tempFile).use { fos ->
                    val output = fos.buffered(524288) // 512KB write buffer
                    val buffer = ByteArray(131072) // 128KB read chunks (2x previous)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val progress = if (totalSize > 0) {
                            ((downloadedSize * 100) / totalSize).toInt()
                        } else {
                            -1
                        }

                        if (progress != lastProgressUpdate) {
                            lastProgressUpdate = progress
                            _activeDownloads.update { downloads ->
                                downloads + (song.id to DownloadProgress(
                                    songId = song.id,
                                    songTitle = song.title,
                                    progress = progress,
                                    status = DownloadStatus.DOWNLOADING
                                ))
                            }
                            if (progress % 5 == 0) {
                                DatabaseHelper.updateDownloadProgress(song.id, progress, "downloading")
                            }
                        }

                        yield()
                    }
                    output.flush()
                }
            }

            response.close()

            Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

            DatabaseHelper.updateSongDownloaded(song.id, true, outputFile.absolutePath)
            DatabaseHelper.removeFromDownloadQueue(song.id)

            _activeDownloads.update { downloads ->
                downloads + (song.id to DownloadProgress(
                    songId = song.id,
                    songTitle = song.title,
                    progress = 100,
                    status = DownloadStatus.COMPLETED
                ))
            }

            delay(2000)
            _activeDownloads.update { downloads ->
                downloads - song.id
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
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
        _activeDownloads.update { downloads ->
            downloads - song.id
        }
        queueDownload(song)
    }
}
