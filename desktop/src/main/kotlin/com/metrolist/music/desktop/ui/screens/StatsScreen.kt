package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.ui.components.ScrollableRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StatPeriod(val label: String, val durationMs: Long) {
    WEEK_1("1 Week", 7L * 24 * 60 * 60 * 1000),
    MONTH_1("1 Month", 30L * 24 * 60 * 60 * 1000),
    MONTH_3("3 Months", 90L * 24 * 60 * 60 * 1000),
    MONTH_6("6 Months", 180L * 24 * 60 * 60 * 1000),
    YEAR_1("1 Year", 365L * 24 * 60 * 60 * 1000),
    ALL("All Time", 0L);

    fun fromTimestamp(): Long {
        if (this == ALL) return 0L
        return System.currentTimeMillis() - durationMs
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    player: DesktopPlayer? = null
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedPeriod by remember { mutableStateOf(StatPeriod.MONTH_1) }

    var topSongs by remember { mutableStateOf<List<DatabaseHelper.SongStats>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<DatabaseHelper.ArtistStats>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<DatabaseHelper.AlbumStats>>(emptyList()) }
    var totalPlayTime by remember { mutableStateOf(0L) }
    var uniqueSongs by remember { mutableStateOf(0L) }
    var uniqueArtists by remember { mutableStateOf(0L) }
    var eventCount by remember { mutableStateOf(0L) }

    // Load stats when period changes
    LaunchedEffect(selectedPeriod) {
        withContext(Dispatchers.IO) {
            val from = selectedPeriod.fromTimestamp()
            val to = System.currentTimeMillis()
            topSongs = DatabaseHelper.getMostPlayedSongs(from, to)
            topArtists = DatabaseHelper.getMostPlayedArtists(from, to)
            topAlbums = DatabaseHelper.getMostPlayedAlbums(from, to)
            totalPlayTime = DatabaseHelper.getTotalPlayTimeInRange(from, to)
            uniqueSongs = DatabaseHelper.getUniqueSongCountInRange(from, to)
            uniqueArtists = DatabaseHelper.getUniqueArtistCountInRange(from, to)
            eventCount = DatabaseHelper.getEventCount()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
            Text("Listening Stats", style = MaterialTheme.typography.headlineMedium)
        }

        Spacer(Modifier.height(16.dp))

        // Stats / History tabs
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.width(320.dp)) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Stats") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History") })
        }

        Spacer(Modifier.height(16.dp))

        if (selectedTab == 1) {
            HistoryTab(player = player)
            return@Column
        }

        // Period selector chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = { Text(period.label) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (eventCount == 0L) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No listening data yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Play some music and your stats will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Overview cards
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("Listening Time", formatDuration(totalPlayTime), Modifier.weight(1f))
                StatCard("Songs Played", uniqueSongs.toString(), Modifier.weight(1f))
                StatCard("Artists", uniqueArtists.toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(28.dp))

            // Most Played Songs
            if (topSongs.isNotEmpty()) {
                Text("Most Played Songs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                ScrollableRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topSongs) { song ->
                        StatsItemCard(
                            title = song.title,
                            subtitle = "${song.playCount} plays  •  ${formatDuration(song.totalTime)}",
                            thumbnailUrl = song.thumbnailUrl,
                            onClick = {}
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Most Played Artists
            if (topArtists.isNotEmpty()) {
                Text("Most Played Artists", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                ScrollableRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topArtists) { artist ->
                        StatsItemCard(
                            title = artist.name,
                            subtitle = "${artist.playCount} plays  •  ${formatDuration(artist.totalTime)}",
                            thumbnailUrl = artist.thumbnailUrl,
                            isCircular = true,
                            onClick = { onArtistClick(artist.id) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Most Played Albums
            if (topAlbums.isNotEmpty()) {
                Text("Most Played Albums", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                ScrollableRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topAlbums) { album ->
                        StatsItemCard(
                            title = album.title,
                            subtitle = "${album.playCount} plays  •  ${formatDuration(album.totalTime)}",
                            thumbnailUrl = album.thumbnailUrl,
                            onClick = { onAlbumClick(album.id) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HistoryTab(player: DesktopPlayer?) {
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<DatabaseHelper.PlayEvent>>(emptyList()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        events = withContext(Dispatchers.IO) { DatabaseHelper.getAllEvents() }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear listen history?") },
            text = { Text("This permanently deletes all ${events.size} history entries and resets your stats. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        DatabaseHelper.clearAllEvents()
                        refreshKey++
                    }
                    showClearDialog = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No listen history yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${events.size} entries",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear history")
            }
        }
        Spacer(Modifier.height(8.dp))

        val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(events.size) { index ->
                val event = events[index]
                ListItem(
                    headlineContent = {
                        Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${dateFormat.format(Date(event.timestamp))}  •  played ${formatDuration(event.playTime)}",
                            maxLines = 1
                        )
                    },
                    leadingContent = {
                        AsyncImage(
                            model = event.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    },
                    modifier = if (player != null) {
                        Modifier.clickable {
                            scope.launch {
                                player.playSong(
                                    SongInfo(
                                        id = event.songId,
                                        title = event.title,
                                        artist = event.albumName ?: "",
                                        thumbnailUrl = event.thumbnailUrl
                                    )
                                )
                            }
                        }
                    } else Modifier
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsItemCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    isCircular: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.width(160.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(if (isCircular) CircleShape else RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier.size(120.dp).clip(if (isCircular) CircleShape else RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isCircular) Icons.Default.Person else Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60

    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
