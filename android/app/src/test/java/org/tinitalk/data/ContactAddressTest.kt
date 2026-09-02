package org.tinitalk.data

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAddressTest {
    @Test
    fun normalizesServerButKeepsLoginExact() {
        assertEquals(
            ContactAddress.of("https://EXAMPLE.com:443/", "Alex"),
            ContactAddress.of("https://example.com", "Alex"),
        )
        assertNotEquals(
            ContactAddress.of("https://example.com", "Alex"),
            ContactAddress.of("https://example.com", "alex"),
        )
        assertNotEquals(
            ContactAddress.of("https://example.com", "alex"),
            ContactAddress.of("https://example.com", "alex "),
        )
    }

    @Test
    fun accountContactProjectsStructuredAddress() {
        val contact = AccountContact(
            AccountId("local-one"),
            "https://EXAMPLE.com:443/",
            Contact("Alex", "Alex"),
        )

        assertEquals(ContactAddress.of("https://example.com", "Alex"), contact.address)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankLogin() {
        ContactAddress.of("https://example.com", "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankServer() {
        ContactAddress.of("   ", "alex")
    }

    @Test
    fun constructorCannotBypassFactory() {
        val constructor = ContactAddress::class.java.getDeclaredConstructor(String::class.java, String::class.java)

        assertTrue(Modifier.isPrivate(constructor.modifiers))
    }
}
