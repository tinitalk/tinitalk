package org.tinitalk.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = org.tinitalk.i18n.LocalizedTestApplication::class)
class ContactVisualsTest {
    @Test
    fun initialUsesTrimmedDisplayNameAndRussianUppercase() {
        assertEquals("А", contactInitial("  анна  ", "anna", Locale.forLanguageTag("ru")))
    }

    @Test
    fun initialFallsBackToLogin() {
        assertEquals("B", contactInitial("  ", "boris", Locale.ROOT))
    }

    @Test
    fun paletteSelectionIsStable() {
        assertEquals(0, contactColorIndex("anna", 6))
    }

    @Test
    fun displayNameDoesNotExposeLoginWhenNameIsMissing() {
        assertEquals("Без имени", contactDisplayName("  "))
    }
}
