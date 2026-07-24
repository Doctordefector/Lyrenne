package com.metrolist.music.desktop.download

import com.metrolist.music.desktop.AppPaths
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val ffmpegExe = if (isWindows) "ffmpeg.exe" else "ffmpeg"

    /** Locate ffmpeg: bundled next to the app (portable), then PATH. Null if unavailable. */
    fun findFfmpeg(): File? {
        val bundled = listOf(
            File(AppPaths.appDir, ffmpegExe),
            File(AppPaths.appDir, "ffmpeg/$ffmpegExe"),
            File(AppPaths.appDir, "ffmpeg/bin/$ffmpegExe")
        ).firstOrNull { it.isFile }
        if (bundled != null) return bundled

        return System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it.trim(), ffmpegExe) }
            ?.firstOrNull { it.isFile }
    }

    const val FFMPEG_MISSING =
        "ffmpeg not found — put ffmpeg.exe next to Metrolist.exe (or install it on PATH), then try again"

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
            var ok = 0
            var failed = 0
            val tempDir = File(AppPaths.cacheDir, "carexport").apply { mkdirs() }

            songs.forEachIndexed { index, song ->
                coroutineContext.ensureActive()
                report(index, "${song.artist} - ${song.title}")

                val name = "%02d - %s".format(index + 1, DownloadManager.sanitizeFilename("${song.artist} - ${song.title}"))
                val out = File(targetDir, "$name.mp3")
                if (out.exists() && out.length() > 0) {
                    ok++
                    return@forEachIndexed
                }

                // Prefer the already-downloaded local file; otherwise pull the stream to a temp file.
                val existing = DownloadManager.getDownloadPathById(song.id)
                var temp: File? = null
                val source = existing ?: run {
                    val dest = File(tempDir, "${song.id}.tmp")
                    temp = dest
                    if (fetchToFile(song, dest)) dest else null
                }

                if (source == null) {
                    Timber.w("Car export: no source for ${song.id}")
                    failed++
                } else if (transcode(ffmpeg, source, out)) {
                    ok++
                } else {
                    failed++
                }
                temp?.delete()
            }

            tempDir.deleteRecursively()
            ok to failed
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
            var ok = 0
            var failed = 0
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                report(index, file.name)
                val out = File(outDir, "${file.nameWithoutExtension}.mp3")
                if (out.exists() && out.length() > 0) ok++
                else if (transcode(ffmpeg, file, out)) ok++
                else failed++
            }
            ok to failed
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
        work: suspend (ffmpeg: File, report: (Int, String) -> Unit) -> Pair<Int, Int>
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
