package org.tinitalk.push

import android.graphics.Bitmap
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

private const val NotificationPhotoPixels = 256

class ContactPhotoNotificationLoader(
    private val reader: ContactPhotoReader,
    private val worker: Executor,
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<LoadKey, MutableList<PendingCallback>>()

    val revision: Long
        get() = reader.revision.value

    val revisions: StateFlow<Long>
        get() = reader.revision

    fun peek(address: ContactAddress): Bitmap? =
        reader.peekBitmap(address, NotificationPhotoPixels)

    fun load(
        address: ContactAddress,
        requestKey: String,
        capturedRevision: Long,
        onLoaded: (requestKey: String, capturedRevision: Long, bitmap: Bitmap?) -> Unit,
    ) {
        val key = LoadKey(address, capturedRevision)
        synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null) {
                existing += PendingCallback(requestKey, onLoaded)
                return
            }
            inFlight[key] = mutableListOf(PendingCallback(requestKey, onLoaded))
        }
        worker.execute {
            val bitmap = runCatching { reader.loadBitmap(address, NotificationPhotoPixels) }.getOrNull()
            val pending = synchronized(lock) { inFlight.remove(key).orEmpty() }
            pending.forEach { pendingCallback ->
                pendingCallback.callback(pendingCallback.requestKey, capturedRevision, bitmap)
            }
        }
    }

    private data class LoadKey(val address: ContactAddress, val revision: Long)
    private data class PendingCallback(
        val requestKey: String,
        val callback: (String, Long, Bitmap?) -> Unit,
    )
}
