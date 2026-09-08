package org.tinitalk.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSecurityEmojiTest {
    @Test
    fun convertsEveryCodeToFiveEmoji() {
        val emoji = CallSecurityEmoji.fromNumericCode("4821 7034 1596")

        assertEquals(5, emoji.size)
        assertTrue(emoji.all(String::isNotBlank))
        assertEquals(listOf("🏰", "🍆", "🧵", "🛷", "🧬"), emoji)
    }

    @Test
    fun conversionIsStableAndKeepsLeadingZeros() {
        assertEquals(
            listOf("😀", "😀", "😀", "😀", "😀"),
            CallSecurityEmoji.fromNumericCode("0000 0000 0000"),
        )
        assertEquals(
            listOf("😀", "😀", "😀", "😀", "😂"),
            CallSecurityEmoji.fromNumericCode("0000 0000 0001"),
        )
    }

    @Test
    fun differentNumericCodesDoNotCollapseToSameEmojiCode() {
        val first = CallSecurityEmoji.fromNumericCode("4821 7034 1596")
        val second = CallSecurityEmoji.fromNumericCode("4821 7034 1597")

        assertNotEquals(first, second)
    }

    @Test
    fun rejectsMalformedNumericCode() {
        assertTrue(runCatching { CallSecurityEmoji.fromNumericCode("1234") }.isFailure)
        assertTrue(runCatching { CallSecurityEmoji.fromNumericCode("1234 5678 abcd") }.isFailure)
    }
}
