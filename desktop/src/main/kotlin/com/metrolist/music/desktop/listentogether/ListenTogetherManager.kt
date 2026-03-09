/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.desktop.listentogether

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.PlaybackState
import com.metrolist.music.desktop.playback.SongInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Manager that bridges the Listen Together WebSocket client with the DesktopPlayer.
 * Handles syncing playback actions between connected users.
 */
object ListenTogetherManager {
    private const val TAG = "ListenTogetherManager"
    // Debounce threshold for playback syncs - prevents excessive seeking/pausing
    private const val SYNC_DEBOUNCE_THRESHOLD_MS = 1000L
    // Position tolerance - only seek if difference exceeds this (prevents micro-adjustments)
    private const val POSITION_TOLERANCE_MS = 2000L
    // Large position tolerance - only seek during playback if difference exceeds this
    private const val PLAYBACK_POSITION_TOLERANCE_MS = 3000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = ListenTogetherClient

    private var player: DesktopPlayer? = null
    private var eventCollectorJob: Job? = null
    private var playerObserverJob: Job? = null
    private var heartbeatJob: Job? = null

    // Whether we're currently syncing (to prevent feedback loops)
    @Volatile
    private var isSyncing = false

    // Track the last state we synced to avoid duplicate events
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSyncedTrackId: String? = null

    // Track last sync action time for debouncing
    private var lastSyncActionTime: Long = 0L

    // Track ID being buffered
    private var bufferingTrackId: String? = null

    // Track active sync job
    private var activeSyncJob: Job? = null

    // Generation ID for track changes
    private var currentTrackGeneration: Int = 0

    // Pending sync to apply after buffering completes for guest
    private var pendingSyncState: SyncStatePayload? = null

    // Track if a buffer-complete arrived before the pending sync was ready
    private var bufferCompleteReceivedForTrack: String? = null

    // Expose client state for UI
    val connectionState = client.connectionState
    val roomState = client.roomState
    val role = client.role
    val userId = client.userId
    val pendingJoinRequests = client.pendingJoinRequests
    val bufferingUsers = client.bufferingUsers
    val logs = client.logs
    val events = client.events
    val pendingSuggestions = client.pendingSuggestions

    val isInRoom: Boolean get() = client.isInRoom
    val isHost: Boolean get() = client.isHost
    val hasPersistedSession: Boolean get() = client.hasPersistedSession

    /**
     * Initialize the manager with a DesktopPlayer instance.
     * Should be called once at app start after the player is created.
     */
    fun initialize(desktopPlayer: DesktopPlayer) {
        Timber.tag(TAG).d("Initializing ListenTogetherManager")
        player = desktopPlayer

        // Start collecting events from the client
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            client.events.collect { event ->
                try {
                    Timber.tag(TAG).d("Received event: $event")
                    handleEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling event: $event")
                }
            }
        }

        // Observe player state changes for host sync
        startPlayerObservation()

        // Observe role changes
        scope.launch {
            role.collectLatest { newRole ->
                try {
                    if (newRole == RoomRole.HOST) {
                        startHeartbeat()
                    } else {
                        stopHeartbeat()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in role change handler")
                }
            }
        }
    }

    private fun startPlayerObservation() {
        playerObserverJob?.cancel()
        playerObserverJob = scope.launch {
            val p = player ?: return@launch

            // Observe playback state changes for host
            var previousState: PlaybackState? = null
            p.state.collectLatest { state ->
                if (isSyncing || !isHost || !isInRoom) return@collectLatest

                val prev = previousState
                previousState = state

                if (prev == null) return@collectLatest

                // Detect track change
                val currentTrackId = state.currentSong?.id
                if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                    lastSyncedTrackId = currentTrackId
                    lastSyncedIsPlaying = false // Server resets on track change

                    state.currentSong?.let { song ->
                        Timber.tag(TAG).d("Host sending track change: ${song.title}")
                        sendTrackChange(song)

                        // If host is playing during track change, send PLAY
                        if (state.isPlaying) {
                            lastSyncedIsPlaying = true
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        }
                    }
                    return@collectLatest
                }

                // Detect play/pause change
                if (prev.isPlaying != state.isPlaying) {
                    if (state.isPlaying) {
                        Timber.tag(TAG).d("Host sending PLAY at position ${state.position}")
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        lastSyncedIsPlaying = true
                    } else if (lastSyncedIsPlaying == true) {
                        Timber.tag(TAG).d("Host sending PAUSE at position ${state.position}")
                        client.sendPlaybackAction(PlaybackActions.PAUSE, position = state.position)
                        lastSyncedIsPlaying = false
                    }
                }

                // Detect seek (position jump while same track)
                if (prev.currentSong?.id == state.currentSong?.id && prev.isPlaying == state.isPlaying) {
                    val posDiff = kotlin.math.abs(state.position - prev.position)
                    // Detect seek: large jump that isn't just normal playback progression
                    // Normal playback: ~200ms updates, so anything > 2s is likely a seek
                    if (posDiff > 2000 && state.currentSong != null) {
                        Timber.tag(TAG).d("Host sending SEEK to ${state.position}")
                        client.sendPlaybackAction(PlaybackActions.SEEK, position = state.position)
                    }
                }
            }
        }
    }

    private fun handleEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Connected -> {
                Timber.tag(TAG).d("Connected to server with userId: ${event.userId}")
            }

            is ListenTogetherEvent.RoomCreated -> {
                Timber.tag(TAG).d("Room created: ${event.roomCode}")
                try {
                    val p = player ?: return
                    val state = p.state.value

                    // Initialize sync state
                    lastSyncedIsPlaying = state.isPlaying
                    lastSyncedTrackId = state.currentSong?.id

                    // If there's already a track loaded, send it to the server
                    state.currentSong?.let { song ->
                        Timber.tag(TAG).d("Room created with existing track: ${song.title}")
                        sendTrackChange(song)

                        if (state.isPlaying) {
                            lastSyncedIsPlaying = true
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        }
                    }
                    startHeartbeat()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling RoomCreated event")
                }
            }

            is ListenTogetherEvent.JoinApproved -> {
                Timber.tag(TAG).d("Join approved for room: ${event.roomCode}")
                applyPlaybackState(
                    currentTrack = event.state.currentTrack,
                    isPlaying = event.state.isPlaying,
                    position = event.state.position,
                    queue = event.state.queue
                )
            }

            is ListenTogetherEvent.PlaybackSync -> {
                Timber.tag(TAG).d("PlaybackSync received: ${event.action.action}")
                if (!isHost) {
                    handlePlaybackSync(event.action)
                }
            }

            is ListenTogetherEvent.UserJoined -> {
                Timber.tag(TAG).d("[SYNC] User joined: ${event.username}")
                if (isHost) {
                    try {
                        val p = player ?: return
                        val state = p.state.value
                        state.currentSong?.let { song ->
                            Timber.tag(TAG).d("[SYNC] Sending current track to newly joined user: ${song.title}")
                            sendTrackChange(song)
                            if (state.isPlaying) {
                                Timber.tag(TAG).d("[SYNC] Host playing, sending PLAY at ${state.position} for new joiner")
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling UserJoined event")
                    }
                }
            }

            is ListenTogetherEvent.BufferWait -> {
                Timber.tag(TAG).d("BufferWait: waiting for ${event.waitingFor.size} users")
            }

            is ListenTogetherEvent.BufferComplete -> {
                Timber.tag(TAG).d("BufferComplete for track: ${event.trackId}")
                if (!isHost && bufferingTrackId == event.trackId) {
                    bufferCompleteReceivedForTrack = event.trackId
                    applyPendingSyncIfReady()
                }
            }

            is ListenTogetherEvent.SyncStateReceived -> {
                Timber.tag(TAG).d("SyncStateReceived: playing=${event.state.isPlaying}, pos=${event.state.position}")
                if (!isHost) {
                    handleSyncState(event.state)
                }
            }

            is ListenTogetherEvent.Kicked -> {
                Timber.tag(TAG).d("Kicked from room: ${event.reason}")
                cleanup()
            }

            is ListenTogetherEvent.Disconnected -> {
                Timber.tag(TAG).d("Disconnected from server")
            }

            is ListenTogetherEvent.Reconnecting -> {
                Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}/${event.maxAttempts}")
            }

            is ListenTogetherEvent.Reconnected -> {
                Timber.tag(TAG).d("Reconnected to room: ${event.roomCode}, isHost: ${event.isHost}")
                try {
                    if (event.isHost) {
                        val p = player ?: return
                        val state = p.state.value
                        lastSyncedIsPlaying = state.isPlaying
                        lastSyncedTrackId = state.currentSong?.id

                        // Check if server already has the right track
                        val serverTrackId = event.state.currentTrack?.id
                        if (serverTrackId != state.currentSong?.id && state.currentSong != null) {
                            sendTrackChange(state.currentSong!!)
                        }

                        scope.launch {
                            delay(500)
                            val currentState = player?.state?.value ?: return@launch
                            if (currentState.isPlaying) {
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = currentState.position)
                            }
                        }
                    } else {
                        // Guest: sync to host's state
                        applyPlaybackState(
                            currentTrack = event.state.currentTrack,
                            isPlaying = event.state.isPlaying,
                            position = event.state.position,
                            queue = event.state.queue,
                            bypassBuffer = true
                        )

                        scope.launch {
                            delay(1000)
                            if (isInRoom && !isHost) {
                                requestSync()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling Reconnected event")
                }
            }

            is ListenTogetherEvent.UserReconnected -> {
                Timber.tag(TAG).d("User reconnected: ${event.username}")
            }

            is ListenTogetherEvent.UserDisconnected -> {
                Timber.tag(TAG).d("User temporarily disconnected: ${event.username}")
            }

            is ListenTogetherEvent.HostChanged -> {
                Timber.tag(TAG).d("Host changed: new host is ${event.newHostName} (${event.newHostId})")
                val nowIsHost = event.newHostId == userId.value
                if (nowIsHost) {
                    // Gained host role: sync current state
                    val p = player ?: return
                    val state = p.state.value
                    state.currentSong?.let { song ->
                        sendTrackChange(song)
                        if (state.isPlaying) {
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        }
                    }
                    startHeartbeat()
                } else {
                    stopHeartbeat()
                }
            }

            is ListenTogetherEvent.ConnectionError -> {
                Timber.tag(TAG).e("Connection error: ${event.error}")
                cleanup()
            }

            else -> { /* Other events handled by UI */ }
        }
    }

    private fun cleanup() {
        stopHeartbeat()
        lastSyncedIsPlaying = null
        lastSyncedTrackId = null
        bufferingTrackId = null
        isSyncing = false
        bufferCompleteReceivedForTrack = null
        lastSyncActionTime = 0L
        ++currentTrackGeneration
    }

    private fun applyPendingSyncIfReady() {
        val pending = pendingSyncState ?: return
        val pendingTrackId = pending.currentTrack?.id ?: bufferingTrackId ?: return
        val completeForTrack = bufferCompleteReceivedForTrack

        if (completeForTrack != pendingTrackId) return

        val p = player ?: return
        val state = p.state.value

        Timber.tag(TAG).d("Applying pending sync: track=$pendingTrackId, pos=${pending.position}, play=${pending.isPlaying}")
        isSyncing = true

        val targetPos = pending.position
        val posDiff = kotlin.math.abs(state.position - targetPos)
        val willPlay = pending.isPlaying

        val tolerance = if (willPlay && state.isPlaying) PLAYBACK_POSITION_TOLERANCE_MS else POSITION_TOLERANCE_MS

        if (posDiff > tolerance) {
            Timber.tag(TAG).d("Applying pending sync: seeking ${state.position} -> $targetPos")
            p.seekTo(targetPos)
        }

        if (willPlay && !state.isPlaying) {
            p.play()
        } else if (!willPlay && state.isPlaying) {
            p.pause()
        }

        scope.launch {
            delay(200)
            isSyncing = false
        }

        bufferingTrackId = null
        pendingSyncState = null
        bufferCompleteReceivedForTrack = null
    }

    private fun handlePlaybackSync(action: PlaybackActionPayload) {
        val p = player ?: run {
            Timber.tag(TAG).w("Cannot sync playback - no player")
            return
        }
        val state = p.state.value

        Timber.tag(TAG).d("Handling playback sync: ${action.action}, position: ${action.position}")

        isSyncing = true

        try {
            when (action.action) {
                PlaybackActions.PLAY -> {
                    val basePos = action.position ?: 0L
                    val now = System.currentTimeMillis()
                    val adjustedPos = action.serverTime?.let { serverTime ->
                        basePos + kotlin.math.max(0L, now - serverTime)
                    } ?: basePos

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = true,
                            position = adjustedPos,
                            lastUpdate = now
                        )).copy(isPlaying = true, position = adjustedPos, lastUpdate = now)
                        applyPendingSyncIfReady()
                        return
                    }

                    val posDiff = kotlin.math.abs(state.position - adjustedPos)
                    val alreadyPlaying = state.isPlaying

                    if (alreadyPlaying && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PLAY debounced")
                        return
                    }

                    if (alreadyPlaying) {
                        if (posDiff > PLAYBACK_POSITION_TOLERANCE_MS) {
                            p.seekTo(adjustedPos)
                        }
                    } else {
                        if (posDiff > POSITION_TOLERANCE_MS) {
                            p.seekTo(adjustedPos)
                        }
                        p.play()
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.PAUSE -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = false,
                            position = pos,
                            lastUpdate = now
                        )).copy(isPlaying = false, position = pos, lastUpdate = now)
                        applyPendingSyncIfReady()
                        return
                    }

                    val posDiff = kotlin.math.abs(state.position - pos)
                    val alreadyPaused = !state.isPlaying

                    if (alreadyPaused && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PAUSE debounced")
                        return
                    }

                    if (state.isPlaying) {
                        p.pause()
                    }

                    if (posDiff > POSITION_TOLERANCE_MS) {
                        p.seekTo(pos)
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.SEEK -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    if (now - lastSyncActionTime < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: SEEK debounced")
                        return
                    }

                    if (kotlin.math.abs(state.position - pos) > POSITION_TOLERANCE_MS) {
                        p.seekTo(pos)
                        lastSyncActionTime = now
                    }
                }

                PlaybackActions.CHANGE_TRACK -> {
                    action.trackInfo?.let { track ->
                        Timber.tag(TAG).d("Guest: CHANGE_TRACK to ${track.title}")
                        lastSyncActionTime = 0L

                        if (action.queue != null && action.queue.isNotEmpty()) {
                            applyPlaybackState(
                                currentTrack = track,
                                isPlaying = false,
                                position = 0,
                                queue = action.queue
                            )
                        } else {
                            // Load single track
                            bufferingTrackId = track.id
                            syncToTrack(track, false, 0)
                        }
                    }
                }

                PlaybackActions.SKIP_NEXT -> {
                    Timber.tag(TAG).d("Guest: SKIP_NEXT")
                    scope.launch { p.playNext() }
                }

                PlaybackActions.SKIP_PREV -> {
                    Timber.tag(TAG).d("Guest: SKIP_PREV")
                    scope.launch { p.playPrevious() }
                }

                PlaybackActions.QUEUE_ADD -> {
                    val track = action.trackInfo
                    if (track != null) {
                        Timber.tag(TAG).d("Guest: QUEUE_ADD ${track.title}")
                        val song = trackInfoToSongInfo(track)
                        if (action.insertNext == true) {
                            p.addToQueueNext(song)
                        } else {
                            p.addToQueue(song)
                        }
                    }
                }

                PlaybackActions.QUEUE_REMOVE -> {
                    val removeId = action.trackId
                    if (!removeId.isNullOrEmpty()) {
                        Timber.tag(TAG).d("Guest: QUEUE_REMOVE $removeId")
                        val queue = p.state.value.queue
                        val idx = queue.indexOfFirst { it.id == removeId }
                        if (idx >= 0 && idx != p.state.value.currentIndex) {
                            p.removeFromQueue(idx)
                        }
                    }
                }

                PlaybackActions.QUEUE_CLEAR -> {
                    Timber.tag(TAG).d("Guest: QUEUE_CLEAR")
                    p.clearQueue()
                }

                PlaybackActions.SET_VOLUME -> {
                    val vol = action.volume
                    if (vol != null) {
                        Timber.tag(TAG).d("Guest: SET_VOLUME $vol")
                        p.setVolume(vol.coerceIn(0f, 1f))
                    }
                }

                PlaybackActions.SYNC_QUEUE -> {
                    val queue = action.queue
                    if (queue != null) {
                        Timber.tag(TAG).d("Guest: SYNC_QUEUE size=${queue.size}")
                        // For desktop, we rebuild the queue from TrackInfo
                        val songs = queue.map { trackInfoToSongInfo(it) }
                        val currentId = state.currentSong?.id
                        val newIndex = if (currentId != null) songs.indexOfFirst { it.id == currentId } else -1

                        if (newIndex >= 0) {
                            // Rebuild queue keeping current track position
                            scope.launch {
                                p.playQueue(songs, newIndex)
                            }
                        }
                    }
                }
            }
        } finally {
            scope.launch {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun handleSyncState(syncState: SyncStatePayload) {
        Timber.tag(TAG).d("handleSyncState: playing=${syncState.isPlaying}, pos=${syncState.position}, track=${syncState.currentTrack?.id}")
        applyPlaybackState(
            currentTrack = syncState.currentTrack,
            isPlaying = syncState.isPlaying,
            position = syncState.position,
            queue = syncState.queue,
            bypassBuffer = true
        )
    }

    private fun applyPlaybackState(
        currentTrack: TrackInfo?,
        isPlaying: Boolean,
        position: Long,
        queue: List<TrackInfo>?,
        bypassBuffer: Boolean = false
    ) {
        val p = player ?: run {
            Timber.tag(TAG).w("Cannot apply playback state - no player")
            return
        }

        Timber.tag(TAG).d("Applying playback state: track=${currentTrack?.id}, pos=$position, queue=${queue?.size}, bypassBuffer=$bypassBuffer")

        activeSyncJob?.cancel()

        if (currentTrack == null) {
            Timber.tag(TAG).d("No track in state, pausing")
            p.pause()
            pendingSyncState = null
            bufferingTrackId = null
            bufferCompleteReceivedForTrack = null
            return
        }

        bufferingTrackId = currentTrack.id
        val generation = ++currentTrackGeneration

        activeSyncJob = scope.launch {
            if (currentTrackGeneration != generation) return@launch
            isSyncing = true

            try {
                if (queue != null && queue.isNotEmpty()) {
                    val songs = queue.map { trackInfoToSongInfo(it) }
                    val startIndex = songs.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)

                    p.playQueue(songs, startIndex)
                } else {
                    // Load single track
                    val song = trackInfoToSongInfo(currentTrack)
                    p.playSong(song)
                }

                // Wait briefly for player to start loading
                delay(500)

                if (currentTrackGeneration != generation) return@launch

                if (bypassBuffer) {
                    // Bypass buffer protocol: seek and apply play/pause immediately
                    p.seekTo(position)

                    if (isPlaying) {
                        p.play()
                    } else {
                        p.pause()
                    }

                    pendingSyncState = null
                    bufferingTrackId = null
                    bufferCompleteReceivedForTrack = null
                } else {
                    // Normal sync: pause, store pending, send buffer_ready
                    p.pause()
                    pendingSyncState = SyncStatePayload(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        position = position,
                        lastUpdate = System.currentTimeMillis()
                    )
                    applyPendingSyncIfReady()
                    client.sendBufferReady(currentTrack.id)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying playback state")
            } finally {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun syncToTrack(track: TrackInfo, shouldPlay: Boolean, position: Long) {
        Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

        bufferingTrackId = track.id
        val generation = currentTrackGeneration

        activeSyncJob?.cancel()
        activeSyncJob = scope.launch {
            try {
                if (currentTrackGeneration != generation) return@launch

                val song = trackInfoToSongInfo(track)
                val p = player ?: run {
                    isSyncing = false
                    return@launch
                }

                isSyncing = true
                p.playSong(song)

                // Wait for player to be ready
                delay(1000)

                if (currentTrackGeneration != generation) return@launch

                p.pause()

                pendingSyncState = SyncStatePayload(
                    currentTrack = track,
                    isPlaying = shouldPlay,
                    position = position,
                    lastUpdate = System.currentTimeMillis()
                )

                applyPendingSyncIfReady()
                client.sendBufferReady(track.id)

                delay(100)
                isSyncing = false
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing to track")
                isSyncing = false
            }
        }
    }

    // --- Track info conversion ---

    private fun songInfoToTrackInfo(song: SongInfo): TrackInfo {
        return TrackInfo(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = song.durationMs.takeIf { it > 0 } ?: (song.duration.toLong() * 1000).takeIf { it > 0 } ?: 180000L,
            thumbnail = song.thumbnailUrl
        )
    }

    private fun trackInfoToSongInfo(track: TrackInfo): SongInfo {
        return SongInfo(
            id = track.id,
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.thumbnail,
            durationMs = track.duration,
            album = track.album,
            duration = (track.duration / 1000).toInt()
        )
    }

    /**
     * Send track change to server (host only).
     */
    private fun sendTrackChange(song: SongInfo) {
        if (!isHost) return

        val trackInfo = songInfoToTrackInfo(song)
        Timber.tag(TAG).d("Sending track change: ${trackInfo.title}, duration: ${trackInfo.duration}")

        // Always send a non-empty queue so guests stay in sync
        // (playSong() now emits queue state, but add fallback just in case)
        val currentQueue = try {
            val stateQueue = player?.state?.value?.queue?.map { songInfoToTrackInfo(it) }
            if (stateQueue.isNullOrEmpty()) listOf(trackInfo) else stateQueue
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current queue, sending single track")
            listOf(trackInfo)
        }

        client.sendPlaybackAction(
            PlaybackActions.CHANGE_TRACK,
            trackInfo = trackInfo,
            queue = currentQueue
        )
    }

    // --- Heartbeat ---

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isInRoom && isHost) {
                delay(15000L) // 15 seconds
                val p = player ?: continue
                val state = p.state.value
                if (state.isPlaying && state.currentSong != null) {
                    Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos ${state.position}")
                    client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                }
            }
        }
        Timber.tag(TAG).d("Host heartbeat started (15s interval)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.tag(TAG).d("Host heartbeat stopped")
    }

    // --- Public API (delegates to client) ---

    fun connect() {
        Timber.tag(TAG).d("Connecting to server")
        client.connect()
    }

    fun disconnect() {
        Timber.tag(TAG).d("Disconnecting from server")
        cleanup()
        client.disconnect()
    }

    fun createRoom(username: String) {
        Timber.tag(TAG).d("Creating room with username: $username")
        client.createRoom(username)
    }

    fun joinRoom(roomCode: String, username: String) {
        Timber.tag(TAG).d("Joining room $roomCode as $username")
        client.joinRoom(roomCode, username)
    }

    fun leaveRoom() {
        Timber.tag(TAG).d("Leaving room")
        cleanup()
        client.leaveRoom()
    }

    fun approveJoin(userId: String) = client.approveJoin(userId)
    fun rejectJoin(userId: String, reason: String? = null) = client.rejectJoin(userId, reason)
    fun kickUser(userId: String, reason: String? = null) = client.kickUser(userId, reason)
    fun transferHost(newHostId: String) = client.transferHost(newHostId)
    fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

    fun approveSuggestion(suggestionId: String) {
        if (!isHost) return
        client.approveSuggestion(suggestionId)
    }

    fun rejectSuggestion(suggestionId: String, reason: String? = null) = client.rejectSuggestion(suggestionId, reason)

    fun requestSync() {
        if (!isInRoom || isHost) return
        Timber.tag(TAG).d("Requesting sync from server")
        client.requestSync()
    }

    fun clearLogs() = client.clearLogs()

    fun forceReconnect() {
        Timber.tag(TAG).d("Forcing reconnection")
        client.forceReconnect()
    }

    fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()
    fun getSessionAge(): Long = client.getSessionAge()
}
