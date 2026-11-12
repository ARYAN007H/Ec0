/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Canonical Ec0Envelope Ed25519 signer and verifier with integrated ReplayWindow
 * and OfflineFlushDeduplicator enforcement (docs/engine/design.md §4.1–§4.4).
 */
package ec0.core.engine.crypto

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.OfflineFlushDeduplicator
import ec0.core.engine.ReplayCheckResult
import ec0.core.engine.ReplayWindow
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

object EnvelopeSigner {

    private val secureRandom = SecureRandom()

    /**
     * Signs and constructs an [Ec0Envelope] over the deterministic 8-line `ec0-sig-v1`
     * preimage (`docs/engine/design.md` §4.3).
     */
    fun signEnvelope(
        identity: Ed25519KeyPair,
        msgType: String,
        msgBody: JsonElement?,
        category: String? = null,
        srcRole: String = EngineConstants.ENGINE_SRC_ROLE,
        timestampMs: Long = System.currentTimeMillis(),
        seq: Long,
        nonce: Long = secureRandom.nextLong(),
    ): Ec0Envelope {
        val preimage = Ec0EnvelopeCodec.signingPreimage(
            msgType = msgType,
            srcRole = srcRole,
            category = category,
            timestamp = timestampMs,
            seq = seq,
            nonce = nonce,
            msgBody = msgBody,
        )
        val sigBytes = identity.sign(preimage)
        return Ec0Envelope(
            msgType = msgType,
            msgBody = msgBody,
            srcRole = srcRole,
            category = category,
            timestamp = timestampMs,
            seq = seq,
            nonce = nonce,
            sig = sigBytes.toBase64UrlUnpadded(),
        )
    }

    /**
     * Verifies the `Ed25519` signature of [envelope] against [peerPublicKeyRaw32]
     * over the canonical `ec0-sig-v1` preimage. Returns `false` on any malformed
     * header field, non-integer number in `msg_body`, or signature mismatch.
     */
    fun verifySignature(
        envelope: Ec0Envelope,
        peerPublicKeyRaw32: ByteArray,
    ): Boolean {
        val sigBytes = try {
            envelope.sig.decodeBase64Url()
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (sigBytes.size != EngineConstants.ED25519_SIG_BYTES) return false
        val preimage = try {
            Ec0EnvelopeCodec.signingPreimage(envelope)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return Ed25519Identity.verify(peerPublicKeyRaw32, preimage, sigBytes)
    }
}

/**
 * Per-session outbound envelope builder (tracks monotonic `seq = 1, 2, 3...`)
 * and inbound verifier (checks `Ed25519` signature + `ReplayWindow` + `OfflineFlushDeduplicator`).
 */
class SessionEnvelopeProcessor(
    private val localIdentity: Ed25519KeyPair,
    private val peerDeviceId: String,
    private val peerPublicKeyRaw32: ByteArray,
    val replayWindow: ReplayWindow = ReplayWindow(),
    val offlineDeduplicator: OfflineFlushDeduplicator = OfflineFlushDeduplicator(),
) {
    private val outboundSeq = AtomicLong(0L)

    fun nextCmdEnvelope(
        cmdType: String,
        cmdBody: JsonElement? = null,
        cmdValue: Int = 0,
        reason: String = "",
        category: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope {
        val seq = outboundSeq.incrementAndGet()
        val body = Ec0EnvelopeCodec.buildCmdBody(cmdType, cmdBody, cmdValue, reason)
        return EnvelopeSigner.signEnvelope(
            identity = localIdentity,
            msgType = Ec0EnvelopeCodec.TYPE_CMD,
            msgBody = body,
            category = category,
            timestampMs = timestampMs,
            seq = seq,
        )
    }

    fun nextInfoEnvelope(
        infoType: String,
        infoBody: JsonElement? = null,
        infoValue: Int = 0,
        category: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope {
        val seq = outboundSeq.incrementAndGet()
        val body = Ec0EnvelopeCodec.buildInfoBody(infoType, infoBody, infoValue)
        return EnvelopeSigner.signEnvelope(
            identity = localIdentity,
            msgType = Ec0EnvelopeCodec.TYPE_INFO,
            msgBody = body,
            category = category,
            timestampMs = timestampMs,
            seq = seq,
        )
    }

    /**
     * Verifies both the `Ed25519` signature and the live session [ReplayWindow] for [envelope].
     * Note: signature is checked BEFORE mutating [ReplayWindow] so an unauthenticated
     * forged packet can never advance `highestSeq` (anti-DoS window poisoning).
     */
    fun verifyLiveEnvelope(
        envelope: Ec0Envelope,
        nowMs: Long = System.currentTimeMillis(),
    ): ReplayCheckResult {
        if (!EnvelopeSigner.verifySignature(envelope, peerPublicKeyRaw32)) {
            throw HandshakeSecurityException("Invalid Ed25519 signature on Ec0Envelope (seq=${envelope.seq})")
        }
        return replayWindow.checkAndRecord(
            seq = envelope.seq,
            timestampMs = envelope.timestamp,
            nowMs = nowMs,
        )
    }

    /**
     * Verifies and deduplicates an `"offline_flush"` batch from [peerDeviceId]
     * using [OfflineFlushDeduplicator] (`docs/engine/design.md` §4.4, Amendment 2).
     */
    fun verifyOfflineBatch(
        batchSeq: Long,
        innerEnvelopes: List<Ec0Envelope>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Ec0Envelope> = offlineDeduplicator.filterBatch(
        peerDeviceId = peerDeviceId,
        batchSeq = batchSeq,
        innerEnvelopes = innerEnvelopes,
        nowMs = nowMs,
        signatureVerifier = { inner -> EnvelopeSigner.verifySignature(inner, peerPublicKeyRaw32) },
    )
}
