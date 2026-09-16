package org.tinitalk.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.tinitalk.CallActivity
import org.tinitalk.R
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.VideoCallStateStore
import org.tinitalk.data.ContactAddress
import org.tinitalk.push.ContactPhotoNotificationLoader
import org.tinitalk.telecom.CallForegroundService.Companion.ActionEnd
import org.tinitalk.telecom.CallForegroundService.Companion.ActionScreenStop
import org.tinitalk.telecom.CallForegroundService.Companion.ChannelId
import org.tinitalk.telecom.CallForegroundService.Companion.NotificationId

internal fun callNotificationIcon(state: CallUiState): Int = when {
    state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Reconnecting ->
        R.drawable.ic_call_reconnecting
    state.direction == CallDirection.Outgoing &&
        (state.phase == CallPhase.Ringing || state.phase == CallPhase.Connecting) ->
        R.drawable.ic_call_outgoing
    state.phase == CallPhase.Ringing -> R.drawable.ic_call_ringing
    else -> R.drawable.ic_call_active
}

/** Presentation only; the service remains the owner of the call and foreground lifecycle. */
internal class CallNotificationPresenter(
    private val context: Context,
    private val handler: Handler,
    private val photoLoader: ContactPhotoNotificationLoader,
    private val currentOwner: () -> AccountCallOwner?,
    private val canRefresh: () -> Boolean,
    private val currentState: () -> CallUiState = CallUiStateStore::snapshot,
) {
    private var photoRevisionJob: Job? = null
    @Volatile private var closed = false

    fun observePhotos() {
        if (closed || photoRevisionJob != null) return
        photoRevisionJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            photoLoader.revisions.drop(1).collect {
                handler.post { refreshNotificationAfterPhotoRevision() }
            }
        }
    }

    fun close() {
        closed = true
        photoRevisionJob?.cancel()
        photoRevisionJob = null
    }

    fun show(state: CallUiState) {
        context.getSystemService(NotificationManager::class.java).notify(NotificationId, build(state))
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NotificationId)
    }

    private fun actionIntent(action: String): Intent =
        currentOwner()?.let { CallForegroundService.serviceIntent(context, action, it) }
            ?: Intent(context, CallForegroundService::class.java).setAction(action)

    fun build(state: CallUiState, bitmap: Bitmap? = state.peer?.contactAddress?.let(photoLoader::peek)): Notification {
        state.peer?.contactAddress?.let { address ->
            if (bitmap == null) enqueueNotificationPhotoRefresh(state, address)
        }
        val builder = Notification.Builder(context, ChannelId)
        val content = PendingIntent.getActivity(
            context,
            0,
            CallActivity.ongoingIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            context,
            1,
            actionIntent(ActionEnd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val peerName = state.peer?.displayName?.takeIf(String::isNotBlank) ?: "TiniTalk"
        bitmap?.let(builder::setLargeIcon)
        val status = when (state.phase) {
            CallPhase.Ringing -> if (state.direction == CallDirection.Outgoing) "Ждём ответа…" else "Входящий звонок"
            CallPhase.Connecting -> "Пробуем связаться…"
            CallPhase.Active -> if (state.muted) "Микрофон выключен" else "Звонок идёт"
            CallPhase.Ended -> when (state.endReason) {
                CallEndReason.Busy -> "Занято"
                CallEndReason.NotInContacts -> "Вас ещё не добавили в контакты"
                else -> "Звонок завершён"
            }
            CallPhase.Idle -> "Звонок"
        }
        builder
            .setSmallIcon(callNotificationIcon(state))
            .setContentTitle(peerName)
            .setContentText(status)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(content)
            .setOngoing(true)
        val screen = VideoCallStateStore.snapshot().takeIf { it.callKey == state.callKey }?.screen
        if (screen?.requested == true) {
            val stop = PendingIntent.getService(context, 2,
                actionIntent(ActionScreenStop),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentText(if (screen.sending) "Вы показываете экран" else "Подготовка показа экрана…")
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_screen_share), "Остановить показ", stop).build())
        }
        state.connectedAtElapsedMs?.takeIf { state.phase == CallPhase.Active }?.let { connectedAt ->
            val elapsed = (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
            builder
                .setWhen(System.currentTimeMillis() - elapsed)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } ?: builder.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) {
            val personBuilder = Person.Builder().setName(peerName).setImportant(true)
            bitmap?.let { personBuilder.setIcon(android.graphics.drawable.Icon.createWithBitmap(it)) }
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    personBuilder.build(),
                    hangUp,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(R.drawable.ic_call, "Завершить", hangUp).build())
        }
        return builder.build()
    }

    private fun enqueueNotificationPhotoRefresh(state: CallUiState, address: ContactAddress) {
        if (closed) return
        val key = state.callKey ?: return
        val requestKey = key.localId()
        val revision = photoLoader.revision
        photoLoader.load(address, requestKey, revision) { loadedKey, capturedRevision, bitmap ->
            if (loadedKey != requestKey || bitmap == null) return@load
            handler.post {
                val current = currentState()
                if (current.callKey != key || current.peer?.contactAddress != address) return@post
                if (capturedRevision != photoLoader.revision) return@post
                if (current.phase == CallPhase.Idle || closed || !canRefresh()) return@post
                context.getSystemService(NotificationManager::class.java).notify(NotificationId, build(current, bitmap))
            }
        }
    }

    private fun refreshNotificationAfterPhotoRevision() {
        val current = currentState()
        if (closed || !canRefresh() || current.callKey != currentOwner()?.key || current.phase == CallPhase.Idle) return
        if (current.peer?.contactAddress == null) return
        context.getSystemService(NotificationManager::class.java).notify(NotificationId, build(current, null))
    }

    fun terminal(): Notification {
        val builder = Notification.Builder(context, ChannelId)
        return builder
            .setSmallIcon(R.drawable.ic_call_active)
            .setContentTitle("TiniTalk")
            .setContentText("Завершаем звонок…")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Активные звонки", NotificationManager.IMPORTANCE_LOW),
        )
    }

}
