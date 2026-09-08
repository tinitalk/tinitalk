package org.tinitalk.call

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSecurityCodeTest {
    @Test
    fun bothParticipantsDeriveTheSameTwelveDigitCode() {
        val alice = EphemeralCallKey.generate()
        val bob = EphemeralCallKey.generate()
        val fingerprintA = ByteArray(32) { it.toByte() }
        val fingerprintB = ByteArray(32) { (it + 32).toByte() }
        val secretA = alice.sharedSecret(bob.publicKey)
        val secretB = bob.sharedSecret(alice.publicKey)

        val codeA = derive(secretA, alice.publicKey, bob.publicKey, fingerprintA, fingerprintB)
        val codeB = derive(secretB, alice.publicKey, bob.publicKey, fingerprintA, fingerprintB)

        assertTrue(MessageDigest.isEqual(secretA, secretB))
        assertEquals(codeA, codeB)
        assertTrue(codeA.matches(Regex("\\d{4} \\d{4} \\d{4}")))
        secretA.fill(0)
        secretB.fill(0)
        alice.close()
        bob.close()
    }

    @Test
    fun commitmentDetectsChangedCallerData() {
        val publicKey = ByteArray(32) { it.toByte() }
        val fingerprint = ByteArray(32) { (it + 1).toByte() }
        val commitment = CallSecurityCode.commitment(CallId, publicKey, fingerprint)

        publicKey[0] = 99

        assertFalse(CallSecurityCode.commitmentMatches(
            commitment,
            CallSecurityCode.commitment(CallId, publicKey, fingerprint),
        ))
    }

    @Test
    fun parsesRepeatedEquivalentSha256FingerprintsFromSdp() {
        val sdp = """
            v=0
            a=fingerprint:sha-256 ${fingerprintText(0)}
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=fingerprint:SHA-256 ${fingerprintText(0).lowercase()}
        """.trimIndent()

        assertArrayEquals(ByteArray(32) { it.toByte() }, SdpFingerprint.sha256(sdp))
    }

    @Test
    fun rejectsMissingOrConflictingSdpFingerprints() {
        assertTrue(runCatching { SdpFingerprint.sha256("v=0") }.isFailure)
        val conflicting = "a=fingerprint:sha-256 ${fingerprintText(0)}\n" +
            "a=fingerprint:sha-256 ${fingerprintText(1)}"
        assertTrue(runCatching { SdpFingerprint.sha256(conflicting) }.isFailure)
    }

    @Test
    fun hkdfMatchesRfc5869Sha256TestVector() {
        val output = hkdfSha256(
            inputKey = hex("0b".repeat(22)),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )

        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"),
            output,
        )
    }

    private fun derive(
        secret: ByteArray,
        publicA: ByteArray,
        publicB: ByteArray,
        fingerprintA: ByteArray,
        fingerprintB: ByteArray,
    ) = CallSecurityCode.derive(
        sharedSecret = secret,
        callId = CallId,
        caller = "alice",
        callee = "bob",
        callerPublicKey = publicA,
        calleePublicKey = publicB,
        callerFingerprint = fingerprintA,
        calleeFingerprint = fingerprintB,
    )

    private fun fingerprintText(offset: Int): String =
        ByteArray(32) { (it + offset).toByte() }.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val CallId = "018f7d51-40a1-7bb5-a2d0-7e47f9181766"
    }
}
