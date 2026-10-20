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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.ui.components.wheelStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    player: DesktopPlayer,
    /** Null when the screen was opened from the rail: it is a destination, not a step down. */
    onBack: (() -> Unit)? = null
) {
    val prefs by PreferencesManager.preferences.collectAsState()
    val presets = remember { player.getEqualizerPresets() }
    val bandFreqs = remember { player.getEqualizerBands() }

    // Local state for sliders (avoids saving on every drag pixel)
    var localBands by remember(prefs.eqBands) { mutableStateOf(prefs.eqBands) }
    var localPreamp by remember(prefs.eqPreamp) { mutableStateOf(prefs.eqPreamp) }

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
                .verticalScroll(rememberScrollState())
        ) {
            // Preset selector
            if (presets.isNotEmpty()) {
                Text("Preset", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val displayPresets = listOf("Custom") + presets
                    var expanded by remember { mutableStateOf(false) }
                    val currentPreset = prefs.eqPreset ?: "Custom"

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentPreset,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().width(250.dp).suppressMediaKeys()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            displayPresets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset) },
                                    onClick = {
                                        expanded = false
                                        if (preset == "Custom") {
                                            PreferencesManager.setEqPreset(null)
                                            player.applyEqualizer()
                                        } else {
                                            player.setEqualizerPreset(preset)
                                            // Update local state from new preset values
                                            localBands = PreferencesManager.preferences.value.eqBands
                                            localPreamp = PreferencesManager.preferences.value.eqPreamp
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Preamp slider
            Text("Preamp", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${localPreamp.toInt()} dB",
                    modifier = Modifier.width(55.dp),
                    textAlign = TextAlign.End,
                    fontSize = 12.sp
                )
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
            }

            Spacer(Modifier.height(24.dp))

            // Band sliders
            Text("Bands", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                localBands.forEachIndexed { index, gain ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        // dB value label
                        Text(
                            "${gain.toInt()}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            fontSize = 9.sp,
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
