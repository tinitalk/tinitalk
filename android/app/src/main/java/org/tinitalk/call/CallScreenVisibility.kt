package org.tinitalk.call

/** Resumed call screens that can present a reply for the displayed outgoing call. */
internal object CallScreenVisibility {
    private val screens = mutableMapOf<Any, AccountCallKey>()

    @Synchronized
    fun update(screen: Any, key: AccountCallKey?) {
        if (key == null) screens.remove(screen) else screens[screen] = key
    }

    @Synchronized
    fun isShowing(key: AccountCallKey): Boolean = key in screens.values
}
