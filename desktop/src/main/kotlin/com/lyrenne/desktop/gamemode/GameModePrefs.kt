package com.lyrenne.desktop.gamemode

import java.util.Properties

/** What the overlay shows on its own, without a hotkey asking. */
enum class OverlayMode(val label: String) {
    OFF("Off"),
    TOAST("Song toast"),
    LYRICS("Lyrics strip"),
}

/** Which corner or edge of the screen the overlay sits against. */
enum class OverlayCorner(val label: String, val top: Boolean, val horizontal: Int) {
    TOP_LEFT("Top left", true, -1),
    TOP_CENTER("Top", true, 0),
    TOP_RIGHT("Top right", true, 1),
    BOTTOM_LEFT("Bottom left", false, -1),
    BOTTOM_CENTER("Bottom", false, 0),
    BOTTOM_RIGHT("Bottom right", false, 1),
}

/** Overlay size: one layout, scaled (see windowWidthDp). The window's empty part is see-through. */
enum class OverlaySize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.25f),
}

/**
 * Game Mode settings, kept together in one value on [com.lyrenne.desktop.settings.AppPreferences]
 * so the shared preferences file only gains one field, one load line and one store line.
 */
data class GameModePrefs(
    /** Master switch for global hotkeys. Individual hotkeys are switched in [hotkeys]. */
    val hotkeysEnabled: Boolean = true,
    val hotkeys: Map<HotkeyAction, HotkeyBinding> = HotkeyDefaults.bindings,
    val overlayMode: OverlayMode = OverlayMode.OFF,
    val overlayCorner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val overlaySize: OverlaySize = OverlaySize.MEDIUM,
    /** 0.4 to 1. Applied to the overlay's content, so it never makes text unreadably faint. */
    val overlayOpacity: Float = 0.95f,
    /** [MONITOR_FOLLOW], or the AWT id string of a screen ("\Display1"). */
    val overlayMonitor: String = MONITOR_FOLLOW,
) {
    fun binding(action: HotkeyAction): HotkeyBinding = hotkeys[action] ?: HotkeyDefaults.bindings.getValue(action)

    fun store(props: Properties) {
        props.setProperty("gameMode.hotkeysEnabled", hotkeysEnabled.toString())
        for (action in HotkeyAction.entries) props.setProperty("gameMode.hotkey.${action.id}", binding(action).encode())
        props.setProperty("gameMode.overlayMode", overlayMode.name)
        props.setProperty("gameMode.overlayCorner", overlayCorner.name)
        props.setProperty("gameMode.overlaySize", overlaySize.name)
        props.setProperty("gameMode.overlayOpacity", overlayOpacity.toString())
        props.setProperty("gameMode.overlayMonitor", overlayMonitor)
    }

    companion object {
        const val MONITOR_FOLLOW = "follow"
        const val MIN_OPACITY = 0.4f

        fun load(props: Properties): GameModePrefs {
            val d = GameModePrefs()
            return GameModePrefs(
                hotkeysEnabled = props.getProperty("gameMode.hotkeysEnabled")?.toBoolean() ?: d.hotkeysEnabled,
                hotkeys = HotkeyAction.entries.associateWith {
                    HotkeyBinding.decode(props.getProperty("gameMode.hotkey.${it.id}"), HotkeyDefaults.bindings.getValue(it))
                },
                overlayMode = enumOr(props.getProperty("gameMode.overlayMode"), d.overlayMode),
                overlayCorner = enumOr(props.getProperty("gameMode.overlayCorner"), d.overlayCorner),
                overlaySize = enumOr(props.getProperty("gameMode.overlaySize"), d.overlaySize),
                overlayOpacity = props.getProperty("gameMode.overlayOpacity")?.toFloatOrNull()
                    ?.coerceIn(MIN_OPACITY, 1f) ?: d.overlayOpacity,
                overlayMonitor = props.getProperty("gameMode.overlayMonitor")?.takeIf { it.isNotBlank() } ?: d.overlayMonitor,
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
    }
}
