package org.tinitalk.data

import com.google.gson.Gson

private const val ContactCacheVersion = 1
private const val ContactCacheKeyPrefix = "contacts_v1:"

private data class StoredContacts(
    val version: Int = ContactCacheVersion,
    val items: List<Contact> = emptyList(),
)

internal class ContactCache(private val store: KeyValueStore) {
    private val gson = Gson()

    fun load(account: AccountRecord): AccountContactPage {
        val stored = store.get(key(account.id))?.let { encoded ->
            runCatching { gson.fromJson(encoded, StoredContacts::class.java) }
                .getOrNull()
                ?.takeIf { it.version == ContactCacheVersion }
        }
        return AccountContactPage(
            account.id,
            stored?.items.orEmpty().map { AccountContact(account.id, account.session.url, it) },
        )
    }

    fun replace(page: AccountContactPage) {
        store.put(
            key(page.accountId),
            gson.toJson(StoredContacts(items = page.items.map(AccountContact::contact))),
        )
    }

    fun update(account: AccountRecord, contact: AccountContact) {
        val page = load(account)
        replace(
            page.copy(items = page.items.filterNot { it.login == contact.login } + contact),
        )
    }

    fun remove(accountId: AccountId) {
        store.remove(key(accountId))
    }

    private fun key(accountId: AccountId) = ContactCacheKeyPrefix + accountId.value
}
