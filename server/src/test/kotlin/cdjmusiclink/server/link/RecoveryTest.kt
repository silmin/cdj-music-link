package cdjmusiclink.server.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryTest {
    @Test
    fun staleStopSignalWhileRunningWithDevicesIsIgnored() {
        assertFalse(Recovery.isDown(virtualCdjRunning = true, deviceCount = 2))
    }

    @Test
    fun downWhenNoDevicesOrVirtualCdjStopped() {
        assertTrue(Recovery.isDown(virtualCdjRunning = true, deviceCount = 0))
        assertTrue(Recovery.isDown(virtualCdjRunning = false, deviceCount = 2))
        assertTrue(Recovery.isDown(virtualCdjRunning = false, deviceCount = 0))
    }

    @Test
    fun awaitQuietReturnsOnceIdle() = runTest {
        var polls = 0
        val quiet = Recovery.awaitQuiet(timeoutMs = 10_000, pollMs = 100) { ++polls < 4 }
        assertTrue(quiet)
        assertEquals(300L, currentTime)
    }

    @Test
    fun awaitQuietGivesUpAtTimeout() = runTest {
        val quiet = Recovery.awaitQuiet(timeoutMs = 1_000, pollMs = 100) { true }
        assertFalse(quiet)
        assertEquals(1_000L, currentTime)
    }
}
