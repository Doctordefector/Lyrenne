package com.lyrenne.desktop.download

import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Keeps the machine awake while a long transfer is running.
 *
 * A 700 track download outlives any normal idle timeout, and a laptop that suspends partway
 * through wakes up to a dead socket. Downloads resume now rather than being lost, but resuming
 * a queue the user thought had finished overnight is still the wrong outcome.
 *
 * ### Why this pings instead of holding a lock
 *
 * The obvious call is `SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)` to assert a
 * standing "do not sleep" state, then `ES_CONTINUOUS` alone to release it. Two problems with that
 * here, and the second is the serious one:
 *
 * 1. **The state is per-thread and dies with the thread.** Asserting it from a coroutine means
 *    asserting it on whichever `Dispatchers.IO` thread happened to run that resumption, and those
 *    are pooled and reclaimed after an idle period. The lock would silently evaporate mid-download.
 *    Holding it correctly needs a dedicated thread that outlives the transfer.
 * 2. **A standing lock leaks on a crash.** If the process dies between assert and release, the
 *    machine never sleeps again until the user reboots or notices. That is a far worse bug than
 *    the one being fixed, and it is invisible: nothing in the UI would say why.
 *
 * Passing `ES_SYSTEM_REQUIRED` *without* `ES_CONTINUOUS` is the documented one-shot form: it resets
 * the system idle timer once and asserts nothing persistent. Calling it on a timer therefore has no
 * thread affinity, needs no release call, and cannot outlive the process. The cost is a wakeup
 * every 30 s while downloading, which is nothing next to the transfer itself.
 *
 * The display is deliberately left alone. `ES_DISPLAY_REQUIRED` would also keep the screen lit,
 * and nobody downloading in the background wants their laptop screen burning all night.
 */
internal object SleepGuard {

    /** Resets the system idle timer. Deliberately not OR'd with ES_CONTINUOUS. See above. */
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    /**
     * Comfortably inside the shortest sleep timeout Windows will accept, which is one minute.
     * Typical settings are 15 to 30 minutes, so this is not a tight margin.
     */
    private const val PING_INTERVAL_MS = 30_000L

    /**
     * Bound directly rather than through `jna-platform`'s `Kernel32`, whose declared return type
     * for this function has moved between `int` and `DWORD` across versions. Four lines here is
     * cheaper than pinning a transitive dependency's signature.
     */
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val isWindows =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val kernel32: Kernel32? by lazy {
        if (!isWindows) return@lazy null
        try {
            Native.load("kernel32", Kernel32::class.java)
        } catch (e: Throwable) {
            Timber.w("Cannot load kernel32, sleep will not be held off: ${e.message}")
            null
        }
    }

    /**
     * Hold off sleep until the caller cancels this. Runs forever by design; the download loop
     * owns the job and cancels it in its `finally`.
     */
    suspend fun keepAwake() {
        if (kernel32 == null) return
        var warned = false
        while (true) {
            if (!ping() && !warned) {
                warned = true
                Timber.w("SetThreadExecutionState failed, the machine may sleep mid-download")
            }
            delay(PING_INTERVAL_MS)
        }
    }

    /**
     * Push the system idle timer back once. False if the call did not reach Windows or Windows
     * refused it, which is the failure this is worth checking: a wrong native binding does not
     * throw, it just quietly stops holding sleep off.
     */
    internal fun ping(): Boolean {
        val lib = kernel32 ?: return false
        // Returns the previous execution state, or 0 on failure.
        return lib.SetThreadExecutionState(ES_SYSTEM_REQUIRED) != 0
    }
}
