package org.tinitalk.push

import android.content.Context
import org.tinitalk.data.AccountId
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class WebPushEndpointHandoff {
    private val waiters = ConcurrentHashMap<AccountId, CompletableFuture<WebPushSubscription>>()

    fun begin(accountId: AccountId): CompletableFuture<WebPushSubscription> {
        val future = CompletableFuture<WebPushSubscription>()
        waiters.put(accountId, future)?.completeExceptionally(
            IllegalStateException("WebPush registration was restarted"),
        )
        return future
    }

    fun complete(accountId: AccountId, subscription: WebPushSubscription): Boolean =
        waiters.remove(accountId)?.complete(subscription) == true

    fun fail(accountId: AccountId, error: Throwable): Boolean =
        waiters.remove(accountId)?.completeExceptionally(error) == true

    fun cancel(accountId: AccountId) {
        waiters.remove(accountId)?.cancel(false)
    }
}

internal val GlobalWebPushEndpointHandoff = WebPushEndpointHandoff()

internal interface AccountWebPushRegistration {
    fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription
    fun restore(accountId: AccountId, config: StoredWebPushConfig)
    fun unsubscribe(accountId: AccountId)
}

internal class UnifiedPushAccountRegistration(
    context: Context,
    private val handoff: WebPushEndpointHandoff = GlobalWebPushEndpointHandoff,
) : AccountWebPushRegistration {
    private val applicationContext = context.applicationContext

    override fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription {
        val endpoint = handoff.begin(accountId)
        try {
            restore(accountId, config)
            return endpoint.get(RegistrationTimeoutSeconds, TimeUnit.SECONDS)
        } catch (error: Exception) {
            handoff.cancel(accountId)
            throw error
        }
    }

    override fun restore(accountId: AccountId, config: StoredWebPushConfig) {
        require(config.isValid()) { "invalid WebPush configuration" }
        UnifiedPush.saveDistributor(applicationContext, applicationContext.packageName)
        UnifiedPush.register(
            applicationContext,
            accountId.value,
            config.serverUrl,
            config.vapidPublicKey,
        )
    }

    override fun unsubscribe(accountId: AccountId) {
        handoff.cancel(accountId)
        UnifiedPush.unregister(applicationContext, accountId.value)
    }

    private companion object {
        const val RegistrationTimeoutSeconds = 30L
    }
}
