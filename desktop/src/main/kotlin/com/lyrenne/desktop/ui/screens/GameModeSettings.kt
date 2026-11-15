package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.gamemode.GameMode
import com.lyrenne.desktop.gamemode.GameModePrefs
import com.lyrenne.desktop.gamemode.GlobalHotkeys
import com.lyrenne.desktop.gamemode.HotkeyAction
import com.lyrenne.desktop.gamemode.HotkeyBinding
import com.lyrenne.desktop.gamemode.HotkeyDefaults
import com.lyrenne.desktop.gamemode.HotkeyPlan
import com.lyrenne.desktop.gamemode.HotkeyStatus
import com.lyrenne.desktop.gamemode.KeyCombo
import com.lyrenne.desktop.gamemode.OverlayCorner
import com.lyrenne.desktop.gamemode.OverlayMode
import com.lyrenne.desktop.gamemode.OverlayScreens
import com.lyrenne.desktop.gamemode.OverlaySize
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.SecondaryAction
import com.lyrenne.desktop.ui.components.wheelStep

/** Key of the Game Mode group header, for deep links (the command palette). */
const val SETTINGS_GROUP_GAME_MODE = "group:Game Mode"

/** The one hotkey row recording a new combo, if any. Only one records at a time. */
private val recording = mutableStateOf<HotkeyAction?>(null)

/** The Game Mode group in Settings: the in-game overlay and global hotkeys. */
fun LazyListScope.gameModeSettings(prefs: GameModePrefs) {
    item(key = SETTINGS_GROUP_GAME_MODE) {
        SettingsGroupHeader("Game Mode")
    }
    item {
        if (LocalSettingsFilter.current.isBlank()) {
            Text(
                "Control your music and see lyrics while you play, without alt-tabbing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
    }

    // ---- Overlay
    item(key = "section:Overlay") { SettingsSectionHeader("In-game overlay") }
    if (!GameMode.overlaySupported) {
        item {
            SettingsItem(
                icon = Icons.Default.Layers,
                title = "In-game overlay",
                subtitle = "The overlay is Windows only for now. Global hotkeys below still work."
            )
        }
    } else {
        item {
            SettingsItem(
                icon = Icons.Default.Layers,
                title = "Overlay",
                subtitle = "Shows over borderless and windowed games, never takes focus, and clicks go " +
                    "through it. Games in exclusive fullscreen hide it.",
                trailing = {
                    SecondaryAction("Preview", Icons.Default.Visibility, onClick = { GameMode.preview() })
                }
            ) {
                Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    Segmented(
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                        options = OverlayMode.entries,
                        selected = prefs.overlayMode,
                        label = { it.label },
                        onSelect = { m -> PreferencesManager.updateGameMode { it.copy(overlayMode = m) } }
                    )
                }
                Text(
                    when (prefs.overlayMode) {
                        OverlayMode.OFF -> "Nothing shows on its own. The pin hotkey and hotkey confirmations still do."
                        OverlayMode.TOAST -> "The cover, title and artist slide in when the song changes, then hide."
                        OverlayMode.LYRICS -> "The line being sung and the next one, while a song with synced lyrics plays."
                        OverlayMode.ALWAYS -> "The song and its lyrics stay on screen the whole time, paused or playing."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp)
                )
            }
        }
        item {
            SettingsItem(
                icon = Icons.Default.PictureInPicture,
                title = "Position",
                subtitle = prefs.overlayCorner.label,
                trailing = {
                    CornerPicker(prefs.overlayCorner) { c -> PreferencesManager.updateGameMode { it.copy(overlayCorner = c) } }
                }
            )
        }
        item {
            SettingsItem(
                icon = Icons.Default.AspectRatio,
                title = "Size",
                subtitle = "Scales with your display settings",
                trailing = {
                    Segmented(
                        options = OverlaySize.entries,
                        selected = prefs.overlaySize,
                        label = { it.label },
                        onSelect = { s -> PreferencesManager.updateGameMode { it.copy(overlaySize = s) } }
                    )
                }
            )
        }
        item {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val value = dragging ?: prefs.overlayOpacity
            SettingsItem(
                icon = Icons.Default.Opacity,
                title = "Opacity",
                subtitle = "${(value * 100).toInt()}%",
                trailing = {
                    Slider(
                        value = value,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val v = dragging ?: return@Slider
                            dragging = null
                            PreferencesManager.updateGameMode { it.copy(overlayOpacity = v) }
                        },
                        valueRange = GameModePrefs.MIN_OPACITY..1f,
                        modifier = Modifier.width(160.dp)
                            .wheelStep({ PreferencesManager.preferences.value.gameMode.overlayOpacity }, 0.05f, GameModePrefs.MIN_OPACITY..1f) { v ->
                                PreferencesManager.updateGameMode { it.copy(overlayOpacity = v) }
                            }
                    )
                }
            )
        }
        item {
            val screens = remember { OverlayScreens.list() }
            if (screens.size > 1 || prefs.overlayMonitor != GameModePrefs.MONITOR_FOLLOW) {
                var open by remember { mutableStateOf(false) }
                val current = screens.firstOrNull { it.id == prefs.overlayMonitor }?.label ?: "The game's screen"
                SettingsItem(
                    icon = Icons.Default.Monitor,
                    title = "Screen",
                    subtitle = "Follows the window you are playing in, or stays on one screen",
                    trailing = {
                        Box {
                            OutlinedButton(onClick = { open = true }) {
                                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(open, onDismissRequest = { open = false }) {
                                DropdownMenuItem(
                                    text = { Text("The game's screen") },
                                    onClick = {
                                        open = false
                                        PreferencesManager.updateGameMode { it.copy(overlayMonitor = GameModePrefs.MONITOR_FOLLOW) }
                                    }
                                )
                                screens.forEach { screen ->
                                    DropdownMenuItem(
                                        text = { Text(screen.label) },
                                        onClick = {
                                            open = false
                                            PreferencesManager.updateGameMode { it.copy(overlayMonitor = screen.id) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // ---- Hotkeys
    item(key = "section:Hotkeys") { SettingsSectionHeader("Global hotkeys") }
    val unavailable = GlobalHotkeys.unavailableReason
    if (unavailable != null) {
        item { SettingsItem(icon = Icons.Default.Keyboard, title = "Global hotkeys", subtitle = unavailable) }
        return
    }
    item {
        val error by GlobalHotkeys.startError.collectAsState()
        SettingsItem(
            icon = Icons.Default.Keyboard,
            title = "Global hotkeys",
            subtitle = error ?: "Control music while a game or any other app has focus",
            trailing = {
                Switch(
                    checked = prefs.hotkeysEnabled,
                    onCheckedChange = { on -> PreferencesManager.updateGameMode { it.copy(hotkeysEnabled = on) } }
                )
            }
        )
    }
    if (!prefs.hotkeysEnabled) return
    items(HotkeyAction.entries.size) { i ->
        HotkeyRow(HotkeyAction.entries[i], prefs)
    }
    item {
        val filter = LocalSettingsFilter.current
        if (filter.isNotBlank()) return@item
        val isDefault = HotkeyAction.entries.all { prefs.binding(it) == HotkeyDefaults.bindings.getValue(it) }
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Click a combo, then press the keys you want.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    recording.value = null
                    GlobalHotkeys.pause(false)
                    PreferencesManager.updateGameMode { it.copy(hotkeys = HotkeyDefaults.bindings) }
                },
                enabled = !isDefault
            ) {
                Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun HotkeyRow(action: HotkeyAction, prefs: GameModePrefs) {
    val filter = LocalSettingsFilter.current
    val binding = prefs.binding(action)
    if (filter.isNotBlank() && !action.label.contains(filter, true) && !binding.combo.format().contains(filter, true) &&
        !"hotkey".contains(filter, true)
    ) return
    val statuses by GlobalHotkeys.status.collectAsState()
    val status = statuses[action] ?: if (binding.enabled) HotkeyStatus.Pending else HotkeyStatus.Off
    val isRecording = recording.value == action
    var hint by remember(action) { mutableStateOf<String?>(null) }

    fun save(b: HotkeyBinding) = PreferencesManager.updateGameMode { it.copy(hotkeys = it.hotkeys + (action to b)) }

    if (isRecording) {
        ComboRecorder(
            onCombo = { combo ->
                val problem = KeyCombo.problem(combo)
                val holder = HotkeyPlan.holderOf(combo, prefs.hotkeys, except = action)
                when {
                    problem != null -> hint = "${combo.format()}: $problem"
                    holder != null -> hint = "${combo.format()} is already set for \"${holder.label}\"."
                    else -> {
                        hint = null
                        recording.value = null
                        save(HotkeyBinding(combo, enabled = true))
                    }
                }
            },
            onCancel = { hint = null; recording.value = null }
        )
    }

    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (binding.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            ComboChip(
                text = if (isRecording) "Press keys" else binding.combo.format(),
                recording = isRecording,
                problem = binding.enabled && status.message != null,
                onClick = {
                    hint = null
                    recording.value = if (isRecording) null else action
                }
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = binding.enabled,
                onCheckedChange = { on -> save(binding.copy(enabled = on)) },
                modifier = Modifier.semantics { contentDescription = "${action.label} hotkey" }
            )
        }
        val message = when {
            isRecording -> hint ?: "Press the combo now, or Esc to cancel. If nothing happens, another app is using it."
            binding.enabled -> status.message
            else -> null
        }
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isRecording && hint == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/**
 * Listens for the next key combo while a row is recording.
 *
 * Through AWT's dispatcher rather than a Compose key handler: the window's own shortcuts run
 * first in Compose (Ctrl+K would open the palette instead of being recorded), and this sees the
 * key before any of them and consumes it. Global hotkeys are paused for as long as it listens,
 * since a registered hotkey never reaches any window at all.
 */
@Composable
private fun ComboRecorder(onCombo: (KeyCombo) -> Unit, onCancel: () -> Unit) {
    val currentOnCombo by rememberUpdatedState(onCombo)
    val currentOnCancel by rememberUpdatedState(onCancel)
    DisposableEffect(Unit) {
        GlobalHotkeys.pause(true)
        val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id == java.awt.event.KeyEvent.KEY_PRESSED) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ESCAPE -> java.awt.EventQueue.invokeLater { currentOnCancel() }
                    in MODIFIER_KEYS -> Unit
                    else -> KeyCombo.fromAwt(e.keyCode, e.modifiersEx)?.let { combo ->
                        java.awt.EventQueue.invokeLater { currentOnCombo(combo) }
                    }
                }
            }
            true // every key goes to the recorder while it listens
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose {
            manager.removeKeyEventDispatcher(dispatcher)
            GlobalHotkeys.pause(false)
        }
    }
}

private val MODIFIER_KEYS = setOf(
    java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_ALT, java.awt.event.KeyEvent.VK_SHIFT,
    java.awt.event.KeyEvent.VK_WINDOWS, java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.VK_ALT_GRAPH,
    java.awt.event.KeyEvent.VK_CAPS_LOCK, java.awt.event.KeyEvent.VK_NUM_LOCK,
)

@Composable
private fun ComboChip(text: String, recording: Boolean, problem: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val border = when {
        recording -> colors.primary
        problem -> colors.error
        else -> colors.outline
    }
    Box(
        Modifier
            .widthIn(min = 150.dp)
            .clip(shape)
            .background(if (recording) colors.primaryContainer else colors.surfaceContainerHigh)
            .border(if (recording) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = if (recording) null else FontFamily.Monospace,
            color = if (recording) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 1
        )
    }
}

/** A tiny screen with the six places the overlay can go. Click one to move it there. */
@Composable
private fun CornerPicker(selected: OverlayCorner, onSelect: (OverlayCorner) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(width = 132.dp, height = 78.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHighest)
            .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        OverlayCorner.entries.forEach { corner ->
            val align = when (corner) {
                OverlayCorner.TOP_LEFT -> Alignment.TopStart
                OverlayCorner.TOP_CENTER -> Alignment.TopCenter
                OverlayCorner.TOP_RIGHT -> Alignment.TopEnd
                OverlayCorner.BOTTOM_LEFT -> Alignment.BottomStart
                OverlayCorner.BOTTOM_CENTER -> Alignment.BottomCenter
                OverlayCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
            }
            val isSelected = corner == selected
            Box(
                Modifier
                    .align(align)
                    .size(width = 34.dp, height = 18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) colors.primary else Color.Transparent)
                    .border(1.dp, if (isSelected) colors.primary else colors.outline, RoundedCornerShape(4.dp))
                    .clickable { onSelect(corner) }
                    .semantics { contentDescription = corner.label }
            )
        }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                icon = {}
            ) { Text(label(option), maxLines = 1) }
        }
    }
}
