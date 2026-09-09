package org.tinitalk.call

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallReplyResultTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = CallReplyResultStore(context)

    @After
    fun clear() = store.clear()

    @Test
    fun persistsKnownReplyAcrossStoreRecreationAndUiReset() {
        val result = CallReplyResult(
            key = AccountCallKey(AccountId("account-a"), "call-a"),
            peer = CallPeer("Bob", "bob"),
            code = CallReplyCode.CannotTalk,
        )
        store.save(result)
        CallUiStateStore.reset()

        assertEquals(result, CallReplyResultStore(context).load())
    }

    @Test
    fun acceptsOnlyKnownReplyForMatchingOutgoingRejectedCall() {
        val key = AccountCallKey(AccountId("account-a"), "call-a")
        val state = CallUiState(
            accountId = key.accountId,
            callId = key.callId,
            peer = CallPeer("Bob", "bob"),
            direction = CallDirection.Outgoing,
            phase = CallPhase.Ringing,
        )

        assertEquals(
            CallReplyResult(key, requireNotNull(state.peer), CallReplyCode.WillCallBack),
            callReplyResult(state, "call.reject", key, "will_call_back"),
        )
        assertNull(callReplyResult(state, "call.reject", AccountCallKey(AccountId("account-b"), "call-a"), "will_call_back"))
        assertNull(callReplyResult(state, "call.end", key, "will_call_back"))
        assertNull(callReplyResult(state, "call.reject", key, "future_code"))
        assertNull(callReplyResult(state.copy(direction = CallDirection.Incoming), "call.reject", key, "will_call_back"))
    }

    @Test
    fun newCallClearsOnlyAnOlderResult() {
        val old = CallReplyResult(
            AccountCallKey(AccountId("account-a"), "old-call"),
            CallPeer("Bob", "bob"),
            CallReplyCode.CallMeLater,
        )
        store.save(old)

        store.clearForNewCall(AccountCallKey(AccountId("account-b"), "new-call"))

        assertNull(store.load())
    }
}
