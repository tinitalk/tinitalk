package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.data.AccountId
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1100dp")
class PasswordCredentialsFormTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun passwordRecoveryShowsPrefilledLoginWithoutPasswordSetupFields() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var submitted: List<String>? = null
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AddAccountScreen(
                        resetKey = 1, loading = false, errorMessage = "Войдите с новым паролем", internetAvailable = true,
                        signInRecovery = AccountSummary(AccountId("account"), "https://family.example", "alice", null),
                        onBack = {}, onAdd = { server, login, password -> submitted = listOf(server, login, password) },
                        onSetPassword = { _, _, _, _ -> error("must log in, not repeat the password change") },
                        onCheckServer = { ServerCheckResult.Available },
                    )
                }
            }
        }
        try {
            compose.onNodeWithText("Войти снова").assertExists()
            compose.onNodeWithText("alice").assertExists()
            compose.onNodeWithText("https://family.example").assertExists()
            compose.onNodeWithText("Новый пароль").assertDoesNotExist()
            compose.onNodeWithText("Повторите пароль").assertDoesNotExist()
            compose.onNodeWithText("Пароль").performScrollTo().performTextInput("new-password")
            compose.onNodeWithText("Войти").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf("https://family.example", "alice", "new-password"), submitted) }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun temporaryLoginSwitchesToSetupAndSendsBothPasswordsWithoutTrimming() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val setup = mutableStateOf(false)
        var submitted: List<String>? = null
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AddAccountScreen(
                        resetKey = 0, loading = false, errorMessage = null, internetAvailable = true,
                        onBack = { error("Setup Back must not leave account creation") },
                        onAdd = { _, _, code -> assertEquals("1234 5678", code); setup.value = true },
                        onCheckServer = { ServerCheckResult.Available },
                        passwordSetupRequired = setup.value,
                        onSetPassword = { server, login, code, password -> submitted = listOf(server, login, code, password) },
                        onCancelPasswordSetup = { setup.value = false },
                    )
                }
            }
        }
        try {
            compose.onNodeWithText("Логин").performTextInput("alice")
            compose.onNodeWithText("Пароль").performTextInput("1234 5678")
            compose.onNodeWithText("Адрес сервера").performTextInput("family.example")
            compose.onNodeWithText("Добавить").performScrollTo().performClick()
            compose.onNodeWithText("Придумайте пароль").assertExists()
            compose.onNodeWithText("Добавить аккаунт").assertDoesNotExist()
            compose.onNodeWithText("Логин").assertDoesNotExist()
            compose.onNodeWithText("Пароль").assertDoesNotExist()
            compose.onNodeWithText("Адрес сервера").assertDoesNotExist()
            compose.onNodeWithText("Временный пароль", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Новый пароль").performScrollTo().performTextInput("  личный длинный пароль  ")
            compose.onNodeWithText("Повторите пароль").performScrollTo().performTextInput("  личный длинный пароль  ")
            compose.onNodeWithText("Войти").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(listOf("https://family.example", "alice", "1234 5678", "  личный длинный пароль  "), submitted)
            }
            compose.onNodeWithContentDescription("Назад").performScrollTo().performClick()
            compose.onNodeWithText("alice").assertExists()
            compose.onNodeWithText("family.example").assertExists()
            compose.onNodeWithText("Добавить").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("Придумайте пароль").assertExists()
            compose.runOnUiThread { activity.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Добавить").performScrollTo().assertIsEnabled()
        } finally { activity.pause().stop().destroy() }
    }
}
