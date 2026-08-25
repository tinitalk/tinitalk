package org.tinitalk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallPhase
import org.tinitalk.data.Contact
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import okhttp3.OkHttpClient

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var contacts: LinearLayout
    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private var socket: SignalSocket? = null
    private var coordinator: CallCoordinator? = null

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
                    setupSignal()
                    showContacts(it)
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
        socket = newSocket
        coordinator = newCoordinator
        newSocket.connect { event ->
            newCoordinator.onEvent(event)
            renderCallState()
        }
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
                CallPhase.Active -> status.text = "Call active"
                CallPhase.Ended -> status.text = "Call ended"
            }
        }
    }
}
