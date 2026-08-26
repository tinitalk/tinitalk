package org.tinitalk.ui

import java.util.Locale

fun contactInitial(displayName: String, login: String, locale: Locale = Locale.getDefault()): String {
    val value = displayName.trim().ifEmpty { login.trim() }.ifEmpty { "?" }
    val end = value.offsetByCodePoints(0, 1)
    return value.substring(0, end).uppercase(locale)
}

fun contactColorIndex(key: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "palette must not be empty" }
    return Math.floorMod(key.trim().lowercase(Locale.ROOT).hashCode(), paletteSize)
}
