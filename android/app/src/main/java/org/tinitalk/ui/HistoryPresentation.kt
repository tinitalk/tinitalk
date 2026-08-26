package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun historyStatus(item: CallHistoryItem): String {
    if (item.outcome == "completed") return "Разговор · ${historyDuration(item.durationSeconds)}"
    if (item.outcome == "interrupted") return "Связь прервалась · ${historyDuration(item.durationSeconds)}"
    return if (item.direction == "incoming") {
        when (item.outcome) {
            "unanswered", "cancelled_after_ringing" -> "Пропущенный вызов"
            "busy" -> "Вы были заняты"
            "rejected" -> "Вы отклонили вызов"
            "connection_failed" -> "Связь не установлена"
            "unreachable", "cancelled_before_ringing", "interrupted_before_answer" -> "Вызов не состоялся"
            else -> "Вызов завершён"
        }
    } else {
        when (item.outcome) {
            "unanswered" -> "Не ответили"
            "busy" -> "Абонент занят"
            "rejected" -> "Вызов отклонён"
            "cancelled_before_ringing", "cancelled_after_ringing" -> "Вызов отменён"
            "connection_failed" -> "Связь не установлена"
            "unreachable", "interrupted_before_answer" -> "Не удалось дозвониться"
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
