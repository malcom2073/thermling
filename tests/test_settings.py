from __future__ import annotations

from thermling import app as app_module
from thermling.protocol import DEFAULT_DEVICE
from thermling.settings import AppSettings
from thermling.transport import BACKEND_SERIAL, BACKEND_SOCKET


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


def _install_fake_settings(
    monkeypatch,
    current: dict[str, object],
    legacy_stores: dict[str, dict[str, object]],
) -> None:
    class FakeSettings:
        def __init__(self, organization: str | None = None, application: str = "") -> None:
            self.store = current if organization is None else legacy_stores[organization]

        def allKeys(self):
            return list(self.store)

        def value(self, key: str):
            return self.store.get(key)

        def setValue(self, key: str, value: object) -> None:
            self.store[key] = value

        def sync(self) -> None:
            pass

    monkeypatch.setattr(app_module, "QSettings", FakeSettings)


def test_legacy_settings_are_migrated(monkeypatch):
    current: dict[str, object] = {}
    legacy_stores = {
        "Labelwright": {
            "printer/mac": "E4:53:A2:5F:F1:E7",
            "window/geometry": b"geometry",
        },
        "LabelPrinter": {
            "printer/mac": "AA:BB:CC:DD:EE:FF",
            "printer/backend": BACKEND_SOCKET,
        },
    }
    _install_fake_settings(monkeypatch, current, legacy_stores)

    app_module._migrate_legacy_settings()

    assert current == {
        "printer/mac": "E4:53:A2:5F:F1:E7",
        "window/geometry": b"geometry",
        "printer/backend": BACKEND_SOCKET,
    }


def test_legacy_migration_does_not_overwrite_existing_settings(monkeypatch):
    current: dict[str, object] = {"printer/mac": "AA:BB:CC:DD:EE:FF"}
    legacy_stores = {
        "Labelwright": {"printer/mac": "E4:53:A2:5F:F1:E7"},
        "LabelPrinter": {"printer/backend": BACKEND_SOCKET},
    }
    _install_fake_settings(monkeypatch, current, legacy_stores)

    app_module._migrate_legacy_settings()

    assert current == {"printer/mac": "AA:BB:CC:DD:EE:FF"}
