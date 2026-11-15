package com.lyrenne.desktop.ui.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.link.LinkSettings
import com.lyrenne.desktop.link.LyrenneLink
import com.lyrenne.desktop.link.OverlayConfig
import com.lyrenne.desktop.lyrics.LyricsManager
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.ui.components.PillTab
import com.lyrenne.desktop.ui.components.PrimaryAction
import com.lyrenne.desktop.ui.components.SecondaryAction
import com.lyrenne.desktop.ui.components.Tip
import com.lyrenne.desktop.ui.theme.rememberArtTint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.JFileChooser

private val KEYWORDS = listOf("lyrenne link", "link", "obs", "overlay", "stream", "phone", "remote", "qr", "now playing", "streamer")

/**
 * Settings, Integrations, "Lyrenne Link": the overlay builder, the phone remote and the now
 * playing files. One composable so SettingsScreen only needs a single call.
 */
@Composable
fun LyrenneLinkSettings(filter: String = "") {
    if (filter.isNotBlank() && KEYWORDS.none { it.contains(filter, ignoreCase = true) || filter.contains(it, ignoreCase = true) }) return
    val prefs by LinkSettings.prefs.collectAsState()
    val status by LyrenneLink.status.collectAsState()
    var confirmRotate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        if (filter.isBlank()) {
            Text(
                "Lyrenne Link",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 16.dp)
            )
        }

        // --- Stream overlay ---
        ListItem(
            headlineContent = { Text("Stream overlay") },
            supportingContent = {
                Text(
                    if (prefs.overlayEnabled) "On for this PC only. Add it to OBS as a Browser Source."
                    else "Show what's playing and live lyrics on your stream. Only this PC can see it."
                )
            },
            leadingContent = { Icon(Icons.Default.Videocam, null) },
            trailingContent = {
                Switch(checked = prefs.overlayEnabled, onCheckedChange = { LyrenneLink.setOverlayEnabled(it) })
            }
        )
        OverlayBuilder(prefs.overlay, prefs.overlayEnabled, status)

        // --- Phone remote ---
        ListItem(
            headlineContent = { Text("Phone remote") },
            supportingContent = {
                Text(
                    if (prefs.remoteEnabled) "Open the QR code and scan it with your phone's camera. No app needed."
                    else "Control Lyrenne from your phone's browser on the same Wi-Fi. Windows may ask to " +
                        "allow Lyrenne on your network: choose Private networks."
                )
            },
            leadingContent = { Icon(Icons.Default.Smartphone, null) },
            trailingContent = {
                Switch(checked = prefs.remoteEnabled, onCheckedChange = { on ->
                    LyrenneLink.setRemoteEnabled(on)
                    if (on) LyrenneLink.pairingRequested.value = true
                })
            }
        )
        if (prefs.remoteEnabled) {
            Row(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryAction("Show QR code", Icons.Default.QrCode2, { LyrenneLink.pairingRequested.value = true })
                SecondaryAction("Forget paired phones", Icons.Default.LinkOff, { confirmRotate = true })
            }
            StatusLine(status, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp))
        }

        // --- Now playing files ---
        ListItem(
            headlineContent = { Text("Now playing files") },
            supportingContent = {
                Text(
                    prefs.exportFolder?.takeIf { prefs.exportEnabled }
                        ?.let { "Writing now-playing.txt, now-playing.json and cover.jpg to $it" }
                        ?: "Write now-playing.txt, .json and cover.jpg to a folder, for OBS text sources and Streamer.bot."
                )
            },
            leadingContent = { Icon(Icons.Default.Description, null) },
            trailingContent = {
                Switch(checked = prefs.exportEnabled, onCheckedChange = { on ->
                    val folder = prefs.exportFolder ?: if (on) chooseFolder(null) else null
                    if (on && folder == null) return@Switch
                    LyrenneLink.setExport(on, folder)
                })
            }
        )
        if (prefs.exportEnabled) {
            Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Change folder", Icons.Default.FolderOpen, {
                    chooseFolder(prefs.exportFolder)?.let { LyrenneLink.setExport(true, it) }
                })
                prefs.exportFolder?.let { folder ->
                    SecondaryAction("Open folder", Icons.AutoMirrored.Filled.OpenInNew, { Platform.open(folder) })
                }
            }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            icon = { Icon(Icons.Default.LinkOff, null) },
            title = { Text("Forget paired phones?") },
            text = { Text("Every phone paired with Lyrenne stops working until it scans the new QR code. The stream overlay is not affected.") },
            confirmButton = {
                Button(onClick = {
                    LyrenneLink.rotateToken()
                    confirmRotate = false
                    LyrenneLink.notice.value = "Paired phones forgotten. Scan the new QR code to pair again."
                }) { Text("Forget phones") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } }
        )
    }
}

private fun chooseFolder(current: String?): String? {
    val chooser = JFileChooser(current ?: System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Folder for now playing files"
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.absolutePath
}

@Composable
private fun StatusLine(status: com.lyrenne.desktop.link.LinkStatus, modifier: Modifier = Modifier) {
    val text = when {
        status.error != null -> status.error
        status.portNote != null -> status.portNote
        status.running -> if (status.lan) "Listening on your network, port ${status.port}." else "Running on this PC, port ${status.port}."
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

private val POSITION_LABELS = listOf(
    "tl" to "Top left", "tc" to "Top", "tr" to "Top right",
    "bl" to "Bottom left", "bc" to "Bottom", "br" to "Bottom right",
)

private val ACCENT_PRESETS = listOf("auto" to "From album art", "e3c089" to "Lyrenne gold", "ffffff" to "White")

@Composable
private fun OverlayBuilder(config: OverlayConfig, enabled: Boolean, status: com.lyrenne.desktop.link.LinkStatus) {
    val colors = MaterialTheme.colorScheme
    fun set(c: OverlayConfig) = LyrenneLink.setOverlay(c)
    Surface(
        color = colors.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
    ) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val wide = maxWidth >= 760.dp
            val controls: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("Layout")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("card" to "Card", "lyrics" to "Lyrics", "ticker" to "Ticker").forEach { (id, label) ->
                            PillTab(label, config.layout == id, {
                                // Lyrics read best centred; a ticker spans the width, so only top or bottom matters.
                                val pos = when {
                                    id == "lyrics" && config.layout != "lyrics" -> "bc"
                                    id == "ticker" -> if (config.position.startsWith("t")) "tl" else "bl"
                                    else -> config.position
                                }
                                set(config.copy(layout = id, position = pos))
                            })
                        }
                    }
                    Label("Position")
                    val positions = if (config.layout == "ticker") listOf("tl" to "Top", "bl" to "Bottom") else POSITION_LABELS
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        positions.forEach { (id, label) ->
                            FilterChip(
                                selected = config.position == id,
                                onClick = { set(config.copy(position = id)) },
                                label = { Text(label) },
                                colors = com.lyrenne.desktop.ui.theme.selectedChipColors()
                            )
                        }
                    }
                    Label("Size  ${(config.scale * 100).toInt()}%")
                    Slider(
                        value = config.scale, onValueChange = { set(config.copy(scale = (it * 20).toInt() / 20f)) },
                        valueRange = 0.5f..2f
                    )
                    if (config.layout != "ticker") {
                        Label("Lyrics text  ${config.fontSize} px")
                        Slider(
                            value = config.fontSize.toFloat(), onValueChange = { set(config.copy(fontSize = it.toInt())) },
                            valueRange = 24f..96f
                        )
                    }
                    Label("Colour")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ACCENT_PRESETS.forEach { (id, label) ->
                            FilterChip(
                                selected = config.accent == id,
                                onClick = { set(config.copy(accent = id)) },
                                label = { Text(label) },
                                colors = com.lyrenne.desktop.ui.theme.selectedChipColors()
                            )
                        }
                        HexField(config.accent) { set(config.copy(accent = it)) }
                    }
                    if (config.layout == "card") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                            set(config.copy(cardLyrics = !config.cardLyrics))
                        }) {
                            Checkbox(checked = config.cardLyrics, onCheckedChange = { set(config.copy(cardLyrics = it)) })
                            Text("Show the current lyric line", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            val preview: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverlayPreview(config, Modifier.fillMaxWidth())
                    val url = LyrenneLink.overlayUrl(config)
                    SelectionContainer {
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryAction("Copy OBS URL", Icons.Default.ContentCopy, { LyrenneLink.copyOverlayUrl() })
                        SecondaryAction("Try it in a browser", Icons.AutoMirrored.Filled.OpenInNew, {
                            if (!enabled) LyrenneLink.setOverlayEnabled(true)
                            Platform.open(LyrenneLink.overlayUrl(config) + "&preview=1")
                        })
                    }
                    Text(
                        "In OBS, add a Browser Source, paste the URL, and set the size to 1920 x 1080. " +
                            "The overlay places itself and stays see-through.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    // Only what concerns the overlay: a moved port changes its URL, an error stops it.
                    if (enabled && (status.portNote != null || status.error != null)) StatusLine(status)
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(0.9f)) { controls() }
                    Box(Modifier.weight(1.1f)) { preview() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { preview(); controls() }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HexField(accent: String, onChange: (String) -> Unit) {
    var text by remember(accent) { mutableStateOf(if (accent.length == 6 && accent != "e3c089" && accent != "ffffff") "#$accent" else "") }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(7)
            val hex = v.removePrefix("#")
            if (LinkSettings.HEX.matches(hex)) onChange(hex.lowercase())
        },
        placeholder = { Text("#hex") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.width(120.dp).heightIn(min = 32.dp).suppressMediaKeys()
    )
}

private val SAMPLE_LINES = listOf("Your lyrics show up here", "line by line as you sing along", "with every word lighting up")

/**
 * A drawn stand-in for the overlay, at the same proportions, with what is playing right now. The
 * real page is HTML; this mirrors its layout closely enough to choose a position and size.
 */
@Composable
fun OverlayPreview(config: OverlayConfig, modifier: Modifier = Modifier) {
    val player = LyrenneLink.activePlayer
    val state by (player?.state ?: remember { MutableStateFlow(PlaybackState()) }).collectAsState()
    val lyrics by LyricsManager.state.collectAsState()
    val song = state.currentSong
    val tint by rememberArtTint(song?.thumbnailUrl)
    val accent = when {
        config.accent == "auto" -> if (tint.alpha > 0.5f) tint else Color(0xFFE3C089)
        else -> Color(("ff" + config.accent).toLong(16))
    }
    val title = song?.title ?: "Your song title here"
    val artist = song?.artist ?: "Artist name"
    val lines = lyrics.lines?.takeIf { lyrics.songId == song?.id }
    val idx = lines?.indexOfLast { it.timeMs <= state.position } ?: -1
    val lineAt = { i: Int -> lines?.getOrNull(i)?.text ?: SAMPLE_LINES.getOrNull(i - idx + 1) ?: "" }
    val progress = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0.3f

    BoxWithConstraints(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A4A), Color(0xFF4A3B5C), Color(0xFF1F2A33))))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        val f = maxWidth.value / 1920f
        fun px(v: Float): Dp = (v * config.scale * f).dp
        fun tx(v: Float) = (v * config.scale * f).sp
        val margin = (48 * f).dp
        val align = when (config.position) {
            "tl" -> Alignment.TopStart; "tc" -> Alignment.TopCenter; "tr" -> Alignment.TopEnd
            "bl" -> Alignment.BottomStart; "br" -> Alignment.BottomEnd; else -> Alignment.BottomCenter
        }
        // A hint of a game scene, so the see-through overlay reads as one.
        Text(
            "Your stream",
            color = Color.White.copy(alpha = 0.18f),
            fontSize = (120 * f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
        Box(Modifier.fillMaxSize().padding(margin), contentAlignment = align) {
            val glass = Brush.linearGradient(listOf(accent.copy(alpha = 0.26f), Color(0xC7100F0E), Color(0xC7100F0E)))
            when (config.layout) {
                "ticker" -> Row(
                    Modifier.fillMaxWidth().height(px(64f)).clip(RoundedCornerShape(px(18f))).background(glass)
                        .padding(start = px(8f), end = px(20f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Art(song?.thumbnailUrl, px(48f), px(12f))
                    Spacer(Modifier.width(px(16f)))
                    Box(Modifier.clip(RoundedCornerShape(px(8f))).background(accent).padding(horizontal = px(10f), vertical = px(4f))) {
                        Text("NOW PLAYING", color = Color(0xFF121110), fontSize = tx(15f), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Spacer(Modifier.width(px(16f)))
                    Text("$title  ·  $artist", color = Color.White, fontSize = tx(24f), fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(lineAt(idx), color = Color.White.copy(alpha = 0.8f), fontSize = tx(22f), maxLines = 1)
                }
                "lyrics" -> Column(
                    Modifier.width(px(1400f)),
                    horizontalAlignment = when (config.position) {
                        "tl", "bl" -> Alignment.Start; "tr", "br" -> Alignment.End; else -> Alignment.CenterHorizontally
                    }
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xB8100F0E)).padding(px(8f)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Art(song?.thumbnailUrl, px(40f), px(20f))
                        Spacer(Modifier.width(px(14f)))
                        Text("$title  ·  $artist", color = Color.White, fontSize = tx(20f), maxLines = 1)
                        Spacer(Modifier.width(px(10f)))
                    }
                    Spacer(Modifier.height(px(18f)))
                    val fs = config.fontSize.toFloat()
                    val textAlign = when (config.position) { "tl", "bl" -> TextAlign.Start; "tr", "br" -> TextAlign.End; else -> TextAlign.Center }
                    Text(lineAt(idx - 1), color = Color.White.copy(alpha = 0.3f), fontSize = tx(fs * 0.86f), fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = textAlign)
                    Text(lineAt(idx), color = Color.White, fontSize = tx(fs), fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = textAlign)
                    Text(lineAt(idx + 1), color = Color.White.copy(alpha = 0.3f), fontSize = tx(fs * 0.86f), fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = textAlign)
                }
                else -> Column(
                    Modifier.width(px(600f)).clip(RoundedCornerShape(px(26f))).background(glass).padding(px(20f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Art(song?.thumbnailUrl, px(128f), px(16f))
                        Spacer(Modifier.width(px(22f)))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontSize = tx(30f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Color.White.copy(alpha = 0.72f), fontSize = tx(22f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(px(16f)))
                            Box(Modifier.fillMaxWidth().height(px(6f)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))) {
                                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
                            }
                        }
                    }
                    if (config.cardLyrics) {
                        Spacer(Modifier.height(px(16f)))
                        Text(lineAt(idx), color = Color.White, fontSize = tx(config.fontSize * 0.6f), fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Art(url: String?, size: Dp, radius: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = 0.08f))) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
