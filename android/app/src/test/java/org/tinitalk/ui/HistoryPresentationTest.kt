package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationTest {
    @Test
    fun usesClearRussianStatusForDirectionAndOutcome() {
        val outgoing = mapOf(
            "busy" to "Занято",
            "rejected" to "Вызов отклонён",
            "connection_failed" to "Связь не установлена",
        )
        val incoming = mapOf(
            "busy" to "Пропущенный (вы были заняты)",
            "rejected" to "Вы отклонили вызов",
            "connection_failed" to "Связь не установлена",
        )

        outgoing.forEach { (outcome, expected) ->
            assertEquals(expected, historyStatus(item("outgoing", outcome, reached = true)))
        }
        incoming.forEach { (outcome, expected) ->
            assertEquals(expected, historyStatus(item("incoming", outcome, reached = true)))
        }
        assertEquals("Разговор · 1:05", historyStatus(item("outgoing", "completed", duration = 65)))
        assertEquals("Связь прервалась · 1:01:01", historyStatus(item("incoming", "interrupted", duration = 3661)))
    }

    @Test
    fun labelsEveryPassivePreAnswerOutcomeByDirectionAndReachability() {
        listOf(
            "unreachable",
            "unanswered",
            "cancelled_before_ringing",
            "cancelled_after_ringing",
            "interrupted_before_answer",
        ).forEach { outcome ->
            assertEquals("Пропущенный", historyStatus(item("incoming", outcome, reached = true)))
            assertEquals("Пропущенный (не в сети)", historyStatus(item("incoming", outcome, reached = false)))
            assertEquals("Неотвеченный", historyStatus(item("outgoing", outcome, reached = true)))
            assertEquals("Неотвеченный (не в сети)", historyStatus(item("outgoing", outcome, reached = false)))
        }
    }

    @Test
    fun incomingNoAnswerAndBusyOutcomesAreMarkedAsMissed() {
        assertTrue(isMissedIncoming(item("incoming", "unanswered", reached = true)))
        assertTrue(isMissedIncoming(item("incoming", "unreachable", reached = false)))
        assertTrue(isMissedIncoming(item("incoming", "busy", reached = true)))
        assertFalse(isMissedIncoming(item("incoming", "rejected", reached = true)))
        assertFalse(isMissedIncoming(item("incoming", "connection_failed", reached = true)))
        assertFalse(isMissedIncoming(item("outgoing", "unanswered", reached = true)))
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

    @Test
    fun labelsLatestUnreadMissedCallForContact() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = Instant.parse("2026-08-26T19:00:00Z")

        assertEquals(
            "Пропущенный в 21:30",
            missedContactSubtitle(Instant.parse("2026-08-26T18:30:00Z").epochSecond, now, zone),
        )
        assertEquals(
            "Пропущенный вчера",
            missedContactSubtitle(Instant.parse("2026-08-25T18:30:00Z").epochSecond, now, zone),
        )
        assertEquals(
            "Пропущенный 20.08.2026",
            missedContactSubtitle(Instant.parse("2026-08-20T18:30:00Z").epochSecond, now, zone),
        )
    }

    @Test
    fun formatsUnreadBadgeWithoutOverflowingTheTab() {
        assertNull(historyBadgeText(0))
        assertEquals("7", historyBadgeText(7))
        assertEquals("99", historyBadgeText(99))
        assertEquals("99+", historyBadgeText(100))
    }

    @Test
    fun describesMissedCountForHistoryTab() {
        assertEquals("История", historyTabDescription(0))
        assertEquals("История, 1 пропущенный вызов", historyTabDescription(1))
        assertEquals("История, 2 пропущенных вызова", historyTabDescription(2))
        assertEquals("История, 5 пропущенных вызовов", historyTabDescription(5))
        assertEquals("История, 11 пропущенных вызовов", historyTabDescription(11))
        assertEquals("История, 21 пропущенный вызов", historyTabDescription(21))
    }

    private fun item(
        direction: String,
        outcome: String,
        reached: Boolean = true,
        duration: Long = 0,
    ) = CallHistoryItem(1, "alice", "Alice", direction, outcome, reached, 1787740200, duration)
}
