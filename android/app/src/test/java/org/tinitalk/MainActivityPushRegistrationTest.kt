package org.tinitalk

import org.tinitalk.data.ContactPage
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityPushRegistrationTest {
    @Test
    fun successfulLoginRearmsPushRegistrationForRecreatedAccount() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.setPushRegistrationStartedForTest(true)

        activity.showContactsForTest(ContactPage(emptyList(), ""))

        assertFalse(activity.pushRegistrationStartedForTest())
        activity.finish()
    }

    private fun MainActivity.setPushRegistrationStartedForTest(value: Boolean) {
        javaClass.getDeclaredField("pushRegistrationStarted").apply {
            isAccessible = true
            setBoolean(this@setPushRegistrationStartedForTest, value)
        }
    }

    private fun MainActivity.pushRegistrationStartedForTest(): Boolean =
        javaClass.getDeclaredField("pushRegistrationStarted").let { field ->
            field.isAccessible = true
            field.getBoolean(this)
        }

    private fun MainActivity.showContactsForTest(page: ContactPage) {
        javaClass.getDeclaredMethod("showContacts", ContactPage::class.java, String::class.java).apply {
            isAccessible = true
            invoke(this@showContactsForTest, page, "https://talk.example")
        }
    }
}
