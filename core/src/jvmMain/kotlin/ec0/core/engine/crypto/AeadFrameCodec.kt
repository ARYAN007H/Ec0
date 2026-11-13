/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * ChaCha20-Poly1305 (RFC 8439) AEAD frame encryption/decryption and session
 * counter channel (docs/engine/design.md §3.5, Amendment 1).
 *
 * Packet layout on wire:
 *   [frameType: 1B][counter: 8B big-endian][ciphertext || poly1305_tag: N + 16B]
 *
 * Nonce (12 bytes):
 *   [0x00, 0x00, 0x00, frameType] (4B) || counter_uint64_be (8B)
 *
 * Additional Authenticated Data (AAD, 41 bytes):
 *   sessionId (32B) || frameType (1B) || counter_uint64_be (8B)
 *
 * Counter invariant:
 *   - counter == 0 is reserved exclusively for frameType == 0x01 (AUTH_CONFIRM).
 *   - counter >= 1 is reserved exclusively for frameType == 0x02 (ENVELOPE_JSON)
 *     and frameType == 0x03 (BULK_CHUNK).
 */
package ec0.core.engine.crypto

import ec0.core.engine.EngineConstants
import ec0.core.engine.HkdfSha256
import ec0.core.engine.SessionKeyMaterial
import java.security.GeneralSecurityException
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

class AeadSecurityException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

data class DecryptedAeadFrame(
    val frameType: Byte,
    val counter: Long,
    val plaintext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedAeadFrame) return false
        return frameType == other.frameType &&
            counter == other.counter &&
            plaintext.contentEquals(other.plaintext)
    }

    override fun hashCode(): Int = plaintext.contentHashCode()
}

object AeadFrameCodec {

    private const val JCA_ALGORITHM = "ChaCha20-Poly1305"
    private const val KEY_ALGORITHM = "ChaCha20"

    /**
     * Raw RFC 8439 §2.8 AEAD encryption (`ciphertext || 16B tag`).
     * Directly testable against RFC 8439 §2.8.2 test vectors.
     */
    fun encryptRaw(
        key32: ByteArray,
        nonce12: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key32.size == EngineConstants.SESSION_KEY_BYTES) {
            "ChaCha20-Poly1305 key must be 32 bytes (got ${key32.size})"
        }
        require(nonce12.size == EngineConstants.AEAD_NONCE_BYTES) {
            "ChaCha20-Poly1305 nonce must be 12 bytes (got ${nonce12.size})"
        }
        return try {
            val cipher = Cipher.getInstance(JCA_ALGORITHM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key32, KEY_ALGORITHM),
                IvParameterSpec(nonce12),
            )
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw AeadSecurityException("ChaCha20-Poly1305 encryption failed", e)
        }
    }

    /**
     * Raw RFC 8439 §2.8 AEAD decryption. Throws [AeadSecurityException] on tag mismatch.
     */
    fun decryptRaw(
        key32: ByteArray,
        nonce12: ByteArray,
        aad: ByteArray,
        ciphertextWithTag: ByteArray,
    ): ByteArray {
        require(key32.size == EngineConstants.SESSION_KEY_BYTES) {
            "ChaCha20-Poly1305 key must be 32 bytes (got ${key32.size})"
        }
        require(nonce12.size == EngineConstants.AEAD_NONCE_BYTES) {
            "ChaCha20-Poly1305 nonce must be 12 bytes (got ${nonce12.size})"
        }
        if (ciphertextWithTag.size < EngineConstants.AEAD_TAG_BYTES) {
            throw AeadSecurityException(
                "Ciphertext shorter than Poly1305 tag (${ciphertextWithTag.size} bytes)",
            )
        }
        return try {
            val cipher = Cipher.getInstance(JCA_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key32, KEY_ALGORITHM),
                IvParameterSpec(nonce12),
            )
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            cipher.doFinal(ciphertextWithTag)
        } catch (e: GeneralSecurityException) {
            throw AeadSecurityException("ChaCha20-Poly1305 authentication tag verification failed", e)
        }
    }

    /**
     * Seals an `ec0-engine` session frame (`[frameType: 1B][counter: 8B][ciphertext || tag]`).
     */
    fun sealFrame(
        key32: ByteArray,
        sessionId32: ByteArray,
        frameType: Byte,
        counter: Long,
        plaintext: ByteArray,
    ): ByteArray {
        require(sessionId32.size == EngineConstants.SESSION_ID_BYTES) {
            "sessionId must be 32 bytes"
        }
        validateFrameTypeAndCounter(frameType, counter)

        val nonce12 = buildSessionNonce(frameType, counter)
        val aad41 = buildSessionAad(sessionId32, frameType, counter)
        val ctAndTag = encryptRaw(key32, nonce12, aad41, plaintext)

        val packet = ByteArray(EngineConstants.AEAD_HEADER_BYTES + ctAndTag.size)
        packet[0] = frameType
        writeLongBE(packet, 1, counter)
        ctAndTag.copyInto(packet, destinationOffset = EngineConstants.AEAD_HEADER_BYTES)
        return packet
    }

    /**
     * Opens and authenticates an `ec0-engine` session frame, strictly enforcing
     * that the wire counter equals [expectedCounter] and satisfies the counter=0
     * vs counter>=1 frame-type separation rule.
     */
    fun openFrame(
        key32: ByteArray,
        sessionId32: ByteArray,
        expectedCounter: Long,
        packet: ByteArray,
    ): DecryptedAeadFrame {
        require(sessionId32.size == EngineConstants.SESSION_ID_BYTES) {
            "sessionId must be 32 bytes"
        }
        if (packet.size < EngineConstants.AEAD_HEADER_BYTES + EngineConstants.AEAD_TAG_BYTES) {
            throw AeadSecurityException("Truncated AEAD frame packet (${packet.size} bytes)")
        }
        val frameType = packet[0]
        val wireCounter = readLongBE(packet, 1)
        if (wireCounter != expectedCounter) {
            throw AeadSecurityException(
                "AEAD counter mismatch: expected $expectedCounter, got $wireCounter",
            )
        }
        validateFrameTypeAndCounter(frameType, wireCounter)

        val nonce12 = buildSessionNonce(frameType, wireCounter)
        val aad41 = buildSessionAad(sessionId32, frameType, wireCounter)
        val ctAndTag = packet.copyOfRange(EngineConstants.AEAD_HEADER_BYTES, packet.size)
        val plaintext = decryptRaw(key32, nonce12, aad41, ctAndTag)
        return DecryptedAeadFrame(frameType, wireCounter, plaintext)
    }

    internal fun buildSessionNonce(frameType: Byte, counter: Long): ByteArray {
        val nonce = ByteArray(EngineConstants.AEAD_NONCE_BYTES)
        nonce[0] = 0
        nonce[1] = 0
        nonce[2] = 0
        nonce[3] = frameType
        writeLongBE(nonce, 4, counter)
        return nonce
    }

    internal fun buildSessionAad(sessionId32: ByteArray, frameType: Byte, counter: Long): ByteArray {
        val aad = ByteArray(EngineConstants.AEAD_AAD_BYTES)
        sessionId32.copyInto(aad, destinationOffset = 0)
        aad[32] = frameType
        writeLongBE(aad, 33, counter)
        return aad
    }

    private fun validateFrameTypeAndCounter(frameType: Byte, counter: Long) {
        if (counter < 0L) {
            throw AeadSecurityException("Negative AEAD counter prohibited: $counter")
        }
        when (frameType) {
            EngineConstants.FRAME_AUTH_CONFIRM -> {
                if (counter != 0L) {
                    throw AeadSecurityException(
                        "FRAME_AUTH_CONFIRM (0x01) is only permitted at counter=0 (got counter=$counter)",
                    )
                }
            }
            EngineConstants.FRAME_ENVELOPE_JSON,
            EngineConstants.FRAME_BULK_CHUNK,
            EngineConstants.FRAME_KEY_UPDATE,
            -> {
                if (counter == 0L) {
                    throw AeadSecurityException(
                        "Application frameType 0x0${frameType.toInt()} prohibited at counter=0",
                    )
                }
            }
            else -> throw AeadSecurityException("Unknown AEAD frameType: 0x${(frameType.toInt() and 0xff).toString(16)}")
        }
    }

    private fun writeLongBE(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buf[offset + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    private fun readLongBE(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (buf[offset + i].toLong() and 0xffL)
        }
        return result
    }
}

/**
 * Stateful bidirectional AEAD channel bound to a derived [SessionKeyMaterial].
 * Automatically tracks `sendCounter` and `recvCounter` (`0` for auth confirmation,
 * `1, 2, 3...` for application frames) and supports forward-secret session key
 * rotation (`FRAME_KEY_UPDATE = 0x04`).
 */
class AeadSessionChannel(
    keys: SessionKeyMaterial,
    val isInitiator: Boolean,
) {
    private var currentKeys: SessionKeyMaterial = keys
    private var sendKey: ByteArray = if (isInitiator) keys.kInitToResp else keys.kRespToInit
    private var recvKey: ByteArray = if (isInitiator) keys.kRespToInit else keys.kInitToResp
    private val sessionId: ByteArray = keys.sessionId

    private val sendLock = ReentrantLock()
    private val recvLock = ReentrantLock()

    var rekeyEpoch: Int = 0
        private set

    var nextSendCounter: Long = 0L
        private set

    var nextRecvCounter: Long = 0L
        private set

    /**
     * Rotates the symmetric directional keys forward using [HkdfSha256.deriveRekeyedSessionKeys]
     * without resetting the monotonic counters or `sessionId`.
     */
    fun rotateKeys(): Int = sendLock.withLock {
        recvLock.withLock {
            val nextEpoch = rekeyEpoch + 1
            val rotated = HkdfSha256.deriveRekeyedSessionKeys(currentKeys, nextEpoch)
            currentKeys = rotated
            sendKey = if (isInitiator) rotated.kInitToResp else rotated.kRespToInit
            recvKey = if (isInitiator) rotated.kRespToInit else rotated.kInitToResp
            rekeyEpoch = nextEpoch
            nextEpoch
        }
    }

    fun sealAuthConfirm(plaintext: ByteArray): ByteArray = sendLock.withLock {
        if (nextSendCounter != 0L) {
            throw AeadSecurityException("sealAuthConfirm must be the first frame (counter=0)")
        }
        val packet = AeadFrameCodec.sealFrame(
            key32 = sendKey,
            sessionId32 = sessionId,
            frameType = EngineConstants.FRAME_AUTH_CONFIRM,
            counter = 0L,
            plaintext = plaintext,
        )
        nextSendCounter = 1L
        packet
    }

    fun openAuthConfirm(packet: ByteArray): ByteArray = recvLock.withLock {
        if (nextRecvCounter != 0L) {
            throw AeadSecurityException("openAuthConfirm must be the first received frame (counter=0)")
        }
        val frame = AeadFrameCodec.openFrame(
            key32 = recvKey,
            sessionId32 = sessionId,
            expectedCounter = 0L,
            packet = packet,
        )
        nextRecvCounter = 1L
        frame.plaintext
    }

    fun sealApplicationFrame(frameType: Byte, plaintext: ByteArray): ByteArray = sendLock.withLock {
        if (nextSendCounter < 1L) {
            throw AeadSecurityException("Cannot send application frame before AUTH_CONFIRM (counter=0)")
        }
        val counter = nextSendCounter
        val packet = AeadFrameCodec.sealFrame(
            key32 = sendKey,
            sessionId32 = sessionId,
            frameType = frameType,
            counter = counter,
            plaintext = plaintext,
        )
        nextSendCounter = counter + 1L
        packet
    }

    fun openApplicationFrame(packet: ByteArray): DecryptedAeadFrame = recvLock.withLock {
        if (nextRecvCounter < 1L) {
            throw AeadSecurityException("Cannot receive application frame before AUTH_CONFIRM (counter=0)")
        }
        val counter = nextRecvCounter
        val frame = AeadFrameCodec.openFrame(
            key32 = recvKey,
            sessionId32 = sessionId,
            expectedCounter = counter,
            packet = packet,
        )
        nextRecvCounter = counter + 1L
        frame
    }
}
