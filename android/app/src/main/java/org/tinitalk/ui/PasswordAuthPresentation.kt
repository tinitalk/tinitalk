package org.tinitalk.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import org.tinitalk.data.ApiException

internal const val PersonalPasswordLengthMessage = "Пароль должен содержать от 8 до 128 символов"

internal fun passwordRetryDeadline(error: Throwable): Long =
    (error as? ApiException)?.takeIf { it.code == 429 }?.retryAfterSeconds
        ?.coerceIn(1, 3600)?.let { SystemClock.elapsedRealtime() + it * 1000 } ?: 0

internal fun remainingRetrySeconds(deadline: Long, now: Long): Long =
    ((deadline - now + 999) / 1000).coerceAtLeast(0)

@Composable
internal fun retrySeconds(deadline: Long): Long = produceState(
    initialValue = remainingRetrySeconds(deadline, SystemClock.elapsedRealtime()), deadline,
) {
    do {
        value = remainingRetrySeconds(deadline, SystemClock.elapsedRealtime())
        if (value > 0) delay(250)
    } while (value > 0)
}.value

internal fun personalPasswordError(password: String): String? {
    val length = password.codePointCount(0, password.length)
    return PersonalPasswordLengthMessage.takeUnless { length in 8..128 }
}

internal fun passwordConfirmationError(password: String, confirmation: String): String? =
    "Пароли не совпадают".takeUnless { password == confirmation }

internal fun passwordAuthErrorMessage(error: ApiException): String = when (error.errorCode) {
    "invalid_credentials" -> "Неверный логин или пароль"
    "temporary_password_expired" -> "Срок действия пароля истёк. Попросите администратора выдать новый"
    "temporary_password_locked" -> "Слишком много неверных попыток. Попросите администратора выдать новый пароль"
    "password_retry_later" -> error.retryAfterSeconds?.coerceAtLeast(1)?.let {
        "Слишком много попыток. Повторите через $it с"
    } ?: "Слишком много попыток. Повторите позже"
    "invalid_password" -> PersonalPasswordLengthMessage
    "auth_busy" -> "Сервер занят. Попробуйте чуть позже"
    else -> when (error.code) {
        401 -> "Неверный логин или пароль"
        429 -> error.retryAfterSeconds?.coerceAtLeast(1)?.let { "Повторите через $it с" }
            ?: "Слишком много попыток. Повторите позже"
        else -> "Сервер вернул ошибку ${error.code}"
    }
}
