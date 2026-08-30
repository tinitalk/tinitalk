package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationTest {
    @Test
    fun backReturnsFromGlobalHistoryOnly() {
        assertTrue(shouldReturnToContactsOnBack(currentPage = 1, contactOpen = false))
        assertFalse(shouldReturnToContactsOnBack(currentPage = 0, contactOpen = false))
        assertFalse(shouldReturnToContactsOnBack(currentPage = 1, contactOpen = true))
    }
}
