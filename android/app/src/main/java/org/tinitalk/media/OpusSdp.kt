package org.tinitalk.media

object OpusSdp {
    fun enableNetworkResilience(sdp: String): String {
        val opusPayload = Regex("""a=rtpmap:(\d+) opus/48000/2""").find(sdp)?.groupValues?.get(1) ?: return sdp
        val fmtpPrefix = "a=fmtp:$opusPayload "
        val lines = sdp.lines().toMutableList()
        val fmtpIndex = lines.indexOfFirst { it.startsWith(fmtpPrefix) }
        if (fmtpIndex >= 0) {
            lines[fmtpIndex] = withFecWithoutDtx(lines[fmtpIndex], fmtpPrefix)
        } else {
            val rtpIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPayload ") }
            lines.add((rtpIndex + 1).coerceAtLeast(0), "${fmtpPrefix}useinbandfec=1")
        }
        return lines.joinToString("\n")
    }

    private fun withFecWithoutDtx(line: String, prefix: String): String {
        val options = line.removePrefix(prefix)
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { option ->
                val name = option.substringBefore('=').trim()
                name.equals("useinbandfec", ignoreCase = true) || name.equals("usedtx", ignoreCase = true)
            }
            .toMutableList()
        options += "useinbandfec=1"
        return prefix + options.joinToString(";")
    }
}
