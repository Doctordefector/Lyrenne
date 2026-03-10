package com.metrolist.music.desktop.ui.screens

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
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.settings.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val prefs by PreferencesManager.preferences.collectAsState()
    val presets = remember { player.getEqualizerPresets() }
    val bandFreqs = remember { player.getEqualizerBands() }

    // Local state for sliders (avoids saving on every drag pixel)
    var localBands by remember(prefs.eqBands) { mutableStateOf(prefs.eqBands) }
    var localPreamp by remember(prefs.eqPreamp) { mutableStateOf(prefs.eqPreamp) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
            Text("Equalizer", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = prefs.eqEnabled,
                onCheckedChange = { player.setEqualizerEnabled(it) }
            )
        }

        Spacer(Modifier.height(16.dp))

        if (!prefs.eqEnabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Enable the equalizer to customize your sound",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
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
                            modifier = Modifier.menuAnchor().width(250.dp)
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

                        // Vertical slider (rotated horizontal slider)
                        // Using a Column with a vertical Slider
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
                                .width(48.dp),
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

private fun formatFreq(hz: Float): String {
    return if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
}
