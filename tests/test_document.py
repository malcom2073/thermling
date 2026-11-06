from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from thermling.document import (
    DEFAULT_FONT,
    LABEL_PRESETS,
    SCHEMA_VERSION,
    DocumentError,
    Halftone,
    HorizontalAlign,
    ImageElement,
    ImageFit,
    LabelDocument,
    Orientation,
    TextElement,
    VerticalAlign,
)
from thermling.protocol import Style

TINY_PNG_BASE64 = base64.b64encode(b"not really a png").decode("ascii")


def text_element(**overrides) -> TextElement:
    values = {
        "text": "Hello",
        "x_mm": 1.5,
        "y_mm": 2.5,
        "width_mm": 9.0,
        "height_mm": 6.0,
        "font_family": "DejaVu Sans",
        "font_size_pt": 14.0,
        "bold": True,
        "italic": True,
        "underline": True,
        "align": HorizontalAlign.RIGHT,
        "valign": VerticalAlign.BOTTOM,
        "line_spacing": 1.2,
    }
    values.update(overrides)
    return TextElement(**values)


def test_text_element_round_trip():
    element = text_element()
    restored = LabelDocument.from_dict(
        {
            "version": SCHEMA_VERSION,
            "label": {"width_mm": 14, "length_mm": 40, "gap_mm": 5, "style": "gapped"},
            "elements": [element.to_dict()],
        }
    )
    assert len(restored.elements) == 1
    result = restored.elements[0]
    assert isinstance(result, TextElement)
    assert result.id == element.id
    assert result.text == "Hello"
    assert result.x_mm == 1.5
    assert result.align is HorizontalAlign.RIGHT
    assert result.valign is VerticalAlign.BOTTOM
    assert result.bold and result.italic and result.underline
    assert result.line_spacing == 1.2
    assert restored.halftone is Halftone.AUTO


def test_image_element_round_trip():
    element = ImageElement(
        png_base64=TINY_PNG_BASE64,
        source_name="logo.png",
        x_mm=0.5,
        y_mm=1.0,
        width_mm=8.0,
        height_mm=4.0,
        fit=ImageFit.COVER,
    )
    restored = LabelDocument.from_dict(
        {"version": 1, "elements": [element.to_dict()]}
    )
    result = restored.elements[0]
    assert isinstance(result, ImageElement)
    assert result.png_base64 == TINY_PNG_BASE64
    assert result.source_name == "logo.png"
    assert result.fit is ImageFit.COVER


def test_document_round_trip_and_clone():
    document = LabelDocument(
        width_mm=15,
        length_mm=30,
        gap_mm=3,
        style=Style.BLACKMARK,
        halftone=Halftone.FLOYD,
        thermal_correction=True,
        name="Shipping",
    )
    document.add(text_element())
    document.add(ImageElement(png_base64=TINY_PNG_BASE64, source_name="a.png"))

    state = document.to_dict()
    assert state["version"] == SCHEMA_VERSION
    assert state["label"]["style"] == "blackmark"
    assert state["output"] == {"halftone": "floyd", "thermal_correction": True}

    restored = LabelDocument.from_dict(state)
    assert restored.to_dict() == state
    assert restored.name == "Shipping"
    assert restored.style is Style.BLACKMARK

    clone = document.clone()
    assert clone.to_dict() == state
    assert clone is not document
    assert clone.elements[0] is not document.elements[0]
    assert clone.elements[0].id == document.elements[0].id


def test_orientation_round_trip_and_default():
    document = LabelDocument(orientation=Orientation.LANDSCAPE, name="Wide")
    document.add(text_element())

    state = document.to_dict()
    assert state["label"]["orientation"] == "landscape"
    restored = LabelDocument.from_dict(state)
    assert restored.orientation is Orientation.LANDSCAPE

    # Older documents have no orientation field and load as portrait.
    legacy = LabelDocument.from_dict(
        {"version": 1, "label": {"width_mm": 14, "length_mm": 40}}
    )
    assert legacy.orientation is Orientation.PORTRAIT


def test_page_size_follows_orientation_and_print_head():
    portrait = LabelDocument(width_mm=14, length_mm=40)
    assert portrait.page_size_mm(12) == (12, 40)

    landscape = LabelDocument(
        width_mm=14, length_mm=40, orientation=Orientation.LANDSCAPE
    )
    assert landscape.page_size_mm(12) == (40, 12)

    wide_roll = LabelDocument(width_mm=40, length_mm=30, orientation=Orientation.LANDSCAPE)
    assert wide_roll.page_size_mm(12) == (30, 12)


def test_horizontal_presets_match_orientation():
    preset = next(p for p in LABEL_PRESETS if p.orientation is Orientation.LANDSCAPE)
    document = LabelDocument(
        width_mm=preset.width_mm,
        length_mm=preset.length_mm,
        gap_mm=preset.gap_mm,
        style=preset.style,
        orientation=preset.orientation,
    )
    assert document.matches_preset(preset)

    document.orientation = Orientation.PORTRAIT
    assert not document.matches_preset(preset)


def test_duplicate_clamps_to_landscape_page_size():
    document = LabelDocument(
        width_mm=14,
        length_mm=40,
        orientation=Orientation.LANDSCAPE,
    )
    element = document.add(
        text_element(x_mm=38.0, y_mm=8.0, width_mm=1.0, height_mm=1.0)
    )
    duplicate = document.duplicate(element.id)
    assert duplicate is not None
    assert duplicate.x_mm == 39.0
    assert duplicate.y_mm == 9.0

    edge = document.add(text_element(x_mm=39.0, y_mm=11.0, width_mm=1.0, height_mm=1.0))
    duplicate = document.duplicate(edge.id)
    assert duplicate is not None
    assert duplicate.x_mm == 39.0
    assert duplicate.y_mm == 11.0


def test_find_remove_duplicate_and_has_images():
    document = LabelDocument()
    text = document.add(text_element(x_mm=1.0, y_mm=1.0, width_mm=9.0, height_mm=5.0))
    assert document.find(text.id) is text
    assert not document.has_images

    duplicate = document.duplicate(text.id)
    assert duplicate is not None
    assert duplicate.id != text.id
    assert duplicate.text == text.text
    assert duplicate.width_mm == text.width_mm
    assert duplicate.height_mm == text.height_mm
    assert duplicate.font_family == text.font_family
    assert duplicate.x_mm == 2.0
    assert duplicate.y_mm == 2.0
    assert document.find(duplicate.id) is duplicate

    image = document.add(ImageElement(png_base64=TINY_PNG_BASE64))
    assert document.has_images
    assert document.remove(image.id) is image
    assert document.find(image.id) is None
    assert document.remove("missing") is None
    assert document.duplicate("missing") is None


def test_duplicate_clamps_to_page_size():
    document = LabelDocument(width_mm=10, length_mm=10)
    element = document.add(text_element(x_mm=8.0, y_mm=8.0, width_mm=5.0, height_mm=5.0))
    duplicate = document.duplicate(element.id)
    assert duplicate is not None
    assert duplicate.x_mm == 5.0
    assert duplicate.y_mm == 5.0


def test_matches_preset():
    preset = LABEL_PRESETS[0]
    document = LabelDocument(
        width_mm=preset.width_mm,
        length_mm=preset.length_mm,
        gap_mm=preset.gap_mm,
        style=preset.style,
    )
    assert document.matches_preset(preset)
    document.gap_mm += 0.5
    assert not document.matches_preset(preset)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("width_mm", 0.5),
        ("width_mm", 201),
        ("length_mm", 0.5),
        ("length_mm", 501),
        ("gap_mm", -0.1),
        ("gap_mm", 50.1),
    ],
)
def test_validate_rejects_out_of_range(field, value):
    document = LabelDocument()
    setattr(document, field, value)
    with pytest.raises(DocumentError):
        document.validate()


def test_save_and_load(tmp_path: Path):
    path = tmp_path / "shipping.label.json"
    document = LabelDocument(name="Shipping")
    document.add(text_element())
    document.save(path)

    raw = json.loads(path.read_text(encoding="utf-8"))
    assert raw["name"] == "Shipping"
    assert path.read_text(encoding="utf-8").endswith("\n")

    loaded = LabelDocument.load(path)
    assert loaded.to_dict() == document.to_dict()


def test_save_and_load_file_uri(tmp_path: Path):
    document = LabelDocument(name="URI label", elements=[TextElement(text="Hello")])
    path = tmp_path / "uri.label.json"
    uri = path.as_uri()

    document.save(uri)
    loaded = LabelDocument.load(uri)

    assert loaded.to_dict() == document.to_dict()
    assert path.exists()


def test_load_invalid_json(tmp_path: Path):
    path = tmp_path / "broken.json"
    path.write_text("{not json", encoding="utf-8")
    with pytest.raises(DocumentError, match="not valid JSON"):
        LabelDocument.load(path)


def test_load_missing_file(tmp_path: Path):
    with pytest.raises(DocumentError, match="Could not read"):
        LabelDocument.load(tmp_path / "missing.json")


def test_from_dict_rejects_future_version():
    with pytest.raises(DocumentError, match="Unsupported"):
        LabelDocument.from_dict({"version": SCHEMA_VERSION + 1})


def test_from_dict_rejects_malformed_sections():
    with pytest.raises(DocumentError):
        LabelDocument.from_dict([])  # type: ignore[arg-type]
    with pytest.raises(DocumentError):
        LabelDocument.from_dict({"label": "nope"})


def test_from_dict_rejects_unknown_element_type():
    with pytest.raises(DocumentError, match="Unknown element type"):
        LabelDocument.from_dict({"elements": [{"type": "barcode"}]})


def test_from_dict_rejects_invalid_image_data():
    with pytest.raises(DocumentError, match="base64"):
        LabelDocument.from_dict(
            {"elements": [{"type": "image", "png_base64": "not base64!!!"}]}
        )


def test_from_dict_defaults_and_clamping():
    document = LabelDocument.from_dict(
        {
            "elements": [
                {
                    "type": "text",
                    "text": 123,
                    "x_mm": -10,
                    "y_mm": "nonsense",
                    "width_mm": 0.1,
                    "height_mm": 9999,
                    "font_size_pt": 1,
                    "line_spacing": 99,
                    "align": "sideways",
                    "valign": "sideways",
                    "bold": "yes",
                }
            ]
        }
    )
    element = document.elements[0]
    assert isinstance(element, TextElement)
    assert element.text == "123"
    assert element.x_mm == 0
    assert element.y_mm == 0
    assert element.width_mm == 0.5
    assert element.height_mm == 500
    assert element.font_size_pt == 3
    assert element.line_spacing == 3.0
    assert element.align is HorizontalAlign.CENTER
    assert element.valign is VerticalAlign.MIDDLE
    assert element.bold is True
    assert element.font_family == DEFAULT_FONT


def test_from_dict_gives_duplicate_ids_new_values():
    element = text_element().to_dict()
    document = LabelDocument.from_dict({"elements": [element, dict(element)]})
    first, second = document.elements
    assert first.id == element["id"]
    assert second.id != element["id"]
    assert second.id != first.id


def test_unknown_style_and_halftone_are_rejected():
    with pytest.raises(DocumentError, match="label style"):
        LabelDocument.from_dict({"label": {"style": "sparkly"}})
    with pytest.raises(DocumentError, match="halftone"):
        LabelDocument.from_dict({"output": {"halftone": "sparkly"}})
