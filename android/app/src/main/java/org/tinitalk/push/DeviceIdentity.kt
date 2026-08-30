package org.tinitalk.push

import android.content.Context
import android.provider.Settings

object DeviceIdentity {
    fun id(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
}
