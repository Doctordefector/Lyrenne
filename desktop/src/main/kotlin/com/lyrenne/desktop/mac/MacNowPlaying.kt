package com.lyrenne.desktop.mac

import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.playback.DesktopPlayer
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

/**
 * macOS Now Playing: the Control Center and menu bar media widget, the lock screen, AirPods and
 * the keyboard's media keys (F7 to F9). Windows and Linux get the same through mediasession-kt in
 * [com.lyrenne.desktop.integration.SystemMediaSession], which has no macOS backend, so this talks
 * to MediaPlayer.framework directly through the Objective-C runtime over JNA:
 *
 * - `MPNowPlayingInfoCenter.defaultCenter` gets the title, artist, album, duration, elapsed time
 *   and rate, plus the playback state. The system extrapolates the position from elapsed + rate,
 *   so it is only republished on discrete events: song change, play, pause, a seek. The cover
 *   goes in as an `MPMediaItemArtwork`, whose image comes from an Objective-C block: a global
 *   block literal laid out by hand, with a JNA callback as its function.
 * - `MPRemoteCommandCenter.sharedCommandCenter` delivers play, pause, toggle, next, previous and
 *   scrubbing to a small Objective-C class made at runtime, whose methods are JNA callbacks.
 *
 * objc_msgSend is called with fixed arguments, never as a variadic function: on Apple Silicon a
 * variadic call puts arguments on the stack where the method does not look for them.
 */
object MacNowPlaying {
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, sel: Pointer, imp: Callback, types: String): Boolean
        fun objc_autoreleasePoolPush(): Pointer
        fun objc_autoreleasePoolPop(pool: Pointer)
    }

    /**
     * The artwork block's function, `NSImage *(^)(CGSize)`. A CGSize is two doubles, which both
     * ABIs pass by value exactly like two double arguments, so no JNA Structure is needed.
     */
    internal interface ArtworkImp : Callback {
        fun invoke(block: Pointer?, width: Double, height: Double): Pointer?
    }

    /** `- (MPRemoteCommandHandlerStatus)handle:(MPRemoteCommandEvent *)event` */
    internal interface CommandImp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long
    }

    private const val STATUS_SUCCESS = 0L
    private const val STATUS_COMMAND_FAILED = 200L
    private const val STATE_PLAYING = 1L
    private const val STATE_PAUSED = 2L
    private const val STATE_STOPPED = 3L

    private var objc: ObjC? = null
    private var msgSend: Function? = null
    private var mediaPlayer: NativeLibrary? = null

    /** Callbacks the Objective-C class points at. Held for the life of the process: freed, they crash it. */
    private val imps = mutableListOf<CommandImp>()
    private var target: Pointer? = null

    /** The current cover (a retained NSImage), and the one before it, released a song later. */
    @Volatile private var image: Pointer? = null
    private var previousImage: Pointer? = null
    @Volatile private var imageSongId: String? = null
    private val artworkImp = object : ArtworkImp {
        override fun invoke(block: Pointer?, width: Double, height: Double): Pointer? = image
    }

    /**
     * `{ isa, flags, reserved, invoke, descriptor }` with BLOCK_IS_GLOBAL, so copying it is a no-op
     * and there is nothing to dispose. Kept for the process's life, like the callback inside it.
     */
    private val artworkBlock: Pointer by lazy {
        // 32 bytes of block, then its 16-byte descriptor { reserved, size } in the same allocation.
        com.sun.jna.Memory(32 + 16).apply {
            setPointer(0, NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, 1 shl 28) // BLOCK_IS_GLOBAL
            setInt(12, 0)
            setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(artworkImp))
            setLong(32, 0)
            setLong(40, 32)
            setPointer(24, share(32))
        }
    }
    private var scope: CoroutineScope? = null
    private var observer: Job? = null

    /** What the remote commands do. Set by [initialize]; the e2e test sets its own. */
    @Volatile internal var onCommand: (String, Double) -> Unit = { _, _ -> }

    @Volatile internal var lastFailure: String? = null
        private set

    /**
     * Publish [player] to Now Playing and take the media keys. True when that worked, in which
     * case the AWT media-key fallback is not needed.
     */
    fun initialize(player: DesktopPlayer): Boolean {
        if (!Platform.isMac || observer != null) return observer != null
        if (!load()) return false
        onCommand = { name, position ->
            java.awt.EventQueue.invokeLater {
                when (name) {
                    "play" -> player.play()
                    "pause" -> player.pause()
                    "toggle" -> player.togglePlayPause()
                    "next" -> scope?.launch { player.playNext() }
                    "previous" -> scope?.launch { player.playPrevious() }
                    "seek" -> player.seekTo((position * 1000).toLong())
                }
            }
        }
        if (!registerCommands()) return false
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        observer = s.launch { observe(player) }
        Timber.i("macOS Now Playing active")
        return true
    }

    /** Republish on song change, play/pause and seeks; between those the system keeps time. */
    private suspend fun observe(player: DesktopPlayer) {
        var songId: String? = null
        var playing = false
        var duration = 0L
        var anchorPos = 0L
        var anchorAt = 0L
        while (true) {
            val st = player.state.value
            val song = st.currentSong
            val now = System.currentTimeMillis()
            val expected = if (playing) anchorPos + (now - anchorAt) else anchorPos
            val changed = song?.id != songId || st.isPlaying != playing || st.duration != duration ||
                abs(st.position - expected) > 2_500
            if (changed) {
                songId = song?.id
                playing = st.isPlaying
                duration = st.duration
                anchorPos = st.position
                anchorAt = now
                // A new song drops the old cover first, so it is never shown under the new title,
                // then gets its own once downloaded (the same cache the Windows/Linux session uses).
                if (song?.id != imageSongId) {
                    imageSongId = song?.id
                    setArtwork(null)
                    val url = song?.thumbnailUrl
                    if (song != null && url != null) scope?.launch {
                        val file = com.lyrenne.desktop.integration.SystemMediaSession.cacheArtwork(url)
                        if (file != null && imageSongId == song.id && setArtwork(file)) {
                            val now2 = player.state.value
                            publish(song.title, song.artist, song.album, now2.duration, now2.position, now2.isPlaying)
                        }
                    }
                }
                if (song == null) clear()
                else publish(song.title, song.artist, song.album, st.duration, st.position, st.isPlaying)
            }
            delay(1_000)
        }
    }

    private fun load(): Boolean = try {
        if (objc == null) {
            objc = Native.load("objc", ObjC::class.java)
            msgSend = NativeLibrary.getInstance("objc").getFunction("objc_msgSend")
            // AppKit for NSImage; a JVM without a window (the smoke test, unit tests) has not loaded it.
            NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
            mediaPlayer = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
        }
        true
    } catch (e: Throwable) {
        lastFailure = "MediaPlayer.framework unavailable: ${e.message}"
        Timber.w(lastFailure.orEmpty())
        false
    }

    private fun sel(name: String): Pointer = objc!!.sel_registerName(name)
    private fun cls(name: String): Pointer = objc!!.objc_getClass(name) ?: error("no class $name")
    private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend!!.invokePointer(arrayOf(receiver, sel(selector), *args))
    private fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long =
        msgSend!!.invokeLong(arrayOf(receiver, sel(selector), *args))
    private fun sendDouble(receiver: Pointer?, selector: String): Double =
        msgSend!!.invokeDouble(arrayOf(receiver, sel(selector)))

    private fun nsString(s: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", s)
    private fun nsDouble(d: Double): Pointer? = send(cls("NSNumber"), "numberWithDouble:", d)
    private fun constant(name: String): Pointer = mediaPlayer!!.getGlobalVariableAddress(name).getPointer(0)

    private inline fun <T> pooled(block: () -> T): T {
        val pool = objc!!.objc_autoreleasePoolPush()
        try {
            return block()
        } finally {
            objc!!.objc_autoreleasePoolPop(pool)
        }
    }

    private fun center(): Pointer? = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")

    /** Sets Now Playing. Durations and positions in milliseconds. */
    @Synchronized
    internal fun publish(title: String, artist: String, album: String?, durationMs: Long, positionMs: Long, playing: Boolean) {
        if (msgSend == null) return
        runCatching {
            pooled {
                val dict = send(cls("NSMutableDictionary"), "dictionary")
                fun put(key: String, value: Pointer?) { if (value != null) send(dict, "setObject:forKey:", value, constant(key)) }
                put("MPMediaItemPropertyTitle", nsString(title))
                put("MPMediaItemPropertyArtist", nsString(artist))
                album?.takeIf { it.isNotBlank() }?.let { put("MPMediaItemPropertyAlbumTitle", nsString(it)) }
                if (durationMs > 0) put("MPMediaItemPropertyPlaybackDuration", nsDouble(durationMs / 1000.0))
                put("MPNowPlayingInfoPropertyElapsedPlaybackTime", nsDouble(positionMs / 1000.0))
                put("MPNowPlayingInfoPropertyPlaybackRate", nsDouble(if (playing) 1.0 else 0.0))
                if (image != null) {
                    val artwork = send(send(cls("MPMediaItemArtwork"), "alloc"), "initWithBoundsSize:requestHandler:", 600.0, 600.0, artworkBlock)
                    put("MPMediaItemPropertyArtwork", artwork)
                    send(artwork, "release") // the dictionary holds it now
                }
                val c = center()
                send(c, "setNowPlayingInfo:", dict)
                send(c, "setPlaybackState:", if (playing) STATE_PLAYING else STATE_PAUSED)
            }
        }.onFailure { lastFailure = "publish failed: ${it.message}"; Timber.w(lastFailure.orEmpty()) }
    }

    /** Loads [file] as the cover, or drops the cover for null. False if macOS could not read it. */
    @Synchronized
    internal fun setArtwork(file: java.io.File?): Boolean {
        if (msgSend == null) return false
        return runCatching {
            pooled {
                val next = file?.let { send(send(cls("NSImage"), "alloc"), "initWithContentsOfFile:", nsString(it.absolutePath)) }
                previousImage?.let { send(it, "release") }
                previousImage = image
                image = next
                file == null || next != null
            }
        }.getOrDefault(false)
    }

    @Synchronized
    private fun clear() {
        if (msgSend == null) return
        runCatching {
            pooled {
                val c = center()
                send(c, "setNowPlayingInfo:", null as Pointer?)
                send(c, "setPlaybackState:", STATE_STOPPED)
            }
        }
    }

    /** What the system now holds: title, artist, elapsed seconds and the playback state. For tests. */
    internal fun readBack(): Map<String, String?> = pooled {
        val info = send(center(), "nowPlayingInfo")
        fun str(key: String): String? = send(info, "objectForKey:", constant(key))
            ?.let { send(it, "description") }?.let { send(it, "UTF8String") }?.getString(0)
        mapOf(
            "title" to str("MPMediaItemPropertyTitle"),
            "artist" to str("MPMediaItemPropertyArtist"),
            "duration" to str("MPMediaItemPropertyPlaybackDuration"),
            "state" to sendLong(center(), "playbackState").toString(),
            // Asks the artwork for an image, which runs the block: proves the hand-made block works.
            "artwork" to send(info, "objectForKey:", constant("MPMediaItemPropertyArtwork"))
                ?.let { send(it, "imageWithSize:", 64.0, 64.0) }?.let { "image" },
        )
    }

    /** The remote command target, its class made once per process. */
    private fun registerCommands(): Boolean = try {
        pooled {
            val handlers = listOf(
                "lyrennePlay:" to "play", "lyrennePause:" to "pause", "lyrenneToggle:" to "toggle",
                "lyrenneNext:" to "next", "lyrennePrevious:" to "previous", "lyrenneSeek:" to "seek",
            )
            val o = objc!!
            val c = o.objc_getClass("LyrenneRemoteTarget") ?: run {
                val nc = o.objc_allocateClassPair(cls("NSObject"), "LyrenneRemoteTarget", 0)
                    ?: error("could not create the command target class")
                for ((selector, name) in handlers) {
                    val imp = object : CommandImp {
                        override fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long = try {
                            val position = if (name == "seek" && event != null) sendDouble(event, "positionTime") else 0.0
                            onCommand(name, position)
                            STATUS_SUCCESS
                        } catch (e: Throwable) {
                            Timber.w("Now Playing command $name failed: ${e.message}")
                            STATUS_COMMAND_FAILED
                        }
                    }
                    imps += imp
                    // q = NSInteger return, @ self, : _cmd, @ event
                    o.class_addMethod(nc, sel(selector), imp, "q@:@")
                }
                o.objc_registerClassPair(nc)
                nc
            }
            val t = send(send(c, "alloc"), "init") ?: error("could not create the command target")
            target = t
            val commands = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")
            val wiring = listOf(
                "playCommand" to "lyrennePlay:", "pauseCommand" to "lyrennePause:",
                "togglePlayPauseCommand" to "lyrenneToggle:", "nextTrackCommand" to "lyrenneNext:",
                "previousTrackCommand" to "lyrennePrevious:", "changePlaybackPositionCommand" to "lyrenneSeek:",
            )
            for ((getter, selector) in wiring) {
                val command = send(commands, getter)
                send(command, "setEnabled:", true)
                send(command, "addTarget:action:", t, sel(selector))
            }
        }
        true
    } catch (e: Throwable) {
        lastFailure = "remote commands unavailable: ${e.message}"
        Timber.w(lastFailure.orEmpty())
        false
    }

    /** Calls one of the target's handlers the way MPRemoteCommandCenter would. For tests. */
    internal fun dispatchForTest(selector: String) {
        pooled { send(target, selector, null as Pointer?) }
    }

    /** For tests: load and register without a player. */
    internal fun initializeForTest(handler: (String, Double) -> Unit): Boolean {
        if (!Platform.isMac || !load()) return false
        onCommand = handler
        return target != null || registerCommands()
    }

    fun release() {
        observer?.cancel()
        observer = null
        scope?.cancel()
        scope = null
        clear()
    }
}
