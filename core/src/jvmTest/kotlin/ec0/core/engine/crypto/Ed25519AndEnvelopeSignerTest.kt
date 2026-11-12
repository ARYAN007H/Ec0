/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned RFC 8032 §7.1 Ed25519 vectors, seed -> public-key derivation tests,
 * and Golden Vector 2 (ec0-sig-v1 canonical envelope signature) tests
 * (docs/engine/design.md §2.1, §4.3, §8.1, §8.4).
 */
package ec0.core.engine.crypto

import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.ReplayCheckResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519AndEnvelopeSignerTest {

    @Test
    fun rfc8032Test1AndTest2SeedDerivationAndSignatures() {
        // RFC 8032 §7.1 TEST 1 (empty message)
        val sk1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".decodeHex()
        val pk1Expected = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".decodeHex()
        val sig1Expected = (
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            ).decodeHex()

        val kp1 = Ed25519Identity.fromPrivateSeed(sk1)
        assertTrue(kp1.publicKeyRaw.contentEquals(pk1Expected))
        assertEquals(
            "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9",
            kp1.deviceId,
        )

        val sig1 = kp1.sign(ByteArray(0))
        assertTrue(sig1.contentEquals(sig1Expected))
        assertTrue(Ed25519Identity.verify(pk1Expected, ByteArray(0), sig1))
        assertFalse(Ed25519Identity.verify(pk1Expected, byteArrayOf(0x01), sig1))

        // RFC 8032 §7.1 TEST 2 (message = 0x72)
        val sk2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".decodeHex()
        val pk2Expected = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".decodeHex()
        val sig2Expected = (
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
            ).decodeHex()

        val kp2 = Ed25519Identity.fromPrivateSeed(sk2)
        assertTrue(kp2.publicKeyRaw.contentEquals(pk2Expected))
        val msg2 = "72".decodeHex()
        val sig2 = kp2.sign(msg2)
        assertTrue(sig2.contentEquals(sig2Expected))
        assertTrue(Ed25519Identity.verify(pk2Expected, msg2, sig2))
    }

    @Test
    fun rejectsMismatchedRawKeyPair() {
        val sk1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".decodeHex()
        val wrongPk = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".decodeHex()
        assertFailsWith<IllegalArgumentException> {
            Ed25519Identity.fromRawKeyPair(sk1, wrongPk)
        }
        assertTrue(true)
    }

    @Test
    fun goldenVector2EnvelopeSignatureMatchesSpecByteForByte() {
        val sk1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".decodeHex()
        val kp1 = Ed25519Identity.fromPrivateSeed(sk1)

        // Intentionally construct msg_body with non-alphabetical key order to prove
        // CanonicalJson normalizes the preimage before Ed25519 signing.
        val body = buildJsonObject {
            put("reason", "")
            put("cmd_value", 0)
            put("cmd_type", "clipboard_sync")
            put(
                "cmd_body",
                buildJsonObject {
                    put("size_bytes", 3)
                    put("mime", "text/plain")
                    put(
                        "clip_sha256",
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    )
                },
            )
        }

        val envelope = EnvelopeSigner.signEnvelope(
            identity = kp1,
            msgType = "cmd",
            msgBody = body,
            category = "clipboard",
            srcRole = "ec0-engine",
            timestampMs = 1763000000000L,
            seq = 1L,
            nonce = 4829104728193048291L,
        )

        assertEquals(
            "lHqyai9_6zlrNe4SxT9YXztsSeVwX5JJThFeiD52uNWlTQqGjx5cC56aKyOWqAZD_SGYw9y_vc962X05MqLDBw",
            envelope.sig,
        )
        assertTrue(EnvelopeSigner.verifySignature(envelope, kp1.publicKeyRaw))

        // Wire JSON encode/decode roundtrip preserves signature validity
        val wireJson = Ec0EnvelopeCodec.encode(envelope)
        val decoded = Ec0EnvelopeCodec.decode(wireJson)
        assertTrue(EnvelopeSigner.verifySignature(decoded, kp1.publicKeyRaw))

        // Mutating a single field inside msg_body invalidates the signature
        val tampered = decoded.copy(seq = 2L)
        assertFalse(EnvelopeSigner.verifySignature(tampered, kp1.publicKeyRaw))
    }

    @Test
    fun sessionEnvelopeProcessorRejectsForgedPacketWithoutPoisoningReplayWindow() {
        val alice = Ed25519Identity.generate()
        val bob = Ed25519Identity.generate()
        val attacker = Ed25519Identity.generate()

        val aliceProcessor = SessionEnvelopeProcessor(
            localIdentity = alice,
            peerDeviceId = bob.deviceId,
            peerPublicKeyRaw32 = bob.publicKeyRaw,
        )
        val bobProcessor = SessionEnvelopeProcessor(
            localIdentity = bob,
            peerDeviceId = alice.deviceId,
            peerPublicKeyRaw32 = alice.publicKeyRaw,
        )

        val now = 1_763_000_000_000L
        val validEnv1 = aliceProcessor.nextCmdEnvelope(
            cmdType = "clipboard_sync",
            category = "clipboard",
            timestampMs = now,
        )
        assertEquals(1L, validEnv1.seq)
        assertEquals(ReplayCheckResult.ACCEPTED, bobProcessor.verifyLiveEnvelope(validEnv1, now))

        // Attacker tries to forge seq = 1000 to advance Bob's ReplayWindow.highestSeq
        val forgedEnv = EnvelopeSigner.signEnvelope(
            identity = attacker,
            msgType = "cmd",
            msgBody = Ec0EnvelopeCodec.buildCmdBody("clipboard_sync"),
            category = "clipboard",
            timestampMs = now,
            seq = 1000L,
        )
        assertFailsWith<HandshakeSecurityException> {
            bobProcessor.verifyLiveEnvelope(forgedEnv, now)
        }
        // Bob's ReplayWindow must still be at highestSeq == 1, so Alice's seq = 2 succeeds!
        assertEquals(1L, bobProcessor.replayWindow.highestSeq)
        val validEnv2 = aliceProcessor.nextInfoEnvelope(
            infoType = "presence_beacon",
            category = "presence",
            timestampMs = now + 100L,
        )
        assertEquals(2L, validEnv2.seq)
        assertEquals(ReplayCheckResult.ACCEPTED, bobProcessor.verifyLiveEnvelope(validEnv2, now + 100L))
    }
}
