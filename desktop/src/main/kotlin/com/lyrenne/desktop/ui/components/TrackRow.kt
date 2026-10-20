package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs

/**
 * The one song row: Library, Search, Album, playlists, Downloads and the queue all draw this, so
 * they share height, columns, hover and the song menu (⋮ and right-click).
 *
 * 56 dp tall with 40 dp art, like Spotify's desktop rows; the old two-line ListItem pitched about
 * 76 dp and showed five songs at 1200x800. An album column appears from 900 dp of row width.
 *
 * [liked]: null hides the heart entirely (a list that is all liked songs). Otherwise a filled
 * heart shows when liked, and an outline appears on hover; either toggles the like.
 */
@Composable
fun TrackRow(
    song: SongInfo,
    player: DesktopPlayer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    index: Int? = null,
    showArt: Boolean = true,
    liked: Boolean? = null,
    downloaded: Boolean = false,
    explicit: Boolean = false,
    subtitle: String = song.artist,
    leading: (@Composable () -> Unit)? = null,
    menuExtra: @Composable ColumnScope.(close: () -> Unit) -> Unit = {}
) {
    val anchor = rememberMenuAnchor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.fillMaxWidth().contextMenu(anchor)) {
        val wide = maxWidth >= 900.dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            if (index != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    if (isPlaying) {
                        Icon(Icons.Default.GraphicEq, "Playing", tint = colors.primary, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            "$index",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (showArt) {
                Box {
                    AsyncImage(
                        model = squareCropFriendly(song.thumbnailUrl),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop
                    )
                    if (isPlaying && index == null) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.GraphicEq, "Playing",
                                tint = Color.White, modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaying) colors.primary else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (explicit) {
                        Icon(
                            Icons.Default.Explicit, "Explicit",
                            tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (downloaded) {
                        Icon(
                            Icons.Default.DownloadDone, "Downloaded",
                            tint = colors.secondary, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (wide) {
                Spacer(Modifier.width(16.dp))
                Text(
                    song.album.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.6f)
                )
            }
            Row(
                Modifier.width(if (liked != null) 132.dp else 92.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (liked != null && (liked || hovered)) {
                    Tip(if (liked) "Remove from liked songs" else "Add to liked songs") {
                        IconButton(onClick = { setLiked(song, !liked, downloaded) }) {
                            Icon(
                                if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (liked) "Remove from liked songs" else "Add to liked songs",
                                tint = if (liked) colors.primary else colors.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                val ms = song.knownDurationMs()
                Text(
                    if (ms > 0) formatTime(ms) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp)
                )
                MenuButton(anchor)
            }
        }
        SongMenu(anchor, song, player, menuExtra)
    }
}
