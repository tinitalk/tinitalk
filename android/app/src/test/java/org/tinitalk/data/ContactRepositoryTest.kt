package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRepositoryTest {
    @Test
    fun verifiesCredentialsBeforeSavingAndFiltersSelf() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val api = FakeApiClient(
            profile = Profile("alice", "Alice"),
            contacts = listOf(Contact("alice", "Alice"), Contact("bob", "Bob")),
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        val contacts = repo.signIn("https://host", "alice", "token")

        assertEquals(listOf(Contact("bob", "Bob")), contacts)
        assertEquals(Session("https://host", "alice", "token"), store.load())
    }

    @Test
    fun sortsContactsByDisplayNameInsteadOfLogin() {
        val repo = ContactRepository(AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())) { _, _, _ ->
            FakeApiClient(
                profile = Profile("self", "Я"),
                contacts = listOf(
                    Contact("anna", "Яна"),
                    Contact("maria", "мария"),
                    Contact("zoe", "Анна"),
                ),
            )
        }

        val contacts = repo.signIn("https://host", "self", "token")

        assertEquals(listOf("Анна", "мария", "Яна"), contacts.map(Contact::displayName))
    }

    @Test
    fun trimsManualCredentialsBeforeAuthenticatingAndSaving() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        var captured: Session? = null
        val repo = ContactRepository(store) { url, login, token ->
            captured = Session(url, login, token)
            FakeApiClient(profile = Profile("alex", "Alex"))
        }

        repo.signIn(" https://tinitalk.example.com/ ", " alex ", " token\n")

        val expected = Session("https://tinitalk.example.com", "alex", "token")
        assertEquals(expected, captured)
        assertEquals(expected, store.load())
    }

    @Test
    fun invalidTokenClearsSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "old"))
        val repo = ContactRepository(store) { _, _, _ -> FakeApiClient(error = ApiException(401, "unauthorized")) }

        val result = runCatching { repo.signIn("https://host", "alice", "bad") }

        assertEquals(true, result.isFailure)
        assertNull(store.load())
    }

    @Test
    fun signOutClearsSavedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val repo = ContactRepository(store) { _, _, _ -> FakeApiClient() }

        repo.signOut()

        assertNull(store.load())
    }

    @Test
    fun loadsCallHistoryUsingSavedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val expected = CallHistoryPage(
            items = listOf(CallHistoryItem(7, "bob", "Bob", "outgoing", "completed", 1787740200, 65)),
            nextBefore = 5,
            latestId = 7,
            unreadMissedCount = 1,
        )
        val api = FakeApiClient(callHistory = expected)
        val repo = ContactRepository(store) { _, _, _ -> api }

        assertEquals(expected, repo.loadCallHistory(peerLogin = "bob"))
        assertEquals("bob", api.requestedPeer)
    }

    @Test
    fun updatesContactAndMarksOnlyItsHistoryRead() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val updated = Contact("bob", "Мама", "Bob", "Мама")
        val api = FakeApiClient(updatedContact = updated, unreadAfterRead = 2)
        val repo = ContactRepository(store) { _, _, _ -> api }

        assertEquals(updated, repo.updateContactName("bob", "Мама"))
        assertEquals(2, repo.markCallHistoryRead(42, peerLogin = "bob"))
        assertEquals("bob", api.updatedLogin)
        assertEquals("Мама", api.updatedName)
        assertEquals("bob", api.readPeer)
    }
}

private class FakeApiClient(
    private val profile: Profile = Profile("alice", "Alice"),
    private val contacts: List<Contact> = emptyList(),
    private val callHistory: CallHistoryPage = CallHistoryPage(emptyList(), 0, 0, 0),
    private val updatedContact: Contact = Contact("bob", "Bob"),
    private val unreadAfterRead: Int = 0,
    private val error: RuntimeException? = null,
) : HouseholdApi {
    var requestedPeer: String? = null
    var updatedLogin: String? = null
    var updatedName: String? = null
    var readPeer: String? = null

    override fun me(): Profile {
        error?.let { throw it }
        return profile
    }

    override fun contacts(): List<Contact> {
        error?.let { throw it }
        return contacts
    }

    override fun updateContactName(login: String, customName: String?): Contact {
        updatedLogin = login
        updatedName = customName
        return updatedContact
    }

    override fun calls(limit: Int, before: Long, peerLogin: String?): CallHistoryPage {
        requestedPeer = peerLogin
        return callHistory
    }

    override fun markCallsRead(throughId: Long, peerLogin: String?): Int {
        readPeer = peerLogin
        return unreadAfterRead
    }

    override fun putDevice(deviceId: String, fcmToken: String) = Unit
}
