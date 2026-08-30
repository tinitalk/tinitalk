package org.tinitalk.ui

import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardStateTest {
    @Test
    fun callActionReflectsCurrentCall() {
        assertEquals(
            ContactCallAction("Позвонить", enabled = true, opensCurrentCall = false),
            contactCallAction("anna", null),
        )
        assertEquals(
            ContactCallAction("Вернуться к звонку", enabled = true, opensCurrentCall = true),
            contactCallAction(
                "anna",
                CallUiState(peer = CallPeer("Анна", "anna"), phase = CallPhase.Active),
            ),
        )

        val anotherContact = contactCallAction(
            "anna",
            CallUiState(peer = CallPeer("Ирина", "ira"), phase = CallPhase.Active),
        )
        assertEquals("Сначала завершите текущий звонок", anotherContact.label)
        assertFalse(anotherContact.enabled)
        assertTrue(!anotherContact.opensCurrentCall)
    }

    @Test
    fun offlineBlocksNewCallButKeepsCurrentCallAccessible() {
        assertEquals(
            ContactCallAction("Нет подключения", enabled = false, opensCurrentCall = false),
            contactCallAction("anna", null, internetAvailable = false),
        )
        assertEquals(
            ContactCallAction("Вернуться к звонку", enabled = true, opensCurrentCall = true),
            contactCallAction(
                "anna",
                CallUiState(peer = CallPeer("Анна", "anna"), phase = CallPhase.Active),
                internetAvailable = false,
            ),
        )
    }
}
