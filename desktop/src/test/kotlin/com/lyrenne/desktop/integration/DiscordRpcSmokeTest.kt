package com.lyrenne.desktop.integration

import com.lyrenne.desktop.playback.PlaybackState
import com.lyrenne.desktop.playback.SongInfo
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real IPC code against a fake Discord: a named pipe served through kernel32, under a
 * name of its own so the Discord on the machine is never touched. Windows only, like the pipe.
 *
 * Each test pins one way the presence used to go missing for a whole track.
 */
class DiscordRpcSmokeTest {

    private val pipeName = "lyrenne-test-ipc-${System.nanoTime()}"
    private val state = MutableStateFlow(PlaybackState())
    private var discord: FakeDiscord? = null

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("win"))
        DiscordRPC.windowsPipePathFor = { i -> "\\\\.\\pipe\\$pipeName-$i" }
        DiscordRPC.retryFirstMs = 300
        DiscordRPC.retryMaxMs = 1_000
    }

    @After
    fun tearDown() = runBlocking {
        DiscordRPC.stopPresenceUpdates()
        discord?.stop()
        DiscordRPC.retryFirstMs = 5_000
        DiscordRPC.retryMaxMs = 60_000
    }

    private fun playing(title: String, positionMs: Long = 0L) = PlaybackState(
        isPlaying = true,
        currentSong = SongInfo(id = "id-$title", title = title, artist = "Artist", thumbnailUrl = null),
        position = positionMs,
        duration = 180_000
    )

    private fun paused(title: String) = playing(title).copy(isPlaying = false)

    @Test
    fun `a song change queued behind another send survives the next position tick`() = runBlocking {
        // Discord takes its time over each update, so the paused send below holds the lock a while
        discord = FakeDiscord(pipeName) { _ -> 1_500 }.also { it.start() }
        state.value = paused("A")
        DiscordRPC.startPresenceUpdates(state)
        assertTrue(discord!!.awaitEvent("activity#1:A"))

        // B starts while A's paused send is still waiting for its reply, and keeps ticking
        val started = System.currentTimeMillis()
        state.value = playing("B")
        repeat(30) {
            Thread.sleep(100)
            state.value = playing("B", positionMs = System.currentTimeMillis() - started)
        }

        assertTrue("B was not lost: ${discord!!.events()}", "activity#1:B" in discord!!.events())
    }

    /**
     * The send now suspends while it waits for Discord's reply (see DiscordRPC.awaitReply), so the
     * position tick that follows can reach it mid-wait. Cancelling it there would drop the
     * connection, and with it the presence.
     */
    @Test
    fun `a send outlives the position tick that follows it`() = runBlocking {
        // Slower than the tick interval, so the next tick arrives while the reply is pending
        discord = FakeDiscord(pipeName) { _ -> 400 }.also { it.start() }
        val started = System.currentTimeMillis()
        state.value = playing("A")
        DiscordRPC.startPresenceUpdates(state)

        repeat(20) {
            Thread.sleep(100)
            state.value = playing("A", positionMs = System.currentTimeMillis() - started)
        }

        val events = discord!!.events()
        assertEquals("one connection, kept open: $events", listOf("connect#1", "activity#1:A"), events)
    }

    @Test
    fun `a Discord that stops answering does not hold up later updates`() = runBlocking {
        // The first connection answers the handshake, then never answers an update
        discord = FakeDiscord(pipeName) { connection -> if (connection == 1) NEVER else 0 }
            .also { it.start() }
        state.value = playing("A")
        DiscordRPC.startPresenceUpdates(state)
        assertTrue(discord!!.awaitEvent("activity#1:A"))

        state.value = playing("B")
        assertTrue(
            "B reached Discord on a new connection: ${discord!!.events()}",
            discord!!.awaitEvent("activity#2:B", timeoutMs = 8_000)
        )
    }

    @Test
    fun `an update sent before Discord opened arrives once it does`() = runBlocking {
        state.value = playing("A")
        DiscordRPC.startPresenceUpdates(state)
        Thread.sleep(500) // The send finds no pipe

        discord = FakeDiscord(pipeName) { _ -> 0 }.also { it.start() }
        assertTrue(
            "A was retried: ${discord!!.events()}",
            discord!!.awaitEvent("activity#1:A", timeoutMs = 5_000)
        )
    }

    private companion object {
        const val NEVER = -1L
    }

    /**
     * A Discord IPC server on `\\.\pipe\<name>-0`. [replyDelayMs] says, per connection numbered
     * from 1, how long to sit on each update before answering it, or [NEVER].
     */
    private class FakeDiscord(name: String, private val replyDelayMs: (connection: Int) -> Long) {
        private val path = "\\\\.\\pipe\\$name-0"
        private val log = Collections.synchronizedList(mutableListOf<String>())
        private val handles = Collections.synchronizedList(mutableListOf<HANDLE>())
        @Volatile private var stopped = false

        fun events(): List<String> = synchronized(log) { log.toList() }

        fun awaitEvent(event: String, timeoutMs: Long = 3_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (event in events()) return true
                Thread.sleep(25)
            }
            return false
        }

        /** Returns once the pipe exists, so a client connecting straight after finds it. */
        fun start() {
            val listening = CountDownLatch(1)
            daemon {
                var connection = 0
                while (!stopped) {
                    val pipe = Kernel32.INSTANCE.CreateNamedPipe(
                        path, PIPE_ACCESS_DUPLEX, 0, PIPE_UNLIMITED_INSTANCES, 65536, 65536, 0, null
                    )
                    if (pipe == WinBase.INVALID_HANDLE_VALUE) return@daemon
                    handles += pipe
                    listening.countDown()
                    // False with ERROR_PIPE_CONNECTED just means the client got there first
                    if (!Kernel32.INSTANCE.ConnectNamedPipe(pipe, null) &&
                        Kernel32.INSTANCE.GetLastError() != ERROR_PIPE_CONNECTED
                    ) continue
                    if (stopped) return@daemon
                    val n = ++connection
                    daemon { serve(pipe, n) }
                }
            }
            check(listening.await(5, TimeUnit.SECONDS)) { "fake Discord pipe was not created" }
        }

        /** Handles are closed off-thread: CloseHandle can block behind a read pending on it. */
        fun stop() {
            stopped = true
            val all = synchronized(handles) { handles.toList() }
            all.forEach { h -> daemon { Kernel32.INSTANCE.CloseHandle(h) } }
        }

        /** Logs the connection's end too: the client closing it is what clears a real presence. */
        private fun serve(pipe: HANDLE, n: Int) {
            try {
                converse(pipe, n)
            } finally {
                if (!stopped) log += "closed#$n"
            }
        }

        private fun converse(pipe: HANDLE, n: Int) {
            while (true) {
                val header = read(pipe, 8) ?: return
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val opcode = buf.getInt()
                val payload = String(read(pipe, buf.getInt()) ?: return, Charsets.UTF_8)
                when (opcode) {
                    0 -> {
                        log += "connect#$n"
                        write(pipe, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
                    }
                    1 -> {
                        val title = Regex(""""details":"([^"]*)"""").find(payload)?.groupValues?.get(1)
                        log += if (title != null) "activity#$n:$title" else "clear#$n"
                        val delay = replyDelayMs(n)
                        if (delay == NEVER) continue
                        Thread.sleep(delay)
                        write(pipe, """{"cmd":"SET_ACTIVITY","data":{}}""")
                    }
                    else -> return
                }
            }
        }

        private fun read(pipe: HANDLE, size: Int): ByteArray? {
            val out = ByteArray(size)
            var got = 0
            while (got < size) {
                val chunk = ByteArray(size - got)
                val n = IntByReference()
                if (!Kernel32.INSTANCE.ReadFile(pipe, chunk, chunk.size, n, null) || n.value == 0) {
                    return null
                }
                System.arraycopy(chunk, 0, out, got, n.value)
                got += n.value
            }
            return out
        }

        private fun write(pipe: HANDLE, json: String) {
            val payload = json.toByteArray(Charsets.UTF_8)
            val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).putInt(payload.size).put(payload).array()
            Kernel32.INSTANCE.WriteFile(pipe, frame, frame.size, IntByReference(), null)
        }

        private fun daemon(block: () -> Unit) =
            Thread(block).apply { isDaemon = true }.start()

        private companion object {
            const val PIPE_ACCESS_DUPLEX = 3
            const val PIPE_UNLIMITED_INSTANCES = 255
            const val ERROR_PIPE_CONNECTED = 535
        }
    }
}
