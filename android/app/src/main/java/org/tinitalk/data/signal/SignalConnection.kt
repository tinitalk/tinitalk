package org.tinitalk.data.signal

import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.call.SignalClient

/** A signaling subscription; closing it releases only this owner's connection. */
interface SignalConnection : SignalClient, AutoCloseable {
    fun connect(
        onEvent: (SequencedSignalEvent) -> Unit,
        onOpen: (Long) -> Unit = {},
        onDisconnected: (Long) -> Unit = {},
        onError: (SignalFailure) -> Unit = {},
    )
    fun reconnectNow()
    fun isOpen(): Boolean
    fun isOpen(expectedGeneration: Long): Boolean
    fun sendVisibility(callId: String, visible: Boolean): Boolean
    /** Stop retransmission; cannot undo a packet already received by the server. */
    fun cancelQueued(eventId: String) {}
}
