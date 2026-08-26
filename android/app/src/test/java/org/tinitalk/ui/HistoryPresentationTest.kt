package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPresentationTest {
    @Test
    fun usesClearRussianStatusForDirectionAndOutcome() {
        val outgoing = mapOf(
            "unreachable" to "Не удалось дозвониться",
            "unanswered" to "Не ответили",
            "busy" to "Абонент занят",
            "rejected" to "Вызов отклонён",
            "cancelled_before_ringing" to "Вызов отменён",
            "cancelled_after_ringing" to "Вызов отменён",
            "connection_failed" to "Связь не установлена",
            "interrupted_before_answer" to "Не удалось дозвониться",
        )
        val incoming = mapOf(
            "unreachable" to "Вызов не состоялся",
            "unanswered" to "Пропущенный вызов",
            "busy" to "Вы были заняты",
            "rejected" to "Вы отклонили вызов",
            "cancelled_before_ringing" to "Вызов не состоялся",
            "cancelled_after_ringing" to "Пропущенный вызов",
            "connection_failed" to "Связь не установлена",
            "interrupted_before_answer" to "Вызов не состоялся",
        )

        outgoing.forEach { (outcome, expected) ->
            assertEquals(expected, historyStatus(item("outgoing", outcome)))
        }
        incoming.forEach { (outcome, expected) ->
            assertEquals(expected, historyStatus(item("incoming", outcome)))
        }
        assertEquals("Разговор · 1:05", historyStatus(item("outgoing", "completed", 65)))
        assertEquals("Связь прервалась · 1:01:01", historyStatus(item("incoming", "interrupted", 3661)))
    }

    @Test
    fun labelsHistoryDatesInThePhoneTimeZone() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = Instant.parse("2026-08-26T12:00:00Z")

        assertEquals("Сегодня", historyDayLabel(Instant.parse("2026-08-26T09:15:00Z").epochSecond, now, zone))
        assertEquals("Вчера", historyDayLabel(Instant.parse("2026-08-25T20:05:00Z").epochSecond, now, zone))
        assertEquals("20 августа", historyDayLabel(Instant.parse("2026-08-20T09:00:00Z").epochSecond, now, zone))
        assertEquals("12:15", historyTime(Instant.parse("2026-08-26T09:15:00Z").epochSecond, zone))
    }

    private fun item(direction: String, outcome: String, duration: Long = 0) =
        CallHistoryItem(1, "alice", "Alice", direction, outcome, 1787740200, duration)
}
