package com.metrolist.music.desktop.download

import com.metrolist.music.desktop.AppPaths
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Exports songs to a plain folder of loudness-normalized MP3s — the format car stereos,
 * USB sticks and CD players actually accept. Also normalizes a folder of existing files.
 *
 * Needs ffmpeg: looked up next to the app first (portable), then on PATH.
 */
object CarExport {

    sealed class ExportState {
        data object Idle : ExportState()
        data class Running(val done: Int, val total: Int, val current: String) : ExportState()
        data class Finished(val ok: Int, val failed: Int, val outDir: File) : ExportState()
        data class Failed(val message: String) : ExportState()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "webm")

    /** Concurrent ffmpeg processes. Leave a core free so the UI and playback stay smooth. */
    private val exportSemaphore = Semaphore(
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
    )

    private val ffmpegExe =
        if ("win" in System.getProperty("os.name").orEmpty().lowercase()) "ffmpeg.exe" else "ffmpeg"

    /**
     * Locate ffmpeg. The packaged app ships its own copy under the Compose resources dir, the
     * same place bundled VLC lives, so export works with no user setup. The remaining paths
     * cover running from source and anyone who would rather use their own build.
     */
    private fun findFfmpeg(): File? {
        val candidates = listOfNotNull(
            // Packaged distributable — resources/ffmpeg/ffmpeg.exe
            System.getProperty("compose.application.resources.dir")
                ?.let { File(it, "ffmpeg/$ffmpegExe") },
            // Running from gradle / IDE
            File("desktop/resources/windows-x64/ffmpeg/$ffmpegExe"),
            File("resources/windows-x64/ffmpeg/$ffmpegExe"),
            // User-supplied, next to the exe
            File(AppPaths.appDir, ffmpegExe),
            File(AppPaths.appDir, "ffmpeg/$ffmpegExe"),
            File(AppPaths.appDir, "ffmpeg/bin/$ffmpegExe")
        )
        candidates.firstOrNull { it.isFile }?.let { return it }

        return System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it.trim(), ffmpegExe) }
            ?.firstOrNull { it.isFile }
    }

    private const val FFMPEG_MISSING =
        "ffmpeg not found. It ships with Lyrenne, so this build may be incomplete — " +
            "reinstall, or put ffmpeg.exe next to Lyrenne.exe."

    /**
     * Loudness-normalize to a consistent level and guarantee a real two-channel stereo file,
     * so quiet/loud tracks and mono uploads all sound right on a left+right car system.
     *
     * `aformat` alone already duplicates a 1-channel source into both speakers. Dual-mono mode
     * additionally folds L and R together, which fixes tracks mastered onto a single side.
     */
    private fun filterChain(): String {
        val dualMono = PreferencesManager.preferences.value.carExportDualMono
        val channels = if (dualMono) {
            "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1"
        } else {
            "aformat=channel_layouts=stereo"
        }
        return "loudnorm=I=-14:TP=-1.0:LRA=11,$channels,aformat=sample_rates=44100"
    }

    /**
     * Download (or reuse already-downloaded) songs and write them into [targetDir] as
     * `01 - Artist - Title.mp3`. The numeric prefix keeps car head units in playlist order.
     * Nothing is written to the library DB — this is a throwaway copy for a USB stick.
     */
    fun exportSongs(songs: List<SongInfo>, targetDir: File) {
        startJob(targetDir, songs.size) { ffmpeg, report ->
            val tempDir = File(AppPaths.cacheDir, "carexport").apply { mkdirs() }
            val done = java.util.concurrent.atomic.AtomicInteger(0)

            // Run several tracks concurrently — one ffmpeg process barely saturates a core,
            // and downloads overlap with transcodes instead of waiting in line.
            val results = songs.mapIndexed { index, song ->
                async {
                    exportSemaphore.withPermit {
                        coroutineContext.ensureActive()
                        report(done.getAndIncrement(), "${song.artist} - ${song.title}")

                        val name = "%02d - %s".format(
                            index + 1,
                            DownloadManager.sanitizeFilename("${song.artist} - ${song.title}")
                        )
                        val out = File(targetDir, "$name.mp3")
                        if (out.exists() && out.length() > 0) return@withPermit true

                        // Prefer the already-downloaded local file; otherwise pull the stream.
                        val existing = DownloadManager.getDownloadPathById(song.id)
                        var temp: File? = null
                        val source = existing ?: run {
                            val dest = File(tempDir, "${song.id}.tmp")
                            temp = dest
                            if (fetchToFile(song, dest)) dest else null
                        }

                        val success = if (source == null) {
                            Timber.w("Car export: no source for ${song.id}")
                            false
                        } else {
                            transcode(ffmpeg, source, out)
                        }
                        temp?.delete()
                        success
                    }
                }
            }.awaitAll()

            tempDir.deleteRecursively()
            results.count { it } to results.count { !it }
        }
    }

    /**
     * Normalize every audio file in [srcDir] into `<srcDir>/Normalized/` as MP3.
     * Writes to a subfolder rather than overwriting — the originals stay untouched.
     */
    fun normalizeFolder(srcDir: File) {
        val outDir = File(srcDir, "Normalized")
        val files = srcDir.listFiles { f: File ->
            f.isFile && f.extension.lowercase() in audioExtensions
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        if (files.isEmpty()) {
            _state.value = ExportState.Failed("No audio files found in ${srcDir.name}")
            return
        }

        startJob(outDir, files.size) { ffmpeg, report ->
            // Transcoding is CPU-bound and single-file ffmpeg barely uses one core, so run
            // several at once — this is where nearly all the wall-clock time went.
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val results = files.map { file ->
                async {
                    exportSemaphore.withPermit {
                        coroutineContext.ensureActive()
                        report(done.getAndIncrement(), file.name)
                        val out = File(outDir, "${file.nameWithoutExtension}.mp3")
                        if (out.exists() && out.length() > 0) true
                        else transcode(ffmpeg, file, out)
                    }
                }
            }.awaitAll()
            results.count { it } to results.count { !it }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ExportState.Idle
    }

    // --- internals ---

    private fun startJob(
        outDir: File,
        total: Int,
        work: suspend CoroutineScope.(ffmpeg: File, report: (Int, String) -> Unit) -> Pair<Int, Int>
    ) {
        if (isRunning) return
        val ffmpeg = findFfmpeg()
        if (ffmpeg == null) {
            _state.value = ExportState.Failed(FFMPEG_MISSING)
            return
        }
        outDir.mkdirs()
        _state.value = ExportState.Running(0, total, "")

        job = scope.launch {
            try {
                val (ok, failed) = work(ffmpeg) { done, current ->
                    _state.value = ExportState.Running(done, total, current)
                }
                _state.value = ExportState.Finished(ok, failed, outDir)
            } catch (e: CancellationException) {
                _state.value = ExportState.Idle
            } catch (e: Exception) {
                Timber.e(e, "Car export failed")
                _state.value = ExportState.Failed(e.message ?: "Export failed")
            }
        }
    }

    /** Runs ffmpeg. Returns true when a non-empty output file was produced. */
    private fun transcode(ffmpeg: File, input: File, output: File): Boolean {
        return try {
            val process = ProcessBuilder(
                ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.absolutePath,
                "-vn",
                "-map_metadata", "0",
                "-af", filterChain(),
                "-c:a", "libmp3lame",
                "-b:a", "320k",
                "-id3v2_version", "3",
                output.absolutePath
            ).redirectErrorStream(true).start()

            // Drain stdout — ffmpeg blocks forever if its pipe buffer fills up.
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                Timber.w("ffmpeg timed out on ${input.name}")
                output.delete()
                return false
            }
            if (process.exitValue() != 0) {
                Timber.w("ffmpeg failed on ${input.name}: ${log.take(300)}")
                output.delete()
                return false
            }
            output.isFile && output.length() > 0
        } catch (e: Exception) {
            Timber.e(e, "ffmpeg error on ${input.name}")
            output.delete()
            false
        }
    }

    private suspend fun fetchToFile(song: SongInfo, dest: File): Boolean {
        val stream = DownloadManager.getStreamInfo(song.id) ?: return false
        return try {
            val request = Request.Builder()
                .url(stream.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                dest.outputStream().use { out -> response.body.byteStream().copyTo(out, 131072) }
            }
            dest.length() > 0
        } catch (e: Exception) {
            Timber.w("Car export fetch failed for ${song.id}: ${e.message}")
            dest.delete()
            false
        }
    }
}
