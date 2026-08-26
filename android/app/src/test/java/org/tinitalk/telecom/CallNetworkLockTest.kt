package org.tinitalk.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNetworkLockTest {
    @Test
    fun holdsNetworkOnlyWhileCallIsActive() {
        val backend = FakeNetworkLockBackend()
        val lock = CallNetworkLock(backend)

        lock.setActive(true)
        lock.setActive(true)

        assertTrue(backend.isHeld)
        assertEquals(1, backend.acquireCount)

        lock.setActive(false)
        lock.setActive(false)

        assertFalse(backend.isHeld)
        assertEquals(1, backend.releaseCount)
    }

    @Test
    fun closeReleasesAnActiveLockOnce() {
        val backend = FakeNetworkLockBackend()
        val lock = CallNetworkLock(backend)
        lock.setActive(true)

        lock.close()
        lock.close()

        assertFalse(backend.isHeld)
        assertEquals(1, backend.releaseCount)
    }

    private class FakeNetworkLockBackend : NetworkLockBackend {
        override var isHeld = false
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire() {
            check(!isHeld)
            isHeld = true
            acquireCount++
        }

        override fun release() {
            check(isHeld)
            isHeld = false
            releaseCount++
        }
    }
}
