"""Printer connection and status dock panel."""

from __future__ import annotations

import re

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..permissions import bluetooth_permission_status, request_bluetooth_permission
from ..protocol import Battery, Config, LabelRoll, Status, describe_status
from ..runtime import ANDROID
from ..theme import repolish
from ..transport import (
    BACKEND_LABELS,
    BACKEND_SERIAL,
    DEFAULT_BACKEND,
    MAC_PATTERN,
    list_paired_devices,
)

_MAC_IN_TEXT = re.compile(r"([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})")


class PrinterPanel(QWidget):
    connectRequested = Signal(str, str, str)
    disconnectRequested = Signal()
    refreshRequested = Signal()
    selfTestRequested = Signal()
    beepChanged = Signal(bool)
    timerChanged = Signal(int)
    preferencesRequested = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._connected = False
        self._updating = False

        root = QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(10)

        # --- connection ------------------------------------------------------
        connection_group = QGroupBox("Connection", self)
        connection_layout = QVBoxLayout(connection_group)
        connection_layout.setSpacing(6)

        device_form = QFormLayout()
        device_form.setHorizontalSpacing(8)
        device_form.setVerticalSpacing(4)
        device_row = QHBoxLayout()
        self.device_combo = QComboBox(self)
        self.device_combo.setEditable(True)
        self.device_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        self.device_combo.setToolTip(
            "Paired Bluetooth devices. You can also type the printer's MAC address."
        )
        self.scan_button = QPushButton("Scan", self)
        self.scan_button.setFixedWidth(58)
        self.scan_button.setToolTip("Re-read paired devices from BlueZ")
        device_row.addWidget(self.device_combo, 1)
        device_row.addWidget(self.scan_button)
        device_form.addRow("Printer", device_row)

        self.backend_combo = QComboBox(self)
        for key, label in BACKEND_LABELS.items():
            self.backend_combo.addItem(label, key)
        device_form.addRow("Transport", self.backend_combo)

        self.device_path_edit = QLineEdit("/dev/rfcomm0", self)
        self.device_path_edit.setToolTip(
            "RFCOMM tty to open. Only used by the serial transport."
        )
        device_path_label = QLabel("Device", self)
        device_form.addRow(device_path_label, self.device_path_edit)
        if ANDROID:
            device_path_label.hide()
            self.device_path_edit.hide()
        connection_layout.addLayout(device_form)

        self.connect_button = QPushButton("Connect", self)
        self.connect_button.setObjectName("primaryButton")
        connection_layout.addWidget(self.connect_button)

        self.error_label = QLabel("", self)
        self.error_label.setWordWrap(True)
        self.error_label.setProperty("role", "error")
        self.error_label.hide()
        connection_layout.addWidget(self.error_label)

        note = QLabel(
            "On Android, pair the printer in the system Bluetooth settings first, "
            "then tap Scan. The printer accepts one Bluetooth connection at a time."
            if ANDROID
            else (
                "The printer accepts one Bluetooth connection at a time. "
                "“Take over existing link” in Preferences lets the app replace a "
                "Blueman/kbluetooth connection."
            ),
            self,
        )
        note.setWordWrap(True)
        note.setProperty("role", "hint")
        connection_layout.addWidget(note)
        root.addWidget(connection_group)

        # --- status ----------------------------------------------------------
        status_group = QGroupBox("Printer status", self)
        status_layout = QVBoxLayout(status_group)
        status_layout.setSpacing(6)

        state_row = QHBoxLayout()
        self.state_dot = QLabel("●", self)
        self.state_dot.setObjectName("stateDot")
        self.state_dot.setProperty("state", "disconnected")
        self.state_text = QLabel("Disconnected", self)
        self.state_text.setProperty("role", "heading")
        state_row.addWidget(self.state_dot)
        state_row.addWidget(self.state_text, 1)
        status_layout.addLayout(state_row)

        status_form = QFormLayout()
        status_form.setHorizontalSpacing(8)
        status_form.setVerticalSpacing(4)
        self.roll_value = QLabel("—", self)
        self.battery_value = QLabel("—", self)
        self.config_value = QLabel("—", self)
        for label in (self.roll_value, self.battery_value, self.config_value):
            label.setWordWrap(True)
            label.setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse
            )
        status_form.addRow("Label roll", self.roll_value)
        status_form.addRow("Battery", self.battery_value)
        status_form.addRow("Firmware", self.config_value)
        status_layout.addLayout(status_form)

        status_buttons = QHBoxLayout()
        self.refresh_button = QPushButton("Refresh", self)
        self.self_test_button = QPushButton("Self-test", self)
        self.self_test_button.setToolTip("Print the printer's built-in test page")
        status_buttons.addWidget(self.refresh_button)
        status_buttons.addWidget(self.self_test_button)
        status_buttons.addStretch(1)
        status_layout.addLayout(status_buttons)
        root.addWidget(status_group)

        # --- device settings -------------------------------------------------
        self.settings_group = QGroupBox("Printer settings", self)
        settings_layout = QFormLayout(self.settings_group)
        settings_layout.setHorizontalSpacing(8)
        settings_layout.setVerticalSpacing(4)
        self.beep_check = QCheckBox("Beep on button press", self)
        self.timer_combo = QComboBox(self)
        for label, minutes in (
            ("Never", 0),
            ("15 minutes", 15),
            ("30 minutes", 30),
            ("60 minutes", 60),
        ):
            self.timer_combo.addItem(label, minutes)
        settings_layout.addRow("Beep", self.beep_check)
        settings_layout.addRow("Auto power-off", self.timer_combo)
        root.addWidget(self.settings_group)

        preferences_button = QPushButton("Preferences…", self)
        root.addWidget(preferences_button)
        root.addStretch(1)

        # --- wiring ----------------------------------------------------------
        self.connect_button.clicked.connect(self._on_connect_clicked)
        self.scan_button.clicked.connect(self._scan_devices)
        self.refresh_button.clicked.connect(self.refreshRequested.emit)
        self.self_test_button.clicked.connect(self.selfTestRequested.emit)
        self.preferences_button = preferences_button
        preferences_button.clicked.connect(self.preferencesRequested.emit)
        self.backend_combo.currentIndexChanged.connect(self._update_device_visibility)
        self.beep_check.toggled.connect(self._on_beep_changed)
        self.timer_combo.currentIndexChanged.connect(self._on_timer_changed)

        self._populate_devices()
        self.set_connected(False)
        self._update_device_visibility()

    # --- public API -----------------------------------------------------------
    def set_settings(self, mac: str, backend: str, device: str) -> None:
        self.backend_combo.blockSignals(True)
        index = self.backend_combo.findData(backend)
        self.backend_combo.setCurrentIndex(index if index >= 0 else 0)
        self.backend_combo.blockSignals(False)
        self.device_path_edit.setText(device)
        self._populate_devices(preferred_mac=mac)
        self._update_device_visibility()

    def current_mac(self) -> str:
        data = self.device_combo.currentData()
        if isinstance(data, str) and data:
            return data.upper()
        text = self.device_combo.currentText().strip()
        match = _MAC_IN_TEXT.search(text)
        if match:
            return match.group(1).upper()
        return text.upper()

    def backend(self) -> str:
        value = self.backend_combo.currentData()
        return str(value) if value else DEFAULT_BACKEND

    def device(self) -> str:
        return self.device_path_edit.text().strip() or "/dev/rfcomm0"

    def set_connecting(self) -> None:
        self.connect_button.setEnabled(False)
        self.connect_button.setText("Connecting…")
        self.state_text.setText("Connecting…")
        self._set_dot_state("connecting")
        self.error_label.hide()

    def set_connected(self, connected: bool) -> None:
        self._connected = connected
        self.connect_button.setEnabled(True)
        self.connect_button.setText("Disconnect" if connected else "Connect")
        for widget in (
            self.device_combo,
            self.scan_button,
            self.backend_combo,
            self.device_path_edit,
        ):
            widget.setEnabled(not connected)
        for widget in (
            self.refresh_button,
            self.self_test_button,
            self.settings_group,
        ):
            widget.setEnabled(connected)
        if not connected:
            self._set_dot_state("disconnected")
            self.state_text.setText("Disconnected")
            self.roll_value.setText("—")
            self.battery_value.setText("—")
            self.config_value.setText("—")
        else:
            self._set_dot_state("connected")
            self.state_text.setText("Connected")
            self.error_label.hide()

    def set_connection_failed(self, message: str) -> None:
        self._connected = False
        self.connect_button.setEnabled(True)
        self.connect_button.setText("Connect")
        for widget in (
            self.device_combo,
            self.scan_button,
            self.backend_combo,
            self.device_path_edit,
        ):
            widget.setEnabled(True)
        self.settings_group.setEnabled(False)
        self._set_dot_state("error")
        self.state_text.setText("Connection failed")
        self.error_label.setText(message)
        self.error_label.show()

    def show_error(self, message: str) -> None:
        self.error_label.setText(message)
        self.error_label.show()

    def set_status(self, code: int) -> None:
        if not self._connected:
            return
        self.state_text.setText(describe_status(code))
        self._set_dot_state("ready" if code == Status.READY else "warning")

    def set_label(self, label: LabelRoll) -> None:
        if label.has_tag:
            self.roll_value.setText(
                f"{label.width_mm:g} × {label.length_mm:g} mm\n"
                f"{label.style} · {label.color} · gap {label.gap_mm:g} mm\n"
                f"{label.paper_code}"
            )
        else:
            self.roll_value.setText("No readable label tag")

    def set_config(self, config: Config) -> None:
        self.config_value.setText(
            f"{config.dpi} dpi · hardware {config.hardware_version} · "
            f"firmware {config.firmware_version}"
        )
        self._updating = True
        try:
            self.beep_check.setChecked(config.beep)
            index = self.timer_combo.findData(
                {0: 0, 1: 15, 2: 30, 3: 60}.get(config.timer, 0)
            )
            self.timer_combo.setCurrentIndex(max(0, index))
        finally:
            self._updating = False

    def set_battery(self, battery: Battery) -> None:
        self.battery_value.setText(str(battery))
        if battery.charging:
            self.battery_value.setToolTip(
                "The printer reports 99% while charging; unplug it for a real reading."
            )
        else:
            self.battery_value.setToolTip("")

    # --- internals -----------------------------------------------------------
    def _set_dot_state(self, state: str) -> None:
        if self.state_dot.property("state") == state:
            return
        self.state_dot.setProperty("state", state)
        repolish(self.state_dot)

    def _scan_devices(self, checked: bool = False) -> None:
        del checked  # QPushButton.clicked passes a bool
        if (
            ANDROID
            and bluetooth_permission_status() != Qt.PermissionStatus.Granted
        ):
            self.error_label.hide()
            self.scan_button.setEnabled(False)
            request_bluetooth_permission(self, self._scan_after_permission)
            return
        self._run_scan()

    def _scan_after_permission(self, granted: bool) -> None:
        if not granted:
            self.scan_button.setEnabled(not self._connected)
            self.error_label.setText(
                "Bluetooth permission was denied. Grant “Nearby devices” access "
                "to Thermling in Android settings and try again."
            )
            self.error_label.show()
            return
        self._run_scan()

    def _run_scan(self) -> None:
        self.scan_button.setEnabled(False)
        self.scan_button.setText("…")
        try:
            self._populate_devices(scan=True)
            if self.device_combo.count() == 0:
                self.error_label.setText(
                    "No Bluetooth devices found. Pair the printer in Android's "
                    "Bluetooth settings and make sure Bluetooth is turned on."
                    if ANDROID
                    else "No paired Bluetooth devices found."
                )
                self.error_label.show()
            else:
                self.error_label.hide()
        finally:
            self.scan_button.setEnabled(not self._connected)
            self.scan_button.setText("Scan")

    def _populate_devices(
        self,
        checked: bool = False,
        preferred_mac: str | None = None,
        scan: bool | None = None,
    ) -> None:
        del checked  # QPushButton.clicked passes a bool
        if scan is None:
            scan = not ANDROID
        preferred = preferred_mac or self.current_mac()
        devices = list_paired_devices() if scan else []
        self.device_combo.blockSignals(True)
        try:
            self.device_combo.clear()
            for device in sorted(devices, key=lambda item: (item.name == "", item.name)):
                self.device_combo.addItem(device.display, device.address)
            addresses = {device.address for device in devices}
            if preferred and preferred not in addresses:
                self.device_combo.addItem(preferred, preferred)
            index = self.device_combo.findData(preferred) if preferred else -1
            if index < 0:
                index = self._preferred_index(devices)
            if index >= 0:
                self.device_combo.setCurrentIndex(index)
            elif preferred:
                self.device_combo.setEditText(preferred)
        finally:
            self.device_combo.blockSignals(False)

    @staticmethod
    def _preferred_index(devices: list) -> int:
        for index, device in enumerate(devices):
            name = device.name.upper()
            if "P21" in name or "P22" in name:
                return index
        return 0 if devices else -1

    def _on_connect_clicked(self) -> None:
        if self._connected:
            self.disconnectRequested.emit()
            return
        mac = self.current_mac()
        if not MAC_PATTERN.match(mac):
            self.error_label.setText(
                "Choose a paired device or enter a Bluetooth address like "
                "E4:53:A2:5F:F1:E7."
            )
            self.error_label.show()
            return
        self.error_label.hide()
        self.connectRequested.emit(mac, self.backend(), self.device())
        self.set_connecting()

    def _update_device_visibility(self) -> None:
        serial = self.backend_combo.currentData() == BACKEND_SERIAL
        self.device_path_edit.setVisible(serial)

    def _on_beep_changed(self, enabled: bool) -> None:
        if not self._updating:
            self.beepChanged.emit(enabled)

    def _on_timer_changed(self, index: int) -> None:
        if self._updating:
            return
        minutes = self.timer_combo.itemData(index)
        if isinstance(minutes, int):
            self.timerChanged.emit(minutes)
