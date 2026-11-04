"""Render a label document to the printer's 1-bit-per-pixel bitmap format.

The printer expects MSB-first rows padded to whole bytes, where a **0 bit prints
black** and a 1 bit is white.  Text is rasterised with Qt at the target DPI,
images are drawn as greyscale, and the canvas is then thresholded or dithered.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from functools import lru_cache

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QColor,
    QFont,
    QImage,
    QPainter,
    QTextBlockFormat,
    QTextCharFormat,
    QTextCursor,
    QTextDocument,
    QTextOption,
    QTransform,
)

from .document import (
    Element,
    Halftone,
    HorizontalAlign,
    ImageElement,
    ImageFit,
    LabelDocument,
    Orientation,
    TextElement,
    VerticalAlign,
)
from .protocol import bitmap_width_dots, mm_to_dots, printable_width_mm

# Thermal heads over-darken mid-tones.  This is the curve tuned in the reference
# implementation: 16/255 -> 81/255 while keeping 0 and 255 fixed.
_THRESHOLD = 128
_ALIGN_FLAGS = {
    HorizontalAlign.LEFT: Qt.AlignmentFlag.AlignLeft,
    HorizontalAlign.CENTER: Qt.AlignmentFlag.AlignHCenter,
    HorizontalAlign.RIGHT: Qt.AlignmentFlag.AlignRight,
}


class RenderError(Exception):
    """Raised when a document cannot be rendered."""


@dataclass(frozen=True)
class RenderResult:
    width_dots: int
    height_dots: int
    data: bytes
    preview: QImage
    dpi: int
    printable_width_mm: float
    halftone: Halftone

    @property
    def row_bytes(self) -> int:
        return self.width_dots // 8

    @property
    def size_text(self) -> str:
        return (
            f"{self.width_dots} × {self.height_dots} dots · "
            f"{self.printable_width_mm:g} mm wide"
        )


def effective_halftone(document: LabelDocument) -> Halftone:
    if document.halftone == Halftone.AUTO:
        return Halftone.FLOYD if document.has_images else Halftone.THRESHOLD
    return document.halftone


def element_rect_dots(element: Element, dpi: int) -> QRectF:
    scale = dpi / 25.4
    return QRectF(
        element.x_mm * scale,
        element.y_mm * scale,
        max(1.0, element.width_mm * scale),
        max(1.0, element.height_mm * scale),
    )


def text_font(element: TextElement, dpi: int) -> QFont:
    font = QFont(element.font_family)
    font.setPixelSize(max(1, round(element.font_size_pt * dpi / 72.0)))
    font.setBold(element.bold)
    font.setItalic(element.italic)
    font.setUnderline(element.underline)
    font.setStyleStrategy(QFont.StyleStrategy.PreferAntialias)
    return font


def draw_text_element(painter: QPainter, element: TextElement, rect: QRectF, dpi: int) -> None:
    document = QTextDocument()
    document.setDocumentMargin(0)
    document.setDefaultFont(text_font(element, dpi))

    option = QTextOption()
    option.setWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
    option.setAlignment(_ALIGN_FLAGS[element.align])
    document.setDefaultTextOption(option)

    document.setPlainText(element.text)
    document.setTextWidth(rect.width())

    cursor = QTextCursor(document)
    cursor.select(QTextCursor.SelectionType.Document)
    # QTextDocument otherwise inherits the widget palette's text colour.  In
    # dark mode that is nearly white, which disappears on the white label and
    # is thresholded away in the print preview.
    text_format = QTextCharFormat()
    text_format.setForeground(QColor(Qt.GlobalColor.black))
    cursor.mergeCharFormat(text_format)
    if abs(element.line_spacing - 1.0) > 0.001:
        block = QTextBlockFormat()
        block.setLineHeight(
            element.line_spacing * 100.0,
            QTextBlockFormat.LineHeightTypes.ProportionalHeight,
        )
        cursor.mergeBlockFormat(block)

    text_height = document.size().height()
    if element.valign == VerticalAlign.MIDDLE:
        offset = max(0.0, (rect.height() - text_height) / 2.0)
    elif element.valign == VerticalAlign.BOTTOM:
        offset = max(0.0, rect.height() - text_height)
    else:
        offset = 0.0

    painter.save()
    painter.setClipRect(rect)
    painter.translate(QPointF(rect.x(), rect.y() + offset))
    document.drawContents(painter, QRectF(0, 0, rect.width(), rect.height()))
    painter.restore()


@lru_cache(maxsize=48)
def decode_image(png_base64: str) -> QImage:
    """Decode an embedded PNG and return it as greyscale (cached)."""
    if not png_base64:
        raise RenderError("This image element has no image data")
    try:
        raw = base64.b64decode(png_base64, validate=True)
    except (ValueError, TypeError) as error:
        raise RenderError("Image data is not valid base64") from error
    image = QImage()
    if not image.loadFromData(raw):
        raise RenderError("Image data could not be decoded")
    return image.convertToFormat(QImage.Format.Format_Grayscale8)


def image_target_rect(source: QImage, rect: QRectF, fit: ImageFit) -> tuple[QRectF, QRectF]:
    """Return (target, source) rectangles for drawing an image with ``fit``."""
    if source.isNull() or rect.width() <= 0 or rect.height() <= 0:
        return rect, QRectF()
    source_rect = QRectF(0, 0, source.width(), source.height())
    if fit == ImageFit.STRETCH:
        return rect, source_rect

    scale_x = rect.width() / source.width()
    scale_y = rect.height() / source.height()
    scale = max(scale_x, scale_y) if fit == ImageFit.COVER else min(scale_x, scale_y)
    width = source.width() * scale
    height = source.height() * scale
    target = QRectF(
        rect.x() + (rect.width() - width) / 2.0,
        rect.y() + (rect.height() - height) / 2.0,
        width,
        height,
    )
    return target, source_rect


def draw_image_element(painter: QPainter, element: ImageElement, rect: QRectF) -> None:
    image = decode_image(element.png_base64)
    target, source = image_target_rect(image, rect, element.fit)
    painter.save()
    painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
    if element.fit == ImageFit.COVER:
        painter.setClipRect(rect)
    painter.drawImage(target, image, source)
    painter.restore()


def _rotate_bitmap(image: QImage, angle: int) -> QImage:
    """Rotate without interpolation; used only for 90° quarter turns."""
    return image.transformed(
        QTransform().rotate(angle), Qt.TransformationMode.FastTransformation
    )


def render_document(
    document: LabelDocument,
    *,
    dpi: int = 203,
    head_width_mm: float = 12.0,
) -> RenderResult:
    """Rasterise ``document`` and return printer-ready bytes plus a preview."""
    document.validate()
    if dpi < 50 or dpi > 1200:
        raise RenderError(f"Unsupported DPI: {dpi}")

    printable = printable_width_mm(document.width_mm, head_width_mm)
    width_dots = bitmap_width_dots(printable, dpi)
    height_dots = max(1, round(mm_to_dots(document.length_mm, dpi)))
    landscape = document.orientation == Orientation.LANDSCAPE

    if landscape:
        # Compose in the same wide orientation the user sees in the editor.
        # The canvas is rotated into the printer's fixed 12 mm head width below.
        page_width_dots = max(1, round(mm_to_dots(document.length_mm, dpi)))
        page_height_dots = width_dots
    else:
        page_width_dots = width_dots
        page_height_dots = height_dots

    canvas = QImage(page_width_dots, page_height_dots, QImage.Format.Format_Grayscale8)
    canvas.fill(Qt.GlobalColor.white)

    painter = QPainter(canvas)
    try:
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, True)
        painter.setPen(Qt.GlobalColor.black)
        for element in document.elements:
            rect = element_rect_dots(element, dpi)
            if isinstance(element, TextElement):
                draw_text_element(painter, element, rect, dpi)
            elif isinstance(element, ImageElement):
                draw_image_element(painter, element, rect)
    finally:
        painter.end()

    halftone = effective_halftone(document)
    if landscape:
        printer_canvas = _rotate_bitmap(canvas, -90)
        data, mono = binarize(printer_canvas, halftone, document.thermal_correction)
        # QImage.transformed() does not carry the mono colour table reliably, so
        # convert to greyscale before rotating the preview back into page space.
        preview_gray = mono.convertToFormat(QImage.Format.Format_Grayscale8)
        preview = _rotate_bitmap(preview_gray, 90).convertToFormat(
            QImage.Format.Format_RGB32
        )
    else:
        data, mono = binarize(canvas, halftone, document.thermal_correction)
        preview = mono.convertToFormat(QImage.Format.Format_RGB32)

    return RenderResult(
        width_dots=width_dots,
        height_dots=height_dots,
        data=data,
        preview=preview,
        dpi=dpi,
        printable_width_mm=printable,
        halftone=halftone,
    )


# --- Binarisation -------------------------------------------------------------
def _thermal_lut() -> list[int]:
    return [
        min(255, max(0, int(value * 5.0625) if value <= 16 else int(81 + (value - 16) * 0.728)))
        for value in range(256)
    ]


def _gray_rows(image: QImage) -> list[list[int]]:
    if image.format() != QImage.Format.Format_Grayscale8:
        image = image.convertToFormat(QImage.Format.Format_Grayscale8)
    width = image.width()
    height = image.height()
    stride = image.bytesPerLine()
    raw = bytes(image.constBits())
    if len(raw) >= stride * height:
        return [list(raw[row * stride : row * stride + width]) for row in range(height)]
    # Very defensive fallback (should not happen with a Grayscale8 image).
    return [
        [image.pixelColor(x, y).value() for x in range(width)] for y in range(height)
    ]


def binarize(
    gray: QImage, mode: Halftone, thermal_correction: bool = False
) -> tuple[bytes, QImage]:
    """Convert a greyscale image to packed 1-bpp rows and a mono QImage."""
    width = gray.width()
    height = gray.height()
    if width % 8:
        raise RenderError("Bitmap width must be a multiple of 8 dots")
    row_bytes = width // 8
    pixels = _gray_rows(gray)

    if thermal_correction:
        lut = _thermal_lut()
        pixels = [[lut[value] for value in row] for row in pixels]

    if mode == Halftone.THRESHOLD:
        for row in pixels:
            for x, value in enumerate(row):
                row[x] = 255 if value >= _THRESHOLD else 0
    else:
        _floyd_steinberg(pixels)

    packed = bytearray(row_bytes * height)
    for y, row in enumerate(pixels):
        offset = y * row_bytes
        for x, value in enumerate(row):
            if value:  # white; black is a zero bit
                packed[offset + (x >> 3)] |= 1 << (7 - (x & 7))

    # QImage requires 32-bit aligned scanlines, so pad for the preview only.
    stride = (row_bytes + 3) & ~3
    padded = bytearray(stride * height)
    for y in range(height):
        padded[y * stride : y * stride + row_bytes] = packed[y * row_bytes : (y + 1) * row_bytes]
    mono = QImage(bytes(padded), width, height, stride, QImage.Format.Format_Mono).copy()
    return bytes(packed), mono


def _floyd_steinberg(pixels: list[list[int]]) -> None:
    height = len(pixels)
    if not height:
        return
    width = len(pixels[0])
    for y in range(height):
        row = pixels[y]
        next_row = pixels[y + 1] if y + 1 < height else None
        for x in range(width):
            old = row[x]
            new = 255 if old >= _THRESHOLD else 0
            row[x] = new
            error = old - new
            if error == 0:
                continue
            if x + 1 < width:
                row[x + 1] = _clamp(row[x + 1] + (error * 7) // 16)
            if next_row is not None:
                if x > 0:
                    next_row[x - 1] = _clamp(next_row[x - 1] + (error * 3) // 16)
                next_row[x] = _clamp(next_row[x] + (error * 5) // 16)
                if x + 1 < width:
                    next_row[x + 1] = _clamp(next_row[x + 1] + error // 16)


def _clamp(value: int) -> int:
    return 0 if value < 0 else 255 if value > 255 else value
