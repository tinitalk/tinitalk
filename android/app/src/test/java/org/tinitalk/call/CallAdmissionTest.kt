package org.tinitalk.call

import org.tinitalk.data.AccountId
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAdmissionTest {
    @Test
    fun concurrentAccountsAdmitExactlyOneCompositeCall() {
        val admission = CallAdmission()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val keys = listOf(
            AccountCallKey(AccountId("account-a"), "same-call"),
            AccountCallKey(AccountId("account-b"), "same-call"),
        )

        val attempts = keys.map { key ->
            executor.submit<CallAdmissionAttempt> {
                ready.countDown()
                start.await()
                admission.reserve(owner(key))
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is CallAdmissionAttempt.Acquired })
        assertEquals(1, results.count { it is CallAdmissionAttempt.Busy })
        assertTrue(admission.current()?.owner?.key in keys)
    }

    @Test
    fun duplicateKeyIsIdempotentAndStaleLeaseCannotReleaseOrRekeyNewOwner() {
        val admission = CallAdmission()
        val original = AccountCallKey(AccountId("account-a"), "call-1")
        val replacement = AccountCallKey(AccountId("account-b"), "call-1")
        val canonical = AccountCallKey(AccountId("account-b"), "call-2")

        val first = (admission.reserve(owner(original)) as CallAdmissionAttempt.Acquired).lease
        val duplicate = (admission.reserve(owner(original)) as CallAdmissionAttempt.Existing).lease
        assertEquals(first, duplicate)
        assertTrue(admission.release(first))

        val second = (admission.reserve(owner(replacement)) as CallAdmissionAttempt.Acquired).lease
        assertFalse(admission.release(first))
        assertNull(admission.rekey(first, canonical))
        assertEquals(replacement, admission.current()?.owner?.key)

        val rekeyed = admission.rekey(second, canonical)
        assertNotNull(rekeyed)
        assertEquals(canonical, admission.current()?.owner?.key)
    }

    @Test
    fun duplicateAfterTakeCannotRestageOrReleaseTheRunningCall() {
        val handoff = CallAdmissionHandoff(CallAdmission())
        val current = owner(AccountCallKey(AccountId("account-a"), "call-1"))
        assertTrue(handoff.stage(current) is CallAdmissionAttempt.Acquired)
        val running = requireNotNull(handoff.take(current))

        assertTrue(handoff.stage(current) is CallAdmissionAttempt.Existing)
        assertFalse(handoff.releaseStaged(current))
        assertEquals(CallAdmissionState.Running, handoff.current()?.state)
        assertEquals(current, handoff.current()?.owner)

        assertTrue(handoff.release(running))
        assertNull(handoff.current())
    }

    @Test
    fun staleSameAccountRemovalBindingDoesNotMatchReplacementOwner() {
        val accountId = AccountId("account-a")
        val oldBinding = binding("account-a", "session-old")
        val newBinding = binding("account-a", "session-new")
        val replacement = AccountCallOwner(AccountCallKey(accountId, "call-2"), newBinding)

        assertFalse(replacement.matchesRemoval(accountId, oldBinding))
        assertTrue(replacement.matchesRemoval(accountId, newBinding))
    }

    @Test
    fun callSessionResolverPinsTheRequestedAccountAndRejectsAReplacement() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val accountA = auth.upsert(Session("https://a.example", "alice", "token-a", sessionId = "session-a"))
        val accountB = auth.upsert(Session("https://b.example", "bob", "token-b", sessionId = "session-b"))
        val bindingB = CallSessionBinding.from(accountB.session)

        assertEquals(accountB.session, resolvePinnedCallSession(auth, accountB.id, bindingB))
        assertFalse(resolvePinnedCallSession(auth, accountA.id, bindingB) == accountA.session)

        val replacementB = accountB.session.copy(token = "new-token", sessionId = "new-session")
        assertTrue(auth.saveIfCurrent(accountB.id, accountB.session, replacementB))
        assertNull(resolvePinnedCallSession(auth, accountB.id, bindingB))
    }

    private fun owner(key: AccountCallKey): AccountCallOwner =
        AccountCallOwner(key, binding(key.accountId.value, "session-${key.accountId.value}"))

    private fun binding(account: String, session: String) =
        CallSessionBinding("https://$account.example", account, session, "config-$account")
}
