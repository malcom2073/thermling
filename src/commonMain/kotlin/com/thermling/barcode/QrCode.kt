package com.thermling.barcode

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * QR Code Model 2 encoder for byte-mode data.
 *
 * This is a compact Kotlin port of the core algorithm from Project Nayuki's
 * QR Code generator (MIT License, https://www.nayuki.io/page/qr-code-generator-library).
 * It supports versions 1-40, all four error-correction levels, automatic mask
 * selection, and UTF-8 byte-mode payloads.
 */
internal class QrCode private constructor(
    val version: Int,
    val errorCorrection: BarcodeErrorCorrection,
    dataCodewords: IntArray,
    requestedMask: Int,
) {
    val size: Int = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    init {
        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(dataCodewords))
        val mask = if (requestedMask == -1) {
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (candidate in 0 until 8) {
                applyMask(candidate)
                drawFormatBits(candidate)
                val penalty = penaltyScore()
                if (penalty < bestPenalty) {
                    bestMask = candidate
                    bestPenalty = penalty
                }
                applyMask(candidate)
            }
            bestMask
        } else {
            requestedMask
        }
        applyMask(mask)
        drawFormatBits(mask)
    }

    fun isDark(x: Int, y: Int): Boolean =
        x in 0 until size && y in 0 until size && modules[y][x]

    private fun drawFunctionPatterns() {
        for (index in 0 until size) {
            setFunctionModule(6, index, index % 2 == 0)
            setFunctionModule(index, 6, index % 2 == 0)
        }

        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)

        val alignmentPositions = alignmentPatternPositions()
        val count = alignmentPositions.size
        for (i in 0 until count) {
            for (j in 0 until count) {
                if (!(i == 0 && j == 0 ||
                        i == 0 && j == count - 1 ||
                        i == count - 1 && j == 0)
                ) {
                    drawAlignmentPattern(alignmentPositions[i], alignmentPositions[j])
                }
            }
        }

        drawFormatBits(0)
        drawVersion()
    }

    private fun drawFormatBits(mask: Int) {
        val data = errorCorrection.formatBits shl 3 or mask
        var remainder = data
        repeat(10) {
            remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
        }
        val bits = (data shl 10 or remainder) xor 0x5412

        for (i in 0..5) setFunctionModule(8, i, bit(bits, i))
        setFunctionModule(8, 7, bit(bits, 6))
        setFunctionModule(8, 8, bit(bits, 7))
        setFunctionModule(7, 8, bit(bits, 8))
        for (i in 9 until 15) setFunctionModule(14 - i, 8, bit(bits, i))

        for (i in 0 until 8) setFunctionModule(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) setFunctionModule(8, size - 15 + i, bit(bits, i))
        setFunctionModule(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) {
            remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25)
        }
        val bits = version shl 12 or remainder
        for (i in 0 until 18) {
            val value = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunctionModule(a, b, value)
            setFunctionModule(b, a, value)
        }
    }

    private fun drawFinderPattern(centerX: Int, centerY: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val distance = max(abs(dx), abs(dy))
                val x = centerX + dx
                val y = centerY + dy
                if (x in 0 until size && y in 0 until size) {
                    setFunctionModule(x, y, distance != 2 && distance != 4)
                }
            }
        }
    }

    private fun drawAlignmentPattern(centerX: Int, centerY: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunctionModule(
                    centerX + dx,
                    centerY + dy,
                    max(abs(dx), abs(dy)) != 1,
                )
            }
        }
    }

    private fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    private fun addEccAndInterleave(data: IntArray): IntArray {
        require(data.size == dataCodewordCount(version, errorCorrection))
        val blockCount = errorCorrectionBlockCount(errorCorrection, version)
        val blockEccLength = errorCorrectionCodewordsPerBlock(errorCorrection, version)
        val rawCodewords = rawDataModules(version) / 8
        val shortBlockCount = blockCount - rawCodewords % blockCount
        val shortBlockLength = rawCodewords / blockCount

        val blocks = Array(blockCount) { IntArray(0) }
        val divisor = reedSolomonDivisor(blockEccLength)
        var dataIndex = 0
        for (blockIndex in 0 until blockCount) {
            val dataLength = shortBlockLength - blockEccLength +
                if (blockIndex < shortBlockCount) 0 else 1
            val blockData = data.copyOfRange(dataIndex, dataIndex + dataLength)
            dataIndex += dataLength
            val block = IntArray(shortBlockLength + 1)
            blockData.copyInto(block)
            val ecc = reedSolomonRemainder(blockData, divisor)
            ecc.copyInto(block, block.size - blockEccLength)
            blocks[blockIndex] = block
        }

        val result = IntArray(rawCodewords)
        var resultIndex = 0
        for (i in blocks[0].indices) {
            for (j in blocks.indices) {
                if (i != shortBlockLength - blockEccLength || j >= shortBlockCount) {
                    result[resultIndex++] = blocks[j][i]
                }
            }
        }
        return result
    }

    private fun drawCodewords(data: IntArray) {
        require(data.size == rawDataModules(version) / 8)
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1 and 2) == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!isFunction[y][x] && bitIndex < data.size * 8) {
                        modules[y][x] = bit(data[bitIndex ushr 3], 7 - (bitIndex and 7))
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        check(bitIndex == data.size * 8)
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> error("Invalid mask: $mask")
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun penaltyScore(): Int {
        var result = 0

        for (y in 0 until size) {
            var runColor = false
            var runLength = 0
            val history = IntArray(7)
            for (x in 0 until size) {
                if (modules[y][x] == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1
                    else if (runLength > 5) result++
                } else {
                    addRunHistory(runLength, history)
                    if (!runColor) result += countFinderPatterns(history) * PENALTY_N3
                    runColor = modules[y][x]
                    runLength = 1
                }
            }
            result += terminateRunHistory(runColor, runLength, history) * PENALTY_N3
        }

        for (x in 0 until size) {
            var runColor = false
            var runLength = 0
            val history = IntArray(7)
            for (y in 0 until size) {
                if (modules[y][x] == runColor) {
                    runLength++
                    if (runLength == 5) result += PENALTY_N1
                    else if (runLength > 5) result++
                } else {
                    addRunHistory(runLength, history)
                    if (!runColor) result += countFinderPatterns(history) * PENALTY_N3
                    runColor = modules[y][x]
                    runLength = 1
                }
            }
            result += terminateRunHistory(runColor, runLength, history) * PENALTY_N3
        }

        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y][x]
                if (color == modules[y][x + 1] &&
                    color == modules[y + 1][x] &&
                    color == modules[y + 1][x + 1]
                ) {
                    result += PENALTY_N2
                }
            }
        }

        var dark = 0
        for (row in modules) {
            for (color in row) if (color) dark++
        }
        val total = size * size
        val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * PENALTY_N4
        return result
    }

    private fun countFinderPatterns(history: IntArray): Int {
        val n = history[1]
        val core = n > 0 &&
            history[2] == n &&
            history[3] == n * 3 &&
            history[4] == n &&
            history[5] == n
        return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
            (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    private fun terminateRunHistory(
        currentColor: Boolean,
        currentLength: Int,
        history: IntArray,
    ): Int {
        var length = currentLength
        if (currentColor) {
            addRunHistory(length, history)
            length = 0
        }
        length += size
        addRunHistory(length, history)
        return countFinderPatterns(history)
    }

    private fun addRunHistory(currentLength: Int, history: IntArray) {
        var length = currentLength
        if (history[0] == 0) length += size
        for (i in history.size - 1 downTo 1) history[i] = history[i - 1]
        history[0] = length
    }

    private fun alignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val result = IntArray(count)
        result[0] = 6
        var position = size - 7
        for (i in result.size - 1 downTo 1) {
            result[i] = position
            position -= step
        }
        return result
    }

    companion object {
        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(
                -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22,
                24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            intArrayOf(
                -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24,
                28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
                28, 28, 28, 28, 28, 28, 28, 28, 28,
            ),
            intArrayOf(
                -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30,
                24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            intArrayOf(
                -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24,
                30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
        )

        private val ERROR_CORRECTION_BLOCKS = arrayOf(
            intArrayOf(
                -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7,
                8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20,
                21, 22, 24, 25,
            ),
            intArrayOf(
                -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14,
                16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38,
                40, 43, 45, 47, 49,
            ),
            intArrayOf(
                -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18,
                21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51,
                53, 56, 59, 62, 65, 68,
            ),
            intArrayOf(
                -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21,
                25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60,
                63, 66, 70, 74, 77, 81,
            ),
        )

        fun encode(
            value: String,
            errorCorrection: BarcodeErrorCorrection,
            requestedMask: Int = -1,
        ): QrCode {
            if (value.isEmpty()) throw BarcodeException("QR Code data must not be empty")
            val bytes = value.encodeToByteArray().map { it.toInt() and 0xFF }
            if (bytes.size > 2953) {
                throw BarcodeException("QR Code data is too long (maximum 2953 bytes)")
            }

            var version = 1
            var capacityBits = 0
            var usedBits = 0
            while (version <= 40) {
                capacityBits = dataCodewordCount(version, errorCorrection) * 8
                usedBits = 4 + characterCountBits(version) + bytes.size * 8
                if (usedBits <= capacityBits) break
                version++
            }
            if (version > 40) {
                throw BarcodeException("QR Code data is too long at ${errorCorrection.label} error correction")
            }

            val bits = ArrayList<Boolean>(capacityBits)
            appendBits(bits, 0b0100, 4)
            appendBits(bits, bytes.size, characterCountBits(version))
            for (byte in bytes) appendBits(bits, byte, 8)
            repeat(min(4, capacityBits - bits.size)) { bits += false }
            while (bits.size % 8 != 0) bits += false

            val data = IntArray(bits.size / 8) { index ->
                var valueByte = 0
                for (offset in 0 until 8) {
                    if (bits[index * 8 + offset]) valueByte = valueByte or (1 shl (7 - offset))
                }
                valueByte
            }.toMutableList()
            var pad = 0xEC
            while (data.size < capacityBits / 8) {
                data += pad
                pad = pad xor (0xEC xor 0x11)
            }

            return QrCode(version, errorCorrection, data.toIntArray(), requestedMask)
        }

        private fun characterCountBits(version: Int): Int = when {
            version <= 9 -> 8
            else -> 16
        }

        private fun appendBits(bits: MutableList<Boolean>, value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += bit(value, i)
        }

        private fun bit(value: Int, index: Int): Boolean =
            (value ushr index and 1) != 0

        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val alignCount = version / 7 + 2
                result -= (25 * alignCount - 10) * alignCount - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewordCount(
            version: Int,
            errorCorrection: BarcodeErrorCorrection,
        ): Int = rawDataModules(version) / 8 -
            errorCorrectionCodewordsPerBlock(errorCorrection, version) *
            errorCorrectionBlockCount(errorCorrection, version)

        private fun errorCorrectionCodewordsPerBlock(
            errorCorrection: BarcodeErrorCorrection,
            version: Int,
        ): Int = ECC_CODEWORDS_PER_BLOCK[errorCorrection.tableIndex][version]

        private fun errorCorrectionBlockCount(
            errorCorrection: BarcodeErrorCorrection,
            version: Int,
        ): Int = ERROR_CORRECTION_BLOCKS[errorCorrection.tableIndex][version]

        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (index in result.indices) {
                    result[index] = reedSolomonMultiply(result[index], root)
                    if (index + 1 < result.size) result[index] = result[index] xor result[index + 1]
                }
                root = reedSolomonMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (byte in data) {
                val factor = byte xor result[0]
                result.copyInto(result, 0, 1)
                result[result.size - 1] = 0
                for (index in result.indices) {
                    result[index] = result[index] xor reedSolomonMultiply(divisor[index], factor)
                }
            }
            return result
        }

        private fun reedSolomonMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor ((y ushr i and 1) * x)
            }
            return z
        }
    }
}
