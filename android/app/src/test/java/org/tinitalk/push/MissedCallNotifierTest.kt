package org.tinitalk.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.core.os.BundleCompat
import android.service.notification.StatusBarNotification
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.contactPeerFromIntent
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallAdmission
import org.tinitalk.call.CallAdmissionHandoff
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.data.UnreadMissedContact
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

import org.tinitalk.missed.MissedCallsPreferences
import org.tinitalk.missed.MissedCallsRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MissedCallNotifierTest {
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


    private fun createMissedCalls(
        context: android.content.Context,
        photoLoader: ContactPhotoNotificationLoader? = null,
    ): MissedCallsRepository {
        lateinit var renderer: MissedCallNotifier
        val repository = MissedCallsRepository(
            MissedCallsPreferences(context.getSharedPreferences("tinitalk", android.content.Context.MODE_PRIVATE)),
            execute = { it() },
            publish = { renderer.render(it) },
        )
        val loader = photoLoader ?: ContactPhotoNotificationLoader(object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        }) { it.run() }
        renderer = MissedCallNotifier(context, repository, loader)
        return repository
    }

    @Test
    fun serverMissedStateWithoutAnExactSessionOmitsRedial() {
        val context = RuntimeEnvironment.getApplication()
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(accountId))
        val refreshId = missedCalls.beginRefresh(accountId)

        missedCalls.update(
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
        val missedCalls = createMissedCalls(context)
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        missedCalls.syncAccounts(listOf(accountId))

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            missedCalls.beginRefresh(accountId),
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

        missedCalls.update(
            accountId,
            CallUnreadState(3, listOf(UnreadMissedContact("anna", 200, "Анна", 3))),
            missedCalls.beginRefresh(accountId),
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
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(accountId))
        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            missedCalls.beginRefresh(accountId),
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
        val missedCalls = createMissedCalls(
            context,
            ContactPhotoNotificationLoader(reader) { command -> command.run() },
        )
        missedCalls.syncAccounts(listOf(accountId))

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            missedCalls.beginRefresh(accountId),
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
        val missedCalls = createMissedCalls(
            context,
            ContactPhotoNotificationLoader(reader) { command -> command.run() },
        )
        missedCalls.syncAccounts(listOf(accountId))

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            missedCalls.beginRefresh(accountId),
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
        val missedCalls = createMissedCalls(
            context,
            ContactPhotoNotificationLoader(reader) { command -> queued += command },
        )
        missedCalls.syncAccounts(listOf(accountId))

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Anna"))),
            missedCalls.beginRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )
        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 300, "Ira"))),
            missedCalls.beginRefresh(accountId),
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
        val missedCalls = createMissedCalls(context)
        val newestAccount = AccountId("newest-account")
        val olderAccount = AccountId("older-account")
        val newestBinding = CallSessionBinding("https://new.example", "new", "new-session", "new-config")
        val olderBinding = CallSessionBinding("https://old.example", "old", "old-session", "old-config")
        missedCalls.syncAccounts(listOf(newestAccount, olderAccount))

        missedCalls.update(
            newestAccount,
            CallUnreadState(
                2,
                listOf(
                    UnreadMissedContact("new-peer-older", 50, "Старый на новом сервере"),
                    UnreadMissedContact("new-peer", 200, "Новый"),
                ),
            ),
            missedCalls.beginRefresh(newestAccount),
            redialBinding = newestBinding,
            immediate = true,
        )
        missedCalls.update(
            olderAccount,
            CallUnreadState(1, listOf(UnreadMissedContact("old-peer", 100, "Старый"))),
            missedCalls.beginRefresh(olderAccount),
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
        val missedCalls = createMissedCalls(context)
        val missed = invite("missed-call").copy(callerLogin = "anna")
        missedCalls.syncAccounts(listOf(accountId))
        val refreshId = missedCalls.beginRefresh(accountId)

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            refreshId,
            latest = missed.toMissedCall(),
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
        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            missedCalls.beginRefresh(accountId),
            latest = replacement.toMissedCall(),
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
        val missedCalls = createMissedCalls(context)
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        missedCalls.syncAccounts(listOf(accountId))
        missedCalls.update(
            accountId,
            CallUnreadState(
                3,
                listOf(
                    UnreadMissedContact("anna", 200, "Анна", 2),
                    UnreadMissedContact("ira", 100, "Ира", 1),
                ),
            ),
            missedCalls.beginRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 100, "Ира", 1))),
            missedCalls.beginRefresh(accountId),
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
    fun zeroMissedCountRemovesChildrenAndSummaryDespiteLatePhotoLoad() {
        val context = RuntimeEnvironment.getApplication()
        val queued = mutableListOf<Runnable>()
        val reader = object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap =
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        val missedCalls = createMissedCalls(context, ContactPhotoNotificationLoader(reader) { queued += it })
        missedCalls.syncAccounts(listOf(accountId))
        missedCalls.update(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            missedCalls.beginRefresh(accountId),
            redialBinding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
            immediate = true,
        )

        missedCalls.update(
            accountId,
            CallUnreadState(0, emptyList()),
            missedCalls.beginRefresh(accountId),
            immediate = true,
        )

        assertEquals(1, queued.size)
        queued.single().run()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none {
            it.notification.category == Notification.CATEGORY_MISSED_CALL
        })
    }

    @Test
    fun missedRedialRequiresItsAccountIdentity() {
        assertTrue(shouldOfferMissedRedial("sam", true))
        assertFalse(shouldOfferMissedRedial("sam", false))
    }

    @Test
    fun sameLoginOnTwoAccountsKeepsTwoCorrectRedialTargets() {
        val context = RuntimeEnvironment.getApplication()
        val first = AccountId("account-first")
        val second = AccountId("account-second")
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(first, second))
        missedCalls.update(
            first,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 100, "Анна"))),
            missedCalls.beginRefresh(first),
            redialBinding = CallSessionBinding("https://a.example", "first", "session-first", "config-first"),
            immediate = true,
        )
        missedCalls.update(
            second,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            missedCalls.beginRefresh(second),
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
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(first, second))
        missedCalls.update(
            first,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 100, "Анна"))),
            missedCalls.beginRefresh(first),
            redialBinding = CallSessionBinding("https://a.example", "first", "session-first", "config-first"),
            immediate = true,
        )
        missedCalls.update(
            second,
            CallUnreadState(1, listOf(UnreadMissedContact("ira", 200, "Ира"))),
            missedCalls.beginRefresh(second),
            redialBinding = CallSessionBinding("https://b.example", "second", "session-second", "config-second"),
            immediate = true,
        )

        missedCalls.syncAccounts(listOf(second))

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
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(account))

        missedCalls.update(
            account,
            CallUnreadState(4, listOf(UnreadMissedContact("anna", 200, "Анна"))),
            missedCalls.beginRefresh(account),
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
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(account))
        val anna = IncomingInvite(
            account,
            binding,
            "anna-call",
            "Анна",
            Instant.now().plusSeconds(30),
            callerLogin = "anna",
        )
        val ira = anna.copy(callId = "ira-call", caller = "Ира", callerLogin = "ira")

        missedCalls.recordMissedIfAbsent(account, anna.toMissedCall())
        missedCalls.recordMissedIfAbsent(account, ira.toMissedCall())

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, missedChildren(manager).size)
        assertEquals(2, missedSummary(manager).number)
    }

    @Test
    fun missedNotificationRetainsServerStartThroughIncomingHandoffs() {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountRecord(AccountId("missed-start"), Session("https://a.example", "alice", "token"))
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(account.id))
        val incoming = controller()
        val now = Instant.now()
        val started = now.minusSeconds(5)
        for (ttl in listOf(30L, 45L)) {
            val invite = requireNotNull(IncomingPushPayload.parse(mapOf(
                "type" to "incoming_call",
                "call_id" to "start-$ttl",
                "caller_login" to "bob-$ttl",
                "caller" to "bob-$ttl",
                "started_at" to started.toString(),
                "expires_at" to started.plusSeconds(ttl).toString(),
            ), account, now))
            assertTrue(incoming.save(context, invite))
            val restored = requireNotNull(incoming.load(context)).invite
            val action = incoming.actionIntent(context, IncomingCallController.ActionReject, restored)
            val handedOff = requireNotNull(IncomingCallController.inviteFrom(Shadows.shadowOf(action).savedIntent))

            missedCalls.recordMissedIfAbsent(account.id, handedOff.toMissedCall())

            val child = childFor(context.getSystemService(NotificationManager::class.java), "bob-$ttl")
            assertEquals("Wrong start with $ttl-second expiry", started.epochSecond * 1_000, child.`when`)
        }
    }

    @Test
    fun legacyMissedNotificationDoesNotInferStartFromExpiry() {
        val context = RuntimeEnvironment.getApplication()
        val account = AccountRecord(AccountId("legacy-missed-start"), Session("https://a.example", "alice", "token"))
        val missedCalls = createMissedCalls(context)
        missedCalls.syncAccounts(listOf(account.id))
        for (ttl in listOf(30L, 45L)) {
            for (startedAt in listOf(null, "invalid")) {
                val before = Instant.now().epochSecond * 1_000
                val data = mutableMapOf(
                    "type" to "incoming_call",
                    "call_id" to "legacy-$ttl-$startedAt",
                    "caller_login" to "bob-$ttl-$startedAt",
                    "caller" to "bob-$ttl-$startedAt",
                    "expires_at" to Instant.now().plusSeconds(ttl - 5).toString(),
                )
                startedAt?.let { data["started_at"] = it }
                val invite = requireNotNull(IncomingPushPayload.parse(data, account))

                missedCalls.recordMissedIfAbsent(account.id, invite.toMissedCall())

                val child = childFor(context.getSystemService(NotificationManager::class.java), "bob-$ttl-$startedAt")
                val after = Instant.now().epochSecond * 1_000
                assertTrue("Expiry shifted fallback time with $ttl seconds", child.`when` in before..after)
            }
        }
    }

}
