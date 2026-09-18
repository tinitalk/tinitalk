package org.tinitalk.data

import android.content.Context
import android.util.Log
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.ContactRefreshScheduler
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.UnifiedPushAccountRegistration
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.isValid
import java.io.IOException

private const val TINITALK_SERVICE = "tinitalk"
private const val SUPPORTED_API_VERSION = 4
private const val WEBPUSH_FEATURE = "webpush_v1"
private const val PERSONAL_CONTACTS_FEATURE = "personal_contacts"
internal const val PASSWORD_AUTH_FEATURE = "password_auth_v1"

data class AddedAccount(
    val account: AccountRecord,
    val contacts: AccountContactPage,
)

enum class CompatibilityProblem {
    WrongServer,
    ServerOutdated,
    AppOutdated,
    Unavailable,
}

enum class ServerCheckResult {
    Available,
    WrongServer,
    ServerOutdated,
    AppOutdated,
    Unavailable,
}

data class ServerCheckDetails(
    val result: ServerCheckResult,
    val apiVersion: Int? = null,
    val commit: String? = null,
)

class ServerCompatibilityException(
    val problem: CompatibilityProblem,
    val serverUrl: String? = null,
) : RuntimeException()

class DuplicateAccountException : IllegalArgumentException("server already exists")
class PasswordSetupRequiredException : IllegalStateException("personal password setup is required")
class PasswordSignInRequiredException(cause: Exception) : IOException("password change requires a new login", cause)

class ContactRepository internal constructor(
    private val authStore: AuthStore,
    private val webPushRegistration: AccountWebPushRegistration? = null,
    private val onAccountRemoved: (AccountId, Session) -> Unit = { _, _ -> },
    private val onExplicitAccountRemoved: (AccountId, Session) -> Unit = { _, _ -> },
    private val contactCache: ContactCache? = null,
    private val onContactAdded: (AccountRecord, String) -> Unit = { _, _ -> },
    private val apiFactory: (url: String, login: String, token: String, sessionId: String?) -> HouseholdApi =
        { url, login, token, sessionId -> UrlConnectionApiClient(url, login, token, sessionId) },
) {
    constructor(authStore: AuthStore) : this(authStore, null)

    constructor(
        authStore: AuthStore,
        apiFactory: (url: String, login: String, token: String) -> HouseholdApi,
    ) : this(
        authStore = authStore,
        apiFactory = { url, login, token, _ -> apiFactory(url, login, token) },
    )

    internal constructor(
        context: Context,
        authStore: AuthStore,
        contactCache: ContactCache = ContactCache(SharedPreferencesKeyValueStore(context)),
        onExplicitAccountRemoved: (AccountId, Session) -> Unit = { _, _ -> },
    ) : this(
        authStore = authStore,
        webPushRegistration = UnifiedPushAccountRegistration(context),
        onAccountRemoved = { accountId, session -> cleanupWebPushAccount(context, accountId, session) },
        onExplicitAccountRemoved = onExplicitAccountRemoved,
        contactCache = contactCache,
        onContactAdded = { account, login ->
            runCatching { ContactRefreshScheduler(context.applicationContext).enqueue(account, login) }
                .onFailure { Log.w("TiniContacts", "failed to schedule added contact refresh", it) }
        },
    )

    fun checkServer(url: String): ServerCheckResult {
        return checkServerDetails(url).result
    }

    fun checkServerDetails(url: String): ServerCheckDetails {
        return try {
            val normalizedUrl = httpsServerUrl(url) ?: return ServerCheckDetails(ServerCheckResult.Unavailable)
            val info = apiFactory(normalizedUrl, "", "", null).serverInfo()
            val problem = info.compatibilityProblem()
                ?: CompatibilityProblem.ServerOutdated.takeIf { WEBPUSH_FEATURE !in info.features }
            val result = when (problem) {
                null -> ServerCheckResult.Available
                CompatibilityProblem.WrongServer -> ServerCheckResult.WrongServer
                CompatibilityProblem.ServerOutdated -> ServerCheckResult.ServerOutdated
                CompatibilityProblem.AppOutdated -> ServerCheckResult.AppOutdated
                CompatibilityProblem.Unavailable -> ServerCheckResult.Unavailable
            }
            val isTiniTalk = info.service == TINITALK_SERVICE
            if (result == ServerCheckResult.Available) {
                authStore.updateFeatures(normalizedUrl, info.features)
            }
            ServerCheckDetails(
                result = result,
                apiVersion = info.apiVersion.takeIf { isTiniTalk && it > 0 },
                commit = info.commit?.trim()?.takeIf { isTiniTalk && it.isNotEmpty() },
            )
        } catch (_: Exception) {
            ServerCheckDetails(ServerCheckResult.Unavailable)
        }
    }

    fun checkAddAccountServer(url: String): ServerCheckResult = try {
        val normalizedUrl = checkNotNull(httpsServerUrl(url))
        apiFactory(normalizedUrl, "", "", null).requireWebPushServer(normalizedUrl)
        ServerCheckResult.Available
    } catch (error: ServerCompatibilityException) {
        when (error.problem) {
            CompatibilityProblem.WrongServer -> ServerCheckResult.WrongServer
            CompatibilityProblem.ServerOutdated -> ServerCheckResult.ServerOutdated
            CompatibilityProblem.AppOutdated -> ServerCheckResult.AppOutdated
            CompatibilityProblem.Unavailable -> ServerCheckResult.Unavailable
        }
    } catch (_: Exception) {
        ServerCheckResult.Unavailable
    }

    fun signIn(url: String, login: String, token: String, deviceId: String = ""): ContactPage {
        val normalizedUrl = requireNotNull(httpsServerUrl(url))
        val normalizedLogin = login.trim()
        val client = apiFactory(normalizedUrl, normalizedLogin, token, null)
        val info = client.requireWebPushServer()
        val accessToken = if (PASSWORD_AUTH_FEATURE in info.features) {
            client.login(normalizedLogin, token).accessTokenOrThrow()
        } else {
            token.trim()
        }
        return activateFirstAccount(normalizedUrl, normalizedLogin, accessToken, info, deviceId)
    }

    fun setInitialPassword(
        url: String,
        login: String,
        temporaryPassword: String,
        newPassword: String,
        deviceId: String,
    ): ContactPage {
        val normalizedUrl = requireNotNull(httpsServerUrl(url))
        val normalizedLogin = login.trim()
        val client = apiFactory(normalizedUrl, normalizedLogin, "", null)
        val info = client.requireWebPushServer()
        if (PASSWORD_AUTH_FEATURE !in info.features) {
            throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated, normalizedUrl)
        }
        val token = client.setPassword(normalizedLogin, temporaryPassword, newPassword).accessTokenOrThrow()
        return activateFirstAccount(normalizedUrl, normalizedLogin, token, info, deviceId)
    }

    fun restorableSession(): Session? {
        val account = authStore.list().firstOrNull() ?: return null
        return authStore.loadBoundTo(authStore.webPushConfig(account.id))
    }

    fun accounts(): List<AccountRecord> = authStore.list()

    fun passwordSet(accountId: AccountId): Boolean? {
        val account = authStore.get(accountId) ?: return null
        if (PASSWORD_AUTH_FEATURE !in account.session.features) {
            val info = api(account.session).requireWebPushServer(account.session.url)
            authStore.updateFeatures(account.session.url, info.features)
            if (PASSWORD_AUTH_FEATURE !in info.features) return null
        }
        account.session.passwordSet?.let { return it }
        val value = api(account.session).me().passwordSet ?: return null
        authStore.saveIfCurrent(account.id, account.session, account.session.copy(passwordSet = value))
        return value
    }

    fun addAccount(url: String, login: String, token: String, deviceId: String): AddedAccount {
        val existing = authStore.list()
        require(existing.isNotEmpty()) { "addAccount requires an existing account" }
        val normalizedUrl = requireNotNull(httpsServerUrl(url))
        val normalizedLogin = login.trim()
        val candidate = Session(normalizedUrl, normalizedLogin, token.trim())
        if (existing.any { sameServerUrl(it.session.url, candidate.url) }) throw DuplicateAccountException()
        val client = apiFactory(normalizedUrl, normalizedLogin, token, null)
        val info = client.requireWebPushServer(candidate.url)
        val accessToken = if (PASSWORD_AUTH_FEATURE in info.features) {
            client.login(normalizedLogin, token).accessTokenOrThrow()
        } else {
            token.trim()
        }
        return activateAdditionalAccount(normalizedUrl, normalizedLogin, accessToken, info, deviceId)
    }

    fun setInitialPasswordAndAddAccount(
        url: String,
        login: String,
        temporaryPassword: String,
        newPassword: String,
        deviceId: String,
    ): AddedAccount {
        val normalizedUrl = requireNotNull(httpsServerUrl(url))
        val normalizedLogin = login.trim()
        if (authStore.list().any { sameServerUrl(it.session.url, normalizedUrl) }) throw DuplicateAccountException()
        val client = apiFactory(normalizedUrl, normalizedLogin, "", null)
        val info = client.requireWebPushServer(normalizedUrl)
        if (PASSWORD_AUTH_FEATURE !in info.features) {
            throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated, normalizedUrl)
        }
        val token = client.setPassword(normalizedLogin, temporaryPassword, newPassword).accessTokenOrThrow()
        return activateAdditionalAccount(normalizedUrl, normalizedLogin, token, info, deviceId)
    }

    fun changePassword(accountId: AccountId, currentPassword: String, newPassword: String, deviceId: String): ContactPage {
        val account = authStore.get(accountId) ?: throw IllegalStateException("account is unavailable")
        val client = api(account.session)
        val info = if (PASSWORD_AUTH_FEATURE in account.session.features) {
            ServerInfo(TINITALK_SERVICE, "ok", SUPPORTED_API_VERSION, features = account.session.features)
        } else {
            client.requireWebPushServer(account.session.url).also {
                if (PASSWORD_AUTH_FEATURE !in it.features) {
                    throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated, account.session.url)
                }
                authStore.updateFeatures(account.session.url, it.features)
            }
        }
        val password = currentPassword.takeUnless(String::isEmpty) ?: account.session.token
        return authStore.changingCredentials(account) {
            val token = try {
                client.setPassword(account.session.login, password, newPassword).accessTokenOrThrow()
            } catch (error: Exception) {
                if (error is ApiException) throw error
                // The server may have replaced the password and revoked this token.
                // Do not retry the mutation with credentials whose validity is unknown.
                if (authStore.requireSignInIfCurrent(account.id, account.session)) {
                    contactCache?.remove(account.id)
                    onAccountRemoved(account.id, account.session)
                    throw PasswordSignInRequiredException(error)
                }
                throw error
            }
            activateExistingAccount(account, token, info, deviceId)
        }
    }

    private fun activateFirstAccount(
        url: String,
        login: String,
        token: String,
        info: ServerInfo,
        deviceId: String,
    ): ContactPage {
        val previous = authStore.load()
        val provisional = Session(url, login, token, features = info.features)
        val accountId = if (previous == null) {
            authStore.upsert(provisional).id
        } else {
            check(authStore.saveIfCurrent(previous, provisional)) { "authentication state changed" }
            checkNotNull(authStore.list().firstOrNull()?.id)
        }
        return activateStagedAccount(accountId, provisional, deviceId).second
    }

    private fun activateAdditionalAccount(
        url: String,
        login: String,
        token: String,
        info: ServerInfo,
        deviceId: String,
    ): AddedAccount {
        require(authStore.list().isNotEmpty()) { "addAccount requires an existing account" }
        require(authStore.list().none { sameServerUrl(it.session.url, url) }) { "duplicate server" }
        val provisional = Session(url, login, token, features = info.features)
        val staged = authStore.upsert(provisional)
        val accountId = staged.id
        val (account, contacts) = activateStagedAccount(accountId, provisional, deviceId)
        return AddedAccount(account, contacts.boundTo(accountId, url))
    }

    private fun activateExistingAccount(
        account: AccountRecord,
        token: String,
        info: ServerInfo,
        deviceId: String,
    ): ContactPage {
        val provisional = account.session.copy(
            token = token,
            features = info.features,
            sessionId = null,
            configId = null,
            passwordSet = true,
        )
        check(authStore.saveIfCurrent(account.id, account.session, provisional)) { "authentication state changed" }
        return activateStagedAccount(account.id, provisional, deviceId).second
    }

    private fun activateStagedAccount(
        accountId: AccountId,
        provisional: Session,
        deviceId: String,
    ): Pair<AccountRecord, ContactPage> {
        require(deviceId.isNotBlank()) { "device_id is required for push activation" }
        val registration = checkNotNull(webPushRegistration) { "push activation is unavailable" }
        val client = api(provisional)
        var committed = false
        var subscriptionStarted = false
        try {
            val config = client.webPushConfig().toStoredConfig(provisional.url)
            subscriptionStarted = true
            val subscription = registration.subscribe(accountId, config)
            val session = provisional.copy(
                sessionId = client.claimSession(deviceId, subscription, config.configId),
                configId = config.configId,
            )
            check(authStore.activateWebPushIfCurrent(accountId, provisional, session, config)) {
                "authentication state changed"
            }
            committed = true
            val claimedApi = this.api(session)
            val profile = claimedApi.me()
            val contacts = claimedApi.allContacts(profile.login)
            val profiledSession = session.copy(passwordSet = profile.passwordSet ?: session.passwordSet)
            if (profiledSession != session) {
                check(authStore.saveIfCurrent(accountId, session, profiledSession)) {
                    "authentication state changed"
                }
            }
            val account = checkNotNull(authStore.get(accountId))
            val bound = contacts.boundTo(accountId, session.url)
            contactCache?.replace(bound)
            return account to contacts
        } catch (e: ApiException) {
            handleUnauthorized(e, accountId, provisional)
            throw e
        } finally {
            if (subscriptionStarted && !committed) runCatching { registration.unsubscribe(accountId) }
        }
    }

    fun restorableSession(accountId: AccountId): Session? {
        val account = authStore.get(accountId) ?: return null
        val config = authStore.webPushConfig(accountId) ?: return null
        return account.session.takeIf { session ->
            normalizeServerUrl(session.url) == normalizeServerUrl(config.serverUrl) &&
                session.configId == config.configId
        }
    }

    fun refreshContacts(accountId: AccountId, deviceId: String = ""): AccountContactPage? {
        val account = authStore.get(accountId) ?: return null
        return try {
            if (account.session.needsActivation()) {
                if (deviceId.isBlank()) return null
                return activateStagedAccount(account.id, account.session, deviceId).second.boundTo(account.id, account.session.url)
            }
            repeat(2) {
                val revision = contactCache?.revision(account.id)
                val page = api(account.session).allContacts(account.session.login).boundTo(account.id, account.session.url)
                val applied = authStore.withCurrent(account.id, account.session) {
                    contactCache?.replace(page, revision) != false
                } ?: return null
                if (applied) return page
            }
            contactCache?.load(account)
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    internal fun refreshContact(accountId: AccountId, login: String, expectedSession: Session) {
        val account = authStore.get(accountId) ?: return
        if (!account.session.sameIdentity(expectedSession)) return
        val cache = contactCache ?: return
        repeat(2) {
            val revision = cache.revision(accountId)
            if (cache.load(account).items.none { it.login == login }) return
            val canCall = try {
                api(account.session).contact(login).canCall
            } catch (e: ApiException) {
                if (!authStore.isCurrent(account.id, account.session)) return
                handleUnauthorized(e, account.id, account.session)
                if (e.code != 404) throw e
                false
            }
            val applied = authStore.withCurrent(account.id, account.session) {
                cache.updateAvailability(account, login, canCall, revision)
            } ?: return
            if (applied) return
        }
        throw IOException("contact changed during refresh")
    }

    fun updateContactName(accountId: AccountId, login: String, customName: String): AccountContact? {
        val account = authStore.get(accountId) ?: return null
        return try {
            val contact = api(account.session).updateContactName(login, customName)
            authStore.withCurrent(account.id, account.session) {
                AccountContact(account.id, account.session.url, contact).also {
                    contactCache?.updateName(account, it)
                }
            }
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun addContact(accountId: AccountId, login: String, customName: String): AccountContact? {
        val account = authStore.get(accountId) ?: return null
        return try {
            val client = api(account.session)
            requirePersonalContacts(client, account)
            val contact = client.addContact(login.trim(), customName.trim())
            val added = authStore.withCurrent(account.id, account.session) {
                AccountContact(account.id, account.session.url, contact).also {
                    contactCache?.update(account, it)
                }
            } ?: return null
            // A push may have arrived before the PUT response populated the cache.
            onContactAdded(account, contact.login)
            added
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun removeContact(accountId: AccountId, login: String): Boolean {
        val account = authStore.get(accountId) ?: return false
        return try {
            val client = api(account.session)
            requirePersonalContacts(client, account)
            client.removeContact(login)
            authStore.withCurrent(account.id, account.session) {
                contactCache?.remove(account, login)
                true
            } ?: false
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return false
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun loadCallHistory(
        accountId: AccountId,
        before: Long = 0,
        limit: Int = 50,
        peerLogin: String? = null,
        expectedSession: Session? = null,
    ): AccountCallHistoryPage? {
        val account = authStore.get(accountId) ?: return null
        if (expectedSession != null && !account.session.sameIdentity(expectedSession)) return null
        if (!authStore.isCurrent(account.id, account.session)) return null
        return try {
            val page = api(account.session).calls(limit, before, peerLogin)
            if (!authStore.isCurrent(account.id, account.session)) null else page.boundTo(account.id, account.session)
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun markCallHistoryRead(
        accountId: AccountId,
        throughId: Long,
        peerLogin: String? = null,
        expectedSession: Session? = null,
    ): AccountUnreadState? {
        val account = authStore.get(accountId) ?: return null
        if (expectedSession != null && !account.session.sameIdentity(expectedSession)) return null
        if (!authStore.isCurrent(account.id, account.session)) return null
        return try {
            val unread = api(account.session).markCallsRead(throughId, peerLogin)
            if (!authStore.isCurrent(account.id, account.session)) null else AccountUnreadState(account.id, unread, account.session)
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun removeAccount(accountId: AccountId): Boolean {
        val account = authStore.get(accountId) ?: return false
        try { passwordSet(accountId) } catch (error: ApiException) {
            if (error.code != 401) throw error
            // A reset/revocation has already logged this device out.
            if (!authStore.removeIfCurrent(accountId, account.session)) return false
            contactCache?.remove(accountId)
            onAccountRemoved(accountId, account.session)
            onExplicitAccountRemoved(accountId, account.session)
            return true
        }
        val current = authStore.get(accountId) ?: return false
        if (PASSWORD_AUTH_FEATURE in current.session.features) {
            authStore.changingCredentials(current) {
                try { api(current.session).logout() } catch (error: ApiException) {
                    if (error.code != 401) throw error // Already revoked is also logged out.
                }
                if (!authStore.removeIfCurrent(accountId, current.session)) return@changingCredentials false
                true
            }.let { if (!it) return false }
        } else if (!authStore.removeIfCurrent(accountId, current.session)) return false
        contactCache?.remove(accountId)
        onAccountRemoved(accountId, account.session)
        onExplicitAccountRemoved(accountId, account.session)
        return true
    }

    private fun api(session: Session): HouseholdApi =
        apiFactory(session.url, session.login, session.token, session.sessionId)

    private fun requirePersonalContacts(api: HouseholdApi, account: AccountRecord) {
        if (PERSONAL_CONTACTS_FEATURE in account.session.features) return
        val info = api.requireCompatibleServer(account.session.url)
        if (PERSONAL_CONTACTS_FEATURE !in info.features) {
            throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated, account.session.url)
        }
        authStore.updateFeatures(account.session.url, info.features)
    }

    private fun handleUnauthorized(error: ApiException, session: Session) {
        val account = authStore.list().singleOrNull { it.session.sameIdentity(session) } ?: return
        handleUnauthorized(error, account.id, session)
    }

    private fun handleUnauthorized(error: ApiException, accountId: AccountId, session: Session) {
        if (error.code != 401) return
        val reason = if (error.authReason == SessionReplacedReason) {
            AuthRemovalReason.SessionReplaced
        } else {
            AuthRemovalReason.Unauthorized
        }
        val removed = authStore.invalidateIfCurrent(accountId, session, reason)
        if (removed) {
            contactCache?.remove(accountId)
            onAccountRemoved(accountId, session)
        }
    }
}

private fun PasswordAuthResult.accessTokenOrThrow(): String {
    if (passwordRequired) throw PasswordSetupRequiredException()
    return token?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("password authentication returned no token")
}

private fun ContactPage.withoutUser(login: String): ContactPage =
    copy(items = items.filterNot { it.login == login })

private fun HouseholdApi.allContacts(login: String): ContactPage {
    val contacts = mutableListOf<Contact>()
    var cursor = ""
    do {
        val page = contactsPage(limit = 100, cursor = cursor).withoutUser(login)
        contacts += page.items
        cursor = page.nextCursor
    } while (cursor.isNotEmpty())
    return ContactPage(contacts.distinctBy(Contact::login), "")
}

private fun ContactPage.boundTo(accountId: AccountId, serverUrl: String): AccountContactPage = AccountContactPage(
    accountId = accountId,
    items = items.map { contact -> AccountContact(accountId, serverUrl, contact) },
)

private fun CallHistoryPage.boundTo(accountId: AccountId, session: Session): AccountCallHistoryPage = AccountCallHistoryPage(
    accountId = accountId,
    items = items.map { item -> AccountHistory(accountId, normalizeServerUrl(session.url), item) },
    nextBefore = nextBefore,
    latestId = latestId,
    unread = CallUnreadState(unreadMissedCount, unreadMissed),
    session = session,
)

private fun HouseholdApi.requireCompatibleServer(serverUrl: String? = null): ServerInfo {
    val info = serverInfo()
    throw ServerCompatibilityException(info.compatibilityProblem() ?: return info, serverUrl)
}

private fun HouseholdApi.requireWebPushServer(serverUrl: String? = null): ServerInfo {
    val info = requireCompatibleServer(serverUrl)
    if (WEBPUSH_FEATURE !in info.features) {
        throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated, serverUrl)
    }
    return info
}

private fun WebPushClientConfig.toStoredConfig(serverUrl: String): StoredWebPushConfig = StoredWebPushConfig(
    serverUrl = normalizeServerUrl(serverUrl),
    vapidPublicKey = vapidPublicKey,
    configId = configId,
).also { require(it.isValid()) { "invalid WebPush configuration" } }

private fun ServerInfo.compatibilityProblem(): CompatibilityProblem? = when {
    service != TINITALK_SERVICE -> CompatibilityProblem.WrongServer
    status != "ok" -> CompatibilityProblem.Unavailable
    apiVersion < SUPPORTED_API_VERSION -> CompatibilityProblem.ServerOutdated
    apiVersion > SUPPORTED_API_VERSION -> CompatibilityProblem.AppOutdated
    else -> null
}
