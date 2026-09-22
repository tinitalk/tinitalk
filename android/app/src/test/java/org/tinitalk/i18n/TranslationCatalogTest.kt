package org.tinitalk.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class TranslationCatalogTest {
    @Test fun allLanguagesHaveTheSameKeysAndFormattingArguments() {
        val root = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.resolve("values/strings.xml").isFile }
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        fun catalog(suffix: String): Map<String, Element> =
            listOf("strings.xml", "call_replies.xml", "language.xml").flatMap { name ->
                val document = factory.newDocumentBuilder().parse(root.resolve("values$suffix/$name"))
                val children = document.documentElement.childNodes
                (0 until children.length).mapNotNull { children.item(it) as? Element }
            }.associateBy { it.getAttribute("name") }
        fun arguments(text: String) = Regex("%[0-9]+\\\$[sd]").findAll(text).map { it.value }.toList().sorted()
        val english = catalog("")
        for (lang in listOf("ru", "pl", "de", "es", "fr", "pt", "it", "tr", "ja", "ko", "b+zh+Hans")) {
            val translated = catalog("-$lang")
            assertEquals("$lang: missing or unexpected keys", english.keys, translated.keys)
            english.forEach { (key, value) ->
                val actual = translated.getValue(key)
                assertTrue("$lang/$key is empty", actual.textContent.isNotBlank())
                if (value.tagName == "string") assertEquals("$lang/$key arguments", arguments(value.textContent), arguments(actual.textContent))
                else {
                    val forms = (0 until actual.childNodes.length)
                        .mapNotNull { actual.childNodes.item(it) as? Element }
                    assertTrue("$lang/$key needs an other plural", forms.any { it.getAttribute("quantity") == "other" })
                    val original = (0 until value.childNodes.length)
                        .mapNotNull { value.childNodes.item(it) as? Element }
                        .first { it.getAttribute("quantity") == "other" }
                    forms.forEach { form ->
                        assertEquals("$lang/$key plural arguments", arguments(original.textContent), arguments(form.textContent))
                    }
                }
            }
        }
    }
}
