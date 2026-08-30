package org.tinitalk.ui

internal fun isHistoryVisibleToUser(activityResumed: Boolean, historySelected: Boolean): Boolean =
    activityResumed && historySelected

internal fun shouldMarkHistoryRead(
    userInitiated: Boolean,
    activityResumed: Boolean,
    historySelected: Boolean,
): Boolean = userInitiated && isHistoryVisibleToUser(activityResumed, historySelected)

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
