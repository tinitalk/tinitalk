package org.tinitalk.ui

import org.tinitalk.i18n.appString

import org.tinitalk.R

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.call.CallReplyCode
import androidx.annotation.StringRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun historyBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

fun historyTabDescription(count: Int): String {
    if (count <= 0) return appString(R.string.text_history_251)
    val calls = org.tinitalk.i18n.AppLanguage.quantity(R.plurals.missed_calls_count, count)
    return appString(R.string.text_history_value_value_252, calls)
}

private val NoAnswerOutcomes = setOf(
    "unreachable",
    "unanswered",
    "cancelled_before_ringing",
    "cancelled_after_ringing",
    "interrupted_before_answer",
)

enum class HistoryCallDirection {
    Incoming,
    Outgoing,
}

enum class HistoryCallMark {
    Completed,
    Missed,
    Busy,
    Rejected,
    Failed,
    Interrupted,
}

data class HistoryCallIcon(
    val direction: HistoryCallDirection,
    val mark: HistoryCallMark,
)

fun historyCallIcon(item: CallHistoryItem): HistoryCallIcon {
    val direction = if (item.direction == "incoming") {
        HistoryCallDirection.Incoming
    } else {
        HistoryCallDirection.Outgoing
    }
    val mark = when {
        item.outcome == "completed" -> HistoryCallMark.Completed
        item.outcome == "interrupted" -> HistoryCallMark.Interrupted
        item.outcome in NoAnswerOutcomes -> HistoryCallMark.Missed
        item.outcome == "busy" -> HistoryCallMark.Busy
        item.outcome == "rejected" -> HistoryCallMark.Rejected
        else -> HistoryCallMark.Failed
    }
    return HistoryCallIcon(direction, mark)
}

fun isMissedIncoming(item: CallHistoryItem): Boolean =
    item.direction == "incoming" && (item.outcome in NoAnswerOutcomes || item.outcome == "busy")

@StringRes
fun historyReplySummaryRes(item: CallHistoryItem): Int? {
    if (item.outcome != "rejected") return null
    val reply = CallReplyCode.fromWire(item.replyCode) ?: return null
    return if (item.direction == "incoming") reply.sentHistoryRes else reply.receivedHistoryRes
}

fun historyStatus(item: CallHistoryItem): String {
    if (item.outcome == "completed") return appString(R.string.text_call_value_253, historyDuration(item.durationSeconds))
    if (item.outcome == "interrupted") return appString(R.string.text_connection_lost_value_254, historyDuration(item.durationSeconds))
    if (item.outcome in NoAnswerOutcomes) {
        return if (item.direction == "incoming") {
            if (item.reached) appString(R.string.text_missed_255) else appString(R.string.text_missed_offline_256)
        } else {
            if (item.reached) appString(R.string.text_unanswered_257) else appString(R.string.text_unanswered_offline_258)
        }
    }
    return if (item.direction == "incoming") {
        when (item.outcome) {
            "busy" -> appString(R.string.text_missed_you_were_busy_259)
            "rejected" -> appString(R.string.text_you_declined_the_call_260)
            "connection_failed" -> appString(R.string.text_connection_not_established_261)
            else -> appString(R.string.text_call_ended_262)
        }
    } else {
        when (item.outcome) {
            "busy" -> appString(R.string.text_busy_91)
            "rejected" -> appString(R.string.text_call_declined_263)
            "connection_failed" -> appString(R.string.text_connection_not_established_261)
            else -> appString(R.string.text_call_ended_262)
        }
    }
}

fun historyDayLabel(
    startedAt: Long,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochSecond(startedAt).atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (date) {
        today -> appString(R.string.text_today_264)
        today.minusDays(1) -> appString(R.string.text_yesterday_265)
        else -> date.format(
            DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.getBestDateTimePattern(HistoryLocale, if (date.year == today.year) "MMMMd" else "yMMMMd"),
                HistoryLocale,
            ),
        )
    }
}

fun historyTime(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(startedAt).atZone(zone).format(TimeFormatter)

fun missedContactSubtitle(
    startedAt: Long,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val call = Instant.ofEpochSecond(startedAt).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    return when (call.toLocalDate()) {
        today -> appString(R.string.text_missed_at_value_266, call.format(TimeFormatter))
        today.minusDays(1) -> appString(R.string.text_missed_yesterday_267)
        else -> appString(R.string.text_missed_value_268, call.format(ContactDateFormatter))
    }
}

private fun historyDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainingSeconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(HistoryLocale, hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(HistoryLocale, minutes, remainingSeconds)
    }
}

private val HistoryLocale: Locale get() = org.tinitalk.i18n.AppLanguage.locale
private val TimeFormatter: DateTimeFormatter get() = DateTimeFormatter.ofPattern("HH:mm", HistoryLocale)
private val ContactDateFormatter: DateTimeFormatter get() = DateTimeFormatter.ofPattern(
    android.text.format.DateFormat.getBestDateTimePattern(HistoryLocale, "yyyyMMdd"), HistoryLocale,
)
