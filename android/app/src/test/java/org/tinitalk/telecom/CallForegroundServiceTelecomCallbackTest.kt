package org.tinitalk.telecom

import android.os.Looper
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallSnapshot
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.SignalClient
import org.tinitalk.data.signal.SignalEvent
import kotlin.jvm.functions.Function0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceTelecomCallbackTest {
    @Test
    fun acceptsLocalTelecomCallbackAfterCrossedCallAdoptsCanonicalId() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val coordinator = CallCoordinator("alice", NoopSignalClient()).apply {
            restoreIncoming(CanonicalCallId, acknowledgeRinging = false)
            accept()
        }
        service.setPrivateField("coordinator", coordinator)
        service.setPrivateField("telecomCallId", LocalTelecomCallId)
        CallUiStateStore.begin(
            CanonicalCallId,
            CallPeer("Bob"),
            CallDirection.Outgoing,
            CallPhase.Active,
        )
        val endpoint = AudioEndpoint("speaker", "Speaker", 4)
        val endpoints = AudioEndpointState(endpoint, listOf(endpoint))
        var disconnected = false

        val callbacks = service.telecomCallbacksForTest(LocalTelecomCallId) { disconnected = true }
        callbacks.onEndpointsChanged(endpoints)
        callbacks.onDisconnect()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CallSnapshot(CallPhase.Active, CanonicalCallId, 0), coordinator.snapshot())
        assertEquals(endpoints, CallAudioState.snapshot())
        assertEquals(endpoint, CallUiStateStore.snapshot().currentAudioEndpoint)
        assertTrue(disconnected)
        controller.destroy()
    }

    private fun CallForegroundService.setPrivateField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setPrivateField, value)
        }
    }

    private fun CallForegroundService.telecomCallbacksForTest(
        callId: String,
        onDisconnect: () -> Unit,
    ): TelecomCallCallbacks {
        val method = javaClass.getDeclaredMethod(
            "telecomCallbacks",
            String::class.java,
            Function0::class.java,
        ).apply { isAccessible = true }
        return method.invoke(this, callId, onDisconnect) as TelecomCallCallbacks
    }

    private class NoopSignalClient : SignalClient {
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
    }

    private companion object {
        const val LocalTelecomCallId = "local-call"
        const val CanonicalCallId = "canonical-call"
    }
}
