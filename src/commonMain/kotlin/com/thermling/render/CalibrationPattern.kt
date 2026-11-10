package com.thermling.render

import com.thermling.protocol.mmToDots
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A symmetric pattern used to measure how the printable band sits on the
 * physical label.
 *
 * It contains:
 *  - small marks at the middle of each printable edge, showing where the print
 *    head's first and last dots land;
 *  - a frame 1.5 mm inside the printable band, leaving room for a ±1 mm shift;
 *  - 1 mm ruler ticks on all four sides, longer every 5 mm;
 *  - a cross at the centre;
 *  - a large square in the top-left corner and a small one in the top-right,
 *    so the orientation of the print is obvious.
 *
 * Printed on a 14 x 40 mm P21 label the edge marks are the 12 mm-wide printable
 * band; measuring the gap from the frame to each physical label edge shows the
 * head/label alignment offset. Set the calibration offset and print again to
 * confirm the frame is centred.
 */
fun calibrationPattern(widthDots: Int, heightDots: Int, dpi: Int): PackedMonochrome {
    require(widthDots > 0 && widthDots % 8 == 0) { "bitmap width must be a positive whole number of bytes" }
    require(heightDots > 0) { "bitmap height must be positive" }

    val rowBytes = widthDots / 8
    // A set bit prints white; start with a blank page and clear bits for ink.
    val data = ByteArray(rowBytes * heightDots) { 0xFF.toByte() }

    fun black(x: Int, y: Int) {
        if (x !in 0 until widthDots || y !in 0 until heightDots) return
        val index = y * rowBytes + (x shr 3)
        data[index] = (data[index].toInt() and (1 shl (7 - (x and 7))).inv()).toByte()
    }

    fun hLine(y: Int, x0: Int, x1: Int) {
        for (x in min(x0, x1)..max(x0, x1)) black(x, y)
    }

    fun vLine(x: Int, y0: Int, y1: Int) {
        for (y in min(y0, y1)..max(y0, y1)) black(x, y)
    }

    fun frame(inset: Int, thickness: Int) {
        val left = inset
        val right = widthDots - inset - 1
        val top = inset
        val bottom = heightDots - inset - 1
        if (right <= left || bottom <= top) return
        repeat(thickness) { t ->
            hLine(top + t, left, right)
            hLine(bottom - t, left, right)
            vLine(left + t, top, bottom)
            vLine(right - t, top, bottom)
        }
    }

    fun fill(left: Int, top: Int, size: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) black(left + x, top + y)
        }
    }

    val step = max(1, mmToDots(1.0, dpi).roundToInt())
    val cx = widthDots / 2
    val cy = heightDots / 2

    // Marks at the middle of the printable band edges. These show the head's
    // first and last dot even when the pattern itself is shifted.
    val edgeMark = max(2, step)
    vLine(0, cy - edgeMark, cy + edgeMark)
    vLine(widthDots - 1, cy - edgeMark, cy + edgeMark)
    hLine(0, cx - edgeMark, cx + edgeMark)
    hLine(heightDots - 1, cx - edgeMark, cx + edgeMark)

    // Frame 1.5 mm inside the band, so a ±1 mm calibration shift does not clip
    // it against the edge of the print head.
    val inset = max(2, mmToDots(1.5, dpi).roundToInt())
    frame(inset, 2)

    val interiorLeft = inset + 3
    val interiorRight = widthDots - inset - 4
    val interiorTop = inset + 3
    val interiorBottom = heightDots - inset - 4

    if (interiorRight > interiorLeft && interiorBottom > interiorTop) {
        // Ruler ticks down both sides.
        var mm = 1
        while (true) {
            val y = inset + mm * step
            if (y > interiorBottom) break
            val len = if (mm % 5 == 0) {
                min(step * 4, (interiorRight - interiorLeft) / 2)
            } else {
                step + 2
            }
            hLine(y, interiorLeft, interiorLeft + len)
            hLine(y, interiorRight - len, interiorRight)
            mm++
        }
        // Ruler ticks along the top and bottom.
        var across = 1
        while (true) {
            val x = inset + across * step
            if (x > interiorRight) break
            val len = if (across % 5 == 0) {
                min(step * 4, (interiorBottom - interiorTop) / 2)
            } else {
                step + 2
            }
            vLine(x, interiorTop, interiorTop + len)
            vLine(x, interiorBottom - len, interiorBottom)
            across++
        }
    }

    // Centre cross.
    val arm = max(step, min(widthDots, heightDots) / 8)
    repeat(2) { t ->
        hLine(cy + t, cx - arm, cx + arm)
        vLine(cx + t, cy - arm, cy + arm)
    }

    // Orientation markers: a large square top-left, a small one top-right.
    val large = max(2, 2 * step)
    val small = max(1, step)
    fill(inset + 4, inset + 4, large)
    fill(widthDots - inset - 4 - small, inset + 4, small)

    return PackedMonochrome(widthDots, heightDots, data)
}
