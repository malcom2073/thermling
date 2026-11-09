package com.thermling

import com.thermling.model.Halftone
import com.thermling.model.ImageElement
import com.thermling.model.LabelDocument
import com.thermling.model.TextElement
import com.thermling.render.PackedMonochrome
import com.thermling.render.RasterPixels
import com.thermling.render.binarizeRgb
import com.thermling.render.effectiveHalftone
import com.thermling.render.rotateQuarterTurns
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonochromeTest {
    @Test
    fun quarterTurnRotatesClockwise() {
        val source = intArrayOf(1, 2, 3, 4, 5, 6)

        val turned = rotateQuarterTurns(source, width = 2, height = 3, quarterTurns = 1)

        assertEquals(3, turned.width)
        assertEquals(2, turned.height)
        assertContentEquals(intArrayOf(5, 3, 1, 6, 4, 2), turned.pixels)
    }

    @Test
    fun fullTurnAndZeroTurnsPreservePixels() {
        val source = intArrayOf(1, 2, 3, 4, 5, 6)

        assertContentEquals(source, rotateQuarterTurns(source, 2, 3, 0).pixels)
        assertContentEquals(source, rotateQuarterTurns(source, 2, 3, 4).pixels)
        assertContentEquals(source, rotateQuarterTurns(source, 2, 3, -4).pixels)
    }

    @Test
    fun halfTurnReversesRowsAndColumns() {
        val source = intArrayOf(1, 2, 3, 4, 5, 6)

        val turned = rotateQuarterTurns(source, width = 2, height = 3, quarterTurns = 2)

        assertEquals(2, turned.width)
        assertEquals(3, turned.height)
        assertContentEquals(intArrayOf(6, 5, 4, 3, 2, 1), turned.pixels)
    }

    @Test
    fun whitePixelsBecomeSetBits() {
        val packed = binarizeRgb(
            rgb = IntArray(8 * 2) { 0xFFFFFFFF.toInt() },
            width = 8,
            height = 2,
            halftone = Halftone.THRESHOLD,
            thermalCorrection = false,
        )

        assertEquals(1, packed.rowBytes)
        assertEquals(2, packed.data.size)
        assertTrue(packed.data.all { it == 0xFF.toByte() })
        assertTrue((0 until 8).all { packed.isSet(it, 0) })
    }

    @Test
    fun blackPixelsBecomeClearBits() {
        val packed = binarizeRgb(
            rgb = IntArray(8 * 2) { 0xFF000000.toInt() },
            width = 8,
            height = 2,
            halftone = Halftone.THRESHOLD,
            thermalCorrection = false,
        )

        assertTrue(packed.data.all { it == 0x00.toByte() })
        assertFalse((0 until 8).any { packed.isSet(it, 0) })
    }

    @Test
    fun thresholdBoundaryMatchesProductionApp() {
        val justBlack = IntArray(8) { 0xFF7F7F7F.toInt() }
        val justWhite = IntArray(8) { 0xFF808080.toInt() }

        val black = binarizeRgb(justBlack, 8, 1, Halftone.THRESHOLD, false)
        val white = binarizeRgb(justWhite, 8, 1, Halftone.THRESHOLD, false)

        assertEquals(0x00.toByte(), black.data[0])
        assertEquals(0xFF.toByte(), white.data[0])
    }

    @Test
    fun thermalCorrectionBrightensDarkPixels() {
        val gray = IntArray(8) { 0xFF646464.toInt() }

        val plain = binarizeRgb(gray, 8, 1, Halftone.THRESHOLD, thermalCorrection = false)
        val corrected = binarizeRgb(gray, 8, 1, Halftone.THRESHOLD, thermalCorrection = true)

        assertEquals(0x00.toByte(), plain.data[0])
        assertEquals(0xFF.toByte(), corrected.data[0])
    }

    @Test
    fun bitmapWidthMustBeByteAligned() {
        assertFailsWith<IllegalArgumentException> {
            binarizeRgb(IntArray(7), width = 7, height = 1, Halftone.THRESHOLD, false)
        }
    }

    @Test
    fun packedPreviewExpandsToBlackAndWhiteArgb() {
        val packed = PackedMonochrome(
            width = 8,
            height = 1,
            data = byteArrayOf(0b1010_0000.toByte()),
        )

        val argb = packed.toArgb()

        assertEquals(0xFFFFFFFF.toInt(), argb[0])
        assertEquals(0xFF000000.toInt(), argb[1])
        assertEquals(0xFFFFFFFF.toInt(), argb[2])
        assertEquals(0xFF000000.toInt(), argb[7])
    }

    @Test
    fun autoHalftoneUsesDitherOnlyForDocumentsWithImages() {
        val withImage = LabelDocument(elements = listOf(ImageElement()))
        val withText = LabelDocument(elements = listOf(TextElement()))

        assertEquals(Halftone.FLOYD, effectiveHalftone(withImage))
        assertEquals(Halftone.THRESHOLD, effectiveHalftone(withText))
        assertEquals(
            Halftone.FLOYD,
            effectiveHalftone(LabelDocument(elements = listOf(TextElement()), halftone = Halftone.FLOYD)),
        )
        assertEquals(
            Halftone.THRESHOLD,
            effectiveHalftone(
                LabelDocument(elements = listOf(ImageElement()), halftone = Halftone.THRESHOLD),
            ),
        )
    }

    @Test
    fun rasterPixelsKeepTheirOwnBuffer() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val raster = RasterPixels(2, 2, pixels)

        assertEquals(2, raster.width)
        assertEquals(2, raster.height)
        assertContentEquals(pixels, raster.pixels)
    }
}
