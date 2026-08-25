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
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSnapshot
import org.tinitalk.data.Contact
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var contacts: LinearLayout
    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private var contactItems: List<Contact> = emptyList()
    private var incomingInvite: org.tinitalk.push.IncomingInvite? = null
    private var muted = false
    private val incomingController = IncomingCallController()
    private val callObserver: (CallSnapshot) -> Unit = { snapshot -> runOnUiThread { renderCallState(snapshot) } }

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
        CallServiceState.observe(callObserver)
        Thread {
            runCatching { repository.restoreContacts() }
                .onSuccess { restored ->
                    restored?.let {
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
                    registerPushToken()
                    showContacts(it)
                    runOnUiThread { handleIncomingIntent(intent) }
                }
                .onFailure { showError(it) }
        }.start()
    }

    private fun showContacts(items: List<Contact>) {
        contactItems = items
        runOnUiThread { renderCallState(CallServiceState.snapshot()) }
    }

    private fun renderContacts() {
        status.text = "Connected"
        contacts.removeAllViews()
        contactItems.forEach { contact ->
            contacts.addView(Button(this).apply {
                text = "Call ${contact.displayName}"
                setOnClickListener {
                    if (!ensureRecordAudioPermission()) return@setOnClickListener
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
        incomingInvite = invite
        val action = intent?.action ?: pending?.action ?: IncomingCallController.ActionIncoming
        when (action) {
            IncomingCallController.ActionAnswer -> {
                if (!ensureRecordAudioPermission()) return
                incomingController.answer(this, invite)
            }
            IncomingCallController.ActionReject -> {
                incomingController.reject(this, invite)
            }
        }
        renderCallState(CallServiceState.snapshot())
    }

    private fun renderCallState(snapshot: CallSnapshot) {
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
                contacts.addView(Button(this).apply {
                    text = "Hang up"
                    setOnClickListener { CallForegroundService.end(this@MainActivity) }
                })
            }
            CallPhase.Ended -> {
                incomingInvite = null
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
                if (!ensureRecordAudioPermission()) return@setOnClickListener
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

}
