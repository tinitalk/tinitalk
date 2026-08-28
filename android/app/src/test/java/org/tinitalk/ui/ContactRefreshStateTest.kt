package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactPage
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
            reached = true,
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

        val refreshed = state.withRefreshedContacts(
            ContactPage(listOf(Contact("ira", "Ирина")), nextCursor = "next-page"),
        )

        assertEquals(listOf("ira"), refreshed.contacts.map(Contact::login))
        assertFalse(refreshed.contactsRefreshing)
        assertNull(refreshed.contactsRefreshErrorMessage)
        assertEquals(listOf(historyItem), refreshed.history)
        assertTrue(refreshed.historyLoaded)
        assertEquals(4, refreshed.unreadMissedCount)
    }

    @Test
    fun additionalContactPageAppendsWithoutDuplicates() {
        val state = MainScreenState(
            contacts = listOf(Contact("anna", "Анна"), Contact("boris", "Борис")),
            contactsLoadingMore = true,
            contactsNextCursor = "next-page",
        )

        val updated = state.withContactsPage(
            ContactPage(
                items = listOf(Contact("boris", "Борис"), Contact("ira", "Ирина")),
                nextCursor = "",
            ),
        )

        assertEquals(listOf("anna", "boris", "ira"), updated.contacts.map(Contact::login))
        assertFalse(updated.contactsLoadingMore)
        assertEquals("", updated.contactsNextCursor)
        assertNull(updated.contactsLoadMoreErrorMessage)
    }

    @Test
    fun requestsNextContactPageFiveItemsBeforeEnd() {
        assertFalse(shouldLoadMoreContacts(index = 14, itemCount = 20, nextCursor = "next", loading = false, hasError = false))
        assertTrue(shouldLoadMoreContacts(index = 15, itemCount = 20, nextCursor = "next", loading = false, hasError = false))
        assertFalse(shouldLoadMoreContacts(index = 15, itemCount = 20, nextCursor = "next", loading = false, hasError = true))
    }
}
