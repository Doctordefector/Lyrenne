package com.lyrenne.desktop.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dark theme, built from the same tokens as the website and the app mark: near-black surfaces,
 * hairline borders, and the bronze accent, rather than Material's stock purple.
 *
 * The accent is #A37C43 exactly, the same value the logo ring and the site use. It appears as a
 * *fill* only. As a foreground on near-black it measures 4.0:1, under the 4.5:1 AA wants for body
 * text, so anywhere the accent has to be legible as text or an icon a lighter tint of it
 * (#E3C089, from the top of the logo's gradient) is used instead, which measures 10.9:1.
 *
 * Neutrals are warm (a hint of the bronze's hue) rather than Material's blue-grey, which looked
 * cold next to the gold.
 */
private val LyrenneGoldLight = Color(0xFFE3C089)

object LyrenneTokens {
    // Labelled rail: icon over a one-line label.
    val navigationRailWidth = 76.dp
    val playerHeight = 80.dp
    // The player and tray panel are floating, rounded docks rather than edge-to-edge bars.
    val dockRadius = 18.dp
    val artworkRadius = 4.dp
    val panelRadius = 8.dp
    val contentPadding = 20.dp
}

// Contrast ratios below are WCAG 2.x, measured against the colour named in each comment. They are
// written down because the palette has been retuned twice and the ratios are the only part of it a
// reviewer cannot judge by eye. Recompute them when a colour moves.
private val DarkColorScheme = darkColorScheme(
    primary = LyrenneGoldLight,                // foreground-capable gold, 10.9:1 on the background
    onPrimary = Color(0xFF2A1B06),             // 9.7:1 on primary
    // Containers are DARK in a dark scheme. Filling a whole card with the mid-tone bronze made it
    // read as a flat brown slab: the accent looks metallic as a hairline or a label, not as a
    // large area. Dark panel, light gold on top, which is how the site uses it.
    primaryContainer = Color(0xFF4A3819),
    onPrimaryContainer = Color(0xFFF7E2BC),    // 8.9:1 on primaryContainer, gold on it 6.5:1

    secondary = Color(0xFFD3C3AA),
    onSecondary = Color(0xFF2A2015),
    // Tonal buttons and selected chips. At #4E4231 (1.9:1 against the page) they barely showed.
    secondaryContainer = Color(0xFF5A4A33),    // 2.2:1 against the background
    onSecondaryContainer = Color(0xFFF1E4CE),  // 6.8:1 on secondaryContainer

    tertiary = Color(0xFFC4B197),
    onTertiary = Color(0xFF241B0E),
    tertiaryContainer = Color(0xFF443725),
    onTertiaryContainer = Color(0xFFEDE0CB),

    background = Color(0xFF121110),
    onBackground = Color(0xFFECE8E2),          // 15.5:1 on the background
    surface = Color(0xFF181715),
    onSurface = Color(0xFFECE8E2),             // 14.7:1 on surface
    surfaceVariant = Color(0xFF27241F),
    onSurfaceVariant = Color(0xFFC7C0B6),      // 8.6:1 on surfaceVariant, 9.9:1 on surface

    // Borders lift with the surfaces. A hairline tuned for near-black disappears once the page
    // comes up, which would have traded one legibility problem for another.
    outline = Color(0xFF958E84),               // 5.8:1 on the background
    outlineVariant = Color(0xFF3A3530),        // dividers, not held to a text ratio

    surfaceTint = LyrenneGoldLight,
    surfaceBright = Color(0xFF3A3530),
    surfaceDim = Color(0xFF121110),
    surfaceContainerLowest = Color(0xFF0D0C0B),
    surfaceContainerLow = Color(0xFF161513),
    surfaceContainer = Color(0xFF1D1B19),
    surfaceContainerHigh = Color(0xFF2A2622),   // onSurfaceVariant 8.3:1 on it
    surfaceContainerHighest = Color(0xFF332E29),

    inverseSurface = Color(0xFFE8E6E2),
    inverseOnSurface = Color(0xFF1F1B16),
    inversePrimary = Color(0xFF6B4E1F),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3D0907),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    scrim = Color(0xFF000000),
)

private val LyrenneTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    // Section titles (Home shelves, artist sections, settings groups). Bold, so a section reads
    // as a level above the SemiBold/Medium titles of the tiles and rows beneath it.
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    )
)

private val LyrenneShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)


/**
 * Light theme, drawn from the flag of Cyprus.
 *
 * The three flag colours are used exactly as specified: copper #D57800 (Pantone 1385), olive
 * green #4E5B31 (Pantone 574) and white. Everything else here is derived from those two hues
 * rather than invented, so the palette stays honest to the source.
 *
 * Two deliberate departures, both for legibility rather than taste:
 *
 * - Copper is bright: as text on the page it measures 2.9:1, and `primary` is a text colour
 *   (headers, links, every TextButton, the current lyric line). So `primary` is a copper *ink*,
 *   #9A4A00, the flag hue darkened to 5.6:1 on the page. The exact flag copper misses even the
 *   3:1 non-text floor on the page, so nothing draws it; [CyprusCopper] stays for reference.
 * - The background is a warm off-white rather than pure #FFFFFF. Full white next to a large
 *   copper fill glares; a 2% warm tint keeps the flag's feel without the hotspot.
 */
@Suppress("unused")
private val CyprusCopper = Color(0xFFD57800)
private val CyprusCopperInk = Color(0xFF9A4A00)
private val CyprusGreen = Color(0xFF4E5B31)

private val LightColorScheme = lightColorScheme(
    primary = CyprusCopperInk,                 // 5.6:1 on the background, 6.2:1 on cards
    onPrimary = Color(0xFFFFFFFF),             // 6.3:1 on primary
    primaryContainer = Color(0xFFFFE3C4),      // copper, lightened for container fills
    onPrimaryContainer = Color(0xFF3D1F00),    // 12.2:1 on its container; primary on it 5.1:1

    secondary = CyprusGreen,
    onSecondary = Color(0xFFFFFFFF),           // 7.3:1 on the flag green; green on the page 6.5:1
    // Olive-grey, not a saturated green: selected chips sat olive next to copper selections.
    secondaryContainer = Color(0xFFE3E6D2),
    onSecondaryContainer = Color(0xFF252B14),  // 11.5:1

    tertiary = Color(0xFF7A5C31),              // midpoint of the two flag hues, for accents
    onTertiary = Color(0xFFFFFFFF),            // 6.2:1
    tertiaryContainer = Color(0xFFF3E0C7),
    onTertiaryContainer = Color(0xFF2C1B00),

    // The page is deliberately a shade or two down from white. At #FDFBF7 the background, the
    // cards and the white container tones were all within 3% of each other, so panels had no
    // visible edge and outlined controls looked borderless.
    background = Color(0xFFF6F1E9),
    onBackground = Color(0xFF1F1B16),          // 15.2:1
    surface = Color(0xFFF6F1E9),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFE9E0D1),
    onSurfaceVariant = Color(0xFF4F4639),      // 7.1:1 on surfaceVariant, 8.2:1 on the page
    outline = Color(0xFF6B5F52),               // 5.5:1 on background
    outlineVariant = Color(0xFFB5A692),        // dividers and control borders, visible on the page

    // Every role below is set explicitly on purpose. Anything left out falls back to Material's
    // default baseline palette, which is purple-tinted: it would clash with the copper and, worse,
    // put text on surfaces whose contrast nobody has checked. Menus, cards, snackbars and error
    // states all draw from these.
    surfaceTint = CyprusCopperInk,
    surfaceBright = Color(0xFFFDFBF7),
    surfaceDim = Color(0xFFD8D1C4),
    surfaceContainerLowest = Color(0xFFFFFDF9),   // raised cards read as lighter than the page
    surfaceContainerLow = Color(0xFFFCF8F1),
    surfaceContainer = Color(0xFFF8F2E8),
    surfaceContainerHigh = Color(0xFFEEE6D9),     // the rail; onSurfaceVariant 7.5:1 on it
    surfaceContainerHighest = Color(0xFFE4DCCC),

    inverseSurface = Color(0xFF352F27),
    inverseOnSurface = Color(0xFFF8F1E7),      // 12.4:1 on inverseSurface
    inversePrimary = Color(0xFFF3B266),        // copper lightened for use on the dark inverse

    error = Color(0xFF8C1D18),                 // warm red, sits with copper rather than fighting it
    onError = Color(0xFFFFFFFF),               // 8.7:1
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),      // 13.9:1

    scrim = Color(0xFF000000),
)

/**
 * The OS light/dark preference, read off the UI thread. Every check spawns a process (`reg`,
 * `gsettings`, `defaults`), which used to run synchronously inside composition, once per window,
 * and never again — so switching the OS theme did nothing until restart. Now one poller serves
 * every window and picks the change up within [POLL_MS].
 */
object SystemTheme {
    private const val POLL_MS = 30_000L
    // One blocking read seeds it, so the first frame is not painted in the wrong scheme. Main
    // touches this object before any window exists, so that read happens off composition.
    private val _isDark = MutableStateFlow(isSystemDarkTheme())
    val isDark: StateFlow<Boolean> = _isDark.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (true) {
                delay(POLL_MS)
                _isDark.value = isSystemDarkTheme()
            }
        }
    }
}

fun isSystemDarkTheme(): Boolean {
    return try {
        when {
            Platform.isWindows -> isWindowsDarkTheme()
            Platform.isMac -> isMacDarkTheme()
            else -> isLinuxDarkTheme()
        }
    } catch (_: Exception) {
        true // Default to dark
    }
}

private fun runForOutput(vararg cmd: String): String? = try {
    val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0) output.trim() else null
} catch (_: Exception) {
    null
}

private fun isWindowsDarkTheme(): Boolean {
    val output = runForOutput(
        "reg", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "/v", "AppsUseLightTheme"
    ) ?: return true
    return output.contains("0x0")
}

private fun isMacDarkTheme(): Boolean =
    runForOutput("defaults", "read", "-g", "AppleInterfaceStyle").equals("Dark", ignoreCase = true)

/**
 * GNOME 42+ keeps the preference in `color-scheme` ('prefer-dark'); the GTK theme name is often
 * plain `Adwaita` either way, so it is only the fallback. KDE writes its scheme to kdeglobals.
 */
private fun isLinuxDarkTheme(): Boolean {
    runForOutput("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.lowercase()?.let {
        if ("dark" in it) return true
        if ("light" in it) return false
        // 'default': GNOME's "no preference", which is light unless the GTK theme says otherwise.
    }
    runForOutput("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.let {
        if ("dark" in it.lowercase()) return true
    }
    val kdeglobals = File(
        System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "kdeglobals"
    )
    if (kdeglobals.isFile) {
        val scheme = kdeglobals.readLines().firstOrNull { it.startsWith("ColorScheme=") }
        if (scheme != null) return "dark" in scheme.lowercase()
    }
    return false
}

/**
 * Selected = the copper/gold family everywhere. FilterChip defaults to secondaryContainer, which
 * put olive selections beside copper ones in light and was near-invisible in dark.
 */
@Composable
fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * Per-scheme values Material has no role for. Read with `LocalLyrenneExtras.current`.
 *
 * [artWashAlpha] is the strength of the cover-colour wash (see ArtColor). Over near-black a
 * strong wash stays dark, but over the light page 0.32 turned it salmon and pulled primary text
 * to 3.7:1. At 0.10 the worst hue (pure blue) leaves primary at 4.5:1 on the page; gold on a
 * 0.26 red wash in dark is 8.7:1.
 */
@Immutable
class LyrenneExtras(val artWashAlpha: Float, val railColor: Color)

private val DarkExtras = LyrenneExtras(0.26f, railColor = DarkColorScheme.surfaceContainerLowest)
// A pure white rail glared next to the warm page.
private val LightExtras = LyrenneExtras(0.10f, railColor = LightColorScheme.surfaceContainerHigh)

val LocalLyrenneExtras = staticCompositionLocalOf { DarkExtras }

@Composable
fun LyrenneTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> SystemTheme.isDark.collectAsState().value
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LyrenneTypography,
        shapes = LyrenneShapes
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides 40.dp,
            LocalLyrenneExtras provides if (darkTheme) DarkExtras else LightExtras,
            content = content
        )
    }
}
