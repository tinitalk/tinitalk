package org.tinitalk.data

private const val TINITALK_SERVICE = "tinitalk"
private const val SUPPORTED_API_VERSION = 2

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
    private val apiFactory: (url: String, login: String, token: String) -> HouseholdApi =
        { url, login, token -> UrlConnectionApiClient(url, login, token) },
) {
    fun checkServer(url: String): ServerCheckResult {
        return checkServerDetails(url).result
    }

    fun checkServerDetails(url: String): ServerCheckDetails {
        return try {
            val info = apiFactory(url.trim().trimEnd('/'), "", "").serverInfo()
            val result = when (info.compatibilityProblem()) {
                null -> ServerCheckResult.Available
                CompatibilityProblem.WrongServer -> ServerCheckResult.WrongServer
                CompatibilityProblem.ServerOutdated -> ServerCheckResult.ServerOutdated
                CompatibilityProblem.AppOutdated -> ServerCheckResult.AppOutdated
                CompatibilityProblem.Unavailable -> ServerCheckResult.Unavailable
            }
            val isTiniTalk = info.service == TINITALK_SERVICE
            ServerCheckDetails(
                result = result,
                apiVersion = info.apiVersion.takeIf { isTiniTalk && it > 0 },
                commit = info.commit?.trim()?.takeIf { isTiniTalk && it.isNotEmpty() },
            )
        } catch (_: Exception) {
            ServerCheckDetails(ServerCheckResult.Unavailable)
        }
    }

    fun signIn(url: String, login: String, token: String): ContactPage {
        val session = Session(url.trim().trimEnd('/'), login.trim(), token.trim())
        val api = apiFactory(session.url, session.login, session.token)
        return try {
            api.requireCompatibleServer()
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            authStore.save(session)
            contacts
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun restoreContacts(): ContactPage? {
        val session = authStore.load() ?: return null
        val api = apiFactory(session.url, session.login, session.token)
        return try {
            api.requireCompatibleServer()
            val profile = api.me()
            api.contactsPage().withoutUser(profile.login)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun refreshContacts(cursor: String = ""): ContactPage? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token)
                .contactsPage(cursor = cursor)
                .withoutUser(session.login)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun updateContactName(login: String, customName: String?): Contact? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).updateContactName(login, customName)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun loadCallHistory(before: Long = 0, limit: Int = 50, peerLogin: String? = null): CallHistoryPage? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).calls(limit, before, peerLogin)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun markCallHistoryRead(throughId: Long, peerLogin: String? = null): Int? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).markCallsRead(throughId, peerLogin)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clearIfCurrent(session)
            throw e
        }
    }

    fun signOut() {
        authStore.clear()
    }
}

private fun ContactPage.withoutUser(login: String): ContactPage =
    copy(items = items.filterNot { it.login == login })

private fun HouseholdApi.requireCompatibleServer() {
    throw ServerCompatibilityException(serverInfo().compatibilityProblem() ?: return)
}

private fun ServerInfo.compatibilityProblem(): CompatibilityProblem? = when {
    service != TINITALK_SERVICE -> CompatibilityProblem.WrongServer
    status != "ok" -> CompatibilityProblem.Unavailable
    apiVersion < SUPPORTED_API_VERSION -> CompatibilityProblem.ServerOutdated
    apiVersion > SUPPORTED_API_VERSION -> CompatibilityProblem.AppOutdated
    else -> null
}
