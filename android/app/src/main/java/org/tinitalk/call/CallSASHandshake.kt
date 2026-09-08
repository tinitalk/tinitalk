package org.tinitalk.call

import com.google.gson.JsonObject
import java.security.MessageDigest

internal enum class CallSASRole {
    Caller,
    Callee,
}

internal class CallSASHandshake(
    private val callId: String,
    private val caller: String,
    private val callee: String,
    private val role: CallSASRole,
    private val send: (String, JsonObject) -> Unit,
    private val publish: (CallSecurityState) -> Unit,
    private val key: EphemeralCallKey = EphemeralCallKey.generate(),
) : AutoCloseable {
    private var localFingerprint: ByteArray? = null
    private var remoteFingerprint: ByteArray? = null
    private var receivedCommitment: ByteArray? = null
    private var peerPublicKey: ByteArray? = null
    private var peerAdvertisedFingerprint: ByteArray? = null
    private var sentCommitment = false
    private var sentKey = false
    private var sentReveal = false
    private var transportConnected = false
    private var code: String? = null
    private var failed = false
    private var closed = false

    init {
        publish(CallSecurityState.Establishing)
    }

    fun recordLocalSdp(sdp: String) = guard {
        recordFingerprint(local = true, parseFingerprint(sdp))
        if (role == CallSASRole.Caller) maybeSendCommitment() else maybeSendKey()
    }

    fun recordRemoteSdp(sdp: String) = guard {
        recordFingerprint(local = false, parseFingerprint(sdp))
        if (role == CallSASRole.Caller) maybeCompleteCaller() else maybeCompleteCallee()
    }

    fun onCommitment(payload: JsonObject) = guard {
        securityRequire(
            role == CallSASRole.Callee && receivedCommitment == null,
            CallSecurityFailureReason.UnexpectedMessage,
        )
        receivedCommitment = decodeValue(payload, "commitment", CallSecurityFailureReason.UnexpectedMessage)
        maybeSendKey()
    }

    fun onKey(payload: JsonObject) = guard {
        securityRequire(
            role == CallSASRole.Caller && peerPublicKey == null,
            CallSecurityFailureReason.UnexpectedMessage,
        )
        peerPublicKey = decodeValue(payload, "public_key", CallSecurityFailureReason.InvalidPublicKey)
        peerAdvertisedFingerprint = decodeFingerprint(payload)
        maybeCompleteCaller()
    }

    fun onReveal(payload: JsonObject) = guard {
        securityRequire(
            role == CallSASRole.Callee && peerPublicKey == null,
            CallSecurityFailureReason.UnexpectedMessage,
        )
        peerPublicKey = decodeValue(payload, "public_key", CallSecurityFailureReason.InvalidPublicKey)
        peerAdvertisedFingerprint = decodeFingerprint(payload)
        maybeCompleteCallee()
    }

    fun onTransportConnected() {
        if (failed || closed) return
        transportConnected = true
        publishCodeIfReady()
    }

    fun onTransportUnavailable() {
        if (failed || closed) return
        transportConnected = false
        if (code != null) publish(CallSecurityState.Establishing)
    }

    fun reject(reason: CallSecurityFailureReason) = fail(reason)

    fun timeOut() {
        when {
            code == null -> fail(CallSecurityFailureReason.ExchangeTimeout)
            !transportConnected -> fail(CallSecurityFailureReason.TransportTimeout)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        clearKeyMaterial()
    }

    private fun maybeSendCommitment() {
        if (sentCommitment) return
        val fingerprint = localFingerprint ?: return
        sentCommitment = true
        val commitment = CallSecurityCode.commitment(callId, key.publicKey, fingerprint)
        send("rtc.sas.commit", JsonObject().apply {
            addProperty("commitment", CallSecurityCode.encode(commitment))
        })
        commitment.fill(0)
    }

    private fun maybeSendKey() {
        if (sentKey || receivedCommitment == null) return
        val fingerprint = localFingerprint ?: return
        sentKey = true
        send("rtc.sas.key", JsonObject().apply {
            addProperty("public_key", CallSecurityCode.encode(key.publicKey))
            addProperty("fingerprint", CallSecurityCode.encodeFingerprint(fingerprint))
        })
    }

    private fun maybeCompleteCaller() {
        if (code != null) return
        val remote = remoteFingerprint ?: return
        val advertised = peerAdvertisedFingerprint ?: return
        securityRequire(
            MessageDigest.isEqual(remote, advertised),
            CallSecurityFailureReason.FingerprintMismatch,
        )
        val peerKey = peerPublicKey ?: return
        val local = localFingerprint ?: return
        if (!sentReveal) {
            sentReveal = true
            send("rtc.sas.reveal", JsonObject().apply {
                addProperty("public_key", CallSecurityCode.encode(key.publicKey))
                addProperty("fingerprint", CallSecurityCode.encodeFingerprint(local))
            })
        }
        deriveCode(
            sharedSecret = sharedSecret(peerKey),
            callerPublicKey = key.publicKey,
            calleePublicKey = peerKey,
            callerFingerprint = local,
            calleeFingerprint = remote,
        )
    }

    private fun maybeCompleteCallee() {
        if (code != null) return
        val commitment = receivedCommitment ?: return
        val peerKey = peerPublicKey ?: return
        val advertised = peerAdvertisedFingerprint ?: return
        val remote = remoteFingerprint ?: return
        securityRequire(
            MessageDigest.isEqual(remote, advertised),
            CallSecurityFailureReason.FingerprintMismatch,
        )
        val revealedCommitment = CallSecurityCode.commitment(callId, peerKey, advertised)
        try {
            securityRequire(
                CallSecurityCode.commitmentMatches(commitment, revealedCommitment),
                CallSecurityFailureReason.CommitmentMismatch,
            )
        } finally {
            revealedCommitment.fill(0)
        }
        val local = localFingerprint ?: return
        deriveCode(
            sharedSecret = sharedSecret(peerKey),
            callerPublicKey = peerKey,
            calleePublicKey = key.publicKey,
            callerFingerprint = remote,
            calleeFingerprint = local,
        )
    }

    private fun deriveCode(
        sharedSecret: ByteArray,
        callerPublicKey: ByteArray,
        calleePublicKey: ByteArray,
        callerFingerprint: ByteArray,
        calleeFingerprint: ByteArray,
    ) {
        try {
            code = CallSecurityCode.derive(
                sharedSecret,
                callId,
                caller,
                callee,
                callerPublicKey,
                calleePublicKey,
                callerFingerprint,
                calleeFingerprint,
            )
        } finally {
            sharedSecret.fill(0)
            key.close()
        }
        publishCodeIfReady()
    }

    private fun publishCodeIfReady() {
        val current = code ?: return
        if (!transportConnected) return
        publish(CallSecurityState.Ready(current))
    }

    private fun recordFingerprint(local: Boolean, fingerprint: ByteArray) {
        val previous = if (local) localFingerprint else remoteFingerprint
        securityRequire(
            previous == null || MessageDigest.isEqual(previous, fingerprint),
            CallSecurityFailureReason.FingerprintChanged,
        )
        if (previous == null) {
            if (local) localFingerprint = fingerprint else remoteFingerprint = fingerprint
        } else {
            fingerprint.fill(0)
        }
    }

    private inline fun guard(action: () -> Unit) {
        if (failed || closed) return
        runCatching(action).onFailure { error ->
            fail((error as? CallSecurityException)?.reason ?: CallSecurityFailureReason.InternalError)
        }
    }

    private fun fail(reason: CallSecurityFailureReason) {
        if (failed || closed) return
        failed = true
        clearKeyMaterial()
        publish(CallSecurityState.Failed(reason))
    }

    private fun parseFingerprint(sdp: String): ByteArray =
        runCatching { SdpFingerprint.sha256(sdp) }
            .getOrElse { throw CallSecurityException(CallSecurityFailureReason.InvalidFingerprint) }

    private fun decodeValue(
        payload: JsonObject,
        name: String,
        reason: CallSecurityFailureReason,
    ): ByteArray = runCatching {
        val value = payload[name]
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        CallSecurityCode.decode(value.asString)
    }.getOrElse { throw CallSecurityException(reason) }

    private fun decodeFingerprint(payload: JsonObject): ByteArray = runCatching {
        val value = payload["fingerprint"]
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        CallSecurityCode.decodeFingerprint(value.asString)
    }.getOrElse { throw CallSecurityException(CallSecurityFailureReason.InvalidFingerprint) }

    private fun sharedSecret(peerKey: ByteArray): ByteArray =
        runCatching { key.sharedSecret(peerKey) }
            .getOrElse { throw CallSecurityException(CallSecurityFailureReason.InvalidPublicKey) }

    private fun clearKeyMaterial() {
        key.close()
        localFingerprint?.fill(0)
        remoteFingerprint?.fill(0)
        receivedCommitment?.fill(0)
        peerPublicKey?.fill(0)
        peerAdvertisedFingerprint?.fill(0)
        localFingerprint = null
        remoteFingerprint = null
        receivedCommitment = null
        peerPublicKey = null
        peerAdvertisedFingerprint = null
    }
}

private class CallSecurityException(
    val reason: CallSecurityFailureReason,
) : RuntimeException()

private fun securityRequire(condition: Boolean, reason: CallSecurityFailureReason) {
    if (!condition) throw CallSecurityException(reason)
}
