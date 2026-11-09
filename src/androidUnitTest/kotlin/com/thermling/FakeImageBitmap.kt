package com.thermling

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/**
 * Headless [ImageBitmap] for shared tests that run on the Android JVM unit-test
 * classpath, where `android.graphics` is only a stub. Desktop tests keep using
 * a real bitmap so Compose can draw the preview.
 */
class FakeImageBitmap(
    override val width: Int,
    override val height: Int,
) : ImageBitmap {
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val hasAlpha: Boolean get() = false
    override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) {
        for (row in 0 until height) {
            val offset = bufferOffset + row * stride
            for (column in 0 until width) {
                buffer[offset + column] = 0xFFFFFFFF.toInt()
            }
        }
    }

    override fun prepareToDraw() = Unit
}
