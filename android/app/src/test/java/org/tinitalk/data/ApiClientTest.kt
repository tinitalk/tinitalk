package org.tinitalk.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientTest {
    @Test
    fun loadsCallHistoryPageFromServerContract() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items":[{
                    "id":7,
                    "peer_login":"alice",
                    "peer_name":"Alice",
                    "direction":"incoming",
                    "outcome":"cancelled_after_ringing",
                    "started_at":1787740200,
                    "duration_seconds":0
                  }],
                  "next_before":5,
                  "latest_id":7,
                  "unread_missed_count":1
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val page = UrlConnectionApiClient(server.url("/").toString(), "bob", "token").calls()

            assertEquals(1, page.items.size)
            assertEquals(CallHistoryItem(7, "alice", "Alice", "incoming", "cancelled_after_ringing", 1787740200, 0), page.items.single())
            assertEquals(5, page.nextBefore)
            assertEquals(7, page.latestId)
            assertEquals(1, page.unreadMissedCount)
            val request = server.takeRequest()
            assertEquals("/api/calls?limit=50&before=0", request.path)
            assertEquals("Basic Ym9iOnRva2Vu", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun marksCallHistoryReadThroughRequestedItem() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            UrlConnectionApiClient(server.url("/").toString(), "bob", "token").markCallsRead(42)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/calls/read", request.path)
            assertEquals("{\"through_id\":42}", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
