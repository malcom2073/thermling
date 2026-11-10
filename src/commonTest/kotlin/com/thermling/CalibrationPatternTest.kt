package com.thermling

import com.thermling.render.PackedMonochrome
import com.thermling.render.calibrationPattern
import com.thermling.render.shifted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationPatternTest {
    @Test
    fun patternMatchesRequestedDotSize() {
        val pattern = calibrationPattern(widthDots = 96, heightDots = 320, dpi = 203)

        assertEquals(96, pattern.width)
        assertEquals(320, pattern.height)
        assertEquals(96 / 8 * 320, pattern.data.size)
    }

    @Test
    fun edgeMarksShowThePrintableBandEdges() {
        val pattern = calibrationPattern(widthDots = 96, heightDots = 320, dpi = 203)
        val cx = pattern.width / 2
        val cy = pattern.height / 2

        // A clear bit is ink: the mid-edge marks must be black.
        assertFalse(pattern.isSet(0, cy), "left head edge should have an ink mark")
        assertFalse(pattern.isSet(pattern.width - 1, cy), "right head edge should have an ink mark")
        assertFalse(pattern.isSet(cx, 0), "top head edge should have an ink mark")
        assertFalse(pattern.isSet(cx, pattern.height - 1), "bottom head edge should have an ink mark")

        // The corners are deliberately blank so the frame can shift without clipping.
        assertTrue(pattern.isSet(0, 0), "top-left corner should be blank")
        assertTrue(pattern.isSet(pattern.width - 1, pattern.height - 1), "bottom-right corner should be blank")
    }

    @Test
    fun patternIsMostlyBlankPaper() {
        val pattern = calibrationPattern(widthDots = 96, heightDots = 320, dpi = 203)
        var ink = 0
        for (y in 0 until pattern.height) {
            for (x in 0 until pattern.width) {
                if (!pattern.isSet(x, y)) ink++
            }
        }

        assertTrue(ink > 0, "the pattern should contain ink")
        assertTrue(
            ink < pattern.width * pattern.height / 2,
            "the calibration pattern should leave most of the label blank",
        )
    }

    @Test
    fun shiftingMovesInkAndFillsVacatedSpace() {
        val width = 16
        val height = 4
        val rowBytes = width / 8
        val data = ByteArray(rowBytes * height) { 0xFF.toByte() }
        fun ink(x: Int, y: Int) {
            val index = y * rowBytes + (x shr 3)
            data[index] = (data[index].toInt() and (1 shl (7 - (x and 7))).inv()).toByte()
        }
        ink(3, 1)
        val original = PackedMonochrome(width, height, data)

        val moved = original.shifted(dx = 2, dy = 1)

        assertFalse(moved.isSet(5, 2), "the dot should have moved right and down")
        assertTrue(moved.isSet(3, 1), "the original position should now be blank paper")
        assertTrue(moved.isSet(0, 0), "the vacated top-left corner should be blank paper")
    }

    @Test
    fun shiftingPastTheEdgeClipsTheImage() {
        val width = 16
        val height = 4
        val data = ByteArray(width / 8 * height) { 0xFF.toByte() }
        // Ink only in the leftmost column.
        for (y in 0 until height) {
            data[y * (width / 8)] = (data[y * (width / 8)].toInt() and (1 shl 7).inv()).toByte()
        }
        val original = PackedMonochrome(width, height, data)

        val clipped = original.shifted(dx = width, dy = 0)

        for (y in 0 until height) {
            for (x in 0 until width) {
                assertTrue(clipped.isSet(x, y), "ink pushed past the right edge should be clipped at ($x,$y)")
            }
        }
    }
}
