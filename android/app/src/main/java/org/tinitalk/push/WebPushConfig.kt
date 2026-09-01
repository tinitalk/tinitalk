package org.tinitalk.push

import com.google.gson.annotations.SerializedName
import org.tinitalk.data.Session
import org.tinitalk.data.normalizeServerUrl

data class WebPushClientConfig(
    @SerializedName("vapid_public_key") val vapidPublicKey: String,
    @SerializedName("config_id") val configId: String,
)

data class WebPushKeys(
    @SerializedName("p256dh") val p256dh: String,
    @SerializedName("auth") val auth: String,
)

data class WebPushSubscription(
    @SerializedName("endpoint") val endpoint: String,
    @SerializedName("keys") val keys: WebPushKeys,
)

data class StoredWebPushConfig(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("vapid_public_key") val vapidPublicKey: String,
    @SerializedName("config_id") val configId: String,
)

internal fun StoredWebPushConfig.isValid(): Boolean =
    serverUrl.isNotBlank() && vapidPublicKey.isNotBlank() && configId.isNotBlank()

internal fun StoredWebPushConfig.isBoundTo(session: Session): Boolean =
    isValid() &&
        normalizeServerUrl(serverUrl) == normalizeServerUrl(session.url) &&
        configId == session.configId

internal fun WebPushSubscription.isValid(): Boolean =
    endpoint.isNotBlank() && keys.p256dh.isNotBlank() && keys.auth.isNotBlank()
