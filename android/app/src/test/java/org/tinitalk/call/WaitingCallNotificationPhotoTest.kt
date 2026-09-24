package org.tinitalk.call

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.tinitalk.R
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.i18n.LocalizedTestApplication
import org.tinitalk.i18n.appString
import org.tinitalk.push.ContactPhotoNotificationLoader
import org.tinitalk.push.IncomingInvite
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = LocalizedTestApplication::class, sdk = [26, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class WaitingCallNotificationPhotoTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val queued = mutableListOf<Runnable>()
    private val photos = mutableMapOf<ContactAddress, Bitmap>()
    private val cached = mutableSetOf<ContactAddress>()
    private val reader = object : ContactPhotoReader {
        override val revision = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int) = photos[address].takeIf { address in cached }
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            cached += address
            return photos[address]
        }
    }
    private val loader = ContactPhotoNotificationLoader(reader) { queued += it }
    private val presentation = WaitingCallPresentation(context, loader)
    private val call = pending()

    @After fun cleanup() {
        presentation.close()
        manager.cancelAll()
    }

    @Test fun cachedPhotoIsDisplayedImmediatelyWithExistingActions() {
        addPhoto(call, Color.RED, alreadyCached = true)
        render(call)

        val notification = notification(call)
        assertPhotoColor(notification, Color.RED)
        val bitmap = (notification.getLargeIcon().loadDrawable(context) as BitmapDrawable).bitmap
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(listOf(appString(R.string.text_busy_91), appString(R.string.text_answer_64)),
            notification.actions.map { it.title.toString() })
        assertTrue(queued.isEmpty())
    }

    @Test fun asynchronousPhotosBelongToTheirOwnCallEvenWithSameLoginAndCallId() {
        val other = pending(server = "two")
        addPhoto(call, Color.RED)
        addPhoto(other, Color.BLUE)
        render(call, other)
        assertNull(notification(call).getLargeIcon())
        assertNull(notification(other).getLargeIcon())
        assertEquals(2, queued.size)

        // Finish in reverse order to exercise independent notification tags.
        queued.removeAt(1).run()
        idle()
        assertPhotoColor(notification(other), Color.BLUE)
        assertNull(notification(call).getLargeIcon())
        finishPhotos()
        assertPhotoColor(notification(call), Color.RED)
        assertPhotoColor(notification(other), Color.BLUE)
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(notification(call).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(manager.getNotificationChannel(notification(call).channelId).sound)
    }

    @Test fun latePhotoCannotBringBackRejectedOrCancelledCall() {
        val other = pending(server = "two")
        addPhoto(call, Color.RED)
        addPhoto(other, Color.BLUE)
        render(call, other)
        render(other)
        finishPhotos()

        assertEquals(listOf(other.invite.owner.localId()), manager.activeNotifications.map { it.tag })
        assertPhotoColor(notification(other), Color.BLUE)
    }

    @Test fun latePhotoCannotRestoreActionsWhileAnswering() {
        addPhoto(call, Color.RED)
        render(call)
        presentation.render(WaitingCallsState(listOf(call), answering = call.invite.owner), CallUiState())
        finishPhotos()

        assertPhotoColor(notification(call), Color.RED)
        assertTrue(notification(call).actions.isNullOrEmpty())
    }

    @Test fun closingPresentationPreventsPendingPhotoAndFutureRenderFromPosting() {
        addPhoto(call, Color.RED)
        render(call)
        presentation.close()
        finishPhotos()
        render(call)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun removedSystemNotificationIsNotRecreatedByPhoto() {
        addPhoto(call, Color.RED)
        render(call)
        val shown = manager.activeNotifications.single()
        manager.cancel(shown.tag, shown.id)
        finishPhotos()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun expiredPhotoDoesNotExtendTheWaitingDeadline() {
        addPhoto(call, Color.RED)
        render(call)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        finishPhotos()
        // Some notification managers expire it themselves; neither a retained nor
        // an already removed notification may be refreshed after the deadline.
        assertTrue(manager.activeNotifications.all { it.notification.getLargeIcon() == null })
    }

    @Test fun oldPhotoRevisionIsDiscarded() {
        addPhoto(call, Color.RED)
        render(call)
        reader.revision.value++
        finishPhotos()
        assertNull(notification(call).getLargeIcon())
    }

    @Test fun unknownCallerDoesNotAttemptPhotoLookupAndStillHasActions() {
        val anonymous = call.copy(invite = call.invite.copy(callerLogin = null))
        render(anonymous)
        assertNull(notification(anonymous).getLargeIcon())
        assertEquals(2, notification(anonymous).actions.size)
        assertTrue(queued.isEmpty())
    }

    private fun render(vararg calls: WaitingCall) {
        presentation.render(WaitingCallsState(calls.toList()), CallUiState())
    }

    private fun notification(call: WaitingCall) = manager.activeNotifications.single {
        it.tag == call.invite.owner.localId()
    }.notification

    private fun addPhoto(call: WaitingCall, color: Int, alreadyCached: Boolean = false) {
        val address = ContactAddress.of(call.invite.sessionBinding.serverUrl, call.invite.callerLogin!!)
        photos[address] = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        if (alreadyCached) cached += address
    }

    private fun assertPhotoColor(notification: Notification, color: Int) {
        val icon = requireNotNull(notification.getLargeIcon())
        val bitmap = (icon.loadDrawable(context) as BitmapDrawable).bitmap
        assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun finishPhotos() {
        val work = queued.toList()
        queued.clear()
        work.forEach(Runnable::run)
        idle()
    }

    private fun pending(server: String = "one") = WaitingCall(
        invite = IncomingInvite(AccountId(server), CallSessionBinding("https://$server.example", "me", "session", null),
            "same-call-id", "Caller", Instant.now().plusSeconds(45), callerLogin = "caller"),
        deadlineElapsedMs = SystemClock.elapsedRealtime() + 15_000,
        originalDeadlineElapsedMs = SystemClock.elapsedRealtime() + 45_000,
        acknowledged = true,
    )
}
