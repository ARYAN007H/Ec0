/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ephemeral X25519 (RFC 7748) key agreement and the normative 4-message mutual
 * authentication & bidirectional key-confirmation handshake (docs/engine/design.md
 * §2.2, §3.2–§3.5, §8.2–§8.3, Amendments 1 & 3).
 *
 * Transcript flow:
 *   1. CLIENT_HELLO (WS TEXT, I -> R)
 *   2. SERVER_HELLO (WS TEXT, R -> I)
 *   3. CLIENT_AUTH  (WS BINARY, AEAD frameType=0x01 counter=0 under K_I2R, I -> R)
 *   4. SERVER_AUTH_CONFIRM (WS BINARY, AEAD frameType=0x01 counter=0 under K_R2I, R -> I)
 */
package ec0.core.engine.crypto

import ec0.core.engine.CanonicalJson
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.decodeBase64Url
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toBase64UrlUnpadded
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.EngineIdentity
import ec0.core.engine.HkdfSha256
import ec0.core.engine.SessionKeyMaterial
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class HandshakeSecurityException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

class X25519KeyPair internal constructor(
    private val privateScalarBytes: ByteArray,
    private val publicKeyBytes: ByteArray,
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    val privateScalarRaw: ByteArray get() = privateScalarBytes.copyOf()
    val publicKeyRaw: ByteArray get() = publicKeyBytes.copyOf()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientHelloMessage(
    @SerialName("type") val type: String = TYPE_CLIENT_HELLO,
    @SerialName("ver") val version: Int = EngineConstants.PROTOCOL_VERSION,
    @SerialName("id") val deviceId: String,
    @SerialName("ik_pub") val ikPubB64u: String,
    @SerialName("eph_pub") val ephPubB64u: String,
    @SerialName("nonce_i") val nonceIHex: String,
    @SerialName("alias") val alias: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("pairing_proof") val pairingProofHex: String? = null,
) {
    companion object {
        const val TYPE_CLIENT_HELLO: String = "client_hello"
    }
}

@Serializable
data class ServerHelloMessage(
    @SerialName("type") val type: String = TYPE_SERVER_HELLO,
    @SerialName("ver") val version: Int = EngineConstants.PROTOCOL_VERSION,
    @SerialName("id") val deviceId: String,
    @SerialName("rk_pub") val rkPubB64u: String,
    @SerialName("eph_pub") val ephPubB64u: String,
    @SerialName("nonce_r") val nonceRHex: String,
    @SerialName("alias") val alias: String,
    @SerialName("sig") val sigB64u: String,
) {
    companion object {
        const val TYPE_SERVER_HELLO: String = "server_hello"
    }
}

@Serializable
data class ClientAuthMessage(
    @SerialName("session_id") val sessionIdHex: String,
    @SerialName("sig") val sigB64u: String,
    @SerialName("type") val type: String = TYPE_CLIENT_AUTH,
) {
    companion object {
        const val TYPE_CLIENT_AUTH: String = "client_auth"
    }
}

@Serializable
data class ServerAuthConfirmMessage(
    @SerialName("session_id") val sessionIdHex: String,
    @SerialName("status") val status: String = STATUS_OK,
    @SerialName("type") val type: String = TYPE_SERVER_AUTH_CONFIRM,
) {
    companion object {
        const val TYPE_SERVER_AUTH_CONFIRM: String = "server_auth_confirm"
        const val STATUS_OK: String = "ok"
    }
}

data class EstablishedHandshakeSession(
    val peerDeviceId: String,
    val peerPublicKeyRaw: ByteArray,
    val peerAlias: String,
    val sessionKeys: SessionKeyMaterial,
    val aeadChannel: AeadSessionChannel,
) {
    val sessionIdHex: String get() = sessionKeys.sessionId.toHex()
}

object X25519Handshake {

    const val TRANSCRIPT_BODY_BYTES: Int = 160
    const val TRANSCRIPT_PREIMAGE_BYTES: Int = 174

    /**
     * ASN.1 DER prefix for X25519 SubjectPublicKeyInfo (OID 1.3.101.110, 12 bytes).
     * Total SPKI DER = 12B prefix + 32B raw u-coordinate = 44 bytes.
     */
    val SPKI_PREFIX: ByteArray = "302a300506032b656e032100".decodeHex()

    /**
     * ASN.1 DER prefix for X25519 PKCS#8 PrivateKeyInfo (OID 1.3.101.110, 16 bytes).
     * Total PKCS#8 DER = 16B prefix + 32B raw private scalar = 48 bytes.
     */
    val PKCS8_PREFIX: ByteArray = "302e020100300506032b656e04220420".decodeHex()

    /** RFC 7748 §4.1 Curve25519 base point `u = 9` (32 bytes little-endian). */
    private val BASE_POINT_U9: ByteArray = ByteArray(32).apply { this[0] = 9 }

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val secureRandom = SecureRandom()

    fun generateEphemeralKeyPair(): X25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val pkcs8 = kp.private.encoded
        val spki = kp.public.encoded
        val privRaw = pkcs8.copyOfRange(PKCS8_PREFIX.size, PKCS8_PREFIX.size + EngineConstants.X25519_KEY_BYTES)
        val pubRaw = spki.copyOfRange(SPKI_PREFIX.size, SPKI_PREFIX.size + EngineConstants.X25519_KEY_BYTES)
        return X25519KeyPair(privRaw, pubRaw, kp.private, kp.public)
    }

    /**
     * Reconstructs an [X25519KeyPair] from a raw 32-byte RFC 7748 private scalar,
     * deriving the 32-byte public u-coordinate via scalar multiplication against `u = 9`.
     */
    fun fromPrivateScalar(scalarRaw32: ByteArray): X25519KeyPair {
        require(scalarRaw32.size == EngineConstants.X25519_KEY_BYTES) {
            "X25519 private scalar must be 32 bytes (got ${scalarRaw32.size})"
        }
        val kf = KeyFactory.getInstance("X25519")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + scalarRaw32))
        val pubRaw32 = computeSharedSecret(privKey, BASE_POINT_U9)
        val pubKey = kf.generatePublic(X509EncodedKeySpec(SPKI_PREFIX + pubRaw32))
        return X25519KeyPair(scalarRaw32.copyOf(), pubRaw32, privKey, pubKey)
    }

    fun publicKeyFromRaw(publicKeyRaw32: ByteArray): PublicKey {
        require(publicKeyRaw32.size == EngineConstants.X25519_KEY_BYTES) {
            "X25519 public key must be 32 bytes (got ${publicKeyRaw32.size})"
        }
        val kf = KeyFactory.getInstance("X25519")
        return kf.generatePublic(X509EncodedKeySpec(SPKI_PREFIX + publicKeyRaw32))
    }

    /**
     * Performs RFC 7748 X25519 scalar multiplication (`Z = X25519(esk, epk)`).
     * Rejects low-order points / all-zero shared secrets with [HandshakeSecurityException].
     */
    fun computeSharedSecret(privateKey: PrivateKey, peerPublicRaw32: ByteArray): ByteArray {
        if (peerPublicRaw32.size != EngineConstants.X25519_KEY_BYTES) {
            throw HandshakeSecurityException("X25519 peer public key must be 32 bytes")
        }
        val secret = try {
            val peerPub = publicKeyFromRaw(peerPublicRaw32)
            val ka = KeyAgreement.getInstance("X25519")
            ka.init(privateKey)
            ka.doPhase(peerPub, true)
            ka.generateSecret()
        } catch (e: InvalidKeyException) {
            throw HandshakeSecurityException("X25519 rejected low-order or invalid peer public key", e)
        } catch (e: GeneralSecurityException) {
            throw HandshakeSecurityException("X25519 key agreement failed", e)
        }
        if (secret.size != EngineConstants.X25519_KEY_BYTES || secret.all { it == 0.toByte() }) {
            throw HandshakeSecurityException("All-zero X25519 shared secret rejected")
        }
        return secret
    }

    fun computeSharedSecret(privateScalarRaw32: ByteArray, peerPublicRaw32: ByteArray): ByteArray {
        val kp = fromPrivateScalar(privateScalarRaw32)
        return computeSharedSecret(kp.privateKey, peerPublicRaw32)
    }

    fun randomNonce16(): ByteArray = ByteArray(EngineConstants.HANDSHAKE_NONCE_BYTES).also {
        secureRandom.nextBytes(it)
    }

    /**
     * Constructs the 160-byte role-symmetric transcript body (`docs/engine/design.md` §3.3):
     * `ik_pub(32B) || rk_pub(32B) || epk_I(32B) || epk_R(32B) || nonce_I(16B) || nonce_R(16B)`
     */
    fun transcriptBody(
        ikPubRaw32: ByteArray,
        rkPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
        epkRRaw32: ByteArray,
        nonceI16: ByteArray,
        nonceR16: ByteArray,
    ): ByteArray {
        require(ikPubRaw32.size == 32 && rkPubRaw32.size == 32) { "Ed25519 public keys must be 32 bytes" }
        require(epkIRaw32.size == 32 && epkRRaw32.size == 32) { "X25519 public keys must be 32 bytes" }
        require(nonceI16.size == 16 && nonceR16.size == 16) { "Handshake nonces must be 16 bytes" }

        val out = ByteArray(TRANSCRIPT_BODY_BYTES)
        var offset = 0
        ikPubRaw32.copyInto(out, offset); offset += 32
        rkPubRaw32.copyInto(out, offset); offset += 32
        epkIRaw32.copyInto(out, offset); offset += 32
        epkRRaw32.copyInto(out, offset); offset += 32
        nonceI16.copyInto(out, offset); offset += 16
        nonceR16.copyInto(out, offset)
        return out
    }

    /** 174-byte Responder signature preimage: `"ec0-hs-resp-v1" (14B) || transcriptBody (160B)`. */
    fun responderPreimage(
        ikPubRaw32: ByteArray,
        rkPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
        epkRRaw32: ByteArray,
        nonceI16: ByteArray,
        nonceR16: ByteArray,
    ): ByteArray =
        EngineConstants.HS_RESP_TAG.encodeToByteArray() +
            transcriptBody(ikPubRaw32, rkPubRaw32, epkIRaw32, epkRRaw32, nonceI16, nonceR16)

    /** 174-byte Initiator signature preimage: `"ec0-hs-init-v1" (14B) || transcriptBody (160B)`. */
    fun initiatorPreimage(
        ikPubRaw32: ByteArray,
        rkPubRaw32: ByteArray,
        epkIRaw32: ByteArray,
        epkRRaw32: ByteArray,
        nonceI16: ByteArray,
        nonceR16: ByteArray,
    ): ByteArray =
        EngineConstants.HS_INIT_TAG.encodeToByteArray() +
            transcriptBody(ikPubRaw32, rkPubRaw32, epkIRaw32, epkRRaw32, nonceI16, nonceR16)

    // -------------------------------------------------------------------------
    // 4-Message Handshake State Machine (Initiator & Responder)
    // -------------------------------------------------------------------------

    class InitiatorSession(
        private val identity: Ed25519KeyPair,
        private val alias: String,
        private val ephemeral: X25519KeyPair = generateEphemeralKeyPair(),
        private val nonceI: ByteArray = randomNonce16(),
        private val qrPairingTarget: Pair<ByteArray, ByteArray>? = null, // (nonceQr16, expectedRkPub32)
    ) {
        private var pendingSession: EstablishedHandshakeSession? = null

        /** Step 1: Build `CLIENT_HELLO` JSON frame (WS TEXT). */
        fun createClientHelloJson(): String {
            val proofHex = qrPairingTarget?.let { (nonceQr16, rkPub32) ->
                EngineIdentity.computePairingProof(
                    nonceQr16 = nonceQr16,
                    ikPubRaw32 = identity.publicKeyRaw,
                    rkPubRaw32 = rkPub32,
                    epkIRaw32 = ephemeral.publicKeyRaw,
                )
            }
            val msg = ClientHelloMessage(
                deviceId = identity.deviceId,
                ikPubB64u = identity.publicKeyRaw.toBase64UrlUnpadded(),
                ephPubB64u = ephemeral.publicKeyRaw.toBase64UrlUnpadded(),
                nonceIHex = nonceI.toHex(),
                alias = alias,
                pairingProofHex = proofHex,
            )
            return CanonicalJson.encode(json.encodeToJsonElement(ClientHelloMessage.serializer(), msg))
        }

        /**
         * Step 3: Process Responder's `SERVER_HELLO` JSON (WS TEXT), verify peer identity +
         * `respSig`, derive session keys, and return the AEAD-encrypted `CLIENT_AUTH` binary
         * frame (`frameType = 0x01`, `counter = 0` under `K_I2R`).
         */
        fun processServerHelloAndCreateClientAuth(
            serverHelloJson: String,
            peerPinVerifier: (deviceId: String, rkPubRaw32: ByteArray, alias: String) -> Unit,
        ): ByteArray {
            val serverHello = try {
                json.decodeFromString(ServerHelloMessage.serializer(), serverHelloJson)
            } catch (e: Exception) {
                throw HandshakeSecurityException("Malformed SERVER_HELLO JSON", e)
            }
            if (serverHello.type != ServerHelloMessage.TYPE_SERVER_HELLO ||
                serverHello.version != EngineConstants.PROTOCOL_VERSION
            ) {
                throw HandshakeSecurityException("Unsupported SERVER_HELLO type/version")
            }

            val rkPubRaw = decodeKeyB64u("rk_pub", serverHello.rkPubB64u, 32)
            val epkRRaw = decodeKeyB64u("eph_pub", serverHello.ephPubB64u, 32)
            val nonceR = decodeHexField("nonce_r", serverHello.nonceRHex, 16)
            val respSig = decodeKeyB64u("sig", serverHello.sigB64u, 64)

            if (!EngineIdentity.verifyDeviceId(serverHello.deviceId, rkPubRaw)) {
                throw HandshakeSecurityException("SERVER_HELLO deviceId does not match SHA-256(rk_pub)")
            }

            // Check TOFU PinStore / QR expected peer public key
            qrPairingTarget?.let { (_, expectedRkPub) ->
                if (!EngineEncoding.constantTimeEquals(expectedRkPub, rkPubRaw)) {
                    throw HandshakeSecurityException("SERVER_HELLO rk_pub does not match scanned QR public key")
                }
            }
            peerPinVerifier(serverHello.deviceId, rkPubRaw, serverHello.alias)

            val respPre = responderPreimage(
                ikPubRaw32 = identity.publicKeyRaw,
                rkPubRaw32 = rkPubRaw,
                epkIRaw32 = ephemeral.publicKeyRaw,
                epkRRaw32 = epkRRaw,
                nonceI16 = nonceI,
                nonceR16 = nonceR,
            )
            if (!Ed25519Identity.verify(rkPubRaw, respPre, respSig)) {
                throw HandshakeSecurityException("SERVER_HELLO Ed25519 signature verification failed")
            }

            val z = computeSharedSecret(ephemeral.privateKey, epkRRaw)
            val keys = HkdfSha256.deriveSessionKeys(
                sharedSecretZ = z,
                nonceI = nonceI,
                nonceR = nonceR,
                ikPubRaw = identity.publicKeyRaw,
                rkPubRaw = rkPubRaw,
                epkIRaw = ephemeral.publicKeyRaw,
                epkRRaw = epkRRaw,
            )

            val initPre = initiatorPreimage(
                ikPubRaw32 = identity.publicKeyRaw,
                rkPubRaw32 = rkPubRaw,
                epkIRaw32 = ephemeral.publicKeyRaw,
                epkRRaw32 = epkRRaw,
                nonceI16 = nonceI,
                nonceR16 = nonceR,
            )
            val initSig = identity.sign(initPre)

            val clientAuth = ClientAuthMessage(
                sessionIdHex = keys.sessionId.toHex(),
                sigB64u = initSig.toBase64UrlUnpadded(),
            )
            val clientAuthBytes = CanonicalJson.encode(
                json.encodeToJsonElement(ClientAuthMessage.serializer(), clientAuth),
            ).encodeToByteArray()

            val channel = AeadSessionChannel(keys, isInitiator = true)
            val sealedAuthFrame = channel.sealAuthConfirm(clientAuthBytes)

            pendingSession = EstablishedHandshakeSession(
                peerDeviceId = serverHello.deviceId,
                peerPublicKeyRaw = rkPubRaw,
                peerAlias = serverHello.alias,
                sessionKeys = keys,
                aeadChannel = channel,
            )
            return sealedAuthFrame
        }

        /**
         * Step 4: Decrypt and validate Responder's `SERVER_AUTH_CONFIRM` AEAD binary frame
         * (`frameType = 0x01`, `counter = 0` under `K_R2I`), completing mutual key confirmation.
         */
        fun verifyServerAuthConfirm(aeadPacket: ByteArray): EstablishedHandshakeSession {
            val session = pendingSession
                ?: throw HandshakeSecurityException("verifyServerAuthConfirm called before SERVER_HELLO")
            val plaintext = session.aeadChannel.openAuthConfirm(aeadPacket)
            val confirm = try {
                json.decodeFromString(ServerAuthConfirmMessage.serializer(), plaintext.decodeToString())
            } catch (e: Exception) {
                throw HandshakeSecurityException("Malformed SERVER_AUTH_CONFIRM payload", e)
            }
            if (confirm.type != ServerAuthConfirmMessage.TYPE_SERVER_AUTH_CONFIRM ||
                confirm.status != ServerAuthConfirmMessage.STATUS_OK
            ) {
                throw HandshakeSecurityException("Invalid SERVER_AUTH_CONFIRM status: ${confirm.status}")
            }
            if (!EngineEncoding.constantTimeEquals(
                    confirm.sessionIdHex.encodeToByteArray(),
                    session.sessionIdHex.encodeToByteArray(),
                )
            ) {
                throw HandshakeSecurityException("SERVER_AUTH_CONFIRM session_id mismatch")
            }
            return session
        }
    }

    class ResponderSession(
        private val identity: Ed25519KeyPair,
        private val alias: String,
        private val ephemeral: X25519KeyPair = generateEphemeralKeyPair(),
        private val nonceR: ByteArray = randomNonce16(),
    ) {
        private var clientIkPubRaw: ByteArray? = null
        private var clientEpkIRaw: ByteArray? = null
        private var clientNonceI: ByteArray? = null
        private var pendingSession: EstablishedHandshakeSession? = null

        /**
         * Step 2: Process Initiator's `CLIENT_HELLO` JSON (WS TEXT), authorize via [peerAuthorizer],
         * derive session keys, and return `SERVER_HELLO` JSON (WS TEXT).
         */
        fun processClientHelloAndCreateServerHello(
            clientHelloJson: String,
            peerAuthorizer: (
                deviceId: String,
                ikPubRaw32: ByteArray,
                epkIRaw32: ByteArray,
                alias: String,
                pairingProofHex: String?,
            ) -> Unit,
        ): String {
            val clientHello = try {
                json.decodeFromString(ClientHelloMessage.serializer(), clientHelloJson)
            } catch (e: Exception) {
                throw HandshakeSecurityException("Malformed CLIENT_HELLO JSON", e)
            }
            if (clientHello.type != ClientHelloMessage.TYPE_CLIENT_HELLO ||
                clientHello.version != EngineConstants.PROTOCOL_VERSION
            ) {
                throw HandshakeSecurityException("Unsupported CLIENT_HELLO type/version")
            }

            val ikPubRaw = decodeKeyB64u("ik_pub", clientHello.ikPubB64u, 32)
            val epkIRaw = decodeKeyB64u("eph_pub", clientHello.ephPubB64u, 32)
            val nonceI = decodeHexField("nonce_i", clientHello.nonceIHex, 16)

            if (!EngineIdentity.verifyDeviceId(clientHello.deviceId, ikPubRaw)) {
                throw HandshakeSecurityException("CLIENT_HELLO deviceId does not match SHA-256(ik_pub)")
            }

            peerAuthorizer(
                clientHello.deviceId,
                ikPubRaw,
                epkIRaw,
                clientHello.alias,
                clientHello.pairingProofHex,
            )

            val z = computeSharedSecret(ephemeral.privateKey, epkIRaw)
            val keys = HkdfSha256.deriveSessionKeys(
                sharedSecretZ = z,
                nonceI = nonceI,
                nonceR = nonceR,
                ikPubRaw = ikPubRaw,
                rkPubRaw = identity.publicKeyRaw,
                epkIRaw = epkIRaw,
                epkRRaw = ephemeral.publicKeyRaw,
            )

            val respPre = responderPreimage(
                ikPubRaw32 = ikPubRaw,
                rkPubRaw32 = identity.publicKeyRaw,
                epkIRaw32 = epkIRaw,
                epkRRaw32 = ephemeral.publicKeyRaw,
                nonceI16 = nonceI,
                nonceR16 = nonceR,
            )
            val respSig = identity.sign(respPre)

            clientIkPubRaw = ikPubRaw
            clientEpkIRaw = epkIRaw
            clientNonceI = nonceI
            pendingSession = EstablishedHandshakeSession(
                peerDeviceId = clientHello.deviceId,
                peerPublicKeyRaw = ikPubRaw,
                peerAlias = clientHello.alias,
                sessionKeys = keys,
                aeadChannel = AeadSessionChannel(keys, isInitiator = false),
            )

            val serverHello = ServerHelloMessage(
                deviceId = identity.deviceId,
                rkPubB64u = identity.publicKeyRaw.toBase64UrlUnpadded(),
                ephPubB64u = ephemeral.publicKeyRaw.toBase64UrlUnpadded(),
                nonceRHex = nonceR.toHex(),
                alias = alias,
                sigB64u = respSig.toBase64UrlUnpadded(),
            )
            return CanonicalJson.encode(json.encodeToJsonElement(ServerHelloMessage.serializer(), serverHello))
        }

        /**
         * Step 3 & 4: Decrypt Initiator's `CLIENT_AUTH` AEAD frame (`counter = 0` under `K_I2R`),
         * verify `session_id` and `initSig` over the 174-byte `initPreimage`, and return both
         * the `SERVER_AUTH_CONFIRM` AEAD binary frame (`counter = 0` under `K_R2I`) and the
         * [EstablishedHandshakeSession].
         */
        fun verifyClientAuthAndCreateServerConfirm(
            aeadPacket: ByteArray,
        ): Pair<ByteArray, EstablishedHandshakeSession> {
            val session = pendingSession
                ?: throw HandshakeSecurityException("verifyClientAuth called before CLIENT_HELLO")
            val ikPubRaw = clientIkPubRaw!!
            val epkIRaw = clientEpkIRaw!!
            val nonceI = clientNonceI!!

            val plaintext = session.aeadChannel.openAuthConfirm(aeadPacket)
            val clientAuth = try {
                json.decodeFromString(ClientAuthMessage.serializer(), plaintext.decodeToString())
            } catch (e: Exception) {
                throw HandshakeSecurityException("Malformed CLIENT_AUTH JSON", e)
            }
            if (clientAuth.type != ClientAuthMessage.TYPE_CLIENT_AUTH) {
                throw HandshakeSecurityException("Unexpected CLIENT_AUTH type: ${clientAuth.type}")
            }
            if (!EngineEncoding.constantTimeEquals(
                    clientAuth.sessionIdHex.encodeToByteArray(),
                    session.sessionIdHex.encodeToByteArray(),
                )
            ) {
                throw HandshakeSecurityException("CLIENT_AUTH session_id mismatch")
            }

            val initSig = decodeKeyB64u("sig", clientAuth.sigB64u, 64)
            val initPre = initiatorPreimage(
                ikPubRaw32 = ikPubRaw,
                rkPubRaw32 = identity.publicKeyRaw,
                epkIRaw32 = epkIRaw,
                epkRRaw32 = ephemeral.publicKeyRaw,
                nonceI16 = nonceI,
                nonceR16 = nonceR,
            )
            if (!Ed25519Identity.verify(ikPubRaw, initPre, initSig)) {
                throw HandshakeSecurityException("CLIENT_AUTH Ed25519 signature verification failed")
            }

            val confirmMsg = ServerAuthConfirmMessage(sessionIdHex = session.sessionIdHex)
            val confirmBytes = CanonicalJson.encode(
                json.encodeToJsonElement(ServerAuthConfirmMessage.serializer(), confirmMsg),
            ).encodeToByteArray()
            val confirmPacket = session.aeadChannel.sealAuthConfirm(confirmBytes)
            return confirmPacket to session
        }
    }

    private fun decodeKeyB64u(field: String, b64u: String, expectedBytes: Int): ByteArray {
        val bytes = try {
            b64u.decodeBase64Url()
        } catch (e: IllegalArgumentException) {
            throw HandshakeSecurityException("Invalid Base64url in '$field'", e)
        }
        if (bytes.size != expectedBytes) {
            throw HandshakeSecurityException("Field '$field' expected $expectedBytes bytes, got ${bytes.size}")
        }
        return bytes
    }

    private fun decodeHexField(field: String, hex: String, expectedBytes: Int): ByteArray {
        val bytes = try {
            hex.decodeHex()
        } catch (e: IllegalArgumentException) {
            throw HandshakeSecurityException("Invalid hex in '$field'", e)
        }
        if (bytes.size != expectedBytes) {
            throw HandshakeSecurityException("Field '$field' expected $expectedBytes bytes, got ${bytes.size}")
        }
        return bytes
    }
}
