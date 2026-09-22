package org.tinitalk.ui

import org.tinitalk.i18n.appString

import org.tinitalk.R

import java.util.Locale

fun contactDisplayName(displayName: String): String = displayName.trim().ifEmpty { appString(R.string.text_unnamed_246) }

fun contactInitial(displayName: String, login: String, locale: Locale = org.tinitalk.i18n.AppLanguage.locale): String {
    val value = displayName.trim().ifEmpty { login.trim() }.ifEmpty { "?" }
    val end = value.offsetByCodePoints(0, 1)
    return value.substring(0, end).uppercase(locale)
}

fun contactColorIndex(key: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "palette must not be empty" }
    return Math.floorMod(key.trim().lowercase(Locale.ROOT).hashCode(), paletteSize)
}
