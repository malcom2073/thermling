"""Preferences dialog."""

from __future__ import annotations

import copy

from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFormLayout,
    QGroupBox,
    QLineEdit,
    QMessageBox,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..protocol import DEFAULT_DPI
from ..settings import AppSettings
from ..transport import BACKEND_LABELS, BACKEND_SERIAL, MAC_PATTERN


class PreferencesDialog(QDialog):
    def __init__(self, settings: AppSettings, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Preferences")
        self.setMinimumWidth(480)
        self.settings = copy.deepcopy(settings)

        layout = QVBoxLayout(self)
        layout.setSpacing(10)

        connection_group = QGroupBox("Connection", self)
        connection_form = QFormLayout(connection_group)
        connection_form.setHorizontalSpacing(10)
        connection_form.setVerticalSpacing(6)

        self.mac_edit = QLineEdit(settings.mac, self)
        self.mac_edit.setPlaceholderText("E4:53:A2:5F:F1:E7")
        connection_form.addRow("Printer address", self.mac_edit)

        self.backend_combo = QComboBox(self)
        for key, label in BACKEND_LABELS.items():
            self.backend_combo.addItem(label, key)
        index = self.backend_combo.findData(settings.backend)
        self.backend_combo.setCurrentIndex(index if index >= 0 else 0)
        connection_form.addRow("Transport", self.backend_combo)

        self.device_edit = QLineEdit(settings.device, self)
        connection_form.addRow("Serial device", self.device_edit)

        self.take_over_check = QCheckBox(
            "Disconnect an existing Bluetooth link before connecting", self
        )
        self.take_over_check.setChecked(settings.take_over)
        connection_form.addRow("", self.take_over_check)

        self.auto_connect_check = QCheckBox("Connect automatically on startup", self)
        self.auto_connect_check.setChecked(settings.auto_connect)
        connection_form.addRow("", self.auto_connect_check)
        layout.addWidget(connection_group)

        render_group = QGroupBox("Rendering", self)
        render_form = QFormLayout(render_group)
        render_form.setHorizontalSpacing(10)
        render_form.setVerticalSpacing(6)

        self.auto_dpi_check = QCheckBox("Use the DPI reported by the printer", self)
        self.auto_dpi_check.setChecked(settings.auto_dpi)
        render_form.addRow("", self.auto_dpi_check)

        self.dpi_spin = QSpinBox(self)
        self.dpi_spin.setRange(50, 1200)
        self.dpi_spin.setValue(settings.dpi_override or DEFAULT_DPI)
        self.dpi_spin.setSuffix(" dpi")
        render_form.addRow("DPI override", self.dpi_spin)

        self.head_width_spin = QDoubleSpinBox(self)
        self.head_width_spin.setRange(5.0, 50.0)
        self.head_width_spin.setDecimals(1)
        self.head_width_spin.setSingleStep(0.5)
        self.head_width_spin.setSuffix(" mm")
        self.head_width_spin.setValue(settings.head_width_mm)
        self.head_width_spin.setToolTip(
            "Width of the printer's thermal head. The P21/P22 head is 12 mm (96 dots)."
        )
        render_form.addRow("Print head width", self.head_width_spin)
        layout.addWidget(render_group)

        note = QWidget(self)
        layout.addWidget(note)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save
            | QDialogButtonBox.StandardButton.Cancel,
            self,
        )
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self.backend_combo.currentIndexChanged.connect(self._update_device_visibility)
        self.auto_dpi_check.toggled.connect(self._update_dpi_enabled)
        self._update_device_visibility()
        self._update_dpi_enabled()

    def _update_device_visibility(self) -> None:
        self.device_edit.setEnabled(self.backend_combo.currentData() == BACKEND_SERIAL)

    def _update_dpi_enabled(self) -> None:
        self.dpi_spin.setEnabled(not self.auto_dpi_check.isChecked())

    def _accept(self) -> None:
        mac = self.mac_edit.text().strip().upper()
        if mac and not MAC_PATTERN.match(mac):
            QMessageBox.warning(
                self,
                "Invalid address",
                "The printer address should look like E4:53:A2:5F:F1:E7.",
            )
            return
        self.settings.mac = mac
        self.settings.backend = str(self.backend_combo.currentData())
        self.settings.device = self.device_edit.text().strip() or "/dev/rfcomm0"
        self.settings.take_over = self.take_over_check.isChecked()
        self.settings.auto_connect = self.auto_connect_check.isChecked()
        self.settings.auto_dpi = self.auto_dpi_check.isChecked()
        self.settings.dpi_override = self.dpi_spin.value()
        self.settings.head_width_mm = self.head_width_spin.value()
        self.settings.normalize()
        self.accept()
