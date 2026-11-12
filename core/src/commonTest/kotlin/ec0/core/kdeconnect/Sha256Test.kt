/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Independent SHA-256 correctness vectors (NIST FIPS 180-4 test vectors)
 * — these do NOT depend on the ported pairing logic, so a broken digest
 * implementation is caught separately from the upstream-pinned PairingTest.
 */
package ec0.core.kdeconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256Test {

    private fun sha256Hex(input: String): String = Sha256.digest(input.encodeToByteArray()).toHexString()

    @Test
    fun emptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
    }

    @Test
    fun abcVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun singleBlock448BitVector() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun multiBlock896BitVector() {
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            sha256Hex(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                    "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu",
            ),
        )
    }

    @Test
    fun longInputSpansMultipleBlocks() {
        // 1,000,000 'a' bytes → known NIST vector; trimmed here to a fast but
        // still multi-block 10,000-char run compared against java-free math:
        // we only assert determinism + a length-stable digest here because the
        // canonical million-'a' vector is slow in pure Kotlin.
        val input = "a".repeat(10_000)
        val d1 = Sha256.digest(input.encodeToByteArray())
        val d2 = Sha256.digest(input.encodeToByteArray())
        // NB: assertTrue(contentEquals) — kotlin.test assertEquals on ByteArray
        // falls through to JVM reference equality.
        assertTrue(d1.contentEquals(d2))
        assertEquals(32, d1.size)
        // sanity: differs from the 9999-'a' digest (padding boundary behavior)
        val d3 = Sha256.digest("a".repeat(9_999).encodeToByteArray())
        assertFalse(d1.contentEquals(d3))
    }

    @Test
    fun millionAVector() {
        // Canonical NIST test vector: SHA-256 over 1,000,000 'a' bytes.
        // 15,625 blocks in pure Kotlin — a few hundred ms on JVM CI.
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e047d5694e91fdc34",
            sha256Hex("a".repeat(1_000_000)),
        )
    }

    @Test
    fun colonHexFormatMatchesUpstreamCertificateHashStyle() {
        // Upstream SslHelper.getCertificateHash renders "%02x:" per byte with
        // trailing colon — same helper used for the fingerprint UI.
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0x01, 0x0f)
        assertEquals("de:ad:01:0f:", bytes.toColonHex())
    }
}
