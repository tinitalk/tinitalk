package org.tinitalk.media

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Guards the shared session's audio-only construction path on a device. */
class WebRtcCallSessionInstrumentedTest {
    @Test
    fun audioOnlySessionCreatesOfferAndClosesOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = WebRtcCallSession.create(context, videoAllowed = false)

        runBlockingLite {
            session.createOffer()
            session.close()
            session.close()
        }
    }

    private fun runBlockingLite(block: suspend () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    failure = result.exceptionOrNull()
                    done.countDown()
                }
            },
        )
        check(done.await(10, TimeUnit.SECONDS)) { "Timed out waiting for media session" }
        failure?.let { throw it }
    }
}
