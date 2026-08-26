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
            val contacts = api.contacts().filterNot { it.login == profile.login }
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
            api.contacts().filterNot { it.login == profile.login }
        } catch (e: ApiException) {
            if (e.code == 401) authStore.clear()
            throw e
        }
    }

    fun signOut() {
        authStore.clear()
    }
}
