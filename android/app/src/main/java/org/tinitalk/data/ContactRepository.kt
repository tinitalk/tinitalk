package org.tinitalk.data

private const val TINITALK_SERVICE = "tinitalk"
private const val SUPPORTED_API_VERSION = 3
private const val SINGLE_DEVICE_SESSION_FEATURE = "single_device_session"

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
) : RuntimeException()

class ContactRepository(
    private val authStore: AuthStore,
    private val apiFactory: (url: String, login: String, token: String, sessionId: String?) -> HouseholdApi =
        { url, login, token, sessionId -> UrlConnectionApiClient(url, login, token, sessionId) },
) {
    constructor(
        authStore: AuthStore,
        apiFactory: (url: String, login: String, token: String) -> HouseholdApi,
    ) : this(authStore, { url, login, token, _ -> apiFactory(url, login, token) })

    fun checkServer(url: String): ServerCheckResult {
        return checkServerDetails(url).result
    }

    fun checkServerDetails(url: String): ServerCheckDetails {
        return try {
            val normalizedUrl = url.trim().trimEnd('/')
            val info = apiFactory(normalizedUrl, "", "", null).serverInfo()
            val result = when (info.compatibilityProblem()) {
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

    fun signIn(url: String, login: String, token: String, deviceId: String = ""): ContactPage {
        val previous = authStore.load()
        var session = Session(url.trim().trimEnd('/'), login.trim(), token.trim())
        var expectedCurrent = previous
        var api = api(session)
        return try {
            val info = api.requireCompatibleServer()
            val managed = SINGLE_DEVICE_SESSION_FEATURE in info.features
            session = session.copy(
                features = info.features,
                sessionId = if (managed) {
                    require(deviceId.isNotBlank()) { "device_id is required for managed sessions" }
                    api.claimSession(deviceId)
                } else {
                    null
                },
            )
            if (managed) {
                check(authStore.saveIfCurrent(expectedCurrent, session)) { "authentication state changed" }
                expectedCurrent = session
            }
            api = api(session)
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            check(authStore.saveIfCurrent(expectedCurrent, session)) { "authentication state changed" }
            contacts
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun restoreContacts(deviceId: String = ""): ContactPage? {
        val storedSession = authStore.load() ?: return null
        var session = storedSession
        var expectedCurrent = storedSession
        var api = api(session)
        return try {
            val info = api.requireCompatibleServer()
            val claimed = SINGLE_DEVICE_SESSION_FEATURE in info.features && session.sessionId == null
            session = session.copy(
                features = info.features,
                sessionId = if (claimed) {
                    require(deviceId.isNotBlank()) { "device_id is required for managed sessions" }
                    api.claimSession(deviceId)
                } else {
                    session.sessionId
                },
            )
            if (claimed) {
                check(authStore.saveIfCurrent(expectedCurrent, session)) { "authentication state changed" }
                expectedCurrent = session
                api = api(session)
            }
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            if (authStore.saveIfCurrent(expectedCurrent, session)) contacts else null
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun refreshContacts(cursor: String = ""): ContactPage? {
        val session = authStore.load() ?: return null
        return try {
            api(session)
                .contactsPage(cursor = cursor)
                .withoutUser(session.login)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun updateContactName(login: String, customName: String?): Contact? {
        val session = authStore.load() ?: return null
        return try {
            api(session).updateContactName(login, customName)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun loadCallHistory(before: Long = 0, limit: Int = 50, peerLogin: String? = null): CallHistoryPage? {
        val session = authStore.load() ?: return null
        return try {
            api(session).calls(limit, before, peerLogin)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun markCallHistoryRead(throughId: Long, peerLogin: String? = null): CallUnreadState? {
        val session = authStore.load() ?: return null
        return try {
            api(session).markCallsRead(throughId, peerLogin)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun signOut() {
        authStore.clear()
    }

    private fun api(session: Session): HouseholdApi =
        apiFactory(session.url, session.login, session.token, session.sessionId)

    private fun handleUnauthorized(error: ApiException, session: Session) {
        if (error.code != 401) return
        if (error.authReason == SessionReplacedReason) {
            authStore.invalidateIfCurrent(session)
        } else {
            authStore.clearIfCurrent(session)
        }
    }
}

private fun ContactPage.withoutUser(login: String): ContactPage =
    copy(items = items.filterNot { it.login == login })

private fun HouseholdApi.requireCompatibleServer(): ServerInfo {
    val info = serverInfo()
    throw ServerCompatibilityException(info.compatibilityProblem() ?: return info)
}

private fun ServerInfo.compatibilityProblem(): CompatibilityProblem? = when {
    service != TINITALK_SERVICE -> CompatibilityProblem.WrongServer
    status != "ok" -> CompatibilityProblem.Unavailable
    apiVersion < SUPPORTED_API_VERSION -> CompatibilityProblem.ServerOutdated
    apiVersion > SUPPORTED_API_VERSION -> CompatibilityProblem.AppOutdated
    else -> null
}
