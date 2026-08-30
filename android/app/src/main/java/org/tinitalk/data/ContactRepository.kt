package org.tinitalk.data

import android.content.Context
import org.tinitalk.push.FirebaseBootstrap
import org.tinitalk.push.FirebaseBootstrapResult
import org.tinitalk.push.FirebaseConfigStore
import org.tinitalk.push.FirebaseRegistration
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.PushRegistrationScheduler
import org.tinitalk.push.PushRegistrationStore
import org.tinitalk.push.StoredFirebaseConfig
import org.tinitalk.push.persistRegisteredInstallation

private const val TINITALK_SERVICE = "tinitalk"
private const val SUPPORTED_API_VERSION = 3
private const val DYNAMIC_FCM_FEATURE = "dynamic_fcm_v1"

enum class CompatibilityProblem {
    WrongServer,
    ServerOutdated,
    AppOutdated,
    Unavailable,
}

enum class ServerCheckResult {
    Available,
    WrongServer,
    ServerOutdated,
    AppOutdated,
    Unavailable,
}

data class ServerCheckDetails(
    val result: ServerCheckResult,
    val apiVersion: Int? = null,
    val commit: String? = null,
)

class ServerCompatibilityException(
    val problem: CompatibilityProblem,
) : RuntimeException()

class FirebaseConfigurationRestartRequiredException : IllegalStateException(
    "Firebase configuration changed; restart the app to continue",
)

class ContactRepository internal constructor(
    private val authStore: AuthStore,
    private val firebaseConfigStore: FirebaseConfigStore?,
    private val firebaseBootstrap: FirebaseBootstrap?,
    private val firebaseRegistration: FirebaseRegistration?,
    private val onSessionActivated: (
        config: StoredFirebaseConfig,
        session: Session,
        deviceId: String,
        installationId: String,
    ) -> Unit = { _, _, _, _ -> },
    private val apiFactory: (url: String, login: String, token: String, sessionId: String?) -> HouseholdApi =
        { url, login, token, sessionId -> UrlConnectionApiClient(url, login, token, sessionId) },
) {
    constructor(authStore: AuthStore) : this(authStore, null, null, null)

    constructor(
        authStore: AuthStore,
        apiFactory: (url: String, login: String, token: String) -> HouseholdApi,
    ) : this(
        authStore,
        null,
        null,
        null,
        { _, _, _, _ -> },
        { url, login, token, _ -> apiFactory(url, login, token) },
    )

    constructor(context: Context, authStore: AuthStore) : this(
        authStore,
        FirebaseConfigStore(context),
        FirebaseBootstrap(context),
        FirebaseRegistration(),
        { config, session, deviceId, installationId ->
            persistRegisteredInstallation(
                installationId = installationId,
                config = config,
                session = session,
                deviceId = deviceId,
                store = PushRegistrationStore(context),
                enqueue = { PushRegistrationScheduler(context).enqueue() },
            )
        },
    )

    fun checkServer(url: String): ServerCheckResult {
        return checkServerDetails(url).result
    }

    fun checkServerDetails(url: String): ServerCheckDetails {
        return try {
            val normalizedUrl = url.trim().trimEnd('/')
            val info = apiFactory(normalizedUrl, "", "", null).serverInfo()
            val result = when (info.compatibilityProblem()) {
                null -> ServerCheckResult.Available
                CompatibilityProblem.WrongServer -> ServerCheckResult.WrongServer
                CompatibilityProblem.ServerOutdated -> ServerCheckResult.ServerOutdated
                CompatibilityProblem.AppOutdated -> ServerCheckResult.AppOutdated
                CompatibilityProblem.Unavailable -> ServerCheckResult.Unavailable
            }
            val isTiniTalk = info.service == TINITALK_SERVICE
            if (result == ServerCheckResult.Available) {
                authStore.updateFeatures(normalizedUrl, info.features)
            }
            ServerCheckDetails(
                result = result,
                apiVersion = info.apiVersion.takeIf { isTiniTalk && it > 0 },
                commit = info.commit?.trim()?.takeIf { isTiniTalk && it.isNotEmpty() },
            )
        } catch (_: Exception) {
            ServerCheckDetails(ServerCheckResult.Unavailable)
        }
    }

    fun signIn(url: String, login: String, token: String, deviceId: String = ""): ContactPage {
        val previous = authStore.load()
        var session = Session(normalizeServerUrl(url), login.trim(), token.trim())
        var api = api(session)
        return try {
            val info = api.requireDynamicFirebaseServer()
            require(deviceId.isNotBlank()) { "device_id is required for Firebase activation" }
            val configStore = checkNotNull(firebaseConfigStore) { "Firebase activation is unavailable" }
            val bootstrap = checkNotNull(firebaseBootstrap) { "Firebase activation is unavailable" }
            val registration = checkNotNull(firebaseRegistration) { "Firebase activation is unavailable" }
            val config = api.firebaseConfig()
            val storedConfig = configStore.save(session.url, config)
            when (bootstrap.restore()) {
                FirebaseBootstrapResult.ConfigurationMismatch -> throw FirebaseConfigurationRestartRequiredException()
                FirebaseBootstrapResult.Absent -> error("persisted Firebase configuration is unavailable")
                FirebaseBootstrapResult.Initialized,
                FirebaseBootstrapResult.AlreadyInitialized -> Unit
            }
            val firebaseInstallationId = registration.registerAndGetInstallationId()
            val sessionId = api.claimSession(deviceId, firebaseInstallationId, storedConfig.configId)
            session = session.copy(
                features = info.features,
                sessionId = sessionId,
                configId = storedConfig.configId,
            )
            check(authStore.saveIfCurrent(previous, session)) { "authentication state changed" }
            onSessionActivated(storedConfig, session, deviceId, firebaseInstallationId)
            api = api(session)
            val profile = api.me()
            api.contactsPage().withoutUser(profile.login)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun restorableSession(): Session? = authStore.loadBoundTo(firebaseConfigStore?.load())

    fun restoreContacts(): ContactPage? {
        val storedSession = restorableSession() ?: return null
        var session = storedSession
        val api = api(session)
        return try {
            val info = api.requireDynamicFirebaseServer()
            session = session.copy(features = info.features)
            val profile = api.me()
            val contacts = api.contactsPage().withoutUser(profile.login)
            if (authStore.saveIfCurrent(storedSession, session)) contacts else null
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun refreshContacts(cursor: String = ""): ContactPage? {
        val session = authStore.load() ?: return null
        return try {
            api(session)
                .contactsPage(cursor = cursor)
                .withoutUser(session.login)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun updateContactName(login: String, customName: String?): Contact? {
        val session = authStore.load() ?: return null
        return try {
            api(session).updateContactName(login, customName)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun loadCallHistory(before: Long = 0, limit: Int = 50, peerLogin: String? = null): CallHistoryPage? {
        val session = authStore.load() ?: return null
        return try {
            api(session).calls(limit, before, peerLogin)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun markCallHistoryRead(throughId: Long, peerLogin: String? = null): CallUnreadState? {
        val session = authStore.load() ?: return null
        return try {
            api(session).markCallsRead(throughId, peerLogin)
        } catch (e: ApiException) {
            handleUnauthorized(e, session)
            throw e
        }
    }

    fun signOut() {
        authStore.clear()
    }

    private fun api(session: Session): HouseholdApi =
        apiFactory(session.url, session.login, session.token, session.sessionId)

    private fun handleUnauthorized(error: ApiException, session: Session) {
        if (error.code != 401) return
        if (error.authReason == SessionReplacedReason) {
            authStore.invalidateIfCurrent(session)
        } else {
            authStore.clearIfCurrent(session)
        }
    }
}

private fun ContactPage.withoutUser(login: String): ContactPage =
    copy(items = items.filterNot { it.login == login })

private fun HouseholdApi.requireCompatibleServer(): ServerInfo {
    val info = serverInfo()
    throw ServerCompatibilityException(info.compatibilityProblem() ?: return info)
}

private fun HouseholdApi.requireDynamicFirebaseServer(): ServerInfo {
    val info = requireCompatibleServer()
    if (DYNAMIC_FCM_FEATURE !in info.features) {
        throw ServerCompatibilityException(CompatibilityProblem.ServerOutdated)
    }
    return info
}

private fun ServerInfo.compatibilityProblem(): CompatibilityProblem? = when {
    service != TINITALK_SERVICE -> CompatibilityProblem.WrongServer
    status != "ok" -> CompatibilityProblem.Unavailable
    apiVersion < SUPPORTED_API_VERSION -> CompatibilityProblem.ServerOutdated
    apiVersion > SUPPORTED_API_VERSION -> CompatibilityProblem.AppOutdated
    else -> null
}
