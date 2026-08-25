package org.tinitalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallPhase
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.Contact
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.media.WebRtcAudioSession
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController
import okhttp3.OkHttpClient

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var contacts: LinearLayout
    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private var socket: SignalSocket? = null
    private var coordinator: CallCoordinator? = null
    private var foregroundCall: ForegroundCallController? = null
    private var muted = false
    private val incomingController = IncomingCallController()

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
                addView(url)
                addView(login)
                addView(token)
                addView(connect)
                addView(status)
                addView(contacts)
            }
        )
        Thread {
            runCatching { repository.restoreContacts() }
                .onSuccess { restored ->
                    restored?.let {
                        setupSignal()
                        registerPushToken()
                        showContacts(it)
                        runOnUiThread { handleIncomingIntent(intent) }
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
                    setupSignal()
                    registerPushToken()
                    showContacts(it)
                    runOnUiThread { handleIncomingIntent(intent) }
                }
                .onFailure { showError(it) }
        }.start()
    }

    private fun showContacts(items: List<Contact>) {
        runOnUiThread {
            status.text = "Connected"
            contacts.removeAllViews()
            items.forEach { contact ->
                contacts.addView(Button(this).apply {
                    text = "Call ${contact.displayName}"
                    setOnClickListener {
                        if (!ensureRecordAudioPermission()) return@setOnClickListener
                        runCatching { coordinator?.startCall(contact.login) }
                            .onSuccess { renderCallState() }
                            .onFailure { showError(it) }
                    }
                })
            }
        }
    }

    private fun showError(error: Throwable) {
        runOnUiThread {
            status.text = error.message ?: "Connection failed"
            contacts.removeAllViews()
        }
    }

    private fun setupSignal() {
        val session = authStore.load() ?: return
        socket?.close()
        val newSocket = SignalSocket(OkHttpClient(), session)
        val newCoordinator = CallCoordinator(session.login, newSocket)
        val newForegroundCall = ForegroundCallController(
            signal = newSocket,
            mediaFactory = { _, iceServers, onLocalIce, onIceRestartNeeded ->
                WebRtcAudioSession.create(
                    this,
                    iceServers = iceServers,
                    onLocalIceCandidate = onLocalIce,
                    onIceRestartNeeded = onIceRestartNeeded,
                )
            },
        )
        socket = newSocket
        coordinator = newCoordinator
        foregroundCall = newForegroundCall
        runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).registerAudioOnly() }
        newSocket.connect(
            onEvent = { event ->
                if (newCoordinator.onEvent(event)) {
                    newForegroundCall.onSignalEvent(newCoordinator.snapshot(), event.event)
                }
                renderCallState()
            },
            onOpen = { newCoordinator.resume() },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val pending = incomingController.load(this)
        val invite = IncomingCallController.inviteFrom(intent) ?: pending?.invite ?: return
        if (!invite.expiresAt.isAfter(java.time.Instant.now())) {
            incomingController.clear(this)
            IncomingCallNotifier(this).cancel()
            return
        }
        val action = intent?.action ?: pending?.action ?: IncomingCallController.ActionIncoming
        coordinator?.restoreIncoming(invite.callId, invite.lastSeq)
        when (action) {
            IncomingCallController.ActionAnswer -> {
                if (!ensureRecordAudioPermission()) return
                startCallService()
                coordinator?.resume()
                coordinator?.accept()
                IncomingCallNotifier(this).cancel()
                incomingController.clear(this)
            }
            IncomingCallController.ActionReject -> {
                coordinator?.reject()
                IncomingCallNotifier(this).cancel()
                incomingController.clear(this)
            }
        }
        renderCallState()
    }

    private fun renderCallState() {
        val snapshot = coordinator?.snapshot() ?: return
        runOnUiThread {
            when (snapshot.phase) {
                CallPhase.Idle -> status.text = "Connected"
                CallPhase.Connecting -> {
                    status.text = "Calling..."
                    contacts.removeAllViews()
                    contacts.addView(Button(this).apply {
                        text = "Cancel"
                        setOnClickListener {
                            coordinator?.cancel()
                            renderCallState()
                        }
                    })
                }
                CallPhase.Ringing -> {
                    status.text = "Incoming call"
                    contacts.removeAllViews()
                    contacts.addView(Button(this).apply {
                        text = "Accept"
                        setOnClickListener {
                            if (!ensureRecordAudioPermission()) return@setOnClickListener
                            coordinator?.accept()
                            renderCallState()
                        }
                    })
                    contacts.addView(Button(this).apply {
                        text = "Reject"
                        setOnClickListener {
                            coordinator?.reject()
                            renderCallState()
                        }
                    })
                }
                CallPhase.Active -> {
                    startCallService()
                    status.text = "Call active"
                    contacts.removeAllViews()
                    contacts.addView(Button(this).apply {
                        text = if (muted) "Unmute" else "Mute"
                        setOnClickListener {
                            muted = !muted
                            foregroundCall?.setMuted(muted)
                            renderCallState()
                        }
                    })
                    contacts.addView(Button(this).apply {
                        text = "Hang up"
                        setOnClickListener {
                            coordinator?.cancel()
                            cleanupCall()
                            renderCallState()
                        }
                    })
                }
                CallPhase.Ended -> {
                    cleanupCall()
                    status.text = "Call ended"
                }
            }
        }
    }

    override fun onDestroy() {
        socket?.close()
        foregroundCall?.close()
        super.onDestroy()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 20)
        return false
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 21)
    }

    private fun registerPushToken() {
        ensureNotificationPermission()
        val session = authStore.load() ?: return
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this))
    }

    private fun startCallService() {
        val intent = Intent(this, CallForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun cleanupCall() {
        foregroundCall?.close()
        IncomingCallNotifier(this).cancel()
        incomingController.clear(this)
        stopService(Intent(this, CallForegroundService::class.java))
    }
}
