package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "w240dp-h1000dp",
)
class AboutScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun narrowCardsPlaceCommitBelowVersion() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AboutScreen(
                        serverUrl = "https://talk.example.com",
                        onCheckServer = {
                            ServerCheckDetails(
                                result = ServerCheckResult.Available,
                                apiVersion = 2,
                                commit = "12345678",
                            )
                        },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Коммит", substring = true))
                .fetchSemanticsNodes().size == 2
        }

        val versions = composeRule.onAllNodes(hasText("Версия", substring = true))
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        val commits = composeRule.onAllNodes(hasText("Коммит", substring = true))
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }

        assertEquals(2, versions.size)
        assertEquals(2, commits.size)
        versions.zip(commits).forEach { (version, commit) ->
            assertTrue(
                "Expected commit below version, version=${version.boundsInRoot}, commit=${commit.boundsInRoot}",
                commit.boundsInRoot.top > version.boundsInRoot.top,
            )
        }
        activity.pause().stop().destroy()
    }
}
