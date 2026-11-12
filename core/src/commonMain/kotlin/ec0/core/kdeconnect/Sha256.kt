/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-Kotlin SHA-256 (FIPS 180-4). Needed because commonMain has no
 * java.security / platform crypto; the pairing verification key and the
 * certificate fingerprint display both hash over raw bytes, which IS
 * commonMain-safe. Independent correctness vectors live in Sha256Test, and
 * the pairing tests pin end-to-end compatibility against kdeconnect-android
 * upstream vectors.
 */
package ec0.core.kdeconnect

/** SHA-256 digest over raw bytes; 32-byte output, big-endian. */
object Sha256 {

    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a
        var h4 = 0x510e527f
        var h5 = 0x9b05688c
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val bitLength = message.size.toLong() * 8
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < paddedLength) {
            for (t in 0 until 16) {
                val i = offset + t * 4
                w[t] = ((padded[i].toInt() and 0xff) shl 24) or
                    ((padded[i + 1].toInt() and 0xff) shl 16) or
                    ((padded[i + 2].toInt() and 0xff) shl 8) or
                    (padded[i + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = (w[t - 15] rotr 7) xor (w[t - 15] rotr 18) xor (w[t - 15] ushr 3)
                val s1 = (w[t - 2] rotr 17) xor (w[t - 2] rotr 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (t in 0 until 64) {
                val s1 = (e rotr 6) xor (e rotr 11) xor (e rotr 25)
                val ch = (e and f) xor (g and e.inv())
                val temp1 = h + s1 + ch + K[t] + w[t]
                val s0 = (a rotr 2) xor (a rotr 13) xor (a rotr 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }

        return byteArrayOf(
            h0.asBytes(0), h0.asBytes(1), h0.asBytes(2), h0.asBytes(3),
            h1.asBytes(0), h1.asBytes(1), h1.asBytes(2), h1.asBytes(3),
            h2.asBytes(0), h2.asBytes(1), h2.asBytes(2), h2.asBytes(3),
            h3.asBytes(0), h3.asBytes(1), h3.asBytes(2), h3.asBytes(3),
            h4.asBytes(0), h4.asBytes(1), h4.asBytes(2), h4.asBytes(3),
            h5.asBytes(0), h5.asBytes(1), h5.asBytes(2), h5.asBytes(3),
            h6.asBytes(0), h6.asBytes(1), h6.asBytes(2), h6.asBytes(3),
            h7.asBytes(0), h7.asBytes(1), h7.asBytes(2), h7.asBytes(3),
        )
    }

    private infix fun Int.rotr(n: Int) = (this ushr n) or (this shl (32 - n))

    private fun Int.asBytes(i: Int): Byte = (this ushr (8 * (3 - i))).toByte()

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    )
}

/** Lowercase hex of a byte array (no separators). */
internal fun ByteArray.toHexString(): String = joinToString("") { it.toHexByte() }

/** Upstream `getCertificateHash` format: "%02x:" per byte, trailing colon kept. */
internal fun ByteArray.toColonHex(): String = joinToString("") { it.toHexByte() + ":" }

private fun Byte.toHexByte(): String {
    val v = toInt() and 0xff
    val digits = "0123456789abcdef"
    return "${digits[v ushr 4]}${digits[v and 0x0f]}"
}
