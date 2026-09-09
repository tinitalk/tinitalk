// Compatibility fixture from commit 576e7cf; unchanged history DTO.
package org.tinitalk.compat.legacy576e7cf

import com.google.gson.annotations.SerializedName

data class CallHistoryItem(
    val id: Long,
    @SerializedName("peer_login") val peerLogin: String,
    @SerializedName("peer_name") val peerName: String,
    val direction: String,
    val outcome: String,
    val reached: Boolean,
    @SerializedName("started_at") val startedAt: Long,
    @SerializedName("duration_seconds") val durationSeconds: Long,
)
