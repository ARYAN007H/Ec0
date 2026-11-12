/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Verification-key tests pin END-TO-END interop against kdeconnect-android
 * upstream: PairingHandlerTest.kt vectors over two real upstream test
 * certificates (EC P-256, SPKI DER extracted with JVM java.security — see
 * docs/re/kdeconnect-port.md). If Sha256, sortedConcat, or the hash format
 * drifts from upstream, these fail.
 */
package ec0.core.kdeconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    // SubjectPublicKeyInfo (SPKI DER) of upstream PairingHandlerTest certA/certB
    private val spkiA = (
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004aa3882939b98fac7c0ca8184a422d2e68c28085cfd2090e5ba6e656f9680c6b1" +
            "89efa982cc1467bb616f20f254ffa8f05311f0ce33852943c5fa8e2744f97ae7"
        ).hexToBytes()
    private val spkiB = (
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004395b9debac3269b3dbffb2ccd07f478bb6fa068916dfb18fac9cee882497e2d6" +
            "915e1c3423da7004f9a4f25187a5adcc75788baac451e725316dfc7a97c2e02f"
        ).hexToBytes()

    private val timestampA = 1737228658L
    private val timestampB = 2737228658L

    @Test
    fun verificationKeyMatchesUpstream() {
        // Upstream PairingHandlerTest.getVerificationKey() expected values
        assertEquals("54DC916E", VerificationKey.getVerificationKey(spkiA, spkiB, timestampA))
        assertEquals("54DC916E", VerificationKey.getVerificationKey(spkiB, spkiA, timestampA))
        assertEquals("8C07153A", VerificationKey.getVerificationKey(spkiA, spkiB, timestampB))
    }

    @Test
    fun verificationKeyV7MatchesUpstream() {
        assertEquals("F3900DB5", VerificationKey.getVerificationKeyV7(spkiA, spkiB))
        assertEquals("F3900DB5", VerificationKey.getVerificationKeyV7(spkiB, spkiA))
        // Upstream: hashing a key with itself (sortedConcat(a, a)) differs from a+b
        assertEquals("97A75917", VerificationKey.getVerificationKeyV7(spkiA, spkiA))
    }

    @Test
    fun verificationKeyFormat() {
        val key = VerificationKey.getVerificationKeyV7(spkiA, spkiB)
        assertEquals(8, key.length)
        assertTrue(key.all { it.isDigit() || it in 'A'..'F' })
    }

    @Test
    fun pairPacketShapes() {
        val request = PairingPackets.requestPair(timestampA)
        assertEquals("kdeconnect.pair", request.type)
        assertEquals(true, request.getBoolean("pair"))
        assertEquals(timestampA, PairingPackets.pairingTimestamp(request))

        val accept = PairingPackets.acceptPair()
        assertEquals(true, PairingPackets.wantsPair(accept))
        assertNull(PairingPackets.pairingTimestamp(accept)) // accept does not carry a timestamp upstream

        val unpair = PairingPackets.unpair()
        assertEquals(false, PairingPackets.wantsPair(unpair))

        // Roundtrip through the wire format
        val parsed = NetworkPacket.unserialize(request.serialize())
        assertEquals(true, PairingPackets.wantsPair(parsed))
        assertEquals(timestampA, PairingPackets.pairingTimestamp(parsed))
    }

    @Test
    fun clockSkewConstant() {
        // Upstream: ±30 minutes tolerance for protocol >= 8 pairing timestamps
        assertEquals(1_800, VerificationKey.ALLOWED_TIMESTAMP_DIFFERENCE_SECONDS)
        assertEquals(8, PairingPackets.PROTOCOL_VERSION_TIMESTAMP_SUPPORT)
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}
