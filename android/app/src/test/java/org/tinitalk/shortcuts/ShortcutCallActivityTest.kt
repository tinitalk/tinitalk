package org.tinitalk.shortcuts

import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShortcutCallActivityTest {
    @Test
    fun restoringConsumedRequestDoesNotDialAgain() {
        val intent = contactShortcutIntent(RuntimeEnvironment.getApplication(), AccountPeerKey(AccountId("account"), "anna"))
        val state = Bundle().apply { putBoolean("consumed", true) }
        Robolectric.buildActivity(ShortcutCallActivity::class.java, intent).use { controller ->
            controller.create(state).start().resume().postResume()
            assertTrue(controller.get().isFinishing)
        }
    }

    @Test
    fun openingFromRecentsDoesNotDialAgain() {
        val intent = contactShortcutIntent(RuntimeEnvironment.getApplication(), AccountPeerKey(AccountId("account"), "anna"))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        Robolectric.buildActivity(ShortcutCallActivity::class.java, intent).use { controller ->
            controller.create().start().resume().postResume()
            assertTrue(controller.get().isFinishing)
        }
    }
}
