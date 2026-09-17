package org.tinitalk.telecom

import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.media.CallMediaDispatcher

/** Installs real runtime resources without opening a network connection or creating WebRTC. */
internal fun CallForegroundService.installRuntimeForTest(
    owner: AccountCallOwner,
    coordinator: CallCoordinator? = null,
    media: ForegroundCallController? = null,
    dispatcher: CallMediaDispatcher = CallMediaDispatcher(),
) {
    val client = signalingHttpClient()
    val socket = SignalSocket(client, Session(owner.sessionBinding.serverUrl, owner.sessionBinding.login, "test-token"))
    val controller = media ?: ForegroundCallController(
        signal = socket,
        accountId = owner.key.accountId,
        mediaFactory = { _, _, _, _, _, _ -> error("No WebRTC needed") },
    )
    val runtime = CallRuntime(
        client, socket,
        coordinator ?: CallCoordinator(owner.sessionBinding.login, socket, accountId = owner.key.accountId),
        controller, dispatcher,
    )
    // Some service tests intentionally exercise terminal signaling without local media.
    if (media == null) runtime.releaseMedia()
    ReflectionHelpers.setField(this, "runtime", runtime)
}
