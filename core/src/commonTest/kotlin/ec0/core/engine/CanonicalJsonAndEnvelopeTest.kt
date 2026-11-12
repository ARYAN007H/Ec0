/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Byte-exact tests for CanonicalJson determinism (key ordering, null omission,
 * integer-only number formatting, float rejection, string escaping), Golden Vector 2
 * (256-byte ec0-sig-v1 preimage), ReplayWindow, OfflineFlushDeduplicator, and
 * PairingQrPayload / ephemeral-bound pairing_proof.
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.kdeconnect.Sha256
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalJsonAndEnvelopeTest {

    @Test
    fun nullTopLevelBodyEncodesAsEmptyBraces() {
        assertEquals("{}", CanonicalJson.encode(null))
        assertEquals("{}", CanonicalJson.encode(JsonNull))
    }

    @Test
    fun recursiveLexicographicalKeySortIsInsertionOrderIndependent() {
        val treeA = buildJsonObject {
            put("reason", "")
            put("cmd_value", 0)
            put("cmd_type", "clipboard_sync")
            put("null_field", JsonNull)
            put(
                "cmd_body",
                buildJsonObject {
                    put("size_bytes", 3)
                    put("mime", "text/plain")
                    put("ignored_null", JsonNull)
                    put(
                        "clip_sha256",
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    )
                },
            )
        }
        val treeB = buildJsonObject {
            put(
                "cmd_body",
                buildJsonObject {
                    put(
                        "clip_sha256",
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    )
                    put("mime", "text/plain")
                    put("size_bytes", 3)
                },
            )
            put("cmd_type", "clipboard_sync")
            put("cmd_value", 0)
            put("reason", "")
        }

        val expected = """{"cmd_body":{"clip_sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","mime":"text/plain","size_bytes":3},"cmd_type":"clipboard_sync","cmd_value":0,"reason":""}"""
        assertEquals(expected, CanonicalJson.encode(treeA))
        assertEquals(expected, CanonicalJson.encode(treeB))
    }

    @Test
    fun utf16CodeUnitKeyOrderingAndArrayNullPreservation() {
        val obj = JsonObject(
            mapOf(
                "b" to JsonPrimitive(2),
                "B" to JsonPrimitive(1),
                "a10" to JsonPrimitive(4),
                "a2" to JsonPrimitive(3),
                "arr" to buildJsonArray {
                    add(JsonPrimitive(10))
                    add(JsonNull)
                    add(JsonPrimitive(false))
                    add(JsonPrimitive(true))
                },
            ),
        )
        // ASCII/UTF-16 order: "B" (0x42) < "a10" < "a2" < "arr" < "b" (0x62)
        assertEquals(
            """{"B":1,"a10":4,"a2":3,"arr":[10,null,false,true],"b":2}""",
            CanonicalJson.encode(obj),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun integerNumberFormattingAndStrictFloatRejection() {
        val validInts = buildJsonObject {
            put("max", Long.MAX_VALUE)
            put("min", Long.MIN_VALUE)
            put("neg_zero", JsonUnquotedLiteral("-0"))
            put("zero", 0)
        }
        assertEquals(
            """{"max":9223372036854775807,"min":-9223372036854775808,"neg_zero":0,"zero":0}""",
            CanonicalJson.encode(validInts),
        )

        // Floating-point, exponent, leading '+', leading zero, and >64-bit integers MUST fail closed
        val badLiterals = listOf("1.0", "3.14159", "-0.5", "1e5", "2E-3", "+42", "007", "9223372036854775808")
        for (bad in badLiterals) {
            val badObj = buildJsonObject {
                put("n", JsonUnquotedLiteral(bad))
            }
            assertFailsWith<IllegalArgumentException>("Expected '$bad' to be rejected") {
                CanonicalJson.encode(badObj)
            }
        }
    }

    @Test
    fun minimalRfc8785StringEscaping() {
        val s = "quote\"slash\\back\btab\tnewline\nform\u000Cret\rnull\u0000ctrl\u001fΩ🚀"
        val obj = buildJsonObject { put("s", s) }
        assertEquals(
            "{\"s\":\"quote\\\"slash\\\\back\\btab\\tnewline\\nform\\fret\\rnull\\u0000ctrl\\u001fΩ🚀\"}",
            CanonicalJson.encode(obj),
        )
    }

    @Test
    fun goldenVector2SigningPreimageMatches256BytesAndSha256() {
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
        val preimage = Ec0EnvelopeCodec.signingPreimage(
            msgType = "cmd",
            srcRole = "ec0-engine",
            category = "clipboard",
            timestamp = 1763000000000L,
            seq = 1L,
            nonce = 4829104728193048291L,
            msgBody = body,
        )
        assertEquals(256, preimage.size)
        assertEquals(
            "92ef942b467b87625e298a6047cdc851a810118faf05b509d3f3512108885ced",
            Sha256.digest(preimage).toHex(),
        )
    }

    @Test
    fun signingPreimageRejectsHeaderLineBreakInjectionAndNonPositiveSeq() {
        assertFailsWith<IllegalArgumentException> {
            Ec0EnvelopeCodec.signingPreimage(
                msgType = "cmd",
                srcRole = "ec0-engine\ninjected",
                category = "clipboard",
                timestamp = 1000L,
                seq = 1L,
                nonce = 1L,
                msgBody = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Ec0EnvelopeCodec.signingPreimage(
                msgType = "cmd",
                srcRole = "ec0-engine",
                category = "clip\rboard",
                timestamp = 1000L,
                seq = 1L,
                nonce = 1L,
                msgBody = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Ec0EnvelopeCodec.signingPreimage(
                msgType = "cmd",
                srcRole = "ec0-engine",
                category = "clipboard",
                timestamp = 1000L,
                seq = 0L,
                nonce = 1L,
                msgBody = null,
            )
        }
        assertTrue(true)
    }

    @Test
    fun replayWindowSlidingBitmaskAndClockSkew() {
        val window = ReplayWindow(maxClockSkewMs = 300_000L)
        val now = 1_763_000_000_000L

        assertEquals(ReplayCheckResult.INVALID_SEQ, window.checkAndRecord(0L, now, now))
        assertEquals(ReplayCheckResult.CLOCK_SKEW_EXCEEDED, window.checkAndRecord(1L, now - 300_001L, now))
        assertEquals(ReplayCheckResult.CLOCK_SKEW_EXCEEDED, window.checkAndRecord(1L, now + 300_001L, now))

        // Sequence 1 accepted
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(1L, now, now))
        assertEquals(ReplayCheckResult.DUPLICATE_SEQ, window.checkAndRecord(1L, now, now))

        // Jump to sequence 64 -> seq 1 is at offset 63 (still inside 64-entry window, already marked seen)
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(64L, now, now))
        assertEquals(ReplayCheckResult.DUPLICATE_SEQ, window.checkAndRecord(1L, now, now))
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(2L, now, now))
        assertEquals(ReplayCheckResult.DUPLICATE_SEQ, window.checkAndRecord(2L, now, now))

        // Advance to 65 -> seq 1 is now at offset 64 (outside 64-packet window -> SEQ_TOO_OLD)
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(65L, now, now))
        assertEquals(ReplayCheckResult.SEQ_TOO_OLD, window.checkAndRecord(1L, now, now))

        // Huge jump (> 64) resets mask cleanly
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(200L, now, now))
        assertEquals(ReplayCheckResult.SEQ_TOO_OLD, window.checkAndRecord(65L, now, now))
        assertEquals(ReplayCheckResult.ACCEPTED, window.checkAndRecord(199L, now, now))
    }

    @Test
    fun offlineFlushDeduplicatorEnforcesContentIdAndBatchMarkerAcrossSessions() {
        val dedupe = OfflineFlushDeduplicator(maxEntries = 16, offlineTtlMs = 3_600_000L)
        val peerId = "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9"
        val now = 1_763_000_000_000L

        fun clipEnv(seq: Long, ts: Long, nonce: Long, sha: String) = Ec0Envelope(
            msgType = "cmd",
            msgBody = Ec0EnvelopeCodec.buildCmdBody(
                cmdType = "clipboard_sync",
                cmdBody = buildJsonObject {
                    put("clip_sha256", sha)
                    put("mime", "text/plain")
                    put("size_bytes", 3)
                },
            ),
            category = "clipboard",
            timestamp = ts,
            seq = seq,
            nonce = nonce,
            sig = "valid",
        )

        val e1 = clipEnv(seq = 10L, ts = now - 60_000L, nonce = 111L, sha = "aaaa")
        val e2Expired = clipEnv(seq = 11L, ts = now - 3_600_001L, nonce = 222L, sha = "bbbb")
        val e3SameContentDifferentSeq = clipEnv(seq = 12L, ts = now - 30_000L, nonce = 333L, sha = "aaaa")
        val e4Fresh = clipEnv(seq = 13L, ts = now - 10_000L, nonce = 444L, sha = "cccc")

        val firstAccepted = dedupe.filterBatch(
            peerDeviceId = peerId,
            batchSeq = 1L,
            innerEnvelopes = listOf(e1, e2Expired, e3SameContentDifferentSeq, e4Fresh),
            nowMs = now,
            signatureVerifier = { true },
        )
        // e1 and e4Fresh accepted; e2Expired dropped (TTL > 1h); e3 dropped (duplicate clip_sha256 "aaaa")
        assertEquals(listOf(e1, e4Fresh), firstAccepted)

        // Simulate session reconnect: batchSeq resets to 1, but content-id/origin cache persists!
        dedupe.onNewSession()
        val replayedAcrossSession = dedupe.filterBatch(
            peerDeviceId = peerId,
            batchSeq = 1L,
            innerEnvelopes = listOf(e1, e4Fresh),
            nowMs = now + 5_000L,
            signatureVerifier = { true },
        )
        assertTrue(replayedAcrossSession.isEmpty())
    }

    @Test
    fun pairingQrUriRoundtripAndEphemeralBoundProofVector() {
        val ikPub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".decodeHex()
        val rkPub = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".decodeHex()
        val epkI = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".decodeHex()
        val qrNonce = "aabbccddeeff00112233445566778899".decodeHex()

        // Pinned vector from docs/engine/design.md §8.3 (Amendment 3)
        val proof = EngineIdentity.computePairingProof(qrNonce, ikPub, rkPub, epkI)
        assertEquals(
            "b2fed94557974e200549b98fa5e027d6b8712c8813bced6d0c6339e1c6d04d38",
            proof,
        )
        assertTrue(EngineIdentity.verifyPairingProof(proof, qrNonce, ikPub, rkPub, epkI))
        // Different ephemeral key must fail verification (cannot replay proof with attacker epk)
        val attackerEpk = ByteArray(32) { 0x42 }
        assertFalse(EngineIdentity.verifyPairingProof(proof, qrNonce, ikPub, rkPub, attackerEpk))

        // QR URI roundtrip with spaces and UTF-8 characters in alias
        val rkId = EngineIdentity.computeDeviceId(rkPub)
        val qr = PairingQrPayload(
            deviceId = rkId,
            publicKeyRaw = rkPub,
            alias = "Aryan's OPPO A5x",
            nonce = qrNonce,
            port = EngineConstants.PRIMARY_PORT,
            ips = listOf("192.168.1.42", "192.168.137.1"),
        )
        val uri = qr.toUri()
        val parsed = PairingQrPayload.parseUri(uri)
        assertEquals(qr, parsed)
        assertEquals(49880, parsed.port)
    }
}
