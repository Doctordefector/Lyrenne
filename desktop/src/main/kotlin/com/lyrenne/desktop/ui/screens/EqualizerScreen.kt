package com.lyrenne.desktop.ui.screens

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.lyrenne.desktop.ui.components.blockPointerInput
import com.lyrenne.desktop.ui.components.ToolHeader
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.eq.EqPreset
import com.lyrenne.desktop.sound.eq.EqPresets
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.components.wheelStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    player: DesktopPlayer,
    /** Null when the screen was opened from the rail: it is a destination, not a step down. */
    onBack: (() -> Unit)? = null
) {
    val prefs by PreferencesManager.preferences.collectAsState()
    val presets = remember { player.getEqualizerPresets() }
    // VLC's own band table, never a hardcoded one: AutoEQ fits to whatever libvlc reports
    val bandFreqs = remember { player.getEqualizerBands() }
    val userPresets by EqPresets.presets.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { EqPresets.load() } }

    // Local state for sliders (avoids saving on every drag pixel)
    var localBands by remember(prefs.eqBands) { mutableStateOf(prefs.eqBands) }
    var localPreamp by remember(prefs.eqPreamp) { mutableStateOf(prefs.eqPreamp) }

    var showAutoEq by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<EqPreset?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var lastAutoEq by remember { mutableStateOf<Pair<EqPreset, Double>?>(null) }

    // The command palette's "Correct my headphones" lands here with the search open
    val launch by AutoEqLauncher.pending.collectAsState()
    LaunchedEffect(launch) {
        if (launch) {
            AutoEqLauncher.consume()
            if (bandFreqs.isNotEmpty()) showAutoEq = true
        }
    }

    // The saved preset these settings are, so the picker names it rather than saying Custom
    val matching = remember(userPresets, prefs.eqPreamp, prefs.eqBands) {
        if (userPresets == null) null else EqPresets.matching(prefs.eqPreamp, prefs.eqBands)
    }
    val currentName = prefs.eqPreset ?: matching?.name ?: "Custom"
    val currentAsPreset = { name: String -> EqPreset(name, prefs.eqPreamp, bandFreqs, prefs.eqBands) }

    if (showAutoEq) AutoEqDialog(
        player, bandFreqs,
        onApplied = { p, rms -> lastAutoEq = p to rms; showAutoEq = false },
        onDismiss = { showAutoEq = false }
    )
    if (showImport) ImportPresetDialog(player, bandFreqs, onImported = { showImport = false }, onDismiss = { showImport = false })
    exporting?.let { ExportPresetDialog(it) { exporting = null } }
    if (showSave) SavePresetDialog(
        suggested = matching?.name ?: prefs.eqPreset ?: "",
        onSave = { name -> EqPresets.save(currentAsPreset(name)); showSave = false },
        onDismiss = { showSave = false }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(LyrenneTokens.contentPadding)
    ) {
        ToolHeader("Equalizer", onBack) {
            // Labelled, and the controls stay in view: an unlabelled switch over an empty page
            // gave no hint what turning it on would do.
            Text(
                if (prefs.eqEnabled) "On" else "Off",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(
                checked = prefs.eqEnabled,
                onCheckedChange = { player.setEqualizerEnabled(it) }
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Outside the On/Off dimming: picking headphones turns the equalizer on.
            HeadphoneCard(
                correcting = matching?.name?.takeIf { AUTOEQ_TAG in it }?.substringBefore(AUTOEQ_TAG),
                fitNote = lastAutoEq?.takeIf { it.first.name == matching?.name }?.second,
                available = bandFreqs.isNotEmpty(),
                onFind = { showAutoEq = true }
            )
            Spacer(Modifier.height(20.dp))

            if (!prefs.eqEnabled) {
                Text(
                    "Turn the equalizer on to apply these settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Column(
                Modifier
                    .alpha(if (prefs.eqEnabled) 1f else 0.45f)
                    .then(if (prefs.eqEnabled) Modifier else Modifier.blockPointerInput())
            ) {
                Text("Preset", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        OutlinedTextField(
                            value = currentName,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().widthIn(max = 380.dp).fillMaxWidth().suppressMediaKeys()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Custom") },
                                onClick = {
                                    expanded = false
                                    PreferencesManager.setEqPreset(null)
                                    player.applyEqualizer()
                                }
                            )
                            val mine = userPresets.orEmpty()
                            if (mine.isNotEmpty()) {
                                MenuHeading("Your presets")
                                mine.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            expanded = false
                                            val (pre, gains) = p.gainsFor(bandFreqs.ifEmpty { p.bandsHz })
                                            applyEq(player, pre, gains, "preset ${p.name}")
                                        }
                                    )
                                }
                            }
                            if (presets.isNotEmpty()) {
                                MenuHeading("Built in")
                                presets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset) },
                                        onClick = {
                                            expanded = false
                                            player.setEqualizerPreset(preset)
                                            // Update local state from new preset values
                                            localBands = PreferencesManager.preferences.value.eqBands
                                            localPreamp = PreferencesManager.preferences.value.eqPreamp
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Tip("Save as a preset") {
                        IconButton(onClick = { showSave = true }, enabled = bandFreqs.isNotEmpty()) { Icon(Icons.Default.Save, "Save as a preset") }
                    }
                    Tip("Import a preset") {
                        IconButton(onClick = { showImport = true }, enabled = bandFreqs.isNotEmpty()) { Icon(Icons.Default.FileDownload, "Import a preset") }
                    }
                    Tip("Share these settings") {
                        IconButton(onClick = { exporting = matching ?: currentAsPreset(currentName) }, enabled = bandFreqs.isNotEmpty()) {
                            Icon(Icons.Default.Share, "Share these settings")
                        }
                    }
                    if (matching != null) Tip("Delete this preset") {
                        IconButton(onClick = { EqPresets.delete(matching.name) }) { Icon(Icons.Default.Delete, "Delete this preset") }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Preamp slider
                Text("Preamp", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = localPreamp,
                        onValueChange = { localPreamp = it },
                        onValueChangeFinished = {
                            player.setEqualizerPreamp(localPreamp)
                        },
                        valueRange = -20f..20f,
                        modifier = Modifier.weight(1f)
                            .wheelStep({ PreferencesManager.preferences.value.eqPreamp }, 1f, -20f..20f) {
                                localPreamp = it
                                player.setEqualizerPreamp(it)
                            }
                    )
                    Spacer(Modifier.width(12.dp))
                    DbField(localPreamp, { localPreamp = it; player.setEqualizerPreamp(it) }, Modifier.width(64.dp), label = "Preamp in dB")
                    Text(" dB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "12 dB leaves the volume as it is. Lower it if boosted bands distort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                // Band sliders
                Text("Bands", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Drag, scroll, or click a number and type the dB you want.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    localBands.forEachIndexed { index, gain ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            // The value, typed
                            DbField(
                                gain,
                                { v ->
                                    localBands = localBands.toMutableList().also { it[index] = v }
                                    player.setEqualizerBand(index, v)
                                },
                                Modifier.widthIn(max = 56.dp).fillMaxWidth().padding(horizontal = 2.dp),
                                label = "${formatFreq(bandFreqs.getOrNull(index) ?: 0f)} Hz band in dB"
                            )

                            // A horizontal Slider turned upright: rotated about its corner, with the
                            // layout swapping width and height. It used to be a 48 dp horizontal
                            // slider standing in for a vertical one.
                            Slider(
                                value = gain,
                                onValueChange = { newGain ->
                                    localBands = localBands.toMutableList().also { it[index] = newGain }
                                },
                                onValueChangeFinished = {
                                    player.setEqualizerBand(index, localBands[index])
                                },
                                valueRange = -20f..20f,
                                modifier = Modifier
                                    .weight(1f)
                                    .width(40.dp)
                                    .upright()
                                    .wheelStep({ PreferencesManager.preferences.value.eqBands.getOrElse(index) { 0f } }, 1f, -20f..20f) {
                                        localBands = localBands.toMutableList().also { bands -> bands[index] = it }
                                        player.setEqualizerBand(index, it)
                                    },
                            )

                            // Frequency label
                            Text(
                                formatFreq(bandFreqs.getOrNull(index) ?: 0f),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Reset button
                OutlinedButton(
                    onClick = {
                        localBands = List(10) { 0f }
                        localPreamp = 12f
                        PreferencesManager.setEqPreamp(12f)
                        PreferencesManager.setEqBands(List(10) { 0f })
                        player.applyEqualizer()
                    }
                ) {
                    Text("Reset to Flat")
                }
            }
        }
    }
}

/** How AutoEQ presets are named: "<headphones> (AutoEQ, <who measured them>)". */
internal const val AUTOEQ_TAG = " (AutoEQ"

/** A non-clickable label between groups in the preset menu. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)
    )
}

/** The AutoEQ way in: what is being corrected now, and the button to find your headphones. */
@Composable
private fun HeadphoneCard(correcting: String?, fitNote: Double?, available: Boolean, onFind: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (correcting != null) "Correcting for $correcting" else "Headphone correction",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    when {
                        !available -> "Needs VLC's equalizer, which isn't loaded."
                        correcting != null && fitNote != null ->
                            "From AutoEQ measurements. The bands follow the correction to within ${"%.1f".format(fitNote)} dB on average."
                        correcting != null -> "From AutoEQ measurements, saved in your presets."
                        else -> "Make your headphones sound neutral with a measured AutoEQ profile."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onFind, enabled = available) {
                Text(if (correcting != null) "Change headphones" else "Find my headphones")
            }
        }
    }
}

/** Lays a horizontal control out vertically, low end at the bottom. */
private fun Modifier.upright(): Modifier = this
    .graphicsLayer {
        rotationZ = 270f
        transformOrigin = TransformOrigin(0f, 0f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth, maxHeight = constraints.maxWidth
            )
        )
        layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
    }

private fun formatFreq(hz: Float): String {
    return if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
}
