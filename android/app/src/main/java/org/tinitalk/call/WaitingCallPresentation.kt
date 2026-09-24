package org.tinitalk.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import org.tinitalk.CallActivity
import org.tinitalk.R
import org.tinitalk.contactPhotoNotificationLoader
import org.tinitalk.data.ContactAddress
import org.tinitalk.i18n.appString
import org.tinitalk.push.ContactPhotoNotificationLoader
import org.tinitalk.push.roundedNotificationPhoto

internal const val WaitingAnswerAction = "org.tinitalk.WAITING_ANSWER"
internal const val WaitingOwnerExtra = "waiting_owner"

internal fun waitingCallShouldSound(state: WaitingCallsState, active: CallUiState, alertsAllowed: Boolean): Boolean =
    alertsAllowed && state.answering == null && state.calls.any { it.acknowledged } &&
        active.phase == CallPhase.Active && active.connectedAtElapsedMs != null &&
        active.connectionHealth != ConnectionHealth.Reconnecting

class WaitingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = WaitingCalls.snapshot().calls.firstOrNull {
            it.invite.owner.localId() == intent.getStringExtra(WaitingOwnerExtra)
        }?.invite?.owner ?: return
        WaitingCalls.reject(owner)
    }
}

internal class WaitingCallPresentation(
    private val context: Context,
    photoLoader: ContactPhotoNotificationLoader? = null,
) : AutoCloseable {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val photoLoader by lazy { photoLoader ?: contactPhotoNotificationLoader(context) }
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var posted = emptySet<String>()
    private var tone: ToneGenerator? = null
    private var sounding = false
    private var rendered: WaitingCallsState? = null
    private val channel = "waiting_calls"

    fun render(state: WaitingCallsState, active: CallUiState) {
        if (closed) return
        val shouldSound = waitingCallShouldSound(state, active,
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        if (shouldSound != sounding) {
            sounding = shouldSound
            if (shouldSound) {
                tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 45) }.getOrNull()
                runCatching { tone?.startTone(ToneGenerator.TONE_SUP_CALL_WAITING) }
            } else stopTone()
        }
        if (rendered == state) return
        rendered = state
        manager.createNotificationChannel(NotificationChannel(channel, appString(R.string.waiting_calls_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        val visible = state.calls.filter { it.acknowledged }
        val next = visible.map { it.invite.owner.localId() }.toSet()
        (posted - next).forEach { manager.cancel(it, NotificationId) }
        visible.forEach { pending ->
            val ownerId = pending.invite.owner.localId()
            val address = photoAddress(pending)
            val bitmap = address?.let(photoLoader::peek)
            val answer = PendingIntent.getActivity(context, 0,
                CallActivity.ongoingIntent(context).setAction(WaitingAnswerAction)
                    .setData("tinitalk://waiting/${android.net.Uri.encode(ownerId)}/answer".toUri())
                    .putExtra(WaitingOwnerExtra, ownerId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reject = PendingIntent.getBroadcast(context, 0,
                Intent(context, WaitingCallActionReceiver::class.java)
                    .setData("tinitalk://waiting/${android.net.Uri.encode(ownerId)}/reject".toUri())
                    .putExtra(WaitingOwnerExtra, ownerId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val content = PendingIntent.getActivity(context, 0, CallActivity.ongoingIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_call_ringing)
                .setContentTitle(pending.invite.caller)
                .setContentText(appString(R.string.waiting_calls_title))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(content)
                .setTimeoutAfter((pending.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            bitmap?.let { builder.setLargeIcon(roundedNotificationPhoto(it)) }
            if (state.answering == null) {
                builder.addAction(0, appString(R.string.text_busy_91), reject)
                    .addAction(0, appString(R.string.text_answer_64), answer)
            }
            runCatching { manager.notify(ownerId, NotificationId, builder.build()) }
            if (bitmap == null && address != null) enqueuePhotoRefresh(pending, address)
        }
        posted = next
    }

    private fun photoAddress(pending: WaitingCall): ContactAddress? =
        pending.invite.callerLogin?.takeIf(String::isNotBlank)?.let {
            ContactAddress.of(pending.invite.sessionBinding.serverUrl, it)
        }

    private fun enqueuePhotoRefresh(pending: WaitingCall, address: ContactAddress) {
        val owner = pending.invite.owner
        val requestKey = owner.localId()
        photoLoader.load(address, requestKey, photoLoader.revision) { loadedKey, revision, bitmap ->
            if (loadedKey != requestKey || bitmap == null) return@load
            // Serialize with cancellation and accepting another call. Never bring back
            // a finished invite or restore actions removed while switching calls.
            handler.post {
                if (closed || revision != photoLoader.revision) return@post
                val current = rendered?.calls?.firstOrNull { it.acknowledged && it.invite.owner == owner }
                    ?: return@post
                val remaining = current.deadlineElapsedMs - SystemClock.elapsedRealtime()
                if (remaining <= 0 || photoAddress(current) != address) return@post
                val notification = manager.activeNotifications.firstOrNull {
                    it.id == NotificationId && it.tag == requestKey
                }?.notification ?: return@post
                val updated = Notification.Builder.recoverBuilder(context, notification)
                    .setLargeIcon(roundedNotificationPhoto(bitmap))
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(remaining)
                    .build()
                runCatching { manager.notify(requestKey, NotificationId, updated) }
            }
        }
    }

    private fun stopTone() { runCatching { tone?.stopTone(); tone?.release() }; tone = null }
    override fun close() {
        closed = true
        rendered = null
        handler.removeCallbacksAndMessages(null)
        stopTone()
        posted.forEach { manager.cancel(it, NotificationId) }
        posted = emptySet()
    }
    private companion object { const val NotificationId = 7401 }
}
