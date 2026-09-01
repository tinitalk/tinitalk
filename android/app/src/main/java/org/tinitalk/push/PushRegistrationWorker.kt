package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.tinitalk.R
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountStorageException
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthRemovalReason
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.UrlConnectionApiClient
import org.tinitalk.data.normalizeServerUrl
import org.tinitalk.data.sameIdentity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal enum class PushRegistrationAttemptResult {
    SUCCESS,
    RETRY,
    REFRESH_CONFIG,
}

internal class PushRegistrationRunner(
    private val accountId: AccountId,
    private val registrationStore: PushRegistrationStore,
    private val authStore: AuthStore,
    private val deviceId: () -> String,
    private val onAccountRemoved: (AccountId) -> Unit,
    private val upload: (Session, PendingPushRegistration) -> Unit,
) {
    fun runAttempt(): PushRegistrationAttemptResult {
        val account = try {
            authStore.get(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.SUCCESS
        } catch (_: Exception) {
            return PushRegistrationAttemptResult.RETRY
        }
        val config = authStore.webPushConfig(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
        val pending = try {
            registrationStore.loadBoundTo(accountId, config, account.session, deviceId())
                ?: return PushRegistrationAttemptResult.SUCCESS
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.SUCCESS
        } catch (_: Exception) {
            return PushRegistrationAttemptResult.RETRY
        }
        try {
            val current = authStore.get(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
            if (!current.session.sameIdentity(account.session) || registrationStore.load(accountId) != pending) {
                return PushRegistrationAttemptResult.SUCCESS
            }
            upload(account.session, pending)
        } catch (_: IOException) {
            return PushRegistrationAttemptResult.RETRY
        } catch (error: ApiException) {
            return handleApiFailure(error, account.session)
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.RETRY
        }
        return when (registrationStore.clearIfGeneration(accountId, pending.generation)) {
            PushRegistrationClearResult.CLEARED,
            PushRegistrationClearResult.STALE,
            -> PushRegistrationAttemptResult.SUCCESS
            PushRegistrationClearResult.FAILED -> PushRegistrationAttemptResult.RETRY
        }
    }

    private fun handleApiFailure(error: ApiException, session: Session): PushRegistrationAttemptResult = try {
        when {
            error.code == 408 || error.code == 429 || error.code in 500..599 ->
                PushRegistrationAttemptResult.RETRY
            error.code == 401 && error.authReason == SessionReplacedReason -> {
                removeAccount(session, AuthRemovalReason.SessionReplaced)
                PushRegistrationAttemptResult.SUCCESS
            }
            error.code == 409 -> PushRegistrationAttemptResult.REFRESH_CONFIG
            else -> PushRegistrationAttemptResult.SUCCESS
        }
    } catch (_: AccountStorageException) {
        PushRegistrationAttemptResult.RETRY
    }

    private fun removeAccount(session: Session, reason: AuthRemovalReason) {
        if (authStore.invalidateIfCurrent(accountId, session, reason)) {
            runCatching { onAccountRemoved(accountId) }
        }
    }
}

internal class StaleWebPushConfigRefresher(
    private val accountId: AccountId,
    private val authStore: AuthStore,
    private val deviceId: String,
    private val fetch: (Session) -> WebPushClientConfig,
    private val subscribe: (AccountId, StoredWebPushConfig) -> WebPushSubscription,
    private val onStaleSubscription: (AccountId) -> Unit,
) {
    fun refresh(expected: Session): Boolean {
        val remote = fetch(expected)
        val config = StoredWebPushConfig(
            serverUrl = normalizeServerUrl(expected.url),
            vapidPublicKey = remote.vapidPublicKey,
            configId = remote.configId,
        )
        require(config.isValid()) { "invalid WebPush configuration" }
        if (!authStore.isCurrent(accountId, expected)) return false
        val subscription = subscribe(accountId, config)
        val refreshed = expected.copy(configId = config.configId)
        val committed = try {
            authStore.activateWebPushRegistrationIfCurrent(
                accountId,
                expected,
                refreshed,
                config,
                deviceId,
                subscription,
            )
        } catch (error: Exception) {
            runCatching { onStaleSubscription(accountId) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        if (!committed) {
            onStaleSubscription(accountId)
            return false
        }
        return true
    }
}

internal fun recoverCurrentWebPushRegistration(
    accountId: AccountId,
    authStore: AuthStore,
    registration: AccountWebPushRegistration,
) {
    while (true) {
        val current = authStore.get(accountId)
        if (current == null) return
        val snapshot = authStore.withCurrent(accountId, current.session) {
            CurrentWebPushConfig(authStore.webPushConfig(accountId))
        } ?: continue
        val config = snapshot.value ?: return
        registration.restore(accountId, config)
        val unchanged = authStore.withCurrent(accountId, current.session) {
            authStore.webPushConfig(accountId) == config
        }
        if (unchanged == true) return
    }
}

private data class CurrentWebPushConfig(val value: StoredWebPushConfig?)

internal fun runPushRegistrationAttempt(
    attempt: () -> PushRegistrationAttemptResult,
    refreshConfig: () -> Boolean,
): PushRegistrationAttemptResult {
    val first = attempt()
    if (first != PushRegistrationAttemptResult.REFRESH_CONFIG) return first
    return try {
        if (refreshConfig()) attempt() else PushRegistrationAttemptResult.SUCCESS
    } catch (_: IOException) {
        PushRegistrationAttemptResult.RETRY
    } catch (error: ApiException) {
        if (error.code == 408 || error.code == 429 || error.code in 500..599) {
            PushRegistrationAttemptResult.RETRY
        } else {
            PushRegistrationAttemptResult.SUCCESS
        }
    } catch (_: Exception) {
        PushRegistrationAttemptResult.RETRY
    }
}

class PushRegistrationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ForegroundChannelId,
                "Подключение TiniTalk",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        val notification = Notification.Builder(applicationContext, ForegroundChannelId)
            .setSmallIcon(R.drawable.ic_server_available)
            .setContentTitle("TiniTalk")
            .setContentText("Обновляем подключение к серверу")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        return ForegroundInfo(foregroundNotificationId(), notification)
    }

    override fun doWork(): Result {
        val accountId = inputData.getString(AccountIdInputKey)
            ?.let { value -> runCatching { AccountId(value) }.getOrNull() }
            ?: return Result.success()
        return synchronized(AccountLocks.computeIfAbsent(accountId) { Any() }) {
            // Cancelling a synchronous Worker does not interrupt doWork(). Let an older run
            // finish first, then skip cancelled waiters so the newest endpoint is written last.
            if (isStopped) Result.success() else runForAccount(accountId)
        }
    }

    private fun runForAccount(accountId: AccountId): Result {
        val authStore = AuthStore(
            SharedPreferencesKeyValueStore(applicationContext),
            AndroidKeystoreTokenCipher(),
        )
        val config = authStore.webPushConfig(accountId) ?: return Result.success()
        val account = try {
            authStore.get(accountId) ?: return Result.success()
        } catch (_: AccountStorageException) {
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
        val registrationStore = PushRegistrationStore(applicationContext)
        val deviceId = DeviceIdentity.id(applicationContext)
        val pending = registrationStore.loadBoundTo(accountId, config, account.session, deviceId)
        val endpointSubscription = inputData.webPushSubscription()
        if (endpointSubscription != null && pending?.subscription != endpointSubscription) {
            try {
                registrationStore.upsert(accountId, account.session, deviceId, endpointSubscription)
            } catch (_: Exception) {
                return Result.retry()
            }
        } else if (endpointSubscription == null &&
            (inputData.getBoolean(ForceRefreshInputKey, false) || pending == null)
        ) {
            val subscription = try {
                UnifiedPushAccountRegistration(applicationContext).subscribe(accountId, config)
            } catch (_: Exception) {
                return Result.retry()
            }
            try {
                registrationStore.upsert(accountId, account.session, deviceId, subscription)
            } catch (_: Exception) {
                return Result.retry()
            }
        }

        val runner = PushRegistrationRunner(
            accountId = accountId,
            registrationStore = registrationStore,
            authStore = authStore,
            deviceId = { deviceId },
            onAccountRemoved = { id -> cleanupWebPushAccount(applicationContext, id) },
            upload = { session, pending ->
                UrlConnectionApiClient(
                    session.url,
                    session.login,
                    session.token,
                    session.sessionId,
                ).putDevice(pending.deviceId, pending.subscription, pending.configId)
            },
        )
        val finalAttempt = try {
            runPushRegistrationAttempt(
                attempt = runner::runAttempt,
                refreshConfig = {
                    val registration = UnifiedPushAccountRegistration(applicationContext)
                    StaleWebPushConfigRefresher(
                        accountId = accountId,
                        authStore = authStore,
                        deviceId = deviceId,
                        fetch = { session ->
                            UrlConnectionApiClient(
                                session.url,
                                session.login,
                                session.token,
                                session.sessionId,
                            ).webPushConfig()
                        },
                        subscribe = { id, refreshedConfig ->
                            registration.subscribe(id, refreshedConfig)
                        },
                        onStaleSubscription = { id ->
                            recoverCurrentWebPushRegistration(id, authStore, registration)
                        },
                    ).refresh(account.session)
                },
            )
        } catch (_: Exception) {
            return Result.retry()
        }
        return when (finalAttempt) {
            PushRegistrationAttemptResult.SUCCESS -> Result.success()
            PushRegistrationAttemptResult.RETRY -> Result.retry()
            PushRegistrationAttemptResult.REFRESH_CONFIG -> Result.retry()
        }
    }

    companion object {
        const val AccountIdInputKey = "account_id"
        const val ForceRefreshInputKey = "force_refresh"
        const val EndpointInputKey = "endpoint"
        const val P256dhInputKey = "p256dh"
        const val AuthInputKey = "auth"
        private const val ForegroundChannelId = "push-registration"
        private const val ForegroundNotificationIdPrefix = 0x40000000

        // Keep locks for the process lifetime: removing one while a cancelled worker waits on it
        // could create a second lock and allow two registrations for one account to overlap.
        private val AccountLocks = ConcurrentHashMap<AccountId, Any>()
    }

    private fun foregroundNotificationId(): Int = ForegroundNotificationIdPrefix or
        ((inputData.getString(AccountIdInputKey)?.hashCode() ?: id.hashCode()) and 0x3fffffff)
}

private fun androidx.work.Data.webPushSubscription(): WebPushSubscription? {
    val subscription = WebPushSubscription(
        endpoint = getString(PushRegistrationWorker.EndpointInputKey) ?: return null,
        keys = WebPushKeys(
            p256dh = getString(PushRegistrationWorker.P256dhInputKey) ?: return null,
            auth = getString(PushRegistrationWorker.AuthInputKey) ?: return null,
        ),
    )
    return subscription.takeIf(WebPushSubscription::isValid)
}
