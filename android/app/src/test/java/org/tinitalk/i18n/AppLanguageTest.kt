package org.tinitalk.i18n

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class AppLanguageTest {
    @Before fun initialize() { AppLanguage.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun resolvesRegionalVariantsAndFallsBackToEnglish() {
        assertEquals("de", AppLanguage.resolve(listOf("de-AT")))
        assertEquals("es", AppLanguage.resolve(listOf("es-MX")))
        assertEquals("fr", AppLanguage.resolve(listOf("fr-CA")))
        assertEquals("pt", AppLanguage.resolve(listOf("pt-BR")))
        assertEquals("pt", AppLanguage.resolve(listOf("pt-PT")))
        assertEquals("it", AppLanguage.resolve(listOf("it-CH")))
        assertEquals("tr", AppLanguage.resolve(listOf("tr-TR")))
        assertEquals("ja", AppLanguage.resolve(listOf("ja-JP")))
        assertEquals("ko", AppLanguage.resolve(listOf("ko-KR")))
        assertEquals("en", AppLanguage.resolve(listOf("lt-LT")))
        assertEquals("en", AppLanguage.resolve(listOf("lv-LV")))
        assertEquals("en", AppLanguage.resolve(listOf("et-EE")))
        assertEquals("pl", AppLanguage.resolve(listOf("fi-FI", "pl-PL", "en-US")))
        assertEquals("en", AppLanguage.resolve(listOf("fi-FI")))
        assertEquals("en", AppLanguage.resolve(emptyList()))
    }

    @Test fun selectionPersistsAndResourcesWorkOutsideActivity() {
        val labels = mapOf("en" to "Language", "ru" to "Язык", "pl" to "Język", "de" to "Sprache", "es" to "Idioma",
            "fr" to "Langue", "pt" to "Idioma", "it" to "Lingua", "tr" to "Dil",
            "ja" to "言語", "ko" to "언어", "zh-Hans" to "语言")
        labels.forEach { (tag, title) ->
            AppLanguage.select(tag)
            AppLanguage.initialize(RuntimeEnvironment.getApplication())
            assertEquals(tag, AppLanguage.selection)
            assertEquals(title, appString(R.string.language_title))
            assertFalse(appString(R.string.call_reply_select, "Alex").contains("%1\$s"))
            assertTrue(appString(R.string.call_reply_select, "Alex").contains("Alex"))
        }
        AppLanguage.select("")
        assertEquals("", AppLanguage.selection)
    }

    @Test fun firstLaunchUsesSystemLanguageForSignInWithoutSavingAChoice() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("language", 0).edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.getEmptyLocaleList()
        }
        for ((system, signIn, password) in listOf(
            Triple("ru", "Войти", "Пароль"),
            Triple("de", "Anmelden", "Passwort"),
            Triple("ja", "ログイン", "パスワード"),
            Triple("fi", "Sign in", "Password"),
        )) {
            RuntimeEnvironment.setQualifiers(system)
            AppLanguage.initialize(context)
            assertEquals("", AppLanguage.selection)
            assertEquals(signIn, appString(R.string.text_sign_in_115))
            assertEquals(password, appString(R.string.text_password_117))
            assertFalse(context.getSharedPreferences("language", 0).contains("selection"))
        }
    }

    @Test fun simplifiedChineseRespectsScriptAndRegion() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-HK")) {
            assertEquals(tag, "zh-Hans", AppLanguage.resolve(listOf(tag)))
        }
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN")) {
            assertEquals(tag, "en", AppLanguage.resolve(listOf(tag)))
        }
        assertEquals("ja", AppLanguage.resolve(listOf("zh-Hant", "ja-JP")))
        AppLanguage.select("zh-Hans")
        AppLanguage.initialize(RuntimeEnvironment.getApplication())
        assertEquals("zh-Hans", AppLanguage.selection)
        assertEquals("Hans", AppLanguage.locale.script)
        assertEquals("语言", appString(R.string.language_title))
    }

    @Test fun asianCountersUseTheirOwnPluralRules() {
        val cases = mapOf(
            "ja" to listOf("不在着信0件", "不在着信1件", "不在着信2件", "不在着信11件", "不在着信21件"),
            "ko" to listOf("부재중 전화 0통", "부재중 전화 1통", "부재중 전화 2통", "부재중 전화 11통", "부재중 전화 21통"),
            "zh-Hans" to listOf("0个未接来电", "1个未接来电", "2个未接来电", "11个未接来电", "21个未接来电"),
        )
        cases.forEach { (tag, expected) ->
            AppLanguage.select(tag)
            listOf(0, 1, 2, 11, 21).zip(expected).forEach { (count, text) ->
                assertEquals("$tag/$count", text, AppLanguage.quantity(R.plurals.missed_calls_count, count))
            }
        }
    }

    @Test fun pluralsDoNotUseRussianRulesInOtherLanguages() {
        AppLanguage.select("en")
        assertEquals("21 missed calls", AppLanguage.quantity(R.plurals.missed_calls_count, 21))
        AppLanguage.select("ru")
        assertEquals("21 пропущенный вызов", AppLanguage.quantity(R.plurals.missed_calls_count, 21))
        AppLanguage.select("pl")
        assertEquals("21 nieodebranych połączeń", AppLanguage.quantity(R.plurals.missed_calls_count, 21))
        assertEquals("22 nieodebrane połączenia", AppLanguage.quantity(R.plurals.missed_calls_count, 22))
        for ((tag, singular, plural) in listOf(
            Triple("fr", "1 appel manqué", "2 appels manqués"),
            Triple("pt", "1 chamada perdida", "2 chamadas perdidas"),
            Triple("it", "1 chiamata persa", "2 chiamate perse"),
            Triple("tr", "1 cevapsız arama", "2 cevapsız arama"),
        )) {
            AppLanguage.select(tag)
            assertEquals(singular, AppLanguage.quantity(R.plurals.missed_calls_count, 1))
            assertEquals(plural, AppLanguage.quantity(R.plurals.missed_calls_count, 2))
        }
    }

    @Test fun automaticModeFollowsSystemAndManualChoiceDoesNot() {
        AppLanguage.select("")
        RuntimeEnvironment.setQualifiers("fi")
        AppLanguage.refresh()
        assertEquals("en", AppLanguage.locale.language)
        assertEquals("Language", appString(R.string.language_title))
        AppLanguage.select("pl")
        RuntimeEnvironment.setQualifiers("de")
        AppLanguage.refresh()
        assertEquals("pl", AppLanguage.locale.language)
        AppLanguage.select("")
        assertEquals("de", AppLanguage.locale.language)
    }

    @Test fun systemLabelAndLanguageOrderDoNotFollowAppLanguage() {
        RuntimeEnvironment.setQualifiers("ru")
        AppLanguage.refresh()
        AppLanguage.select("ja")
        assertEquals("Как в системе", AppLanguage.systemLanguageLabel())
        val russianOrder = AppLanguage.sortedLanguages()
        assertEquals(AppLanguage.supported.keys, russianOrder.map { it.first }.toSet())
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("ru"))
        assertTrue(russianOrder.zipWithNext().all { (a, b) -> collator.compare(a.second, b.second) <= 0 })
        AppLanguage.select("de")
        assertEquals(russianOrder, AppLanguage.sortedLanguages())
        assertEquals("Как в системе", AppLanguage.systemLanguageLabel())
        RuntimeEnvironment.setQualifiers("fr")
        AppLanguage.refresh()
        assertEquals("Langue du système", AppLanguage.systemLanguageLabel())
        assertEquals("de", AppLanguage.selection)
        RuntimeEnvironment.setQualifiers("fi")
        AppLanguage.refresh()
        assertEquals("System default", AppLanguage.systemLanguageLabel())
    }

    @Test
    @Config(sdk = [35])
    fun androidUpgradeMigratesOnceAndDoesNotOverrideReturningToSystemLanguage() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("language", 0).edit().putString("selection", "pl").commit()
        AppLanguage.initialize(context)
        assertEquals("pl", AppLanguage.selection)
        assertFalse(context.getSharedPreferences("language", 0).contains("selection"))
        AppLanguage.select("")
        AppLanguage.initialize(context)
        assertEquals("", AppLanguage.selection)
    }
}
