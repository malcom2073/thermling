package com.thermling.model

import com.thermling.protocol.DEFAULT_HEAD_WIDTH_MM
import com.thermling.protocol.Style
import com.thermling.protocol.printableWidthMm
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

const val SCHEMA_VERSION = 2
const val DEFAULT_FONT = "DejaVu Sans"
const val DEFAULT_FONT_SIZE_PT = 10.0

enum class HorizontalAlign(val value: String, val label: String) {
    LEFT("left", "Left"),
    CENTER("center", "Center"),
    RIGHT("right", "Right");
}

enum class VerticalAlign(val value: String, val label: String) {
    TOP("top", "Top"),
    MIDDLE("middle", "Middle"),
    BOTTOM("bottom", "Bottom");
}

enum class ImageFit(val value: String, val label: String) {
    CONTAIN("contain", "Contain (keep aspect)"),
    STRETCH("stretch", "Stretch"),
    COVER("cover", "Cover (crop)");
}

enum class Halftone(val value: String, val label: String) {
    AUTO("auto", "Auto (dither if images)"),
    FLOYD("floyd", "Floyd–Steinberg dither"),
    THRESHOLD("threshold", "Hard threshold");
}

enum class Orientation(val value: String, val label: String) {
    PORTRAIT("portrait", "Vertical (portrait)"),
    LANDSCAPE("landscape", "Horizontal (landscape)");
}

fun newId(): String {
    val alphabet = "0123456789abcdef"
    return buildString(12) {
        repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}

sealed interface LabelElement {
    val id: String
    val xMm: Double
    val yMm: Double
    val widthMm: Double
    val heightMm: Double
    val kind: String
}

data class TextElement(
    override val id: String = newId(),
    val text: String = "Text",
    override val xMm: Double = 1.0,
    override val yMm: Double = 1.0,
    override val widthMm: Double = 10.0,
    override val heightMm: Double = 8.0,
    val fontFamily: String = DEFAULT_FONT,
    val fontSizePt: Double = DEFAULT_FONT_SIZE_PT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: HorizontalAlign = HorizontalAlign.CENTER,
    val valign: VerticalAlign = VerticalAlign.MIDDLE,
    val lineSpacing: Double = 1.0,
) : LabelElement {
    override val kind: String get() = "text"

    fun withBounds(x: Double, y: Double, width: Double, height: Double) =
        copy(xMm = x, yMm = y, widthMm = width, heightMm = height)
}

data class ImageElement(
    override val id: String = newId(),
    val pngBase64: String = "",
    val sourceName: String = "",
    override val xMm: Double = 1.0,
    override val yMm: Double = 1.0,
    override val widthMm: Double = 10.0,
    override val heightMm: Double = 10.0,
    val fit: ImageFit = ImageFit.CONTAIN,
) : LabelElement {
    override val kind: String get() = "image"

    fun withBounds(x: Double, y: Double, width: Double, height: Double) =
        copy(xMm = x, yMm = y, widthMm = width, heightMm = height)
}

data class LabelPreset(
    val name: String,
    val widthMm: Double,
    val lengthMm: Double,
    val gapMm: Double,
    val style: Style,
    val orientation: Orientation = Orientation.PORTRAIT,
)

val LABEL_PRESETS: List<LabelPreset> = listOf(
    LabelPreset("P21 stock — 14 × 40 mm", 14.0, 40.0, 5.0, Style.GAPPED),
    LabelPreset("14 × 30 mm", 14.0, 30.0, 3.0, Style.GAPPED),
    LabelPreset("15 × 30 mm", 15.0, 30.0, 3.0, Style.GAPPED),
    LabelPreset("12 × 22 mm", 12.0, 22.0, 3.0, Style.GAPPED),
    LabelPreset("40 × 30 mm", 40.0, 30.0, 3.0, Style.GAPPED),
    LabelPreset("Continuous roll", 14.0, 40.0, 0.0, Style.CONTINUOUS),
    LabelPreset(
        "P21 stock — 40 × 14 mm horizontal",
        14.0,
        40.0,
        5.0,
        Style.GAPPED,
        Orientation.LANDSCAPE,
    ),
    LabelPreset("30 × 14 mm horizontal", 14.0, 30.0, 3.0, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset("30 × 15 mm horizontal", 15.0, 30.0, 3.0, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset("22 × 12 mm horizontal", 12.0, 22.0, 3.0, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset(
        "Continuous roll horizontal",
        14.0,
        40.0,
        0.0,
        Style.CONTINUOUS,
        Orientation.LANDSCAPE,
    ),
)

class DocumentException(message: String) : Exception(message)

data class LabelDocument(
    val name: String = "Untitled",
    val widthMm: Double = 14.0,
    val lengthMm: Double = 40.0,
    val gapMm: Double = 5.0,
    val style: Style = Style.GAPPED,
    val orientation: Orientation = Orientation.PORTRAIT,
    val halftone: Halftone = Halftone.AUTO,
    val thermalCorrection: Boolean = false,
    val elements: List<LabelElement> = emptyList(),
) {
    fun find(elementId: String): LabelElement? = elements.firstOrNull { it.id == elementId }

    fun hasImages(): Boolean = elements.any { it is ImageElement }

    fun pageSizeMm(headWidthMm: Double = DEFAULT_HEAD_WIDTH_MM): Pair<Double, Double> {
        val shortSide = printableWidthMm(widthMm, headWidthMm)
        return if (orientation == Orientation.LANDSCAPE) lengthMm to shortSide
        else shortSide to lengthMm
    }

    fun matchesPreset(preset: LabelPreset): Boolean =
        abs(widthMm - preset.widthMm) < 0.01 &&
            abs(lengthMm - preset.lengthMm) < 0.01 &&
            abs(gapMm - preset.gapMm) < 0.01 &&
            style == preset.style &&
            orientation == preset.orientation

    fun add(element: LabelElement): LabelDocument = copy(elements = elements + element)

    fun remove(elementId: String): LabelDocument =
        copy(elements = elements.filterNot { it.id == elementId })

    fun replace(element: LabelElement): LabelDocument =
        copy(elements = elements.map { if (it.id == element.id) element else it })

    fun duplicate(elementId: String, headWidthMm: Double = DEFAULT_HEAD_WIDTH_MM): LabelDocument {
        val source = find(elementId) ?: return this
        val (pageWidth, pageHeight) = pageSizeMm(headWidthMm)
        val clone = when (source) {
            is TextElement -> source.copy(
                id = newId(),
                xMm = min(source.xMm + 1.0, max(0.0, pageWidth - source.widthMm)),
                yMm = min(source.yMm + 1.0, max(0.0, pageHeight - source.heightMm)),
            )
            is ImageElement -> source.copy(
                id = newId(),
                xMm = min(source.xMm + 1.0, max(0.0, pageWidth - source.widthMm)),
                yMm = min(source.yMm + 1.0, max(0.0, pageHeight - source.heightMm)),
            )
        }
        return add(clone)
    }

    fun validate() {
        if (widthMm !in 1.0..200.0) throw DocumentException("Label width must be between 1 and 200 mm")
        if (lengthMm !in 1.0..500.0) throw DocumentException("Label length must be between 1 and 500 mm")
        if (gapMm !in 0.0..50.0) throw DocumentException("Gap must be between 0 and 50 mm")
    }

    fun clone(): LabelDocument = copy(elements = elements.toList())

    companion object {
        fun fromPreset(preset: LabelPreset, name: String = "Untitled"): LabelDocument =
            LabelDocument(
                name = name,
                widthMm = preset.widthMm,
                lengthMm = preset.lengthMm,
                gapMm = preset.gapMm,
                style = preset.style,
                orientation = preset.orientation,
            )

        fun defaultTextElement(
            document: LabelDocument,
            headWidthMm: Double = DEFAULT_HEAD_WIDTH_MM,
        ): TextElement {
            val (pageWidth, _) = document.pageSizeMm(headWidthMm)
            val width = max(1.0, pageWidth - 2.0)
            return TextElement(
                text = "Text",
                xMm = 1.0,
                yMm = 1.0,
                widthMm = width,
                heightMm = min(10.0, max(4.0, width * 0.35)),
            )
        }
    }
}

@Serializable
private data class DocumentDto(
    val version: Int = 1,
    val name: String? = null,
    val label: LabelDto? = null,
    val output: OutputDto? = null,
    val elements: List<ElementDto> = emptyList(),
)

@Serializable
private data class LabelDto(
    val width_mm: Double = 14.0,
    val length_mm: Double = 40.0,
    val gap_mm: Double = 5.0,
    val style: String = "gapped",
    val orientation: String = "portrait",
)

@Serializable
private data class OutputDto(
    val halftone: String = "auto",
    val thermal_correction: Boolean = false,
)

@Serializable
private data class ElementDto(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val x_mm: Double? = null,
    val y_mm: Double? = null,
    val width_mm: Double? = null,
    val height_mm: Double? = null,
    val font_family: String? = null,
    val font_size_pt: Double? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val align: String? = null,
    val valign: String? = null,
    val line_spacing: Double? = null,
    val png_base64: String? = null,
    val source_name: String? = null,
    val fit: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
object DocumentCodec {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(document: LabelDocument): String {
        document.validate()
        val dto = DocumentDto(
            version = SCHEMA_VERSION,
            name = document.name,
            label = LabelDto(
                width_mm = document.widthMm,
                length_mm = document.lengthMm,
                gap_mm = document.gapMm,
                style = document.style.name.lowercase(),
                orientation = document.orientation.value,
            ),
            output = OutputDto(
                halftone = document.halftone.value,
                thermal_correction = document.thermalCorrection,
            ),
            elements = document.elements.map { it.toDto() },
        )
        return json.encodeToString(DocumentDto.serializer(), dto) + "\n"
    }

    fun decode(raw: String): LabelDocument {
        val dto = try {
            json.decodeFromString(DocumentDto.serializer(), raw)
        } catch (error: SerializationException) {
            throw DocumentException("Document is not valid JSON: ${error.message}")
        } catch (error: IllegalArgumentException) {
            throw DocumentException("Document is not valid JSON: ${error.message}")
        }
        if (dto.version > SCHEMA_VERSION) {
            throw DocumentException("Unsupported document version: ${dto.version}")
        }
        val label = dto.label ?: LabelDto()
        val output = dto.output ?: OutputDto()
        val document = LabelDocument(
            name = dto.name?.takeIf { it.isNotBlank() } ?: "Untitled",
            widthMm = label.width_mm,
            lengthMm = label.length_mm,
            gapMm = label.gap_mm,
            style = parseStyle(label.style),
            orientation = parseOrientation(label.orientation),
            halftone = parseHalftone(output.halftone),
            thermalCorrection = output.thermal_correction,
            elements = buildList {
                val seen = mutableSetOf<String>()
                for (item in dto.elements) {
                    var element = item.toElement()
                    if (element.id in seen) {
                        element = when (element) {
                            is TextElement -> element.copy(id = newId())
                            is ImageElement -> element.copy(id = newId())
                        }
                    }
                    seen.add(element.id)
                    add(element)
                }
            },
        )
        document.validate()
        return document
    }

    private fun LabelElement.toDto(): ElementDto = when (this) {
        is TextElement -> ElementDto(
            type = "text",
            id = id,
            text = text,
            x_mm = xMm,
            y_mm = yMm,
            width_mm = widthMm,
            height_mm = heightMm,
            font_family = fontFamily,
            font_size_pt = fontSizePt,
            bold = bold,
            italic = italic,
            underline = underline,
            align = align.value,
            valign = valign.value,
            line_spacing = lineSpacing,
        )
        is ImageElement -> ElementDto(
            type = "image",
            id = id,
            png_base64 = pngBase64,
            source_name = sourceName,
            x_mm = xMm,
            y_mm = yMm,
            width_mm = widthMm,
            height_mm = heightMm,
            fit = fit.value,
        )
    }

    private fun ElementDto.toElement(): LabelElement {
        val elementId = id?.takeIf { it.isNotBlank() } ?: newId()
        val x = clamp(x_mm ?: 0.0, 0.0, 200.0)
        val y = clamp(y_mm ?: 0.0, 0.0, 500.0)
        val width = clamp(width_mm ?: 10.0, 0.5, 200.0)
        val height = clamp(height_mm ?: 8.0, 0.5, 500.0)
        return when (type) {
            "text" -> TextElement(
                id = elementId,
                text = text ?: "",
                xMm = x,
                yMm = y,
                widthMm = width,
                heightMm = height,
                fontFamily = font_family?.takeIf { it.isNotBlank() } ?: DEFAULT_FONT,
                fontSizePt = clamp(font_size_pt ?: DEFAULT_FONT_SIZE_PT, 3.0, 200.0),
                bold = bold ?: false,
                italic = italic ?: false,
                underline = underline ?: false,
                align = parseAlign(align ?: "center"),
                valign = parseVAlign(valign ?: "middle"),
                lineSpacing = clamp(line_spacing ?: 1.0, 0.5, 3.0),
            )
            "image" -> ImageElement(
                id = elementId,
                pngBase64 = png_base64 ?: "",
                sourceName = source_name ?: "",
                xMm = x,
                yMm = y,
                widthMm = width,
                heightMm = height,
                fit = parseFit(fit ?: "contain"),
            )
            else -> throw DocumentException("Unknown element type: $type")
        }
    }

    private fun parseStyle(raw: String): Style = when (raw.trim().lowercase()) {
        "continuous" -> Style.CONTINUOUS
        "gapped" -> Style.GAPPED
        "blackmark" -> Style.BLACKMARK
        else -> throw DocumentException("Unknown label style: $raw")
    }

    private fun parseHalftone(raw: String): Halftone =
        Halftone.entries.firstOrNull { it.value == raw.trim().lowercase() }
            ?: throw DocumentException("Unknown halftone mode: $raw")

    private fun parseOrientation(raw: String): Orientation =
        Orientation.entries.firstOrNull { it.value == raw.trim().lowercase() }
            ?: throw DocumentException("Unknown label orientation: $raw")

    private fun parseAlign(raw: String): HorizontalAlign =
        HorizontalAlign.entries.firstOrNull { it.value == raw.trim().lowercase() }
            ?: HorizontalAlign.CENTER

    private fun parseVAlign(raw: String): VerticalAlign =
        VerticalAlign.entries.firstOrNull { it.value == raw.trim().lowercase() }
            ?: VerticalAlign.MIDDLE

    private fun parseFit(raw: String): ImageFit =
        ImageFit.entries.firstOrNull { it.value == raw.trim().lowercase() }
            ?: ImageFit.CONTAIN

    private fun clamp(value: Double, low: Double, high: Double): Double = min(high, max(low, value))
}
