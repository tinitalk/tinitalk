package org.tinitalk.push

import android.app.NotificationManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingRingingHandoffTest {
    private val context = RuntimeEnvironment.getApplication()
    private val incoming = IncomingCallController()
    private val invite = IncomingInvite(
        AccountId("ringing-account"), CallSessionBinding("https://ringing.example", "bob", "session", "config"),
        "018f7d51-40a1-7bb5-a2d0-7e47f9181000", "Alice", Instant.now().plusSeconds(45),
    )
    private val serviceController = Robolectric.buildService(IncomingCallForegroundService::class.java)
    private val service get() = serviceController.get()
    private val client = OkHttpClient()
    private val transport = DelayedRingingSocket()
    private val socket = SignalSocket(
        client, Session(invite.sessionBinding.serverUrl, "bob", "token"), socketFactory = transport,
    )
    private var destroyed = false

    @Before fun startPendingAcknowledgement() {
        incoming.admitIncoming(context, invite)
        serviceController.create()
        val acknowledger = ReflectionHelpers.callInstanceMethod<IncomingRingingAcknowledger>(service, "getRingingAcknowledger")
        // Hold the real signaling socket before its network handshake completes.
        ReflectionHelpers.setField(acknowledger, "owner", invite.owner)
        ReflectionHelpers.setField(acknowledger, "socket", socket)
        CallCoordinator("bob", socket, accountId = invite.accountId).restoreIncoming(invite.callId) {
            acknowledger.stop(invite.owner)
        }
        socket.connect(onEvent = {})
        service.onStartCommand(incoming.presentationIntent(context, invite), 0, 1)
        drainServiceCommands()
    }

    @After fun cleanup() {
        if (!destroyed) serviceController.destroy()
        socket.close()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        incoming.finishTerminalPresentation(context, invite.owner) { IncomingCallNotifier(context).cancel() }
        CallUiStateStore.reset()
        CallServiceState.reset()
    }

    @Test fun fullScreenShownBeforeSocketOpensStillDeliversRingingToCaller() {
        IncomingCallNotifier(context).fullScreenShown(invite)
        drainServiceCommands()

        assertFalse("Opening the incoming screen cancelled the pending ringing acknowledgement", transport.closed)
        assertFalse(destroyed)
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())

        transport.open()
        val ringing = SignalEvent.decodeForDelivery(transport.sent.single())
        assertEquals("call.ringing", ringing.type)
        val caller = CallCoordinator("alice", object : SignalClient {
            override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
        }, accountId = invite.accountId)
        caller.startCall("bob", invite.callId)
        assertEquals(CallPhase.Connecting, caller.snapshot().phase)
        caller.onEvent(SequencedSignalEvent(ringing, 1))
        assertEquals(CallPhase.Ringing, caller.snapshot().phase)
    }

    @Test fun terminalCancellationStillClosesPendingAcknowledgement() {
        IncomingCallNotifier(context).cancel()
        drainServiceCommands()
        assertTrue(destroyed)
        assertTrue(transport.closed)
        transport.open()
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun returningToFullScreenHidesRestoredNotificationWithoutClosingSocket() {
        val notifier = IncomingCallNotifier(context)
        val expiryTask = ReflectionHelpers.getField<Runnable>(service, "stopTask")
        repeat(2) {
            notifier.fullScreenShown(invite)
            drainServiceCommands()
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            assertFalse(transport.closed)
            assertSame(expiryTask, ReflectionHelpers.getField<Runnable>(service, "stopTask"))
            notifier.fullScreenHidden(invite)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
        }
    }

    @Test fun staleHideCannotRemoveCurrentCallsNotification() {
        val stale = invite.copy(sessionBinding = invite.sessionBinding.copy(sessionId = "old-session"))
        IncomingCallScreenState.shown(stale.owner)
        service.onStartCommand(incoming.presentationIntent(context, stale)
            .setAction(IncomingCallForegroundService.ActionHideNotification), 0, 2)
        assertFalse(Shadows.shadowOf(service).isForegroundStopped)
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        assertFalse(transport.closed)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
    }

    @Test fun queuedHideCannotRemoveNotificationRestoredAfterScreenCloses() {
        val notifier = IncomingCallNotifier(context)
        notifier.fullScreenShown(invite)
        notifier.fullScreenHidden(invite)
        drainServiceCommands()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
        assertFalse(transport.closed)
        assertFalse(destroyed)
    }

    private fun drainServiceCommands() {
        val app = Shadows.shadowOf(context)
        while (true) {
            val stop = app.nextStoppedService ?: break
            if (stop.component?.className == IncomingCallForegroundService::class.java.name && !destroyed) {
                serviceController.destroy()
                destroyed = true
            }
        }
        while (true) {
            val start = app.nextStartedService ?: break
            if (start.component?.className == IncomingCallForegroundService::class.java.name && !destroyed) {
                service.onStartCommand(start, 0, 2)
            }
        }
    }

    private class DelayedRingingSocket : WebSocket, WebSocket.Factory {
        private lateinit var request: Request
        private lateinit var listener: WebSocketListener
        var closed = false
        val sent = mutableListOf<String>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            return this
        }
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").header("X-TiniTalk-Signal-Protocol", "2").build())
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (closed) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString) = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }
}
