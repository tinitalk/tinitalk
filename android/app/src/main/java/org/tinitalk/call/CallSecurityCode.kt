package org.tinitalk.call

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

internal class EphemeralCallKey private constructor(
    private var privateKey: ByteArray?,
) : AutoCloseable {
    val publicKey: ByteArray = X25519PrivateKeyParameters(requireNotNull(privateKey)).generatePublicKey().encoded

    fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == KeySize) { "X25519 public key must be 32 bytes" }
        val secret = ByteArray(KeySize)
        X25519PrivateKeyParameters(requireNotNull(privateKey) { "X25519 private key was destroyed" })
            .generateSecret(X25519PublicKeyParameters(peerPublicKey), secret, 0)
        return secret
    }

    override fun close() {
        privateKey?.fill(0)
        privateKey = null
    }

    companion object {
        private const val KeySize = 32

        fun generate(random: SecureRandom = SecureRandom()): EphemeralCallKey =
            EphemeralCallKey(ByteArray(KeySize).also(random::nextBytes))
    }
}

internal object CallSecurityCode {
    private const val CommitmentDomain = "tinitalk-call-sas-v1/commit"
    private const val SaltDomain = "tinitalk-call-sas-v1/salt"
    private const val CodeDomain = "tinitalk-call-sas-v1/code"
    private val CodeModulus = BigInteger.TEN.pow(12)

    fun commitment(callId: String, callerPublicKey: ByteArray, callerFingerprint: ByteArray): ByteArray =
        digestTranscript(CommitmentDomain, ascii(callId), callerPublicKey, callerFingerprint)

    fun commitmentMatches(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun derive(
        sharedSecret: ByteArray,
        callId: String,
        caller: String,
        callee: String,
        callerPublicKey: ByteArray,
        calleePublicKey: ByteArray,
        callerFingerprint: ByteArray,
        calleeFingerprint: ByteArray,
    ): String {
        require(sharedSecret.size == 32) { "X25519 shared secret must be 32 bytes" }
        listOf(callerPublicKey, calleePublicKey, callerFingerprint, calleeFingerprint).forEach {
            require(it.size == 32) { "SAS key material must be 32 bytes" }
        }
        val salt = digestTranscript(SaltDomain, ascii(callId))
        val info = transcript(
            CodeDomain,
            ascii(callId),
            caller.toByteArray(Charsets.UTF_8),
            callee.toByteArray(Charsets.UTF_8),
            callerPublicKey,
            calleePublicKey,
            callerFingerprint,
            calleeFingerprint,
        )
        val output = try {
            hkdfSha256(sharedSecret, salt, info, 8)
        } finally {
            info.fill(0)
        }
        return try {
            BigInteger(1, output).mod(CodeModulus).toString().padStart(12, '0')
                .chunked(4).joinToString(" ")
        } finally {
            output.fill(0)
            salt.fill(0)
        }
    }

    fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value).also {
        require(it.size == 32 && encode(it) == value) { "value must be canonical 32-byte base64url" }
    }

    fun encodeFingerprint(value: ByteArray): String {
        require(value.size == 32)
        return value.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun decodeFingerprint(value: String): ByteArray {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "fingerprint must be lowercase SHA-256 hex"
        }
        return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun transcript(domain: String, vararg values: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                sequenceOf(domain.toByteArray(Charsets.US_ASCII), *values).forEach { value ->
                    output.writeInt(value.size)
                    output.write(value)
                }
            }
            bytes.toByteArray()
        }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun digestTranscript(domain: String, vararg values: ByteArray): ByteArray {
        val encoded = transcript(domain, *values)
        return try {
            sha256(encoded)
        } finally {
            encoded.fill(0)
        }
    }
}

internal object SdpFingerprint {
    fun sha256(sdp: String): ByteArray {
        val fingerprints = sdp.lineSequence().mapNotNull { line ->
            val value = line.trimEnd('\r')
            if (!value.startsWith("a=fingerprint:", ignoreCase = true)) return@mapNotNull null
            val parts = value.substringAfter(':').trim().split(Regex("\\s+"), limit = 2)
            // We support one certificate for the whole call. Never ignore an
            // algorithm that libwebrtc could select at a more specific SDP level.
            require(value.startsWith("a=fingerprint:") && parts.size == 2 &&
                parts[0].equals("sha-256", ignoreCase = true)) {
                "SDP contains an unsupported DTLS fingerprint"
            }
            decodeColonHex(parts[1])
        }.toList()
        require(fingerprints.isNotEmpty()) { "SDP has no SHA-256 DTLS fingerprint" }
        require(fingerprints.drop(1).all { MessageDigest.isEqual(fingerprints[0], it) }) {
            "SDP contains different DTLS fingerprints"
        }
        return fingerprints[0]
    }

    private fun decodeColonHex(value: String): ByteArray {
        val octets = value.split(':')
        require(octets.size == 32 && octets.all { part ->
            part.length == 2 && part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }) { "invalid SHA-256 DTLS fingerprint" }
        return ByteArray(32) { index -> octets[index].toInt(16).toByte() }
    }
}

internal fun hkdfSha256(inputKey: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32)) { "invalid HKDF output length" }
    val extract = Mac.getInstance("HmacSHA256")
    extract.init(SecretKeySpec(salt, "HmacSHA256"))
    val pseudoRandomKey = extract.doFinal(inputKey)
    val output = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    try {
        while (offset < length) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            val block = expand.doFinal()
            previous.fill(0)
            previous = block
            val copied = minOf(block.size, length - offset)
            block.copyInto(output, offset, 0, copied)
            offset += copied
            counter++
        }
        return output
    } finally {
        previous.fill(0)
        pseudoRandomKey.fill(0)
    }
}
