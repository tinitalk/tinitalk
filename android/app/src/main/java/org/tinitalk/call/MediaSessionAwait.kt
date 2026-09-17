package org.tinitalk.call

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Preserves the bounded synchronous handoff to the media worker used by call control. */
internal fun <T> awaitMediaOperation(block: suspend () -> T): T {
    val done = CountDownLatch(1)
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result.getOrNull()
                failure = result.exceptionOrNull()
                done.countDown()
            }
        },
    )
    check(done.await(10, TimeUnit.SECONDS)) { "Timed out waiting for media session" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
