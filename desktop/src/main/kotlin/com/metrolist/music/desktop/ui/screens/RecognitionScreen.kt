package com.metrolist.music.desktop.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.recognition.DesktopMusicRecognizer
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun RecognitionScreen(
    player: DesktopPlayer,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RecognitionStatus>(RecognitionStatus.Ready) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    val hasMicrophone = remember { DesktopMusicRecognizer.isMicrophoneAvailable() }

    fun startRecognition() {
        recognitionJob?.cancel()
        recognitionJob = scope.launch {
            status = RecognitionStatus.Listening
            val result = DesktopMusicRecognizer.recognize { newStatus ->
                status = newStatus
            }
            result.onSuccess { recognitionResult ->
                status = RecognitionStatus.Success(recognitionResult)
                // Save to recognition history
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.metrolist.music.desktop.db.DatabaseHelper.insertRecognition(
                            videoId = recognitionResult.youtubeVideoId,
                            title = recognitionResult.title,
                            artist = recognitionResult.artist,
                            thumbnailUrl = recognitionResult.coverArtUrl
                        )
                    } catch (_: Exception) {}
                }
            }.onFailure { error ->
                val message = error.message ?: "Recognition failed"
                if (message.contains("No match", ignoreCase = true)) {
                    status = RecognitionStatus.NoMatch()
                } else {
                    status = RecognitionStatus.Error(message)
                }
            }
        }
    }

    fun stopRecognition() {
        recognitionJob?.cancel()
        recognitionJob = null
        status = RecognitionStatus.Ready
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                stopRecognition()
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "Music Recognition",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Main content
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (val currentStatus = status) {
                is RecognitionStatus.Ready -> {
                    ReadyState(
                        hasMicrophone = hasMicrophone,
                        onStart = ::startRecognition,
                        player = player
                    )
                }
                is RecognitionStatus.Listening -> {
                    ListeningState(onCancel = ::stopRecognition)
                }
                is RecognitionStatus.Processing -> {
                    ProcessingState()
                }
                is RecognitionStatus.Success -> {
                    SuccessState(
                        result = currentStatus.result,
                        player = player,
                        onTryAgain = {
                            status = RecognitionStatus.Ready
                        }
                    )
                }
                is RecognitionStatus.NoMatch -> {
                    NoMatchState(
                        message = currentStatus.message,
                        onTryAgain = ::startRecognition
                    )
                }
                is RecognitionStatus.Error -> {
                    ErrorState(
                        message = currentStatus.message,
                        onTryAgain = ::startRecognition
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyState(
    hasMicrophone: Boolean,
    onStart: () -> Unit,
    player: DesktopPlayer? = null
) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<com.metrolist.music.desktop.db.DatabaseHelper.Recognition>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        history = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { com.metrolist.music.desktop.db.DatabaseHelper.getRecognitionHistory() }
            catch (_: Exception) { emptyList() }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxHeight().padding(vertical = 24.dp)
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            "Tap to identify a song",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Make sure the music is playing nearby",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (hasMicrophone) {
            Button(
                onClick = onStart,
                modifier = Modifier.size(120.dp),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Start listening",
                    modifier = Modifier.size(48.dp)
                )
            }
        } else {
            Text(
                "No microphone detected.\nPlease connect a microphone to use this feature.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Recognition history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) {
                Text(
                    "Recently recognized",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.metrolist.music.desktop.db.DatabaseHelper.clearRecognitionHistory()
                        refreshKey++
                    }
                }) { Text("Clear") }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.widthIn(max = 480.dp).weight(1f, fill = false)
            ) {
                items(history.size) { index ->
                    val rec = history[index]
                    ListItem(
                        headlineContent = { Text(rec.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(rec.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            AsyncImage(
                                model = rec.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                        },
                        trailingContent = {
                            if (rec.videoId != null && player != null) {
                                IconButton(onClick = {
                                    scope.launch {
                                        player.playSong(
                                            SongInfo(
                                                id = rec.videoId,
                                                title = rec.title,
                                                artist = rec.artist,
                                                thumbnailUrl = rec.thumbnailUrl
                                            )
                                        )
                                    }
                                }) {
                                    Icon(Icons.Default.PlayArrow, "Play")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningState(onCancel: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(80.dp).scale(scale),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            "Listening...",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Recording for 12 seconds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ProcessingState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))

        Text(
            "Identifying...",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Analyzing audio fingerprint",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessState(
    result: RecognitionResult,
    player: DesktopPlayer,
    onTryAgain: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(32.dp).widthIn(max = 500.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Album art
        result.coverArtHqUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = result.title,
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } ?: Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Song info
        Text(
            result.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            result.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        result.album?.let { album ->
            Text(
                album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Play on YouTube Music (if video ID available)
            result.youtubeVideoId?.let { videoId ->
                Button(
                    onClick = {
                        scope.launch {
                            val songInfo = SongInfo(
                                id = videoId,
                                title = result.title,
                                artist = result.artist,
                                thumbnailUrl = result.coverArtUrl,
                                album = result.album
                            )
                            player.playSong(songInfo)
                        }
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
            }

            OutlinedButton(onClick = onTryAgain) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Try Again")
            }
        }

        // Additional info
        result.genre?.let { genre ->
            Text(
                "Genre: $genre",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoMatchState(
    message: String,
    onTryAgain: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            "No Match Found",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Try again with the music playing louder or closer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Button(onClick = onTryAgain) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Try Again")
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onTryAgain: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Text(
            "Recognition Failed",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Button(onClick = onTryAgain) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Try Again")
        }
    }
}
