package org.tinitalk.data

import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushKeys
import org.tinitalk.push.WebPushSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRepositoryTest {
    @Test
    fun lostPasswordResponseRequiresLoginAndDoesNotRetryThePasswordMutation() {
        for (additional in listOf(false, true)) {
            val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val first = if (additional) auth.upsert(Session("https://first.example", "first", "first-token", sessionId = "first-session")) else null
            val account = auth.upsert(Session("https://a.example", "alice", "old-token",
                features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session", passwordSet = false))
            val api = RecordingApi("alice", "config-a", features = account.session.features,
                loginResult = PasswordAuthResult("new-token", false))
            val failure = java.io.IOException("response lost after server saved the password")
            var mutations = 0
            api.beforePassword = { mutations++; throw failure }
            val removed = mutableListOf<AccountId>()
            val repo = ContactRepository(auth, RecordingWebPushRegistration(),
                onAccountRemoved = { id, _ -> removed += id },
                onExplicitAccountRemoved = { _, _ -> error("recovery must not delete personal photos") },
                apiFactory = { _, _, _, _ -> api })

            val error = assertThrows(PasswordSignInRequiredException::class.java) {
                repo.changePassword(account.id, "", "password123", "phone")
            }
            assertEquals(failure, error.cause)
            assertEquals(listOfNotNull(first), auth.list())
            assertEquals(listOf(account.id), removed)
            assertEquals(0, api.logoutRequests)

            if (additional) repo.addAccount("a.example", "alice", "password123", "phone")
            else repo.signIn("a.example", "alice", "password123", "phone")
            assertEquals("alice" to "password123", api.loginCredentials)
            assertEquals(1, mutations)
            assertEquals(account.id, auth.list().single { it.session.login == "alice" }.id)
            assertEquals("new-token", auth.list().single { it.session.login == "alice" }.session.token)
            if (first != null) assertEquals(first, auth.get(first.id))
        }
    }

    @Test
    fun incompletePasswordResponseAlsoRequiresSignIn() {
        for (failure in listOf(
            com.google.gson.JsonSyntaxException("truncated response"),
            IllegalStateException("successful password response without token"),
        )) {
            val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val account = auth.upsert(Session("https://a.example", "alice", "old-token",
                features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session"))
            val api = RecordingApi("alice", "config-a").apply { beforePassword = { throw failure } }
            val repo = ContactRepository(auth, apiFactory = { _, _, _, _ -> api })
            val error = assertThrows(PasswordSignInRequiredException::class.java) {
                repo.changePassword(account.id, "", "new-password", "phone")
            }
            assertEquals(failure, error.cause)
            assertTrue(auth.list().isEmpty())
        }
    }

    @Test
    fun passwordRequestLostBeforeCommitCanRecoverWithPreviousLoginCredentials() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "legacy-token",
            features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session", passwordSet = false))
        var verified = false
        var mutations = 0
        val api = object : HouseholdApi by RecordingApi("alice", "config-a", features = account.session.features) {
            override fun setPassword(login: String, password: String, newPassword: String): PasswordAuthResult {
                mutations++
                throw java.io.IOException("connection lost before request reached server")
            }
            override fun login(login: String, password: String): PasswordAuthResult {
                if (password != account.session.token) throw ApiException(401, "unauthorized")
                verified = true
                return PasswordAuthResult(account.session.token, false)
            }
        }
        val repo = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })
        assertThrows(PasswordSignInRequiredException::class.java) {
            repo.changePassword(account.id, "", "new-password", "phone")
        }
        assertThrows(ApiException::class.java) { repo.signIn("a.example", "alice", "new-password", "phone") }
        assertTrue(auth.list().isEmpty())
        repo.signIn("a.example", "alice", "legacy-token", "phone")
        assertTrue(verified)
        assertEquals(account.id, auth.list().single().id)
        assertEquals(1, mutations)
    }

    @Test
    fun explicitPasswordFailureKeepsTheCurrentAccount() {
        for (code in listOf(401, 429, 503)) {
            val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val account = auth.upsert(Session("https://a.example", "alice", "old-token",
                features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session"))
            val failure = ApiException(code, "password request rejected")
            val api = RecordingApi("alice", "config-a").apply { beforePassword = { throw failure } }
            val repo = ContactRepository(auth, onAccountRemoved = { _, _ -> error("must keep account") },
                apiFactory = { _, _, _, _ -> api })

            assertEquals(failure, assertThrows(ApiException::class.java) {
                repo.changePassword(account.id, "password123", "new-password", "phone")
            })
            assertEquals(account, auth.get(account.id))
        }
    }

    @Test
    fun passwordTokenReceivedBeforeConnectionLossIsKeptForActivation() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "old-token",
            features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session"))
        val failure = java.io.IOException("config unavailable")
        val api = RecordingApi("alice", "config-a", passwordResult = PasswordAuthResult("new-token", false))
        api.beforeConfig = { throw failure }
        val repo = ContactRepository(auth, RecordingWebPushRegistration(),
            onAccountRemoved = { _, _ -> error("must keep issued token") }, apiFactory = { _, _, _, _ -> api })

        assertEquals(failure, assertThrows(java.io.IOException::class.java) {
            repo.changePassword(account.id, "password123", "new-password", "phone")
        })
        assertEquals("new-token", auth.get(account.id)?.session?.token)
        api.beforeConfig = {}
        assertEquals(listOf("bob"), repo.refreshContacts(account.id, "phone")?.items?.map { it.login })
    }

    @Test
    fun lostPasswordResponseDoesNotDiscardANewerSession() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "old-token",
            features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE), sessionId = "old-session"))
        val replacement = account.session.copy(token = "new-token", sessionId = "new-session")
        val failure = java.io.IOException("old request failed")
        val api = RecordingApi("alice", "config-a").apply {
            beforePassword = {
                assertTrue(auth.saveIfCurrent(account.id, account.session, replacement))
                throw failure
            }
        }
        val repo = ContactRepository(auth, onAccountRemoved = { _, _ -> error("must keep newer session") },
            apiFactory = { _, _, _, _ -> api })

        assertEquals(failure, assertThrows(java.io.IOException::class.java) {
            repo.changePassword(account.id, "password123", "new-password", "phone")
        })
        assertEquals(replacement, auth.get(account.id)?.session)
    }

    @Test
    fun rejectedLegacyTokenIsRemovedAndCorrectedLoginCanBeRetried() {
        for (additional in listOf(false, true)) {
            val persistence = MemoryKeyValueStore()
            val auth = AuthStore(persistence, PrefixTokenCipher())
            val first = if (additional) auth.upsert(Session("https://first.example", "first", "first-token", sessionId = "first-session")) else null
            val registration = RecordingWebPushRegistration()
            val failure = ApiException(401, "unauthorized")
            val rejectedApi = RecordingApi("alice", "config-old").apply { beforeConfig = { throw failure } }
            val acceptedApi = RecordingApi("alice", "config-old")
            val removed = mutableListOf<Session>()
            val repo = ContactRepository(auth, registration,
                onAccountRemoved = { _, session -> removed += session },
                apiFactory = { _, _, token, _ -> if (token == "wrong-token") rejectedApi else acceptedApi })

            assertEquals(failure, assertThrows(ApiException::class.java) {
                if (additional) repo.addAccount("old.example", "alice", "wrong-token", "phone")
                else repo.signIn("old.example", "alice", "wrong-token", "phone")
            })
            assertEquals(listOfNotNull(first), AuthStore(persistence, PrefixTokenCipher()).list())
            assertEquals(listOf("wrong-token"), removed.map { it.token })
            assertTrue(registration.subscribed.isEmpty())
            assertTrue(registration.unsubscribed.isEmpty())

            if (additional) repo.addAccount("old.example", "alice", "correct-token", "phone")
            else repo.signIn("old.example", "alice", "correct-token", "phone")
            val activated = auth.list().single { it.session.login == "alice" }
            assertEquals("correct-token", activated.session.token)
            assertEquals("session-alice", activated.session.sessionId)
            assertFalse(activated.session.needsActivation())
            if (first != null) assertEquals(first, auth.get(first.id))
        }
    }

    @Test
    fun temporaryConfigFailureKeepsIssuedTokenAndCanResumeActivation() {
        for (failure in listOf(java.io.IOException("offline"), ApiException(503, "unavailable"))) {
            val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val registration = RecordingWebPushRegistration()
            val api = RecordingApi("alice", "config-a", features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE),
                loginResult = PasswordAuthResult("issued-token", false))
            api.beforeConfig = { throw failure }
            val repo = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })
            assertEquals(failure, assertThrows(failure.javaClass) {
                repo.signIn("a.example", "alice", "password123", "phone")
            })
            val pending = auth.list().single()
            assertEquals("issued-token", pending.session.token)
            assertTrue(pending.session.needsActivation())
            assertTrue(registration.unsubscribed.isEmpty())

            api.beforeConfig = {}
            api.loginCredentials = null
            assertEquals(listOf("bob"), repo.refreshContacts(pending.id, "phone")?.items?.map { it.login })
            assertEquals(null, api.loginCredentials)
            assertFalse(requireNotNull(auth.get(pending.id)).session.needsActivation())
        }
    }

    @Test
    fun staleConfigRejectionDoesNotRemoveOrUnsubscribeNewerSession() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("alice", "config-a")
        var replacement: AccountRecord? = null
        api.beforeConfig = {
            val pending = auth.list().single()
            val session = pending.session.copy(token = "new-token", sessionId = "new-session", configId = "config-a")
            assertTrue(auth.activateWebPushIfCurrent(pending.id, pending.session, session, storedConfig(session)))
            replacement = auth.get(pending.id)
            throw ApiException(401, "unauthorized")
        }
        val repo = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertThrows(ApiException::class.java) { repo.signIn("a.example", "alice", "old-token", "phone") }

        assertEquals(listOf(requireNotNull(replacement)), auth.list())
        assertTrue(registration.unsubscribed.isEmpty())
    }

    @Test
    fun legacyLoginAndAdditionalAccountIgnorePrematureSessionReplacement() {
        for (additional in listOf(false, true)) {
            val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val first = if (additional) auth.upsert(Session("https://first.example", "first", "first-token", sessionId = "first-session")) else null
            val api = RecordingApi("alice", "config-old", features = setOf("webpush_v1"))
            api.beforeClaim = {
                val pending = auth.list().single { it.session.login == "alice" }
                assertTrue(pending.session.needsActivation())
                assertFalse(auth.invalidateIfCurrent(pending.id, pending.session, AuthRemovalReason.SessionReplaced))
            }
            val repo = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })
            if (additional) repo.addAccount("old.example", "alice", " legacy-token ", "phone")
            else repo.signIn("old.example", "alice", " legacy-token ", "phone")
            assertEquals(null, api.loginCredentials)
            val activated = auth.list().single { it.session.login == "alice" }
            assertEquals("legacy-token", activated.session.token)
            assertEquals("session-alice", activated.session.sessionId)
            assertFalse(activated.session.needsActivation())
            if (first != null) assertEquals(first, auth.get(first.id))
        }
    }

    @Test
    fun legacyTokenCanResumeActivationAfterInterruptedLogin() {
        val persistence = MemoryKeyValueStore()
        val auth = AuthStore(persistence, PrefixTokenCipher())
        val api = RecordingApi("alice", "config-old", features = setOf("webpush_v1"))
        api.beforeClaim = { throw java.io.IOException("offline") }
        val repo = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })
        assertThrows(java.io.IOException::class.java) { repo.signIn("old.example", "alice", "legacy-token", "phone") }
        val account = auth.list().single()
        api.beforeClaim = {}
        val restored = ContactRepository(AuthStore(persistence, PrefixTokenCipher()), RecordingWebPushRegistration(),
            apiFactory = { _, _, token, _ -> assertEquals("legacy-token", token); api })
        assertEquals(listOf("bob"), restored.refreshContacts(account.id, "phone")?.items?.map { it.login })
        assertEquals("session-alice", restored.accounts().single().session.sessionId)
        assertEquals(null, api.loginCredentials)
    }

    @Test
    fun issuedTokenSurvivesFailedClaimAndResumesWithoutAnotherPasswordLogin() {
        val persistence = MemoryKeyValueStore()
        val auth = AuthStore(persistence, PrefixTokenCipher())
        val api = RecordingApi("alice", "config-a", features = setOf("webpush_v1", PASSWORD_AUTH_FEATURE),
            loginResult = PasswordAuthResult("issued-token", false))
        api.beforeClaim = { throw java.io.IOException("connection lost") }
        val repo = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })
        assertThrows(java.io.IOException::class.java) { repo.signIn("a.example", "alice", "personal password", "phone") }
        val staged = AuthStore(persistence, PrefixTokenCipher()).list().single()
        assertEquals("issued-token", staged.session.token)
        assertEquals(null, staged.session.sessionId)
        api.beforeClaim = {}
        api.loginCredentials = null
        val restored = ContactRepository(AuthStore(persistence, PrefixTokenCipher()), RecordingWebPushRegistration(),
            apiFactory = { _, _, token, _ -> assertEquals("issued-token", token); api })
        assertEquals(listOf("bob"), restored.refreshContacts(staged.id, "phone")?.items?.map { it.login })
        assertEquals(null, api.loginCredentials)
        assertEquals("session-alice", restored.accounts().single().session.sessionId)
    }

    @Test
    fun passwordServerExchangesPasswordAndPersistsReturnedTokenBeforeSessionClaim() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi(
            "alice",
            "config-a",
            features = setOf("webpush_v1", "password_auth_v1"),
            loginResult = PasswordAuthResult("issued-token", passwordRequired = false),
        ).apply {
            beforeClaim = {
                assertEquals("issued-token", auth.list().single().session.token)
                assertEquals(null, auth.list().single().session.sessionId)
            }
        }
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        repository.signIn("a.example", "alice", "  personal password  ", "phone")

        assertEquals("alice" to "  personal password  ", api.loginCredentials)
        assertEquals("issued-token", auth.list().single().session.token)
        assertEquals("session-alice", auth.list().single().session.sessionId)
    }

    @Test
    fun temporaryPasswordRequestsSetupWithoutGrantingOrPersistingAccess() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi(
            "alice",
            "config-a",
            features = setOf("webpush_v1", "password_auth_v1"),
            loginResult = PasswordAuthResult(passwordRequired = true),
        )
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertThrows(PasswordSetupRequiredException::class.java) {
            repository.signIn("a.example", "alice", "1234 5678", "phone")
        }

        assertTrue(auth.list().isEmpty())
        assertTrue(registration.subscribed.isEmpty())
    }

    @Test
    fun passwordServerErrorsNeverFallBackToTokenAuthentication() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val registration = RecordingWebPushRegistration()
        val expected = ApiException(401, "invalid", errorCode = "invalid_credentials")
        val api = RecordingApi(
            "alice",
            "config-a",
            features = setOf("webpush_v1", "password_auth_v1"),
            loginFailure = expected,
        )
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertEquals(expected, assertThrows(ApiException::class.java) {
            repository.signIn("a.example", "alice", "looks-like-token", "phone")
        })
        assertTrue(auth.list().isEmpty())
        assertTrue(registration.subscribed.isEmpty())
    }

    @Test
    fun missingPasswordFeatureKeepsLegacyTokenFlowWithoutCallingLogin() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val api = RecordingApi("alice", "config-a", features = setOf("webpush_v1"))
        val repository = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })

        repository.signIn("a.example", "alice", " legacy-token ", "phone")

        assertEquals(null, api.loginCredentials)
        assertEquals("legacy-token", auth.list().single().session.token)
    }

    @Test
    fun setupExchangesTemporaryPasswordForPersonalPasswordAndActivatesReturnedToken() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val api = RecordingApi(
            "alice",
            "config-a",
            features = setOf("webpush_v1", "password_auth_v1"),
            passwordResult = PasswordAuthResult("personal-token", passwordRequired = false),
        )
        val repository = ContactRepository(auth, RecordingWebPushRegistration(), apiFactory = { _, _, _, _ -> api })

        repository.setInitialPassword(
            "a.example",
            "alice",
            "1234 5678",
            "a deliberately long password",
            "phone",
        )

        assertEquals(
            Triple("alice", "1234 5678", "a deliberately long password"),
            api.passwordCredentials,
        )
        assertEquals("personal-token", auth.list().single().session.token)
    }

    @Test
    fun passwordCapableLogoutRevokesBeforeRemovingAndKeepsAccountOnNetworkError() {
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1", "password_auth_v1"),
            "session-a",
            "config-a",
        )
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.add(auth.newAccountId(), session, storedConfig(session))
        val failure = java.io.IOException("offline")
        val api = RecordingApi("alice", "config-a", logoutFailure = failure)
        val repository = ContactRepository(auth, apiFactory = { _, _, _, _ -> api })

        assertEquals(failure, assertThrows(java.io.IOException::class.java) {
            repository.removeAccount(account.id)
        })

        assertEquals(account, auth.get(account.id))
        assertEquals(1, api.logoutRequests)
    }

    @Test
    fun legacyAccountWithoutPasswordCanLogoutWithoutPasswordSetup() {
        val session = Session(
            "https://a.example",
            "alice",
            "legacy-token",
            setOf("webpush_v1", "password_auth_v1"),
            "session-a",
            "config-a",
            passwordSet = false,
        )
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.add(auth.newAccountId(), session, storedConfig(session))
        val api = RecordingApi("alice", "config-a")
        val repository = ContactRepository(auth, apiFactory = { _, _, _, _ -> api })

        assertTrue(repository.removeAccount(account.id))
        assertEquals(null, auth.get(account.id))
        assertEquals(1, api.logoutRequests)
        assertEquals(null, api.passwordCredentials)
    }

    @Test
    fun legacyCacheKeepsOnlyPersonalNamesOffline() {
        val store = MemoryKeyValueStore()
        val account = AccountRecord(AccountId("account-a"), Session("https://a.example", "alice", "token"))
        store.put("contacts_v1:account-a", """{"version":1,"revision":7,"items":[
            {"login":"bob","displayName":"ADMIN-ONLY-bob","defaultDisplayName":"ADMIN-ONLY-bob"},
            {"login":"anna","displayName":"Мама","defaultDisplayName":"ADMIN-ONLY-anna","customName":"Мама"}
        ]}""")
        val cache = ContactCache(store)

        assertEquals(listOf("bob", "Мама"), cache.load(account).items.map { it.displayName })
        assertEquals(8L, cache.revision(account.id))
        assertFalse(requireNotNull(store.get("contacts_v1:account-a")).contains("ADMIN-ONLY"))
        assertEquals(listOf("bob", "Мама"), ContactCache(store).load(account).items.map { it.displayName })
    }

    @Test
    fun firstLoginClaimsSessionWithWebPushBeforeSavingAccount() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val registration = RecordingWebPushRegistration()
        val cache = ContactCache(MemoryKeyValueStore())
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("bob", "Bob")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(
            auth,
            registration,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> api },
        )

        val contacts = repository.signIn("a.example/", "alice", "token-a", "phone")

        assertEquals(listOf("bob", "carol"), contacts.items.map { it.login })
        assertEquals(listOf(auth.list().single().id), registration.subscribed)
        assertEquals(subscription, api.claimedSubscription)
        assertEquals("phone", api.claimedDeviceId)
        assertEquals("session-alice", auth.list().single().session.sessionId)
        assertEquals("https://a.example", auth.webPushConfig(auth.list().single().id)?.serverUrl)
        assertEquals(listOf("bob", "carol"), cache.load(auth.list().single()).items.map { it.login })
    }

    @Test
    fun addingSecondServerKeepsFirstAccountAndUsesAnotherPushInstance() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val firstSession = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val firstId = auth.newAccountId()
        auth.add(firstId, firstSession, storedConfig(firstSession))
        val registration = RecordingWebPushRegistration()
        val cache = ContactCache(MemoryKeyValueStore())
        val api = RecordingApi(
            "bob",
            "config-b",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("alice", "Alice")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(
            auth,
            registration,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> api },
        )

        val added = repository.addAccount("b.example", "bob", "token-b", "phone")

        assertEquals(listOf(firstId, added.account.id), auth.list().map { it.id })
        assertEquals(listOf("alice", "carol"), added.contacts.items.map { it.login })
        assertEquals(listOf("alice", "carol"), cache.load(added.account).items.map { it.login })
        assertEquals(listOf(added.account.id), registration.subscribed)
        assertEquals("https://b.example", auth.webPushConfig(added.account.id)?.serverUrl)
    }

    @Test
    fun addingAnotherLoginFromTheSameServerIsRejectedBeforePushRegistration() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = Session("https://a.example", "alice", "token-a", setOf("webpush_v1"), "session-a", "config-a")
        auth.add(auth.newAccountId(), first, storedConfig(first))
        val registration = RecordingWebPushRegistration()
        val repository = ContactRepository(
            auth,
            registration,
            apiFactory = { _, _, _, _ -> RecordingApi("anna", "config-a") },
        )

        assertThrows(DuplicateAccountException::class.java) {
            repository.addAccount("HTTPS://A.EXAMPLE:443/", "anna", "token-b", "phone")
        }

        assertTrue(registration.subscribed.isEmpty())
        assertEquals(listOf("alice"), auth.list().map { it.session.login })
    }

    @Test
    fun serverWithoutWebPushIsRejectedBeforeRegistration() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = Session("https://a.example", "alice", "token", setOf("webpush_v1"), "s-a", "config-a")
        auth.add(auth.newAccountId(), first, storedConfig(first))
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("bob", "config-b", features = emptySet())
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertThrows(ServerCompatibilityException::class.java) {
            repository.addAccount("https://b.example", "bob", "token-b", "phone")
        }
        assertTrue(registration.subscribed.isEmpty())
    }

    @Test
    fun apiVersionFourIsTheCompatibilityBoundary() {
        for ((version, expected) in mapOf(
            3 to ServerCheckResult.ServerOutdated,
            4 to ServerCheckResult.Available,
            5 to ServerCheckResult.AppOutdated,
        )) {
            val repository = ContactRepository(
                AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()),
                apiFactory = { _, _, _, _ -> RecordingApi("alice", "config", apiVersion = version) },
            )
            assertEquals(expected, repository.checkAddAccountServer("talk.example"))
        }
    }

    @Test
    fun refreshingAccountLoadsEveryContactPage() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val cacheStore = MemoryKeyValueStore()
        val cache = ContactCache(cacheStore)
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val accountId = auth.newAccountId()
        auth.add(accountId, session, storedConfig(session))
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("bob", "Bob")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })

        val restored = requireNotNull(repository.refreshContacts(accountId))

        assertEquals(listOf("bob", "carol"), restored.items.map { it.login })
        assertEquals(
            listOf("bob", "carol"),
            ContactCache(cacheStore).load(requireNotNull(auth.get(accountId))).items.map { it.login },
        )
    }

    @Test
    fun failedContactSyncKeepsPreviousCompleteCache() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val accountId = auth.newAccountId()
        val account = auth.add(accountId, session, storedConfig(session))
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    accountId,
                    listOf(AccountContact(accountId, session.url, Contact("old", "Old"))),
                ),
            )
        }
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("new", "New")), "second"),
            ),
            failedCursor = "second",
        )
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })

        assertThrows(IllegalStateException::class.java) { repository.refreshContacts(accountId) }

        assertEquals(listOf("old"), cache.load(account).items.map { it.login })
    }

    @Test
    fun renamingContactUpdatesCachedSnapshot() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    account.id,
                    listOf(AccountContact(account.id, session.url, Contact("bob", "Bob"))),
                ),
            )
        }
        val repository = ContactRepository(
            auth,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a") },
        )

        repository.updateContactName(account.id, "bob", "Bobby")

        assertEquals(listOf("Bobby"), cache.load(account).items.map { it.displayName })
    }

    @Test
    fun removingAccountDeletesItsCachedContacts() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    account.id,
                    listOf(AccountContact(account.id, session.url, Contact("bob", "Bob"))),
                ),
            )
        }
        val repository = ContactRepository(auth, contactCache = cache,
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a") })

        repository.removeAccount(account.id)

        assertTrue(cache.load(account).items.isEmpty())
    }

    @Test
    fun targetedRefreshUpdatesOnlyAvailabilityOnItsServerAndNotifiesUi() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val a = auth.upsert(Session("https://a.example", "alice", "token-a"))
        val b = auth.upsert(Session("https://b.example", "alice", "token-b"))
        val cache = ContactCache(MemoryKeyValueStore())
        for (account in listOf(a, b)) {
            cache.update(account, AccountContact(account.id, account.session.url, Contact("bob", "Папа", canCall = true)))
        }
        var allowed = false
        var requests = 0
        val api = object : HouseholdApi by RecordingApi("alice", "config-a") {
            override fun contact(login: String): Contact {
                requests++
                assertEquals("bob", login)
                return Contact(login, "Bob", canCall = allowed)
            }
            override fun contactsPage(limit: Int, cursor: String): ContactPage = error("must not reload all contacts")
        }
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })
        val changes = mutableListOf<AccountId>()
        val observer: (AccountId) -> Unit = { changes += it }
        ContactEvents.observe(observer)
        try {
            for (status in listOf(false, true)) {
                allowed = status
                repository.refreshContact(a.id, "bob", a.session)
                assertEquals(status, cache.load(a).items.single().canCall)
                assertEquals("Папа", cache.load(a).items.single().displayName)
                assertTrue(cache.load(b).items.single().canCall)
            }
            repository.refreshContact(a.id, "bob", a.session.copy(sessionId = "revoked-session"))
            assertEquals(2, requests)
            assertEquals(listOf(a.id, a.id), changes)
        } finally {
            ContactEvents.removeObserver(observer)
        }
    }

    @Test
    fun delayedListAndRenameCannotOverwriteUpdatedAvailability() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "token"))
        val cache = ContactCache(MemoryKeyValueStore())
        val original = AccountContact(account.id, account.session.url, Contact("bob", "Bob", canCall = true))
        cache.update(account, original)
        var requests = 0
        val api = object : HouseholdApi by RecordingApi("alice", "config-a") {
            override fun contactsPage(limit: Int, cursor: String): ContactPage {
                requests++
                if (requests == 1) cache.updateAvailability(account, "bob", false)
                return ContactPage(listOf(original.contact.copy(canCall = requests == 1)), "")
            }
        }
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })
        repository.refreshContacts(account.id)
        assertEquals(2, requests)
        assertFalse(cache.load(account).items.single().canCall)
        repository.updateContactName(account.id, "bob", "Папа")
        assertFalse(cache.load(account).items.single().canCall)
        assertEquals("Папа", cache.load(account).items.single().displayName)
    }

    @Test
    fun mutualAddRefreshesAvailabilityAfterAnEarlyPush() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "token", setOf("personal_contacts")))
        val cache = ContactCache(MemoryKeyValueStore())
        lateinit var repository: ContactRepository
        var detailRequests = 0
        val api = object : HouseholdApi by RecordingApi("alice", "config-a") {
            override fun addContact(login: String, customName: String): Contact {
                val delayedResponse = Contact(login, customName, canCall = false)
                // Bob adds Alice while her PUT response is in flight. His push arrives first.
                cache.replace(cache.load(account))
                repository.refreshContact(account.id, login, account.session)
                assertEquals(0, detailRequests)
                return delayedResponse
            }

            override fun contact(login: String): Contact {
                detailRequests++
                return Contact(login, "Bob", canCall = true)
            }
        }
        repository = ContactRepository(
            auth,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> api },
            onContactAdded = { current, login -> repository.refreshContact(current.id, login, current.session) },
        )

        repository.addContact(account.id, "bob", "Папа")

        assertEquals(1, detailRequests)
        assertTrue(cache.load(account).items.single().canCall)
        assertEquals("Папа", cache.load(account).items.single().displayName)
    }

    @Test
    fun delayedContactResponseCannotRestoreDeletedContact() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://a.example", "alice", "token"))
        val cache = ContactCache(MemoryKeyValueStore())
        cache.update(account, AccountContact(account.id, account.session.url, Contact("bob", "Папа")))
        val api = object : HouseholdApi by RecordingApi("alice", "config-a") {
            override fun contact(login: String): Contact {
                cache.remove(account, login)
                return Contact(login, "Bob", canCall = true)
            }
        }
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })
        repository.refreshContact(account.id, "bob", account.session)
        assertTrue(cache.load(account).items.isEmpty())
    }

    @Test
    fun explicitRemoveAccountInvokesCommonAndExplicitCallbacksOnce() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val common = mutableListOf<Pair<AccountId, Session>>()
        val explicit = mutableListOf<Pair<AccountId, Session>>()
        val repository = ContactRepository(
            authStore = auth,
            onAccountRemoved = { accountId, removedSession -> common += accountId to removedSession },
            onExplicitAccountRemoved = { accountId, removedSession -> explicit += accountId to removedSession },
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a") },
        )

        assertTrue(repository.removeAccount(account.id))

        assertEquals(listOf(account.id to session), common)
        assertEquals(listOf(account.id to session), explicit)
    }

    @Test
    fun staleRemoveAccountDoesNotInvokeExplicitCallback() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val repository = ContactRepository(
            authStore = auth,
            onExplicitAccountRemoved = { _, _ -> error("stale account must not trigger explicit cleanup") },
        )

        assertFalse(repository.removeAccount(AccountId("missing")))
    }

    @Test
    fun unauthorizedRemovalInvokesCommonCallbackButNotExplicitCallback() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val common = mutableListOf<Pair<AccountId, Session>>()
        val explicit = mutableListOf<Pair<AccountId, Session>>()
        val repository = ContactRepository(
            authStore = auth,
            onAccountRemoved = { accountId, removedSession -> common += accountId to removedSession },
            onExplicitAccountRemoved = { accountId, removedSession -> explicit += accountId to removedSession },
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a", failContactsWith = ApiException(401, "unauthorized")) },
        )

        assertThrows(ApiException::class.java) {
            repository.refreshContacts(account.id)
        }

        assertEquals(listOf(account.id to session), common)
        assertTrue(explicit.isEmpty())
        assertTrue(auth.list().isEmpty())
    }
}

private val subscription = WebPushSubscription(
    "https://fcm.googleapis.com/fcm/send/test",
    WebPushKeys("p256dh", "auth"),
)

private class RecordingWebPushRegistration : AccountWebPushRegistration {
    val subscribed = mutableListOf<AccountId>()
    val unsubscribed = mutableListOf<AccountId>()

    override fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription {
        subscribed += accountId
        return subscription
    }

    override fun restore(accountId: AccountId, config: StoredWebPushConfig) = Unit
    override fun unsubscribe(accountId: AccountId) { unsubscribed += accountId }
}

private class RecordingApi(
    private val login: String,
    private val configId: String,
    private val apiVersion: Int = 4,
    private val features: Set<String> = setOf("webpush_v1"),
    private val contactPages: Map<String, ContactPage>? = null,
    private val failedCursor: String? = null,
    private val failContactsWith: RuntimeException? = null,
    private val loginResult: PasswordAuthResult? = null,
    private val loginFailure: RuntimeException? = null,
    private val passwordResult: PasswordAuthResult? = null,
    private val logoutFailure: Throwable? = null,
) : HouseholdApi {
    var claimedDeviceId: String? = null
    var claimedSubscription: WebPushSubscription? = null
    var loginCredentials: Pair<String, String>? = null
    var passwordCredentials: Triple<String, String, String>? = null
    var logoutRequests: Int = 0
    var beforeConfig: () -> Unit = {}
    var beforePassword: () -> Unit = {}
    var beforeClaim: () -> Unit = {}

    override fun serverInfo() = ServerInfo("tinitalk", "ok", apiVersion, features = features)
    override fun login(login: String, password: String): PasswordAuthResult {
        loginCredentials = login to password
        loginFailure?.let { throw it }
        return requireNotNull(loginResult)
    }
    override fun setPassword(login: String, password: String, newPassword: String): PasswordAuthResult {
        passwordCredentials = Triple(login, password, newPassword)
        beforePassword()
        return requireNotNull(passwordResult)
    }
    override fun logout() {
        logoutRequests++
        logoutFailure?.let { throw it }
    }
    override fun webPushConfig(): WebPushClientConfig {
        beforeConfig()
        return WebPushClientConfig(
            "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
            configId,
        )
    }
    override fun me() = Profile(login, login.replaceFirstChar(Char::uppercase))
    override fun contactsPage(limit: Int, cursor: String): ContactPage {
        failContactsWith?.let { throw it }
        if (cursor == failedCursor) error("page failed")
        return contactPages?.getValue(cursor) ?: ContactPage(
            listOf(Contact(login, login), Contact("bob", "Bob")),
            "",
        )
    }
    override fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String {
        beforeClaim()
        claimedDeviceId = deviceId
        claimedSubscription = subscription
        return "session-$login"
    }
    override fun updateContactName(login: String, customName: String) = Contact(login, customName, customName)
    override fun calls(limit: Int, before: Long, peerLogin: String?) = CallHistoryPage(emptyList(), 0, 0, 0)
    override fun markCallsRead(throughId: Long, peerLogin: String?) = CallUnreadState(0, emptyList())
    override fun putDevice(deviceId: String, subscription: WebPushSubscription, configId: String) = Unit
}

private fun storedConfig(session: Session) = StoredWebPushConfig(
    session.url,
    "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
    requireNotNull(session.configId),
)
