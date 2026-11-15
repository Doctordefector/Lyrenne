package com.lyrenne.desktop.link

import com.lyrenne.desktop.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.util.Properties

/** How the stream overlay looks. Every field maps to one query parameter of `/overlay`. */
data class OverlayConfig(
    /** `card`, `lyrics` or `ticker`. */
    val layout: String = "card",
    /** `tl`, `tc`, `tr`, `bl`, `bc` or `br`. */
    val position: String = "bl",
    val scale: Float = 1f,
    /** `auto` (from the album art) or a six digit hex colour without the `#`. */
    val accent: String = "auto",
    /** Lyric text size in px on a 1080p canvas. */
    val fontSize: Int = 44,
    /** Show the current lyric line under the card. */
    val cardLyrics: Boolean = true,
) {
    companion object {
        val LAYOUTS = listOf("card", "lyrics", "ticker")
        val POSITIONS = listOf("tl", "tc", "tr", "bl", "bc", "br")
    }

    /** The query string for `/overlay`, defaults left out so the URL stays short. */
    fun query(): String {
        val d = OverlayConfig()
        val parts = mutableListOf("layout=$layout")
        if (position != d.position) parts += "pos=$position"
        if (scale != d.scale) parts += "scale=${"%.2f".format(java.util.Locale.ROOT, scale).trimEnd('0').trimEnd('.')}"
        if (accent != d.accent) parts += "accent=$accent"
        if (fontSize != d.fontSize) parts += "font=$fontSize"
        if (!cardLyrics && layout == "card") parts += "lyrics=0"
        return parts.joinToString("&")
    }
}

data class LinkPrefs(
    /** The localhost server for the stream overlay. Loopback only. */
    val overlayEnabled: Boolean = false,
    /** The phone remote: the server binds on the LAN. Off by default, see CLAUDE.md. */
    val remoteEnabled: Boolean = false,
    val port: Int = LinkSettings.DEFAULT_PORT,
    /** Pairing token for the phone remote. Rotating it unpairs every phone. */
    val token: String = "",
    /** Write now-playing.txt / .json / cover.jpg for OBS text sources and Streamer.bot. */
    val exportEnabled: Boolean = false,
    val exportFolder: String? = null,
    val overlay: OverlayConfig = OverlayConfig(),
)

/**
 * Lyrenne Link's settings, in their own file (`data/link.properties`) rather than
 * preferences.properties: the pairing token should not travel with a settings backup to another
 * machine, and keeping the feature in one file keeps it out of everyone else's way.
 */
object LinkSettings {
    const val DEFAULT_PORT = 48712

    private val _prefs = MutableStateFlow(LinkPrefs())
    val prefs: StateFlow<LinkPrefs> = _prefs.asStateFlow()

    /** Overridable for tests. */
    internal var fileOverride: File? = null
    private val file: File get() = fileOverride ?: File(AppPaths.dataDir, "link.properties")

    @Volatile private var loaded = false

    fun load() {
        val p = Properties()
        runCatching { if (file.exists()) file.inputStream().use { p.load(it) } }
            .onFailure { Timber.e("Link settings unreadable: ${it.message}") }
        fun bool(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
        val d = OverlayConfig()
        val overlay = OverlayConfig(
            layout = p.getProperty("overlay.layout")?.takeIf { it in OverlayConfig.LAYOUTS } ?: d.layout,
            position = p.getProperty("overlay.position")?.takeIf { it in OverlayConfig.POSITIONS } ?: d.position,
            scale = p.getProperty("overlay.scale")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: d.scale,
            accent = p.getProperty("overlay.accent")?.takeIf { it == "auto" || HEX.matches(it) } ?: d.accent,
            fontSize = p.getProperty("overlay.font")?.toIntOrNull()?.coerceIn(20, 120) ?: d.fontSize,
            cardLyrics = bool("overlay.cardLyrics", d.cardLyrics),
        )
        var token = p.getProperty("token").orEmpty()
        val fresh = token.length < 20
        if (fresh) token = LinkSecurity.newToken()
        _prefs.value = LinkPrefs(
            overlayEnabled = bool("overlayEnabled", false),
            remoteEnabled = bool("remoteEnabled", false),
            port = p.getProperty("port")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT,
            token = token,
            exportEnabled = bool("exportEnabled", false),
            exportFolder = p.getProperty("exportFolder")?.takeIf { it.isNotBlank() },
            overlay = overlay,
        )
        loaded = true
        if (fresh) save()
    }

    fun update(change: (LinkPrefs) -> LinkPrefs) {
        if (!loaded) load()
        synchronized(this) {
            _prefs.value = change(_prefs.value)
            save()
        }
    }

    fun rotateToken() = update { it.copy(token = LinkSecurity.newToken()) }

    @Synchronized
    private fun save() {
        val v = _prefs.value
        val p = Properties()
        p.setProperty("overlayEnabled", v.overlayEnabled.toString())
        p.setProperty("remoteEnabled", v.remoteEnabled.toString())
        p.setProperty("port", v.port.toString())
        p.setProperty("token", v.token)
        p.setProperty("exportEnabled", v.exportEnabled.toString())
        v.exportFolder?.let { p.setProperty("exportFolder", it) }
        p.setProperty("overlay.layout", v.overlay.layout)
        p.setProperty("overlay.position", v.overlay.position)
        p.setProperty("overlay.scale", v.overlay.scale.toString())
        p.setProperty("overlay.accent", v.overlay.accent)
        p.setProperty("overlay.font", v.overlay.fontSize.toString())
        p.setProperty("overlay.cardLyrics", v.overlay.cardLyrics.toString())
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "Lyrenne Link") }
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure { Timber.e("Could not save link settings: ${it.message}") }
    }

    internal val HEX = Regex("[0-9a-fA-F]{6}")
}
