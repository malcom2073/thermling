"""Application entry point."""

from __future__ import annotations

import signal
import sys
from pathlib import Path

from PySide6.QtCore import QCoreApplication
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication

from .theme import detect_theme, install_theme
from .ui.main_window import MainWindow


def main() -> int:
    # Let Ctrl+C terminate the Qt event loop from a terminal.
    signal.signal(signal.SIGINT, signal.SIG_DFL)

    QCoreApplication.setOrganizationName("LabelPrinter")
    QCoreApplication.setOrganizationDomain("labelprinter.local")
    QCoreApplication.setApplicationName("LabelPrinter")

    app = QApplication(sys.argv)
    app.setApplicationDisplayName("Label Printer")
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
