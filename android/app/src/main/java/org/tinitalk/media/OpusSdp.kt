package org.tinitalk.media

object OpusSdp {
    fun enableNetworkResilience(sdp: String): String {
        val opusPayload = Regex("""a=rtpmap:(\d+) opus/48000/2""").find(sdp)?.groupValues?.get(1) ?: return sdp
        val fmtpPrefix = "a=fmtp:$opusPayload "
        val lines = sdp.lines().toMutableList()
        val fmtpIndex = lines.indexOfFirst { it.startsWith(fmtpPrefix) }
        if (fmtpIndex >= 0) {
            lines[fmtpIndex] = withOption(withOption(lines[fmtpIndex], "useinbandfec=1"), "usedtx=1")
        } else {
            val rtpIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPayload ") }
            lines.add((rtpIndex + 1).coerceAtLeast(0), "${fmtpPrefix}useinbandfec=1;usedtx=1")
        }
        return lines.joinToString("\n")
    }

    private fun withOption(line: String, option: String): String =
        if (line.contains(option)) line else "$line;$option"
}
