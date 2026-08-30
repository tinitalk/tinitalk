package org.tinitalk.data

import org.tinitalk.push.FirebaseClientConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientTest {
    @Test
    fun loadsFirebaseConfigurationWithBasicCredentialsOnly() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"application_id":"1:123:android:abc","api_key":"public-api-key","project_id":"demo-project","gcm_sender_id":"123","config_id":"sha256:config"}""",
            ),
        )
        server.start()
        try {
            val config = UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "secret-token",
                sessionId = "session-123",
            ).firebaseConfig()

            assertEquals(
                FirebaseClientConfig(
                    applicationId = "1:123:android:abc",
                    apiKey = "public-api-key",
                    projectId = "demo-project",
                    gcmSenderId = "123",
                    configId = "sha256:config",
                ),
                config,
            )
            val request = server.takeRequest()
            assertEquals("/api/firebase-config", request.path)
            assertEquals("Basic YWxpY2U6c2VjcmV0LXRva2Vu", request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loadsServerFeaturesFromHealthResponse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"service":"tinitalk","status":"ok","api_version":3,"features":["video_1to1","future_feature"]}""",
            ),
        )
        server.start()
        try {
            val info = UrlConnectionApiClient(server.url("/").toString(), "alice", "token").serverInfo()

            assertEquals(setOf("video_1to1", "future_feature"), info.features)
        } finally {
            server.shutdown()
        }
    }

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
            val info = UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "secret-token",
                sessionId = "session-123",
            )
                .serverInfo()

            assertEquals(ServerInfo("tinitalk", "ok", 1), info)
            assertEquals(emptySet<String>(), info.features)
            val request = server.takeRequest()
            assertEquals("/healthz", request.path)
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-TiniTalk-Session-ID"))
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
            val page = UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "token",
                sessionId = "session-123",
            )
                .contactsPage(limit = 20, cursor = "current-page")

            assertEquals(listOf(Contact("bob", "Bob", "Bob", null)), page.items)
            assertEquals("next-page", page.nextCursor)
            val request = server.takeRequest()
            assertEquals("/api/contacts/page?limit=20&cursor=current-page", request.path)
            assertEquals("session-123", request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun claimsSessionForExactDeviceContract() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"session_id":"opaque-session"}"""))
        server.start()
        try {
            val sessionId = UrlConnectionApiClient(server.url("/").toString(), "alice", "token")
                .claimSession("android-device")

            val request = server.takeRequest()
            assertEquals("opaque-session", sessionId)
            assertEquals("POST", request.method)
            assertEquals("/api/session", request.path)
            assertEquals("{\"device_id\":\"android-device\"}", request.body.readUtf8())
            assertEquals("Basic YWxpY2U6dG9rZW4=", request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun exposesSessionReplacementReasonFromUnauthorizedResponse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("X-TiniTalk-Auth-Reason", "session_replaced")
                .setBody("unauthorized"),
        )
        server.start()
        try {
            val error = runCatching {
                UrlConnectionApiClient(
                    server.url("/").toString(),
                    "alice",
                    "token",
                    sessionId = "session-old",
                ).me()
            }.exceptionOrNull() as ApiException

            assertEquals(401, error.code)
            assertEquals("session_replaced", error.authReason)
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
                  "unread_missed_count":1,
                  "unread_missed":[{"peer_login":"alice","started_at":1787740200}]
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
            assertEquals(listOf(UnreadMissedContact("alice", 1787740200)), page.unreadMissed)
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
        server.enqueue(
            MockResponse().setBody(
                """{"unread_missed_count":1,"unread_missed":[{"peer_login":"carol","started_at":1787743800}]}""",
            ),
        )
        server.start()
        try {
            val unread = UrlConnectionApiClient(server.url("/").toString(), "bob", "token")
                .markCallsRead(42, peerLogin = "alice")

            val request = server.takeRequest()
            assertEquals(
                CallUnreadState(1, listOf(UnreadMissedContact("carol", 1787743800))),
                unread,
            )
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
