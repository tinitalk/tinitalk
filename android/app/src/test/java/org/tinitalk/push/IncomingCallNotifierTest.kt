package org.tinitalk.push

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.core.os.BundleCompat
import android.service.notification.StatusBarNotification
import org.tinitalk.CallActivity
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.contactPeerFromIntent
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.Session
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallAdmission
import org.tinitalk.call.CallAdmissionHandoff
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.data.UnreadMissedContact
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.TelecomCallController
import org.tinitalk.telecom.TelecomCapabilities
import org.tinitalk.telecom.TelecomRegistrar
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingCallNotifierTest {
    private val accountId = AccountId("account-a")
    private fun invite(callId: String, caller: String = "Alice") = IncomingInvite(
        accountId,
        CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
        callId,
        caller,
        Instant.now().plusSeconds(30),
    )
    private fun controller() = IncomingCallController(CallAdmissionHandoff(CallAdmission()))
    private fun missedChildren(manager: NotificationManager): List<StatusBarNotification> =
        manager.activeNotifications.filter { active ->
            active.notification.category == Notification.CATEGORY_MISSED_CALL &&
                active.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
    private fun missedSummary(manager: NotificationManager): Notification =
        manager.activeNotifications.single { active ->
            active.notification.category == Notification.CATEGORY_MISSED_CALL &&
                active.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        }.notification
    private fun childFor(manager: NotificationManager, login: String): Notification =
        missedChildren(manager).single { active ->
            active.notification.actions.orEmpty().any { action ->
                Shadows.shadowOf(action.actionIntent).savedIntent.getStringExtra("outgoing_login") == login
            } || active.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == login
        }.notification

    @Test
    fun incomingPushRequiresExactSessionTarget() {
        val session = Session("https://a.example", "alice", "token", sessionId = "session-a")

        assertEquals(false, IncomingPushPayload.matchesTarget(mapOf("type" to "incoming_call"), session, "phone"))
        assertEquals(
            true,
            IncomingPushPayload.matchesTarget(
                mapOf(
                    "target_login" to "alice",
                    "target_device_id" to "phone",
                    "target_session_id" to "session-a",
                ),
                session,
                "phone",
            ),
        )
    }

    @Test
    fun serverMissedStateWithoutAnExactSessionOmitsRedial() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(accountId))
        val refreshId = notifier.beginAccountMissedCountRefresh(accountId)

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(
                unreadMissedCount = 3,
                unreadMissed = listOf(
                    UnreadMissedContact("anna", 200, missedCount = 2),
                    UnreadMissedContact("ira", 100, missedCount = 1),
                ),
            ),
            refreshId,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val children = missedChildren(manager)
        val summary = missedSummary(manager)

        assertEquals(2, children.size)
        assertTrue(children.all { it.notification.actions.isNullOrEmpty() })
        assertTrue(children.all { it.notification.group == summary.group })
        assertEquals(listOf(1, 2), children.map { it.notification.number }.sorted())
        assertEquals(3, summary.number)
    }

    @Test
    fun historyBackedCurrentSessionNamesPinnedRedialTarget() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        notifier.syncMissedAccounts(listOf(accountId))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = childFor(manager, "anna")
        val redialIntent = Shadows.shadowOf(notification.actions.single().actionIntent).savedIntent

        assertEquals("Пропущенный звонок", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("anna", redialIntent.getStringExtra("outgoing_login"))
        assertEquals("Анна", redialIntent.getStringExtra("outgoing_name"))
        assertEquals(binding.serverUrl, redialIntent.getStringExtra("redial_server_url"))
        assertEquals(binding.sessionId, redialIntent.getStringExtra("redial_session_id"))
        assertTrue(redialIntent.data.toString().contains("session-a"))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(3, listOf(UnreadMissedContact("anna", 200, "Анна", 3))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )
        val multiple = childFor(manager, "anna")
        assertEquals("3 пропущенных вызова", multiple.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(3, multiple.number)
    }

    @Test
    fun missedContactNotificationOpensItsExactContactProfile() {
        val context = RuntimeEnvironment.getApplication()
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(accountId))
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val notification = childFor(context.getSystemService(NotificationManager::class.java), "anna")
        val openContact = Shadows.shadowOf(notification.contentIntent).savedIntent

        assertEquals(MainActivity::class.java.name, openContact.component?.className)
        assertEquals(org.tinitalk.data.AccountPeerKey(accountId, "anna"), contactPeerFromIntent(openContact))
        assertTrue(openContact.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        val openSummary = Shadows.shadowOf(
            missedSummary(context.getSystemService(NotificationManager::class.java)).contentIntent,
        ).savedIntent
        assertNull(contactPeerFromIntent(openSummary))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun missedNotificationLoadsContactPhotoFromLocalStore() {
        val context = RuntimeEnvironment.getApplication()
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val photo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        var loadedAddress: ContactAddress? = null
        val reader = object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap {
                loadedAddress = address
                return photo
            }
        }
        val notifier = IncomingCallNotifier(
            context,
            ContactPhotoNotificationLoader(reader) { command -> command.run() },
        )
        notifier.syncMissedAccounts(listOf(accountId))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val notification = childFor(context.getSystemService(NotificationManager::class.java), "anna")
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            requireNotNull(BundleCompat.getParcelableArray(notification.extras, Notification.EXTRA_MESSAGES, Bundle::class.java)),
        )
        val sender = requireNotNull(messages.single().senderPerson)
        assertEquals(ContactAddress.of(binding.serverUrl, "anna"), loadedAddress)
        assertEquals(Notification.MessagingStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Anna", sender.name)
        assertNotNull(sender.icon)
        val renderedIcon = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        requireNotNull(sender.icon?.loadDrawable(context)).apply {
            setBounds(0, 0, renderedIcon.width, renderedIcon.height)
            draw(Canvas(renderedIcon))
        }
        assertEquals(Color.RED, renderedIcon.getPixel(32, 32))
        assertNull(notification.getLargeIcon())
        assertTrue(notification.shortcutId.startsWith("missed_"))
        assertEquals(R.drawable.ic_call_missed, notification.smallIcon.resId)
    }

    @Test
    fun missedNotificationWithoutPhotoUsesBrandedPersonPlaceholder() {
        val context = RuntimeEnvironment.getApplication()
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val reader = object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        }
        val notifier = IncomingCallNotifier(
            context,
            ContactPhotoNotificationLoader(reader) { command -> command.run() },
        )
        notifier.syncMissedAccounts(listOf(accountId))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val notification = childFor(context.getSystemService(NotificationManager::class.java), "anna")
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            requireNotNull(BundleCompat.getParcelableArray(notification.extras, Notification.EXTRA_MESSAGES, Bundle::class.java)),
        )

        assertEquals(Notification.MessagingStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNotNull(messages.single().senderPerson?.icon)
        assertNull(notification.getLargeIcon())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun missedContactPlaceholderUsesRoundedBrandColors() {
        val placeholder = missedContactPlaceholder(RuntimeEnvironment.getApplication(), size = 128)

        assertEquals(0, Color.alpha(placeholder.getPixel(0, 0)))
        assertEquals(Color.rgb(0x0F, 0x17, 0x2A), placeholder.getPixel(64, 4))
        assertEquals(Color.rgb(0xD4, 0xAF, 0x37), placeholder.getPixel(64, 43))
    }

    @Test
    fun staleMissedPhotoDoesNotReplaceTheNewestCaller() {
        val context = RuntimeEnvironment.getApplication()
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val queued = mutableListOf<Runnable>()
        val reader = object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap =
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val notifier = IncomingCallNotifier(
            context,
            ContactPhotoNotificationLoader(reader) { command -> queued += command },
        )
        notifier.syncMissedAccounts(listOf(accountId))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 300, "Ira"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        assertEquals(2, queued.size)
        queued.first().run()
        val manager = context.getSystemService(NotificationManager::class.java)
        val afterStaleLoad = childFor(manager, "ira")
        assertNull(afterStaleLoad.getLargeIcon())
        assertEquals(1, missedChildren(manager).size)

        queued.last().run()
        val afterCurrentLoad = childFor(manager, "ira")
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            requireNotNull(BundleCompat.getParcelableArray(afterCurrentLoad.extras, Notification.EXTRA_MESSAGES, Bundle::class.java)),
        )
        val sender = requireNotNull(messages.single().senderPerson)
        assertEquals("Ira", sender.name)
        assertNotNull(sender.icon)
        assertNull(afterCurrentLoad.getLargeIcon())
    }

    @Test
    fun eachMissedContactUsesItsOwnPinnedAccountForRedial() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val newestAccount = AccountId("newest-account")
        val olderAccount = AccountId("older-account")
        val newestBinding = CallSessionBinding("https://new.example", "new", "new-session", "new-config")
        val olderBinding = CallSessionBinding("https://old.example", "old", "old-session", "old-config")
        notifier.syncMissedAccounts(listOf(newestAccount, olderAccount))

        notifier.updateAccountMissedState(
            newestAccount,
            CallUnreadState(
                2,
                listOf(
                    UnreadMissedContact("new-peer-older", 50, "Старый на новом сервере"),
                    UnreadMissedContact("new-peer", 200, "Новый"),
                ),
            ),
            notifier.beginAccountMissedCountRefresh(newestAccount),
            redialBinding = newestBinding,
            immediate = true,
        )
        notifier.updateAccountMissedState(
            olderAccount,
            CallUnreadState(1, listOf(UnreadMissedContact("old-peer", 100, "Старый"))),
            notifier.beginAccountMissedCountRefresh(olderAccount),
            redialBinding = olderBinding,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val newPeer = childFor(manager, "new-peer")
        val oldPeer = childFor(manager, "old-peer")
        val newRedial = Shadows.shadowOf(newPeer.actions.single().actionIntent).savedIntent
        val oldRedial = Shadows.shadowOf(oldPeer.actions.single().actionIntent).savedIntent

        assertEquals(3, missedChildren(manager).size)
        assertEquals(R.drawable.ic_call_missed, newPeer.smallIcon.resId)
        assertEquals("new-session", newRedial.getStringExtra("redial_session_id"))
        assertEquals("old-session", oldRedial.getStringExtra("redial_session_id"))
    }

    @Test
    fun inviteBackedMissedRedialCarriesItsSessionBinding() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val missed = invite("missed-call").copy(callerLogin = "anna")
        notifier.syncMissedAccounts(listOf(accountId))
        val refreshId = notifier.beginAccountMissedCountRefresh(accountId)

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            refreshId,
            latest = missed,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = childFor(manager, "anna")
        val firstRedial = Shadows.shadowOf(notification.actions.single().actionIntent)
        val redialIntent = firstRedial.savedIntent

        assertEquals(missed.sessionBinding.serverUrl, redialIntent.getStringExtra("redial_server_url"))
        assertEquals(missed.sessionBinding.login, redialIntent.getStringExtra("redial_session_login"))
        assertEquals(missed.sessionBinding.sessionId, redialIntent.getStringExtra("redial_session_id"))
        assertEquals(missed.sessionBinding.configId, redialIntent.getStringExtra("redial_config_id"))
        assertTrue(redialIntent.data.toString().contains("session-a"))

        val replacement = missed.copy(
            sessionBinding = missed.sessionBinding.copy(sessionId = "replacement-session"),
        )
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            notifier.beginAccountMissedCountRefresh(accountId),
            latest = replacement,
            immediate = true,
        )
        val replacementRedial = Shadows.shadowOf(
            childFor(manager, "anna").actions.single().actionIntent,
        )

        assertNotEquals(firstRedial.requestCode, replacementRedial.requestCode)
        assertEquals("replacement-session", replacementRedial.savedIntent.getStringExtra("redial_session_id"))
    }

    @Test
    fun authoritativeMissedStateRemovesOnlyTheContactThatWasRead() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        notifier.syncMissedAccounts(listOf(accountId))
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(
                3,
                listOf(
                    UnreadMissedContact("anna", 200, "Анна", 2),
                    UnreadMissedContact("ira", 100, "Ира", 1),
                ),
            ),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 100, "Ира", 1))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, missedChildren(manager).size)
        assertEquals("ira", Shadows.shadowOf(missedChildren(manager).single().notification.actions.single().actionIntent)
            .savedIntent.getStringExtra("outgoing_login"))
        assertEquals(1, missedSummary(manager).number)
    }

    @Test
    fun zeroMissedCountRemovesChildrenAndSummary() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(accountId))
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
            immediate = true,
        )

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(0, emptyList()),
            notifier.beginAccountMissedCountRefresh(accountId),
            immediate = true,
        )

        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none {
            it.notification.category == Notification.CATEGORY_MISSED_CALL
        })
    }

    @Test
    fun sameLoginOnTwoAccountsKeepsTwoCorrectRedialTargets() {
        val context = RuntimeEnvironment.getApplication()
        val first = AccountId("account-first")
        val second = AccountId("account-second")
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(first, second))
        notifier.updateAccountMissedState(
            first,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 100, "Анна"))),
            notifier.beginAccountMissedCountRefresh(first),
            redialBinding = CallSessionBinding("https://a.example", "first", "session-first", "config-first"),
            immediate = true,
        )
        notifier.updateAccountMissedState(
            second,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            notifier.beginAccountMissedCountRefresh(second),
            redialBinding = CallSessionBinding("https://a.example", "second", "session-second", "config-second"),
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val sessions = missedChildren(manager).map { active ->
            Shadows.shadowOf(active.notification.actions.single().actionIntent)
                .savedIntent.getStringExtra("redial_session_id")
        }.toSet()
        val openContacts = missedChildren(manager).map { active ->
            val intent = Shadows.shadowOf(active.notification.contentIntent).savedIntent
            requireNotNull(contactPeerFromIntent(intent)) to intent.data
        }

        assertEquals(2, missedChildren(manager).size)
        assertEquals(setOf("session-first", "session-second"), sessions)
        assertEquals(
            setOf(AccountPeerKey(first, "anna"), AccountPeerKey(second, "anna")),
            openContacts.map { it.first }.toSet(),
        )
        assertEquals(2, openContacts.map { it.second }.toSet().size)
        assertEquals(2, missedSummary(manager).number)
    }

    @Test
    fun syncingActiveAccountsRemovesOnlyChildrenOfRemovedAccount() {
        val context = RuntimeEnvironment.getApplication()
        val first = AccountId("remove-first")
        val second = AccountId("keep-second")
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(first, second))
        notifier.updateAccountMissedState(
            first,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 100, "Анна"))),
            notifier.beginAccountMissedCountRefresh(first),
            redialBinding = CallSessionBinding("https://a.example", "first", "session-first", "config-first"),
            immediate = true,
        )
        notifier.updateAccountMissedState(
            second,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 200, "Ира"))),
            notifier.beginAccountMissedCountRefresh(second),
            redialBinding = CallSessionBinding("https://b.example", "second", "session-second", "config-second"),
            immediate = true,
        )

        notifier.syncMissedAccounts(listOf(second))

        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 2_000L
        while (
            (missedChildren(manager).size != 1 || missedSummary(manager).number != 1) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        val remaining = missedChildren(manager).single().notification
        val redial = Shadows.shadowOf(remaining.actions.single().actionIntent).savedIntent
        assertEquals("ira", redial.getStringExtra("outgoing_login"))
        assertEquals(1, missedSummary(manager).number)
    }

    @Test
    fun oldServerCountIsInferredWhenOnlyOneContactIsUnread() {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountId("old-server-account")
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(account))

        notifier.updateAccountMissedState(
            account,
            CallUnreadState(4, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            notifier.beginAccountMissedCountRefresh(account),
            redialBinding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
            immediate = true,
        )

        val child = childFor(context.getSystemService(NotificationManager::class.java), "anna")
        assertEquals(4, child.number)
        assertEquals("4 пропущенных вызова", child.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun twoOptimisticMissedCallersNeverProduceSummaryCountOfOne() {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountId("optimistic-account")
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(account))
        val anna = IncomingInvite(
            account,
            binding,
            "anna-call",
            "Анна",
            Instant.now().plusSeconds(30),
            callerLogin = "anna",
        )
        val ira = anna.copy(callId = "ira-call", caller = "Ира", callerLogin = "ira")

        notifier.showAccountMissedIfAbsent(account, anna)
        notifier.showAccountMissedIfAbsent(account, ira)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, missedChildren(manager).size)
        assertEquals(2, missedSummary(manager).number)
    }

    @Test
    fun fullScreenIntentAndAlertingChannelMatchTheSelectedPresentation() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val invite = invite("call-presentation")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val locked = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.FullScreen)!!
        val headsUp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.HeadsUp)!!
        val inApp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.InApp)!!
        val manager = context.getSystemService(NotificationManager::class.java)

        assertNotNull(locked.fullScreenIntent)
        assertNull(headsUp.fullScreenIntent)
        assertNull(inApp.fullScreenIntent)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(headsUp.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(inApp.channelId).importance)
        incoming.finishTerminalPresentation(context, invite.owner) {}
        notifier.cancel()
    }

    @Test
    fun callStyleShowsCallerWithIncomingStatusText() {
        val context = RuntimeEnvironment.getApplication()
        val invite = invite("call-text")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val notification = IncomingCallNotifier(context).buildIncomingNotification(invite)!!
        val caller = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

        assertEquals("Alice", caller?.name)
        assertEquals("Входящий звонок", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        incoming.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun incomingNotificationUsesPhotoForCallPersonAndLargeIcon() {
        val context = RuntimeEnvironment.getApplication()
        val invite = invite("call-photo").copy(callerLogin = "alice")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val notification = IncomingCallNotifier(context).buildIncomingNotification(
            invite,
            IncomingCallPresentationMode.FullScreen,
            bitmap,
        )!!
        val caller = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

        assertNotNull(caller?.icon)
        assertNotNull(notification.getLargeIcon())
        assertEquals(R.drawable.ic_call_ringing, notification.smallIcon.resId)
        assertEquals(Notification.BADGE_ICON_NONE, notification.badgeIconType)
        incoming.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun incomingNotificationPhotoUsesRoundedCorners() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }

        val rounded = roundedNotificationPhoto(bitmap)

        assertEquals(0, Color.alpha(rounded.getPixel(0, 0)))
        assertTrue(Color.alpha(rounded.getPixel(1, 5)) > 200)
        assertTrue(Color.alpha(rounded.getPixel(16, 16)) > 200)
    }

    @Test
    fun appAcceptsIncomingCallWithoutWaitingForTelecom() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(
            { TelecomCallController(SilentAnswerTelecomRegistrar()) },
            CallAdmissionHandoff(CallAdmission()),
        )
        val invite = invite("call-without-telecom")
        controller.admitIncoming(context, invite)

        controller.answer(context, invite)

        assertEquals(IncomingCallController.ActionAnswer, controller.load(context)?.action)
    }

    @Suppress("DEPRECATION")
    @Test
    fun answerActionOpensCallScreenDirectly() {
        val context = RuntimeEnvironment.getApplication()
        val invite = invite("call-1")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val notification = IncomingCallNotifier(context).buildIncomingNotification(invite)!!
        val answer = notification.actions
            .map { it.actionIntent }
            .first { Shadows.shadowOf(it).savedIntent.action == IncomingCallController.ActionAnswer }
        val shadowAnswer = Shadows.shadowOf(answer)
        val answerIntent = shadowAnswer.savedIntent

        assertTrue(shadowAnswer.isActivityIntent)
        assertEquals(CallActivity::class.java.name, answerIntent.component?.className)
        assertEquals(IncomingCallController.ActionAnswer, answerIntent.action)
        incoming.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun answerActionCanOnlyBeClaimedOnce() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-once")
        controller.admitIncoming(context, invite)

        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))
        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))

        val expired = invite.copy(callId = "call-expired", expiresAt = Instant.now().minusSeconds(1))
        controller.finishTerminalPresentation(context, invite.owner) {}
        controller.save(context, expired)
        assertEquals(IncomingAnswerClaim.Invalid, controller.claimAnswer(context, expired))
    }

    @Test
    fun repeatedPresentationDoesNotLoseClaimedAnswer() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-claimed")
        controller.admitIncoming(context, invite)
        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))

        assertTrue(controller.presentSavedIncoming(context, invite) {})

        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))
    }

    @Test
    fun systemDisconnectImmediatelyPreventsIncomingCallReplay() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-rejected-by-system")
        controller.admitIncoming(context, invite)

        controller.disconnectFromTelecom(context, invite)

        assertTrue(controller.isTerminal(context, invite.owner))
        assertEquals(null, controller.load(context))
        assertEquals(null, IncomingCallNotifier(context).buildIncomingNotification(invite))
    }

    @Test
    fun staleTerminalEventDoesNotDismissANewerIncomingCall() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val current = invite("new-call", "Bob")
        controller.admitIncoming(context, current)
        var cancelled = false

        val finished = controller.finishTerminalPresentation(
            context,
            invite("old-call").owner,
        ) {
            cancelled = true
        }

        assertEquals(false, finished)
        assertEquals(false, cancelled)
        assertEquals(current.callId, controller.load(context)?.invite?.callId)
    }

    private class SilentAnswerTelecomRegistrar : TelecomRegistrar {
        override fun register(capabilities: TelecomCapabilities) = Unit
        override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) = Unit
        override fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks) = Unit
        override fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit) = Unit
        override fun reject(key: AccountCallKey) = Unit
        override fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit) = onResult(false)
        override fun selectEndpoint(key: AccountCallKey, endpointId: String) = Unit
        override fun cancel(key: AccountCallKey) = Unit
    }
}
