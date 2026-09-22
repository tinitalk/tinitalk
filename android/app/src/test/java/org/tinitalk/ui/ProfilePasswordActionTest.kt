package org.tinitalk.ui

import org.tinitalk.R
import org.tinitalk.i18n.appString

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
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp")
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
                compose.onAllNodesWithText(appString(R.string.text_set_password_327)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(appString(R.string.text_sign_out_323)).performClick()
            compose.onNodeWithText(appString(R.string.text_sign_out_of_this_account_321)).assertExists()
            compose.onNodeWithText(appString(R.string.text_new_password_319)).assertDoesNotExist()
            compose.onNodeWithText(appString(R.string.text_cancel_12)).performClick()
            compose.runOnIdle { assertEquals(null, removed) }
            compose.onNodeWithContentDescription(appString(R.string.text_sign_out_323)).performClick()
            compose.onNodeWithText(appString(R.string.text_sign_out_323)).performClick()
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
                compose.onAllNodesWithText(appString(R.string.text_checking_325)).fetchSemanticsNodes()
                started.count == 0L
            }
            compose.onNodeWithText(appString(R.string.text_password_117)).assertDoesNotExist()
            compose.onNodeWithText(appString(R.string.text_set_password_327)).assertDoesNotExist()
            compose.onNodeWithText(appString(R.string.text_change_password_326)).assertDoesNotExist()
            release.countDown()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(appString(R.string.text_server_available_119)).fetchSemanticsNodes().isNotEmpty()
            }
            if (passwordSet == null) {
                compose.onNodeWithText(appString(R.string.text_password_117)).assertDoesNotExist()
                compose.onNodeWithText(appString(R.string.text_set_password_327)).assertDoesNotExist()
                compose.onNodeWithText(appString(R.string.text_change_password_326)).assertDoesNotExist()
            } else {
                compose.onNodeWithText(if (passwordSet) appString(R.string.text_change_password_326) else appString(R.string.text_set_password_327)).assertIsEnabled()
            }
        } finally {
            release.countDown()
            activity.pause().stop().destroy()
        }
    }
}
