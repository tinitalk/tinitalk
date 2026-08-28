package org.tinitalk.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientTest {
    @Test
    fun loadsTiniTalkServerIdentityWithoutCredentials() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"service":"tinitalk","status":"ok","api_version":1}""",
            ),
        )
        server.start()
        try {
            val info = UrlConnectionApiClient(server.url("/").toString(), "alice", "secret-token")
                .serverInfo()

            assertEquals(ServerInfo("tinitalk", "ok", 1), info)
            val request = server.takeRequest()
            assertEquals("/healthz", request.path)
            assertEquals(null, request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loadsRequestedContactsPage() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"login":"bob","display_name":"Bob"}],"next_cursor":"next-page"}""",
            ),
        )
        server.start()
        try {
            val page = UrlConnectionApiClient(server.url("/").toString(), "alice", "token")
                .contactsPage(limit = 20, cursor = "current-page")

            assertEquals(listOf(Contact("bob", "Bob", "Bob", null)), page.items)
            assertEquals("next-page", page.nextCursor)
            assertEquals("/api/contacts/page?limit=20&cursor=current-page", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

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
                    "reached":true,
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
            val page = UrlConnectionApiClient(server.url("/").toString(), "bob", "token").calls(peerLogin = "alice")

            assertEquals(1, page.items.size)
            assertEquals(
                CallHistoryItem(7, "alice", "Alice", "incoming", "cancelled_after_ringing", true, 1787740200, 0),
                page.items.single(),
            )
            assertEquals(5, page.nextBefore)
            assertEquals(7, page.latestId)
            assertEquals(1, page.unreadMissedCount)
            val request = server.takeRequest()
            assertEquals("/api/calls?limit=50&before=0&peer=alice", request.path)
            assertEquals("Basic Ym9iOnRva2Vu", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun marksCallHistoryReadThroughRequestedItem() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"unread_missed_count":3}"""))
        server.start()
        try {
            val unread = UrlConnectionApiClient(server.url("/").toString(), "bob", "token")
                .markCallsRead(42, peerLogin = "alice")

            val request = server.takeRequest()
            assertEquals(3, unread)
            assertEquals("PUT", request.method)
            assertEquals("/api/calls/read", request.path)
            assertEquals("{\"through_id\":42,\"peer_login\":\"alice\"}", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun updatesAndResetsPersonalContactName() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"login":"bob","display_name":"Мама","default_display_name":"Bob","custom_name":"Мама"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"login":"bob","display_name":"Bob","default_display_name":"Bob","custom_name":null}""",
            ),
        )
        server.start()
        try {
            val api = UrlConnectionApiClient(server.url("/").toString(), "alice", "token")

            val renamed = api.updateContactName("bob", "Мама")
            val renameRequest = server.takeRequest()
            val reset = api.updateContactName("bob", null)
            val resetRequest = server.takeRequest()

            assertEquals(Contact("bob", "Мама", "Bob", "Мама"), renamed)
            assertEquals("/api/contacts/bob/name", renameRequest.path)
            assertEquals("{\"custom_name\":\"Мама\"}", renameRequest.body.readUtf8())
            assertEquals(Contact("bob", "Bob", "Bob", null), reset)
            assertEquals("{\"custom_name\":null}", resetRequest.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
