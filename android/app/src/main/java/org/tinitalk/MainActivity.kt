package org.tinitalk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var contacts: TextView
    private lateinit var repository: ContactRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)

        val url = field("https://")
        val login = field("login")
        val token = field("token").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        status = TextView(this).apply { text = "Not connected" }
        contacts = TextView(this)
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
                .onSuccess { restored -> restored?.let { showContacts(it) } }
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
                .onSuccess { showContacts(it) }
                .onFailure { showError(it) }
        }.start()
    }

    private fun showContacts(items: List<org.tinitalk.data.Contact>) {
        runOnUiThread {
            status.text = "Connected"
            contacts.text = items.joinToString(separator = "\n") { it.displayName }
        }
    }

    private fun showError(error: Throwable) {
        runOnUiThread {
            status.text = error.message ?: "Connection failed"
            contacts.text = ""
        }
    }
}
