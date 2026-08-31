package org.tinitalk.data

import android.content.Context
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.UnifiedPushAccountRegistration
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.isValid

private const val TINITALK_SERVICE = "tinitalk"
private const val SUPPORTED_API_VERSION = 3
private const val WEBPUSH_FEATURE = "webpush_v1"

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

class DuplicateAccountException : IllegalArgumentException("account already exists")

class ContactRepository internal constructor(
    private val authStore: AuthStore,
    private val webPushRegistration: AccountWebPushRegistration? = null,
    private val onAccountRemoved: (AccountId, Session) -> Unit = { _, _ -> },
    private val apiFactory: (url: String, login: String, token: String, sessionId: String?) -> HouseholdApi =
        { url, login, token, sessionId -> UrlConnectionApiClient(url, login, token, sessionId) },
) {
    constructor(authStore: AuthStore) : this(authStore, null)

    constructor(
        authStore: AuthStore,
        apiFactory: (url: String, login: String, token: String) -> HouseholdApi,
    ) : this(
        authStore,
        null,
        { _, _ -> },
        { url, login, token, _ -> apiFactory(url, login, token) },
    )

    constructor(context: Context, authStore: AuthStore) : this(
        authStore,
        UnifiedPushAccountRegistration(context),
        { accountId, session -> cleanupWebPushAccount(context, accountId, session) },
    )

    fun checkServer(url: String): ServerCheckResult {
        return checkServerDetails(url).result
    }

    fun checkServerDetails(url: String): ServerCheckDetails {
        return try {
            val normalizedUrl = url.trim().trimEnd('/')
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
        val normalizedUrl = normalizeServerUrl(url)
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
        val previous = authStore.load()
        val accountId = authStore.list().firstOrNull()?.id ?: authStore.newAccountId()
        var session = Session(normalizeServerUrl(url), login.trim(), token.trim())
        var api = api(session)
        var subscribed = false
        var persisted = false
        return try {
            val info = api.requireWebPushServer()
            require(deviceId.isNotBlank()) { "device_id is required for push activation" }
            val registration = checkNotNull(webPushRegistration) { "push activation is unavailable" }
            val config = api.webPushConfig().toStoredConfig(session.url)
            val subscription = registration.subscribe(accountId, config)
            subscribed = true
            val sessionId = api.claimSession(deviceId, subscription, config.configId)
            session = session.copy(
                features = info.features,
                sessionId = sessionId,
                configId = config.configId,
            )
            if (previous == null) {
                authStore.add(accountId, session, config, "")
            } else {
                check(authStore.activateWebPushIfCurrent(accountId, previous, session, config)) {
                    "authentication state changed"
                }
            }
            persisted = true
            api = api(session)
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            runCatching { authStore.saveIfCurrent(accountId, session, session, profile.displayName) }
            contacts
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        } finally {
            if (subscribed && !persisted) runCatching { webPushRegistration?.unsubscribe(accountId) }
        }
    }

    fun restorableSession(): Session? {
        val account = authStore.list().firstOrNull() ?: return null
        return authStore.loadBoundTo(authStore.webPushConfig(account.id))
    }

    fun accounts(): List<AccountRecord> = authStore.list()

    fun addAccount(url: String, login: String, token: String, deviceId: String): AddedAccount {
        val existing = authStore.list()
        require(existing.isNotEmpty()) { "addAccount requires an existing account" }
        val candidate = Session(normalizeServerUrl(url), login.trim(), token.trim())
        if (existing.any {
            normalizeServerUrl(it.session.url) == candidate.url && it.session.login.trim() == candidate.login
        }) throw DuplicateAccountException()
        require(deviceId.isNotBlank()) { "device_id is required for push activation" }
        val registration = checkNotNull(webPushRegistration) { "push activation is unavailable" }
        val api = api(candidate)
        val info = api.requireWebPushServer(candidate.url)
        val config = api.webPushConfig().toStoredConfig(candidate.url)
        val accountId = authStore.newAccountId()
        var committed = false
        try {
            val subscription = registration.subscribe(accountId, config)
            val session = candidate.copy(
                features = info.features,
                sessionId = api.claimSession(deviceId, subscription, config.configId),
                configId = config.configId,
            )
            val claimedApi = api(session)
            val profile = claimedApi.me()
            val contacts = claimedApi.contactsPage().withoutUser(profile.login)
            val account = authStore.add(accountId, session, config, profile.displayName)
            committed = true
            return AddedAccount(account, contacts.boundTo(accountId, session.url))
        } finally {
            if (!committed) runCatching { registration.unsubscribe(accountId) }
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

    fun restoreContacts(accountId: AccountId): AccountContactPage? {
        val account = authStore.get(accountId) ?: return null
        val storedSession = restorableSession(accountId) ?: return null
        var session = storedSession
        val api = api(session)
        return try {
            val info = api.requireWebPushServer(account.session.url)
            session = session.copy(features = info.features)
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            if (authStore.saveIfCurrent(accountId, storedSession, session, profile.displayName)) {
                contacts.boundTo(account.id, session.url)
            } else {
                null
            }
        } catch (e: ApiException) {
            if (!authStore.isCurrent(accountId, storedSession)) return null
            handleUnauthorized(e, accountId, session)
            throw e
        }
    }

    fun refreshContacts(accountId: AccountId, cursor: String = ""): AccountContactPage? {
        val account = authStore.get(accountId) ?: return null
        return try {
            val page = api(account.session)
                .contactsPage(cursor = cursor)
                .withoutUser(account.session.login)
            if (!authStore.isCurrent(account.id, account.session)) null else page.boundTo(account.id, account.session.url)
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
            handleUnauthorized(e, account.id, account.session)
            throw e
        }
    }

    fun updateContactName(accountId: AccountId, login: String, customName: String?): AccountContact? {
        val account = authStore.get(accountId) ?: return null
        return try {
            val contact = api(account.session).updateContactName(login, customName)
            if (!authStore.isCurrent(account.id, account.session)) null else AccountContact(account.id, account.session.url, contact)
        } catch (e: ApiException) {
            if (!authStore.isCurrent(account.id, account.session)) return null
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
        if (!authStore.removeIfCurrent(accountId, account.session)) return false
        onAccountRemoved(accountId, account.session)
        return true
    }

    private fun api(session: Session): HouseholdApi =
        apiFactory(session.url, session.login, session.token, session.sessionId)

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
        if (removed) onAccountRemoved(accountId, session)
    }
}

private fun ContactPage.withoutUser(login: String): ContactPage =
    copy(items = items.filterNot { it.login == login })

private fun ContactPage.boundTo(accountId: AccountId, serverUrl: String): AccountContactPage = AccountContactPage(
    accountId = accountId,
    items = items.map { contact -> AccountContact(accountId, serverUrl, contact) },
    nextCursor = nextCursor,
)

private fun CallHistoryPage.boundTo(accountId: AccountId, session: Session? = null): AccountCallHistoryPage = AccountCallHistoryPage(
    accountId = accountId,
    items = items.map { item -> AccountHistory(accountId, item) },
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
