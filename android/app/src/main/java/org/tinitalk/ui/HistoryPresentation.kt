package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
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
    if (count <= 0) return "История"
    val calls = when {
        count % 100 in 11..14 -> "пропущенных вызовов"
        count % 10 == 1 -> "пропущенный вызов"
        count % 10 in 2..4 -> "пропущенных вызова"
        else -> "пропущенных вызовов"
    }
    return "История, $count $calls"
}

private val NoAnswerOutcomes = setOf(
    "unreachable",
    "unanswered",
    "cancelled_before_ringing",
    "cancelled_after_ringing",
    "interrupted_before_answer",
)

fun isMissedIncoming(item: CallHistoryItem): Boolean =
    item.direction == "incoming" && (item.outcome in NoAnswerOutcomes || item.outcome == "busy")

fun historyStatus(item: CallHistoryItem): String {
    if (item.outcome == "completed") return "Разговор · ${historyDuration(item.durationSeconds)}"
    if (item.outcome == "interrupted") return "Связь прервалась · ${historyDuration(item.durationSeconds)}"
    if (item.outcome in NoAnswerOutcomes) {
        return if (item.direction == "incoming") {
            if (item.reached) "Пропущенный" else "Пропущенный (не в сети)"
        } else {
            if (item.reached) "Неотвеченный" else "Неотвеченный (не в сети)"
        }
    }
    return if (item.direction == "incoming") {
        when (item.outcome) {
            "busy" -> "Пропущенный (вы были заняты)"
            "rejected" -> "Вы отклонили вызов"
            "connection_failed" -> "Связь не установлена"
            else -> "Вызов завершён"
        }
    } else {
        when (item.outcome) {
            "busy" -> "Занято"
            "rejected" -> "Вызов отклонён"
            "connection_failed" -> "Связь не установлена"
            else -> "Вызов завершён"
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
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date.format(
            DateTimeFormatter.ofPattern(
                if (date.year == today.year) "d MMMM" else "d MMMM yyyy",
                RussianLocale,
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
        today -> "Пропущенный в ${call.format(TimeFormatter)}"
        today.minusDays(1) -> "Пропущенный вчера"
        else -> "Пропущенный ${call.format(ContactDateFormatter)}"
    }
}

private fun historyDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainingSeconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(RussianLocale, hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(RussianLocale, minutes, remainingSeconds)
    }
}

private val RussianLocale = Locale.forLanguageTag("ru-RU")
private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RussianLocale)
private val ContactDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", RussianLocale)
