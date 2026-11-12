/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ed25519 (RFC 8032) long-term device identity keypair management, deterministic
 * ASN.1 SPKI/PKCS#8 DER wrapping for JDK 17 SunEC interop, and raw 32-byte seed ->
 * public-key derivation (docs/engine/design.md §2.1, §8.1).
 */
package ec0.core.engine.crypto

import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineIdentity
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class Ed25519KeyPair internal constructor(
    private val privateSeedBytes: ByteArray,
    private val publicKeyBytes: ByteArray,
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    val deviceId: String = EngineIdentity.computeDeviceId(publicKeyBytes)
    val shortFingerprint: String = EngineIdentity.shortFingerprint(deviceId)

    val privateSeedRaw: ByteArray get() = privateSeedBytes.copyOf()
    val publicKeyRaw: ByteArray get() = publicKeyBytes.copyOf()

    fun sign(message: ByteArray): ByteArray = Ed25519Identity.sign(privateKey, message)

    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519Identity.verify(publicKey, message, signature)
}

object Ed25519Identity {

    /**
     * ASN.1 DER prefix for Ed25519 SubjectPublicKeyInfo (OID 1.3.101.112, 12 bytes).
     * Total SPKI DER = 12B prefix + 32B raw public key = 44 bytes.
     */
    val SPKI_PREFIX: ByteArray = "302a300506032b6570032100".decodeHex()

    /**
     * ASN.1 DER prefix for Ed25519 PKCS#8 PrivateKeyInfo (OID 1.3.101.112, 16 bytes).
     * Total PKCS#8 DER = 16B prefix + 32B raw secret seed = 48 bytes.
     */
    val PKCS8_PREFIX: ByteArray = "302e020100300506032b657004220420".decodeHex()

    /** Generates a fresh random Ed25519 identity keypair using JDK 17 SunEC. */
    fun generate(): Ed25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        val pkcs8 = kp.private.encoded
        val spki = kp.public.encoded
        require(pkcs8.size >= PKCS8_PREFIX.size + EngineConstants.ED25519_KEY_BYTES) {
            "Unexpected Ed25519 PKCS#8 length: ${pkcs8.size}"
        }
        require(spki.size == SPKI_PREFIX.size + EngineConstants.ED25519_KEY_BYTES) {
            "Unexpected Ed25519 SPKI length: ${spki.size}"
        }
        val seed = pkcs8.copyOfRange(PKCS8_PREFIX.size, PKCS8_PREFIX.size + EngineConstants.ED25519_KEY_BYTES)
        val pub = spki.copyOfRange(SPKI_PREFIX.size, SPKI_PREFIX.size + EngineConstants.ED25519_KEY_BYTES)
        return Ed25519KeyPair(seed, pub, kp.private, kp.public)
    }

    /**
     * Reconstructs an [Ed25519KeyPair] from a 32-byte RFC 8032 secret seed,
     * deriving the 32-byte public key deterministically per RFC 8032 §5.1.5.
     */
    fun fromPrivateSeed(seedRaw32: ByteArray): Ed25519KeyPair {
        require(seedRaw32.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 private seed must be 32 bytes (got ${seedRaw32.size})"
        }
        val pubRaw32 = derivePublicKeyFromSeed(seedRaw32)
        return fromRawKeyPair(seedRaw32, pubRaw32)
    }

    /**
     * Reconstructs an [Ed25519KeyPair] from raw 32-byte seed and public key,
     * verifying that [publicKeyRaw32] genuinely corresponds to [seedRaw32].
     */
    fun fromRawKeyPair(seedRaw32: ByteArray, publicKeyRaw32: ByteArray): Ed25519KeyPair {
        require(seedRaw32.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 private seed must be 32 bytes (got ${seedRaw32.size})"
        }
        require(publicKeyRaw32.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 public key must be 32 bytes (got ${publicKeyRaw32.size})"
        }
        val derivedPub = derivePublicKeyFromSeed(seedRaw32)
        require(EngineEncoding.constantTimeEquals(derivedPub, publicKeyRaw32)) {
            "Ed25519 public key does not match private seed"
        }
        val kf = KeyFactory.getInstance("Ed25519")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(encodePkcs8Der(seedRaw32)))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(encodeSpkiDer(publicKeyRaw32)))
        return Ed25519KeyPair(seedRaw32.copyOf(), publicKeyRaw32.copyOf(), privKey, pubKey)
    }

    fun publicKeyFromRaw(publicKeyRaw32: ByteArray): PublicKey {
        require(publicKeyRaw32.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 public key must be 32 bytes (got ${publicKeyRaw32.size})"
        }
        val kf = KeyFactory.getInstance("Ed25519")
        return kf.generatePublic(X509EncodedKeySpec(encodeSpkiDer(publicKeyRaw32)))
    }

    fun encodeSpkiDer(publicKeyRaw32: ByteArray): ByteArray {
        require(publicKeyRaw32.size == EngineConstants.ED25519_KEY_BYTES)
        return SPKI_PREFIX + publicKeyRaw32
    }

    fun decodeSpkiDer(spkiDer: ByteArray): ByteArray {
        require(spkiDer.size == SPKI_PREFIX.size + EngineConstants.ED25519_KEY_BYTES) {
            "Invalid Ed25519 SPKI DER length: ${spkiDer.size}"
        }
        for (i in SPKI_PREFIX.indices) {
            require(spkiDer[i] == SPKI_PREFIX[i]) { "Invalid Ed25519 SPKI ASN.1 header at byte $i" }
        }
        return spkiDer.copyOfRange(SPKI_PREFIX.size, spkiDer.size)
    }

    fun encodePkcs8Der(seedRaw32: ByteArray): ByteArray {
        require(seedRaw32.size == EngineConstants.ED25519_KEY_BYTES)
        return PKCS8_PREFIX + seedRaw32
    }

    fun decodePkcs8Der(pkcs8Der: ByteArray): ByteArray {
        require(pkcs8Der.size == PKCS8_PREFIX.size + EngineConstants.ED25519_KEY_BYTES) {
            "Invalid Ed25519 PKCS#8 DER length: ${pkcs8Der.size}"
        }
        for (i in PKCS8_PREFIX.indices) {
            require(pkcs8Der[i] == PKCS8_PREFIX[i]) { "Invalid Ed25519 PKCS#8 ASN.1 header at byte $i" }
        }
        return pkcs8Der.copyOfRange(PKCS8_PREFIX.size, pkcs8Der.size)
    }

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(message)
        return sig.sign()
    }

    fun verify(publicKey: PublicKey, message: ByteArray, signature64: ByteArray): Boolean {
        if (signature64.size != EngineConstants.ED25519_SIG_BYTES) return false
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(signature64)
        } catch (_: Exception) {
            false
        }
    }

    fun verify(publicKeyRaw32: ByteArray, message: ByteArray, signature64: ByteArray): Boolean {
        if (publicKeyRaw32.size != EngineConstants.ED25519_KEY_BYTES) return false
        if (signature64.size != EngineConstants.ED25519_SIG_BYTES) return false
        return try {
            val pubKey = publicKeyFromRaw(publicKeyRaw32)
            verify(pubKey, message, signature64)
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // RFC 8032 §5.1.5 deterministic public-key derivation from 32-byte seed
    // -------------------------------------------------------------------------

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val TWO_D: BigInteger = TWO.multiply(D).mod(P)
    private val BY: BigInteger = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P))
        .mod(P)
    private val BX: BigInteger = BigInteger(
        "15112221349535400772501151409588531511454012693041857206046113283949847762202",
    )

    private data class ExtPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    /**
     * Derives the 32-byte compressed Edwards25519 public key from a 32-byte
     * secret seed per RFC 8032 §5.1.5 (`SHA-512` clamping + base point multiply).
     */
    fun derivePublicKeyFromSeed(seedRaw32: ByteArray): ByteArray {
        require(seedRaw32.size == EngineConstants.ED25519_KEY_BYTES) {
            "Ed25519 seed must be 32 bytes"
        }
        val h = MessageDigest.getInstance("SHA-512").digest(seedRaw32)
        val clamped = h.copyOfRange(0, 32)
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = ((clamped[31].toInt() and 127) or 64).toByte()

        // Convert little-endian clamped[0..31] into positive BigInteger scalar s
        val bigEndianScalar = ByteArray(33)
        for (i in 0 until 32) {
            bigEndianScalar[32 - i] = clamped[i]
        }
        var s = BigInteger(bigEndianScalar)

        var q = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var b = ExtPoint(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))
        while (s.signum() > 0) {
            if (s.testBit(0)) {
                q = pointAdd(q, b)
            }
            b = pointAdd(b, b)
            s = s.shiftRight(1)
        }

        val zInv = q.z.modInverse(P)
        val x = q.x.multiply(zInv).mod(P)
        val y = q.y.multiply(zInv).mod(P)

        val yBigEndian = y.toByteArray()
        val out = ByteArray(32)
        // Copy y into little-endian 32 bytes
        for (i in yBigEndian.indices) {
            val srcByte = yBigEndian[yBigEndian.size - 1 - i]
            if (i < 32) {
                out[i] = srcByte
            }
        }
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    private fun pointAdd(p1: ExtPoint, p2: ExtPoint): ExtPoint {
        val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
        val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
        val c = p1.t.multiply(TWO_D).mod(P).multiply(p2.t).mod(P)
        val dd = p1.z.multiply(TWO).multiply(p2.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = dd.subtract(c).mod(P)
        val g = dd.add(c).mod(P)
        val h = b.add(a).mod(P)
        return ExtPoint(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }
}
