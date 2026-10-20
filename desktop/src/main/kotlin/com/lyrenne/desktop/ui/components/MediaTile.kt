package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.ui.screens.toSongInfo
import com.lyrenne.desktop.ui.theme.LyrenneTokens
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

/**
 * The one card for a song, album, artist, playlist or podcast in a shelf or grid: transparent,
 * with a hover fill, a 160 dp square (round for artists) and two lines of text. Songs carry the
 * song menu on right-click, so "Play next" works from Home too.
 */
@Composable
fun MediaTile(
    item: YTItem,
    player: DesktopPlayer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = item.subtitle()
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val tileShape = RoundedCornerShape(LyrenneTokens.panelRadius)
    val song = remember(item) { item.toSongInfo() }
    val anchor = rememberMenuAnchor()

    Box(if (song != null) modifier.contextMenu(anchor) else modifier) {
        Column(
            modifier = Modifier
                .width(172.dp)
                .clip(tileShape)
                .background(
                    if (isHovered || isFocused) MaterialTheme.colorScheme.surfaceContainerLow
                    else Color.Transparent
                )
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = tileShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                )
                .padding(6.dp)
        ) {
            AsyncImage(
                model = squareCropFriendly(item.thumbnail),
                // The surrounding clickable tile merges its title text into one accessible label.
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(if (item is ArtistItem) CircleShape else RoundedCornerShape(LyrenneTokens.artworkRadius)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp)) {
                Text(
                    text = item.title,
                    // Medium, not SemiBold: the section title above is the heavier one.
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (song != null) SongMenu(anchor, song, player)
    }
}

/** Second line under an item's title: who made it, or what kind of thing it is. */
fun YTItem.subtitle(): String = when (this) {
    is SongItem -> artists.joinToString { it.name }
    is AlbumItem -> artists?.joinToString { it.name } ?: "Album"
    is ArtistItem -> "Artist"
    is PlaylistItem -> author?.name ?: "Playlist"
    is PodcastItem -> author?.name ?: "Podcast"
    else -> ""
}
