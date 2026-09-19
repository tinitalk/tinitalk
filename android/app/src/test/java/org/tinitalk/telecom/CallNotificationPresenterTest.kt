package org.tinitalk.telecom

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.push.ContactPhotoNotificationLoader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallNotificationPresenterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val address = ContactAddress.of("https://calls.example", "alex")
    private var state = CallUiState(
        accountId = AccountId("account-a"), callId = "call-1",
        peer = CallPeer("Алексей", "alex", address),
        direction = CallDirection.Outgoing, phase = CallPhase.Active,
    )
    private var owner = AccountCallOwner(
        requireNotNull(state.callKey), CallSessionBinding("https://calls.example", "me", "session-a", "config-a"),
    )
    private var refreshAllowed = true
    private val queued = mutableListOf<Runnable>()
    private val revision = MutableStateFlow(0L)
    private val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    private val photos = ContactPhotoNotificationLoader(object : ContactPhotoReader {
        override val revision: StateFlow<Long> = this@CallNotificationPresenterTest.revision
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap = bitmap
    }) { queued += it }
    private val presenter = CallNotificationPresenter(context, handler, photos, { owner }, { refreshAllowed }, { state })

    @After fun cleanUp() {
        presenter.close()
        VideoCallStateStore.publish(CallVideoState())
    }

    @Test fun ongoingNotificationUsesPeerPhotoForPersonAndLargeIcon() {
        presenter.ensureChannel()
        val notification = presenter.build(state, bitmap)
        val person = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

        assertNotNull(notification.getLargeIcon())
        assertNotNull(person?.icon)
        assertEquals("Алексей", person?.name)
    }

    @Test @Config(sdk = [26, 35])
    fun endedCallHasNoActiveCallStyleActionsOrChronometer() {
        presenter.ensureChannel()
        state = state.copy(phase = CallPhase.Ended, connectedAtElapsedMs = 1L, endReason = CallEndReason.RemoteHangup)
        // Late screen state must not turn a terminal notification back into a live call.
        VideoCallStateStore.publish(CallVideoState(
            accountId = state.accountId, callId = state.callId,
            screen = ScreenShareState(localId = "old-share", sending = true),
        ))
        val notification = presenter.build(state)
        assertEquals("Звонок завершён", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertTrue(notification.actions.isNullOrEmpty())
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNotEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test fun observerCannotRestoreNotificationAfterResourcesWereReleased() {
        presenter.ensureChannel()
        presenter.show(state)
        assertNotNull(shadowOf(manager).getNotification(CallForegroundService.NotificationId))
        refreshAllowed = false
        presenter.cancel()
        presenter.show(state.copy(phase = CallPhase.Ended))
        assertNull(shadowOf(manager).getNotification(CallForegroundService.NotificationId))
    }

    @Test fun actionsAreScopedToTheCallAndScreenSharingKeepsTheDuration() {
        presenter.ensureChannel()
        state = state.copy(connectedAtElapsedMs = SystemClock.elapsedRealtime())
        VideoCallStateStore.publish(CallVideoState(
            accountId = state.accountId, callId = state.callId,
            screen = ScreenShareState(localId = "share-1", sending = true),
        ))
        val notification = presenter.build(state, bitmap)
        val intents = notification.actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent }
            .filter { it.action in setOf(CallForegroundService.ActionEnd, CallForegroundService.ActionScreenStop) }

        assertEquals(2, intents.size)
        intents.forEach { intent ->
            assertEquals("account-a", intent.getStringExtra("account_id"))
            assertEquals("call-1", intent.getStringExtra("call_id"))
            assertEquals("session-a", intent.getStringExtra("session_id"))
            assertEquals("config-a", intent.getStringExtra("config_id"))
        }
        assertNotEquals(intents[0].data, intents[1].data)
        assertEquals("Вы показываете экран", notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("org.tinitalk.CallActivity", shadowOf(notification.contentIntent).savedIntent.component?.className)
    }

    @Test @Config(sdk = [28])
    fun olderAndroidStillOffersHangUpWithoutAnotherCallsScreenAction() {
        presenter.ensureChannel()
        VideoCallStateStore.publish(CallVideoState(
            accountId = state.accountId, callId = "another-call",
            screen = ScreenShareState(localId = "share-2", sending = true),
        ))
        val notification = presenter.build(state, bitmap)
        assertEquals(1, notification.actions.orEmpty().size)
        assertEquals(CallForegroundService.ActionEnd, shadowOf(notification.actions.single().actionIntent).savedIntent.action)
        assertEquals("Завершить", notification.actions.single().title)
    }

    @Test fun terminalNotificationHasNoActionsAndCancelRemovesThePublishedCall() {
        presenter.ensureChannel()
        presenter.show(state)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(10, manager.activeNotifications.single().id)
        assertEquals("calls", manager.activeNotifications.single().notification.channelId)
        val terminal = presenter.terminal()
        assertEquals(Notification.CATEGORY_SERVICE, terminal.category)
        assertTrue(terminal.actions.isNullOrEmpty())
        presenter.cancel()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun loadedPhotoUpdatesTheCurrentNotificationUsingTheLatestCallState() {
        presenter.ensureChannel()
        presenter.show(state)
        assertEquals(1, queued.size)
        state = state.copy(muted = true)
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        val notification = manager.activeNotifications.single().notification
        assertNotNull(notification.getLargeIcon())
        assertEquals("Микрофон выключен", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun delayedPhotoCannotReviveAReleasedNotification() {
        presenter.ensureChannel()
        presenter.show(state)
        assertEquals(1, queued.size)
        presenter.cancel()
        refreshAllowed = false
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun delayedPhotoIsDiscardedAfterTheCurrentCallChanges() {
        presenter.ensureChannel()
        presenter.show(state)
        assertEquals(1, queued.size)
        state = state.copy(accountId = AccountId("account-b"))
        owner = owner.copy(key = requireNotNull(state.callKey))
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(manager.activeNotifications.single().notification.getLargeIcon())
    }

    @Test fun delayedPhotoIsDiscardedAfterPhotoRevisionOrPresenterClose() {
        presenter.ensureChannel()
        presenter.show(state)
        assertEquals(1, queued.size)
        revision.value++
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(manager.activeNotifications.single().notification.getLargeIcon())
        queued.clear()
        presenter.show(state)
        presenter.close()
        presenter.cancel()
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
