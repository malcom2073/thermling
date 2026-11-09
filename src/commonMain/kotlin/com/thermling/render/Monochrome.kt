package com.thermling.render

import com.thermling.model.Halftone
import kotlin.math.roundToInt

/** A raster of ARGB pixels used between the platform canvas and packing. */
data class RasterPixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/** A printer-ready, row-major 1-bit bitmap. A set bit prints white. */
data class PackedMonochrome(
    val width: Int,
    val height: Int,
    val data: ByteArray,
) {
    val rowBytes: Int get() = width / 8

    fun isSet(x: Int, y: Int): Boolean {
        val byte = data[y * rowBytes + (x shr 3)].toInt()
        return byte and (1 shl (7 - (x and 7))) != 0
    }

    /** Expands the packed bitmap back to black/white ARGB pixels for a preview. */
    fun toArgb(): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        if (isSet(x, y)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}

/**
 * Rotates ARGB pixels by exact quarter turns. This matches the pixel mapping
 * used by the desktop renderer so even-sized images do not gain a half-pixel
 * white edge when a landscape label is turned into the print-head orientation.
 */
fun rotateQuarterTurns(source: IntArray, width: Int, height: Int, quarterTurns: Int): RasterPixels {
    val turns = ((quarterTurns % 4) + 4) % 4
    if (turns == 0) return RasterPixels(width, height, source.copyOf())

    val swap = turns % 2 == 1
    val targetWidth = if (swap) height else width
    val targetHeight = if (swap) width else height
    val target = IntArray(targetWidth * targetHeight)

    for (y in 0 until targetHeight) {
        for (x in 0 until targetWidth) {
            val sourceX = when (turns) {
                1 -> y
                2 -> width - 1 - x
                else -> width - 1 - y
            }
            val sourceY = when (turns) {
                1 -> height - 1 - x
                2 -> height - 1 - y
                else -> x
            }
            target[y * targetWidth + x] = source[sourceY * width + sourceX]
        }
    }
    return RasterPixels(targetWidth, targetHeight, target)
}

/**
 * Converts ARGB pixels to the luminance bitmap sent to the printer.
 * Set bits are white, matching the P21 protocol.
 */
fun binarizeRgb(
    rgb: IntArray,
    width: Int,
    height: Int,
    halftone: Halftone,
    thermalCorrection: Boolean,
): PackedMonochrome {
    require(width % 8 == 0) { "Bitmap width must be a multiple of 8 dots" }
    require(rgb.size == width * height) { "Pixel buffer does not match the bitmap size" }

    val pixels = IntArray(rgb.size) { index ->
        val value = rgb[index]
        ((value ushr 16 and 0xFF) * 77 + (value ushr 8 and 0xFF) * 150 + (value and 0xFF) * 29) ushr 8
    }

    if (thermalCorrection) {
        val lut = thermalLut()
        for (index in pixels.indices) pixels[index] = lut[pixels[index]]
    }

    if (halftone == Halftone.THRESHOLD) {
        for (index in pixels.indices) {
            pixels[index] = if (pixels[index] >= THRESHOLD) 255 else 0
        }
    } else {
        floydSteinberg(pixels, width, height)
    }

    val rowBytes = width / 8
    val packed = ByteArray(rowBytes * height)
    for (y in 0 until height) {
        val offset = y * rowBytes
        for (x in 0 until width) {
            if (pixels[y * width + x] != 0) {
                packed[offset + (x shr 3)] =
                    (packed[offset + (x shr 3)].toInt() or (1 shl (7 - (x and 7)))).toByte()
            }
        }
    }
    return PackedMonochrome(width, height, packed)
}

/** Effective halftone mode for a document, matching the PySide6 production app. */
fun effectiveHalftone(document: com.thermling.model.LabelDocument): Halftone =
    if (document.halftone == Halftone.AUTO) {
        if (document.hasImages()) Halftone.FLOYD else Halftone.THRESHOLD
    } else {
        document.halftone
    }

/** Scales a millimetre value to dots at [dpi], rounded like the print renderers. */
fun mmToDotsRounded(mm: Double, dpi: Int): Int = (mm * dpi / 25.4).roundToInt()

private fun floydSteinberg(pixels: IntArray, width: Int, height: Int) {
    for (y in 0 until height) {
        val rowOffset = y * width
        val nextOffset = (y + 1) * width
        for (x in 0 until width) {
            val old = pixels[rowOffset + x]
            val new = if (old >= THRESHOLD) 255 else 0
            pixels[rowOffset + x] = new
            val error = old - new
            if (error == 0) continue
            if (x + 1 < width) {
                pixels[rowOffset + x + 1] =
                    clamp(pixels[rowOffset + x + 1] + floorDiv(error * 7, 16))
            }
            if (y + 1 < height) {
                if (x > 0) {
                    pixels[nextOffset + x - 1] =
                        clamp(pixels[nextOffset + x - 1] + floorDiv(error * 3, 16))
                }
                pixels[nextOffset + x] =
                    clamp(pixels[nextOffset + x] + floorDiv(error * 5, 16))
                if (x + 1 < width) {
                    pixels[nextOffset + x + 1] =
                        clamp(pixels[nextOffset + x + 1] + floorDiv(error, 16))
                }
            }
        }
    }
}

private fun floorDiv(value: Int, divisor: Int): Int {
    val quotient = value / divisor
    return if (value % divisor != 0 && (value xor divisor) < 0) quotient - 1 else quotient
}

private fun clamp(value: Int): Int = value.coerceIn(0, 255)

private fun thermalLut(): IntArray = IntArray(256) { value ->
    val corrected = if (value <= 16) {
        value * 5.0625
    } else {
        81.0 + (value - 16) * 0.728
    }
    corrected.toInt().coerceIn(0, 255)
}

private const val THRESHOLD = 128
