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
}

private class FakeApiClient(
    private val profile: Profile = Profile("alice", "Alice"),
    private val contacts: List<Contact> = emptyList(),
    private val error: RuntimeException? = null,
) : HouseholdApi {
    override fun me(): Profile {
        error?.let { throw it }
        return profile
    }

    override fun contacts(): List<Contact> {
        error?.let { throw it }
        return contacts
    }

    override fun putDevice(deviceId: String, fcmToken: String) = Unit
}
