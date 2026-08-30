package org.tinitalk.push

import com.google.gson.annotations.SerializedName

data class FirebaseClientConfig(
    @SerializedName("application_id") val applicationId: String,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("project_id") val projectId: String,
    @SerializedName("gcm_sender_id") val gcmSenderId: String,
    @SerializedName("config_id") val configId: String,
)
