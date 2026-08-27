package org.tinitalk.data

class ContactRepository(
    private val authStore: AuthStore,
    private val apiFactory: (url: String, login: String, token: String) -> HouseholdApi =
        { url, login, token -> UrlConnectionApiClient(url, login, token) },
) {
    fun signIn(url: String, login: String, token: String): ContactPage {
        val session = Session(url.trim().trimEnd('/'), login.trim(), token.trim())
        val api = apiFactory(session.url, session.login, session.token)
        return try {
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
