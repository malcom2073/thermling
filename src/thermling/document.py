"""Serializable label document model.

All geometry is stored in millimetres so a document is independent of the
printer's resolution.  Images are embedded as base64 PNG data, which keeps saved
documents portable and means a moved or deleted source file cannot break a
template.
"""

from __future__ import annotations

import base64
import copy
import json
import uuid
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any

from PySide6.QtCore import QFile, QIODevice, QUrl

from .protocol import DEFAULT_HEAD_WIDTH_MM, Style, printable_width_mm

SCHEMA_VERSION = 2

DEFAULT_FONT = "DejaVu Sans"
DEFAULT_FONT_SIZE_PT = 10.0


def _local_location(path: str) -> str:
    return QUrl(path).toLocalFile() if path.startswith("file:") else path


def _read_text(path: str | Path) -> str:
    location = str(path)
    if not location.startswith("content:"):
        return Path(_local_location(location)).read_text(encoding="utf-8")
    file = QFile(location)
    if not file.open(QIODevice.OpenModeFlag.ReadOnly):
        raise OSError(file.errorString())
    try:
        return bytes(file.readAll()).decode("utf-8")
    finally:
        file.close()


def _write_text(path: str | Path, text: str) -> None:
    location = str(path)
    if not location.startswith("content:"):
        Path(_local_location(location)).write_text(text, encoding="utf-8")
        return
    file = QFile(location)
    if not file.open(
        QIODevice.OpenModeFlag.WriteOnly | QIODevice.OpenModeFlag.Truncate
    ):
        raise OSError(file.errorString())
    try:
        file.write(text.encode("utf-8"))
        if not file.flush():
            raise OSError(file.errorString())
    finally:
        file.close()


class DocumentError(Exception):
    """Raised when a document file cannot be read or understood."""


class HorizontalAlign(StrEnum):
    LEFT = "left"
    CENTER = "center"
    RIGHT = "right"

    @property
    def label(self) -> str:
        return self.value.title()


class VerticalAlign(StrEnum):
    TOP = "top"
    MIDDLE = "middle"
    BOTTOM = "bottom"

    @property
    def label(self) -> str:
        return self.value.title()


class ImageFit(StrEnum):
    CONTAIN = "contain"
    STRETCH = "stretch"
    COVER = "cover"

    @property
    def label(self) -> str:
        return {"contain": "Contain (keep aspect)", "stretch": "Stretch", "cover": "Cover (crop)"}[
            self.value
        ]


class Halftone(StrEnum):
    AUTO = "auto"
    FLOYD = "floyd"
    THRESHOLD = "threshold"

    @property
    def label(self) -> str:
        return {
            "auto": "Auto (dither if images)",
            "floyd": "Floyd–Steinberg dither",
            "threshold": "Hard threshold",
        }[self.value]


class Orientation(StrEnum):
    PORTRAIT = "portrait"
    LANDSCAPE = "landscape"

    @property
    def label(self) -> str:
        if self is Orientation.PORTRAIT:
            return "Vertical (portrait)"
        return "Horizontal (landscape)"


def new_id() -> str:
    return uuid.uuid4().hex[:12]


@dataclass
class TextElement:
    text: str = "Text"
    x_mm: float = 1.0
    y_mm: float = 1.0
    width_mm: float = 10.0
    height_mm: float = 8.0
    font_family: str = DEFAULT_FONT
    font_size_pt: float = DEFAULT_FONT_SIZE_PT
    bold: bool = False
    italic: bool = False
    underline: bool = False
    align: HorizontalAlign = HorizontalAlign.CENTER
    valign: VerticalAlign = VerticalAlign.MIDDLE
    line_spacing: float = 1.0
    id: str = field(default_factory=new_id)
    kind: str = field(default="text", init=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": "text",
            "id": self.id,
            "text": self.text,
            "x_mm": self.x_mm,
            "y_mm": self.y_mm,
            "width_mm": self.width_mm,
            "height_mm": self.height_mm,
            "font_family": self.font_family,
            "font_size_pt": self.font_size_pt,
            "bold": self.bold,
            "italic": self.italic,
            "underline": self.underline,
            "align": self.align.value,
            "valign": self.valign.value,
            "line_spacing": self.line_spacing,
        }


@dataclass
class ImageElement:
    png_base64: str = ""
    source_name: str = ""
    x_mm: float = 1.0
    y_mm: float = 1.0
    width_mm: float = 10.0
    height_mm: float = 10.0
    fit: ImageFit = ImageFit.CONTAIN
    id: str = field(default_factory=new_id)
    kind: str = field(default="image", init=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": "image",
            "id": self.id,
            "png_base64": self.png_base64,
            "source_name": self.source_name,
            "x_mm": self.x_mm,
            "y_mm": self.y_mm,
            "width_mm": self.width_mm,
            "height_mm": self.height_mm,
            "fit": self.fit.value,
        }


Element = TextElement | ImageElement


@dataclass(frozen=True)
class LabelPreset:
    name: str
    width_mm: float
    length_mm: float
    gap_mm: float
    style: Style
    orientation: Orientation = Orientation.PORTRAIT


LABEL_PRESETS: tuple[LabelPreset, ...] = (
    LabelPreset("P21 stock — 14 × 40 mm", 14, 40, 5, Style.GAPPED),
    LabelPreset("14 × 30 mm", 14, 30, 3, Style.GAPPED),
    LabelPreset("15 × 30 mm", 15, 30, 3, Style.GAPPED),
    LabelPreset("12 × 22 mm", 12, 22, 3, Style.GAPPED),
    LabelPreset("40 × 30 mm", 40, 30, 3, Style.GAPPED),
    LabelPreset("Continuous roll", 14, 40, 0, Style.CONTINUOUS),
    LabelPreset(
        "P21 stock — 40 × 14 mm horizontal",
        14,
        40,
        5,
        Style.GAPPED,
        Orientation.LANDSCAPE,
    ),
    LabelPreset("30 × 14 mm horizontal", 14, 30, 3, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset("30 × 15 mm horizontal", 15, 30, 3, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset("22 × 12 mm horizontal", 12, 22, 3, Style.GAPPED, Orientation.LANDSCAPE),
    LabelPreset(
        "Continuous roll horizontal",
        14,
        40,
        0,
        Style.CONTINUOUS,
        Orientation.LANDSCAPE,
    ),
)


@dataclass
class LabelDocument:
    width_mm: float = 14.0
    length_mm: float = 40.0
    gap_mm: float = 5.0
    style: Style = Style.GAPPED
    orientation: Orientation = Orientation.PORTRAIT
    halftone: Halftone = Halftone.AUTO
    thermal_correction: bool = False
    elements: list[Element] = field(default_factory=list)
    name: str = "Untitled"

    # --- queries -------------------------------------------------------------
    def find(self, element_id: str) -> Element | None:
        return next((element for element in self.elements if element.id == element_id), None)

    @property
    def has_images(self) -> bool:
        return any(isinstance(element, ImageElement) for element in self.elements)

    def page_size_mm(self, head_width_mm: float) -> tuple[float, float]:
        """Return the editor/preview page size in the current orientation."""
        short_side = printable_width_mm(self.width_mm, head_width_mm)
        if self.orientation == Orientation.LANDSCAPE:
            return self.length_mm, short_side
        return short_side, self.length_mm

    def matches_preset(self, preset: LabelPreset) -> bool:
        return (
            abs(self.width_mm - preset.width_mm) < 0.01
            and abs(self.length_mm - preset.length_mm) < 0.01
            and abs(self.gap_mm - preset.gap_mm) < 0.01
            and self.style == preset.style
            and self.orientation == preset.orientation
        )

    # --- mutations -----------------------------------------------------------
    def add(self, element: Element) -> Element:
        self.elements.append(element)
        return element

    def remove(self, element_id: str) -> Element | None:
        element = self.find(element_id)
        if element is not None:
            self.elements.remove(element)
        return element

    def duplicate(self, element_id: str) -> Element | None:
        source = self.find(element_id)
        if source is None:
            return None
        clone = copy.deepcopy(source)
        clone.id = new_id()
        page_width, page_height = self.page_size_mm(DEFAULT_HEAD_WIDTH_MM)
        clone.x_mm = min(clone.x_mm + 1.0, max(0.0, page_width - clone.width_mm))
        clone.y_mm = min(clone.y_mm + 1.0, max(0.0, page_height - clone.height_mm))
        self.elements.append(clone)
        return clone

    # --- serialization -------------------------------------------------------
    def to_dict(self) -> dict[str, Any]:
        return {
            "version": SCHEMA_VERSION,
            "name": self.name,
            "label": {
                "width_mm": self.width_mm,
                "length_mm": self.length_mm,
                "gap_mm": self.gap_mm,
                "style": self.style.name.lower(),
                "orientation": self.orientation.value,
            },
            "output": {
                "halftone": self.halftone.value,
                "thermal_correction": self.thermal_correction,
            },
            "elements": [element.to_dict() for element in self.elements],
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> LabelDocument:
        if not isinstance(data, dict):
            raise DocumentError("Document root must be a JSON object")
        version = data.get("version", 1)
        if not isinstance(version, int) or version > SCHEMA_VERSION:
            raise DocumentError(f"Unsupported document version: {version!r}")

        label = data.get("label") or {}
        output = data.get("output") or {}
        if not isinstance(label, dict) or not isinstance(output, dict):
            raise DocumentError("Malformed label or output section")

        document = cls(
            name=str(data.get("name") or "Untitled"),
            width_mm=float(label.get("width_mm", 14)),
            length_mm=float(label.get("length_mm", 40)),
            gap_mm=float(label.get("gap_mm", 5)),
            style=_parse_style(label.get("style", "gapped")),
            orientation=_parse_orientation(label.get("orientation", "portrait")),
            halftone=_parse_halftone(output.get("halftone", "auto")),
            thermal_correction=bool(output.get("thermal_correction", False)),
        )
        document.validate()

        elements = data.get("elements", [])
        if not isinstance(elements, list):
            raise DocumentError("'elements' must be a list")
        seen: set[str] = set()
        for raw in elements:
            element = _element_from_dict(raw)
            if element.id in seen:
                element.id = new_id()
            seen.add(element.id)
            document.elements.append(element)
        return document

    def validate(self) -> None:
        if not 1.0 <= self.width_mm <= 200.0:
            raise DocumentError("Label width must be between 1 and 200 mm")
        if not 1.0 <= self.length_mm <= 500.0:
            raise DocumentError("Label length must be between 1 and 500 mm")
        if not 0.0 <= self.gap_mm <= 50.0:
            raise DocumentError("Gap must be between 0 and 50 mm")

    def clone(self) -> LabelDocument:
        return LabelDocument.from_dict(self.to_dict())

    # --- files ---------------------------------------------------------------
    def save(self, path: str | Path) -> None:
        self.validate()
        _write_text(
            path, json.dumps(self.to_dict(), indent=2, ensure_ascii=False) + "\n"
        )

    @classmethod
    def load(cls, path: str | Path) -> LabelDocument:
        try:
            raw = _read_text(path)
        except (OSError, UnicodeError) as error:
            raise DocumentError(f"Could not read {path}: {error}") from error
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as error:
            raise DocumentError(f"{path} is not valid JSON: {error}") from error
        return cls.from_dict(data)


# --- parsing helpers ----------------------------------------------------------
def _number(raw: Any, default: float, *, low: float, high: float) -> float:
    try:
        value = float(raw)
    except (TypeError, ValueError):
        value = default
    return min(high, max(low, value))


def _boolean(raw: Any, default: bool = False) -> bool:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, str):
        return raw.strip().lower() in {"1", "true", "yes", "on"}
    return default


def _parse_style(raw: Any) -> Style:
    if isinstance(raw, Style):
        return raw
    if isinstance(raw, int):
        try:
            return Style(raw)
        except ValueError as error:
            raise DocumentError(f"Unknown label style: {raw!r}") from error
    try:
        return Style[str(raw).strip().upper()]
    except KeyError as error:
        raise DocumentError(f"Unknown label style: {raw!r}") from error


def _parse_halftone(raw: Any) -> Halftone:
    try:
        return Halftone(str(raw).strip().lower())
    except ValueError as error:
        raise DocumentError(f"Unknown halftone mode: {raw!r}") from error


def _parse_orientation(raw: Any) -> Orientation:
    try:
        return Orientation(str(raw).strip().lower())
    except ValueError as error:
        raise DocumentError(f"Unknown label orientation: {raw!r}") from error


def _parse_alignment(raw: Any) -> HorizontalAlign:
    try:
        return HorizontalAlign(str(raw).strip().lower())
    except ValueError:
        return HorizontalAlign.CENTER


def _parse_valignment(raw: Any) -> VerticalAlign:
    try:
        return VerticalAlign(str(raw).strip().lower())
    except ValueError:
        return VerticalAlign.MIDDLE


def _parse_fit(raw: Any) -> ImageFit:
    try:
        return ImageFit(str(raw).strip().lower())
    except ValueError:
        return ImageFit.CONTAIN


def _element_from_dict(raw: Any) -> Element:
    if not isinstance(raw, dict):
        raise DocumentError("Each element must be a JSON object")
    kind = raw.get("type")
    element_id = str(raw.get("id") or new_id())
    common = {
        "id": element_id,
        "x_mm": _number(raw.get("x_mm"), 0, low=0, high=200),
        "y_mm": _number(raw.get("y_mm"), 0, low=0, high=500),
        "width_mm": _number(raw.get("width_mm"), 10, low=0.5, high=200),
        "height_mm": _number(raw.get("height_mm"), 8, low=0.5, high=500),
    }
    if kind == "text":
        return TextElement(
            text=str(raw.get("text", "")),
            font_family=str(raw.get("font_family") or DEFAULT_FONT),
            font_size_pt=_number(
                raw.get("font_size_pt"), DEFAULT_FONT_SIZE_PT, low=3, high=200
            ),
            bold=_boolean(raw.get("bold")),
            italic=_boolean(raw.get("italic")),
            underline=_boolean(raw.get("underline")),
            align=_parse_alignment(raw.get("align", "center")),
            valign=_parse_valignment(raw.get("valign", "middle")),
            line_spacing=_number(raw.get("line_spacing"), 1.0, low=0.5, high=3.0),
            **common,
        )
    if kind == "image":
        png = raw.get("png_base64") or ""
        if not isinstance(png, str):
            raise DocumentError("Image data must be a base64 string")
        if png:
            try:
                base64.b64decode(png, validate=True)
            except (ValueError, TypeError) as error:
                raise DocumentError("Image data is not valid base64") from error
        return ImageElement(
            png_base64=png,
            source_name=str(raw.get("source_name", "")),
            fit=_parse_fit(raw.get("fit", "contain")),
            **common,
        )
    raise DocumentError(f"Unknown element type: {kind!r}")
