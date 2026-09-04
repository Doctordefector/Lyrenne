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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadProgress(
    val songId: String,
    val songTitle: String,
    val progress: Int,
    val status: DownloadStatus,
    val error: String? = null,
    /** 1-based attempt number, only meaningful while [status] is RETRYING. */
    val attempt: Int = 1
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    RETRYING,
    COMPLETED,
    ERROR,
    CANCELLED
}

/** A queued song plus where it should land. */
private data class QueuedSong(val song: SongInfo, val subfolder: String?)

object DownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Max concurrent downloads, matching Android Metrolist's maxParallelDownloads */
    private const val MAX_PARALLEL_DOWNLOADS = 3
    private val downloadSemaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)

    /**
     * Attempts per song before it parks as ERROR, and how long to wait between them.
     * A closed lid or a dropped connection recovers on its own; a dead video id does not, and
     * three tries surfaces that within about twenty seconds instead of hammering it.
     */
    private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000)

    /**
     * How often a single song may push a progress update into the flow.
     *
     * Every emission copies the whole map and recomposes the Downloads tab. At three parallel
     * downloads emitting on each 1% change that was several hundred copies of a 700-entry map
     * per second, which is most of the stutter people saw on a large playlist.
     */
    private const val PROGRESS_EMIT_INTERVAL_MS = 250L

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val _downloadQueue = MutableStateFlow<List<SongInfo>>(emptyList())
    val downloadQueue: StateFlow<List<SongInfo>> = _downloadQueue.asStateFlow()

    /** Destination subfolder per queued song id, or absent for a flat download. */
    private val subfolders = ConcurrentHashMap<String, String>()

    private val isProcessing = AtomicBoolean(false)
    private var processingJob: Job? = null
    private val downloadJobs = ConcurrentHashMap<String, Job>()

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

    /** Resolve the folder a song downloads into, creating it if a subfolder was requested. */
    private fun targetDir(subfolder: String?): File {
        val base = downloadDir
        val name = subfolder?.let { sanitizeFilename(it) }?.takeIf { it != "Unknown" } ?: return base
        val dir = File(base, name)
        if (!dir.exists()) Files.createDirectories(dir.toPath())
        return dir
    }

    /**
     * Generate a download file path using "Artist - Title.m4a" naming.
     * Falls back to songId.m4a if artist/title are unavailable.
     * Handles filename conflicts by appending (2), (3), etc.
     */
    fun getDownloadPath(song: SongInfo, subfolder: String? = null): File {
        val dir = targetDir(subfolder)
        // A blank artist would otherwise render as a leading "- Title".
        val raw = if (song.artist.isBlank()) song.title else "${song.artist} - ${song.title}"
        val baseName = sanitizeFilename(raw).ifBlank { song.id }
        var candidate = File(dir, "$baseName.m4a")
        var counter = 2
        while (candidate.exists() && !isSameSong(candidate, song.id)) {
            candidate = File(dir, "$baseName ($counter).m4a")
            counter++
        }
        return candidate
    }

    /** Legacy path lookup by songId: DB localPath first, then the old songId.m4a format. */
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
            .trimEnd('.')
            .take(200)
            .ifBlank { "Unknown" }
    }

    private fun isSameSong(file: File, songId: String): Boolean {
        val dbPath: String = DatabaseHelper.getSongLocalPath(songId) ?: return false
        return File(dbPath).absolutePath == file.absolutePath
    }

    fun queueDownload(song: SongInfo, subfolder: String? = null) {
        queueDownloads(listOf(song), subfolder)
    }

    /**
     * Queue a batch of songs for download.
     *
     * Everything here happens off the caller's thread and inside one transaction. The old
     * version did two autocommit writes and two whole-collection copies per song, from the
     * Compose UI thread, so "Download All" on a 700-track playlist meant 1400 separate SQLite
     * transactions and 1400 O(n) copies before the UI could paint another frame.
     *
     * Already-downloaded songs are skipped here rather than at the call site, because that
     * filter is a database read per song and callers were running it on the UI thread too.
     */
    fun queueDownloads(songs: List<SongInfo>, subfolder: String? = null) {
        if (songs.isEmpty()) return
        scope.launch {
            val queued = _downloadQueue.value.mapTo(mutableSetOf()) { it.id }
            val fresh = songs
                .distinctBy { it.id }
                .filter { it.id !in queued && !isDownloaded(it.id) }
            if (fresh.isEmpty()) return@launch

            fresh.forEach { song ->
                if (subfolder != null) subfolders[song.id] = subfolder else subfolders.remove(song.id)
            }

            DatabaseHelper.transaction {
                fresh.forEach { song ->
                    DatabaseHelper.insertSong(
                        id = song.id,
                        title = song.title,
                        duration = song.duration,
                        thumbnailUrl = song.thumbnailUrl,
                        albumName = song.album
                    )
                    DatabaseHelper.addToDownloadQueue(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        album = song.album,
                        durationSec = song.duration,
                        subfolder = subfolder
                    )
                }
            }

            _downloadQueue.update { it + fresh }
            _activeDownloads.update { downloads ->
                downloads + fresh.associate { song ->
                    song.id to DownloadProgress(song.id, song.title, 0, DownloadStatus.PENDING)
                }
            }

            processQueue()
        }
    }

    /**
     * Re-queue anything that did not finish before the app last closed.
     *
     * Called once from startup. Rows still marked 'downloading' were interrupted mid-transfer
     * (a kill, a closed lid) and go back to pending; 'error' rows are included because a run
     * that died on a network drop leaves exactly those behind. Partial `.part` files are left
     * on disk by the failure path, so this resumes rather than restarting the transfer.
     */
    fun restoreQueue() {
        scope.launch {
            delay(5_000)
            try {
                DatabaseHelper.resetStuckDownloads()
                val rows = DatabaseHelper.getUnfinishedDownloads()
                    .filterNot { isDownloaded(it.songId) }
                if (rows.isEmpty()) return@launch

                val songs = rows.map { row ->
                    SongInfo(
                        id = row.songId,
                        title = row.title.ifBlank { row.songId },
                        artist = row.artist,
                        thumbnailUrl = row.thumbnailUrl,
                        album = row.album,
                        duration = row.durationSec.toInt()
                    )
                }
                rows.forEach { row -> row.subfolder?.let { subfolders[row.songId] = it } }

                _downloadQueue.update { existing ->
                    val known = existing.mapTo(mutableSetOf()) { it.id }
                    existing + songs.filter { it.id !in known }
                }
                _activeDownloads.update { downloads ->
                    downloads + songs.associate { song ->
                        song.id to DownloadProgress(song.id, song.title, 0, DownloadStatus.PENDING)
                    }
                }
                Timber.d("Restored ${songs.size} unfinished downloads")
                processQueue()
            } catch (e: Exception) {
                Timber.e("Failed to restore download queue: ${e.message}")
            }
        }
    }

    fun cancelDownload(songId: String) {
        downloadJobs.remove(songId)?.cancel()
        subfolders.remove(songId)

        scope.launch { DatabaseHelper.removeFromDownloadQueue(songId) }

        _downloadQueue.update { queue -> queue.filter { it.id != songId } }
        _activeDownloads.update { downloads -> downloads - songId }
    }

    /**
     * Stop everything in flight and empty the queue.
     *
     * The processing loop is cancelled too. Cancelling only the per-song jobs left the loop
     * spinning, and clearing the processing flag under it meant the next queued song started a
     * second one.
     */
    fun cancelAllDownloads() {
        processingJob?.cancel()
        processingJob = null
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        subfolders.clear()

        val queued = _downloadQueue.value.map { it.id }
        _downloadQueue.value = emptyList()
        _activeDownloads.value = emptyMap()
        isProcessing.set(false)

        scope.launch {
            DatabaseHelper.transaction {
                queued.forEach { DatabaseHelper.removeFromDownloadQueue(it) }
            }
        }
    }

    /** Requeue every song currently parked as ERROR. */
    fun retryAllFailed() {
        val failed = _activeDownloads.value.values
            .filter { it.status == DownloadStatus.ERROR }
            .map { it.songId }
        if (failed.isEmpty()) return

        _activeDownloads.update { downloads ->
            downloads + failed.associateWith { id ->
                downloads[id]!!.copy(progress = 0, status = DownloadStatus.PENDING, error = null)
            }
        }
        scope.launch {
            DatabaseHelper.retryFailedDownloads()
            val known = _downloadQueue.value.mapTo(mutableSetOf()) { it.id }
            val rows = DatabaseHelper.getUnfinishedDownloads()
                .filter { it.songId in failed && it.songId !in known }
            rows.forEach { row -> row.subfolder?.let { subfolders[row.songId] = it } }
            val songs = rows
                .map { row ->
                    SongInfo(
                        id = row.songId,
                        title = row.title.ifBlank { row.songId },
                        artist = row.artist,
                        thumbnailUrl = row.thumbnailUrl,
                        album = row.album,
                        duration = row.durationSec.toInt()
                    )
                }
            if (songs.isNotEmpty()) {
                _downloadQueue.update { it + songs }
                processQueue()
            }
        }
    }

    /** Drop completed and failed entries from the list. Files on disk are not touched. */
    fun clearFinished() {
        _activeDownloads.update { downloads ->
            downloads.filterValues {
                it.status != DownloadStatus.COMPLETED &&
                    it.status != DownloadStatus.ERROR &&
                    it.status != DownloadStatus.CANCELLED
            }
        }
        scope.launch { DatabaseHelper.clearFinishedDownloads() }
    }

    fun deleteDownload(songId: String) {
        val file = getDownloadPathById(songId)
        if (file != null && file.exists()) {
            val parent = file.parentFile
            file.delete()
            pruneEmptySubfolder(parent)
        }
        DatabaseHelper.updateSongDownloaded(songId, false, null)
    }

    /**
     * Delete every downloaded file and clear the downloaded flag on all of them.
     * Destructive, so the confirmation lives at the call site.
     */
    fun deleteAllDownloads() {
        scope.launch {
            val songs = DatabaseHelper.getDownloadedSongsOnce()
            val touched = mutableSetOf<File>()
            songs.forEach { song ->
                val path = song.localPath ?: return@forEach
                val file = File(path)
                if (file.exists()) {
                    file.parentFile?.let { touched.add(it) }
                    file.delete()
                }
            }
            DatabaseHelper.transaction {
                songs.forEach { DatabaseHelper.updateSongDownloaded(it.id, false, null) }
            }
            touched.forEach { pruneEmptySubfolder(it) }
        }
    }

    /** Remove a per-playlist folder once its last file is gone. Never touches the root. */
    private fun pruneEmptySubfolder(dir: File?) {
        if (dir == null) return
        if (dir.absolutePath == downloadDir.absolutePath) return
        if (dir.parentFile?.absolutePath != downloadDir.absolutePath) return
        if (dir.list()?.isEmpty() == true) dir.delete()
    }

    /**
     * Process the download queue with up to MAX_PARALLEL_DOWNLOADS concurrent downloads.
     * Uses a semaphore to limit concurrency while launching all queued items immediately.
     */
    private fun processQueue() {
        if (!isProcessing.compareAndSet(false, true)) return

        processingJob = scope.launch {
            try {
                while (_downloadQueue.value.isNotEmpty()) {
                    val pendingSongs = _downloadQueue.value.filter { song ->
                        !downloadJobs.containsKey(song.id)
                    }

                    if (pendingSongs.isEmpty()) {
                        // All queued songs already have active jobs, so wait for one to finish
                        delay(200)
                        continue
                    }

                    for (song in pendingSongs) {
                        val job = launch(start = CoroutineStart.LAZY) {
                            runDownload(QueuedSong(song, subfolders[song.id]))
                        }
                        downloadJobs[song.id] = job
                        job.start()
                    }

                    // Wait for all current batch jobs to complete before checking for more
                    downloadJobs.values.toList().forEach { it.join() }
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /**
     * One song, with retries. Each attempt re-resolves the stream URL, because a transient
     * failure is usually the connection but an expired URL looks identical from here.
     */
    private suspend fun runDownload(queued: QueuedSong) {
        val song = queued.song
        downloadSemaphore.withPermit {
            try {
                var lastError: String? = null
                for (attempt in 1..RETRY_DELAYS_MS.size) {
                    emitProgress(song, 0, DownloadStatus.DOWNLOADING)
                    DatabaseHelper.updateDownloadProgress(song.id, 0, "downloading")
                    try {
                        downloadSong(song, queued.subfolder)
                        return@withPermit
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message ?: "Unknown error"
                        Timber.w("Download attempt $attempt/${RETRY_DELAYS_MS.size} failed for ${song.id}: $lastError")
                        if (attempt == RETRY_DELAYS_MS.size) break
                        updateIfTracked(song.id) {
                            it.copy(
                                progress = 0,
                                status = DownloadStatus.RETRYING,
                                error = lastError,
                                attempt = attempt + 1
                            )
                        }
                        delay(RETRY_DELAYS_MS[attempt - 1])
                    }
                }
                handleDownloadError(song, lastError ?: "Unknown error")
            } catch (e: CancellationException) {
                // Cancelling already took the entry out of the list, and putting a CANCELLED
                // card back is the one thing the user just asked not to see. Nothing to do.
            } finally {
                downloadJobs.remove(song.id)
                _downloadQueue.update { queue -> queue.filter { it.id != song.id } }
            }
        }
    }

    private fun emitProgress(song: SongInfo, progress: Int, status: DownloadStatus) {
        updateIfTracked(song.id) { it.copy(progress = progress, status = status, error = null) }
    }

    /**
     * Update a song's entry only while it is still in the list.
     *
     * A cancel removes the entry, but the job it cancelled keeps running until the coroutine
     * actually unwinds, and any plain `downloads + (id to ...)` in that window puts the card
     * back. Every write for an in-flight song goes through here so a cancel stays cancelled;
     * the only unconditional inserts are the ones that queue a song in the first place.
     */
    private fun updateIfTracked(songId: String, transform: (DownloadProgress) -> DownloadProgress) {
        _activeDownloads.update { downloads ->
            val current = downloads[songId] ?: return@update downloads
            downloads + (songId to transform(current))
        }
    }

    /**
     * Resolve stream URL and content length for a video.
     * Walks upstream's client chain and deobfuscates the format URL.
     * Returns the URL with &range= appended and the content length.
     */
    internal data class StreamInfo(val url: String, val contentLength: Long, val baseUrl: String)

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
                    Timber.d("Stream resolved via ${client.clientName}: contentLength=$contentLength")
                    return StreamInfo(
                        url = rangedUrl(baseUrl, 0, contentLength),
                        contentLength = contentLength,
                        baseUrl = baseUrl
                    )
                }
            } catch (e: Exception) {
                Timber.d("Client ${client.clientName} failed for $videoId: ${e.message}")
                continue
            }
        }
        return null
    }

    /** Append the range parameter Android uses to bypass YouTube CDN throttling. */
    internal fun rangedUrl(baseUrl: String, from: Long, contentLength: Long): String {
        if (contentLength <= 0) return baseUrl
        val separator = if ("?" in baseUrl) "&" else "?"
        return "$baseUrl${separator}range=$from-$contentLength"
    }

    /**
     * The partial file for a transfer, named after the exact byte count it is a prefix of.
     *
     * Resuming against a different encoding of the same video produces a corrupt m4a that
     * nothing detects until playback. Putting the length in the name means a partial only ever
     * matches the stream it came from: if YouTube hands back a different format next time, the
     * file for it simply does not exist and the download starts clean.
     */
    internal fun partFile(dir: File, songId: String, contentLength: Long): File =
        File(dir, if (contentLength > 0) "$songId-$contentLength.part" else "$songId.part")

    /** Drop partials for this song that belong to a different (or legacy) encoding. */
    internal fun pruneStalePartials(dir: File, songId: String, keep: File) {
        dir.listFiles { f -> f.name.startsWith("$songId") && (f.name.endsWith(".part") || f.name.endsWith(".tmp")) }
            ?.forEach { if (it.absolutePath != keep.absolutePath) it.delete() }
    }

    private suspend fun downloadSong(song: SongInfo, subfolder: String?) {
        Timber.d("Downloading song: id=${song.id}, artist=${song.artist}, title=${song.title}")

        val streamInfo = getStreamInfo(song.id) ?: throw Exception("Failed to get stream URL")

        val outputFile = getDownloadPath(song, subfolder)
        val dir = outputFile.parentFile
        val tempFile = partFile(dir, song.id, streamInfo.contentLength)
        pruneStalePartials(dir, song.id, tempFile)

        // Resume from whatever survived the last attempt. A partial larger than the stream is
        // a partial for something else, so start over rather than trust it.
        var downloadedSize = if (tempFile.exists()) tempFile.length() else 0L
        if (streamInfo.contentLength > 0 && downloadedSize >= streamInfo.contentLength) {
            downloadedSize = 0L
            tempFile.delete()
        }
        val resuming = downloadedSize > 0
        if (resuming) Timber.d("Resuming ${song.id} at $downloadedSize bytes")

        val url = if (resuming) {
            rangedUrl(streamInfo.baseUrl, downloadedSize, streamInfo.contentLength)
        } else {
            streamInfo.url
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body!!
        // Use content length from format metadata (more reliable) or fall back to HTTP header
        val totalSize = if (streamInfo.contentLength > 0) {
            streamInfo.contentLength
        } else {
            body.contentLength().let { if (it > 0) it + downloadedSize else it }
        }
        var lastProgressUpdate = -1
        var lastEmitMs = 0L

        // The temp file is deliberately NOT deleted when this throws. It is the resume point,
        // and the caller retries; a genuinely dead download is swept by pruneStalePartials on
        // the next attempt, or by cancelDownload.
        response.use {
            body.byteStream().buffered(524288).use { input ->
                FileOutputStream(tempFile, resuming).use { fos ->
                    val output = fos.buffered(524288) // 512KB write buffer
                    val buffer = ByteArray(131072) // 128KB read chunks (2x previous)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val progress = if (totalSize > 0) {
                            ((downloadedSize * 100) / totalSize).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        val now = System.currentTimeMillis()
                        if (progress != lastProgressUpdate && now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                            lastProgressUpdate = progress
                            lastEmitMs = now
                            emitProgress(song, progress, DownloadStatus.DOWNLOADING)
                            if (progress % 5 == 0) {
                                DatabaseHelper.updateDownloadProgress(song.id, progress, "downloading")
                            }
                        }

                        yield()
                    }
                    output.flush()
                    // Flush the buffered stream before the fd closes, otherwise a partial keeps
                    // up to 512KB of bytes that the resume offset thinks are already on disk.
                    fos.fd.sync()
                }
            }
        }

        if (totalSize > 0 && downloadedSize < totalSize) {
            throw Exception("Incomplete download: $downloadedSize of $totalSize bytes")
        }

        Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        DatabaseHelper.updateSongDownloaded(song.id, true, outputFile.absolutePath)
        DatabaseHelper.removeFromDownloadQueue(song.id)
        subfolders.remove(song.id)

        emitProgress(song, 100, DownloadStatus.COMPLETED)

        delay(2000)
        _activeDownloads.update { downloads -> downloads - song.id }
    }

    private fun handleDownloadError(song: SongInfo, error: String) {
        updateIfTracked(song.id) {
            it.copy(progress = 0, status = DownloadStatus.ERROR, error = error)
        }
        DatabaseHelper.updateDownloadError(song.id, error)
    }

    /**
     * Retry one parked download.
     *
     * Takes an id rather than a SongInfo: the queue row already holds the real title, artist and
     * destination folder, and rebuilding those from whatever the card was displaying lost the
     * artist on anything restored from a previous run, renaming the file on the way out.
     */
    fun retryDownload(songId: String) {
        scope.launch {
            val row = DatabaseHelper.getUnfinishedDownloads().firstOrNull { it.songId == songId }
            val song = if (row != null) {
                row.subfolder?.let { subfolders[songId] = it }
                SongInfo(
                    id = row.songId,
                    title = row.title.ifBlank { row.songId },
                    artist = row.artist,
                    thumbnailUrl = row.thumbnailUrl,
                    album = row.album,
                    duration = row.durationSec.toInt()
                )
            } else {
                val known = _activeDownloads.value[songId] ?: return@launch
                SongInfo(id = songId, title = known.songTitle, artist = "", thumbnailUrl = null)
            }
            val subfolder = subfolders[songId]
            _activeDownloads.update { downloads -> downloads - songId }
            DatabaseHelper.removeFromDownloadQueue(songId)
            queueDownload(song, subfolder)
        }
    }
}
