package org.tinitalk.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AccountStorageException
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val contactRefreshExecutor = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "tinitalk-contact-refresh").apply { isDaemon = true }
}

internal class ContactRefreshScheduler(context: Context) {
    private val app = context.applicationContext

    fun enqueue(account: AccountRecord, login: String) {
        val auth = AuthStore(SharedPreferencesKeyValueStore(app), AndroidKeystoreTokenCipher())
        val cache = ContactCache(SharedPreferencesKeyValueStore(app))
        auth.withCurrent(account.id, account.session) {
            // Invalidate responses already in flight, including a full-list refresh.
            cache.replace(cache.load(account))
        } ?: return
        val input = Data.Builder()
            .putString("account_id", account.id.value)
            .putString("session_id", account.session.sessionId)
            .putString("config_id", account.session.configId)
            .putString("contact_login", login)
            .build()
        val request = OneTimeWorkRequestBuilder<ContactRefreshWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniqueWork("contact-refresh:${account.id.value}:$login", ExistingWorkPolicy.REPLACE, request)
        // Update visible screens now; the durable job covers process death and a lost network.
        contactRefreshExecutor.execute {
            if (runCatching { refreshContact(app, input) }.isSuccess) manager.cancelWorkById(request.id)
        }
    }
}

internal class ContactRefreshWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        refreshContact(applicationContext, inputData)
        Result.success()
    } catch (_: AccountStorageException) {
        Result.success()
    } catch (error: Exception) {
        if (isTemporaryMissedCountRefreshFailure(error)) Result.retry() else Result.success()
    }
}

private fun refreshContact(context: Context, input: Data) {
    val accountId = input.getString("account_id")?.let(::AccountId) ?: return
    val login = input.getString("contact_login")?.takeIf(String::isNotBlank) ?: return
    val auth = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
    val account = auth.get(accountId) ?: return
    if (account.session.sessionId != input.getString("session_id") ||
        account.session.configId != input.getString("config_id")
    ) return
    ContactRepository(context, auth).refreshContact(accountId, login, account.session)
}
