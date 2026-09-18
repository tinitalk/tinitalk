package org.tinitalk.data

import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushKeys
import org.tinitalk.push.WebPushSubscription
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
    @Test
    fun passwordRequestsNeverFollowRedirects() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
            server.enqueue(MockResponse().setBody("{}"))
            val error = assertThrows(ApiException::class.java) {
                UrlConnectionApiClient(server.url("/").toString(), "", "").login("alice", "secret")
            }
            assertEquals(307, error.code)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun passwordLoginUsesUnauthenticatedJsonContract() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"new-token","password_required":false}"""))
        server.start()
        try {
            val result = UrlConnectionApiClient(
                server.url("/").toString(),
                "constructor-login",
                "constructor-token",
                sessionId = "constructor-session",
            ).login("alice", "  пароль без обрезки  ")

            assertEquals("new-token", result.token)
            assertFalse(result.passwordRequired)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/auth/login", request.path)
            assertEquals(
                "{\"login\":\"alice\",\"password\":\"  пароль без обрезки  \"}",
                request.body.readUtf8(),
            )
            assertEquals(null, request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun temporaryPasswordResponseDoesNotRequireAToken() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"password_required":true}"""))
        server.start()
        try {
            val result = UrlConnectionApiClient(server.url("/").toString(), "", "")
                .login("alice", "1234 5678")

            assertEquals(null, result.token)
            assertTrue(result.passwordRequired)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun passwordSetupSendsCurrentAndNewPasswordWithoutBasicAuth() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"token":"rotated","password_required":false}"""))
        server.start()
        try {
            val result = UrlConnectionApiClient(server.url("/").toString(), "alice", "legacy-token")
                .setPassword("alice", "1234 5678", "  личный пароль длиной 15+  ")

            assertEquals("rotated", result.token)
            val request = server.takeRequest()
            assertEquals("/api/auth/password", request.path)
            assertEquals(
                "{\"login\":\"alice\",\"password\":\"1234 5678\",\"new_password\":\"  личный пароль длиной 15+  \"}",
                request.body.readUtf8(),
            )
            assertEquals(null, request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun logoutIncludesTheSessionBeingEnded() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            UrlConnectionApiClient(server.url("/").toString(), "alice", "current-token", "session-a").logout()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/auth/logout", request.path)
            assertEquals("Basic YWxpY2U6Y3VycmVudC10b2tlbg==", request.getHeader("Authorization"))
            assertEquals("session-a", request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun exposesPasswordErrorCodeAndRetrySeconds() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "31")
                .setBody("""{"error":"password_retry_later","retry_after":30}"""),
        )
        server.start()
        try {
            val error = assertThrows(ApiException::class.java) {
                UrlConnectionApiClient(server.url("/").toString(), "", "").login("alice", "wrong")
            }

            assertEquals(429, error.code)
            assertEquals("password_retry_later", error.errorCode)
            assertEquals(30L, error.retryAfterSeconds)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ignoresAdminNamesReturnedByOlderServers() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"login":"alice","display_name":"ADMIN-ONLY"}"""))
        server.enqueue(MockResponse().setBody("""{"login":"bob","display_name":"ADMIN-ONLY","default_display_name":"ADMIN-ONLY","custom_name":"Папа"}"""))
        server.enqueue(MockResponse().setBody("""{"login":"bob","display_name":"ADMIN-ONLY","default_display_name":"ADMIN-ONLY"}"""))
        server.start()
        try {
            val api = UrlConnectionApiClient(server.url("/").toString(), "alice", "token")
            assertEquals("alice", api.me().displayName)
            assertEquals(Contact("bob", "Папа", "Папа"), api.contact("bob"))
            assertEquals(Contact("bob", "bob"), api.contact("bob"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loadsOptionalPersonalPasswordStateFromProfile() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"login":"alice","password_set":false}"""))
        server.start()
        try {
            val profile = UrlConnectionApiClient(server.url("/").toString(), "alice", "token").me()

            assertEquals(false, profile.passwordSet)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loadsWebPushConfigurationWithBasicCredentialsOnly() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"vapid_public_key":"vapid-key","config_id":"sha256:webpush"}""",
            ),
        )
        server.start()
        try {
            val config = UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "secret-token",
                sessionId = "session-123",
            ).webPushConfig()

            assertEquals(
                WebPushClientConfig(
                    vapidPublicKey = "vapid-key",
                    configId = "sha256:webpush",
                ),
                config,
            )
            val request = server.takeRequest()
            assertEquals("/api/webpush-config", request.path)
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
                """{"service":"tinitalk","status":"ok","api_version":4,"features":["video_1to1","future_feature"]}""",
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

            assertEquals(listOf(Contact("bob", "bob")), page.items)
            assertEquals("next-page", page.nextCursor)
            val request = server.takeRequest()
            assertEquals("/api/contacts/page?limit=20&cursor=current-page", request.path)
            assertEquals("session-123", request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun claimsSessionForExactWebPushContract() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"session_id":"opaque-session"}"""))
        server.start()
        try {
            val sessionId = UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "token",
                sessionId = "session-must-not-be-sent",
            ).claimSession("android-device", subscription("first"), "sha256:webpush")

            val request = server.takeRequest()
            assertEquals("opaque-session", sessionId)
            assertEquals("POST", request.method)
            assertEquals("/api/session", request.path)
            assertEquals(
                "{\"device_id\":\"android-device\",\"webpush_subscription\":{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/first\",\"keys\":{\"p256dh\":\"p256dh\",\"auth\":\"auth\"}},\"config_id\":\"sha256:webpush\"}",
                request.body.readUtf8(),
            )
            assertEquals("Basic YWxpY2U6dG9rZW4=", request.getHeader("Authorization"))
            assertEquals(null, request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun updatesDeviceForExactWebPushContract() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            UrlConnectionApiClient(
                server.url("/").toString(),
                "alice",
                "token",
                sessionId = "session-123",
            ).putDevice("android-device", subscription("refreshed"), "sha256:webpush")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/device", request.path)
            assertEquals(
                "{\"device_id\":\"android-device\",\"webpush_subscription\":{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/refreshed\",\"keys\":{\"p256dh\":\"p256dh\",\"auth\":\"auth\"}},\"config_id\":\"sha256:webpush\"}",
                request.body.readUtf8(),
            )
            assertEquals("Basic YWxpY2U6dG9rZW4=", request.getHeader("Authorization"))
            assertEquals("session-123", request.getHeader("X-TiniTalk-Session-ID"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsNon204SuccessResponsesForDeviceRegistrationOnly() {
        listOf(200, 202).forEach { status ->
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(status))
            server.start()
            try {
                val error = assertThrows(ApiException::class.java) {
                    UrlConnectionApiClient(
                        server.url("/").toString(),
                        "alice",
                        "token",
                        sessionId = "session-123",
                    ).putDevice("android-device", subscription("retry"), "sha256:webpush")
                }
                assertEquals(status, error.code)
            } finally {
                server.shutdown()
            }
        }
    }

    private fun subscription(token: String) = WebPushSubscription(
        endpoint = "https://fcm.googleapis.com/fcm/send/$token",
        keys = WebPushKeys(p256dh = "p256dh", auth = "auth"),
    )

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
                  "unread_missed_count":2,
                  "unread_missed":[{"peer_login":"alice","started_at":1787740200,"peer_name":"Alice","missed_count":2}]
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
            assertEquals(2, page.unreadMissedCount)
            assertEquals(listOf(UnreadMissedContact("alice", 1787740200, "Alice", 2)), page.unreadMissed)
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
    fun updatesPersonalContactName() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"login":"bob","display_name":"Мама","default_display_name":"Bob","custom_name":"Мама"}""",
            ),
        )
        server.start()
        try {
            val api = UrlConnectionApiClient(server.url("/").toString(), "alice", "token")

            val renamed = api.updateContactName("bob", "Мама")
            val renameRequest = server.takeRequest()
            assertEquals(Contact("bob", "Мама", "Мама"), renamed)
            assertEquals("/api/contacts/bob/name", renameRequest.path)
            assertEquals("{\"custom_name\":\"Мама\"}", renameRequest.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
