package org.tinitalk.data

import com.google.gson.Gson

private const val ContactCacheVersion = 2
private const val ContactCacheKeyPrefix = "contacts_v1:"

private data class StoredContacts(
    val version: Int = ContactCacheVersion,
    val revision: Long = 0,
    val items: List<Contact> = emptyList(),
)

internal class ContactCache(private val store: KeyValueStore) {
    private val gson = Gson()

    private fun read(accountId: AccountId): StoredContacts {
        val encoded = store.get(key(accountId)) ?: return StoredContacts()
        val stored = runCatching { gson.fromJson(encoded, StoredContacts::class.java) }.getOrNull()
            ?: return StoredContacts()
        if (stored.version == 1) {
            // Old display/default names came from the server. Only customName is personal.
            val migrated = stored.copy(
                version = ContactCacheVersion,
                revision = stored.revision + 1,
                items = stored.items.map { it.copy(displayName = it.customName?.takeIf(String::isNotBlank) ?: it.login) },
            )
            store.put(key(accountId), gson.toJson(migrated))
            return migrated
        }
        return stored.takeIf { it.version == ContactCacheVersion } ?: StoredContacts()
    }

    fun revision(accountId: AccountId): Long = synchronized(AccountStorageLock) { read(accountId).revision }

    fun load(account: AccountRecord): AccountContactPage = synchronized(AccountStorageLock) {
        AccountContactPage(
            account.id,
            read(account.id).items.map { AccountContact(account.id, account.session.url, it) },
        )
    }

    fun replace(page: AccountContactPage, expectedRevision: Long? = null): Boolean = synchronized(AccountStorageLock) {
        val current = read(page.accountId)
        if (expectedRevision != null && current.revision != expectedRevision) return false
        store.put(
            key(page.accountId),
            gson.toJson(StoredContacts(revision = current.revision + 1, items = page.items.map(AccountContact::contact))),
        )
        ContactEvents.publish(page.accountId)
        true
    }

    fun update(account: AccountRecord, contact: AccountContact) = synchronized(AccountStorageLock) {
        val page = load(account)
        replace(
            page.copy(items = page.items.filterNot { it.login == contact.login } + contact),
        )
    }

    fun remove(account: AccountRecord, login: String) = synchronized(AccountStorageLock) {
        val page = load(account)
        replace(page.copy(items = page.items.filterNot { it.login == login }))
    }

    fun updateAvailability(
        account: AccountRecord,
        login: String,
        canCall: Boolean?,
        expectedRevision: Long? = null,
    ): Boolean = synchronized(AccountStorageLock) {
        val page = load(account)
        // A delayed push must not restore a contact deleted by this user.
        replace(page.copy(items = page.items.map {
            if (it.login == login) it.copy(contact = it.contact.copy(canCall = canCall)) else it
        }), expectedRevision)
    }

    fun updateName(account: AccountRecord, contact: AccountContact) = synchronized(AccountStorageLock) {
        val page = load(account)
        replace(page.copy(items = page.items.map {
            if (it.login == contact.login) it.copy(contact = it.contact.copy(
                displayName = contact.displayName,
                customName = contact.customName,
            )) else it
        }))
    }

    fun remove(accountId: AccountId) = synchronized(AccountStorageLock) {
        store.remove(key(accountId))
        ContactEvents.publish(accountId)
    }

    private fun key(accountId: AccountId) = ContactCacheKeyPrefix + accountId.value
}
