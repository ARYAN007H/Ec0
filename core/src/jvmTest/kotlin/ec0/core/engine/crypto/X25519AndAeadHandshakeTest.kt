/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned RFC 7748 §6.1 X25519 vectors, RFC 8439 §2.8.2 ChaCha20-Poly1305 vectors,
 * Golden Vector 1 4-message mutual handshake (CLIENT_HELLO -> SERVER_HELLO ->
 * AEAD CLIENT_AUTH -> AEAD SERVER_AUTH_CONFIRM), counter=0 vs counter>=1 rules,
 * and PairingCoordinator / TOFU PinStore security invariants.
 */
package ec0.core.engine.crypto

import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.kdeconnect.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class X25519AndAeadHandshakeTest {

    @Test
    fun rfc7748Section61X25519Vector() {
        val aliceScalar = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".decodeHex()
        val alicePubExpected = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".decodeHex()
        val bobScalar = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".decodeHex()
        val bobPubExpected = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".decodeHex()
        val sharedExpected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".decodeHex()

        val alice = X25519Handshake.fromPrivateScalar(aliceScalar)
        val bob = X25519Handshake.fromPrivateScalar(bobScalar)
        assertTrue(alice.publicKeyRaw.contentEquals(alicePubExpected))
        assertTrue(bob.publicKeyRaw.contentEquals(bobPubExpected))

        val zAlice = X25519Handshake.computeSharedSecret(alice.privateKey, bob.publicKeyRaw)
        val zBob = X25519Handshake.computeSharedSecret(bob.privateKey, alice.publicKeyRaw)
        assertTrue(zAlice.contentEquals(sharedExpected))
        assertTrue(zBob.contentEquals(sharedExpected))
    }

    @Test
    fun rfc8439Section282ChaCha20Poly1305Vector() {
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".decodeHex()
        val nonce = "070000004041424344454647".decodeHex()
        val aad = "50515253c0c1c2c3c4c5c6c7".decodeHex()
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: " +
                "If I could offer you only one tip for the future, sunscreen would be it."
            ).encodeToByteArray()

        val expectedCtAndTag = (
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
            ).decodeHex()

        val sealed = AeadFrameCodec.encryptRaw(key, nonce, aad, plaintext)
        assertTrue(sealed.contentEquals(expectedCtAndTag))

        val opened = AeadFrameCodec.decryptRaw(key, nonce, aad, sealed)
        assertTrue(opened.contentEquals(plaintext))

        // Flip 1 bit in AAD -> must throw AeadSecurityException
        val tamperedAad = aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<AeadSecurityException> {
            AeadFrameCodec.decryptRaw(key, nonce, tamperedAad, sealed)
        }
        assertTrue(true)
    }

    @Test
    fun goldenVector1FullFourMessageMutualHandshakeMatchesSpec() {
        val ikSeed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".decodeHex()
        val rkSeed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".decodeHex()
        val ephIScalar = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".decodeHex()
        val ephRScalar = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".decodeHex()
        val nonceI = "0102030405060708090a0b0c0d0e0f10".decodeHex()
        val nonceR = "1112131415161718191a1b1c1d1e1f20".decodeHex()
        val qrNonce = "aabbccddeeff00112233445566778899".decodeHex()

        val ik = Ed25519Identity.fromPrivateSeed(ikSeed)
        val rk = Ed25519Identity.fromPrivateSeed(rkSeed)
        val ephI = X25519Handshake.fromPrivateScalar(ephIScalar)
        val ephR = X25519Handshake.fromPrivateScalar(ephRScalar)

        // Verify 174-byte preimages & signatures match docs/engine/design.md §8.3
        val respPre = X25519Handshake.responderPreimage(
            ik.publicKeyRaw, rk.publicKeyRaw, ephI.publicKeyRaw, ephR.publicKeyRaw, nonceI, nonceR,
        )
        assertEquals(174, respPre.size)
        assertEquals(
            "2ad3499d109a804cf60d653f09515d52cd8e38e7cf2653ddb758448256a8893e",
            Sha256.digest(respPre).toHex(),
        )
        assertEquals(
            "1065cf7cd7b6905a4b461175432ebfd4b2a8a53e1e8c018b049a894ef007b428" +
                "183cc1900900ce9f470251a9e363cd5acdcb327dfd67449d192cddb1cf866609",
            rk.sign(respPre).toHex(),
        )

        val initPre = X25519Handshake.initiatorPreimage(
            ik.publicKeyRaw, rk.publicKeyRaw, ephI.publicKeyRaw, ephR.publicKeyRaw, nonceI, nonceR,
        )
        assertEquals(174, initPre.size)
        assertEquals(
            "014ba89f9f009fc6a39bfa5381927e71a5dcb172caeb922c944988024e10d5a0",
            Sha256.digest(initPre).toHex(),
        )
        assertEquals(
            "0022b4c1f95eedc9fc092d80624cd5466ce904ff6641357d116865777a3acf85" +
                "8b0f590dc9f45df7e1ec9ae2cc5e76b211267c92cbedd734fff7a3ba3f0d010a",
            ik.sign(initPre).toHex(),
        )

        // Drive the 4-message handshake state machine end-to-end with PairingCoordinator
        val initiatorCoordinator = PairingCoordinator(ik, InMemoryPinStore(allowAutoTofuOnUnknownPeer = false))
        val responderCoordinator = PairingCoordinator(rk, InMemoryPinStore(allowAutoTofuOnUnknownPeer = false))

        val now = 1_763_000_000_000L
        val qrPayload = responderCoordinator.generateQrPayload(
            alias = "OPPO-A5x",
            port = 49880,
            nonceOverride = qrNonce,
            nowMs = now,
        )
        val scanned = initiatorCoordinator.acceptScannedQrUri(qrPayload.toUri(), nowMs = now)

        val initiatorHs = X25519Handshake.InitiatorSession(
            identity = ik,
            alias = "arch-linux",
            ephemeral = ephI,
            nonceI = nonceI,
            qrPairingTarget = scanned.nonce to scanned.publicKeyRaw,
        )
        val responderHs = X25519Handshake.ResponderSession(
            identity = rk,
            alias = "OPPO-A5x",
            ephemeral = ephR,
            nonceR = nonceR,
        )

        // Msg 1: CLIENT_HELLO
        val clientHelloJson = initiatorHs.createClientHelloJson()

        // Msg 2: SERVER_HELLO
        val serverHelloJson = responderHs.processClientHelloAndCreateServerHello(
            clientHelloJson = clientHelloJson,
            peerAuthorizer = { devId, ikPub, epkIBytes, alias, proofHex ->
                responderCoordinator.authorizeIncomingClientHello(
                    clientDeviceId = devId,
                    ikPubRaw32 = ikPub,
                    epkIRaw32 = epkIBytes,
                    clientAlias = alias,
                    pairingProofHex = proofHex,
                    nowMs = now,
                )
            },
        )

        // Msg 3: CLIENT_AUTH (AEAD counter=0 under K_I2R)
        val clientAuthPacket = initiatorHs.processServerHelloAndCreateClientAuth(
            serverHelloJson = serverHelloJson,
            peerPinVerifier = { devId, rkPub, alias ->
                initiatorCoordinator.pinStore.verifyOrPin(devId, rkPub, alias, TrustLevel.TRUSTED_QR, now)
            },
        )
        assertEquals(
            "0100000000000000001094070bf244b177a9d27aed0245fc36fd63fb02c73b5c" +
                "34b5afd642fa6a253762549fb8bb4508263f08eaa908aed0c6e4573d732cb696" +
                "31975fede4e9a7bda07847521df718c61f90e13a6f89ef08a31458ac80f0e3c2" +
                "23f4a03908cd85e195f65bb766b49153ad0a3bad7ef0e4bcd3743e1249e3e6f3" +
                "88d08ccc18ec7cd7eee3cc09365a78069a2a5a536e8df4e817dff2628aec8cec" +
                "ffb4f93236774c8b5fd8028be5baf0c00b0d8163ae38e208ac6ca289ea5f938c" +
                "502e22d480c9015d1c42315423ca1474c929fb94bbb99ac48dc4427603cf",
            clientAuthPacket.toHex(),
        )

        // Msg 4: SERVER_AUTH_CONFIRM (AEAD counter=0 under K_R2I)
        val (serverConfirmPacket, responderSession) = responderHs.verifyClientAuthAndCreateServerConfirm(
            clientAuthPacket,
        )
        assertEquals(
            "0100000000000000005c1e245d44c1b83da2b720057da3e0a9b702c8a5f7699e" +
                "72b55d0742864ec6ea7c25197a70e723e04cb80521b08bcd79cccda050ff45df" +
                "ed95ff44c8e869d1321eee2e90abb48532590bcf7846d21da771aa4c5e4be09f" +
                "e69aabe4c01d30fdaa272ae2d42abc90f96719fbce017f3d10e801f9fb1bc0c8" +
                "dacd814706d3ac16766287f73863666ce1ebe5a5b0",
            serverConfirmPacket.toHex(),
        )

        val initiatorSession = initiatorHs.verifyServerAuthConfirm(serverConfirmPacket)
        assertEquals(initiatorSession.sessionIdHex, responderSession.sessionIdHex)
        assertEquals(1L, initiatorSession.aeadChannel.nextSendCounter)
        assertEquals(1L, initiatorSession.aeadChannel.nextRecvCounter)
        assertEquals(1L, responderSession.aeadChannel.nextSendCounter)
        assertEquals(1L, responderSession.aeadChannel.nextRecvCounter)

        // Subsequent application traffic starts at counter=1 in both directions
        val appPayload = """{"hello":"world"}""".encodeToByteArray()
        val appFrame = initiatorSession.aeadChannel.sealApplicationFrame(
            EngineConstants.FRAME_ENVELOPE_JSON,
            appPayload,
        )
        val decrypted = responderSession.aeadChannel.openApplicationFrame(appFrame)
        assertEquals(EngineConstants.FRAME_ENVELOPE_JSON, decrypted.frameType)
        assertEquals(1L, decrypted.counter)
        assertTrue(decrypted.plaintext.contentEquals(appPayload))
    }

    @Test
    fun aeadCounterAndFrameTypeSeparationEnforced() {
        val key = ByteArray(32) { 0x11 }
        val sessionId = ByteArray(32) { 0x22 }

        // FRAME_ENVELOPE_JSON (0x02) prohibited at counter=0
        assertFailsWith<AeadSecurityException> {
            AeadFrameCodec.sealFrame(key, sessionId, EngineConstants.FRAME_ENVELOPE_JSON, 0L, ByteArray(4))
        }
        // FRAME_AUTH_CONFIRM (0x01) prohibited at counter>=1
        assertFailsWith<AeadSecurityException> {
            AeadFrameCodec.sealFrame(key, sessionId, EngineConstants.FRAME_AUTH_CONFIRM, 1L, ByteArray(4))
        }
        assertTrue(true)
    }

    @Test
    fun qrTokenIsSingleUseAndPinStoreRejectsKeySubstitution() {
        val rk = Ed25519Identity.generate()
        val ik = Ed25519Identity.generate()
        val ephI = X25519Handshake.generateEphemeralKeyPair()

        val coordinator = PairingCoordinator(rk, InMemoryPinStore(allowAutoTofuOnUnknownPeer = false))
        val qr = coordinator.generateQrPayload("OPPO-A5x", nowMs = 1_000L)
        val proof = ec0.core.engine.EngineIdentity.computePairingProof(
            nonceQr16 = qr.nonce,
            ikPubRaw32 = ik.publicKeyRaw,
            rkPubRaw32 = rk.publicKeyRaw,
            epkIRaw32 = ephI.publicKeyRaw,
        )

        // First use succeeds and pins ik
        val pinned = coordinator.authorizeIncomingClientHello(
            clientDeviceId = ik.deviceId,
            ikPubRaw32 = ik.publicKeyRaw,
            epkIRaw32 = ephI.publicKeyRaw,
            clientAlias = "arch-linux",
            pairingProofHex = proof,
            nowMs = 2_000L,
        )
        assertEquals(TrustLevel.TRUSTED_QR, pinned.trustLevel)

        // Replaying the consumed QR proof a second time MUST fail
        assertFailsWith<HandshakeSecurityException> {
            coordinator.authorizeIncomingClientHello(
                clientDeviceId = ik.deviceId,
                ikPubRaw32 = ik.publicKeyRaw,
                epkIRaw32 = ephI.publicKeyRaw,
                clientAlias = "arch-linux",
                pairingProofHex = proof,
                nowMs = 3_000L,
            )
        }

        // Presenting a different Ed25519 key for an already-pinned deviceId MUST throw KeyMismatchException
        val imposterKey = Ed25519Identity.generate()
        assertFailsWith<KeyMismatchException> {
            coordinator.pinStore.verifyOrPin(
                deviceId = ik.deviceId,
                publicKeyRaw = imposterKey.publicKeyRaw,
                alias = "imposter",
                trustLevel = TrustLevel.TRUSTED_QR,
            )
        }
        assertTrue(true)
    }
}
