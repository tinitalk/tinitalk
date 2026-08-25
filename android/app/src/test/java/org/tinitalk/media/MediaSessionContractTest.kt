package org.tinitalk.media

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MediaSessionContractTest {
    @Test
    fun closeCanBeCalledMoreThanOnce() {
        val session = CloseCountingMediaSession()

        runBlockingLite {
            session.close()
            session.close()
        }

        assertTrue(session.closed)
    }

    private class CloseCountingMediaSession : MediaSession {
        var closed = false

        override suspend fun createOffer(): String = ""
        override suspend fun acceptOffer(sdp: String): String = ""
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun restartIce(): String = ""
        override fun setMuted(muted: Boolean) = Unit

        override suspend fun close() {
            closed = true
        }
    }

    private fun runBlockingLite(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    failure = result.exceptionOrNull()
                }
            },
        )
        failure?.let { throw it }
    }
}
