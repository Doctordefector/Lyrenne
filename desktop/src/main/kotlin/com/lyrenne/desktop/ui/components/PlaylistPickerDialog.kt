package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyrenne.desktop.db.DatabaseHelper
import com.lyrenne.desktop.db.Playlist
import com.lyrenne.desktop.media.suppressMediaKeys
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.sync.YouTubeWrites
import kotlinx.coroutines.launch

@Composable
fun PlaylistPickerDialog(
    song: SongInfo,
    playlists: List<Playlist>,
    onDismiss: () -> Unit
) {
    // Check duplicates for each playlist
    val duplicateMap = remember(song.id, playlists) {
        playlists.associate { it.id to DatabaseHelper.isSongInPlaylist(it.id, song.id) }
    }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun addToPlaylist(playlistId: String) {
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            thumbnailUrl = song.thumbnailUrl,
            albumName = song.album
        )
        val position = DatabaseHelper.getPlaylistSongCount(playlistId)
        DatabaseHelper.addSongToPlaylist(playlistId, song.id, position)
        // Mirror to YouTube — without this the song only ever existed in the local DB.
        YouTubeWrites.addToPlaylist(playlistId, song.id)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Create new playlist row
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("Playlist name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).suppressMediaKeys()
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    scope.launch {
                                        // Creates the playlist on YouTube first when signed in,
                                        // so the song added next has somewhere to mirror to.
                                        val id = YouTubeWrites.createPlaylist(newName.trim())
                                        addToPlaylist(id)
                                    }
                                }
                            },
                            enabled = newName.isNotBlank()
                        ) {
                            Icon(Icons.Default.Check, "Create")
                        }
                    }
                } else {
                    ListItem(
                        headlineContent = { Text("New playlist") },
                        leadingContent = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { creating = true }
                    )
                }

                if (playlists.isEmpty() && !creating) {
                    Text(
                        "No playlists yet — create one above",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    playlists.forEach { playlist ->
                        val alreadyIn = duplicateMap[playlist.id] == true
                        ListItem(
                            headlineContent = {
                                Text(
                                    playlist.name,
                                    color = if (alreadyIn)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (alreadyIn) "Already added"
                                    else "${playlist.remoteSongCount ?: 0} songs"
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (alreadyIn) Icons.Default.Check
                                    else Icons.AutoMirrored.Filled.PlaylistAdd,
                                    null,
                                    tint = if (alreadyIn)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable(enabled = !alreadyIn) {
                                addToPlaylist(playlist.id)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
