package org.tinitalk.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.tinitalk.data.ApiException
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactRepository

data class ContactNameUpdateState(
    val login: String? = null,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val errorMessage: String? = null,
    val authExpired: Boolean = false,
)

class ContactNameViewModel : ViewModel() {
    var state by mutableStateOf(ContactNameUpdateState())
        private set

    var updatedContacts by mutableStateOf<Map<String, Contact>>(emptyMap())
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var operationId = 0

    fun rename(repository: ContactRepository, login: String, customName: String?) {
        if (state.saving) return
        val currentOperation = ++operationId
        state = ContactNameUpdateState(login = login, saving = true)
        Thread {
            runCatching {
                repository.updateContactName(login, customName)
                    ?: error("Сеанс завершён")
            }.onSuccess { contact ->
                mainHandler.post {
                    if (currentOperation != operationId) return@post
                    updatedContacts = updatedContacts + (contact.login to contact)
                    state = ContactNameUpdateState(login = login, completed = true)
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (currentOperation != operationId) return@post
                    state = ContactNameUpdateState(
                        login = login,
                        errorMessage = contactNameError(error),
                        authExpired = error is ApiException && error.code == 401,
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
