package com.thermling

import com.thermling.barcode.BarcodeFormat
import com.thermling.model.BarcodeElement
import com.thermling.model.DocumentCodec
import com.thermling.model.DocumentException
import com.thermling.model.Halftone
import com.thermling.model.ImageElement
import com.thermling.model.LabelDocument
import com.thermling.model.Orientation
import com.thermling.model.TextElement
import com.thermling.protocol.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentTest {
    @Test
    fun roundTripsSchemaVersionThree() {
        val document = LabelDocument(
            name = "Round trip",
            widthMm = 14.0,
            lengthMm = 40.0,
            gapMm = 5.0,
            style = Style.GAPPED,
            orientation = Orientation.PORTRAIT,
            halftone = Halftone.AUTO,
            thermalCorrection = true,
            elements = listOf(
                TextElement(
                    id = "text-1",
                    text = "Hello\nworld",
                    xMm = 1.0,
                    yMm = 2.0,
                    widthMm = 12.0,
                    heightMm = 10.0,
                    bold = true,
                    underline = true,
                ),
                ImageElement(
                    id = "image-1",
                    pngBase64 = "iVBORw0KGgo=",
                    sourceName = "sample.png",
                    xMm = 2.0,
                    yMm = 14.0,
                    widthMm = 10.0,
                    heightMm = 10.0,
                ),
                BarcodeElement(
                    id = "barcode-1",
                    value = "12345670",
                    format = BarcodeFormat.CODE128,
                    xMm = 1.0,
                    yMm = 26.0,
                    widthMm = 10.0,
                    heightMm = 12.0,
                    showText = true,
                ),
            ),
        )

        val raw = DocumentCodec.encode(document)
        assertTrue(raw.contains("\"version\": 3"))
        assertTrue(raw.contains("\"orientation\": \"portrait\""))
        assertTrue(raw.contains("\"halftone\": \"auto\""))
        assertTrue(raw.contains("\"thermal_correction\": true"))
        assertTrue(raw.contains("\"type\": \"barcode\""))
        assertTrue(raw.contains("\"barcode_format\": \"code128\""))

        val decoded = DocumentCodec.decode(raw)
        assertEquals(document, decoded)
    }

    @Test
    fun decodesLegacyDocumentsWithDefaults() {
        val raw = """
            {
              "version": 1,
              "name": "Legacy",
              "label": {"width_mm": 14.0, "length_mm": 30.0, "gap_mm": 3.0},
              "elements": [
                {"type": "text", "text": "Old", "x_mm": 1.0, "y_mm": 2.0}
              ]
            }
        """.trimIndent()

        val document = DocumentCodec.decode(raw)
        assertEquals("Legacy", document.name)
        assertEquals(Orientation.PORTRAIT, document.orientation)
        assertEquals(Halftone.AUTO, document.halftone)
        assertEquals(Style.GAPPED, document.style)
        assertEquals("Old", (document.elements.single() as TextElement).text)
    }

    @Test
    fun rejectsFutureSchemaAndBadElements() {
        assertFailsWith<DocumentException> {
            DocumentCodec.decode("""{"version": 4}""")
        }
        assertFailsWith<DocumentException> {
            DocumentCodec.decode(
                """{"version": 3, "elements": [{"type": "qrcode", "text": "123"}]}""",
            )
        }
        assertFailsWith<DocumentException> {
            DocumentCodec.decode(
                """{"version": 3, "elements": [{"type": "barcode", "barcode_format": "pdf417"}]}""",
            )
        }
    }

    @Test
    fun clampsElementGeometryOnLoad() {
        val raw = """
            {
              "version": 2,
              "elements": [
                {
                  "type": "text",
                  "text": "Clamped",
                  "x_mm": 500.0,
                  "y_mm": -20.0,
                  "width_mm": 0.0,
                  "height_mm": 900.0,
                  "font_size_pt": 1.0,
                  "line_spacing": 9.0
                }
              ]
            }
        """.trimIndent()
        val element = DocumentCodec.decode(raw).elements.single() as TextElement
        assertEquals(200.0, element.xMm)
        assertEquals(0.0, element.yMm)
        assertEquals(0.5, element.widthMm)
        assertEquals(500.0, element.heightMm)
        assertEquals(3.0, element.fontSizePt)
        assertEquals(3.0, element.lineSpacing)
    }

    @Test
    fun pageSizeFollowsHeadWidthAndOrientation() {
        val portrait = LabelDocument(widthMm = 14.0, lengthMm = 40.0)
        assertEquals(12.0 to 40.0, portrait.pageSizeMm(12.0))
        val landscape = portrait.copy(orientation = Orientation.LANDSCAPE)
        assertEquals(40.0 to 12.0, landscape.pageSizeMm(12.0))
        val narrower = portrait.copy(widthMm = 10.0)
        assertEquals(10.0 to 40.0, narrower.pageSizeMm(12.0))
    }

    @Test
    fun duplicateCreatesANewElementInsideThePage() {
        val element = TextElement(id = "one", xMm = 9.0, yMm = 9.0, widthMm = 5.0, heightMm = 5.0)
        val document = LabelDocument(elements = listOf(element))
        val duplicate = document.duplicate("one", headWidthMm = 12.0)
        assertEquals(2, duplicate.elements.size)
        val copied = duplicate.elements.last()
        assertTrue(copied.id != element.id)
        assertTrue(copied.xMm <= 7.0)
        assertTrue(copied.yMm <= 35.0)
    }

    @Test
    fun validatesLabelDimensions() {
        assertFailsWith<DocumentException> {
            LabelDocument(widthMm = 0.5).validate()
        }
        assertFailsWith<DocumentException> {
            LabelDocument(lengthMm = 501.0).validate()
        }
        assertFailsWith<DocumentException> {
            LabelDocument(gapMm = 51.0).validate()
        }
    }

    @Test
    fun presetNamesAndDefaultsAreStable() {
        val document = LabelDocument()
        val preset = com.thermling.model.LABEL_PRESETS.first()
        assertEquals("P21 stock — 14 × 40 mm", preset.name)
        assertTrue(document.matchesPreset(preset))
        assertNotNull(LabelDocument.fromPreset(preset))
    }
}
