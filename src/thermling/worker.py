"""Printer I/O worker and the QThread service that hosts it.

All blocking serial/socket work lives in ``PrinterWorker`` on a dedicated
thread, so the UI never stalls waiting for the printer.  ``PrinterService`` is
the small facade the main window talks to.
"""

from __future__ import annotations

from contextlib import suppress

from PySide6.QtCore import QMetaObject, QObject, Qt, QThread, Signal, Slot

from .protocol import (
    CMD_BATTERY,
    CMD_CONFIG,
    CMD_LABEL,
    CMD_SELFTEST,
    CMD_STATUS,
    MINUTES_TO_TIMER,
    Battery,
    Config,
    LabelRoll,
    ProtocolError,
    Status,
    describe_status,
    parse_battery_reply,
    parse_config_reply,
    parse_label_reply,
    parse_print_reply,
    parse_status_reply,
)
from .transport import (
    BaseTransport,
    TransportError,
    disconnect_bluetooth,
    make_transport,
    normalize_mac,
)


class PrinterWorker(QObject):
    """Blocking printer operations, always executed on the worker thread."""

    connected = Signal(str)
    disconnected = Signal()
    connectionFailed = Signal(str)
    statusUpdated = Signal(int)
    labelUpdated = Signal(object)
    configUpdated = Signal(object)
    batteryUpdated = Signal(object)
    actionFinished = Signal(str)
    error = Signal(str)
    printFinished = Signal(bool, str)

    def __init__(self) -> None:
        super().__init__()
        self._transport: BaseTransport | None = None

    # --- connection ----------------------------------------------------------
    @Slot(dict)
    def connect_to(self, options: dict) -> None:
        self._close_transport()
        try:
            mac = normalize_mac(str(options.get("mac", "")))
            backend = str(options.get("backend", ""))
            device = str(options.get("device", ""))
            take_over = bool(options.get("take_over", False))
            if take_over and backend == "socket":
                disconnect_bluetooth(mac)
            transport = make_transport(backend, mac, device)
            transport.open()
            self._transport = transport
            self.connected.emit(mac)
            self._refresh_all()
        except Exception as error:  # noqa: BLE001 - report every connection failure
            self._close_transport()
            self.connectionFailed.emit(str(error))

    @Slot()
    def disconnect_from(self) -> None:
        self._close_transport()
        self.disconnected.emit()

    @Slot()
    def shutdown(self) -> None:
        self._close_transport()

    def _close_transport(self) -> None:
        if self._transport is not None:
            with suppress(Exception):
                self._transport.close()
            self._transport = None

    # --- queries -------------------------------------------------------------
    @Slot()
    def refresh(self) -> None:
        if self._transport is None:
            self.error.emit("Not connected to the printer")
            return
        self._refresh_all()

    def _refresh_all(self) -> None:
        queries = (
            ("configuration", self._query_config, self.configUpdated),
            ("status", self._query_status, self.statusUpdated),
            ("label roll", self._query_label, self.labelUpdated),
            ("battery", self._query_battery, self.batteryUpdated),
        )
        for name, query, signal in queries:
            try:
                signal.emit(query())
            except (TransportError, ProtocolError) as error:
                self.error.emit(f"Could not read {name}: {error}")

    def _send(self, payload: bytes, reply_len: int | None = None, timeout: float = 3.0) -> bytes:
        if self._transport is None:
            raise TransportError("Not connected to the printer")
        self._transport.reset_input()
        self._transport.write(payload)
        if reply_len is None:
            return self._transport.read_line(timeout)
        if reply_len == 0:
            return b""
        return self._transport.read_exact(reply_len, timeout)

    def _query_status(self) -> int:
        return parse_status_reply(self._send(CMD_STATUS, 1))

    def _query_config(self) -> Config:
        return parse_config_reply(self._send(CMD_CONFIG))

    def _query_battery(self) -> Battery:
        return parse_battery_reply(self._send(CMD_BATTERY))

    def _query_label(self) -> LabelRoll:
        return parse_label_reply(self._send(CMD_LABEL, 16))

    # --- actions -------------------------------------------------------------
    @Slot(bytes)
    def print_job(self, payload: bytes) -> None:
        if self._transport is None:
            self.printFinished.emit(False, "Not connected to the printer")
            return
        try:
            status = self._query_status()
            if status != Status.READY:
                self.printFinished.emit(
                    False, f"Printer not ready: {describe_status(status)}"
                )
                return
            self._transport.reset_input()
            self._transport.write(payload)
            reply = self._transport.read_optional(16, 1.0)
            reported = parse_print_reply(reply)
            if reported is not None and reported != Status.READY:
                self.printFinished.emit(
                    False, f"Printer reported: {describe_status(reported)}"
                )
                return
            self.printFinished.emit(True, "Print job sent")
        except (TransportError, ProtocolError) as error:
            self.printFinished.emit(False, str(error))

    @Slot(bool)
    def set_beep(self, enabled: bool) -> None:
        try:
            self._send(b"BEEP " + bytes([1 if enabled else 0]) + b"\r\n", 0)
            self.configUpdated.emit(self._query_config())
            self.actionFinished.emit(f"Beep {'enabled' if enabled else 'disabled'}")
        except (TransportError, ProtocolError) as error:
            self.error.emit(str(error))

    @Slot(int)
    def set_timer(self, minutes: int) -> None:
        if minutes not in MINUTES_TO_TIMER:
            self.error.emit(f"Unsupported auto-off value: {minutes}")
            return
        try:
            self._send(b"TIMER " + bytes([MINUTES_TO_TIMER[minutes]]) + b"\r\n", 0)
            self.configUpdated.emit(self._query_config())
            self.actionFinished.emit("Auto power-off updated")
        except (TransportError, ProtocolError) as error:
            self.error.emit(str(error))

    @Slot()
    def self_test(self) -> None:
        try:
            self._send(CMD_SELFTEST, 0)
            self.actionFinished.emit("Self-test page sent")
        except (TransportError, ProtocolError) as error:
            self.error.emit(str(error))


class PrinterService(QObject):
    """Facade that owns the worker thread and forwards its signals."""

    connected = Signal(str)
    disconnected = Signal()
    connectionFailed = Signal(str)
    statusUpdated = Signal(int)
    labelUpdated = Signal(object)
    configUpdated = Signal(object)
    batteryUpdated = Signal(object)
    actionFinished = Signal(str)
    error = Signal(str)
    printFinished = Signal(bool, str)

    _connectRequested = Signal(dict)
    _disconnectRequested = Signal()
    _refreshRequested = Signal()
    _printRequested = Signal(bytes)
    _beepRequested = Signal(bool)
    _timerRequested = Signal(int)
    _selfTestRequested = Signal()

    def __init__(self, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._thread = QThread(self)
        self._thread.setObjectName("printer-io")
        self._worker = PrinterWorker()
        self._worker.moveToThread(self._thread)

        self._connectRequested.connect(self._worker.connect_to)
        self._disconnectRequested.connect(self._worker.disconnect_from)
        self._refreshRequested.connect(self._worker.refresh)
        self._printRequested.connect(self._worker.print_job)
        self._beepRequested.connect(self._worker.set_beep)
        self._timerRequested.connect(self._worker.set_timer)
        self._selfTestRequested.connect(self._worker.self_test)

        self._worker.connected.connect(self.connected.emit)
        self._worker.disconnected.connect(self.disconnected.emit)
        self._worker.connectionFailed.connect(self.connectionFailed.emit)
        self._worker.statusUpdated.connect(self.statusUpdated.emit)
        self._worker.labelUpdated.connect(self.labelUpdated.emit)
        self._worker.configUpdated.connect(self.configUpdated.emit)
        self._worker.batteryUpdated.connect(self.batteryUpdated.emit)
        self._worker.actionFinished.connect(self.actionFinished.emit)
        self._worker.error.connect(self.error.emit)
        self._worker.printFinished.connect(self.printFinished.emit)
        self._thread.start()

    def connect_to(
        self, mac: str, backend: str, device: str, take_over: bool
    ) -> None:
        self._connectRequested.emit(
            {
                "mac": mac,
                "backend": backend,
                "device": device,
                "take_over": take_over,
            }
        )

    def disconnect_from(self) -> None:
        self._disconnectRequested.emit()

    def refresh(self) -> None:
        self._refreshRequested.emit()

    def print_job(self, payload: bytes) -> None:
        self._printRequested.emit(payload)

    def set_beep(self, enabled: bool) -> None:
        self._beepRequested.emit(enabled)

    def set_timer(self, minutes: int) -> None:
        self._timerRequested.emit(minutes)

    def self_test(self) -> None:
        self._selfTestRequested.emit()

    def stop(self) -> None:
        if not self._thread.isRunning():
            return
        QMetaObject.invokeMethod(
            self._worker, "shutdown", Qt.ConnectionType.BlockingQueuedConnection
        )
        self._thread.quit()
        self._thread.wait(3000)
