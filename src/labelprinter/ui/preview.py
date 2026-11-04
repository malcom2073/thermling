"""Live 1-bit preview and print controls."""

from __future__ import annotations

from PySide6.QtCore import QEvent, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QImage, QPainter, QPen
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSizePolicy,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..protocol import LabelRoll
from ..render import RenderResult
from ..theme import PAGE_WHITE, current_theme


class PreviewWidget(QWidget):
    """Draws the rendered 1-bpp preview with nearest-neighbour pixels."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._image: QImage | None = None
        self._message = "No preview"
        self._is_error = False
        self.setMinimumHeight(180)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)

    def set_result(self, result: RenderResult) -> None:
        self._image = result.preview
        self._message = ""
        self._is_error = False
        self.update()

    def set_error(self, message: str) -> None:
        self._image = None
        self._message = message
        self._is_error = True
        self.update()

    def clear(self) -> None:
        self._image = None
        self._message = "No preview"
        self._is_error = False
        self.update()

    def apply_theme(self) -> None:
        self.update()

    def changeEvent(self, event) -> None:
        if event.type() == QEvent.Type.PaletteChange:
            self.update()
        super().changeEvent(event)

    def paintEvent(self, event) -> None:
        theme = current_theme()
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(theme.preview_bg))
        if self._image is None or self._image.isNull():
            painter.setPen(QColor(theme.danger if self._is_error else theme.muted))
            painter.drawText(
                self.rect().adjusted(16, 16, -16, -16),
                Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap,
                self._message,
            )
            painter.end()
            return

        available = QRectF(self.rect()).adjusted(14, 14, -14, -14)
        image = self._image
        scale = min(available.width() / image.width(), available.height() / image.height())
        scale = max(0.01, min(scale, 10.0))
        width = image.width() * scale
        height = image.height() * scale
        target = QRectF(
            available.center().x() - width / 2.0,
            available.center().y() - height / 2.0,
            width,
            height,
        )
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, scale < 1.0)
        painter.fillRect(target, QColor(PAGE_WHITE))
        painter.drawImage(target, image)
        painter.setPen(QPen(QColor(theme.page_edge), 1))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawRect(target.adjusted(-0.5, -0.5, 0.5, 0.5))
        painter.end()


class PreviewPanel(QWidget):
    printRequested = Signal()
    densityChanged = Signal(int)
    copiesChanged = Signal(int)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._result: RenderResult | None = None
        self._connected = False
        self._busy = False
        self._label: LabelRoll | None = None
        self._document_size: tuple[float, float] | None = None

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)

        self.preview = PreviewWidget(self)
        layout.addWidget(self.preview, 1)

        self.info = QLabel("", self)
        self.info.setWordWrap(True)
        self.info.setProperty("role", "info")
        layout.addWidget(self.info)

        self.warning = QLabel("", self)
        self.warning.setWordWrap(True)
        self.warning.setProperty("role", "warning")
        self.warning.hide()
        layout.addWidget(self.warning)

        controls = QHBoxLayout()
        controls.setSpacing(6)
        density_label = QLabel("Density", self)
        self.density = QSpinBox(self)
        self.density.setRange(1, 15)
        self.density.setValue(15)
        self.density.setToolTip("Print darkness (1 = lightest, 15 = darkest)")
        copies_label = QLabel("Copies", self)
        self.copies = QSpinBox(self)
        self.copies.setRange(1, 99)
        self.copies.setValue(1)
        self.print_button = QPushButton("Print", self)
        self.print_button.setObjectName("primaryButton")
        self.print_button.setMinimumWidth(84)
        controls.addWidget(density_label)
        controls.addWidget(self.density)
        controls.addSpacing(4)
        controls.addWidget(copies_label)
        controls.addWidget(self.copies)
        controls.addStretch(1)
        controls.addWidget(self.print_button)
        layout.addLayout(controls)

        self.density.valueChanged.connect(self.densityChanged.emit)
        self.copies.valueChanged.connect(self.copiesChanged.emit)
        self.print_button.clicked.connect(self.printRequested.emit)
        self._update_enabled()

    def set_values(self, density: int, copies: int) -> None:
        self.density.blockSignals(True)
        self.copies.blockSignals(True)
        self.density.setValue(density)
        self.copies.setValue(copies)
        self.density.blockSignals(False)
        self.copies.blockSignals(False)

    def set_result(self, result: RenderResult) -> None:
        self._result = result
        self.preview.set_result(result)
        self.info.setText(f"{result.size_text} · {result.halftone.label}")
        self._update_warning()
        self._update_enabled()

    def set_error(self, message: str) -> None:
        self._result = None
        self.preview.set_error(message)
        self.info.setText("")
        self.warning.hide()
        self._update_enabled()

    def set_connected(self, connected: bool) -> None:
        self._connected = connected
        self._update_enabled()

    def set_busy(self, busy: bool) -> None:
        self._busy = busy
        self.print_button.setText("Printing…" if busy else "Print")
        self._update_enabled()

    def set_document_size(self, width_mm: float, length_mm: float) -> None:
        self._document_size = (width_mm, length_mm)
        self._update_warning()

    def set_detected_label(self, label: LabelRoll | None) -> None:
        self._label = label
        self._update_warning()

    def _update_warning(self) -> None:
        label = self._label
        if label is None:
            self.warning.hide()
            return
        if not label.has_tag or not label.width_mm or not label.length_mm:
            self.warning.setText("No readable roll tag — using the document's label size.")
            self.warning.show()
            return
        if self._document_size is None:
            self.warning.hide()
            return
        width, length = self._document_size
        if abs(width - label.width_mm) > 0.6 or abs(length - label.length_mm) > 0.6:
            self.warning.setText(
                f"Document is {width:g} × {length:g} mm but the printer roll is "
                f"{label.width_mm:g} × {label.length_mm:g} mm."
            )
            self.warning.show()
        else:
            self.warning.hide()

    def _update_enabled(self) -> None:
        self.print_button.setEnabled(
            self._connected and self._result is not None and not self._busy
        )
