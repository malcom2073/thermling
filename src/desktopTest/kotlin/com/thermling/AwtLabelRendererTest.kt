package com.thermling

import com.thermling.barcode.BarcodeFormat
import com.thermling.model.BarcodeElement
import com.thermling.model.Halftone
import com.thermling.model.ImageElement
import com.thermling.model.ImageFit
import com.thermling.model.LabelDocument
import com.thermling.model.Orientation
import com.thermling.model.TextElement
import com.thermling.render.AwtLabelRenderer
import com.thermling.render.RenderError
import com.thermling.render.RenderResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AwtLabelRendererTest {
    @BeforeTest
    fun headless() {
        System.setProperty("java.awt.headless", "true")
    }

    @Test
    fun blankPortraitLabelIsAllWhiteAndFitsTheHead() {
        val result = AwtLabelRenderer().render(LabelDocument(), 203, 12.0, 0.0, 0.0)
        assertEquals(96, result.widthDots)
        assertEquals(320, result.heightDots)
        assertEquals(12, result.rowBytes)
        assertEquals(3840, result.data.size)
        assertTrue(result.data.all { it == 0xFF.toByte() })
        assertEquals(96, result.preview.width)
        assertEquals(320, result.preview.height)
        assertEquals("threshold", result.halftone)
    }

    @Test
    fun textProducesBlackDots() {
        val document = LabelDocument(
            elements = listOf(
                TextElement(
                    text = "Hello",
                    xMm = 1.0,
                    yMm = 1.0,
                    widthMm = 10.0,
                    heightMm = 8.0,
                    fontSizePt = 16.0,
                    bold = true,
                ),
            ),
        )
        val result = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        assertTrue(result.data.any { it != 0xFF.toByte() })
    }

    @Test
    fun landscapeRotatesThePrinterBitmapAndThePreview() {
        val document = LabelDocument(
            widthMm = 14.0,
            lengthMm = 40.0,
            orientation = Orientation.LANDSCAPE,
            elements = listOf(
                TextElement(
                    text = "Wide",
                    xMm = 2.0,
                    yMm = 2.0,
                    widthMm = 30.0,
                    heightMm = 8.0,
                    fontSizePt = 14.0,
                ),
            ),
        )
        val result = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        assertEquals(96, result.widthDots)
        assertEquals(320, result.heightDots)
        assertEquals(320, result.preview.width)
        assertEquals(96, result.preview.height)
        assertTrue(result.data.any { it != 0xFF.toByte() })
    }

    @Test
    fun landscapeRotationKeepsTheBlackHalfOnOneEndOfTheFeed() {
        val document = LabelDocument(
            widthMm = 14.0,
            lengthMm = 40.0,
            orientation = Orientation.LANDSCAPE,
            elements = listOf(
                ImageElement(
                    pngBase64 = blackPixelPng(),
                    xMm = 0.0,
                    yMm = 0.0,
                    // Slightly larger than the page so rounding at 203 DPI does
                    // not leave a one-dot white edge in the test image.
                    widthMm = 20.1,
                    heightMm = 12.1,
                    fit = ImageFit.STRETCH,
                ),
            ),
        )

        val result = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        val rowBytes = result.rowBytes
        val blackRows = (0 until result.heightDots).filter { row ->
            val offset = row * rowBytes
            (0 until rowBytes).all { result.data[offset + it] == 0.toByte() }
        }
        val whiteRows = (0 until result.heightDots).filter { row ->
            val offset = row * rowBytes
            (0 until rowBytes).all { result.data[offset + it] == 0xFF.toByte() }
        }

        assertEquals(160, blackRows.size)
        assertEquals(160, whiteRows.size)
        assertTrue(
            blackRows.max() < whiteRows.min() || whiteRows.max() < blackRows.min(),
            "the black half should occupy one contiguous end of the rotated bitmap",
        )
    }

    @Test
    fun widerLabelsAreClippedToThePrintHead() {
        val result = AwtLabelRenderer().render(LabelDocument(widthMm = 40.0), 203, 12.0, 0.0, 0.0)
        assertEquals(12.0, result.printableWidthMm)
        assertEquals(96, result.widthDots)
    }

    @Test
    fun explicitFloydSteinbergIsReported() {
        val document = LabelDocument(halftone = Halftone.FLOYD)
        val result = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        assertEquals("floyd", result.halftone)
        assertTrue(result.data.all { it == 0xFF.toByte() })
    }

    @Test
    fun calibrationOffsetShiftsThePrintedRasterByWholeDots() {
        val document = LabelDocument(
            elements = listOf(
                TextElement(
                    text = "|",
                    xMm = 1.0,
                    yMm = 1.0,
                    widthMm = 1.0,
                    heightMm = 8.0,
                    fontSizePt = 16.0,
                    bold = true,
                ),
            ),
        )

        val plain = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        val shifted = AwtLabelRenderer().render(document, 203, 12.0, 1.0, 0.0)

        val plainFirst = firstInkColumn(plain)
        val shiftedFirst = firstInkColumn(shifted)
        assertTrue(plainFirst >= 0, "the test element should produce ink")
        assertEquals(8, shiftedFirst - plainFirst, "1 mm at 203 DPI is 8 dots")
    }

    @Test
    fun barcodeProducesBarsAndRejectsInvalidData() {
        val document = LabelDocument(
            elements = listOf(
                BarcodeElement(
                    value = "12345670",
                    format = BarcodeFormat.CODE128,
                    xMm = 1.0,
                    yMm = 1.0,
                    widthMm = 10.0,
                    heightMm = 12.0,
                    showText = true,
                ),
            ),
        )

        val result = AwtLabelRenderer().render(document, 203, 12.0, 0.0, 0.0)
        assertTrue(result.data.any { it != 0xFF.toByte() }, "the barcode should print ink")

        val invalid = document.copy(
            elements = listOf(
                (document.elements.single() as BarcodeElement).copy(
                    value = "not-a-valid-ean",
                    format = BarcodeFormat.EAN13,
                ),
            ),
        )
        assertFailsWith<RenderError> {
            AwtLabelRenderer().render(invalid, 203, 12.0, 0.0, 0.0)
        }
    }

    private fun firstInkColumn(result: RenderResult): Int {
        val rowBytes = result.rowBytes
        for (x in 0 until result.widthDots) {
            val mask = 1 shl (7 - (x % 8))
            for (y in 0 until result.heightDots) {
                val value = result.data[y * rowBytes + (x shr 3)].toInt()
                if (value and mask == 0) return x
            }
        }
        return -1
    }

    private fun blackPixelPng(): String {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFF000000.toInt())
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
