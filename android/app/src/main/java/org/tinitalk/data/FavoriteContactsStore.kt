package org.tinitalk.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Local presentation preferences, independent of server availability and contact names. */
internal class FavoriteContactsStore(context: Context) {
    val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences("favorite_contacts", Context.MODE_PRIVATE)

    fun load(): List<AccountPeerKey> = runCatching {
        val rows = JSONArray(preferences.getString("order", "[]"))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            AccountPeerKey(AccountId(row.getString("account")), row.getString("login"))
        }.distinct()
    }.getOrDefault(emptyList())

    fun setFavorite(peer: AccountPeerKey, favorite: Boolean, position: Int = Int.MAX_VALUE) {
        val current = load()
        if (favorite && peer in current) return
        val updated = current.filterNot { it == peer }.toMutableList()
        if (favorite) updated.add(position.coerceIn(0, updated.size), peer)
        save(updated)
    }

    fun reorder(visibleOrder: List<AccountPeerKey>) {
        val current = load()
        val ordered = visibleOrder.distinct().filter { it in current }
        val moving = ordered.toSet()
        val iterator = ordered.iterator()
        // Keep unavailable accounts' positions; a refresh must never erase their favorites.
        save(current.map { if (it in moving) iterator.next() else it })
    }

    fun removeAccount(accountId: AccountId) = save(load().filterNot { it.accountId == accountId })

    private fun save(peers: List<AccountPeerKey>) {
        val rows = JSONArray()
        peers.forEach { peer ->
            rows.put(JSONObject().put("account", peer.accountId.value).put("login", peer.login))
        }
        preferences.edit { putString("order", rows.toString()) }
    }
}
