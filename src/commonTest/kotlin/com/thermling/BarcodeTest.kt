package com.thermling

import com.thermling.barcode.BarcodeDimension
import com.thermling.barcode.BarcodeEncoder
import com.thermling.barcode.BarcodeEncoding
import com.thermling.barcode.BarcodeErrorCorrection
import com.thermling.barcode.BarcodeException
import com.thermling.barcode.BarcodeFormat
import com.thermling.barcode.MatrixBarcodeEncoding
import com.thermling.barcode.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BarcodeTest {
    @Test
    fun code128UsesSubsetCForEvenDigitData() {
        val encoding = encodeLinear(BarcodeFormat.CODE128, "12345670")

        // Start C, four digit pairs, checksum, stop: 6 symbols of 11 modules
        // plus the 13-module stop pattern.
        assertEquals(79, encoding.modules.length)
        assertEquals("12345670", encoding.text)
        assertEquals(10, encoding.quietZoneModules)
        assertEquals(99, encoding.totalModules)
        assertTrue(encoding.modules.all { it == '0' || it == '1' })
    }

    @Test
    fun code128SupportsEveryPrintableAsciiCharacter() {
        for (code in 32..126) {
            val encoding = encodeLinear(BarcodeFormat.CODE128, code.toChar().toString())
            // Start, one data symbol, checksum and the 13-module stop.
            assertEquals(46, encoding.modules.length, "character code $code")
        }
    }

    @Test
    fun code128RejectsUnsupportedData() {        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.CODE128, "")
        }
        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.CODE128, "café")
        }
    }

    @Test
    fun code39WrapsTheDataInStartAndStopCharacters() {
        val encoding = encodeLinear(BarcodeFormat.CODE39, "ab-12")

        assertEquals("AB-12", encoding.text)
        // Each character is 15 modules, with a one-module gap between the
        // seven characters (start, five data characters, stop).
        assertEquals(7 * 15 + 6, encoding.modules.length)
        assertEquals(
            "100010111011101",
            encoding.modules.take(15),
            "the symbol should begin with the Code 39 start character",
        )
    }

    @Test
    fun code39RejectsCharactersOutsideItsAlphabet() {
        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.CODE39, "hello!")
        }
    }

    @Test
    fun ean13EncodesTheGuardAndParityPatterns() {
        val encoding = encodeLinear(BarcodeFormat.EAN13, "5901234123457")

        val expected = "101" +
            "0001011" + // 9, L
            "0100111" + // 0, G
            "0110011" + // 1, G
            "0010011" + // 2, L
            "0111101" + // 3, L
            "0011101" + // 4, G
            "01010" +
            "1100110" + // 1, R
            "1101100" + // 2, R
            "1000010" + // 3, R
            "1011100" + // 4, R
            "1001110" + // 5, R
            "1000100" + // 7, R
            "101"

        assertEquals(expected, encoding.modules)
        assertEquals(95, encoding.modules.length)
        assertEquals("5901234123457", encoding.text)
    }

    @Test
    fun ean13AddsOrValidatesTheCheckDigit() {
        assertEquals(
            "5901234123457",
            BarcodeEncoder.encode(BarcodeFormat.EAN13, "590123412345").text,
        )
        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.EAN13, "5901234123450")
        }
        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.EAN13, "590123")
        }
    }

    @Test
    fun ean8EncodesSixtySevenModules() {
        val encoding = encodeLinear(BarcodeFormat.EAN8, "96385074")

        assertEquals("96385074", encoding.text)
        assertEquals(67, encoding.modules.length)
        assertTrue(encoding.modules.startsWith("101"))
        assertTrue(encoding.modules.endsWith("101"))
        assertEquals("01010", encoding.modules.substring(31, 36))
    }

    @Test
    fun upcAReusesTheEan13SymbolShape() {
        val upc = encodeLinear(BarcodeFormat.UPCA, "036000291452")
        val ean = encodeLinear(BarcodeFormat.EAN13, "0036000291452")

        assertEquals("036000291452", upc.text)
        assertEquals(BarcodeFormat.UPCA, upc.format)
        assertEquals(ean.modules, upc.modules)
        assertEquals(95, upc.modules.length)
    }

    @Test
    fun everyFormatRejectsValuesThatWouldProduceAnEmptySymbol() {
        for (format in BarcodeFormat.entries) {
            assertFailsWith<BarcodeException>("$format should reject an empty value") {
                BarcodeEncoder.encode(format, "")
            }
        }
    }

    @Test
    fun qrCodeProducesASquareMatrixWithAQuietZone() {
        val symbol = BarcodeEncoder.encode(BarcodeFormat.QR, "Thermling")

        assertEquals(BarcodeFormat.QR, symbol.format)
        assertEquals(BarcodeDimension.MATRIX, symbol.format.dimension)
        val qr = symbol as MatrixBarcodeEncoding
        assertEquals(4, qr.quietZoneModules)
        assertEquals(qr.size, qr.widthModules)
        assertEquals(qr.size, qr.heightModules)
        assertEquals(qr.size + 8, qr.totalWidthModules)
        assertEquals(qr.size + 8, qr.totalHeightModules)
        assertEquals(qr.version * 4 + 17, qr.size)
        assertEquals("Thermling", qr.text)
        assertTrue(qr.isDark(3, 3), "the top-left finder centre should be dark")
        assertTrue(qr.isDark(qr.size - 4, 3), "the top-right finder centre should be dark")
        assertTrue(qr.isDark(3, qr.size - 4), "the bottom-left finder centre should be dark")
    }

    @Test
    fun qrCodeSupportsEveryErrorCorrectionLevel() {
        val text = "Thermling label QR code ".repeat(6)
        val sizes = BarcodeErrorCorrection.entries.map { level ->
            val qr = BarcodeEncoder.encode(BarcodeFormat.QR, text, level) as MatrixBarcodeEncoding
            assertEquals(text, qr.text)
            assertEquals(qr.version * 4 + 17, qr.size)
            qr.size
        }
        assertTrue(
            sizes.last() >= sizes.first(),
            "high error correction should not need a smaller symbol than low",
        )
    }

    @Test
    fun qrCodeEncodesUtf8AndRejectsOversizedData() {
        val qr = BarcodeEncoder.encode(BarcodeFormat.QR, "Café ☕") as MatrixBarcodeEncoding
        assertEquals("Café ☕", qr.text)

        assertFailsWith<BarcodeException> {
            BarcodeEncoder.encode(BarcodeFormat.QR, "x".repeat(3000))
        }
    }

    @Test
    fun qrCodeMatchesTheReferenceImplementation() {
        val qr = BarcodeEncoder.encode(
            BarcodeFormat.QR,
            "Thermling",
            BarcodeErrorCorrection.MEDIUM,
        ) as MatrixBarcodeEncoding

        // Generated with Project Nayuki's Java QR Code generator at Medium ECC,
        // byte mode, with automatic masking and ECC boosting disabled to match.
        val expected = listOf(
            "111111100110101111111",
            "100000100110101000001",
            "101110101100001011101",
            "101110101110101011101",
            "101110101110101011101",
            "100000101011001000001",
            "111111101010101111111",
            "000000001000000000000",
            "101111100011001111100",
            "010011010101111110001",
            "111111101010101000110",
            "111110011011110001100",
            "100010100110101110010",
            "000000001110110111110",
            "111111100001011001110",
            "100000101010000101110",
            "101110101011000011001",
            "101110101111111110100",
            "101110101010101000000",
            "100000100011111001100",
            "111111101110100010010",
        )

        assertEquals(1, qr.version)
        assertEquals(expected.size, qr.size)
        for (y in expected.indices) {
            val actual = buildString {
                for (x in 0 until qr.size) append(if (qr.isDark(x, y)) '1' else '0')
            }
            assertEquals(expected[y], actual, "row $y")
        }
    }

    @Test
    fun qrCodeWithAFixedMaskMatchesTheReferenceImplementation() {
        val qr = QrCode.encode(
            "Thermling",
            BarcodeErrorCorrection.MEDIUM,
            requestedMask = 0,
        )

        // Mask 0 from Project Nayuki's Java QR Code generator at Medium ECC.
        val expected = listOf(
            "111111100000101111111",
            "100000101111001000001",
            "101110100010001011101",
            "101110100111001011101",
            "101110101000101011101",
            "100000100010101000001",
            "111111101010101111111",
            "000000000001100000000",
            "101010100101000010010",
            "100010000100001111111",
            "110001100100100110111",
            "001111001010000000010",
            "101100101000100000011",
            "000000001111000110000",
            "111111100111010111111",
            "100000100011110100000",
            "101110101101001101000",
            "101110100110001111010",
            "101110101100100110001",
            "100000100010001000010",
            "111111101000101100011",
        )

        assertEquals(1, qr.version)
        assertEquals(expected.size, qr.size)
        for (y in expected.indices) {
            val actual = buildString {
                for (x in 0 until qr.size) append(if (qr.isDark(x, y)) '1' else '0')
            }
            assertEquals(expected[y], actual, "row $y")
        }
    }

    @Test
    fun qrCodeMatchesReferenceForALargerMultiBlockSymbol() {
        val text = "Thermling multi-block QR verification with enough data to require " +
            "version ten or higher. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ " +
            "abcdefghijklmnopqrstuvwxyz"
        val qr = BarcodeEncoder.encode(
            BarcodeFormat.QR,
            text,
            BarcodeErrorCorrection.MEDIUM,
        ) as MatrixBarcodeEncoding

        // FNV-1a over the dark modules, generated by Project Nayuki's Java
        // implementation (version 9, mask 2, automatic masking without ECC boost).
        var hash = -3750763034362895579L
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                hash = hash xor if (qr.isDark(x, y)) 1L else 0L
                hash *= 1099511628211L
            }
        }

        assertEquals(9, qr.version)
        assertEquals(-533829146160347403L, hash)
    }

    @Test
    fun qrCodeMatchesReferenceAcrossAllErrorCorrectionLevels() {
        val text = "Thermling error correction verification 0123456789"
        val expected = listOf(
            Triple(BarcodeErrorCorrection.LOW, 3, 1617991994959825230L),
            Triple(BarcodeErrorCorrection.MEDIUM, 4, -9159566428723392984L),
            Triple(BarcodeErrorCorrection.QUARTILE, 5, -6146452632942997221L),
            Triple(BarcodeErrorCorrection.HIGH, 6, -7877999116358894614L),
        )

        for ((level, version, expectedHash) in expected) {
            val qr = BarcodeEncoder.encode(BarcodeFormat.QR, text, level) as MatrixBarcodeEncoding
            var hash = -3750763034362895579L
            for (y in 0 until qr.size) {
                for (x in 0 until qr.size) {
                    hash = hash xor if (qr.isDark(x, y)) 1L else 0L
                    hash *= 1099511628211L
                }
            }
            assertEquals(version, qr.version, "$level should use version $version")
            assertEquals(expectedHash, hash, "$level should match the reference implementation")
        }
    }

    private fun encodeLinear(format: BarcodeFormat, value: String): BarcodeEncoding =
        BarcodeEncoder.encode(format, value) as BarcodeEncoding
}
