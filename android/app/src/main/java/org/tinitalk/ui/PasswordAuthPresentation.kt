package org.tinitalk.ui

import org.tinitalk.i18n.appString

import org.tinitalk.R

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import org.tinitalk.data.ApiException

internal val PersonalPasswordLengthMessage: String get() = appString(R.string.text_password_must_contain_8_to_128_characters_309)

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
    appString(R.string.text_passwords_do_not_match_310).takeUnless { password == confirmation }

internal fun passwordAuthErrorMessage(error: ApiException): String = when (error.errorCode) {
    "invalid_credentials" -> appString(R.string.text_incorrect_username_or_password_21)
    "temporary_password_expired" -> appString(R.string.text_password_expired_ask_your_administrator_for_a_new_one_311)
    "temporary_password_locked" -> appString(R.string.text_too_many_incorrect_attempts_ask_your_administrator_for_a_new_pass_312)
    "password_retry_later" -> error.retryAfterSeconds?.coerceAtLeast(1)?.let {
        appString(R.string.text_too_many_attempts_try_again_in_value_s_313, it)
    } ?: appString(R.string.text_too_many_attempts_try_again_later_314)
    "invalid_password" -> PersonalPasswordLengthMessage
    "auth_busy" -> appString(R.string.text_the_server_is_busy_try_again_later_315)
    else -> when (error.code) {
        401 -> appString(R.string.text_incorrect_username_or_password_21)
        429 -> error.retryAfterSeconds?.coerceAtLeast(1)?.let { appString(R.string.text_try_again_in_value_s_316, it) }
            ?: appString(R.string.text_too_many_attempts_try_again_later_314)
        else -> appString(R.string.text_the_server_returned_error_value_23, error.code)
    }
}
