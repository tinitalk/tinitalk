package org.tinitalk.call

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.CallHistoryItem

class CallReplyCodeTest {
    @Test fun decodesHistoryRepliesAndKeepsOlderHistoryReadable() {
        for ((wire, expected) in listOf(
            "cannot_talk" to CallReplyCode.CannotTalk,
            "call_me_later" to CallReplyCode.CallMeLater,
            "will_call_back" to CallReplyCode.WillCallBack,
            "future_code" to null,
        )) {
            val item = Gson().fromJson("""{"id":1,"peer_login":"bob","peer_name":"Bob","direction":"outgoing","outcome":"rejected","reply_code":"$wire","reached":true,"started_at":1,"duration_seconds":0}""", CallHistoryItem::class.java)
            assertEquals(expected, CallReplyCode.fromWire(item.replyCode))
            assertEquals(wire, item.replyCode)
            assertEquals("rejected", item.outcome)
        }
        val old = Gson().fromJson("""{"id":1,"peer_login":"bob","peer_name":"Bob","direction":"outgoing","outcome":"rejected"}""", CallHistoryItem::class.java)
        assertNull(CallReplyCode.fromWire(old.replyCode))
        assertNull(CallReplyCode.fromWire(""))
    }
}
