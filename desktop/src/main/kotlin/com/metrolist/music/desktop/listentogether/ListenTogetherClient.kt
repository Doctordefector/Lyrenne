/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.desktop.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Log entry for debugging
 */
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val details: String? = null
)

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    DEBUG
}

/**
 * Pending action to execute when connected
 */
sealed class PendingAction {
    data class CreateRoom(val username: String) : PendingAction()
    data class JoinRoom(val roomCode: String, val username: String) : PendingAction()
}

/**
 * WebSocket client for Listen Together feature.
 * Desktop singleton (replaces Android's Hilt-injected class).
 */
object ListenTogetherClient {
    private const val TAG = "ListenTogether"
    private const val DEFAULT_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
    private const val MAX_RECONNECT_ATTEMPTS = 15
    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 120000L
    private const val PING_INTERVAL_MS = 25000L
    private const val MAX_LOG_ENTRIES = 500
    private const val SESSION_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes
    private const val SESSION_FILE_NAME = "listen_together_session.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()

    private val _role = MutableStateFlow(RoomRole.NONE)
    val role: StateFlow<RoomRole> = _role.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _pendingJoinRequests = MutableStateFlow<List<JoinRequestPayload>>(emptyList())
    val pendingJoinRequests: StateFlow<List<JoinRequestPayload>> = _pendingJoinRequests.asStateFlow()

    private val _bufferingUsers = MutableStateFlow<List<String>>(emptyList())
    val bufferingUsers: StateFlow<List<String>> = _bufferingUsers.asStateFlow()

    private val _pendingSuggestions = MutableStateFlow<List<SuggestionReceivedPayload>>(emptyList())
    val pendingSuggestions: StateFlow<List<SuggestionReceivedPayload>> = _pendingSuggestions.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Event flow
    private val _events = MutableSharedFlow<ListenTogetherEvent>()
    val events: SharedFlow<ListenTogetherEvent> = _events.asSharedFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Message codec - uses Protobuf with compression enabled
    private val codec = MessageCodec(true)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectAttempts = 0

    // Session info for reconnection
    private var sessionToken: String? = null
    private var storedUsername: String? = null
    private var storedRoomCode: String? = null
    private var wasHost: Boolean = false
    private var sessionStartTime: Long = 0

    // Pending actions to execute when connected
    private var pendingAction: PendingAction? = null

    init {
        // Load persisted session info asynchronously
        scope.launch {
            loadPersistedSession()
        }
    }

    // --- App data directory ---

    private fun getAppDataDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "Metrolist")
            }
            os.contains("mac") -> {
                File(System.getProperty("user.home"), "Library/Application Support/Metrolist")
            }
            else -> {
                File(System.getProperty("user.home"), ".config/metrolist")
            }
        }
    }

    private fun getSessionFile(): File {
        val dir = getAppDataDir()
        dir.mkdirs()
        return File(dir, SESSION_FILE_NAME)
    }

    // --- Session persistence ---

    @kotlinx.serialization.Serializable
    private data class PersistedSession(
        val sessionToken: String,
        val roomCode: String,
        val userId: String,
        val isHost: Boolean,
        val timestamp: Long
    )

    private fun loadPersistedSession() {
        try {
            val file = getSessionFile()
            if (!file.exists()) return

            val content = file.readText()
            if (content.isBlank()) return

            val session = json.decodeFromString<PersistedSession>(content)

            // Check if session is still valid (within grace period)
            if (session.sessionToken.isNotEmpty() && session.roomCode.isNotEmpty() &&
                (System.currentTimeMillis() - session.timestamp < SESSION_GRACE_PERIOD_MS)) {
                sessionToken = session.sessionToken
                storedRoomCode = session.roomCode
                _userId.value = session.userId.ifEmpty { null }
                wasHost = session.isHost
                sessionStartTime = session.timestamp
                log(LogLevel.INFO, "Loaded persisted session", "Room: ${session.roomCode}, Host: ${session.isHost}")
            } else if (session.sessionToken.isNotEmpty()) {
                log(LogLevel.WARNING, "Session expired", "Age: ${System.currentTimeMillis() - session.timestamp}ms")
                clearPersistedSession()
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to load persisted session", e.message)
        }
    }

    private fun savePersistedSession() {
        try {
            if (sessionToken == null) return
            val session = PersistedSession(
                sessionToken = sessionToken!!,
                roomCode = storedRoomCode ?: "",
                userId = _userId.value ?: "",
                isHost = wasHost,
                timestamp = System.currentTimeMillis()
            )
            val content = json.encodeToString(PersistedSession.serializer(), session)
            getSessionFile().writeText(content)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to save persisted session", e.message)
        }
    }

    private fun clearPersistedSession() {
        try {
            val file = getSessionFile()
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to clear persisted session", e.message)
        }
    }

    // --- Logging ---

    private fun log(level: LogLevel, message: String, details: String? = null) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val entry = LogEntry(timestamp, level, message, details)

        _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)

        when (level) {
            LogLevel.ERROR -> Timber.tag(TAG).e("$message ${details ?: ""}")
            LogLevel.WARNING -> Timber.tag(TAG).w("$message ${details ?: ""}")
            LogLevel.DEBUG -> Timber.tag(TAG).d("$message ${details ?: ""}")
            LogLevel.INFO -> Timber.tag(TAG).i("$message ${details ?: ""}")
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // --- Backoff ---

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = INITIAL_RECONNECT_DELAY_MS * (2 shl (minOf(attempt - 1, 4)))
        val cappedDelay = minOf(exponentialDelay, MAX_RECONNECT_DELAY_MS)
        // Add 0-20% jitter to prevent thundering herd
        val jitter = (cappedDelay * 0.2 * Math.random()).toLong()
        return cappedDelay + jitter
    }

    // --- Connection ---

    /**
     * Connect to the Listen Together server
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            log(LogLevel.WARNING, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        log(LogLevel.INFO, "Connecting to server", DEFAULT_SERVER_URL)

        val request = Request.Builder()
            .url(DEFAULT_SERVER_URL)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log(LogLevel.INFO, "Connected to server")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                startPingJob()

                // Try to reconnect to previous session if we have a valid token
                if (sessionToken != null && storedRoomCode != null) {
                    log(LogLevel.INFO, "Attempting to reconnect to previous session", "Room: $storedRoomCode")
                    sendMessage(MessageTypes.RECONNECT, ReconnectPayload(sessionToken!!))
                } else {
                    // Execute any pending action
                    executePendingAction()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Handle binary protobuf messages
                handleMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log(LogLevel.INFO, "Server closing connection", "Code: $code, Reason: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log(LogLevel.INFO, "Connection closed", "Code: $code, Reason: $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log(LogLevel.ERROR, "Connection failure", t.message)
                handleConnectionFailure(t)
            }
        })
    }

    private fun executePendingAction() {
        val action = pendingAction ?: return
        pendingAction = null

        when (action) {
            is PendingAction.CreateRoom -> {
                log(LogLevel.INFO, "Executing pending create room", action.username)
                sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(action.username))
            }
            is PendingAction.JoinRoom -> {
                log(LogLevel.INFO, "Executing pending join room", "${action.roomCode} as ${action.username}")
                sendMessage(MessageTypes.JOIN_ROOM, JoinRoomPayload(action.roomCode.uppercase(), action.username))
            }
        }
    }

    /**
     * Disconnect from the server
     */
    fun disconnect() {
        log(LogLevel.INFO, "Disconnecting from server")
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED

        // Clear session and state on explicit disconnect
        sessionToken = null
        storedRoomCode = null
        storedUsername = null
        pendingAction = null
        _roomState.value = null
        _role.value = RoomRole.NONE
        _userId.value = null
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()

        clearPersistedSession()
        reconnectAttempts = 0

        scope.launch { _events.emit(ListenTogetherEvent.Disconnected) }
    }

    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                sendMessageNoPayload(MessageTypes.PING)
            }
        }
    }

    private fun handleDisconnect() {
        pingJob?.cancel()
        pingJob = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()

        // If we have a session, try to reconnect
        if (sessionToken != null && _roomState.value != null) {
            log(LogLevel.INFO, "Connection lost, will attempt to reconnect")
            handleConnectionFailure(Exception("Connection lost"))
        } else {
            scope.launch { _events.emit(ListenTogetherEvent.Disconnected) }
        }
    }

    private fun handleConnectionFailure(t: Throwable) {
        pingJob?.cancel()
        pingJob = null

        val shouldReconnect = sessionToken != null || _roomState.value != null || pendingAction != null

        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && shouldReconnect) {
            reconnectAttempts++
            _connectionState.value = ConnectionState.RECONNECTING

            val delayMs = calculateBackoffDelay(reconnectAttempts)
            val delaySeconds = delayMs / 1000

            log(LogLevel.INFO, "Attempting reconnect",
                "Attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS, waiting ${delaySeconds}s, reason: ${t.message}")

            scope.launch {
                _events.emit(ListenTogetherEvent.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS))
                delay(delayMs)

                if (_connectionState.value == ConnectionState.RECONNECTING || _connectionState.value == ConnectionState.DISCONNECTED) {
                    log(LogLevel.INFO, "Reconnecting after backoff", "Delay was ${delaySeconds}s")
                    connect()
                }
            }
        } else {
            _connectionState.value = ConnectionState.ERROR

            if (sessionToken != null) {
                log(LogLevel.ERROR, "Reconnection failed",
                    "Max attempts reached, but session preserved for manual reconnect")
                scope.launch {
                    _events.emit(ListenTogetherEvent.ConnectionError(
                        "Connection failed after $MAX_RECONNECT_ATTEMPTS attempts. ${t.message ?: "Unknown error"}"
                    ))
                }
            } else {
                sessionToken = null
                storedRoomCode = null
                storedUsername = null
                _roomState.value = null
                _role.value = RoomRole.NONE
                clearPersistedSession()

                scope.launch {
                    _events.emit(ListenTogetherEvent.ConnectionError(t.message ?: "Unknown error"))
                }
            }
        }
    }

    // --- Message handling ---

    private fun handleMessage(data: ByteArray) {
        log(LogLevel.DEBUG, "Received message", "${data.size} bytes")

        try {
            val (msgType, payloadBytes) = codec.decode(data)

            when (msgType) {
                MessageTypes.ROOM_CREATED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? RoomCreatedPayload ?: return
                    _userId.value = payload.userId
                    _role.value = RoomRole.HOST
                    sessionToken = payload.sessionToken
                    storedRoomCode = payload.roomCode
                    wasHost = true
                    sessionStartTime = System.currentTimeMillis()

                    _roomState.value = RoomState(
                        roomCode = payload.roomCode,
                        hostId = payload.userId,
                        users = listOf(UserInfo(payload.userId, storedUsername ?: "", true)),
                        isPlaying = false,
                        position = 0,
                        lastUpdate = System.currentTimeMillis(),
                        volume = 1f
                    )

                    savePersistedSession()
                    log(LogLevel.INFO, "Room created", "Code: ${payload.roomCode}")
                    scope.launch { _events.emit(ListenTogetherEvent.RoomCreated(payload.roomCode, payload.userId)) }
                }

                MessageTypes.JOIN_REQUEST -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? JoinRequestPayload ?: return
                    _pendingJoinRequests.value += payload
                    log(LogLevel.INFO, "Join request received", "User: ${payload.username}")
                    scope.launch { _events.emit(ListenTogetherEvent.JoinRequestReceived(payload.userId, payload.username)) }
                }

                MessageTypes.JOIN_APPROVED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? JoinApprovedPayload ?: return
                    _userId.value = payload.userId
                    _role.value = RoomRole.GUEST
                    sessionToken = payload.sessionToken
                    storedRoomCode = payload.roomCode
                    wasHost = false
                    sessionStartTime = System.currentTimeMillis()

                    _roomState.value = payload.state

                    savePersistedSession()
                    log(LogLevel.INFO, "Joined room", "Code: ${payload.roomCode}")
                    scope.launch { _events.emit(ListenTogetherEvent.JoinApproved(payload.roomCode, payload.userId, payload.state)) }
                }

                MessageTypes.JOIN_REJECTED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? JoinRejectedPayload ?: return
                    log(LogLevel.WARNING, "Join rejected", payload.reason)
                    scope.launch { _events.emit(ListenTogetherEvent.JoinRejected(payload.reason)) }
                }

                MessageTypes.USER_JOINED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? UserJoinedPayload ?: return
                    _roomState.value = _roomState.value?.copy(
                        users = _roomState.value!!.users + UserInfo(payload.userId, payload.username, false)
                    )
                    _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != payload.userId }
                    log(LogLevel.INFO, "User joined", payload.username)
                    scope.launch { _events.emit(ListenTogetherEvent.UserJoined(payload.userId, payload.username)) }
                }

                MessageTypes.USER_LEFT -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? UserLeftPayload ?: return
                    _roomState.value = _roomState.value?.copy(
                        users = _roomState.value!!.users.filter { it.userId != payload.userId }
                    )
                    log(LogLevel.INFO, "User left", payload.username)
                    scope.launch { _events.emit(ListenTogetherEvent.UserLeft(payload.userId, payload.username)) }
                }

                MessageTypes.HOST_CHANGED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? HostChangedPayload ?: return
                    _roomState.value = _roomState.value?.copy(
                        hostId = payload.newHostId,
                        users = _roomState.value!!.users.map {
                            it.copy(isHost = it.userId == payload.newHostId)
                        }
                    )
                    if (payload.newHostId == _userId.value) {
                        _role.value = RoomRole.HOST
                    } else if (_role.value == RoomRole.HOST) {
                        _role.value = RoomRole.GUEST
                    }
                    log(LogLevel.INFO, "Host changed", "New host: ${payload.newHostName}")
                    scope.launch { _events.emit(ListenTogetherEvent.HostChanged(payload.newHostId, payload.newHostName)) }
                }

                MessageTypes.KICKED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? KickedPayload ?: return
                    log(LogLevel.WARNING, "Kicked from room", payload.reason)
                    sessionToken = null
                    _roomState.value = null
                    _role.value = RoomRole.NONE
                    clearPersistedSession()
                    scope.launch { _events.emit(ListenTogetherEvent.Kicked(payload.reason)) }
                }

                MessageTypes.SYNC_PLAYBACK -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? PlaybackActionPayload ?: return
                    log(LogLevel.DEBUG, "Playback sync", "Action: ${payload.action}")

                    // Update room state based on action
                    when (payload.action) {
                        PlaybackActions.PLAY -> {
                            _roomState.value = _roomState.value?.copy(
                                isPlaying = true,
                                position = payload.position ?: _roomState.value!!.position
                            )
                        }
                        PlaybackActions.PAUSE -> {
                            _roomState.value = _roomState.value?.copy(
                                isPlaying = false,
                                position = payload.position ?: _roomState.value!!.position
                            )
                        }
                        PlaybackActions.SEEK -> {
                            _roomState.value = _roomState.value?.copy(
                                position = payload.position ?: _roomState.value!!.position
                            )
                        }
                        PlaybackActions.CHANGE_TRACK -> {
                            _roomState.value = _roomState.value?.copy(
                                currentTrack = payload.trackInfo,
                                isPlaying = false,
                                position = 0
                            )
                        }
                        PlaybackActions.QUEUE_ADD -> {
                            val ti = payload.trackInfo
                            if (ti != null) {
                                val currentQueue = _roomState.value?.queue ?: emptyList()
                                _roomState.value = _roomState.value?.copy(
                                    queue = if (payload.insertNext == true) listOf(ti) + currentQueue else currentQueue + ti
                                )
                            }
                        }
                        PlaybackActions.QUEUE_REMOVE -> {
                            val id = payload.trackId
                            if (!id.isNullOrEmpty()) {
                                val currentQueue = _roomState.value?.queue ?: emptyList()
                                _roomState.value = _roomState.value?.copy(
                                    queue = currentQueue.filter { it.id != id }
                                )
                            }
                        }
                        PlaybackActions.QUEUE_CLEAR -> {
                            _roomState.value = _roomState.value?.copy(queue = emptyList())
                        }
                        PlaybackActions.SET_VOLUME -> {
                            val vol = payload.volume
                            if (vol != null) {
                                _roomState.value = _roomState.value?.copy(volume = vol.coerceIn(0f, 1f))
                            }
                        }
                    }

                    scope.launch { _events.emit(ListenTogetherEvent.PlaybackSync(payload)) }
                }

                MessageTypes.BUFFER_WAIT -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? BufferWaitPayload ?: return
                    _bufferingUsers.value = payload.waitingFor
                    log(LogLevel.DEBUG, "Waiting for buffering", "Users: ${payload.waitingFor.size}")
                    scope.launch { _events.emit(ListenTogetherEvent.BufferWait(payload.trackId, payload.waitingFor)) }
                }

                MessageTypes.BUFFER_COMPLETE -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? BufferCompletePayload ?: return
                    _bufferingUsers.value = emptyList()
                    log(LogLevel.INFO, "All users buffered", "Track: ${payload.trackId}")
                    scope.launch { _events.emit(ListenTogetherEvent.BufferComplete(payload.trackId)) }
                }

                MessageTypes.SYNC_STATE -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? SyncStatePayload ?: return
                    log(LogLevel.INFO, "Sync state received", "Playing: ${payload.isPlaying}, Position: ${payload.position}")
                    scope.launch { _events.emit(ListenTogetherEvent.SyncStateReceived(payload)) }
                }

                MessageTypes.SUGGESTION_RECEIVED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionReceivedPayload ?: return
                    if (_role.value == RoomRole.HOST) {
                        log(LogLevel.INFO, "Suggestion received", "${payload.fromUsername}: ${payload.trackInfo.title}")
                        _pendingSuggestions.value += payload
                    }
                    scope.launch { _events.emit(ListenTogetherEvent.SuggestionReceived(payload)) }
                }

                MessageTypes.SUGGESTION_APPROVED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionApprovedPayload ?: return
                    log(LogLevel.INFO, "Suggestion approved", payload.trackInfo.title)
                    scope.launch { _events.emit(ListenTogetherEvent.SuggestionApproved(payload)) }
                }

                MessageTypes.SUGGESTION_REJECTED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionRejectedPayload ?: return
                    log(LogLevel.WARNING, "Suggestion rejected", payload.reason ?: "")
                    scope.launch { _events.emit(ListenTogetherEvent.SuggestionRejected(payload)) }
                }

                MessageTypes.ERROR -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? ErrorPayload ?: return
                    log(LogLevel.ERROR, "Server error", "${payload.code}: ${payload.message}")

                    when (payload.code) {
                        "session_not_found" -> {
                            if (storedRoomCode != null && storedUsername != null && !wasHost) {
                                log(LogLevel.WARNING, "Session expired on server",
                                    "Attempting automatic rejoin to room: $storedRoomCode")
                                scope.launch {
                                    delay(500)
                                    joinRoom(storedRoomCode!!, storedUsername!!)
                                }
                            } else {
                                clearPersistedSession()
                                sessionToken = null
                            }
                        }
                    }

                    scope.launch { _events.emit(ListenTogetherEvent.ServerError(payload.code, payload.message)) }
                }

                MessageTypes.PONG -> {
                    log(LogLevel.DEBUG, "Pong received")
                }

                MessageTypes.RECONNECTED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? ReconnectedPayload ?: return
                    _userId.value = payload.userId
                    _role.value = if (payload.isHost) RoomRole.HOST else RoomRole.GUEST
                    _roomState.value = payload.state

                    wasHost = payload.isHost
                    sessionStartTime = System.currentTimeMillis()
                    savePersistedSession()

                    reconnectAttempts = 0

                    log(LogLevel.INFO, "Successfully reconnected to room",
                        "Code: ${payload.roomCode}, isHost: ${payload.isHost}")
                    scope.launch { _events.emit(ListenTogetherEvent.Reconnected(payload.roomCode, payload.userId, payload.state, payload.isHost)) }
                }

                MessageTypes.USER_RECONNECTED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? UserReconnectedPayload ?: return
                    _roomState.value = _roomState.value?.copy(
                        users = _roomState.value!!.users.map { user ->
                            if (user.userId == payload.userId) user.copy(isConnected = true) else user
                        }
                    )
                    log(LogLevel.INFO, "User reconnected", payload.username)
                    scope.launch { _events.emit(ListenTogetherEvent.UserReconnected(payload.userId, payload.username)) }
                }

                MessageTypes.USER_DISCONNECTED -> {
                    val payload = codec.decodePayload(msgType, payloadBytes) as? UserDisconnectedPayload ?: return
                    _roomState.value = _roomState.value?.copy(
                        users = _roomState.value!!.users.map { user ->
                            if (user.userId == payload.userId) user.copy(isConnected = false) else user
                        }
                    )
                    log(LogLevel.INFO, "User temporarily disconnected", payload.username)
                    scope.launch { _events.emit(ListenTogetherEvent.UserDisconnected(payload.userId, payload.username)) }
                }

                else -> {
                    log(LogLevel.WARNING, "Unknown message type", msgType)
                }
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error parsing message", e.message)
        }
    }

    // --- Sending ---

    private fun <T> sendMessage(type: String, payload: T?) {
        try {
            val data = codec.encode(type, payload)
            log(LogLevel.DEBUG, "Sending message", "$type (protobuf)")

            val success = webSocket?.send(okio.ByteString.of(*data)) ?: false
            if (!success) {
                log(LogLevel.ERROR, "Failed to send message", type)
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error encoding message", "$type: ${e.message}")
        }
    }

    private fun sendMessageNoPayload(type: String) {
        sendMessage<Unit>(type, null)
    }

    // --- Public API ---

    /**
     * Create a new listening room.
     * If not connected, will queue the action and connect first.
     */
    fun createRoom(username: String) {
        // Clear any existing session
        clearPersistedSession()
        sessionToken = null
        storedRoomCode = null
        wasHost = false

        storedUsername = username

        if (_connectionState.value == ConnectionState.CONNECTED) {
            sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(username))
        } else {
            log(LogLevel.INFO, "Not connected, queueing create room action")
            pendingAction = PendingAction.CreateRoom(username)
            if (_connectionState.value == ConnectionState.DISCONNECTED ||
                _connectionState.value == ConnectionState.ERROR) {
                connect()
            }
        }
    }

    /**
     * Join an existing room.
     * If not connected, will queue the action and connect first.
     */
    fun joinRoom(roomCode: String, username: String) {
        clearPersistedSession()
        sessionToken = null
        storedRoomCode = null
        wasHost = false

        storedUsername = username

        if (_connectionState.value == ConnectionState.CONNECTED) {
            sendMessage(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode.uppercase(), username))
        } else {
            log(LogLevel.INFO, "Not connected, queueing join room action")
            pendingAction = PendingAction.JoinRoom(roomCode, username)
            if (_connectionState.value == ConnectionState.DISCONNECTED ||
                _connectionState.value == ConnectionState.ERROR) {
                connect()
            }
        }
    }

    /**
     * Leave the current room
     */
    fun leaveRoom() {
        sendMessageNoPayload(MessageTypes.LEAVE_ROOM)

        sessionToken = null
        storedRoomCode = null
        storedUsername = null
        pendingAction = null
        _roomState.value = null
        _role.value = RoomRole.NONE
        _userId.value = null
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()

        clearPersistedSession()
    }

    /**
     * Approve a join request (host only)
     */
    fun approveJoin(userId: String) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot approve join", "Not host")
            return
        }
        sendMessage(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))
    }

    /**
     * Reject a join request (host only)
     */
    fun rejectJoin(userId: String, reason: String? = null) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot reject join", "Not host")
            return
        }
        sendMessage(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, reason))
        _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != userId }
    }

    /**
     * Kick a user from the room (host only)
     */
    fun kickUser(userId: String, reason: String? = null) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot kick user", "Not host")
            return
        }
        sendMessage(MessageTypes.KICK_USER, KickUserPayload(userId, reason))
    }

    /**
     * Transfer host role to another user (host only)
     */
    fun transferHost(newHostId: String) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot transfer host", "Not host")
            return
        }
        sendMessage(MessageTypes.TRANSFER_HOST, TransferHostPayload(newHostId))
    }

    /**
     * Send a playback action (host or guest — server decides permissions)
     */
    fun sendPlaybackAction(
        action: String,
        trackId: String? = null,
        position: Long? = null,
        trackInfo: TrackInfo? = null,
        insertNext: Boolean? = null,
        queue: List<TrackInfo>? = null,
        queueTitle: String? = null,
        volume: Float? = null
    ) {
        if (!isInRoom) {
            log(LogLevel.WARNING, "Cannot send playback action", "Not in room")
            return
        }
        sendMessage(
            MessageTypes.PLAYBACK_ACTION,
            PlaybackActionPayload(action, trackId, position, trackInfo, insertNext, queue, queueTitle, volume)
        )
    }

    /**
     * Signal that buffering is complete for the current track
     */
    fun sendBufferReady(trackId: String) {
        sendMessage(MessageTypes.BUFFER_READY, BufferReadyPayload(trackId))
    }

    /**
     * Suggest a track to the host (guest only)
     */
    fun suggestTrack(trackInfo: TrackInfo) {
        if (!isInRoom) {
            log(LogLevel.ERROR, "Cannot suggest track", "Not in room")
            return
        }
        if (_role.value == RoomRole.HOST) {
            log(LogLevel.WARNING, "Host should not suggest tracks")
            return
        }
        sendMessage(MessageTypes.SUGGEST_TRACK, SuggestTrackPayload(trackInfo))
    }

    /**
     * Approve a suggestion (host only)
     */
    fun approveSuggestion(suggestionId: String) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot approve suggestion", "Not host")
            return
        }
        sendMessage(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(suggestionId))
        _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }
    }

    /**
     * Reject a suggestion (host only)
     */
    fun rejectSuggestion(suggestionId: String, reason: String? = null) {
        if (_role.value != RoomRole.HOST) {
            log(LogLevel.ERROR, "Cannot reject suggestion", "Not host")
            return
        }
        sendMessage(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId, reason))
        _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }
    }

    /**
     * Request current playback state from server (for guest re-sync)
     */
    fun requestSync() {
        if (_roomState.value == null) {
            log(LogLevel.ERROR, "Cannot request sync", "Not in room")
            return
        }
        log(LogLevel.INFO, "Requesting sync state from server")
        sendMessageNoPayload(MessageTypes.REQUEST_SYNC)
    }

    /**
     * Force reconnection to server (useful for manual recovery)
     */
    fun forceReconnect() {
        log(LogLevel.INFO, "Forcing reconnection to server")
        reconnectAttempts = 0

        if (webSocket != null) {
            try {
                webSocket?.close(1000, "Forcing reconnection")
            } catch (e: Exception) {
                log(LogLevel.DEBUG, "Error closing WebSocket", e.message)
            }
            webSocket = null
        }

        _connectionState.value = ConnectionState.DISCONNECTED

        scope.launch {
            delay(500)
            connect()
        }
    }

    /**
     * Check if currently in a room
     */
    val isInRoom: Boolean
        get() = _roomState.value != null

    /**
     * Check if current user is host
     */
    val isHost: Boolean
        get() = _role.value == RoomRole.HOST

    /**
     * Check if there's a persisted session available for recovery
     */
    val hasPersistedSession: Boolean
        get() = sessionToken != null && storedRoomCode != null

    /**
     * Get the persisted room code if available
     */
    fun getPersistedRoomCode(): String? = storedRoomCode

    /**
     * Get current session age in milliseconds
     */
    fun getSessionAge(): Long = if (sessionStartTime > 0) {
        System.currentTimeMillis() - sessionStartTime
    } else {
        0L
    }
}
