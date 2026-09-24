package org.tinitalk.data.signal

import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.call.SignalSendResult
import org.tinitalk.data.Session

/** One transport per authenticated device, independent subscriptions for UI and call services. */
internal class SharedSignalConnections(
    private val create: (Session, String) -> SignalConnection,
    // Must enqueue (not run inline). Socket callbacks hold the socket lock; consumers must not.
    private val dispatch: (() -> Unit) -> Unit,
    private val onCallbackFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val entries = mutableMapOf<Key, Entry>()

    private fun deliver(action: () -> Unit) {
        try { action() } catch (error: Exception) { onCallbackFailure(error) }
    }

    fun acquire(session: Session, deviceId: String): SignalConnection = synchronized(lock) {
        val key = Key(CallSessionBinding.from(session), session.token, deviceId)
        val entry = entries.getOrPut(key) { Entry(key, create(session, deviceId)) }
        Lease(entry).also { entry.leases += it }
    }

    private data class Key(val session: CallSessionBinding, val token: String, val device: String)
    private class Callbacks(
        val event: (SequencedSignalEvent) -> Unit,
        val opened: (Long) -> Unit,
        val disconnected: (Long) -> Unit,
        val error: (SignalFailure) -> Unit,
    )

    private inner class Entry(val key: Key, val socket: SignalConnection) {
        val leases = mutableSetOf<Lease>()
        var started = false
        var generation: Long? = null

        fun post(action: Entry.() -> Unit) = dispatch {
            if (synchronized(lock) { entries[key] === this }) action()
        }

        fun broadcast(action: (Callbacks) -> Unit) {
            val subscribers = synchronized(lock) { leases.toList() }
            subscribers.forEach { lease ->
                synchronized(lock) { lease.callbacks.takeUnless { lease.closed } }?.let { callbacks ->
                    deliver { action(callbacks) }
                }
            }
        }

        fun start() {
            started = true
            socket.connect(
                onEvent = { event -> post { broadcast { it.event(event) } } },
                onOpen = { next -> post {
                    synchronized(lock) { generation = next }
                    broadcast { it.opened(next) }
                } },
                onDisconnected = { previous -> post {
                    synchronized(lock) { generation = null }
                    broadcast { it.disconnected(previous) }
                } },
                onError = { failure -> post { broadcast { it.error(failure) } } },
            )
        }
    }

    private inner class Lease(val entry: Entry) : SignalConnection {
        var callbacks: Callbacks? = null
        var closed = false

        override fun connect(
            onEvent: (SequencedSignalEvent) -> Unit,
            onOpen: (Long) -> Unit,
            onDisconnected: (Long) -> Unit,
            onError: (SignalFailure) -> Unit,
        ): Unit = synchronized(lock) {
            check(!closed) { "signaling subscription is closed" }
            val next = Callbacks(onEvent, onOpen, onDisconnected, onError)
            callbacks = next
            if (!entry.started) {
                entry.start()
            } else entry.generation?.let { generation ->
                entry.post {
                    val current = synchronized(lock) {
                        !closed && callbacks === next && entry.generation == generation && entry.socket.isOpen(generation)
                    }
                    if (current) deliver { onOpen(generation) }
                }
            }
        }

        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = synchronized(lock) {
            if (!closed) entry.socket.send(event, onSettled?.let { settled -> { dispatch(settled) } })
        }

        override fun sendTracked(event: SignalEvent, onResult: (SignalSendResult) -> Unit) = synchronized(lock) {
            if (!closed) entry.socket.sendTracked(event) { result -> dispatch { onResult(result) } }
        }

        override fun cancelQueued(eventId: String) = synchronized(lock) {
            if (!closed) entry.socket.cancelQueued(eventId)
        }

        override fun isOpen(): Boolean = synchronized(lock) { !closed && entry.socket.isOpen() }
        override fun isOpen(expectedGeneration: Long): Boolean = synchronized(lock) {
            !closed && entry.socket.isOpen(expectedGeneration)
        }
        override fun reconnectNow() = synchronized(lock) {
            if (!closed) entry.socket.reconnectNow()
        }
        override fun sendVisibility(callId: String, visible: Boolean): Boolean = synchronized(lock) {
            !closed && entry.socket.sendVisibility(callId, visible)
        }

        override fun close(): Unit = synchronized(lock) {
            if (closed) return
            closed = true
            callbacks = null
            entry.leases.remove(this)
            if (entry.leases.isEmpty()) {
                entries.remove(entry.key)
                entry.socket.close()
            }
        }
    }
}
