package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.settings.AppPreferences
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.listenbrainz.ListenBrainzManager
import com.lyrenne.desktop.sound.listenbrainz.TokenCheck
import com.lyrenne.desktop.sound.sponsorblock.SkipCategory
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.theme.selectedChipColors
import kotlinx.coroutines.delay

/**
 * SponsorBlock's settings, for the Playback & audio group: one switch, and the categories once
 * it is on. The subtitle says what switching it on sends, because it asks a third party.
 */
fun LazyListScope.sponsorBlockSettings(preferences: AppPreferences) {
    item(key = "section:SponsorBlock") {
        val on = preferences.sponsorBlockEnabled
        SettingsItem(
            icon = Icons.Default.ContentCut,
            title = "Skip non-music parts (SponsorBlock)",
            subtitle = if (on) "Skipping ${SkipCategory.entries.filter { it.api in preferences.sponsorBlockCategories }.joinToString { it.label.lowercase() }.ifEmpty { "nothing yet: pick below" }}"
            else "Jump past talking, skits and sponsor reads. Asks sponsor.ajay.app using only a 4-character hash, never what you play.",
            trailing = {
                Switch(checked = on, onCheckedChange = { PreferencesManager.setSponsorBlockEnabled(it) })
            }
        ) {
            if (on && LocalSettingsFilter.current.isBlank()) SponsorBlockCategories(preferences.sponsorBlockCategories)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SponsorBlockCategories(selected: Set<String>) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SkipCategory.entries.forEach { c ->
                val isOn = c.api in selected
                Tip(c.hint) {
                    FilterChip(
                        selected = isOn,
                        onClick = { PreferencesManager.setSponsorBlockCategory(c.api, !isOn) },
                        label = { Text(c.label) },
                        leadingIcon = if (isOn) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        colors = selectedChipColors()
                    )
                }
            }
        }
        Text(
            "Segments come from SponsorBlock's community. A skip shows Undo, and anything you seek into plays.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** ListenBrainz, for the Integrations group under Last.fm. */
fun LazyListScope.listenBrainzSettings(preferences: AppPreferences) {
    item(key = "section:ListenBrainz") {
        SettingsSectionHeader("ListenBrainz")
    }
    item {
        val token = preferences.listenBrainzToken
        SettingsItem(
            icon = Icons.Default.History,
            title = "ListenBrainz scrobbling",
            subtitle = if (token != null) "Sending what you play to ${preferences.listenBrainzUsername ?: "your account"}"
            else "Keep a public record of what you play on ListenBrainz, free and open source. Paste your token to start."
        ) {
            if (LocalSettingsFilter.current.isBlank()) {
                Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    if (token != null) ListenBrainzConnected(preferences.listenBrainzUsername)
                    else ListenBrainzConnect()
                }
            }
        }
    }
}

@Composable
private fun ListenBrainzConnected(username: String?) {
    val status by ListenBrainzManager.status.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Connected as ${username ?: "your account"}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            status.problem != null -> status.problem!!
                            status.pending == 1 -> "1 listen waiting to send"
                            status.pending > 1 -> "${status.pending} listens waiting to send"
                            else -> "Every listen sent"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (status.pending > 0) TextButton(onClick = { ListenBrainzManager.retryNow() }) { Text("Send now") }
                TextButton(onClick = { PreferencesManager.setListenBrainz(null, null) }) { Text("Disconnect") }
            }
            Text(
                "A song counts once you've heard half of it, or four minutes. Podcasts aren't sent, and nothing is while listen history is paused.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ListenBrainzConnect() {
    var token by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<TokenCheck?>(null) }
    var checking by remember { mutableStateOf(false) }

    // Checked live as it is pasted or typed, so the name shows before anything is saved
    LaunchedEffect(token) {
        check = null
        val t = token.trim()
        if (t.length < 20) return@LaunchedEffect
        delay(500)
        checking = true
        check = ListenBrainzManager.validate(t)
        checking = false
    }

    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        label = { Text("User token") },
        placeholder = { Text("Paste it from your ListenBrainz settings") },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Tip(if (shown) "Hide token" else "Show token") {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (shown) "Hide token" else "Show token")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().suppressMediaKeys()
    )
    Spacer(Modifier.height(6.dp))
    val c = check
    when {
        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking with ListenBrainz...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        c is TokenCheck.Valid -> Text("This is ${c.userName}'s token.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        c is TokenCheck.Invalid -> Text("ListenBrainz doesn't know this token. Copy it again from your settings page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        c is TokenCheck.Unreachable -> Text("Couldn't reach ListenBrainz (${c.reason}). Check your connection and try again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Button(
            onClick = { (c as? TokenCheck.Valid)?.let { PreferencesManager.setListenBrainz(token.trim(), it.userName) } },
            enabled = c is TokenCheck.Valid
        ) {
            Text(if (c is TokenCheck.Valid) "Connect as ${c.userName}" else "Connect")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { runCatching { Platform.open("https://listenbrainz.org/settings/") } }) {
            Text("Find your token")
        }
    }
}
