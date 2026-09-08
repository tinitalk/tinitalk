package org.tinitalk.call

internal object CallSecurityEmoji {
    private const val EmojiCount = 5
    private const val Radix = 256L

    // This order is part of the displayed security code. Do not change it after
    // release without negotiating a new table version between call participants.
    private val symbols = """
        😀 😂 😍 😎 🤠 😱 😡 😴 🤢 🤯 🤡 💀 😈 👻 👽 🤖
        🐒 🐶 🦊 🦝 🐱 🐯 🐴 🦄 🦓 🐮 🐷 🐐 🐪 🦒 🐘 🦏
        🦛 🐭 🐰 🦔 🦇 🐼 🦨 🦘 🐧 🦅 🦉 🦩 🦚 🐸 🐊 🐢
        🐍 🐌 🦖 🐳 🐬 🐙 🦀 🦋 🍇 🍉 🍋 🍌 🍍 🍎 🍒 🍓
        🥝 🥥 🥑 🍆 🥕 🌽 🫑 🥦 🧄 🍞 🥐 🧀 🍗 🥓 🍔 🍟
        🍕 🌭 🍳 🍿 🍣 🍦 🎂 🍭 🌍 🧭 🌞 🌙 🌟 🌈 ⚡ 🔥
        ❄️ ☔ 💧 🌊 🌋 🌪️ ✈️ 🚀 🌴 🌵 🍀 🌻 🍄 🏠 🏥 🏭
        🏰 🗽 ⛺ 🌉 🎡 🛝 🚂 🚢 ⚽ 🏹 🛹 🪂 🤿 🏄 🏋️ 🎳
        🏓 🤺 🥊 🏆 🥇 🎯 🎣 🎿 🛷 🤹 🪁 🎱 🔮 🪄 🎮 🎰
        🎲 🧩 🪃 🎫 🛒 🎭 🎨 🧵 🎃 🎄 🎆 🎈 🎤 🎁 🎸 🥁
        🪑 🦺 👔 👕 👖 🧣 🧤 🧦 👗 🎒 👟 👑 🎩 🎓 🪖 💄
        💍 💎 🔔 🎵 🎧 📻 🎷 🧺 🎹 🪣 📱 🔋 🔌 💻 💾 💿
        📷 📺 🔍 💡 🔦 🔑 🔒 🔨 🔧 🧲 💣 💊 💉 💰 🚿 🔬
        🧹 ⏰ 🧯 🪓 🛡️ 🚪 🚽 🧼 ❤️ 💯 💥 💤 🧬 💬 👁️ 👂
        👃 👄 🧠 🦷 🦴 👣 👍 ✋ 🙏 💪 ✅ ❌ ❓ ❗ ⚠️ 🚫
        ♻️ ☢️ 🚦 🆘 🛑 ▶️ ⏸️ 🕯️ 🧪 🩺 🛏️ ⚓ ✂️ 📎 📖 ⌛
    """.trimIndent().split(Regex("\\s+"))

    init {
        check(symbols.size == Radix.toInt())
        check(symbols.toSet().size == symbols.size)
    }

    fun fromNumericCode(code: String): List<String> {
        val digits = code.filterNot(Char::isWhitespace)
        require(digits.length == 12 && digits.all(Char::isDigit)) {
            "security code must contain exactly 12 digits"
        }
        var value = digits.toLong()
        return MutableList(EmojiCount) { "" }.also { result ->
            for (index in result.lastIndex downTo 0) {
                result[index] = symbols[(value % Radix).toInt()]
                value /= Radix
            }
            check(value == 0L)
        }
    }
}
