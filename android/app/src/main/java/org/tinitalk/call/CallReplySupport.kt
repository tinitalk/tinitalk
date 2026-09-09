package org.tinitalk.call

import org.tinitalk.data.ServerInfo

internal const val CallReplyFeature = "call_reply_v1"

/** A fresh health request owns its result; previous calls and sessions cannot enable replies. */
internal class CallReplySupport {
    data class Request(val owner: AccountCallOwner, val generation: Long)

    private var generation = 0L
    private var current: Request? = null
    private var supported = false

    @Synchronized fun begin(owner: AccountCallOwner): Request {
        supported = false
        return Request(owner, ++generation).also { current = it }
    }

    @Synchronized fun complete(request: Request, info: ServerInfo?): Boolean {
        if (current != request) return false
        supported = info?.service == "tinitalk" && info.status == "ok" &&
            info.apiVersion == 4 && CallReplyFeature in info.features
        return true
    }

    @Synchronized fun isSupported(owner: AccountCallOwner): Boolean = current?.owner == owner && supported

    @Synchronized fun clear() {
        current = null
        supported = false
    }
}
