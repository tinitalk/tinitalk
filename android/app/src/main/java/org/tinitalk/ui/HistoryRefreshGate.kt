package org.tinitalk.ui

internal class HistoryRefreshGate {
    private var pending = false

    fun request(loading: Boolean): Boolean {
        if (loading) {
            pending = true
            return false
        }
        pending = false
        return true
    }

    fun afterLoad(): Boolean {
        val refresh = pending
        pending = false
        return refresh
    }

    fun clear() {
        pending = false
    }
}
