package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w800dp-h360dp-land-mdpi")
class AccountPasswordDialogLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun landscapeCentersDescriptionActionsAndFieldsInTheirPanes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme {
                    AccountPasswordDialog(
                        onDismissRequest = {}, title = { Text("Password") },
                        landscapeDescription = { Text("Password requirements") },
                        text = { Box(Modifier.size(200.dp).testTag("fields")) },
                        dismissButton = { Box(Modifier.size(64.dp, 48.dp).testTag("cancel")) },
                        confirmButton = { Box(Modifier.size(80.dp, 48.dp).testTag("save")) },
                    )
                }
            }
        }
        try {
            val fields = compose.onNodeWithTag("fields").fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithTag("cancel").fetchSemanticsNode().boundsInRoot
            val save = compose.onNodeWithTag("save").fetchSemanticsNode().boundsInRoot
            val hint = compose.onNodeWithText("Password requirements").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("Password").fetchSemanticsNode().boundsInRoot
            assertEquals(560f, fields.center.x, 1f)
            assertEquals(180f, fields.center.y, 1f)
            assertEquals(160f, hint.center.x, 1f)
            assertEquals(160f, (cancel.left + save.right) / 2, 1f)
            assertEquals((title.bottom + cancel.top) / 2, hint.center.y, 1f)
            assertTrue(hint.right < fields.left)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
