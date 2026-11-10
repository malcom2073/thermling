package com.thermling.barcode

import kotlin.math.min

/** A one-dimensional barcode symbology. Two-dimensional formats can be added here later. */
enum class BarcodeFormat(val value: String, val label: String) {
    CODE128("code128", "Code 128"),
    CODE39("code39", "Code 39"),
    EAN13("ean13", "EAN-13"),
    EAN8("ean8", "EAN-8"),
    UPCA("upca", "UPC-A");

    companion object {
        fun fromValue(raw: String): BarcodeFormat? =
            entries.firstOrNull { it.value == raw.trim().lowercase() }
    }
}

class BarcodeException(message: String) : Exception(message)

/**
 * An encoded barcode as a run of modules.
 *
 * [modules] contains the bars and spaces of the symbol itself, with `1` for a
 * black module and `0` for a white one. [quietZoneModules] is the white space
 * required on each side; it is not included in [modules] but is part of
 * [totalModules], which is what a renderer should scale the element to.
 */
data class BarcodeEncoding(
    val format: BarcodeFormat,
    val text: String,
    val modules: String,
    val quietZoneModules: Int,
) {
    val totalModules: Int get() = modules.length + quietZoneModules * 2
}

/**
 * A small, dependency-free 1D barcode encoder.
 *
 * The supported formats are deliberately limited to the ones most useful on
 * narrow thermal labels. Code 128 automatically uses subset C for an even
 * number of digits and subset B otherwise.
 */
object BarcodeEncoder {
    private val CODE128_PATTERNS: List<IntArray> = listOf(
        intArrayOf(2, 1, 2, 2, 2, 2), intArrayOf(2, 2, 2, 1, 2, 2),
        intArrayOf(2, 2, 2, 2, 2, 1), intArrayOf(1, 2, 1, 2, 2, 3),
        intArrayOf(1, 2, 1, 3, 2, 2), intArrayOf(1, 3, 1, 2, 2, 2),
        intArrayOf(1, 2, 2, 2, 1, 3), intArrayOf(1, 2, 2, 3, 1, 2),
        intArrayOf(1, 3, 2, 2, 1, 2), intArrayOf(2, 2, 1, 2, 1, 3),
        intArrayOf(2, 2, 1, 3, 1, 2), intArrayOf(2, 3, 1, 2, 1, 2),
        intArrayOf(1, 1, 2, 2, 3, 2), intArrayOf(1, 2, 2, 1, 3, 2),
        intArrayOf(1, 2, 2, 2, 3, 1), intArrayOf(1, 1, 3, 2, 2, 2),
        intArrayOf(1, 2, 3, 1, 2, 2), intArrayOf(1, 2, 3, 2, 2, 1),
        intArrayOf(2, 2, 3, 2, 1, 1), intArrayOf(2, 2, 1, 1, 3, 2),
        intArrayOf(2, 2, 1, 2, 3, 1), intArrayOf(2, 1, 3, 2, 1, 2),
        intArrayOf(2, 2, 3, 1, 1, 2), intArrayOf(3, 1, 2, 1, 3, 1),
        intArrayOf(3, 1, 1, 2, 2, 2), intArrayOf(3, 2, 1, 1, 2, 2),
        intArrayOf(3, 2, 1, 2, 2, 1), intArrayOf(3, 1, 2, 2, 1, 2),
        intArrayOf(3, 2, 2, 1, 1, 2), intArrayOf(3, 2, 2, 2, 1, 1),
        intArrayOf(2, 1, 2, 1, 2, 3), intArrayOf(2, 1, 2, 3, 2, 1),
        intArrayOf(2, 3, 2, 1, 2, 1), intArrayOf(1, 1, 1, 3, 2, 3),
        intArrayOf(1, 3, 1, 1, 2, 3), intArrayOf(1, 3, 1, 3, 2, 1),
        intArrayOf(1, 1, 2, 3, 1, 3), intArrayOf(1, 3, 2, 1, 1, 3),
        intArrayOf(1, 3, 2, 3, 1, 1), intArrayOf(2, 1, 1, 3, 1, 3),
        intArrayOf(2, 3, 1, 1, 1, 3), intArrayOf(2, 3, 1, 3, 1, 1),
        intArrayOf(1, 1, 2, 1, 3, 3), intArrayOf(1, 1, 2, 3, 3, 1),
        intArrayOf(1, 3, 2, 1, 3, 1), intArrayOf(1, 1, 3, 1, 2, 3),
        intArrayOf(1, 1, 3, 3, 2, 1), intArrayOf(1, 3, 3, 1, 2, 1),
        intArrayOf(3, 1, 3, 1, 2, 1), intArrayOf(2, 1, 1, 3, 3, 1),
        intArrayOf(2, 3, 1, 1, 3, 1), intArrayOf(2, 1, 3, 1, 1, 3),
        intArrayOf(2, 1, 3, 3, 1, 1), intArrayOf(2, 1, 3, 1, 3, 1),
        intArrayOf(3, 1, 1, 1, 2, 3), intArrayOf(3, 1, 1, 3, 2, 1),
        intArrayOf(3, 3, 1, 1, 2, 1), intArrayOf(3, 1, 2, 1, 1, 3),
        intArrayOf(3, 1, 2, 3, 1, 1), intArrayOf(3, 3, 2, 1, 1, 1),
        intArrayOf(3, 1, 4, 1, 1, 1), intArrayOf(2, 2, 1, 4, 1, 1),
        intArrayOf(4, 3, 1, 1, 1, 1), intArrayOf(1, 1, 1, 2, 2, 4),
        intArrayOf(1, 1, 1, 4, 2, 2), intArrayOf(1, 2, 1, 1, 2, 4),
        intArrayOf(1, 2, 1, 4, 2, 1), intArrayOf(1, 4, 1, 1, 2, 2),
        intArrayOf(1, 4, 1, 2, 2, 1), intArrayOf(1, 1, 2, 2, 1, 4),
        intArrayOf(1, 1, 2, 4, 1, 2), intArrayOf(1, 2, 2, 1, 1, 4),
        intArrayOf(1, 2, 2, 4, 1, 1), intArrayOf(1, 4, 2, 1, 1, 2),
        intArrayOf(1, 4, 2, 2, 1, 1), intArrayOf(2, 4, 1, 2, 1, 1),
        intArrayOf(2, 2, 1, 1, 1, 4), intArrayOf(4, 1, 3, 1, 1, 1),
        intArrayOf(2, 4, 1, 1, 1, 2), intArrayOf(1, 3, 4, 1, 1, 1),
        intArrayOf(1, 1, 1, 2, 4, 2), intArrayOf(1, 2, 1, 1, 4, 2),
        intArrayOf(1, 2, 1, 2, 4, 1), intArrayOf(1, 1, 4, 2, 1, 2),
        intArrayOf(1, 2, 4, 1, 1, 2), intArrayOf(1, 2, 4, 2, 1, 1),
        intArrayOf(4, 1, 1, 2, 1, 2), intArrayOf(4, 2, 1, 1, 1, 2),
        intArrayOf(4, 2, 1, 2, 1, 1), intArrayOf(2, 1, 2, 1, 4, 1),
        intArrayOf(2, 1, 4, 1, 2, 1), intArrayOf(4, 1, 2, 1, 2, 1),
        intArrayOf(1, 1, 1, 1, 4, 3), intArrayOf(1, 1, 1, 3, 4, 1),
        intArrayOf(1, 3, 1, 1, 4, 1), intArrayOf(1, 1, 4, 1, 1, 3),
        intArrayOf(1, 1, 4, 3, 1, 1), intArrayOf(4, 1, 1, 1, 1, 3),
        intArrayOf(4, 1, 1, 3, 1, 1), intArrayOf(1, 1, 3, 1, 4, 1),
        intArrayOf(1, 1, 4, 1, 3, 1), intArrayOf(3, 1, 1, 1, 4, 1),
        intArrayOf(4, 1, 1, 1, 3, 1), intArrayOf(2, 1, 1, 4, 1, 2),
        intArrayOf(2, 1, 1, 2, 1, 4), intArrayOf(2, 1, 1, 2, 3, 2),
        intArrayOf(2, 3, 3, 1, 1, 1, 2),
    )

    private const val CODE128_START_B = 104
    private const val CODE128_START_C = 105
    private const val CODE128_STOP = 106

    private const val CODE39_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%"
    private val CODE39_ENCODINGS = intArrayOf(
        0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064,
        0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00D, 0x10C, 0x04C, 0x01C,
        0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,
        0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x0A8,
        0x0A2, 0x08A, 0x02A,
    )
    private const val CODE39_ASTERISK = 0x094

    private val EAN_L_PATTERNS = arrayOf(
        intArrayOf(3, 2, 1, 1),
        intArrayOf(2, 2, 2, 1),
        intArrayOf(2, 1, 2, 2),
        intArrayOf(1, 4, 1, 1),
        intArrayOf(1, 1, 3, 2),
        intArrayOf(1, 2, 3, 1),
        intArrayOf(1, 1, 1, 4),
        intArrayOf(1, 3, 1, 2),
        intArrayOf(1, 2, 1, 3),
        intArrayOf(3, 1, 1, 2),
    )
    private val EAN_FIRST_DIGIT_ENCODINGS = intArrayOf(
        0x00, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A,
    )
    private val EAN_GUARD = intArrayOf(1, 1, 1)
    private val EAN_MIDDLE = intArrayOf(1, 1, 1, 1, 1)

    fun encode(format: BarcodeFormat, value: String): BarcodeEncoding = when (format) {
        BarcodeFormat.CODE128 -> encodeCode128(value)
        BarcodeFormat.CODE39 -> encodeCode39(value)
        BarcodeFormat.EAN13 -> encodeEan13(value)
        BarcodeFormat.EAN8 -> encodeEan8(value)
        BarcodeFormat.UPCA -> encodeUpcA(value)
    }

    private fun encodeCode128(value: String): BarcodeEncoding {
        if (value.isEmpty()) throw BarcodeException("Code 128 data must not be empty")
        val codes = mutableListOf<Int>()
        val digitsOnly = value.all { it in '0'..'9' }
        if (digitsOnly && value.length >= 2 && value.length % 2 == 0) {
            codes += CODE128_START_C
            var index = 0
            while (index < value.length) {
                codes += value.substring(index, index + 2).toInt()
                index += 2
            }
        } else {
            if (value.any { it.code !in 32..126 }) {
                throw BarcodeException("Code 128 supports printable ASCII only")
            }
            codes += CODE128_START_B
            codes += value.map { it.code - 32 }
        }

        var checksum = codes[0]
        for (index in 1 until codes.size) {
            checksum += index * codes[index]
        }
        checksum %= 103

        val modules = StringBuilder()
        for (code in codes + checksum + CODE128_STOP) {
            appendWidths(modules, CODE128_PATTERNS[code], startBlack = true)
        }
        return BarcodeEncoding(BarcodeFormat.CODE128, value, modules.toString(), quietZoneModules = 10)
    }

    private fun encodeCode39(value: String): BarcodeEncoding {
        val text = value.uppercase()
        if (text.isEmpty()) throw BarcodeException("Code 39 data must not be empty")
        val modules = StringBuilder()
        val characters = "*" + text + "*"
        for ((position, character) in characters.withIndex()) {
            if (position > 0) modules.append('0')
            val encoding = if (character == '*') {
                CODE39_ASTERISK
            } else {
                val index = CODE39_ALPHABET.indexOf(character)
                if (index < 0) {
                    throw BarcodeException("Code 39 cannot encode '$character'")
                }
                CODE39_ENCODINGS[index]
            }
            for (bit in 8 downTo 0) {
                val element = 8 - bit
                val width = if ((encoding shr bit) and 1 == 1) 3 else 1
                modules.append(if (element % 2 == 0) '1' else '0')
                repeat(width - 1) { modules.append(modules.last()) }
            }
        }
        return BarcodeEncoding(BarcodeFormat.CODE39, text, modules.toString(), quietZoneModules = 10)
    }

    private fun encodeEan13(value: String): BarcodeEncoding {
        val digits = normalizeNumeric(value, 12, 13, "EAN-13")
        val first = digits[0] - '0'
        val parities = EAN_FIRST_DIGIT_ENCODINGS[first]
        val modules = StringBuilder()

        appendWidths(modules, EAN_GUARD, startBlack = true)
        for (position in 1..6) {
            val digit = digits[position] - '0'
            val pattern = if ((parities shr (6 - position)) and 1 == 1) {
                EAN_L_PATTERNS[digit].reversedArray()
            } else {
                EAN_L_PATTERNS[digit]
            }
            appendWidths(modules, pattern, startBlack = false)
        }
        appendWidths(modules, EAN_MIDDLE, startBlack = false)
        for (position in 7..12) {
            appendWidths(modules, EAN_L_PATTERNS[digits[position] - '0'], startBlack = true)
        }
        appendWidths(modules, EAN_GUARD, startBlack = true)

        return BarcodeEncoding(BarcodeFormat.EAN13, digits, modules.toString(), quietZoneModules = 11)
    }

    private fun encodeEan8(value: String): BarcodeEncoding {
        val digits = normalizeNumeric(value, 7, 8, "EAN-8")
        val modules = StringBuilder()

        appendWidths(modules, EAN_GUARD, startBlack = true)
        for (position in 0..3) {
            appendWidths(modules, EAN_L_PATTERNS[digits[position] - '0'], startBlack = false)
        }
        appendWidths(modules, EAN_MIDDLE, startBlack = false)
        for (position in 4..7) {
            appendWidths(modules, EAN_L_PATTERNS[digits[position] - '0'], startBlack = true)
        }
        appendWidths(modules, EAN_GUARD, startBlack = true)

        return BarcodeEncoding(BarcodeFormat.EAN8, digits, modules.toString(), quietZoneModules = 7)
    }

    private fun encodeUpcA(value: String): BarcodeEncoding {
        val digits = normalizeNumeric(value, 11, 12, "UPC-A")
        val ean = encodeEan13("0$digits")
        return ean.copy(format = BarcodeFormat.UPCA, text = digits, quietZoneModules = 9)
    }

    private fun normalizeNumeric(value: String, shortLength: Int, length: Int, name: String): String {
        val trimmed = value.trim()
        if (trimmed.any { it !in '0'..'9' }) {
            throw BarcodeException("$name accepts digits only")
        }
        if (trimmed.length == shortLength) {
            val check = standardChecksum(trimmed)
            return trimmed + check
        }
        if (trimmed.length != length) {
            throw BarcodeException("$name needs $shortLength or $length digits")
        }
        val expected = standardChecksum(trimmed.dropLast(1))
        val actual = trimmed.last() - '0'
        if (expected != actual) {
            throw BarcodeException("$name check digit should be $expected, not $actual")
        }
        return trimmed
    }

    /** The UPC/EAN check digit for [data], which must contain digits only. */
    private fun standardChecksum(data: String): Int {
        var sum = 0
        var weight = 3
        for (index in data.indices.reversed()) {
            val digit = data[index] - '0'
            if (digit !in 0..9) throw BarcodeException("Barcode data must contain digits only")
            sum += digit * weight
            weight = if (weight == 3) 1 else 3
        }
        return (1000 - sum) % 10
    }

    private fun appendWidths(builder: StringBuilder, widths: IntArray, startBlack: Boolean) {
        var black = startBlack
        for (width in widths) {
            val module = if (black) '1' else '0'
            repeat(width) { builder.append(module) }
            black = !black
        }
    }
}
