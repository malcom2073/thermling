from __future__ import annotations

from labelprinter.protocol import DEFAULT_DEVICE
from labelprinter.settings import AppSettings
from labelprinter.transport import BACKEND_SERIAL, BACKEND_SOCKET


def test_settings_round_trip(qapp):
    settings = AppSettings(
        mac="e4:53:a2:5f:f1:e7",
        backend=BACKEND_SERIAL,
        device="/dev/ttyUSB0",
        take_over=False,
        auto_connect=True,
        auto_dpi=False,
        dpi_override=300,
        head_width_mm=11.5,
        density=8,
        copies=3,
        last_dir="/tmp",
    )
    settings.save()

    loaded = AppSettings.load()
    assert loaded == settings
    assert loaded.mac == "E4:53:A2:5F:F1:E7"


def test_settings_normalize_clamps_and_fixes_invalid_values():
    settings = AppSettings(
        mac="  e4:53:a2:5f:f1:e7 ",
        backend="nonsense",
        device="   ",
        dpi_override=5000,
        head_width_mm=0.1,
        density=99,
        copies=0,
    )
    settings.normalize()
    assert settings.mac == "E4:53:A2:5F:F1:E7"
    assert settings.backend == BACKEND_SOCKET
    assert settings.device == DEFAULT_DEVICE
    assert settings.dpi_override == 1200
    assert settings.head_width_mm == 5.0
    assert settings.density == 15
    assert settings.copies == 1


def test_settings_defaults_when_missing(qapp):
    loaded = AppSettings.load()
    assert loaded.backend == BACKEND_SOCKET
    assert loaded.auto_dpi is True
    assert loaded.head_width_mm == 12.0
    assert loaded.density == 15
    assert loaded.copies == 1


def test_settings_null_device_falls_back(qapp):
    settings = AppSettings()
    settings.device = "   "
    settings.save()
    assert AppSettings.load().device == DEFAULT_DEVICE
