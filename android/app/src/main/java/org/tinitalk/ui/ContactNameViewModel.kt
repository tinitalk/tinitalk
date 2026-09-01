package org.tinitalk.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.tinitalk.data.ApiException
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactRepository

data class ContactNameUpdateState(
    val key: AccountPeerKey? = null,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val errorMessage: String? = null,
    val authExpired: Boolean = false,
    val authReason: String? = null,
) {
    val login: String? get() = key?.login
}

class ContactNameViewModel : ViewModel() {
    var state by mutableStateOf(ContactNameUpdateState())
        private set

    var updatedContacts by mutableStateOf<Map<AccountPeerKey, Contact>>(emptyMap())
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var operationId = 0

    fun rename(repository: ContactRepository, key: AccountPeerKey, customName: String?) {
        if (state.saving) return
        val currentOperation = ++operationId
        state = ContactNameUpdateState(key = key, saving = true)
        Thread {
            runCatching {
                repository.updateContactName(key.accountId, key.login, customName)?.contact
                    ?: error("Сеанс завершён")
            }.onSuccess { contact ->
                mainHandler.post {
                    if (currentOperation != operationId) return@post
                    updatedContacts = updatedContacts + (key to contact)
                    state = ContactNameUpdateState(key = key, completed = true)
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (currentOperation != operationId) return@post
                    state = ContactNameUpdateState(
                        key = key,
                        errorMessage = contactNameError(error),
                        authExpired = error is ApiException && error.code == 401,
                        authReason = (error as? ApiException)?.authReason,
                    )
                }
            }
        }.start()
    }

    fun clearResult() {
        if (!state.saving) state = ContactNameUpdateState()
    }

    fun reset() {
        operationId++
        state = ContactNameUpdateState()
        updatedContacts = emptyMap()
    }
}

private fun contactNameError(error: Throwable): String = when (error) {
    is ApiException -> when (error.code) {
        400 -> "Проверьте имя контакта"
        404 -> "Контакт больше недоступен"
        else -> "Не удалось сохранить имя. Попробуйте ещё раз"
    }
    else -> "Не удалось сохранить имя. Проверьте соединение"
}
