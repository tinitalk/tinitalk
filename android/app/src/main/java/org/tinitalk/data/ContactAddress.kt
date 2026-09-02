package org.tinitalk.data

class ContactAddress private constructor(
    val serverUrl: String,
    val login: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ContactAddress && serverUrl == other.serverUrl && login == other.login

    override fun hashCode(): Int = 31 * serverUrl.hashCode() + login.hashCode()

    override fun toString(): String = "ContactAddress(serverUrl=$serverUrl, login=$login)"

    companion object {
        fun of(serverUrl: String, login: String): ContactAddress {
            val normalizedServerUrl = normalizeServerUrl(serverUrl)
            require(normalizedServerUrl.isNotBlank()) { "serverUrl is blank" }
            require(login.isNotBlank()) { "login is blank" }
            return ContactAddress(normalizedServerUrl, login)
        }
    }
}
