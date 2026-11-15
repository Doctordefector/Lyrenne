package com.lyrenne.desktop.gamemode

import com.lyrenne.desktop.Platform
import com.lyrenne.desktop.settings.PreferencesManager
import com.sun.jna.platform.unix.X11
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * System-wide hotkeys: they fire while a game, or any other app, has focus.
 *
 * Registration follows the Game Mode preferences: every change to them re-registers the whole set,
 * which also makes "unregister when a binding changes" and "unregister on quit" the same code path.
 * The OS refusing a combo is not an error here, it is the expected way to learn another app owns
 * it, and it lands in [status] for the Settings row to explain.
 */
object GlobalHotkeys {
    /** Whether this desktop can have global hotkeys at all, and if not, the one line saying why. */
    val unavailableReason: String? by lazy { detectUnavailable() }
    val isAvailable: Boolean get() = unavailableReason == null

    private val _status = MutableStateFlow<Map<HotkeyAction, HotkeyStatus>>(emptyMap())
    val status: StateFlow<Map<HotkeyAction, HotkeyStatus>> = _status.asStateFlow()

    /** Set when the native side failed to start, so Settings can say so instead of showing dead rows. */
    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private var backend: HotkeyBackend? = null
    private var onAction: (HotkeyAction) -> Unit = {}
    @Volatile private var paused = false
    @Volatile private var started = false

    fun start(onAction: (HotkeyAction) -> Unit) {
        if (started) return
        started = true
        this.onAction = onAction
        scope.launch {
            PreferencesManager.preferences
                .map { it.gameMode.hotkeysEnabled to it.gameMode.hotkeys }
                .distinctUntilChanged()
                .collect { reapply() }
        }
    }

    /**
     * Suspends every hotkey while Settings records a new combo. A registered hotkey swallows its
     * keys before any window sees them, so without this pressing the current combo again could
     * never be recorded, and recording would also play or pause the music.
     */
    fun pause(on: Boolean) {
        if (paused == on) return
        paused = on
        scope.launch { reapply() }
    }

    private fun reapply() {
        val prefs = PreferencesManager.preferences.value.gameMode
        val offAll = HotkeyAction.entries.associateWith { HotkeyStatus.Off as HotkeyStatus }
        if (!isAvailable || !prefs.hotkeysEnabled) {
            backend?.apply(emptyMap())
            _status.value = offAll
            return
        }
        val (toRegister, statuses) = HotkeyPlan.plan(prefs.hotkeys)
        if (paused) {
            backend?.apply(emptyMap())
            _status.value = statuses + toRegister.keys.associateWith { HotkeyStatus.Pending }
            return
        }
        val be = backend ?: try {
            createBackend { id -> HotkeyAction.byNativeId(id)?.let(::fire) }.also { backend = it }
        } catch (e: Throwable) {
            Timber.w("Global hotkeys could not start: ${e.message}")
            _startError.value = "Global hotkeys could not start on this system."
            _status.value = offAll
            return
        }
        val refused = try {
            be.apply(toRegister.entries.associate { it.key.nativeId to it.value })
        } catch (e: Throwable) {
            Timber.w("Registering hotkeys failed: ${e.message}")
            toRegister.keys.map { it.nativeId }.toSet()
        }
        _status.value = statuses + toRegister.keys.associateWith {
            if (it.nativeId in refused) HotkeyStatus.Taken else HotkeyStatus.Active
        }
        Timber.i("Global hotkeys: ${toRegister.size - refused.size} active, ${refused.size} taken by another app")
    }

    private fun fire(action: HotkeyAction) {
        // Off the native thread at once: the action may touch the database or the network.
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { onAction(action) }.onFailure { Timber.w("Hotkey ${action.id} failed: ${it.message}") }
        }
    }

    /** Unregisters everything and stops the native thread. Safe to call more than once. */
    fun release() {
        val be = backend ?: return
        backend = null
        runCatching { be.close() }.onFailure { Timber.w("Hotkey release failed: ${it.message}") }
    }

    private fun createBackend(onHotkey: (Int) -> Unit): HotkeyBackend = when {
        Platform.isWindows -> WindowsHotkeyBackend(onHotkey)
        Platform.isLinux -> X11HotkeyBackend(onHotkey)
        else -> error("no hotkey backend")
    }

    private fun detectUnavailable(): String? = detectUnavailable(
        isWindows = Platform.isWindows,
        isLinux = Platform.isLinux,
        env = System::getenv,
    )

    internal fun detectUnavailable(isWindows: Boolean, isLinux: Boolean, env: (String) -> String?): String? = when {
        isWindows -> null
        isLinux && (env("XDG_SESSION_TYPE").equals("wayland", true) || !env("WAYLAND_DISPLAY").isNullOrBlank()) ->
            "Wayland doesn't let apps catch keys while a game has focus. Bind \"playerctl play-pause\" " +
                "in your desktop's keyboard shortcuts instead."
        isLinux && env("DISPLAY").isNullOrBlank() -> "Global hotkeys need an X11 desktop."
        isLinux -> null
        else -> "Global hotkeys aren't available on this system yet."
    }
}

/** One platform's way of registering hotkeys. Every call blocks until the OS has answered. */
internal interface HotkeyBackend {
    /** Replaces every registration with [combos] (by native id) and returns the ids the OS refused. */
    fun apply(combos: Map<Int, KeyCombo>): Set<Int>
    fun close()
}

/**
 * RegisterHotKey on a thread of its own with its own message loop.
 *
 * Registering with no window ties each hotkey to the calling thread: WM_HOTKEY arrives in that
 * thread's queue, and only that thread may unregister it. So the thread is dedicated and never
 * blocks on anything but GetMessage, and every request is handed to it as a task and woken with a
 * posted message. Running this on the AWT thread would have worked until the first time someone
 * changed a binding from a coroutine.
 */
internal class WindowsHotkeyBackend(private val onHotkey: (Int) -> Unit) : HotkeyBackend {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var threadId = 0
    private val ready = CountDownLatch(1)
    private val registered = mutableSetOf<Int>() // only touched on the hotkey thread

    private val thread = Thread(::loop, "lyrenne-hotkeys").apply { isDaemon = true; start() }

    init {
        check(ready.await(5, TimeUnit.SECONDS) && threadId != 0) { "hotkey thread did not start" }
    }

    private fun loop() {
        val user32 = User32.INSTANCE
        val msg = WinUser.MSG()
        // A thread has no message queue until it first asks for a message. Without one,
        // PostThreadMessage to it fails, so create it before telling anyone the thread is ready.
        user32.PeekMessage(msg, null, 0, 0, PM_NOREMOVE)
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        ready.countDown()
        while (true) {
            val got = user32.GetMessage(msg, null, 0, 0)
            if (got == 0 || got == -1) break // WM_QUIT, or the queue is gone
            when (msg.message) {
                WM_HOTKEY -> onHotkey(msg.wParam.toInt())
                WM_RUN_TASKS -> drain()
            }
        }
        drain()
        unregisterAll()
    }

    private fun drain() {
        while (true) (tasks.poll() ?: break).run()
    }

    private fun unregisterAll() {
        registered.forEach { User32.INSTANCE.UnregisterHotKey(null, it) }
        registered.clear()
    }

    private fun <T> onThread(block: () -> T): T {
        val result = CompletableFuture<T>()
        tasks.add(Runnable { runCatching(block).fold(result::complete, result::completeExceptionally) })
        User32.INSTANCE.PostThreadMessage(threadId, WM_RUN_TASKS, WinDef.WPARAM(0), WinDef.LPARAM(0))
        return result.get(5, TimeUnit.SECONDS)
    }

    override fun apply(combos: Map<Int, KeyCombo>): Set<Int> = onThread {
        unregisterAll()
        val refused = mutableSetOf<Int>()
        for ((id, combo) in combos) {
            // MOD_NOREPEAT: holding the keys must not skip through ten songs.
            val ok = User32.INSTANCE.RegisterHotKey(null, id, combo.windowsModifiers or MOD_NOREPEAT, combo.key.vk)
            if (ok) registered += id else {
                refused += id
                Timber.i("Hotkey ${combo.format()} refused (error ${Kernel32.INSTANCE.GetLastError()})")
            }
        }
        refused
    }

    override fun close() {
        runCatching { onThread { unregisterAll() } }
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        thread.join(1_000)
    }

    private companion object {
        const val WM_HOTKEY = 0x0312
        const val WM_RUN_TASKS = 0x8000 + 0x4C // WM_APP + something of our own
        const val MOD_NOREPEAT = 0x4000
        const val PM_NOREMOVE = 0
    }
}

/**
 * XGrabKey on the root window, on a display connection of its own.
 *
 * Two X11 details shape this. A grab matches the modifier state exactly, so Caps Lock or Num Lock
 * being on would make every hotkey silently dead; each combo is therefore grabbed four times, once
 * per lock state. And a grab someone else holds fails asynchronously with BadAccess, whose default
 * handler exits the process. An error handler is installed only around the grab and its XSync, and
 * hands any error from another connection (AWT's) to the handler it replaced.
 *
 * Untested on real hardware at the time of writing (developed on Windows); everything is wrapped
 * so a failure here turns hotkeys off rather than taking the app down.
 */
internal class X11HotkeyBackend(private val onHotkey: (Int) -> Unit) : HotkeyBackend {
    private val x = X11.INSTANCE
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val ready = CompletableFuture<Unit>()
    @Volatile private var running = true
    private var display: X11.Display? = null
    private var root: X11.Window? = null
    private val grabs = mutableMapOf<Int, Pair<Int, Int>>() // id to (keycode, modifiers), thread-confined
    @Volatile private var errorSeen = false

    private val thread = Thread(::loop, "lyrenne-hotkeys").apply { isDaemon = true; start() }

    init {
        ready.get(5, TimeUnit.SECONDS)
    }

    private fun loop() {
        val d = try {
            x.XOpenDisplay(null) ?: error("XOpenDisplay failed")
        } catch (e: Throwable) {
            ready.completeExceptionally(e); return
        }
        display = d
        root = x.XDefaultRootWindow(d)
        ready.complete(Unit)
        val event = X11.XEvent()
        try {
            while (running) {
                while (true) (tasks.poll() ?: break).run()
                while (x.XPending(d) > 0) {
                    x.XNextEvent(d, event)
                    if (event.type == KEY_PRESS) {
                        event.setType(X11.XKeyEvent::class.java)
                        event.read()
                        val key = event.xkey
                        val state = key.state and RELEVANT_MODS
                        grabs.entries.firstOrNull { it.value.first == key.keycode && it.value.second == state }
                            ?.let { onHotkey(it.key) }
                    }
                }
                Thread.sleep(25)
            }
        } catch (e: Throwable) {
            Timber.w("X11 hotkey loop ended: ${e.message}")
        } finally {
            runCatching { ungrabAll(); x.XCloseDisplay(d) }
        }
    }

    private fun ungrabAll() {
        val d = display ?: return
        for ((code, mods) in grabs.values) for (lock in LOCK_VARIANTS) x.XUngrabKey(d, code, mods or lock, root)
        grabs.clear()
        x.XSync(d, false)
    }

    private val handler = object : X11.XErrorHandler {
        var previous: X11.XErrorHandler? = null
        override fun apply(d: X11.Display?, e: X11.XErrorEvent?): Int {
            if (d != null && d == display) { errorSeen = true; return 0 }
            return previous?.apply(d, e) ?: 0
        }
    }

    private fun <T> onThread(block: () -> T): T {
        val result = CompletableFuture<T>()
        tasks.add(Runnable { runCatching(block).fold(result::complete, result::completeExceptionally) })
        return result.get(5, TimeUnit.SECONDS)
    }

    override fun apply(combos: Map<Int, KeyCombo>): Set<Int> = onThread {
        val d = display!!
        ungrabAll()
        val refused = mutableSetOf<Int>()
        for ((id, combo) in combos) {
            val code = x.XKeysymToKeycode(d, X11.KeySym(combo.key.keysym)).toInt() and 0xFF
            if (code == 0) { refused += id; continue }
            val mods = combo.x11Modifiers
            errorSeen = false
            handler.previous = x.XSetErrorHandler(handler)
            try {
                for (lock in LOCK_VARIANTS) x.XGrabKey(d, code, mods or lock, root, 1, GRAB_MODE_ASYNC, GRAB_MODE_ASYNC)
                x.XSync(d, false)
            } finally {
                x.XSetErrorHandler(handler.previous)
            }
            if (errorSeen) {
                for (lock in LOCK_VARIANTS) x.XUngrabKey(d, code, mods or lock, root)
                refused += id
            } else {
                grabs[id] = code to mods
            }
        }
        refused
    }

    override fun close() {
        running = false
        thread.join(1_000)
    }

    internal companion object {
        const val KEY_PRESS = 2
        const val GRAB_MODE_ASYNC = 1
        /** Shift, Control, Mod1 (Alt), Mod4 (Super): the modifiers a combo is made of. */
        const val RELEVANT_MODS = 0x01 or 0x04 or 0x08 or 0x40
        /** No lock, Caps Lock, Num Lock (Mod2), both. */
        val LOCK_VARIANTS = intArrayOf(0, 0x02, 0x10, 0x12)
    }
}
