package com.lyrenne.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.sponsorblock.SponsorBlock
import com.lyrenne.desktop.sound.sponsorblock.trustedFor
import kotlinx.coroutines.delay

/** What a skipped category is called in "Skipped ...". */
private fun noun(category: String) = when (category) {
    "music_offtopic" -> "non-music part"
    "sponsor" -> "sponsor"
    "selfpromo" -> "self-promotion"
    "interaction" -> "reminder"
    "intro" -> "intro"
    "outro" -> "outro"
    "preview" -> "preview"
    "filler" -> "tangent"
    else -> "segment"
}

internal fun skipMessage(categories: List<String>): String {
    val names = categories.map(::noun).distinct()
    return "Skipped " + when (names.size) {
        0 -> "a segment"
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/**
 * "Skipped intro · Undo" after SponsorBlock jumps, for six seconds. Undo goes back to where the
 * segment starts and plays it from then on. A skip that ended the track has no Undo: the next
 * song is already playing.
 */
@Composable
fun SegmentSkipSnackbar(player: DesktopPlayer, modifier: Modifier = Modifier) {
    val notice by SponsorBlock.notice.collectAsState()
    val shown = notice
    LaunchedEffect(shown?.serial) {
        if (shown == null) return@LaunchedEffect
        delay(6_000)
        SponsorBlock.dismiss(shown)
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        val n = shown ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 6.dp
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = if (n.undoable) 6.dp else 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ContentCut, null, Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(skipMessage(n.categories), style = MaterialTheme.typography.bodyMedium)
                if (n.undoable) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { SponsorBlock.undo(player, n) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
                    ) { Text("Undo") }
                }
            }
        }
    }
}

/**
 * Where the current song's skippable segments sit, as fractions of [durationMs], for the seek
 * bar. Empty while SponsorBlock is off or the song has none.
 */
@Composable
fun rememberSkipMarks(player: DesktopPlayer, durationMs: Long): List<ClosedFloatingPointRange<Float>> {
    val segs by SponsorBlock.segments.collectAsState()
    val songId by player.select { it.currentSong?.id }
    val s = segs
    return remember(s, songId, durationMs) {
        if (s == null || s.songId != songId || durationMs <= 0 || !PreferencesManager.preferences.value.sponsorBlockEnabled) emptyList()
        else s.segments.filter { it.trustedFor(durationMs) }.map {
            (it.startMs.toFloat() / durationMs).coerceIn(0f, 1f)..(it.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }
}

/**
 * The marks' colour: olive, the flag green, in light; a pale olive in dark. Distinct from the
 * played (primary) and unplayed (outline) parts of the track in both, and quiet beside them.
 */
@Composable
internal fun skipMarkColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFA9B98A) else MaterialTheme.colorScheme.secondary
