/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned RFC 5869 Appendix A (Test Cases 1, 2, 3) and Golden Vector 1
 * session-key derivation tests for HkdfSha256 (docs/engine/design.md §3.4, §8.3).
 */
package ec0.core.engine

import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HkdfSha256Test {

    @Test
    fun rfc5869TestCase1() {
        val ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".decodeHex()
        val salt = "000102030405060708090a0b0c".decodeHex()
        val info = "f0f1f2f3f4f5f6f7f8f9".decodeHex()

        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            prk.toHex(),
        )

        val okm = HkdfSha256.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun rfc5869TestCase2LongInputsAndOutputs() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }

        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            prk.toHex(),
        )

        val okm = HkdfSha256.expand(prk, info, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHex(),
        )
    }

    @Test
    fun rfc5869TestCase3EmptySaltAndEmptyInfo() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = HkdfSha256.extract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            prk.toHex(),
        )

        val okm = HkdfSha256.deriveKey(ByteArray(0), ikm, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }

    @Test
    fun goldenVector1SessionKeyScheduleMatchesSpec() {
        val ikPub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".decodeHex()
        val rkPub = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".decodeHex()
        val epkI = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".decodeHex()
        val epkR = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".decodeHex()
        val z = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".decodeHex()
        val nonceI = "0102030405060708090a0b0c0d0e0f10".decodeHex()
        val nonceR = "1112131415161718191a1b1c1d1e1f20".decodeHex()

        val mat = HkdfSha256.deriveSessionKeys(
            sharedSecretZ = z,
            nonceI = nonceI,
            nonceR = nonceR,
            ikPubRaw = ikPub,
            rkPubRaw = rkPub,
            epkIRaw = epkI,
            epkRRaw = epkR,
        )

        assertEquals(
            "35da37f9a644ebcce158e3dc4f9f6d176af0e41768c7e31928fb23ababac5fba",
            mat.prk.toHex(),
        )
        assertEquals(
            "97a94be57fa3709c7b899753e653ab6a564b54e7719aafeaadcd20aee39ecfdb",
            mat.kInitToResp.toHex(),
        )
        assertEquals(
            "a0018bca1e9f43a43188de462e269db08cd283a5c2bfe8f5c80a3367102aae3b",
            mat.kRespToInit.toHex(),
        )
        assertEquals(
            "40ba833dce3c130e47879cce64bbd4f6fd40cf1708c80ce14141b7091f7ac647",
            mat.sessionId.toHex(),
        )
    }

    @Test
    fun rejectsAllZeroSharedSecret() {
        val dummy32 = ByteArray(32) { 1 }
        val dummy16 = ByteArray(16) { 2 }
        assertFailsWith<IllegalArgumentException> {
            HkdfSha256.deriveSessionKeys(
                sharedSecretZ = ByteArray(32), // all zeros -> must fail
                nonceI = dummy16,
                nonceR = dummy16,
                ikPubRaw = dummy32,
                rkPubRaw = dummy32,
                epkIRaw = dummy32,
                epkRRaw = dummy32,
            )
        }
        assertTrue(true)
    }
}
