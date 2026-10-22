/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.lyrenne.desktop.listentogether

import com.lyrenne.desktop.playback.DesktopPlayer
import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.playback.SongInfo
import com.lyrenne.desktop.playback.knownDurationMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager that bridges the Listen Together WebSocket client with the DesktopPlayer.
 * Handles syncing playback actions between connected users.
 */
object ListenTogetherManager {
    private const val TAG = "ListenTogetherManager"
    // Debounce threshold for playback syncs - prevents excessive seeking/pausing
    private const val SYNC_DEBOUNCE_THRESHOLD_MS = 1000L
    // Position tolerance - only seek if difference exceeds this (prevents micro-adjustments)
    private const val POSITION_TOLERANCE_MS = 1000L
    // Tolerance during active playback - tighter for better sync
    private const val PLAYBACK_POSITION_TOLERANCE_MS = 1500L
    // Host heartbeat interval
    private const val HEARTBEAT_INTERVAL_MS = 5000L
    // Guest periodic resync interval for long tracks (>10 min)
    private const val GUEST_RESYNC_INTERVAL_MS = 60000L
    // Track duration threshold for long-track mode (10 minutes)
    private const val LONG_TRACK_THRESHOLD_MS = 600000L
    // Drift correction: consecutive small drifts in same direction trigger correction
    private const val DRIFT_CORRECTION_COUNT = 3
    // How long the player gets to report what a sync did before the observer listens again
    private const val SYNC_SETTLE_MS = 200L
    // Queue edits the host applies as well as guests. An approved suggestion reaches the host's own
    // queue only as a QUEUE_ADD the server sends to the whole room.
    private val QUEUE_EDITS = setOf(PlaybackActions.QUEUE_ADD, PlaybackActions.QUEUE_REMOVE, PlaybackActions.QUEUE_CLEAR)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = ListenTogetherClient

    private var player: DesktopPlayer? = null
    private var eventCollectorJob: Job? = null
    private var playerObserverJob: Job? = null
    private var roleObserverJob: Job? = null
    private var heartbeatJob: Job? = null
    private var guestResyncJob: Job? = null

    // How many syncs are applying room state right now. The player observer ignores the player
    // while any are, so what a sync does is not sent back to the room as a local change. A count,
    // not a flag, because syncs overlap: a PLAY arriving while a track loads used to clear the flag
    // the loading sync still needed, and the guest then announced the room's own track as its pick.
    // Every beginSync() is paired with an end in a finally. See CLAUDE.md before changing this.
    private val syncDepth = AtomicInteger(0)
    private val isSyncing: Boolean get() = syncDepth.get() > 0

    private fun beginSync() {
        syncDepth.incrementAndGet()
    }

    private fun endSync() {
        syncDepth.updateAndGet { maxOf(0, it - 1) }
    }

    /** Ends a sync once the player has had [SYNC_SETTLE_MS] to report what it did. */
    private fun endSyncSoon() {
        scope.launch {
            delay(SYNC_SETTLE_MS)
            endSync()
        }
    }

    // Track the last state we synced to avoid duplicate events
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSyncedTrackId: String? = null

    // Track last sync action time for debouncing
    private var lastSyncActionTime: Long = 0L

    // Drift correction: track consecutive small drifts in the same direction
    private var consecutiveDriftCount: Int = 0
    private var lastDriftDirection: Int = 0 // -1 = behind, +1 = ahead, 0 = none

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

    // The room's track when this client had neither a download nor a stream for it
    private var unavailableTrackId: String? = null

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
        // The Listen Together screen calls this every time it opens. Re-running it stacked another
        // role collector each visit and dropped room events while the event collector restarted.
        if (player === desktopPlayer && eventCollectorJob?.isActive == true) return
        Timber.tag(TAG).d("Initializing ListenTogetherManager")
        player = desktopPlayer
        // Only the host may autoplay. A guest sends track changes too, so a guest that ran out of
        // queue and autoplayed would move the whole room onto its own pick.
        desktopPlayer.autoplayAllowed = { !isInRoom || isHost }

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
        roleObserverJob?.cancel()
        roleObserverJob = scope.launch {
            role.collectLatest { newRole ->
                try {
                    if (newRole == RoomRole.HOST) {
                        startHeartbeat()
                        stopGuestResync()
                    } else {
                        stopHeartbeat()
                        startGuestResync()
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

            // Observe playback state changes — both host and guest send actions
            var previousState: PlaybackState? = null
            p.state.collectLatest { state ->
                // Advance even while syncing. Skipping this left prev at the state from before the
                // sync, so the first tick after it read the sync's own pause, play or seek as the
                // user's and sent it back to the room.
                val prev = previousState
                previousState = state

                if (isSyncing || !isInRoom || prev == null) return@collectLatest

                // Detect track change
                val currentTrackId = state.currentSong?.id
                if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                    lastSyncedTrackId = currentTrackId
                    lastSyncedIsPlaying = false // Server resets on track change

                    state.currentSong?.let { song ->
                        Timber.tag(TAG).d("Sending track change: ${song.title} (isHost=$isHost)")
                        sendTrackChange(song)

                        // If playing during track change, send PLAY
                        if (state.isPlaying) {
                            lastSyncedIsPlaying = true
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        }
                    }
                    return@collectLatest
                }

                // Detect play/pause change. Compared against what the room was last told, so VLC
                // reporting a sync's play late does not go back out as this client's PLAY.
                if (prev.isPlaying != state.isPlaying) {
                    if (state.isPlaying && lastSyncedIsPlaying != true) {
                        Timber.tag(TAG).d("Sending PLAY at position ${state.position} (isHost=$isHost)")
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                        lastSyncedIsPlaying = true
                    } else if (!state.isPlaying && lastSyncedIsPlaying == true) {
                        Timber.tag(TAG).d("Sending PAUSE at position ${state.position} (isHost=$isHost)")
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
                        Timber.tag(TAG).d("Sending SEEK to ${state.position} (isHost=$isHost)")
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
                if (!isHost || event.action.action in QUEUE_EDITS) {
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

                        // cleanup() stopped the heartbeat on the disconnect, and the role
                        // StateFlow re-emits the same HOST value so the role collector won't
                        // restart it. Restart it here or guests stop receiving position updates.
                        startHeartbeat()
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
        stopGuestResync()
        // A sync still loading belongs to the room just left, and a pending one would be copied into
        // the next room's first PLAY with this room's track. The cancelled job ends its own sync.
        activeSyncJob?.cancel()
        activeSyncJob = null
        pendingSyncState = null
        unavailableTrackId = null
        lastSyncedIsPlaying = null
        lastSyncedTrackId = null
        bufferingTrackId = null
        bufferCompleteReceivedForTrack = null
        lastSyncActionTime = 0L
        consecutiveDriftCount = 0
        lastDriftDirection = 0
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
        beginSync()
        try {
            val targetPos = pending.position
            val posDiff = kotlin.math.abs(state.position - targetPos)
            val willPlay = pending.isPlaying
            lastSyncedIsPlaying = willPlay

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
        } finally {
            endSyncSoon()
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

        // Track mismatch check: if we're on a different track than the room,
        // force a full re-sync instead of blindly applying play/pause/seek
        val roomTrackId = roomState.value?.currentTrack?.id
        val localTrackId = state.currentSong?.id
        if (roomTrackId != null && localTrackId != null && roomTrackId != localTrackId &&
            action.action in listOf(PlaybackActions.PLAY, PlaybackActions.PAUSE, PlaybackActions.SEEK)) {
            if (roomTrackId == unavailableTrackId) {
                // Already failed to load here. Retrying on every 5 s heartbeat re-resolved the stream
                // each time; Sync with Host and the next track change still try again.
                Timber.tag(TAG).d("Room track $roomTrackId is unavailable here, staying paused")
                return
            }
            Timber.tag(TAG).d("Track mismatch! Local=$localTrackId, Room=$roomTrackId, forcing full re-sync")
            val roomTrack = roomState.value?.currentTrack ?: return
            val roomQueue = roomState.value?.queue
            val roomPlaying = roomState.value?.isPlaying ?: false
            val roomPosition = action.position ?: roomState.value?.position ?: 0L
            applyPlaybackState(
                currentTrack = roomTrack,
                isPlaying = roomPlaying,
                position = roomPosition,
                queue = roomQueue,
                bypassBuffer = true
            )
            return
        }

        // Queue edits move nothing the observer reports, and the host applies them too, so they must
        // not mute its observer.
        val latched = action.action !in QUEUE_EDITS
        if (latched) beginSync()

        try {
            when (action.action) {
                PlaybackActions.PLAY -> {
                    lastSyncedIsPlaying = true
                    val basePos = action.position ?: 0L
                    val now = System.currentTimeMillis()
                    val adjustedPos = action.serverTime?.let { serverTime ->
                        basePos + kotlin.math.max(0L, now - serverTime)
                    } ?: basePos

                    // Clamp position to track duration to prevent seeking past end.
                    // A duration of 0 means "not known yet", not "zero length" — without the
                    // takeIf, coerceIn(0, 0) would pin every guest at the start of the track.
                    val trackDuration = roomState.value?.currentTrack?.duration
                        ?.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val clampedPos = adjustedPos.coerceIn(0L, trackDuration)

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = true,
                            position = clampedPos,
                            lastUpdate = now
                        )).copy(isPlaying = true, position = clampedPos, lastUpdate = now)
                        applyPendingSyncIfReady()
                        return
                    }

                    val posDiff = kotlin.math.abs(state.position - clampedPos)
                    val alreadyPlaying = state.isPlaying

                    if (alreadyPlaying && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PLAY debounced")
                        return
                    }

                    if (alreadyPlaying) {
                        if (posDiff > PLAYBACK_POSITION_TOLERANCE_MS) {
                            // Large drift — seek immediately
                            p.seekTo(clampedPos)
                            consecutiveDriftCount = 0
                            lastDriftDirection = 0
                        } else if (posDiff > 200) {
                            // Small drift — track direction for gradual correction
                            val direction = if (state.position < clampedPos) -1 else 1
                            if (direction == lastDriftDirection) {
                                consecutiveDriftCount++
                            } else {
                                consecutiveDriftCount = 1
                                lastDriftDirection = direction
                            }
                            // After DRIFT_CORRECTION_COUNT consecutive drifts in same direction, correct
                            if (consecutiveDriftCount >= DRIFT_CORRECTION_COUNT) {
                                Timber.tag(TAG).d("Guest: drift correction, seeking ${state.position} -> $clampedPos (${consecutiveDriftCount} consecutive)")
                                p.seekTo(clampedPos)
                                consecutiveDriftCount = 0
                                lastDriftDirection = 0
                            }
                        } else {
                            // In sync — reset drift tracking
                            consecutiveDriftCount = 0
                            lastDriftDirection = 0
                        }
                    } else {
                        if (posDiff > POSITION_TOLERANCE_MS) {
                            p.seekTo(clampedPos)
                        }
                        p.play()
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.PAUSE -> {
                    lastSyncedIsPlaying = false
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
                    // 0 means unknown, not zero-length. See the note on the other clamp.
                    val trackDuration = roomState.value?.currentTrack?.duration
                        ?.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val clampedPos = pos.coerceIn(0L, trackDuration)
                    val now = System.currentTimeMillis()

                    if (now - lastSyncActionTime < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: SEEK debounced")
                        return
                    }

                    if (kotlin.math.abs(state.position - clampedPos) > POSITION_TOLERANCE_MS) {
                        p.seekTo(clampedPos)
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
                        val clamped = vol.coerceIn(0f, 1f)
                        // Keep the stored preference in step or the MiniPlayer slider lies.
                        com.lyrenne.desktop.settings.PreferencesManager.setVolume(clamped)
                        p.setVolume(clamped)
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
                            // Swap the queue under the playing song. playQueue restarted it from 0:00.
                            p.replaceQueue(songs, newIndex)
                        }
                    }
                }
            }
        } finally {
            if (latched) endSyncSoon()
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
            lastSyncedIsPlaying = false
            p.pause()
            pendingSyncState = null
            bufferingTrackId = null
            bufferCompleteReceivedForTrack = null
            return
        }

        bufferingTrackId = currentTrack.id
        markSynced(currentTrack.id, isPlaying)
        val generation = ++currentTrackGeneration

        activeSyncJob = scope.launch {
            if (currentTrackGeneration != generation) return@launch
            beginSync()

            try {
                val loaded = if (queue != null && queue.isNotEmpty()) {
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

                if (!loaded) {
                    onTrackUnavailable(p, currentTrack.id, reportBuffered = !bypassBuffer)
                    return@launch
                }
                unavailableTrackId = null

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying playback state")
            } finally {
                // NonCancellable is load-bearing, not defensive. `delay` in a finally throws
                // immediately once the job is cancelled, which skipped the end below and left
                // the sync open forever. The player observer is gated on it, so this client
                // silently stopped sending any playback change to the room until it rejoined.
                // syncToTrack cancels this job by design, so the cancelled path is the common one.
                withContext(NonCancellable) {
                    delay(SYNC_SETTLE_MS)
                    endSync()
                }
            }
        }
    }

    private fun syncToTrack(track: TrackInfo, shouldPlay: Boolean, position: Long) {
        Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

        bufferingTrackId = track.id
        markSynced(track.id, shouldPlay)
        val generation = currentTrackGeneration

        activeSyncJob?.cancel()
        activeSyncJob = scope.launch {
            if (currentTrackGeneration != generation) return@launch
            val p = player ?: return@launch
            beginSync()

            try {
                val loaded = p.playSong(trackInfoToSongInfo(track))

                // Wait for player to be ready
                delay(1000)

                if (currentTrackGeneration != generation) return@launch

                if (!loaded) {
                    onTrackUnavailable(p, track.id, reportBuffered = true)
                    return@launch
                }
                unavailableTrackId = null

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing to track")
            } finally {
                // Every exit has to end the sync, and the generation check above is a plain early
                // return rather than an exception, so the old catch-only cleanup missed it. That
                // left the sync open forever and wedged the player observer, which is gated on
                // it. Two sync messages arriving inside the 1s delay is all it took.
                endSync()
            }
        }
    }

    /**
     * Records the room's track and play state as already known, so the player observer does not
     * announce them back once the sync settles. The guest used to skip this, saw the room's track
     * as differing from the last one it had sent, and sent it to the room as its own pick, which
     * reset everyone to paused at 0:00 on every track change.
     */
    private fun markSynced(trackId: String, isPlaying: Boolean) {
        lastSyncedTrackId = trackId
        lastSyncedIsPlaying = isPlaying
    }

    /**
     * The room's track has neither a download nor a stream here. Stays paused rather than play the
     * previous song at the room's position, and reports buffered anyway so one guest cannot hold
     * the whole room waiting. Remembered so the track-mismatch resync does not retry it on every
     * heartbeat.
     */
    private fun onTrackUnavailable(p: DesktopPlayer, trackId: String, reportBuffered: Boolean) {
        Timber.tag(TAG).w("Room track $trackId could not be loaded here")
        unavailableTrackId = trackId
        p.pause()
        pendingSyncState = null
        bufferingTrackId = null
        bufferCompleteReceivedForTrack = null
        if (reportBuffered) client.sendBufferReady(trackId)
    }

    // --- Track info conversion ---

    private fun songInfoToTrackInfo(song: SongInfo): TrackInfo {
        // Live duration first, metadata second, 0 last. The old fallback here was 180000L, which
        // announced an unknown-length track to the room as exactly three minutes. Guests clamp
        // incoming positions to the room's track duration, so on a long track with no metadata
        // that pinned every guest at 3:00 for the rest of it. Reporting 0 says "unknown", and the
        // clamps below now treat 0 as unbounded rather than clamping everything to zero.
        val live = player?.state?.value
            ?.takeIf { it.currentSong?.id == song.id }
            ?.duration
            ?.takeIf { it > 0 }
        return TrackInfo(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = live ?: song.knownDurationMs(),
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
     * Send track change to server (both host and guest).
     */
    private fun sendTrackChange(song: SongInfo) {

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
            // Runs until cancelled by stopHeartbeat()/cleanup(). Checking isInRoom as the loop
            // condition raced with the client setting roomState just after the role changed,
            // which killed the job on the first tick.
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isInRoom || !isHost) continue
                val p = player ?: continue
                val state = p.state.value
                if (state.isPlaying && state.currentSong != null) {
                    Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos ${state.position}")
                    client.sendPlaybackAction(PlaybackActions.PLAY, position = state.position)
                }
            }
        }
        Timber.tag(TAG).d("Host heartbeat started (${HEARTBEAT_INTERVAL_MS / 1000}s interval)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.tag(TAG).d("Host heartbeat stopped")
    }

    /**
     * Start periodic resync for guests on long tracks.
     * Sends REQUEST_SYNC every 60s when the current track is >10 minutes.
     */
    private fun startGuestResync() {
        guestResyncJob?.cancel()
        guestResyncJob = scope.launch {
            // Same race as the heartbeat: role flips to GUEST before roomState is populated.
            while (true) {
                delay(GUEST_RESYNC_INTERVAL_MS)
                if (!isInRoom || isHost) continue
                val p = player ?: continue
                val state = p.state.value
                // Prefer the live duration: it comes from VLC and is authoritative. The metadata
                // fallback exists only for the window before VLC has parsed the stream.
                //
                // This used to read `currentSong.durationMs` alone, which is 0 for every song
                // built by `toPlayerSongInfo` (search, home, radio, explore) because that path
                // fills `duration` in seconds instead. The threshold was therefore never crossed
                // and long-track resync — the one mechanism meant to keep exactly these tracks in
                // sync — silently never ran for most of what people play.
                val duration = state.duration.takeIf { it > 0 }
                    ?: state.currentSong?.knownDurationMs() ?: 0L
                if (duration > LONG_TRACK_THRESHOLD_MS && state.isPlaying) {
                    Timber.tag(TAG).d("Guest long-track resync: requesting sync (duration=${duration / 1000}s)")
                    client.requestSync()
                }
            }
        }
        Timber.tag(TAG).d("Guest periodic resync started (${GUEST_RESYNC_INTERVAL_MS / 1000}s interval)")
    }

    private fun stopGuestResync() {
        guestResyncJob?.cancel()
        guestResyncJob = null
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
