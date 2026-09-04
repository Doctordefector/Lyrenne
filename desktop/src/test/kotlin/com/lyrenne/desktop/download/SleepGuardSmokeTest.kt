package com.lyrenne.desktop.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks that the sleep hold-off actually reaches Windows.
 *
 * A wrong JNA binding does not throw. `Native.load` resolves the library, the call marshals, and
 * the wrong thing happens quietly, so the only symptom is a laptop suspending in the middle of a
 * long download weeks later. Nothing else in the build can catch that, which is why it is asserted
 * against the real kernel32 here rather than mocked.
 */
class SleepGuardSmokeTest {

    private val onWindows =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The binding resolves and Windows accepts the flag. */
    @Test
    fun `ping reaches SetThreadExecutionState`() {
        assumeTrue("Windows-only API", onWindows)
        assertTrue(
            "SetThreadExecutionState returned 0, so sleep is not being held off",
            SleepGuard.ping()
        )
    }

    /**
     * The download loop cancels this job in a `finally`, so it has to actually stop. A hold that
     * ignored cancellation would keep the machine awake for the rest of the session.
     */
    @Test
    fun `keepAwake stops when cancelled`() = runBlocking {
        assumeTrue("Windows-only API", onWindows)
        val job = launch { SleepGuard.keepAwake() }
        delay(100)
        job.cancel()
        assertTrue(
            "keepAwake did not unwind on cancellation",
            withTimeoutOrNull(2_000) { job.join(); true } == true
        )
    }
}
