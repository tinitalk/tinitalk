package org.tinitalk.push

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactRefreshSchedulerTest {
    @Test
    fun callRejectionDoesNotWaitForStorageOnTheCallingThread() {
        val enteredStorage = CountDownLatch(1)
        val releaseStorage = CountDownLatch(1)
        val storageThread = AtomicReference<Thread>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                storageThread.set(Thread.currentThread())
                enteredStorage.countDown()
                check(releaseStorage.await(5, TimeUnit.SECONDS)) { "storage was not released" }
                return super.getSharedPreferences(name, mode)
            }
        }
        val account = AccountRecord(AccountId("a"), Session("https://a.example", "alice", "token"))
        try {
            ContactRefreshScheduler(context).enqueueAfterCallRejection(account, "bob")

            assertTrue(enteredStorage.await(5, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), storageThread.get())
        } finally {
            releaseStorage.countDown()
        }
    }
}
