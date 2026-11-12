/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-Kotlin SHA-256 (FIPS 180-4). Needed because commonMain has no
 * java.security / platform crypto; the pairing verification key and the
 * certificate fingerprint display both hash over raw bytes, which IS
 * commonMain-safe. Independent correctness vectors live in Sha256Test, and
 * the pairing tests pin end-to-end compatibility against kdeconnect-android
 * upstream vectors.
 *
 * Note: hex literals above Int.MAX_VALUE carry an explicit .toInt() — without
 * it Kotlin infers Long and the 32-bit wraparound arithmetic breaks.
 */
package ec0.core.kdeconnect

/** SHA-256 digest over raw bytes; 32-byte output, big-endian. */
object Sha256 {

    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

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
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
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
