package org.tinitalk.permissions

data class AppPermissionsState(
    val notificationsGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val fullScreenIntentGranted: Boolean = false,
) {
    val allRequiredGranted: Boolean
        get() = notificationsGranted && microphoneGranted && fullScreenIntentGranted
}
