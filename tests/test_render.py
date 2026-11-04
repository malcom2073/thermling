from __future__ import annotations

import base64

import pytest
from PySide6.QtCore import QBuffer, QIODevice, QRectF, Qt
from PySide6.QtGui import QColor, QImage

from thermling.document import (
    Halftone,
    ImageElement,
    ImageFit,
    LabelDocument,
    Orientation,
    TextElement,
)
from thermling.render import (
    RenderError,
    binarize,
    decode_image,
    draw_text_element,
    effective_halftone,
    element_rect_dots,
    image_target_rect,
    render_document,
    text_font,
)


def make_png(width: int = 8, height: int = 8, color: QColor | None = None) -> str:
    image = QImage(width, height, QImage.Format.Format_RGB32)
    image.fill(color or QColor("black"))
    buffer = QBuffer()
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    assert image.save(buffer, "PNG")
    return base64.b64encode(bytes(buffer.data())).decode("ascii")


def make_half_png() -> str:
    """A black left half and white right half, used to verify rotation."""
    image = QImage(8, 8, QImage.Format.Format_RGB32)
    image.fill(QColor("white"))
    for y in range(image.height()):
        for x in range(image.width() // 2):
            image.setPixelColor(x, y, QColor("black"))
    buffer = QBuffer()
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    assert image.save(buffer, "PNG")
    return base64.b64encode(bytes(buffer.data())).decode("ascii")


def gray_image(values: list[list[int]]) -> QImage:
    height = len(values)
    width = len(values[0])
    raw = bytearray()
    for row in values:
        raw.extend(row)
    image = QImage(bytes(raw), width, height, width, QImage.Format.Format_Grayscale8)
    return image.copy()


def test_render_blank_document():
    result = render_document(LabelDocument())
    assert result.width_dots == 96
    assert result.height_dots == 320
    assert result.row_bytes == 12
    assert len(result.data) == 12 * 320
    assert result.data == b"\xff" * len(result.data)
    assert result.preview.width() == 96
    assert result.preview.height() == 320
    assert result.halftone is Halftone.THRESHOLD
    assert "96 × 320 dots" in result.size_text


def test_render_landscape_rotates_content_for_fixed_head():
    document = LabelDocument(
        width_mm=14,
        length_mm=40,
        orientation=Orientation.LANDSCAPE,
    )
    document.add(
        ImageElement(
            png_base64=make_half_png(),
            x_mm=0,
            y_mm=0,
            width_mm=40,
            height_mm=12,
            fit=ImageFit.STRETCH,
        )
    )
    result = render_document(document)
    assert result.width_dots == 96
    assert result.height_dots == 320
    assert result.preview.width() == 320
    assert result.preview.height() == 96
    assert "96 × 320 dots" in result.size_text

    # In page space the left half is black.  Rotating counter-clockwise for
    # the printer puts that half at the bottom of the label.
    row_bytes = result.row_bytes
    assert result.data[:row_bytes] == b"\xff" * row_bytes
    assert result.data[-row_bytes:] == b"\x00" * row_bytes

    # The preview is rotated back, so it matches the editor's page orientation.
    assert result.preview.pixelColor(20, 48).value() == 0
    assert result.preview.pixelColor(300, 48).value() == 255


def test_render_text_marks_pixels():
    document = LabelDocument()
    document.add(
        TextElement(
            text="TEST",
            x_mm=1,
            y_mm=1,
            width_mm=10,
            height_mm=8,
            font_size_pt=24,
        )
    )
    result = render_document(document)
    assert len(result.data) == result.row_bytes * result.height_dots
    assert any(byte != 0xFF for byte in result.data)
    assert result.halftone is Halftone.THRESHOLD
    assert result.data != b"\xff" * len(result.data)


def test_render_image_uses_floyd_by_default():
    document = LabelDocument()
    document.add(
        ImageElement(
            png_base64=make_png(16, 16, QColor("grey")),
            x_mm=1,
            y_mm=1,
            width_mm=10,
            height_mm=10,
        )
    )
    result = render_document(document)
    assert result.halftone is Halftone.FLOYD
    assert len(result.data) == result.row_bytes * result.height_dots


def test_render_honours_halftone_and_thermal_flags():
    document = LabelDocument(halftone=Halftone.FLOYD, thermal_correction=True)
    document.add(TextElement(text="A", width_mm=10, height_mm=8))
    result = render_document(document)
    assert result.halftone is Halftone.FLOYD


def test_render_300_dpi_width_is_whole_bytes():
    result = render_document(LabelDocument(), dpi=300)
    assert result.width_dots % 8 == 0
    assert result.width_dots == 144
    assert result.height_dots == 472


def test_render_wide_label_is_clipped_to_head():
    document = LabelDocument(width_mm=40, length_mm=30)
    result = render_document(document, head_width_mm=12)
    assert result.printable_width_mm == 12
    assert result.width_dots == 96


def test_render_rejects_bad_dpi():
    with pytest.raises(RenderError, match="Unsupported DPI"):
        render_document(LabelDocument(), dpi=10)


def test_effective_halftone():
    document = LabelDocument()
    assert effective_halftone(document) is Halftone.THRESHOLD
    document.add(ImageElement(png_base64=make_png()))
    assert effective_halftone(document) is Halftone.FLOYD
    document.halftone = Halftone.THRESHOLD
    assert effective_halftone(document) is Halftone.THRESHOLD
    document.halftone = Halftone.FLOYD
    assert effective_halftone(document) is Halftone.FLOYD


def test_element_rect_and_font_scale_with_dpi():
    element = TextElement(x_mm=1, y_mm=2, width_mm=10, height_mm=8, font_size_pt=12)
    rect = element_rect_dots(element, 254)
    assert rect == QRectF(10, 20, 100, 80)
    font = text_font(element, 203)
    assert font.pixelSize() == round(12 * 203 / 72)


def test_element_rect_has_minimum_size():
    element = TextElement(width_mm=0.0, height_mm=0.0)
    rect = element_rect_dots(element, 203)
    assert rect.width() == 1.0
    assert rect.height() == 1.0


def test_image_target_rect_contain_and_cover():
    source = QImage(100, 50, QImage.Format.Format_Grayscale8)
    rect = QRectF(0, 0, 100, 100)

    target, source_rect = image_target_rect(source, rect, ImageFit.CONTAIN)
    assert source_rect == QRectF(0, 0, 100, 50)
    assert target.width() == 100
    assert target.height() == 50
    assert target.top() == 25

    target, source_rect = image_target_rect(source, rect, ImageFit.COVER)
    assert source_rect == QRectF(0, 0, 100, 50)
    assert target.height() == 100
    assert target.width() == 200
    assert target.left() == -50

    target, source_rect = image_target_rect(source, rect, ImageFit.STRETCH)
    assert target == rect
    assert source_rect == QRectF(0, 0, 100, 50)


def test_image_target_rect_empty():
    target, source = image_target_rect(QImage(), QRectF(0, 0, 10, 10), ImageFit.CONTAIN)
    assert target == QRectF(0, 0, 10, 10)
    assert source == QRectF()


def test_decode_image_caches_and_rejects_bad_data():
    png = make_png()
    first = decode_image(png)
    second = decode_image(png)
    assert first is second
    assert first.format() == QImage.Format.Format_Grayscale8
    with pytest.raises(RenderError, match="no image data"):
        decode_image("")
    with pytest.raises(RenderError, match="base64"):
        decode_image("not base64!!!")
    with pytest.raises(RenderError, match="could not be decoded"):
        decode_image(base64.b64encode(b"plain text").decode("ascii"))


def test_binarize_threshold_packs_msb_first_with_zero_black():
    image = gray_image([[0] + [255] * 15, [255] * 16])
    data, mono = binarize(image, Halftone.THRESHOLD)
    assert data[0] == 0x7F  # first pixel black => bit 7 cleared
    assert data[1] == 0xFF
    assert len(data) == 2 * 2
    assert mono.width() == 16
    assert mono.height() == 2
    assert mono.format() == QImage.Format.Format_Mono


def test_binarize_thermal_correction_lifts_shadows():
    image = gray_image([[100] * 16])
    plain, _ = binarize(image, Halftone.THRESHOLD, thermal_correction=False)
    lifted, _ = binarize(image, Halftone.THRESHOLD, thermal_correction=True)
    assert plain == b"\x00\x00"
    assert lifted == b"\xff\xff"


def test_binarize_floyd_steinberg_mid_gray():
    image = gray_image([[128] * 64 for _ in range(64)])
    data, mono = binarize(image, Halftone.FLOYD)
    bits = "".join(f"{byte:08b}" for byte in data)
    assert "0" in bits
    assert "1" in bits
    assert mono.width() == 64


def test_binarize_requires_whole_bytes():
    image = gray_image([[255] * 10])
    with pytest.raises(RenderError, match="multiple of 8"):
        binarize(image, Halftone.THRESHOLD)


def test_draw_text_element_handles_empty_text(qapp):
    image = QImage(80, 80, QImage.Format.Format_Grayscale8)
    image.fill(Qt.GlobalColor.white)
    from PySide6.QtGui import QPainter

    painter = QPainter(image)
    try:
        element = TextElement(
            text="",
            x_mm=0,
            y_mm=0,
            width_mm=10,
            height_mm=10,
        )
        draw_text_element(painter, element, QRectF(0, 0, 80, 80), 203)
    finally:
        painter.end()
    assert image.width() == 80


def test_render_image_cover_and_stretch():
    document = LabelDocument()
    document.add(
        ImageElement(
            png_base64=make_png(16, 8),
            x_mm=1,
            y_mm=1,
            width_mm=10,
            height_mm=10,
            fit=ImageFit.COVER,
        )
    )
    document.add(
        ImageElement(
            png_base64=make_png(8, 16),
            x_mm=1,
            y_mm=12,
            width_mm=10,
            height_mm=10,
            fit=ImageFit.STRETCH,
        )
    )
    result = render_document(document)
    assert len(result.data) == result.row_bytes * result.height_dots
    assert result.preview.width() == result.width_dots
