package org.tinitalk.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.tinitalk.data.AccountStorageLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.FailedReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TinitalkPushServiceTest {
    @Test
    fun registrationCallbackDoesNotWaitForAccountStorage() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        val service = Robolectric.buildService(TinitalkPushService::class.java).create().get()
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Thread {
            synchronized(AccountStorageLock) {
                lockHeld.countDown()
                releaseLock.await()
            }
        }.apply { start() }
        assertTrue(lockHeld.await(1, TimeUnit.SECONDS))

        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val callback = Thread {
            runCatching {
                service.onRegistrationFailed(FailedReason.NETWORK, "callback-account")
            }.onFailure(failure::set)
            returned.countDown()
        }.apply { start() }

        try {
            assertTrue("registration callback blocked on account storage", returned.await(500, TimeUnit.MILLISECONDS))
            assertNull(failure.get())
        } finally {
            releaseLock.countDown()
            holder.join(1_000)
            callback.join(1_000)
        }
    }
}
