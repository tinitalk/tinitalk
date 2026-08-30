package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactHistoryStateTest {
    @Test
    fun pagesAreMergedWithoutDuplicatingBoundaryItem() {
        val first = ContactHistoryState(peerLogin = "anna", items = listOf(item(3), item(2)))
        val nextPage = CallHistoryPage(
            items = listOf(item(2), item(1)),
            nextBefore = 1,
            latestId = 3,
            unreadMissedCount = 0,
        )

        val merged = first.withPage("anna", nextPage, reset = false)

        assertEquals(listOf(3L, 2L, 1L), merged.items.map { it.id })
        assertEquals(1L, merged.nextBefore)
        assertEquals(3L, merged.latestId)
        assertFalse(merged.loadingMore)
    }

    @Test
    fun pagingWaitsForManualRetryAfterAnError() {
        assertTrue(shouldLoadMoreHistory(index = 2, itemCount = 3, nextBefore = 2, loading = false, hasError = false))
        assertFalse(shouldLoadMoreHistory(index = 2, itemCount = 3, nextBefore = 2, loading = false, hasError = true))
        assertFalse(
            shouldLoadMoreHistory(
                index = 2,
                itemCount = 3,
                nextBefore = 2,
                loading = false,
                hasError = false,
                internetAvailable = false,
            ),
        )
    }

    @Test
    fun staleRequestIsRejectedAfterContactCardCloses() {
        assertTrue(isCurrentContactHistoryRequest(4, 4, "anna", "anna"))
        assertFalse(isCurrentContactHistoryRequest(4, 5, "anna", "anna"))
        assertFalse(isCurrentContactHistoryRequest(4, 4, "anna", null))
    }

    @Test
    fun staleSessionResponseIsRejectedAfterSigningInAgain() {
        assertTrue(isCurrentSessionRequest(7, 7))
        assertFalse(isCurrentSessionRequest(7, 8))
    }

    private fun item(id: Long) = CallHistoryItem(
        id = id,
        peerLogin = "anna",
        peerName = "Анна",
        direction = "incoming",
        outcome = "completed",
        reached = true,
        startedAt = 1_787_740_200,
        durationSeconds = 10,
    )
}
