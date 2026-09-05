package org.tinitalk.push

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object DeviceIdentity {
    // Part of existing server sessions and push bindings, not used for tracking.
    // Changing it requires migrating device registrations, not just replacing this getter.
    @SuppressLint("HardwareIds")
    fun id(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
}
