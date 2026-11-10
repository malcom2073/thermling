package com.thermling.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import com.thermling.barcode.BarcodeEncoder
import com.thermling.barcode.BarcodeEncoding
import com.thermling.barcode.BarcodeException
import com.thermling.barcode.MatrixBarcodeEncoding
import com.thermling.model.BarcodeElement
import com.thermling.model.ImageElement
import com.thermling.model.ImageFit
import com.thermling.model.LabelDocument
import com.thermling.model.Orientation
import com.thermling.model.TextElement
import com.thermling.model.VerticalAlign
import com.thermling.protocol.bitmapWidthDots
import com.thermling.protocol.mmToDots
import com.thermling.protocol.printableWidthMm
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android renderer using `android.graphics`. It draws the page at the printer's
 * dot resolution and then hands the pixels to the shared binarisation and
 * rotation code, so the print bitmap is identical to the desktop renderer.
 */
class AndroidLabelRenderer : LabelRenderer {
    override fun render(
        document: LabelDocument,
        dpi: Int,
        headWidthMm: Double,
        offsetXMm: Double,
        offsetYMm: Double,
    ): RenderResult {
        document.validate()
        if (dpi !in 50..1200) throw RenderError("Unsupported DPI: $dpi")

        val printable = printableWidthMm(document.widthMm, headWidthMm)
        val widthDots = bitmapWidthDots(printable, dpi)
        val heightDots = max(1, mmToDots(document.lengthMm, dpi).roundToInt())
        val landscape = document.orientation == Orientation.LANDSCAPE

        val pageWidthDots = if (landscape) {
            max(1, mmToDots(document.lengthMm, dpi).roundToInt())
        } else {
            widthDots
        }
        val pageHeightDots = if (landscape) widthDots else heightDots

        val bitmap = Bitmap.createBitmap(pageWidthDots, pageHeightDots, Bitmap.Config.ARGB_8888)
        val pixels: IntArray
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            for (element in document.elements) {
                val rect = RectF(
                    (element.xMm * dpi / 25.4).toFloat(),
                    (element.yMm * dpi / 25.4).toFloat(),
                    ((element.xMm + max(1.0, element.widthMm)) * dpi / 25.4).toFloat(),
                    ((element.yMm + max(1.0, element.heightMm)) * dpi / 25.4).toFloat(),
                )
                when (element) {
                    is TextElement -> drawText(canvas, element, rect, dpi)
                    is ImageElement -> drawImage(canvas, element, rect)
                    is BarcodeElement -> drawBarcode(canvas, element, rect, dpi)
                }
            }
            pixels = IntArray(pageWidthDots * pageHeightDots)
            bitmap.getPixels(pixels, 0, pageWidthDots, 0, 0, pageWidthDots, pageHeightDots)
        } finally {
            bitmap.recycle()
        }

        val printer = if (landscape) {
            rotateQuarterTurns(pixels, pageWidthDots, pageHeightDots, 3)
        } else {
            RasterPixels(pageWidthDots, pageHeightDots, pixels)
        }
        val packed = binarizeRgb(
            printer.pixels,
            printer.width,
            printer.height,
            effectiveHalftone(document),
            document.thermalCorrection,
        ).shifted(
            dx = mmToDots(offsetXMm, dpi).roundToInt(),
            dy = mmToDots(offsetYMm, dpi).roundToInt(),
        )
        val preview = if (landscape) {
            rotateQuarterTurns(packed.toArgb(), packed.width, packed.height, 1)
        } else {
            RasterPixels(packed.width, packed.height, packed.toArgb())
        }
        val previewBitmap = Bitmap.createBitmap(
            preview.width,
            preview.height,
            Bitmap.Config.ARGB_8888,
        )
        previewBitmap.setPixels(
            preview.pixels,
            0,
            preview.width,
            0,
            0,
            preview.width,
            preview.height,
        )
        return RenderResult(
            widthDots = widthDots,
            heightDots = heightDots,
            data = packed.data,
            preview = previewBitmap.asImageBitmap(),
            dpi = dpi,
            printableWidthMm = printable,
            halftone = effectiveHalftone(document).value,
        )
    }

    private fun drawText(canvas: Canvas, element: TextElement, rect: RectF, dpi: Int) {
        val pixelSize = max(1f, (element.fontSizePt * dpi / 72.0).toFloat())
        val typeface = Typeface.create(
            androidFontFamily(element.fontFamily),
            when {
                element.bold && element.italic -> Typeface.BOLD_ITALIC
                element.bold -> Typeface.BOLD
                element.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            this.typeface = typeface
            textSize = pixelSize
            textAlign = Paint.Align.LEFT
        }
        val metrics = paint.fontMetrics
        val lines = wrapText(element.text, { paint.measureText(it) }, rect.width())
        val lineHeight =
            (metrics.descent - metrics.ascent + metrics.leading) * element.lineSpacing.toFloat()
        val textHeight = lineHeight * lines.size
        val verticalOffset = when (element.valign) {
            VerticalAlign.TOP -> 0f
            VerticalAlign.MIDDLE -> max(0f, (rect.height() - textHeight) / 2f)
            VerticalAlign.BOTTOM -> max(0f, rect.height() - textHeight)
        }

        canvas.save()
        canvas.clipRect(rect)
        var baseline = rect.top - metrics.ascent + verticalOffset
        for (line in lines) {
            val lineWidth = paint.measureText(line)
            val x = when (element.align) {
                com.thermling.model.HorizontalAlign.LEFT -> rect.left
                com.thermling.model.HorizontalAlign.CENTER -> rect.left + (rect.width() - lineWidth) / 2f
                com.thermling.model.HorizontalAlign.RIGHT -> rect.right - lineWidth
            }
            canvas.drawText(line, x, baseline, paint)
            if (element.underline && line.isNotEmpty()) {
                canvas.drawLine(x, baseline + 1f, x + lineWidth, baseline + 1f, paint)
            }
            baseline += lineHeight
        }
        canvas.restore()
    }

    private fun wrapText(text: String, widthOf: (String) -> Float, maxWidth: Float): List<String> {
        if (maxWidth <= 1f) return listOf("")
        val result = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                result += ""
                continue
            }
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                result += ""
                continue
            }
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (widthOf(candidate) <= maxWidth || line.isEmpty()) {
                    if (line.isEmpty() && widthOf(word) > maxWidth) {
                        var piece = ""
                        for (character in word) {
                            val next = piece + character
                            if (piece.isNotEmpty() && widthOf(next) > maxWidth) {
                                result += piece
                                piece = character.toString()
                            } else {
                                piece = next
                            }
                        }
                        line = piece
                    } else {
                        line = candidate
                    }
                } else {
                    result += line
                    line = word
                }
            }
            if (line.isNotEmpty()) result += line
        }
        return if (result.isEmpty()) listOf("") else result
    }

    private fun drawBarcode(canvas: Canvas, element: BarcodeElement, rect: RectF, dpi: Int) {
        val encoding = try {
            BarcodeEncoder.encode(element.format, element.value, element.errorCorrection)
        } catch (error: BarcodeException) {
            throw RenderError(error.message ?: "Invalid barcode", error)
        }

        if (encoding is MatrixBarcodeEncoding) {
            drawMatrixBarcode(canvas, encoding, rect)
            return
        }
        val linear = encoding as BarcodeEncoding

        val textHeight = if (element.showText) {
            min(rect.height() * 0.24, 4.0 * dpi / 25.4).toFloat()
        } else {
            0f
        }
        val gap = if (element.showText) max(1f, dpi / 203f) else 0f
        val barHeight = max(1f, rect.height() - textHeight - gap)
        val moduleWidth = rect.width() / linear.totalModules
        val left = rect.left + linear.quietZoneModules * moduleWidth

        val barPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
        }
        var index = 0
        while (index < linear.modules.length) {
            if (linear.modules[index] == '1') {
                val start = index
                while (index < linear.modules.length && linear.modules[index] == '1') {
                    index++
                }
                val x = left + start * moduleWidth
                val end = max(x + 1f, left + index * moduleWidth)
                canvas.drawRect(x, rect.top, end, rect.top + barHeight, barPaint)
            } else {
                index++
            }
        }

        if (element.showText && linear.text.isNotEmpty()) {
            val byHeight = (textHeight * 0.76f).toInt()
            val byWidth = (rect.width() / linear.text.length / 0.62f).toInt()
            val fontPx = max(1, min(byHeight, byWidth)).toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.MONOSPACE
                textSize = fontPx
                textAlign = Paint.Align.LEFT
            }
            val metrics = paint.fontMetrics
            val baseline = rect.top + barHeight + gap +
                (textHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            val textWidth = paint.measureText(linear.text)
            val textX = rect.left + (rect.width() - textWidth) / 2f
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawText(linear.text, textX, baseline, paint)
            canvas.restore()
        }
    }

    private fun drawMatrixBarcode(
        canvas: Canvas,
        encoding: MatrixBarcodeEncoding,
        rect: RectF,
    ) {
        val totalWidth = encoding.totalWidthModules
        val totalHeight = encoding.totalHeightModules
        val moduleSize = min(rect.width() / totalWidth, rect.height() / totalHeight)
        val drawnWidth = moduleSize * totalWidth
        val drawnHeight = moduleSize * totalHeight
        val left = rect.left + (rect.width() - drawnWidth) / 2f
        val top = rect.top + (rect.height() - drawnHeight) / 2f
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
        }

        for (y in 0 until encoding.heightModules) {
            var x = 0
            while (x < encoding.widthModules) {
                if (!encoding.isDark(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < encoding.widthModules && encoding.isDark(x, y)) x++
                val startX = left + (encoding.quietZoneModules + start) * moduleSize
                val endX = max(startX + 1f, left + (encoding.quietZoneModules + x) * moduleSize)
                val startY = top + (encoding.quietZoneModules + y) * moduleSize
                val endY = max(startY + 1f, top + (encoding.quietZoneModules + y + 1) * moduleSize)
                canvas.drawRect(startX, startY, endX, endY, paint)
            }
        }
    }

    private fun drawImage(canvas: Canvas, element: ImageElement, rect: RectF) {
        val image = decodeImage(element.pngBase64)
        try {
            val (target, source) = imageTargetRect(image.width, image.height, rect, element.fit)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawBitmap(image, source, target, paint)
            canvas.restore()
        } finally {
            image.recycle()
        }
    }

    private fun decodeImage(pngBase64: String): Bitmap {
        if (pngBase64.isEmpty()) throw RenderError("This image element has no image data")
        val bytes = try {
            Base64.decode(pngBase64, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw RenderError("Image data is not valid base64", error)
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw RenderError("Image data could not be decoded")
        } catch (error: Exception) {
            if (error is RenderError) throw error
            throw RenderError("Image data could not be decoded", error)
        }
    }

    private fun imageTargetRect(
        sourceWidth: Int,
        sourceHeight: Int,
        rect: RectF,
        fit: ImageFit,
    ): Pair<RectF, Rect> {
        val source = Rect(0, 0, sourceWidth, sourceHeight)
        if (fit == ImageFit.STRETCH || sourceWidth == 0 || sourceHeight == 0) {
            return rect to source
        }
        val scaleX = rect.width() / sourceWidth
        val scaleY = rect.height() / sourceHeight
        val scale = if (fit == ImageFit.COVER) max(scaleX, scaleY) else min(scaleX, scaleY)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val target = RectF(
            rect.left + (rect.width() - width) / 2f,
            rect.top + (rect.height() - height) / 2f,
            rect.left + (rect.width() + width) / 2f,
            rect.top + (rect.height() + height) / 2f,
        )
        return target to source
    }

    private fun androidFontFamily(family: String): String {
        val lower = family.lowercase()
        return when {
            lower.contains("mono") -> "monospace"
            lower.contains("serif") && !lower.contains("sans") -> "serif"
            else -> "sans-serif"
        }
    }
}
