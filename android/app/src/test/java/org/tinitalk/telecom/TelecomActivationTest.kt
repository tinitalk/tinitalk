package org.tinitalk.telecom

import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TelecomActivationTest {
    @Test
    fun unavailableControlDoesNotBecomeAnActivationRejection() = runBlocking {
        assertEquals(TelecomActivationResult.Unavailable, activateTelecomCall { null })
    }

    @Test
    fun earlyAnswerIsReleasedWhenRegistrationEndsWithoutControl() = runBlocking {
        val session = TelecomSession()
        val activation = async(start = CoroutineStart.UNDISPATCHED) {
            activateTelecomCall { session.control.await() }
        }
        assertFalse(activation.isCompleted)

        // The addCall finally block also runs after its registration timeout.
        session.finish()

        assertEquals(TelecomActivationResult.Unavailable, withTimeout(1_000) { activation.await() })
        assertFalse(session.publish(control { CallControlResult.Success() }))
    }

    @Test
    fun answerAfterRegistrationHasEndedAlsoSeesUnavailable() = runBlocking {
        val session = TelecomSession()
        session.finish()
        assertEquals(TelecomActivationResult.Unavailable, activateTelecomCall { session.control.await() })
    }

    @Test
    fun registeredControlCanActivate() = runBlocking {
        assertEquals(TelecomActivationResult.Activated,
            activateTelecomCall { control { CallControlResult.Success() } })
    }

    @Test
    fun registeredControlRejectionIsStillTerminal() = runBlocking {
        assertEquals(TelecomActivationResult.Rejected,
            activateTelecomCall { control { CallControlResult.Error(1) } })
    }

    @Test
    fun registeredControlExceptionIsNotTreatedAsMissingIntegration() = runBlocking {
        assertEquals(TelecomActivationResult.Rejected,
            activateTelecomCall { control { throw IllegalStateException("activation failed") } })
    }

    @Test
    fun cancellationIsNotConvertedToAnActivationResult() = runBlocking {
        val cancellation = CancellationException("call was cancelled")
        try {
            activateTelecomCall { control { throw cancellation } }
            fail("Cancellation must propagate")
        } catch (failure: CancellationException) {
            assertSame(cancellation, failure)
        }
    }

    private fun control(activate: () -> CallControlResult): CallControlScope =
        Proxy.newProxyInstance(CallControlScope::class.java.classLoader, arrayOf(CallControlScope::class.java)) {
                _, method, _ ->
            check(method.name == "setActive") { "Unexpected call: ${method.name}" }
            activate()
        } as CallControlScope
}
