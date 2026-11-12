/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * TOFU peer key pinning store and single-use QR pairing coordinator
 * (docs/engine/design.md §3.1, §7, Amendment 3).
 */
package ec0.core.engine.crypto

import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.EngineIdentity
import ec0.core.engine.PairingQrPayload
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

class KeyMismatchException(
    val deviceId: String,
    message: String,
) : GeneralSecurityException(message)

enum class TrustLevel {
    /** Pinned via verified QR scan (`ec0://pair` + ephemeral-bound `pairing_proof`). */
    TRUSTED_QR,

    /** Pinned via Trust-On-First-Use (8-character verification badge confirmation). */
    TOFU_PINNED,
}

data class PinnedPeer(
    val deviceId: String,
    val publicKeyRaw: ByteArray,
    val alias: String,
    val trustLevel: TrustLevel,
    val pinnedAtMs: Long,
) {
    init {
        require(publicKeyRaw.size == EngineConstants.ED25519_KEY_BYTES)
        require(EngineIdentity.verifyDeviceId(deviceId, publicKeyRaw)) {
            "PinnedPeer deviceId does not match SHA-256(publicKeyRaw)"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinnedPeer) return false
        return deviceId == other.deviceId &&
            EngineEncoding.constantTimeEquals(publicKeyRaw, other.publicKeyRaw) &&
            alias == other.alias &&
            trustLevel == other.trustLevel &&
            pinnedAtMs == other.pinnedAtMs
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Persistent or in-memory store of pinned peer Ed25519 identities.
 * Enforces the strict key-pinning invariant: once a `deviceId` (or peer entry) is
 * pinned, any connection presenting a different public key throws [KeyMismatchException]
 * unless [unpair] is explicitly called.
 */
interface PinStore {
    fun get(deviceId: String): PinnedPeer?
    fun all(): List<PinnedPeer>
    fun verifyOrPin(
        deviceId: String,
        publicKeyRaw: ByteArray,
        alias: String,
        trustLevel: TrustLevel,
        nowMs: Long = System.currentTimeMillis(),
    ): PinnedPeer

    fun unpair(deviceId: String): Boolean
}

class InMemoryPinStore(
    private val allowAutoTofuOnUnknownPeer: Boolean = true,
) : PinStore {
    private val peers = ConcurrentHashMap<String, PinnedPeer>()

    override fun get(deviceId: String): PinnedPeer? = peers[deviceId]

    override fun all(): List<PinnedPeer> = peers.values.sortedBy { it.alias }

    /**
     * Checks [deviceId] and [publicKeyRaw] against the pin store.
     * - If `deviceId` does not match `SHA-256(publicKeyRaw)`, throws [KeyMismatchException].
     * - If `deviceId` is already pinned with a different key, throws [KeyMismatchException].
     * - If `deviceId` is unknown and (`trustLevel == TRUSTED_QR` or [allowAutoTofuOnUnknownPeer]),
     *   pins and returns the new [PinnedPeer].
     */
    override fun verifyOrPin(
        deviceId: String,
        publicKeyRaw: ByteArray,
        alias: String,
        trustLevel: TrustLevel,
        nowMs: Long,
    ): PinnedPeer {
        if (publicKeyRaw.size != EngineConstants.ED25519_KEY_BYTES ||
            !EngineIdentity.verifyDeviceId(deviceId, publicKeyRaw)
        ) {
            throw KeyMismatchException(
                deviceId = deviceId,
                message = "Presented Ed25519 public key does not match claimed deviceId '$deviceId'",
            )
        }

        val existing = peers[deviceId]
        if (existing != null) {
            if (!EngineEncoding.constantTimeEquals(existing.publicKeyRaw, publicKeyRaw)) {
                throw KeyMismatchException(
                    deviceId = deviceId,
                    message = "Pinned Ed25519 key mismatch for deviceId '$deviceId'",
                )
            }
            // Upgrade TOFU -> TRUSTED_QR or update alias if changed
            val updatedTrust = if (existing.trustLevel == TrustLevel.TRUSTED_QR) {
                TrustLevel.TRUSTED_QR
            } else {
                trustLevel
            }
            val updated = existing.copy(alias = alias, trustLevel = updatedTrust)
            peers[deviceId] = updated
            return updated
        }

        if (trustLevel != TrustLevel.TRUSTED_QR && !allowAutoTofuOnUnknownPeer) {
            throw KeyMismatchException(
                deviceId = deviceId,
                message = "Unknown peer '$deviceId' requires explicit pairing or QR scan",
            )
        }

        val pinned = PinnedPeer(
            deviceId = deviceId,
            publicKeyRaw = publicKeyRaw.copyOf(),
            alias = alias,
            trustLevel = trustLevel,
            pinnedAtMs = nowMs,
        )
        peers[deviceId] = pinned
        return pinned
    }

    override fun unpair(deviceId: String): Boolean = peers.remove(deviceId) != null
}

/**
 * Coordinates QR pairing URI generation, single-use QR nonce lifecycle (`120 s` TTL),
 * and ephemeral-bound `pairing_proof` verification (`docs/engine/design.md` §3.1).
 */
class PairingCoordinator(
    private val identity: Ed25519KeyPair,
    val pinStore: PinStore = InMemoryPinStore(),
    private val qrNonceTtlMs: Long = EngineConstants.QR_NONCE_TTL_MS,
) {
    private data class ActiveQrToken(
        val nonce: ByteArray,
        val createdAtMs: Long,
    )

    private val activeTokens = ConcurrentHashMap<String, ActiveQrToken>()

    /**
     * Generates a fresh [PairingQrPayload] with a single-use 16-byte random nonce
     * valid for [qrNonceTtlMs] (`120 s`).
     */
    fun generateQrPayload(
        alias: String,
        port: Int = EngineConstants.PRIMARY_PORT,
        ips: List<String> = emptyList(),
        nonceOverride: ByteArray? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): PairingQrPayload {
        purgeExpiredTokens(nowMs)
        val nonce = nonceOverride?.copyOf() ?: X25519Handshake.randomNonce16()
        val payload = PairingQrPayload(
            version = EngineConstants.PROTOCOL_VERSION,
            deviceId = identity.deviceId,
            publicKeyRaw = identity.publicKeyRaw,
            alias = alias,
            nonce = nonce,
            port = port,
            ips = ips,
        )
        activeTokens[nonce.toHex()] = ActiveQrToken(nonce = nonce, createdAtMs = nowMs)
        return payload
    }

    /**
     * Scanning side: validates a scanned `ec0://pair?...` URI, pins the Responder in
     * [pinStore] as [TrustLevel.TRUSTED_QR], and returns the parsed [PairingQrPayload].
     */
    fun acceptScannedQrUri(
        uri: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PairingQrPayload {
        val payload = PairingQrPayload.parseUri(uri)
        pinStore.verifyOrPin(
            deviceId = payload.deviceId,
            publicKeyRaw = payload.publicKeyRaw,
            alias = payload.alias,
            trustLevel = TrustLevel.TRUSTED_QR,
            nowMs = nowMs,
        )
        return payload
    }

    /**
     * Responder side: authorizes an incoming `CLIENT_HELLO` by checking either:
     *  1. A valid, un-expired, single-use ephemeral-bound `pairing_proof` (which consumes
     *     the QR nonce and pins the Initiator as [TrustLevel.TRUSTED_QR]), OR
     *  2. The existing [pinStore] policy ([TrustLevel.TOFU_PINNED]).
     */
    fun authorizeIncomingClientHello(
        clientDeviceId: String,
        ikPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
        clientAlias: String,
        pairingProofHex: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): PinnedPeer {
        purgeExpiredTokens(nowMs)
        if (pairingProofHex != null) {
            val matchedHex = activeTokens.entries.firstOrNull { (_, token) ->
                EngineIdentity.verifyPairingProof(
                    proofHex = pairingProofHex,
                    nonceQr16 = token.nonce,
                    ikPubRaw32 = ikPubRaw32,
                    rkPubRaw32 = identity.publicKeyRaw,
                    epkIRaw32 = epkIRaw32,
                )
            }?.key
                ?: throw HandshakeSecurityException(
                    "Invalid or expired ephemeral-bound QR pairing_proof from '$clientDeviceId'",
                )
            // Single-use: consume the QR token immediately
            activeTokens.remove(matchedHex)
            return pinStore.verifyOrPin(
                deviceId = clientDeviceId,
                publicKeyRaw = ikPubRaw32,
                alias = clientAlias,
                trustLevel = TrustLevel.TRUSTED_QR,
                nowMs = nowMs,
            )
        }

        return pinStore.verifyOrPin(
            deviceId = clientDeviceId,
            publicKeyRaw = ikPubRaw32,
            alias = clientAlias,
            trustLevel = TrustLevel.TOFU_PINNED,
            nowMs = nowMs,
        )
    }

    private fun purgeExpiredTokens(nowMs: Long) {
        val iter = activeTokens.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (nowMs - entry.value.createdAtMs > qrNonceTtlMs) {
                iter.remove()
            }
        }
    }
}
