/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-Kotlin ISO/IEC 18004 QR Code Matrix Encoder (Versions 1..10, Byte Mode,
 * Error Correction Level L, GF(256) Reed-Solomon 0x11D, 8-mask penalty evaluation).
 *
 * Used by Desktop and Android UIs to render `ec0://pair?v=1...` pairing URIs directly
 * onto a Compose Canvas without any third-party ZXing/Java2D dependencies.
 */
package ec0.core.engine

class QrCodeMatrix(
    val version: Int,
    val size: Int,
    private val modules: BooleanArray,
) {
    init {
        require(version in 1..10) { "QR version must be in 1..10 (got $version)" }
        require(size == version * 4 + 17) { "Invalid QR dimension $size for version $version" }
        require(modules.size == size * size) { "Modules array length mismatch" }
    }

    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size) { "Coordinates ($x, $y) out of range 0..<$size" }
        return modules[y * size + x]
    }

    fun toAsciiArt(quietZone: Int = 2): String = buildString {
        val total = size + quietZone * 2
        for (y in 0 until total step 2) {
            for (x in 0 until total) {
                val topDark = (x - quietZone in 0 until size) && (y - quietZone in 0 until size) &&
                    this@QrCodeMatrix[x - quietZone, y - quietZone]
                val botDark = (x - quietZone in 0 until size) && (y + 1 - quietZone in 0 until size) &&
                    this@QrCodeMatrix[x - quietZone, y + 1 - quietZone]
                val ch = when {
                    topDark && botDark -> '█'
                    topDark && !botDark -> '▀'
                    !topDark && botDark -> '▄'
                    else -> ' '
                }
                append(ch)
            }
            append('\n')
        }
    }

    companion object {
        // Total data codewords and EC codewords per block for Version 1..10, EC Level L (ISO/IEC 18004 Table 9)
        private data class VersionSpec(
            val version: Int,
            val totalDataCodewords: Int,
            val ecCodewordsPerBlock: Int,
            val numBlocksGroup1: Int,
            val dataCodewordsGroup1: Int,
            val numBlocksGroup2: Int = 0,
            val dataCodewordsGroup2: Int = 0,
            val alignmentCenters: IntArray = intArrayOf(),
        )

        private val VERSION_SPECS = arrayOf(
            VersionSpec(1, 19, 7, 1, 19),
            VersionSpec(2, 34, 10, 1, 34, alignmentCenters = intArrayOf(6, 18)),
            VersionSpec(3, 55, 15, 1, 55, alignmentCenters = intArrayOf(6, 22)),
            VersionSpec(4, 80, 20, 1, 80, alignmentCenters = intArrayOf(6, 26)),
            VersionSpec(5, 108, 26, 1, 108, alignmentCenters = intArrayOf(6, 30)),
            VersionSpec(6, 136, 18, 2, 68, alignmentCenters = intArrayOf(6, 34)),
            VersionSpec(7, 156, 20, 2, 78, alignmentCenters = intArrayOf(6, 22, 38)),
            VersionSpec(8, 194, 24, 2, 97, alignmentCenters = intArrayOf(6, 24, 42)),
            VersionSpec(9, 232, 30, 2, 116, alignmentCenters = intArrayOf(6, 26, 46)),
            VersionSpec(10, 274, 18, 2, 68, 2, 69, alignmentCenters = intArrayOf(6, 28, 50)),
        )

        /**
         * Encodes [text] (UTF-8) into the smallest fitting ISO/IEC 18004 QR Code
         * matrix (Versions 1..10, Error Correction Level L, Byte Mode).
         */
        fun encodeText(text: String): QrCodeMatrix = encodeBytes(text.encodeToByteArray())

        fun encodeBytes(payload: ByteArray): QrCodeMatrix {
            require(payload.isNotEmpty()) { "QR payload must not be empty" }
            val spec = selectVersion(payload.size)
            val dataCodewords = buildDataCodewords(payload, spec)
            val allCodewords = interleaveWithEc(dataCodewords, spec)
            return buildBestMaskedMatrix(allCodewords, spec)
        }

        private fun selectVersion(byteLen: Int): VersionSpec {
            for (spec in VERSION_SPECS) {
                val charCountBits = if (spec.version <= 9) 8 else 16
                val maxBits = spec.totalDataCodewords * 8
                val neededBits = 4 + charCountBits + byteLen * 8
                if (neededBits <= maxBits) return spec
            }
            throw IllegalArgumentException(
                "QR payload length ($byteLen bytes) exceeds Version 10-L capacity (271 bytes)",
            )
        }

        private fun buildDataCodewords(payload: ByteArray, spec: VersionSpec): IntArray {
            val maxBits = spec.totalDataCodewords * 8
            val bits = BooleanArray(maxBits)
            var bitIdx = 0

            fun appendBits(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    if (bitIdx < maxBits) {
                        bits[bitIdx++] = ((value ushr i) and 1) != 0
                    }
                }
            }

            // Byte mode indicator = 0100 (4 bits)
            appendBits(0b0100, 4)
            // Character count indicator (8 bits for v1..9, 16 bits for v10)
            val countBits = if (spec.version <= 9) 8 else 16
            appendBits(payload.size, countBits)
            for (b in payload) {
                appendBits(b.toInt() and 0xff, 8)
            }
            // Terminator up to 4 zero bits
            val terminator = minOf(4, maxBits - bitIdx)
            appendBits(0, terminator)
            // Pad to byte boundary
            while (bitIdx % 8 != 0) {
                appendBits(0, 1)
            }
            // Pad codewords 0xEC, 0x11 alternately
            var padToggle = true
            while (bitIdx < maxBits) {
                appendBits(if (padToggle) 0xEC else 0x11, 8)
                padToggle = !padToggle
            }

            val out = IntArray(spec.totalDataCodewords)
            for (i in out.indices) {
                var byteVal = 0
                for (b in 0 until 8) {
                    byteVal = (byteVal shl 1) or (if (bits[i * 8 + b]) 1 else 0)
                }
                out[i] = byteVal
            }
            return out
        }

        private fun interleaveWithEc(dataCodewords: IntArray, spec: VersionSpec): IntArray {
            val totalBlocks = spec.numBlocksGroup1 + spec.numBlocksGroup2
            val dataBlocks = Array(totalBlocks) { IntArray(0) }
            val ecBlocks = Array(totalBlocks) { IntArray(0) }
            val genPoly = rsGeneratorPoly(spec.ecCodewordsPerBlock)

            var offset = 0
            var blockIdx = 0
            repeat(spec.numBlocksGroup1) {
                val blk = dataCodewords.copyOfRange(offset, offset + spec.dataCodewordsGroup1)
                offset += spec.dataCodewordsGroup1
                dataBlocks[blockIdx] = blk
                ecBlocks[blockIdx] = rsComputeRemainder(blk, genPoly)
                blockIdx++
            }
            repeat(spec.numBlocksGroup2) {
                val blk = dataCodewords.copyOfRange(offset, offset + spec.dataCodewordsGroup2)
                offset += spec.dataCodewordsGroup2
                dataBlocks[blockIdx] = blk
                ecBlocks[blockIdx] = rsComputeRemainder(blk, genPoly)
                blockIdx++
            }

            val maxDataLen = maxOf(spec.dataCodewordsGroup1, spec.dataCodewordsGroup2)
            val totalCodewords = spec.totalDataCodewords + totalBlocks * spec.ecCodewordsPerBlock
            val out = IntArray(totalCodewords)
            var outIdx = 0

            for (i in 0 until maxDataLen) {
                for (b in 0 until totalBlocks) {
                    if (i < dataBlocks[b].size) {
                        out[outIdx++] = dataBlocks[b][i]
                    }
                }
            }
            for (i in 0 until spec.ecCodewordsPerBlock) {
                for (b in 0 until totalBlocks) {
                    out[outIdx++] = ecBlocks[b][i]
                }
            }
            return out
        }

        private fun buildBestMaskedMatrix(codewords: IntArray, spec: VersionSpec): QrCodeMatrix {
            val size = spec.version * 4 + 17
            val baseModules = BooleanArray(size * size)
            val isFunction = BooleanArray(size * size)

            fun setFunc(x: Int, y: Int, dark: Boolean) {
                if (x in 0 until size && y in 0 until size) {
                    baseModules[y * size + x] = dark
                    isFunction[y * size + x] = true
                }
            }

            // 1. Finder patterns + separators
            fun drawFinder(cx: Int, cy: Int) {
                for (dy in -4..4) {
                    for (dx in -4..4) {
                        val x = cx + dx
                        val y = cy + dy
                        if (x in 0 until size && y in 0 until size) {
                            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                            setFunc(x, y, dist != 2 && dist != 4)
                        }
                    }
                }
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)

            // 2. Timing patterns
            for (i in 0 until size) {
                if (!isFunction[6 * size + i]) setFunc(i, 6, i % 2 == 0)
                if (!isFunction[i * size + 6]) setFunc(6, i, i % 2 == 0)
            }

            // 3. Alignment patterns
            val centers = spec.alignmentCenters
            for (cy in centers) {
                for (cx in centers) {
                    if ((cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)) continue
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                            setFunc(cx + dx, cy + dy, dist != 1)
                        }
                    }
                }
            }

            // 4. Reserve format information areas + dark module
            for (i in 0..8) {
                if (i != 6) {
                    if (!isFunction[8 * size + i]) setFunc(i, 8, false)
                    if (!isFunction[i * size + 8]) setFunc(8, i, false)
                }
            }
            for (i in 0..7) {
                if (!isFunction[8 * size + (size - 1 - i)]) setFunc(size - 1 - i, 8, false)
                if (!isFunction[(size - 1 - i) * size + 8]) setFunc(8, size - 1 - i, false)
            }
            setFunc(8, size - 8, true)

            // 5. Place data + EC bits in zigzag upward/downward 2-column strips
            var bitIdx = 0
            val totalBits = codewords.size * 8
            var right = size - 1
            var upward = true
            while (right >= 1) {
                if (right == 6) right--
                for (step in 0 until size) {
                    val y = if (upward) size - 1 - step else step
                    for (colOffset in 0..1) {
                        val x = right - colOffset
                        if (!isFunction[y * size + x]) {
                            val dark = if (bitIdx < totalBits) {
                                val cw = codewords[bitIdx ushr 3]
                                ((cw ushr (7 - (bitIdx and 7))) and 1) != 0
                            } else {
                                false
                            }
                            baseModules[y * size + x] = dark
                            bitIdx++
                        }
                    }
                }
                upward = !upward
                right -= 2
            }

            // 6. Evaluate all 8 masks and pick the lowest penalty
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            var bestModules = baseModules

            for (mask in 0..7) {
                val candidate = baseModules.copyOf()
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        if (!isFunction[y * size + x] && maskBit(mask, x, y)) {
                            candidate[y * size + x] = !candidate[y * size + x]
                        }
                    }
                }
                drawFormatBits(candidate, size, mask)
                val penalty = computePenalty(candidate, size)
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                    bestModules = candidate
                }
            }
            drawFormatBits(bestModules, size, bestMask)
            return QrCodeMatrix(spec.version, size, bestModules)
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            else -> false
        }

        private fun drawFormatBits(modules: BooleanArray, size: Int, mask: Int) {
            // EC Level L = 01
            val data = (0b01 shl 3) or mask
            var rem = data
            repeat(10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or rem) xor 0x5412

            fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

            // Top-left
            for (i in 0..5) modules[8 * size + i] = bit(i)
            modules[8 * size + 7] = bit(6)
            modules[8 * size + 8] = bit(7)
            modules[7 * size + 8] = bit(8)
            for (i in 9..14) modules[(14 - i) * size + 8] = bit(i)

            // Top-right and bottom-left
            for (i in 0..7) modules[8 * size + (size - 1 - i)] = bit(i)
            for (i in 8..14) modules[(size - 15 + i) * size + 8] = bit(i)
            modules[(size - 8) * size + 8] = true
        }

        private fun computePenalty(modules: BooleanArray, size: Int): Int {
            var penalty = 0
            // Horizontal & vertical runs >= 5
            for (y in 0 until size) {
                var runColor = modules[y * size]
                var runLen = 1
                for (x in 1 until size) {
                    val c = modules[y * size + x]
                    if (c == runColor) {
                        runLen++
                        if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                    } else {
                        runColor = c
                        runLen = 1
                    }
                }
            }
            for (x in 0 until size) {
                var runColor = modules[x]
                var runLen = 1
                for (y in 1 until size) {
                    val c = modules[y * size + x]
                    if (c == runColor) {
                        runLen++
                        if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                    } else {
                        runColor = c
                        runLen = 1
                    }
                }
            }
            // 2x2 blocks
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = modules[y * size + x]
                    if (c == modules[y * size + x + 1] &&
                        c == modules[(y + 1) * size + x] &&
                        c == modules[(y + 1) * size + x + 1]
                    ) {
                        penalty += 3
                    }
                }
            }
            return penalty
        }

        private fun gfMultiply(x: Int, y: Int): Int {
            var res = 0
            var a = x
            var b = y
            for (i in 7 downTo 0) {
                res = (res shl 1) xor ((res ushr 7) * 0x11D)
                if (((b ushr i) and 1) != 0) {
                    res = res xor a
                }
            }
            return res
        }

        private fun rsGeneratorPoly(degree: Int): IntArray {
            var result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) {
                        result[j] = result[j] xor result[j + 1]
                    }
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun rsComputeRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                for (i in 0 until divisor.size - 1) {
                    result[i] = result[i + 1] xor gfMultiply(divisor[i], factor)
                }
                result[divisor.size - 1] = gfMultiply(divisor[divisor.size - 1], factor)
            }
            return result
        }
    }
}
