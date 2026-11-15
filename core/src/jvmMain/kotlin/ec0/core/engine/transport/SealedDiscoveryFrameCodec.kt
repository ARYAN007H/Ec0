package ec0.core.engine.transport

import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectIdentity
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Codec for Motorola Smart Connect ("Ready For") binary `SealedDiscoveryFrame` payloads
 * exchanged over `/readyfor/mdns` WebSocket binary frames and BLE GATT characteristic `0x0722`
 * (`docs/engine/smart-connect-design.md` §1.3 & §1.4).
 *
 * Binary Wire Layout (`>= 45` bytes):
 * ```
 * Offset  Length  Field
 * 0       16      senderNonceId (raw 16-byte salted-hash nonceId of sender)
 * 16      1       frameType     (MUST be 0x01 = SEALED)
 * 17      12      iv            (96-bit AES-GCM initialization vector)
 * 29      N       ciphertext    (UTF-8 JSON payload encrypted under AES-256-GCM)
 * 29 + N  16      gcmTag        (128-bit authentication tag)
 * ```
 *
 * - **AAD**: `bytes[0..17)` (`17` bytes = `senderNonceId[0..16) || frameType[16]`).
 * - **Key**: 32-byte `perPairKey` used directly as AES-256 key (no HKDF/PBKDF2).
 *
 * **CRITICAL UNPAIR HAZARD GUARD**:
 * In the decompiled phone APK (`f.h.c.a.s.b`), receiving `frameType == 0x02` (`PLAINTEXT`) or
 * `frameType == 0x03` (`CANNOT_DECRYPT`) fires `OnReceiveUnPairedEvent` and causes the phone to
 * delete the PC pairing record! [seal] therefore hardcodes `frameType = 0x01` with no parameter
 * to override it, and [assertOutgoingFrameSafe] rejects any frame whose `frameType != 0x01`.
 */
object SealedDiscoveryFrameCodec {

    private const val HEADER_AAD_LEN = SmartConnectConstants.SEALED_FRAME_AAD_BYTES // 17
    private const val NONCE_ID_LEN = SmartConnectConstants.SEALED_FRAME_NONCE_ID_BYTES // 16
    private const val IV_LEN = SmartConnectConstants.SEALED_FRAME_IV_BYTES // 12
    private const val TAG_LEN_BYTES = SmartConnectConstants.SEALED_FRAME_TAG_BYTES // 16
    private const val TAG_LEN_BITS = TAG_LEN_BYTES * 8 // 128
    private const val MIN_SEALED_FRAME_LEN = HEADER_AAD_LEN + IV_LEN + TAG_LEN_BYTES // 45

    private val secureRandom = SecureRandom()

    data class OpenedDiscoveryFrame(
        val senderNonceIdRaw16: ByteArray,
        val frameType: Byte,
        val iv12: ByteArray,
        val plaintextUtf8: String,
        val matchedPeerRealDeviceId: String? = null
    )

    data class CandidatePeerKey(
        val realDeviceIdBase64: String,
        val perPairKey32: ByteArray,
        val phoneKey32: ByteArray? = null
    )

    /**
     * Seals [plaintextUtf8] into a binary `SealedDiscoveryFrame` using `AES-256-GCM`.
     *
     * `frameType` is hardcoded to `0x01` ([SmartConnectConstants.SEALED_FRAME_TYPE_ENCRYPTED]).
     */
    fun seal(
        senderNonceIdRaw16: ByteArray,
        perPairKey32: ByteArray,
        plaintextUtf8: String,
        iv12: ByteArray? = null
    ): ByteArray {
        require(senderNonceIdRaw16.size == NONCE_ID_LEN) {
            "senderNonceIdRaw16 must be $NONCE_ID_LEN bytes (got ${senderNonceIdRaw16.size})"
        }
        require(perPairKey32.size == 32) {
            "perPairKey32 must be 32 bytes for AES-256-GCM (got ${perPairKey32.size})"
        }
        val actualIv = iv12 ?: ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
        require(actualIv.size == IV_LEN) {
            "iv12 must be $IV_LEN bytes (got ${actualIv.size})"
        }

        val aad = ByteArray(HEADER_AAD_LEN)
        senderNonceIdRaw16.copyInto(aad, destinationOffset = 0, startIndex = 0, endIndex = NONCE_ID_LEN)
        // Hardcode 0x01 (SEALED) — NEVER emit 0x02 or 0x03!
        aad[NONCE_ID_LEN] = SmartConnectConstants.SEALED_FRAME_TYPE_ENCRYPTED

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(perPairKey32, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LEN_BITS, actualIv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        val plaintextBytes = plaintextUtf8.toByteArray(StandardCharsets.UTF_8)
        val ciphertextAndTag = cipher.doFinal(plaintextBytes)

        val out = ByteArray(HEADER_AAD_LEN + IV_LEN + ciphertextAndTag.size)
        aad.copyInto(out, destinationOffset = 0)
        actualIv.copyInto(out, destinationOffset = HEADER_AAD_LEN)
        ciphertextAndTag.copyInto(out, destinationOffset = HEADER_AAD_LEN + IV_LEN)

        assertOutgoingFrameSafe(out)
        return out
    }

    /**
     * Verifies that an outgoing discovery binary frame is at least 45 bytes and carries
     * `frameType == 0x01` (`SEALED`), preventing accidental transmission of `0x02` (`PLAINTEXT`)
     * or `0x03` (`CANNOT_DECRYPT`) unpair-hazard frames.
     */
    fun assertOutgoingFrameSafe(frame: ByteArray) {
        require(frame.size >= MIN_SEALED_FRAME_LEN) {
            "SealedDiscoveryFrame must be at least $MIN_SEALED_FRAME_LEN bytes (got ${frame.size})"
        }
        val frameType = frame[NONCE_ID_LEN]
        require(frameType == SmartConnectConstants.SEALED_FRAME_TYPE_ENCRYPTED) {
            "UNPAIR HAZARD BLOCKED: Outgoing discovery frame must have frameType=0x01, got 0x${
                (frameType.toInt() and 0xff).toString(16).padStart(2, '0')
            }"
        }
    }

    /**
     * Opens and authenticates a binary `SealedDiscoveryFrame` using a known 32-byte key
     * (and optional fallback [fallbackPhoneKey32]).
     */
    fun openWithKey(
        frame: ByteArray,
        perPairKey32: ByteArray,
        fallbackPhoneKey32: ByteArray? = null,
        matchedPeerRealDeviceId: String? = null
    ): OpenedDiscoveryFrame {
        require(frame.size >= MIN_SEALED_FRAME_LEN) {
            "SealedDiscoveryFrame too short: ${frame.size} bytes (min $MIN_SEALED_FRAME_LEN)"
        }
        val senderNonceIdRaw16 = frame.copyOfRange(0, NONCE_ID_LEN)
        val frameType = frame[NONCE_ID_LEN]
        require(frameType == SmartConnectConstants.SEALED_FRAME_TYPE_ENCRYPTED) {
            "Refusing to open non-encrypted discovery frameType=0x${
                (frameType.toInt() and 0xff).toString(16).padStart(2, '0')
            }"
        }
        val aad = frame.copyOfRange(0, HEADER_AAD_LEN)
        val iv12 = frame.copyOfRange(HEADER_AAD_LEN, HEADER_AAD_LEN + IV_LEN)
        val ciphertextAndTag = frame.copyOfRange(HEADER_AAD_LEN + IV_LEN, frame.size)

        val primaryAttempt = runCatching {
            decryptGcm(perPairKey32, iv12, aad, ciphertextAndTag)
        }
        if (primaryAttempt.isSuccess) {
            return OpenedDiscoveryFrame(
                senderNonceIdRaw16 = senderNonceIdRaw16,
                frameType = frameType,
                iv12 = iv12,
                plaintextUtf8 = primaryAttempt.getOrThrow(),
                matchedPeerRealDeviceId = matchedPeerRealDeviceId
            )
        }

        if (fallbackPhoneKey32 != null && fallbackPhoneKey32.size == 32) {
            val fallbackAttempt = runCatching {
                decryptGcm(fallbackPhoneKey32, iv12, aad, ciphertextAndTag)
            }
            if (fallbackAttempt.isSuccess) {
                return OpenedDiscoveryFrame(
                    senderNonceIdRaw16 = senderNonceIdRaw16,
                    frameType = frameType,
                    iv12 = iv12,
                    plaintextUtf8 = fallbackAttempt.getOrThrow(),
                    matchedPeerRealDeviceId = matchedPeerRealDeviceId
                )
            }
        }

        throw primaryAttempt.exceptionOrNull()
            ?: IllegalArgumentException("Failed to decrypt SealedDiscoveryFrame")
    }

    /**
     * Matches the 16-byte `senderNonceId` header against [candidates] using
     * [SmartConnectIdentity.matchNonceIdToPairedPeer] (with fallback trial across all paired peers)
     * and opens the frame.
     *
     * Note: If decryption fails for all paired peers, callers MUST close the socket silently and
     * NEVER reply with `0x02` or `0x03`.
     */
    fun openFromPairedCandidates(
        frame: ByteArray,
        candidates: List<CandidatePeerKey>
    ): OpenedDiscoveryFrame? {
        if (frame.size < MIN_SEALED_FRAME_LEN) return null
        val senderNonceId = frame.copyOfRange(0, NONCE_ID_LEN)

        val matchedCandidate = SmartConnectIdentity.matchNonceIdToPairedPeer(
            candidateNonceIdRaw16 = senderNonceId,
            pairedPeers = candidates,
            realDeviceIdSelector = { it.realDeviceIdBase64 }
        )

        val orderedCandidates = buildList {
            if (matchedCandidate != null) add(matchedCandidate)
            for (c in candidates) {
                if (c !== matchedCandidate) add(c)
            }
        }

        for (candidate in orderedCandidates) {
            val opened = runCatching {
                openWithKey(
                    frame = frame,
                    perPairKey32 = candidate.perPairKey32,
                    fallbackPhoneKey32 = candidate.phoneKey32,
                    matchedPeerRealDeviceId = candidate.realDeviceIdBase64
                )
            }.getOrNull()
            if (opened != null) return opened
        }
        return null
    }

    private fun decryptGcm(
        key32: ByteArray,
        iv12: ByteArray,
        aad17: ByteArray,
        ciphertextAndTag: ByteArray
    ): String {
        require(key32.size == 32) { "AES-256 key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key32, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LEN_BITS, iv12)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad17)
        val pt = cipher.doFinal(ciphertextAndTag)
        return pt.toString(StandardCharsets.UTF_8)
    }
}
