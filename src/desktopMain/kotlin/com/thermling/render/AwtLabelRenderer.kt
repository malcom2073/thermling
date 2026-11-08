package com.thermling.render

import com.thermling.model.Halftone
import com.thermling.model.ImageElement
import com.thermling.model.ImageFit
import com.thermling.model.LabelDocument
import com.thermling.model.Orientation
import com.thermling.model.TextElement
import com.thermling.model.VerticalAlign
import com.thermling.protocol.DEFAULT_DPI
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RenderError(message: String, cause: Throwable? = null) : Exception(message, cause)

class AwtLabelRenderer : LabelRenderer {
    override fun render(document: LabelDocument, dpi: Int, headWidthMm: Double): RenderResult {
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
                }
            }
        } finally {
            graphics.dispose()
        }

        val halftone = effectiveHalftone(document)
        val printerCanvas = if (landscape) rotate(canvas, -90.0) else canvas
        val (packed, mono) = binarize(printerCanvas, halftone, document.thermalCorrection)
        val preview = if (landscape) rotate(mono, 90.0) else mono
        return RenderResult(
            widthDots = widthDots,
            heightDots = heightDots,
            data = packed,
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

    /**
     * Exact quarter-turn rotation for the only angles used by the renderer
     * (±90°). A general affine rotation shifts even-sized images by half a
     * pixel and can introduce a white edge on the print bitmap; this loop
     * matches Qt's FastTransformation pixel mapping instead.
     */
    private fun rotate(source: BufferedImage, degrees: Double): BufferedImage {
        val quarterTurns = (((degrees / 90.0).roundToInt() % 4) + 4) % 4
        if (quarterTurns == 0) return source

        val quarterTurn = quarterTurns % 2 == 1
        val width = if (quarterTurn) source.height else source.width
        val height = if (quarterTurn) source.width else source.height
        val target = BufferedImage(width, height, source.type)
        val sourceRaster = source.raster
        val targetRaster = target.raster
        val gray = sourceRaster.numBands == 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = when (quarterTurns) {
                    1 -> y
                    2 -> source.width - 1 - x
                    else -> source.width - 1 - y
                }
                val sourceY = when (quarterTurns) {
                    1 -> source.height - 1 - x
                    2 -> source.height - 1 - y
                    else -> x
                }
                if (gray) {
                    targetRaster.setSample(x, y, 0, sourceRaster.getSample(sourceX, sourceY, 0))
                } else {
                    target.setRGB(x, y, source.getRGB(sourceX, sourceY))
                }
            }
        }
        return target
    }

    private fun effectiveHalftone(document: LabelDocument): Halftone =
        if (document.halftone == Halftone.AUTO) {
            if (document.hasImages()) Halftone.FLOYD else Halftone.THRESHOLD
        } else {
            document.halftone
        }

    private fun binarize(
        gray: BufferedImage,
        mode: Halftone,
        thermalCorrection: Boolean,
    ): Pair<ByteArray, BufferedImage> {
        val width = gray.width
        val height = gray.height
        if (width % 8 != 0) throw RenderError("Bitmap width must be a multiple of 8 dots")
        val rowBytes = width / 8
        val pixels = IntArray(width * height)
        val rgb = gray.getRGB(0, 0, width, height, null, 0, width)
        for (index in rgb.indices) {
            val value = rgb[index]
            pixels[index] = ((value ushr 16 and 0xFF) * 77 + (value ushr 8 and 0xFF) * 150 + (value and 0xFF) * 29) ushr 8
        }

        if (thermalCorrection) {
            val lut = thermalLut()
            for (index in pixels.indices) pixels[index] = lut[pixels[index]]
        }

        if (mode == Halftone.THRESHOLD) {
            for (index in pixels.indices) {
                pixels[index] = if (pixels[index] >= THRESHOLD) 255 else 0
            }
        } else {
            floydSteinberg(pixels, width, height)
        }

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

        val mono = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val set = packed[y * rowBytes + (x shr 3)].toInt() and (1 shl (7 - (x and 7))) != 0
                mono.setRGB(x, y, if (set) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
        }
        return packed to mono
    }

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
                    pixels[rowOffset + x + 1] = clamp(pixels[rowOffset + x + 1] + Math.floorDiv(error * 7, 16))
                }
                if (y + 1 < height) {
                    if (x > 0) {
                        pixels[nextOffset + x - 1] = clamp(pixels[nextOffset + x - 1] + Math.floorDiv(error * 3, 16))
                    }
                    pixels[nextOffset + x] = clamp(pixels[nextOffset + x] + Math.floorDiv(error * 5, 16))
                    if (x + 1 < width) {
                        pixels[nextOffset + x + 1] = clamp(pixels[nextOffset + x + 1] + Math.floorDiv(error, 16))
                    }
                }
            }
        }
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

    companion object {
        private const val THRESHOLD = 128
    }
}
