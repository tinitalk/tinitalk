package org.tinitalk.push

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.UrlConnectionApiClient

class DeviceRegistrar(
    private val tokenProvider: ((String) -> Unit) -> Unit,
    private val register: (deviceId: String, token: String) -> Unit,
) {
    fun register(deviceId: String) {
        tokenProvider { token -> register(deviceId, token) }
    }

    fun register(deviceId: String, token: String) {
        register.invoke(deviceId, token)
    }

    companion object {
        fun forSession(context: Context, session: Session): DeviceRegistrar {
            @Suppress("DEPRECATION")
            fun fetchToken(callback: (String) -> Unit) {
                runCatching { FirebaseMessaging.getInstance().token }
                    .getOrNull()
                    ?.addOnSuccessListener(callback)
            }
            val authStore = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
            return DeviceRegistrar(
                tokenProvider = ::fetchToken,
                register = { deviceId, token ->
                    Thread {
                        runCatching {
                            UrlConnectionApiClient(
                                session.url,
                                session.login,
                                session.token,
                                session.sessionId,
                            ).putDevice(deviceId, token)
                        }.onFailure { error ->
                            if (error is ApiException &&
                                error.code == 401 &&
                                error.authReason == SessionReplacedReason
                            ) {
                                authStore.invalidateIfCurrent(session)
                            }
                        }
                    }.start()
                },
            )
        }

        fun deviceId(context: Context): String =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
    }
}
