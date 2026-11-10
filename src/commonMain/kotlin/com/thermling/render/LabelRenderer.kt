package com.thermling.render

import androidx.compose.ui.graphics.ImageBitmap
import com.thermling.model.LabelDocument

class RenderError(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RenderResult(
    val widthDots: Int,
    val heightDots: Int,
    val data: ByteArray,
    val preview: ImageBitmap,
    val dpi: Int,
    val printableWidthMm: Double,
    val halftone: String,
) {
    val rowBytes: Int get() = widthDots / 8
    val sizeText: String get() = "$widthDots × $heightDots dots · ${data.size} bytes"
}

fun interface LabelRenderer {
    fun render(
        document: LabelDocument,
        dpi: Int,
        headWidthMm: Double,
        offsetXMm: Double,
        offsetYMm: Double,
    ): RenderResult
}
