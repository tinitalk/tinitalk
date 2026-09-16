package org.tinitalk.missed

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.tinitalk.data.AccountId

internal class MissedCallsPreferences(private val preferences: SharedPreferences) : MissedCallsPersistence {
    override fun load(): Map<AccountId, Int> = runCatching {
        val objectValue = JSONObject(preferences.getString(MissedCountsKey, "{}") ?: "{}")
        objectValue.keys().asSequence().associate { raw -> AccountId(raw) to objectValue.optInt(raw).coerceAtLeast(0) }
    }.getOrDefault(emptyMap())

    override fun save(counts: Map<AccountId, Int>) {
        val value = JSONObject().apply { counts.forEach { (id, count) -> put(id.value, count.coerceAtLeast(0)) } }
        preferences.edit { putString(MissedCountsKey, value.toString()) }
    }
}

private const val MissedCountsKey = "account_missed_badges"
