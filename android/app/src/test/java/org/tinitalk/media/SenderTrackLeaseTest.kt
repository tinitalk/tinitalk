package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderTrackLeaseTest {
    @Test
    fun attachFalseDisposesTheNeverAttachedTrack() {
        var disposed = false
        val lease = SenderTrackLease(
            track = "local",
            attach = { false },
            detach = { true },
            disable = {},
            dispose = { disposed = true },
        )

        assertFalse(lease.attach())

        assertTrue(disposed)
    }

    @Test
    fun detachFalseDisablesAndDefersTrackDisposalUntilPeerIsClosed() {
        var disabled = false
        var disposed = false
        val lease = SenderTrackLease(
            track = "local",
            attach = { true },
            detach = { false },
            disable = { disabled = true },
            dispose = { disposed = true },
        )
        assertTrue(lease.attach())

        val release = lease.release()

        assertEquals("failed to detach local video track", release.failure)
        assertTrue(disabled)
        assertFalse(disposed)
        assertTrue(release.requiresPeerClosed)
        lease.dispose()
        assertTrue(disposed)
    }

    @Test
    fun detachExceptionAlsoDefersDisposalBecauseTheSenderMayStillReferenceTheTrack() {
        var disposed = false
        val lease = SenderTrackLease(
            track = "local",
            attach = { true },
            detach = { error("peer is closing") },
            disable = {},
            dispose = { disposed = true },
        )
        assertTrue(lease.attach())

        val release = lease.release()

        assertEquals("failed to detach local video track", release.failure)
        assertFalse(disposed)
        assertTrue(release.requiresPeerClosed)
        lease.dispose()
        assertTrue(disposed)
    }

    @Test
    fun successfulDetachWaitsForCameraStopBeforeDisposal() {
        var disposed = false
        val lease = SenderTrackLease(
            track = "local",
            attach = { true },
            detach = { true },
            disable = {},
            dispose = { disposed = true },
        )
        assertTrue(lease.attach())

        val release = lease.release()

        assertNull(release.failure)
        assertFalse(release.requiresPeerClosed)
        assertFalse(disposed)
        lease.dispose()
        assertTrue(disposed)
    }
}
