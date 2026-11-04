"""Persistent application preferences (QSettings-backed)."""

from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import QSettings

from .protocol import DEFAULT_DEVICE, DEFAULT_DPI, DEFAULT_HEAD_WIDTH_MM
from .transport import BACKEND_LABELS, BACKEND_SOCKET


def _as_bool(value: object, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    if value is None:
        return default
    return bool(value)


def _as_int(value: object, default: int) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default


def _as_float(value: object, default: float) -> float:
    try:
        return float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default


def _clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


@dataclass
class AppSettings:
    mac: str = ""
    backend: str = BACKEND_SOCKET
    device: str = DEFAULT_DEVICE
    take_over: bool = True
    auto_connect: bool = False
    auto_dpi: bool = True
    dpi_override: int = DEFAULT_DPI
    head_width_mm: float = DEFAULT_HEAD_WIDTH_MM
    density: int = 15
    copies: int = 1
    last_dir: str = ""

    def normalize(self) -> None:
        if self.backend not in BACKEND_LABELS:
            self.backend = BACKEND_SOCKET
        self.dpi_override = int(_clamp(self.dpi_override, 50, 1200))
        self.head_width_mm = float(_clamp(self.head_width_mm, 5.0, 50.0))
        self.density = int(_clamp(self.density, 1, 15))
        self.copies = int(_clamp(self.copies, 1, 99))
        self.mac = self.mac.strip().upper()
        self.device = self.device.strip() or DEFAULT_DEVICE

    @classmethod
    def load(cls) -> AppSettings:
        settings = QSettings()
        result = cls(
            mac=str(settings.value("printer/mac", "")),
            backend=str(settings.value("printer/backend", BACKEND_SOCKET)),
            device=str(settings.value("printer/device", DEFAULT_DEVICE)),
            take_over=_as_bool(settings.value("printer/take_over", True), True),
            auto_connect=_as_bool(settings.value("printer/auto_connect", False), False),
            auto_dpi=_as_bool(settings.value("render/auto_dpi", True), True),
            dpi_override=_as_int(
                settings.value("render/dpi_override", DEFAULT_DPI), DEFAULT_DPI
            ),
            head_width_mm=_as_float(
                settings.value("render/head_width_mm", DEFAULT_HEAD_WIDTH_MM),
                DEFAULT_HEAD_WIDTH_MM,
            ),
            density=_as_int(settings.value("print/density", 15), 15),
            copies=_as_int(settings.value("print/copies", 1), 1),
            last_dir=str(settings.value("files/last_dir", "")),
        )
        result.normalize()
        return result

    def save(self) -> None:
        self.normalize()
        settings = QSettings()
        settings.setValue("printer/mac", self.mac)
        settings.setValue("printer/backend", self.backend)
        settings.setValue("printer/device", self.device)
        settings.setValue("printer/take_over", self.take_over)
        settings.setValue("printer/auto_connect", self.auto_connect)
        settings.setValue("render/auto_dpi", self.auto_dpi)
        settings.setValue("render/dpi_override", self.dpi_override)
        settings.setValue("render/head_width_mm", self.head_width_mm)
        settings.setValue("print/density", self.density)
        settings.setValue("print/copies", self.copies)
        settings.setValue("files/last_dir", self.last_dir)
        settings.sync()
