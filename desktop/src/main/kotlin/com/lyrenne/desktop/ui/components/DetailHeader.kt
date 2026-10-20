package com.lyrenne.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** True once the header (item 0) has scrolled far enough that its title is out of view. */
fun LazyListState.headerScrolledAway(): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 160

/**
 * A detail screen's top bar. Once the header has scrolled away it shows the screen's name and a
 * small Play, so a long playlist never leaves you wondering where you are. While the header is in
 * view it shows only Back: the header already says the name, in large type.
 */
@Composable
fun DetailTopBar(onBack: () -> Unit, title: String, collapsed: Boolean, onPlay: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tip("Back (Esc)") {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        AnimatedVisibility(collapsed, Modifier.weight(1f), enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPlay != null) {
                    FilledIconButton(onClick = onPlay, shape = CircleShape, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.PlayArrow, "Play", Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The header of an album, playlist or podcast: art, kind, title, a subtitle line and the actions.
 * Actions follow Spotify's order: a big round Play, then Shuffle and Download as icons, and a ⋮
 * holding the rest. Five equal buttons used to wrap onto two lines and bury the one that matters.
 */
@Composable
fun DetailHeader(
    thumbnail: String?,
    kind: String,
    title: String,
    meta: String?,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    circleArt: Boolean = false,
    subtitle: @Composable () -> Unit = {},
    below: @Composable () -> Unit = {},
    more: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        val artSize = if (maxWidth < 560.dp) 128.dp else 184.dp
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = squareCropFriendly(thumbnail),
                contentDescription = null,
                modifier = Modifier.size(artSize)
                    .clip(if (circleArt) CircleShape else RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    kind,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle()
                if (!meta.isNullOrBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tip("Play") {
                        FilledIconButton(onClick = onPlay, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.PlayArrow, "Play", Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (onShuffle != null) {
                        Tip("Shuffle") {
                            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle") }
                        }
                    }
                    if (onDownload != null) {
                        Tip("Download all") {
                            IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download all") }
                        }
                    }
                    if (more != null) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Tip("More") {
                                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                more { open = false }
                            }
                        }
                    }
                }
                below()
            }
        }
    }
}
