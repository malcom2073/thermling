package com.thermling

import com.thermling.barcode.BarcodeEncoder
import com.thermling.barcode.BarcodeException
import com.thermling.barcode.BarcodeFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BarcodeTest {
    @Test
    fun code128UsesSubsetCForEvenDigitData() {
        val encoding = BarcodeEncoder.encode(BarcodeFormat.CODE128, "12345670")

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
            val encoding = BarcodeEncoder.encode(BarcodeFormat.CODE128, code.toChar().toString())
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
        val encoding = BarcodeEncoder.encode(BarcodeFormat.CODE39, "ab-12")

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
        val encoding = BarcodeEncoder.encode(BarcodeFormat.EAN13, "5901234123457")

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
        val encoding = BarcodeEncoder.encode(BarcodeFormat.EAN8, "96385074")

        assertEquals("96385074", encoding.text)
        assertEquals(67, encoding.modules.length)
        assertTrue(encoding.modules.startsWith("101"))
        assertTrue(encoding.modules.endsWith("101"))
        assertEquals("01010", encoding.modules.substring(31, 36))
    }

    @Test
    fun upcAReusesTheEan13SymbolShape() {
        val upc = BarcodeEncoder.encode(BarcodeFormat.UPCA, "036000291452")
        val ean = BarcodeEncoder.encode(BarcodeFormat.EAN13, "0036000291452")

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
}
