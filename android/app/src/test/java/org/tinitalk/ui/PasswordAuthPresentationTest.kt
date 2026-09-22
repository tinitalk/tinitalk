package org.tinitalk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tinitalk.data.ApiException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = org.tinitalk.i18n.LocalizedTestApplication::class)
class PasswordAuthPresentationTest {
    @Test
    fun credentialPastePreservesPasswordWhitespace() {
        assertEquals(Triple("alice", "  personal password  ", "example.com"),
            splitCredentials("alice\n  personal password  \nexample.com\n"))
        assertEquals(Triple("alice", "  personal password  ", "example.com"),
            splitCredentials("alice@example.com\n  personal password  "))
    }

    @Test
    fun retryDeadlineRoundsUpAndStopsAtZero() {
        assertEquals(2L, remainingRetrySeconds(2001, 1000))
        assertEquals(1L, remainingRetrySeconds(2001, 2000))
        assertEquals(0L, remainingRetrySeconds(2001, 2001))
        assertEquals(0L, remainingRetrySeconds(2001, 5000))
    }

    @Test
    fun personalPasswordLengthUsesUnicodeCodePointsWithoutTrimming() {
        assertEquals("Пароль должен содержать от 8 до 128 символов", personalPasswordError("😀".repeat(7)))
        assertNull(personalPasswordError("😀".repeat(8)))
        assertNull(personalPasswordError("               "))
        assertEquals("Пароль должен содержать от 8 до 128 символов", personalPasswordError("😀".repeat(129)))
    }

    @Test
    fun confirmationMustMatchExactly() {
        assertEquals("Пароли не совпадают", passwordConfirmationError("long enough password", "long enough password "))
        assertNull(passwordConfirmationError("long enough password", "long enough password"))
    }

    @Test
    fun passwordApiErrorsHaveHumanReadableRussianMessages() {
        val cases = listOf(
            ApiException(401, "", errorCode = "invalid_credentials") to "Неверный логин или пароль",
            ApiException(403, "", errorCode = "temporary_password_expired") to
                "Срок действия пароля истёк. Попросите администратора выдать новый",
            ApiException(403, "", errorCode = "temporary_password_locked") to
                "Слишком много неверных попыток. Попросите администратора выдать новый пароль",
            ApiException(429, "", errorCode = "password_retry_later", retryAfterSeconds = 31) to
                "Слишком много попыток. Повторите через 31 с",
            ApiException(400, "", errorCode = "invalid_password") to
                "Пароль должен содержать от 8 до 128 символов",
            ApiException(503, "", errorCode = "auth_busy") to
                "Сервер занят. Попробуйте чуть позже",
        )

        cases.forEach { (error, expected) -> assertEquals(expected, passwordAuthErrorMessage(error)) }
    }
}
