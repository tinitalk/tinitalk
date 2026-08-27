package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRefreshStateTest {
    @Test
    fun refreshedContactsReplaceOnlyContactData() {
        val historyItem = CallHistoryItem(
            id = 7,
            peerLogin = "anna",
            peerName = "Анна",
            direction = "incoming",
            outcome = "completed",
            startedAt = 1_787_740_200,
            durationSeconds = 30,
        )
        val state = MainScreenState(
            restoring = false,
            signedIn = true,
            contacts = listOf(Contact("anna", "Анна")),
            contactsRefreshing = true,
            contactsRefreshErrorMessage = "Старая ошибка",
            history = listOf(historyItem),
            historyLoaded = true,
            unreadMissedCount = 4,
        )

        val refreshed = state.withRefreshedContacts(listOf(Contact("ira", "Ирина")))

        assertEquals(listOf("ira"), refreshed.contacts.map(Contact::login))
        assertFalse(refreshed.contactsRefreshing)
        assertNull(refreshed.contactsRefreshErrorMessage)
        assertEquals(listOf(historyItem), refreshed.history)
        assertTrue(refreshed.historyLoaded)
        assertEquals(4, refreshed.unreadMissedCount)
    }
}
