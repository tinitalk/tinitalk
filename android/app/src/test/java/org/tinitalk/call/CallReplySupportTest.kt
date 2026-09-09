package org.tinitalk.call

import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.AccountId
import org.tinitalk.data.ServerInfo

class CallReplySupportTest {
    private fun owner(account: String, call: String = "call") = AccountCallOwner(
        AccountCallKey(AccountId(account), call), CallSessionBinding("https://$account.example", "me", "session", "config"),
    )

    @Test fun supportRequiresHealthConfirmationForTheCurrentCall() {
        val gate = CallReplySupport()
        val first = owner("one")
        val second = owner("two")
        val info = ServerInfo("tinitalk", "ok", 4, features = setOf("call_reply_v1"))
        val firstRequest = gate.begin(first)
        assertFalse(gate.isSupported(first))
        val secondRequest = gate.begin(second)
        assertFalse(gate.complete(firstRequest, info))
        assertFalse(gate.isSupported(first))
        assertFalse(gate.isSupported(second))
        assertTrue(gate.complete(secondRequest, info))
        assertTrue(gate.isSupported(second))
        assertFalse(gate.isSupported(first))
    }

    @Test fun refreshAndUnsupportedOrInvalidHealthHideThePanel() {
        val gate = CallReplySupport()
        val current = owner("one")
        for (info in listOf(
            null, ServerInfo("tinitalk", "ok", 4),
            ServerInfo("other", "ok", 4, features = setOf("call_reply_v1")),
            ServerInfo("tinitalk", "ok", 5, features = setOf("call_reply_v1")),
        )) {
            gate.complete(gate.begin(current), ServerInfo("tinitalk", "ok", 4, features = setOf("call_reply_v1")))
            assertTrue(gate.isSupported(current))
            val request = gate.begin(current)
            assertFalse(gate.isSupported(current))
            gate.complete(request, info)
            assertFalse(gate.isSupported(current))
        }
        val stale = gate.begin(current)
        gate.clear()
        assertFalse(gate.complete(stale, ServerInfo("tinitalk", "ok", 4, features = setOf("call_reply_v1"))))
    }
}
