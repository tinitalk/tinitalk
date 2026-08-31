package org.tinitalk.data

import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushKeys
import org.tinitalk.push.WebPushSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRepositoryTest {
    @Test
    fun firstLoginClaimsSessionWithWebPushBeforeSavingAccount() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("alice", "config-a")
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        val contacts = repository.signIn("https://a.example/", "alice", "token-a", "phone")

        assertEquals(listOf("bob"), contacts.items.map { it.login })
        assertEquals(listOf(AccountId("account-a")), registration.subscribed)
        assertEquals(subscription, api.claimedSubscription)
        assertEquals("phone", api.claimedDeviceId)
        assertEquals("session-alice", auth.list().single().session.sessionId)
        assertEquals("https://a.example", auth.webPushConfig(AccountId("account-a"))?.serverUrl)
    }

    @Test
    fun addingSecondServerKeepsFirstAccountAndUsesAnotherPushInstance() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val firstSession = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val firstId = auth.newAccountId()
        auth.add(firstId, firstSession, storedConfig(firstSession), "Alice")
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("bob", "config-b")
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        val added = repository.addAccount("https://b.example", "bob", "token-b", "phone")

        assertEquals(listOf(firstId, added.account.id), auth.list().map { it.id })
        assertEquals(listOf(added.account.id), registration.subscribed)
        assertEquals("https://b.example", auth.webPushConfig(added.account.id)?.serverUrl)
    }

    @Test
    fun serverWithoutWebPushIsRejectedBeforeRegistration() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = Session("https://a.example", "alice", "token", setOf("webpush_v1"), "s-a", "config-a")
        auth.add(auth.newAccountId(), first, storedConfig(first), "Alice")
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("bob", "config-b", features = emptySet())
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertThrows(ServerCompatibilityException::class.java) {
            repository.addAccount("https://b.example", "bob", "token-b", "phone")
        }
        assertTrue(registration.subscribed.isEmpty())
    }
}

private val subscription = WebPushSubscription(
    "https://fcm.googleapis.com/fcm/send/test",
    WebPushKeys("p256dh", "auth"),
)

private class RecordingWebPushRegistration : AccountWebPushRegistration {
    val subscribed = mutableListOf<AccountId>()

    override fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription {
        subscribed += accountId
        return subscription
    }

    override fun restore(accountId: AccountId, config: StoredWebPushConfig) = Unit
    override fun unsubscribe(accountId: AccountId) = Unit
}

private class RecordingApi(
    private val login: String,
    private val configId: String,
    private val features: Set<String> = setOf("webpush_v1"),
) : HouseholdApi {
    var claimedDeviceId: String? = null
    var claimedSubscription: WebPushSubscription? = null

    override fun serverInfo() = ServerInfo("tinitalk", "ok", 3, features = features)
    override fun webPushConfig() = WebPushClientConfig(
        "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
        configId,
    )
    override fun me() = Profile(login, login.replaceFirstChar(Char::uppercase))
    override fun contactsPage(limit: Int, cursor: String) = ContactPage(
        listOf(Contact(login, login), Contact("bob", "Bob")),
        "",
    )
    override fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String {
        claimedDeviceId = deviceId
        claimedSubscription = subscription
        return "session-$login"
    }
    override fun updateContactName(login: String, customName: String?) = Contact(login, customName.orEmpty())
    override fun calls(limit: Int, before: Long, peerLogin: String?) = CallHistoryPage(emptyList(), 0, 0, 0)
    override fun markCallsRead(throughId: Long, peerLogin: String?) = CallUnreadState(0, emptyList())
    override fun putDevice(deviceId: String, subscription: WebPushSubscription, configId: String) = Unit
}

private fun storedConfig(session: Session) = StoredWebPushConfig(
    session.url,
    "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
    requireNotNull(session.configId),
)
