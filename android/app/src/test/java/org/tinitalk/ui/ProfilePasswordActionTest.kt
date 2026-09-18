package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.data.AccountId
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.theme.TiniTalkTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class ProfilePasswordActionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun noPasswordShowsSetActionOnlyAfterCheck() = checkAction(false)
    @Test fun existingPasswordShowsChangeActionOnlyAfterCheck() = checkAction(true)
    @Test fun oldServerDoesNotShowActionEvenWithCachedPassword() = checkAction(null)
    @Test fun failedCheckDoesNotShowActionEvenWithCachedPassword() = checkAction(null, fail = true)

    @Test fun logoutWithoutPasswordShowsRegularConfirmationAndDoesNotOpenPasswordForm() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val accountId = AccountId("account")
        var removed: AccountId? = null
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ProfileScreen(
                        accounts = listOf(AccountSummary(accountId, "https://family.example", "alice", null, passwordSet = false)),
                        internetAvailable = true,
                        onCheckServer = { ServerCheckDetails(ServerCheckResult.Available) },
                        onCheckPasswordSet = { false },
                        onBack = {}, onAdd = {}, onRemoveAccount = { removed = it },
                        onChangePassword = { _, _, _ -> error("logout must not set a password") },
                    )
                }
            }
        }
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Задать пароль").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Выйти").performClick()
            compose.onNodeWithText("Выйти из аккаунта?").assertExists()
            compose.onNodeWithText("Новый пароль").assertDoesNotExist()
            compose.onNodeWithText("Отмена").performClick()
            compose.runOnIdle { assertEquals(null, removed) }
            compose.onNodeWithContentDescription("Выйти").performClick()
            compose.onNodeWithText("Выйти").performClick()
            compose.runOnIdle { assertEquals(accountId, removed) }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun checkAction(passwordSet: Boolean?, fail: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ProfileScreen(
                        accounts = listOf(AccountSummary(AccountId("account"), "https://family.example", "alice", null, passwordSet = true)),
                        internetAvailable = true,
                        onCheckServer = { ServerCheckDetails(ServerCheckResult.Available) },
                        onCheckPasswordSet = {
                            started.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            if (fail) error("unavailable")
                            passwordSet
                        },
                        onBack = {}, onAdd = {}, onRemoveAccount = {},
                    )
                }
            }
        }
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Проверяем…").fetchSemanticsNodes()
                started.count == 0L
            }
            compose.onNodeWithText("Пароль").assertDoesNotExist()
            compose.onNodeWithText("Задать пароль").assertDoesNotExist()
            compose.onNodeWithText("Сменить пароль").assertDoesNotExist()
            release.countDown()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Сервер доступен").fetchSemanticsNodes().isNotEmpty()
            }
            if (passwordSet == null) {
                compose.onNodeWithText("Пароль").assertDoesNotExist()
                compose.onNodeWithText("Задать пароль").assertDoesNotExist()
                compose.onNodeWithText("Сменить пароль").assertDoesNotExist()
            } else {
                compose.onNodeWithText(if (passwordSet) "Сменить пароль" else "Задать пароль").assertIsEnabled()
            }
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }
}
