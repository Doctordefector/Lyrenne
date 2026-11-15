package com.lyrenne.desktop.ui.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.rewind.RewindPrompt
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.rewind.RewindScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

/**
 * Draws Stage, Rewind and the Rewind offer over the whole app. App.kt places this once, after
 * its main Row, so it sits on top of everything without any screen knowing about it.
 */
@Composable
fun StageRewindHost(player: DesktopPlayer) {
    Box(Modifier.fillMaxSize()) {
        RewindOffer(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 108.dp))

        AnimatedVisibility(
            visible = StageMode.isOpen,
            enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.985f),
            exit = fadeOut(tween(250)),
        ) {
            StageScreen(player, onClose = StageMode::close)
        }
        val period = RewindMode.period
        AnimatedVisibility(visible = period != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            // Holds the last period through the exit fade.
            val shown = remember { mutableStateOf(period) }
            if (period != null) shown.value = period
            shown.value?.let { RewindScreen(player, it, onClose = RewindMode::close) }
        }
    }
}

/**
 * The once-only "your Rewind is ready" note. Looks once, a little after launch, and shows at most
 * one offer per period ever: the key is stored the moment it appears, so dismissing it, opening it
 * or ignoring it all end the same way. Off entirely with the Settings switch.
 */
@Composable
private fun RewindOffer(modifier: Modifier) {
    var offer by remember { mutableStateOf<RewindPrompt.Offer?>(null) }
    LaunchedEffect(Unit) {
        delay(OFFER_DELAY_MS)
        val prefs = PreferencesManager.preferences.value
        val zone = ZoneId.systemDefault()
        val found = try {
            withContext(Dispatchers.IO) {
                RewindPrompt.due(LocalDate.now(zone), prefs.rewindPromptsSeen, prefs.rewindReminders) { period ->
                    val range = period.range(zone)
                    DatabaseHelper.getTotalPlayTimeInRange(range.first, range.last)
                }
            }
        } catch (e: Exception) {
            Timber.w("Rewind offer check failed: ${e.message}")
            null
        }
        if (found != null && !StageMode.isOpen && !RewindMode.isOpen) {
            PreferencesManager.markRewindPromptSeen(found.key)
            offer = found
            delay(OFFER_VISIBLE_MS)
            offer = null
        }
    }
    AnimatedVisibility(
        visible = offer != null && !StageMode.isOpen && !RewindMode.isOpen,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
        modifier = modifier
    ) {
        val shown = remember { mutableStateOf(offer) }
        if (offer != null) shown.value = offer
        val o = shown.value ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(o.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { offer = null; RewindMode.open(o.period) }) { Text("See it") }
                Tip("Dismiss") {
                    IconButton(onClick = { offer = null }) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

private const val OFFER_DELAY_MS = 12_000L
private const val OFFER_VISIBLE_MS = 45_000L

/** Settings: Stage and Rewind. One call from SettingsScreen. */
@Composable
fun StageRewindSettings(filter: String = "") {
    val prefs by PreferencesManager.preferences.collectAsState()
    @Composable
    fun row(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, trailing: @Composable () -> Unit) {
        if (filter.isNotBlank() && !title.contains(filter, true) && !subtitle.contains(filter, true)) return
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            trailingContent = trailing,
        )
    }
    row(
        "Stage",
        "Big art and karaoke lyrics over the whole window, for parties and second screens. $STAGE_SHORTCUT_LABEL opens it, F11 goes full screen.",
        Icons.Outlined.Fullscreen
    ) { OutlinedButton(onClick = StageMode::open) { Text("Open Stage") } }
    row(
        "Rewind",
        "A recap of what you played, from your history on this computer. Nothing leaves it.",
        Icons.Outlined.History
    ) { OutlinedButton(onClick = { RewindMode.open() }) { Text("Open Rewind") } }
    row(
        "Tell me when a Rewind is ready",
        "Once in December for your year, and after a busy month. Shown once each, never again.",
        Icons.Outlined.History
    ) { Switch(checked = prefs.rewindReminders, onCheckedChange = PreferencesManager::setRewindReminders) }
}
