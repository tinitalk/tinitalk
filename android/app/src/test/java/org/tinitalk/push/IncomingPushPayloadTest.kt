package org.tinitalk.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IncomingPushPayloadTest {
    @Test
    fun acceptsFreshIncomingCallPayload() {
        val invite = IncomingPushPayload.parse(
            mapOf(
                "type" to "incoming_call",
                "call_id" to "call-1",
                "caller" to "Alice",
                "expires_at" to "2026-08-26T10:00:30Z",
            ),
            now = Instant.parse("2026-08-26T10:00:00Z"),
        )

        assertEquals("call-1", invite?.callId)
        assertEquals("Alice", invite?.caller)
    }

    @Test
    fun ignoresExpiredOrUnknownPayload() {
        assertNull(IncomingPushPayload.parse(mapOf("type" to "other"), Instant.parse("2026-08-26T10:00:00Z")))
        assertNull(
            IncomingPushPayload.parse(
                mapOf("type" to "incoming_call", "call_id" to "call-1", "expires_at" to "2026-08-26T09:59:59Z"),
                Instant.parse("2026-08-26T10:00:00Z"),
            ),
        )
    }

    @Test
    fun detectsCancelPayload() {
        assertEquals(PushAction.Cancel, IncomingPushPayload.action(mapOf("type" to "call_cancel")))
    }
}
