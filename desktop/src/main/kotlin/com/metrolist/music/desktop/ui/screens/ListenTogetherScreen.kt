package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.listentogether.*
import com.metrolist.music.desktop.playback.DesktopPlayer
import kotlinx.coroutines.launch

@Composable
fun ListenTogetherScreen(
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val connectionState by ListenTogetherClient.connectionState.collectAsState()
    val roomState by ListenTogetherClient.roomState.collectAsState()
    val role by ListenTogetherClient.role.collectAsState()
    val pendingRequests by ListenTogetherClient.pendingJoinRequests.collectAsState()
    val pendingSuggestions by ListenTogetherClient.pendingSuggestions.collectAsState()

    var username by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    // Initialize manager with player
    LaunchedEffect(Unit) {
        ListenTogetherManager.initialize(player)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "Listen Together",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )

            // Connection status indicator
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            val statusText = when (connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.RECONNECTING -> "Reconnecting..."
                ConnectionState.ERROR -> "Error"
                ConnectionState.DISCONNECTED -> "Disconnected"
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.15f),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider()

        if (roomState == null) {
            // No room — show create/join options
            NoRoomView(
                onCreateClick = { showCreateDialog = true },
                onJoinClick = { showJoinDialog = true }
            )
        } else {
            // In a room
            RoomView(
                roomState = roomState!!,
                role = role,
                pendingRequests = pendingRequests,
                pendingSuggestions = pendingSuggestions,
                player = player,
                onLeave = {
                    ListenTogetherManager.leaveRoom()
                }
            )
        }
    }

    // Create Room Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Room") },
            text = {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (username.isNotBlank()) {
                            ListenTogetherManager.createRoom(username.trim())
                            showCreateDialog = false
                        }
                    },
                    enabled = username.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Join Room Dialog
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join Room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it.uppercase() },
                        label = { Text("Room code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (username.isNotBlank() && roomCode.isNotBlank()) {
                            ListenTogetherManager.joinRoom(roomCode.trim(), username.trim())
                            showJoinDialog = false
                        }
                    },
                    enabled = username.isNotBlank() && roomCode.isNotBlank()
                ) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoRoomView(
    onCreateClick: () -> Unit,
    onJoinClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Group,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Listen Together",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Create or join a room to listen to music in sync with friends",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp)
        )

        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onCreateClick,
                modifier = Modifier.width(160.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create Room")
            }

            OutlinedButton(
                onClick = onJoinClick,
                modifier = Modifier.width(160.dp)
            ) {
                Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Join Room")
            }
        }
    }
}

@Composable
private fun RoomView(
    roomState: RoomState,
    role: RoomRole,
    pendingRequests: List<JoinRequestPayload>,
    pendingSuggestions: List<SuggestionReceivedPayload>,
    player: DesktopPlayer,
    onLeave: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize()) {
        // Left side: room info + users
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)
        ) {
            // Room code card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Room Code", style = MaterialTheme.typography.labelMedium)
                    Text(
                        roomState.roomCode,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Share this code with friends",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Now playing
            if (roomState.currentTrack != null) {
                val track = roomState.currentTrack
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (roomState.isPlaying) "Now Playing" else "Paused",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Users
            Text(
                "Listeners (${roomState.users.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(roomState.users) { user ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(user.username)
                                if (user.isHost) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            "Host",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (user.isConnected) Icons.Default.Person else Icons.Default.PersonOff,
                                null,
                                tint = if (user.isConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            if (role == RoomRole.HOST && !user.isHost) {
                                IconButton(onClick = {
                                    ListenTogetherManager.kickUser(user.userId)
                                }) {
                                    Icon(Icons.Default.RemoveCircle, "Kick", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    )
                }
            }

            // Leave button
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Leave Room")
            }
        }

        // Right side: pending requests + suggestions (host only)
        if (role == RoomRole.HOST && (pendingRequests.isNotEmpty() || pendingSuggestions.isNotEmpty())) {
            VerticalDivider()
            Column(
                modifier = Modifier.width(300.dp).fillMaxHeight().padding(16.dp)
            ) {
                if (pendingRequests.isNotEmpty()) {
                    Text(
                        "Join Requests",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    pendingRequests.forEach { request ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(request.username, fontWeight = FontWeight.Medium)
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { ListenTogetherManager.approveJoin(request.userId) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text("Accept") }
                                    OutlinedButton(
                                        onClick = { ListenTogetherManager.rejectJoin(request.userId) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text("Decline") }
                                }
                            }
                        }
                    }
                }

                if (pendingSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Track Suggestions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    pendingSuggestions.forEach { suggestion ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    suggestion.trackInfo.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "by ${suggestion.fromUsername}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { ListenTogetherManager.approveSuggestion(suggestion.suggestionId) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text("Add") }
                                    OutlinedButton(
                                        onClick = { ListenTogetherManager.rejectSuggestion(suggestion.suggestionId) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text("Skip") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
