package com.lyrenne.desktop.gamemode

import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sync.setLiked
import com.lyrenne.desktop.ui.components.fetchLyricsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Game Mode: runs hotkey actions and decides what the in-game overlay shows.
 *
 * The decisions live in [OverlayModel]; this object feeds it events (song changes, hotkeys, the
 * Settings preview) and the clock, and publishes the result as [view] for the overlay window.
 */
object GameMode {
    /** The overlay is a Windows feature for now: click-through needs WS_EX_TRANSPARENT. */
    val overlaySupported: Boolean get() = Platform.isWindows

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val model = OverlayModel()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inputs = MutableStateFlow(OverlayInputs(hasSong = false, isPlaying = false, hasSyncedLyrics = false))

    private val _view = MutableStateFlow(OverlayView.NONE)
    val view: StateFlow<OverlayView> = _view.asStateFlow()

    private var player: DesktopPlayer? = null
    private val appFocused = MutableStateFlow(false)

    /** Seek step for the seek hotkeys, and volume step for the volume ones. */
    private const val SEEK_MS = 10_000L
    private const val VOLUME_STEP = 0.05f

    fun start(player: DesktopPlayer) {
        if (this.player != null) return
        this.player = player
        synchronized(model) { model.mode = PreferencesManager.preferences.value.gameMode.overlayMode }

        scope.launch {
            PreferencesManager.preferences.map { it.gameMode.overlayMode }.distinctUntilChanged().collect { mode ->
                synchronized(model) { model.mode = mode }
                wake.trySend(Unit)
            }
        }
        scope.launch {
            combine(player.state, LyricsManager.state, appFocused) { p, l, focused ->
                val song = p.currentSong
                OverlayInputs(
                    hasSong = song != null,
                    isPlaying = p.isPlaying,
                    hasSyncedLyrics = song != null && l.songId == song.id && !l.lines.isNullOrEmpty(),
                    appFocused = focused,
                )
            }.distinctUntilChanged().collect {
                inputs.value = it
                wake.trySend(Unit)
            }
        }
        scope.launch {
            player.state.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id }.drop(1).collect { song ->
                if (song == null) return@collect
                synchronized(model) { model.songChanged(now()) }
                wake.trySend(Unit)
                // The lyrics panel looks lyrics up too, but only while the main window composes,
                // and a gamer has it minimised. LyricsManager drops the second request.
                if (overlaySupported) launch { fetchLyricsFor(player, song) }
            }
        }
        scope.launch { tick() }
        // AWT knows when any Lyrenne window is the active one; the overlay window never is.
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow") { e ->
            appFocused.value = e.newValue != null
        }

        GlobalHotkeys.start(::perform)
    }

    /** Recomputes the view on every event, and otherwise sleeps until the next timer runs out. */
    private suspend fun tick() {
        while (true) {
            val now = now()
            val (v, next) = synchronized(model) { model.view(now, inputs.value) to model.nextChange(now) }
            _view.value = v
            val wait = next?.let { it - now } ?: Long.MAX_VALUE
            withTimeoutOrNull(wait.coerceAtLeast(1)) { wake.receive() }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun flash(kind: OverlayFlash.Kind, text: String) {
        synchronized(model) { model.flash(kind, text, now()) }
        wake.trySend(Unit)
    }

    /** Shows the song card and lyrics line for a few seconds, as the peek hotkey does. */
    fun peek() {
        synchronized(model) { model.peek(now()) }
        wake.trySend(Unit)
    }

    /** The Settings "Preview" button: a peek with a flash, so every part of the overlay shows. */
    fun preview() {
        synchronized(model) { model.peek(now(), OverlayModel.PEEK_MS + 1_000) }
        flash(OverlayFlash.Kind.INFO, "This is how it looks")
    }

    fun toggleOverlay() {
        val shown = synchronized(model) { model.toggle(); model.isShownByToggle }
        flash(if (shown) OverlayFlash.Kind.OVERLAY_ON else OverlayFlash.Kind.OVERLAY_OFF, if (shown) "Overlay on" else "Overlay hidden")
    }

    fun perform(action: HotkeyAction) {
        val player = player ?: return
        Timber.i("Hotkey: ${action.id}")
        when (action) {
            HotkeyAction.PLAY_PAUSE -> {
                val wasPlaying = player.state.value.isPlaying
                player.togglePlayPause()
                if (player.state.value.currentSong != null) {
                    flash(if (wasPlaying) OverlayFlash.Kind.PAUSE else OverlayFlash.Kind.PLAY, if (wasPlaying) "Paused" else "Playing")
                }
            }
            HotkeyAction.NEXT -> {
                flash(OverlayFlash.Kind.NEXT, "Next")
                scope.launch { player.playNext() }
            }
            HotkeyAction.PREVIOUS -> {
                flash(OverlayFlash.Kind.PREVIOUS, "Previous")
                scope.launch { player.playPrevious() }
            }
            HotkeyAction.LIKE -> scope.launch { toggleLike(player) }
            HotkeyAction.VOLUME_UP -> changeVolume(player, +VOLUME_STEP)
            HotkeyAction.VOLUME_DOWN -> changeVolume(player, -VOLUME_STEP)
            HotkeyAction.SEEK_FORWARD -> seek(player, +SEEK_MS)
            HotkeyAction.SEEK_BACK -> seek(player, -SEEK_MS)
            HotkeyAction.PEEK -> peek()
            HotkeyAction.TOGGLE_OVERLAY -> toggleOverlay()
        }
    }

    private suspend fun toggleLike(player: DesktopPlayer) {
        val song = player.state.value.currentSong ?: run {
            flash(OverlayFlash.Kind.INFO, "Nothing playing")
            return
        }
        withContext(Dispatchers.IO) {
            // From the database, as the player bar reads it: the queue's copy does not know.
            val row = withTimeoutOrNull(2_000) { DatabaseHelper.getSongById(song.id).first() }
            val liked = row?.liked == 1L
            setLiked(song, !liked, row?.isDownloaded == 1L)
            if (liked) flash(OverlayFlash.Kind.UNLIKED, "Removed from liked songs")
            else flash(OverlayFlash.Kind.LIKED, "Added to liked songs")
        }
    }

    /** As the player bar's volume slider does it, unmuting on the way. */
    private fun changeVolume(player: DesktopPlayer, delta: Float) {
        val prefs = PreferencesManager.preferences.value
        if (prefs.isMuted) PreferencesManager.setMuted(false)
        val v = (prefs.volume + delta).coerceIn(0f, 1f)
        PreferencesManager.setVolume(v)
        player.setVolume(v)
        flash(OverlayFlash.Kind.VOLUME, "Volume ${(v * 100).roundToInt()}%")
    }

    private fun seek(player: DesktopPlayer, delta: Long) {
        val s = player.state.value
        if (s.currentSong == null) return
        val target = (s.position + delta).coerceIn(0L, if (s.duration > 0) s.duration else Long.MAX_VALUE)
        player.seekTo(target)
        flash(
            if (delta > 0) OverlayFlash.Kind.SEEK_FORWARD else OverlayFlash.Kind.SEEK_BACK,
            if (delta > 0) "Ahead ${delta / 1000} s" else "Back ${-delta / 1000} s"
        )
    }

    fun release() {
        GlobalHotkeys.release()
    }
}
