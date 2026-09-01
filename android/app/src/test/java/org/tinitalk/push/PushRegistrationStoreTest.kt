package org.tinitalk.push

import org.tinitalk.data.AccountId
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRegistrationStoreTest {
    @Test
    fun newerEndpointWinsAndCannotBeClearedByStaleWorker() {
        val persistence = MemoryKeyValueStore()
        val accounts = AuthStore(persistence, PrefixTokenCipher())
        val accountId = AccountId("account-a")
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            sessionId = "session-a",
            configId = "config-a",
        )
        accounts.add(
            accountId,
            session,
            StoredWebPushConfig("https://a.example", "vapid-a", "config-a"),
            "Alice",
        )
        val store = PushRegistrationStore(persistence)

        val first = store.upsert(accountId, session, "device", subscription("first"))
        val second = store.upsert(accountId, session, "device", subscription("second"))

        assertEquals(1L, first.generation)
        assertEquals(2L, second.generation)
        assertEquals(PushRegistrationClearResult.STALE, store.clearIfGeneration(accountId, first.generation))
        assertEquals(second, store.load(accountId))
        assertEquals(PushRegistrationClearResult.CLEARED, store.clearIfGeneration(accountId, second.generation))
        assertNull(store.load(accountId))
    }

    private fun subscription(token: String) = WebPushSubscription(
        endpoint = "https://fcm.googleapis.com/fcm/send/$token",
        keys = WebPushKeys("p256dh", "auth"),
    )
}
