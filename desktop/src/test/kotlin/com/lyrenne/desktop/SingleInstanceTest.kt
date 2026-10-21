package com.lyrenne.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @Test
    fun secondClaimWakesTheFirst() {
        val woke = CountDownLatch(1)
        SingleInstance.onActivate = { woke.countDown() }
        assertTrue("first claim should own the socket", SingleInstance.claim())
        assertFalse("second claim should find the first", SingleInstance.claim())
        assertTrue("first copy was not poked", woke.await(2, TimeUnit.SECONDS))
    }
}
