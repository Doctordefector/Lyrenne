package com.lyrenne.desktop.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lyrenne.desktop.settings.ThemeMode

/**
 * Dark theme, built from the same tokens as the website and the app mark: near-black surfaces,
 * hairline borders, and the bronze accent, rather than Material's stock purple.
 *
 * The accent is #A37C43 exactly, the same value the logo ring and the site use. It appears as a
 * *fill* only. As a foreground on near-black it measures 4.0:1, under the 4.5:1 AA wants for body
 * text, so anywhere the accent has to be legible as text or an icon the lighter #D8B57C from the
 * top of the logo's gradient is used instead, which measures 8.6:1.
 */
private val LyrenneGold = Color(0xFFA37C43)
private val LyrenneGoldLight = Color(0xFFD8B57C)

private val DarkColorScheme = darkColorScheme(
    primary = LyrenneGoldLight,                // foreground-capable gold, 8.6:1 on the background
    onPrimary = Color(0xFF2A1B06),             // 7.7:1
    primaryContainer = LyrenneGold,            // the brand value, used as a fill
    onPrimaryContainer = Color(0xFF1F1400),    // 6.5:1

    secondary = Color(0xFFC9B79B),             // desaturated gold for secondary accents
    onSecondary = Color(0xFF2A2015),
    secondaryContainer = Color(0xFF3D3225),
    onSecondaryContainer = Color(0xFFE9DCC8),

    tertiary = Color(0xFFB9A588),
    onTertiary = Color(0xFF241B0E),
    tertiaryContainer = Color(0xFF352A1B),
    onTertiaryContainer = Color(0xFFE6D8C1),

    background = Color(0xFF0A0A0A),            // site background
    onBackground = Color(0xFFE0E0E0),          // site body text
    surface = Color(0xFF111111),               // site panels
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF1C1A17),
    onSurfaceVariant = Color(0xFFB5ACA0),      // 8.0:1 on surfaceVariant

    outline = Color(0xFF8A7F72),               // visible borders, 5.1:1 on the background
    outlineVariant = Color(0xFF333333),        // the site's hairline, for dividers only

    surfaceTint = LyrenneGoldLight,
    surfaceBright = Color(0xFF2A2724),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF161514),
    surfaceContainerHigh = Color(0xFF1F1D1A),
    surfaceContainerHighest = Color(0xFF2A2724),

    inverseSurface = Color(0xFFE6E1D9),
    inverseOnSurface = Color(0xFF1F1B16),
    inversePrimary = Color(0xFF6B4E1F),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3D0907),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    scrim = Color(0xFF000000),
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
 * - Copper is bright. White on it measures 3.2:1 and copper as text on the background measures
 *   3.1:1, both short of the 4.5:1 WCAG AA needs for body text. Rather than darken the flag
 *   colour, copper stays exact and carries dark text instead (5.4:1). Copper is never used as a
 *   text colour on the background.
 * - The background is a warm off-white rather than pure #FFFFFF. Full white next to a large
 *   copper fill glares; a 2% warm tint keeps the flag's feel without the hotspot.
 */
private val CyprusCopper = Color(0xFFD57800)
private val CyprusGreen = Color(0xFF4E5B31)

private val LightColorScheme = lightColorScheme(
    primary = CyprusCopper,
    onPrimary = Color(0xFF2B1400),             // dark on copper: 5.4:1. White would be 3.2:1.
    primaryContainer = Color(0xFFFFDCBA),      // copper, lightened for container fills
    onPrimaryContainer = Color(0xFF4A2600),    // copper darkened to 9.6:1 on its container

    secondary = CyprusGreen,
    onSecondary = Color(0xFFFFFFFF),           // 7.3:1 on the flag green
    secondaryContainer = Color(0xFFD5DEC0),    // green, lightened
    onSecondaryContainer = Color(0xFF1B2408),

    tertiary = Color(0xFF7A5C31),              // midpoint of the two flag hues, for accents
    onTertiary = Color(0xFFFFFFFF),            // 6.2:1
    tertiaryContainer = Color(0xFFF3E0C7),
    onTertiaryContainer = Color(0xFF2C1B00),

    background = Color(0xFFFDFBF7),            // warm off-white, see note above
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFDFBF7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E7D9),
    onSurfaceVariant = Color(0xFF4F4639),      // 7.4:1 on surfaceVariant
    outline = Color(0xFF6B5F52),               // 6.0:1 on background
    outlineVariant = Color(0xFFD5C9BA),

    // Every role below is set explicitly on purpose. Anything left out falls back to Material's
    // default baseline palette, which is purple-tinted: it would clash with the copper and, worse,
    // put text on surfaces whose contrast nobody has checked. Menus, cards, snackbars and error
    // states all draw from these.
    surfaceTint = CyprusCopper,
    surfaceBright = Color(0xFFFDFBF7),
    surfaceDim = Color(0xFFDED8CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F3EB),
    surfaceContainer = Color(0xFFF2EDE4),
    surfaceContainerHigh = Color(0xFFECE7DE),
    surfaceContainerHighest = Color(0xFFE6E1D8),

    inverseSurface = Color(0xFF352F27),
    inverseOnSurface = Color(0xFFF8F1E7),      // 12.4:1 on inverseSurface
    inversePrimary = Color(0xFFF3B266),        // copper lightened for use on the dark inverse

    error = Color(0xFF8C1D18),                 // warm red, sits with copper rather than fighting it
    onError = Color(0xFFFFFFFF),               // 8.7:1
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),      // 13.9:1

    scrim = Color(0xFF000000),
)

fun isSystemDarkTheme(): Boolean {
    return try {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        when {
            osName.contains("win") -> isWindowsDarkTheme()
            osName.contains("mac") -> isMacDarkTheme()
            else -> isLinuxDarkTheme()
        }
    } catch (_: Exception) {
        true // Default to dark
    }
}

private fun isWindowsDarkTheme(): Boolean {
    return try {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.contains("0x0")
    } catch (_: Exception) {
        true
    }
}

private fun isMacDarkTheme(): Boolean {
    return try {
        val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.equals("Dark", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}

private fun isLinuxDarkTheme(): Boolean {
    return try {
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").start()
        val output = process.inputStream.bufferedReader().readText().trim().lowercase()
        process.waitFor()
        output.contains("dark")
    } catch (_: Exception) {
        true
    }
}

@Composable
fun LyrenneTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
