package org.tinitalk

import org.junit.Assert.assertEquals
import org.junit.Test

class RootUiModelTest {
    @Test
    fun rootScreenShowsApplicationName() {
        assertEquals("TiniTalk", RootUiModel().title)
    }
}
