package org.tinitalk.data

class ContactPhotoAccountLifecycle(
    private val store: ContactPhotoStore,
    private val isServerOwned: (serverUrl: String) -> Boolean,
) {
    private val lock = Any()

    fun activateServer(serverUrl: String) = synchronized(lock) {
        normalizeServerUrl(serverUrl)
        Unit
    }

    fun removeServerAfterExplicitLogout(serverUrl: String): Result<Boolean> = synchronized(lock) {
        val normalized = normalizeServerUrl(serverUrl)
        if (isServerOwned(normalized)) {
            Result.success(false)
        } else {
            store.removeServer(normalized)
        }
    }
}
