"""Application entry point."""

from __future__ import annotations

import signal
import sys
from pathlib import Path

from PySide6.QtCore import QCoreApplication, QSettings
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication

from .permissions import request_bluetooth_permission
from .runtime import ANDROID
from .theme import detect_theme, install_theme
from .ui.main_window import MainWindow

LEGACY_SETTINGS = (
    ("Labelwright", "Labelwright"),
    ("LabelPrinter", "LabelPrinter"),
)


def _migrate_legacy_settings() -> None:
    """Carry preferences over from the app's earlier names."""
    current = QSettings()
    if current.allKeys():
        return

    existing: set[str] = set()
    migrated = False
    for organization, application in LEGACY_SETTINGS:
        legacy = QSettings(organization, application)
        for key in legacy.allKeys():
            if key in existing:
                continue
            current.setValue(key, legacy.value(key))
            existing.add(key)
            migrated = True
    if migrated:
        current.sync()


def main() -> int:
    # Let Ctrl+C terminate the Qt event loop from a terminal.
    signal.signal(signal.SIGINT, signal.SIG_DFL)

    QCoreApplication.setOrganizationName("Thermling")
    QCoreApplication.setOrganizationDomain("thermling.local")
    QCoreApplication.setApplicationName("Thermling")

    app = QApplication(sys.argv)
    app.setApplicationDisplayName("Thermling")
    _migrate_legacy_settings()
    initial_theme = detect_theme(app)
    app.setStyle("Fusion")
    install_theme(app, initial_theme)

    assets = Path(__file__).parent / "assets"
    icon_path = assets / ("icon.png" if ANDROID else "app.svg")
    if not icon_path.exists():
        icon_path = assets / "app.svg"
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))

    window = MainWindow()
    window.show()
    if ANDROID:
        # Ask once at startup; scanning and connecting request again if needed.
        request_bluetooth_permission(window, lambda _granted: None)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
