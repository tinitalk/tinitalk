package org.tinitalk.data

class ContactRepository(
    private val authStore: AuthStore,
    private val apiFactory: (url: String, login: String, token: String) -> HouseholdApi =
        { url, login, token -> UrlConnectionApiClient(url, login, token) },
) {
    fun signIn(url: String, login: String, token: String): List<Contact> {
        val session = Session(url.trim().trimEnd('/'), login.trim(), token.trim())
        val api = apiFactory(session.url, session.login, session.token)
        return try {
            val profile = api.me()
            val contacts = api.contacts()
                .filterNot { it.login == profile.login }
                .sortedWith(contactOrder)
            authStore.save(session)
            contacts
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun restoreContacts(): List<Contact>? {
        val session = authStore.load() ?: return null
        val api = apiFactory(session.url, session.login, session.token)
        return try {
            val profile = api.me()
            api.contacts()
                .filterNot { it.login == profile.login }
                .sortedWith(contactOrder)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun updateContactName(login: String, customName: String?): Contact? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).updateContactName(login, customName)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun loadCallHistory(before: Long = 0, limit: Int = 50, peerLogin: String? = null): CallHistoryPage? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).calls(limit, before, peerLogin)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun markCallHistoryRead(throughId: Long, peerLogin: String? = null): Int? {
        val session = authStore.load() ?: return null
        return try {
            apiFactory(session.url, session.login, session.token).markCallsRead(throughId, peerLogin)
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun signOut() {
        authStore.clear()
    }
}

private val contactOrder = compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName.trim() }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.login }
