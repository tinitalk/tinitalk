package org.tinitalk.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Language belongs to this installation, not to an account or a server. */
internal object AppLanguage {
    val supported = linkedMapOf(
        "en" to "English", "ru" to "Русский", "pl" to "Polski",
        "de" to "Deutsch", "es" to "Español",
        "fr" to "Français", "pt" to "Português (Brasil)",
        "it" to "Italiano", "tr" to "Türkçe",
        "ja" to "日本語", "ko" to "한국어", "zh-Hans" to "简体中文",
    )
    private lateinit var application: Context
    @Volatile private var resourceCache: Pair<Locale, android.content.res.Resources>? = null
    private val observers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    var selection by mutableStateOf("")
        private set
    var locale by mutableStateOf(Locale.ENGLISH)
        private set
    var systemLocale by mutableStateOf(Locale.ENGLISH)
        private set

    fun systemLanguageLabel(): String = resources(
        Locale.forLanguageTag(resolve(listOf(systemLocale.toLanguageTag()))),
    ).getString(org.tinitalk.R.string.language_system)

    fun sortedLanguages(): List<Pair<String, String>> {
        val collator = java.text.Collator.getInstance(systemLocale)
        return supported.toList().sortedWith { a, b ->
            collator.compare(a.second, b.second).takeIf { it != 0 } ?: a.first.compareTo(b.first)
        }
    }

    fun initialize(context: Context) {
        application = context.applicationContext
        resourceCache = null
        refresh()
    }

    fun resolve(tags: List<String>): String = tags.asSequence()
        .mapNotNull(::supportedTag)
        .firstOrNull() ?: "en"

    private fun supportedTag(tag: String): String? {
        val candidate = Locale.forLanguageTag(tag)
        if (candidate.language == "zh") {
            // Do not silently substitute simplified Chinese for traditional Chinese.
            if (candidate.script == "Hant") return null
            if (candidate.script.isEmpty() && candidate.country in setOf("TW", "HK", "MO")) return null
            return "zh-Hans"
        }
        return candidate.language.takeIf { it in supported }
    }

    fun refresh() {
        if (!::application.isInitialized) return
        val previous = locale
        resourceCache = null
        val manager = if (Build.VERSION.SDK_INT >= 33) application.getSystemService(LocaleManager::class.java) else null
        val preferences = application.getSharedPreferences("language", Context.MODE_PRIVATE)
        // Preserve a manual choice when the phone upgrades from Android 12 to 13.
        if (Build.VERSION.SDK_INT >= 33 && manager != null && preferences.contains("selection")) {
            val legacy = preferences.getString("selection", "").orEmpty()
            if (manager.applicationLocales.isEmpty && legacy in supported) {
                manager.applicationLocales = LocaleList.forLanguageTags(legacy)
            }
            preferences.edit { remove("selection") }
        }
        selection = if (Build.VERSION.SDK_INT >= 33 && manager != null) {
            manager.applicationLocales.toLanguageTags().substringBefore(',').let {
                if (it.isEmpty()) "" else supportedTag(it) ?: "en"
            }
        } else preferences.getString("selection", "").orEmpty().let {
            if (it.isEmpty()) "" else supportedTag(it) ?: "en"
        }
        val system = if (Build.VERSION.SDK_INT >= 33 && manager != null) manager.systemLocales else android.content.res.Resources.getSystem().configuration.locales
        systemLocale = if (system.isEmpty) Locale.ENGLISH else system[0]
        locale = Locale.forLanguageTag(resolve(if (selection.isEmpty()) system.toLanguageTags().split(',') else listOf(selection)))
        if (locale != previous) observers.forEach { it() }
    }

    fun select(tag: String) {
        require(tag.isEmpty() || tag in supported)
        if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            application.getSharedPreferences("language", Context.MODE_PRIVATE).edit { putString("selection", tag) }
        }
        refresh()
    }

    fun context(base: Context): Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocales(LocaleList(AppLanguage.locale)) },
    )

    fun string(@StringRes id: Int, vararg args: Any): String {
        check(::application.isInitialized) { "AppLanguage must be initialized before reading resources" }
        return resources(locale).getString(id, *args)
    }

    fun quantity(@androidx.annotation.PluralsRes id: Int, count: Int): String =
        resources(locale).getQuantityString(id, count, count)

    private fun resources(target: Locale): android.content.res.Resources {
        resourceCache?.takeIf { it.first == target }?.let { return it.second }
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply { setLocales(LocaleList(target)) },
        ).resources.also { resourceCache = target to it }
    }
}

internal fun appString(@StringRes id: Int, vararg args: Any): String = AppLanguage.string(id, *args)
