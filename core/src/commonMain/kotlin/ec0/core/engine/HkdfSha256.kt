/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-Kotlin RFC 2104 HMAC-SHA256 and RFC 5869 HKDF-SHA256 in commonMain,
 * built over ec0.core.kdeconnect.Sha256 (FIPS 180-4).
 *
 * Also implements the normative ec0-engine session key derivation schedule
 * (docs/engine/design.md §3.4):
 *   salt = nonce_I (16B) || nonce_R (16B)
 *   ikm  = Z (32B X25519 shared secret)
 *   info = "ec0-v1-session" (14B) || ik_pub (32B) || rk_pub (32B) || epk_I (32B) || epk_R (32B)
 *   OKM  = 96 bytes -> [0..32) kInitToResp, [32..64) kRespToInit, [64..96) sessionId
 */
package ec0.core.engine

import ec0.core.kdeconnect.Sha256

data class SessionKeyMaterial(
    val kInitToResp: ByteArray,
    val kRespToInit: ByteArray,
    val sessionId: ByteArray,
    val prk: ByteArray,
) {
    init {
        require(kInitToResp.size == EngineConstants.SESSION_KEY_BYTES)
        require(kRespToInit.size == EngineConstants.SESSION_KEY_BYTES)
        require(sessionId.size == EngineConstants.SESSION_ID_BYTES)
        require(prk.size == HkdfSha256.HASH_LEN)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionKeyMaterial) return false
        return EngineEncoding.constantTimeEquals(kInitToResp, other.kInitToResp) &&
            EngineEncoding.constantTimeEquals(kRespToInit, other.kRespToInit) &&
            EngineEncoding.constantTimeEquals(sessionId, other.sessionId) &&
            EngineEncoding.constantTimeEquals(prk, other.prk)
    }

    override fun hashCode(): Int = sessionId.contentHashCode()
}

object HkdfSha256 {
    const val HASH_LEN: Int = 32
    private const val BLOCK_SIZE: Int = 64
    private const val MAX_OKM_LEN: Int = 255 * HASH_LEN

    /** RFC 2104 HMAC-SHA256 over pure-Kotlin Sha256. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val normalizedKey = if (key.size > BLOCK_SIZE) Sha256.digest(key) else key
        val kPad = ByteArray(BLOCK_SIZE)
        normalizedKey.copyInto(kPad)

        val ipad = ByteArray(BLOCK_SIZE) { i -> (kPad[i].toInt() xor 0x36).toByte() }
        val opad = ByteArray(BLOCK_SIZE) { i -> (kPad[i].toInt() xor 0x5c).toByte() }

        val innerInput = ByteArray(BLOCK_SIZE + data.size)
        ipad.copyInto(innerInput, destinationOffset = 0)
        data.copyInto(innerInput, destinationOffset = BLOCK_SIZE)
        val innerHash = Sha256.digest(innerInput)

        val outerInput = ByteArray(BLOCK_SIZE + HASH_LEN)
        opad.copyInto(outerInput, destinationOffset = 0)
        innerHash.copyInto(outerInput, destinationOffset = BLOCK_SIZE)
        return Sha256.digest(outerInput)
    }

    /**
     * RFC 5869 §2.2 `HKDF-Extract(salt, IKM) -> PRK`.
     * If [salt] is empty, defaults to [HASH_LEN] zero bytes per RFC 5869 §2.2.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    /**
     * RFC 5869 §2.3 `HKDF-Expand(PRK, info, L) -> OKM`.
     */
    fun expand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(prk.size >= HASH_LEN) { "PRK must be at least $HASH_LEN bytes (got ${prk.size})" }
        require(outLen in 1..MAX_OKM_LEN) { "Invalid HKDF output length: $outLen" }

        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < outLen) {
            val hmacInput = ByteArray(t.size + info.size + 1)
            t.copyInto(hmacInput, destinationOffset = 0)
            info.copyInto(hmacInput, destinationOffset = t.size)
            hmacInput[t.size + info.size] = counter.toByte()
            t = hmacSha256(prk, hmacInput)
            val copyLen = minOf(HASH_LEN, outLen - generated)
            t.copyInto(out, destinationOffset = generated, startIndex = 0, endIndex = copyLen)
            generated += copyLen
            counter++
        }
        return out
    }

    /** Combined RFC 5869 Extract + Expand. */
    fun deriveKey(salt: ByteArray, ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, outLen)
    }

    /**
     * Normative `ec0-engine` session key derivation (`docs/engine/design.md` §3.4).
     */
    fun deriveSessionKeys(
        sharedSecretZ: ByteArray,
        nonceI: ByteArray,
        nonceR: ByteArray,
        ikPubRaw: ByteArray,
        rkPubRaw: ByteArray,
        epkIRaw: ByteArray,
        epkRRaw: ByteArray,
    ): SessionKeyMaterial {
        require(sharedSecretZ.size == EngineConstants.X25519_KEY_BYTES) { "Z must be 32 bytes" }
        require(!sharedSecretZ.all { it == 0.toByte() }) { "All-zero X25519 shared secret rejected" }
        require(nonceI.size == EngineConstants.HANDSHAKE_NONCE_BYTES) { "nonce_I must be 16 bytes" }
        require(nonceR.size == EngineConstants.HANDSHAKE_NONCE_BYTES) { "nonce_R must be 16 bytes" }
        require(ikPubRaw.size == EngineConstants.ED25519_KEY_BYTES) { "ik_pub must be 32 bytes" }
        require(rkPubRaw.size == EngineConstants.ED25519_KEY_BYTES) { "rk_pub must be 32 bytes" }
        require(epkIRaw.size == EngineConstants.X25519_KEY_BYTES) { "epk_I must be 32 bytes" }
        require(epkRRaw.size == EngineConstants.X25519_KEY_BYTES) { "epk_R must be 32 bytes" }

        val salt = ByteArray(32)
        nonceI.copyInto(salt, destinationOffset = 0)
        nonceR.copyInto(salt, destinationOffset = 16)

        val tagBytes = EngineConstants.SESSION_INFO_TAG.encodeToByteArray() // 14 bytes
        val info = ByteArray(tagBytes.size + 128) // 142 bytes total
        var offset = 0
        tagBytes.copyInto(info, offset); offset += tagBytes.size
        ikPubRaw.copyInto(info, offset); offset += 32
        rkPubRaw.copyInto(info, offset); offset += 32
        epkIRaw.copyInto(info, offset); offset += 32
        epkRRaw.copyInto(info, offset)

        val prk = extract(salt, sharedSecretZ)
        val okm = expand(prk, info, 96)
        return SessionKeyMaterial(
            kInitToResp = okm.copyOfRange(0, 32),
            kRespToInit = okm.copyOfRange(32, 64),
            sessionId = okm.copyOfRange(64, 96),
            prk = prk,
        )
    }
}
