package org.tinitalk

import android.os.Looper
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.tinitalk.data.AccountId
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.Session

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TinitalkApplicationLifecycleTest {
    private val event = AuthSessionEvent(
        AccountId("removed-account"), Session("https://family.example", "alice", "old-token"),
    )

    @After fun clearAuthEvent() = AuthSessionEvents.clear()

    @Test fun terminatedApplicationDoesNotReceiveNewAuthEvents() {
        RuntimeEnvironment.getApplication().onTerminate()

        AuthSessionEvents.publish(event)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun terminationCancelsQueuedAuthEvents() {
        val application = RuntimeEnvironment.getApplication()
        AuthSessionEvents.publish(event)

        application.onTerminate()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun inFlightAuthObserverDoesNotUseTerminatedApplication() {
        val application = RuntimeEnvironment.getApplication()
        // A publisher can already hold the observer in a CopyOnWriteArraySet snapshot.
        @Suppress("UNCHECKED_CAST")
        val observer = TinitalkApplication::class.java.getDeclaredField("authSessionObserver")
            .apply { isAccessible = true }.get(application) as (AuthSessionEvent) -> Unit
        application.onTerminate()

        observer(event)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
