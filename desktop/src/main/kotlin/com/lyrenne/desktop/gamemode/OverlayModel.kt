package com.lyrenne.desktop.gamemode

/** A tiny confirmation for a hotkey, such as "Liked" or "Volume 60%". */
data class OverlayFlash(val kind: Kind, val text: String, val serial: Long) {
    enum class Kind { PLAY, PAUSE, NEXT, PREVIOUS, LIKED, UNLIKED, VOLUME, SEEK_FORWARD, SEEK_BACK, OVERLAY_ON, OVERLAY_OFF, INFO }
}

/** What the overlay draws at one moment. Nothing at all means its window is hidden. */
data class OverlayView(val card: Boolean = false, val lyrics: Boolean = false, val flash: OverlayFlash? = null) {
    val visible: Boolean get() = card || lyrics || flash != null

    companion object {
        val NONE = OverlayView()
    }
}

/** Facts about playback the overlay depends on. */
data class OverlayInputs(
    val hasSong: Boolean,
    val isPlaying: Boolean,
    val hasSyncedLyrics: Boolean,
    /** Lyrenne's own window is the active one: its player bar and lyrics panel are right there. */
    val appFocused: Boolean = false,
)

/**
 * When the overlay shows what. Pure and clock-free: every call takes `now` in milliseconds, so the
 * timers are tested by passing numbers (OverlayModelTest) rather than by sleeping.
 *
 * - **Song toast** mode shows the song card for [TOAST_MS] on every song change.
 * - **Lyrics strip** mode shows the current line while a song with synced lyrics is playing, and
 *   the card briefly on a song change. It hides while paused, so a frozen line is not left over
 *   the game.
 * - **Peek** shows the card, and the lyrics line when there is one, for [PEEK_MS], in any mode.
 * - **Always on** mode keeps the card, and the lyrics when there are any, up the whole time.
 * - **Pin** (the Home hotkey) does the same on demand, in any mode, until pressed again. Both show
 *   while paused: the user asked for them to stay.
 * - **Toggle** hides whatever the mode shows, or, from Off, pins the overlay on until toggled back.
 * - While Lyrenne itself is the active window the mode and the pin show nothing (its own player is
 *   in view, and the overlay would sit on top of it); peeks and flashes still show.
 * - **Flashes** confirm a hotkey for [FLASH_MS] and always show, since the user just asked for
 *   something and wants to know it happened.
 */
class OverlayModel {
    var mode: OverlayMode = OverlayMode.OFF
    /** Flipped by the show/hide hotkey. Its meaning depends on [mode], see [effectiveMode]. */
    var toggled: Boolean = false
        private set
    /** Set by the pin hotkey: card and lyrics stay up until it is pressed again. */
    var pinned: Boolean = false
        private set

    private var cardUntil = 0L
    private var peekUntil = 0L
    private var flashUntil = 0L
    private var flash: OverlayFlash? = null
    private var flashSerial = 0L

    private enum class Effective { OFF, TOAST, LYRICS, PINNED, HIDDEN }

    private val effectiveMode: Effective
        get() = when {
            !toggled -> when (mode) {
                OverlayMode.OFF -> Effective.OFF
                OverlayMode.TOAST -> Effective.TOAST
                OverlayMode.LYRICS -> Effective.LYRICS
                OverlayMode.ALWAYS -> Effective.PINNED
            }
            mode == OverlayMode.OFF -> Effective.PINNED
            else -> Effective.HIDDEN
        }

    /** True when the overlay is now showing on its own, after a toggle. For the flash text. */
    val isShownByToggle: Boolean get() = effectiveMode != Effective.HIDDEN && effectiveMode != Effective.OFF

    fun songChanged(now: Long) {
        if (effectiveMode == Effective.TOAST || effectiveMode == Effective.LYRICS) cardUntil = now + TOAST_MS
    }

    fun peek(now: Long, durationMs: Long = PEEK_MS) {
        peekUntil = maxOf(peekUntil, now + durationMs)
    }

    /** Pins or unpins the card and lyrics. True when they are now pinned. */
    fun togglePin(): Boolean {
        pinned = !pinned
        if (!pinned) peekUntil = 0
        return pinned
    }

    fun toggle() {
        toggled = !toggled
        // Hiding takes the toast and any peek with it; the user asked for a clear screen.
        if (effectiveMode == Effective.HIDDEN) { cardUntil = 0; peekUntil = 0 }
    }

    fun flash(kind: OverlayFlash.Kind, text: String, now: Long): OverlayFlash {
        val f = OverlayFlash(kind, text, ++flashSerial)
        flash = f
        flashUntil = now + FLASH_MS
        return f
    }

    fun view(now: Long, inputs: OverlayInputs): OverlayView {
        val flashNow = flash?.takeIf { now < flashUntil }
        val peeking = now < peekUntil
        // A peek with nothing playing still shows the card, which then says so.
        if (!inputs.hasSong) return OverlayView(card = peeking, flash = flashNow)
        val effective = effectiveMode
        val byMode = !inputs.appFocused
        val timedCard = byMode && now < cardUntil && (effective == Effective.TOAST || effective == Effective.LYRICS)
        // Pinned (by the hotkey, Always on, or a toggle from Off): card and lyrics, paused or not.
        val pin = byMode && (pinned || effective == Effective.PINNED)
        val card = peeking || timedCard || pin
        val lyrics = inputs.hasSyncedLyrics && (
            peeking || pin || (byMode && effective == Effective.LYRICS && inputs.isPlaying)
        )
        return OverlayView(card = card, lyrics = lyrics, flash = flashNow)
    }

    /** The next moment [view] can change by the clock alone, or null if only an event can change it. */
    fun nextChange(now: Long): Long? =
        listOf(cardUntil, peekUntil, flashUntil).filter { it > now }.minOrNull()

    companion object {
        const val TOAST_MS = 5_000L
        const val PEEK_MS = 5_000L
        const val FLASH_MS = 1_400L
    }
}
