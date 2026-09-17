package org.tinitalk.push

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.telecom.IncomingCallController
import com.google.gson.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.tinitalk.data.*
import org.tinitalk.data.signal.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class ForegroundIncomingCallsTest {
    private val app = RuntimeEnvironment.getApplication().also {
        shadowOf(it).grantPermissions("${it.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
    private val sockets = mutableListOf<Pair<Session, ListeningConnection>>()
    private var online = true
    private val presented = mutableListOf<IncomingInvite>()
    private val canceled = mutableListOf<CallCancellation>()
    private val receiver = ForegroundIncomingCalls(app, auth, { online },
        acquire = { session, _ -> ListeningConnection().also { sockets += session to it } },
        present = presented::add,
        cancel = { _, cancellation -> canceled += cancellation },
    )
    private val firstActivity = Robolectric.buildActivity(Activity::class.java).create().get()
    private val secondActivity = Robolectric.buildActivity(Activity::class.java).create().get()

    @After fun cleanup() {
        receiver.close()
        IncomingCallScreenState.hidden()
    }

    @Test fun allAccountsListenOnlyWhileVisibleAndActivityHandoffKeepsConnections() {
        unlock()
        auth.upsert(Session("https://one.example", "alice", "token", sessionId = "s1"))
        auth.upsert(Session("https://two.example", "alice", "token", sessionId = "s2"))
        assertTrue(sockets.isEmpty())
        receiver.onActivityResumed(firstActivity)
        await { sockets.size == 2 }
        assertTrue(sockets.all { it.second.connected })
        receiver.onActivityPaused(firstActivity)
        receiver.onActivityResumed(secondActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, sockets.size)
        assertTrue(sockets.none { it.second.closed })
        receiver.onActivityPaused(secondActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(sockets.all { it.second.closed })
    }

    @Test fun offlineAndRemovedAccountReleaseOnlyTheAffectedSubscriptions() {
        unlock()
        val first = auth.upsert(Session("https://one.example", "alice", "token"))
        auth.upsert(Session("https://two.example", "bob", "token"))
        receiver.onActivityResumed(firstActivity)
        await { sockets.size == 2 }
        auth.remove(first.id)
        receiver.networkChanged()
        await { sockets.first().second.closed }
        assertFalse(sockets.last().second.closed)
        online = false
        receiver.networkChanged()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sockets.last().second.closed)
        online = true
        receiver.networkChanged()
        await { sockets.size == 3 }
        assertEquals("bob", sockets.last().first.login)
    }

    @Test fun lockedScreenDoesNotStartIdleConnections() {
        unlock()
        auth.upsert(Session("https://one.example", "alice", "token"))
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        receiver.onActivityResumed(firstActivity)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sockets.isEmpty())
    }

    @Test @Suppress("DEPRECATION")
    fun screenOffClosesIdleSocketsEvenWithoutAnActivityPause() {
        unlock()
        auth.upsert(Session("https://one.example", "alice", "token"))
        receiver.onActivityResumed(firstActivity)
        await { sockets.size == 1 }
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        await { sockets.single().second.closed }
        unlock()
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        await { sockets.size == 2 }
    }

    @Test fun visibilitySurvivesActivityRecreationButIsWithdrawnInBackground() {
        unlock()
        val account = auth.upsert(Session("https://one.example", "alice", "token"))
        val invite = IncomingInvite(account.id, CallSessionBinding.from(account.session), UUID.randomUUID().toString(),
            "Bob", Instant.now().plusSeconds(45), "bob")
        val controller = IncomingCallController()
        controller.admitIncoming(app, invite)
        try {
            receiver.onActivityResumed(firstActivity)
            await { sockets.size == 1 }
            val socket = sockets.single().second
            IncomingCallScreenState.shown(invite.owner)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(socket.visibility.last())
            IncomingCallScreenState.hidden(invite.owner)
            receiver.onActivityPaused(firstActivity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertFalse(socket.visibility.contains(false))
            IncomingCallScreenState.shown(invite.owner)
            receiver.onActivityResumed(secondActivity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(socket.visibility.contains(false))
            receiver.onActivityPaused(secondActivity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, socket.visibility.count { !it })
            assertTrue(socket.closed)
        } finally {
            controller.clear(app, invite.owner)
            GlobalCallAdmission.releaseStaged(invite.owner)
        }
    }

    @Test fun incomingWithoutPushIsDeliveredButLateEventAfterLogoutIsIgnored() {
        unlock()
        val account = auth.upsert(Session("https://one.example", "alice", "token"))
        receiver.onActivityResumed(firstActivity)
        await { sockets.size == 1 }
        fun incoming() = SequencedSignalEvent(SignalEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "call.incoming", Instant.now().toEpochMilli(), JsonObject().apply { addProperty("caller_login", "bob") }), 1)
        sockets.single().second.event(incoming())
        assertEquals("bob", presented.single().caller)
        auth.remove(account.id)
        // Account reconciliation hasn't run yet; the event must still be rejected immediately.
        sockets.single().second.event(incoming())
        assertEquals(1, presented.size)
    }

    @Test fun recoveryDismissesAnUnansweredInviteCanceledWhileDisconnected() {
        unlock()
        MockWebServer().use { server ->
            server.start()
            val account = auth.upsert(Session(server.url("/").toString(), "alice", "token"))
            val invite = IncomingInvite(account.id, CallSessionBinding.from(account.session), UUID.randomUUID().toString(),
                "Bob", Instant.now().plusSeconds(45), "bob")
            val controller = IncomingCallController()
            controller.admitIncoming(app, invite)
            try {
                receiver.onActivityResumed(firstActivity)
                await { sockets.size == 1 }
                val socket = sockets.single().second
                server.enqueue(MockResponse().setResponseCode(204))
                socket.open(1)
                await { canceled.isNotEmpty() }
                assertEquals(invite.key, canceled.single().key)
                assertTrue(presented.isEmpty())
            } finally {
                controller.clear(app, invite.owner)
                GlobalCallAdmission.releaseStaged(invite.owner)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun unlock() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("foreground subscriptions did not reach expected state", condition())
    }
}

private class ListeningConnection : SignalConnection {
    var connected = false
    var closed = false
    val visibility = mutableListOf<Boolean>()
    lateinit var event: (SequencedSignalEvent) -> Unit
    lateinit var open: (Long) -> Unit
    override fun connect(onEvent: (SequencedSignalEvent) -> Unit, onOpen: (Long) -> Unit,
                         onDisconnected: (Long) -> Unit, onError: (SignalFailure) -> Unit) {
        connected = true
        event = onEvent
        open = onOpen
    }
    override fun reconnectNow() = Unit
    override fun isOpen() = connected && !closed
    override fun isOpen(expectedGeneration: Long) = isOpen()
    override fun sendVisibility(callId: String, visible: Boolean): Boolean {
        if (!isOpen()) return false
        visibility += visible
        return true
    }
    override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
    override fun close() { closed = true }
}
