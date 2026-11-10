package com.thermling.render

import com.thermling.barcode.BarcodeEncoder
import com.thermling.barcode.BarcodeException
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AwtLabelRenderer : LabelRenderer {
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

        val pageWidthDots = if (landscape) max(1, mmToDots(document.lengthMm, dpi).roundToInt()) else widthDots
        val pageHeightDots = if (landscape) widthDots else heightDots

        val canvas = BufferedImage(pageWidthDots, pageHeightDots, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = canvas.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, pageWidthDots, pageHeightDots)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON,
            )
            graphics.color = Color.BLACK
            for (element in document.elements) {
                val rect = elementRectDots(element.xMm, element.yMm, element.widthMm, element.heightMm, dpi)
                when (element) {
                    is TextElement -> drawText(graphics, element, rect, dpi)
                    is ImageElement -> drawImage(graphics, element, rect)
                    is BarcodeElement -> drawBarcode(graphics, element, rect, dpi)
                }
            }
        } finally {
            graphics.dispose()
        }

        val halftone = effectiveHalftone(document)
        val page = canvas.getRGB(0, 0, pageWidthDots, pageHeightDots, null, 0, pageWidthDots)
        val printerPixels = if (landscape) {
            rotateQuarterTurns(page, pageWidthDots, pageHeightDots, 3)
        } else {
            RasterPixels(pageWidthDots, pageHeightDots, page)
        }
        val packed = binarizeRgb(
            printerPixels.pixels,
            printerPixels.width,
            printerPixels.height,
            halftone,
            document.thermalCorrection,
        ).shifted(
            dx = mmToDots(offsetXMm, dpi).roundToInt(),
            dy = mmToDots(offsetYMm, dpi).roundToInt(),
        )
        val previewPixels = if (landscape) {
            rotateQuarterTurns(packed.toArgb(), packed.width, packed.height, 1)
        } else {
            RasterPixels(packed.width, packed.height, packed.toArgb())
        }
        val preview = BufferedImage(
            previewPixels.width,
            previewPixels.height,
            BufferedImage.TYPE_INT_RGB,
        ).apply {
            setRGB(
                0,
                0,
                previewPixels.width,
                previewPixels.height,
                previewPixels.pixels,
                0,
                previewPixels.width,
            )
        }
        return RenderResult(
            widthDots = widthDots,
            heightDots = heightDots,
            data = packed.data,
            preview = preview.toComposeImageBitmap(),
            dpi = dpi,
            printableWidthMm = printable,
            halftone = halftone.value,
        )
    }

    private fun elementRectDots(
        xMm: Double,
        yMm: Double,
        widthMm: Double,
        heightMm: Double,
        dpi: Int,
    ): Rectangle2D.Double {
        val scale = dpi / 25.4
        return Rectangle2D.Double(
            xMm * scale,
            yMm * scale,
            max(1.0, widthMm * scale),
            max(1.0, heightMm * scale),
        )
    }

    private fun drawText(
        graphics: Graphics2D,
        element: TextElement,
        rect: Rectangle2D.Double,
        dpi: Int,
    ) {
        val pixelSize = max(1, (element.fontSizePt * dpi / 72.0).roundToInt())
        val style = (if (element.bold) Font.BOLD else Font.PLAIN) or
            (if (element.italic) Font.ITALIC else Font.PLAIN)
        val family = element.fontFamily.takeIf { it.isNotBlank() } ?: "SansSerif"
        val font = Font(family, style, pixelSize)
        graphics.font = font
        val metrics = graphics.fontMetrics
        val lines = wrapText(element.text, metrics::stringWidth, rect.width)
        val lineHeight = metrics.height * element.lineSpacing
        val textHeight = lineHeight * lines.size
        val verticalOffset = when (element.valign) {
            VerticalAlign.TOP -> 0.0
            VerticalAlign.MIDDLE -> max(0.0, (rect.height - textHeight) / 2.0)
            VerticalAlign.BOTTOM -> max(0.0, rect.height - textHeight)
        }

        val oldClip = graphics.clip
        graphics.clip(rect)
        var baseline = rect.y + metrics.ascent + verticalOffset
        for (line in lines) {
            val lineWidth = metrics.stringWidth(line).toDouble()
            val x = when (element.align) {
                com.thermling.model.HorizontalAlign.LEFT -> rect.x
                com.thermling.model.HorizontalAlign.CENTER -> rect.x + (rect.width - lineWidth) / 2.0
                com.thermling.model.HorizontalAlign.RIGHT -> rect.x + rect.width - lineWidth
            }
            graphics.drawString(line, x.toFloat(), baseline.toFloat())
            if (element.underline && line.isNotEmpty()) {
                val y = (baseline + 1.0).toFloat()
                graphics.drawLine(x.toInt(), y.toInt(), (x + lineWidth).toInt(), y.toInt())
            }
            baseline += lineHeight
        }
        graphics.clip = oldClip
    }

    private fun wrapText(text: String, widthOf: (String) -> Int, maxWidth: Double): List<String> {
        if (maxWidth <= 1.0) return listOf("")
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

    private fun drawBarcode(
        graphics: Graphics2D,
        element: BarcodeElement,
        rect: Rectangle2D.Double,
        dpi: Int,
    ) {
        val encoding = try {
            BarcodeEncoder.encode(element.format, element.value)
        } catch (error: BarcodeException) {
            throw RenderError(error.message ?: "Invalid barcode", error)
        }

        val textHeight = if (element.showText) {
            min(rect.height * 0.24, 4.0 * dpi / 25.4)
        } else {
            0.0
        }
        val gap = if (element.showText) max(1.0, dpi / 203.0) else 0.0
        val barHeight = max(1.0, rect.height - textHeight - gap)
        val moduleWidth = rect.width / encoding.totalModules
        val left = rect.x + encoding.quietZoneModules * moduleWidth

        var index = 0
        while (index < encoding.modules.length) {
            if (encoding.modules[index] == '1') {
                val start = index
                while (index < encoding.modules.length && encoding.modules[index] == '1') {
                    index++
                }
                val x = (left + start * moduleWidth).roundToInt()
                val end = (left + index * moduleWidth).roundToInt().coerceAtLeast(x + 1)
                graphics.fillRect(x, rect.y.roundToInt(), end - x, barHeight.roundToInt())
            } else {
                index++
            }
        }

        if (element.showText && encoding.text.isNotEmpty()) {
            val byHeight = (textHeight * 0.76).roundToInt()
            val byWidth = (rect.width / encoding.text.length / 0.62).toInt()
            val fontPx = max(1, min(byHeight, byWidth))
            graphics.font = Font("Monospaced", Font.PLAIN, fontPx)
            val metrics = graphics.fontMetrics
            val baseline = rect.y + barHeight + gap +
                (textHeight - metrics.height) / 2.0 + metrics.ascent
            val textWidth = metrics.stringWidth(encoding.text).toDouble()
            val textX = rect.x + (rect.width - textWidth) / 2.0
            val oldClip = graphics.clip
            graphics.clip(rect)
            graphics.drawString(encoding.text, textX.toFloat(), baseline.toFloat())
            graphics.clip = oldClip
        }
    }

    private fun drawImage(graphics: Graphics2D, element: ImageElement, rect: Rectangle2D.Double) {
        val image = decodeImage(element.pngBase64)
        val (target, source) = imageTargetRect(image, rect, element.fit)
        val oldClip = graphics.clip
        graphics.clip(rect)
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.drawImage(
            image,
            target.x.toInt(),
            target.y.toInt(),
            (target.x + target.width).toInt(),
            (target.y + target.height).toInt(),
            source.x.toInt(),
            source.y.toInt(),
            (source.x + source.width).toInt(),
            (source.y + source.height).toInt(),
            null,
        )
        graphics.clip = oldClip
    }

    private fun decodeImage(pngBase64: String): BufferedImage {
        if (pngBase64.isEmpty()) throw RenderError("This image element has no image data")
        val bytes = try {
            Base64.getDecoder().decode(pngBase64)
        } catch (error: IllegalArgumentException) {
            throw RenderError("Image data is not valid base64", error)
        }
        val decoded = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (error: Exception) {
            throw RenderError("Image data could not be decoded", error)
        } ?: throw RenderError("Image data could not be decoded")
        val gray = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        graphics.drawImage(decoded, 0, 0, null)
        graphics.dispose()
        return gray
    }

    private fun imageTargetRect(
        source: BufferedImage,
        rect: Rectangle2D.Double,
        fit: ImageFit,
    ): Pair<Rectangle2D.Double, Rectangle2D.Double> {
        val sourceRect = Rectangle2D.Double(0.0, 0.0, source.width.toDouble(), source.height.toDouble())
        if (fit == ImageFit.STRETCH || source.width == 0 || source.height == 0) {
            return rect to sourceRect
        }
        val scaleX = rect.width / source.width
        val scaleY = rect.height / source.height
        val scale = if (fit == ImageFit.COVER) max(scaleX, scaleY) else min(scaleX, scaleY)
        val width = source.width * scale
        val height = source.height * scale
        val target = Rectangle2D.Double(
            rect.x + (rect.width - width) / 2.0,
            rect.y + (rect.height - height) / 2.0,
            width,
            height,
        )
        return target to sourceRect
    }

}
