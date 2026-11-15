package com.lyrenne.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.settings.PreferencesManager
import com.lyrenne.desktop.sound.eq.AutoEq
import com.lyrenne.desktop.sound.eq.AutoEqEntry
import com.lyrenne.desktop.sound.eq.EqFitter
import com.lyrenne.desktop.sound.eq.EqPreset
import com.lyrenne.desktop.sound.eq.EqPresetText
import com.lyrenne.desktop.sound.eq.EqPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

/** Lets the command palette open the equalizer with the headphone search already showing. */
object AutoEqLauncher {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    fun request() { _pending.value = true }
    fun consume() { _pending.value = false }
}

/** One AutoEq for the app, so the index is read from disk once per session. */
internal val autoEq by lazy { AutoEq() }

/**
 * Applies [preamp] and [bands] to VLC and the preferences, turning the equalizer on, and logs what
 * VLC then reports holding, so a profile can be checked against the log.
 */
internal fun applyEq(player: DesktopPlayer, preamp: Float, bands: List<Float>, what: String) {
    if (!PreferencesManager.preferences.value.eqEnabled) PreferencesManager.setEqEnabled(true)
    PreferencesManager.setEqBands(bands)
    PreferencesManager.setEqPreamp(preamp)
    player.applyEqualizer()
    val vlc = player.equalizerSnapshot()
    Timber.i("Equalizer: applied $what; VLC now has preamp ${vlc?.first} dB, bands ${vlc?.second}")
}

internal fun fmtDb(v: Float): String = String.format(Locale.ROOT, "%.1f", v).replace("-0.0", "0.0")

/**
 * A dB value you can type into. Enter or leaving the field applies it; Escape puts it back. Takes
 * a comma as the decimal mark too, and clamps to VLC's +-20 dB.
 */
@Composable
internal fun DbField(value: Float, onCommit: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String) {
    var field by remember(value) { mutableStateOf(TextFieldValue(fmtDb(value))) }
    var focused by remember { mutableStateOf(false) }
    // Clicking a number selects it, so typing replaces it. After the click has placed its cursor,
    // or the click would undo the selection.
    LaunchedEffect(focused) {
        if (focused) {
            delay(30)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    fun commit() {
        val v = field.text.replace(',', '.').replace("+", "").trim().toFloatOrNull()
        if (v == null) field = TextFieldValue(fmtDb(value))
        else {
            val clamped = ((v.coerceIn(-20f, 20f) * 10).roundToInt() / 10f)
            field = TextFieldValue(fmtDb(clamped), TextRange(fmtDb(clamped).length))
            if (clamped != value) onCommit(clamped)
        }
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        BasicTextField(
            value = field,
            onValueChange = { new -> if (new.text.length <= 6 && new.text.all { it.isDigit() || it in "-+.," }) field = new },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 5.dp)
                .semantics { contentDescription = label }
                .onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { commit(); true }
                        Key.Escape -> { field = TextFieldValue(fmtDb(value)); false }
                        else -> false
                    }
                }
                .suppressMediaKeys()
        )
    }
}

/**
 * Search the AutoEq project's measurements for your headphones and apply the correction. The
 * profile is fitted to VLC's bands as [vlcBands] reports them (see EqFitter), applied at once, and
 * saved as a preset named after the headphones.
 */
@Composable
internal fun AutoEqDialog(player: DesktopPlayer, vlcBands: List<Float>, onApplied: (EqPreset, Double) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var index by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingIndex by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf<AutoEqEntry?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        loadingIndex = true
        error = null
        try {
            index = withContext(Dispatchers.IO) { autoEq.index() }
        } catch (e: Exception) {
            error = "Couldn't load the headphone list from GitHub (${e.message}). Check your connection and try again."
        }
        loadingIndex = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val results = remember(index, query) { index?.let { AutoEq.search(it, query) }.orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Headphones, null) },
        title = { Text("Correct your headphones") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp)) {
                Text(
                    "Measured corrections from the AutoEq project make headphones sound neutral. Pick yours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Model, e.g. HD 600 or AirPods Pro") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).suppressMediaKeys()
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    when {
                        loadingIndex -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading the headphone list...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            TextButton(onClick = { attempt++ }) { Text("Try again") }
                        }
                        query.isBlank() -> Text(
                            "${index?.size ?: 0} headphones and earphones to choose from.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        results.isEmpty() -> Text(
                            "No headphones match \"$query\". Try fewer words, or only the model number.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        else -> LazyColumn {
                            items(results) { entry ->
                                val busy = applying == entry
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(enabled = applying == null) {
                                            applying = entry
                                            scope.launch {
                                                try {
                                                    val curve = withContext(Dispatchers.IO) { autoEq.curve(entry) }
                                                    val fit = withContext(Dispatchers.Default) { EqFitter.fit(curve, vlcBands.map { it.toDouble() }) }
                                                    val preset = EqPreset("${entry.name} (AutoEQ, ${entry.source})", fit.preampDb, vlcBands, fit.bandsDb)
                                                    EqPresets.save(preset)
                                                    applyEq(player, preset.preampDb, preset.gainsDb, "AutoEQ ${entry.name} by ${entry.label}, rms error ${"%.2f".format(fit.rmsErrorDb)} dB")
                                                    onApplied(preset, fit.rmsErrorDb)
                                                } catch (e: Exception) {
                                                    error = "Couldn't get the correction for ${entry.name} (${e.message})."
                                                } finally {
                                                    applying = null
                                                }
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Measured by ${entry.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Shows a preset as text with a Copy button, for sharing. */
@Composable
internal fun ExportPresetDialog(preset: EqPreset, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = remember(preset) { EqPresetText.export(preset) }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"${preset.name}\"") },
        text = {
            Column(Modifier.widthIn(min = 380.dp)) {
                Text("Copy this and paste it anywhere. Anyone with Lyrenne can import it.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp).fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) { Text(if (copied) "Copied" else "Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Paste a preset (Lyrenne's text, AutoEq's GraphicEQ, or Equalizer APO filters) and apply it. */
@Composable
internal fun ImportPresetDialog(player: DesktopPlayer, vlcBands: List<Float>, onImported: (EqPreset) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a preset") },
        text = {
            Column(Modifier.widthIn(min = 420.dp, max = 560.dp)) {
                Text("Paste a preset someone shared, or AutoEq or Equalizer APO text.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp).suppressMediaKeys(),
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } }
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { text = it; error = null } }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste from clipboard")
                }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                val preset = runCatching { EqPresetText.import(text, vlcBands) }.getOrNull()
                if (preset == null) {
                    error = "That doesn't look like an equalizer preset."
                } else {
                    EqPresets.save(preset)
                    applyEq(player, preset.preampDb, preset.gainsDb, "imported preset ${preset.name}")
                    onImported(preset)
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Names the current settings and saves them as a preset. */
@Composable
internal fun SavePresetDialog(suggested: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    val exists = EqPresets.load().any { it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as a preset") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Name") },
                singleLine = true,
                supportingText = if (exists) ({ Text("Replaces the preset with this name") }) else null,
                modifier = Modifier.fillMaxWidth().suppressMediaKeys()
            )
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
