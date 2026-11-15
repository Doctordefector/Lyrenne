package com.lyrenne.desktop.ui.movein

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.movein.*
import com.lyrenne.desktop.ui.components.NativeFileDialog
import com.lyrenne.desktop.ui.components.PrimaryAction
import com.lyrenne.desktop.ui.components.SecondaryAction
import com.lyrenne.desktop.ui.components.Tip

/** The one line that tells a MetroList user how to get the file. */
const val METROLIST_BACKUP_HOWTO =
    "In MetroList, open Settings, then Backup and restore, and tap Backup. Copy that file to this computer."

/**
 * Draws whichever Move in window is open, over the whole app. Placed once in App, after
 * everything else, so it covers onboarding as well as the main screens.
 *
 * [onOpenPlaylist] gets a saved playlist's id and whether it is local; null hides the button.
 */
@Composable
fun MoveInHost(onOpenPlaylist: ((id: String, local: Boolean) -> Unit)? = null) {
    val dialog by MoveInController.dialog.collectAsState()
    if (dialog == MoveInDialog.NONE) return
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            // Swallow clicks so nothing behind the window reacts
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { MoveInController.close(); true } else false
            },
        contentAlignment = Alignment.Center
    ) {
        val wide = maxWidth >= 760.dp
        val width = if (dialog == MoveInDialog.PLAYLIST) minOf(maxWidth - 32.dp, 860.dp) else minOf(maxWidth - 32.dp, 620.dp)
        Surface(
            modifier = Modifier.width(width).heightIn(max = maxHeight - 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            when (dialog) {
                MoveInDialog.BACKUP -> BackupWindow()
                MoveInDialog.PLAYLIST -> PlaylistWindow(wide, onOpenPlaylist)
                MoveInDialog.NONE -> {}
            }
        }
    }
}

@Composable
private fun WindowHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Tip("Close") {
            IconButton(onClick = MoveInController::close) { Icon(Icons.Default.Close, "Close") }
        }
    }
}

// ====================================================================== MetroList backup

@Composable
private fun BackupWindow() {
    val state by MoveInController.backup.collectAsState()
    Column(Modifier.padding(24.dp)) {
        WindowHeader(Icons.Default.MoveToInbox, "Bring your MetroList library", when (state) {
            is BackupState.Preview -> "Here's what's in ${(state as BackupState.Preview).fileName}."
            is BackupState.Done -> null
            else -> "Likes, playlists, saved albums, followed artists and your play history. Nothing in Lyrenne is replaced."
        })
        Spacer(Modifier.height(20.dp))
        when (val s = state) {
            BackupState.Idle -> BackupPick()
            is BackupState.Reading -> Working("Reading ${s.fileName}...")
            is BackupState.Preview -> BackupPreview(s)
            BackupState.Importing -> Working("Bringing it over. A big library takes a few seconds.")
            is BackupState.Done -> BackupDone(s)
            is BackupState.Failed -> {
                Banner(Icons.Default.ErrorOutline, s.message, error = true)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = MoveInController::close) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    PrimaryAction("Choose another file", Icons.Default.FolderOpen, onClick = ::pickBackup)
                }
            }
        }
    }
}

private fun pickBackup() {
    NativeFileDialog.open("Choose your MetroList backup")?.let(MoveInController::readBackup)
}

@Composable
private fun BackupPick() {
    Banner(Icons.Default.Lightbulb, METROLIST_BACKUP_HOWTO)
    Spacer(Modifier.height(12.dp))
    Text(
        "Works with backups from MetroList 13 and 14, and from InnerTune and its forks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = MoveInController::close) { Text("Not now") }
        Spacer(Modifier.width(8.dp))
        PrimaryAction("Choose backup file", Icons.Default.FolderOpen, onClick = ::pickBackup)
    }
}

@Composable
private fun Working(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, error: Boolean = false) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColumnScope.BackupPreview(p: BackupState.Preview) {
    val lib = p.library
    val o = p.options
    val signedIn = MoveInController.isSignedIn
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Text(MoveInSummary.headline(lib), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("From ${lib.flavor.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        fun set(change: MoveInOptions) = MoveInController.setOptions(change)
        OptionRow(o.likes, "Liked songs", MoveInSummary.count(lib.likes.size, "song"), lib.likes.isNotEmpty()) { set(o.copy(likes = it)) }
        OptionRow(o.library, "Songs in your library", MoveInSummary.count(lib.librarySongs.size, "song"), lib.librarySongs.isNotEmpty()) { set(o.copy(library = it)) }
        OptionRow(
            o.playlists, "Playlists",
            buildList {
                if (lib.localPlaylists.isNotEmpty()) add("${lib.localPlaylists.size} made in MetroList (songs in order)")
                if (lib.youTubePlaylists.isNotEmpty()) add("${lib.youTubePlaylists.size} from YouTube (opened from there)")
            }.joinToString(" and ").ifEmpty { "None" },
            lib.playlists.isNotEmpty()
        ) { set(o.copy(playlists = it)) }
        OptionRow(
            o.albumsAndArtists, "Saved albums and followed artists",
            "${MoveInSummary.count(lib.albums.size, "album")}, ${MoveInSummary.count(lib.artists.size, "artist")}",
            lib.albums.isNotEmpty() || lib.artists.isNotEmpty()
        ) { set(o.copy(albumsAndArtists = it)) }
        OptionRow(
            o.history, "Play history",
            MoveInSummary.count(lib.plays.size, "play") + (lib.firstPlayYear?.let { ", back to $it. Your stats and recaps start there" } ?: ""),
            lib.plays.isNotEmpty()
        ) { set(o.copy(history = it)) }
        if (lib.lyrics.isNotEmpty() || lib.lyricsOffsets.isNotEmpty()) {
            OptionRow(o.lyrics, "Your lyrics", "Lyrics you picked or wrote, and timing fixes", true) { set(o.copy(lyrics = it)) }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        OptionRow(
            o.likeOnYouTube && signedIn,
            "Also like them on YouTube",
            if (signedIn) "Off: likes stay in Lyrenne only. On: each new like is also sent to your YouTube account, a few a second. Leave it off if MetroList already synced them."
            else "Sign in to Lyrenne first to send likes to YouTube. They stay in Lyrenne only.",
            signedIn && o.likes && lib.likes.isNotEmpty()
        ) { set(o.copy(likeOnYouTube = it)) }
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = MoveInController::close) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        val any = o.likes || o.library || o.playlists || o.albumsAndArtists || o.history || o.lyrics
        PrimaryAction("Bring it over", Icons.AutoMirrored.Filled.ArrowForward, onClick = MoveInController::runImport, enabled = any)
    }
}

@Composable
private fun OptionRow(checked: Boolean, title: String, detail: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackupDone(d: BackupState.Done) {
    val lines = MoveInSummary.resultLines(d.result)
    Text(
        if (d.result.nothingNew) "You're all set. Everything in that backup was already here."
        else "Welcome home. Your MetroList library is in Lyrenne.",
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    lines.forEach { line ->
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(line, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (d.pushTotal > 0) {
        Spacer(Modifier.height(16.dp))
        val finished = d.pushedLikes >= d.pushTotal
        Text(
            if (finished) "Liked ${MoveInSummary.count(d.pushTotal - d.pushFailed, "song")} on YouTube" +
                (if (d.pushFailed > 0) ". ${MoveInSummary.count(d.pushFailed, "song")} didn't go through and stay liked in Lyrenne." else ".")
            else "Liking on YouTube: ${MoveInSummary.count(d.pushedLikes, "song")} of ${"%,d".format(d.pushTotal)}. You can close this, it carries on.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { d.pushedLikes / d.pushTotal.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { MoveInController.close(); MoveInController.openPlaylist() }) { Text("Import a playlist too") }
        Spacer(Modifier.width(8.dp))
        PrimaryAction("Done", null, onClick = MoveInController::close)
    }
}

// ====================================================================== playlist link or text

@Composable
private fun PlaylistWindow(wide: Boolean, onOpenPlaylist: ((String, Boolean) -> Unit)?) {
    val s by MoveInController.playlist.collectAsState()
    Column(Modifier.padding(24.dp)) {
        WindowHeader(
            Icons.AutoMirrored.Filled.PlaylistAdd,
            if (s.phase == PlaylistPhase.INPUT || s.phase == PlaylistPhase.FETCHING) "Import a playlist" else s.source?.name ?: "Import a playlist",
            when (s.phase) {
                PlaylistPhase.INPUT, PlaylistPhase.FETCHING ->
                    "Paste a link from Spotify, Apple Music, Deezer, TIDAL or SoundCloud. Or paste the songs, one per line, or a CSV export."
                PlaylistPhase.MATCHING -> "Finding each song on YouTube Music..."
                PlaylistPhase.REVIEW -> "Check the matches, then save it as a playlist."
                PlaylistPhase.SAVING, PlaylistPhase.SAVED -> null
            }
        )
        Spacer(Modifier.height(16.dp))
        when (s.phase) {
            PlaylistPhase.INPUT, PlaylistPhase.FETCHING -> PlaylistInput(s)
            PlaylistPhase.MATCHING, PlaylistPhase.REVIEW -> PlaylistReview(s, wide)
            PlaylistPhase.SAVING -> {
                val total = s.matchedCount
                Working(if (s.createOnYouTube && total > 0) "Adding songs to YouTube: ${s.saved} of $total" else "Saving...")
                if (s.createOnYouTube && total > 0) LinearProgressIndicator(progress = { s.saved / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            PlaylistPhase.SAVED -> PlaylistSaved(s, onOpenPlaylist)
        }
    }
}

@Composable
private fun PlaylistInput(s: PlaylistImportState) {
    val busy = s.phase == PlaylistPhase.FETCHING
    val link = remember(s.input) { if (PlaylistSources.looksLikeLink(s.input)) PlaylistSources.detect(s.input) else null }
    OutlinedTextField(
        value = s.input,
        onValueChange = MoveInController::setInput,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).suppressMediaKeys(),
        placeholder = {
            Text("https://open.spotify.com/playlist/...\n\nor\n\nDaft Punk - One More Time\nQueen - Bohemian Rhapsody")
        },
        supportingText = {
            val hint = when {
                s.input.isBlank() -> "Private playlists can't be read without a login. Paste their songs as text instead."
                link != null -> "${link.service.label} ${link.kind.name.lowercase()} link"
                PlaylistSources.looksLikeLink(s.input) && PlaylistSources.isShortLink(s.input) -> "Share link, will be followed"
                PlaylistSources.looksLikeLink(s.input) -> "Not a link Lyrenne can read"
                else -> "${TextPlaylist.parse(s.input).tracks.size} songs in your text"
            }
            Text(hint)
        }
    )
    s.error?.let {
        Spacer(Modifier.height(8.dp))
        Banner(Icons.Default.ErrorOutline, it, error = true)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reading the playlist...", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = MoveInController::cancelPlaylist) { Text("Cancel") }
        } else {
            TextButton(onClick = MoveInController::close) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            PrimaryAction("Find songs", Icons.Default.Search, onClick = MoveInController::startPlaylist, enabled = s.input.isNotBlank())
        }
    }
}

@Composable
private fun ColumnScope.PlaylistReview(s: PlaylistImportState, wide: Boolean) {
    val matching = s.phase == PlaylistPhase.MATCHING
    val total = s.matches.size
    s.source?.limitNote?.let {
        Banner(Icons.Default.Info, it)
        Spacer(Modifier.height(12.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (matching) "${s.doneCount} of $total searched. ${MoveInSummary.matchLine(s)}"
            else MoveInSummary.matchLine(s),
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
        )
        if (matching) TextButton(onClick = MoveInController::stopMatching) { Text("Stop and review") }
    }
    if (matching) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { if (total == 0) 0f else s.doneCount / total.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
        // Not keyed: two rows can be the same song, see AGENTS.md
        itemsIndexed(s.matches) { index, match ->
            MatchRow(index, s.source!!.tracks[index], match, wide)
        }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    val signedIn = MoveInController.isSignedIn
    // Naming and saving wait for the search to finish, which leaves the list the room
    if (matching) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = MoveInController::cancelPlaylist) { Text("Cancel") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NameField(s, if (wide) Modifier.width(320.dp) else Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        YouTubeToggle(s, signedIn, if (wide) Modifier.weight(1f) else Modifier.widthIn(max = 260.dp))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = MoveInController::cancelPlaylist) { Text("Start over") }
        Spacer(Modifier.width(8.dp))
        PrimaryAction(
            "Save ${MoveInSummary.count(s.matchedCount, "song")}", Icons.Default.Check,
            onClick = MoveInController::savePlaylist,
            enabled = !matching && s.matchedCount > 0
        )
    }
}

@Composable
private fun NameField(s: PlaylistImportState, modifier: Modifier) {
    OutlinedTextField(
        value = s.name, onValueChange = MoveInController::setName, singleLine = true,
        label = { Text("Playlist name") }, modifier = modifier.suppressMediaKeys()
    )
}

@Composable
private fun YouTubeToggle(s: PlaylistImportState, signedIn: Boolean, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = signedIn) { MoveInController.setCreateOnYouTube(!s.createOnYouTube) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = s.createOnYouTube && signedIn, onCheckedChange = MoveInController::setCreateOnYouTube, enabled = signedIn)
        Column {
            Text("Also create it on YouTube", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (signedIn) "Off keeps it in Lyrenne only" else "Sign in to create it on YouTube too",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MatchRow(index: Int, source: SourceTrack, match: TrackMatch?, wide: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val unsure = match != null && match.chosen != null && match.quality != MatchQuality.GOOD
    val missing = match != null && match.chosen == null
    val bg = when {
        unsure -> scheme.tertiaryContainer
        missing -> scheme.surfaceVariant
        else -> Color.Transparent
    }
    val fg = if (unsure) scheme.onTertiaryContainer else scheme.onSurface
    val fgDim = if (unsure) scheme.onTertiaryContainer else scheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = fgDim, modifier = Modifier.width(28.dp))
        if (wide) {
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.bodyMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(source.artistLine.ifEmpty { "Unknown artist" }, style = MaterialTheme.typography.bodySmall, color = fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(16.dp), tint = fgDim)
        }
        Box(Modifier.weight(if (wide) 1.3f else 1f)) {
            AnimatedContent(
                targetState = match,
                transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() },
                contentKey = { it?.chosen?.videoId to (it == null) }
            ) { m ->
                when {
                    m == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (wide) "Searching..." else "${source.title}: searching...", style = MaterialTheme.typography.bodySmall, color = fgDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    m.chosen == null -> Column(Modifier.heightIn(min = 44.dp), verticalArrangement = Arrangement.Center) {
                        Text("Not found", style = MaterialTheme.typography.bodyMedium, color = fg)
                        Text(
                            if (wide) (if (m.options.isEmpty()) "Nothing close on YouTube Music" else "Pick one if it's right")
                            else "${source.title} by ${source.artistLine}",
                            style = MaterialTheme.typography.bodySmall, color = fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> CandidateLine(m.chosen, fg, fgDim)
                }
            }
        }
        if (match != null) {
            QualityChip(match)
            if (match.options.isNotEmpty() || match.chosen != null) AlternativesMenu(index, match, fgDim)
        } else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun CandidateLine(c: Candidate, fg: Color, fgDim: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = c.thumbnailUrl, contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = if (c.isSong) "" else if (c.isUserVideo) "  Video upload" else "  Music video"
            Text(
                c.artists.joinToString(", ") + (c.durationSec?.let { "  ${it / 60}:${"%02d".format(it % 60)}" } ?: "") + kind,
                style = MaterialTheme.typography.bodySmall, color = fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QualityChip(m: TrackMatch) {
    val scheme = MaterialTheme.colorScheme
    val (label, bg, fg) = when {
        m.chosen == null && m.quality == MatchQuality.NONE -> return
        m.chosen == null -> Triple("Skipped", scheme.surface, scheme.onSurfaceVariant)
        m.chosen != m.options.firstOrNull()?.candidate -> Triple("Your pick", scheme.secondaryContainer, scheme.onSecondaryContainer)
        m.quality == MatchQuality.GOOD -> Triple("Match", scheme.primaryContainer, scheme.onPrimaryContainer)
        else -> Triple("Check", scheme.surface, scheme.onSurface)
    }
    Box(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(label, style = MaterialTheme.typography.labelMedium, color = fg) }
}

@Composable
private fun AlternativesMenu(index: Int, m: TrackMatch, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("Other matches") {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.SwapHoriz, "Other matches", tint = tint) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            m.options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(opt.candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                opt.candidate.artists.joinToString(", ") +
                                    (opt.candidate.durationSec?.let { "  ${it / 60}:${"%02d".format(it % 60)}" } ?: "") +
                                    (if (opt.candidate.isSong) "" else "  video") +
                                    "  ${(opt.score * 100).toInt()}% sure",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = {
                        if (opt.candidate == m.chosen) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { MoveInController.choose(index, opt.candidate); open = false },
                    modifier = Modifier.widthIn(max = 420.dp)
                )
            }
            if (m.chosen != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Skip this song") },
                    leadingIcon = { Icon(Icons.Default.Block, null) },
                    onClick = { MoveInController.choose(index, null); open = false }
                )
            }
        }
    }
}

@Composable
private fun PlaylistSaved(s: PlaylistImportState, onOpenPlaylist: ((String, Boolean) -> Unit)?) {
    Text("Saved \"${s.name.ifBlank { s.source?.name.orEmpty() }}\"", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    val skipped = s.matches.size - s.saved
    Text(
        buildString {
            append(MoveInSummary.count(s.saved, "song"))
            if (s.savedRemotely) append(", also on your YouTube account") else append(", in your Library")
            append(".")
            if (skipped > 0) append(" ${MoveInSummary.count(skipped, "song")} not found, left out.")
            if (s.remoteFailures > 0) append(" YouTube refused ${s.remoteFailures}; they are still in the Lyrenne copy.")
        },
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { MoveInController.close(); MoveInController.openPlaylist() }) { Text("Import another") }
        Spacer(Modifier.width(8.dp))
        val id = s.savedPlaylistId
        if (onOpenPlaylist != null && id != null) {
            PrimaryAction("Open playlist", Icons.AutoMirrored.Filled.ArrowForward, onClick = {
                MoveInController.close(); onOpenPlaylist(id, !s.savedRemotely)
            })
        } else {
            PrimaryAction("Done", null, onClick = MoveInController::close)
        }
    }
}

// ====================================================================== entry points

/**
 * The Move in rows in Settings, under Backup & Restore. [filter] is the Settings search text,
 * so the rows hide like every other one when they do not match.
 */
@Composable
fun MoveInSettingsRows(filter: String) {
    val rows = listOf(
        Triple(Icons.Default.MoveToInbox, "Import from MetroList", "Bring likes, playlists, albums, artists and play history from a MetroList backup") to
            { MoveInController.openBackup() },
        Triple(Icons.AutoMirrored.Filled.PlaylistAdd, "Import a playlist", "From a Spotify, Apple Music, Deezer, TIDAL or SoundCloud link, or a list of songs") to
            { MoveInController.openPlaylist() },
    )
    for ((info, action) in rows) {
        val (icon, title, subtitle) = info
        if (filter.isNotBlank() && !title.contains(filter, true) && !subtitle.contains(filter, true) &&
            !"metrolist spotify import move".contains(filter.lowercase())) continue
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            modifier = Modifier.clickable(onClick = action)
        )
    }
}

/** The optional onboarding step. Next skips it; nothing here is required. */
@Composable
fun MoveInOnboardingStep() {
    Text("Coming from MetroList?", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Bring your likes, playlists and play history along, so Lyrenne feels like home from the first song. " +
            "Not coming from anywhere? Press Next.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    val backup by MoveInController.backup.collectAsState()
    val done = backup as? BackupState.Done
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MoveToInbox, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Import a MetroList backup", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(METROLIST_BACKUP_HOWTO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (done != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(MoveInSummary.resultLines(done.result).firstOrNull() ?: "Imported", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                PrimaryAction("Choose backup file", Icons.Default.FolderOpen, onClick = {
                    MoveInController.openBackup()
                    pickBackup()
                })
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Or bring a playlist from another app", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Spotify, Apple Music, Deezer, TIDAL and SoundCloud links work, and so does a plain list of songs.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            SecondaryAction("Import a playlist", Icons.AutoMirrored.Filled.PlaylistAdd, onClick = { MoveInController.openPlaylist() })
        }
    }
}
