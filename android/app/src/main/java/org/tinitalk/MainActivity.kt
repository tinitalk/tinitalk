package org.tinitalk

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSnapshot
import org.tinitalk.telecom.AudioEndpointState
import org.tinitalk.data.Contact
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private lateinit var status: TextView
    private lateinit var contacts: LinearLayout
    private lateinit var sessionControls: LinearLayout
    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private var contactItems: List<Contact> = emptyList()
    private var incomingInvite: org.tinitalk.push.IncomingInvite? = null
    private var permissionsState = AppPermissionsState()
    private var signedIn = false
    private var pushRegistrationStarted = false
    private var muted = false
    private var audioEndpoints = AudioEndpointState()
    private val incomingController = IncomingCallController()
    private val callObserver: (CallSnapshot) -> Unit = { snapshot -> runOnUiThread { renderCallState(snapshot) } }
    private val audioEndpointObserver: (AudioEndpointState) -> Unit = { state ->
        runOnUiThread {
            audioEndpoints = state
            if (CallServiceState.snapshot().phase == CallPhase.Active) renderCallState(CallServiceState.snapshot())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)

        val url = field("https://")
        val login = field("login")
        val token = field("token").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        status = TextView(this).apply { text = "Not connected" }
        contacts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connect = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                loadContacts(url.text.toString(), login.text.toString(), token.text.toString())
            }
        }
        sessionControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(url)
            addView(login)
            addView(token)
            addView(connect)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 48, 32, 32)
                addView(TextView(context).apply {
                    text = RootUiModel().title
                    textSize = 28f
                    gravity = Gravity.CENTER
                })
                addView(sessionControls)
                addView(status)
                addView(contacts)
            }
        )
        CallServiceState.observe(callObserver)
        CallAudioState.observe(audioEndpointObserver)
        refreshPermissions()
        Thread {
            runCatching { repository.restoreContacts() }
                .onSuccess { restored ->
                    restored?.let {
                        showContacts(it)
                    }
                }
                .onFailure { showError(it) }
        }.start()
    }

    private fun field(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun loadContacts(url: String, login: String, token: String) {
        status.text = "Connecting..."
        Thread {
            runCatching { repository.signIn(url, login, token) }
                .onSuccess {
                    showContacts(it)
                }
                .onFailure { showError(it) }
        }.start()
    }

    private fun showContacts(items: List<Contact>) {
        contactItems = items
        signedIn = true
        runOnUiThread {
            sessionControls.visibility = View.GONE
            refreshPermissions()
            if (permissionsState.allRequiredGranted) handleIncomingIntent(intent)
        }
    }

    private fun renderContacts() {
        if (!permissionsState.allRequiredGranted) {
            renderPermissions()
            return
        }
        status.text = "Connected"
        contacts.removeAllViews()
        contactItems.forEach { contact ->
            contacts.addView(Button(this).apply {
                text = "Call ${contact.displayName}"
                setOnClickListener {
                    CallForegroundService.startOutgoing(this@MainActivity, contact.login)
                }
            })
        }
    }

    private fun showError(error: Throwable) {
        runOnUiThread {
            status.text = error.message ?: "Connection failed"
            contacts.removeAllViews()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!permissionsState.allRequiredGranted) {
            renderPermissions()
            return
        }
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val pending = incomingController.load(this)
        val invite = IncomingCallController.inviteFrom(intent) ?: pending?.invite ?: return
        if (!invite.expiresAt.isAfter(java.time.Instant.now())) {
            incomingController.clear(this, invite.callId)
            IncomingCallNotifier(this).cancel()
            return
        }
        incomingInvite = invite
        val action = intent?.action ?: pending?.action ?: IncomingCallController.ActionIncoming
        when (action) {
            IncomingCallController.ActionAnswer -> {
                incomingController.answer(this, invite)
            }
            IncomingCallController.ActionReject -> {
                incomingController.reject(this, invite)
            }
        }
        renderCallState(CallServiceState.snapshot())
    }

    private fun renderCallState(snapshot: CallSnapshot) {
        if (!signedIn) return
        if (!permissionsState.allRequiredGranted) {
            renderPermissions()
            return
        }
        val invite = incomingInvite?.takeIf { it.expiresAt.isAfter(java.time.Instant.now()) }
        val phase = if (snapshot.phase == CallPhase.Idle && invite != null) CallPhase.Ringing else snapshot.phase
        when (snapshot.phase) {
            CallPhase.Idle -> if (phase == CallPhase.Ringing) renderIncoming(invite!!) else renderContacts()
            CallPhase.Connecting -> {
                status.text = "Calling..."
                contacts.removeAllViews()
                contacts.addView(Button(this).apply {
                    text = "Cancel"
                    setOnClickListener { CallForegroundService.end(this@MainActivity) }
                })
            }
            CallPhase.Ringing -> invite?.let(::renderIncoming)
            CallPhase.Active -> {
                status.text = "Call active"
                contacts.removeAllViews()
                contacts.addView(Button(this).apply {
                    text = if (muted) "Unmute" else "Mute"
                    setOnClickListener {
                        muted = !muted
                        CallForegroundService.mute(this@MainActivity, muted)
                        renderCallState(snapshot)
                    }
                })
                audioEndpoints.current?.let { current ->
                    contacts.addView(TextView(this).apply { text = "Audio: ${current.name}" })
                }
                snapshot.callId?.let { callId ->
                    audioEndpoints.available
                        .filter { it.id != audioEndpoints.current?.id }
                        .forEach { endpoint ->
                            contacts.addView(Button(this).apply {
                                text = "Use ${endpoint.name}"
                                setOnClickListener {
                                    CallForegroundService.selectAudioEndpoint(this@MainActivity, callId, endpoint.id)
                                }
                            })
                        }
                }
                contacts.addView(Button(this).apply {
                    text = "Hang up"
                    setOnClickListener { CallForegroundService.end(this@MainActivity) }
                })
            }
            CallPhase.Ended -> {
                incomingInvite = null
                muted = false
                renderContacts()
            }
        }
    }

    private fun renderIncoming(invite: org.tinitalk.push.IncomingInvite) {
        status.text = "Incoming call from ${invite.caller.ifEmpty { "TiniTalk" }}"
        contacts.removeAllViews()
        contacts.addView(Button(this).apply {
            text = "Accept"
            setOnClickListener {
                incomingController.answer(this@MainActivity, invite)
            }
        })
        contacts.addView(Button(this).apply {
            text = "Reject"
            setOnClickListener { incomingController.reject(this@MainActivity, invite) }
        })
    }

    override fun onDestroy() {
        CallServiceState.removeObserver(callObserver)
        CallAudioState.removeObserver(audioEndpointObserver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val microphoneGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        val fullScreenIntentGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        permissionsState = AppPermissionsState(
            notificationsGranted = notificationsGranted,
            microphoneGranted = microphoneGranted,
            fullScreenIntentGranted = fullScreenIntentGranted,
        )
        if (!signedIn || !::contacts.isInitialized) return
        if (permissionsState.allRequiredGranted) {
            registerPushToken()
            renderCallState(CallServiceState.snapshot())
        } else {
            renderPermissions()
        }
    }

    private fun renderPermissions() {
        status.text = "Permissions required"
        contacts.removeAllViews()
        permissionButton(
            title = "Notifications",
            granted = permissionsState.notificationsGranted,
            onRequest = ::requestNotificationPermission,
        )
        permissionButton(
            title = "Microphone",
            granted = permissionsState.microphoneGranted,
            onRequest = ::requestMicrophonePermission,
        )
        permissionButton(
            title = "Full-screen incoming calls",
            granted = permissionsState.fullScreenIntentGranted,
            onRequest = ::requestFullScreenIntentPermission,
        )
        contacts.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshPermissions() }
        })
    }

    private fun permissionButton(title: String, granted: Boolean, onRequest: () -> Unit) {
        contacts.addView(Button(this).apply {
            text = if (granted) "$title: allowed" else "Allow $title"
            isEnabled = !granted
            setOnClickListener { onRequest() }
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissions()
        }
    }

    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            refreshPermissions()
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.canUseFullScreenIntent()) {
            refreshPermissions()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }

    private fun registerPushToken() {
        if (pushRegistrationStarted || !permissionsState.allRequiredGranted) return
        val session = authStore.load() ?: return
        pushRegistrationStarted = true
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this))
    }

}
