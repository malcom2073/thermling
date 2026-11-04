"""Application entry point."""

from __future__ import annotations

import signal
import sys
from pathlib import Path

from PySide6.QtCore import QCoreApplication, QSettings
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication

from .theme import detect_theme, install_theme
from .ui.main_window import MainWindow

LEGACY_SETTINGS_ORG = "LabelPrinter"
LEGACY_SETTINGS_APP = "LabelPrinter"


def _migrate_legacy_settings() -> None:
    """Carry preferences over from the app's old working title."""
    current = QSettings()
    if current.allKeys():
        return
    legacy = QSettings(LEGACY_SETTINGS_ORG, LEGACY_SETTINGS_APP)
    keys = legacy.allKeys()
    if not keys:
        return
    for key in keys:
        current.setValue(key, legacy.value(key))
    current.sync()


def main() -> int:
    # Let Ctrl+C terminate the Qt event loop from a terminal.
    signal.signal(signal.SIGINT, signal.SIG_DFL)

    QCoreApplication.setOrganizationName("Labelwright")
    QCoreApplication.setOrganizationDomain("labelwright.local")
    QCoreApplication.setApplicationName("Labelwright")

    app = QApplication(sys.argv)
    app.setApplicationDisplayName("Labelwright")
    _migrate_legacy_settings()
    initial_theme = detect_theme(app)
    app.setStyle("Fusion")
    install_theme(app, initial_theme)

    icon_path = Path(__file__).parent / "assets" / "app.svg"
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))

    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
