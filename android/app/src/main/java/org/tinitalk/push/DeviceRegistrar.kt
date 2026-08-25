package org.tinitalk.push

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import org.tinitalk.data.Session
import org.tinitalk.data.UrlConnectionApiClient

class DeviceRegistrar(
    private val tokenProvider: ((String) -> Unit) -> Unit,
    private val register: (deviceId: String, token: String) -> Unit,
) {
    fun register(deviceId: String) {
        tokenProvider { token -> register(deviceId, token) }
    }

    companion object {
        fun forSession(context: Context, session: Session): DeviceRegistrar {
            @Suppress("DEPRECATION")
            fun fetchToken(callback: (String) -> Unit) {
                runCatching { FirebaseMessaging.getInstance().token }
                    .getOrNull()
                    ?.addOnSuccessListener(callback)
            }
            return DeviceRegistrar(
                tokenProvider = ::fetchToken,
                register = { deviceId, token ->
                    Thread {
                        UrlConnectionApiClient(session.url, session.login, session.token).putDevice(deviceId, token)
                    }.start()
                },
            )
        }

        fun deviceId(context: Context): String =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
    }
}
