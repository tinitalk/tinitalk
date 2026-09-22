package org.tinitalk.telecom

import org.tinitalk.i18n.appString

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
    private val languageObserver: () -> Unit = {
        handler.post {
            val state = currentState()
            if (!closed && canRefresh() && state.callKey == currentOwner()?.key && state.phase != CallPhase.Idle) {
                ensureChannel()
                show(state)
            }
        }
    }

    fun observePhotos() {
        if (closed || photoRevisionJob != null) return
        org.tinitalk.i18n.AppLanguage.observe(languageObserver)
        photoRevisionJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            photoLoader.revisions.drop(1).collect {
                handler.post { refreshNotificationAfterPhotoRevision() }
            }
        }
    }

    fun close() {
        closed = true
        org.tinitalk.i18n.AppLanguage.removeObserver(languageObserver)
        photoRevisionJob?.cancel()
        photoRevisionJob = null
    }

    fun show(state: CallUiState) {
        // finishCallSoon may have released the service since its observer started.
        if (closed || !canRefresh()) return
        context.getSystemService(NotificationManager::class.java).notify(NotificationId, build(state))
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NotificationId)
    }

    private fun actionIntent(action: String): Intent =
        currentOwner()?.let { CallForegroundService.serviceIntent(context, action, it) }
            ?: Intent(context, CallForegroundService::class.java).setAction(action)

    fun build(state: CallUiState, bitmap: Bitmap? = state.peer?.contactAddress?.let(photoLoader::peek)): Notification {
        val liveCall = state.phase != CallPhase.Ended && state.phase != CallPhase.Idle
        state.peer?.contactAddress?.takeIf { liveCall }?.let { address ->
            if (bitmap == null) enqueueNotificationPhotoRefresh(state, address)
        }
        val builder = Notification.Builder(org.tinitalk.i18n.AppLanguage.context(context), ChannelId)
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
            CallPhase.Ringing -> if (state.direction == CallDirection.Outgoing) appString(R.string.text_waiting_for_an_answer_4) else appString(R.string.text_incoming_call_62)
            CallPhase.Connecting -> appString(R.string.text_trying_to_connect_5)
            CallPhase.Active -> if (state.muted) appString(R.string.text_microphone_muted_89) else appString(R.string.text_call_in_progress_90)
            CallPhase.Ended -> when (state.endReason) {
                CallEndReason.Busy -> appString(R.string.text_busy_91)
                CallEndReason.NotInContacts -> appString(R.string.text_you_have_not_been_added_to_contacts_yet_92)
                else -> appString(R.string.text_call_ended_93)
            }
            CallPhase.Idle -> appString(R.string.text_call_94)
        }
        builder
            .setSmallIcon(callNotificationIcon(state))
            .setContentTitle(peerName)
            .setContentText(status)
            .setCategory(if (liveCall) Notification.CATEGORY_CALL else Notification.CATEGORY_SERVICE)
            .setContentIntent(content)
            .setOngoing(liveCall)
        val screen = VideoCallStateStore.snapshot().takeIf { it.callKey == state.callKey }?.screen
        if (liveCall && screen?.requested == true) {
            val stop = PendingIntent.getService(context, 2,
                actionIntent(ActionScreenStop),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentText(if (screen.sending) appString(R.string.text_you_are_sharing_your_screen_95) else appString(R.string.text_preparing_screen_sharing_96))
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_screen_share), appString(R.string.text_stop_sharing_97), stop).build())
        }
        state.connectedAtElapsedMs?.takeIf { state.phase == CallPhase.Active }?.let { connectedAt ->
            val elapsed = (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
            builder
                .setWhen(System.currentTimeMillis() - elapsed)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } ?: builder.setShowWhen(false)
        if (liveCall && Build.VERSION.SDK_INT >= 31) {
            val personBuilder = Person.Builder().setName(peerName).setImportant(true)
            bitmap?.let { personBuilder.setIcon(android.graphics.drawable.Icon.createWithBitmap(it)) }
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    personBuilder.build(),
                    hangUp,
                ),
            )
        } else if (liveCall) {
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(R.drawable.ic_call, appString(R.string.text_end_call_98), hangUp).build())
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
            .setContentText(appString(R.string.text_ending_call_99))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, appString(R.string.text_active_calls_100), NotificationManager.IMPORTANCE_LOW),
        )
    }

}
